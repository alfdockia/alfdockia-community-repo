package com.cparedesr.alfdockia.agents.service.license;

import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.*;
import java.util.concurrent.*;

/** Enforces licensed runtime slots without deleting containers or repository configuration. */
public class LicenseRuntimeEnforcementService {
    private static final Log LOGGER = LogFactory.getLog(LicenseRuntimeEnforcementService.class);
    private LicenseService licenseService;
    private DockerService dockerService;
    private AgentRegistryService registryService;
    private TransactionService transactionService;
    private ScheduledExecutorService scheduler;

    public void setLicenseService(LicenseService value) { licenseService = value; }
    public void setDockerService(DockerService value) { dockerService = value; }
    public void setRegistryService(AgentRegistryService value) { registryService = value; }
    public void setTransactionService(TransactionService value) { transactionService = value; }

    public synchronized void startMonitoring() {
        if (scheduler != null) return;
        enforceSafely();
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "alfdockia-license-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::enforceSafely, 60, 60, TimeUnit.SECONDS);
    }

    public synchronized void stopMonitoring() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void enforceSafely() {
        try { enforce(); }
        catch (RuntimeException failure) {
            LOGGER.error("No se pudo aplicar el limite de licencia; se reintentara en el siguiente ciclo", failure);
        }
    }

    public void updateRuntimeState(AgentRuntimeInfo runtime, String currentState) {
        AuthenticationUtil.runAsSystem(() -> transactionService.getRetryingTransactionHelper()
                .doInTransaction(() -> { registryService.updateRuntimeState(runtime, currentState); return null; }, false, true));
    }

    public void assertCanRun(String agentOrContainerId) {
        LicenseStatus status = licenseService.getStatus();
        List<AgentRuntimeInfo> inventory = inventory();
        int permitted = status.isUnlimitedAgents() ? inventory.size() : Math.min(status.getMaxAgents(), inventory.size());
        for (int i = 0; i < permitted; i++) {
            AgentRuntimeInfo info = inventory.get(i);
            if (agentOrContainerId.equals(info.getAgentId()) || agentOrContainerId.equals(info.getContainerId())) return;
        }
        throw new BadRequestException("LICENSE_LIMIT_EXCEEDED",
                "limite de " + status.getMaxAgents() + " agentes de la edicion " + status.getEdition()
                        + "; este agente no esta entre los " + status.getMaxAgents() + " mas antiguos permitidos");
    }

    public synchronized void enforce() {
        LicenseStatus status = licenseService.getStatus();
        List<AgentRuntimeInfo> inventory = inventory();
        for (int i = 0; i < inventory.size(); i++) {
            AgentRuntimeInfo info = inventory.get(i);
            boolean excess = !status.isUnlimitedAgents() && i >= status.getMaxAgents();
            if (!excess && !"stopped".equalsIgnoreCase(info.getDesiredState())) continue;
            try {
                if (info.getContainerId() != null && !info.getContainerId().isBlank()) {
                    dockerService.stop(info.getContainerId(), 10);
                    if (!"stopped".equals(info.getCurrentState())) {
                        LOGGER.info("Agente detenido: " + info.getAgentId() + (excess
                                ? "; excede el limite de " + status.getMaxAgents() + " agentes"
                                : "; estado deseado stopped en Alfresco"));
                    }
                }
                if (info.getNodeId() != null && (!"stopped".equals(info.getDesiredState())
                        || !"stopped".equals(info.getCurrentState()))) {
                    AuthenticationUtil.runAsSystem(() -> transactionService.getRetryingTransactionHelper()
                            .doInTransaction(() -> { registryService.markLicenseStopped(info); return null; }, false, true));
                }
            } catch (RuntimeException failure) {
                LOGGER.error("No se pudo detener o actualizar el agente excedente " + info.getAgentId(), failure);
            }
        }
    }

    private List<AgentRuntimeInfo> inventory() {
        List<AgentRuntimeInfo> result = new ArrayList<>(AuthenticationUtil.runAsSystem(() ->
                transactionService.getRetryingTransactionHelper()
                        .doInTransaction(() -> registryService.listLicenseRuntimeInfos(), true, true)));
        result.sort(Comparator.comparingLong(AgentRuntimeInfo::getCreatedAt)
                .thenComparing(info -> Objects.toString(info.getAgentId(), ""))
                .thenComparing(info -> Objects.toString(info.getNodeId(), "")));
        return result;
    }
}
