package com.chengyinhub.business.domain.vo;

import java.util.Date;

public class MerchantCrmExportTaskVO {
    private Long id;
    private String status;
    private Integer rowCount;
    private String errorMessage;
    private Date expiresAt;
    private String downloadToken;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer value) { rowCount = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date value) { expiresAt = value; }
    public String getDownloadToken() { return downloadToken; }
    public void setDownloadToken(String value) { downloadToken = value; }
}
