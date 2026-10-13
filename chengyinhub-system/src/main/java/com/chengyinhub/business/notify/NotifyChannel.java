package com.chengyinhub.business.notify;

/**
 * 一条通知通道(微信订阅消息 / App push / 站内信 …)。
 *
 * ⚠️ **实现类绝对不能抛异常。** 调用链上游是业务事务:
 * `RefundServiceImpl.refundOne` 等方法带 @Transactional(rollbackFor=Exception.class),
 * 通道抛出会把**已被微信网关受理的退款**连带回滚 = 资损。
 * 这是 IWxSubscribeMsgService#send 从一开始就有的约定,新通道必须一并继承。
 *
 * 发不出去就 return false 并留日志 —— 不抛 ≠ 静默。
 */
public interface NotifyChannel {

    /**
     * @return 该通道是否**真的**把消息交付出去了(不是「调用没报错」)。
     *         未配置、无收件地址、对端拒收都返回 false。
     */
    boolean send(NotifyMessage message);
}
