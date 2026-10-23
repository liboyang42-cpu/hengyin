package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;

/** F3 套票发放 explore_bundle_grant:余额键 (registration, holder, chapter, grant_no)。 */
public class ExploreBundleGrant {
    private Long id;
    private Long bundleOrderId;
    private Long registrationId;
    private Long holderMemberId;
    private Long topicId;
    private Long chapterId;
    private Integer grantNo;
    private String grantType;
    private Long sourceGrantId;
    private Integer unitsTotal;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validFrom;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validTo;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBundleOrderId() { return bundleOrderId; }
    public void setBundleOrderId(Long bundleOrderId) { this.bundleOrderId = bundleOrderId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getHolderMemberId() { return holderMemberId; }
    public void setHolderMemberId(Long holderMemberId) { this.holderMemberId = holderMemberId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Integer getGrantNo() { return grantNo; }
    public void setGrantNo(Integer grantNo) { this.grantNo = grantNo; }
    public String getGrantType() { return grantType; }
    public void setGrantType(String grantType) { this.grantType = grantType; }
    public Long getSourceGrantId() { return sourceGrantId; }
    public void setSourceGrantId(Long sourceGrantId) { this.sourceGrantId = sourceGrantId; }
    public Integer getUnitsTotal() { return unitsTotal; }
    public void setUnitsTotal(Integer unitsTotal) { this.unitsTotal = unitsTotal; }
    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }
    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
