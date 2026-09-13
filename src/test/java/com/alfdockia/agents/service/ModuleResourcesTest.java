package com.alfdockia.agents.service;

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
        assertTrue(beans.containsBeanDefinition("webscript.com.alfdockia.agents.license.get"));
        List<?> models = (List<?>) beans.getBeanDefinition("alfdockia.dictionaryBootstrap")
                .getPropertyValues().get("models");
        assertTrue(models != null && !models.isEmpty());
        for (Object model : models) {
            String path = ((TypedStringValue) model).getValue();
            assertTrue("Missing model: " + path, new ClassPathResource(path).exists());
        }
    }

    @Test
    public void webScriptBeansHaveMatchingDescriptorsAndTemplates() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(beans).loadBeanDefinitions(new ClassPathResource(
                "alfresco/module/alfdockia/context/alfdockia-webscripts-context.xml"));

        int checked = 0;
        for (String name : beans.getBeanDefinitionNames()) {
            if (!name.startsWith("webscript.")) {
                continue;
            }
            String script = name.substring("webscript.".length());
            int directoryEnd = script.lastIndexOf('.', script.lastIndexOf('.') - 1);
            String path = "alfresco/extension/templates/webscripts/"
                    + script.substring(0, directoryEnd).replace('.', '/')
                    + "/" + script.substring(directoryEnd + 1);
            assertTrue("Missing descriptor for " + name,
                    new ClassPathResource(path + ".desc.xml").exists());
            assertTrue("Missing JSON template for " + name,
                    new ClassPathResource(path + ".json.ftl").exists());
            checked++;
        }
        assertTrue("No Web Scripts checked", checked > 0);
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
