package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsMemberTemplate;

public class MemberTemplateVO extends CmsMemberTemplate {
    private Integer originalPublishStatus;
    private Integer originalDelFlag;
    private String templateSource;
    private Long marketTemplateId;
    public Long getMarketTemplateId() { return marketTemplateId; }
    public void setMarketTemplateId(Long marketTemplateId) { this.marketTemplateId = marketTemplateId; }
    private Long marketStatus;
    public Long getMarketStatus() { return marketStatus; }
    public void setMarketStatus(Long marketStatus) { this.marketStatus = marketStatus; }
    private Long marketPublishStatus;
    public Long getMarketPublishStatus() { return marketPublishStatus; }
    public void setMarketPublishStatus(Long marketPublishStatus) { this.marketPublishStatus = marketPublishStatus; }
    private String marketReason;
    public String getMarketReason() { return marketReason; }
    public void setMarketReason(String marketReason) { this.marketReason = marketReason; }


    // Getter和Setter方法
    public Integer getOriginalPublishStatus() {
        return originalPublishStatus;
    }

    public void setOriginalPublishStatus(Integer originalPublishStatus) {
        this.originalPublishStatus = originalPublishStatus;
    }

    public Integer getOriginalDelFlag() {
        return originalDelFlag;
    }

    public void setOriginalDelFlag(Integer originalDelFlag) {
        this.originalDelFlag = originalDelFlag;
    }

    public String getTemplateSource() {
        return templateSource;
    }

    public void setTemplateSource(String templateSource) {
        this.templateSource = templateSource;
    }
}
