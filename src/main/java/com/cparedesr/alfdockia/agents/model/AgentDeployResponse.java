/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.model;

/**
 * Respuesta devuelta despues de solicitar el despliegue de un agente.
 */
public class AgentDeployResponse {

    private String agentId;
    private String name;
    private String desiredState;
    private String currentState;
    private String statusUrl;

    public AgentDeployResponse() {}

    public AgentDeployResponse(String agentId, String name, String desiredState, String currentState, String statusUrl) {
        this.agentId = agentId;
        this.name = name;
        this.desiredState = desiredState;
        this.currentState = currentState;
        this.statusUrl = statusUrl;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDesiredState() { return desiredState; }
    public void setDesiredState(String desiredState) { this.desiredState = desiredState; }
    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
    public String getStatusUrl() { return statusUrl; }
    public void setStatusUrl(String statusUrl) { this.statusUrl = statusUrl; }
}
