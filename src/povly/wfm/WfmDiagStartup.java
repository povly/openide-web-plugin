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
        log("diag complete");
    }

    private static void log(String message) {
        // slf4j-free: use the same logger as the provider
        com.intellij.openapi.diagnostic.Logger.getInstance(WfmDiagStartup.class).warn("WFM-DIAG " + message);
    }
}
