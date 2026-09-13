/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.model.AgentDeployResponse;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.license.LicenseService;
import com.cparedesr.alfdockia.agents.service.license.LicenseStatus;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;

import java.util.*;

/**
 * Orquesta el despliegue de un agente: prepara variables de entorno seguras,
 * arranca el contenedor y registra el resultado en el repositorio.
 */
public class AgentDeploymentService {

    private DockerService dockerService;
    private AgentRegistryService registryService;
    private AgentContainerSpecFactory containerSpecFactory;
    private LicenseService licenseService;

    public void setDockerService(DockerService dockerService) { this.dockerService = dockerService; }
    public void setRegistryService(AgentRegistryService registryService) { this.registryService = registryService; }
    public void setContainerSpecFactory(AgentContainerSpecFactory containerSpecFactory) { this.containerSpecFactory = containerSpecFactory; }
    public void setLicenseService(LicenseService licenseService) { this.licenseService = licenseService; }

    public synchronized AgentDeployResponse deploy(AgentDeployRequest req) {
        assertLicenseCapacity();

        String agentId = "agent-" + UUID.randomUUID();

        AgentDeployRequest sanitized = containerSpecFactory.sanitize(req);
        Map<String, String> env = containerSpecFactory.buildEnvironment(sanitized);
        Map<String, String> labels = containerSpecFactory.buildLabels(agentId, sanitized.getName());

        DockerService.CreateResult created = dockerService.createAndStart(agentId, sanitized.getImage(), env, labels, sanitized.getPorts());
        registryService.createAgentNode(agentId, sanitized, created.getContainerId(), "running", created.getCurrentState());

        return new AgentDeployResponse(
                agentId,
                sanitized.getName(),
                "running",
                created.getCurrentState(),
                "/alfresco/api/-default-/public/alfdockia/versions/1/agents/" + agentId
        );
    }

    private void assertLicenseCapacity() {
        if (licenseService == null) {
            return;
        }

        LicenseStatus status = licenseService.getStatus();
        if (status.isUnlimitedAgents()) {
            return;
        }

        int currentAgents = registryService.countAgentsUpTo(status.getMaxAgents());
        licenseService.assertCanCreateAgent(currentAgents);
    }
}
