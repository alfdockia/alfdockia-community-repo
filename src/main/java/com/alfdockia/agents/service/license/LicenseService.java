/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.license;

/**
 * Expone la politica de edicion/licencia efectiva de AlfDokia.
 */
public interface LicenseService {

    LicenseStatus getStatus();

    void assertCanCreateAgent(int currentAgentCount);
}
