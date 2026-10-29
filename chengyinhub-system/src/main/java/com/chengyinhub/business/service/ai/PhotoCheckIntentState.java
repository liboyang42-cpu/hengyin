package com.chengyinhub.business.service.ai;

/** Local application progress; RESULT_READY may contain the original degraded decision. */
public enum PhotoCheckIntentState {
    PREPARED, IN_FLIGHT, UNKNOWN, RESULT_READY, APPLIED, NOT_SENT
}
