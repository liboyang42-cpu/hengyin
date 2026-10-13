package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

/** 资金查询的跨来源稳定分页结果。 */
public class MerchantFinancePage<T> {
    private List<T> rows = new ArrayList<>();
    private long total;
    private int pageNum;
    private int pageSize;
    /** 可选的整集合聚合(当前仅核销记录页用 MerchantRedemptionSummary)。放这里是为了让前端拿到
     *  跨页口径,而不是拿当页 rows 求和 —— 后者分页后必错且错得静默。 */
    private Object summary;

    public Object getSummary() { return summary; }
    public void setSummary(Object summary) { this.summary = summary; }

    public List<T> getRows() { return rows; }
    public void setRows(List<T> rows) { this.rows = rows == null ? new ArrayList<T>() : rows; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
