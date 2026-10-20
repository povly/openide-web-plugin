package povly.wfm;

import com.intellij.formatting.Formatter;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import org.jetbrains.annotations.NotNull;

/**
 * No-op {@code lang.formatter} for the textmate language (v1.15.2).
 *
 * <p>Root-cause of "Reformat does nothing" in v1.15.0/1: {@code ReformatCodeAction.update()}
 * gates the action on {@code LanguageFormatting.INSTANCE.forContext(element) != null} — with no
 * registered builder the Ctrl+Alt+L action is disabled for textmate files and the action
 * {@link com.intellij.psi.codeStyle.CodeStyleManager#reformatText} path is never entered, so the
 * {@link WfmPrettierFormattingService} (which does the real work via Prettier) is never even
 * asked. Blade files stayed formatable only because the PHP plugin registers its own builder
 * for the Blade language, which re-enables the action — and our service then wins the
 * FormattingService selection.</p>
 *
 * <p>This builder returns the platform dummy model (a no-op): the dummy keeps the action enabled
 * AND is the safe fallback when the external tools are not resolvable. It replaces the retired
 * lexeme formatter ({@code WfmFormattingModelBuilder}, v1.10–v1.14) whose rich block model also
 * fed the startup {@code DetectAndAdjustIndentOptionsTask} — the dummy model gives the detector
 * nothing to analyze, so that drift source stays closed.</p>
 */
public class WfmNoopFormattingModelBuilder implements FormattingModelBuilder {

    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
        return Formatter.getInstance().createDummyFormattingModel(context.getContainingFile());
    }
}
