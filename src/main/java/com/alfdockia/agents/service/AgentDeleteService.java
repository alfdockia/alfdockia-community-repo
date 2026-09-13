/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service;

import com.alfdockia.agents.model.AgentRuntimeInfo;
import com.alfdockia.agents.service.docker.DockerService;
import com.alfdockia.agents.service.registry.AgentRegistryService;

import java.util.Properties;

/**
 * Elimina un agente de forma coordinada: primero intenta retirar el contenedor
 * Docker y despues borra el nodo de registro en Alfresco.
 */
public class AgentDeleteService {

    private AgentRegistryService registryService;
    private DockerService dockerService;
    private Properties globalProperties;

    public void setRegistryService(AgentRegistryService registryService) { this.registryService = registryService; }
    public void setDockerService(DockerService dockerService) { this.dockerService = dockerService; }
    public void setGlobalProperties(Properties globalProperties) { this.globalProperties = globalProperties; }

    public void deleteAgent(String agentId) {
        AgentRuntimeInfo info = registryService.getRuntimeInfoByAgentId(agentId);

        boolean dockerEnabled = Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));

        if (dockerEnabled && info.getContainerId() != null && !info.getContainerId().trim().isEmpty()) {
            // Operacion idempotente: si el contenedor ya no existe, el borrado
            // del registro de Alfresco debe continuar.
            dockerService.remove(info.getContainerId(), true);
        }

        registryService.deleteByAgentId(agentId);
    }
}
