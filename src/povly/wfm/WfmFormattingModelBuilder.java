package povly.wfm;

import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.Formatter;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.Wrap;
import com.intellij.lang.ASTNode;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.DocumentBasedFormattingModel;
import com.intellij.psi.tree.IElementType;

import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Reformat (Ctrl+Alt+L, Actions on Save) for the wfm TextMate web files.
 *
 * Root-cause: platform 262 ships an active {@code TextMateLineIndentProvider} but NO formatter —
 * the {@code <lang.formatter language="textmate">} registration stays commented out in
 * {@code intellij.textmate.xml} and the draft {@code TextMateFormattingModelBuilder} in the
 * platform sources is an empty stub meant only to feed {@code DetectableIndentOptionsProvider}.
 * This builder closes the gap with a real lexeme-based block model:
 *
 * <ul>
 *   <li>Blocks are built from ONE full-document TextMate pass ({@link WfmHighlighterStack#MATCHER}).
 *       The TextMate lexer state is binary (0 = clean line end, 1 = mid-context, no context stack),
 *       so incremental restarts inside embedded regions (vue {@code <script>} → source.ts) re-lex
 *       as the root grammar — same quirk fixed for highlighting in v1.8.2
 *       ({@link WfmEditorHighlighterProvider}); formatting never relies on lexer restarts.</li>
 * <li>v1 rules: brace-nesting indent normalization for every grammar; tag-nesting
 *       ({@code <div>…</div>}) indent for html-ish scopes (vue template); line/spacing rules
 *       ({@code { } ; , :}) only inside CSS-family scopes ({@code source.css/scss/less}, including
 *       vue embedded style). Everything else (js/ts) keeps its original inter-token
 *       spacing via {@link Spacing#getReadOnlySpacing()} — indentation still normalizes because
 *       existing line feeds are preserved and indents are recomputed.</li>
 * <li>v1.14.0 root-cause №1 «Reformat меняет ничего»: {@code Formatter.createFormattingModelForPsiFile}
 *       возвращает {@code PsiBasedFormattingModel}, который применяет каждую правку через AST
 *       ({@code replaceWithPSI → FormatterUtil.replaceWhiteSpace(…, TokenType.WHITE_SPACE, …)}),
 *       а {@code TextMateParserDefinition.createLexer()} возвращает {@code EmptyLexer} — TextMate PSI
 *       не содержит WHITE_SPACE-узлов вовсе, все правки умирали молча (без исключений).
 *       Фикс: {@link DocumentBasedFormattingModel} пишет whitespace прямо в Document
 *       ({@code Document.replaceString}). Доказательства и карта пути движка:
 *       {@code .ai-factory/references/FORMAT-ENGINE.md}.</li>
 * <li>v1.14.0 root-cause №2 «каждое нажатие добавляет отступ»: {@code CodeFormatterFacade.getIndentOptions}
 *       без провайдера запускает АВТО-ДЕТЕКТ опций; на смешанных табах/пробелах каждый прогон
 *       детектил разные USE_TAB/INDENT_SIZE и перегенерил отступы — вечный дрейф (воспроизведено
 *       в движок-лаборатории {@code tests/povly/wfm/WfmFormatEngineTest}, сценарий options-drift:
 *       GROWING; на статичных опциях движок + эта модель полностью идемпотентны). Фикс:
 *       {@link WfmFileIndentOptionsProvider} пинит опции профилем TextMate Code Style.</li>
 *   <li>Tag blocks: an opening {@code <tag …>} (non-void, non-self-closing) pairs with its
 *       matching {@code </tag>} into a group exactly like braces, so Reformat aligns vue
 *       template nesting. Void elements (br, img, …) and self-closing tags stay leaves.
 *       In non-html scopes {@code <} is an operator and never starts a group (scope-gated).</li>
 *   <li>Braces must be standalone lexemes (true for the bundled atom/shiki grammars which tokenize
 *       punctuation separately). If a grammar merges {@code a{}}, the pair is simply skipped and
 *       original spacing is kept — safe degradation, never corrupts text.</li>
 *   <li>Lexing honors {@code textmate.line.highlighting.limit} (20000) exactly like the editor
 *       highlighter; over-long lines degrade to original spacing the same way highlighting does.</li>
 * </ul>
 *
 * Note: the commented-out platform stub was studied as a reference (JetBrains, Apache 2.0);
 * the block model here is a clean-room lexeme implementation, not a copy.
 */
public final class WfmFormattingModelBuilder implements FormattingModelBuilder {

    private static final Logger LOG = Logger.getInstance(WfmFormattingModelBuilder.class);

    /** One-time session marker; grep 'wfm: formatter active' idea.log to verify installation. */
    private static volatile boolean loggedActive;

    /**
     * Safety valve: documents with more non-whitespace tokens than this get a no-op dummy model
     * (full TextMate re-lexing of monsters is too expensive for the interactive formatter).
     */
    private static final int MAX_LEXEMES = 200_000;

    /** createModel call counter — logged every 500th call to expose runaway callers in idea.log. */
    private static final AtomicLong MODEL_CALLS = new AtomicLong();

    /**
     * Single-entry per-file model cache. Root-cause: IndentOptionsDetectorImpl.calcLineIndentInfo
     * asks the FormattingModelBuilder for a model per analyzed line — v1.10.x-v1.11.1 re-lexed the
     * WHOLE document (Joni regexes) on every call, an allocation storm that the GC could not keep
     * up with ("gc overloaded: true", Memory deficit, cascading OOM at startup with 27 open
     * editors). The lexeme list and the lazily-built block tree are immutable after construction,
     * so one tree per file per document modification is safe to share across calls/threads.
     * Invalidated by Document.getModificationCount(); the file is held via WeakReference.
     */
    private static final AtomicReference<CachedModel> LAST_MODEL = new AtomicReference<>();

    private static final class CachedModel {
        final WeakReference<PsiFile> file;
        final long modificationCount;
        final WfmBlock root;

        CachedModel(PsiFile file, long modificationCount, WfmBlock root) {
            this.file = new WeakReference<>(file);
            this.modificationCount = modificationCount;
            this.root = root;
        }

        WfmBlock rootFor(PsiFile candidate) {
            if (candidate == null || file.get() != candidate) return null;
            Document document = PsiDocumentManager.getInstance(candidate.getProject()).getDocument(candidate);
            if (document == null || document.getModificationStamp() != modificationCount) return null;
            return root;
        }
    }

    @Override
    public FormattingModel createModel(FormattingContext context) {
        PsiFile file = context.getContainingFile();

        if (!loggedActive) {
            loggedActive = true;
            LOG.info("wfm: formatter active");
        }
        long calls = MODEL_CALLS.incrementAndGet();
        if (calls % 500 == 0) {
            LOG.info("wfm: formatter createModel calls=" + calls + " lastFile=" + file.getName());
        }

        CachedModel cached = LAST_MODEL.get();
        WfmBlock root = cached != null ? cached.rootFor(file) : null;
        if (root == null) {
            List<Lexeme> lexemes = lexAll(file);
            if (lexemes.size() > MAX_LEXEMES || lexemes.isEmpty()) {
                // no grammar / runaway size: the platform dummy model is a valid no-op
                LOG.info("wfm: formatter dummy model file=" + file.getName() + " lexemes=" + lexemes.size());
                return Formatter.getInstance().createDummyFormattingModel(file);
            }
            root = new WfmBlock(lexemes, 0, lexemes.size(), false, Indent.getNoneIndent());
            Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
            LAST_MODEL.set(new CachedModel(file,
                    document != null ? document.getModificationStamp() : -1, root));
            LOG.info("wfm: formatter model built file=" + file.getName() + " lexemes=" + lexemes.size());
        }
        return new InstrumentedDocumentModel(root, context.getCodeStyleSettings(), file);
    }

    /**
     * Document-applied formatting model (v1.14.0 fix, see class Javadoc) with edit counting:
     * {@code commitChanges()} logs the number of whitespace replacements that actually reached
     * the document — the single reliable signal that the engine's edit chain is alive
     * (model building alone does NOT prove it: the indent auto-detector also builds models).
     */
    private static final class InstrumentedDocumentModel extends DocumentBasedFormattingModel {
        private final String fileName;
        private int edits;

        InstrumentedDocumentModel(Block root, CodeStyleSettings settings, PsiFile file) {
            super(root, settings, file);
            this.fileName = file.getName();
        }

        @Override
        public TextRange replaceWhiteSpace(TextRange textRange, ASTNode nodeAfter, String whiteSpace) {
            TextRange result = super.replaceWhiteSpace(textRange, nodeAfter, whiteSpace);
            edits++;
            LOG.debug("wfm: formatter edit offset=" + textRange.getStartOffset()
                    + " ws=" + whiteSpace.replace("\n", "\\n"));
            return result;
        }

        @Override
        public void commitChanges() {
            super.commitChanges();
            LOG.info("wfm: formatter commit file=" + fileName + " edits=" + edits);
        }
    }

    /** Full-document single-pass lexing; whitespace runs are dropped (blocks cover non-whitespace). */
    private static List<Lexeme> lexAll(PsiFile file) {
        List<Lexeme> out = new ArrayList<>();
        TextMateLanguageDescriptor descriptor =
                TextMateService.getInstance().getLanguageDescriptorByFileName(file.getName());
        if (descriptor == null) return out; // soft refusal: no grammar → empty model → no-op reformat
        String text = file.getText();
        Lexer lexer = new TextMateHighlightingLexer(
                descriptor, WfmHighlighterStack.MATCHER, WfmHighlighterStack.LINE_LIMIT);
        lexer.start(text, 0, text.length(), 0);
        while (lexer.getTokenType() != null) {
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            String token = text.substring(start, end);
            if (!token.isBlank()) {
                IElementType type = lexer.getTokenType();
                String scope = type instanceof TextMateElementType ? scopeChain((TextMateElementType) type) : "";
                out.add(new Lexeme(token, scope, start, end));
            }
            lexer.advance();
        }
        return out;
    }

    /** Full dotted scope chain, outermost first: "source.css meta.property-list.css …". */
    private static String scopeChain(TextMateElementType type) {
        StringBuilder sb = new StringBuilder();
        TextMateScope scope = type.getScope();
        while (scope != null && !scope.isEmpty()) {
            if (sb.length() > 0) sb.insert(0, ' ');
            sb.insert(0, scope.getScopeName());
            scope = scope.getParent();
        }
        return sb.toString();
    }

    /** CSS-family lexeme (incl. vue embedded style)? Only these get active spacing rules. */
    private static boolean cssFamily(Lexeme lexeme) {
        String s = lexeme.scope();
        return s.contains("source.css") || s.contains("source.scss") || s.contains("source.less");
    }

    /** Pseudo-class / pseudo-element token (":hover", "::after" tails): no space after the colon. */
    private static boolean isPseudo(Lexeme lexeme) {
        return lexeme.scope().contains("pseudo");
    }

    /** One non-whitespace TextMate token with its full scope chain and document offsets. */
    record Lexeme(String text, String scope, int start, int end) {}

    /**
     * Canonical spacing presets (Spacing instances are immutable and freely shared).
     * Holder class — root-cause: Spacing.createSpacing internally resolves the Formatter
     * service, and platform forbids service lookups in &lt;clinit&gt; ("Class initialization
     * must not depend on services" — logged as an error when the formatter class was first
     * instantiated by an early FileIndentOptionsProvider walk). The holder defers creation
     * to the first real formatting call, when the application is fully up.
     */
    private static final class Presets {
        static final Spacing LINE_BREAK = Spacing.createSpacing(0, Integer.MAX_VALUE, 1, true, 1);
        static final Spacing NO_SPACE = Spacing.createSpacing(0, 0, 0, false, 0);
        /** Space, but a manually typed line break survives (Allman '{', multi-line selector/value lists). */
        static final Spacing SPACE_OR_LINE = Spacing.createSpacing(1, 1, 0, true, 0);
    }

    /**
     * Lexeme-window block. Composite blocks span matched pairs — {@code { … }} braces or
     * {@code <tag …> … </tag>} tags — delimiters included; every other child is a single-lexeme
     * leaf. The parent decides each child's {@link Indent}: inside a composite only the middle
     * children get the normal indent — the first (opening delimiter) and the last (closing
     * delimiter) stay at the group's own level.
     */
    static final class WfmBlock implements Block {
        private final List<Lexeme> lexemes; // shared immutable list for the whole file
        private final int from;             // [from, to) window into lexemes
        private final int to;
        private final boolean composite;    // spans a matched brace or tag pair inclusive
        private final Indent indent;        // decided by the parent at construction time
        private List<Block> subBlocks;      // lazy; a format run is single-threaded per file

        WfmBlock(List<Lexeme> lexemes, int from, int to, boolean composite, Indent indent) {
            this.lexemes = lexemes;
            this.from = from;
            this.to = to;
            this.composite = composite;
            this.indent = indent;
        }

        @Override
        public TextRange getTextRange() {
            return from >= to ? TextRange.EMPTY_RANGE
                    : new TextRange(lexemes.get(from).start(), lexemes.get(to - 1).end());
        }

        @Override
        public List<Block> getSubBlocks() {
            if (subBlocks == null) subBlocks = buildChildren();
            return subBlocks;
        }

        private List<Block> buildChildren() {
            // v1.12.0 root-cause fix (found by token fuzzing): a single-lexeme leaf block
            // produced a child identical to itself — the same one-lexeme range — so any walker
            // that called getSubBlocks() on leaves (the indent auto-detector at startup) built
            // an infinite leaf-to-leaf chain: millions of blocks, OOM. The v1.11.1 same-range
            // guard below was correct but insufficient — it only covered composite groups,
            // while the explosion lived in leaves. A block over one lexeme is a leaf: no children.
            if (to - from <= 1) return List.of();
            List<Block> children = new ArrayList<>();
            int i = from;
            while (i < to) {
                int close = -1;
                if (isOpenTagStart(i)) {
                    close = findMatchingTag(i, to);
                } else if (isBrace(i, "{")) {
                    close = findMatchingBrace(i, to);
                }
                // v1.10.0-v1.11.0 froze the IDE with OOM (7.4M blocks, 1.18GB): building the
                // children of a group re-detected the SAME group on the group's own opening
                // delimiter (findMatchingBrace/Tag scan starts at it), producing a child
                // identical to its parent — an infinite single-child chain that the platform's
                // IndentOptionsDetector walked. A child group spanning the WHOLE parent range
                // is by construction the parent itself, so it can never be legitimate:
                // delimiters are part of the range. Leaf-fallback instead.
                boolean sameRangeAsSelf = composite && i == from && close + 1 == to;
                if (close > i && !sameRangeAsSelf) {
                    children.add(new WfmBlock(lexemes, i, close + 1, true,
                            composite ? Indent.getNormalIndent() : Indent.getNoneIndent()));
                    i = close + 1;
                    continue;
                }
                Indent childIndent = !composite || i == from || i == to - 1
                        ? Indent.getNoneIndent()
                        : Indent.getNormalIndent();
                children.add(new WfmBlock(lexemes, i, i + 1, false, childIndent));
                i++;
            }
            return children;
        }

        @Override
        public Wrap getWrap() {
            return null;
        }

        @Override
        public Indent getIndent() {
            return indent;
        }

        @Override
        public Alignment getAlignment() {
            return null;
        }

        @Override
        public Spacing getSpacing(Block child1, Block child2) {
            if (!(child1 instanceof WfmBlock) || !(child2 instanceof WfmBlock)) return null;
            Lexeme last = ((WfmBlock) child1).lexemes.get(((WfmBlock) child1).to - 1);
            Lexeme next = ((WfmBlock) child2).lexemes.get(((WfmBlock) child2).from);
            if (!cssFamily(last) && !cssFamily(next)) return Spacing.getReadOnlySpacing();

            String t1 = last.text();
            String t2 = next.text();
            if (t1.equals("{") || t2.equals("}")) return Presets.LINE_BREAK;   // after '{', before '}'
            if (t2.equals("{")) return Presets.SPACE_OR_LINE;                  // "selector {"
            if (t1.equals(";")) return Presets.LINE_BREAK;                     // next declaration on a new line
            if (t2.equals(";") || t2.equals(",") || t2.equals(":")) return Presets.NO_SPACE;
            if (t1.equals(",")) return Presets.SPACE_OR_LINE;               // "a, b" — keeps "a,\nb" lists
            if (t1.equals(":")) return isPseudo(next) ? Presets.NO_SPACE : Presets.SPACE_OR_LINE;
            return Spacing.getReadOnlySpacing();                       // conservative default
        }

        @Override
        public ChildAttributes getChildAttributes(int newChildIndex) {
            return new ChildAttributes(
                    composite ? Indent.getNormalIndent() : Indent.getNoneIndent(), null);
        }

        @Override
        public boolean isIncomplete() {
            return false; // matched groups only; unmatched braces/tags degrade to original spacing
        }

        @Override
        public boolean isLeaf() {
            // v1.13.2 root-cause: "Reformat changes nothing" — isLeaf used to return
            // !composite, so the ROOT (never composite) declared itself a leaf and the
            // engine never walked into its children: zero edits for any file. A leaf is
            // a block over a single lexeme; every larger block has children.
            return to - from <= 1;
        }

        /** html-ish scope (vue template, html): tags are structural here, '<' is markup. */
        private static boolean htmlFamily(Lexeme lexeme) {
            return lexeme.scope().contains("text.html");
        }

        /** html void elements never close — they must not open a group. */
        private static final Set<String> VOID_ELEMENTS = Set.of(
                "area", "base", "br", "col", "embed", "hr", "img", "input",
                "link", "meta", "param", "source", "track", "wbr");

        /** Tag name: letter first, then word chars, dots or dashes (my-comp, transition-group). */
        private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z][\\w.-]*");

        /** The lexeme at i opens a real tag "<name …>": not closing, void, self-closing or malformed. */
        private boolean isOpenTagStart(int i) {
            if (!lexemes.get(i).text().equals("<") || !htmlFamily(lexemes.get(i))) return false;
            if (i + 1 >= to) return false;
            String name = lexemes.get(i + 1).text();
            if (!TAG_NAME.matcher(name).matches() || isVoidName(name)) return false;
            int close = tagCloseLexeme(i, to);
            return close > 0 && !isSelfClosing(i, close);
        }

        /** Inclusive index of the lexeme that closes a tag (">" or "/>"), or -1. */
        private int tagCloseLexeme(int open, int end) {
            for (int i = open + 1; i < end; i++) {
                String t = lexemes.get(i).text();
                if (t.equals(">") || t.equals("/>")) return i;
                if (t.equals("<") || t.equals("</")) return -1;
            }
            return -1;
        }

        private boolean isSelfClosing(int open, int close) {
            if (lexemes.get(close).text().equals("/>")) return true;
            return close > open + 1 && lexemes.get(close - 1).text().equals("/");
        }

        private static boolean isVoidName(String name) {
            return VOID_ELEMENTS.contains(name.toLowerCase(Locale.ROOT));
        }

        /** i starts "<name" (closing=false) or "</name" (closing=true) in an html scope. */
        private boolean isTagStartWithName(int i, String name, boolean closing) {
            if (i + 1 >= to) return false;
            String t = lexemes.get(i).text();
            if (!(closing ? t.equals("</") : t.equals("<"))) return false;
            if (!htmlFamily(lexemes.get(i))) return false;
            return lexemes.get(i + 1).text().equals(name);
        }

        /**
         * Inclusive index of the "&gt;" lexeme of the matching {@code </name>} for the tag
         * opened at {@code open}, or -1. Depth-counts same-name tags; an unclosed pair
         * degrades to plain leaves (original spacing kept — never corrupts text).
         */
        private int findMatchingTag(int open, int end) {
            String name = lexemes.get(open + 1).text();
            int depth = 0;
            int i = open;
            while (i < end) {
                if (isTagStartWithName(i, name, false)) {
                    int close = tagCloseLexeme(i, end);
                    if (close < 0) return -1;
                    if (!isSelfClosing(i, close)) depth++;
                    i = close + 1;
                    continue;
                }
                if (isTagStartWithName(i, name, true)) {
                    depth--;
                    if (depth == 0) return tagCloseLexeme(i, end);
                    int close = tagCloseLexeme(i, end);
                    if (close < 0) return -1;
                    i = close + 1;
                    continue;
                }
                i++;
            }
            return -1;
        }

        private boolean isBrace(int i, String brace) {
            return lexemes.get(i).text().equals(brace);
        }

        /** Index of the '}' matching the '{' at {@code open}, or -1. String lexemes never contain braces. */
        private int findMatchingBrace(int open, int end) {
            int depth = 0;
            for (int i = open; i < end; i++) {
                String t = lexemes.get(i).text();
                if (t.equals("{")) {
                    depth++;
                } else if (t.equals("}")) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }
    }
}
