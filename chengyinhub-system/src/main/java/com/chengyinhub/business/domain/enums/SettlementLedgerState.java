package com.chengyinhub.business.domain.enums;

/**
 * 分账/结算的商家侧账本视图(P1-5 新定义,任务书 1.4 节最后一段)。
 *
 * <p>★这是一个跨表的概念性状态,不是任何单一列的直接映射——现码"商家收入"落在
 * {@code settlement_detail}/{@code merchant_payable}/{@code merchant_payable_batch} 三套并行台账里
 * (真源优先级见四账映射文档 four_ledger_mapping.sql 的说明:优先 merchant_payable/merchant_payable_batch)，
 * 本枚举给 M/N 块一个统一的"商家能不能提现"语义骨架，具体由哪张表的哪个字段推导，
 * 留给 M/N 落地时决定，不在 P1-5 里预先绑定字段。</p>
 *
 * <p>不允许跳过 {@link #WITHDRAWABLE} 直接改余额伪造"已完成"（商业化清单 §6 铁律）；
 * 撤销/退回必须落 {@link #REVERSED}，不能直接回退到 {@link #PENDING_SETTLE}。</p>
 */
public enum SettlementLedgerState {
    /** 已计提待结(如 settlement_detail.status=0 / merchant_payable_batch.status=PENDING) */
    PENDING_SETTLE,
    /** 已结算但尚不可提现(如 merchant_payable_batch.status=CONFIRMED,金额已确定未打款/未转入可提现余额) */
    SETTLED,
    /** 已转入平台内可提现余额,商家可发起 CashWithdrawServiceImpl 提现 */
    WITHDRAWABLE,
    /** 撤销/退回:append-only 冲正(参照 MerchantPayableServiceImpl.reverseOnRedemption 的冲正范式),
     *  不允许直接改动原行余额制造"已完成" */
    REVERSED
}
