package com.chengyinhub.business.domain.dto;

/** 暂停不是状态按钮：影响、替代与通知策略必须一并留档。 */
public class OfficialEventV2PauseRequest {
    private String reason;
    private String impactScope;
    private String alternativePlan;
    private String notificationPlan;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getImpactScope() { return impactScope; }
    public void setImpactScope(String impactScope) { this.impactScope = impactScope; }
    public String getAlternativePlan() { return alternativePlan; }
    public void setAlternativePlan(String alternativePlan) { this.alternativePlan = alternativePlan; }
    public String getNotificationPlan() { return notificationPlan; }
    public void setNotificationPlan(String notificationPlan) { this.notificationPlan = notificationPlan; }
}
