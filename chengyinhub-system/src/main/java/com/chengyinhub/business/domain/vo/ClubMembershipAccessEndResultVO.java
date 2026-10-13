package com.chengyinhub.business.domain.vo;

/**
 * 退出、移除、封禁或解散后的会费处理回执。
 * pendingReviewCount > 0 只表示平台审核中；payoutCompleted 在首版恒为 false。
 */
public class ClubMembershipAccessEndResultVO {
    private int cancelledTermCount;
    private int blockedInvoiceCount;
    private int pendingReviewCount;
    private int nonRefundableCount;
    private boolean payoutCompleted;

    public int getCancelledTermCount() { return cancelledTermCount; }
    public void setCancelledTermCount(int cancelledTermCount) { this.cancelledTermCount = cancelledTermCount; }
    public int getBlockedInvoiceCount() { return blockedInvoiceCount; }
    public void setBlockedInvoiceCount(int blockedInvoiceCount) { this.blockedInvoiceCount = blockedInvoiceCount; }
    public int getPendingReviewCount() { return pendingReviewCount; }
    public void setPendingReviewCount(int pendingReviewCount) { this.pendingReviewCount = pendingReviewCount; }
    public int getNonRefundableCount() { return nonRefundableCount; }
    public void setNonRefundableCount(int nonRefundableCount) { this.nonRefundableCount = nonRefundableCount; }
    public boolean isPayoutCompleted() { return payoutCompleted; }
    public void setPayoutCompleted(boolean payoutCompleted) { this.payoutCompleted = payoutCompleted; }
}
