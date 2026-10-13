package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 章节商家招募状态 VO —— 登录商家及本主题原发布者可见；发布者只看聚合，不下发申请人身份。
 *
 * 白名单口径:只下发档位/名额/申请与生效数量,不下发任何商家身份与谈判金额
 * (perHeadFee/quotaTotal 是 offer 侧字段,永不进本 VO)。所有计数来自章节申请与生效供给，
 * 不再读取旧 cms_registration_merchant 节点报名记录。
 *
 * @author chengyinhub
 * @date 2026-07-21
 */
public class TopicTemplateRecruitVO {

    @Schema(description = "V1条款档位:TRAFFIC引流/PERK权益(章节 terms_mode,null 按 PERK)")
    private String termsMode;

    @Schema(description = "权益档要求的最低零售价值；null表示本章不设门槛")
    private BigDecimal perkMinValue;

    /**
     * 本章节允许的玩法验证方式(逗号分隔),空 = 不限。
     * ★必须下发给商家:边界是**招商时公示**的 —— 商家申请前看得到才叫公示,
     * 看不到就等于「做完了才被告知超标」,那正是这套设计要避免的。
     */
    private String allowedValidationMethods;

    /** 单点位探索值上限,空 = 不限。同样属于公示项。 */
    private Integer maxNodeXp;

    @Schema(description = "该章节商家坑位上限")
    private Integer maxMerchant;

    @Schema(description = "已预占承接名额的商家数：已通过申请与生效供给按商家去重")
    private Integer currentOfferCount;

    @Schema(description = "待审核申请数")
    private Integer pendingApplicationCount;

    @Schema(description = "已通过申请数")
    private Integer approvedApplicationCount;

    @Schema(description = "已生效供给数")
    private Integer activeOfferCount;

    @Schema(description = "剩余商家名额，null表示不限")
    private Integer remainingMerchantCount;

    @Schema(description = "派生展示状态：OPEN招募中 FULL已满 LOCKED已锁价 CLOSED招商期已关闭；未上架、非自由探索或未配置品类时整段不下发")
    private String state;

    @Schema(description = "是否仍可申请：显式开启、已上架、自由探索、已配置品类、招商期内、未锁价且尚有名额")
    private Boolean isOpen;

    // ====== getters/setters ======

    public String getTermsMode() { return termsMode; }
    public void setTermsMode(String termsMode) { this.termsMode = termsMode; }
    public String getAllowedValidationMethods() { return allowedValidationMethods; }
    public void setAllowedValidationMethods(String v) { this.allowedValidationMethods = v; }
    public Integer getMaxNodeXp() { return maxNodeXp; }
    public void setMaxNodeXp(Integer v) { this.maxNodeXp = v; }

    public BigDecimal getPerkMinValue() { return perkMinValue; }
    public void setPerkMinValue(BigDecimal perkMinValue) { this.perkMinValue = perkMinValue; }
    public Integer getMaxMerchant() { return maxMerchant; }
    public void setMaxMerchant(Integer maxMerchant) { this.maxMerchant = maxMerchant; }
    public Integer getCurrentOfferCount() { return currentOfferCount; }
    public void setCurrentOfferCount(Integer currentOfferCount) { this.currentOfferCount = currentOfferCount; }
    public Integer getPendingApplicationCount() { return pendingApplicationCount; }
    public void setPendingApplicationCount(Integer pendingApplicationCount) { this.pendingApplicationCount = pendingApplicationCount; }
    public Integer getApprovedApplicationCount() { return approvedApplicationCount; }
    public void setApprovedApplicationCount(Integer approvedApplicationCount) { this.approvedApplicationCount = approvedApplicationCount; }
    public Integer getActiveOfferCount() { return activeOfferCount; }
    public void setActiveOfferCount(Integer activeOfferCount) { this.activeOfferCount = activeOfferCount; }
    public Integer getRemainingMerchantCount() { return remainingMerchantCount; }
    public void setRemainingMerchantCount(Integer remainingMerchantCount) { this.remainingMerchantCount = remainingMerchantCount; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Boolean getIsOpen() { return isOpen; }
    public void setIsOpen(Boolean isOpen) { this.isOpen = isOpen; }
}
