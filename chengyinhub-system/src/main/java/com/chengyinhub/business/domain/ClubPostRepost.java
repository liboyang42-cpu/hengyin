package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 俱乐部帖文转发对象 club_post_repost(dw29 L8)
 * 撤销转发置 del_flag=2;再转发复用同一行,create_time 刷新为新的转发时间。
 */
public class ClubPostRepost extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 被转发的帖文ID */
    private Long postId;

    /** 转发会员ID */
    private Long memberId;

    /** del_flag 0有效 2已撤销 */
    private Integer delFlag;

    /** 转发人昵称/头像(非表字段,动态流联表带出) */
    private String nickname;
    private String avatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}
