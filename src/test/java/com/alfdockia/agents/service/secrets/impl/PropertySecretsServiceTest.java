/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.secrets.impl;

import com.alfdockia.agents.service.exception.BadRequestException;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.*;

public class PropertySecretsServiceTest {

    private PropertySecretsService service;

    @Before
    public void setUp() {
        service = new PropertySecretsService();
        Properties props = new Properties();
        props.setProperty("alfresco.alfdockia.secret.svc_ai_password", "supersecret");
        service.setGlobalProperties(props);
    }

    @Test
    public void resolvesPropSecret() {
        String value = service.resolve("prop:alfresco.alfdockia.secret.svc_ai_password");
        assertEquals("supersecret", value);
    }

    @Test(expected = BadRequestException.class)
    public void throwsWhenSecretRefEmpty() {
        service.resolve("");
    }

    @Test(expected = BadRequestException.class)
    public void throwsWhenKeyMissing() {
        service.resolve("prop:missing.key");
    }

    @Test(expected = BadRequestException.class)
    public void throwsWhenUnsupportedScheme() {
        service.resolve("vault:kv/some#key");
    }
}
