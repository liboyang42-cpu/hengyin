package com.chengyinhub.business.domain;

/** Dedicated original-request receipt; never stores contract text or terms. */
public class CoopInviteRequestReceipt {
    private Long id;
    private Long principalMemberId;
    private String requestId;
    private Integer fingerprintVersion;
    private String fingerprint;
    private String fromType;
    private Long fromId;
    private Long fromOwnerMemberId;
    private Long toOwnerMemberId;
    private String scope;
    private Integer inviteType;
    private Long topicId;
    private String toType;
    private Long toId;
    private Long inviteId;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getPrincipalMemberId() { return principalMemberId; }
    public void setPrincipalMemberId(Long value) { this.principalMemberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
    public Integer getFingerprintVersion() { return fingerprintVersion; }
    public void setFingerprintVersion(Integer value) { this.fingerprintVersion = value; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String value) { this.fingerprint = value; }
    public String getFromType() { return fromType; }
    public void setFromType(String value) { this.fromType = value; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long value) { this.fromId = value; }
    public Long getFromOwnerMemberId() { return fromOwnerMemberId; }
    public void setFromOwnerMemberId(Long value) { this.fromOwnerMemberId = value; }
    public Long getToOwnerMemberId() { return toOwnerMemberId; }
    public void setToOwnerMemberId(Long value) { this.toOwnerMemberId = value; }
    public String getScope() { return scope; }
    public void setScope(String value) { this.scope = value; }
    public Integer getInviteType() { return inviteType; }
    public void setInviteType(Integer value) { this.inviteType = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { this.topicId = value; }
    public String getToType() { return toType; }
    public void setToType(String value) { this.toType = value; }
    public Long getToId() { return toId; }
    public void setToId(Long value) { this.toId = value; }
    public Long getInviteId() { return inviteId; }
    public void setInviteId(Long value) { this.inviteId = value; }
}
