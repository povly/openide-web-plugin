import org.jetbrains.plugins.textmate.bundles.BundleReaderCoreKt;
import org.jetbrains.plugins.textmate.bundles.TextMateBundleReader;
import org.jetbrains.plugins.textmate.bundles.TextMateGrammar;
import org.jetbrains.plugins.textmate.bundles.TextMateNioResourceReader;
import org.jetbrains.plugins.textmate.language.TextMateInterner;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.plugins.textmate.language.TextMateScopeComparatorCore;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableBuilder;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableCore;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateCachingSyntaxMatcher;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcher;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcherImpl;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType;
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateTheme;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigher;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateCachingSelectorWeigherKt;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigherImpl;
import org.jetbrains.plugins.textmate.plist.JsonOrXmlOrYamlPlistReader;
import org.jetbrains.plugins.textmate.plist.Plist;
import org.jetbrains.plugins.textmate.plist.PlistReaderCore;
import org.jetbrains.plugins.textmate.plist.XmlPlistReader;
import org.jetbrains.plugins.textmate.plist.YamlPlistReader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Session-4 A2 harness: run the REAL paint pipeline stages on a .ts and a .vue file
 * and diff where .vue-script loses colors.
 *
 * Stages (per token):
 *   0: raw scope chain from the lexer (TextMateElementType)
 *   1: trimEmbeddedScope(elementType) — private static in TextMateHighlighter, called via reflection
 *   2: TextMateScopeComparatorCore.sortAndFilter(TextMateTheme rules) — real weigher
 *   3: keys = reversed(matched).map { customColors?no : TextMateTheme.getTextAttributesKey(sel) }
 *      (customHighlightingColors = empty: our bundle ships no preferences with
 *       customHighlightingAttributes — verified; empty map = union unchanged)
 *
 * Usage: PipelineRepro <bundleDir> <tsFile> <vueFile>
 */
public class PipelineRepro {

    static TextMateSelectorWeigher weigher =
            TextMateCachingSelectorWeigherKt.caching(new TextMateSelectorWeigherImpl());
    static Method trimEmbedded;

    public static void main(String[] args) throws Exception {
        Path bundleDir = Path.of(args[0]);
        Path tsFile = Path.of(args[1]);
        Path vueFile = Path.of(args[2]);

        Class<?> hl = Class.forName("org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateHighlighter");
        trimEmbedded = hl.getDeclaredMethod("trimEmbeddedScope", TextMateElementType.class);
        trimEmbedded.setAccessible(true);

        TextMateSyntaxTableCore table = loadBundle(bundleDir);

        System.out.println("== theme rules: " + TextMateTheme.INSTANCE.getRules().size() + " selectors");

        runFile(table, "text.html.vue", vueFile);
        runFile(table, "source.ts", tsFile);
    }

    static TextMateSyntaxTableCore loadBundle(Path bundleDir) {
        PlistReaderCore plistReader = new JsonOrXmlOrYamlPlistReader(
                new org.jetbrains.plugins.textmate.plist.JsonPlistReader(),
                new XmlPlistReader(), new YamlPlistReader());
        TextMateBundleReader reader = BundleReaderCoreKt.readTextMateBundle(
                "wfm-s4", plistReader, new TextMateNioResourceReader(bundleDir));
        TextMateInterner interner = new TextMateInterner() {
            @Override public String intern(String s) { return s; }
            @Override public void clear() { }
        };
        TextMateSyntaxTableBuilder builder = new TextMateSyntaxTableBuilder(interner);
        var it = reader.readGrammars().iterator();
        while (it.hasNext()) {
            TextMateGrammar g = it.next();
            Plist plist = (Plist) ((kotlin.Lazy<?>) g.getPlist()).getValue();
            builder.addSyntax(plist);
        }
        return builder.build();
    }

