package com.chengyinhub.business.domain.dto;

/** 群聊投票提交命令；首版不支持撤回或改票。 */
public class ImPollVoteCommand {
    private Long pollId;
    private Long optionId;

    public Long getPollId() { return pollId; }
    public void setPollId(Long pollId) { this.pollId = pollId; }
    public Long getOptionId() { return optionId; }
    public void setOptionId(Long optionId) { this.optionId = optionId; }
}
