/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.secrets.impl;

import com.alfdockia.agents.service.exception.BadRequestException;
import com.alfdockia.agents.service.secrets.SecretsService;

import java.util.Properties;

/**
 * Implementacion inicial de secretos basada en propiedades globales de Alfresco.
 */
public class PropertySecretsService implements SecretsService {

    private Properties globalProperties;

    public void setGlobalProperties(Properties globalProperties) {
        this.globalProperties = globalProperties;
    }

    @Override
    public String resolve(String secretRef) {
        if (secretRef == null || secretRef.trim().isEmpty()) {
            throw new BadRequestException("SECRETREF_REQUIRED", "secretRef is required");
        }

        if (secretRef.startsWith("prop:")) {
            String key = secretRef.substring("prop:".length());
            String value = globalProperties.getProperty(key);
            if (value == null || value.isEmpty()) {
                throw new BadRequestException("SECRET_NOT_FOUND", "Secret not found for key: " + key);
            }
            return value;
        }

        throw new BadRequestException("SECRETREF_UNSUPPORTED", "Unsupported secretRef. Use prop:<key> for now.");
    }
}
