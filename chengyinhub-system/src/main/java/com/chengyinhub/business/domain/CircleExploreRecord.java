package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/** 玩家自助记录；不表示商家到店、预约、核销或成交。 */
public class CircleExploreRecord implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long sessionId;
    private Long memberId;
    private Long offerId;
    private Long supplyProfileId;
    private String note;
    private Date recordedAt;
    private Long merchantId;
    private String supplyName;
    private String candidateCode;
    private String candidateName;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { this.sessionId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { this.memberId = value; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long value) { this.offerId = value; }
    public Long getSupplyProfileId() { return supplyProfileId; }
    public void setSupplyProfileId(Long value) { this.supplyProfileId = value; }
    public String getNote() { return note; }
    public void setNote(String value) { this.note = value; }
    public Date getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Date value) { this.recordedAt = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { this.merchantId = value; }
    public String getSupplyName() { return supplyName; }
    public void setSupplyName(String value) { this.supplyName = value; }
    public String getCandidateCode() { return candidateCode; }
    public void setCandidateCode(String value) { this.candidateCode = value; }
    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String value) { this.candidateName = value; }
}
