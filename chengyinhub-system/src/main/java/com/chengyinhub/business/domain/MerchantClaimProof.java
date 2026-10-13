package com.chengyinhub.business.domain;

public class MerchantClaimProof {
    private Long id;
    private Long claimId;
    private String proofType;
    private String assetId;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public String getProofType() { return proofType; }
    public void setProofType(String proofType) { this.proofType = proofType; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
}
