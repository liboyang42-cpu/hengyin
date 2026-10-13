package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** 圈层主题商家现成供给及专项声明快照。 */
public class MerchantCircleSupplyProfile implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long offerId;
    private Integer versionNo;
    private Integer current;
    private String supplyName;
    private String supplyKind;
    private BigDecimal regularPrice;
    private String actionType;
    private String actionValue;
    private String availableHours;
    private Integer minPeople;
    private Integer maxPeople;
    private Integer durationMinutes;
    private Integer splitBookingAllowed;
    private String optionalBenefit;
    private String benefitRules;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date checkedAt;
    private String merchantRules;
    private Integer workdayEveningCovered;
    private Integer singlePersonAllowed;
    private String declaredAgeRange;
    private String guardianMode;
    private Integer guardianPresentThroughout;
    private Integer merchantChildcareRequired;
    private String adultChildCapacity;
    private String priceUnit;
    private String restrictions;
    private String toolsAnimalsMaterials;
    private String incidentProcess;
    private String cancellationRule;
    private Integer personalPhotoAllowed;
    private String photoAllowedArea;
    private String tripodPolicy;
    private String lightingPolicy;
    private String photoDurationLimit;
    private String nightHours;
    private String lastEntry;
    private Integer alcoholInvolved;
    private Integer legalDrinkingAge;
    private String legalDrinkingAgeRule;
    private String alcoholWarning;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date eventEndAt;
    private Date createTime;
    private Date updateTime;

    /* 连表只读字段。 */
    private Long topicId;
    private Long chapterId;
    private Long merchantId;
    private String candidateCode;
    private String candidateName;
    private String circleRole;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer value) { this.versionNo = value; }
    public Integer getCurrent() { return current; }
    public void setCurrent(Integer value) { this.current = value; }
    public String getSupplyName() { return supplyName; }
    public void setSupplyName(String supplyName) { this.supplyName = supplyName; }
    public String getSupplyKind() { return supplyKind; }
    public void setSupplyKind(String supplyKind) { this.supplyKind = supplyKind; }
    public BigDecimal getRegularPrice() { return regularPrice; }
    public void setRegularPrice(BigDecimal regularPrice) { this.regularPrice = regularPrice; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getActionValue() { return actionValue; }
    public void setActionValue(String actionValue) { this.actionValue = actionValue; }
    public String getAvailableHours() { return availableHours; }
    public void setAvailableHours(String availableHours) { this.availableHours = availableHours; }
    public Integer getMinPeople() { return minPeople; }
    public void setMinPeople(Integer minPeople) { this.minPeople = minPeople; }
    public Integer getMaxPeople() { return maxPeople; }
    public void setMaxPeople(Integer maxPeople) { this.maxPeople = maxPeople; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer value) { this.durationMinutes = value; }
    public Integer getSplitBookingAllowed() { return splitBookingAllowed; }
    public void setSplitBookingAllowed(Integer value) { this.splitBookingAllowed = value; }
    public String getOptionalBenefit() { return optionalBenefit; }
    public void setOptionalBenefit(String value) { this.optionalBenefit = value; }
    public String getBenefitRules() { return benefitRules; }
    public void setBenefitRules(String value) { this.benefitRules = value; }
    public Date getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Date checkedAt) { this.checkedAt = checkedAt; }
    public String getMerchantRules() { return merchantRules; }
    public void setMerchantRules(String merchantRules) { this.merchantRules = merchantRules; }
    public Integer getWorkdayEveningCovered() { return workdayEveningCovered; }
    public void setWorkdayEveningCovered(Integer value) { this.workdayEveningCovered = value; }
    public Integer getSinglePersonAllowed() { return singlePersonAllowed; }
    public void setSinglePersonAllowed(Integer value) { this.singlePersonAllowed = value; }
    public String getDeclaredAgeRange() { return declaredAgeRange; }
    public void setDeclaredAgeRange(String value) { this.declaredAgeRange = value; }
    public String getGuardianMode() { return guardianMode; }
    public void setGuardianMode(String value) { this.guardianMode = value; }
    public Integer getGuardianPresentThroughout() { return guardianPresentThroughout; }
    public void setGuardianPresentThroughout(Integer value) { this.guardianPresentThroughout = value; }
    public Integer getMerchantChildcareRequired() { return merchantChildcareRequired; }
    public void setMerchantChildcareRequired(Integer value) { this.merchantChildcareRequired = value; }
    public String getAdultChildCapacity() { return adultChildCapacity; }
    public void setAdultChildCapacity(String value) { this.adultChildCapacity = value; }
    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String value) { this.priceUnit = value; }
    public String getRestrictions() { return restrictions; }
    public void setRestrictions(String value) { this.restrictions = value; }
    public String getToolsAnimalsMaterials() { return toolsAnimalsMaterials; }
    public void setToolsAnimalsMaterials(String value) { this.toolsAnimalsMaterials = value; }
    public String getIncidentProcess() { return incidentProcess; }
    public void setIncidentProcess(String value) { this.incidentProcess = value; }
    public String getCancellationRule() { return cancellationRule; }
    public void setCancellationRule(String value) { this.cancellationRule = value; }
    public Integer getPersonalPhotoAllowed() { return personalPhotoAllowed; }
    public void setPersonalPhotoAllowed(Integer value) { this.personalPhotoAllowed = value; }
    public String getPhotoAllowedArea() { return photoAllowedArea; }
    public void setPhotoAllowedArea(String value) { this.photoAllowedArea = value; }
    public String getTripodPolicy() { return tripodPolicy; }
    public void setTripodPolicy(String value) { this.tripodPolicy = value; }
    public String getLightingPolicy() { return lightingPolicy; }
    public void setLightingPolicy(String value) { this.lightingPolicy = value; }
    public String getPhotoDurationLimit() { return photoDurationLimit; }
    public void setPhotoDurationLimit(String value) { this.photoDurationLimit = value; }
    public String getNightHours() { return nightHours; }
    public void setNightHours(String value) { this.nightHours = value; }
    public String getLastEntry() { return lastEntry; }
    public void setLastEntry(String value) { this.lastEntry = value; }
    public Integer getAlcoholInvolved() { return alcoholInvolved; }
    public void setAlcoholInvolved(Integer value) { this.alcoholInvolved = value; }
    public Integer getLegalDrinkingAge() { return legalDrinkingAge; }
    public void setLegalDrinkingAge(Integer value) { this.legalDrinkingAge = value; }
    public String getLegalDrinkingAgeRule() { return legalDrinkingAgeRule; }
    public void setLegalDrinkingAgeRule(String value) { this.legalDrinkingAgeRule = value; }
    public String getAlcoholWarning() { return alcoholWarning; }
    public void setAlcoholWarning(String value) { this.alcoholWarning = value; }
    public Date getEventEndAt() { return eventEndAt; }
    public void setEventEndAt(Date value) { this.eventEndAt = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { this.createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { this.updateTime = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { this.topicId = value; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long value) { this.chapterId = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { this.merchantId = value; }
    public String getCandidateCode() { return candidateCode; }
    public void setCandidateCode(String value) { this.candidateCode = value; }
    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String value) { this.candidateName = value; }
    public String getCircleRole() { return circleRole; }
    public void setCircleRole(String value) { this.circleRole = value; }
}
