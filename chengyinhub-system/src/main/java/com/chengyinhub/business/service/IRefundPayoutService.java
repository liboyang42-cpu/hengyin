package com.chengyinhub.business.service;

/**
 * 退款打款编排(D-2)。
 *
 * 为什么单独一个 service 而不是并进 RefundServiceImpl:WechatPayServiceImpl 已注入 IRefundService
 * (退款结果通知回调回写打款态),若 RefundServiceImpl 反向注入 IWechatPayService 即成闭环 —— Boot 2.5
 * 默认容忍,但 2.6+ 默认禁止,等于给升级埋雷。抽一层则依赖为 RefundPayout →{WechatPay, Refund},无环。
 */
public interface IRefundPayoutService {

    /**
     * 审核通过后执行现金原路退款并回写打款态。两路共用:后台运营审核 与 玩家自退。
     *
     * ⚠️ 不带 @Transactional,事务边界跟随调用方 —— 本方法含微信 HTTP 调用,
     * 不应主动开启事务(长事务持连接;且退款已发出时事务回滚只会让账与钱劈叉)。
     *
     * 幂等:out_refund_no 使用服务端退款单持久化编号，首次为 RF+registrationId，后续申请按前序退款 ID 区分,微信侧按其去重;
     * markPayoutResult 的 updatePayoutResult 只允许从待派发/处理中/待人工(5/0/2)置位,
     * 不覆盖人工已处理(3/4)的成果。
     *
     * @param refundId 退款单 id;非审核通过态/非报名来源/无现金(纯积分单)一律 no-op
     */
    void payoutAfterReview(Long refundId);
}
