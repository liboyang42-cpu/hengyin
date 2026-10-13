package com.chengyinhub.business.domain.vo;

import java.util.Date;

public class BadgeWallItemVO {
    private String badgeCode, badgeName, nameEn, statement, iconUrl, assetType, category, unlockHint;
    private boolean unlocked;
    private Date unlockTime;
    /** 服务端按同一解锁规则计算；规则暂不可观测时保持 null，禁止用 0 冒充。 */
    private Long currentValue;
    private Long targetValue;
    private Integer progressPercent;
    private Boolean nextMilestone;
    // getters/setters (全字段)
    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String v) { this.badgeCode = v; }
    public String getBadgeName() { return badgeName; }
    public void setBadgeName(String v) { this.badgeName = v; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String v) { this.nameEn = v; }
    public String getStatement() { return statement; }
    public void setStatement(String v) { this.statement = v; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String v) { this.iconUrl = v; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String v) { this.assetType = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getUnlockHint() { return unlockHint; }
    public void setUnlockHint(String v) { this.unlockHint = v; }
    public boolean isUnlocked() { return unlocked; }
    public void setUnlocked(boolean v) { this.unlocked = v; }
    public Date getUnlockTime() { return unlockTime; }
    public void setUnlockTime(Date v) { this.unlockTime = v; }
    public Long getCurrentValue() { return currentValue; }
    public void setCurrentValue(Long v) { this.currentValue = v; }
    public Long getTargetValue() { return targetValue; }
    public void setTargetValue(Long v) { this.targetValue = v; }
    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer v) { this.progressPercent = v; }
    public Boolean getNextMilestone() { return nextMilestone; }
    public void setNextMilestone(Boolean v) { this.nextMilestone = v; }
}
