package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P1 会员能力索引(member_capability)。
 * 一个会员可有多条(capability 多能力),替代单值 ums_member.role 作能力真源。
 * player 能力隐式默认,不落表。
 */
public class MemberCapability extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String CAP_CLUB_LEADER = "club_leader";
    public static final String CAP_MERCHANT = "merchant";

    private Long id;
    private Long memberId;
    private String capability;
    /** 1=有效 0=已撤销 */
    private Integer status;
    private java.util.Date grantedAt;
    private Long grantedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getCapability() { return capability; }
    public void setCapability(String capability) { this.capability = capability; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public java.util.Date getGrantedAt() { return grantedAt; }
    public void setGrantedAt(java.util.Date grantedAt) { this.grantedAt = grantedAt; }
    public Long getGrantedBy() { return grantedBy; }
    public void setGrantedBy(Long grantedBy) { this.grantedBy = grantedBy; }
}
