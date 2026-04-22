package dev.jumpwatch.serverfabric.proxy.api;

import dev.jumpwatch.serverfabric.api.SFabricAPI;
import dev.jumpwatch.serverfabric.api.enums.SFabricInstanceState;
import dev.jumpwatch.serverfabric.api.model.SFabricCreateRequest;
import dev.jumpwatch.serverfabric.api.model.SFabricCreateResponse;
import dev.jumpwatch.serverfabric.api.model.SFabricHost;
import dev.jumpwatch.serverfabric.api.model.SFabricInstance;
import dev.jumpwatch.serverfabric.api.model.SFabricInstanceStats;
import dev.jumpwatch.serverfabric.api.model.SFabricTemplate;
import dev.jumpwatch.serverfabric.proxy.HostClient;
import dev.jumpwatch.serverfabric.proxy.HostRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class SFabricApiImpl implements SFabricAPI {

    private final HostRegistry hosts;

    public SFabricApiImpl(HostRegistry hosts) {
        this.hosts = hosts;
    }

    @Override
    public Optional<SFabricInstance> getInstance(String name) {
        if (name == null || name.isBlank()) return Optional.empty();

        String hostId = hosts.hostIdForInstance(name);
        if (hostId == null) return Optional.empty();

        HostRegistry.HostDef host = hosts.getHost(hostId);
        if (host == null) return Optional.empty();

        try {
            HostClient.StatusResponse status = host.client().status();
            for (HostClient.InstanceStatus inst : status.instances) {
                if (inst.name != null && inst.name.equalsIgnoreCase(name)) {
                    return Optional.of(mapInstance(inst, hostId));
                }
            }
        } catch (Exception ignored) {
        }

        return Optional.empty();
    }

    @Override
    public Collection<SFabricInstance> getInstances() {
        List<SFabricInstance> result = new ArrayList<>();

        for (HostRegistry.HostDef host : hosts.allHosts()) {
            try {
                HostClient.StatusResponse status = host.client().status();
                for (HostClient.InstanceStatus inst : status.instances) {
                    hosts.mapInstanceToHost(inst.name, host.id());
                    result.add(mapInstance(inst, host.id()));
                }
            } catch (Exception ignored) {
            }
        }

        result.sort(Comparator.comparing(SFabricInstance::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public Collection<SFabricHost> getHosts() {
        List<SFabricHost> result = new ArrayList<>();

        for (HostRegistry.HostDef host : hosts.allHosts()) {
            result.add(new SFabricHost(
                    host.id(),
                    host.baseUrl(),
                    host.connectHost(),
                    true
            ));
        }

        result.sort(Comparator.comparing(SFabricHost::id, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public Collection<SFabricTemplate> getTemplates() {
        List<SFabricTemplate> result = new ArrayList<>();

        for (HostRegistry.HostDef host : hosts.allHosts()) {
            try {
                HostClient.TemplatesResponse templates = host.client().templates();
                for (String template : templates.templates) {
                    result.add(new SFabricTemplate(
                            host.id(),
                            template,
                            template,
                            null
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        result.sort(Comparator
                .comparing(SFabricTemplate::hostId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SFabricTemplate::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public Optional<SFabricInstanceStats> getInstanceStats(String name) {
        if (name == null || name.isBlank()) return Optional.empty();

        String hostId = hosts.hostIdForInstance(name);
        if (hostId == null) return Optional.empty();

        HostRegistry.HostDef host = hosts.getHost(hostId);
        if (host == null) return Optional.empty();

        try {
            HostClient.InstanceStatsResponse stats = host.client().stats(name);
            return Optional.of(new SFabricInstanceStats(
                    stats.name,
                    stats.state,
                    stats.alive,
                    stats.stopping,
                    stats.pid,
                    stats.uptimeMs,
                    stats.startedAtMs,
                    stats.lastOutputAtMs,
                    stats.memoryRssBytes,
                    stats.memoryVirtualBytes,
                    stats.diskUsageBytes
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public SFabricCreateResponse createInstance(SFabricCreateRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (request.hostId() == null || request.hostId().isBlank()) {
            throw new IllegalArgumentException("hostId is required");
        }
        if (request.template() == null || request.template().isBlank()) {
            throw new IllegalArgumentException("template is required");
        }
        if (request.instanceName() == null || request.instanceName().isBlank()) {
            throw new IllegalArgumentException("instanceName is required");
        }

        HostRegistry.HostDef host = hosts.getHost(request.hostId());
        if (host == null) {
            throw new IllegalArgumentException("Unknown host: " + request.hostId());
        }

        HostClient.CreateResponse created = host.client().create(
                request.template(),
                request.instanceName(),
                request.versionOverride()
        );

        hosts.mapInstanceToHost(created.name(), host.id());

        return new SFabricCreateResponse(
                created.name(),
                host.id(),
                created.port(),
                request.template(),
                request.versionOverride()
        );
    }

    @Override
    public boolean startInstance(String name) {
        return performInstanceAction(name, Action.START, null);
    }

    @Override
    public boolean stopInstance(String name) {
        return performInstanceAction(name, Action.STOP, null);
    }

    @Override
    public boolean restartInstance(String name) {
        return performInstanceAction(name, Action.RESTART, null);
    }

    @Override
    public boolean killInstance(String name) {
        return performInstanceAction(name, Action.KILL, null);
    }

    @Override
    public boolean sendCommand(String name, String command) {
        return performInstanceAction(name, Action.COMMAND, command);
    }

    private boolean performInstanceAction(String instanceName, Action action, String command) {
        if (instanceName == null || instanceName.isBlank()) return false;

        String hostId = hosts.hostIdForInstance(instanceName);
        if (hostId == null) return false;

        HostRegistry.HostDef host = hosts.getHost(hostId);
        if (host == null) return false;

        try {
            switch (action) {
                case START -> host.client().start(instanceName);
                case STOP -> host.client().stop(instanceName);
                case RESTART -> host.client().restart(instanceName);
                case KILL -> host.client().kill(instanceName);
                case COMMAND -> {
                    if (command == null || command.isBlank()) return false;
                    host.client().command(instanceName, command);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SFabricInstance mapInstance(HostClient.InstanceStatus inst, String hostId) {
        return new SFabricInstance(
                inst.name,
                hostId,
                inst.port,
                null,
                null,
                mapState(inst.state)
        );
    }

    private SFabricInstanceState mapState(String state) {
        if (state == null || state.isBlank()) {
            return SFabricInstanceState.UNKNOWN;
        }

        try {
            return SFabricInstanceState.valueOf(state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SFabricInstanceState.UNKNOWN;
        }
    }

    private enum Action {
        START, STOP, RESTART, KILL, COMMAND
    }
}