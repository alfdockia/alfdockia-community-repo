/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Peticion de alta de un agente recibida por el endpoint REST.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDeployRequest {

    private String name;
    private String image;
    private List<PortMapping> ports;
    private ListenerConfig listener;
    private Map<String, String> env;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public List<PortMapping> getPorts() { return ports; }
    public void setPorts(List<PortMapping> ports) { this.ports = ports; }
    public ListenerConfig getListener() { return listener; }
    public void setListener(ListenerConfig listener) { this.listener = listener; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortMapping {
        private int containerPort;
        private int hostPort;
        private String protocol = "tcp";

        public int getContainerPort() { return containerPort; }
        public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
        public int getHostPort() { return hostPort; }
        public void setHostPort(int hostPort) { this.hostPort = hostPort; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SecretRef {
        private String secretRef;
        public String getSecretRef() { return secretRef; }
        public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListenerConfig {
        private SecretRef passwordSecretRef;
        private String passwordEnvName = "CONTENT_SERVICE_SECURITY_BASICAUTH_PASSWORD";

        public SecretRef getPasswordSecretRef() { return passwordSecretRef; }
        public void setPasswordSecretRef(SecretRef passwordSecretRef) { this.passwordSecretRef = passwordSecretRef; }
        public String getPasswordEnvName() { return passwordEnvName; }
        public void setPasswordEnvName(String passwordEnvName) { this.passwordEnvName = passwordEnvName; }
    }
}
