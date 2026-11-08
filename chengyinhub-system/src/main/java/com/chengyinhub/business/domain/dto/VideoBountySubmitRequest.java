package com.chengyinhub.business.domain.dto;

/**
 * 作者投稿请求。
 *
 * <p>{@code originGroupKey} 表示"同一原视频";跨平台搬运同一作品时必须使用同一个组键,
 * 组内合计封顶。留空时服务端退化为"本条规范化视频键自成一组"(单平台)。
 *
 * @author chengyinhub
 */
public class VideoBountySubmitRequest
{
    private String campaignKey;
    private Long topicId;
    private String platform;
    private String sourceUrl;
    private String originGroupKey;

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getOriginGroupKey() { return originGroupKey; }
    public void setOriginGroupKey(String originGroupKey) { this.originGroupKey = originGroupKey; }
}
