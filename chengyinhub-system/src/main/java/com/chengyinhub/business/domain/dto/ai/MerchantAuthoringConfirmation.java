package com.chengyinhub.business.domain.dto.ai;

import java.util.Map;

/** Only human-reviewed text is accepted. Identity, method, money, inventory and publication are server-owned. */
public class MerchantAuthoringConfirmation {
    private Long candidateId;
    private Map<String, String> acceptedFields;
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long value) { candidateId = value; }
    public Map<String, String> getAcceptedFields() { return acceptedFields; }
    public void setAcceptedFields(Map<String, String> value) { acceptedFields = value; }
}
