package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MerchantReviewPageVO {
    private String mode;
    private int pageNum;
    private int pageSize;
    private long total;
    private boolean hasMore;
    private BigDecimal averageRating;
    private int pendingReplyCount;
    private int monthNewCount;
    private int replyRatePct;
    private MerchantReviewEligibilityVO eligibility;
    private List<MerchantReviewVO> items = new ArrayList<>();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal averageRating) { this.averageRating = averageRating; }
    public int getPendingReplyCount() { return pendingReplyCount; }
    public void setPendingReplyCount(int pendingReplyCount) { this.pendingReplyCount = pendingReplyCount; }
    public int getMonthNewCount() { return monthNewCount; }
    public void setMonthNewCount(int monthNewCount) { this.monthNewCount = monthNewCount; }
    public int getReplyRatePct() { return replyRatePct; }
    public void setReplyRatePct(int replyRatePct) { this.replyRatePct = replyRatePct; }
    public MerchantReviewEligibilityVO getEligibility() { return eligibility; }
    public void setEligibility(MerchantReviewEligibilityVO eligibility) { this.eligibility = eligibility; }
    public List<MerchantReviewVO> getItems() { return items; }
    public void setItems(List<MerchantReviewVO> items) { this.items = items; }
}
