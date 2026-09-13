/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.service.secrets.SecretsService;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Construye la configuracion del contenedor de Alfdockia sin persistir secretos.
 */
public class AgentContainerSpecFactory {

    static final String DEFAULT_LISTENER_PASSWORD_ENV = "CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD";

    private static final String MANAGED_AGENT_LABEL = "com.cparedesr.alfdockia.agentId";

    private SecretsService secretsService;

    public void setSecretsService(SecretsService secretsService) {
        this.secretsService = secretsService;
    }

    public Map<String, String> buildEnvironment(AgentDeployRequest req) {
        Map<String, String> env = new HashMap<>();

        if (req.getEnv() != null) {
            req.getEnv().forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    env.put(key, resolveEnvValue(value));
                }
            });
        }

        String listenerSecretRef = listenerPasswordSecretRef(req);
        if (StringUtils.hasText(listenerSecretRef)) {
            env.put(listenerPasswordEnvName(req), secretsService.resolve(listenerSecretRef));
        }

        return env;
    }

    public Map<String, String> buildLabels(String agentId, String name) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_AGENT_LABEL, agentId);
        labels.put("com.cparedesr.alfdockia.name", name);
        labels.put("com.cparedesr.alfdockia.product", "Alfdockia");
        return labels;
    }

    public AgentDeployRequest sanitize(AgentDeployRequest in) {
        AgentDeployRequest out = new AgentDeployRequest();
        out.setName(in.getName());
        out.setImage(in.getImage());
        out.setPorts(in.getPorts());
        out.setEnv(in.getEnv());

        if (in.getListener() != null) {
            AgentDeployRequest.ListenerConfig listener = new AgentDeployRequest.ListenerConfig();
            listener.setPasswordSecretRef(in.getListener().getPasswordSecretRef());
            listener.setPasswordEnvName(listenerPasswordEnvName(in));
            out.setListener(listener);
        }

        return out;
    }

    private String resolveEnvValue(String value) {
        return value.startsWith("prop:") ? secretsService.resolve(value) : value;
    }

    private String listenerPasswordSecretRef(AgentDeployRequest req) {
        if (req == null || req.getListener() == null
                || req.getListener().getPasswordSecretRef() == null) {
            return null;
        }
        return req.getListener().getPasswordSecretRef().getSecretRef();
    }

    private String listenerPasswordEnvName(AgentDeployRequest req) {
        if (req == null || req.getListener() == null
                || !StringUtils.hasText(req.getListener().getPasswordEnvName())) {
            return DEFAULT_LISTENER_PASSWORD_ENV;
        }
        return req.getListener().getPasswordEnvName().trim();
    }
}
