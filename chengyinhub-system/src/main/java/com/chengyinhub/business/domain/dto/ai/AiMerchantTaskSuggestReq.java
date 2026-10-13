package com.chengyinhub.business.domain.dto.ai;

/**
 * 商家任务建议请求: 商户想知道自己的店适合参与哪些主题/能变成什么任务节点。
 *
 * @author chengyinhub
 */
public class AiMerchantTaskSuggestReq
{
    /** 商户背景描述 (如"我的咖啡店在静安,有户外座位,周末人多") */
    private String merchantContext;

    /** 商户目标 (如"适合参与哪些主题" / "能变成什么任务节点") */
    private String goal;

    public String getMerchantContext() { return merchantContext; }
    public void setMerchantContext(String merchantContext) { this.merchantContext = merchantContext; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
}
