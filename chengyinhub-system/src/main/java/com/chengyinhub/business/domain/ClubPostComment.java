package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 俱乐部动态评论对象 club_post_comment
 */
public class ClubPostComment extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 动态ID */
    private Long postId;

    /** 评论会员ID */
    private Long memberId;

    /** 评论内容 */
    private String content;

    /** del_flag 0存在 2删除 */
    private Integer delFlag;

    /** 非持久：评论者昵称 */
    private String nickname;

    /** 非持久：评论者头像 */
    private String avatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("postId", getPostId())
            .append("memberId", getMemberId())
            .toString();
    }
}
