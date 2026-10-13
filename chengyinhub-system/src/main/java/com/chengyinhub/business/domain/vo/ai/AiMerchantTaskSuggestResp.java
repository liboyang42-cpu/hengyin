package com.chengyinhub.business.domain.vo.ai;

import java.util.List;

/**
 * 商家任务建议响应。
 *
 * @author chengyinhub
 */
public class AiMerchantTaskSuggestResp
{
    private String traceId;
    private List<AiMerchantSuggestion> suggestions;

    /**
     * 解析失败标记。null = 正常(suggestions 为空只代表 AI 没给建议);
     * 非 null = AI 响应解析失败的用户可读文案,suggestions 不可信。
     * 与 safety_precheck 的 parse_error issue 同一语义、同一文案。
     */
    private String parseError;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
    public List<AiMerchantSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<AiMerchantSuggestion> suggestions) { this.suggestions = suggestions; }

    /** 单条建议 */
    public static class AiMerchantSuggestion
    {
        private String title;
        private String themeFit;
        private String taskRole;
        private String taskIdea;
        private String rewardIdea;
        private String coopScript;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getThemeFit() { return themeFit; }
        public void setThemeFit(String themeFit) { this.themeFit = themeFit; }
        public String getTaskRole() { return taskRole; }
        public void setTaskRole(String taskRole) { this.taskRole = taskRole; }
        public String getTaskIdea() { return taskIdea; }
        public void setTaskIdea(String taskIdea) { this.taskIdea = taskIdea; }
        public String getRewardIdea() { return rewardIdea; }
        public void setRewardIdea(String rewardIdea) { this.rewardIdea = rewardIdea; }
        public String getCoopScript() { return coopScript; }
        public void setCoopScript(String coopScript) { this.coopScript = coopScript; }
    }
}
