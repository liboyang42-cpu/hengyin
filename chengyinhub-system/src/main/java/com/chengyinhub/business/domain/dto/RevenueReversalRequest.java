package com.chengyinhub.business.domain.dto;

/**
 * 退款/chargeback 反向分录请求。
 *
 * <p><b>只有退款单标识，没有金额、没有币种、没有原单</b>：三样全部由服务端从
 * {@code RefundApplication}(原单关联/金额/退款号) + {@code wx_verified_payment_fact}(通道实际退款成功)
 * 读取。管理员在后台填的 refundAmount 不是资金事实 —— 申请或批准都不等于通道真的退了钱。
 *
 * <p>调用方若确实想核对某一原单，可以传 {@link #registrationId}；与退款单自身关联不一致时拒绝。
 */
public class RevenueReversalRequest {

    /** 退款单 id(RefundApplication.id)。幂等事实按它归组。 */
    private Long refundId;
    /** 可选交叉核对：必须等于退款单自身的 sourceId，否则拒绝。 */
    private Long registrationId;
    /** 事件类型：refund / chargeback，仅留痕。 */
    private String eventType;

    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
}
