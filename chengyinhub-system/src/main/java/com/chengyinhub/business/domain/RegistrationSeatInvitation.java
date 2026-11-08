package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 已付多人票「内含席位」邀请事实（P5）。
 *
 * <p><b>不是第二种购买/权益账本</b>：原 {@code cms_registration} 行仍是唯一已付真源
 * （{@code member_id} 财务/退款归属不动、{@code order_num} 是下单时冻结的席位数）。
 * 本表只记录「第 2..N 席是否已邀请、邀请给谁、是否已领取/取消/过期/释放」。
 *
 * <p>席号 1 恒为购票人自用：现有可玩入口只认原行 {@code member_id}，没有第二套身份分配，
 * 因此不把购票人再算成一张可邀请席位，也不会产生 N+1。
 *
 * <p>商户核销席位 {@link RegistrationSeat} 是另一套语义（核销证明），本表不写它、
 * 不初始化它、也不把邀请领取伪装成核销。
 */
public class RegistrationSeatInvitation {

    /** 0=待领取（占用一个内含席位） 1=已领取（受让人可玩） */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CLAIMED = 1;
    /** 2=购票人撤销 3=服务端过期 4=已释放（受让人不再具备可玩事实） */
    public static final int STATUS_CANCELLED = 2;
    public static final int STATUS_EXPIRED = 3;
    public static final int STATUS_RELEASED = 4;

    private Long id;
    private Long registrationId;
    private Integer seatNo;
    private Integer generation;
    private Long ownerMemberId;
    private Long ownerType;
    private Long ownerId;
    private Long topicId;
    private Integer status;
    /** 随行持久化的公开随机数，用于幂等重放时重算同一枚签名码；不是秘密。 */
    private String tokenNonce;
    /** 明文码的 SHA-256；明文永不落库、永不写日志。 */
    private String tokenHash;
    private Date expiresAt;
    private Long inviteeMemberId;
    private Date claimedAt;
    private Date createTime;
    private Date updateTime;
    private Integer version;

    public boolean pending() { return Integer.valueOf(STATUS_PENDING).equals(status); }
    public boolean claimed() { return Integer.valueOf(STATUS_CLAIMED).equals(status); }
    public boolean active() { return pending() || claimed(); }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer value) { seatNo = value; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer value) { generation = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { ownerMemberId = value; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long value) { ownerType = value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public String getTokenNonce() { return tokenNonce; }
    public void setTokenNonce(String value) { tokenNonce = value; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String value) { tokenHash = value; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date value) { expiresAt = value; }
    public Long getInviteeMemberId() { return inviteeMemberId; }
    public void setInviteeMemberId(Long value) { inviteeMemberId = value; }
    public Date getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Date value) { claimedAt = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
}
