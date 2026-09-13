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

    public void assertCanRun(String agentOrContainerId) {
        LicenseStatus status = licenseService.getStatus();
        if (status.isUnlimitedAgents()) return;
        List<AgentRuntimeInfo> inventory = inventory(false);
        for (int i = 0; i < Math.min(status.getMaxAgents(), inventory.size()); i++) {
            AgentRuntimeInfo info = inventory.get(i);
            if (agentOrContainerId.equals(info.getAgentId()) || agentOrContainerId.equals(info.getContainerId())) return;
        }
        throw new BadRequestException("LICENSE_LIMIT_EXCEEDED",
                "limite de " + status.getMaxAgents() + " agentes de la edicion " + status.getEdition()
                        + "; este agente no esta entre los " + status.getMaxAgents() + " mas antiguos permitidos");
    }

    public synchronized void enforce() {
        LicenseStatus status = licenseService.getStatus();
        if (status.isUnlimitedAgents()) return;
        List<AgentRuntimeInfo> inventory = inventory(true);
        for (int i = status.getMaxAgents(); i < inventory.size(); i++) {
            AgentRuntimeInfo info = inventory.get(i);
            try {
                if (info.getContainerId() != null && "running".equals(info.getCurrentState())) {
                    dockerService.stop(info.getContainerId(), 10);
                    LOGGER.warn("Agente detenido por limite de licencia: " + info.getAgentId()
                            + "; limite=" + status.getMaxAgents() + "; contenedor=" + info.getContainerId());
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

    private List<AgentRuntimeInfo> inventory(boolean allowDockerFallback) {
        List<AgentRuntimeInfo> registered;
        try {
            registered = AuthenticationUtil.runAsSystem(() ->
                    transactionService.getRetryingTransactionHelper()
                            .doInTransaction(() -> registryService.listLicenseRuntimeInfos(), true, true));
        } catch (RuntimeException failure) {
            if (!allowDockerFallback) throw failure;
            LOGGER.warn("Registro de Alfresco no disponible; se aplica el limite usando las fechas de Docker. Causa: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            LOGGER.debug("Detalle del fallo al leer el registro para aplicar la licencia", failure);
            registered = Collections.emptyList();
        }
        Map<String, AgentRuntimeInfo> byContainer = new LinkedHashMap<>();
        List<AgentRuntimeInfo> result = new ArrayList<>();
        for (AgentRuntimeInfo info : registered) {
            result.add(info);
            if (info.getContainerId() != null) byContainer.put(info.getContainerId(), info);
        }
        for (AgentRuntimeInfo docker : dockerService.listManagedRuntimeInfos()) {
            AgentRuntimeInfo existing = byContainer.get(docker.getContainerId());
            if (existing == null) {
                byContainer.put(docker.getContainerId(), docker);
                result.add(docker);
            } else {
                // Docker is authoritative for whether the container is actually running.
                existing.setCurrentState(docker.getCurrentState());
                if (existing.getCreatedAt() == Long.MAX_VALUE) existing.setCreatedAt(docker.getCreatedAt());
            }
        }
        result.sort(Comparator.comparingLong(AgentRuntimeInfo::getCreatedAt)
                .thenComparing(info -> Objects.toString(info.getAgentId(), ""))
                .thenComparing(info -> Objects.toString(info.getContainerId(), "")));
        return result;
    }
}
