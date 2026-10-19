package com.chengyinhub.business.notify;

/**
 * 一条通知通道(微信订阅消息 / App push / 站内信 …)。
 *
 * ⚠️ **实现类绝对不能抛异常。** 调用链上游是业务事务:
 * `RefundServiceImpl.refundOne` 等方法带 @Transactional(rollbackFor=Exception.class),
 * 通道抛出会把**已被微信网关受理的退款**连带回滚 = 资损。
 * 这是 IWxSubscribeMsgService#send 从一开始就有的约定,新通道必须一并继承。
 *
 * 发不出去就返回可区分的结果并留日志 —— 不抛 ≠ 静默。
 */
public interface NotifyChannel {

    /**
     * @return 兼容信号：站内已落库或外部渠道明确接受（不是设备送达/用户已读）。
     *         未配置、无收件地址、对端拒收或终态未知均返回 false。
     */
    boolean send(NotifyMessage message);

    /** typed 兼容桥；已有通道可渐进覆盖以保留 UNKNOWN 和渠道身份。 */
    default NotifyResult sendResult(NotifyMessage message) {
        return send(message) ? NotifyResult.accepted(channelId())
                : NotifyResult.failed(channelId(), null);
    }

    default String channelId() {
        return getClass().getSimpleName();
    }
}
