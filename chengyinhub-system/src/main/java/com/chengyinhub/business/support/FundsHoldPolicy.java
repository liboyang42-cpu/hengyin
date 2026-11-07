package com.chengyinhub.business.support;

import java.util.Date;

/**
 * 钱在活动结束后多久可提现 —— 全仓唯一的天数(2026-11-05 业主:所有收款人、所有路径都是结束后 14 天,原 7 天)。
 *
 * <p>读它的地方(改天数只改这里):
 * <ul>
 *   <li>{@code CmsActivityMapper.selectActivitiesForSettlement}:活动结束满 N 天才结算入余额;</li>
 *   <li>{@code CmsTopicServiceImpl} 主题结算(俱乐部自有主题、合作主题)写的 {@code coop_settlement.payable_time};
 *       {@code payoutDueCoopSettlements}(商家结算 T+N 打款)按它放款;</li>
 *   <li>{@code SettlementDetailMapper.topicTicketNotYetSettleable}:主题级票的商家结算明细满 N 天才归批;</li>
 *   <li>{@code MemberFundsStageService}:余额页「X月X日可提现」;</li>
 *   <li>{@code TopicSaleFinalizationServiceImpl}:俱乐部酬劳的证据截止(履约窗结束 + N 天,FINAL 之前不发)。</li>
 * </ul>
 * SQL 里用 {@code ${@com.chengyinhub.business.support.FundsHoldPolicy@WITHDRAWABLE_AFTER_END_DAYS}} 读同一个常量。
 */
public final class FundsHoldPolicy {

    public static final int WITHDRAWABLE_AFTER_END_DAYS = 14;

    public static final long WITHDRAWABLE_AFTER_END_MS = WITHDRAWABLE_AFTER_END_DAYS * 24L * 3600 * 1000;

    private FundsHoldPolicy() {
    }

    /** 结束时刻 + N 天;结束时刻缺失时从 {@code now} 起算(与原 T+7 写法同口径)。 */
    public static Date payableAfterEnd(Date endDate, Date now) {
        Date base = endDate != null ? endDate : now;
        return new Date(base.getTime() + WITHDRAWABLE_AFTER_END_MS);
    }
}
