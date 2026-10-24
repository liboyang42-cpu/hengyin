package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** 管理态当前生效的服务端筛选(all/pending/low/photo/platform);公开态为 null。 */
    private String filter;
    /** 管理态全量筛选计数:按本店全部评价计算,不随分页与当前筛选变化;公开态为 null。 */
    private Map<String, Integer> filterCounts;
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
    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }
    public Map<String, Integer> getFilterCounts() { return filterCounts; }
    public void setFilterCounts(Map<String, Integer> filterCounts) {
        this.filterCounts = filterCounts == null ? null : new LinkedHashMap<>(filterCounts);
    }
    public MerchantReviewEligibilityVO getEligibility() { return eligibility; }
    public void setEligibility(MerchantReviewEligibilityVO eligibility) { this.eligibility = eligibility; }
    public List<MerchantReviewVO> getItems() { return items; }
    public void setItems(List<MerchantReviewVO> items) { this.items = items; }
}
