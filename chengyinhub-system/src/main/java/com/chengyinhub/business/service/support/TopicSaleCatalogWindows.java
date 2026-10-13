package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** 冻结售卖目录的时间投影；售罄不改变目录，也不得提前生命周期。 */
public final class TopicSaleCatalogWindows {

    private TopicSaleCatalogWindows() {
    }

    public static Windows resolve(CmsTopic topic, List<OmsTicket> tickets) {
        if (topic == null) throw new IllegalStateException("topic 不能为空");
        Date maxSaleEnd = null;
        Date maxFulfillmentEnd = null;
        int activeCount = 0;
        for (OmsTicket ticket : tickets == null ? Collections.<OmsTicket>emptyList() : tickets) {
            if (!isActiveSellableTicket(ticket)) continue;
            FulfillmentWindowResolver.Window fulfillment = FulfillmentWindowResolver.resolve(topic, ticket);
            Date saleEnd = SaleAvailabilityPolicy.effectiveSaleEndAt(topic, ticket);
            maxSaleEnd = later(maxSaleEnd, saleEnd);
            maxFulfillmentEnd = later(maxFulfillmentEnd, fulfillment.getEndExclusive());
            activeCount++;
        }
        if (Integer.valueOf(1).equals(topic.getSelfPlay())) {
            if (topic.getSelfPlayPrice() == null || topic.getSelfPlayPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("selfPlayPrice 必须存在且不得为负数");
            }
            FulfillmentWindowResolver.Window fulfillment = FulfillmentWindowResolver.resolve(topic, null);
            maxSaleEnd = later(maxSaleEnd, SaleAvailabilityPolicy.effectiveSaleEndAt(topic, null));
            maxFulfillmentEnd = later(maxFulfillmentEnd, fulfillment.getEndExclusive());
            activeCount++;
        }
        if (activeCount == 0) throw new IllegalStateException("冻结售卖目录没有 active sellable SKU");
        return new Windows(maxSaleEnd, maxFulfillmentEnd, activeCount);
    }

    private static boolean isActiveSellableTicket(OmsTicket ticket) {
        return ticket != null && !Integer.valueOf(2).equals(ticket.getDelFlag())
                && ticket.getPrice() != null && ticket.getPrice().compareTo(BigDecimal.ZERO) >= 0
                && ticket.getTotalInventory() != null && ticket.getTotalInventory() >= 0
                && ticket.getRemainingInventory() != null && ticket.getRemainingInventory() >= 0;
    }

    private static Date later(Date current, Date candidate) {
        return current == null || candidate.after(current) ? candidate : current;
    }

    public static final class Windows {
        private final Date effectiveSaleEndAt;
        private final Date effectiveFulfillmentEndAt;
        private final int activeSellableSkuCount;

        private Windows(Date effectiveSaleEndAt, Date effectiveFulfillmentEndAt, int activeSellableSkuCount) {
            this.effectiveSaleEndAt = effectiveSaleEndAt;
            this.effectiveFulfillmentEndAt = effectiveFulfillmentEndAt;
            this.activeSellableSkuCount = activeSellableSkuCount;
        }

        public Date getEffectiveSaleEndAt() { return effectiveSaleEndAt; }
        public Date getEffectiveFulfillmentEndAt() { return effectiveFulfillmentEndAt; }
        public int getActiveSellableSkuCount() { return activeSellableSkuCount; }
    }
}
