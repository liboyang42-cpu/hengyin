package com.chengyinhub.business.domain.dto;

public class MerchantCrmSegmentRequest {
    private String name;
    private MerchantCrmSegmentFilter filter;
    private String requestId;

    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public MerchantCrmSegmentFilter getFilter() { return filter; }
    public void setFilter(MerchantCrmSegmentFilter value) { filter = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
