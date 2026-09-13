/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.subsystem;

import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.license.LicenseRuntimeEnforcementService;
import com.cparedesr.alfdockia.agents.service.docker.DockerService;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Coordina el arranque y la parada de los runtimes cuando Alfresco inicia o
 * detiene el subsistema Alfdockia.
 */
public class AgentSubsystemLifecycleService implements SmartLifecycle {

    private static final Log LOGGER = LogFactory.getLog(AgentSubsystemLifecycleService.class);
    private static final List<String> STARTUP_BANNER = List.of(
            "==============================================================",
            "                          Alfdockia",
            "          Integracion nativa de Alfresco con Docker",
            "",
            "                 Copyright (c) 2026 Alfdockia",
            "                Todos los derechos reservados.",
            "==============================================================");

    private LicenseRuntimeEnforcementService licenseEnforcement;
    public void setLicenseEnforcement(LicenseRuntimeEnforcementService value) {
        licenseEnforcement = value;
    }
    private AgentRegistryService registryService;
    private DockerService dockerService;
    private Properties globalProperties;
    private volatile boolean running;
    private final Set<String> knownContainerIds = new LinkedHashSet<>();

    public void setRegistryService(AgentRegistryService registryService) {
        this.registryService = registryService;
    }

    public void setDockerService(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    public void setGlobalProperties(Properties globalProperties) {
        this.globalProperties = globalProperties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        STARTUP_BANNER.forEach(LOGGER::info);
        licenseEnforcement.startMonitoring();

        if (!isStartAgentsOnStart()) {
            running = true;
            LOGGER.info("Arranque automatico de agentes desactivado para Alfdockia");
            return;
        }

        List<AgentRuntimeInfo> runtimes = loadAllRuntimeInfos();
        String startupMessage = "[Alfdockia] Subsistema inicializado. Contenedores administrados encontrados: "
                + runtimes.size();
        LOGGER.info(startupMessage);
        for (AgentRuntimeInfo runtime : runtimes) {
            if (!shouldStart(runtime)) {
                continue;
            }

            String containerId = normalize(runtime.getContainerId());
            if (containerId == null) {
                continue;
            }

            try {
                licenseEnforcement.assertCanRun(containerId);
                knownContainerIds.add(containerId);
                dockerService.start(containerId);
                LOGGER.info("Agente " + runtime.getAgentId() + " arrancado al iniciar el subsistema");
            } catch (RuntimeException e) {
                if (e instanceof BadRequestException
                        && "LICENSE_LIMIT_EXCEEDED".equals(((BadRequestException) e).getCode())) {
                    LOGGER.info("Agente " + runtime.getAgentId() + " no arrancado al iniciar Alfdockia: " + e.getMessage());
                } else {
                    LOGGER.warn("No se pudo arrancar el agente " + runtime.getAgentId()
                            + " con contenedor " + containerId + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    LOGGER.debug("Detalle del fallo de arranque del agente " + runtime.getAgentId(), e);
                }
            }
        }

        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }

        licenseEnforcement.stopMonitoring();
        if (!isStopAgentsOnStop()) {
            running = false;
            LOGGER.info("Parada automatica de agentes desactivada para Alfdockia");
            return;
        }

        int timeout = getStopTimeoutSeconds();
        List<AgentRuntimeInfo> runtimes = loadAllRuntimeInfos();
        String shutdownMessage = "[Alfdockia] Deteniendo el subsistema. Contenedores administrados encontrados: "
                + runtimes.size();
        LOGGER.info(shutdownMessage);
        for (AgentRuntimeInfo runtime : runtimes) {
            String containerId = normalize(runtime.getContainerId());
            if (containerId == null) {
                continue;
            }

            try {
                dockerService.stop(containerId, timeout);
                LOGGER.info("Agente " + runtime.getAgentId() + " parado al detener el subsistema");
            } catch (RuntimeException e) {
                LOGGER.warn("No se pudo parar el agente " + runtime.getAgentId()
                        + " con contenedor " + containerId + " al detener el subsistema", e);
            }
        }

        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    private List<AgentRuntimeInfo> loadRuntimeInfos() {
        try {
            return AuthenticationUtil.runAsSystem(() -> registryService.listRuntimeInfos());
        } catch (RuntimeException e) {
            LOGGER.info("Registro de agentes de Alfdockia no disponible durante el ciclo de vida del subsistema; "
                    + "se continuara sin nodos de registro y se usaran los contenedores conocidos o descubiertos en Docker. "
                    + "Causa: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Detalle tecnico al leer el registro de agentes de Alfdockia", e);
            }
            return List.of();
        }
    }

    private List<AgentRuntimeInfo> loadAllRuntimeInfos() {
        Map<String, AgentRuntimeInfo> runtimesByContainer = new LinkedHashMap<>();
        for (AgentRuntimeInfo runtime : loadRuntimeInfos()) {
            addRuntime(runtimesByContainer, runtime);
        }

        for (String containerId : knownContainerIds) {
            addRuntime(runtimesByContainer, runtimeFromContainerId(containerId));
        }

        try {
            for (String containerId : dockerService.listManagedContainerIds()) {
                addRuntime(runtimesByContainer, runtimeFromContainerId(containerId));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("No se pudieron descubrir en Docker los contenedores administrados por Alfdockia", e);
        }

        knownContainerIds.addAll(runtimesByContainer.keySet());
        return new ArrayList<>(runtimesByContainer.values());
    }

    private void addRuntime(Map<String, AgentRuntimeInfo> runtimesByContainer, AgentRuntimeInfo runtime) {
        if (runtime == null) return;
        String containerId = normalize(runtime.getContainerId());
        if (containerId == null) return;
        runtimesByContainer.putIfAbsent(containerId, runtime);
    }

    private AgentRuntimeInfo runtimeFromContainerId(String containerId) {
        AgentRuntimeInfo info = new AgentRuntimeInfo();
        info.setAgentId(containerId);
        info.setContainerId(containerId);
        return info;
    }

    private boolean shouldStart(AgentRuntimeInfo runtime) {
        String desiredState = normalize(runtime.getDesiredState());
        return desiredState == null || "running".equalsIgnoreCase(desiredState);
    }

    private boolean isStartAgentsOnStart() {
        return Boolean.parseBoolean(getProperty("alfresco.alfdockia.subsystem.startAgentsOnStart", "true"));
    }

    private boolean isStopAgentsOnStop() {
        return Boolean.parseBoolean(getProperty("alfresco.alfdockia.subsystem.stopAgentsOnStop", "true"));
    }

    private int getStopTimeoutSeconds() {
        String raw = getProperty("alfresco.alfdockia.subsystem.stopTimeoutSeconds", "10");
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    private String getProperty(String key, String defaultValue) {
        if (globalProperties == null) {
            return defaultValue;
        }
        return globalProperties.getProperty(key, defaultValue);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
