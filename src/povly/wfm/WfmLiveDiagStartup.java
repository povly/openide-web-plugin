package povly.wfm;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;

/**
 * WFM-DIAG2 (temporary, remove after the vue-script highlighting fix is confirmed):
 * measures the LIVE editor highlighter of open web files — how many tokens the real
 * editor instance produced inside the first &lt;script&gt; region and how many of them
 * carry an embedded source.* scope. Logged at WARN ("WFM-DIAG2") at +10s and +40s.
 */
public final class WfmLiveDiagStartup implements StartupActivity {
    private static final Logger LOG = Logger.getInstance(WfmLiveDiagStartup.class);

    @Override
    public void runActivity(@NotNull Project project) {
        schedule(project, 10_000);
        schedule(project, 40_000);
    }

    private void schedule(Project project, int delayMs) {
        ApplicationManager.getApplication().invokeLater(
                () -> ApplicationManager.getApplication().runReadAction(() -> inspect(project)));
    }

    private void inspect(Project project) {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        for (FileEditor fe : fem.getSelectedEditors()) {
            if (!(fe instanceof TextEditor)) continue;
            Editor editor = ((TextEditor) fe).getEditor();
            if (!(editor instanceof EditorEx)) continue;
            VirtualFile file = fe.getFile();
            String name = file == null ? "?" : file.getName();
            if (!name.endsWith(".vue") && !name.endsWith(".ts") && !name.endsWith(".css")) continue;
            try {
                EditorHighlighter h = ((EditorEx) editor).getHighlighter();
                Document doc = ((EditorEx) editor).getDocument();
                String text = doc.getText();
                int scriptStart = text.indexOf("<script");
                int scriptEnd = text.indexOf("</script");
                HighlighterIterator it = h.createIterator(0);
                int total = 0, inScript = 0, scriptSourceScopes = 0;
                Map<String, Integer> top = new TreeMap<>();
                while (!it.atEnd()) {
                    int s = it.getStart();
                    String scope = String.valueOf(it.getTokenType());
                    total++;
                    top.merge(scope.split(" ")[0], 1, Integer::sum);
                    if (scriptStart >= 0 && s >= scriptStart && (scriptEnd < 0 || s < scriptEnd)) {
                        inScript++;
                        if (scope.contains("source.")) scriptSourceScopes++;
                    }
                    it.advance();
                }
                LOG.warn("WFM-DIAG2 " + name + " highlighter=" + h.getClass().getSimpleName()
                        + " tokens=" + total + " inScript=" + inScript
                        + " scriptSourceScopes=" + scriptSourceScopes + " roots=" + top);
            } catch (Exception ex) {
                LOG.warn("WFM-DIAG2 " + name + " failed: " + ex);
            }
        }
    }
}
