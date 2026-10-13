package com.chengyinhub.business.domain.dto;

/**
 * 商家营销分析的两段等长窗口事实。每个字段都是独立事件数，不表达相邻转化。
 */
public class MerchantMarketingAnalyticsFact {
    private long currentBrowse;
    private long previousBrowse;
    private long currentSignup;
    private long previousSignup;
    private long currentPayment;
    private long previousPayment;
    private long currentVerify;
    private long previousVerify;
    private long currentRefundApply;
    private long previousRefundApply;
    private long currentTopicTouchpoint;
    private long currentActivityTouchpoint;
    private long currentUnattributedTouchpoint;

    public long getCurrentBrowse() { return currentBrowse; }
    public void setCurrentBrowse(long currentBrowse) { this.currentBrowse = currentBrowse; }
    public long getPreviousBrowse() { return previousBrowse; }
    public void setPreviousBrowse(long previousBrowse) { this.previousBrowse = previousBrowse; }
    public long getCurrentSignup() { return currentSignup; }
    public void setCurrentSignup(long currentSignup) { this.currentSignup = currentSignup; }
    public long getPreviousSignup() { return previousSignup; }
    public void setPreviousSignup(long previousSignup) { this.previousSignup = previousSignup; }
    public long getCurrentPayment() { return currentPayment; }
    public void setCurrentPayment(long currentPayment) { this.currentPayment = currentPayment; }
    public long getPreviousPayment() { return previousPayment; }
    public void setPreviousPayment(long previousPayment) { this.previousPayment = previousPayment; }
    public long getCurrentVerify() { return currentVerify; }
    public void setCurrentVerify(long currentVerify) { this.currentVerify = currentVerify; }
    public long getPreviousVerify() { return previousVerify; }
    public void setPreviousVerify(long previousVerify) { this.previousVerify = previousVerify; }
    public long getCurrentRefundApply() { return currentRefundApply; }
    public void setCurrentRefundApply(long currentRefundApply) { this.currentRefundApply = currentRefundApply; }
    public long getPreviousRefundApply() { return previousRefundApply; }
    public void setPreviousRefundApply(long previousRefundApply) { this.previousRefundApply = previousRefundApply; }
    public long getCurrentTopicTouchpoint() { return currentTopicTouchpoint; }
    public void setCurrentTopicTouchpoint(long currentTopicTouchpoint) { this.currentTopicTouchpoint = currentTopicTouchpoint; }
    public long getCurrentActivityTouchpoint() { return currentActivityTouchpoint; }
    public void setCurrentActivityTouchpoint(long currentActivityTouchpoint) { this.currentActivityTouchpoint = currentActivityTouchpoint; }
    public long getCurrentUnattributedTouchpoint() { return currentUnattributedTouchpoint; }
    public void setCurrentUnattributedTouchpoint(long currentUnattributedTouchpoint) { this.currentUnattributedTouchpoint = currentUnattributedTouchpoint; }
}
