package com.chengyinhub.business.domain.dto.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** New explicit action key; no client-supplied execution or quota identity. */
public record AiThemeDraftPrepareReq(String prepareKey, String idea, Integer productType,
        List<String> existingNodeNames) {
    public AiThemeDraftPrepareReq {
        if (existingNodeNames != null)
            existingNodeNames = Collections.unmodifiableList(new ArrayList<>(existingNodeNames));
    }
}
