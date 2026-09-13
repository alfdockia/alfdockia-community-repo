/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.webscripts;

import com.alfdockia.agents.model.AgentDetail;
import com.alfdockia.agents.service.AgentRuntimeControlService;
import com.alfdockia.agents.service.exception.BadRequestException;
import com.alfdockia.agents.service.subsystem.AgentSubsystemServiceLocator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Web Script POST para acciones de runtime sobre agentes Alfdockia.
 */
public class AgentRuntimeActionPostWebScript extends DeclarativeWebScript {

    private final ObjectMapper mapper = new ObjectMapper();

    private AgentSubsystemServiceLocator subsystemServiceLocator;
    private String action;

    public void setSubsystemServiceLocator(AgentSubsystemServiceLocator subsystemServiceLocator) {
        this.subsystemServiceLocator = subsystemServiceLocator;
    }

    public void setAction(String action) {
        this.action = action;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        Map<String, Object> model = new HashMap<>();

        try {
            String id = getPathVar(req, "id");
            AgentRuntimeControlService runtimeControlService = subsystemServiceLocator.getRuntimeControlService();
            AgentDetail detail;

            if ("start".equals(action)) {
                detail = runtimeControlService.startAgent(id);
            } else if ("stop".equals(action)) {
                detail = runtimeControlService.stopAgent(id);
            } else if ("restart".equals(action)) {
                detail = runtimeControlService.restartAgent(id, readOptionalJson(req));
            } else {
                throw new BadRequestException("ACTION_INVALID", "Unsupported runtime action: " + action);
            }

            model.put("action", action);
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
            int http = "NOT_FOUND".equals(e.getCode()) ? Status.STATUS_NOT_FOUND : Status.STATUS_BAD_REQUEST;
            status.setCode(http);
            model.put("error", Map.of(
                    "statusCode", http,
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

    private JsonNode readOptionalJson(WebScriptRequest req) throws Exception {
        if (req.getContent() == null) {
            return null;
        }

        String json = req.getContent().getContent();
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        return mapper.readTree(json);
    }

    private String getPathVar(WebScriptRequest req, String name) {
        Map<String, String> vars = req.getServiceMatch().getTemplateVars();
        String v = vars.get(name);
        return v == null ? null : v.trim();
    }
}
