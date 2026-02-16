package dev.jumpwatch.serverfabric.host;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TemplateManager {

    private final Path templatesDir;
    private final ObjectMapper om;
    private final Map<String, TemplateMeta> cache = new ConcurrentHashMap<>();

    public TemplateManager(Path templatesDir, ObjectMapper om) {
        this.templatesDir = templatesDir;
        this.om = om;
    }

    public TemplateMeta get(String templateName) throws IOException {
        if (templateName == null || templateName.isBlank()) return null;
        return cache.computeIfAbsent(templateName, t -> {
            try {
                return load(t);
            } catch (IOException e) {
                System.out.println("[Host] Failed to load template.json for " + t + ": " + e.getMessage());
                return new TemplateMeta();
            }
        });
    }

    public void invalidate(String templateName) {
        cache.remove(templateName);
    }

    private TemplateMeta load(String templateName) throws IOException {
        Path dir = templatesDir.resolve(templateName);
        Path metaFile = dir.resolve("template.json");

        if (!Files.exists(metaFile)) {
            return new TemplateMeta(); // means "no overrides"
        }

        return om.readValue(metaFile.toFile(), TemplateMeta.class);
    }
}
