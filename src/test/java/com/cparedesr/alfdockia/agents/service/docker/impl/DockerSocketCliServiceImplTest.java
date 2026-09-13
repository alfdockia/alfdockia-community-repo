/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.docker.impl;

import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DockerSocketCliServiceImplTest {

    private static final String CONTAINER_ID = "0123456789abcdef".repeat(4);

    @Test(timeout = 10000)
    public void readsDockerCreationTimeAndStateForLicenseOrdering() {
        DockerSocketCliServiceImpl service = new DockerSocketCliServiceImpl() {
            @Override Process startProcess(List<String> command) throws IOException {
                String output;
                if (command.contains("inspect")) {
                    output = "{\"Id\":\"" + CONTAINER_ID + "\",\"Created\":\"2026-01-01T00:00:00Z\","
                            + "\"Config\":{\"Labels\":{\"com.cparedesr.alfdockia.agentId\":\"agent-oldest\"}},"
                            + "\"State\":{\"Running\":true}}";
                } else {
                    assertTrue(command.contains("--all"));
                    assertTrue(command.contains("--no-trunc"));
                    output = CONTAINER_ID;
                }
                return super.startProcess(List.of("sh", "-c", "printf '%s\\n' '" + output + "'"));
            }
        };
        service.setGlobalProperties(new Properties());
        var inventory = service.listManagedRuntimeInfos();
        assertEquals(1, inventory.size());
        assertEquals("agent-oldest", inventory.get(0).getAgentId());
        assertEquals(CONTAINER_ID, inventory.get(0).getContainerId());
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), inventory.get(0).getCreatedAt());
        assertEquals("running", inventory.get(0).getCurrentState());
    }

    @Test(timeout = 10000)
    public void downloadsMissingImageWithoutTreatingProgressAsContainerId() {
        // More than a pipe buffer of stderr, emitted before stdout, reproduces pull output.
        CliFixture service = new CliFixture(
                "printf '%s\\n' \"Unable to find image 'nginx:alpine' locally\" >&2; "
                + "i=0; while [ $i -lt 6000 ]; do "
                + "printf '%s\\n' 'Downloading layer: abcdef0123456789 progress 100 percent' >&2; "
                + "i=$((i + 1)); done; "
                + "printf '%s\\n' '" + CONTAINER_ID + "'");

        assertEquals(CONTAINER_ID, service.createAndStart("agent-test", "nginx:alpine",
                Map.of(), Map.of(), List.of()).getContainerId());
        assertEquals(2, service.commands.size());
        assertTrue(service.commands.get(0).contains("--pull=missing"));
        assertEquals(List.of("docker", "--host", "unix:///var/run/docker.sock", "start", CONTAINER_ID),
                service.commands.get(1));
    }

    @Test(timeout = 10000)
    public void startsCachedImageWithValidContainerId() {
        CliFixture service = new CliFixture("printf '%s\\n' '" + CONTAINER_ID + "'");
        assertEquals("running", service.createAndStart("agent-test", "nginx:alpine",
                Map.of(), Map.of(), List.of()).getCurrentState());
        assertEquals(2, service.commands.size());
    }

    @Test(timeout = 10000)
    public void preservesPullFailureAndDoesNotStartContainer() {
        CliFixture service = new CliFixture("printf '%s\\n' 'pull access denied' >&2; exit 1");
        try {
            service.createAndStart("agent-test", "nginx:alpine", Map.of(), Map.of(), List.of());
            fail("Expected pull failure");
        } catch (BadRequestException e) {
            assertEquals("DOCKER_CLI_ERROR", e.getCode());
            assertTrue(e.getMessage().contains("pull access denied"));
        }
        assertEquals(1, service.commands.size());
    }

    @Test(timeout = 10000)
    public void rejectsInvalidOrMissingIdBeforeStartingContainer() {
        for (String output : List.of("", "not-a-container-id", "sha256:" + CONTAINER_ID,
                CONTAINER_ID + " extra-output")) {
            CliFixture service = new CliFixture("printf '%s\\n' '" + output + "'");
            try {
                service.createAndStart("agent-test", "nginx:alpine", Map.of(), Map.of(), List.of());
                fail("Expected invalid container ID to be rejected");
            } catch (BadRequestException e) {
                assertEquals("DOCKER_CREATE_FAILED", e.getCode());
            }
            assertEquals(1, service.commands.size());
        }
    }

    // Real subprocess pipes exercise stdout/stderr handling without a Docker daemon or image download.
    private static class CliFixture extends DockerSocketCliServiceImpl {
        private final String createScript;
        private final List<List<String>> commands = new ArrayList<>();

        CliFixture(String createScript) {
            this.createScript = createScript;
            Properties properties = new Properties();
            properties.setProperty("alfresco.alfdockia.docker.network.mode", "none");
            setGlobalProperties(properties);
        }

        @Override
        Process startProcess(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            String operation = command.get(3);
            if ("create".equals(operation)) {
                return super.startProcess(List.of("sh", "-c", createScript));
            }
            assertEquals("start", operation);
            assertEquals(CONTAINER_ID, command.get(4));
            return super.startProcess(List.of("sh", "-c", "printf '%s\\n' '" + CONTAINER_ID + "'"));
        }
    }

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
