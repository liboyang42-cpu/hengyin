package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 预制人生 note 留言:一个玩家在一站留下一句,后来的人读得到。
 *
 * <p>跨玩家、按节点存。景点留言要按地点聚合,会话 state 里装不下别的玩家写的东西,
 * 所以走这张表(见 migration_20260917_play_note.sql)。同一个人同一站只留一句(uk_member_node)。
 */
public class PlayNote {
    private Long id;
    private Long topicId;
    private Long nodeId;
    private Long memberId;
    private String content;
    private Integer delFlag;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
