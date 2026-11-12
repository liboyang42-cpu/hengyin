package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 单条促销的资格判定结果(服务端算,计算器只消费)。
 *
 * <p>{@code sourceFactType/sourceFactId} 是判定所依据的<b>服务端事实</b>
 * (当前为 player_badge 通关记录 id),会随使用事实一起落库 —— 事后能复查
 * "这单凭什么给了这个优惠",而不是只有一句 eligible=true。
 */
public class MarketingEligibility {

    private boolean eligible;
    private String reasonCode;
    private Integer sourceFactType;
    private Long sourceFactId;
    private Date sourceFactTime;
    private Date windowEndsAt;

    public static MarketingEligibility denied(String reasonCode) {
        MarketingEligibility e = new MarketingEligibility();
        e.eligible = false;
        e.reasonCode = reasonCode;
        return e;
    }

    public static MarketingEligibility allowed(Integer sourceFactType, Long sourceFactId,
                                               Date sourceFactTime, Date windowEndsAt) {
        MarketingEligibility e = new MarketingEligibility();
        e.eligible = true;
        e.sourceFactType = sourceFactType;
        e.sourceFactId = sourceFactId;
        e.sourceFactTime = sourceFactTime;
        e.windowEndsAt = windowEndsAt;
        return e;
    }

    public boolean isEligible() { return eligible; }
    public void setEligible(boolean eligible) { this.eligible = eligible; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Integer getSourceFactType() { return sourceFactType; }
    public void setSourceFactType(Integer sourceFactType) { this.sourceFactType = sourceFactType; }

    public Long getSourceFactId() { return sourceFactId; }
    public void setSourceFactId(Long sourceFactId) { this.sourceFactId = sourceFactId; }

    public Date getSourceFactTime() { return sourceFactTime; }
    public void setSourceFactTime(Date sourceFactTime) { this.sourceFactTime = sourceFactTime; }

    public Date getWindowEndsAt() { return windowEndsAt; }
    public void setWindowEndsAt(Date windowEndsAt) { this.windowEndsAt = windowEndsAt; }
}
