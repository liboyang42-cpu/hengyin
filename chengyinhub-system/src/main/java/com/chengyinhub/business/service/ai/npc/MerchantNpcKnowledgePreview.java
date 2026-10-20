package com.chengyinhub.business.service.ai.npc;

/** Immutable, unapproved evidence for a current human review. No owner/profile/audit-log payload. */
public final class MerchantNpcKnowledgePreview {
    private final String auditTaskId,merchantId,revisionId,revisionVersion,contentHash,publicFactsDraftJson;
    private final int auditTaskVersion;
    MerchantNpcKnowledgePreview(long taskId,int taskVersion,long merchantId,long revisionId,
            long revisionVersion,String contentHash,String publicFactsDraftJson) {
        this.auditTaskId=Long.toString(taskId); this.auditTaskVersion=taskVersion;
        this.merchantId=Long.toString(merchantId); this.revisionId=Long.toString(revisionId);
        this.revisionVersion=Long.toString(revisionVersion); this.contentHash=contentHash;
        this.publicFactsDraftJson=publicFactsDraftJson;
    }
    public String getContract() { return "questify.merchant-npc-review-preview.v1"; }
    public String getAuditTaskId() { return auditTaskId; }
    public int getAuditTaskVersion() { return auditTaskVersion; }
    public String getMerchantId() { return merchantId; }
    public String getRevisionId() { return revisionId; }
    public String getRevisionVersion() { return revisionVersion; }
    public String getContentHash() { return contentHash; }
    public String getPublicFactsDraftJson() { return publicFactsDraftJson; }
    public String getStatus() { return "PENDING"; }
    public String getTextEncoding() { return "UNTRUSTED_JSON_PLAIN_TEXT"; }
    public boolean isRuntimePublication() { return false; }
}
