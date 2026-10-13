package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;

import java.util.Date;

/** 俱乐部成员封禁留痕；有效性由 status 与 expiresAt 共同决定。 */
public class ClubGovernanceBan extends BaseEntity {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_UNBANNED = "UNBANNED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String SOURCE_CLUB = "CLUB";
    public static final String SOURCE_PLATFORM = "PLATFORM";

    private Long id;
    private Long clubId;
    private Long targetMemberId;
    private String status;
    private String sourceType;
    private String banReason;
    private Date expiresAt;
    private Long bannedByMemberId;
    private Date bannedAt;
    private Long unbannedByMemberId;
    private Date unbannedAt;
    private String unbanReason;
    private String banRequestId;
    private String unbanRequestId;
    private Integer version;
    private String targetNickname;
    private String targetAvatar;
    /** 非持久：封禁同步结束付费权益后的安全回执；不表示已退款。 */
    private ClubMembershipAccessEndResultVO membershipAccessEnd;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getTargetMemberId() { return targetMemberId; }
    public void setTargetMemberId(Long targetMemberId) { this.targetMemberId = targetMemberId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getBanReason() { return banReason; }
    public void setBanReason(String banReason) { this.banReason = banReason; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Long getBannedByMemberId() { return bannedByMemberId; }
    public void setBannedByMemberId(Long bannedByMemberId) { this.bannedByMemberId = bannedByMemberId; }
    public Date getBannedAt() { return bannedAt; }
    public void setBannedAt(Date bannedAt) { this.bannedAt = bannedAt; }
    public Long getUnbannedByMemberId() { return unbannedByMemberId; }
    public void setUnbannedByMemberId(Long unbannedByMemberId) { this.unbannedByMemberId = unbannedByMemberId; }
    public Date getUnbannedAt() { return unbannedAt; }
    public void setUnbannedAt(Date unbannedAt) { this.unbannedAt = unbannedAt; }
    public String getUnbanReason() { return unbanReason; }
    public void setUnbanReason(String unbanReason) { this.unbanReason = unbanReason; }
    public String getBanRequestId() { return banRequestId; }
    public void setBanRequestId(String banRequestId) { this.banRequestId = banRequestId; }
    public String getUnbanRequestId() { return unbanRequestId; }
    public void setUnbanRequestId(String unbanRequestId) { this.unbanRequestId = unbanRequestId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getTargetNickname() { return targetNickname; }
    public void setTargetNickname(String targetNickname) { this.targetNickname = targetNickname; }
    public String getTargetAvatar() { return targetAvatar; }
    public void setTargetAvatar(String targetAvatar) { this.targetAvatar = targetAvatar; }
    public ClubMembershipAccessEndResultVO getMembershipAccessEnd() { return membershipAccessEnd; }
    public void setMembershipAccessEnd(ClubMembershipAccessEndResultVO membershipAccessEnd) {
        this.membershipAccessEnd = membershipAccessEnd;
    }
}
