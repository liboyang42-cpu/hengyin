package com.chengyinhub.business.domain;

/** Internal persistence only; never serialize key/receipt records as an HTTP response. */
public class NativeAppAttestKey {
    private String keyId;
    private Long memberId;
    private String provider;
    private String appId;
    private String environment;
    private byte[] publicKeySpki;
    private byte[] receipt;
    private Long assertionCounter;
    private String status;
    private String bundleVersion;
    private Integer distributionCategory;
    private Long createdAt;
    private Long updatedAt;
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { this.keyId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { this.provider = value; }
    public String getAppId() { return appId; }
    public void setAppId(String value) { this.appId = value; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String value) { this.environment = value; }
    public byte[] getPublicKeySpki() { return publicKeySpki; }
    public void setPublicKeySpki(byte[] value) { this.publicKeySpki = value; }
    public byte[] getReceipt() { return receipt; }
    public void setReceipt(byte[] value) { this.receipt = value; }
    public Long getAssertionCounter() { return assertionCounter; }
    public void setAssertionCounter(Long value) { this.assertionCounter = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getBundleVersion() { return bundleVersion; }
    public void setBundleVersion(String value) { this.bundleVersion = value; }
    public Integer getDistributionCategory() { return distributionCategory; }
    public void setDistributionCategory(Integer value) { this.distributionCategory = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long value) { this.updatedAt = value; }
}
