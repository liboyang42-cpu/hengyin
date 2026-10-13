package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * NPC 追问对话日志实体。
 *
 * @author chengyinhub
 */
public class NpcChatLog
{
    private Long id;
    private Long userId;
    private Long profileId;
    private Long activityId;
    private String userMsg;
    private String npcReply;
    /** 0=正常 1=输入被拦 2=输出被拦 3=降级兜底 */
    private Integer safetyFlag;
    private String model;
    private Integer latencyMs;
    private Integer tokenIn;
    private Integer tokenOut;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getUserMsg() { return userMsg; }
    public void setUserMsg(String userMsg) { this.userMsg = userMsg; }
    public String getNpcReply() { return npcReply; }
    public void setNpcReply(String npcReply) { this.npcReply = npcReply; }
    public Integer getSafetyFlag() { return safetyFlag; }
    public void setSafetyFlag(Integer safetyFlag) { this.safetyFlag = safetyFlag; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public Integer getTokenIn() { return tokenIn; }
    public void setTokenIn(Integer tokenIn) { this.tokenIn = tokenIn; }
    public Integer getTokenOut() { return tokenOut; }
    public void setTokenOut(Integer tokenOut) { this.tokenOut = tokenOut; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
