package povly.wfm;

import com.intellij.formatting.Block;
import povly.wfm.WfmFormattingModelBuilder.Lexeme;
import com.intellij.formatting.FormatProcessor;
import com.intellij.formatting.FormatTextRanges;
import com.intellij.formatting.FormattingDocumentModel;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelEx;
import com.intellij.formatting.FormattingProgressCallback;
import com.intellij.formatting.Indent;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine lab (plan task 6): drives the REAL platform formatting engine
 * ({@code FormatProcessor.format(model, false)} — the same pipeline Ctrl+Alt+L runs,
 * minus the modal-progress wrapper) against the REAL {@code WfmBlock} tree, outside the IDE
 * (a proxy Application serves the Formatter service). Lexemes are produced by a minimal
 * hand lexer with "source.css" scopes, which is enough to exercise the css spacing rules.
 *
 * Checks: (1) the output is correctly indented, (2) idempotency — run N+1 == run N,
 * (3) no growth. This is the harness that caught the ever-growing-indent bug: the engine's
 * {@code AbstractBlockWrapper.getChildOffset} adds the whitespace parsed from the CURRENT
 * text (lines 261/275) when the CAN_USE_FIRST_CHILD_INDENT flag is set, so each run added
 * another indent layer.
 */
public final class WfmFormatEngineTest {

    public static void main(String[] args) throws Exception {
        java.awt.EventQueue.invokeAndWait(() -> {
            installProxyApplication();
            realMain();
        });
        System.exit(failed ? 1 : 0);
    }

    private static boolean failed;

    private static void realMain() {
        String nested =
                ".filters {\n" +
                "\tgrid-template-columns: 1fr 224px;\n" +
                "\n" +
                "\t@media screen and (max-width: 1200px) {\n" +
                "\t\tgrid-template-columns: 1fr;\n" +
                "\t}\n" +
                "}\n";
        String flat = ".a{color:red;background:blue}\n";

        boolean ok = true;
        ok &= check("flat", flat);
        ok &= check("nested", nested);

        System.out.println(ok ? "ALL PASS" : "FAIL");
        failed = !ok;
    }

    private static boolean check(String name, String input) {
        // Default profile (TextMate Code Style defaults) asserts the desired output too;
        // alternative option sets assert idempotency only (desired text obviously differs).
        boolean ok = checkWithOptions(name, input, 2, 2, false, true);
        ok &= checkWithOptions(name, input, 4, 4, true, false);
        ok &= checkWithOptions(name, input, 4, 4, false, false);
        ok &= checkWithOptions(name, input, 8, 4, true, false);
        driftDemonstration(name, input);
        ok &= fuzz(200);
        return ok;
    }

    /**
     * Reproduces the v1.14.0 root cause №2 as a demonstration (not a failing assertion): when
     * indent options change between runs — which is what the platform AUTO-DETECTOR did to
     * mixed tabs/spaces files on every Ctrl+Alt+L — the text keeps drifting and never converges.
     * In the IDE this path is unreachable now: WfmFileIndentOptionsProvider pins the options.
     */
    private static void driftDemonstration(String name, String input) {
        Object[][] optionCycle = {
                {4, 4, true}, {2, 2, false}, {8, 4, true}, {2, 2, false}};
        String text = input;
        int firstLen = -1;
        for (int i = 0; i < 8; i++) {
            Object[] o = optionCycle[i % optionCycle.length];
            text = formatOnce(text, (Integer) o[0], (Integer) o[1], (Boolean) o[2]);
            if (i == 0) firstLen = text.length();
        }
        System.out.println(name + " options-drift (root-cause demo, IDE-fixed by provider): len "
                + firstLen + " -> " + text.length());
    }

    /**
     * Fuzzer (plan task 6): random brace-token soup must never throw, must be idempotent
     * (second run applies zero edits) and must not grow the text.
     */
    private static boolean fuzz(int iterations) {
        java.util.Random random = new java.util.Random(42);
        String[] tokens = {".a", "{", "}", "color", ":", "red", ";", ",", "@media", "(", ")", "&B",
                "<div", ">", "</div>", "x"};
        for (int i = 0; i < iterations; i++) {
            StringBuilder sb = new StringBuilder();
            int lines = 1 + random.nextInt(12);
            for (int l = 0; l < lines; l++) {
                int tokensInLine = random.nextInt(6);
                for (int t = 0; t < tokensInLine; t++) {
                    sb.append(tokens[random.nextInt(tokens.length)]);
                    if (random.nextBoolean()) sb.append(' ');
                }
                sb.append('\n');
            }
            String input = sb.toString();
            String run1 = formatOnce(input, 2, 2, false);
            String run2 = formatOnce(run1, 2, 2, false);
            if (!run1.equals(run2)) {
                System.out.println("FUZZ FAIL (not idempotent) at iteration " + i + ":\n" + input);
                return false;
            }
        }
        System.out.println("fuzz: " + iterations + " random inputs — idempotent, no exceptions");
        return true;
    }

