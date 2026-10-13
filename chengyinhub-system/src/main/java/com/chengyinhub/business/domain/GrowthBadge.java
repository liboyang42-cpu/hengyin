package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P3 成长徽章 growth_badge。
 */
public class GrowthBadge extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String badgeCode;
    private String badgeName;
    private String badgeDesc;
    private String iconUrl;
    private String unlockRule;
    private String status;
    private Integer sortNo;
    /** 俱乐部ID(俱乐部自建勋章归属;NULL=平台全局) */
    private Long clubId;
    private String assetType;
    /** 勋章样式:glow=二维发光 / enamel=3D珐琅 */
    private String badgeStyle;
    private String category;
    private String triggerEvent;
    private String unlockRuleJson;
    private Integer ruleVersion;
    private String rarity;
    private String badgeFamily;
    private String nameEn;
    private String statement;
    /** 锁定态可理解的下一步(PRD 8.3);未获得时展示,已获得时前端不用 */
    private String unlockHint;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String badgeCode) { this.badgeCode = badgeCode; }
    public String getBadgeName() { return badgeName; }
    public void setBadgeName(String badgeName) { this.badgeName = badgeName; }
    public String getBadgeDesc() { return badgeDesc; }
    public void setBadgeDesc(String badgeDesc) { this.badgeDesc = badgeDesc; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    public String getUnlockRule() { return unlockRule; }
    public void setUnlockRule(String unlockRule) { this.unlockRule = unlockRule; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getBadgeStyle() { return badgeStyle; }
    public void setBadgeStyle(String badgeStyle) { this.badgeStyle = badgeStyle; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public String getUnlockRuleJson() { return unlockRuleJson; }
    public void setUnlockRuleJson(String unlockRuleJson) { this.unlockRuleJson = unlockRuleJson; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getRarity() { return rarity; }
    public void setRarity(String rarity) { this.rarity = rarity; }
    public String getBadgeFamily() { return badgeFamily; }
    public void setBadgeFamily(String badgeFamily) { this.badgeFamily = badgeFamily; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
    public String getUnlockHint() { return unlockHint; }
    public void setUnlockHint(String unlockHint) { this.unlockHint = unlockHint; }
}
