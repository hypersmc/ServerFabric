package dev.jumpwatch.serverfabric.client;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class TimedTask extends BukkitRunnable {

    private int runs = 0;
    private final int maxRuns;
    private final Runnable task;

    public TimedTask(JavaPlugin plugin, int durationSeconds, Runnable task) {
        this.maxRuns = (durationSeconds * 20) / 10;
        this.task = task;
        runTaskTimer(plugin, 0L, 10L);
    }

    @Override
    public void run() {
        task.run();
        runs++;

        if (runs >= maxRuns) {
            cancel();
        }
    }
}
