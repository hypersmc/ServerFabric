package dev.jumpwatch.serverfabric.host;

import dev.jumpwatch.serverfabric.host.InstanceManager;

import java.util.Locale;

public final class ScheduleExecutor {

    private ScheduleExecutor() {}

    public static void execute(InstanceManager mgr, ScheduleConfig.Task task) throws Exception {
        if (task.offsetMs > 0) Thread.sleep(task.offsetMs);

        for (ScheduleConfig.Action a : task.actions) {
            if (a == null || a.type == null) continue;

            if (a.delayMs > 0) Thread.sleep(a.delayMs);

            String type = a.type.trim().toUpperCase(Locale.ROOT);
            String inst = a.instance;

            switch (type) {
                case "COMMAND" -> {
                    if (inst == null || inst.isBlank()) continue;
                    if (a.command == null || a.command.isBlank()) continue;
                    mgr.command(inst, a.command);
                }
                case "START" -> {
                    if (inst == null || inst.isBlank()) continue;
                    mgr.start(inst);
                }
                case "STOP" -> {
                    if (inst == null || inst.isBlank()) continue;
                    mgr.stop(inst);
                    if (a.waitMs > 0) Thread.sleep(a.waitMs);
                }
                case "RESTART" -> {
                    if (inst == null || inst.isBlank()) continue;
                    mgr.stop(inst);
                    if (a.waitMs > 0) Thread.sleep(a.waitMs);
                    mgr.start(inst);
                }
                default -> {
                    // unknown action type; ignore
                }
            }
        }
    }
}