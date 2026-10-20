package povly.wfm;

import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * External formatting for the wfm web files via real formatters (v1.15.0): Prettier for
 * css/scss/less/js/mjs/cjs/ts/tsx/jsx/vue and blade-formatter for .blade.php. The user's choice
 * after the built-in lexeme formatter (v1.10–v1.14) kept drifting: see
 * {@code .ai-factory/references/FORMATTERS-RESEARCH.md} for the full study — the built-in
 * formatter is retired in v1.15.0 (its registrations removed from plugin.xml; the classes stay
 * for the engine lab and possible future revival).
 *
 * <p>How it works: the platform routes "Reformat" (Ctrl+Alt+L) and Actions on Save → Reformat code
 * to a {@code FormattingService} whose {@link #canFormat(PsiFile)} returns true; this service is
 * registered {@code order="first"} so it wins over CoreFormattingService. Each task spawns one
 * Node process:</p>
 * <ul>
 *   <li>prettier: {@code node <prettier.cjs> --stdin-filepath <name>} with the project directory as
 *       cwd — the project's own .prettierrc / package.json config resolves naturally (the user's
 *       vue project already ships both);</li>
 *   <li>blade-formatter: {@code node <blade-formatter.cjs> --stdin} (global install) — the dedicated
 *       Laravel Blade formatter, stdin→stdout.</li>
 * </ul>
 *
 * <p>Tool resolution (cached 60s): VM-option overrides {@code -Dwfm.node.path} /
 * {@code -Dwfm.npm.global.root}; prettier — project {@code node_modules/prettier/bin/prettier.cjs}
 * (.js for v2) first, then the npm global root; node — nvm layout discovery ({@code ~/.nvm/versions/node/*})
 * because the IDE is not launched from the user's shell and has no nvm PATH. If nothing resolves,
 * {@link #canFormat} is false and the platform silently falls back to its default (no-op for these
 * file types since v1.15.0).</p>
 */
public class WfmPrettierFormattingService extends AsyncDocumentFormattingService {

    private static final Logger LOG = Logger.getInstance(WfmPrettierFormattingService.class);

    /** Balloon group id for tool errors (registered in plugin.xml). */
    public static final String NOTIFICATION_GROUP = "Web Frontend Mini";

    private static final long RESOLVE_CACHE_MS = 60_000;

    private static volatile long resolvedAt;
    private static volatile String cachedNode;
    private static volatile Tool cachedPrettierGlobal;
    private static volatile Tool cachedBlade;

    @Override
    public @NotNull Set<Feature> getFeatures() {
        // AD_HOC_FORMATTING: whole-file reformatText (Ctrl+Alt+L, Actions on Save) asks services
        // with isExplicit=false and REQUIRES this feature to even consult the service.
        // FORMAT_FRAGMENTS: range/selection reformat.
        return Set.of(Feature.AD_HOC_FORMATTING, Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        String name = file.getName();
        boolean blade = name.endsWith(".blade.php");
        boolean wfmOwned = blade || file.getFileType() instanceof WfmCssFileType
                || file.getFileType() instanceof WfmJsTsFileType
                || file.getFileType() instanceof WfmVueFileType;
        if (!wfmOwned) return false;
        boolean ok = commandFor(name, projectDir(file)) != null;
        if (!ok) {
            LOG.info("wfm: canFormat=false file=" + name + " reason=no-"
                    + (blade ? "blade-formatter" : "prettier") + "-resolved");
        }
        return ok;
    }

    @Override
    protected @NotNull FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        File ioFile = request.getIOFile();
        String text = request.getDocumentText();
        List<String> command = commandFor(ioFile.getName(), projectDir(ioFile));
        return new FormattingTask() {
            private volatile Process process;

            @Override
            public void run() {
                long started = System.currentTimeMillis();
                LOG.info("wfm: external format start file=" + ioFile.getName()
                        + " tool=" + (command == null ? "none" : new File(command.get(1)).getName()));
                if (command == null) {
                    request.onError("Formatter not found", "No prettier/blade-formatter binary resolved");
                    return;
                }
                try {
                    ProcessBuilder builder = new ProcessBuilder(command);
                    builder.directory(projectDir(ioFile));
                    builder.redirectErrorStream(false);
                    process = builder.start();
                    process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().close();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    boolean finished = process.waitFor(getTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        request.onError("Formatter timeout", String.join(" ", command));
                        return;
                    }
                    if (process.exitValue() != 0 || output.isEmpty()) {
                        LOG.warn("wfm: external format failed file=" + ioFile.getName()
                                + " exit=" + process.exitValue() + " stderr=" + errors.trim());
                        request.onError("Formatting failed", errors.isBlank()
                                ? "Empty output from " + command.get(1) : errors.trim());
                        return;
                    }
                    File toolDir = new File(command.get(1)).getParentFile();
                    String tool = toolDir != null && toolDir.getParentFile() != null
                            ? toolDir.getParentFile().getName() : new File(command.get(1)).getName();
                    LOG.info("wfm: external format tool=" + tool
                            + " file=" + ioFile.getName() + " ms=" + (System.currentTimeMillis() - started));
                    request.onTextReady(output);
                }
                catch (IOException e) {
                    LOG.warn("wfm: external format io error file=" + ioFile.getName(), e);
                    request.onError("Formatting failed", e.getMessage());
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (Throwable t) {
                    LOG.error("wfm: external format crashed file=" + ioFile.getName(), t);
                    request.onError("Formatting failed", String.valueOf(t));
                }
            }

            @Override
            public boolean cancel() {
                Process p = process;
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return NOTIFICATION_GROUP;
    }

    @Override
    protected @NotNull String getName() {
        return "Prettier";
    }

    @Override
    protected @NotNull Duration getTimeout() {
        return Duration.ofSeconds(20);
    }

    /** Full CLI by file name: [node, toolBin, args…]; null when the tool is not resolvable. */
    private static List<String> commandFor(String fileName, File projectDir) {
        if (fileName.endsWith(".blade.php")) {
            Tool blade = bladeTool();
            return blade == null ? null : Arrays.asList(blade.node, blade.bin, "--stdin");
        }
        resolveIfNeeded();
        String local = firstExisting(projectDir == null ? List.<String>of() : List.of(
                projectDir + "/node_modules/prettier/bin/prettier.cjs", // v3
                projectDir + "/node_modules/prettier/bin/prettier.js")); // v2
        Tool tool = local != null ? new Tool(cachedNode, local) : cachedPrettierGlobal;
        return tool == null ? null
                : Arrays.asList(tool.node, tool.bin, "--stdin-filepath", fileName);
    }

    private static File projectDir(File ioFile) {
        return ioFile != null ? ioFile.getParentFile() : null;
    }

    private static File projectDir(PsiFile file) {
        File ioFile = file.getVirtualFile() != null
                ? new File(file.getVirtualFile().getPath()) : null;
        return ioFile != null ? ioFile.getParentFile() : null;
    }

    /** A global npm tool paired with the node binary of the nvm version that owns it. */
    private record Tool(String node, String bin) {}

    private static void resolveIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - resolvedAt < RESOLVE_CACHE_MS) return;
        synchronized (WfmPrettierFormattingService.class) {
            if (now - resolvedAt < RESOLVE_CACHE_MS) return;
            cachedNode = discoverNode();
            cachedPrettierGlobal = discoverGlobalTool("prettier", "prettier.cjs", "prettier.js");
            cachedBlade = discoverGlobalTool("blade-formatter", "blade-formatter.cjs", "blade-formatter.js");
            resolvedAt = now;
            LOG.info("wfm: external format resolved node=" + cachedNode
                    + " prettier=" + (cachedPrettierGlobal == null ? null : cachedPrettierGlobal.bin)
                    + " bladeFormatter=" + (cachedBlade == null ? null : cachedBlade.bin));
        }
    }

    private static Tool bladeTool() {
        resolveIfNeeded();
        return cachedBlade;
    }

    /**
     * Version-independent global tool discovery: scans EVERY nvm version's
     * lib/node_modules (newest first), so switching or adding node versions never breaks
     * formatting — each tool runs with the node binary of the version that owns it.
     */
    private static Tool discoverGlobalTool(String packageName, String... binNames) {
        String override = System.getProperty("wfm." + packageName + ".path", "");
        if (!override.isBlank() && new File(override).isFile()) {
            return new Tool(cachedNode != null ? cachedNode : "node", override);
        }
        String rootOverride = System.getProperty("wfm.npm.global.root", "");
        if (!rootOverride.isBlank()) {
            for (String bin : binNames) {
                File f = new File(rootOverride, packageName + "/" + bin);
                if (f.isFile()) return new Tool(cachedNode != null ? cachedNode : "node", rootOverride + "/" + packageName + "/" + bin);
            }
            return null;
        }
        for (File version : nvmVersionsNewestFirst()) {
            for (String bin : binNames) {
                File f = new File(version, "lib/node_modules/" + packageName + "/bin/" + bin);
                if (f.isFile()) {
                    return new Tool(version + "/bin/node", f.getPath());
                }
            }
        }
        return null;
    }

    /** Default node: VM-option override, else the newest nvm version, else "node" from PATH. */
    private static String discoverNode() {
        String override = System.getProperty("wfm.node.path", "");
        if (!override.isBlank()) return override;
        File[] versions = nvmVersions();
        if (versions != null) {
            for (File v : descending(versions)) {
                File node = new File(v, "bin/node");
                if (node.isFile()) return node.getPath();
            }
        }
        return "node";
    }

    private static List<File> nvmVersionsNewestFirst() {
        File[] versions = nvmVersions();
        return versions == null ? List.of() : descending(versions);
    }

    private static File[] nvmVersions() {
        File nvm = new File(System.getProperty("user.home"), ".nvm/versions/node");
        File[] versions = nvm.listFiles();
        return versions;
    }

    private static List<File> descending(File[] versions) {
        List<File> list = new ArrayList<>(Arrays.asList(versions));
        list.sort(Comparator.reverseOrder());
        return list;
    }

    private static String firstExisting(List<String> candidates) {
        for (String c : candidates) {
            if (new File(c).isFile()) return c;
        }
        return null;
    }
}
