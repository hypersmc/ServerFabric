package dev.jumpwatch.serverfabric.proxy;

import net.md_5.bungee.api.ProxyServer;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DynHostPoller {

    private final DynProxyPlugin plugin;
    private final HostRegistry hosts;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DynHostPoller(DynProxyPlugin plugin, HostRegistry hosts) {
        this.plugin = plugin;
        this.hosts = hosts;
    }

    public void start(long intervalSeconds) {
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (!running.compareAndSet(false, true)) return;

            try {
                pollOnce();
            } finally {
                running.set(false);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void pollOnce() {
        // async phase
        final Set<String> seen = new HashSet<>();
        final Map<String, HostRegistry.HostDef> nameToHost = new HashMap<>();
        final Map<String, HostClient.InstanceStatus> nameToStatus = new HashMap<>();
        int successfulHosts = 0;

        for (HostRegistry.HostDef h : hosts.allHosts()) {
            try {
                HostClient.StatusResponse st = h.client().status(); // MUST have timeouts
                successfulHosts++;

                for (HostClient.InstanceStatus inst : st.instances) {
                    seen.add(inst.name);
                    nameToHost.put(inst.name, h);
                    nameToStatus.put(inst.name, inst);
                    hosts.mapInstanceToHost(inst.name, h.id());
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Host poll failed (" + h.id() + "): " + ex.getMessage());
            }
        }

        final int finalSuccessfulHosts = successfulHosts;

        // apply on main thread
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            int newlyRegistered = 0;
            int removed = 0;

            // Register missing instances
            for (String name : seen) {
                if (ProxyServer.getInstance().getServers().containsKey(name)) continue;

                HostRegistry.HostDef h = nameToHost.get(name);
                HostClient.InstanceStatus st = nameToStatus.get(name);
                if (h == null || st == null) continue;

                plugin.registerServer(name, h.connectHost(), st.port);
                newlyRegistered++;
            }

            // Remove dynamic instances that disappeared (only if at least one host responded)
            if (finalSuccessfulHosts > 0) {
                for (String name : new ArrayList<>(ProxyServer.getInstance().getServers().keySet())) {
                    if (!plugin.isDynamicServer(name)) continue;
                    if (seen.contains(name)) continue;

                    plugin.unregisterServer(name);
                    hosts.unmapInstance(name);
                    removed++;
                }
            }

            if (newlyRegistered > 0 || removed > 0) {
                plugin.getLogger().info("Host poll: +" + newlyRegistered + " / -" + removed);
            }
        });
    }
}