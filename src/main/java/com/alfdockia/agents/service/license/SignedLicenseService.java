/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.license;

import com.alfdockia.agents.service.exception.BadRequestException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

/**
 * Valida licencias AlfDokia firmadas. Si no existe una licencia valida,
 * la politica efectiva vuelve siempre a Community.
 */
public class SignedLicenseService implements LicenseService {

    public static final String PRODUCT_NAME = "AlfDokia";
    public static final String LICENSE_FORMAT = "alfdokia-license-v1";
    public static final int COMMUNITY_AGENT_LIMIT = 5;

    private static final Log LOGGER = LogFactory.getLog(SignedLicenseService.class);
    private static final String LICENSE_PATH_PROPERTY = "alfresco.alfdockia.license.path";
    private static final String PUBLIC_KEY_PATH_PROPERTY = "alfresco.alfdockia.license.publicKeyPath";
    private static final String PUBLIC_KEY_OVERRIDE_ENABLED_PROPERTY = "alfresco.alfdockia.license.allowExternalPublicKey";
    private static final String EMBEDDED_PUBLIC_KEY_RESOURCE =
            "alfresco/module/alfdockia/license/alfdokia-license-public.pem";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Properties globalProperties;

    public void setGlobalProperties(Properties globalProperties) {
        this.globalProperties = globalProperties;
    }

    @Override
    public LicenseStatus getStatus() {
        String licensePath = property(LICENSE_PATH_PROPERTY, "");
        if (!StringUtils.hasText(licensePath)) {
            return LicenseStatus.community("No AlfDokia license file configured");
        }

        try {
            Path path = Path.of(licensePath.trim());
            if (!Files.isRegularFile(path)) {
                return communityWithLog("AlfDokia license file not found: " + path);
            }

            JsonNode root = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (!LICENSE_FORMAT.equals(root.path("format").asText())) {
                return communityWithLog("Unsupported AlfDokia license format");
            }

            String encodedPayload = root.path("payload").asText();
            String encodedSignature = root.path("signature").asText();
            if (!StringUtils.hasText(encodedPayload) || !StringUtils.hasText(encodedSignature)) {
                return communityWithLog("AlfDokia license payload or signature is empty");
            }

            if (!verify(encodedPayload, encodedSignature, loadPublicKey())) {
                return communityWithLog("AlfDokia license signature is not valid");
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            AlfDokiaLicensePayload payload = mapper.readValue(payloadJson, AlfDokiaLicensePayload.class);

            String validationError = validatePayload(payload);
            if (validationError != null) {
                return communityWithLog(validationError);
            }

            return LicenseStatus.valid(payload, path.toString());

        } catch (Exception e) {
            return communityWithLog("Failed to load AlfDokia license: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void assertCanCreateAgent(int currentAgentCount) {
        LicenseStatus status = getStatus();
        if (status.isUnlimitedAgents()) {
            return;
        }
        if (currentAgentCount >= status.getMaxAgents()) {
            throw new BadRequestException("LICENSE_LIMIT_EXCEEDED",
                    PRODUCT_NAME + " " + status.getEdition()
                            + " allows up to " + status.getMaxAgents()
                            + " agents. Load a valid AlfDokia license to create more agents.");
        }
    }

    private String validatePayload(AlfDokiaLicensePayload payload) {
        if (payload == null) {
            return "AlfDokia license payload is empty";
        }
        if (!PRODUCT_NAME.equals(payload.getIssuer())) {
            return "AlfDokia license issuer is not valid";
        }
        if (!PRODUCT_NAME.equals(payload.getProduct())) {
            return "AlfDokia license product is not valid";
        }
        if (!StringUtils.hasText(payload.getEdition())) {
            return "AlfDokia license edition is required";
        }
        if (!StringUtils.hasText(payload.getLicenseId())) {
            return "AlfDokia license id is required";
        }
        if (payload.getMaxAgents() == 0 || payload.getMaxAgents() < -1) {
            return "AlfDokia license maxAgents must be positive or -1 for unlimited";
        }
        if (isExpired(payload.getExpiresAt())) {
            return "AlfDokia license is expired";
        }
        if (isIssuedTooFarInFuture(payload.getIssuedAt())) {
            return "AlfDokia license issuedAt is in the future";
        }
        return null;
    }

    private boolean isExpired(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return Instant.parse(value.trim()).isBefore(Instant.now());
    }

    private boolean isIssuedTooFarInFuture(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return Instant.parse(value.trim()).isAfter(Instant.now().plusSeconds(300));
    }

    private boolean verify(String encodedPayload, String encodedSignature, PublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(encodedPayload.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getUrlDecoder().decode(encodedSignature));
    }

    private PublicKey loadPublicKey() throws Exception {
        String externalPath = property(PUBLIC_KEY_PATH_PROPERTY, "");
        if (StringUtils.hasText(externalPath)) {
            if (!Boolean.parseBoolean(property(PUBLIC_KEY_OVERRIDE_ENABLED_PROPERTY, "false"))) {
                throw new IllegalStateException("External AlfDokia public key is not enabled");
            }
            return decodePublicKey(Files.readString(Path.of(externalPath.trim()), StandardCharsets.UTF_8));
        }

        String embedded = readEmbeddedPublicKey();
        if (!StringUtils.hasText(embedded)) {
            throw new IllegalStateException("Embedded AlfDokia public key is not available");
        }
        return decodePublicKey(embedded);
    }

    private String readEmbeddedPublicKey() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(EMBEDDED_PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private PublicKey decodePublicKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private LicenseStatus communityWithLog(String reason) {
        LOGGER.warn(reason + "; using AlfDokia Community limits");
        return LicenseStatus.community(reason);
    }

    private String property(String key, String defaultValue) {
        if (globalProperties == null) {
            return defaultValue;
        }
        return globalProperties.getProperty(key, defaultValue);
    }
}
