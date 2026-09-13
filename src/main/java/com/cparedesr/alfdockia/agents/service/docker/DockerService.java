/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.docker;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;

import java.util.List;
import java.util.Map;

/**
 * Puerto de salida para las operaciones de Docker que necesita el modulo.
 */
public interface DockerService {
    CreateResult createAndStart(String agentId,
                               String image,
                               Map<String, String> env,
                               Map<String, String> labels,
                               List<AgentDeployRequest.PortMapping> ports);

    List<AgentRuntimeInfo> listManagedRuntimeInfos();

    void start(String containerId);

    void stop(String containerId, int timeoutSeconds);

    void remove(String containerId, boolean force);

    /**
     * Devuelve los contenedores administrados por Alfdockia.
     *
     * Docker actua como fuente de verdad adicional durante el ciclo de
     * vida. De este modo la parada no depende de que Search Services haya
     * indexado ya el nodo de registro ni de que Alfresco permita consultas
     * al repositorio mientras se esta cerrando.
     */
    default List<String> listManagedContainerIds() {
        return List.of();
    }

    class CreateResult {
        private final String containerId;
        private final String currentState;

        public CreateResult(String containerId, String currentState) {
            this.containerId = containerId;
            this.currentState = currentState;
        }

        public String getContainerId() { return containerId; }
        public String getCurrentState() { return currentState; }
    }
}
