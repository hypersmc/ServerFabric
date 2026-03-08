package dev.jumpwatch.serverfabric.host.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jumpwatch.serverfabric.host.instance.InstanceManager;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Properties;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

public final class SchedulerService {

    private final InstanceManager mgr;
    private final Path rootPath;

    private final ObjectMapper om;
    private Scheduler quartz;

    public SchedulerService(InstanceManager mgr, Path rootPath) {
        this.mgr = mgr;
        this.rootPath = rootPath;

        this.om = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void startIfPresent() throws Exception {
        Path cfgFile = rootPath.resolve("schedule.json");
        if (!Files.exists(cfgFile)) {
            System.out.println("[ServerFabric-Host] No schedule.json found; scheduler disabled.");
            return;
        }

        ScheduleConfig cfg = om.readValue(cfgFile.toFile(), ScheduleConfig.class);
        if (cfg == null || !cfg.enabled) {
            System.out.println("[ServerFabric-Host] Scheduler disabled by config.");
            return;
        }

        // Quartz in-memory (no DB)
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "ServerFabricHostScheduler");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "4");
        props.setProperty("org.quartz.threadPool.threadPriority", "5");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

        quartz = new StdSchedulerFactory(props).getScheduler();
        quartz.start();

        // Boot catch-up (RUN_ONCE)
        runCatchUp(cfg);

        // Register future schedules
        registerAll(cfg);

        System.out.println("[ServerFabric-Host] Scheduler started with " + cfg.tasks.size() + " task(s).");
    }

    public void shutdown() {
        try {
            if (quartz != null) quartz.shutdown(true);
        } catch (Exception ignored) {}
    }

    private void runCatchUp(ScheduleConfig cfg) throws Exception {
        ScheduleState state = loadState();

        ZoneId zone = ZoneId.of(cfg.timezone);
        long nowMs = System.currentTimeMillis();
        long windowMs = cfg.catchUpWindowHours <= 0 ? 0 : cfg.catchUpWindowHours * 3600_000L;

        CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

        for (ScheduleConfig.Task t : cfg.tasks) {
            if (t == null || !t.enabled) continue;
            if (t.id == null || t.id.isBlank()) continue;
            if (t.cron == null || t.cron.isBlank()) continue;

            long last = state.lastRun.getOrDefault(t.id, 0L);

            // Safety: ignore very old missed triggers
            if (windowMs > 0 && last > 0 && (nowMs - last) > windowMs) {
                System.out.println("[Scheduler] catch-up skipped (too old) task=" + t.id);
                continue;
            }

            // Did we miss at least one fire time since last?
            boolean missed = missedAtLeastOnce(parser, t.cron, last, nowMs, zone);
            if (!missed) continue;

            System.out.println("[Scheduler] catch-up running once for task=" + t.id);
            runTaskNow(cfg, t);

            // Update lastRun after successful run
            state.lastRun.put(t.id, System.currentTimeMillis());
            saveState(state);
        }
    }

    private boolean missedAtLeastOnce(CronParser parser, String quartzCron, long lastRunMs, long nowMs, ZoneId zone) {
        try {
            Cron cron = parser.parse(quartzCron);
            cron.validate();

            var execTime = com.cronutils.model.time.ExecutionTime.forCron(cron);

            // If we've never run before: treat "missed" as false (don't run on first boot)
            // Change to true if you want "run immediately on first ever boot".
            if (lastRunMs <= 0) return false;

            ZonedDateTime last = ZonedDateTime.ofInstant(new Date(lastRunMs).toInstant(), zone);
            ZonedDateTime now = ZonedDateTime.ofInstant(new Date(nowMs).toInstant(), zone);

            // Find next execution after lastRun
            var next = execTime.nextExecution(last);
            return next.isPresent() && !next.get().isAfter(now);
        } catch (Exception e) {
            System.out.println("[Scheduler] invalid cron: " + quartzCron + " err=" + e.getMessage());
            return false;
        }
    }

    private void registerAll(ScheduleConfig cfg) throws Exception {
        for (ScheduleConfig.Task t : cfg.tasks) {
            if (t == null || !t.enabled) continue;
            if (t.id == null || t.id.isBlank()) continue;
            if (t.cron == null || t.cron.isBlank()) continue;

            JobDetail job = newJob(ScheduleJob.class)
                    .withIdentity("job-" + t.id)
                    .usingJobData("taskId", t.id)
                    .build();

            // Store config in scheduler context (simple approach)
            quartz.getContext().put("mgr", mgr);
            quartz.getContext().put("rootPath", rootPath.toString());

            Trigger trigger = newTrigger()
                    .withIdentity("trigger-" + t.id)
                    .withSchedule(CronScheduleBuilder.cronSchedule(t.cron))
                    .build();

            quartz.scheduleJob(job, trigger);
        }
    }

    private void runTaskNow(ScheduleConfig cfg, ScheduleConfig.Task t) throws Exception {
        // Execute in current thread for catch-up; actions are sequential.
        // If you want catch-up async, wrap in a thread pool, but RUN_ONCE is usually fine.
        ScheduleExecutor.execute(mgr, t);
    }

    private ScheduleState loadState() {
        try {
            Path f = rootPath.resolve("schedule_state.json");
            if (!Files.exists(f)) return new ScheduleState();
            return om.readValue(f.toFile(), ScheduleState.class);
        } catch (Exception e) {
            return new ScheduleState();
        }
    }

    private void saveState(ScheduleState state) {
        try {
            Path f = rootPath.resolve("schedule_state.json");
            om.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), state);
        } catch (Exception ignored) {}
    }
}