package com.chengyinhub.business.domain.dto;

public class MerchantCrmTagRemoveRequest {
    private Long tagId;
    private String requestId;

    public Long getTagId() { return tagId; }
    public void setTagId(Long value) { tagId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
