package com.chengyinhub.business.domain.dto;

public class MerchantCrmTagRequest {
    private String tagName;
    private String tagColor;
    private String requestId;

    public String getTagName() { return tagName; }
    public void setTagName(String value) { tagName = value; }
    public String getTagColor() { return tagColor; }
    public void setTagColor(String value) { tagColor = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
