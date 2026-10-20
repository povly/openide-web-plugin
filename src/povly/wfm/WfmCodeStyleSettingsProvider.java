package povly.wfm;

import com.intellij.lang.Language;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.plugins.textmate.TextMateLanguage;

/**
 * Settings → Editor → Code Style → TextMate page (Tabs and Indents) for the shared
 * "textmate" language of all wfm grammars.
 *
 * Root-cause: the platform textmate plugin registers no LanguageCodeStyleSettingsProvider
 * (intellij.textmate.xml), so the language had no Code Style node at all — the formatter
 * ({@link WfmFormattingModelBuilder}) and the platform TextMateLineIndentProvider then
 * silently fall back to IDE-wide defaults. Registering this provider exposes the standard
 * Tabs and Indents UI so both consumers pick up per-language settings.
 *
 * Platform limitation (accepted for v1): TextMateLanguage is ONE singleton for every
 * grammar (css/scss/less/js/ts/vue), so a single profile is shared by all web file types.
 */
public final class WfmCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    private static final Logger LOG = Logger.getInstance(WfmCodeStyleSettingsProvider.class);

    @Override
    public Language getLanguage() {
        return TextMateLanguage.LANGUAGE;
    }

    /** Preview file extension: css — the most representative of the shared textmate profile. */
    @Override
    public String getFileExt() {
        return "css";
    }

    /** Web-stack convention: 2 spaces, no tabs (the IDE-wide default is 4). */
    @Override
    protected void customizeDefaults(CommonCodeStyleSettings common, CommonCodeStyleSettings.IndentOptions indent) {
        indent.INDENT_SIZE = 2;
        indent.CONTINUATION_INDENT_SIZE = 2;
        indent.TAB_SIZE = 2;
        indent.USE_TAB_CHARACTER = false;
        LOG.debug("wfm: code-style defaults indent=", indent.INDENT_SIZE, " tab=", indent.TAB_SIZE);
    }

    @Override
    public String getCodeSample(SettingsType settingsType) {
        return """
                .card {
                  display: flex;
                  padding: 8px;
                }

                .card .title {
                  font-weight: bold;
                }
                """;
    }
}
