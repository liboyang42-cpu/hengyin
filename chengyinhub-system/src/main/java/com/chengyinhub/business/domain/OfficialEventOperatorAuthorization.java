package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * Web 后台账号代表官方会员操作 V2 活动的显式授权。
 *
 * <p>sys_user 与 ums_member 是两个独立身份域，绝不能以相同的数值 ID 互相推断。
 * 每条激活授权仅允许一个后台账号代表一个白名单官方会员；撤销时保留历史行以便调证。</p>
 */
public class OfficialEventOperatorAuthorization {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long id;
    private Long sysUserId;
    /** 仅为唯一索引服务；ACTIVE 时等于 sysUserId，撤销后置空。 */
    private Long activeSysUserId;
    /** OFFICIAL.party_id 语义一致，始终为 ums_member.id。 */
    private Long memberId;
    private String status;
    private Long grantedBy;
    private Date grantedAt;
    private String grantReason;
    private Long revokedBy;
    private Date revokedAt;
    private String revokeReason;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSysUserId() { return sysUserId; }
    public void setSysUserId(Long sysUserId) { this.sysUserId = sysUserId; }
    public Long getActiveSysUserId() { return activeSysUserId; }
    public void setActiveSysUserId(Long activeSysUserId) { this.activeSysUserId = activeSysUserId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getGrantedBy() { return grantedBy; }
    public void setGrantedBy(Long grantedBy) { this.grantedBy = grantedBy; }
    public Date getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Date grantedAt) { this.grantedAt = grantedAt; }
    public String getGrantReason() { return grantReason; }
    public void setGrantReason(String grantReason) { this.grantReason = grantReason; }
    public Long getRevokedBy() { return revokedBy; }
    public void setRevokedBy(Long revokedBy) { this.revokedBy = revokedBy; }
    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
