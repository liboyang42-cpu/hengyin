package com.chengyinhub.business.domain;

/**
 * 主题模板详情页元数据(章节级)—— 07-23 指南 P0 字段字典本轮收窄范围。
 *
 * <p>与 {@link CmsTopicChapter} 1:1(库里 uk_chapter_meta_chapter 保证)。
 * 分表而不是给 cms_topic_chapter 加 13 列:那张表在招商/供给/结算链路上被反复读写,
 * 而这批字段纯展示 —— 分开后「展示元数据缺失」不可能影响招商闸的判断。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class CmsTopicChapterTemplateMeta {

    private Long id;

    /** 章节 id。 */
    private Long chapterId;

    /** 主题 id;冗余一列供按主题批量拉取,避免 N+1。 */
    private Long topicId;

    /** 本章节需要的商家角色与职责描述。 */
    private String roleNeed;

    /** 排他:HARD 硬排他 / SOFT 软排他 / NONE 不限。 */
    private String exclusivityMode;

    /** 排他区域。 */
    private String exclusivityScope;

    /** 排他期限。 */
    private String exclusivityPeriod;

    /** 排他原因。 */
    private String exclusivityReason;

    /** 准备工时(小时)。 */
    private java.math.BigDecimal effortPrepHours;

    /** 每场店员投入(分钟)。 */
    private Integer effortStaffMinutes;

    /** 场地要求。 */
    private String effortVenue;

    /** 物料要求。 */
    private String effortMaterials;

    /** 人工批次每批人数。 */
    private Integer batchSize;

    /** 人工批次间隔(分钟)。 */
    private Integer batchIntervalMinutes;

    /** 最大同时接待人数。 */
    private Integer effortMaxConcurrent;

    /** 可用时段。 */
    private String effortAvailableSlots;

    /** 是否需预约:1 是 0 否。 */
    private Integer effortNeedsBooking;

    /** 取消代价。 */
    private String effortCancelCost;

    /** 资质要求。 */
    private String effortQualifications;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getRoleNeed() { return roleNeed; }
    public void setRoleNeed(String roleNeed) { this.roleNeed = roleNeed; }

    public String getExclusivityMode() { return exclusivityMode; }
    public void setExclusivityMode(String exclusivityMode) { this.exclusivityMode = exclusivityMode; }

    public String getExclusivityScope() { return exclusivityScope; }
    public void setExclusivityScope(String exclusivityScope) { this.exclusivityScope = exclusivityScope; }

    public String getExclusivityPeriod() { return exclusivityPeriod; }
    public void setExclusivityPeriod(String exclusivityPeriod) { this.exclusivityPeriod = exclusivityPeriod; }

    public String getExclusivityReason() { return exclusivityReason; }
    public void setExclusivityReason(String exclusivityReason) { this.exclusivityReason = exclusivityReason; }

    public java.math.BigDecimal getEffortPrepHours() { return effortPrepHours; }
    public void setEffortPrepHours(java.math.BigDecimal effortPrepHours) { this.effortPrepHours = effortPrepHours; }

    public Integer getEffortStaffMinutes() { return effortStaffMinutes; }
    public void setEffortStaffMinutes(Integer effortStaffMinutes) { this.effortStaffMinutes = effortStaffMinutes; }

    public String getEffortVenue() { return effortVenue; }
    public void setEffortVenue(String effortVenue) { this.effortVenue = effortVenue; }

    public String getEffortMaterials() { return effortMaterials; }
    public void setEffortMaterials(String effortMaterials) { this.effortMaterials = effortMaterials; }

    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }

    public Integer getBatchIntervalMinutes() { return batchIntervalMinutes; }
    public void setBatchIntervalMinutes(Integer batchIntervalMinutes) { this.batchIntervalMinutes = batchIntervalMinutes; }

    public Integer getEffortMaxConcurrent() { return effortMaxConcurrent; }
    public void setEffortMaxConcurrent(Integer effortMaxConcurrent) { this.effortMaxConcurrent = effortMaxConcurrent; }

    public String getEffortAvailableSlots() { return effortAvailableSlots; }
    public void setEffortAvailableSlots(String effortAvailableSlots) { this.effortAvailableSlots = effortAvailableSlots; }

    public Integer getEffortNeedsBooking() { return effortNeedsBooking; }
    public void setEffortNeedsBooking(Integer effortNeedsBooking) { this.effortNeedsBooking = effortNeedsBooking; }

    public String getEffortCancelCost() { return effortCancelCost; }
    public void setEffortCancelCost(String effortCancelCost) { this.effortCancelCost = effortCancelCost; }

    public String getEffortQualifications() { return effortQualifications; }
    public void setEffortQualifications(String effortQualifications) { this.effortQualifications = effortQualifications; }
}
