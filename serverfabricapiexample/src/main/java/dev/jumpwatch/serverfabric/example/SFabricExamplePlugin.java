package dev.jumpwatch.serverfabric.example;

import dev.jumpwatch.serverfabric.api.SFabric;
import dev.jumpwatch.serverfabric.api.SFabricAPI;
import dev.jumpwatch.serverfabric.api.model.SFabricHost;
import dev.jumpwatch.serverfabric.api.model.SFabricInstance;
import dev.jumpwatch.serverfabric.api.model.SFabricInstanceStats;
import dev.jumpwatch.serverfabric.api.model.SFabricTemplate;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.Optional;

public final class SFabricExamplePlugin extends Plugin {

    @Override
    public void onEnable() {
        getProxy().getPluginManager().registerCommand(this, new SFabricApiCommand());
        getLogger().info("SFabricExample enabled.");
    }

    private final class SFabricApiCommand extends Command {
        public SFabricApiCommand() {
            super("sfapi");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!SFabric.isAvailable()) {
                sender.sendMessage("§cSFabric API is not available.".replace('§', '\u00A7'));
                return;
            }

            SFabricAPI api = SFabric.get();

            if (args.length == 0) {
                sender.sendMessage("§e/sfapi hosts".replace('§', '\u00A7'));
                sender.sendMessage("§e/sfapi instances".replace('§', '\u00A7'));
                sender.sendMessage("§e/sfapi templates".replace('§', '\u00A7'));
                sender.sendMessage("§e/sfapi stats <instance>".replace('§', '\u00A7'));
                sender.sendMessage("§e/sfapi start <instance>".replace('§', '\u00A7'));
                sender.sendMessage("§e/sfapi stop <instance>".replace('§', '\u00A7'));
                return;
            }

            switch (args[0].toLowerCase()) {
                case "hosts" -> {
                    for (SFabricHost host : api.getHosts()) {
                        sender.sendMessage(("§aHost§7: " + host.id() + " §8| §7connect=" + host.connectHost())
                                .replace('§', '\u00A7'));
                    }
                }

                case "instances" -> {
                    for (SFabricInstance inst : api.getInstances()) {
                        sender.sendMessage(("§aInstance§7: " + inst.name()
                                + " §8| §7host=" + inst.hostId()
                                + " §8| §7state=" + inst.state()
                                + " §8| §7port=" + inst.port())
                                .replace('§', '\u00A7'));
                    }
                }

                case "templates" -> {
                    for (SFabricTemplate tpl : api.getTemplates()) {
                        sender.sendMessage(("§aTemplate§7: " + tpl.name()
                                + " §8| §7host=" + tpl.hostId())
                                .replace('§', '\u00A7'));
                    }
                }

                case "stats" -> {
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /sfapi stats <instance>".replace('§', '\u00A7'));
                        return;
                    }

                    Optional<SFabricInstanceStats> stats = api.getInstanceStats(args[1]);
                    if (stats.isEmpty()) {
                        sender.sendMessage("§cNo stats found.".replace('§', '\u00A7'));
                        return;
                    }

                    SFabricInstanceStats s = stats.get();
                    sender.sendMessage(("§aStats for §f" + s.name()).replace('§', '\u00A7'));
                    sender.sendMessage(("§7State: §f" + s.state()).replace('§', '\u00A7'));
                    sender.sendMessage(("§7PID: §f" + s.pid()).replace('§', '\u00A7'));
                    sender.sendMessage(("§7Uptime: §f" + s.uptimeMs() + "ms").replace('§', '\u00A7'));
                    sender.sendMessage(("§7RAM: §f" + s.memoryRssBytes() + " bytes").replace('§', '\u00A7'));
                    sender.sendMessage(("§7Disk: §f" + s.diskUsageBytes() + " bytes").replace('§', '\u00A7'));
                }

                case "start" -> {
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /sfapi start <instance>".replace('§', '\u00A7'));
                        return;
                    }

                    boolean ok = api.startInstance(args[1]);
                    sender.sendMessage((ok ? "§aStart requested." : "§cStart failed.").replace('§', '\u00A7'));
                }

                case "stop" -> {
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /sfapi stop <instance>".replace('§', '\u00A7'));
                        return;
                    }

                    boolean ok = api.stopInstance(args[1]);
                    sender.sendMessage((ok ? "§aStop requested." : "§cStop failed.").replace('§', '\u00A7'));
                }

                default -> sender.sendMessage("§cUnknown subcommand.".replace('§', '\u00A7'));
            }
        }
    }
}
