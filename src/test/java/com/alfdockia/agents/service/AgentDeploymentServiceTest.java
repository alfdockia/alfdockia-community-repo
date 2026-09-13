package com.alfdockia.agents.service;

import com.alfdockia.agents.model.AgentDeployRequest;
import com.alfdockia.agents.service.docker.DockerService;
import com.alfdockia.agents.service.exception.BadRequestException;
import com.alfdockia.agents.service.license.SignedLicenseService;
import com.alfdockia.agents.service.registry.AgentRegistryService;
import jakarta.transaction.UserTransaction;
import org.alfresco.repo.lock.JobLockService;
import org.alfresco.service.transaction.TransactionService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgentDeploymentServiceTest {
    private AgentDeploymentService service;
    private DockerService docker;
    private AgentRegistryService registry;
    private UserTransaction transaction;
    private JobLockService locks;
    private AgentDeployRequest request;

    @Before
    public void setup() {
        docker = mock(DockerService.class);
        registry = mock(AgentRegistryService.class);
        transaction = mock(UserTransaction.class);
        locks = mock(JobLockService.class);
        TransactionService transactions = mock(TransactionService.class);
        when(transactions.getNonPropagatingUserTransaction()).thenReturn(transaction);
        when(locks.getLock(any(), anyLong(), any(JobLockService.JobLockRefreshCallback.class))).thenReturn("token");
        SignedLicenseService license = new SignedLicenseService();
        license.setGlobalProperties(new Properties());
        AgentValidationService validation = new AgentValidationService();
        validation.setRegistryService(registry);
        validation.setLicenseService(license);
        AgentContainerSpecFactory factory = new AgentContainerSpecFactory();
        factory.setSecretsService(ref -> "test");
        service = new AgentDeploymentService();
        AgentCapacityService capacity = new AgentCapacityService();
        capacity.setRegistryService(registry);

        service.setCapacityService(capacity);
        service.setDockerService(docker);
        service.setRegistryService(registry);
        service.setLicenseService(license);
        service.setValidationService(validation);
        service.setContainerSpecFactory(factory);
        service.setTransactionService(transactions);
        service.setJobLockService(locks);
        request = new AgentDeployRequest();
        request.setName("test");
        request.setImage("nginx:alpine");
        request.setEnv(Map.of("CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD", "prop:test"));
        when(docker.createAndStart(anyString(), anyString(), anyMap(), anyMap(), any()))
                .thenReturn(new DockerService.CreateResult("container-id", "running"));
    }

    @Test
    public void sixthCommunityAgentCreatesNothing() throws Exception {
        when(registry.countAgentsUpTo(anyInt())).thenReturn(5);
        try { service.deploy(request); fail("Must reject sixth agent"); }
        catch (BadRequestException e) { assertEquals("LICENSE_LIMIT_EXCEEDED", e.getCode()); }
        verifyNoInteractions(docker);
        verify(registry, never()).createAgentNode(any(), any(), any(), any(), any());
        verify(transaction).rollback();
        verify(transaction, never()).commit();
    }

    @Test
    public void dockerOnlyContainersDoNotBlockRegisteredAgentCreation() throws Exception {
        when(registry.countAgentsUpTo(anyInt())).thenReturn(1);
        when(docker.listManagedContainerIds()).thenReturn(java.util.List.of("orphan"));
        service.deploy(request);
        verify(docker, never()).listManagedContainerIds();
        verify(transaction).commit();
    }

    @Test
    public void unavailableRegistryCannotGrantCapacity() throws Exception {
        when(registry.countAgentsUpTo(anyInt())).thenThrow(new IllegalStateException("offline"));
        try { service.deploy(request); fail("Must reject unknown capacity"); }
        catch (IllegalStateException expected) { }
        verify(docker, never()).createAndStart(any(), any(), any(), any(), any());
        verify(registry, never()).createAgentNode(any(), any(), any(), any(), any());
    }

    @Test
    public void fifthAgentCommitsBeforeUnlocking() throws Exception {
        when(registry.countAgentsUpTo(anyInt())).thenReturn(4);
        service.deploy(request);
        InOrder order = inOrder(transaction, docker, registry, locks);
        order.verify(transaction).begin();
        order.verify(docker).createAndStart(any(), any(), any(), any(), any());
        order.verify(registry).createAgentNode(any(), any(), any(), any(), any());
        order.verify(transaction).commit();
        order.verify(locks).releaseLock(eq("token"), any());
        verify(docker, never()).remove(any(), anyBoolean());
    }

    @Test
    public void registryFailureRollsBackAndRemovesCreatedContainer() throws Exception {
        doThrow(new BadRequestException("REGISTRY_WRITE_FAILED", "failed"))
                .when(registry).createAgentNode(any(), any(), any(), any(), any());
        try { service.deploy(request); fail("Must fail"); }
        catch (BadRequestException e) { assertEquals("REGISTRY_WRITE_FAILED", e.getCode()); }
        verify(transaction).rollback();
        verify(transaction, never()).commit();
        verify(docker).remove(startsWith("agent-"), eq(true));
    }

    @Test
    public void commitFailureRemovesContainerWithoutRetryingCreation() throws Exception {
        doThrow(new jakarta.transaction.RollbackException("commit failed")).when(transaction).commit();
        try { service.deploy(request); fail("Must fail"); }
        catch (IllegalStateException expected) { }
        verify(transaction).rollback();
        verify(docker).createAndStart(any(), any(), any(), any(), any());
        verify(docker).remove(startsWith("agent-"), eq(true));
    }

    @Test
    public void failedDockerStartDoesNotRegisterAgent() throws Exception {
        when(docker.createAndStart(any(), any(), any(), any(), any()))
                .thenThrow(new BadRequestException("DOCKER_CLI_ERROR", "start failed"));
        try { service.deploy(request); fail("Must fail"); }
        catch (BadRequestException expected) { }
        verify(registry, never()).createAgentNode(any(), any(), any(), any(), any());
        verify(transaction).rollback();
        verify(docker).remove(startsWith("agent-"), eq(true));
    }

    @Test(timeout = 10000)
    public void concurrentRequestsCannotBothTakeLastCommunitySlot() throws Exception {
        AtomicInteger committed = new AtomicInteger(4);
        when(registry.countAgentsUpTo(anyInt())).thenAnswer(invocation -> committed.get());
        doAnswer(invocation -> { committed.incrementAndGet(); return null; }).when(transaction).commit();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> deploy = () -> {
            start.await();
            try { service.deploy(request); return true; }
            catch (BadRequestException e) {
                assertEquals("LICENSE_LIMIT_EXCEEDED", e.getCode());
                return false;
            }
        };
        try {
            Future<Boolean> first = executor.submit(deploy);
            Future<Boolean> second = executor.submit(deploy);
            start.countDown();
            assertNotEquals(first.get(), second.get());
            assertEquals(5, committed.get());
            verify(docker, times(1)).createAndStart(any(), any(), any(), any(), any());
            verify(registry, times(1)).createAgentNode(any(), any(), any(), any(), any());
        } finally { executor.shutdownNow(); }
    }
}
