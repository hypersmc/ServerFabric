package dev.jumpwatch.serverfabric.host;

import java.util.ArrayList;
import java.util.List;

public final class TemplateMeta {

    public String displayName = null;
    public String jar = null; // if null -> fallback to host default / existing behavior
    public Readiness readiness = new Readiness();

    public Jvm jvm = new Jvm();
    public Pool pool = new Pool();
    public Data data = new Data();

    public static final class Jvm {
        public List<String> args = new ArrayList<>(); // if empty -> fallback to host default
    }

    public static final class Pool {
        public boolean enabled = false;
        public int minIdle = 0;
        public int maxIdle = 0;
        public boolean warmupOnBoot = false;
        public String reusePolicy = "NONE";
    }

    public static final class Data {
        // safe default for existing templates:
        // if template.json missing, we won't use this anyway.
        public boolean persistent = true;
        public List<String> resetPaths = new ArrayList<>();
    }

    public static final class Readiness {
        // LOG_CONTAINS | TCP_PORT | NONE
        public String type = "LOG_CONTAINS";

        // for LOG_CONTAINS
        public String contains = "Done (";

        // for TCP_PORT
        public String host = "127.0.0.1";
        public long timeoutMs = 20000;
    }
}
