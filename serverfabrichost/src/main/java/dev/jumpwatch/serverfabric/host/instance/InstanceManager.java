package dev.jumpwatch.serverfabric.host.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jumpwatch.serverfabric.host.HostConfig;
import dev.jumpwatch.serverfabric.host.template.TemplateManager;
import dev.jumpwatch.serverfabric.host.template.TemplateMeta;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InstanceManager {

    private final TemplateManager templateManager;
    private final InstanceActionGuard actionGuard = new InstanceActionGuard();
    private final InstanceStore store;
    private final PortManager ports;
    private final InstanceStatsService statsService;
    private final BuildJdkManager buildJdkManager;

    public record CreateResponse(String name, int port) {}
    public record StatusItem(String name, int port, String state) {}
    public record StatusResponse(String hostId, List<StatusItem> instances) {}

    private final HostConfig cfg;
    private final Path root;
    private final Path templates;
    private final Path buildCache;

    private static final long STOP_FORCE_TIMEOUT_MS = 15_000L; //Temp for now, later it's going in the config.

    private final Map<String, ManagedInstance> live = new ConcurrentHashMap<>();
    private final ObjectMapper om = new ObjectMapper();

    public InstanceManager(HostConfig cfg) throws IOException {
        this.cfg = cfg;
        this.root = cfg.rootPath();
        this.templates = root.resolve("templates");
        Path instances = root.resolve("instances");
        this.ports = new PortManager(cfg.portMin(), cfg.portMax());
        this.templateManager = new TemplateManager(templates, om);
        this.store = new InstanceStore(instances, om);
        this.statsService = new InstanceStatsService(store);
        this.buildCache = root.resolve("build-cache");
        this.buildJdkManager = new BuildJdkManager(cfg);
        Files.createDirectories(buildCache);
        Files.createDirectories(templates);
        Files.createDirectories(instances);

        loadExisting();
        autoStartMarkedInstances();
    }

    public String hostId() { return cfg.hostId(); }


    public CreateResponse createFromTemplate(String templateName, String instanceName) throws IOException {
        return createFromTemplate(templateName, instanceName, null);
    }

    public CreateResponse createFromTemplate(String templateName, String instanceName, String versionOverride) throws IOException {
        return actionGuard.withLock(instanceName, () -> {
            requireName(instanceName);
            if (templateName == null || templateName.isBlank()) throw new IOException("Template required");

            Path templateDir = templates.resolve(templateName);
            Path instanceDir = store.dir(instanceName);

            if (!Files.isDirectory(templateDir)) throw new IOException("Template not found: " + templateName);
            if (Files.exists(instanceDir)) throw new IOException("Instance already exists: " + instanceName);

            Integer allocatedPort = null;
            boolean success = false;

            try {
                TemplateMeta tm = templateManager.get(templateName);

                String jarName = (tm != null && tm.jar != null && !tm.jar.isBlank())
                        ? tm.jar
                        : "server.jar";

                String resolvedVersion = resolveEffectiveServerVersion(tm, versionOverride);

                Path templateJar = templateDir.resolve(jarName);
                Path sourceJar;

                if (Files.exists(templateJar)) {
                    sourceJar = templateJar;
                } else {
                    sourceJar = ensureBuiltJar(templateName, templateDir, tm, jarName, resolvedVersion);
                }

                String[] resolvedJvmArgs = (tm != null && tm.jvm != null && tm.jvm.args != null && !tm.jvm.args.isEmpty())
                        ? tm.jvm.args.toArray(new String[0])
                        : null;

                boolean pooled = tm != null && tm.pool != null && tm.pool.enabled;
                boolean persistent = (tm == null || tm.data == null) ? true : tm.data.persistent;

                System.out.println("[ServerFabric-Host] Copying template " + templateName + " -> " + instanceName);
                store.copyTemplate(templateDir, instanceDir);
                System.out.println("[ServerFabric-Host] Copy done for " + instanceName);

                Path instanceJar = instanceDir.resolve(jarName);
                if (!Files.exists(instanceJar)) {
                    Files.copy(sourceJar, instanceJar, StandardCopyOption.REPLACE_EXISTING);
                }

                allocatedPort = ports.allocate();
                store.writePort(instanceDir, allocatedPort);

                InstanceMeta meta = new InstanceMeta();
                meta.name = instanceName;
                meta.template = templateName;
                meta.port = allocatedPort;
                meta.jar = jarName;
                meta.serverVersion = resolvedVersion;
                meta.pooled = pooled;
                meta.persistent = persistent;
                meta.jvmArgs = resolvedJvmArgs;
                meta.autoStart = false;
                meta.lastState = "STOPPED";
                meta.lastUpdated = System.currentTimeMillis();

                store.writeMeta(instanceDir, meta);

                if (!Files.exists(instanceJar)) {
                    throw new IOException("Jar was not copied into instance: " + instanceJar);
                }

                success = true;
                return new CreateResponse(instanceName, allocatedPort);

            } finally {
                if (!success) {
                    if (allocatedPort != null) {
                        ports.release(allocatedPort);
                    }

                    try {
                        if (Files.exists(instanceDir)) {
                            store.deleteInstanceDir(instanceDir);
                        }
                    } catch (Exception e) {
                        System.out.println("[ServerFabric-Host] Cleanup failed for partial instance " + instanceName + ": " + e.getMessage());
                    }
                }
            }
        });
    }


    private Path ensureBuiltJar(String templateName, Path templateDir, TemplateMeta tm, String jarName, String version) throws IOException {
        Path cachedJar = resolveCachedJarPath(templateName, version, jarName);
        if (Files.exists(cachedJar)) {
            return cachedJar;
        }

        if (tm == null || tm.buildToolExec == null || tm.buildToolExec.isBlank()) {
            throw new IOException("Template jar missing and no buildToolExec is configured for template: " + templateName);
        }

        Files.createDirectories(cachedJar.getParent());

        Path workDir = templateDir.resolve(".buildtools-work");
        Path buildLog = cachedJar.getParent().resolve("buildlog.txt");
        boolean success = false;

        try {
            int buildJavaMajor = resolveBuildJavaMajor(templateName, version);
            BuildJdkManager.BuildJavaEnv javaEnv = buildJdkManager.ensureBuildJdk(buildJavaMajor);

            ProcessBuilder pb = new ProcessBuilder(
                    "bash",
                    "-lc",
                    tm.buildToolExec + " " + escapeShellArg(version) + " " + escapeShellArg(cachedJar.toAbsolutePath().toString())
            );
            pb.directory(templateDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().putAll(javaEnv.toEnv());

            System.out.println("[ServerFabric-Host] Building " + templateName + " " + version
                    + " using " + (javaEnv.internal() ? "internal" : "system")
                    + " JDK at " + javaEnv.javaHome());
            System.out.println("[ServerFabric-Host] Build log: " + buildLog);

            Process proc = pb.start();

            try (var logOut = Files.newOutputStream(
                    buildLog,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                logOut.write(("[ServerFabric-Host] Template: " + templateName + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                logOut.write(("[ServerFabric-Host] Version: " + version + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                logOut.write(("[ServerFabric-Host] Jar: " + jarName + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                logOut.write(("[ServerFabric-Host] Build Java: " + buildJavaMajor + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                logOut.write(System.lineSeparator().getBytes(java.nio.charset.StandardCharsets.UTF_8));

                try (var in = proc.getInputStream()) {
                    in.transferTo(logOut);
                }
            }

            int code;
            try {
                code = proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Build interrupted for template " + templateName + ". See log: " + buildLog, e);
            }

            if (code != 0) {
                throw new IOException("Build failed for template " + templateName + " version " + version
                        + " (exit " + code + "). See log: " + buildLog);
            }

            if (!Files.exists(cachedJar)) {
                throw new IOException("Build finished but output jar was not created: " + cachedJar
                        + ". See log: " + buildLog);
            }

            success = true;
            System.out.println("[ServerFabric-Host] Build finished for " + templateName + " " + version);
            return cachedJar;

        } finally {
            if (success) {
                try {
                    deleteDirIfExists(workDir);
                    System.out.println("[ServerFabric-Host] Cleaned temporary build dir: " + workDir);
                } catch (Exception e) {
                    System.out.println("[ServerFabric-Host] Failed to clean build dir " + workDir + ": " + e.getMessage());
                }
            } else {
                if (Files.exists(workDir)) {
                    System.out.println("[ServerFabric-Host] Build failed, keeping work dir for debugging: " + workDir);
                }
            }
        }
    }

    private static void deleteDirIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static String escapeShellArg(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private String resolveEffectiveServerVersion(TemplateMeta tm, String versionOverride) throws IOException {
        if (versionOverride != null && !versionOverride.isBlank()) {
            return versionOverride.trim();
        }
        if (tm != null && tm.serverVersion != null && !tm.serverVersion.isBlank()) {
            return tm.serverVersion.trim();
        }
        throw new IOException("No server version specified (template missing serverVersion and no override was provided)");
    }

    private int resolveBuildJavaMajor(String templateName, String serverVersion) {
        String t = templateName.toLowerCase(Locale.ROOT);

        if (t.contains("spigot")) {
            if (serverVersion == null || serverVersion.isBlank()) return 21;

            if (serverVersion.startsWith("1.8")
                    || serverVersion.startsWith("1.9")
                    || serverVersion.startsWith("1.10")
                    || serverVersion.startsWith("1.11")
                    || serverVersion.startsWith("1.12")
                    || serverVersion.startsWith("1.13")
                    || serverVersion.startsWith("1.14")
                    || serverVersion.startsWith("1.15")
                    || serverVersion.startsWith("1.16")) {
                return 8;
            }

            if (serverVersion.startsWith("1.17")) {
                return 16;
            }

            if (serverVersion.startsWith("1.18")
                    || serverVersion.startsWith("1.19")
                    || serverVersion.startsWith("1.20")) {
                return 17;
            }

            if (serverVersion.startsWith("1.21")) {
                return 21;
            }

            return 21;
        }

        return 17;
    }


    public void start(String instanceName) throws IOException {
        actionGuard.withLock(instanceName, () -> {
            requireName(instanceName);
            Path dir = store.dir(instanceName);
            if (!Files.isDirectory(dir)) throw new IOException("Instance not found: " + instanceName);

            ManagedInstance existing = live.get(instanceName);
            if (existing != null) {
                if (existing.isAlive()) throw new IOException("Instance already running: " + instanceName);
                live.remove(instanceName);
            }

            InstanceMeta meta = store.readMeta(dir);
            if (meta == null) throw new IOException("Missing instance.json for: " + instanceName);

            String jarName = (meta.jar != null && !meta.jar.isBlank()) ? meta.jar : "paper.jar";

            List<String> jvmArgs = new ArrayList<>();
            if (meta.jvmArgs != null && meta.jvmArgs.length > 0) jvmArgs.addAll(Arrays.asList(meta.jvmArgs));
            else jvmArgs.addAll(cfg.jvmArgs());

            Path jarPath = dir.resolve(jarName);
            if (!Files.exists(jarPath)) throw new IOException("Missing jar: " + jarPath.getFileName());

            meta.lastState = "STARTING";
            meta.autoStart = true;
            meta.lastUpdated = System.currentTimeMillis();
            store.writeMeta(dir, meta);

            TemplateMeta tm = templateManager.get(meta.template);

            ManagedInstance.ReadinessType rType = ManagedInstance.ReadinessType.LOG_CONTAINS;
            String rContains = "Done (";
            String rHost = "127.0.0.1";
            long rTimeout = 20000;

            if (tm != null && tm.readiness != null) {
                String t = tm.readiness.type == null ? "" : tm.readiness.type.trim().toUpperCase();
                if (t.equals("TCP_PORT")) rType = ManagedInstance.ReadinessType.TCP_PORT;
                else if (t.equals("NONE")) rType = ManagedInstance.ReadinessType.NONE;
                else rType = ManagedInstance.ReadinessType.LOG_CONTAINS;

                if (tm.readiness.contains != null && !tm.readiness.contains.isBlank()) rContains = tm.readiness.contains;
                if (tm.readiness.host != null && !tm.readiness.host.isBlank()) rHost = tm.readiness.host;
                if (tm.readiness.timeoutMs > 0) rTimeout = tm.readiness.timeoutMs;
            }

            ManagedInstance mi = new ManagedInstance(
                    cfg.javaCmd(), jvmArgs, instanceName, dir, jarPath,
                    rType, rContains, rHost, meta.port, rTimeout,
                    (n, st) -> { try { onInstanceStateChanged(n, st); } catch (Exception ignored) {} },
                    (n, code, stopping) -> live.remove(n)
            );

            mi.start();
            live.put(instanceName, mi);
        });
    }




    public void stop(String instanceName) throws IOException {
        actionGuard.withLock(instanceName, () -> {
            requireName(instanceName);

            Path dir = store.dir(instanceName);
            if (!Files.isDirectory(dir)) throw new IOException("Instance not found: " + instanceName);

            ManagedInstance mi = live.get(instanceName);

            InstanceMeta meta = store.readMeta(dir);
            if (meta != null) {
                meta.autoStart = false;
                meta.lastState = "STOPPING";
                meta.lastUpdated = System.currentTimeMillis();
                store.writeMeta(dir, meta);
            }

            if (mi == null || !mi.isAlive()) {
                live.remove(instanceName);
                return;
            }

            mi.stopGraceful();
            scheduleForceKillIfStillRunning(instanceName, STOP_FORCE_TIMEOUT_MS);
        });
    }


    public void delete(String instanceName) throws IOException {
        actionGuard.withLock(instanceName, () -> {
            requireName(instanceName);

            ManagedInstance mi = live.get(instanceName);
            if (mi != null && mi.isAlive()) {
                throw new IOException("Stop instance first: " + instanceName);
            }

            Path dir = store.dir(instanceName);
            if (!Files.exists(dir)) throw new IOException("Instance not found: " + instanceName);

            InstanceMeta meta = null;
            try {
                meta = store.readMeta(dir);
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] Could not read instance meta before delete for " + instanceName + ": " + e.getMessage());
            }

            store.deleteInstanceDir(dir);
            live.remove(instanceName);

            if (meta != null && meta.port > 0) {
                ports.release(meta.port);
            }
        });
    }

    public InstanceStats stats(String instanceName) throws IOException {
        requireName(instanceName);

        ManagedInstance mi = live.get(instanceName);
        if (mi == null) {
            throw new IOException("Not running: " + instanceName);
        }

        return statsService.collect(instanceName, mi);
    }

    public StatusResponse status() throws IOException {
        List<StatusItem> items = new ArrayList<>();

        for (Path p : store.listInstanceDirs()) {
            String name = p.getFileName().toString();

            try {
                InstanceMeta meta = store.readMeta(p);

                String state = "STOPPED";
                ManagedInstance mi = live.get(name);
                if (mi != null) {
                    state = mi.getState().name();
                }

                int port = meta != null ? meta.port : 0;
                items.add(new StatusItem(name, port, state));

            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] status failed for " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                items.add(new StatusItem(name, 0, "BROKEN"));
            }
        }

        items.sort(Comparator.comparing(StatusItem::name));
        return new StatusResponse(cfg.hostId(), items);
    }

    // ---- internals ----

    private void loadExisting() throws IOException {
        ports.loadExisting(store.listInstanceDirs(), store);
    }



    public List<String> listTemplates() throws IOException {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(templates)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) result.add(p.getFileName().toString());
            }
        }
        result.sort(String::compareToIgnoreCase);
        return result;
    }


    private static void requireName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Name required");
        if (!name.matches("[a-zA-Z0-9._-]{1,64}")) throw new IOException("Invalid name (allowed: a-z A-Z 0-9 . _ -)");
    }



    public void command(String instanceName, String cmd) throws IOException {
        actionGuard.withLock(instanceName, () -> {
            requireName(instanceName);
            if (cmd == null || cmd.isBlank()) throw new IOException("Command required");

            ManagedInstance mi = live.get(instanceName);
            if (mi == null) throw new IOException("Not running: " + instanceName);

            if (!mi.isAlive()) {
                live.remove(instanceName);
                throw new IOException("Not running: " + instanceName);
            }

            mi.sendCommand(cmd);
        });
    }

    private void onInstanceStateChanged(String name, ManagedInstance.State st) throws IOException {
        Path dir = store.dir(name);
        if (!Files.isDirectory(dir)) return;

        InstanceMeta meta = store.readMeta(dir);

        meta.lastState = st.name();
        meta.lastUpdated = System.currentTimeMillis();

        if (st == ManagedInstance.State.RUNNING
                || st == ManagedInstance.State.STARTING
                || st == ManagedInstance.State.CRASHED
                || st == ManagedInstance.State.START_TIMEOUT) {
            meta.autoStart = true;
        }

        store.writeMeta(dir, meta);
    }

    public void kill(String instanceName) throws IOException {
        actionGuard.withLock(instanceName, () -> {
            requireName(instanceName);

            Path dir = store.dir(instanceName);
            if (!Files.isDirectory(dir)) throw new IOException("Instance not found: " + instanceName);

            ManagedInstance mi = live.get(instanceName);
            if (mi == null || !mi.isAlive()) {
                live.remove(instanceName);
                throw new IOException("Not running: " + instanceName);
            }

            InstanceMeta meta = store.readMeta(dir);
            if (meta != null) {
                meta.autoStart = false; // intentional kill should not auto-start on host reboot
                meta.lastState = "STOPPING";
                meta.lastUpdated = System.currentTimeMillis();
                store.writeMeta(dir, meta);
            }

            mi.stopForcefully();
        });
    }

    private void autoStartMarkedInstances() throws IOException {
        List<String> toStart = new ArrayList<>();

        for (Path p : store.listInstanceDirs()) {
            try {
                InstanceMeta meta = store.readMeta(p);
                if (meta.autoStart) {
                    toStart.add(meta.name);
                }
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] autoStart scan failed for " + p.getFileName() + ": " + e.getMessage());
            }
        }

        for (String name : toStart) {
            try {
                System.out.println("[ServerFabric-Host] Auto-starting " + name + " (autoStart=true)");
                start(name);
                Thread.sleep(1000);
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] Auto-start failed for " + name + ": " + e.getMessage());
            }
        }
    }

    public void persistAllLiveStates() {
        for (var entry : live.entrySet()) {
            String name = entry.getKey();
            ManagedInstance mi = entry.getValue();
            try {
                ManagedInstance.State st = mi.getState();

                Path dir = store.dir(name);
                if (!Files.isDirectory(dir)) continue;

                InstanceMeta meta = store.readMeta(dir);
                meta.lastState = st.name();
                meta.lastUpdated = System.currentTimeMillis();

                if (st == ManagedInstance.State.RUNNING || st == ManagedInstance.State.STARTING) {
                    meta.autoStart = true;
                }

                store.writeMeta(dir, meta);
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] persistAllLiveStates failed for " + name + ": " + e.getMessage());
            }
        }
    }
    public void stopAllGraceful() {
        for (String name : new ArrayList<>(live.keySet())){
            try {
                stop(name);
            } catch (IOException e) {
                System.out.println("[ServerFabric-Host] stopAll failed for " + name + ": " + e.getMessage());
            }
        }
    }

    private void scheduleForceKillIfStillRunning(String instanceName, long delayMs) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                return;
            }

            try {
                actionGuard.withLock(instanceName, () -> {
                    ManagedInstance mi = live.get(instanceName);
                    if (mi == null || !mi.isAlive()) {
                        return;
                    }

                    System.out.println("[ServerFabric-Host] Stop timeout reached for " + instanceName + ", force killing...");
                    mi.stopForcefully();
                });
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] Force-kill escalation failed for " + instanceName + ": " + e.getMessage());
            }
        }, "ServerFabric-Host-stop-timeout-" + instanceName);

        t.setDaemon(true);
        t.start();
    }

    public RuntimeInfo runtimeInfo(String instanceName) throws IOException {
        requireName(instanceName);

        ManagedInstance mi = live.get(instanceName);
        if (mi != null) {
            ManagedInstance.RuntimeSnapshot snap = mi.snapshot();
            return new RuntimeInfo(
                    snap.name(),
                    snap.state().name(),
                    snap.alive(),
                    snap.stopping(),
                    snap.startedAtMs(),
                    snap.lastOutputAtMs(),
                    snap.pid()
            );
        }

        Path dir = store.dir(instanceName);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Instance not found: " + instanceName);
        }

        InstanceMeta meta = store.readMeta(dir);
        return new RuntimeInfo(
                instanceName,
                meta.lastState == null ? "STOPPED" : meta.lastState,
                false,
                false,
                0L,
                0L,
                -1L
        );
    }
    public List<String> recentLogs(String instanceName) throws IOException {
        requireName(instanceName);

        ManagedInstance mi = live.get(instanceName);
        if (mi == null) {
            throw new IOException("Not running: " + instanceName);
        }

        return mi.getRecentLogLines();
    }

    private String resolveServerVersion(TemplateMeta tm) throws IOException {
        if (tm == null || tm.serverVersion == null || tm.serverVersion.isBlank()) {
            throw new IOException("Template is missing serverVersion");
        }
        return tm.serverVersion.trim();
    }

    private Path resolveCachedJarPath(String templateName, String version, String jarName) {
        return buildCache.resolve(templateName).resolve(version).resolve(jarName);
    }
}