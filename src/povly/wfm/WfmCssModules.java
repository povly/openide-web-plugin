package povly.wfm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure CSS-modules logic for Vue SFCs: {@code <style module>} blocks, external
 * {@code <style module src="...">} tags, {@code import x from './a.module.css'} statements,
 * CSS class extraction from style sheets and completion-trigger detection before the caret.
 * <p>
 * Deliberately free of any {@code com.intellij} dependency: the completion contributor and
 * the goto handler written against this API stay thin platform shells, while this class is
 * verifiable with plain {@code javac} (the {@code ./build.sh test} target runs
 * {@code tests/povly/wfm/WfmCssModulesTest.java} without the IDE classpath). All offsets are
 * indexes into the exact string passed in — callers translate them to document offsets.
 * <p>
 * Lexing choices (comments neutralized in-place, selector regions split at structural
 * characters, strings tracked while block-scanning) mirror how CSS/SCSS/LESS actually nests:
 * a pending segment closed by an opening brace is a selector region at any nesting depth
 * (SCSS/LESS nested rules), while segments closed by a semicolon or a closing brace are
 * declaration bodies and must never contribute classes ({@code transition: color .5s} is
 * not a selector).
 */
public final class WfmCssModules {
    private WfmCssModules() {}

    /** Inline {@code <style module>} block: moduleName "style" for the bare attribute, "x" for {@code module="x"}. */
    public record StyleModule(String moduleName, String css, int cssStartOffset) {}

    /** External module stylesheet referenced as {@code <style module src="./x.module.css">}; path exactly as written. */
    public record ExternalModule(String moduleName, String path) {}

    /** Script-side {@code import styles from './x.module.css'}; path must end in .module.css/.scss/.less. */
    public record ModuleImport(String identifier, String path) {}

    /** A CSS class: name as written, camelized alias, and the offset of the starting '.' in the css text. */
    public record CssClass(String name, String camel, int offset) {}

    /** Completion trigger right before the caret: module name, member-name start index, bracket-access flag. */
    public record Trigger(String module, int memberStart, boolean bracket) {}

    /** {@code <style ...>} opening tag; group-free — attributes are parsed from the matched tag text. */
    private static final Pattern STYLE_OPEN = Pattern.compile("<style\\b[^>]*>");
    /** {@code module="x"} attribute inside a tag; group 1 = module name exactly as written. */
    private static final Pattern MODULE_ATTR = Pattern.compile("\\bmodule\\s*=\\s*\"([^\"]*)\"");
    /** Bare {@code module} attribute (no value ⇒ default name "style"); whitespace before, whitespace-or-'&gt;' after, so {@code data-module} / {@code module=} never match. */
    private static final Pattern BARE_MODULE = Pattern.compile("(?<=\\s)module(?=[\\s>])");
    /** {@code src="..."} attribute inside a tag; group 1 = path exactly as written. */
    private static final Pattern SRC_ATTR = Pattern.compile("\\bsrc\\s*=\\s*\"([^\"]*)\"");
    /** ES module import of a CSS module; group 1 = local identifier, group 2 = path ending in .module.css/.scss/.less. */
    private static final Pattern MODULE_IMPORT =
            Pattern.compile("\\bimport\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+['\"]([^'\"]+\\.module\\.(?:css|scss|less))['\"]");
    /** CSS class inside a selector region; group 1 = class name without the leading dot. */
    private static final Pattern CLASS_IN_SELECTOR = Pattern.compile("\\.(-?[_A-Za-z][\\w-]*)");
    /** {@code :global(...)} wrapper inside a selector — its classes belong to no module; group-free, replaced by spaces in place. */
    private static final Pattern GLOBAL_WRAPPER = Pattern.compile(":global\\([^)]*\\)");

    /**
     * Inline {@code <style module>} / {@code <style module="x">} blocks of an SFC, in document
     * order. Tags without the module attribute are ignored; {@code css} is the text between the
     * opening tag and the first {@code </style>} after it, and {@code cssStartOffset} is the
     * document offset of the first char after the opening tag's {@code >'}.
     */
    public static List<StyleModule> inlineModules(String sfcText) {
        List<StyleModule> result = new ArrayList<>();
        for (ParsedStyleTag tag : moduleStyleTags(sfcText)) {
            if (tag.src() == null) result.add(new StyleModule(tag.moduleName(), tag.css(), tag.cssStartOffset()));
        }
        return result;
    }

    /**
     * External module stylesheets ({@code <style module src="./x.module.css">}) of an SFC, in
     * document order. The path is kept exactly as written; resolution against the containing
     * file is the caller's job.
     */
    public static List<ExternalModule> externalModules(String sfcText) {
        List<ExternalModule> result = new ArrayList<>();
        for (ParsedStyleTag tag : moduleStyleTags(sfcText)) {
            if (tag.src() != null) result.add(new ExternalModule(tag.moduleName(), tag.src()));
        }
        return result;
    }

