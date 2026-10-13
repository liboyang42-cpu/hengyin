package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubGovernanceBan;

import java.util.Date;

/** 小程序治理页白名单投影；不下发内部幂等键与操作人账号。 */
public final class ClubGovernanceBanVO {
    private Long id;
    private Long clubId;
    private Long targetMemberId;
    private String targetNickname;
    private String targetAvatar;
    private String status;
    private String sourceType;
    private String banReason;
    private Date expiresAt;
    private Date bannedAt;
    private Date unbannedAt;
    private String unbanReason;
    private Integer version;

    public static ClubGovernanceBanVO from(ClubGovernanceBan source) {
        ClubGovernanceBanVO vo = new ClubGovernanceBanVO();
        vo.id = source.getId();
        vo.clubId = source.getClubId();
        vo.targetMemberId = source.getTargetMemberId();
        vo.targetNickname = source.getTargetNickname();
        vo.targetAvatar = source.getTargetAvatar();
        vo.status = source.getStatus();
        vo.sourceType = source.getSourceType();
        vo.banReason = source.getBanReason();
        vo.expiresAt = source.getExpiresAt();
        vo.bannedAt = source.getBannedAt();
        vo.unbannedAt = source.getUnbannedAt();
        vo.unbanReason = source.getUnbanReason();
        vo.version = source.getVersion();
        return vo;
    }

    public Long getId() { return id; }
    public Long getClubId() { return clubId; }
    public Long getTargetMemberId() { return targetMemberId; }
    public String getTargetNickname() { return targetNickname; }
    public String getTargetAvatar() { return targetAvatar; }
    public String getStatus() { return status; }
    public String getSourceType() { return sourceType; }
    public String getBanReason() { return banReason; }
    public Date getExpiresAt() { return expiresAt; }
    public Date getBannedAt() { return bannedAt; }
    public Date getUnbannedAt() { return unbannedAt; }
    public String getUnbanReason() { return unbanReason; }
    public Integer getVersion() { return version; }
}
