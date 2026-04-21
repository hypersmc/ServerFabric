package dev.jumpwatch.serverfabric.host.instance;

import dev.jumpwatch.serverfabric.host.HostConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BuildJdkManager {

    public record BuildJavaEnv(Path javaHome, Path javaBin, Path javacBin, boolean internal) {
        public Map<String, String> toEnv() {
            Map<String, String> env = new HashMap<>();
            env.put("JAVA_HOME", javaHome.toAbsolutePath().toString());
            env.put("PATH", javaBin.toAbsolutePath() + System.getProperty("path.separator") + System.getenv("PATH"));
            return env;
        }
    }

    private final Path jdksRoot;

    public BuildJdkManager(HostConfig cfg) throws IOException {
        this.jdksRoot = cfg.rootPath().resolve("tools-cache").resolve("jdks");
        Files.createDirectories(jdksRoot);
    }

    public BuildJavaEnv ensureBuildJdk(int majorVersion) throws IOException {
        // First try system java/javac
        //due to older versions not being able to be built from newer versions of Minecraft we cannot keep this function running as planned.
//        BuildJavaEnv system = detectSystemJdk();
//        if (system != null) {
//            return system;
//        }

        // Fallback to internal cached JDK
        return ensureInternalJdk(majorVersion);

    }

    private BuildJavaEnv detectSystemJdk() {
        try {
            if (!commandExists("java")) return null;
            if (!commandExists("javac")) return null;

            Path javaPath = resolveCommand("java");
            Path javacPath = resolveCommand("javac");
            if (javaPath == null || javacPath == null) return null;

            Path javaHome = javaPath.getParent().getParent();
            Path javaBin = javaHome.resolve("bin");
            return new BuildJavaEnv(javaHome, javaBin, javacPath, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BuildJavaEnv ensureInternalJdk(int majorVersion) throws IOException {
        String key = "temurin-" + majorVersion;
        Path dest = jdksRoot.resolve(key);

        Path javaBin = dest.resolve("bin").resolve("java");
        Path javacBin = dest.resolve("bin").resolve("javac");

        if (Files.exists(javaBin) && Files.exists(javacBin)) {
            return new BuildJavaEnv(dest, dest.resolve("bin"), javacBin, true);
        }

        Files.createDirectories(dest.getParent());

        String url = switch (majorVersion) {
            case 8 -> "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u462-b08/OpenJDK8U-jdk_x64_linux_hotspot_8u462b08.tar.gz";
            case 17 -> "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.17_10.tar.gz";
            case 21 -> "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz";
            default -> throw new IOException("Unsupported internal JDK version: " + majorVersion);
        };

        Path archive = jdksRoot.resolve(key + ".tar.gz");
        download(url, archive);

        Path extractRoot = jdksRoot.resolve(key + "-extract");
        deleteDirIfExists(extractRoot);
        Files.createDirectories(extractRoot);

        run(List.of("tar", "xzf", archive.toAbsolutePath().toString(), "-C", extractRoot.toAbsolutePath().toString()));

        Path extractedJavaHome = findFirstJdkHome(extractRoot);
        if (extractedJavaHome == null) {
            throw new IOException("Could not locate extracted JDK home for " + key);
        }

        deleteDirIfExists(dest);
        Files.move(extractedJavaHome, dest, StandardCopyOption.REPLACE_EXISTING);

        deleteDirIfExists(extractRoot);
        Files.deleteIfExists(archive);

        if (!Files.exists(javaBin) || !Files.exists(javacBin)) {
            throw new IOException("Downloaded JDK is missing java/javac for " + key);
        }

        return new BuildJavaEnv(dest, dest.resolve("bin"), javacBin, true);
    }

    private static boolean commandExists(String cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("bash", "-lc", "command -v " + cmd).start();
        return p.waitFor() == 0;
    }

    private static Path resolveCommand(String cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("bash", "-lc", "readlink -f \"$(command -v " + cmd + ")\"").start();
        if (p.waitFor() != 0) return null;
        try (InputStream in = p.getInputStream()) {
            String out = new String(in.readAllBytes()).trim();
            return out.isBlank() ? null : Path.of(out);
        }
    }

    private static void run(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(System.out);
        }

        try {
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("Command failed with exit " + code + ": " + String.join(" ", cmd));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running command: " + String.join(" ", cmd), e);
        }
    }

    private static void download(String url, Path out) throws IOException {
        run(List.of("bash", "-lc", "curl -fsSL \"" + url + "\" -o \"" + out.toAbsolutePath() + "\""));
    }

    private static Path findFirstJdkHome(Path root) throws IOException {
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).findFirst().orElse(null);
        }
    }

    private static void deleteDirIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}