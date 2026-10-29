package com.chengyinhub.business.domain;

/** Durable identity for one NPC and its original canonical recording. Never TTL-delete. */
public class NpcVoiceEnrollment
{
    private Long id;
    private String requestId;
    private Long previousIntentId;
    private Long npcId;
    private Integer scopeType;
    private Long scopeId;
    private Long actorMemberId;
    private String sampleSha256;
    private String sampleReference;
    private String state;
    private String voiceId;
    private String reasonCode;
    private Long profileVersion;

    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getPreviousIntentId() { return previousIntentId; }
    public void setPreviousIntentId(Long value) { previousIntentId = value; }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getNpcId() { return npcId; }
    public void setNpcId(Long value) { npcId = value; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer value) { scopeType = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { actorMemberId = value; }
    public String getSampleSha256() { return sampleSha256; }
    public void setSampleSha256(String value) { sampleSha256 = value; }
    public String getSampleReference() { return sampleReference; }
    public void setSampleReference(String value) { sampleReference = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getVoiceId() { return voiceId; }
    public void setVoiceId(String value) { voiceId = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public Long getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Long value) { profileVersion = value; }
}
