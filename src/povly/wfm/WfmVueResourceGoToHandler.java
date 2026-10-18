package povly.wfm;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ctrl+B / Ctrl+click navigation for external resource paths inside .vue single-file
 * components: {@code <style module src="./tabbar.css">}, {@code <script src="./x.ts">}.
 * <p>
 * Neither Volar (provides no documentLink/definition for these attributes — verified by
 * probe, research/session4) nor the TextMate layer (no PSI references) resolves such
 * paths, so this handler closes the gap: the line under the caret is matched for
 * {@code src="..."} values on a {@code <style>}/{@code <script>} tag line; relative
 * paths resolve against the containing file's directory.
 */
public final class WfmVueResourceGoToHandler implements GotoDeclarationHandler {
    /** src (or source) attribute value in double or single quotes; group 1 = quoted, 2/3 = inner. */
    private static final Pattern SRC_ATTR = Pattern.compile("(?:src|source)\\s*=\\s*(\"([^\"]+)\"|'([^']+)')");
    /** The tag this attribute must belong to (same line). */
    private static final Pattern TAG_LINE = Pattern.compile("<(style|script)\\b");

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) return null;
        PsiFile file = sourceElement.getContainingFile();
        if (file == null || !file.getName().endsWith(".vue")) return null;

        Document document = editor.getDocument();
        String lineText;
        int lineStart;
        try {
            int line = document.getLineNumber(offset);
            lineStart = document.getLineStartOffset(line);
            lineText = document.getText().substring(lineStart, document.getLineEndOffset(line));
        } catch (Exception e) {
            return null;
        }
        if (!TAG_LINE.matcher(lineText).find()) return null;

        List<PsiElement> targets = new ArrayList<>();
        Matcher m = SRC_ATTR.matcher(lineText);
        while (m.find()) {
            String path = m.group(2) != null ? m.group(2) : m.group(3);
            if (path == null || !isRelativePath(path)) continue;
            int valueStart = lineStart + m.start(1) + 1; // skip the opening quote
            int valueEnd = lineStart + m.end(1) - 1;     // stay before the closing quote
            if (offset < valueStart || offset > valueEnd) continue;

            VirtualFile dir = file.getVirtualFile() != null ? file.getVirtualFile().getParent() : null;
            VirtualFile target = dir == null ? null : dir.findFileByRelativePath(path);
            if (target == null || !target.isValid()) continue;
            PsiFile targetPsi = PsiManager.getInstance(file.getProject()).findFile(target);
            if (targetPsi != null) targets.add(targetPsi);
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement[]::new);
    }

    /** Relative-looking only: no absolute, alias (@/…), protocol, anchor or template paths. */
    private static boolean isRelativePath(String path) {
        if (path.isEmpty() || path.length() > 512) return false;
        char c = path.charAt(0);
        return c != '/' && c != '@' && c != '#' && c != '{' && !path.contains("://");
    }

    @Override
    public @Nullable String getActionText(DataContext context) {
        return null;
    }
}
