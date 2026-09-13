package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import java.util.HashSet;
import java.util.Set;

/** Counts registry entries plus managed Docker containers missing from the registry. */
public class AgentCapacityService {
    private AgentRegistryService registryService;
    private DockerService dockerService;
    public void setRegistryService(AgentRegistryService value) { registryService = value; }
    public void setDockerService(DockerService value) { dockerService = value; }

    public int countAgents() {
        int registered = registryService.countAgentsUpTo(Integer.MAX_VALUE);
        Set<String> registeredIds = new HashSet<>(registryService.listRegisteredContainerIds());
        // Do not swallow discovery failures: unavailable inventory must not grant capacity.
        Set<String> dockerIds = new HashSet<>(dockerService.listManagedContainerIds());
        for (String id : dockerIds) {
            if (!registeredIds.contains(id)) registered++;
        }
        return registered;
    }
}
