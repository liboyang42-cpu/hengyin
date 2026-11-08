package com.chengyinhub.business.domain.dto;

/**
 * M7 公开落地译文写入请求。只允许三段公开摘要，不接受正文/答案字段 ——
 * 请求体里多出的字段在服务端被忽略，不会被存进任何表。
 *
 * <p>{@code translationState} 只接受 APPROVED / PENDING；未知值 400。
 */
public class ContentLandingTranslationSaveRequest {

    private Long topicId;

    /** BCP-47 语言标签；必须不是原文语言(原文不通过本表"翻译")。 */
    private String locale;

    /** 本条译文对应的原文语言；null 时取元信息的 sourceLocale，再缺省 zh-CN。 */
    private String sourceLocale;

    private Long expectedVersion;

    private String title;

    private String subtitle;

    private String summary;

    private String translationState;

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getLocale() { return locale; }

    public void setLocale(String locale) { this.locale = locale; }

    public String getSourceLocale() { return sourceLocale; }

    public void setSourceLocale(String sourceLocale) { this.sourceLocale = sourceLocale; }

    public Long getExpectedVersion() { return expectedVersion; }

    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; }

    public String getSubtitle() { return subtitle; }

    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

    public String getTranslationState() { return translationState; }

    public void setTranslationState(String translationState) { this.translationState = translationState; }
}
