package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 俱乐部动态点赞对象 club_post_like
 */
public class ClubPostLike extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 动态ID */
    private Long postId;

    /** 点赞会员ID */
    private Long memberId;

    /** 仅请求入参(P1-023):目标点赞态;为空时按旧切换语义 */
    private Boolean liked;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Boolean getLiked() { return liked; }
    public void setLiked(Boolean liked) { this.liked = liked; }
}
