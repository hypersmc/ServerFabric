package dev.jumpwatch.serverfabric.client;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class StartWatchTask extends BukkitRunnable {

    private final DynClientPlugin plugin;
    private final Player player;
    private final String instanceName;

    private long startedAtMs = System.currentTimeMillis();
    private int attempts = 0;
    private long nextDelayTicks = 10; // 0.5s
    private boolean informedTimeout = false;

    public StartWatchTask(DynClientPlugin plugin, Player player, String instanceName) {
        this.plugin = plugin;
        this.player = player;
        this.instanceName = instanceName;
    }

    @Override
    public void run() {
        if (!player.isOnline()) {
            cancel();
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAtMs;
        if (elapsed > 25_000) { // 25s timeout
            if (!informedTimeout) {
                player.sendMessage("§cNo response from proxy/host. The server may be offline.");
                informedTimeout = true;
            }
            cancel();
            return;
        }

        // Quiet request (no chat)
        plugin.messenger().requestStatus(player);

        attempts++;

        // Exponential-ish backoff: 0.5s, 1s, 2s, 4s, 6s, 6s...
        nextDelayTicks = switch (attempts) {
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 40;
            case 4 -> 80;
            default -> 120; // cap at 6s
        };

        // reschedule self
        cancel();
        runTaskLater(plugin, nextDelayTicks);
    }
}