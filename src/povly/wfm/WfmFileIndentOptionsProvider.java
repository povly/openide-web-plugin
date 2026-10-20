package povly.wfm;

import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.FileIndentOptionsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Deterministic indent options for wfm files — v1.14.0 root-cause of «каждое нажатие Reformat
 * добавляет отступ». {@code CodeFormatterFacade.getIndentOptions} → {@code getIndentOptionsByFile}
 * without a covering provider runs the indent-options AUTO-DETECTOR; our project files mix tabs
 * and spaces (half-reformatted), so every Ctrl+Alt+L detected different USE_TAB/INDENT_SIZE and
 * regenerated the indentation differently — the text drifted on each press (reproduced in the
 * engine lab: {@code tests/povly/wfm/WfmFormatEngineTest} "options-drift" — GROWING; with static
 * options the engine + block model are fully idempotent).
 *
 * <p>This provider pins the options to the shared TextMate Code Style profile
 * (Settings → Editor → Code Style → TextMate; defaults 2 spaces, no tabs — one profile for all
 * wfm file types, the singleton-language platform limitation, see {@link WfmCodeStyleSettingsProvider}).
 * User-configurable but STABLE between runs → Reformat converges. A copy is returned because the
 * platform marks the result with {@code setFileIndentOptionsProvider} (mutates it).</p>
 */
public class WfmFileIndentOptionsProvider extends FileIndentOptionsProvider {

    @Override
    public @Nullable CommonCodeStyleSettings.IndentOptions getIndentOptions(@NotNull CodeStyleSettings settings,
                                                                             @NotNull PsiFile file) {
        boolean wfmOwned = file.getFileType() instanceof WfmCssFileType
                || file.getFileType() instanceof WfmJsTsFileType
                || file.getFileType() instanceof WfmVueFileType;
        // Blade files are matched by the formatter through the Blade language (file type is owned
        // by the PHP for OpenIDE plugin), same drift applies — pin them to the same profile.
        boolean blade = "Blade".equals(file.getLanguage().getID());
        if (!wfmOwned && !blade) {
            return null;
        }

        CommonCodeStyleSettings.IndentOptions source = settings.getIndentOptions(file.getFileType());
        CommonCodeStyleSettings.IndentOptions copy = new CommonCodeStyleSettings.IndentOptions();
        copy.copyFrom(source);
        return copy;
    }

    @Override
    public boolean useOnFullReformat() {
        return true;
    }
}