    static void runFile(TextMateSyntaxTableCore table, String rootScope, Path file) throws Exception {
        String sample = Files.readString(file);
        TextMateLanguageDescriptor desc = table.getLanguageDescriptor(rootScope);
        System.out.println("\n########## " + file.getFileName() + " root=" + rootScope + " descriptor=" + (desc != null));
        if (desc == null) return;

        org.jetbrains.plugins.textmate.regex.RegexProvider regexProvider =
                new org.jetbrains.plugins.textmate.regex.CaffeineCachingRegexProvider(
                        new org.jetbrains.plugins.textmate.regex.RememberingLastMatchRegexFactory(
                                new com.intellij.textmate.joni.JoniRegexFactory()));
        TextMateSyntaxMatcher matcher = new TextMateCachingSyntaxMatcher(
                new TextMateSyntaxMatcherImpl(regexProvider, weigher));
        TextMateHighlightingLexer lexer = new TextMateHighlightingLexer(desc, matcher, 200000);
        lexer.start(sample, 0, sample.length(), 0);

        Set<CharSequence> rules = TextMateTheme.INSTANCE.getRules();

        int n = 0, matched = 0, keyed = 0;
        Map<String, int[]> classAgg = new TreeMap<>(); // innermost-root -> [tokens, matched, keyed]
        List<String> scriptDumps = new ArrayList<>();
        int scriptDumped = 0;

        while (lexer.getTokenType() != null && n < 200000) {
            TextMateElementType tet = (TextMateElementType) lexer.getTokenType();
            String text = sample.substring(lexer.getTokenStart(), Math.min(lexer.getTokenEnd(), lexer.getTokenStart() + 40))
                    .replace("\n", "\\n");

            String raw = chain(tet.getScope());
            TextMateScope trimmed = (TextMateScope) trimEmbedded.invoke(null, tet);
            String trimChain = chain(trimmed);
            boolean trimmedDiffers = !raw.equals(trimChain);

            List<CharSequence> sel = new TextMateScopeComparatorCore<CharSequence>(
                    weigher, trimmed, (kotlin.jvm.functions.Function1<CharSequence, CharSequence>) s -> s)
                    .sortAndFilter(rules);
            Collections.reverse(sel);

            List<String> keys = new ArrayList<>();
            for (CharSequence s : sel) {
                var k = TextMateTheme.INSTANCE.getTextAttributesKey(s);
                keys.add(k == null ? "null(find:" + s + ")" : k.getExternalName());
            }
            String innerRoot = innerRoot(raw);
            boolean inScript = raw.contains("source.ts") || raw.contains("source.js");

            if (!sel.isEmpty()) matched++;
            boolean hasKey = keys.stream().anyMatch(k -> !k.startsWith("null(") && !"TEXT".equals(k));
            if (hasKey) keyed++;
            int[] a = classAgg.computeIfAbsent(innerRoot, x -> new int[3]);
            a[0]++; if (!sel.isEmpty()) a[1]++; if (hasKey) a[2]++;

            if (inScript && scriptDumped < 24 &&
                    (innerRoot.startsWith("keyword") || innerRoot.startsWith("string")
                  || innerRoot.startsWith("comment") || innerRoot.startsWith("storage")
                  || innerRoot.startsWith("constant") || innerRoot.startsWith("entity"))) {
                scriptDumps.add(String.format(
                        "  tok[%d] %s%n    raw:   %s%n    trim:  %s%s%n    sel:   %s%n    keys:  %s",
                        n, text, raw, trimChain, trimmedDiffers ? "   <-- TRIMMED" : "",
                        sel.isEmpty() ? "-NONE-" : sel.toString(),
                        keys.isEmpty() ? "-NONE-" : keys.toString()));
                scriptDumped++;
            }
            lexer.advance();
            n++;
        }
        System.out.printf("tokens=%d matched=%d keyedNonText=%d%n", n, matched, keyed);
        System.out.println("-- innermost-scope aggregates (tokens/matched/keyed):");
        classAgg.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]))
                .limit(18)
                .forEach(e -> System.out.printf("  %-38s %d/%d/%d%n", e.getKey(),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        System.out.println("-- script-token stage dumps:");
        scriptDumps.forEach(d -> System.out.println(d));
    }

    static String chain(TextMateScope s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        TextMateScope cur = s;
        while (cur != null) {
            sb.append(cur.getScopeName());
            cur = cur.getParent();
            if (cur != null) sb.append(" <- ");
        }
        return sb.toString();
    }

    static String innerRoot(String chain) {
        int sp = chain.indexOf(' ');
        String first = sp < 0 ? chain : chain.substring(0, sp);
        String[] parts = first.split("\\.");
        return parts.length > 2 ? parts[0] + "." + parts[1] : first;
    }
}
