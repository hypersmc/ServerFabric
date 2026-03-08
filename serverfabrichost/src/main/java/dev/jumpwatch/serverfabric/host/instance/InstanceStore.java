package dev.jumpwatch.serverfabric.host.instance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InstanceStore {

    private final Path instancesDir;
    private final ObjectMapper om;

    public InstanceStore(Path instancesDir, ObjectMapper om) {
        this.instancesDir = instancesDir;
        this.om = om;
    }

    public Path dir(String instanceName) {
        return instancesDir.resolve(instanceName);
    }

    public boolean exists(String instanceName) {
        return Files.exists(dir(instanceName));
    }

    public List<Path> listInstanceDirs() throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instancesDir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) result.add(p);
            }
        }
        return result;
    }

    public void writeMeta(Path dir, InstanceMeta meta) throws IOException {
        Path file = dir.resolve("instance.json");
        om.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), meta);
    }

    public InstanceMeta readMeta(Path dir) throws IOException {
        Path file = dir.resolve("instance.json");

        if (!Files.exists(file)) {
            return buildFallbackMeta(dir);
        }

        try {
            return om.readValue(file.toFile(), InstanceMeta.class);
        } catch (Exception e) {
            System.out.println("[ServerFabric-Host] Failed to parse instance.json for " + dir.getFileName() + ": " + e.getMessage());
            return buildFallbackMeta(dir);
        }
    }

    public InstanceMeta buildFallbackMeta(Path dir) throws IOException {
        InstanceMeta meta = new InstanceMeta();

        meta.name = dir.getFileName().toString();
        meta.template = "";
        meta.port = readPortFromServerProperties(dir);
        meta.jar = findJarName(dir);

        meta.autoStart = false;
        meta.lastState = "STOPPED";
        meta.lastUpdated = System.currentTimeMillis();
        meta.pooled = false;
        meta.persistent = true;
        meta.jvmArgs = null;

        return meta;
    }

    public int readPortFromServerProperties(Path dir) {
        Path props = dir.resolve("server.properties");
        if (!Files.exists(props)) return 0;

        try {
            List<String> lines = Files.readAllLines(props);
            for (String line : lines) {
                if (line.startsWith("server-port=")) {
                    String value = line.substring("server-port=".length()).trim();
                    return Integer.parseInt(value);
                }
            }
        } catch (Exception e) {
            System.out.println("[ServerFabric-Host] Failed to read server-port from " + dir.getFileName() + ": " + e.getMessage());
        }

        return 0;
    }

    public void writePort(Path dir, int port) throws IOException {
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

    public void copyTemplate(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(from -> {
                try {
                    Path rel = src.relativize(from);
                    Path to = dst.resolve(rel);

                    if (Files.isDirectory(from)) {
                        Files.createDirectories(to);
                        return;
                    }

                    Files.createDirectories(to.getParent());
                    Files.copy(from, to,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public void deleteInstanceDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public String findJarName(Path dir) throws IOException {
        if (Files.exists(dir.resolve("paper.jar"))) return "paper.jar";
        if (Files.exists(dir.resolve("server.jar"))) return "server.jar";

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                return p.getFileName().toString();
            }
        }

        throw new IOException("Missing jar in " + dir);
    }
}
