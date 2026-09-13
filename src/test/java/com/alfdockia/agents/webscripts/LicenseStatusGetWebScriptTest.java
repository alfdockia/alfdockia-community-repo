package com.alfdockia.agents.webscripts;

import com.alfdockia.agents.service.AgentCapacityService;
import com.alfdockia.agents.service.docker.DockerService;
import com.alfdockia.agents.service.license.SignedLicenseService;
import com.alfdockia.agents.service.registry.AgentRegistryService;
import com.alfdockia.agents.service.subsystem.AgentSubsystemServiceLocator;
import org.junit.Test;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LicenseStatusGetWebScriptTest {
    @Test
    public void reportsOnlyRepositoryAgentsInCommunityCapacity() {
        AgentRegistryService registry = mock(AgentRegistryService.class);
        DockerService docker = mock(DockerService.class);
        when(registry.countAgentsUpTo(Integer.MAX_VALUE)).thenReturn(1);
        when(registry.listRegisteredContainerIds()).thenReturn(List.of("a"));
        when(docker.listManagedContainerIds()).thenReturn(List.of("a", "b", "c", "d", "e", "f", "g"));
        AgentCapacityService capacity = new AgentCapacityService();
        capacity.setRegistryService(registry);

        SignedLicenseService license = new SignedLicenseService();
        license.setGlobalProperties(new Properties());
        AgentSubsystemServiceLocator locator = mock(AgentSubsystemServiceLocator.class);
        when(locator.getCapacityService()).thenReturn(capacity);
        when(locator.getLicenseService()).thenReturn(license);
        LicenseStatusGetWebScript endpoint = new LicenseStatusGetWebScript();
        endpoint.setSubsystemServiceLocator(locator);
        Status status = new Status();
        Map<String, Object> result = endpoint.executeImpl(mock(WebScriptRequest.class), status, new Cache());
        assertEquals(200, status.getCode());
        assertEquals(1, ((Map<?, ?>) result.get("data")).get("currentAgents"));
        assertFalse(license.getStatus().isValid());
        assertEquals(5, license.getStatus().getMaxAgents());
    }
}
