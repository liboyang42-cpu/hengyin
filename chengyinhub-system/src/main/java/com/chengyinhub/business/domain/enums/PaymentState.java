package com.chengyinhub.business.domain.enums;

/**
 * 支付状态机(P1-5 新定义,任务书 1.4 节)。"中间态不算成功"——
 * "下单成功"/"HTTP 200"/"请求已受理"均不是 {@link #PAID}。
 * 本枚举只冻结值域供 K 块(feature/p1-wechat-pay-refund)等消费方对齐,
 * 不改动 WechatPayServiceImpl 现有的字符串/常量式状态表达。
 */
public enum PaymentState {
    CREATED(0, "已创建,尚未提交微信"),
    WECHAT_SUBMITTED(1, "已提交微信下单,等待受理"),
    WECHAT_PROCESSING(2, "微信处理中,最终结果待查询/回调确认"),
    PAID(3, "微信最终返回SUCCESS,已核验金额"),
    PAY_FAILED(4, "微信最终返回失败(非中间态)"),
    PAY_CANCELLED(5, "支付前/处理前主动取消");

    private final int code;
    private final String label;

    PaymentState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }
}
