package com.chengyinhub.business.citygame.operations;

import java.util.Date;

/** 本店运营覆盖。没有积分费率、金额或他队资产字段。 */
public class CityGameOperationsStoreFactRow {
    private String merchantId;
    private String factStatus;
    private Long version;
    private Long expectedVersion;
    private Long caseId;
    private String actionId;
    private Date updateTime;

    public CityGameOperationsStoreFactRow copy() {
        CityGameOperationsStoreFactRow row = new CityGameOperationsStoreFactRow();
        row.merchantId = merchantId;
        row.factStatus = factStatus;
        row.version = version;
        row.expectedVersion = expectedVersion;
        row.caseId = caseId;
        row.actionId = actionId;
        row.updateTime = updateTime;
        return row;
    }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getFactStatus() { return factStatus; }
    public void setFactStatus(String factStatus) { this.factStatus = factStatus; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
