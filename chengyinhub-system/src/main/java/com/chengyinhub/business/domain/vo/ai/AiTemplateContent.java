package com.chengyinhub.business.domain.vo.ai;

/**
 * AI 模板核心内容草稿 = 节点玩法文案 + 模板门面字段。
 * 不含完整发布 DTO 的人数、时长、素材、媒体与审核字段，不能直接作为发布请求。
 */
public class AiTemplateContent extends AiNodeContent
{
    /** 玩法标题(≤20字,像关卡名) */
    private String title;
    /** 玩法描述(≤30字,直接给爽点) */
    private String description;
    /** 配图提示:给商家/生图工具的封面画面描述,不是图片 URL */
    private String coverPrompt;
    /** 勋章名建议(有收藏感) */
    private String medalName;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverPrompt() { return coverPrompt; }
    public void setCoverPrompt(String coverPrompt) { this.coverPrompt = coverPrompt; }
    public String getMedalName() { return medalName; }
    public void setMedalName(String medalName) { this.medalName = medalName; }
}
