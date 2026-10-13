package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/** 圈层主题轻互动答案。 */
public class CircleExploreAnswer implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long sessionId;
    private Long memberId;
    private String answerStage;
    private String answerValue;
    /** 仅用于记忆卡展示的匿名同行标签，不落库。 */
    private String memberLabel;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { this.sessionId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public String getAnswerStage() { return answerStage; }
    public void setAnswerStage(String value) { this.answerStage = value; }
    public String getAnswerValue() { return answerValue; }
    public void setAnswerValue(String value) { this.answerValue = value; }
    public String getMemberLabel() { return memberLabel; }
    public void setMemberLabel(String value) { this.memberLabel = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { this.createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { this.updateTime = value; }
}
