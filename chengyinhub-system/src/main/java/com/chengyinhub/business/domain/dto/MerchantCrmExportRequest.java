package com.chengyinhub.business.domain.dto;

public class MerchantCrmExportRequest {
    private MerchantCrmCustomerQuery query;
    private String requestId;

    public MerchantCrmCustomerQuery getQuery() { return query; }
    public void setQuery(MerchantCrmCustomerQuery value) { query = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
