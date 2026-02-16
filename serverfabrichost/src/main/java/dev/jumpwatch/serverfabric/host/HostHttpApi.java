package dev.jumpwatch.serverfabric.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HostHttpApi {
    private final String token;
    private final InstanceManager mgr;
    private final ObjectMapper om = new ObjectMapper();

    public HostHttpApi(String token, InstanceManager mgr) {
        this.token = token;
        this.mgr = mgr;
    }

    public void register(HttpServer server) {
        server.createContext("/server/create", ex -> handleAuthed(ex, () -> {
            var req = om.readTree(readBody(ex));
            String template = req.path("template").asText("");
            String name = req.path("name").asText("");
            var res = mgr.createFromTemplate(template, name);
            writeJson(ex, 200, om.writeValueAsString(res));
        }));

        server.createContext("/server/start", ex -> handleAuthed(ex, () -> {
            var req = om.readTree(readBody(ex));
            mgr.start(req.path("name").asText(""));
            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/stop", ex -> handleAuthed(ex, () -> {
            var req = om.readTree(readBody(ex));
            mgr.stop(req.path("name").asText(""));
            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/delete", ex -> handleAuthed(ex, () -> {
            var req = om.readTree(readBody(ex));
            mgr.delete(req.path("name").asText(""));
            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/command", ex -> handleAuthed(ex, () -> {
            var req = om.readTree(readBody(ex));
            String name = req.path("name").asText("");
            String cmd = req.path("cmd").asText("");
            mgr.command(name, cmd);
            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/templates", ex -> handleAuthed(ex, () -> {
            var node = om.createObjectNode();
            node.put("hostId", mgr.hostId());
            var arr = node.putArray("templates");
            for (String t : mgr.listTemplates()) arr.add(t);
            writeJson(ex, 200, om.writeValueAsString(node));
        }));

        server.createContext("/status", ex -> handleAuthed(ex, () -> {
            writeJson(ex, 200, om.writeValueAsString(mgr.status()));
        }));
    }

    private void handleAuthed(HttpExchange ex, IoRunnable action) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                writeJson(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            action.run();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            writeJson(ex, 500, "{\"error\":\"" + esc(msg) + "\"}");
        } finally {
            ex.close();
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    @FunctionalInterface interface IoRunnable { void run() throws Exception; }
}