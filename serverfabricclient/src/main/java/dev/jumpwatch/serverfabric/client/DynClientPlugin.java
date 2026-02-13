package dev.jumpwatch.serverfabric.client;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DynClientPlugin extends JavaPlugin {

    public static final String CHANNEL = "serverfabric:main";

    private DynGui gui;
    private DynMessenger messenger;
    private CommandInputManager commandInput;

    @Override
    public void onEnable() {
        this.gui = new DynGui(this);
        this.messenger = new DynMessenger(this, gui);

        // register plugin messaging
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", messenger); // Needed for bungee
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, messenger);
        this.commandInput = new CommandInputManager();
        Bukkit.getPluginManager().registerEvents(new ChatCommandListener(this, commandInput), this);
        gui.setCommandInput(commandInput);
        PluginCommand cmd = getCommand("serf");
        if (cmd != null) {
            cmd.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player p)) return true;
                if (!p.hasPermission("serf.gui")) {
                    p.sendMessage("§cNo permission.");
                    return true;
                }
                gui.open(p);
                messenger.requestStatus(p);
                return true;
            });
        }

        Bukkit.getPluginManager().registerEvents(gui, this);

        getLogger().info("ServerFabric-Client enabled");
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    public DynMessenger messenger() { return messenger; }
}