package com.chengyinhub.business.domain.dto;

/**
 * M7 落地元信息写入请求。写入者必须是该主题的发布者本人(服务端校验 owner)，或后台有权限角色。
 *
 * <p>{@code expectedVersion} 是乐观锁：新建用 0；更新必须回传读到的 {@code rowVersion}。
 * 同一份 payload 重试若版本已推进会得到 409 而不是静默覆盖 —— 幂等靠「自然键 + 版本」，
 * 不靠忽略冲突。
 */
public class ContentLandingMetadataSaveRequest {

    private Long topicId;

    private Long expectedVersion;

    /** 公开无剧透摘要，最长 500。 */
    private String landingSummary;

    /** 显式声明；null = 不改/未声明。 */
    private Boolean groupEligible;

    private Boolean schoolEligible;

    private Integer groupMinPeople;

    private Integer groupMaxPeople;

    private Integer ageMin;

    private Integer ageMax;

    private String accessibilityNote;

    /** 1 生效 / 0 停用；null 视为 1。 */
    private Integer status;

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getExpectedVersion() { return expectedVersion; }

    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getLandingSummary() { return landingSummary; }

    public void setLandingSummary(String landingSummary) { this.landingSummary = landingSummary; }

    public Boolean getGroupEligible() { return groupEligible; }

    public void setGroupEligible(Boolean groupEligible) { this.groupEligible = groupEligible; }

    public Boolean getSchoolEligible() { return schoolEligible; }

    public void setSchoolEligible(Boolean schoolEligible) { this.schoolEligible = schoolEligible; }

    public Integer getGroupMinPeople() { return groupMinPeople; }

    public void setGroupMinPeople(Integer groupMinPeople) { this.groupMinPeople = groupMinPeople; }

    public Integer getGroupMaxPeople() { return groupMaxPeople; }

    public void setGroupMaxPeople(Integer groupMaxPeople) { this.groupMaxPeople = groupMaxPeople; }

    public Integer getAgeMin() { return ageMin; }

    public void setAgeMin(Integer ageMin) { this.ageMin = ageMin; }

    public Integer getAgeMax() { return ageMax; }

    public void setAgeMax(Integer ageMax) { this.ageMax = ageMax; }

    public String getAccessibilityNote() { return accessibilityNote; }

    public void setAccessibilityNote(String accessibilityNote) { this.accessibilityNote = accessibilityNote; }

    public Integer getStatus() { return status; }

    public void setStatus(Integer status) { this.status = status; }
}
