package com.alfdockia.agents.webscripts;

import com.alfdockia.agents.service.license.AlfDokiaLicensePayload;
import com.alfdockia.agents.service.license.LicenseStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.junit.Test;
import java.io.StringWriter;
import java.util.Map;
import static org.junit.Assert.*;

public class LicenseStatusTemplateTest {
    private JsonNode render(LicenseStatus license) throws Exception {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_0);
        configuration.setObjectWrapper(new BeansWrapper(Configuration.VERSION_2_3_0));
        configuration.setClassLoaderForTemplateLoading(getClass().getClassLoader(),
                "/alfresco/extension/templates/webscripts/com/alfdockia/agents");
        Template template = configuration.getTemplate("license.get.json.ftl", "UTF-8");
        StringWriter output = new StringWriter();
        template.process(Map.of("data", Map.of("currentAgents", 5, "license", license)), output);
        return new ObjectMapper().readTree(output.toString()).path("data");
    }

    @Test
    public void rendersCommunityAsJsonBooleanWithReason() throws Exception {
        JsonNode data = render(LicenseStatus.community("No AlfDokia license file configured"));
        assertEquals(5, data.path("currentAgents").asInt());
        JsonNode license = data.path("license");
        assertTrue(license.path("valid").isBoolean());
        assertFalse(license.path("valid").asBoolean());
        assertEquals("community", license.path("edition").asText());
        assertEquals(5, license.path("maxAgents").asInt());
        assertTrue(license.path("loadedFrom").isNull());
        assertEquals("No AlfDokia license file configured", license.path("reason").asText());
    }

    @Test
    public void rendersValidLicenseAsJsonBoolean() throws Exception {
        AlfDokiaLicensePayload payload = new AlfDokiaLicensePayload();
        payload.setEdition("enterprise");
        payload.setIssuer("AlfDokia");
        payload.setProduct("AlfDokia");
        payload.setMaxAgents(25);
        JsonNode license = render(LicenseStatus.valid(payload, "/licenses/test.json")).path("license");
        assertTrue(license.path("valid").isBoolean());
        assertTrue(license.path("valid").asBoolean());
        assertEquals(25, license.path("maxAgents").asInt());
        assertEquals("/licenses/test.json", license.path("loadedFrom").asText());
    }
}
