/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.model.AgentDetail;
import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Properties;

/**
 * Operaciones de runtime para arrancar, parar y recrear agentes Alfdockia.
 */
public class AgentRuntimeControlService {

    private AgentRegistryService registryService;
    private DockerService dockerService;
    private AgentValidationService validationService;
    private AgentContainerSpecFactory containerSpecFactory;
    private Properties globalProperties;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public void setRegistryService(AgentRegistryService registryService) { this.registryService = registryService; }
    public void setDockerService(DockerService dockerService) { this.dockerService = dockerService; }
    public void setValidationService(AgentValidationService validationService) { this.validationService = validationService; }
    public void setContainerSpecFactory(AgentContainerSpecFactory containerSpecFactory) { this.containerSpecFactory = containerSpecFactory; }
    public void setGlobalProperties(Properties globalProperties) { this.globalProperties = globalProperties; }

    public AgentDetail startAgent(String agentId) {
        AgentRuntimeInfo info = registryService.getRuntimeInfoByAgentId(agentId);

        if (dockerEnabled()) {
            String containerId = requireContainerId(info);
            dockerService.start(containerId);
            registryService.updateAgentState(agentId, "running", "running");
        } else {
            registryService.updateAgentState(agentId, "running", "disabled");
        }

        return registryService.getAgentDetailByAgentId(agentId);
    }

    public AgentDetail stopAgent(String agentId) {
        AgentRuntimeInfo info = registryService.getRuntimeInfoByAgentId(agentId);

        if (dockerEnabled()) {
            String containerId = requireContainerId(info);
            dockerService.stop(containerId, stopTimeoutSeconds());
            registryService.updateAgentState(agentId, "stopped", "stopped");
        } else {
            registryService.updateAgentState(agentId, "stopped", "disabled");
        }

        return registryService.getAgentDetailByAgentId(agentId);
    }

    public AgentDetail restartAgent(String agentId, JsonNode changes) {
        AgentDetail current = registryService.getAgentDetailByAgentId(agentId);
        AgentDeployRequest merged = mergeConfig(current, changes);
        validationService.validateAgentConfig(merged);

        if (!current.getName().equals(merged.getName())) {
            throw new BadRequestException("AGENT_NAME_IMMUTABLE", "Agent name cannot be changed during restart");
        }

        AgentDeployRequest sanitized = containerSpecFactory.sanitize(merged);
        Map<String, String> env = containerSpecFactory.buildEnvironment(sanitized);
        Map<String, String> labels = containerSpecFactory.buildLabels(agentId, sanitized.getName());

        AgentRuntimeInfo info = registryService.getRuntimeInfoByAgentId(agentId);
        if (dockerEnabled() && StringUtils.hasText(info.getContainerId())) {
            dockerService.remove(info.getContainerId(), true);
        }

        DockerService.CreateResult created = dockerService.createAndStart(
                agentId,
                sanitized.getImage(),
                env,
                labels,
                sanitized.getPorts()
        );

        registryService.updateAgentNode(agentId, sanitized, created.getContainerId(), "running", created.getCurrentState());
        return registryService.getAgentDetailByAgentId(agentId);
    }

    private AgentDeployRequest mergeConfig(AgentDetail current, JsonNode changes) {
        ObjectNode base = configNode(current.getConfigJson());

        if (changes != null && !changes.isNull()) {
            if (!changes.isObject()) {
                throw new BadRequestException("RESTART_BODY_INVALID", "Restart body must be a JSON object");
            }
            mergeInto(base, (ObjectNode) changes);
        }

        normalizeLegacyConfig(base);

        try {
            AgentDeployRequest merged = mapper.treeToValue(base, AgentDeployRequest.class);
            if (!StringUtils.hasText(merged.getName())) {
                merged.setName(current.getName());
            }
            if (!StringUtils.hasText(merged.getImage())) {
                merged.setImage(current.getImage());
            }
            return merged;
        } catch (Exception e) {
            throw new BadRequestException("CONFIG_INVALID", "Stored agent config is not valid JSON");
        }
    }

    private ObjectNode configNode(String json) {
        try {
            if (!StringUtils.hasText(json)) {
                return mapper.createObjectNode();
            }
            JsonNode parsed = mapper.readTree(json);
            if (!parsed.isObject()) {
                throw new BadRequestException("CONFIG_INVALID", "Stored agent config must be a JSON object");
            }
            return (ObjectNode) parsed;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("CONFIG_INVALID", "Stored agent config is not valid JSON");
        }
    }

    private void mergeInto(ObjectNode target, ObjectNode update) {
        update.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode value = entry.getValue();

            if (value == null || value.isNull()) {
                target.remove(field);
                return;
            }

            JsonNode existing = target.get(field);
            if (existing != null && existing.isObject() && value.isObject()) {
                mergeInto((ObjectNode) existing, (ObjectNode) value);
                return;
            }

            target.set(field, value);
        });
    }

    private void normalizeLegacyConfig(ObjectNode config) {
        JsonNode alfresco = config.get("alfresco");
        if (alfresco != null && alfresco.isObject()) {
            ObjectNode alfrescoObject = (ObjectNode) alfresco;
            JsonNode passwordSecretRef = alfrescoObject.get("passwordSecretRef");
            if (passwordSecretRef != null && passwordSecretRef.isObject() && config.get("listener") == null) {
                ObjectNode listener = mapper.createObjectNode();
                listener.set("passwordSecretRef", passwordSecretRef);
                config.set("listener", listener);
            }
            config.remove("alfresco");
        }
        config.remove("llm");
    }

    private String requireContainerId(AgentRuntimeInfo info) {
        if (info == null || !StringUtils.hasText(info.getContainerId())) {
            throw new BadRequestException("CONTAINER_ID_REQUIRED", "Agent has no Docker container id");
        }
        return info.getContainerId().trim();
    }

    private boolean dockerEnabled() {
        return globalProperties == null
                || Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));
    }

    private int stopTimeoutSeconds() {
        if (globalProperties == null) {
            return 10;
        }
        String raw = globalProperties.getProperty("alfresco.alfdockia.subsystem.stopTimeoutSeconds", "10");
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (Exception e) {
            return 10;
        }
    }
}
