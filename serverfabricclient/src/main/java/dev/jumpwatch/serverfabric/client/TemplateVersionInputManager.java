package dev.jumpwatch.serverfabric.client;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TemplateVersionInputManager implements Listener {

    private final DynClientPlugin plugin;
    private final Map<UUID, PendingTemplatePlay> pending = new HashMap<>();

    public TemplateVersionInputManager(DynClientPlugin plugin) {
        this.plugin = plugin;
    }

    public void begin(UUID playerId, String hostId, String templateName) {
        pending.put(playerId, new PendingTemplatePlay(hostId, templateName));
    }

    public boolean isWaiting(UUID playerId) {
        return pending.containsKey(playerId);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        PendingTemplatePlay req = pending.remove(e.getPlayer().getUniqueId());
        if (req == null) return;

        e.setCancelled(true);

        String msg = e.getMessage().trim();
        Player p = e.getPlayer();

        if (msg.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    p.sendMessage("§7Template version input cancelled."));
            return;
        }

        String payload;
        if (msg.equalsIgnoreCase("default")) {
            payload = req.templateName();
        } else {
            payload = req.templateName() + "|" + msg;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.messenger().sendAction(p, "PLAY_ON", req.hostId(), payload);

            if (msg.equalsIgnoreCase("default")) {
                p.sendMessage("§7Starting §f" + req.templateName()
                        + "§7 on host §f" + req.hostId()
                        + "§7 with default version.");
            } else {
                p.sendMessage("§7Starting §f" + req.templateName()
                        + "§7 on host §f" + req.hostId()
                        + "§7 using version §f" + msg + "§7. This might take a while...");
            }
        });
    }

    private record PendingTemplatePlay(String hostId, String templateName) {}
}