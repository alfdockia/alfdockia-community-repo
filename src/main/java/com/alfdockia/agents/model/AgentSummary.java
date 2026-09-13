/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.model;

/**
 * Vista resumida usada al listar agentes desde la API.
 */
public class AgentSummary {

    private String agentId;
    private String name;
    private String image;
    private String desiredState;
    private String currentState;
    private String health;
    private String containerId;
    private String nodeId;
    private String createdAt;
    private String updatedAt;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getDesiredState() { return desiredState; }
    public void setDesiredState(String desiredState) { this.desiredState = desiredState; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }

    public String getHealth() { return health; }
    public void setHealth(String health) { this.health = health; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
