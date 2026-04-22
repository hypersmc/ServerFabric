package dev.jumpwatch.serverfabric.api;

import dev.jumpwatch.serverfabric.api.model.SFabricCreateRequest;
import dev.jumpwatch.serverfabric.api.model.SFabricCreateResponse;
import dev.jumpwatch.serverfabric.api.model.SFabricHost;
import dev.jumpwatch.serverfabric.api.model.SFabricInstance;
import dev.jumpwatch.serverfabric.api.model.SFabricInstanceStats;
import dev.jumpwatch.serverfabric.api.model.SFabricTemplate;

import java.util.Collection;
import java.util.Optional;

public interface SFabricAPI {

    Optional<SFabricInstance> getInstance(String name);

    Collection<SFabricInstance> getInstances();

    Collection<SFabricHost> getHosts();

    Collection<SFabricTemplate> getTemplates();

    Optional<SFabricInstanceStats> getInstanceStats(String name);

    SFabricCreateResponse createInstance(SFabricCreateRequest request) throws Exception;

    boolean startInstance(String name);

    boolean stopInstance(String name);

    boolean restartInstance(String name);

    boolean killInstance(String name);

    boolean sendCommand(String name, String command);
}
