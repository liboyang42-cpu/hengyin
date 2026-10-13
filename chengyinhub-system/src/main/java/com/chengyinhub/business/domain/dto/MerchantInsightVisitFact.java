package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 商家店铺参谋:每 (entitlement, chapter) 一行的到店/核销事实。
 * checkinAt 取 player_node_progress.checkin_time,redeemedAt 取 redemption(ACTIVE).create_time。
 *
 * @author chengyinhub
 */
public class MerchantInsightVisitFact
{
    private Long registrationId;
    private Long memberId;
    private Date checkinAt;
    private Date redeemedAt;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Date getCheckinAt() { return checkinAt; }
    public void setCheckinAt(Date checkinAt) { this.checkinAt = checkinAt; }
    public Date getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Date redeemedAt) { this.redeemedAt = redeemedAt; }
}
