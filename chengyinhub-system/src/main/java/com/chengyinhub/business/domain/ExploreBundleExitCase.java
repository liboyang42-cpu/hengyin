package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;

/** F3 套票商家退出处置 explore_bundle_exit_case(持有人决定,只插不改)。 */
public class ExploreBundleExitCase {
    private Long id;
    private Long unitId;
    private Long registrationId;
    private Long holderMemberId;
    private Long exitedOfferId;
    private Long reservationId;
    private String decision;
    private Long targetOfferId;
    private String idempotencyKey;
    private String requestFingerprint;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getHolderMemberId() { return holderMemberId; }
    public void setHolderMemberId(Long holderMemberId) { this.holderMemberId = holderMemberId; }
    public Long getExitedOfferId() { return exitedOfferId; }
    public void setExitedOfferId(Long exitedOfferId) { this.exitedOfferId = exitedOfferId; }
    public Long getReservationId() { return reservationId; }
    public void setReservationId(Long reservationId) { this.reservationId = reservationId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Long getTargetOfferId() { return targetOfferId; }
    public void setTargetOfferId(Long targetOfferId) { this.targetOfferId = targetOfferId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