    /**
     * {@code import ident from '....module.css|.module.scss|.module.less'} statements anywhere in
     * the SFC text (the {@code <script>} block in practice), deduplicated by identifier — the
     * first import wins, insertion order preserved.
     */
    public static List<ModuleImport> moduleImports(String sfcText) {
        List<ModuleImport> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = MODULE_IMPORT.matcher(sfcText);
        while (m.find()) {
            if (seen.add(m.group(1))) result.add(new ModuleImport(m.group(1), m.group(2)));
        }
        return result;
    }

    /**
     * CSS classes from SELECTOR regions only, deduplicated by name (first occurrence wins,
     * offsets point at that first occurrence's dot).
     * <p>
     * Pass: (1) comments {@code /* ... *&#47;} are neutralized with same-length spaces so every
     * offset below is valid for the original text; (2) a single char scan tracks string state
     * (' and ") and splits the text into pending segments at structural characters — a segment
     * closed by an opening brace is a selector region at any nesting depth (SCSS/LESS),
     * segments closed by a semicolon or a closing brace are declarations and never contribute;
     * (3) per region: skip empty,
     * at-rule ({@code @media}, {@code @import}...) and bare-{:global} regions, neutralize
     * {@code :global(...)} in place, then collect {@code .name} matches skipping LESS mixin
     * calls ({@code .name(} immediately followed by {@code (}).
     */
    public static List<CssClass> extractClasses(String cssText) {
        List<CssClass> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String neutral = neutralizeComments(cssText);

        int segStart = 0;   // absolute index where the current pending segment began
        char inString = 0;  // 0 = outside a string, otherwise the opening quote char
        for (int i = 0; i < neutral.length(); i++) {
            char c = neutral.charAt(i);
            if (inString != 0) {
                if (c == inString) inString = 0;
            } else if (c == '\'' || c == '"') {
                inString = c;
            } else if (c == '{') {
                collectSelectorClasses(neutral, segStart, i, seen, result);
                segStart = i + 1;
            } else if (c == ';' || c == '}') {
                segStart = i + 1;
            }
        }
        return result;
    }

