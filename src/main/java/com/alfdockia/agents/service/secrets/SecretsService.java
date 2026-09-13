/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.secrets;

/**
 * Resuelve referencias de secreto sin exponer el valor en el registro del agente.
 */
public interface SecretsService {
    String resolve(String secretRef);
}
