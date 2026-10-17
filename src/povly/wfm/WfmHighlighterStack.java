package povly.wfm;

import com.intellij.textmate.joni.JoniRegexFactory;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateCachingSyntaxMatcher;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcher;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcherImpl;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateCachingSelectorWeigher;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigher;
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigherImpl;
import org.jetbrains.plugins.textmate.regex.CaffeineCachingRegexProvider;
import org.jetbrains.plugins.textmate.regex.RegexProvider;
import org.jetbrains.plugins.textmate.regex.RememberingLastMatchRegexFactory;

/**
 * The exact regex/matcher stack the textmate SyntaxHighlighterFactory builds in-product
 * (TextMateSyntaxHighlighterFactory$Inner). Kept in one place so the editor highlighter
 * and the pass-based highlighter behave identically.
 */
final class WfmHighlighterStack {

    /** textmate.line.highlighting.limit registry default (20000). */
    static final int LINE_LIMIT = 20000;

    static final TextMateSyntaxMatcher MATCHER = createMatcher();

    private WfmHighlighterStack() {
    }

    private static TextMateSyntaxMatcher createMatcher() {
        RegexProvider regexProvider = new CaffeineCachingRegexProvider(
                new RememberingLastMatchRegexFactory(new JoniRegexFactory()));
        TextMateSelectorWeigher weigher =
                TextMateCachingSelectorWeigherKtHolder.caching(new TextMateSelectorWeigherImpl());
        return new TextMateCachingSyntaxMatcher(new TextMateSyntaxMatcherImpl(regexProvider, weigher));
    }

    /** Load-holder: avoids class-init order issues with the Kotlin caching helper. */
    private static final class TextMateCachingSelectorWeigherKtHolder {
        static TextMateCachingSelectorWeigher caching(TextMateSelectorWeigher weigher) {
            return org.jetbrains.plugins.textmate.language.syntax.selector.TextMateCachingSelectorWeigherKt.caching(weigher);
        }
    }
}
