/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.webscripts;

import com.alfdockia.agents.model.AgentDetail;
import com.alfdockia.agents.service.exception.BadRequestException;
import com.alfdockia.agents.service.registry.AgentRegistryService;
import com.alfdockia.agents.service.subsystem.AgentSubsystemServiceLocator;
import org.springframework.extensions.webscripts.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Web Script GET para consultar el detalle de un agente por su agentId.
 */
public class GetAgentByIdWebScript extends DeclarativeWebScript {

    private AgentSubsystemServiceLocator subsystemServiceLocator;

    public void setSubsystemServiceLocator(AgentSubsystemServiceLocator subsystemServiceLocator) {
        this.subsystemServiceLocator = subsystemServiceLocator;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {

        Map<String, Object> model = new HashMap<>();

        try {
            String id = getPathVar(req, "id");
            AgentRegistryService registryService = subsystemServiceLocator.getRegistryService();
            AgentDetail detail = registryService.getAgentDetailByAgentId(id);

            model.put("data", detail);

            String base = req.getServiceContextPath() + "/api/-default-/public/alfdockia/versions/1/agents/" + id;
            model.put("links", Map.of(
                    "self", base,
                    "start", base + "/start",
                    "stop", base + "/stop",
                    "restart", base + "/restart"
            ));

            status.setCode(Status.STATUS_OK);
            return model;

        } catch (BadRequestException e) {
            int code = "NOT_FOUND".equals(e.getCode()) ? Status.STATUS_NOT_FOUND : Status.STATUS_BAD_REQUEST;
            status.setCode(code);
            model.put("error", Map.of(
                    "statusCode", code,
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

    private String getPathVar(WebScriptRequest req, String name) {
        Map<String, String> vars = req.getServiceMatch().getTemplateVars();
        String v = vars.get(name);
        return v == null ? null : v.trim();
    }
}
