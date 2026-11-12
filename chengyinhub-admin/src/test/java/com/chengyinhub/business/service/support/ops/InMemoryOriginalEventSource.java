package com.chengyinhub.business.service.support.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.BusinessAuditEvent;

/** Isolation fixture: original business_audit_event rows only, never a current projection. */
final class InMemoryOriginalEventSource implements SupportEventLocator.OriginalEventSource {
    private final Map<String, List<BusinessAuditEvent>> byRequest = new LinkedHashMap<String, List<BusinessAuditEvent>>();
    private final Map<String, BusinessAuditEvent> byTarget = new LinkedHashMap<String, BusinessAuditEvent>();

    InMemoryOriginalEventSource add(BusinessAuditEvent event) {
        if (event == null || event.getRequestId() == null) {
            throw new IllegalArgumentException("原事件必须带 requestId");
        }
        byRequest.computeIfAbsent(event.getRequestId(), key -> new ArrayList<BusinessAuditEvent>()).add(event);
        if (event.getTargetType() != null && event.getTargetId() != null) {
            byTarget.put(event.getTargetType() + ":" + event.getTargetId(), event);
        }
        return this;
    }

    @Override
    public List<BusinessAuditEvent> findByRequestId(String requestId) {
        List<BusinessAuditEvent> found = byRequest.get(requestId);
        return found == null ? List.of() : List.copyOf(found);
    }

    @Override
    public BusinessAuditEvent findByTarget(String targetType, Long targetId) {
        return byTarget.get(targetType + ":" + targetId);
    }
}
