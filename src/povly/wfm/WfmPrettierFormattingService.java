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
    private static volatile String cachedPrettier;
    private static volatile String cachedBladeFormatter;

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return Set.of(Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        String name = file.getName();
        if (name.endsWith(".blade.php")) {
            return bladeFormatterBin() != null;
        }
        boolean wfmOwned = file.getFileType() instanceof WfmCssFileType
                || file.getFileType() instanceof WfmJsTsFileType
                || file.getFileType() instanceof WfmVueFileType;
        return wfmOwned && prettierBin(projectDir(file)) != null;
    }

    @Override
    protected @NotNull FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        File ioFile = request.getIOFile();
        String text = request.getDocumentText();
        List<String> command = commandFor(ioFile);
        return new FormattingTask() {
            private volatile Process process;

            @Override
            public void run() {
                long started = System.currentTimeMillis();
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

    /** Full CLI for the file: [node, toolBin, args…]; null when the tool is not resolvable. */
    private static List<String> commandFor(File ioFile) {
        String node = nodeBin();
        if (node == null) return null;
        if (ioFile.getName().endsWith(".blade.php")) {
            String bin = bladeFormatterBin();
            if (bin == null) return null;
            return Arrays.asList(node, bin, "--stdin");
        }
        String bin = prettierBin(projectDir(ioFile));
        if (bin == null) return null;
        return Arrays.asList(node, bin, "--stdin-filepath", ioFile.getName());
    }

    private static File projectDir(PsiFile file) {
        File ioFile = file.getVirtualFile() != null
                ? new File(file.getVirtualFile().getPath()) : null;
        return ioFile != null ? ioFile.getParentFile() : null;
    }

    private static File projectDir(File ioFile) {
        return ioFile != null ? ioFile.getParentFile() : null;
    }

    private static void resolveIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - resolvedAt < RESOLVE_CACHE_MS) return;
        synchronized (WfmPrettierFormattingService.class) {
            if (now - resolvedAt < RESOLVE_CACHE_MS) return;
            cachedNode = discoverNode();
            cachedPrettier = null;          // per-project: resolved lazily by prettierBin(dir)
            cachedBladeFormatter = discoverBladeFormatter();
            resolvedAt = now;
            LOG.info("wfm: external format resolved node=" + cachedNode
                    + " bladeFormatter=" + cachedBladeFormatter);
        }
    }

    private static String nodeBin() {
        resolveIfNeeded();
        return cachedNode;
    }

    private static String prettierBin(File projectDir) {
        resolveIfNeeded();
        List<String> candidates = new ArrayList<>();
        if (projectDir != null) {
            candidates.add(projectDir + "/node_modules/prettier/bin/prettier.cjs"); // v3
            candidates.add(projectDir + "/node_modules/prettier/bin/prettier.js");  // v2
        }
        String global = npmGlobalRoot();
        if (global != null) {
            candidates.add(global + "/prettier/bin/prettier.cjs");
            candidates.add(global + "/prettier/bin/prettier.js");
        }
        return firstExisting(candidates);
    }

    private static String bladeFormatterBin() {
        resolveIfNeeded();
        return cachedBladeFormatter;
    }

    private static String discoverBladeFormatter() {
        String global = npmGlobalRoot();
        if (global == null) return null;
        return firstExisting(List.of(
                global + "/blade-formatter/bin/blade-formatter.cjs",
                global + "/blade-formatter/bin/blade-formatter.js"));
    }

    /** npm global root: VM-option override, else derived from the node binary (nvm layout). */
    private static String npmGlobalRoot() {
        String override = System.getProperty("wfm.npm.global.root", "");
        if (!override.isBlank()) return override;
        String node = nodeBinRaw();
        if (node == null) return null;
        File bin = new File(node).getParentFile();          // …/v24.19.0/bin
        if (bin == null) return null;
        return bin.getParent() + "/lib/node_modules";        // …/v24.19.0/lib/node_modules
    }

    /** node: registry override, else the newest nvm version dir, else "node" from PATH. */
    private static String discoverNode() {
        String node = nodeBinRaw();
        return node != null ? node : "node";
    }

    private static String nodeBinRaw() {
        String override = System.getProperty("wfm.node.path", "");
        if (!override.isBlank()) return override;
        File nvmVersions = new File(System.getProperty("user.home"), ".nvm/versions/node");
        File[] versions = nvmVersions.listFiles();
        if (versions == null) return null;
        return Arrays.stream(versions)
                .filter(File::isDirectory)
                .max(Comparator.naturalOrder())
                .map(v -> new File(v, "bin/node"))
                .filter(File::isFile)
                .map(File::getPath)
                .orElse(null);
    }

    private static String firstExisting(List<String> candidates) {
        for (String c : candidates) {
            if (new File(c).isFile()) return c;
        }
        return null;
    }
}
