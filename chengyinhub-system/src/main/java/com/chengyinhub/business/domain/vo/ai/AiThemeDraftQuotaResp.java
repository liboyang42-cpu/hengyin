package com.chengyinhub.business.domain.vo.ai;

/** 简单 AI 主题草稿的当日额度提示。 */
public class AiThemeDraftQuotaResp
{
    private boolean limited;
    private Integer limit;
    private Integer remaining;

    public boolean isLimited() { return limited; }
    public void setLimited(boolean limited) { this.limited = limited; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public Integer getRemaining() { return remaining; }
    public void setRemaining(Integer remaining) { this.remaining = remaining; }
}
