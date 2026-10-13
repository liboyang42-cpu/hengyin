package com.chengyinhub.business.domain;

import java.util.Date;

/** 主理人转让不可变回执；club.member_id 仍是当前唯一主理人的单一真源。 */
public class ClubGovernanceOwnerTransfer {
    private Long id;
    private Long clubId;
    private Long fromOwnerMemberId;
    private Long toOwnerMemberId;
    private String reason;
    private String requestId;
    private Date transferredAt;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getFromOwnerMemberId() { return fromOwnerMemberId; }
    public void setFromOwnerMemberId(Long fromOwnerMemberId) { this.fromOwnerMemberId = fromOwnerMemberId; }
    public Long getToOwnerMemberId() { return toOwnerMemberId; }
    public void setToOwnerMemberId(Long toOwnerMemberId) { this.toOwnerMemberId = toOwnerMemberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Date getTransferredAt() { return transferredAt; }
    public void setTransferredAt(Date transferredAt) { this.transferredAt = transferredAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
