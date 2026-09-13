/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.docker.impl;

import com.alfdockia.agents.model.AgentDeployRequest;
import com.alfdockia.agents.model.AgentRuntimeInfo;
import com.alfdockia.agents.service.docker.DockerService;
import com.alfdockia.agents.service.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.alfresco.util.Pair;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.KeyManagerFactory;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementacion basada en el CLI de Docker contra el socket local. Mantiene
 * soporte de borrado por Docker Remote API para instalaciones que lo expongan.
 */
public class DockerSocketCliServiceImpl implements DockerService {

    private static final Log LOGGER = LogFactory.getLog(DockerSocketCliServiceImpl.class);
    private static final String MANAGED_CONTAINER_LABEL = "com.alfdockia.agentId";
    private static final String NETWORK_MODE_INHERIT = "inherit";
    private static final String NETWORK_MODE_NONE = "none";
    private static final Pattern MISSING_NETWORK_PATTERN = Pattern.compile("network\\s+([0-9a-f]{12,64})\\s+not found",
            Pattern.CASE_INSENSITIVE);

    private Properties globalProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void setGlobalProperties(Properties globalProperties) {
        this.globalProperties = globalProperties;
    }

    @Override
    public CreateResult createAndStart(String agentId, String image, Map<String, String> env, Map<String, String> labels,
                                      List<AgentDeployRequest.PortMapping> ports) {

        boolean enabled = Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));
        if (!enabled) {
            return new CreateResult(null, "disabled");
        }

        String mode = globalProperties.getProperty("alfresco.alfdockia.docker.mode", "socket");
        if (!"socket".equalsIgnoreCase(mode)) {
            throw new BadRequestException("DOCKER_MODE_UNSUPPORTED", "Only alfresco.alfdockia.docker.mode=socket is supported for create/start");
        }

        String socket = globalProperties.getProperty("alfresco.alfdockia.docker.socket", "/var/run/docker.sock");
        String network = resolveNetworkForCreate(socket);

        // docker create --name agentId ... image
        List<String> cmdCreate = new ArrayList<>();
        cmdCreate.add("docker");
        cmdCreate.add("--host");
        cmdCreate.add("unix://" + socket);
        cmdCreate.add("create");
        cmdCreate.add("--pull=missing");
        cmdCreate.add("--name");
        cmdCreate.add(agentId);
        if (network != null) {
            cmdCreate.add("--network");
            cmdCreate.add(network);
        }

        for (var e : labels.entrySet()) {
            cmdCreate.add("--label");
            cmdCreate.add(e.getKey() + "=" + e.getValue());
        }

        for (var e : env.entrySet()) {
            cmdCreate.add("-e");
            cmdCreate.add(e.getKey() + "=" + e.getValue());
        }

        if (ports != null) {
            for (AgentDeployRequest.PortMapping p : ports) {
                String proto = p.getProtocol() == null ? "tcp" : p.getProtocol();
                cmdCreate.add("-p");
                cmdCreate.add(p.getHostPort() + ":" + p.getContainerPort() + "/" + proto);
            }
        }

        cmdCreate.add(image);

        String containerId = execAndGetContainerId(cmdCreate);

        // docker start <id>
        List<String> cmdStart = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "start", containerId
        );
        execOrFail(cmdStart);

        return new CreateResult(containerId, "running");
    }

    @Override
    public List<AgentRuntimeInfo> listManagedRuntimeInfos() {
        List<AgentRuntimeInfo> result = new ArrayList<>();
        for (String id : listManagedContainerIds()) {
            try {
                JsonNode container;
                if ("socket".equalsIgnoreCase(getTrimmedProperty("alfresco.alfdockia.docker.mode", "socket"))) {
                    String socket = getTrimmedProperty("alfresco.alfdockia.docker.socket", "/var/run/docker.sock");
                    List<String> output = execAndGetLines(List.of("docker", "--host", "unix://" + socket,
                            "inspect", "--format", "{{json .}}", id));
                    container = objectMapper.readTree(String.join("\n", output));
                } else {
                    String base = getTrimmedProperty("alfresco.alfdockia.docker.baseUrl", "");
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(base.replaceAll("/+$", "") + "/containers/" + id + "/json"))
                            .timeout(Duration.ofSeconds(30)).GET().build();
                    HttpResponse<String> response = buildTlsHttpClientIfConfigured()
                            .send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 404) continue;
                    if (response.statusCode() != 200) throw new IllegalStateException("Docker inspect failed");
                    container = objectMapper.readTree(response.body());
                }
                AgentRuntimeInfo info = new AgentRuntimeInfo();
                info.setContainerId(container.path("Id").asText());
                info.setAgentId(container.path("Config").path("Labels").path(MANAGED_CONTAINER_LABEL).asText(id));
                info.setCreatedAt(java.time.Instant.parse(container.path("Created").asText()).toEpochMilli());
                info.setCurrentState(container.path("State").path("Running").asBoolean() ? "running" : "stopped");
                result.add(info);
            } catch (BadRequestException e) {
                if (isNotFound(e)) continue;
                throw e;
            } catch (Exception e) {
                throw new BadRequestException("DOCKER_INVENTORY_FAILED", "Cannot inspect managed container " + id);
            }
        }
        return result;
    }

    @Override
    public void start(String containerId) {
        boolean enabled = Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));
        if (!enabled) return;

        if (containerId == null || containerId.trim().isEmpty()) return;

        String mode = globalProperties.getProperty("alfresco.alfdockia.docker.mode", "socket");

        if ("socket".equalsIgnoreCase(mode)) {
            startBySocket(containerId.trim());
            return;
        }

        if ("url".equalsIgnoreCase(mode)) {
            startByRemoteApi(containerId.trim());
            return;
        }

        throw new BadRequestException("DOCKER_MODE_UNSUPPORTED", "Unsupported alfresco.alfdockia.docker.mode: " + mode);
    }

    @Override
    public void stop(String containerId, int timeoutSeconds) {
        boolean enabled = Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));
        if (!enabled) return;

        if (containerId == null || containerId.trim().isEmpty()) return;

        String mode = globalProperties.getProperty("alfresco.alfdockia.docker.mode", "socket");
        int safeTimeout = Math.max(0, timeoutSeconds);

        if ("socket".equalsIgnoreCase(mode)) {
            stopBySocket(containerId.trim(), safeTimeout);
            return;
        }

        if ("url".equalsIgnoreCase(mode)) {
            stopByRemoteApi(containerId.trim(), safeTimeout);
            return;
        }

        throw new BadRequestException("DOCKER_MODE_UNSUPPORTED", "Unsupported alfresco.alfdockia.docker.mode: " + mode);
    }

    @Override
    public void remove(String containerId, boolean force) {
        boolean enabled = Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));
        if (!enabled) return;

        if (containerId == null || containerId.trim().isEmpty()) return;

        String mode = globalProperties.getProperty("alfresco.alfdockia.docker.mode", "socket");

        if ("socket".equalsIgnoreCase(mode)) {
            removeBySocket(containerId.trim(), force);
            return;
        }

        if ("url".equalsIgnoreCase(mode)) {
            removeByRemoteApi(containerId.trim(), force);
            return;
        }

        throw new BadRequestException("DOCKER_MODE_UNSUPPORTED", "Unsupported alfresco.alfdockia.docker.mode: " + mode);
    }

    @Override
    public List<String> listManagedContainerIds() {
        boolean enabled = Boolean.parseBoolean(globalProperties.getProperty("alfresco.alfdockia.docker.enabled", "true"));
        if (!enabled) return Collections.emptyList();

        String mode = globalProperties.getProperty("alfresco.alfdockia.docker.mode", "socket");
        if ("socket".equalsIgnoreCase(mode)) {
            return listManagedContainersBySocket();
        }
        if ("url".equalsIgnoreCase(mode)) {
            return listManagedContainersByRemoteApi();
        }

        throw new BadRequestException("DOCKER_MODE_UNSUPPORTED", "Unsupported alfresco.alfdockia.docker.mode: " + mode);
    }

    // ---------------- modo socket ----------------

    private void startBySocket(String containerId) {
        String socket = globalProperties.getProperty("alfresco.alfdockia.docker.socket", "/var/run/docker.sock");

        List<String> cmd = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "start", containerId
        );
        try {
            execOrFail(cmd);
        } catch (BadRequestException e) {
            if (!isMissingNetwork(e)) {
                throw e;
            }

            repairContainerNetworkBeforeStart(socket, containerId, e);
            execOrFail(cmd);
        }
    }

    private void stopBySocket(String containerId, int timeoutSeconds) {
        String socket = globalProperties.getProperty("alfresco.alfdockia.docker.socket", "/var/run/docker.sock");

        List<String> cmd = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "stop", "--time", Integer.toString(timeoutSeconds), containerId
        );

        // La parada del subsistema debe ser segura aunque el runtime ya no exista.
        try {
            execOrFail(cmd);
        } catch (BadRequestException e) {
            if (isNotFound(e)) {
                return;
            }
            throw e;
        }
    }

    private void removeBySocket(String containerId, boolean force) {
        String socket = globalProperties.getProperty("alfresco.alfdockia.docker.socket", "/var/run/docker.sock");

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("--host");
        cmd.add("unix://" + socket);
        cmd.add("rm");
        if (force) cmd.add("-f");
        cmd.add(containerId);

        // Si el contenedor no existe, hacemos DELETE idempotente.
        try {
            execOrFail(cmd);
        } catch (BadRequestException e) {
            if (isNotFound(e)) {
                return; // idempotente
            }
            throw e;
        }
    }

    private List<String> listManagedContainersBySocket() {
        String socket = globalProperties.getProperty("alfresco.alfdockia.docker.socket", "/var/run/docker.sock");
        List<String> cmd = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "container", "ls", "--all", "--quiet", "--no-trunc",
                "--filter", "label=" + MANAGED_CONTAINER_LABEL
        );
        return execAndGetLines(cmd);
    }

    private String resolveNetworkForCreate(String socket) {
        String configuredNetwork = getTrimmedProperty("alfresco.alfdockia.docker.network", "");
        if (!configuredNetwork.isEmpty()) {
            LOGGER.info("Usando la red Docker configurada para agentes Alfdockia: " + configuredNetwork);
            return configuredNetwork;
        }

        String networkMode = getTrimmedProperty("alfresco.alfdockia.docker.network.mode", NETWORK_MODE_INHERIT);
        if (NETWORK_MODE_NONE.equalsIgnoreCase(networkMode) || "disabled".equalsIgnoreCase(networkMode)) {
            return null;
        }
        if (!NETWORK_MODE_INHERIT.equalsIgnoreCase(networkMode)) {
            throw new BadRequestException("DOCKER_NETWORK_MODE_UNSUPPORTED",
                    "Unsupported alfresco.alfdockia.docker.network.mode: " + networkMode);
        }

        String selfContainer = resolveCurrentContainerIdentifier();
        if (selfContainer == null) {
            LOGGER.info("No se pudo identificar el contenedor de Alfresco; el agente se creara sin red heredada");
            return null;
        }

        try {
            String inheritedNetwork = selectInheritedNetwork(inspectContainerNetworks(socket, selfContainer));
            if (inheritedNetwork != null) {
                LOGGER.info("Usando la red Docker heredada del contenedor Alfresco para agentes Alfdockia: "
                        + inheritedNetwork);
            }
            return inheritedNetwork;
        } catch (RuntimeException e) {
            LOGGER.info("No se pudo heredar la red Docker del contenedor Alfresco; "
                    + "el agente se creara con la red Docker por defecto. Causa: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Detalle tecnico al heredar red Docker para Alfdockia", e);
            }
            return null;
        }
    }

    private List<String> inspectContainerNetworks(String socket, String containerIdOrName) {
        List<String> cmd = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "inspect",
                "--format", "{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}",
                containerIdOrName
        );
        return execAndGetLines(cmd);
    }

    private void repairContainerNetworkBeforeStart(String socket, String containerId, BadRequestException startError) {
        String network = resolveNetworkForCreate(socket);
        if (network == null) {
            throw startError;
        }

        LOGGER.info("Reparando red Docker del agente " + containerId
                + " antes de arrancarlo. Se conectara a la red actual: " + network);

        String missingNetworkId = extractMissingNetworkId(startError.getMessage());
        if (missingNetworkId != null) {
            disconnectNetworkQuietly(socket, missingNetworkId, containerId);
        }

        connectContainerToNetwork(socket, network, containerId);
    }

    private void connectContainerToNetwork(String socket, String network, String containerId) {
        List<String> cmd = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "network", "connect", network, containerId
        );

        try {
            execOrFail(cmd);
        } catch (BadRequestException e) {
            if (!isAlreadyConnectedToNetwork(e)) {
                throw e;
            }

            disconnectNetworkQuietly(socket, network, containerId);
            execOrFail(cmd);
        }
    }

    private void disconnectNetworkQuietly(String socket, String network, String containerId) {
        List<String> cmd = Arrays.asList(
                "docker", "--host", "unix://" + socket,
                "network", "disconnect", "--force", network, containerId
        );

        Pair<Integer, String> result = exec(cmd);
        if (result.getFirst() != 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("No se pudo desconectar la red Docker " + network + " del agente " + containerId
                    + ": " + result.getSecond());
        }
    }

    private String resolveCurrentContainerIdentifier() {
        String configured = getTrimmedProperty("alfresco.alfdockia.docker.selfContainer", "");
        if (!configured.isEmpty()) {
            return configured;
        }

        String hostname = normalize(System.getenv("HOSTNAME"));
        if (hostname != null) {
            return hostname;
        }

        try {
            return normalize(Files.readString(Path.of("/etc/hostname")));
        } catch (Exception e) {
            return null;
        }
    }

    static String selectInheritedNetwork(List<String> networks) {
        if (networks == null || networks.isEmpty()) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        for (String network : networks) {
            String normalized = normalize(network);
            if (normalized != null && !isBuiltinNetwork(normalized)) {
                candidates.add(normalized);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Collections.sort(candidates);
        for (String candidate : candidates) {
            if (candidate.endsWith("_default") || candidate.endsWith("-default")) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private static boolean isBuiltinNetwork(String network) {
        return "bridge".equals(network) || "host".equals(network) || "none".equals(network);
    }

    // ---------------- modo url (Docker Remote API TLS) ----------------

    private void startByRemoteApi(String containerId) {
        String baseUrl = globalProperties.getProperty("alfresco.alfdockia.docker.baseUrl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new BadRequestException("DOCKER_BASEURL_REQUIRED", "alfresco.alfdockia.docker.baseUrl is required for mode=url");
        }

        String url = baseUrl.replaceAll("/+$", "") + "/containers/" + containerId + "/start";

        try {
            HttpClient client = buildTlsHttpClientIfConfigured();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            // 204 No Content => arrancado; 304 Not Modified => ya estaba arrancado.
            if (res.statusCode() == 204 || res.statusCode() == 304) return;

            throw new BadRequestException("DOCKER_REMOTE_API_ERROR",
                    "Docker API error " + res.statusCode() + ": " + safeBody(res.body()));

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("DOCKER_REMOTE_API_EXEC_FAILED", "Failed to call Docker Remote API");
        }
    }

    private void stopByRemoteApi(String containerId, int timeoutSeconds) {
        String baseUrl = globalProperties.getProperty("alfresco.alfdockia.docker.baseUrl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new BadRequestException("DOCKER_BASEURL_REQUIRED", "alfresco.alfdockia.docker.baseUrl is required for mode=url");
        }

        String url = baseUrl.replaceAll("/+$", "") + "/containers/" + containerId + "/stop?t=" + timeoutSeconds;

        try {
            HttpClient client = buildTlsHttpClientIfConfigured();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            // 204 No Content => parado; 304 Not Modified => ya estaba parado.
            if (res.statusCode() == 204 || res.statusCode() == 304) return;

            // 404 Not Found => idempotente durante parada del subsistema.
            if (res.statusCode() == 404) return;

            throw new BadRequestException("DOCKER_REMOTE_API_ERROR",
                    "Docker API error " + res.statusCode() + ": " + safeBody(res.body()));

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("DOCKER_REMOTE_API_EXEC_FAILED", "Failed to call Docker Remote API");
        }
    }

    private void removeByRemoteApi(String containerId, boolean force) {
        String baseUrl = globalProperties.getProperty("alfresco.alfdockia.docker.baseUrl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new BadRequestException("DOCKER_BASEURL_REQUIRED", "alfresco.alfdockia.docker.baseUrl is required for mode=url");
        }

        // Docker Engine API: DELETE /containers/{id}?force=true
        String url = baseUrl.replaceAll("/+$", "") + "/containers/" + containerId + "?force=" + (force ? "true" : "false");

        try {
            HttpClient client = buildTlsHttpClientIfConfigured();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            // 204 No Content => OK
            if (res.statusCode() == 204) return;

            // 404 Not Found => idempotente
            if (res.statusCode() == 404) return;

            throw new BadRequestException("DOCKER_REMOTE_API_ERROR",
                    "Docker API error " + res.statusCode() + ": " + safeBody(res.body()));

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("DOCKER_REMOTE_API_EXEC_FAILED", "Failed to call Docker Remote API");
        }
    }

    private List<String> listManagedContainersByRemoteApi() {
        String baseUrl = globalProperties.getProperty("alfresco.alfdockia.docker.baseUrl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new BadRequestException("DOCKER_BASEURL_REQUIRED", "alfresco.alfdockia.docker.baseUrl is required for mode=url");
        }

        String filters = "%7B%22label%22%3A%5B%22" + MANAGED_CONTAINER_LABEL + "%22%5D%7D";
        String url = baseUrl.replaceAll("/+$", "") + "/containers/json?all=true&filters=" + filters;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> res = buildTlsHttpClientIfConfigured()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new BadRequestException("DOCKER_REMOTE_API_ERROR",
                        "Docker API error " + res.statusCode() + ": " + safeBody(res.body()));
            }

            JsonNode containers = objectMapper.readTree(res.body());
            if (!containers.isArray()) return Collections.emptyList();

            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonNode container : containers) {
                String id = container.path("Id").asText("").trim();
                if (!id.isEmpty()) ids.add(id);
            }
            return new ArrayList<>(ids);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("DOCKER_REMOTE_API_EXEC_FAILED", "Failed to list containers using Docker Remote API");
        }
    }

    private boolean isNotFound(BadRequestException e) {
        String msg = (e.getMessage() == null) ? "" : e.getMessage().toLowerCase();
        return msg.contains("no such container") || msg.contains("not found");
    }

    private boolean isMissingNetwork(BadRequestException e) {
        return isMissingNetworkMessage(e == null ? null : e.getMessage());
    }

    static boolean isMissingNetworkMessage(String message) {
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return msg.contains("network") && msg.contains("not found");
    }

    static String extractMissingNetworkId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = MISSING_NETWORK_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean isAlreadyConnectedToNetwork(BadRequestException e) {
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("already exists") || msg.contains("already connected");
    }

    /**
     * Construye HttpClient con TLS mutual si keystore/truststore están configurados.
     * Si no, usa client por defecto.
     */
    private HttpClient buildTlsHttpClientIfConfigured() {
        String ksPath = globalProperties.getProperty("alfresco.alfdockia.docker.tls.keystore.path", "").trim();
        String ksPass = globalProperties.getProperty("alfresco.alfdockia.docker.tls.keystore.password", "").trim();
        String tsPath = globalProperties.getProperty("alfresco.alfdockia.docker.tls.truststore.path", "").trim();
        String tsPass = globalProperties.getProperty("alfresco.alfdockia.docker.tls.truststore.password", "").trim();

        boolean hasTls = !ksPath.isEmpty() && !tsPath.isEmpty();

        if (!hasTls) {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        try {
            SSLContext sslContext = buildSslContext(ksPath, ksPass, tsPath, tsPass);

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

        } catch (Exception e) {
            throw new BadRequestException("DOCKER_TLS_CONFIG_ERROR", "Invalid Docker TLS configuration");
        }
    }

    private SSLContext buildSslContext(String keystorePath, String keystorePass,
                                       String truststorePath, String truststorePass) throws Exception {

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystorePath)) {
            keyStore.load(in, keystorePass.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePass.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(truststorePath)) {
            trustStore.load(in, truststorePass.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

    private String safeBody(String body) {
        if (body == null) return "";
        String b = body.trim();
        return b.length() > 500 ? b.substring(0, 500) + "..." : b;
    }

    private String getTrimmedProperty(String key, String defaultValue) {
        if (globalProperties == null) {
            return defaultValue;
        }
        return globalProperties.getProperty(key, defaultValue).trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---------------- helpers CLI ----------------

    private String execAndGetContainerId(List<String> cmd) {
        Pair<Integer, String> r = exec(cmd);
        if (r.getFirst() != 0) {
            throw new BadRequestException("DOCKER_CLI_ERROR", r.getSecond());
        }
        String containerId = r.getSecond().trim();
        if (!containerId.matches("[0-9a-f]{64}")) {
            throw new BadRequestException("DOCKER_CREATE_FAILED", "docker create did not return a valid container id");
        }
        return containerId;
    }

    private List<String> execAndGetLines(List<String> cmd) {
        Pair<Integer, String> r = exec(cmd);
        if (r.getFirst() != 0) {
            throw new BadRequestException("DOCKER_CLI_ERROR", r.getSecond());
        }

        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (String line : r.getSecond().split("\\R")) {
            String value = line.trim();
            if (!value.isEmpty()) lines.add(value);
        }
        return new ArrayList<>(lines);
    }

    private void execOrFail(List<String> cmd) {
        Pair<Integer, String> r = exec(cmd);
        if (r.getFirst() != 0) {
            throw new BadRequestException("DOCKER_CLI_ERROR", r.getSecond());
        }
    }

    private Pair<Integer, String> exec(List<String> cmd) {
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "alfdockia-docker-cli-output");
            thread.setDaemon(true);
            return thread;
        });
        try {
            process = startProcess(cmd);
            process.getOutputStream().close();
            // Docker writes pull progress and warnings to stderr, IDs and JSON to stdout.
            // Drain both concurrently so a large download log cannot block the process.
            Process runningProcess = process;
            Future<String> stdout = readers.submit(() -> readOutput(runningProcess.getInputStream()));
            Future<String> stderr = readers.submit(() -> readOutput(runningProcess.getErrorStream()));
            int exit = process.waitFor();
            String output = stdout.get();
            String diagnostics = stderr.get();
            return new Pair<>(exit, exit == 0 ? output : diagnostics + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("DOCKER_CLI_EXEC_FAILED", "Docker CLI execution interrupted");
        } catch (Exception e) {
            throw new BadRequestException("DOCKER_CLI_EXEC_FAILED", "Failed to execute docker CLI");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            readers.shutdownNow();
        }
    }

    Process startProcess(List<String> cmd) throws IOException {
        return new ProcessBuilder(cmd).start();
    }

    private static String readOutput(InputStream stream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }
}
