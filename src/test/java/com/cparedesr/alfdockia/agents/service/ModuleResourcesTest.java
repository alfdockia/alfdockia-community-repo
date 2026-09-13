package com.cparedesr.alfdockia.agents.service;

import org.junit.Test;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModuleResourcesTest {

    @Test
    public void filteredModuleContextLoadsAndModelsExist() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(beans);
        reader.loadBeanDefinitions(new ClassPathResource("alfresco/module/alfdockia/module-context.xml"));

        assertTrue(beans.containsBeanDefinition("alfdockia.agents.subsystem"));
        assertTrue(beans.containsBeanDefinition("webscript.com.cparedesr.alfdockia.agents.license.get"));
        List<?> models = (List<?>) beans.getBeanDefinition("alfdockia.dictionaryBootstrap")
                .getPropertyValues().get("models");
        assertTrue(models != null && !models.isEmpty());
        for (Object model : models) {
            String path = ((TypedStringValue) model).getValue();
            assertTrue("Missing model: " + path, new ClassPathResource(path).exists());
        }
    }

    @Test
    public void moduleRetainsOriginalIdentityAcrossArtifactRename() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = new ClassPathResource(
                "alfresco/module/alfdockia/module.properties").getInputStream()) {
            properties.load(input);
        }
        assertEquals("alfdockia", properties.getProperty("module.id"));
    }
}