    /**
     * Camelize a CSS class name for JS member access: {@code foo-bar → fooBar}. Segments after
     * each '-' get their first letter uppercased (digits pass through), empty segments collapse
     * ({@code foo--bar → fooBar}, {@code foo- → foo}, {@code -x → X}). Names without '-' are
     * returned unchanged.
     */
    public static String camel(String name) {
        if (!name.contains("-")) return name;
        String[] segments = name.split("-", -1); // -1 keeps trailing empty segments ("foo-")
        StringBuilder sb = new StringBuilder(name.length());
        sb.append(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) continue;
            char first = segment.charAt(0);
            if (Character.isLetter(first)) {
                sb.append(Character.toUpperCase(first)).append(segment, 1, segment.length());
            } else {
                sb.append(segment);
            }
        }
        return sb.toString();
    }

    /**
     * Detects a CSS-modules completion trigger immediately before the caret. Input is the text
     * from the document (or line) start to the caret — only relative indexes matter.
     * <p>
     * Cases: {@code $style.} / {@code $x.member} / {@code importIdent.} (module = the identifier
     * without '$' for style-injected refs) and {@code $style['...'} (bracket access). Member
     * chains like {@code obj.prop.} are rejected via the char preceding the identifier not being
     * {@code [\w$.]}. Plain identifiers are returned unfiltered — the caller matches them
     * against known imports. Returns null when the caret is not after a trigger.
     */
    public static Trigger triggerAt(String textBeforeCaret) {
        int i = textBeforeCaret.length();
        while (i > 0 && isMemberChar(textBeforeCaret.charAt(i - 1))) i--;
        int memberStart = i;
        if (i == 0) return null;
        char c = textBeforeCaret.charAt(i - 1);

        if (c == '.') {
            int j = identStart(textBeforeCaret, i - 1);
            String ident = textBeforeCaret.substring(j, i - 1);
            if (ident.isEmpty()) return null;
            if (ident.charAt(0) == '$') {
                if (isChainedRef(textBeforeCaret, j)) return null;
                return new Trigger(ident.substring(1), memberStart, false);
            }
            if (isChainedRef(textBeforeCaret, j)) return null;
            return new Trigger(ident, memberStart, false);
        }
        if (c == '\'') {
            if (i < 2 || textBeforeCaret.charAt(i - 2) != '[') return null;
            int j = identStart(textBeforeCaret, i - 2);
            String ident = textBeforeCaret.substring(j, i - 2);
            if (ident.isEmpty() || ident.charAt(0) != '$') return null;
            if (isChainedRef(textBeforeCaret, j)) return null;
            return new Trigger(ident.substring(1), memberStart, true);
        }
        return null;
    }

    /**
     * A {@code <style>} tag carrying the module attribute; {@code src == null} marks an inline
     * block, otherwise the tag is external and only the src path matters.
     */
    private record ParsedStyleTag(String moduleName, String src, String css, int cssStartOffset) {}

    /** All module-carrying {@code <style>} tags of an SFC; tags without the module attribute are skipped. */
    private static List<ParsedStyleTag> moduleStyleTags(String sfcText) {
        List<ParsedStyleTag> tags = new ArrayList<>();
        Matcher open = STYLE_OPEN.matcher(sfcText);
        while (open.find()) {
            String moduleName = moduleNameOf(open.group());
            if (moduleName == null) continue; // plain <style>: not a CSS module
            int cssStart = open.end(); // first char after the tag's '>'
            int close = sfcText.indexOf("</style>", open.end());
            String inner = close >= 0
                    ? sfcText.substring(cssStart, close)
                    : sfcText.substring(cssStart); // unclosed block: take the rest
            tags.add(new ParsedStyleTag(moduleName, srcOf(open.group()), inner, cssStart));
        }
        return tags;
    }

    /**
     * Module name from a tag's text: {@code module="x"} ⇒ "x"; bare {@code module} ⇒ "style"
     * (the default inject-as name); no module attribute ⇒ null.
     */
    private static String moduleNameOf(String tagText) {
        Matcher quoted = MODULE_ATTR.matcher(tagText);
        if (quoted.find()) return quoted.group(1);
        if (BARE_MODULE.matcher(tagText).find()) return "style";
        return null;
    }

    /** src attribute value of a tag exactly as written, or null when the tag has no src. */
    private static String srcOf(String tagText) {
        Matcher src = SRC_ATTR.matcher(tagText);
        return src.find() ? src.group(1) : null;
    }

    /**
     * Collects classes from one selector region [{@code regionStart}, {@code regionEnd}) of the
     * comment-neutralized text. {@code start} is the region's first non-whitespace char so that
     * class offsets stay absolute (a trim() would shift them).
     */
    private static void collectSelectorClasses(String neutral, int regionStart, int regionEnd,
                                               Set<String> seen, List<CssClass> result) {
        int start = regionStart;
        while (start < regionEnd && Character.isWhitespace(neutral.charAt(start))) start++;
        int end = regionEnd;
        while (end > start && Character.isWhitespace(neutral.charAt(end - 1))) end--;
        if (start >= end) return;

        String region = neutral.substring(start, end);
        if (region.startsWith("@") || region.startsWith(":global")) return; // at-rule / bare :global
        String scanned = GLOBAL_WRAPPER.matcher(region)
                .replaceAll(m -> " ".repeat(m.group().length())); // keep offsets stable
        Matcher m = CLASS_IN_SELECTOR.matcher(scanned);
        while (m.find()) {
            if (m.end() < scanned.length() && scanned.charAt(m.end()) == '(') continue; // LESS mixin
            String name = m.group(1);
            if (seen.add(name)) result.add(new CssClass(name, camel(name), start + m.start()));
        }
    }

    /**
     * Copies the css with every {@code /* ... *&#47;} comment replaced by spaces — length and
     * therefore all downstream offsets are preserved. An unclosed comment neutralizes the rest
     * of the text.
     */
    private static String neutralizeComments(String css) {
        int open = css.indexOf("/*");
        if (open < 0) return css;
        StringBuilder out = new StringBuilder(css);
        while (open >= 0) {
            int close = css.indexOf("*/", open + 2);
            int end = close >= 0 ? close + 2 : css.length();
            for (int i = open; i < end; i++) out.setCharAt(i, ' ');
            open = close >= 0 ? css.indexOf("/*", end) : -1;
        }
        return out.toString();
    }

    /** Start index of the identifier ending at {@code end} (exclusive): scans [A-Za-z0-9_$]+ left. */
    private static int identStart(String s, int end) {
        int j = end;
        while (j > 0 && isIdentChar(s.charAt(j - 1))) j--;
        return j;
    }

    /**
     * True when the char before {@code j} is {@code [\w$.]}: the scanned identifier continues a
     * longer member chain ({@code obj.prop.}) and is not a module reference. Position 0 has no
     * preceding char and never blocks.
     */
    private static boolean isChainedRef(String s, int j) {
        return j > 0 && (isIdentChar(s.charAt(j - 1)) || s.charAt(j - 1) == '.');
    }

    /** [\w-]: chars of a partially typed CSS class member name. */
    private static boolean isMemberChar(char c) {
        return isIdentChar(c) || c == '-';
    }

    /** [A-Za-z0-9_$]: chars of a JS identifier ($style / import binding). */
    private static boolean isIdentChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }
}
