package dev.jumpwatch.serverfabric.host;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InstanceManager {

    public record CreateResponse(String name, int port) {}
    public record StatusItem(String name, int port, String state) {}
    public record StatusResponse(String hostId, List<StatusItem> instances) {}

    private final HostConfig cfg;
    private final Path root;
    private final Path templates;
    private final Path instances;
    private final String hostId;

    private final int portMin, portMax;
    private final Map<String, ManagedInstance> live = new ConcurrentHashMap<>();
    private final Set<Integer> reservedPorts = Collections.synchronizedSet(new HashSet<>());
    private final ObjectMapper om = new ObjectMapper();

    public InstanceManager(HostConfig cfg) throws IOException {
        this.cfg = cfg;
        this.root = cfg.rootPath();
        this.templates = root.resolve("templates");
        this.instances = root.resolve("instances");
        this.portMin = cfg.portMin();
        this.portMax = cfg.portMax();
        this.hostId = cfg.hostId();

        Files.createDirectories(templates);
        Files.createDirectories(instances);

        loadExisting();
        autoStartMarkedInstances();
    }

    public String hostId() { return hostId; }

    public CreateResponse createFromTemplate(String templateName, String instanceName) throws IOException {
        requireName(instanceName);
        if (templateName == null || templateName.isBlank()) throw new IOException("Template required");

        Path templateDir = templates.resolve(templateName);
        Path instanceDir = instances.resolve(instanceName);

        if (!Files.isDirectory(templateDir)) throw new IOException("Template not found: " + templateName);
        if (Files.exists(instanceDir)) throw new IOException("Instance already exists: " + instanceName);

        copyDir(templateDir, instanceDir);

        int port = allocatePort();
        writeOrUpdateServerProperties(instanceDir, port);

        String jarName = findJarName(instanceDir);
        writeMeta(instanceDir, new InstanceMeta(instanceName, templateName, port, jarName));

        InstanceMeta meta = new InstanceMeta(instanceName, templateName, port, jarName);
        meta.autoStart = false;
        meta.lastState = "STOPPED";
        meta.lastUpdated = System.currentTimeMillis();
        writeMeta(instanceDir, meta);

        return new CreateResponse(instanceName, port);
    }

    public void start(String instanceName) throws IOException {
        requireName(instanceName);
        Path dir = instances.resolve(instanceName);
        if (!Files.isDirectory(dir)) throw new IOException("Instance not found: " + instanceName);

        ManagedInstance existing = live.get(instanceName);
        if (existing != null && existing.isAlive()) throw new IOException("Instance already running: " + instanceName);

        InstanceMeta meta = readMeta(dir);
        Path jarPath = dir.resolve(meta.jar == null || meta.jar.isBlank() ? findJarName(dir) : meta.jar);
        if (!Files.exists(jarPath)) throw new IOException("Missing jar: " + jarPath.getFileName());

        ManagedInstance mi = new ManagedInstance(
                cfg.javaCmd(), cfg.jvmArgs(), instanceName, dir, jarPath,
                (n, st) -> {
                    try { onInstanceStateChanged(n, st); }
                    catch (Exception e) { System.out.println("[ServerFabric-Host] Failed to persist state for " + n + ": " + e.getMessage()); }
                }
        );
        mi.start();
        live.put(instanceName, mi);
    }

    public void stop(String instanceName) throws IOException {
        requireName(instanceName);
        ManagedInstance mi = live.get(instanceName);
        if (mi == null || !mi.isAlive()) throw new IOException("Not running: " + instanceName);
        mi.stopGraceful();
        Path dir = instances.resolve(instanceName);
        if (Files.isDirectory(dir)) {
            InstanceMeta meta = readMeta(dir);
            meta.autoStart = false;                 // intentional stop should NOT auto-start on host reboot
            meta.lastState = "STOPPED";
            meta.lastUpdated = System.currentTimeMillis();
            writeMeta(dir, meta);
        }
    }

    public void delete(String instanceName) throws IOException {
        requireName(instanceName);
        ManagedInstance mi = live.get(instanceName);
        if (mi != null && mi.isAlive()) throw new IOException("Stop instance first: " + instanceName);

        Path dir = instances.resolve(instanceName);
        if (!Files.exists(dir)) throw new IOException("Instance not found: " + instanceName);
        deleteDir(dir);
    }

    public StatusResponse status() throws IOException {
        List<StatusItem> items = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instances)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;

                String name = p.getFileName().toString();
                InstanceMeta meta = readMeta(p);

                String state = "STOPPED";
                ManagedInstance mi = live.get(name);
                if (mi != null) {
                    state = mi.getState().name();
                }

                items.add(new StatusItem(name, meta.port, state));
            }
        }
        items.sort(Comparator.comparing(StatusItem::name));
        return new StatusResponse(cfg.hostId(), items);
    }

    // ---- internals ----

    private void loadExisting() throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instances)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                Path metaFile = p.resolve("instance.json");
                if (!Files.exists(metaFile)) continue;
                InstanceMeta meta = readMeta(p);
                reservedPorts.add(meta.port);
            }
        }
    }

    private int allocatePort() throws IOException {
        for (int p = portMin; p <= portMax; p++) {
            if (reservedPorts.contains(p)) continue;
            if (isPortFree(p)) {
                reservedPorts.add(p);
                return p;
            }
        }
        throw new IOException("No free ports in range " + portMin + "-" + portMax);
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void writeOrUpdateServerProperties(Path dir, int port) throws IOException {
        Path props = dir.resolve("server.properties");
        List<String> lines = Files.exists(props) ? Files.readAllLines(props) : new ArrayList<>();

        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("server-port=")) {
                lines.set(i, "server-port=" + port);
                found = true;
                break;
            }
        }
        if (!found) lines.add("server-port=" + port);

        Files.write(props, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

    private String findJarName(Path dir) throws IOException {
        if (Files.exists(dir.resolve("paper.jar"))) return "paper.jar";
        if (Files.exists(dir.resolve("server.jar"))) return "server.jar";
        throw new IOException("Missing paper.jar/server.jar in " + dir);
    }

    private void writeMeta(Path dir, InstanceMeta meta) throws IOException {
        Path file = dir.resolve("instance.json");
        om.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), meta);
    }

    private InstanceMeta readMeta(Path dir) throws IOException {
        Path file = dir.resolve("instance.json");
        if (!Files.exists(file)) {
            // if someone manually made a folder, try best-effort
            int fallbackPort = 0;
            return new InstanceMeta(dir.getFileName().toString(), "", fallbackPort, findJarName(dir));
        }
        return om.readValue(file.toFile(), InstanceMeta.class);
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.walk(src).forEach(from -> {
            try {
                Path to = dst.resolve(src.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
    }

    private static void requireName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Name required");
        if (!name.matches("[a-zA-Z0-9._-]{1,64}")) throw new IOException("Invalid name (allowed: a-z A-Z 0-9 . _ -)");
    }

    public void command(String instanceName, String cmd) throws IOException {
        requireName(instanceName);
        if (cmd == null || cmd.isBlank()) throw new IOException("Command required");

        ManagedInstance mi = live.get(instanceName);
        if (mi == null || !mi.isAlive()) throw new IOException("Not running: " + instanceName);

        mi.sendCommand(cmd);
    }

    private void onInstanceStateChanged(String name, ManagedInstance.State st) throws IOException {
        Path dir = instances.resolve(name);
        if (!Files.isDirectory(dir)) return;

        InstanceMeta meta = readMeta(dir);

        meta.lastState = st.name();
        meta.lastUpdated = System.currentTimeMillis();

        // The important part:
        // - RUNNING/STARTING => autoStart true (it should come back after a crash)
        // - CRASHED => autoStart true (bring it back on next host boot)
        // - STOPPED => leave as-is here; we will set autoStart=false in stop() explicitly
        if (st == ManagedInstance.State.RUNNING || st == ManagedInstance.State.STARTING || st == ManagedInstance.State.CRASHED) {
            meta.autoStart = true;
        }

        writeMeta(dir, meta);
    }
    private void autoStartMarkedInstances() throws IOException {
        List<String> toStart = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instances)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                InstanceMeta meta = readMeta(p);
                if (meta.autoStart) {
                    toStart.add(meta.name);
                }
            }
        }

        // Start them after scanning so one failure doesn't break scanning
        for (String name : toStart) {
            try {
                System.out.println("[ServerFabric-Host] Auto-starting " + name + " (autoStart=true)");
                start(name);
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

                Path dir = instances.resolve(name);
                if (!Files.isDirectory(dir)) continue;

                InstanceMeta meta = readMeta(dir);
                meta.lastState = st.name();
                meta.lastUpdated = System.currentTimeMillis();

                // If it's alive-ish, keep autoStart so it comes back after host restart/crash
                if (st == ManagedInstance.State.RUNNING || st == ManagedInstance.State.STARTING) {
                    meta.autoStart = true;
                }

                writeMeta(dir, meta);
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] persistAllLiveStates failed for " + name + ": " + e.getMessage());
            }
        }
    }
}