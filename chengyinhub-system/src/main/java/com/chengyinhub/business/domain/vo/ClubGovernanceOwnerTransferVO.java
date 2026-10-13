package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;

import java.util.Date;

/** 主理人转让回执白名单；不下发内部幂等键。 */
public final class ClubGovernanceOwnerTransferVO {
    private Long id;
    private Long clubId;
    private Long fromOwnerMemberId;
    private Long toOwnerMemberId;
    private String reason;
    private Date transferredAt;

    public static ClubGovernanceOwnerTransferVO from(ClubGovernanceOwnerTransfer source) {
        ClubGovernanceOwnerTransferVO vo = new ClubGovernanceOwnerTransferVO();
        vo.id = source.getId();
        vo.clubId = source.getClubId();
        vo.fromOwnerMemberId = source.getFromOwnerMemberId();
        vo.toOwnerMemberId = source.getToOwnerMemberId();
        vo.reason = source.getReason();
        vo.transferredAt = source.getTransferredAt();
        return vo;
    }

    public Long getId() { return id; }
    public Long getClubId() { return clubId; }
    public Long getFromOwnerMemberId() { return fromOwnerMemberId; }
    public Long getToOwnerMemberId() { return toOwnerMemberId; }
    public String getReason() { return reason; }
    public Date getTransferredAt() { return transferredAt; }
}
