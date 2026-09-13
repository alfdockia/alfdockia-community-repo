/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.registry;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.model.AgentDetail;
import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.model.AgentSummary;

import java.util.List;

/**
 * Contrato del registro persistente de agentes dentro de Alfresco.
 */
public interface AgentRegistryService {

    boolean existsByName(String name);

    int countAgentsUpTo(int limit);

    void createAgentNode(String agentId,
                         AgentDeployRequest sanitizedRequest,
                         String containerId,
                         String desired,
                         String current);

    void updateAgentNode(String agentId,
                         AgentDeployRequest sanitizedRequest,
                         String containerId,
                         String desired,
                         String current);

    void updateAgentState(String agentId, String desired, String current);

    List<AgentSummary> listAgents(int skipCount, int maxItems);

    List<AgentRuntimeInfo> listRuntimeInfos();

    AgentDetail getAgentDetailByAgentId(String agentId);

    AgentRuntimeInfo getRuntimeInfoByAgentId(String agentId);

    void deleteByAgentId(String agentId);
}
