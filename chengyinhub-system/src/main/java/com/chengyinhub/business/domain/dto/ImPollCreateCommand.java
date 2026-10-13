package com.chengyinhub.business.domain.dto;

import java.util.Date;
import java.util.List;

/** 群聊投票创建命令。 */
public class ImPollCreateCommand {
    private Long conversationId;
    private String clientPollKey;
    private String question;
    private List<String> options;
    private Date deadlineAt;

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getClientPollKey() { return clientPollKey; }
    public void setClientPollKey(String clientPollKey) { this.clientPollKey = clientPollKey; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public Date getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Date deadlineAt) { this.deadlineAt = deadlineAt; }
}
