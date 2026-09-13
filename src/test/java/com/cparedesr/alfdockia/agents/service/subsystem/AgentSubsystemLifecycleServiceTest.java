/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.subsystem;

import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentSubsystemLifecycleServiceTest {

    private AgentRegistryService registryService;
    private DockerService dockerService;
    private AgentSubsystemLifecycleService lifecycleService;

    @Before
    public void setUp() {
        registryService = mock(AgentRegistryService.class);
        dockerService = mock(DockerService.class);

        Properties props = new Properties();
        props.setProperty("alfresco.alfdockia.subsystem.startAgentsOnStart", "true");
        props.setProperty("alfresco.alfdockia.subsystem.stopAgentsOnStop", "true");
        props.setProperty("alfresco.alfdockia.subsystem.stopTimeoutSeconds", "3");

        lifecycleService = new AgentSubsystemLifecycleService();
        lifecycleService.setLicenseEnforcement(mock(com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService.class));
        lifecycleService.setRegistryService(registryService);
        lifecycleService.setDockerService(dockerService);
        lifecycleService.setGlobalProperties(props);
    }

    @Test
    public void startsAndStopsRegisteredAgentContainers() {
        when(registryService.listRuntimeInfos()).thenReturn(List.of(
                runtime("agent-1", "container-1"),
                runtime("agent-2", " ")
        ));

        lifecycleService.start();
        assertTrue(lifecycleService.isRunning());

        lifecycleService.stop();
        assertFalse(lifecycleService.isRunning());

        verify(dockerService).start("container-1");
        verify(dockerService).stop("container-1", 3);
    }

    @Test
    public void continuesStartingOtherAgentsWhenOneFails() {
        when(registryService.listRuntimeInfos()).thenReturn(List.of(
                runtime("agent-1", "container-1"),
                runtime("agent-2", "container-2")
        ));
        doThrow(new RuntimeException("docker error")).when(dockerService).start("container-1");

        lifecycleService.start();

        assertTrue(lifecycleService.isRunning());
        verify(dockerService).start("container-1");
        verify(dockerService).start("container-2");
    }

    @Test
    public void doesNotStartAgentsWithStoppedDesiredState() {
        when(registryService.listRuntimeInfos()).thenReturn(List.of(
                runtime("agent-1", "container-1", "stopped")
        ));

        lifecycleService.start();

        assertTrue(lifecycleService.isRunning());
        verify(dockerService, never()).start("container-1");
    }

    @Test
    public void startsAndStopsContainersDiscoveredDirectlyInDocker() {
        when(registryService.listRuntimeInfos()).thenReturn(List.of());
        when(dockerService.listManagedContainerIds()).thenReturn(List.of("container-created-after-start"));

        lifecycleService.start();
        lifecycleService.stop();

        verify(dockerService).start("container-created-after-start");
        verify(dockerService).stop("container-created-after-start", 3);
    }

    @Test
    public void stopsDockerContainersWhenAlfrescoRegistryCannotBeRead() {
        when(registryService.listRuntimeInfos()).thenThrow(new RuntimeException("repository unavailable"));
        when(dockerService.listManagedContainerIds()).thenReturn(List.of("managed-container"));

        lifecycleService.start();
        lifecycleService.stop();

        verify(dockerService).stop("managed-container", 3);
    }

    @Test
    public void explicitAndSmartLifecycleStartDoNotRunTwice() {
        when(registryService.listRuntimeInfos()).thenReturn(List.of());
        when(dockerService.listManagedContainerIds()).thenReturn(List.of("managed-container"));

        lifecycleService.start();
        lifecycleService.start();

        verify(dockerService, times(1)).start("managed-container");
    }

    @Test
    public void startsLicenseMonitorEvenWhenAutomaticAgentStartIsDisabled() {
        com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService enforcement =
                mock(com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService.class);
        lifecycleService.setLicenseEnforcement(enforcement);
        Properties properties = new Properties();
        properties.setProperty("alfresco.alfdockia.subsystem.startAgentsOnStart", "false");
        properties.setProperty("alfresco.alfdockia.subsystem.stopAgentsOnStop", "false");
        lifecycleService.setGlobalProperties(properties);
        lifecycleService.start();
        lifecycleService.stop();
        verify(enforcement).startMonitoring();
        verify(enforcement).stopMonitoring();
    }

    @Test
    public void startupDoesNotStartExcessContainers() {
        com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService enforcement =
                mock(com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService.class);
        lifecycleService.setLicenseEnforcement(enforcement);
        when(dockerService.listManagedContainerIds()).thenReturn(List.of("excess"));
        doThrow(new com.cparedesr.alfdockia.agents.service.exception.BadRequestException(
                "LICENSE_LIMIT_EXCEEDED", "blocked")).when(enforcement).assertCanRun("excess");
        lifecycleService.start();
        verify(dockerService, never()).start("excess");
    }

    private AgentRuntimeInfo runtime(String agentId, String containerId) {
        return runtime(agentId, containerId, "running");
    }

    private AgentRuntimeInfo runtime(String agentId, String containerId, String desiredState) {
        AgentRuntimeInfo info = new AgentRuntimeInfo();
        info.setAgentId(agentId);
        info.setContainerId(containerId);
        info.setDesiredState(desiredState);
        return info;
    }
}
