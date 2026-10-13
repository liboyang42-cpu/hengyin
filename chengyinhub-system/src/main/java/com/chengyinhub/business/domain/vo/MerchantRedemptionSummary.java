package com.chengyinhub.business.domain.vo;

/**
 * 核销记录页顶部汇总条(资金域重排草图 01)。
 *
 * <p>⚠️ <b>必须服务端算,不能让前端对当前页 rows 求和</b> —— rows 只是第 1 页,
 * 分页后前端求和必错,而且错得静默(数字看着像那么回事)。
 *
 * <p>口径:统计窗口固定为 <b>Asia/Shanghai 的当前自然月</b>(与 §5.8 一致),
 * 且<b>不随筛选 chip 变化</b> —— 汇总条描述的是"我这个月接待了多少",
 * 切筛选时它跟着变会让人以为钱少了。金额统一两位小数字符串。
 */
public class MerchantRedemptionSummary {

    /** 本月核销笔数(一次核销一条,与列表粒度一致) */
    private int count;

    /** 本月已履约但金额尚未成立/未入账的合计(displayState=PENDING_SETTLEMENT 且金额已成立) */
    private String pendingAmount;

    /** 本月已入账合计(displayState=SETTLED) */
    private String arrivedAmount;

    public int getCount() { return count; }
    public void setCount(int value) { count = value; }
    public String getPendingAmount() { return pendingAmount; }
    public void setPendingAmount(String value) { pendingAmount = value; }
    public String getArrivedAmount() { return arrivedAmount; }
    public void setArrivedAmount(String value) { arrivedAmount = value; }
}
