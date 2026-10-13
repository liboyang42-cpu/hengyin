package com.chengyinhub.business.domain.dto;

/**
 * 主办方取消活动的结果。
 *
 * <p>为什么不是一个 int:原先只返回「退了几单」,含已核销票的单被静默 continue 掉 ——
 * 于是全部单都被跳过时 refundedOrders=0,回执打印「活动已取消(无已付款报名)」,
 * 而实际上有已付款报名、只是一笔都没退。**返回类型表达不了的事,文案就只能撒谎**。
 * 把「待人工处置」提到返回值里,回执才有可能说真话。
 */
public class ActivityCancelResult {

    /** 已自动全额退款的订单数(多票同父订单算一单)。 */
    private final int refundedOrders;

    /** 含已核销票、无法自动退款、留待人工处置的订单数(多票同父订单算一单)。 */
    private final int manualOrders;

    /** 活动取消时同步关闭的待支付报名数；这些订单不再允许支付回调转成成功。 */
    private final int closedPendingOrders;

    public ActivityCancelResult(int refundedOrders, int manualOrders) {
        this(refundedOrders, manualOrders, 0);
    }

    public ActivityCancelResult(int refundedOrders, int manualOrders, int closedPendingOrders) {
        this.refundedOrders = refundedOrders;
        this.manualOrders = manualOrders;
        this.closedPendingOrders = closedPendingOrders;
    }

    public int getRefundedOrders() {
        return refundedOrders;
    }

    public int getManualOrders() {
        return manualOrders;
    }

    public int getClosedPendingOrders() {
        return closedPendingOrders;
    }
}
