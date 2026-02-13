package dev.jumpwatch.serverfabric.proxy;

import net.md_5.bungee.api.ProxyServer;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class DynHostPoller {

    private final DynProxyPlugin plugin;
    private final HostRegistry hosts;

    public DynHostPoller(DynProxyPlugin plugin, HostRegistry hosts) {
        this.plugin = plugin;
        this.hosts = hosts;
    }

    public void start(long intervalSeconds) {
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            ProxyServer.getInstance().getScheduler().runAsync(plugin, this::pollOnce);
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void pollOnce() {
        try {
            Set<String> seen = new HashSet<>();
            int newlyRegistered = 0;

            int successfulHosts = 0;

            for (HostRegistry.HostDef h : hosts.allHosts()) {
                try {
                    HostClient.StatusResponse st = h.client().status();
                    successfulHosts++;

                    for (HostClient.InstanceStatus inst : st.instances) {
                        seen.add(inst.name);
                        hosts.mapInstanceToHost(inst.name, h.id());

                        if (!ProxyServer.getInstance().getServers().containsKey(inst.name)) {
                            plugin.registerServer(inst.name, h.connectHost(), inst.port);
                            newlyRegistered++;
                        }
                    }

                } catch (Exception ex) {
                    plugin.getLogger().warning("Host poll failed (" + h.id() + "): " + ex.getMessage());
                }
            }

            int removed = 0;
            if (successfulHosts > 0) {
                // do removals ONLY if at least one host replied successfully
                for (String name : new ArrayList<>(ProxyServer.getInstance().getServers().keySet())) {
                    if (!plugin.isDynamicServer(name)) continue;
                    if (seen.contains(name)) continue;

                    plugin.unregisterServer(name);
                    hosts.unmapInstance(name);
                    removed++;
                }
            } else {
                // optional log: no hosts reachable, so we skip removals, but this can get spammy quickly
                // plugin.getLogger().warning("Host poll: no hosts reachable; skipping removals");
            }

            if (newlyRegistered > 0 || removed > 0) {
                plugin.getLogger().info("Host poll: +" + newlyRegistered + " / -" + removed);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Poll loop error: " + e.getMessage());
        }
    }
}