    private static boolean checkWithOptions(String name, String input, int indentSize, int tabSize,
                                             boolean useTab, boolean checkDesired) {
        String label = name + " indent=" + indentSize + " tab=" + tabSize + " useTab=" + useTab;
        String run1 = formatOnce(input, indentSize, tabSize, useTab);
        String run2 = formatOnce(run1, indentSize, tabSize, useTab);
        String run3 = formatOnce(run2, indentSize, tabSize, useTab);
        boolean idempotent = run1.equals(run2) && run2.equals(run3);
        System.out.println(label + ": idempotent=" + idempotent + " (len " + run1.length()
                + " -> " + run2.length() + " -> " + run3.length() + ")");
        if (!idempotent) {
            System.out.println("--- run1 ---\n" + run1);
            System.out.println("--- run2 ---\n" + run2);
            return false;
        }
        if (!checkDesired) {
            return true;
        }
        boolean desired;
        if ("flat".equals(name)) {
            desired = run1.equals(".a {\n  color: red;\n  background: blue\n}\n");
        } else {
            desired = run1.equals(".filters {\n" +
                    "  grid-template-columns: 1fr 224px;\n" +
                    "\n" +
                    "  @media screen and (max-width: 1200px) {\n" +
                    "    grid-template-columns: 1fr;\n" +
                    "  }\n" +
                    "}\n");
        }
        System.out.println(label + ": desired=" + desired);
        return desired;
    }

    private static String formatOnce(String text, int indentSize, int tabSize, boolean useTab) {
        List<Lexeme> lexemes = lex(text);
        WfmFormattingModelBuilder.WfmBlock root =
                new WfmFormattingModelBuilder.WfmBlock(lexemes, 0, lexemes.size(), false, Indent.getNoneIndent());
        // Reads go through a real DocumentImpl (the engine parses whitespace from it at build
        // time); edits are applied to a shadow StringBuilder — DocumentImpl.replaceString demands
        // a full EDT/write-action/EP environment the lab does not bootstrap, and the engine never
        // re-reads the document between edits of one run (shift-tracked ranges).
        com.intellij.openapi.editor.impl.DocumentImpl doc =
                new com.intellij.openapi.editor.impl.DocumentImpl(text);
        StringBuilder shadow = new StringBuilder(text);
        FakeDocumentModel fdm = new FakeDocumentModel(doc);
        FakeModel model = new FakeModel(root, fdm, shadow);

        CodeStyleSettings settings = new CodeStyleSettings();
        CommonCodeStyleSettings.IndentOptions io = settings.getIndentOptions();
        io.INDENT_SIZE = indentSize;
        io.TAB_SIZE = tabSize;
        io.USE_TAB_CHARACTER = useTab;

        FormatTextRanges ranges = new FormatTextRanges(new TextRange(0, text.length()), true);
        FormatProcessor processor = new FormatProcessor(fdm, root,
                new FormatProcessor.FormatOptions(settings, io, ranges), FormattingProgressCallback.EMPTY);
        processor.format(model, false);
        System.out.println("(edits applied: " + model.edits + ")");
        return shadow.toString();
    }

