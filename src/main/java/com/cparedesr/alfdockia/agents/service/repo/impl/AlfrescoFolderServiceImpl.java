/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.repo.impl;

import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.repo.AlfrescoFolderService;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.search.SearcherException;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Servicio de repositorio que acepta un NodeRef existente o crea la ruta
 * indicada bajo Company Home.
 */
public class AlfrescoFolderServiceImpl implements AlfrescoFolderService {

    private NodeService nodeService;
    private SearchService searchService;
    private FileFolderService fileFolderService;

    public void setNodeService(NodeService nodeService) { this.nodeService = nodeService; }
    public void setSearchService(SearchService searchService) { this.searchService = searchService; }
    public void setFileFolderService(FileFolderService fileFolderService) { this.fileFolderService = fileFolderService; }

    @Override
    public String ensureAndResolveNodeId(String nodeId, String path) {

        if (StringUtils.hasText(nodeId)) {
            NodeRef nr = new NodeRef(nodeId);
            if (!nodeService.exists(nr)) {
                throw new BadRequestException("NODE_NOT_FOUND", "Node not found: " + nodeId);
            }
            return nr.toString();
        }

        if (!StringUtils.hasText(path)) {
            throw new BadRequestException("PATH_REQUIRED", "path is required when nodeId is empty");
        }

        NodeRef folder = ensureFolderByPath(path);
        return folder.toString();
    }

    private NodeRef ensureFolderByPath(String repoPath) {
        String normalized = repoPath.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;

        // Validamos antes de buscar Company Home para devolver un error claro
        // y evitar llamadas innecesarias al servicio de busqueda.
        String[] parts = normalized.split("/");
        // parts[0] = "" ; parts[1] = "Company Home"
        if (parts.length < 2 || !parts[1].equalsIgnoreCase("Company Home")) {
            throw new BadRequestException("PATH_INVALID", "Path must start with /Company Home");
        }

        NodeRef companyHome = getCompanyHome();
        if (normalized.equalsIgnoreCase("/Company Home")) return companyHome;

        NodeRef current = companyHome;
        for (int i = 2; i < parts.length; i++) {
            String name = parts[i].trim();
            if (name.isEmpty()) continue;

            FileInfo child = getChildFolder(current, name);
            if (child == null) {
                child = fileFolderService.create(current, name, ContentModel.TYPE_FOLDER);
            }
            current = child.getNodeRef();
        }
        return current;
    }

    private FileInfo getChildFolder(NodeRef parent, String folderName) {
        List<FileInfo> children = fileFolderService.list(parent);
        for (FileInfo fi : children) {
            if (fi.isFolder() && folderName.equals(fi.getName())) return fi;
        }
        return null;
    }

    private NodeRef getCompanyHome() {
        ResultSet rs = null;
        try {
            SearchParameters sp = new SearchParameters();
            sp.setLanguage(SearchService.LANGUAGE_FTS_ALFRESCO);
            sp.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
            sp.setQuery("PATH:\"/app:company_home\"");

            rs = searchService.query(sp);

            // Guardia extra: si la busqueda devuelve null, respondemos con un
            // error de dominio en vez de una excepcion generica.
            if (rs == null || rs.length() == 0) {
                throw new BadRequestException("COMPANY_HOME_NOT_FOUND", "Company Home not found");
            }

            return rs.getNodeRef(0);

        } catch (SearcherException e) {
            throw new BadRequestException("SEARCH_ERROR", "Error searching Company Home");
        } finally {
            if (rs != null) rs.close();
        }
    }
}
