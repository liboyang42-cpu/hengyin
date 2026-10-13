package com.chengyinhub.business.domain;

import java.util.Date;
import java.util.List;

/** 群聊投票；首版固定单选、公开参与者且不允许改票。 */
public class ImPoll {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;
    private Long conversationId;
    private Long creatorMemberId;
    private String clientPollKey;
    private String requestHash;
    private String question;
    private String selectionMode;
    private String visibility;
    private String status;
    private Date deadlineAt;
    private Long version;
    private Long messageId;
    private Long closedBy;
    private Date closedAt;
    private Date createTime;
    private Date updateTime;
    private List<ImPollOption> options;
    private Long totalVoters;
    private Long myOptionId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getCreatorMemberId() { return creatorMemberId; }
    public void setCreatorMemberId(Long creatorMemberId) { this.creatorMemberId = creatorMemberId; }
    public String getClientPollKey() { return clientPollKey; }
    public void setClientPollKey(String clientPollKey) { this.clientPollKey = clientPollKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getSelectionMode() { return selectionMode; }
    public void setSelectionMode(String selectionMode) { this.selectionMode = selectionMode; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Date deadlineAt) { this.deadlineAt = deadlineAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getClosedBy() { return closedBy; }
    public void setClosedBy(Long closedBy) { this.closedBy = closedBy; }
    public Date getClosedAt() { return closedAt; }
    public void setClosedAt(Date closedAt) { this.closedAt = closedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public List<ImPollOption> getOptions() { return options; }
    public void setOptions(List<ImPollOption> options) { this.options = options; }
    public Long getTotalVoters() { return totalVoters; }
    public void setTotalVoters(Long totalVoters) { this.totalVoters = totalVoters; }
    public Long getMyOptionId() { return myOptionId; }
    public void setMyOptionId(Long myOptionId) { this.myOptionId = myOptionId; }
}
