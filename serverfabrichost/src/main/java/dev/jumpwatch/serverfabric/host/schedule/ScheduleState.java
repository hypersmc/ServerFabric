package dev.jumpwatch.serverfabric.host.schedule;

import java.util.HashMap;
import java.util.Map;

public final class ScheduleState {
    public Map<String, Long> lastRun = new HashMap<>();
}
