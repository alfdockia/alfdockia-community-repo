/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service;

import com.alfdockia.agents.model.AgentDeployRequest;
import com.alfdockia.agents.model.AgentDeployResponse;
import com.alfdockia.agents.service.docker.DockerService;
import com.alfdockia.agents.service.license.LicenseService;
import com.alfdockia.agents.service.license.LicenseStatus;
import com.alfdockia.agents.service.registry.AgentRegistryService;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.transaction.UserTransaction;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.repo.lock.JobLockService;
import org.alfresco.service.namespace.QName;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.alfdockia.agents.service.exception.BadRequestException;

/**
 * Orquesta el despliegue de un agente: prepara variables de entorno seguras,
 * arranca el contenedor y registra el resultado en el repositorio.
 */
public class AgentDeploymentService {

    private static final Log LOGGER = LogFactory.getLog(AgentDeploymentService.class);
    private static final QName DEPLOY_LOCK = QName.createQName(
            "http://www.com/model/alfdockia/1.0", "agent-deployment");
    private static final long LOCK_TTL = 30000;
    private TransactionService transactionService;
    private JobLockService jobLockService;
    private AgentValidationService validationService;

    public void setTransactionService(TransactionService value) { transactionService = value; }
    public void setJobLockService(JobLockService value) { jobLockService = value; }
    public void setValidationService(AgentValidationService value) { validationService = value; }

    private DockerService dockerService;
    private AgentRegistryService registryService;
    private AgentContainerSpecFactory containerSpecFactory;
    private LicenseService licenseService;
    private AgentCapacityService capacityService;
    public void setCapacityService(AgentCapacityService value) { capacityService = value; }

    public void setDockerService(DockerService dockerService) { this.dockerService = dockerService; }
    public void setRegistryService(AgentRegistryService registryService) { this.registryService = registryService; }
    public void setContainerSpecFactory(AgentContainerSpecFactory containerSpecFactory) { this.containerSpecFactory = containerSpecFactory; }
    public void setLicenseService(LicenseService licenseService) { this.licenseService = licenseService; }

    public synchronized AgentDeployResponse deploy(AgentDeployRequest req) {
        // Hold the shared lock until commit: concurrent requests must see committed capacity.
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean lost = new AtomicBoolean(false);
        String token = jobLockService.getLock(DEPLOY_LOCK, LOCK_TTL, new JobLockService.JobLockRefreshCallback() {
            public boolean isActive() { return active.get(); }
            public void lockReleased() { lost.set(true); }
        });
        UserTransaction transaction = null;
        String agentId = "agent-" + UUID.randomUUID();
        boolean dockerAttempted = false;
        try {
            transaction = transactionService.getNonPropagatingUserTransaction();
            transaction.begin();
            validationService.validateDeployRequest(req);
            assertLicenseCapacity();

            AgentDeployRequest sanitized = containerSpecFactory.sanitize(req);
            Map<String, String> env = containerSpecFactory.buildEnvironment(sanitized);
            Map<String, String> labels = containerSpecFactory.buildLabels(agentId, sanitized.getName());
            if (lost.get()) throw new IllegalStateException("Deployment lock lost");
            dockerAttempted = true;
            DockerService.CreateResult created = dockerService.createAndStart(agentId,
                    sanitized.getImage(), env, labels, sanitized.getPorts());
            registryService.createAgentNode(agentId, sanitized, created.getContainerId(),
                    "running", created.getCurrentState());
            if (lost.get()) throw new IllegalStateException("Deployment lock lost");
            jobLockService.refreshLock(token, DEPLOY_LOCK, LOCK_TTL);
            transaction.commit();
            return new AgentDeployResponse(agentId, sanitized.getName(), "running",
                    created.getCurrentState(),
                    "/alfresco/api/-default-/public/alfdockia/versions/1/agents/" + agentId);
        } catch (Exception failure) {
            if (transaction != null) {
                try { transaction.rollback(); }
                catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            // The unique Docker name also works when create succeeded but start failed.
            if (dockerAttempted) {
                try { dockerService.remove(agentId, true); }
                catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    LOGGER.error("No se pudo eliminar el contenedor de un alta fallida: " + agentId, cleanupFailure);
                }
            }
            if (failure instanceof BadRequestException) throw (BadRequestException) failure;
            throw new IllegalStateException("Agent deployment failed", failure);
        } finally {
            active.set(false);
            try { jobLockService.releaseLock(token, DEPLOY_LOCK); }
            catch (Exception releaseFailure) {
                LOGGER.warn("No se pudo liberar el bloqueo de altas de agentes", releaseFailure);
            }
        }
    }

    private void assertLicenseCapacity() {
        if (licenseService == null) {
            throw new IllegalStateException("License service is required");
        }

        LicenseStatus status = licenseService.getStatus();
        if (status.isUnlimitedAgents()) {
            return;
        }

        int currentAgents = capacityService.countAgents();
        licenseService.assertCanCreateAgent(currentAgents);
    }
}
