package dev.jumpwatch.serverfabric.host.template;

import java.util.ArrayList;
import java.util.List;

public final class TemplateMeta {
    public String displayName = null;
    public String jar = null;

    public String buildToolExec = null;
    public String serverVersion = null;

    public Readiness readiness = new Readiness();
    public Jvm jvm = new Jvm();
    public Pool pool = new Pool();
    public Data data = new Data();

    public static final class Jvm {
        public List<String> args = new ArrayList<>();
    }

    public static final class Pool {
        public boolean enabled = false;
        public int minIdle = 0;
        public int maxIdle = 0;
        public boolean warmupOnBoot = false;
        public String reusePolicy = "NONE";
    }

    public static final class Data {
        public boolean persistent = true;
        public List<String> resetPaths = new ArrayList<>();
    }

    public static final class Readiness {
        public String type = "LOG_CONTAINS";
        public String contains = "Done (";
        public String host = "127.0.0.1";
        public long timeoutMs = 20000;
    }
}