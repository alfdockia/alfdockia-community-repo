/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.repo;

/**
 * Resuelve el destino del agente dentro del repositorio de Alfresco.
 */
public interface AlfrescoFolderService {
    String ensureAndResolveNodeId(String nodeId, String path);
}
