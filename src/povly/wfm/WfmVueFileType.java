package povly.wfm;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateBackedFileType;

import javax.swing.Icon;

/**
 * Explicit file type that claims the "vue" extension for the TextMate web stack.
 *
 * Rationale: without an explicit claim, .vue relies on the textmate plugin's
 * TextMateFileDetector (order="last"). In this OpenIDE build detector-typed files
 * showed degraded editor highlighting for multi-line contexts (script/style
 * interiors), while extensions claimed by explicit LanguageFileType beans
 * (css/js/ts via WfmCssFileType/WfmJsTsFileType) highlighted correctly.
 * Claiming the extension explicitly puts .vue on the same code path.
 *
 * Also gives .vue files a proper name/icon in the UI and a stable file type for
 * LSP mappings, instead of the generic "textmate" detector type.
 *
 * Same TextMateBackedFileType pattern as WfmCssFileType (see its javadoc).
 */
public final class WfmVueFileType extends LanguageFileType implements TextMateBackedFileType {

    public static final WfmVueFileType INSTANCE = new WfmVueFileType();

    private WfmVueFileType() {
        super(baseLanguage());
    }

    private static Language baseLanguage() {
        Language textMate = Language.findLanguageByID("textmate");
        return textMate != null ? textMate : PlainTextLanguage.INSTANCE;
    }

    @Override
    public @NotNull String getName() {
        return "WFM Vue";
    }

    @Override
    public @NotNull String getDescription() {
        return "Vue single-file component (web-frontend TextMate)";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Vue (web-frontend)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "vue";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Html; // closest platform icon; no custom svg shipped yet
    }

    @Override
    public boolean isSecondary() {
        return true;
    }
}
