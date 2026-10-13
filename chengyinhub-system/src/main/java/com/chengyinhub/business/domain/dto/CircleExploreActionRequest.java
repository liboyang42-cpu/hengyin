package com.chengyinhub.business.domain.dto;

/** 圈层探索轻量动作请求；各接口只读取自己需要的字段。 */
public class CircleExploreActionRequest {
    private Long topicId;
    private Long sessionId;
    private Long offerId;
    private String inviteCode;
    private String note;
    private String stage;
    private String value;
    private String scope;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { this.topicId = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { this.sessionId = value; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long value) { this.offerId = value; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String value) { this.inviteCode = value; }
    public String getNote() { return note; }
    public void setNote(String value) { this.note = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { this.stage = value; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getScope() { return scope; }
    public void setScope(String value) { this.scope = value; }
}
