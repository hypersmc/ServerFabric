package dev.jumpwatch.serverfabric.proxy;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.util.*;

public final class DynProxyMessaging implements Listener {

    private static final String CHANNEL = "serverfabric:main";

    private final DynProxyPlugin plugin;
    private final HostRegistry hosts;

    public DynProxyMessaging(DynProxyPlugin plugin, HostRegistry hosts) {
        this.plugin = plugin;
        this.hosts = hosts;
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getTag())) return;
        if (!(e.getSender() instanceof Server server)) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()))) {
            String type = in.readUTF();

            if ("STATUS_REQUEST".equals(type)) {
                int protocolVersion = in.readInt();
                String playerUuid = in.readUTF();

                handleStatusRequest(server, playerUuid);
                return;
            }

            if ("ACTION".equals(type)) {
                int protocolVersion = in.readInt();
                String playerUuid = in.readUTF();
                String actionType = in.readUTF();
                String instance = in.readUTF();
                String templateOrCmd = in.readUTF();
                handleAction(server, playerUuid, actionType, instance, templateOrCmd);
                return;
            }

            if ("STATS_REQUEST".equals(type)) {
                int protocolVersion = in.readInt();
                String playerUuid = in.readUTF();
                String instanceName = in.readUTF();

                handleStatsRequest(server, playerUuid, instanceName);
                return;
            }

            if ("TEMPLATES_REQUEST".equals(type)) {
                int protocolVersion = in.readInt();
                String playerUuid = in.readUTF();
                handleTemplatesRequest(server, playerUuid);
                return;
            }

        } catch (Exception ex) {
            plugin.getLogger().warning("ServerFabric-ProxyMessaging decode error: " + ex.getMessage());
        }
    }

    // ---------------- STATUS ----------------

    private void handleStatusRequest(Server server, String playerUuid) {
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                // Merge instances across ALL hosts into one STATUS_RESPONSE
                byte[] payload = buildMergedStatusResponse(playerUuid);

                server.getInfo().sendData(CHANNEL, payload, false);
            } catch (Exception e) {
                server.getInfo().sendData(CHANNEL, buildActionResult(playerUuid, false,
                        "Status error: " + e.getMessage()), false);
            }
        });
    }

    private byte[] buildMergedStatusResponse(String playerUuid) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("STATUS_RESPONSE");
            out.writeUTF(playerUuid);

            // Keep stable ordering in GUI
            List<WithHost> all = new ArrayList<>();

            for (HostRegistry.HostDef h : hosts.allHosts()) {
                HostClient.StatusResponse st = h.client().status();

                for (HostClient.InstanceStatus inst : st.instances) {
                    hosts.mapInstanceToHost(inst.name, h.id());
                    all.add(new WithHost(inst, h.id()));
                }
            }

            all.sort(Comparator.comparing(a -> a.inst.name.toLowerCase(Locale.ROOT)));

            for (WithHost x : all) {
                out.writeUTF(x.inst.name);
                out.writeUTF(String.valueOf(x.inst.port));
                out.writeUTF(x.inst.state == null ? "UNKNOWN" : x.inst.state);
                out.writeUTF(x.hostId); // NEW
            }
        }
        return baos.toByteArray();
    }

    // ---------------- ACTIONS ----------------

    private void handleAction(Server server, String playerUuid, String actionType, String instance, String templateOrCmd) {
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                String upper = actionType.toUpperCase(Locale.ROOT);

                switch (upper) {
                    case "START" -> {
                        HostRegistry.HostDef h = hostForExistingInstance(instance);
                        if (h == null) { reply(server, playerUuid, false, "Unknown host for " + instance); return; }

                        h.client().start(instance);
                        reply(server, playerUuid, true, "Starting " + instance);
                    }

                    case "STOP" -> {
                        HostRegistry.HostDef h = hostForExistingInstance(instance);
                        if (h == null) { reply(server, playerUuid, false, "Unknown host for " + instance); return; }

                        h.client().stop(instance);
                        reply(server, playerUuid, true, "Stopping " + instance);
                    }

                    case "DELETE" -> {
                        HostRegistry.HostDef h = hostForExistingInstance(instance);
                        if (h == null) { reply(server, playerUuid, false, "Unknown host for " + instance); return; }

                        h.client().delete(instance);
                        hosts.unmapInstance(instance);
                        plugin.unregisterServer(instance);

                        reply(server, playerUuid, true, "Deleted " + instance);
                    }

                    case "KILL" -> {
                        HostRegistry.HostDef h = hostForExistingInstance(instance);
                        if (h == null) { reply(server, playerUuid, false, "Unknown host for " + instance); return; }

                        h.client().kill(instance);
                        reply(server, playerUuid, true, "Killed " + instance);
                    }

                    case "COMMAND" -> {
                        HostRegistry.HostDef h = hostForExistingInstance(instance);
                        if (h == null) { reply(server, playerUuid, false, "Unknown host for " + instance); return; }

                        String cmd = templateOrCmd; // DynClient sends raw "op name" (no /)
                        h.client().command(instance, cmd);

                        reply(server, playerUuid, true, "Sent: " + (cmd.startsWith("/") ? cmd : "/" + cmd));
                    }

                    case "CREATE" -> {
                        // instance = desired name, templateOrCmd = template
                        HostRegistry.HostDef h = hosts.pickHostRoundRobin();
                        if (h == null) { reply(server, playerUuid, false, "No hosts configured"); return; }

                        String desired = instance;
                        if (desired == null || desired.isBlank()) {
                            desired = h.id() + "-" + templateOrCmd + "-" + (System.currentTimeMillis() % 100000);
                        }
                        var created = h.client().create(templateOrCmd, desired);

                        // Register routing to correct machine
                        plugin.registerServer(created.name(), h.connectHost(), created.port());

                        // Remember where it lives
                        hosts.mapInstanceToHost(created.name(), h.id());

                        reply(server, playerUuid, true, "Created " + created.name() + " on host " + h.id());
                    }

                    case "PLAY" -> {
                        // templateOrCmd = template, instance can be empty (auto name)
                        HostRegistry.HostDef h = hosts.pickHostRoundRobin();
                        if (h == null) { reply(server, playerUuid, false, "No hosts configured"); return; }

                        String template = templateOrCmd;
                        String name;
                        if (instance == null || instance.isBlank()) {
                            name = h.id() + "-" + template + "-" + (System.currentTimeMillis() % 100000);
                        } else {
                            name = instance;
                        }

                        if (ProxyServer.getInstance().getServers().containsKey(name)) {
                            reply(server, playerUuid, false, "Name already exists on proxy: " + name);
                            return;
                        }

                        var created = h.client().create(template, name);

                        plugin.registerServer(created.name(), h.connectHost(), created.port());
                        hosts.mapInstanceToHost(created.name(), h.id());

                        h.client().start(created.name());

                        reply(server, playerUuid, true, "Created+started " + created.name() + " on host " + h.id());
                    }

                    case "PLAY_ON" -> {
                        // instance = hostId, templateOrCmd = templateName
                        String hostId = instance;
                        String template = templateOrCmd;
                        String versionOverride = null;
                        if (template != null && template.contains("|")) {
                            String[] split = template.split("\\|", 2);
                            template = split[0];
                            versionOverride = split[1].isBlank() ? null : split[1].trim();
                        }

                        HostRegistry.HostDef h = hosts.getHost(hostId);
                        if (h == null) { reply(server, playerUuid, false, "Unknown host: " + hostId); return; }

                        String name = hostId + "-" + template + "-" + (System.currentTimeMillis() % 100000);

                        var created = h.client().create(template, name, versionOverride);

                        plugin.registerServer(created.name(), h.connectHost(), created.port());
                        hosts.mapInstanceToHost(created.name(), h.id());

                        h.client().start(created.name());

                        reply(server, playerUuid, true, "Created+started " + created.name() + " on host " + h.id());
                    }

                    default -> reply(server, playerUuid, false, "Unknown action: " + actionType);
                }

            } catch (Exception e) {
                reply(server, playerUuid, false, "Action error: " + e.getMessage());
            }
        });
    }

    private HostRegistry.HostDef hostForExistingInstance(String instance) {
        if (instance == null || instance.isBlank()) return null;
        String hostId = hosts.hostIdForInstance(instance);
        if (hostId == null) return null;
        return hosts.getHost(hostId);
    }

    // ---------------- helpers ----------------

    private void reply(Server server, String playerUuid, boolean ok, String message) {
        server.getInfo().sendData(CHANNEL, buildActionResult(playerUuid, ok, message), false);
    }

    private byte[] buildActionResult(String playerUuid, boolean ok, String message) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeUTF("ACTION_RESULT");
                out.writeUTF(playerUuid);
                out.writeUTF(Boolean.toString(ok));
                out.writeUTF(message == null ? "" : message);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static final class WithHost {
        final HostClient.InstanceStatus inst;
        final String hostId;
        WithHost(HostClient.InstanceStatus inst, String hostId) {
            this.inst = inst;
            this.hostId = hostId;
        }
    }

    private void handleTemplatesRequest(Server server, String playerUuid) {
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                byte[] payload = buildTemplatesResponse(playerUuid);
                server.getInfo().sendData(CHANNEL, payload, false);
            } catch (Exception e) {
                reply(server, playerUuid, false, "Templates error: " + e.getMessage());
            }
        });
    }

    private byte[] buildTemplatesResponse(String playerUuid) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("TEMPLATES_RESPONSE");
            out.writeUTF(playerUuid);

            // send pairs: templateName, hostId
            for (HostRegistry.HostDef h : hosts.allHosts()) {
                HostClient.TemplatesResponse tr = h.client().templates();
                for (String t : tr.templates) {
                    out.writeUTF(t);
                    out.writeUTF(h.id()); // use config host id
                }
            }
        }
        return baos.toByteArray();
    }

    private void handleStatsRequest(Server server, String playerUuid, String instanceName) {
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                String hostId = hosts.hostIdForInstance(instanceName);
                if (hostId == null) {
                    reply(server, playerUuid, false, "No host mapping for instance: " + instanceName);
                    return;
                }

                HostRegistry.HostDef h = hosts.getHost(hostId);
                if (h == null) {
                    reply(server, playerUuid, false, "Unknown host for instance: " + instanceName);
                    return;
                }

                HostClient.InstanceStatsResponse stats = h.client().stats(instanceName);
                server.getInfo().sendData(CHANNEL, buildStatsResponse(playerUuid, stats), false);

            } catch (Exception e) {
                reply(server, playerUuid, false, "Stats error: " + e.getMessage());
            }
        });
    }

    private byte[] buildStatsResponse(String playerUuid, HostClient.InstanceStatsResponse stats) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("STATS_RESPONSE");
            out.writeUTF(playerUuid);
            out.writeUTF(stats.name == null ? "" : stats.name);
            out.writeUTF(stats.state == null ? "UNKNOWN" : stats.state);
            out.writeUTF(String.valueOf(stats.alive));
            out.writeUTF(String.valueOf(stats.stopping));
            out.writeUTF(String.valueOf(stats.pid));
            out.writeUTF(String.valueOf(stats.uptimeMs));
            out.writeUTF(String.valueOf(stats.startedAtMs));
            out.writeUTF(String.valueOf(stats.lastOutputAtMs));
            out.writeUTF(String.valueOf(stats.memoryRssBytes));
            out.writeUTF(String.valueOf(stats.memoryVirtualBytes));
            out.writeUTF(String.valueOf(stats.diskUsageBytes));
        }
        return baos.toByteArray();
    }

}