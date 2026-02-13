package dev.jumpwatch.serverfabric.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public record HostConfig(
        String bindHost,
        String hostId,
        int bindPort,
        String token,
        Path rootPath,
        int portMin,
        int portMax,
        String javaCmd,
        List<String> jvmArgs
) {
    public static HostConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }

        String bindHost = p.getProperty("bindHost", "127.0.0.1");
        String hostId = p.getProperty("hostId", "unknown").trim();

        int bindPort = Integer.parseInt(p.getProperty("bindPort", "8085"));
        String token = require(p, "token");

        Path rootPath = Path.of(require(p, "rootPath"));
        int portMin = Integer.parseInt(p.getProperty("portMin", "25570"));
        int portMax = Integer.parseInt(p.getProperty("portMax", "25650"));

        String javaCmd = p.getProperty("javaCmd", "java");
        String jvmArgsRaw = p.getProperty("jvmArgs", "-Xms512M,-Xmx1024M");

        List<String> jvmArgs = Arrays.stream(jvmArgsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new HostConfig(bindHost, hostId, bindPort, token, rootPath, portMin, portMax, javaCmd, jvmArgs);
    }

    private static String require(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IOException("Missing config key: " + key);
        return v.trim();
    }
}