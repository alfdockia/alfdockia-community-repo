package com.cparedesr.alfdockia.agents.service.registry.impl;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.namespace.QNamePattern;
import org.junit.Before;
import org.junit.Test;
import java.util.Date;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgentRegistryStartupTest {
    private AgentRegistryRepoService registry;
    private NodeService nodes;
    private SearchService search;
    private FileFolderService files;
    private final NodeRef root = node("root");
    private final NodeRef home = node("home");
    private final NodeRef dictionary = node("dictionary");
    private final NodeRef folder = node("agents");
    private final NodeRef agent = node("agent");
    private static NodeRef node(String id) { return new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, id); }
    private static QName app(String name) { return QName.createQName("http://www.alfresco.org/model/application/1.0", name); }
    private static QName agentProperty(String name) { return QName.createQName("http://www.cparedesr.com/model/alfdockia/1.0", name); }

    @Before public void setup() {
        nodes = mock(NodeService.class);
        search = mock(SearchService.class);
        files = mock(FileFolderService.class);
        registry = new AgentRegistryRepoService();
        registry.setNodeService(nodes);
        registry.setSearchService(search);
        registry.setFileFolderService(files);
        when(nodes.getRootNode(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE)).thenReturn(root);
        when(nodes.getChildAssocs(eq(root), any(QNamePattern.class), eq(app("company_home"))))
                .thenReturn(List.of(new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, root, app("company_home"), home)));
        when(nodes.getChildAssocs(eq(home), any(QNamePattern.class), eq(app("dictionary"))))
                .thenReturn(List.of(new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, home, app("dictionary"), dictionary)));
        FileInfo folderInfo = mock(FileInfo.class);
        when(folderInfo.isFolder()).thenReturn(true);
        when(folderInfo.getName()).thenReturn("Alfdockia Agents");
        when(folderInfo.getNodeRef()).thenReturn(folder);
        when(files.list(dictionary)).thenReturn(List.of(folderInfo));
        FileInfo agentInfo = mock(FileInfo.class);
        when(agentInfo.getNodeRef()).thenReturn(agent);
        when(files.list(folder)).thenReturn(List.of(agentInfo));
        when(nodes.getType(agent)).thenReturn(agentProperty("agent"));
        when(nodes.getProperty(agent, agentProperty("agentId"))).thenReturn("agent-1");
        when(nodes.getProperty(agent, agentProperty("containerId"))).thenReturn("container-1");
        when(nodes.getProperty(agent, agentProperty("createdAt"))).thenReturn(new Date(1234));
    }

    @Test public void startupAndLicenseInventoryDoNotCallSolr() {
        assertEquals("agent-1", registry.listRuntimeInfos().get(0).getAgentId());
        assertEquals(1234, registry.listLicenseRuntimeInfos().get(0).getCreatedAt());
        assertEquals(1, registry.countAgentsUpTo(5));
        assertEquals(1, registry.listAgents(0, 100).size());
        when(nodes.getProperty(agent, agentProperty("name"))).thenReturn("registered-agent");
        assertTrue(registry.existsByName("registered-agent"));
        assertEquals("container-1", registry.getRuntimeInfoByAgentId("agent-1").getContainerId());
        assertEquals(List.of("container-1"), registry.listRegisteredContainerIds());
        verifyNoInteractions(search);
    }

    @Test public void noRegistryFolderReturnsEmptyWithoutSearchingOrCreating() {
        when(files.list(dictionary)).thenReturn(List.of());
        assertTrue(registry.listRuntimeInfos().isEmpty());
        assertTrue(registry.listLicenseRuntimeInfos().isEmpty());
        assertEquals(0, registry.countAgentsUpTo(5));
        assertTrue(registry.listAgents(0, 100).isEmpty());
        verify(files, never()).create(any(), any(), any());
        verifyNoInteractions(search);
    }
    @Test public void updatingObservedRuntimeStatePreservesDesiredState() {
        when(nodes.exists(agent)).thenReturn(true);
        var runtime = registry.listRuntimeInfos().get(0);
        registry.updateRuntimeState(runtime, "stopped");
        verify(nodes).setProperty(agent, agentProperty("currentState"), "stopped");
        verify(nodes, never()).setProperty(eq(agent), eq(agentProperty("desiredState")), any());
    }

}
