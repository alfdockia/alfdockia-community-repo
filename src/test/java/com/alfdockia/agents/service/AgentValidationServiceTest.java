/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service;

import com.alfdockia.agents.model.AgentDeployRequest;
import com.alfdockia.agents.service.exception.BadRequestException;
import com.alfdockia.agents.service.license.SignedLicenseService;
import com.alfdockia.agents.service.registry.AgentRegistryService;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class AgentValidationServiceTest {

    private AgentValidationService validationService;
    private AgentRegistryService registryService;

    @Before
    public void setUp() {
        validationService = new AgentValidationService();
        registryService = mock(AgentRegistryService.class);

        validationService.setRegistryService(registryService);
    }

    @Test
    public void validRequestPasses() {
        AgentDeployRequest req = validRequest();

        when(registryService.existsByName("agent1")).thenReturn(false);

        validationService.validateDeployRequest(req);
    }

    @Test(expected = BadRequestException.class)
    public void duplicateNameFails() {
        AgentDeployRequest req = validRequest();
        when(registryService.existsByName("agent1")).thenReturn(true);

        validationService.validateDeployRequest(req);
    }

    @Test(expected = BadRequestException.class)
    public void missingListenerPasswordSecretFails() {
        AgentDeployRequest req = validRequest();
        req.setListener(null);

        when(registryService.existsByName("agent1")).thenReturn(false);

        validationService.validateDeployRequest(req);
    }

    @Test
    public void envSecretRefCanProvideListenerPassword() {
        AgentDeployRequest req = validRequest();
        req.setListener(null);
        req.setEnv(java.util.Map.of(
                "CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD",
                "prop:alfresco.alfdockia.secret.content_service_password"
        ));

        when(registryService.existsByName("agent1")).thenReturn(false);

        validationService.validateDeployRequest(req);
    }

    @Test(expected = BadRequestException.class)
    public void rawPasswordEnvValueFailsEvenWhenListenerSecretExists() {
        AgentDeployRequest req = validRequest();
        req.setEnv(java.util.Map.of("CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD", "admin"));

        when(registryService.existsByName("agent1")).thenReturn(false);

        validationService.validateDeployRequest(req);
    }

    @Test(expected = BadRequestException.class)
    public void invalidPortFails() {
        AgentDeployRequest req = validRequest();
        AgentDeployRequest.PortMapping p = new AgentDeployRequest.PortMapping();
        p.setContainerPort(70000);
        p.setHostPort(18080);
        req.setPorts(java.util.List.of(p));

        when(registryService.existsByName("agent1")).thenReturn(false);

        validationService.validateDeployRequest(req);
    }

    @Test
    public void communityLimitBlocksSixthAgent() {
        AgentDeployRequest req = validRequest();
        SignedLicenseService licenseService = new SignedLicenseService();
        licenseService.setGlobalProperties(new Properties());
        validationService.setLicenseService(licenseService);

        when(registryService.existsByName("agent1")).thenReturn(false);
        when(registryService.countAgentsUpTo(5)).thenReturn(5);

        try {
            validationService.validateDeployRequest(req);
            fail("Expected Community limit to block the sixth agent");
        } catch (BadRequestException e) {
            assertEquals("LICENSE_LIMIT_EXCEEDED", e.getCode());
        }
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
