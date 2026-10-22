package com.chengyinhub.business.domain.vo.ai;

import com.alibaba.fastjson2.JSONObject;

/**
 * 商家店铺参谋整体响应:facts(纯 SQL 聚合,必出)+ ai(可为 null 的降级层)。
 * 整份对象按 merchantMemberId+当日 缓存到 Redis(仅当 ai 非空才缓存,失败不锁一天)。
 *
 * @author chengyinhub
 */
public class MerchantInsightResp
{
    /** 聚合事实包:window/sampleMembers/lowSample/checkin/crowd/supply,只含聚合统计 */
    private JSONObject facts;
    /** AI 解读;null = 降级态(失败/超时/配额耗尽) */
    private AiMerchantInsightResp ai;
    /** ai 为 null 时的用户可读原因 */
    private String aiError;
    /** 生成时刻 HH:mm(页面「生成于 HH:mm,次日更新」) */
    private String generatedAt;

    public JSONObject getFacts() { return facts; }
    public void setFacts(JSONObject facts) { this.facts = facts; }
    public AiMerchantInsightResp getAi() { return ai; }
    public void setAi(AiMerchantInsightResp ai) { this.ai = ai; }
    public String getAiError() { return aiError; }
    public void setAiError(String aiError) { this.aiError = aiError; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    /**
     * 店铺承接档案视图(configured/capacity/suitActivityTypes/demand),永远实时读不走缓存;
     * configured=false 时前端置顶「先配置店铺档案」引导。
     */
    private JSONObject profile;

    public JSONObject getProfile() { return profile; }
    public void setProfile(JSONObject profile) { this.profile = profile; }

    /** 推荐主题(真实招商中主题,AI 挑选+理由,AI 挂时候选兜底):{topicId,name,reason} */
    private java.util.List<JSONObject> recommendedTopics;
    /** 推荐联动商家(真实附近商家):{merchantId,name,category,distanceM,reason} */
    private java.util.List<JSONObject> recommendedPartners;

    public java.util.List<JSONObject> getRecommendedTopics() { return recommendedTopics; }
    public void setRecommendedTopics(java.util.List<JSONObject> recommendedTopics) { this.recommendedTopics = recommendedTopics; }
    public java.util.List<JSONObject> getRecommendedPartners() { return recommendedPartners; }
    public void setRecommendedPartners(java.util.List<JSONObject> recommendedPartners) { this.recommendedPartners = recommendedPartners; }

    /** 最近到店玩家(Blackbird 式单客行);只做页面展示,**不进 facts、不进 AI prompt** */
    private java.util.List<RecentVisitor> recentVisitors;

    public java.util.List<RecentVisitor> getRecentVisitors() { return recentVisitors; }
    public void setRecentVisitors(java.util.List<RecentVisitor> recentVisitors) { this.recentVisitors = recentVisitors; }

    /**
     * 主卡走势 {windows:[{days,visitors,checkins,redeems,prevVisitors,prevCheckins,prevRedeems,
     * daily:[{date,visitors,checkins,redeems}]}]},days ∈ 7/30/90;只做页面展示,不进 facts、不进 AI prompt
     */
    private JSONObject trends;

    public JSONObject getTrends() { return trends; }
    public void setTrends(JSONObject trends) { this.trends = trends; }

    /** 单客行:头像 + 第几次来 + 来自哪个主题 + 最近时间 */
    public static class RecentVisitor
    {
        private String nickname;
        private String avatar;
        /** 历史总到店次数(「第 N 次来」) */
        private Integer visitCount;
        /** 最近一次来自的主题名 */
        private String topicName;
        /** 最近到店时间,已格式化(MM-dd HH:mm) */
        private String lastAtText;

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public Integer getVisitCount() { return visitCount; }
        public void setVisitCount(Integer visitCount) { this.visitCount = visitCount; }
        public String getTopicName() { return topicName; }
        public void setTopicName(String topicName) { this.topicName = topicName; }
        public String getLastAtText() { return lastAtText; }
        public void setLastAtText(String lastAtText) { this.lastAtText = lastAtText; }
    }
}
