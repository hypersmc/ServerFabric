package dev.jumpwatch.serverfabric.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jumpwatch.serverfabric.host.http.*;
import dev.jumpwatch.serverfabric.host.instance.InstanceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public final class HostHttpApi {
    private final String token;
    private final InstanceManager mgr;
    private final ObjectMapper om = new ObjectMapper();
    private final HostAuditLogger audit;
    private final HostConfig cfg;
    private final HostSecurityGuard security;
    private final SignedRequestVerifier signedVerifier;
    private final AuthMode authMode;

    public HostHttpApi(String token, InstanceManager mgr, HostConfig cfg) throws IOException {
        this.token = token;
        this.mgr = mgr;
        this.cfg = cfg;
        this.audit = new HostAuditLogger(cfg.rootPath());
        this.security = new HostSecurityGuard(cfg.securityTrustedCidrs());
        this.authMode = AuthMode.parse(cfg.authMode());
        this.signedVerifier = new SignedRequestVerifier(
                cfg.keyId(),
                cfg.secret(),
                cfg.skewSeconds(),
                cfg.ttlSeconds()
        );
    }

    public void register(HttpServer server) {
        server.createContext("/server/create", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String template = req.path("template").asText("");
            String name = req.path("name").asText("");
            String version = req.path("version").asText(null);

            if (version != null && version.isBlank()) {
                version = null;
            }

            audit.info(ctx, "instance.create.request",
                    "template=" + template + " name=" + name + " version=" + (version == null ? "-" : version));

            InstanceManager.CreateResponse res =
                    (version == null)
                            ? mgr.createFromTemplate(template, name)
                            : mgr.createFromTemplate(template, name, version);

            audit.info(ctx, "instance.create.ok",
                    "name=" + res.name() + " port=" + res.port());

            writeJson(ex, 200, om.writeValueAsString(res));
        }));

        server.createContext("/server/start", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.start.request", "name=" + name);
            mgr.start(name);
            audit.info(ctx, "instance.start.ok", "name=" + name);

            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/restart", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.restart.request", "name=" + name);
            mgr.restart(name);
            audit.info(ctx, "instance.restart.ok", "name=" + name);

            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/stop", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.stop.request", "name=" + name);
            mgr.stop(name);
            audit.info(ctx, "instance.stop.ok", "name=" + name);

            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/delete", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.delete.request", "name=" + name);
            mgr.delete(name);
            audit.info(ctx, "instance.delete.ok", "name=" + name);

            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/command", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");
            String cmd = req.path("cmd").asText("");

            audit.info(ctx, "instance.command.request", "name=" + name + " cmd=" + cmd);
            mgr.command(name, cmd);
            audit.info(ctx, "instance.command.ok", "name=" + name);

            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/runtime", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.runtime.request", "name=" + name);
            writeJson(ex, 200, om.writeValueAsString(mgr.runtimeInfo(name)));
        }));

        server.createContext("/server/kill", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.kill.request", "name=" + name);
            mgr.kill(name);
            audit.info(ctx, "instance.kill.ok", "name=" + name);

            writeJson(ex, 200, "{\"ok\":true}");
        }));

        server.createContext("/server/logs", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.logs.request", "name=" + name);
            writeJson(ex, 200, om.writeValueAsString(mgr.recentLogs(name)));
        }));

        server.createContext("/server/stats", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            var req = om.readTree(rawBody);
            String name = req.path("name").asText("");

            audit.info(ctx, "instance.stats.request", "name=" + name);
            try {
                writeJson(ex, 200, om.writeValueAsString(mgr.stats(name)));
            } catch (IOException e){
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.startsWith("Not running:")){
                    audit.warn(ctx, "instance.stats.not_running", "name=" + name);
                    writeError(ex, ctx, 409, "not_running", "Instance is offline. Live stats are unavailable.");
                    return;
                }
                throw e;
            }
        }));

        server.createContext("/templates", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            audit.info(ctx, "templates.request", "list");

            var node = om.createObjectNode();
            node.put("hostId", mgr.hostId());
            var arr = node.putArray("templates");
            for (String t : mgr.listTemplates()) arr.add(t);
            writeJson(ex, 200, om.writeValueAsString(node));
        }));

        server.createContext("/version", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            audit.info(ctx, "version.request", "host-version");

            var info = new HostVersionInfo(
                    HostVersions.PRODUCT,
                    HostVersions.VERSION,
                    HostVersions.HOST_API_VERSION,
                    HostVersions.MIN_SUPPORTED_HOST_API_VERSION
            );
            writeJson(ex, 200, om.writeValueAsString(info));
        }));

        server.createContext("/status", ex -> handleAuthed(ex, (ctx, rawBody) -> {
            audit.info(ctx, "status.request", "all-instances");
            writeJson(ex, 200, om.writeValueAsString(mgr.status()));
        }));
    }

    private void handleAuthed(com.sun.net.httpserver.HttpExchange ex, ThrowingAuthedHandler handler) throws java.io.IOException {
        RequestContext ctx = beginContext(ex);
        audit.info(ctx, "request.begin", "incoming");

        try {
            HostSecurityGuard.SecurityDecision decision = security.checkRequest(ctx.remoteIp(), ctx.path());
            if (!decision.allowed()) {
                if (decision.retryAfterSeconds() > 0) {
                    ex.getResponseHeaders().set("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                }

                audit.warn(ctx, "request.blocked",
                        "error=" + decision.error()
                                + " status=" + decision.statusCode()
                                + " retryAfter=" + decision.retryAfterSeconds() + "s");

                writeError(ex, ctx, decision.statusCode(), decision.error(), decision.message());
                return;
            }

            if (security.isTrusted(ctx.remoteIp())) {
                audit.info(ctx, "security.trusted", "trusted-cidr-bypass");
            }

            String rawBody = readBody(ex);

            boolean authorized = false;
            SignedAuthResult signed = signedVerifier.verify(ctx, ex, rawBody);

            switch (authMode) {
                case TOKEN_ONLY -> {
                    if (isAuthorized(ex)) {
                        audit.info(ctx, "auth.ok", "authorized bearer");
                        authorized = true;
                    } else {
                        audit.warn(ctx, "auth.failed", "unauthorized bearer");
                    }
                }

                case SIGNED_ONLY -> {
                    if (signed.success()) {
                        audit.info(ctx, "signed_auth.ok", "keyId=" + signed.keyId());
                        authorized = true;
                    } else {
                        audit.warn(ctx, "signed_auth.failed", "reason=" + signed.reason() + " keyId=" + signed.keyId());
                    }
                }

                case TOKEN_OR_SIGNED -> {
                    if (signed.headersPresent()) {
                        if (signed.success()) {
                            audit.info(ctx, "signed_auth.ok", "keyId=" + signed.keyId());
                            authorized = true;
                        } else {
                            audit.warn(ctx, "signed_auth.failed", "reason=" + signed.reason() + " keyId=" + signed.keyId());
                        }
                    } else if (isAuthorized(ex)) {
                        audit.info(ctx, "auth.ok", "authorized bearer");
                        authorized = true;
                    } else {
                        audit.warn(ctx, "auth.failed", "unauthorized bearer");
                    }
                }
            }

            if (!authorized) {
                HostSecurityGuard.AuthFailureResult fail = security.recordAuthFailure(ctx.remoteIp());

                if (fail.trusted()) {
                    audit.warn(ctx, "auth.failed.trusted", "unauthorized trusted-ip");
                } else if (fail.banned()) {
                    audit.warn(ctx, "ip.banned",
                            "failures=" + fail.failures() + " banSeconds=" + fail.banSeconds());
                } else {
                    audit.warn(ctx, "auth.failed.count",
                            "failures=" + fail.failures());
                }

                writeError(ex, ctx, 401, "unauthorized", "Missing or invalid authorization.");
                return;
            }

            security.recordAuthSuccess(ctx.remoteIp());

            handler.handle(ctx, rawBody);

            long took = System.currentTimeMillis() - ctx.startedAtMs();
            audit.info(ctx, "request.ok", "completed in " + took + "ms");

        } catch (IllegalArgumentException e) {
            audit.warn(ctx, "request.bad", e.getMessage());
            writeError(ex, ctx, 400, "bad_request", e.getMessage());

        } catch (java.nio.file.NoSuchFileException e) {
            audit.warn(ctx, "request.not_found", e.getMessage());
            writeError(ex, ctx, 404, "not_found", e.getMessage());

        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();

            if (msg.startsWith("Not Running:")) {
                audit.warn(ctx, "request.not_running", msg);
                writeError(ex, ctx, 409, "not_running", msg);
            } else {
                audit.error(ctx, "request.io_error", e.getClass().getSimpleName() + ": " + msg);
                writeError(ex, ctx, 500, "io_error", "I/O error.");
            }
        } catch (Exception e){
            audit.error(ctx, "request.error", e.getClass().getSimpleName() + ": " + e.getMessage());
            writeError(ex, ctx, 500, "internal_error", "Internal server error.");
        }
    }

    @FunctionalInterface
    private interface ThrowingAuthedHandler {
        void handle(RequestContext ctx, String rawBody) throws Exception;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (var in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private RequestContext beginContext(com.sun.net.httpserver.HttpExchange ex) {
        String ip = "-";
        try {
            if (ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null) {
                ip = ex.getRemoteAddress().getAddress().getHostAddress();
            } else if (ex.getRemoteAddress() != null) {
                ip = String.valueOf(ex.getRemoteAddress());
            }
        } catch (Exception ignored) {
        }

        return new RequestContext(
                java.util.UUID.randomUUID().toString(),
                ex.getRequestMethod(),
                ex.getRequestURI().getPath(),
                ip,
                System.currentTimeMillis()
        );
    }

    private void writeError(com.sun.net.httpserver.HttpExchange ex, RequestContext ctx, int status, String error, String message) throws java.io.IOException {
        String json = om.writeValueAsString(ApiError.of(error, message, ctx.requestId()));
        writeJson(ex, status, json);
    }

    private boolean isAuthorized(com.sun.net.httpserver.HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || auth.isBlank()) return false;

        String prefix = "Bearer ";
        if (!auth.startsWith(prefix)) return false;

        String token = auth.substring(prefix.length()).trim();
        return token.equals(cfg.token());
    }

    @FunctionalInterface interface IoRunnable { void run() throws Exception; }
}