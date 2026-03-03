package dev.jumpwatch.serverfabric.host;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jumpwatch.serverfabric.host.InstanceManager;
import org.quartz.*;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ScheduleJob implements Job {

    private static final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            SchedulerContext sc = context.getScheduler().getContext();
            InstanceManager mgr = (InstanceManager) sc.get("mgr");
            Path root = Path.of((String) sc.get("rootPath"));

            ScheduleConfig cfg = loadConfig(root);
            if (cfg == null || !cfg.enabled) return;

            String taskId = context.getJobDetail().getJobDataMap().getString("taskId");
            ScheduleConfig.Task task = cfg.tasks.stream()
                    .filter(t -> t != null && t.enabled && taskId.equals(t.id))
                    .findFirst()
                    .orElse(null);

            if (task == null) return;

            ScheduleExecutor.execute(mgr, task);

            // Update state (lastRun)
            ScheduleState st = loadState(root);
            st.lastRun.put(taskId, System.currentTimeMillis());
            saveState(root, st);

        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }

    private ScheduleConfig loadConfig(Path root) throws Exception {
        Path f = root.resolve("schedule.json");
        if (!Files.exists(f)) return null;
        return om.readValue(f.toFile(), ScheduleConfig.class);
    }

    private ScheduleState loadState(Path root) {
        try {
            Path f = root.resolve("schedule_state.json");
            if (!Files.exists(f)) return new ScheduleState();
            return om.readValue(f.toFile(), ScheduleState.class);
        } catch (Exception e) {
            return new ScheduleState();
        }
    }

    private void saveState(Path root, ScheduleState state) {
        try {
            Path f = root.resolve("schedule_state.json");
            om.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), state);
        } catch (Exception ignored) {}
    }
}
