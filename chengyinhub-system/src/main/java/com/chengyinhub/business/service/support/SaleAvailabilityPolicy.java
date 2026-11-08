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

    /**
     * 只读孪生:返回第一条拒绝原因(可售返回 null),判据与文案只有 {@link #assertSellable} 一份。
     *
     * <p>作者端「现在为什么还不能卖」必须就是售卖闸的原话,所以这里直接调用原闸并捕获它的业务拒绝
     * (IllegalStateException),不重写任何一条判断 —— 重写就会漂移。调用方缺陷(now 为 null)仍是
     * IllegalArgumentException,与抛出版本一致,不当作业务拒绝吞掉。
     */
    public static String sellableBlockReason(CmsTopic topic, OmsTicket ticket, Date now) {
        try {
            assertSellable(topic, ticket, now);
            return null;
        } catch (IllegalStateException refused) {
            return refused.getMessage();
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
