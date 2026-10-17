package povly.wfm;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.lang.Language;
import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;

/**
 * One-shot diagnostics: measures what the REAL editor-highlighting pipeline produces
 * for the web file types in this IDE build. Logs everything with the WFM-DIAG prefix.
 */
public final class WfmDiagStartup implements StartupActivity {

    private static final String[] PROBES = {
            "/var/www/jpbest/resources/js/pages/Login.vue",
            "/var/www/jpbest/resources/js/app.ts",
            "/var/www/jpbest/resources/css/app.css",
    };

    @Override
    public void runActivity(@NotNull Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Thread.sleep(10_000); // let the IDE settle, bundles register, servers warm up
                diag(project);
            } catch (Throwable t) {
                com.intellij.openapi.diagnostic.Logger.getInstance(WfmDiagStartup.class).warn("WFM-DIAG crashed: " + t);
            }
        });
    }

    private static void diag(Project project) throws Exception {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();

        // 1) extension -> FileType mapping as the platform sees it
        for (String probeName : new String[]{"a.vue", "a.css", "a.js", "a.ts", "a.scss", "a.less", "a.html", "a.tsx"}) {
            FileType ft = FileTypeManager.getInstance().getFileTypeByFileName(probeName);
            String lang = (ft instanceof LanguageFileType) ? ((LanguageFileType) ft).getLanguage().toString() : "-";
            log("mapping " + probeName + " -> fileType=" + ft.getName() + " (" + ft.getClass().getName() + ") language=" + lang);
        }

        // 2) textmate descriptors by file name
        for (String name : new String[]{"Login.vue", "app.css", "app.ts", "x.scss"}) {
            TextMateLanguageDescriptor d = TextMateService.getInstance().getLanguageDescriptorByFileName(name);
            log("descriptor[" + name + "] = " + (d == null ? "NULL" : "present"));
        }

        // 3) real editor highlighter token stream on real files
        for (String path : PROBES) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
            if (vf == null) { log("probe missing: " + path); continue; }
            String text = new String(Files.readAllBytes(java.nio.file.Path.of(vf.getPath())));

            FileType ft = vf.getFileType();
            String lang = (ft instanceof LanguageFileType) ? ((LanguageFileType) ft).getLanguage().toString() : "-";

            EditorHighlighter eh;
            try {
                eh = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, vf);
            } catch (Throwable t) {
                log("highlighter FAIL " + path + ": " + t);
                continue;
            }
            log("highlighter " + vf.getName() + " fileType=" + ft.getName() + " language=" + lang
                    + " impl=" + eh.getClass().getName());

            eh.setText(text);
            var it = eh.createIterator(0);
            int total = 0, sourceTokens = 0, distinct = 0;
            String lastScope = "";
            StringBuilder sample = new StringBuilder();
            while (!it.atEnd()) {
                Object token = it.getTokenType(); // IElementType (TextMateElementType toString = scope chain)
                String scope = String.valueOf(token);
                total++;
                if (scope.contains("source.") || scope.contains("text.html")) {
                    sourceTokens++;
                    if (!scope.equals(lastScope)) { distinct++; lastScope = scope; }
                }
                if (sample.length() < 400 && total < 60) {
                    sample.append(scope, 0, Math.min(scope.length(), 90)).append(" | ");
                }
                it.advance();
            }
            log("tokens " + vf.getName() + ": total=" + total + " sourceTokens=" + sourceTokens
                    + " distinctScopes=" + distinct);
            log("sample " + vf.getName() + ": " + sample);
        }

        // 4) scope -> color resolution under the CURRENT scheme
        try {
            Language textMateLang = Language.findLanguageByID("textmate");
            com.intellij.openapi.fileTypes.SyntaxHighlighter sh = textMateLang != null
                    ? com.intellij.openapi.fileTypes.SyntaxHighlighterFactory.getSyntaxHighlighter(textMateLang, project, null)
                    : null;
            log("syntaxHighlighter for textmate = " + (sh == null ? "NULL" : sh.getClass().getName()));
            if (sh != null) {
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(PROBES[0]);
                String text = new String(Files.readAllBytes(java.nio.file.Path.of(vf.getPath())));
                EditorHighlighter eh2 = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, vf);
                eh2.setText(text);
                var it2 = eh2.createIterator(0);
                java.util.Set<String> reported = new java.util.HashSet<>();
                int attrNull = 0, attrTotal = 0;
                while (!it2.atEnd() && reported.size() < 14) {
                    Object t = it2.getTokenType();
                    String scope = String.valueOf(t);
                    String short5 = scope.contains(" ") ? scope.substring(scope.lastIndexOf(' ') + 1) : scope;
                    if (short5.startsWith("source.ts") || short5.startsWith("source.js")
                            || short5.contains("keyword") || short5.contains("string")
                            || short5.contains("storage") || short5.contains("variable")
                            || short5.contains("constant") || short5.contains("entity")) {
                        if (reported.add(short5)) {
                            attrTotal++;
                            try {
                                var keys = sh.getTokenHighlights((com.intellij.psi.tree.IElementType) t);
                                StringBuilder sb2 = new StringBuilder();
                                for (var k : keys) {
                                    var attrs = scheme.getAttributes(k);
                                    sb2.append(k.getExternalName()).append("=>")
                                      .append(attrs == null || attrs.getForegroundColor() == null ? "null" :
                                              String.format("#%06x", attrs.getForegroundColor().getRGB() & 0xFFFFFF))
                                      .append(" ");
                                }
                                if (keys.length == 0) attrNull++;
                                log("color [" + scheme.getName() + "] " + short5 + " :: " + sb2);
                            } catch (Throwable e) {
                                log("color fail " + short5 + ": " + e);
                            }
                        }
                    }
                    it2.advance();
                }
                log("color stats: reported=" + reported.size() + " noKey=" + attrNull);
            }
        } catch (Throwable t) {
            log("colors block failed: " + t);
        }

        // 5) lsp4ij associations for probe names
        try {
            Object registry = Class.forName("com.redhat.devtools.lsp4ij.LanguageServersRegistry")
                    .getMethod("getInstance").invoke(null);
            var findMethod = registry.getClass().getMethod("findLanguageServerDefinitionFor", String.class);
            for (String name : new String[]{"Login.vue", "app.ts", "app.js", "app.css"}) {
                Object assocs = findMethod.invoke(registry, name);
                int n = 0;
                if (assocs instanceof java.util.List<?> list && !list.isEmpty()) {
                    for (Object a : list) {
                        Object def = a.getClass().getMethod("getServerDefinition").invoke(a);
                        Object lsId = def.getClass().getMethod("getId").invoke(def);
                        log("lsp4ij " + name + " -> server " + lsId + " (" + a.getClass().getName() + ")");
                        n++;
                    }
                }
                if (n == 0) log("lsp4ij " + name + " -> NO SERVER ASSOCIATION");
            }
        } catch (Throwable t) {
            log("lsp4ij block failed: " + t);
        }
        log("diag complete");
    }

    private static void log(String message) {
        // slf4j-free: use the same logger as the provider
        com.intellij.openapi.diagnostic.Logger.getInstance(WfmDiagStartup.class).warn("WFM-DIAG " + message);
    }
}
