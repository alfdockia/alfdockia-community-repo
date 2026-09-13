package com.alfdockia.agents.service;

import com.alfdockia.agents.service.registry.AgentRegistryService;

/** The repository is the sole source of registered agents and licensed capacity. */
public class AgentCapacityService {
    private AgentRegistryService registryService;
    public void setRegistryService(AgentRegistryService value) { registryService = value; }
    public int countAgents() {
        return registryService.countAgentsUpTo(Integer.MAX_VALUE);
    }
}
