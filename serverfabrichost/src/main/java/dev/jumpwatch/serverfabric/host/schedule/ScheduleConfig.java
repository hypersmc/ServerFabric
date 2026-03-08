package dev.jumpwatch.serverfabric.host.schedule;

import java.util.ArrayList;
import java.util.List;

public final class ScheduleConfig {
    public String timezone = "UTC";
    public boolean enabled = false;

    // If the host was down longer than this, we ignore missed fires (safety)
    public int catchUpWindowHours = 24;

    public List<Task> tasks = new ArrayList<>();

    public static final class Task {
        public String id;
        public boolean enabled = true;
        public String cron;      // Quartz cron (with seconds)
        public long offsetMs = 0;

        public List<Action> actions = new ArrayList<>();
    }

    public static final class Action {
        // COMMAND | START | STOP | RESTART
        public String type;

        public String instance;  // target instance name
        public String command;   // for COMMAND
        public long waitMs = 0;  // for STOP/RESTART sequencing
        public long delayMs = 0; // optional delay before this action
    }
}