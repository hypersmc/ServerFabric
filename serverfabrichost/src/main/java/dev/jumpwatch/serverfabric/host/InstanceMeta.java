package dev.jumpwatch.serverfabric.host;

public final class InstanceMeta {
    public String name;
    public String template;
    public int port;
    public String jar;

    // new
    public boolean autoStart = false;
    public String lastState = "STOPPED";
    public long lastUpdated = 0L;

    public InstanceMeta() {}

    public InstanceMeta(String name, String template, int port, String jar) {
        this.name = name;
        this.template = template;
        this.port = port;
        this.jar = jar;
        this.autoStart = false;
        this.lastState = "STOPPED";
        this.lastUpdated = System.currentTimeMillis();
    }
}