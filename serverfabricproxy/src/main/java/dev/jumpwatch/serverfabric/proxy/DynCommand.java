package dev.jumpwatch.serverfabric.proxy;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public final class DynCommand extends Command {

    private final DynProxyPlugin plugin;
    private final HostClient host;

    public DynCommand(DynProxyPlugin plugin, HostClient host) {
        super("dynhost", "dyn.admin", "dynhost");
        this.plugin = plugin;
        this.host = host;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /dyn create <template> <name> | start <name> | stop <name> | delete <name> | list");
            return;
        }

        String sub = args[0].toLowerCase();

        try {
            switch (sub) {
                case "play": {
                    if (!(sender instanceof ProxiedPlayer)) {
                        sender.sendMessage(ChatColor.RED + "Players only.");
                        return;
                    }
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Usage: /dyn play <template>");
                        return;
                    }

                    ProxiedPlayer player = (ProxiedPlayer) sender;
                    String template = args[1];

                    // create a unique instance name
                    String name = template + "-" + (System.currentTimeMillis() % 100000);

                    sender.sendMessage(ChatColor.YELLOW + "Creating " + name + " from template " + template + "...");

                    ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
                        try {
                            HostClient.CreateResponse created = host.create(template, name);

                            // register into proxy (main thread)
                            ProxyServer.getInstance().getScheduler().runAsync(plugin, () ->
                                    plugin.registerServer(created.name, "127.0.0.1", created.port)
                            );

                            host.start(created.name);

                            // wait for readiness
                            boolean ready = waitUntilRunning(created.name, 60_000);

                            if (!ready) {
                                ProxyServer.getInstance().getScheduler().runAsync(plugin, () ->
                                        player.sendMessage(ChatColor.RED + "Timed out waiting for server to start.")
                                );
                                return;
                            }

                            // connect on main thread
                            ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
                                net.md_5.bungee.api.config.ServerInfo info = ProxyServer.getInstance().getServers().get(created.name);
                                if (info == null) {
                                    player.sendMessage(ChatColor.RED + "Server not registered in proxy.");
                                    return;
                                }
                                player.sendMessage(ChatColor.GREEN + "Sending you to " + created.name + "!");
                                player.connect(info);
                            });

                        } catch (Exception e) {
                            ProxyServer.getInstance().getScheduler().runAsync(plugin, () ->
                                    player.sendMessage(ChatColor.RED + "Error: " + e.getMessage())
                            );
                        }
                    });

                    break;
                }
                case "create": {
                    if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /dyn create <template> <name>"); return; }
                    String template = args[1];
                    String name = args[2];

                    HostClient.CreateResponse res = host.create(template, name);
                    plugin.registerServer(res.name, "127.0.0.1", res.port);

                    sender.sendMessage(ChatColor.GREEN + "Created " + res.name + " on port " + res.port);
                    break;
                }
                case "start": {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /dyn start <name>"); return; }
                    String name = args[1];
                    host.start(name);
                    sender.sendMessage(ChatColor.GREEN + "Starting " + name);
                    break;
                }
                case "stop": {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /dyn stop <name>"); return; }
                    String name = args[1];
                    host.stop(name);
                    sender.sendMessage(ChatColor.GREEN + "Stopping " + name);
                    break;
                }
                case "delete": {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /dyn delete <name>"); return; }
                    String name = args[1];
                    host.delete(name);
                    plugin.unregisterServer(name);
                    sender.sendMessage(ChatColor.GREEN + "Deleted " + name);
                    break;
                }
                case "list": {
                    String json = host.statusJson();

                    // very naive parse: look for {"name":"X","port":123,"state":"RUNNING"}
                    sender.sendMessage(ChatColor.YELLOW + "Instances:");
                    String[] parts = json.split("\\{");
                    for (String p : parts) {
                        if (!p.contains("\"name\"")) continue;
                        String name = grab(p, "\"name\":\"", "\"");
                        String portStr = grab(p, "\"port\":", ",");
                        String state = grab(p, "\"state\":\"", "\"");
                        sender.sendMessage(ChatColor.GRAY + "- " + name + " : " + portStr.replaceAll("[^0-9]", "") + " : " + state);
                    }
                    break;
                }
                default:
                    sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Error: " + e.getMessage());
            plugin.getLogger().severe("Command failed: " + e);
        }
    }

    private static String grab(String src, String left, String right) {
        int a = src.indexOf(left);
        if (a < 0) return "";
        a += left.length();
        int b = src.indexOf(right, a);
        if (b < 0) b = src.length();
        return src.substring(a, b);
    }
    private boolean waitUntilRunning(String name, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            String state = host.getState(name); // we’ll add this below
            if ("RUNNING".equalsIgnoreCase(state)) return true;
            if ("CRASHED".equalsIgnoreCase(state)) throw new Exception("Server crashed while starting");
            Thread.sleep(500);
        }
        return false;
    }
}