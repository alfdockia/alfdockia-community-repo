/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.license;

import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SignedLicenseServiceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void missingLicenseDefaultsToCommunity() {
        SignedLicenseService service = new SignedLicenseService();
        service.setGlobalProperties(new Properties());

        LicenseStatus status = service.getStatus();

        assertFalse(status.isValid());
        assertEquals("community", status.getEdition());
        assertEquals(5, status.getMaxAgents());
    }

    @Test
    public void validSignedLicenseEnablesConfiguredAgentLimit() throws Exception {
        KeyPair pair = keyPair();
        File publicKey = temp.newFile("alfdokia.public.pem");
        File license = temp.newFile("customer.license.json");
        writePublicKey(publicKey, pair);
        Files.write(license.toPath(), licenseJson(pair.getPrivate(), 25, "enterprise").getBytes(StandardCharsets.UTF_8));

        SignedLicenseService service = serviceWith(license, publicKey);
        LicenseStatus status = service.getStatus();

        assertTrue(status.isValid());
        assertEquals("enterprise", status.getEdition());
        assertEquals(25, status.getMaxAgents());
        assertEquals("Cliente Test", status.getLicensee());
    }

    @Test
    public void invalidSignatureFallsBackToCommunity() throws Exception {
        KeyPair signer = keyPair();
        KeyPair other = keyPair();
        File publicKey = temp.newFile("alfdokia.public.pem");
        File license = temp.newFile("customer.license.json");
        writePublicKey(publicKey, other);
        Files.write(license.toPath(), licenseJson(signer.getPrivate(), 25, "enterprise").getBytes(StandardCharsets.UTF_8));

        SignedLicenseService service = serviceWith(license, publicKey);
        LicenseStatus status = service.getStatus();

        assertFalse(status.isValid());
        assertEquals("community", status.getEdition());
        assertEquals(5, status.getMaxAgents());
    }

    @Test
    public void communityStatusBlocksWhenCurrentAgentCountReachedLimit() {
        SignedLicenseService service = new SignedLicenseService();
        service.setGlobalProperties(new Properties());

        try {
            service.assertCanCreateAgent(5);
            fail("Expected Community limit to block agent creation");
        } catch (BadRequestException e) {
            assertEquals("LICENSE_LIMIT_EXCEEDED", e.getCode());
        }
    }

    private SignedLicenseService serviceWith(File license, File publicKey) {
        Properties props = new Properties();
        props.setProperty("alfresco.alfdockia.license.path", license.getAbsolutePath());
        props.setProperty("alfresco.alfdockia.license.publicKeyPath", publicKey.getAbsolutePath());
        props.setProperty("alfresco.alfdockia.license.allowExternalPublicKey", "true");

        SignedLicenseService service = new SignedLicenseService();
        service.setGlobalProperties(props);
        return service;
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String licenseJson(PrivateKey privateKey, int maxAgents, String edition) throws Exception {
        String payload = "{\n"
                + "  \"issuer\": \"AlfDokia\",\n"
                + "  \"product\": \"AlfDokia\",\n"
                + "  \"edition\": \"" + edition + "\",\n"
                + "  \"licenseId\": \"ALFDOKIA-TEST\",\n"
                + "  \"licensee\": \"Cliente Test\",\n"
                + "  \"maxAgents\": " + maxAgents + ",\n"
                + "  \"issuedAt\": \"2020-01-01T00:00:00Z\",\n"
                + "  \"expiresAt\": \"2099-01-01T00:00:00Z\",\n"
                + "  \"features\": [\"agents\"]\n"
                + "}";
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(encodedPayload.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

        return "{\n"
                + "  \"format\": \"alfdokia-license-v1\",\n"
                + "  \"payload\": \"" + encodedPayload + "\",\n"
                + "  \"signature\": \"" + encodedSignature + "\"\n"
                + "}\n";
    }

    private void writePublicKey(File out, KeyPair pair) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
        Files.write(out.toPath(), pem.getBytes(StandardCharsets.UTF_8));
    }
}
