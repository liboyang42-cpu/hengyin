package com.chengyinhub.business.domain.dto;

/** Adoption-only contract. IDs stay canonical decimal strings at the API boundary. */
public class TemplateAdoptionCommand {
    private String requestId;
    private String originalTemplateId;
    private String sourceVersion;
    private String scope;
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId = v; }
    public String getOriginalTemplateId() { return originalTemplateId; }
    public void setOriginalTemplateId(String v) { originalTemplateId = v; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String v) { sourceVersion = v; }
    public String getScope() { return scope; }
    public void setScope(String v) { scope = v; }
}
