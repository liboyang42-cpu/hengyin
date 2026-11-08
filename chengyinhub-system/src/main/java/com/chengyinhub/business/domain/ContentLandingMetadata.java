package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M7 公开目录落地元信息(表 {@code content_landing_metadata},与 {@link CmsTopic} 1:1)。
 *
 * <p><b>它不是内容真源。</b>正文、答案、提示、发布快照仍只在既有表；本行只承载
 * 「对团体/学校能否承接、人数与年龄建议、无障碍说明、公开摘要覆盖」这类
 * 既有表无处安放的落地配置，供公开目录筛选与落地页使用。
 *
 * <p>为什么单独一张附表：{@code cms_topic} 的 {@code selectCmsTopicVo} 被 9 处 select 复用
 * (含事务内 {@code FOR UPDATE})，加列会放大每一条主题读的宽度，而这批字段只服务目录读面。
 * 与 {@link CmsTopicTemplateMeta} 的分工：那张表服务 {@code is_template=1} 的模板本体
 * (人群/承诺/证据等级)，本表服务已发布实例的对外可承接配置，两者不互相覆盖。
 *
 * <p>缺省语义：无本行 = 全部为「未声明」。{@code groupEligible/schoolEligible} 为 {@code false}
 * 不等于不可承接，读侧必须以 {@code null}(未确认)对外表达，不得显示成「不接待团体」。
 */
public class ContentLandingMetadata {

    private Long id;

    /** cms_topic.id；唯一。 */
    private Long topicId;

    /** 原文语言(即 cms_topic 的 name/subtitle/description 的语言)；缺省 zh-CN，不假装多语言已完成。 */
    private String sourceLocale;

    /** 作者填写的无剧透公开摘要；NULL = 读侧回退 {@code cms_topic.description} 截断。 */
    private String landingSummary;

    /** 1=团队/团建可承接；0=未声明。 */
    private Integer groupEligible;

    /** 1=学校/班级可承接；0=未声明。 */
    private Integer schoolEligible;

    private Integer groupMinPeople;

    private Integer groupMaxPeople;

    private Integer ageMin;

    private Integer ageMax;

    /** 无障碍/天气/场地限制说明；NULL = 未提供。 */
    private String accessibilityNote;

    /** 1=生效 0=停用；只影响本行元信息是否参与读侧，不影响主题自身公开与否。 */
    private Integer status;

    /** 乐观锁版本；作者写入必须回传它。 */
    private Long rowVersion;

    private Long updateBy;

    private Date createTime;

    private Date updateTime;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getSourceLocale() { return sourceLocale; }

    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }

    public String getLandingSummary() { return landingSummary; }

    public void setLandingSummary(String landingSummary) { this.landingSummary = landingSummary; }

    public Integer getGroupEligible() { return groupEligible; }

    public void setGroupEligible(Integer groupEligible) { this.groupEligible = groupEligible; }

    public Integer getSchoolEligible() { return schoolEligible; }

    public void setSchoolEligible(Integer schoolEligible) { this.schoolEligible = schoolEligible; }

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

    public Long getRowVersion() { return rowVersion; }

    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }

    public Long getUpdateBy() { return updateBy; }

    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }

    public Date getCreateTime() { return createTime; }

    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }

    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
