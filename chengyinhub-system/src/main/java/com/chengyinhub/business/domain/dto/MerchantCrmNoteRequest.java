package com.chengyinhub.business.domain.dto;

public class MerchantCrmNoteRequest {
    private String content;
    private String requestId;
    private Long correctsNoteId;

    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getCorrectsNoteId() { return correctsNoteId; }
    public void setCorrectsNoteId(Long value) { correctsNoteId = value; }
}
