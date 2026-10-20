package povly.wfm;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * CSS-modules class completion inside .vue templates: {@code :class="$style.|"} (default
 * module), {@code :class="$named.|"} ({@code <style module="named">}) and
 * {@code importedStyles.|} for identifiers bound by {@code import x from './a.module.css'}.
 *
 * Class lists come from the inline {@code <style module>} blocks of the same SFC and from
 * external module files (resolved relative to the SFC directory, same technique as
 * {@link WfmVueResourceGoToHandler}). All parsing lives in the platform-free
 * {@link WfmCssModules} (unit-tested by tests/povly/wfm); this contributor stays a thin
 * editor adapter, following the regex-over-document pattern of the v1.9.1 goto handler.
 *
 * Coexistence with Volar: LSP4IJ merges platform and LSP completions into one lookup and
 * deduplicates by lookup string, so both sources appear without duplicates. Kebab-case
 * names mirror Volar's typing ({@code $style} keys are string literals): picking a kebab
 * class converts dot access into bracket notation {@code $style['foo-bar']}; the camelCase
 * alias (when it differs) inserts as a bare identifier.
 */
public final class WfmCssModulesCompletionContributor extends CompletionContributor {

    private static final Logger LOG = Logger.getInstance(WfmCssModulesCompletionContributor.class);

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        if (file == null || !file.getName().endsWith(".vue")) return;

        Document document = parameters.getEditor().getDocument();
        int offset = parameters.getOffset();
        if (offset <= 0 || offset > document.getTextLength()) return;

        String before = document.getCharsSequence().subSequence(0, offset).toString();
        WfmCssModules.Trigger trigger = WfmCssModules.triggerAt(before);
        if (trigger == null) return; // caret is not in a module-member position

        List<WfmCssModules.CssClass> classes = ReadAction.compute(() -> resolveClasses(file, trigger.module()));
        if (classes.isEmpty()) return;

        LOG.debug("wfm: css-modules completion file=", file.getName(),
                " module=", trigger.module(), " classes=", classes.size());

        String label = "$" + trigger.module();
        for (WfmCssModules.CssClass c : classes) {
            // original name; kebab names need bracket notation when typed after a dot
            result.addElement(LookupElementBuilder.create(c.name())
                    .withLookupString(c.camel())
                    .withTypeText(label, true)
                    .withInsertHandler(c.name().indexOf('-') >= 0 && !trigger.bracket()
                            ? new BracketAccessHandler(c.name())
                            : null));
            // camelCase alias when it differs: a valid bare identifier
            if (!c.camel().equals(c.name())) {
                result.addElement(LookupElementBuilder.create(c.camel())
                        .withLookupString(c.name())
                        .withTypeText(label, true));
            }
        }
    }

    /** Classes of the referenced module: inline block → external src → script import (first match wins). */
    private static List<WfmCssModules.CssClass> resolveClasses(PsiFile file, String module) {
        String text = file.getText();
        for (WfmCssModules.StyleModule m : WfmCssModules.inlineModules(text)) {
            if (m.moduleName().equals(module)) return WfmCssModules.extractClasses(m.css());
        }
        for (WfmCssModules.ExternalModule m : WfmCssModules.externalModules(text)) {
            if (m.moduleName().equals(module)) return readModuleFile(file, m.path());
        }
        for (WfmCssModules.ModuleImport imp : WfmCssModules.moduleImports(text)) {
            if (imp.identifier().equals(module)) return readModuleFile(file, imp.path());
        }
        return List.of();
    }

    /** Reads and parses an external module file resolved relative to the SFC directory (soft refusal). */
    private static List<WfmCssModules.CssClass> readModuleFile(PsiFile file, String path) {
        VirtualFile dir = file.getVirtualFile() != null ? file.getVirtualFile().getParent() : null;
        VirtualFile target = dir == null ? null : dir.findFileByRelativePath(path);
        if (target == null || !target.isValid()) {
            LOG.debug("wfm: css-modules module file not found path=", path);
            return List.of();
        }
        try {
            return WfmCssModules.extractClasses(VfsUtil.loadText(target));
        } catch (IOException e) {
            LOG.debug("wfm: css-modules module file unreadable path=", path, " error=", e.getMessage());
            return List.of();
        }
    }

    /**
     * Kebab names are not valid bare identifiers: on insert, {@code $style.foo|} picking
     * {@code foo-bar} becomes {@code $style['foo-bar']} (Volar types $style keys as string
     * literals — same behavior). Inside existing brackets ({@code $style['…}) no conversion
     * is needed and this handler is not attached.
     */
    private static final class BracketAccessHandler implements InsertHandler<LookupElement> {
        private final String name;

        BracketAccessHandler(String name) {
            this.name = name;
        }

        @Override
        public void handleInsert(InsertionContext context, LookupElement item) {
            int start = context.getStartOffset();
            Document document = context.getDocument();
            if (start > 0 && document.getCharsSequence().charAt(start - 1) == '.') {
                document.replaceString(start - 1, context.getTailOffset(), "['" + name + "']");
            }
        }
    }
}
