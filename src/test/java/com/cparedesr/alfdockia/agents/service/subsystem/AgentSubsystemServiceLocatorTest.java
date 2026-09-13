/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.subsystem;

import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.alfresco.repo.management.subsystems.ApplicationContextFactory;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentSubsystemServiceLocatorTest {

    @Test
    public void returnsBeansFromSubsystemContext() {
        ApplicationContextFactory factory = mock(ApplicationContextFactory.class);
        ApplicationContext context = mock(ApplicationContext.class);
        AgentRegistryService registryService = mock(AgentRegistryService.class);

        when(factory.getApplicationContext()).thenReturn(context);
        when(context.getBean("alfdockia.agents.registryService", AgentRegistryService.class)).thenReturn(registryService);

        AgentSubsystemServiceLocator locator = new AgentSubsystemServiceLocator();
        locator.setApplicationContextFactory(factory);

        assertSame(registryService, locator.getRegistryService());
    }
}
