package com.chengyinhub.business.service;

import com.fasterxml.jackson.databind.JsonNode;

/** W8-06 port, NOT an assertion that an upstream engine already implements these signatures. */
public interface GuardArrivalEvidenceReader {
    /** Load the authenticated actor's durable original arrive command and its exact guard log. No client snapshot. */
    Origin readOwned(Long memberId, Long guardLogId, String arrivalRequestId);

    /**
     * Same proof plus original-run lifecycle/authority lock, held in caller transaction through audit writes.
     * Lock original runtime BEFORE audit task/appeal rows. Never create a run, backfill binding, clear risk or award.
     * Implementation must use the released actual runtime contract; no implementation/default fallback is supplied here.
     */
    Origin lockOwned(Long memberId, Long guardLogId, String arrivalRequestId);

    record Origin(JsonNode receipt, boolean originalRunActive) { }
}
