package dev.jumpwatch.serverfabric.host.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public final class HostAuditLogger {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private static final long MAX_ACTIVE_FILE_BYTES = 10L * 1024L * 1024L; // 10 MB
    private static final int MAX_ARCHIVES = 100;

    private final Path logDir;
    private final Path archiveDir;
    private final Path activeFile;
    private final Object lock = new Object();

    public HostAuditLogger(Path rootPath) throws IOException {
        this.logDir = rootPath.resolve("logs");
        this.archiveDir = logDir.resolve("archive");
        this.activeFile = logDir.resolve("host-audit.log");

        Files.createDirectories(logDir);
        Files.createDirectories(archiveDir);

        if (!Files.exists(activeFile)) {
            Files.createFile(activeFile);
        }
    }

    public void info(RequestContext ctx, String event, String detail) {
        write("INFO", ctx, event, detail);
    }

    public void warn(RequestContext ctx, String event, String detail) {
        write("WARN", ctx, event, detail);
    }

    public void error(RequestContext ctx, String event, String detail) {
        write("ERROR", ctx, event, detail);
    }

    private void write(String level, RequestContext ctx, String event, String detail) {
        String line = TS.format(Instant.now())
                + " level=" + safe(level)
                + " requestId=" + safe(ctx == null ? null : ctx.requestId())
                + " ip=" + safe(ctx == null ? null : ctx.remoteIp())
                + " method=" + safe(ctx == null ? null : ctx.method())
                + " path=" + safe(ctx == null ? null : ctx.path())
                + " event=" + safe(event)
                + " detail=" + safe(detail)
                + System.lineSeparator();

        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

        synchronized (lock) {
            try {
                rotateIfNeeded(bytes.length);

                Files.write(
                        activeFile,
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] Failed to write audit log: " + e.getMessage());
            }
        }
    }

    private void rotateIfNeeded(long incomingBytes) throws IOException {
        if (!Files.exists(activeFile)) {
            Files.createFile(activeFile);
            return;
        }

        long currentSize = Files.size(activeFile);
        if (currentSize <= 0L) {
            return;
        }

        if ((currentSize + incomingBytes) <= MAX_ACTIVE_FILE_BYTES) {
            return;
        }

        Path archiveGz = nextArchivePath(LocalDate.now(ZoneOffset.UTC));
        Path tempPlain = archiveGz.resolveSibling(archiveGz.getFileName().toString().replace(".gz", ""));

        Files.move(activeFile, tempPlain, StandardCopyOption.REPLACE_EXISTING);
        gzipFile(tempPlain, archiveGz);
        Files.deleteIfExists(tempPlain);

        Files.createFile(activeFile);

        pruneArchivesIfNeeded();

        System.out.println("[ServerFabric-Host] Rotated audit log -> " + archiveGz.getFileName());
    }

    private Path nextArchivePath(LocalDate date) throws IOException {
        String prefix = date.toString() + "-";
        String suffix = ".log.gz";

        int maxIndex = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(archiveDir, "*.log.gz")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
                    continue;
                }

                String middle = name.substring(prefix.length(), name.length() - suffix.length());
                try {
                    int idx = Integer.parseInt(middle);
                    if (idx > maxIndex) {
                        maxIndex = idx;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        int nextIndex = maxIndex + 1;
        return archiveDir.resolve(date + "-" + nextIndex + ".log.gz");
    }

    private void gzipFile(Path source, Path target) throws IOException {
        try (
                InputStream in = Files.newInputStream(source);
                OutputStream rawOut = Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
                GZIPOutputStream gzipOut = new GZIPOutputStream(rawOut)
        ) {
            in.transferTo(gzipOut);
            gzipOut.finish();
        }
    }

    private void pruneArchivesIfNeeded() throws IOException {
        List<Path> archives = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(archiveDir, "*.log.gz")) {
            for (Path p : stream) {
                archives.add(p);
            }
        }

        if (archives.size() <= MAX_ARCHIVES) {
            return;
        }

        archives.sort(Comparator.comparing(this::safeLastModified));

        int toDelete = archives.size() - MAX_ARCHIVES;
        for (int i = 0; i < toDelete; i++) {
            try {
                Files.deleteIfExists(archives.get(i));
            } catch (IOException e) {
                System.out.println("[ServerFabric-Host] Failed to prune audit archive "
                        + archives.get(i).getFileName() + ": " + e.getMessage());
            }
        }
    }

    private FileTime safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0L);
        }
    }

    private String safe(String s) {
        if (s == null) return "-";
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    public Path auditFile() {
        return activeFile;
    }

    public Path archiveDir() {
        return archiveDir;
    }
}