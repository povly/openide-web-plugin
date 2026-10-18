package povly.wfm;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.spellchecker.inspections.PlainTextSplitter;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.TokenConsumer;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import com.intellij.util.text.TextRangeUtil;
import org.jetbrains.plugins.textmate.psi.TextMateFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spell checking for TextMate-backed web files (css/scss/less/js/ts/vue/html).
 * <p>
 * Markup tag bodies ({@code <...>}) are excluded so tag/attribute names do not flood
 * the spellchecker; everything else is fed through the platform {@link PlainTextSplitter}
 * which handles camelCase/snake_case word splitting. Registered for language "textmate"
 * (the single language all bundle grammars resolve to, see WebFrontendBundleProvider).
 */
public class WfmSpellcheckingStrategy extends SpellcheckingStrategy {
    private static final Logger LOG = Logger.getInstance(WfmSpellcheckingStrategy.class);

    /** Matches markup tag bodies: <template>, <MyComponent :prop="x">, closing tags. */
    private static final Pattern TAG_BODY = Pattern.compile("<[^>]*>");

    private static final Tokenizer<TextMateFile> TEXTMATE_TOKENIZER = new Tokenizer<>() {
        @Override
        public void tokenize(TextMateFile file, TokenConsumer consumer) {
            String text = file.getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            List<TextRange> tagRanges = new ArrayList<>();
            Matcher m = TAG_BODY.matcher(text);
            while (m.find()) {
                tagRanges.add(TextRange.from(m.start(), m.end() - m.start()));
            }
            LOG.debug("spellcheck tokenize: file length=", text.length(),
                    ", tag ranges excluded=", tagRanges.size());
            for (TextRange range : TextRangeUtil.excludeRanges(TextRange.allOf(text), tagRanges)) {
                consumer.consumeToken(file, text, true, 0, range, PlainTextSplitter.getInstance());
            }
        }
    };

    @Override
    public boolean isMyContext(PsiElement element) {
        return element instanceof TextMateFile;
    }

    @Override
    public Tokenizer<?> getTokenizer(PsiElement element) {
        return TEXTMATE_TOKENIZER;
    }
}