    /**
     * Minimal css hand lexer: punctuation {}();:,>< as single tokens, other non-whitespace
     * runs as one token each, everything scoped "source.css" so the css spacing rules engage.
     */
    static List<Lexeme> lex(String text) {
        List<Lexeme> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if ("{}();:,><".indexOf(c) >= 0) {
                out.add(new Lexeme(String.valueOf(c), "source.css", i, i + 1));
                i++;
                continue;
            }
            int j = i;
            while (j < n && !Character.isWhitespace(text.charAt(j)) && "{}();:,><".indexOf(text.charAt(j)) < 0) {
                j++;
            }
            out.add(new Lexeme(text.substring(i, j), "source.css", i, j));
            i = j;
        }
        return out;
    }

    private static void installProxyApplication() {
        Object formatter = new com.intellij.formatting.FormatterImpl();
        Object app = Proxy.newProxyInstance(WfmFormatEngineTest.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (proxy, method, args) -> {
                    if ("getService".equals(method.getName()) && args != null && args.length == 1
                            && args[0] == com.intellij.formatting.Formatter.class) {
                        return formatter;
                    }
                    if ("getService".equals(method.getName()) && args != null && args.length == 1
                            && args[0] == com.intellij.openapi.fileEditor.FileDocumentManager.class) {
                        return new StubFileDocumentManager();
                    }
                    if ("getService".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof Class<?> serviceClass && serviceClass.isInterface()) {
                        return serviceStub(serviceClass);
                    }
                    if ("isUnitTestMode".equals(method.getName())) return true;
                    if ("isDispatchThread".equals(method.getName())) return true;
                    if ("isReadAccessAllowed".equals(method.getName())
                            || "isWriteAccessAllowed".equals(method.getName())) return true;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
        ApplicationManager.setApplication((Application) app);
    }

    /** FileDocumentManager stub: DocumentImpl.assertWriteAccess requires a non-null instance. */
    private static final class StubFileDocumentManager
            extends com.intellij.openapi.fileEditor.FileDocumentManager {
        @Override
        public Document getDocument(com.intellij.openapi.vfs.VirtualFile file) {
            return null;
        }

        @Override
        public Document getCachedDocument(com.intellij.openapi.vfs.VirtualFile file) {
            return null;
        }

        @Override
        public com.intellij.openapi.vfs.VirtualFile getFile(Document document) {
            return null;
        }

        @Override
        public void saveAllDocuments() {
        }

        @Override
        public void saveDocuments(java.util.function.Predicate<? super Document> predicate) {
        }

        @Override
        public void saveDocument(Document document) {
        }

        @Override
        public void saveDocumentAsIs(Document document) {
        }

        @Override
        public Document[] getUnsavedDocuments() {
            return new Document[0];
        }

        @Override
        public boolean isDocumentUnsaved(Document document) {
            return false;
        }

        @Override
        public boolean isFileModified(com.intellij.openapi.vfs.VirtualFile file) {
            return false;
        }

        @Override
        public boolean isPartialPreviewOfALargeFile(Document document) {
            return false;
        }

        @Override
        public void reloadFromDisk(Document document, com.intellij.openapi.project.Project project) {
        }

        @Override
        public String getLineSeparator(com.intellij.openapi.vfs.VirtualFile file, com.intellij.openapi.project.Project project) {
            return "\n";
        }

        @Override
        public boolean requestWriting(Document document, com.intellij.openapi.project.Project project) {
            return true;
        }

        @Override
        public void reloadFiles(com.intellij.openapi.vfs.VirtualFile... files) {
        }
    }

    /** Service stub: dynamic proxy answering defaults for any interface service the platform asks for. */
    private static Object serviceStub(Class<?> serviceClass) {
        return Proxy.newProxyInstance(WfmFormatEngineTest.class.getClassLoader(), new Class<?>[]{serviceClass},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt.isInterface()) return serviceStub(rt);
                    return null;
                });
    }

    /** Document model over a real DocumentImpl; mirrors FormattingDocumentModelImpl for pure text. */
    private static final class FakeDocumentModel implements FormattingDocumentModel {
        private final Document doc;

        FakeDocumentModel(Document doc) {
            this.doc = doc;
        }

        @Override
        public int getLineNumber(int offset) {
            return doc.getLineNumber(offset);
        }

        @Override
        public int getLineStartOffset(int line) {
            return doc.getLineStartOffset(line);
        }

        @Override
        public CharSequence getText(TextRange range) {
            return doc.getCharsSequence().subSequence(range.getStartOffset(),
                    Math.min(range.getEndOffset(), doc.getTextLength()));
        }

        @Override
        public int getTextLength() {
            return doc.getTextLength();
        }

        @Override
        public Document getDocument() {
            return doc;
        }

        @Override
        public boolean containsWhiteSpaceSymbolsOnly(int startOffset, int endOffset) {
            CharSequence t = doc.getCharsSequence();
            for (int i = startOffset; i < endOffset && i < t.length(); i++) {
                if (!Character.isWhitespace(t.charAt(i))) return false;
            }
            return true;
        }

        @Override
        public CharSequence adjustWhiteSpaceIfNecessary(CharSequence whiteSpace, int startOffset,
                                                        int endOffset, ASTNode nodeAfter, boolean checkComments) {
            return whiteSpace;
        }
    }

    /** Model that applies each whitespace replacement to the shadow text and counts edits. */
    private static final class FakeModel implements FormattingModelEx {
        private final Block root;
        private final FakeDocumentModel fdm;
        private final StringBuilder shadow;
        int edits;

        FakeModel(Block root, FakeDocumentModel fdm, StringBuilder shadow) {
            this.root = root;
            this.fdm = fdm;
            this.shadow = shadow;
        }

        @Override
        public TextRange replaceWhiteSpace(TextRange textRange, String whiteSpace) {
            return replaceWhiteSpace(textRange, null, whiteSpace);
        }

        @Override
        public TextRange replaceWhiteSpace(TextRange textRange, ASTNode nodeAfter, String whiteSpace) {
            int start = textRange.getStartOffset();
            int end = Math.min(textRange.getEndOffset(), shadow.length());
            shadow.replace(start, end, whiteSpace);
            edits++;
            return new TextRange(start, start + whiteSpace.length());
        }

        @Override
        public TextRange shiftIndentInsideRange(ASTNode node, TextRange range, int shift) {
            return range;
        }

        @Override
        public void commitChanges() {
        }

        @Override
        public Block getRootBlock() {
            return root;
        }

        @Override
        public com.intellij.formatting.FormattingDocumentModel getDocumentModel() {
            return fdm;
        }
    }
}
