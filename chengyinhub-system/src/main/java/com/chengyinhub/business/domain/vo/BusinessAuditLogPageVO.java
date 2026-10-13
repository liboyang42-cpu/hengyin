package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.BusinessAuditEvent;

import java.util.Collections;
import java.util.List;

public class BusinessAuditLogPageVO {
    private List<BusinessAuditEvent> items = Collections.emptyList();
    private Long nextCursor;

    public List<BusinessAuditEvent> getItems() { return items; }
    public void setItems(List<BusinessAuditEvent> items) { this.items = items; }
    public Long getNextCursor() { return nextCursor; }
    public void setNextCursor(Long nextCursor) { this.nextCursor = nextCursor; }
}
