package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;

/** F3 套票单位 explore_bundle_unit:一单位一行一码,状态即持有人余额真源。 */
public class ExploreBundleUnit {
    private Long id;
    private Long grantId;
    private Long registrationId;
    private Long holderMemberId;
    private Long chapterId;
    private Integer unitSeq;
    private String unitCode;
    private BigDecimal unitValue;
    private String status;
    private Long redemptionId;
    private Long reservationId;
    private String statusReason;
    private Integer version;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
    /** 瞬态:所属发放的序号与有效期(join explore_bundle_grant 读回)。 */
    private Integer grantNo;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validFrom;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validTo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGrantId() { return grantId; }
    public void setGrantId(Long grantId) { this.grantId = grantId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getHolderMemberId() { return holderMemberId; }
    public void setHolderMemberId(Long holderMemberId) { this.holderMemberId = holderMemberId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Integer getUnitSeq() { return unitSeq; }
    public void setUnitSeq(Integer unitSeq) { this.unitSeq = unitSeq; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }
    public BigDecimal getUnitValue() { return unitValue; }
    public void setUnitValue(BigDecimal unitValue) { this.unitValue = unitValue; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }
    public Long getReservationId() { return reservationId; }
    public void setReservationId(Long reservationId) { this.reservationId = reservationId; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Integer getGrantNo() { return grantNo; }
    public void setGrantNo(Integer grantNo) { this.grantNo = grantNo; }
    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }
    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }
}
