package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CircleExploreAnswer;
import com.chengyinhub.business.domain.CircleExploreRecord;

import java.util.List;

/** 玩家私人记录卡；明确与商家履约事实隔离。 */
public class CircleExploreCardVO {
    private Long sessionId;
    private Long topicId;
    private String themeCode;
    private String status;
    private String inviteCode;
    private int recordedMerchantCount;
    private int requiredMerchantCount = 2;
    private boolean interactionComplete;
    private boolean completed;
    private String disclaimer = "本卡仅记录玩家自助选择，不代表商家确认到店、预约、核销或成交";
    private List<CircleExploreRecord> records;
    private List<CircleExploreAnswer> answers;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { this.sessionId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { this.topicId = value; }
    public String getThemeCode() { return themeCode; }
    public void setThemeCode(String value) { this.themeCode = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String value) { this.inviteCode = value; }
    public int getRecordedMerchantCount() { return recordedMerchantCount; }
    public void setRecordedMerchantCount(int value) { this.recordedMerchantCount = value; }
    public int getRequiredMerchantCount() { return requiredMerchantCount; }
    public void setRequiredMerchantCount(int value) { this.requiredMerchantCount = value; }
    public boolean isInteractionComplete() { return interactionComplete; }
    public void setInteractionComplete(boolean value) { this.interactionComplete = value; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean value) { this.completed = value; }
    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String value) { this.disclaimer = value; }
    public List<CircleExploreRecord> getRecords() { return records; }
    public void setRecords(List<CircleExploreRecord> value) { this.records = value; }
    public List<CircleExploreAnswer> getAnswers() { return answers; }
    public void setAnswers(List<CircleExploreAnswer> value) { this.answers = value; }
}
