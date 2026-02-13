package dev.jumpwatch.serverfabric.proxy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class HostRegistry {

    public record HostDef(String id, String baseUrl, String connectHost, HostClient client) {}

    private final Map<String, HostDef> hosts = new ConcurrentHashMap<>();
    private final Map<String, String> instanceToHostId = new ConcurrentHashMap<>();
    private final AtomicInteger rr = new AtomicInteger(0);

    public void addHost(HostDef host) {
        hosts.put(host.id(), host);
    }

    public Collection<HostDef> allHosts() {
        return hosts.values();
    }

    public HostDef getHost(String hostId) {
        return hosts.get(hostId);
    }

    public void mapInstanceToHost(String instanceName, String hostId) {
        instanceToHostId.put(instanceName, hostId);
    }

    public String hostIdForInstance(String instanceName) {
        return instanceToHostId.get(instanceName);
    }

    public void unmapInstance(String instanceName) {
        instanceToHostId.remove(instanceName);
    }

    public HostDef pickHostRoundRobin() {
        List<HostDef> list = new ArrayList<>(hosts.values());
        if (list.isEmpty()) return null;
        int idx = Math.floorMod(rr.getAndIncrement(), list.size());
        return list.get(idx);
    }
}