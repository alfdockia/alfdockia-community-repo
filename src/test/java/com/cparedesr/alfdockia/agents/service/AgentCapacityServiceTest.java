package com.cparedesr.alfdockia.agents.service;

import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgentCapacityServiceTest {
    private int count(int registered, List<String> registeredIds, List<String> dockerIds) {
        AgentRegistryService registry = mock(AgentRegistryService.class);
        DockerService docker = mock(DockerService.class);
        when(registry.countAgentsUpTo(Integer.MAX_VALUE)).thenReturn(registered);
        when(registry.listRegisteredContainerIds()).thenReturn(registeredIds);
        when(docker.listManagedContainerIds()).thenReturn(dockerIds);
        AgentCapacityService service = new AgentCapacityService();
        service.setRegistryService(registry);

        int result = service.countAgents();
        verifyNoInteractions(docker);
        return result;
    }

    @Test public void ignoresContainersWithoutRepositoryRecords() {
        assertEquals(1, count(1, List.of("a"), List.of("a", "b", "c", "d", "e", "f", "g")));
    }
    @Test public void countsRecordsEvenWithoutContainers() {
        assertEquals(3, count(3, List.of("a", "b"), List.of("a", "b", "c", "d")));
    }
    @Test public void dockerOnlyInventoryDoesNotConsumeRepositorySlots() {
        assertEquals(0, count(0, List.of(), List.of("a", "a")));
    }
    @Test public void emptyInventoryIsZero() {
        assertEquals(0, count(0, List.of(), List.of()));
    }
}
