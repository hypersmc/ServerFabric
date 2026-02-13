package dev.jumpwatch.serverfabric.client;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatCommandListener implements Listener {

    private final DynClientPlugin plugin;
    private final CommandInputManager input;

    public ChatCommandListener(DynClientPlugin plugin, CommandInputManager input) {
        this.plugin = plugin;
        this.input = input;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!input.isPending(p.getUniqueId())) return;

        e.setCancelled(true);

        String instance = input.consume(p.getUniqueId());
        String msg = e.getMessage().trim();

        // allow cancel
        if (msg.equalsIgnoreCase("cancel")) {
            p.sendMessage("§7Command cancelled.");
            return;
        }


        String display = msg.startsWith("/") ? msg : "/" + msg;

        // plugin messaging must run on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.messenger().sendAction(p, "COMMAND", instance, msg);
            p.sendMessage("§aSent to §f" + instance + "§a: §f" + display);
        });
    }
}