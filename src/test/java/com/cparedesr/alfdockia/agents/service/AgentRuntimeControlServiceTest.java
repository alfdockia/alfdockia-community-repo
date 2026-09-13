/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.model.AgentDetail;
import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import com.cparedesr.alfdockia.agents.service.secrets.SecretsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentRuntimeControlServiceTest {

    private AgentRuntimeControlService service;
    private AgentRegistryService registryService;
    private DockerService dockerService;
    private AgentValidationService validationService;
    private SecretsService secretsService;

    @Before
    public void setUp() {
        registryService = mock(AgentRegistryService.class);
        dockerService = mock(DockerService.class);
        validationService = mock(AgentValidationService.class);
        secretsService = mock(SecretsService.class);

        AgentContainerSpecFactory factory = new AgentContainerSpecFactory();
        factory.setSecretsService(secretsService);

        Properties props = new Properties();
        props.setProperty("alfresco.alfdockia.docker.enabled", "true");
        props.setProperty("alfresco.alfdockia.subsystem.stopTimeoutSeconds", "7");

        service = new AgentRuntimeControlService();
        service.setLicenseEnforcement(mock(com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService.class));
        service.setRegistryService(registryService);
        service.setDockerService(dockerService);
        service.setValidationService(validationService);
        service.setContainerSpecFactory(factory);
        service.setGlobalProperties(props);
    }

    @Test
    public void restartMergesEnvironmentAndRecreatesContainer() throws Exception {
        AgentDetail current = detail(configWithLegacyTarget(), "old-container");
        AgentDetail updated = detail("{}", "new-container");
        AgentRuntimeInfo runtime = new AgentRuntimeInfo();
        runtime.setAgentId("agent-1");
        runtime.setContainerId("old-container");

        when(registryService.getAgentDetailByAgentId("agent-1")).thenReturn(current, updated);
        when(registryService.getRuntimeInfoByAgentId("agent-1")).thenReturn(runtime);
        when(secretsService.resolve("prop:alfresco.alfdockia.secret.content_service_password")).thenReturn("secret");
        when(dockerService.createAndStart(eq("agent-1"), eq("image:v1"), any(), any(), any()))
                .thenReturn(new DockerService.CreateResult("new-container", "running"));

        service.restartAgent("agent-1", new ObjectMapper().readTree("{\"env\":{\"B\":\"2\"}}"));

        verify(dockerService).remove("old-container", true);

        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dockerService).createAndStart(eq("agent-1"), eq("image:v1"), envCaptor.capture(), any(), any());
        assertEquals("1", envCaptor.getValue().get("A"));
        assertEquals("2", envCaptor.getValue().get("B"));
        assertEquals("secret", envCaptor.getValue().get("CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD"));
        assertFalse(envCaptor.getValue().containsKey("ALFRESCO_BASE_URL"));
        assertFalse(envCaptor.getValue().containsKey("ALFRESCO_AUTH_TYPE"));
        assertFalse(envCaptor.getValue().containsKey("ALFRESCO_USERNAME"));
        assertFalse(envCaptor.getValue().containsKey("ALFRESCO_PASSWORD"));
        assertFalse(envCaptor.getValue().containsKey("ALFRESCO_TARGET_NODE_ID"));
        assertFalse(envCaptor.getValue().containsKey("POLLING_SECONDS"));

        ArgumentCaptor<AgentDeployRequest> requestCaptor = ArgumentCaptor.forClass(AgentDeployRequest.class);
        verify(registryService).updateAgentNode(
                eq("agent-1"),
                requestCaptor.capture(),
                eq("new-container"),
                eq("running"),
                eq("running")
        );
        assertEquals("2", requestCaptor.getValue().getEnv().get("B"));
    }

    @Test
    public void stopAgentUpdatesDesiredAndCurrentState() {
        AgentRuntimeInfo runtime = new AgentRuntimeInfo();
        runtime.setAgentId("agent-1");
        runtime.setContainerId("container-1");
        when(registryService.getRuntimeInfoByAgentId("agent-1")).thenReturn(runtime);
        when(registryService.getAgentDetailByAgentId("agent-1")).thenReturn(detail("{}", "container-1"));

        service.stopAgent("agent-1");

        verify(dockerService).stop("container-1", 7);
        verify(registryService).updateAgentState("agent-1", "stopped", "stopped");
    }

    @Test
    public void licenseBlocksStartBeforeDockerOrRegistryMutation() {
        com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService enforcement =
                mock(com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService.class);
        service.setLicenseEnforcement(enforcement);
        org.mockito.Mockito.doThrow(new com.cparedesr.alfdockia.agents.service.exception.BadRequestException(
                "LICENSE_LIMIT_EXCEEDED", "blocked")).when(enforcement).assertCanRun("agent-6");
        try { service.startAgent("agent-6"); org.junit.Assert.fail("Must reject"); }
        catch (com.cparedesr.alfdockia.agents.service.exception.BadRequestException expected) { }
        org.mockito.Mockito.verifyNoInteractions(dockerService, registryService);
    }

    @Test
    public void licenseBlocksRestartBeforeRemovingOriginalContainer() {
        com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService enforcement =
                mock(com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService.class);
        service.setLicenseEnforcement(enforcement);
        org.mockito.Mockito.doThrow(new com.cparedesr.alfdockia.agents.service.exception.BadRequestException(
                "LICENSE_LIMIT_EXCEEDED", "blocked")).when(enforcement).assertCanRun("agent-6");
        try { service.restartAgent("agent-6", null); org.junit.Assert.fail("Must reject"); }
        catch (com.cparedesr.alfdockia.agents.service.exception.BadRequestException expected) { }
        org.mockito.Mockito.verifyNoInteractions(dockerService, registryService);
    }

    private AgentDetail detail(String configJson, String containerId) {
        AgentDetail detail = new AgentDetail();
        detail.setAgentId("agent-1");
        detail.setName("agent1");
        detail.setImage("image:v1");
        detail.setContainerId(containerId);
        detail.setConfigJson(configJson);
        return detail;
    }

    private String configWithLegacyTarget() {
        return "{"
                + "\"name\":\"agent1\","
                + "\"image\":\"image:v1\","
                + "\"alfresco\":{"
                + "\"baseUrl\":\"http://alfresco\","
                + "\"authType\":\"basic\","
                + "\"username\":\"svc_ai\","
                + "\"passwordSecretRef\":{\"secretRef\":\"prop:alfresco.alfdockia.secret.content_service_password\"},"
                + "\"targetNodeId\":\"workspace://SpacesStore/legacy\","
                + "\"pollingSeconds\":10"
                + "},"
                + "\"llm\":{"
                + "\"provider\":\"ollama\","
                + "\"baseUrl\":\"http://ollama:11434\","
                + "\"model\":\"llama3.1\","
                + "\"prompt\":\"hola\""
                + "},"
                + "\"env\":{\"A\":\"1\"}"
                + "}";
    }
}
