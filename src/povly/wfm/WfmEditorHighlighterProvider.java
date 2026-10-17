package povly.wfm;

import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;

import com.intellij.openapi.fileTypes.EditorHighlighterProvider;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.textmate.TextMateService;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateHighlighter;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateLexerDataStorage;

/**
 * Editor highlighter for the web-frontend TextMate file types (WFM Vue / WFM JavaScript / WFM CSS).
 *
 * Mirrors the vendor web-tools plugin's JsEditorHighlighterProvider: a plain
 * LexerEditorHighlighter whose storage is the textmate TextMateLexerDataStorage
 * (packs the many TextMateElementType instances into shorts), wrapping a
 * TextMateHighlighter built from the grammar matched by file name.
 *
 * Registered per file type name; falls back to the plain text highlighter when
 * no grammar matches the file (same as upstream textmate does).
 */
public final class WfmEditorHighlighterProvider implements EditorHighlighterProvider {

    @Override
    public EditorHighlighter getEditorHighlighter(Project project,
                                                   FileType fileType,
                                                   VirtualFile virtualFile,
                                                   EditorColorsScheme colors) {
        return new WfmLexerEditorHighlighter(createHighlighter(virtualFile), colors);
    }

    private static TextMateHighlighter createHighlighter(@Nullable VirtualFile virtualFile) {
        if (virtualFile != null) {
            TextMateLanguageDescriptor descriptor =
                    TextMateService.getInstance().getLanguageDescriptorByFileName(virtualFile.getName());
            if (descriptor != null) {
                return new TextMateHighlighter(
                        new org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer(
                                descriptor, WfmHighlighterStack.MATCHER, WfmHighlighterStack.LINE_LIMIT));
            }
        }
        return new TextMateHighlighter(null);
    }

    private static final class WfmLexerEditorHighlighter extends LexerEditorHighlighter {
        WfmLexerEditorHighlighter(TextMateHighlighter highlighter, EditorColorsScheme scheme) {
            super(highlighter, scheme);
        }

        @Override
        protected com.intellij.openapi.editor.ex.util.DataStorage createStorage() {
            return new TextMateLexerDataStorage();
        }
    }
}
