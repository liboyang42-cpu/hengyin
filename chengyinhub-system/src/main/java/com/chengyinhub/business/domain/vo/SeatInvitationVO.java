package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.RegistrationSeatInvitation;

import java.util.Date;

/**
 * 单个内含席位的邀请视图。默认不含明文 token；只在「签发/同 requestId 幂等重放」的返回里
 * 由服务端重算后放一次，避免把秘密当成可反复读取的 UI 字段。
 */
public class SeatInvitationVO {

    private Long invitationId;
    private Long registrationId;
    private Integer seatNo;
    private Integer generation;
    private Integer status;
    private String statusLabel;
    private Date expiresAt;
    private Date claimedAt;
    private Long inviteeMemberId;
    private Long serverNow;
    private Boolean originalPaid;
    private Boolean playEligible;
    private Boolean replayed;
    /** 仅签发响应携带；查询/状态/历史接口恒为 null。 */
    private String token;

    public static SeatInvitationVO of(RegistrationSeatInvitation row, Date now, boolean originalPaid) {
        SeatInvitationVO vo = new SeatInvitationVO();
        vo.invitationId = row.getId();
        vo.registrationId = row.getRegistrationId();
        vo.seatNo = row.getSeatNo();
        vo.generation = row.getGeneration();
        int effective = effectiveStatus(row, now);
        vo.status = Integer.valueOf(effective);
        vo.statusLabel = label(effective);
        vo.expiresAt = row.getExpiresAt();
        vo.claimedAt = row.getClaimedAt();
        vo.inviteeMemberId = row.getInviteeMemberId();
        vo.serverNow = now == null ? null : Long.valueOf(now.getTime());
        vo.originalPaid = Boolean.valueOf(originalPaid);
        vo.playEligible = Boolean.valueOf(originalPaid
                && effective == RegistrationSeatInvitation.STATUS_CLAIMED);
        vo.replayed = Boolean.FALSE;
        return vo;
    }

    /**
     * 只读投影：待领取且已到服务端过期时刻 ⇒ 视为 EXPIRED。GET 不写库；
     * 真正的状态迁移只在带原单行锁的邀请/领取/撤销/释放事务里发生。
     */
    public static int effectiveStatus(RegistrationSeatInvitation row, Date now) {
        if (row == null || row.getStatus() == null) return RegistrationSeatInvitation.STATUS_EXPIRED;
        if (Integer.valueOf(RegistrationSeatInvitation.STATUS_PENDING).equals(row.getStatus())
                && row.getExpiresAt() != null && now != null && !row.getExpiresAt().after(now)) {
            return RegistrationSeatInvitation.STATUS_EXPIRED;
        }
        return row.getStatus();
    }

    public static String label(Integer status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case RegistrationSeatInvitation.STATUS_PENDING -> "PENDING";
            case RegistrationSeatInvitation.STATUS_CLAIMED -> "CLAIMED";
            case RegistrationSeatInvitation.STATUS_CANCELLED -> "CANCELLED";
            case RegistrationSeatInvitation.STATUS_EXPIRED -> "EXPIRED";
            case RegistrationSeatInvitation.STATUS_RELEASED -> "RELEASED";
            default -> "UNKNOWN";
        };
    }

    public Long getInvitationId() { return invitationId; }
    public void setInvitationId(Long value) { invitationId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer value) { seatNo = value; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer value) { generation = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String value) { statusLabel = value; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date value) { expiresAt = value; }
    public Date getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Date value) { claimedAt = value; }
    public Long getInviteeMemberId() { return inviteeMemberId; }
    public void setInviteeMemberId(Long value) { inviteeMemberId = value; }
    public Long getServerNow() { return serverNow; }
    public void setServerNow(Long value) { serverNow = value; }
    public Boolean getOriginalPaid() { return originalPaid; }
    public void setOriginalPaid(Boolean value) { originalPaid = value; }
    public Boolean getPlayEligible() { return playEligible; }
    public void setPlayEligible(Boolean value) { playEligible = value; }
    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean value) { replayed = value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value; }
}
