package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RefundSettledFact;

/**
 * 「退款已真实到账」的消费端口。实现方是各自域(营销商品权益回冲、促销撤销、收入冲销等),
 * <b>不</b>由本域实现。
 *
 * <p><b>调用语义(唯一写法,不要改)</b>:
 * <ul>
 *   <li>在 {@code RefundServiceImpl.applyRefundPayoutEvent} 的 SUCCESS 分支、
 *       {@code updatePayoutTerminal} CAS 成功之后调用;</li>
 *   <li>与原事实<b>同一事务</b>(REQUIRED),不新建 REQUIRES_NEW 子事务 ——
 *       父事务此时尚未提交,子事务读不到未提交数据,会失败或死锁;</li>
 *   <li>调用方<b>不捕获</b>业务异常后只记日志:实现方抛异常即整笔退款回调回滚并由微信重投,
 *       这是正确行为。实现方必须对"本域与该单无关"静默返回,不得抛异常。</li>
 * </ul>
 *
 * <p><b>实现方必须满足</b>:
 * <ol>
 *   <li>幂等:同一 {@link RefundSettledFact#idempotencyKey()} 重放结果一致,不重复冲销;</li>
 *   <li>只按既有不可变事实算钱:{@code verifiedRefundFactId/Hash} 可再校验,不得用当前配置重算历史单;</li>
 *   <li>不产生钱包/余额/提现/新的支付状态;不改既有 {@code settlement_detail} 语义;</li>
 *   <li>金额不足或事实不符时明确失败(抛异常),由回调重投或人工处理,不得静默截断。</li>
 * </ol>
 */
public interface IRefundSettledConsumer {

    /** 收到一笔已真实到账的退款事实。实现方自行判断是否与自己的域相关。 */
    void onRefundSettled(RefundSettledFact fact);
}
