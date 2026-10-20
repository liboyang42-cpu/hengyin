package com.chengyinhub.business.domain;

/** Internal persistence only; never serialize key/receipt records as an HTTP response. */
public class NativeAppAttestChallenge {
    private String challengeId;
    private Long memberId;
    private String keyId;
    private String provider;
    private String appId;
    private String environment;
    private byte[] nonce;
    private Long issuedAt;
    private Long expiresAt;
    private Long consumedAt;
    private String requestDigest;
    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String value) { this.challengeId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { this.keyId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { this.provider = value; }
    public String getAppId() { return appId; }
    public void setAppId(String value) { this.appId = value; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String value) { this.environment = value; }
    public byte[] getNonce() { return nonce; }
    public void setNonce(byte[] value) { this.nonce = value; }
    public Long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Long value) { this.issuedAt = value; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long value) { this.expiresAt = value; }
    public Long getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Long value) { this.consumedAt = value; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String value) { this.requestDigest = value; }
}
