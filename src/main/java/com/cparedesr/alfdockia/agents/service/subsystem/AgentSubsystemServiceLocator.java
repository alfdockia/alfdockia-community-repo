/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.subsystem;

import com.cparedesr.alfdockia.agents.service.AgentDeleteService;
import com.cparedesr.alfdockia.agents.service.AgentCapacityService;
import com.cparedesr.alfdockia.agents.service.AgentDeploymentService;
import com.cparedesr.alfdockia.agents.service.AgentRuntimeControlService;
import com.cparedesr.alfdockia.agents.service.AgentValidationService;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.license.LicenseService;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.alfresco.repo.management.subsystems.ApplicationContextFactory;
import org.springframework.context.ApplicationContext;

/**
 * Punto de acceso desde el contexto padre hacia los servicios que viven dentro
 * del subsistema Alfdockia.
 */
public class AgentSubsystemServiceLocator {

    private ApplicationContextFactory applicationContextFactory;

    public void setApplicationContextFactory(ApplicationContextFactory applicationContextFactory) {
        this.applicationContextFactory = applicationContextFactory;
    }

    public AgentValidationService getValidationService() {
        return getBean("alfdockia.agents.validationService", AgentValidationService.class);
    }

    public AgentDeploymentService getDeploymentService() {
        return getBean("alfdockia.agents.deploymentService", AgentDeploymentService.class);
    }

    public AgentRegistryService getRegistryService() {
        return getBean("alfdockia.agents.registryService", AgentRegistryService.class);
    }

    public AgentCapacityService getCapacityService() {
        return getBean("alfdockia.agents.capacityService", AgentCapacityService.class);
    }

    public LicenseService getLicenseService() {
        return getBean("alfdockia.agents.licenseService", LicenseService.class);
    }

    public AgentDeleteService getDeleteService() {
        return getBean("alfdockia.agents.deleteService", AgentDeleteService.class);
    }

    public AgentRuntimeControlService getRuntimeControlService() {
        return getBean("alfdockia.agents.runtimeControlService", AgentRuntimeControlService.class);
    }

    private <T> T getBean(String beanName, Class<T> beanType) {
        if (applicationContextFactory == null) {
            throw new BadRequestException("SUBSYSTEM_NOT_CONFIGURED", "Alfdockia subsystem is not configured");
        }

        ApplicationContext context = applicationContextFactory.getApplicationContext();
        if (context == null) {
            throw new BadRequestException("SUBSYSTEM_NOT_AVAILABLE", "Alfdockia subsystem is not available");
        }

        return context.getBean(beanName, beanType);
    }
}
