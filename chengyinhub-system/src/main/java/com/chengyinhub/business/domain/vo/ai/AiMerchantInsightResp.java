package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

/**
 * 商家店铺参谋 AI 解读响应(merchant_insight)。
 * suggestions.type 只允许三枚举 topic_coop|decor|content,跳转路由由前端按 type 写死,
 * 模型不产路由 —— 防编造不存在的跳转。
 *
 * @author chengyinhub
 */
public class AiMerchantInsightResp
{
    private String traceId;
    /** 一句翻译过的转化事实(Yelp 式「你只被发现了 1%」口径);lowSample 时被服务端固定模板覆盖 */
    private String summary;
    /** 一条经营机会(美团「洞察机会和问题」框架的机会侧) */
    private String opportunity;
    /** 一条经营问题(同上问题侧) */
    private String problem;
    private List<InsightAudience> audiences;
    private List<InsightSuggestion> suggestions;
    /** AI 从候选池挑的推荐主题(id ∈ candidates.recruitingTopics,越界由服务端丢弃) */
    private List<InsightPick> recommendedTopics;
    /** AI 从候选池挑的推荐联动商家(id ∈ candidates.nearbyMerchants,越界由服务端丢弃) */
    private List<InsightPick> recommendedPartners;

    public List<InsightPick> getRecommendedTopics() { return recommendedTopics; }
    public void setRecommendedTopics(List<InsightPick> recommendedTopics) { this.recommendedTopics = recommendedTopics; }
    public List<InsightPick> getRecommendedPartners() { return recommendedPartners; }
    public void setRecommendedPartners(List<InsightPick> recommendedPartners) { this.recommendedPartners = recommendedPartners; }

    /** AI 的一次挑选:候选 id + 推荐理由 */
    public static class InsightPick
    {
        private Long id;
        private String reason;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getOpportunity() { return opportunity; }
    public void setOpportunity(String opportunity) { this.opportunity = opportunity; }
    public String getProblem() { return problem; }
    public void setProblem(String problem) { this.problem = problem; }
    public List<InsightAudience> getAudiences() { return audiences; }
    public void setAudiences(List<InsightAudience> audiences) { this.audiences = audiences; }
    public List<InsightSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<InsightSuggestion> suggestions) { this.suggestions = suggestions; }

    /** 适合人群标签 */
    public static class InsightAudience
    {
        private String label;
        private String reason;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** 活动建议卡 */
    public static class InsightSuggestion
    {
        private String title;
        /** topic_coop | decor | content(服务端白名单校验,非法值整条丢弃) */
        private String type;
        private String timeSlot;
        private String audience;
        private String reason;
        /** 「依据:」引用的具体数字事实,让建议看起来是算出来的不是编的 */
        private String basis;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getTimeSlot() { return timeSlot; }
        public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }
    }
}
