/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.webscripts;

import com.alfdockia.agents.model.AgentDeployRequest;
import com.alfdockia.agents.model.AgentDeployResponse;
import com.alfdockia.agents.service.exception.BadRequestException;
import com.alfdockia.agents.service.subsystem.AgentSubsystemServiceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Web Script POST que valida una peticion JSON y delega el despliegue del agente.
 */
public class DeployAgentPostWebScript extends DeclarativeWebScript {

    private final ObjectMapper mapper = new ObjectMapper();

    private AgentSubsystemServiceLocator subsystemServiceLocator;

    public void setSubsystemServiceLocator(AgentSubsystemServiceLocator subsystemServiceLocator) {
        this.subsystemServiceLocator = subsystemServiceLocator;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {

        Map<String, Object> model = new HashMap<>();

        try {
            if (req.getContent() == null) {
                throw new BadRequestException("BODY_REQUIRED", "Request body is required");
            }

            String json = req.getContent().getContent();
            if (json == null || json.trim().isEmpty()) {
                throw new BadRequestException("BODY_REQUIRED", "Request body is required");
            }

            // Jackson convierte el JSON publico en DTOs internos antes de validar.
            AgentDeployRequest deployRequest = mapper.readValue(json, AgentDeployRequest.class);

            AgentDeployResponse response = subsystemServiceLocator.getDeploymentService().deploy(deployRequest);

            status.setCode(Status.STATUS_CREATED);

            model.put("data", response);
            model.put("location",
                    req.getServiceContextPath()
                            + "/api/-default-/public/alfdockia/versions/1/agents/"
                            + response.getAgentId());

            return model;

        } catch (BadRequestException e) {
            status.setCode(Status.STATUS_BAD_REQUEST);
            model.put("error", Map.of(
                    "statusCode", 400,
                    "code", e.getCode(),
                    "message", e.getMessage()
            ));
            return model;

        } catch (Exception e) {
            status.setCode(Status.STATUS_INTERNAL_SERVER_ERROR);
            model.put("error", Map.of(
                    "statusCode", 500,
                    "code", "INTERNAL_ERROR",
                    "message", "Unexpected error"
            ));
            return model;
        }
    }
}
