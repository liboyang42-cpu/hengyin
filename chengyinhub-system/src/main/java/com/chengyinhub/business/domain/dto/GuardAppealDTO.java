package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Guard-specific command. Actor, eligibility and original run identity come exclusively from server readers. */
public class GuardAppealDTO {
    private Long guardLogId;
    private String arrivalRequestId;
    private String requestId;
    private String reason;
    private JsonNode evidence;
    private List<String> attachments;
    public Long getGuardLogId() { return guardLogId; }
    public void setGuardLogId(Long value) { guardLogId = value; }
    public String getArrivalRequestId() { return arrivalRequestId; }
    public void setArrivalRequestId(String value) { arrivalRequestId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public JsonNode getEvidence() { return evidence; }
    public void setEvidence(JsonNode value) { evidence = value; }
    public List<String> getAttachments() { return attachments; }
    public void setAttachments(List<String> value) { attachments = value; }
}
