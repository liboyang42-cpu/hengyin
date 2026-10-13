package com.chengyinhub.business.domain.vo;

/** 某个来源打过卡的人数(已去重到人)。头像堆右边那枚 +N 用它,不用头像条数 —— 头像是截断过的。 */
public class ShopVisitorCountVO {
    private Long sourceId;
    private Integer total;

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
}
