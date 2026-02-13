package dev.jumpwatch.serverfabric.client;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.*;

public final class DynMessenger implements PluginMessageListener {

    private final DynClientPlugin plugin;
    private final DynGui gui;

    public DynMessenger(DynClientPlugin plugin, DynGui gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    public void requestStatus(Player player) {
        send(player, out -> {
            out.writeUTF("STATUS_REQUEST");
            out.writeUTF(player.getUniqueId().toString());
        });
    }

    public void sendAction(Player player, String actionType, String instanceName, String template) {
        send(player, out -> {
            out.writeUTF("ACTION");
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(actionType);
            out.writeUTF(instanceName == null ? "" : instanceName);
            out.writeUTF(template == null ? "" : template);
        });
    }

    private void send(Player player, IoConsumer<DataOutputStream> writer) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {

            writer.accept(out);
            player.sendPluginMessage(plugin, DynClientPlugin.CHANNEL, baos.toByteArray());

        } catch (Exception e) {
            player.sendMessage("§cServerFabric-Client messaging error: " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player ignoredPlayer, byte[] message) {
        if (!DynClientPlugin.CHANNEL.equals(channel)) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String type = in.readUTF();

            if ("STATUS_RESPONSE".equals(type)) {
                UUID target = UUID.fromString(in.readUTF());
                Player targetPlayer = org.bukkit.Bukkit.getPlayer(target);
                if (targetPlayer == null) return;

                List<DynStatus.Instance> instances = new ArrayList<>();
                while (in.available() > 0) {
                    String name = in.readUTF();
                    int port = Integer.parseInt(in.readUTF());
                    String state = in.readUTF();
                    String hostId = in.readUTF();
                    instances.add(new DynStatus.Instance(name, port, state, hostId));
                }

                gui.setStatus(targetPlayer, new DynStatus(instances));
                return;
            }

            if ("ACTION_RESULT".equals(type)) {
                UUID target = UUID.fromString(in.readUTF());
                Player targetPlayer = org.bukkit.Bukkit.getPlayer(target);
                if (targetPlayer == null) return;

                boolean ok = Boolean.parseBoolean(in.readUTF());
                String msg = in.readUTF();

                targetPlayer.sendMessage(ok ? "§a" + msg : "§c" + msg);

                // refresh after action
                requestStatus(targetPlayer);
            }

            if ("TEMPLATES_RESPONSE".equals(type)) {
                UUID target = UUID.fromString(in.readUTF());
                Player targetPlayer = org.bukkit.Bukkit.getPlayer(target);
                if (targetPlayer == null) return;

                List<DynTemplates.Item> items = new ArrayList<>();
                while (in.available() > 0) {
                    String template = in.readUTF();
                    String hostId = in.readUTF();
                    items.add(new DynTemplates.Item(template, hostId));
                }
                gui.setTemplates(targetPlayer, new DynTemplates(items));
                return;
            }


        } catch (Exception e) {
            // can't reliably message a player here, just log
            plugin.getLogger().warning("ServerFabric-Client decode error: " + e.getMessage());
        }
    }

    public void connect(Player player, String serverName) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {

            out.writeUTF("Connect");
            out.writeUTF(serverName);

            player.sendPluginMessage(plugin, "BungeeCord", baos.toByteArray());
        } catch (Exception e) {
            player.sendMessage("§cFailed to connect: " + e.getMessage());
        }
    }

    public void requestTemplates(Player player) {
        send(player, out -> {
            out.writeUTF("TEMPLATES_REQUEST");
            out.writeUTF(player.getUniqueId().toString());
        });
    }

    @FunctionalInterface
    interface IoConsumer<T> { void accept(T t) throws Exception; }
}