/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.model;

/**
 * Informacion minima necesaria para operar sobre el runtime del agente.
 */
public class AgentRuntimeInfo {
    private String agentId;
    private String nodeId;
    private String containerId;
    private String desiredState;
    private String currentState;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getDesiredState() { return desiredState; }
    public void setDesiredState(String desiredState) { this.desiredState = desiredState; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
}
