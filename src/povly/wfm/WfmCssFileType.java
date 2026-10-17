package povly.wfm;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateBackedFileType;

import javax.swing.Icon;

/**
 * Explicit file type that claims the "css" extension for the TextMate web stack.
 *
 * Why it exists: the OpenIDE platform ships a bundled "CSS (syntax highlighting only)"
 * AbstractFileType (defaultFileTypes.xml, name="CSS", extensions="css") which would otherwise
 * win the extension and keep TextMate away from the file. Registering our own fileType bean
 * with the same extension overrides that claim; highlighting itself then comes from the
 * platform's TextMate support (grammar "source.css" is matched by the extension mapping).
 *
 * Pattern follows web-tools' CSSFileType:
 *  - LanguageFileType + TextMateBackedFileType marker (icon provider + textmate integration)
 *  - language is resolved as findLanguageByID("textmate") with a PlainText fallback, because
 *    fileType beans can instantiate before the textmate plugin registers its language
 *    (v1.0 of this plugin hit "Incorrect language ... should be textmate, actual null" when
 *    the language was resolved via the XML attribute instead)
 *  - isSecondary() = true so the real CSS language (if a future platform ships one) stays primary
 */
public final class WfmCssFileType extends LanguageFileType implements TextMateBackedFileType {

    public static final WfmCssFileType INSTANCE = new WfmCssFileType();

    private WfmCssFileType() {
        super(baseLanguage());
    }

    private static Language baseLanguage() {
        Language textMate = Language.findLanguageByID("textmate");
        return textMate != null ? textMate : PlainTextLanguage.INSTANCE;
    }

    @Override
    public @NotNull String getName() {
        return "WFM CSS";
    }

    @Override
    public @NotNull String getDescription() {
        return "CSS file (web-frontend TextMate)";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "CSS (web-frontend)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "css";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Css;
    }

    @Override
    public boolean isSecondary() {
        return true;
    }
}
