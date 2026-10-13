package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

/** 商家售后服务端分页结果。 */
public class MerchantAftercareListVO {
    private String bucket;
    private int pageNum;
    private int pageSize;
    private long total;
    private boolean hasMore;
    private List<MerchantAftercareListItemVO> items = new ArrayList<>();

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public List<MerchantAftercareListItemVO> getItems() { return items; }
    public void setItems(List<MerchantAftercareListItemVO> items) { this.items = items; }
}
