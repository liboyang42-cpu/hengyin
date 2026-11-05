package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;

/**
 * 合同二 §2.3 的时间与 SKU 断言；错误必须点名相邻的两个时间。
 *
 * <p>第十轮 f2 第 4 项:拒绝原因原样出现在平台开售审核台和主办方那里,所以用中文业务名
 * (招商截止、售票截止、主题结束时间、票种开始时间)点名,不写字段名。
 */
public final class TopicSaleTimeValidator {

    private TopicSaleTimeValidator() {
    }

    /** 立项只拒绝已确定的无售卖窗口，未来招募与未完成定价仍可提交。 */
    public static void assertRecruitmentSaleWindow(CmsTopic topic) {
        if (topic.getRecruitDeadline() != null && topic.getSaleEndAt() != null
                && !topic.getRecruitDeadline().before(topic.getSaleEndAt())) {
            throw new ServiceException("招商截止日期必须早于售票截止时间", 400);
        }
    }

    public static void assertValid(CmsTopic topic, Collection<OmsTicket> tickets, Date sellingStartedAt) {
        assertValid(topic, tickets, sellingStartedAt, () -> false);
    }

    /**
     * @param recruitedPartners 只在「未开放承接却有 recruitDeadline」时才问:冻结阵容里已有合作方(如城市定向在招募期邀来的带队俱乐部 ORGANIZER)。
     *                          这说明主题确实走过招募期，recruitDeadline 是那段招募的真实截止，不是脏值(P-01)。
     */
    public static void assertValid(CmsTopic topic, Collection<OmsTicket> tickets, Date sellingStartedAt,
                                   java.util.function.BooleanSupplier recruitedPartners) {
        if (topic == null) {
            throw new IllegalStateException("主题不存在");
        }
        if (sellingStartedAt == null) {
            throw new IllegalStateException("缺少开售时间");
        }
        if (topic.getSaleEndAt() == null) {
            throw new IllegalStateException("主题没有售票截止时间");
        }
        if (!sellingStartedAt.before(topic.getSaleEndAt())) {
            throw new IllegalStateException("开售时间必须早于售票截止时间");
        }

        FulfillmentWindowResolver.Window topicWindow = FulfillmentWindowResolver.resolve(topic, null);
        if (topic.getSaleEndAt().after(topicWindow.getEndExclusive())) {
            throw new IllegalStateException("售票截止时间不能晚于主题结束时间");
        }

        boolean openRecruitment = Integer.valueOf(1).equals(topic.getMerchantStatus())
                || Integer.valueOf(1).equals(topic.getOpenClubPool())
                || Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType());
        if (openRecruitment && topic.getRecruitDeadline() == null) {
            throw new IllegalStateException("开放商家或俱乐部承接的主题必须有招商截止日期");
        }
        if (!openRecruitment && topic.getRecruitDeadline() != null && !recruitedPartners.getAsBoolean()) {
            throw new IllegalStateException("未开放承接的主题不应有招商截止日期");
        }
        if (topic.getRecruitDeadline() != null && topic.getRecruitDeadline().after(sellingStartedAt)) {
            throw new IllegalStateException("招商截止日期必须早于或等于开售时间");
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
                throw new IllegalStateException("自玩票价格未填写或为负数");
            }
            hasActiveSku = true;
        }
        if (!hasActiveSku) {
            throw new IllegalStateException("没有可售的票种");
        }
    }

    private static String label(OmsTicket ticket) {
        String name = ticket.getName() == null ? "" : ticket.getName().trim();
        return name.isEmpty() ? "票种" : "票种「" + name + "」";
    }

    private static void assertTicket(CmsTopic topic, OmsTicket ticket, Date sellingStartedAt) {
        if (ticket.getPrice() == null || ticket.getTotalInventory() == null || ticket.getRemainingInventory() == null) {
            throw new IllegalStateException(label(ticket) + "的价格或库存未填写");
        }
        FulfillmentWindowResolver.Window ticketWindow = FulfillmentWindowResolver.resolve(topic, ticket);
        if (ticketWindow.getStartInclusive().before(FulfillmentWindowResolver.resolve(topic, null).getStartInclusive())
                || ticketWindow.getEndExclusive().after(FulfillmentWindowResolver.resolve(topic, null).getEndExclusive())) {
            throw new IllegalStateException(label(ticket) + "的开始和结束时间必须在主题开始和结束时间之内");
        }
        if (ticket.getSignupDeadline() != null && ticket.getSignupDeadline().after(topic.getSaleEndAt())) {
            throw new IllegalStateException(label(ticket) + "的报名截止时间不能晚于主题售票截止时间");
        }
        Date effectiveSaleEndAt = SaleAvailabilityPolicy.effectiveSaleEndAt(topic, ticket);
        if (!sellingStartedAt.before(effectiveSaleEndAt)) {
            throw new IllegalStateException(label(ticket) + "的售票截止时间已过，不能开售");
        }
        // 第十轮 f2 第 4 项:俱乐部自己的城市定向按「开一场」卖,主题票只是场次模板,真正的开始时刻、
        // 报名与退款钟都在每一场上(ClubSessionServiceImpl.open)。「售票截止早于票种开始」只约束
        // 单次成行的有人带票;套在俱乐部主题上等于「档期一开始就停售」,开一场永远卖不出去。
        boolean guided = Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType())
                && !"self".equalsIgnoreCase(ticket.getSubType())
                && topic.getClubId() == null;
        if (guided && effectiveSaleEndAt.after(ticketWindow.getStartInclusive())) {
            throw new IllegalStateException(label(ticket) + "的售票截止时间必须早于或等于票种开始时间");
        }
        if (!guided && effectiveSaleEndAt.after(ticketWindow.getEndExclusive())) {
            throw new IllegalStateException(label(ticket) + "的售票截止时间必须早于或等于票种结束时间");
        }
    }
}
