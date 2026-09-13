/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.license.LicenseService;
import com.cparedesr.alfdockia.agents.service.license.LicenseStatus;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Properties;

/**
 * Valida las peticiones de alta antes de desplegar contenedores o persistir
 * informacion en Alfresco.
 */
public class AgentValidationService {

    private AgentRegistryService registryService;
    private LicenseService licenseService;
    private Properties globalProperties;

    public void setRegistryService(AgentRegistryService registryService) { this.registryService = registryService; }
    public void setLicenseService(LicenseService licenseService) { this.licenseService = licenseService; }
    public void setGlobalProperties(Properties globalProperties) { this.globalProperties = globalProperties; }

    public void validateDeployRequest(AgentDeployRequest r) {
        validateAgentConfig(r);

        if (registryService.existsByName(r.getName())) {
            throw new BadRequestException("NAME_ALREADY_EXISTS", "Agent name already exists: " + r.getName());
        }

        validateLicenseLimit();
    }

    public void validateAgentConfig(AgentDeployRequest r) {
        if (r == null) throw new BadRequestException("BODY_REQUIRED", "Request body is required");

        if (!StringUtils.hasText(r.getName())) throw new BadRequestException("NAME_REQUIRED", "name is required");
        if (!StringUtils.hasText(r.getImage())) throw new BadRequestException("IMAGE_REQUIRED", "image is required");

        // Allowlist opcional de imagenes Docker. Por defecto no se aplica para
        // facilitar entornos de prueba, pero puede activarse en produccion.
        boolean allowlistEnabled = Boolean.parseBoolean(
                prop("alfresco.alfdockia.image.allowlist.enabled", "false")
        );
        if (allowlistEnabled) {
            String raw = prop("alfresco.alfdockia.image.allowlist", "");
            if (!StringUtils.hasText(raw)) {
                throw new BadRequestException("IMAGE_ALLOWLIST_EMPTY", "Allowlist enabled but alfresco.alfdockia.image.allowlist is empty");
            }
            boolean allowed = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .anyMatch(prefix -> r.getImage().startsWith(prefix));

            if (!allowed) {
                throw new BadRequestException("IMAGE_NOT_ALLOWED", "Image not in allowlist");
            }
        }

        validateListenerPassword(r);
        validateEnv(r);

        if (r.getPorts() != null) {
            for (AgentDeployRequest.PortMapping p : r.getPorts()) {
                if (p.getContainerPort() < 1 || p.getContainerPort() > 65535)
                    throw new BadRequestException("PORT_INVALID", "containerPort invalid");
                if (p.getHostPort() < 1 || p.getHostPort() > 65535)
                    throw new BadRequestException("PORT_INVALID", "hostPort invalid");
                String proto = p.getProtocol() == null ? "tcp" : p.getProtocol();
                if (!proto.equals("tcp") && !proto.equals("udp"))
                    throw new BadRequestException("PORT_PROTOCOL_INVALID", "protocol must be tcp|udp");
            }
        }
    }

    private void validateListenerPassword(AgentDeployRequest r) {
        if (hasListenerPasswordSecret(r) || hasPasswordEnvSecretRef(r)) {
            return;
        }
        throw new BadRequestException("LISTENER_PASSWORD_SECRET_REQUIRED",
                "listener.passwordSecretRef.secretRef or CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD=prop:<key> is required");
    }

    private boolean hasListenerPasswordSecret(AgentDeployRequest r) {
        return r.getListener() != null
                && r.getListener().getPasswordSecretRef() != null
                && StringUtils.hasText(r.getListener().getPasswordSecretRef().getSecretRef());
    }

    private boolean hasPasswordEnvSecretRef(AgentDeployRequest r) {
        if (r.getEnv() == null) {
            return false;
        }
        String passwordEnvName = AgentContainerSpecFactory.DEFAULT_LISTENER_PASSWORD_ENV;
        if (r.getListener() != null && StringUtils.hasText(r.getListener().getPasswordEnvName())) {
            passwordEnvName = r.getListener().getPasswordEnvName().trim();
        }
        String value = r.getEnv().get(passwordEnvName);
        return value != null && value.trim().startsWith("prop:");
    }

    private void validateEnv(AgentDeployRequest r) {
        if (r.getEnv() == null) {
            return;
        }
        String passwordEnvName = listenerPasswordEnvName(r);
        for (var entry : r.getEnv().entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                throw new BadRequestException("ENV_KEY_REQUIRED", "env keys cannot be empty");
            }
            if (entry.getValue() == null) {
                throw new BadRequestException("ENV_VALUE_REQUIRED", "env values cannot be null");
            }
            if (passwordEnvName.equals(entry.getKey()) && !entry.getValue().trim().startsWith("prop:")) {
                throw new BadRequestException("LISTENER_PASSWORD_ENV_SECRETREF_REQUIRED",
                        passwordEnvName + " must use prop:<key> when declared in env");
            }
        }
    }

    private String listenerPasswordEnvName(AgentDeployRequest r) {
        if (r.getListener() != null && StringUtils.hasText(r.getListener().getPasswordEnvName())) {
            return r.getListener().getPasswordEnvName().trim();
        }
        return AgentContainerSpecFactory.DEFAULT_LISTENER_PASSWORD_ENV;
    }

    private String prop(String key, String def) {
        if (globalProperties == null) return def;
        return globalProperties.getProperty(key, def);
    }

    private void validateLicenseLimit() {
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
