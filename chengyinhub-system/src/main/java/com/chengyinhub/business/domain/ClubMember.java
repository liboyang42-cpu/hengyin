package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 俱乐部成员对象 club_member
 */
public class ClubMember extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 俱乐部ID */
    private Long clubId;

    /** 会员ID */
    private Long memberId;

    /** 成员角色 0成员 */
    private Integer role;

    /** 入会状态 0待审 1正常 2已拒绝 */
    private Integer status;

    /** 入会申请留言（申请人自己写的一句话，可空。稿 P3 30:2 每条申请下面那一句） */
    private String joinMessage;

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    @com.fasterxml.jackson.annotation.JsonAlias("rejectReason")
    private String reviewReason;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long reviewerMemberId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Date reviewedAt;
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String value) { reviewReason = value; }
    public Long getReviewerMemberId() { return reviewerMemberId; }
    public void setReviewerMemberId(Long value) { reviewerMemberId = value; }
    public Date getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Date value) { reviewedAt = value; }

    /** 加入时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date joinTime;

    /** del_flag */
    private Integer delFlag;

    /** 非持久：成员昵称（列表 join 展示） */
    private String nickname;

    /** 非持久：成员头像 */
    private String avatar;

    /** 非持久：会员等级（列表 join 展示） */
    private Long levelId;

    /** 非持久：是否群主(=club.member_id) */
    private Boolean isOwner;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getJoinMessage() { return joinMessage; }
    public void setJoinMessage(String joinMessage) { this.joinMessage = joinMessage; }
    public Date getJoinTime() { return joinTime; }
    public void setJoinTime(Date joinTime) { this.joinTime = joinTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Long getLevelId() { return levelId; }
    public void setLevelId(Long levelId) { this.levelId = levelId; }
    public Boolean getIsOwner() { return isOwner; }
    public void setIsOwner(Boolean isOwner) { this.isOwner = isOwner; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("clubId", getClubId())
            .append("memberId", getMemberId())
            .append("status", getStatus())
            .toString();
    }
}
