/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.repo.impl;

import com.alfdockia.agents.service.exception.BadRequestException;
import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AlfrescoFolderServiceImplTest {

    private AlfrescoFolderServiceImpl service;

    private NodeService nodeService;
    private SearchService searchService;
    private FileFolderService fileFolderService;

    @Before
    public void setUp() {
        service = new AlfrescoFolderServiceImpl();
        nodeService = mock(NodeService.class);
        searchService = mock(SearchService.class);
        fileFolderService = mock(FileFolderService.class);

        service.setNodeService(nodeService);
        service.setSearchService(searchService);
        service.setFileFolderService(fileFolderService);
    }

    @Test
    public void returnsNodeIdWhenNodeExists() {
        String nodeId = "workspace://SpacesStore/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        when(nodeService.exists(new NodeRef(nodeId))).thenReturn(true);

        String result = service.ensureAndResolveNodeId(nodeId, null);

        assertEquals(nodeId, result);
        verify(searchService, never()).query(any(SearchParameters.class));
    }

    @Test(expected = BadRequestException.class)
    public void throwsWhenNodeDoesNotExist() {
        String nodeId = "workspace://SpacesStore/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        when(nodeService.exists(new NodeRef(nodeId))).thenReturn(false);

        service.ensureAndResolveNodeId(nodeId, null);
    }

    @Test
    public void createsFoldersWhenPathProvided() {
        NodeRef companyHome = new NodeRef("workspace://SpacesStore/11111111-1111-1111-1111-111111111111");

        ResultSet rs = mock(ResultSet.class);
        when(rs.length()).thenReturn(1);
        when(rs.getNodeRef(0)).thenReturn(companyHome);
        when(searchService.query(any(SearchParameters.class))).thenReturn(rs);

        when(fileFolderService.list(any(NodeRef.class))).thenReturn(Collections.emptyList());

        FileInfo fi1 = mock(FileInfo.class);
        NodeRef nr1 = new NodeRef("workspace://SpacesStore/22222222-2222-2222-2222-222222222222");
        when(fi1.getNodeRef()).thenReturn(nr1);
        when(fi1.isFolder()).thenReturn(true);
        when(fi1.getName()).thenReturn("AI");

        FileInfo fi2 = mock(FileInfo.class);
        NodeRef nr2 = new NodeRef("workspace://SpacesStore/33333333-3333-3333-3333-333333333333");
        when(fi2.getNodeRef()).thenReturn(nr2);
        when(fi2.isFolder()).thenReturn(true);
        when(fi2.getName()).thenReturn("target");

        when(fileFolderService.create(eq(companyHome), eq("AI"), eq(ContentModel.TYPE_FOLDER))).thenReturn(fi1);
        when(fileFolderService.create(eq(nr1), eq("target"), eq(ContentModel.TYPE_FOLDER))).thenReturn(fi2);

        String resolved = service.ensureAndResolveNodeId(null, "/Company Home/AI/target");

        assertEquals(nr2.toString(), resolved);

        verify(rs).close();
        verify(fileFolderService, times(2)).create(any(NodeRef.class), anyString(), eq(ContentModel.TYPE_FOLDER));
    }

    @Test(expected = BadRequestException.class)
    public void pathMustStartWithCompanyHome() {
        // El codigo valida antes de consultar Company Home, asi que no hace
        // falta mockear searchService aqui.
        service.ensureAndResolveNodeId(null, "/Sites/site1/documentLibrary");
    }
}
