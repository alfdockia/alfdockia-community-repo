/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.registry.impl;

import com.cparedesr.alfdockia.agents.model.AgentDeployRequest;
import com.cparedesr.alfdockia.agents.model.AgentDetail;
import com.cparedesr.alfdockia.agents.model.AgentRuntimeInfo;
import com.cparedesr.alfdockia.agents.model.AgentSummary;
import com.cparedesr.alfdockia.agents.service.exception.BadRequestException;
import com.cparedesr.alfdockia.agents.service.registry.AgentRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.namespace.RegexQNamePattern;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.namespace.QName;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Implementacion del registro de agentes usando nodos del repositorio de
 * Alfresco bajo Data Dictionary / Alfdockia Agents.
 */
public class AgentRegistryRepoService implements AgentRegistryService {

    private static final String ALFDOCKIA_URI = "http://www.cparedesr.com/model/alfdockia/1.0";
    private static final String AGENT_TYPE_QUERY = "TYPE:\"alfdockia:agent\"";
    private static final String JSON_EXTENSION = ".json";

    private static final QName TYPE_AGENT = QName.createQName(ALFDOCKIA_URI, "agent");
    private static final QName PROP_AGENT_ID = QName.createQName(ALFDOCKIA_URI, "agentId");
    private static final QName PROP_NAME = QName.createQName(ALFDOCKIA_URI, "name");
    private static final QName PROP_IMAGE = QName.createQName(ALFDOCKIA_URI, "image");
    private static final QName PROP_DESIRED = QName.createQName(ALFDOCKIA_URI, "desiredState");
    private static final QName PROP_CURRENT = QName.createQName(ALFDOCKIA_URI, "currentState");
    private static final QName PROP_HEALTH = QName.createQName(ALFDOCKIA_URI, "health");
    private static final QName PROP_CONTAINER = QName.createQName(ALFDOCKIA_URI, "containerId");
    private static final QName PROP_CREATED = QName.createQName(ALFDOCKIA_URI, "createdAt");
    private static final QName PROP_UPDATED = QName.createQName(ALFDOCKIA_URI, "updatedAt");

    private NodeService nodeService;
    private SearchService searchService;
    private FileFolderService fileFolderService;
    private ContentService contentService;

    private final ObjectMapper mapper = new ObjectMapper();

    public void setNodeService(NodeService nodeService) { this.nodeService = nodeService; }
    public void setSearchService(SearchService searchService) { this.searchService = searchService; }
    public void setFileFolderService(FileFolderService fileFolderService) { this.fileFolderService = fileFolderService; }
    public void setContentService(ContentService contentService) { this.contentService = contentService; }

    @Override
    public boolean existsByName(String name) {
        ResultSet rs = null;
        try {
            SearchParameters sp = new SearchParameters();
            sp.setLanguage(SearchService.LANGUAGE_FTS_ALFRESCO);
            sp.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
            sp.setQuery(AGENT_TYPE_QUERY + " AND =alfdockia:name:\"" + escape(name) + "\"");
            rs = searchService.query(sp);
            return rs != null && rs.length() > 0;
        } finally {
            if (rs != null) rs.close();
        }
    }

    @Override
    public int countAgentsUpTo(int limit) {
        // The quota is global, including agents the caller cannot see.
        return AuthenticationUtil.runAsSystem(() -> countRegisteredAgentsUpTo(limit));
    }

    private int countRegisteredAgentsUpTo(int limit) {
        int safeLimit = Math.max(0, limit);
        if (safeLimit == 0) {
            return 0;
        }

        NodeRef folder = ensureRegistryFolder();
        int count = 0;
        for (FileInfo child : fileFolderService.list(folder)) {
            if (TYPE_AGENT.equals(nodeService.getType(child.getNodeRef()))) {
                count++;
                if (count >= safeLimit) {
                    return count;
                }
            }
        }
        return count;
    }

    @Override
    public List<String> listRegisteredContainerIds() {
        return AuthenticationUtil.runAsSystem(() -> {
            List<String> ids = new ArrayList<>();
            for (FileInfo child : fileFolderService.list(ensureRegistryFolder())) {
                if (TYPE_AGENT.equals(nodeService.getType(child.getNodeRef()))) {
                    String id = toStr(nodeService.getProperty(child.getNodeRef(), PROP_CONTAINER));
                    if (id != null && !id.isBlank()) ids.add(id.trim());
                }
            }
            return ids;
        });
    }

