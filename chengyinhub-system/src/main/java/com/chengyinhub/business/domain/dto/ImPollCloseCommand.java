package com.chengyinhub.business.domain.dto;

/** 投票结束命令，使用 version 做状态 CAS。 */
public class ImPollCloseCommand {
    private Long pollId;
    private Long expectedVersion;

    public Long getPollId() { return pollId; }
    public void setPollId(Long pollId) { this.pollId = pollId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
}
