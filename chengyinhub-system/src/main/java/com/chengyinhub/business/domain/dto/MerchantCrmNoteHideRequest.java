package com.chengyinhub.business.domain.dto;

public class MerchantCrmNoteHideRequest {
    private Long noteId;
    private Integer expectedVersion;
    private String requestId;

    public Long getNoteId() { return noteId; }
    public void setNoteId(Long value) { noteId = value; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer value) { expectedVersion = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
}
