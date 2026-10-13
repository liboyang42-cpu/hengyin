package com.chengyinhub.business.domain.dto;

import java.util.ArrayList;
import java.util.List;

public final class MerchantClaimDTO {
    private MerchantClaimDTO() { }

    public static class Proof {
        private String proofType;
        private String assetId;
        public String getProofType() { return proofType; }
        public void setProofType(String proofType) { this.proofType = proofType; }
        public String getAssetId() { return assetId; }
        public void setAssetId(String assetId) { this.assetId = assetId; }
    }

    public static class SubmitRequest {
        private Long merchantId;
        private String requestId;
        private String contactName;
        private String contactPhone;
        private String statement;
        private List<Proof> proofs = new ArrayList<>();
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
        public String getStatement() { return statement; }
        public void setStatement(String statement) { this.statement = statement; }
        public List<Proof> getProofs() { return proofs; }
        public void setProofs(List<Proof> proofs) { this.proofs = proofs; }
    }

    public static class AuditRequest {
        private Long claimId;
        private String requestId;
        private String action;
        private String reason;
        private String expectedStatus;
        private Integer expectedVersion;
        public Long getClaimId() { return claimId; }
        public void setClaimId(Long claimId) { this.claimId = claimId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getExpectedStatus() { return expectedStatus; }
        public void setExpectedStatus(String expectedStatus) { this.expectedStatus = expectedStatus; }
        public Integer getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    }

    public static class BatchItem {
        private Long claimId;
        private String expectedStatus;
        private Integer expectedVersion;
        public Long getClaimId() { return claimId; }
        public void setClaimId(Long claimId) { this.claimId = claimId; }
        public String getExpectedStatus() { return expectedStatus; }
        public void setExpectedStatus(String expectedStatus) { this.expectedStatus = expectedStatus; }
        public Integer getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    }

    public static class BatchRequest {
        private String requestId;
        private String action;
        private String reason;
        private List<BatchItem> items = new ArrayList<>();
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public List<BatchItem> getItems() { return items; }
        public void setItems(List<BatchItem> items) { this.items = items; }
    }
}
