import org.jetbrains.plugins.textmate.bundles.BundleReaderCoreKt;
import org.jetbrains.plugins.textmate.bundles.TextMateBundleReader;
import org.jetbrains.plugins.textmate.bundles.TextMateGrammar;
import org.jetbrains.plugins.textmate.bundles.TextMateNioResourceReader;
import org.jetbrains.plugins.textmate.language.TextMateInterner;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableBuilder;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableCore;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateCachingSyntaxMatcher;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcherImpl;
import org.jetbrains.plugins.textmate.regex.RegexFactory;
import com.intellij.textmate.joni.JoniRegexFactory;
import org.jetbrains.plugins.textmate.plist.JsonOrXmlOrYamlPlistReader;
import org.jetbrains.plugins.textmate.plist.Plist;
import org.jetbrains.plugins.textmate.plist.PlistReaderCore;
import org.jetbrains.plugins.textmate.plist.XmlPlistReader;
import org.jetbrains.plugins.textmate.plist.YamlPlistReader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone repro: load our TextMate bundle with the very same engine the IDE uses, lex a sample, dump scopes. */
public class Repro {
    public static void main(String[] args) throws Exception {
        Path bundleDir = Path.of(args[0]);
        String sample = Files.readString(Path.of(args[1]));
        String targetScope = args.length > 2 ? args[2] : "text.html.vue";

        PlistReaderCore plistReader = new JsonOrXmlOrYamlPlistReader(new org.jetbrains.plugins.textmate.plist.JsonPlistReader(), new XmlPlistReader(), new YamlPlistReader());
        TextMateBundleReader reader = BundleReaderCoreKt.readTextMateBundle(
                "wfm-repro", plistReader, new TextMateNioResourceReader(bundleDir));

        TextMateInterner interner = new TextMateInterner() {
            @Override public String intern(String s) { return s; }
            @Override public void clear() { }
        };
        TextMateSyntaxTableBuilder builder = new TextMateSyntaxTableBuilder(interner);
        var seq = reader.readGrammars();
        var it = seq.iterator();
        while (it.hasNext()) {
            TextMateGrammar g = it.next();
            Plist plist = (Plist) ((kotlin.Lazy<?>) g.getPlist()).getValue();
            Object registered = builder.addSyntax(plist);
            System.out.println("loaded grammar -> registered as: " + registered
                    + " | matchers: " + g.getFileNameMatchers());
        }
        TextMateSyntaxTableCore table = builder.build();

        for (String probe : new String[]{"text.html.vue", "source.css", "source.js", "source.ts"}) {
            System.out.println("descriptor[" + probe + "] = " + table.getLanguageDescriptor(probe));
        }

        TextMateLanguageDescriptor desc = table.getLanguageDescriptor(targetScope);
        if (desc == null) { System.out.println("NO DESCRIPTOR for " + targetScope); return; }

        org.jetbrains.plugins.textmate.regex.RegexProvider regexProvider = new org.jetbrains.plugins.textmate.regex.CaffeineCachingRegexProvider(
                new org.jetbrains.plugins.textmate.regex.RememberingLastMatchRegexFactory(new com.intellij.textmate.joni.JoniRegexFactory()));
        org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigher weigher =
                org.jetbrains.plugins.textmate.language.syntax.selector.TextMateCachingSelectorWeigherKt.caching(
                        new org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigherImpl());
        org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcher matcher =
                new org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateCachingSyntaxMatcher(
                        new org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcherImpl(regexProvider, weigher));
        TextMateHighlightingLexer lexer = new TextMateHighlightingLexer(desc, matcher, 20000);
        lexer.start(sample, 0, sample.length(), 0);
        int n = 0;
        StringBuilder prev = new StringBuilder();
        while (lexer.getTokenType() != null && n < 4000) {
            String scope = String.valueOf(lexer.getTokenType());
            String text = sample.substring(lexer.getTokenStart(), Math.min(lexer.getTokenEnd(), lexer.getTokenStart() + 48))
                    .replace("\n", "\\n");
            System.out.printf("%04d %-70s | %s%n", n, scope, text);
            lexer.advance();
            n++;
        }
        System.out.println("tokens: " + n);
    }
}
