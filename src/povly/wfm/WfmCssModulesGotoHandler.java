package povly.wfm;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ctrl+B / Ctrl+click navigation for CSS-module members in .vue templates:
 * {@code $style.foo}, {@code $named['foo-bar']} and members of identifiers imported from
 * {@code *.module.css}. Jumps to the class definition — the identifier right after the
 * selector's dot — inside the inline {@code <style module>} block or the external module
 * file.
 *
 * Same layering as the v1.9.1 {@link WfmVueResourceGoToHandler}: regex over the document
 * line, no PSI references (the TextMate layer has none); parsing lives in the tested
 * {@link WfmCssModules}; external files resolve relative to the SFC directory via
 * {@link VirtualFile#findFileByRelativePath}. Returns {@code null} on "not my case" so the
 * v1.9.1 handler and LSP providers keep their turn.
 */
public final class WfmCssModulesGotoHandler implements GotoDeclarationHandler {

    private static final Logger LOG = Logger.getInstance(WfmCssModulesGotoHandler.class);

    /** Member access on a module-ish reference: $style.foo / styles.foo; group 1 = ref ($ optional), group 2 = member. */
    private static final Pattern DOT_MEMBER = Pattern.compile("(\\$?[A-Za-z_][\\w$]*)\\.([\\w-]+)");

    /** Bracket member access: $style['foo-bar'] / styles['x']; group 1 = ref, group 2 = member. */
    private static final Pattern BRACKET_MEMBER = Pattern.compile("(\\$?[A-Za-z_][\\w$]*)\\['([\\w-]+)'");

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) return null;
        PsiFile file = sourceElement.getContainingFile();
        if (file == null || !file.getName().endsWith(".vue")) return null;

        String fullText;
        String lineText;
        int lineStart;
        try {
            Document document = editor.getDocument();
            fullText = document.getText();
            int line = document.getLineNumber(offset);
            lineStart = document.getLineStartOffset(line);
            lineText = fullText.substring(lineStart, document.getLineEndOffset(line));
        } catch (Exception e) {
            return null;
        }

        MemberRef ref = memberAt(lineText, offset - lineStart);
        if (ref == null) return null;

        PsiElement target = resolveTarget(file, fullText, ref);
        if (target == null) return null;
        LOG.debug("wfm: css-modules goto module=", ref.module(), " member=", ref.member());
        return new PsiElement[]{target};
    }

    /** The module-member token under the caret on this line, or null when the caret sits elsewhere. */
    private static MemberRef memberAt(String lineText, int caretInLine) {
        for (Pattern pattern : new Pattern[]{DOT_MEMBER, BRACKET_MEMBER}) {
            Matcher m = pattern.matcher(lineText);
            while (m.find()) {
                if (caretInLine < m.start() || caretInLine > m.end()) continue;
                String raw = m.group(1);
                boolean dollar = raw.startsWith("$");
                return new MemberRef(dollar, dollar ? raw.substring(1) : raw, m.group(2));
            }
        }
        return null;
    }

    /**
     * {@code $}-references resolve against inline and external style modules; plain
     * identifiers only against script imports ({@code obj.prop} of unknown objects
     * resolves to nothing and stays transparent for other handlers).
     */
    private static PsiElement resolveTarget(PsiFile file, String text, MemberRef ref) {
        if (ref.moduleSyntax()) {
            for (WfmCssModules.StyleModule m : WfmCssModules.inlineModules(text)) {
                if (m.moduleName().equals(ref.module())) {
                    PsiElement at = elementAtClass(file, m.css(), m.cssStartOffset(), ref.member());
                    if (at != null) return at;
                }
            }
            for (WfmCssModules.ExternalModule m : WfmCssModules.externalModules(text)) {
                if (m.moduleName().equals(ref.module())) {
                    PsiFile target = resolveModuleFile(file, m.path());
                    if (target == null) continue;
                    PsiElement at = elementAtClass(target, target.getText(), 0, ref.member());
                    if (at != null) return at;
                }
            }
            return null;
        }
        for (WfmCssModules.ModuleImport imp : WfmCssModules.moduleImports(text)) {
            if (imp.identifier().equals(ref.module())) {
                PsiFile target = resolveModuleFile(file, imp.path());
                if (target == null) continue;
                PsiElement at = elementAtClass(target, target.getText(), 0, ref.member());
                if (at != null) return at;
            }
        }
        return null;
    }

    /** PSI element of the class identifier (selector dot + 1), matching by original or camel name. */
    private static PsiElement elementAtClass(PsiFile psiFile, String css, int baseOffset, String member) {
        for (WfmCssModules.CssClass c : WfmCssModules.extractClasses(css)) {
            if (c.name().equals(member) || c.camel().equals(member)) {
                return psiFile.findElementAt(baseOffset + c.offset() + 1);
            }
        }
        return null;
    }

    /** External module file as PSI, resolved relative to the SFC directory (soft refusal). */
    private static PsiFile resolveModuleFile(PsiFile file, String path) {
        VirtualFile dir = file.getVirtualFile() != null ? file.getVirtualFile().getParent() : null;
        VirtualFile target = dir == null ? null : dir.findFileByRelativePath(path);
        if (target == null || !target.isValid()) return null;
        return PsiManager.getInstance(file.getProject()).findFile(target);
    }

    /** A module member occurrence: moduleSyntax = written as $ref (vs a plain import identifier). */
    private record MemberRef(boolean moduleSyntax, String module, String member) {}

    @Override
    public @Nullable String getActionText(DataContext context) {
        return null;
    }
}
