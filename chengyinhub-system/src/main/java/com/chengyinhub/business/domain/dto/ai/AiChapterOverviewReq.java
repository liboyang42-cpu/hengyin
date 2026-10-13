package com.chengyinhub.business.domain.dto.ai;

/**
 * 章节概述请求:为单个章节生成一段整体语境概述(只写这一个字段)。
 */
public class AiChapterOverviewReq
{
    /** 章节名 */
    private String chapterName;
    /** 用户一句话想法/侧重(可空) */
    private String idea;
    /** 主题背景(主题名/简介),给 AI 语境用 */
    private String themeContext;

    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }
    public String getThemeContext() { return themeContext; }
    public void setThemeContext(String themeContext) { this.themeContext = themeContext; }
}
