package com.chengyinhub.business.domain.dto;

import java.util.List;

public class MerchantCrmBatchTagRequest {
    private List<Long> customerMemberIds;
    private String tagName;
    private String tagColor;
    private String requestId;

    public List<Long> getCustomerMemberIds() { return customerMemberIds; }
    public void setCustomerMemberIds(List<Long> value) { customerMemberIds = value; }
    public String getTagName() { return tagName; }
    public void setTagName(String value) { tagName = value; }
    public String getTagColor() { return tagColor; }
    public void setTagColor(String value) { tagColor = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
