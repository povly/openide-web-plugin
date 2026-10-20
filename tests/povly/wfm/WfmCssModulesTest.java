package povly.wfm;

import java.util.List;

/**
 * Plain-java test harness for {@link WfmCssModules} — no JUnit and no platform classpath,
 * so it runs under bare {@code javac}/{@code java} via the {@code ./build.sh test} target.
 * Every case prints PASS/FAIL; the process exits non-zero when any case fails.
 */
public final class WfmCssModulesTest {
    private static int failed = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testExtractClasses();
        testCamel();
        testStyleModules();
        testModuleImports();
        testTriggerAt();

        System.out.println();
        System.out.println("Summary: " + (total - failed) + "/" + total + " passed"
                + (failed == 0 ? " — ALL PASS" : ", " + failed + " FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void testExtractClasses() {
        eq("classes simple", "a@0", classes(WfmCssModules.extractClasses(".a {}")));
        eq("classes comma list", "a@0 b@4", classes(WfmCssModules.extractClasses(".a, .b { }")));
        eq("classes pseudo", "a@0", classes(WfmCssModules.extractClasses(".a:hover {}")));
        String descendant = ".a > .b span {}";
        eq("classes descendant", "a@0 b@" + descendant.indexOf(".b"),
                classes(WfmCssModules.extractClasses(descendant)));
        eq("classes nested scss", "a@0 b@5", classes(WfmCssModules.extractClasses(".a { .b { color: red } }")));
        eq("classes ampersand parent", "foo@1", classes(WfmCssModules.extractClasses("&.foo { }")));
        eq("classes global only", "", classes(WfmCssModules.extractClasses(":global(.g) {}")));
        eq("classes global sibling", "a@0", classes(WfmCssModules.extractClasses(".a :global(.g) {}")));

        String media = "@media (min-width: 100px) { .a {} }";
        eq("classes inside media", "a@" + media.indexOf(".a"), classes(WfmCssModules.extractClasses(media)));
        eq("classes import statement", "", classes(WfmCssModules.extractClasses("@import url(\"x.css\");")));

        String mixins = ".mixin(@x) {} .call(); .real {}";
        eq("classes mixin excluded", "real@" + mixins.indexOf(".real"), classes(WfmCssModules.extractClasses(mixins)));

        String commented = "/* .fake */ .real {}";
        List<WfmCssModules.CssClass> commentedClasses = WfmCssModules.extractClasses(commented);
        eq("classes comment excluded", "real@" + commented.indexOf(".real"), classes(commentedClasses));
        check("classes comment offset at real dot",
                commentedClasses.size() == 1 && commented.startsWith(".real", commentedClasses.get(0).offset()));

        String midComment = ".a { /* .trap */ color: red } .b {}";
        eq("classes mid-block comment", "a@0 b@" + midComment.indexOf(".b"),
                classes(WfmCssModules.extractClasses(midComment)));

        eq("classes declaration value no match", "a@0",
                classes(WfmCssModules.extractClasses(".a { transition: color .5s; }")));
        eq("classes dedup first wins", "a@0", classes(WfmCssModules.extractClasses(".a{} .a{}")));

        List<WfmCssModules.CssClass> kebab = WfmCssModules.extractClasses(".foo-bar {}");
        check("classes kebab name+camel",
                kebab.size() == 1 && "foo-bar".equals(kebab.get(0).name()) && "fooBar".equals(kebab.get(0).camel()));
    }

    private static void testCamel() {
        eq("camel foo-bar", "fooBar", WfmCssModules.camel("foo-bar"));
        eq("camel plain", "foo", WfmCssModules.camel("foo"));
        eq("camel double dash", "fooBar", WfmCssModules.camel("foo--bar"));
        eq("camel digit segment", "a1b", WfmCssModules.camel("a-1b"));
        eq("camel empty", "", WfmCssModules.camel(""));
    }

    private static void testStyleModules() {
        String sfc = """
                <template>
                  <div class="app"/>
                </template>
                <script setup>
                import styles from './a.module.css'
                </script>
                <style scoped>
                .s { color: red }
                </style>
                <style module>
                .a { color: blue }
                </style>
                <style module="named">
                .b { color: green }
                </style>
                <style module src="./ext.module.css"></style>
                """;

        List<WfmCssModules.StyleModule> inline = WfmCssModules.inlineModules(sfc);
        check("inline count (scoped ignored)", inline.size() == 2);
        eq("inline names", "style,named", inline.get(0).moduleName() + "," + inline.get(1).moduleName());

        String bareTag = "<style module>";
        check("inline bare cssStartOffset after '>'",
                inline.get(0).cssStartOffset() == sfc.indexOf(bareTag) + bareTag.length());
        eq("inline bare css", "\n.a { color: blue }\n", inline.get(0).css());

        String namedTag = "<style module=\"named\">";
        check("inline named cssStartOffset after '>'",
                inline.get(1).cssStartOffset() == sfc.indexOf(namedTag) + namedTag.length());
        eq("inline named css", "\n.b { color: green }\n", inline.get(1).css());

        List<WfmCssModules.ExternalModule> external = WfmCssModules.externalModules(sfc);
        check("external count", external.size() == 1);
        eq("external name", "style", external.get(0).moduleName());
        eq("external path", "./ext.module.css", external.get(0).path());
    }

    private static void testModuleImports() {
        String script = """
                import styles from './a.module.css';
                import x from '../b.module.scss'
                import y from './plain.css'
                """;
        List<WfmCssModules.ModuleImport> imports = WfmCssModules.moduleImports(script);
        check("imports count (plain css ignored)", imports.size() == 2);
        eq("imports styles", "styles='./a.module.css'",
                imports.get(0).identifier() + "='" + imports.get(0).path() + "'");
        eq("imports x", "x='../b.module.scss'",
                imports.get(1).identifier() + "='" + imports.get(1).path() + "'");

        List<WfmCssModules.ModuleImport> less = WfmCssModules.moduleImports("import l from './l.module.less';");
        check("imports less", less.size() == 1 && "l".equals(less.get(0).identifier()));

        List<WfmCssModules.ModuleImport> dup = WfmCssModules.moduleImports(
                "import a from './x.module.css'; import a from './y.module.scss';");
        check("imports dedup by identifier",
                dup.size() == 1 && "'./x.module.css'".equals("'" + dup.get(0).path() + "'"));
    }

    private static void testTriggerAt() {
        String afterDot = ":class=\"$style.";
        eq("trigger $style dot", "style|" + afterDot.length() + "|false", trig(WfmCssModules.triggerAt(afterDot)));

        String typed = ":class=\"$style.foo";
        int typedMember = typed.indexOf(".foo") + 1;
        eq("trigger $style.foo member start", "style|" + typedMember + "|false", trig(WfmCssModules.triggerAt(typed)));

        String bracket = ":class=\"$style['";
        eq("trigger bracket access", "style|" + bracket.length() + "|true", trig(WfmCssModules.triggerAt(bracket)));

        String named = ":class=\"$named.x";
        int namedMember = named.indexOf(".x") + 1;
        eq("trigger named module", "named|" + namedMember + "|false", trig(WfmCssModules.triggerAt(named)));

        String importRef = "const c = styles.";
        eq("trigger import identifier", "styles|" + importRef.length() + "|false",
                trig(WfmCssModules.triggerAt(importRef)));

        check("trigger member chain null", WfmCssModules.triggerAt("foo.bar.") == null);
        check("trigger no dot null", WfmCssModules.triggerAt("a $style") == null);
        check("trigger bracket non-dollar null", WfmCssModules.triggerAt("styles['") == null);
        check("trigger bare quote null", WfmCssModules.triggerAt("'$style'") == null);
    }

    /** Renders classes as "name@offset" tokens for compact comparison. */
    private static String classes(List<WfmCssModules.CssClass> list) {
        StringBuilder sb = new StringBuilder();
        for (WfmCssModules.CssClass c : list) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.name()).append('@').append(c.offset());
        }
        return sb.toString();
    }

    /** Renders a trigger as "module|memberStart|bracket", or "null". */
    private static String trig(WfmCssModules.Trigger t) {
        return t == null ? "null" : t.module() + "|" + t.memberStart() + "|" + t.bracket();
    }

    private static void check(String name, boolean cond) {
        report(name, cond, "");
    }

    private static void eq(String name, Object expected, Object actual) {
        report(name, String.valueOf(expected).equals(String.valueOf(actual)),
                "expected=<" + expected + "> actual=<" + actual + ">");
    }

    private static void report(String name, boolean cond, String detail) {
        total++;
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }
}
