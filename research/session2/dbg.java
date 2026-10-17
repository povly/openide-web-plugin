import org.jetbrains.plugins.textmate.bundles.BundleReaderCoreKt;
import org.jetbrains.plugins.textmate.bundles.TextMateBundleReader;
import org.jetbrains.plugins.textmate.bundles.TextMateGrammar;
import org.jetbrains.plugins.textmate.bundles.TextMateNioResourceReader;
import org.jetbrains.plugins.textmate.language.TextMateInterner;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableBuilder;
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTableCore;
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer;
import org.jetbrains.plugins.textmate.plist.*;

import java.nio.file.*;

public class dbg {
    public static void main(String[] a) throws Exception {
        PlistReaderCore pr = new JsonOrXmlOrYamlPlistReader(new JsonPlistReader(), new XmlPlistReader(), new YamlPlistReader());
        TextMateBundleReader r = BundleReaderCoreKt.readTextMateBundle("x", pr, new TextMateNioResourceReader(Path.of(a[0])));
        TextMateInterner in2 = new TextMateInterner() { public String intern(String s){return s;} public void clear(){} };
        TextMateSyntaxTableBuilder b = new TextMateSyntaxTableBuilder(in2);
        var it = r.readGrammars().iterator();
        while (it.hasNext()) { TextMateGrammar g = it.next(); b.addSyntax((Plist)((kotlin.Lazy<?>)g.getPlist()).getValue()); }
        TextMateSyntaxTableCore t = b.build();
        TextMateHighlightingLexer lx = new TextMateHighlightingLexer(t.getLanguageDescriptor("text.html.vue"), 20000);
        String s = Files.readString(Path.of(a[1]));
        int st = 0, ls = 0, ln = 0;
        while (ls < s.length()) {
            int le = s.indexOf('\n', ls); if (le < 0) le = s.length(); le = Math.min(le+1, s.length());
            if (ln >= Integer.parseInt(a[2]) && ln <= Integer.parseInt(a[3])) {
                System.out.printf("== L%02d [%s]%n", ln, s.substring(ls, Math.min(le, ls+50)).replace("\n","\\n"));
                lx.start(s, ls, le, st);
                while (lx.getTokenType() != null) {
                    System.out.printf("   %-80s | %s%n", lx.getTokenType(), s.substring(lx.getTokenStart(), Math.min(lx.getTokenEnd(), lx.getTokenStart()+40)).replace("\n","\\n"));
                    lx.advance();
                }
            } else {
                lx.start(s, ls, le, st);
                while (lx.getTokenType() != null) lx.advance();
            }
            st = lx.getState();
            ls = le; ln++;
        }
    }
}
