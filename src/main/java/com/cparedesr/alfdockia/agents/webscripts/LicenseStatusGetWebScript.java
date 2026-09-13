/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.webscripts;

import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.license.LicenseStatus;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import com.cparedesr.alfdockia.agents.service.subsystem.AgentSubsystemServiceLocator;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Web Script GET para inspeccionar la edicion efectiva de AlfDokia.
 */
public class LicenseStatusGetWebScript extends DeclarativeWebScript {

    private static final int STATUS_AGENT_COUNT_LIMIT = 10000;

    private AgentSubsystemServiceLocator subsystemServiceLocator;

    public void setSubsystemServiceLocator(AgentSubsystemServiceLocator subsystemServiceLocator) {
        this.subsystemServiceLocator = subsystemServiceLocator;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        Map<String, Object> model = new HashMap<>();

        try {
            LicenseStatus license = subsystemServiceLocator.getLicenseService().getStatus();
            AgentRegistryService registryService = subsystemServiceLocator.getRegistryService();

            Map<String, Object> data = new HashMap<>();
            data.put("license", license);
            data.put("currentAgents", registryService.countAgentsUpTo(STATUS_AGENT_COUNT_LIMIT));

            model.put("data", data);
            status.setCode(Status.STATUS_OK);
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
