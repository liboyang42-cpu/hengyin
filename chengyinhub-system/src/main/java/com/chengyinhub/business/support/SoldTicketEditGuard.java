package com.chengyinhub.business.support;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.utils.CoordinateUtils;

/**
 * 已售出的票被锁字段的判定(2026-09-15 裁决 15 的票务面)。
 *
 * <p>票一旦有用户下单(待支付 0 / 支付中 1 / 已支付 2),它的时间、集合点(名称与坐标)、
 * 价格、退款规则就被锁 —— 玩家是按当时的票面下单的,追溯改写等于把已成交订单的履约与
 * 退款口径一起挪走。票名等文字信息仍可改。
 *
 * <p>只比 patch 里非 null 的字段:{@code OmsTicketMapper.updateOmsTicket} 是动态 SET,
 * null = 本次不写该列,不能当「清空」判成改动。
 */
public final class SoldTicketEditGuard {

    private SoldTicketEditGuard() {
    }

    /**
     * 返回第一处被改的锁字段(中文名,可直接进用户文案);都没改返回 null。
     *
     * <p>{@code patch} 只带被锁字段即可(活动票务同步的入参是 {@code ActivityTicketRequest},
     * 调用方先映射成探测对象)。
     */
    public static String lockedChange(OmsTicket existing, OmsTicket patch) {
        if (existing == null || patch == null) {
            return null;
        }
        if (patch.getStartTime() != null && !sameSecond(existing.getStartTime(), patch.getStartTime())) {
            return "集合时间";
        }
        if (patch.getEndTime() != null && !sameSecond(existing.getEndTime(), patch.getEndTime())) {
            return "结束时间";
        }
        if (patch.getMeetingPoint() != null
                && !Objects.equals(existing.getMeetingPoint(), patch.getMeetingPoint())) {
            return "集合地点";
        }
        if (patch.getGatherLng() != null
                && !CoordinateUtils.sameCoordinate(existing.getGatherLng(), patch.getGatherLng())) {
            return "集合点坐标";
        }
        if (patch.getGatherLat() != null
                && !CoordinateUtils.sameCoordinate(existing.getGatherLat(), patch.getGatherLat())) {
            return "集合点坐标";
        }
        if (patch.getPrice() != null && !sameMoney(existing.getPrice(), patch.getPrice())) {
            return "价格";
        }
        if (patch.getRefundRule() != null && !Objects.equals(existing.getRefundRule(), patch.getRefundRule())) {
            return "退款规则";
        }
        return null;
    }

    /** 时间按秒比较:客户端毫秒误差不能算「改了时间」。 */
    public static boolean sameSecond(Date left, Date right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getTime() / 1000L == right.getTime() / 1000L;
    }

    /** 金额按数值比较:199.00 与 199.0000 是同一个价。 */
    public static boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