    @Override
    public List<AgentRuntimeInfo> listLicenseRuntimeInfos() {
        return AuthenticationUtil.runAsSystem(() -> {
            List<AgentRuntimeInfo> result = new ArrayList<>();
            for (FileInfo child : fileFolderService.list(getDataDictionary())) {
                if (!child.isFolder() || !"Alfdockia Agents".equals(child.getName())) continue;
                for (FileInfo agent : fileFolderService.list(child.getNodeRef())) {
                    if (TYPE_AGENT.equals(nodeService.getType(agent.getNodeRef()))) {
                        result.add(mapRuntimeInfo(agent.getNodeRef()));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public void markLicenseStopped(AgentRuntimeInfo runtime) {
        NodeRef node = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, runtime.getNodeId());
        if (nodeService.exists(node)) {
            nodeService.setProperty(node, PROP_DESIRED, "stopped");
            nodeService.setProperty(node, PROP_CURRENT, "stopped");
            nodeService.setProperty(node, PROP_UPDATED, new Date());
        }
    }

    @Override
    public void createAgentNode(String agentId,
                               AgentDeployRequest sanitizedRequest,
                               String containerId,
                               String desired,
                               String current) {

        try {
            NodeRef folder = ensureRegistryFolder();

            Map<QName, Serializable> props = new HashMap<>();
            props.put(PROP_AGENT_ID, agentId);
            props.put(PROP_NAME, sanitizedRequest.getName());
            props.put(PROP_IMAGE, sanitizedRequest.getImage());
            props.put(PROP_DESIRED, desired);
            props.put(PROP_CURRENT, current);
            props.put(PROP_CONTAINER, containerId);
            Date now = new Date();
            props.put(PROP_CREATED, now);
            props.put(PROP_UPDATED, now);

            String fileName = buildConfigFileName(sanitizedRequest.getName());
            FileInfo fi = fileFolderService.create(folder, fileName, ContentModel.TYPE_CONTENT);

            NodeRef node = fi.getNodeRef();
            nodeService.setType(node, TYPE_AGENT);
            nodeService.addAspect(node, ContentModel.ASPECT_TITLED, Map.of(
                    ContentModel.PROP_TITLE, sanitizedRequest.getName()
            ));
            props.put(ContentModel.PROP_NAME, fileName);
            nodeService.setProperties(node, props);
            writeConfig(node, sanitizedRequest);

        } catch (Exception e) {
            throw new BadRequestException("REGISTRY_WRITE_FAILED", "Failed to persist agent in repository");
        }
    }

    @Override
    public void updateAgentNode(String agentId,
                                AgentDeployRequest sanitizedRequest,
                                String containerId,
                                String desired,
                                String current) {
        try {
            NodeRef node = findByAgentIdOrThrow(agentId);
            String fileName = buildConfigFileName(sanitizedRequest.getName());

            nodeService.setProperty(node, ContentModel.PROP_NAME, fileName);
            nodeService.setProperty(node, PROP_NAME, sanitizedRequest.getName());
            nodeService.setProperty(node, PROP_IMAGE, sanitizedRequest.getImage());
            nodeService.setProperty(node, PROP_DESIRED, desired);
            nodeService.setProperty(node, PROP_CURRENT, current);
            nodeService.setProperty(node, PROP_CONTAINER, containerId);
            nodeService.setProperty(node, PROP_UPDATED, new Date());

            writeConfig(node, sanitizedRequest);

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("REGISTRY_WRITE_FAILED", "Failed to update agent in repository");
        }
    }

    @Override
    public void updateAgentState(String agentId, String desired, String current) {
        try {
            NodeRef node = findByAgentIdOrThrow(agentId);
            nodeService.setProperty(node, PROP_DESIRED, desired);
            nodeService.setProperty(node, PROP_CURRENT, current);
            nodeService.setProperty(node, PROP_UPDATED, new Date());

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("REGISTRY_WRITE_FAILED", "Failed to update agent state in repository");
        }
    }

    @Override
    public List<AgentSummary> listAgents(int skipCount, int maxItems) {
        int safeSkip = Math.max(0, skipCount);
        int safeMax = (maxItems <= 0) ? 100 : Math.min(maxItems, 500);

        ResultSet rs = null;
        try {
            String folderPathQuery = buildRegistryFolderPathQuery();

            SearchParameters sp = new SearchParameters();
            sp.setLanguage(SearchService.LANGUAGE_FTS_ALFRESCO);
            sp.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
            sp.setQuery(AGENT_TYPE_QUERY + " AND PATH:\"" + folderPathQuery + "/*\"");
            sp.setSkipCount(safeSkip);
            sp.setMaxItems(safeMax);

            rs = searchService.query(sp);
            if (rs == null || rs.length() == 0) return Collections.emptyList();

            List<AgentSummary> out = new ArrayList<>(rs.length());
            for (int i = 0; i < rs.length(); i++) {
                out.add(mapSummary(rs.getNodeRef(i)));
            }
            return out;

        } finally {
            if (rs != null) rs.close();
        }
    }

    @Override
    public List<AgentRuntimeInfo> listRuntimeInfos() {
        return listLicenseRuntimeInfos();
    }

    @Override
    public AgentDetail getAgentDetailByAgentId(String agentId) {
        NodeRef nr = findByAgentIdOrThrow(agentId);
        return mapDetail(nr);
    }

    @Override
    public AgentRuntimeInfo getRuntimeInfoByAgentId(String agentId) {
        NodeRef nr = findByAgentIdOrThrow(agentId);

        AgentRuntimeInfo info = new AgentRuntimeInfo();
        info.setAgentId(toStr(nodeService.getProperty(nr, PROP_AGENT_ID)));
        info.setNodeId(nr.getId());
        info.setContainerId(toStr(nodeService.getProperty(nr, PROP_CONTAINER)));
        info.setDesiredState(toStr(nodeService.getProperty(nr, PROP_DESIRED)));
        info.setCurrentState(toStr(nodeService.getProperty(nr, PROP_CURRENT)));
        return info;
    }

    @Override
    public void deleteByAgentId(String agentId) {
        NodeRef nr = findByAgentIdOrThrow(agentId);
        nodeService.deleteNode(nr);
    }

    // ---------------- mapeo ----------------

    private AgentSummary mapSummary(NodeRef nodeRef) {
        AgentSummary s = new AgentSummary();
        s.setNodeId(nodeRef.getId());

        s.setAgentId(toStr(nodeService.getProperty(nodeRef, PROP_AGENT_ID)));
        s.setName(toStr(nodeService.getProperty(nodeRef, PROP_NAME)));
        s.setImage(toStr(nodeService.getProperty(nodeRef, PROP_IMAGE)));
        s.setDesiredState(toStr(nodeService.getProperty(nodeRef, PROP_DESIRED)));
        s.setCurrentState(toStr(nodeService.getProperty(nodeRef, PROP_CURRENT)));
        s.setHealth(toStr(nodeService.getProperty(nodeRef, PROP_HEALTH)));
        s.setContainerId(toStr(nodeService.getProperty(nodeRef, PROP_CONTAINER)));

        s.setCreatedAt(toIso(nodeService.getProperty(nodeRef, PROP_CREATED)));
        s.setUpdatedAt(toIso(nodeService.getProperty(nodeRef, PROP_UPDATED)));

        return s;
    }

    private AgentDetail mapDetail(NodeRef nodeRef) {
        AgentDetail d = new AgentDetail();
        d.setNodeId(nodeRef.getId());

        d.setAgentId(toStr(nodeService.getProperty(nodeRef, PROP_AGENT_ID)));
        d.setName(toStr(nodeService.getProperty(nodeRef, PROP_NAME)));
        d.setImage(toStr(nodeService.getProperty(nodeRef, PROP_IMAGE)));
        d.setDesiredState(toStr(nodeService.getProperty(nodeRef, PROP_DESIRED)));
        d.setCurrentState(toStr(nodeService.getProperty(nodeRef, PROP_CURRENT)));
        d.setHealth(toStr(nodeService.getProperty(nodeRef, PROP_HEALTH)));
        d.setContainerId(toStr(nodeService.getProperty(nodeRef, PROP_CONTAINER)));

        d.setCreatedAt(toIso(nodeService.getProperty(nodeRef, PROP_CREATED)));
        d.setUpdatedAt(toIso(nodeService.getProperty(nodeRef, PROP_UPDATED)));

        String cfg = readConfig(nodeRef);
        d.setConfigJson((cfg == null || cfg.trim().isEmpty()) ? "{}" : cfg);

        return d;
    }

    private AgentRuntimeInfo mapRuntimeInfo(NodeRef nodeRef) {
        AgentRuntimeInfo info = new AgentRuntimeInfo();
        Serializable created = nodeService.getProperty(nodeRef, PROP_CREATED);
        if (!(created instanceof Date)) created = nodeService.getProperty(nodeRef, ContentModel.PROP_CREATED);
        if (created instanceof Date) info.setCreatedAt(((Date) created).getTime());
        info.setAgentId(toStr(nodeService.getProperty(nodeRef, PROP_AGENT_ID)));
        info.setNodeId(nodeRef.getId());
        info.setContainerId(toStr(nodeService.getProperty(nodeRef, PROP_CONTAINER)));
        info.setDesiredState(toStr(nodeService.getProperty(nodeRef, PROP_DESIRED)));
        info.setCurrentState(toStr(nodeService.getProperty(nodeRef, PROP_CURRENT)));
        return info;
    }

    private String toStr(Serializable v) { return v == null ? null : v.toString(); }

    private void writeConfig(NodeRef nodeRef, AgentDeployRequest sanitizedRequest) throws Exception {
        ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
        writer.setMimetype("application/json");
        writer.setEncoding("UTF-8");
        writer.putContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitizedRequest));
    }

    private String readConfig(NodeRef nodeRef) {
        ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
        if (reader == null || !reader.exists()) {
            return "{}";
        }
        return reader.getContentString();
    }

    private String toIso(Serializable v) {
        if (v == null) return null;
        if (v instanceof Date) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
            return sdf.format((Date) v);
        }
        return v.toString();
    }

    // ---------------- helpers de busqueda ----------------

    private NodeRef findByAgentIdOrThrow(String agentId) {
        if (agentId == null || agentId.trim().isEmpty()) {
            throw new BadRequestException("ID_REQUIRED", "Agent id is required");
        }

        ResultSet rs = null;
        try {
            SearchParameters sp = new SearchParameters();
            sp.setLanguage(SearchService.LANGUAGE_FTS_ALFRESCO);
            sp.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
            sp.setQuery(AGENT_TYPE_QUERY + " AND =alfdockia:agentId:\"" + escape(agentId.trim()) + "\"");

            rs = searchService.query(sp);
            if (rs == null || rs.length() == 0) {
                throw new BadRequestException("NOT_FOUND", "Agent not found: " + agentId);
            }
            return rs.getNodeRef(0);

        } finally {
            if (rs != null) rs.close();
        }
    }

    // ---------------- helpers de carpeta de registro ----------------

    private NodeRef ensureRegistryFolder() {
        NodeRef dataDictionary = getDataDictionary();
        return ensureChildFolder(dataDictionary, "Alfdockia Agents");
    }

    private NodeRef ensureChildFolder(NodeRef parent, String name) {
        List<FileInfo> children = fileFolderService.list(parent);
        for (FileInfo fi : children) {
            if (fi.isFolder() && name.equals(fi.getName())) return fi.getNodeRef();
        }
        return fileFolderService.create(parent, name, ContentModel.TYPE_FOLDER).getNodeRef();
    }

    private NodeRef getDataDictionary() {
        // Traverse repository associations directly: startup must not depend on Solr or indexing.
        NodeRef root = nodeService.getRootNode(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
        NodeRef companyHome = applicationChild(root, "company_home");
        return applicationChild(companyHome, "dictionary");
    }

    private NodeRef applicationChild(NodeRef parent, String localName) {
        QName name = QName.createQName("http://www.alfresco.org/model/application/1.0", localName);
        List<ChildAssociationRef> children = nodeService.getChildAssocs(parent, RegexQNamePattern.MATCH_ALL, name);
        if (children.size() != 1) {
            throw new BadRequestException("PATH_NOT_FOUND", "Repository application folder unavailable: " + localName);
        }
        return children.get(0).getChildRef();
    }

    /**
     * PATH fijo del folder "Alfdockia Agents" bajo Data Dictionary.
     */
    private String buildRegistryFolderPathQuery() {
        return "/app:company_home/app:dictionary/cm:Alfdockia_x0020_Agents";
    }

    private String buildConfigFileName(String agentName) {
        String name = agentName == null ? "agent" : agentName.trim();
        if (name.isEmpty()) {
            name = "agent";
        }
        return name.toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION) ? name : name + JSON_EXTENSION;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
