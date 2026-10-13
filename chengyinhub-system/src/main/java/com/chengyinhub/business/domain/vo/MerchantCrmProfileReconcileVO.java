package com.chengyinhub.business.domain.vo;

public class MerchantCrmProfileReconcileVO {
    private Integer upsertedRows;
    private Integer staleRows;
    private Long sourceCustomerCount;
    private Long activeProfileCount;
    private Boolean consistent;

    public Integer getUpsertedRows() { return upsertedRows; }
    public void setUpsertedRows(Integer value) { upsertedRows = value; }
    public Integer getStaleRows() { return staleRows; }
    public void setStaleRows(Integer value) { staleRows = value; }
    public Long getSourceCustomerCount() { return sourceCustomerCount; }
    public void setSourceCustomerCount(Long value) { sourceCustomerCount = value; }
    public Long getActiveProfileCount() { return activeProfileCount; }
    public void setActiveProfileCount(Long value) { activeProfileCount = value; }
    public Boolean getConsistent() { return consistent; }
    public void setConsistent(Boolean value) { consistent = value; }
}
