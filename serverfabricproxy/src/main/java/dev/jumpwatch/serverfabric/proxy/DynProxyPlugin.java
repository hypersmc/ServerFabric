package dev.jumpwatch.serverfabric.proxy;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public final class DynProxyPlugin extends Plugin {

    private HostRegistry hosts;
    private DynHostPoller poller;
    private final java.util.Set<String> dynamicServers =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        try {
            ensureDefaultConfig();
        } catch (Exception e) {
            getLogger().severe("Failed to init config: " + e.getMessage());
        }

        this.hosts = new HostRegistry();
        loadHostsFromConfig();

        ProxyServer.getInstance().registerChannel("serverfabric:main");
        ProxyServer.getInstance().getPluginManager().registerListener(this, new DynProxyMessaging(this, hosts));

        bootstrapServersFromAllHosts();

        long pollSeconds = loadPollIntervalSeconds();
        this.poller = new DynHostPoller(this, hosts);
        poller.start(pollSeconds);

        getLogger().info("Host polling enabled: every " + pollSeconds + "s");

        getLogger().info("ServerFabric-Proxy enabled with " + hosts.allHosts().size() + " host(s)");
    }

    public HostRegistry hostRegistry() {
        return hosts;
    }

    public void registerServer(String name, String host, int port) {
        var address = new java.net.InetSocketAddress(host, port);
        var info = ProxyServer.getInstance().constructServerInfo(
                name, address, "Dynamic server", false
        );
        ProxyServer.getInstance().getServers().put(name, info);
        dynamicServers.add(name);
    }

    public void unregisterServer(String name) {
        ProxyServer.getInstance().getServers().remove(name);
        dynamicServers.remove(name);
    }

    public boolean isDynamicServer(String name) {
        return dynamicServers.contains(name);
    }

    private void bootstrapServersFromAllHosts() {
        ProxyServer.getInstance().getScheduler().runAsync(this, () -> {
            int total = 0;

            for (HostRegistry.HostDef h : hosts.allHosts()) {
                try {
                    HostClient.StatusResponse status = h.client().status();

                    // Prefer status.hostId (from host) but fall back to config id
                    String hostId = (status.hostId != null && !status.hostId.isBlank()) ? status.hostId : h.id();

                    for (HostClient.InstanceStatus inst : status.instances) {
                        // Avoid overriding statics
                        if (ProxyServer.getInstance().getServers().containsKey(inst.name)) {
                            continue;
                        }

                        // Register routing using THIS host's connectHost
                        registerServer(inst.name, h.connectHost(), inst.port);

                        // Remember instance -> host mapping
                        hosts.mapInstanceToHost(inst.name, hostId);
                        total++;
                    }

                    getLogger().info("Bootstrapped " + status.instances.size() + " instance(s) from host " + hostId);

                } catch (Exception e) {
                    getLogger().warning("Failed to bootstrap from host " + h.id() + ": " + e.getMessage());
                }
            }

            getLogger().info("Re-registered " + total + " dynamic server(s) across all hosts");
        });
    }

    private void loadHostsFromConfig() {
        try {
            Configuration cfg = ConfigurationProvider.getProvider(YamlConfiguration.class)
                    .load(new File(getDataFolder(), "config.yml"));

            String token = cfg.getString("token");
            List<Map<String, Object>> list = (List<Map<String, Object>>) cfg.getList("hosts");

            if (list == null || list.isEmpty()) {
                getLogger().severe("No hosts configured in config.yml");
                return;
            }

            for (Map<String, Object> obj : list) {
                String id = String.valueOf(obj.get("id"));
                String baseUrl = String.valueOf(obj.get("baseUrl"));
                String connectHost = String.valueOf(obj.get("connectHost"));

                HostClient client = new HostClient(baseUrl, token);
                hosts.addHost(new HostRegistry.HostDef(id, baseUrl, connectHost, client));
            }

        } catch (Exception e) {
            getLogger().severe("Failed to load hosts from config.yml: " + e.getMessage());
        }
    }

    private void ensureDefaultConfig() throws Exception {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        File cfg = new File(getDataFolder(), "config.yml");
        if (!cfg.exists()) {
            // write a minimal default config
            String yml =
                    "token: \"CHANGE_ME_TOKEN\"\n" +
                            "\n" +
                            "hosts:\n" +
                            "  - id: \"local\"\n" +
                            "    baseUrl: \"http://127.0.0.1:8085\"\n" +
                            "    connectHost: \"127.0.0.1\"\n";
            Files.writeString(cfg.toPath(), yml);
        }
    }
    private long loadPollIntervalSeconds() {
        try {
            var cfg = net.md_5.bungee.config.ConfigurationProvider.getProvider(net.md_5.bungee.config.YamlConfiguration.class)
                    .load(new java.io.File(getDataFolder(), "config.yml"));
            long v = cfg.getLong("pollIntervalSeconds", 5);
            return Math.max(2, v); // don't go crazy low
        } catch (Exception e) {
            return 5;
        }
    }
}