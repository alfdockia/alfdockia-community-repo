/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.webscripts;

import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.subsystem.AgentSubsystemServiceLocator;
import org.springframework.extensions.webscripts.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Web Script DELETE para eliminar un agente y su runtime asociado.
 */
public class DeleteAgentWebScript extends DeclarativeWebScript {

    private AgentSubsystemServiceLocator subsystemServiceLocator;

    public void setSubsystemServiceLocator(AgentSubsystemServiceLocator subsystemServiceLocator) {
        this.subsystemServiceLocator = subsystemServiceLocator;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {

        Map<String, Object> model = new HashMap<>();

        try {
            String id = req.getServiceMatch().getTemplateVars().get("id");

            subsystemServiceLocator.getDeleteService().deleteAgent(id);

            model.put("agentId", id);
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
}
