package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 官方活动(可配置活动引擎)。分类=预设,一切皆开关皆可配。
 * 设计真源:vault《Questify官方活动系统_产品设计报告_2026-07-08》§5.2 / §8.1
 *
 * @author cy
 * @date 2026-07-08
 */
public class OfficialEvent
{
    private Long id;
    private String title;
    private String subtitle;
    private String category;        // city_light/custom/festival/brand/challenge/hidden/community/merchant_hunt
    private String coverImg;
    private String story;
    private String city;            // 城市名关键词;NULL=全国
    private Date signupStart;
    private Date activityStart;
    private Date activityEnd;
    private Date settleTime;
    private Integer signupLimit;    // 0=不限
    private String contentJson;     // {"play_type":..,"topic_ids":[..],"poi_ids":[..]}
    private Integer collectiveEnabled;
    private String collectiveMetric; // light_count/signup_count/complete_count
    private Integer collectiveThreshold;
    private Integer leaderboardEnabled;
    private String rewardJson;      // {"instant":..,"milestone":..,"settle":..,"collective":..,"limit":N}
    private String merchantIds;     // 逗号分隔
    private String brandLogo;
    private Integer bannerEnabled;
    private Integer sort;
    private Integer status;         // 0草稿 1已排期 2报名中 3进行中 4待结算 5已结束 6已复盘 9已下线
    private Long senderAccount;
    /** V2 草稿创建身份域；UMS_MEMBER 才能按 senderAccount 做会员归属校验。 */
    private String creatorIdentitySource;
    /**
     * 合同版本。1=历史 content_json/progress 活动；2=绑定、任务、证据与承接真源活动。
     * V2 永不允许旧的 progress 完成入口写入。
     */
    private Integer contractVersion;
    /** SELF_RUN / OPTIONAL_PARTNERS / REQUIRED_FULFILLMENT。 */
    private String fulfillmentPolicy;
    /** NORMAL / PAUSED，暂停是覆盖生命周期状态的独立控制层。 */
    private String controlState;
    private Integer pausedFromStatus;
    private Date pausedAt;
    private Long pausedBy;
    private String pausedReason;
    /** 暂停影响范围/人数；仅记录本场风险影响，不复制 PII。 */
    private String pauseImpactScope;
    /** 暂停期间的替代安排。 */
    private String pauseAlternativePlan;
    /** 通知策略及当前可回读状态（PENDING / NOT_REQUIRED）。 */
    private String pauseNotificationPlan;
    private String pauseNotificationStatus;
    private String resumeNote;
    private String createBy;
    private Date createTime;
    private Date updateTime;

    // 状态码
    public static final int ST_DRAFT = 0;
    public static final int ST_SCHEDULED = 1;
    public static final int ST_SIGNUP = 2;
    public static final int ST_LIVE = 3;
    public static final int ST_SETTLING = 4;
    public static final int ST_ENDED = 5;
    public static final int ST_REVIEWED = 6;
    public static final int ST_OFFLINE = 9;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }

    public String getStory() { return story; }
    public void setStory(String story) { this.story = story; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public Date getSignupStart() { return signupStart; }
    public void setSignupStart(Date signupStart) { this.signupStart = signupStart; }

    public Date getActivityStart() { return activityStart; }
    public void setActivityStart(Date activityStart) { this.activityStart = activityStart; }

    public Date getActivityEnd() { return activityEnd; }
    public void setActivityEnd(Date activityEnd) { this.activityEnd = activityEnd; }

    public Date getSettleTime() { return settleTime; }
    public void setSettleTime(Date settleTime) { this.settleTime = settleTime; }

    public Integer getSignupLimit() { return signupLimit; }
    public void setSignupLimit(Integer signupLimit) { this.signupLimit = signupLimit; }

    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }

    public Integer getCollectiveEnabled() { return collectiveEnabled; }
    public void setCollectiveEnabled(Integer collectiveEnabled) { this.collectiveEnabled = collectiveEnabled; }

    public String getCollectiveMetric() { return collectiveMetric; }
    public void setCollectiveMetric(String collectiveMetric) { this.collectiveMetric = collectiveMetric; }

    public Integer getCollectiveThreshold() { return collectiveThreshold; }
    public void setCollectiveThreshold(Integer collectiveThreshold) { this.collectiveThreshold = collectiveThreshold; }

    public Integer getLeaderboardEnabled() { return leaderboardEnabled; }
    public void setLeaderboardEnabled(Integer leaderboardEnabled) { this.leaderboardEnabled = leaderboardEnabled; }

    public String getRewardJson() { return rewardJson; }
    public void setRewardJson(String rewardJson) { this.rewardJson = rewardJson; }

    public String getMerchantIds() { return merchantIds; }
    public void setMerchantIds(String merchantIds) { this.merchantIds = merchantIds; }

    public String getBrandLogo() { return brandLogo; }
    public void setBrandLogo(String brandLogo) { this.brandLogo = brandLogo; }

    public Integer getBannerEnabled() { return bannerEnabled; }
    public void setBannerEnabled(Integer bannerEnabled) { this.bannerEnabled = bannerEnabled; }

    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getSenderAccount() { return senderAccount; }
    public void setSenderAccount(Long senderAccount) { this.senderAccount = senderAccount; }

    public String getCreatorIdentitySource() { return creatorIdentitySource; }
    public void setCreatorIdentitySource(String creatorIdentitySource) { this.creatorIdentitySource = creatorIdentitySource; }

    public Integer getContractVersion() { return contractVersion; }
    public void setContractVersion(Integer contractVersion) { this.contractVersion = contractVersion; }

    public String getFulfillmentPolicy() { return fulfillmentPolicy; }
    public void setFulfillmentPolicy(String fulfillmentPolicy) { this.fulfillmentPolicy = fulfillmentPolicy; }

    public String getControlState() { return controlState; }
    public void setControlState(String controlState) { this.controlState = controlState; }

    public Integer getPausedFromStatus() { return pausedFromStatus; }
    public void setPausedFromStatus(Integer pausedFromStatus) { this.pausedFromStatus = pausedFromStatus; }

    public Date getPausedAt() { return pausedAt; }
    public void setPausedAt(Date pausedAt) { this.pausedAt = pausedAt; }

    public Long getPausedBy() { return pausedBy; }
    public void setPausedBy(Long pausedBy) { this.pausedBy = pausedBy; }

    public String getPausedReason() { return pausedReason; }
    public void setPausedReason(String pausedReason) { this.pausedReason = pausedReason; }

    public String getPauseImpactScope() { return pauseImpactScope; }
    public void setPauseImpactScope(String pauseImpactScope) { this.pauseImpactScope = pauseImpactScope; }

    public String getPauseAlternativePlan() { return pauseAlternativePlan; }
    public void setPauseAlternativePlan(String pauseAlternativePlan) { this.pauseAlternativePlan = pauseAlternativePlan; }

    public String getPauseNotificationPlan() { return pauseNotificationPlan; }
    public void setPauseNotificationPlan(String pauseNotificationPlan) { this.pauseNotificationPlan = pauseNotificationPlan; }

    public String getPauseNotificationStatus() { return pauseNotificationStatus; }
    public void setPauseNotificationStatus(String pauseNotificationStatus) { this.pauseNotificationStatus = pauseNotificationStatus; }

    public String getResumeNote() { return resumeNote; }
    public void setResumeNote(String resumeNote) { this.resumeNote = resumeNote; }

    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
