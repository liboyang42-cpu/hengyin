package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;

import java.util.Date;

/**
 * 合同二唯一售卖时间闸。
 *
 * <p>票的 {@code startTime/endTime} 只描述履约窗；售卖一律以主题实际开售时刻和
 * 主题硬售止（票种可提前）判断。缺少任一持久化事实时从严拒绝，绝不从存量更新时间猜测。</p>
 */
public final class SaleAvailabilityPolicy {

    private SaleAvailabilityPolicy() {
    }

    public static void assertSellable(CmsTopic topic, OmsTicket ticket, Date now) {
        if (topic == null) {
            throw new IllegalStateException("主题不存在，拒绝售卖");
        }
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("lifecycle 未知或缺失，拒绝售卖", ex);
        }
        if (!lifecycle.isDirectSaleOpen()) {
            throw new IllegalStateException("lifecycle 必须为 SELLING");
        }
        if (topic.getSellingStartedAt() == null) {
            throw new IllegalStateException("sellingStartedAt 缺失，拒绝售卖");
        }
        if (topic.getSaleEndAt() == null) {
            throw new IllegalStateException("saleEndAt 缺失，拒绝售卖");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 不能为空");
        }
        if (now.before(topic.getSellingStartedAt())) {
            throw new IllegalStateException("sellingStartedAt 尚未到达，拒绝售卖");
        }

        Date effectiveSaleEndAt = effectiveSaleEndAt(topic, ticket);
        if (!now.before(effectiveSaleEndAt)) {
            throw new IllegalStateException(ticket == null
                    ? "saleEndAt 已到，自玩通行证不可再售"
                    : "saleEndAt 已到，票种不可再售");
        }
    }

    /** ticket.signupDeadline 仅能提前主题硬售止；无票通行证只读主题硬售止。 */
    public static Date effectiveSaleEndAt(CmsTopic topic, OmsTicket ticket) {
        if (topic == null || topic.getSaleEndAt() == null) {
            throw new IllegalStateException("saleEndAt 缺失，拒绝售卖");
        }
        if (ticket == null || ticket.getSignupDeadline() == null
                || !ticket.getSignupDeadline().before(topic.getSaleEndAt())) {
            return topic.getSaleEndAt();
        }
        return ticket.getSignupDeadline();
    }
}
