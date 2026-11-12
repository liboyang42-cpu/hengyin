package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsMemberTemplate;

public class MemberTemplateVO extends CmsMemberTemplate {
    private Integer originalStatus;
    private Integer originalDelFlag;
    private String templateSource;
    private Long marketTemplateId;
    public Long getMarketTemplateId() { return marketTemplateId; }
    public void setMarketTemplateId(Long marketTemplateId) { this.marketTemplateId = marketTemplateId; }
    private Long marketStatus;
    public Long getMarketStatus() { return marketStatus; }
    public void setMarketStatus(Long marketStatus) { this.marketStatus = marketStatus; }
    private String marketReason;
    public String getMarketReason() { return marketReason; }
    public void setMarketReason(String marketReason) { this.marketReason = marketReason; }


    // Getter和Setter方法
    public Integer getOriginalStatus() {
        return originalStatus;
    }

    public void setOriginalStatus(Integer originalStatus) {
        this.originalStatus = originalStatus;
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
