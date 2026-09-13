/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.docker.impl;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DockerSocketCliServiceImplTest {

    @Test
    public void prefersComposeDefaultNetworkWhenInheritingFromAlfrescoContainer() {
        String network = DockerSocketCliServiceImpl.selectInheritedNetwork(List.of(
                "bridge",
                "alden-innova_default",
                "traefik-public"
        ));

        assertEquals("alden-innova_default", network);
    }

    @Test
    public void ignoresDockerBuiltinNetworksWhenInheriting() {
        String network = DockerSocketCliServiceImpl.selectInheritedNetwork(List.of(
                "bridge",
                "host",
                "none"
        ));

        assertNull(network);
    }

    @Test
    public void fallsBackToFirstDeterministicNonBuiltinNetwork() {
        String network = DockerSocketCliServiceImpl.selectInheritedNetwork(List.of(
                "z-runtime",
                "app-network"
        ));

        assertEquals("app-network", network);
    }

    @Test
    public void detectsDockerMissingNetworkStartFailure() {
        String message = "Error response from daemon: failed to set up container networking: "
                + "network b5158d3a3945bc4b1eff5209a2502cfe7c1af08f196373ee60ffa0e05a119303 not found";

        assertEquals(true, DockerSocketCliServiceImpl.isMissingNetworkMessage(message));
        assertEquals("b5158d3a3945bc4b1eff5209a2502cfe7c1af08f196373ee60ffa0e05a119303",
                DockerSocketCliServiceImpl.extractMissingNetworkId(message));
    }

    @Test
    public void missingNetworkExtractorReturnsNullWhenMessageHasNoNetworkId() {
        assertNull(DockerSocketCliServiceImpl.extractMissingNetworkId("No such container: agent-1"));
    }
}
