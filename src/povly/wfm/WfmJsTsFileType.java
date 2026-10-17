package povly.wfm;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateBackedFileType;

import javax.swing.Icon;

/**
 * Explicit file type that claims the JS/TS extension family for the TextMate web stack.
 *
 * Why it exists (two reasons):
 *  1. "js" is claimed by the platform's bundled "JavaScript (syntax highlighting only)"
 *     AbstractFileType (defaultFileTypes.xml) — must be overridden for TextMate to reach the file.
 *  2. "ts", "tsx", "jsx", "mjs", "cjs" are not claimed by anything, but the shiki-sourced
 *     javascript/typescript grammars declare no "fileTypes" of their own, so without an explicit
 *     file type the extension-mapping lookup finds no grammar either. Both problems are solved
 *     by this bean + the fileTypes entries patched into the grammars (see bundle/Syntaxes).
 *
 * Same TextMateBackedFileType pattern as WfmCssFileType (see its javadoc for the S7 timing story).
 */
public final class WfmJsTsFileType extends LanguageFileType implements TextMateBackedFileType {

    public static final WfmJsTsFileType INSTANCE = new WfmJsTsFileType();

    private WfmJsTsFileType() {
        super(baseLanguage());
    }

    private static Language baseLanguage() {
        Language textMate = Language.findLanguageByID("textmate");
        return textMate != null ? textMate : PlainTextLanguage.INSTANCE;
    }

    @Override
    public @NotNull String getName() {
        return "WFM JavaScript";
    }

    @Override
    public @NotNull String getDescription() {
        return "JavaScript/TypeScript file (web-frontend TextMate)";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "JavaScript/TypeScript (web-frontend)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "js";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.JavaScript;
    }

    @Override
    public boolean isSecondary() {
        return true;
    }
}
