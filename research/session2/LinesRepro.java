import org.jetbrains.plugins.textmate.bundles.BundleReaderCoreKt;
import org.jetbrains.plugins.textmate.bundles.TextMateBundleReader;
import org.jetbrains.plugins.textmate.bundles.TextMateGrammar;
import org.jetbrains.plugins.textmate.bundles.TextMateNioResourceReader;
import org.jetbrains.plugins.textmate.language.TextMateInterner;
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableBuilder;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableCore;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer;
import org.jetbrains.plugins.textmate.plist.JsonOrXmlOrYamlPlistReader;
import org.jetbrains.plugins.textmate.plist.JsonPlistReader;
import org.jetbrains.plugins.textmate.plist.Plist;
import org.jetbrains.plugins.textmate.plist.PlistReaderCore;
import org.jetbrains.plugins.textmate.plist.XmlPlistReader;
import org.jetbrains.plugins.textmate.plist.YamlPlistReader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Mimic the editor: re-lex the file line by line, carrying lexer state between lines. */
public class LinesRepro {
    public static void main(String[] args) throws Exception {
        Path bundleDir = Path.of(args[0]);
        String sample = Files.readString(Path.of(args[1]));
        String targetScope = args.length > 2 ? args[2] : "text.html.vue";

        PlistReaderCore plistReader = new JsonOrXmlOrYamlPlistReader(new JsonPlistReader(), new XmlPlistReader(), new YamlPlistReader());
        TextMateBundleReader reader = BundleReaderCoreKt.readTextMateBundle(
                "wfm-repro", plistReader, new TextMateNioResourceReader(bundleDir));

        TextMateInterner interner = new TextMateInterner() {
            @Override public String intern(String s) { return s; }
            @Override public void clear() { }
        };
        TextMateSyntaxTableBuilder builder = new TextMateSyntaxTableBuilder(interner);
        var it = reader.readGrammars().iterator();
        while (it.hasNext()) {
            TextMateGrammar g = it.next();
            Plist plist = (Plist) ((kotlin.Lazy<?>) g.getPlist()).getValue();
            builder.addSyntax(plist);
        }
        TextMateSyntaxTableCore table = builder.build();
        TextMateLanguageDescriptor desc = table.getLanguageDescriptor(targetScope);

        TextMateHighlightingLexer lexer = new TextMateHighlightingLexer(desc, 10000);

        int state = 0;
        int lineStart = 0;
        int lineNo = 0;
        int coloredTokens = 0, totalTokens = 0;
        java.util.Set<String> scriptScopes = new java.util.TreeSet<>();
        while (lineStart < sample.length()) {
            int lineEnd = sample.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = sample.length();
            lineEnd = Math.min(lineEnd + 1, sample.length()); // include \n
            lexer.start(sample, lineStart, lineEnd, state);
            StringBuilder sb = new StringBuilder();
            while (lexer.getTokenType() != null) {
                String scope = String.valueOf(lexer.getTokenType());
                String text = sample.substring(lexer.getTokenStart(), Math.min(lexer.getTokenEnd(), lexer.getTokenStart() + 30))
                        .replace("\n", "\\n");
                totalTokens++;
                if (!scope.equals(targetScope)) { coloredTokens++; }
                if (scope.contains("source.")) {
                    scriptScopes.add(scope);
                    sb.append(String.format("    %-75s | %s%n", scope, text));
                }
                lexer.advance();
            }
            if (sb.length() > 0) {
                System.out.printf("L%02d [state %d]:%n%s", lineNo, state, sb);
            }
            state = lexer.getState();
            lineStart = lineEnd;
            lineNo++;
        }
        System.out.println("lines=" + lineNo + " tokens=" + totalTokens + " non-root=" + coloredTokens);
        System.out.println("distinct source.* scopes in script regions: " + scriptScopes.size());
    }
}
