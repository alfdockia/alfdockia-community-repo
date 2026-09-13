package com.cparedesr.alfdockia.agents.service.license;

import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.service.transaction.TransactionService;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LicenseRuntimeEnforcementServiceTest {
    private LicenseRuntimeEnforcementService service;
    private LicenseService license;
    private DockerService docker;
    private AgentRegistryService registry;
    private List<AgentRuntimeInfo> registered;
    private List<AgentRuntimeInfo> containers;

    @Before public void setup() {
        license = mock(LicenseService.class);
        docker = mock(DockerService.class);
        registry = mock(AgentRegistryService.class);
        TransactionService transaction = mock(TransactionService.class);
        RetryingTransactionHelper helper = mock(RetryingTransactionHelper.class);
        when(transaction.getRetryingTransactionHelper()).thenReturn(helper);
        when(helper.doInTransaction(any(), anyBoolean(), anyBoolean())).thenAnswer(invocation ->
                ((RetryingTransactionHelper.RetryingTransactionCallback<?>) invocation.getArgument(0)).execute());
        service = new LicenseRuntimeEnforcementService();
        service.setLicenseService(license);
        service.setDockerService(docker);
        service.setRegistryService(registry);
        service.setTransactionService(transaction);
        when(license.getStatus()).thenReturn(LicenseStatus.community("missing"));
        registered = new ArrayList<>();
        containers = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            registered.add(runtime(i, true));
            containers.add(runtime(i, false));
        }
        Collections.reverse(registered);
        when(registry.listLicenseRuntimeInfos()).thenReturn(registered);
        when(docker.listManagedRuntimeInfos()).thenReturn(containers);
    }

    private AgentRuntimeInfo runtime(int i, boolean registered) {
        AgentRuntimeInfo info = new AgentRuntimeInfo();
        info.setAgentId("agent-" + i);
        info.setContainerId("container-" + i);
        if (registered) info.setNodeId("node-" + i);
        info.setCreatedAt(i * 1000L);
        info.setCurrentState("running");
        info.setDesiredState("running");
        return info;
    }

    private LicenseStatus limit(int count) {
        AlfDokiaLicensePayload payload = new AlfDokiaLicensePayload();
        payload.setMaxAgents(count);
        payload.setEdition("enterprise");
        return LicenseStatus.valid(payload, "test");
    }

    @Test public void licenseRemovalStopsOnlyNewestFiveAndPreservesAllRecords() {
        when(license.getStatus()).thenReturn(limit(25), LicenseStatus.community("removed"));
        service.enforce();
        verify(docker, never()).stop(anyString(), anyInt());
        service.enforce();
        for (int i = 1; i <= 5; i++) verify(docker, never()).stop("container-" + i, 10);
        for (int i = 6; i <= 10; i++) verify(docker).stop("container-" + i, 10);
        verify(registry, times(5)).markLicenseStopped(any());
        verify(docker, never()).remove(anyString(), anyBoolean());
        verify(registry, never()).deleteByAgentId(anyString());
    }

    @Test public void blocksExcessAgentAndAllowsOldestEvenWhenInputIsUnsorted() {
        service.assertCanRun("agent-1");
        service.assertCanRun("container-5");
        try { service.assertCanRun("agent-6"); fail("Must reject"); }
        catch (BadRequestException e) { assertEquals("LICENSE_LIMIT_EXCEEDED", e.getCode()); }
    }

    @Test public void reducedPaidLicenseUsesItsOwnLimit() {
        when(license.getStatus()).thenReturn(limit(3));
        service.enforce();
        verify(docker, times(7)).stop(anyString(), eq(10));
        verify(docker, never()).stop("container-3", 10);
    }

    @Test public void restoredLicenseAllowsStartButDoesNotStartAutomatically() {
        service.enforce();
        when(license.getStatus()).thenReturn(limit(25));
        service.assertCanRun("agent-10");
        service.enforce();
        verify(docker, never()).start(anyString());
    }

    @Test public void unlimitedLicenseDoesNotStopContainers() {
        when(license.getStatus()).thenReturn(limit(-1));
        service.enforce();
        service.assertCanRun("agent-10");
        verify(docker, never()).stop(anyString(), anyInt());
    }

    @Test public void dockerOnlyContainersAreNeverManaged() {
        when(registry.listLicenseRuntimeInfos()).thenReturn(List.of());
        service.enforce();
        verifyNoInteractions(docker);
        verify(registry, never()).markLicenseStopped(any());
    }

    @Test public void repositoryFailureNeverFallsBackToDocker() {
        when(registry.listLicenseRuntimeInfos()).thenThrow(new IllegalStateException("offline"));
        try { service.enforce(); fail("Must fail closed"); }
        catch (IllegalStateException expected) { }
        verifyNoInteractions(docker);
        try { service.assertCanRun("agent-1"); fail("Unknown inventory must block start"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void usesOriginalAgentAgeAfterContainerRecreation() {
        containers.get(0).setCreatedAt(999999L);
        service.enforce();
        verify(docker, never()).stop("container-1", 10);
    }

    @Test public void oneStopFailureDoesNotPreventOtherStops() {
        doThrow(new IllegalStateException("offline")).when(docker).stop("container-6", 10);
        service.enforce();
        verify(docker).stop("container-10", 10);
        verify(registry, times(4)).markLicenseStopped(any());
    }

    @Test public void stoppedMetadataIsEnforcedWithoutDockerDiscovery() {
        for (AgentRuntimeInfo info : containers) info.setCurrentState("stopped");
        for (AgentRuntimeInfo info : registered) {
            info.setDesiredState("stopped");
            info.setCurrentState("stopped");
        }
        service.enforce();
        verify(docker, times(10)).stop(anyString(), eq(10));
        verify(docker, never()).listManagedRuntimeInfos();
        verify(registry, never()).markLicenseStopped(any());
    }
}
