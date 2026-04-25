package dev.jumpwatch.serverfabric.proxy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HostClient {
    private final String baseUrl;
    private final String token;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final String signedKeyId;
    private final byte[] signedSecretBytes;

    public HostClient(String baseUrl, String token, String signedKeyId, String signedSecret) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.token = token == null ? "" : token;
        this.signedKeyId = signedKeyId == null ? "" : signedKeyId.trim();
        this.signedSecretBytes = signedSecret == null
                ? new byte[0]
                : signedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public record CreateResponse(String name, int port) {}


    public CreateResponse create(String template, String name) throws IOException {
        return create(template, name, null);
    }

    public CreateResponse create(String template, String name, String version) throws IOException {
        String body;
        if (version == null || version.isBlank()) {
            body = "{\"template\":\"" + esc(template) + "\",\"name\":\"" + esc(name) + "\"}";
        } else {
            body = "{\"template\":\"" + esc(template) + "\",\"name\":\"" + esc(name) + "\",\"version\":\"" + esc(version) + "\"}";
        }

        String json = post("/server/create", body);
        return om.readValue(json, CreateResponse.class);
    }

    public void start(String name) throws IOException { post("/server/start", "{\"name\":\"" + esc(name) + "\"}"); }
    public void stop(String name) throws IOException  { post("/server/stop",  "{\"name\":\"" + esc(name) + "\"}"); }
    public void delete(String name) throws IOException{ post("/server/delete","{\"name\":\"" + esc(name) + "\"}"); }
    public void restart(String name) throws IOException { String body = "{\"name\":\"" + esc(name) + "\"}"; post("/server/restart", body); }
    public void kill(String name) throws IOException { post("/server/kill", "{\"name\":\"" + esc(name) + "\"}" );  }
    public HostVersionInfo version() throws IOException {
        String json = get("/version");
        return om.readValue(json, HostVersionInfo.class);
    }

    private String post(String path, String body) throws IOException {
        String normalizedBody = body == null ? "" : body;

        try {
            java.net.HttpURLConnection con =
                    (java.net.HttpURLConnection) new java.net.URL(baseUrl + path).openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(10_000);
            con.setReadTimeout(60_000);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("Accept", "application/json");

            // keep bearer during transition
            if (token != null && !token.isBlank()) {
                con.setRequestProperty("Authorization", "Bearer " + token);
            }

            try {
                Map<String, String> signed = buildSignedHeaders("POST", path, normalizedBody);
                for (Map.Entry<String, String> entry : signed.entrySet()) {
                    con.setRequestProperty(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                throw new IOException("Failed to build signed headers: " + e.getMessage(), e);
            }

            try (java.io.OutputStream os = con.getOutputStream()) {
                os.write(normalizedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            java.io.InputStream in = code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream();
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            if (code < 200 || code >= 300) {
                throw hostError(code, json);
            }

            return json;
        } catch (java.net.MalformedURLException e) {
            throw new IOException("Bad URL for path " + path, e);
        }
    }


    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String extract(String src, String left, String right) throws IOException {
        int a = src.indexOf(left);
        if (a < 0) throw new IOException("Bad response: " + src);
        a += left.length();
        int b = src.indexOf(right, a);
        if (b < 0) b = src.length();
        return src.substring(a, b);
    }

    public String statusJson() throws IOException {
        return get("/status");
    }

    private String get(String path) throws IOException {
        String body = "";

        try {
            java.net.HttpURLConnection con =
                    (java.net.HttpURLConnection) new java.net.URL(baseUrl + path).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(10_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("Accept", "application/json");

            // keep bearer during transition
            if (token != null && !token.isBlank()) {
                con.setRequestProperty("Authorization", "Bearer " + token);
            }

            try {
                Map<String, String> signed = buildSignedHeaders("GET", path, body);
                for (Map.Entry<String, String> entry : signed.entrySet()) {
                    con.setRequestProperty(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                throw new IOException("Failed to build signed headers: " + e.getMessage(), e);
            }

            int code = con.getResponseCode();
            java.io.InputStream in = code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream();
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            if (code < 200 || code >= 300) {
                throw hostError(code, json);
            }

            return json;
        } catch (java.net.MalformedURLException e) {
            throw new IOException("Bad URL for path " + path, e);
        }
    }

    public StatusResponse status() throws IOException {
        String json = statusJson();

        String hostId = extract(json, "\"hostId\":\"", "\"");
        if (hostId == null) hostId = "";

        List<InstanceStatus> list = new ArrayList<>();

        String[] parts = json.split("\\{");
        for (String p : parts) {
            if (!p.contains("\"name\"")) continue;

            String name = grab(p, "\"name\":\"", "\"");

            String portStr = grab(p, "\"port\":", ",");
            if (portStr.isEmpty()) portStr = grab(p, "\"port\":", "}");

            String state = grab(p, "\"state\":\"", "\"");

            int port = 0;
            try { port = Integer.parseInt(portStr.replaceAll("[^0-9]", "")); }
            catch (Exception ignored) {}

            if (!name.isEmpty() && port > 0) {
                list.add(new InstanceStatus(name, port, state.isEmpty() ? "UNKNOWN" : state));
            }
        }

        return new StatusResponse(hostId, list);
    }

    public static final class StatusResponse {
        public final String hostId;
        public final List<InstanceStatus> instances;
        public StatusResponse(String hostId, List<InstanceStatus> instances) {
            this.hostId = hostId;
            this.instances = instances;
        }
    }

    public static final class InstanceStatus {
        public final String name;
        public final int port;
        public final String state;
        public InstanceStatus(String name, int port, String state) {
            this.name = name;
            this.port = port;
            this.state = state;
        }
    }

    private static String grab(String src, String left, String right) {
        int a = src.indexOf(left);
        if (a < 0) return "";
        a += left.length();
        int b = src.indexOf(right, a);
        if (b < 0) b = src.length();
        return src.substring(a, b);
    }

    public String getState(String name) throws IOException {
        String json = statusJson();

        // very naive parse: find object containing "name":"<name>"
        String needle = "\"name\":\"" + esc(name) + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return "UNKNOWN";

        int stateIdx = json.indexOf("\"state\":\"", idx);
        if (stateIdx < 0) return "UNKNOWN";
        stateIdx += "\"state\":\"".length();

        int end = json.indexOf("\"", stateIdx);
        if (end < 0) return "UNKNOWN";

        return json.substring(stateIdx, end);
    }

    public TemplatesResponse templates() throws IOException {
        String json = get("/templates");

        String hostId = extract(json, "\"hostId\":\"", "\"");
        List<String> templates = new ArrayList<>();

        // crude parsing: find "templates":[...]
        int a = json.indexOf("\"templates\"");
        if (a >= 0) {
            int lb = json.indexOf("[", a);
            int rb = json.indexOf("]", a);
            if (lb >= 0 && rb > lb) {
                String inside = json.substring(lb + 1, rb).trim();
                if (!inside.isEmpty()) {
                    for (String part : inside.split(",")) {
                        String t = part.trim();
                        if (t.startsWith("\"")) t = t.substring(1);
                        if (t.endsWith("\"")) t = t.substring(0, t.length() - 1);
                        if (!t.isBlank()) templates.add(t);
                    }
                }
            }
        }

        return new TemplatesResponse(hostId, templates);
    }

    public static final class TemplatesResponse {
        public final String hostId;
        public final List<String> templates;
        public TemplatesResponse(String hostId, List<String> templates) {
            this.hostId = hostId;
            this.templates = templates;
        }
    }

    public void command(String name, String cmd) throws IOException {
        post("/server/command", "{\"name\":\"" + esc(name) + "\",\"cmd\":\"" + esc(cmd) + "\"}");
    }

    public static final class InstanceStatsResponse {
        public String name;
        public String state;
        public boolean alive;
        public boolean stopping;
        public long pid;
        public long uptimeMs;
        public long startedAtMs;
        public long lastOutputAtMs;
        public long memoryRssBytes;
        public long memoryVirtualBytes;
        public long diskUsageBytes;
    }
    public InstanceStatsResponse stats(String name) throws IOException {
        String body = "{\"name\":\"" + esc(name) + "\"}";
        String json = post("/server/stats", body);
        return om.readValue(json, InstanceStatsResponse.class);
    }

    public static final class ApiErrorResponse {
        public boolean ok;
        public String error;
        public String message;
        public String requestId;
    }
    private IOException hostError(int code, String json){
        try {
            ApiErrorResponse err = om.readValue(json, ApiErrorResponse.class);
            String error = err.error == null ? "unknown_error" : err.error;
            String message = err.message == null ? "Unknown error." : err.message;
            return new IOException("HOST_API " + code + " " + error + ": " + message);
        } catch (Exception ignored){
            return new IOException("HTTP " + code + ": " + json);
        }
    }

    private Map<String, String> buildSignedHeaders(String method, String path, String body) throws Exception {
        if (!isSigningConfigured()) {
            return java.util.Collections.emptyMap();
        }

        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedPath = path == null ? "/" : path;
        String normalizedBody = body == null ? "" : body;

        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = java.util.UUID.randomUUID().toString();
        String bodySha256 = sha256Hex(normalizedBody);

        String canonical = normalizedMethod
                + "\n" + normalizedPath
                + "\n" + timestamp
                + "\n" + nonce
                + "\n" + bodySha256;

        String signature = hmacSha256Hex(signedSecretBytes, canonical);

        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("X-SFabric-KeyId", signedKeyId);
        headers.put("X-SFabric-Timestamp", timestamp);
        headers.put("X-SFabric-Nonce", nonce);
        headers.put("X-SFabric-Body-SHA256", bodySha256);
        headers.put("X-SFabric-Signature", signature);
        return headers;
    }

    private static String sha256Hex(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(byte[] secret, String canonical) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        byte[] out = mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(out);
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private boolean isSigningConfigured() {
        return !signedKeyId.isBlank() && signedSecretBytes.length > 0;
    }

}