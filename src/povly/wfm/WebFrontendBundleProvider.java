package povly.wfm;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;

/**
 * TextMate bundle provider for the OpenIDE web stack (css/scss/less/vue + html/js/ts).
 *
 * Root-cause fix of "Пакет web-frontend-mini имеет неизвестный формат":
 * TextMateServiceImpl#registerBundle detects the bundle format via
 * BundleType.Companion.detectBundleType(), which checks the TOP-LEVEL listing of the
 * bundle directory (Files.list, non-recursive, regular files only):
 *   - name ends with ".tmBundle"                  -> TEXTMATE
 *   - contains "package.json"                     -> VSCODE
 *   - any file ends with ".tmLanguage"/".tmPreferences" -> SUBLIME
 *   - contains "info.plist"                       -> TEXTMATE
 *   - otherwise                                   -> UNDEFINED -> error notification
 * v1.1 exposed "<cache>/wfm-bundle" whose only child was the "syntaxes" DIRECTORY
 * (filtered out) -> UNDEFINED. v1.2 ships a proper TextMate bundle layout:
 *   <cache>/wfm-bundle-v2/info.plist + Syntaxes/*.tmLanguage[.json]
 * (the TEXTMATE reader reads grammars from the "Syntaxes" subdirectory, accepting
 * *.tmLanguage, *.plist and *.tmLanguage.json, case-sensitive).
 */
public final class WebFrontendBundleProvider implements TextMateBundleProvider {

    private static final Logger LOG = Logger.getInstance(WebFrontendBundleProvider.class);

    /** Bundle directory inside the IDE system (cache) dir. v2: new layout, new name. */
    private static final String BUNDLE_DIR_NAME = "wfm-bundle-v2";

    /**
     * All bundle files shipped in the plugin jar under /bundle/.
     * Syntaxes/ names must satisfy the TEXTMATE reader filter (*.tmLanguage, *.plist, *.tmLanguage.json).
     * Preferences/*.tmPreferences carry indentation rules + comment shell variables: they fuel the
     * platform's active TextMateLineIndentProvider (Enter indent) and TextMateCommentProvider (Ctrl+/).
     */
    private static final String[] BUNDLE_FILES = {
            "info.plist",
            "Syntaxes/css.tmLanguage",
            "Syntaxes/scss.tmLanguage.json",
            "Syntaxes/less.tmLanguage.json",
            "Syntaxes/vue.tmLanguage.json",
            "Syntaxes/vue-directives.tmLanguage.json",
            "Syntaxes/vue-interpolations.tmLanguage.json",
            "Syntaxes/html.tmLanguage.json",
            "Syntaxes/javascript.tmLanguage.json",
            "Syntaxes/typescript.tmLanguage.json",
            "Syntaxes/blade.tmLanguage.json",
            "Preferences/CSS.tmPreferences",
            "Preferences/SCSS.tmPreferences",
            "Preferences/LESS.tmPreferences",
            "Preferences/Vue.tmPreferences",
            "Preferences/JavaScript.tmPreferences",
            "Preferences/TypeScript.tmPreferences",
    };

    @Override
    public List<PluginBundle> getBundles() {
        LOG.info("wfm: getBundles() entered");
        Path bundleRoot = Paths.get(System.getProperty("user.home"),
                ".cache", "OpenIDE", "OpenIDE2026.2", BUNDLE_DIR_NAME);
        try {
            extractBundle(bundleRoot);
        } catch (IOException e) {
            LOG.error("wfm: failed to extract textmate bundle to " + bundleRoot, e);
            throw new UncheckedIOException("Cannot extract wfm textmate bundle to " + bundleRoot, e);
        }
        LOG.info("wfm: bundle ready at " + bundleRoot + " (" + BUNDLE_FILES.length + " files)");
        return List.of(new PluginBundle("web-frontend-mini", bundleRoot));
    }

    /** Idempotent extraction: every boot overwrites files, so grammar updates apply without manual cache cleanup. */
    private static void extractBundle(Path root) throws IOException {
        for (String res : BUNDLE_FILES) {
            Path target = root.resolve(res);
            Files.createDirectories(target.getParent());
            try (InputStream in = WebFrontendBundleProvider.class.getResourceAsStream("/bundle/" + res)) {
                if (in == null) {
                    throw new IOException("Missing bundled resource: " + res);
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
