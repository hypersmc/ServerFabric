package dev.jumpwatch.serverfabric.proxy;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class HostClient {
    private final String baseUrl;
    private final String token;

    public HostClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public static final class CreateResponse {
        public final String name;
        public final int port;
        public CreateResponse(String name, int port) { this.name = name; this.port = port; }
    }

    public CreateResponse create(String template, String name) throws IOException {
        String json = "{\"template\":\"" + esc(template) + "\",\"name\":\"" + esc(name) + "\"}";
        String resp = post("/server/create", json);
        // tiny parse: {"name":"mg-001","port":25571}
        String rName = extract(resp, "\"name\":\"", "\"");
        int rPort = Integer.parseInt(extract(resp, "\"port\":", "}").replaceAll("[^0-9]", ""));
        return new CreateResponse(rName, rPort);
    }

    public void start(String name) throws IOException { post("/server/start", "{\"name\":\"" + esc(name) + "\"}"); }
    public void stop(String name) throws IOException  { post("/server/stop",  "{\"name\":\"" + esc(name) + "\"}"); }
    public void delete(String name) throws IOException{ post("/server/delete","{\"name\":\"" + esc(name) + "\"}"); }

    private String post(String path, String body) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = readAll(is);

        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + resp);
        return resp;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

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
        URL url = new URL(baseUrl + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Authorization", "Bearer " + token);

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = readAll(is);

        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + resp);
        return resp;
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
}