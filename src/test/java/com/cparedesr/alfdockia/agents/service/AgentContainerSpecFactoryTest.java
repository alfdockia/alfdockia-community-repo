/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.service.secrets.SecretsService;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentContainerSpecFactoryTest {

    private AgentContainerSpecFactory factory;
    private SecretsService secretsService;

    @Before
    public void setUp() {
        factory = new AgentContainerSpecFactory();
        secretsService = mock(SecretsService.class);
        factory.setSecretsService(secretsService);
    }

    @Test
    public void buildsAlfDociaEnvironmentWithoutLegacyTargetVariables() {
        AgentDeployRequest req = validRequest();
        req.setEnv(Map.of("LOG_LEVEL", "debug"));

        when(secretsService.resolve("prop:alfresco.alfdockia.secret.content_service_password")).thenReturn("secret");

        Map<String, String> env = factory.buildEnvironment(req);

        assertEquals("secret", env.get("CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD"));
        assertEquals("debug", env.get("LOG_LEVEL"));
        assertFalse(env.containsKey("ALFRESCO_BASE_URL"));
        assertFalse(env.containsKey("ALFRESCO_AUTH_TYPE"));
        assertFalse(env.containsKey("ALFRESCO_USERNAME"));
        assertFalse(env.containsKey("ALFRESCO_PASSWORD"));
        assertFalse(env.containsKey("ALFRESCO_TARGET_NODE_ID"));
        assertFalse(env.containsKey("POLLING_SECONDS"));
    }

    @Test
    public void resolvesPropReferencesDeclaredAsEnvironmentValues() {
        AgentDeployRequest req = validRequest();
        req.setListener(null);
        req.setEnv(Map.of("CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD", "prop:alfresco.alfdockia.secret.content_service_password"));

        when(secretsService.resolve("prop:alfresco.alfdockia.secret.content_service_password")).thenReturn("secret");

        Map<String, String> env = factory.buildEnvironment(req);

        assertEquals("secret", env.get("CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD"));
    }

    private AgentDeployRequest validRequest() {
        AgentDeployRequest req = new AgentDeployRequest();
        req.setName("agent1");
        req.setImage("registry.tuorg.com/listeners/openmed-pii:1.0.0");

        AgentDeployRequest.ListenerConfig listener = new AgentDeployRequest.ListenerConfig();
        AgentDeployRequest.SecretRef sr = new AgentDeployRequest.SecretRef();
        sr.setSecretRef("prop:alfresco.alfdockia.secret.content_service_password");
        listener.setPasswordSecretRef(sr);
        req.setListener(listener);

        return req;
    }
}
