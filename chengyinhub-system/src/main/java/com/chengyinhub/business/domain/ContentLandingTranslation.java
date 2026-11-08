package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M7 公开落地文案译文(表 {@code content_landing_translation})。
 *
 * <p><b>只承载公开摘要三段：title / subtitle / summary。</b>正文 blocks、答案、提示、
 * 角色私密线索与发布快照都不在这里 —— 换语言不能产生第二份正文真源，
 * 也不能因为本行存在就把未翻译的玩法内容标成已支持。
 *
 * <p>{@code translationState} 只用两值：
 * <ul>
 *   <li>{@code APPROVED} —— 可用于对外返回；</li>
 *   <li>{@code PENDING} —— 待校对；读侧<b>不</b>当作已完成翻译，仍按缺译回退。</li>
 * </ul>
 *
 * <p>{@code sourceLocale} 记录本条译文对应的原文语言。原文(zh-CN)改动后，旧译文应被标记待更新：
 * 读侧不能靠 {@code updateTime} 比较自动判定，需作者/运营显式改回 PENDING；
 * 本类不提供任何猜测定级。
 */
public class ContentLandingTranslation {

    private Long id;

    private Long topicId;

    /** BCP-47 语言标签，如 en / zh-CN / ja；读侧统一小写后比较。 */
    private String locale;

    /** 本条译文对应的原文语言。 */
    private String sourceLocale;

    private String title;

    private String subtitle;

    private String summary;

    /** APPROVED / PENDING，见类注释。 */
    private String translationState;

    private Long rowVersion;

    private Long updateBy;

    private Date createTime;

    private Date updateTime;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getLocale() { return locale; }

    public void setLocale(String locale) { this.locale = locale; }

    public String getSourceLocale() { return sourceLocale; }

    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public String getSubtitle() { return subtitle; }

    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

    public String getTranslationState() { return translationState; }

    public void setTranslationState(String translationState) { this.translationState = translationState; }

    public Long getRowVersion() { return rowVersion; }

    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }

    public Long getUpdateBy() { return updateBy; }

    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }

    public Date getCreateTime() { return createTime; }

    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }

    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
