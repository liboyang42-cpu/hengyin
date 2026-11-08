package com.chengyinhub.business.domain.vo;

import java.util.Date;

/** M7 落地译文读回投影(作者视角，含 PENDING 稿)。 */
public class ContentLandingTranslationVO {

    private String locale;

    private String sourceLocale;

    private String title;

    private String subtitle;

    private String summary;

    private String translationState;

    private Long rowVersion;

    private Date updateTime;

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

    public Date getUpdateTime() { return updateTime; }

    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
