package com.chengyinhub.business.domain;

/** 群聊投票选项及结果计数。 */
public class ImPollOption {
    private Long id;
    private Long pollId;
    private Integer position;
    private String content;
    private Long voteCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPollId() { return pollId; }
    public void setPollId(Long pollId) { this.pollId = pollId; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getVoteCount() { return voteCount; }
    public void setVoteCount(Long voteCount) { this.voteCount = voteCount; }
}
