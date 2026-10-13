package com.chengyinhub.business.service.support;

import com.chengyinhub.common.constant.PurchaseKind;
import java.util.Date;

/** 探店日新使用的纯日期策略；到期不改核销、退款、库存或历史事实。 */
public final class ExploreTicketUsePolicy {
    private ExploreTicketUsePolicy() { }

    public static String rejection(Integer purchaseKind, Date endDate, Date now) {
        if (!Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(purchaseKind)) return null;
        if (endDate == null) return "活动结束时间未完善，暂不可使用";
        if (now == null || !now.before(endDate)) return "活动已结束，探索票不可继续使用";
        return null;
    }
}
