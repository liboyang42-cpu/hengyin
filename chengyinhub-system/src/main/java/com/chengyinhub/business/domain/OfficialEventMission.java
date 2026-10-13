package com.chengyinhub.business.domain;

import java.util.Date;

/** V2 官方活动玩家任务合同。 */
public class OfficialEventMission {
    public static final String TYPE_ROAM_POI_ARRIVAL = "ROAM_POI_ARRIVAL";
    public static final String TYPE_EVENT_POINT_ARRIVAL = "EVENT_POINT_ARRIVAL";
    public static final String TYPE_THEME_VERIFIED_FINISH = "THEME_VERIFIED_FINISH";
    public static final String TYPE_ROAM_SESSION_AREA = "ROAM_SESSION_AREA";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private Long id;
    private Long eventId;
    private Long bindingId;
    /** 制作台保存时使用的稳定绑定引用；持久化仍以 bindingId 为准。 */
    private String bindingCode;
    private String missionCode;
    private String missionType;
    private String title;
    private String shortDescription;
    private String ruleJson;
    /**
     * @deprecated 「顺序与前置」能力已按裁决表 §1.5 的漫游免费层红线整组作废：运行期求值、
     *     发布期校验、后台输入三处均已移除，本字段只剩存量数据，<b>不得再有任何消费方</b>。
     *     列保留是为了不销毁历史行；重新接线由 {@code chengyinhub-xcx/scripts/roam-redline-gate.js}
     *     的 R3 拦住（{@code OfficialEventV2ServiceImpl} 在其扫描面内，出现 prerequisite 即红）。
     */
    @Deprecated
    private String prerequisiteJson;
    private String ruleVersion;
    private Integer requiredCount;
    private Integer visibility;
    private String status;
    private Integer sort;
    private Date availableStart;
    private Date availableEnd;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }
    public String getBindingCode() { return bindingCode; }
    public void setBindingCode(String bindingCode) { this.bindingCode = bindingCode; }
    public String getMissionCode() { return missionCode; }
    public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
    public String getMissionType() { return missionType; }
    public void setMissionType(String missionType) { this.missionType = missionType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }
    public String getRuleJson() { return ruleJson; }
    public void setRuleJson(String ruleJson) { this.ruleJson = ruleJson; }
    public String getPrerequisiteJson() { return prerequisiteJson; }
    public void setPrerequisiteJson(String prerequisiteJson) { this.prerequisiteJson = prerequisiteJson; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public Integer getRequiredCount() { return requiredCount; }
    public void setRequiredCount(Integer requiredCount) { this.requiredCount = requiredCount; }
    public Integer getVisibility() { return visibility; }
    public void setVisibility(Integer visibility) { this.visibility = visibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Date getAvailableStart() { return availableStart; }
    public void setAvailableStart(Date availableStart) { this.availableStart = availableStart; }
    public Date getAvailableEnd() { return availableEnd; }
    public void setAvailableEnd(Date availableEnd) { this.availableEnd = availableEnd; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
