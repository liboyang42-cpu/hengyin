package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.ProductType;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;

/** 合同二 §2.3 的时间与 SKU 断言；错误必须点名相邻字段。 */
public final class TopicSaleTimeValidator {

    private TopicSaleTimeValidator() {
    }

    public static void assertValid(CmsTopic topic, Collection<OmsTicket> tickets, Date sellingStartedAt) {
        if (topic == null) {
            throw new IllegalStateException("topic 不能为空");
        }
        if (sellingStartedAt == null) {
            throw new IllegalStateException("sellingStartedAt 必填");
        }
        if (topic.getSaleEndAt() == null) {
            throw new IllegalStateException("saleEndAt 必填");
        }
        if (!sellingStartedAt.before(topic.getSaleEndAt())) {
            throw new IllegalStateException("sellingStartedAt 必须早于 saleEndAt");
        }

        FulfillmentWindowResolver.Window topicWindow = FulfillmentWindowResolver.resolve(topic, null);
        if (topic.getSaleEndAt().after(topicWindow.getEndExclusive())) {
            throw new IllegalStateException("saleEndAt 必须早于或等于 topicFulfillmentEndAt");
        }

        boolean openRecruitment = Integer.valueOf(1).equals(topic.getMerchantStatus())
                || Integer.valueOf(1).equals(topic.getOpenClubPool())
                || Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType());
        if (openRecruitment && topic.getRecruitDeadline() == null) {
            throw new IllegalStateException("开放承接时 recruitDeadline 必填");
        }
        if (!openRecruitment && topic.getRecruitDeadline() != null) {
            throw new IllegalStateException("未开放承接时 recruitDeadline 必须为空");
        }
        if (topic.getRecruitDeadline() != null && topic.getRecruitDeadline().after(sellingStartedAt)) {
            throw new IllegalStateException("recruitDeadline 必须早于或等于 sellingStartedAt");
        }

        boolean hasActiveSku = false;
        if (tickets != null) {
            for (OmsTicket ticket : tickets) {
                if (ticket == null || Integer.valueOf(2).equals(ticket.getDelFlag())) {
                    continue;
                }
                hasActiveSku = true;
                assertTicket(topic, ticket, sellingStartedAt);
            }
        }
        if (Integer.valueOf(1).equals(topic.getSelfPlay())) {
            if (topic.getSelfPlayPrice() == null || topic.getSelfPlayPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException("selfPlayPrice 必须存在且不得为负数");
            }
            hasActiveSku = true;
        }
        if (!hasActiveSku) {
            throw new IllegalStateException("至少需要一个 active sellable SKU");
        }
    }

    private static void assertTicket(CmsTopic topic, OmsTicket ticket, Date sellingStartedAt) {
        if (ticket.getPrice() == null || ticket.getTotalInventory() == null || ticket.getRemainingInventory() == null) {
            throw new IllegalStateException("ticket.price、ticket.totalInventory 与 ticket.remainingInventory 必须齐全");
        }
        FulfillmentWindowResolver.Window ticketWindow = FulfillmentWindowResolver.resolve(topic, ticket);
        if (ticketWindow.getStartInclusive().before(FulfillmentWindowResolver.resolve(topic, null).getStartInclusive())
                || ticketWindow.getEndExclusive().after(FulfillmentWindowResolver.resolve(topic, null).getEndExclusive())) {
            throw new IllegalStateException("ticket.startTime/ticket.endTime 必须落在主题履约窗内");
        }
        if (ticket.getSignupDeadline() != null && ticket.getSignupDeadline().after(topic.getSaleEndAt())) {
            throw new IllegalStateException("ticket.signupDeadline 必须早于或等于 saleEndAt");
        }
        Date effectiveSaleEndAt = SaleAvailabilityPolicy.effectiveSaleEndAt(topic, ticket);
        if (!sellingStartedAt.before(effectiveSaleEndAt)) {
            throw new IllegalStateException("sellingStartedAt 必须早于 effectiveSaleEndAt");
        }
        boolean guided = Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType())
                && !"self".equalsIgnoreCase(ticket.getSubType());
        if (guided && effectiveSaleEndAt.after(ticketWindow.getStartInclusive())) {
            throw new IllegalStateException("effectiveSaleEndAt 必须早于或等于 ticket.startTime");
        }
        if (!guided && effectiveSaleEndAt.after(ticketWindow.getEndExclusive())) {
            throw new IllegalStateException("effectiveSaleEndAt 必须早于或等于 ticket.endTime");
        }
    }
}
