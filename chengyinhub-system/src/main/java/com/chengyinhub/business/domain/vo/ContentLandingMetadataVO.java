package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.Date;
import java.util.List;

/**
 * M7 落地元信息的作者/运营读回投影。
 *
 * <p>{@code groupEligible/schoolEligible} 以 Boolean 返回且<b>无行时整块为 null</b>，
 * 让作者界面能区分「未填写」与「明确填写为否」——后者是作者主动声明，前者只是没填。
 * 这里不用 {@code false} 兜底，因为那会把「没填」显示成「不接待」。
 */
public class ContentLandingMetadataVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long topicId;

    private String sourceLocale;

    private String landingSummary;

    private Boolean groupEligible;

    private Boolean schoolEligible;

    private Integer groupMinPeople;

    private Integer groupMaxPeople;

    private Integer ageMin;

    private Integer ageMax;

    private String accessibilityNote;

    private Integer status;

    private Long rowVersion;

    private Date updateTime;

    /** 该作者已提交的译文(含 PENDING，作者需要看到自己未校对的稿)。 */
    private List<ContentLandingTranslationVO> translations;

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getSourceLocale() { return sourceLocale; }

    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }

    public String getLandingSummary() { return landingSummary; }

    public void setLandingSummary(String landingSummary) { this.landingSummary = landingSummary; }

    public Boolean getGroupEligible() { return groupEligible; }

    public void setGroupEligible(Boolean groupEligible) { this.groupEligible = groupEligible; }

    public Boolean getSchoolEligible() { return schoolEligible; }

    public void setSchoolEligible(Boolean schoolEligible) { this.schoolEligible = schoolEligible; }

    public Integer getGroupMinPeople() { return groupMinPeople; }

    public void setGroupMinPeople(Integer groupMinPeople) { this.groupMinPeople = groupMinPeople; }

    public Integer getGroupMaxPeople() { return groupMaxPeople; }

    public void setGroupMaxPeople(Integer groupMaxPeople) { this.groupMaxPeople = groupMaxPeople; }

    public Integer getAgeMin() { return ageMin; }

    public void setAgeMin(Integer ageMin) { this.ageMin = ageMin; }

    public Integer getAgeMax() { return ageMax; }

    public void setAgeMax(Integer ageMax) { this.ageMax = ageMax; }

    public String getAccessibilityNote() { return accessibilityNote; }

    public void setAccessibilityNote(String accessibilityNote) { this.accessibilityNote = accessibilityNote; }

    public Integer getStatus() { return status; }

    public void setStatus(Integer status) { this.status = status; }

    public Long getRowVersion() { return rowVersion; }

    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }

    public Date getUpdateTime() { return updateTime; }

    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    public List<ContentLandingTranslationVO> getTranslations() { return translations; }

    public void setTranslations(List<ContentLandingTranslationVO> translations) {
        this.translations = translations;
    }
}
