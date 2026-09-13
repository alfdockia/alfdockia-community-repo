/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.cparedesr.alfdockia.agents.service.license;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resultado seguro de resolver una licencia.
 */
public class LicenseStatus {

    private boolean valid;
    private String edition;
    private String issuer;
    private String product;
    private String licenseId;
    private String licensee;
    private int maxAgents;
    private boolean unlimitedAgents;
    private String issuedAt;
    private String expiresAt;
    private List<String> features = new ArrayList<>();
    private String loadedFrom;
    private String reason;

    public static LicenseStatus community(String reason) {
        LicenseStatus status = new LicenseStatus();
        status.valid = false;
        status.edition = "community";
        status.issuer = SignedLicenseService.PRODUCT_NAME;
        status.product = SignedLicenseService.PRODUCT_NAME;
        status.maxAgents = SignedLicenseService.COMMUNITY_AGENT_LIMIT;
        status.unlimitedAgents = false;
        status.features = Collections.emptyList();
        status.reason = reason;
        return status;
    }

    public static LicenseStatus valid(AlfDokiaLicensePayload payload, String loadedFrom) {
        LicenseStatus status = new LicenseStatus();
        status.valid = true;
        status.edition = payload.getEdition();
        status.issuer = payload.getIssuer();
        status.product = payload.getProduct();
        status.licenseId = payload.getLicenseId();
        status.licensee = payload.getLicensee();
        status.maxAgents = payload.getMaxAgents();
        status.unlimitedAgents = payload.getMaxAgents() < 0;
        status.issuedAt = payload.getIssuedAt();
        status.expiresAt = payload.getExpiresAt();
        status.features = payload.getFeatures() == null
                ? Collections.emptyList()
                : new ArrayList<>(payload.getFeatures());
        status.loadedFrom = loadedFrom;
        return status;
    }

    public boolean isValid() {
        return valid;
    }

    public String getEdition() {
        return edition;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getProduct() {
        return product;
    }

    public String getLicenseId() {
        return licenseId;
    }

    public String getLicensee() {
        return licensee;
    }

    public int getMaxAgents() {
        return maxAgents;
    }

    public boolean isUnlimitedAgents() {
        return unlimitedAgents;
    }

    public String getIssuedAt() {
        return issuedAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public List<String> getFeatures() {
        return features;
    }

    public String getLoadedFrom() {
        return loadedFrom;
    }

    public String getReason() {
        return reason;
    }
}
