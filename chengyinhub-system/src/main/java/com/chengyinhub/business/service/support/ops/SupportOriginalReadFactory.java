package com.chengyinhub.business.service.support.ops;

import java.util.Objects;

import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;

/**
 * Reads through the existing audit-log query and the original event locator.
 * This is not a controller and does not replace the order or withdrawal entries.
 */
public final class SupportOriginalReadFactory {
    private final IBusinessAuditLogQueryService auditLogQuery;
    private final SupportEventLocator locator;

    public SupportOriginalReadFactory(IBusinessAuditLogQueryService auditLogQuery,
                                      BusinessAuditEventMapper mapper) {
        this.auditLogQuery = Objects.requireNonNull(auditLogQuery, "auditLogQuery");
        this.locator = new SupportEventLocator(new SupportEventLocator.MapperOriginalEventSource(
                Objects.requireNonNull(mapper, "businessAuditEventMapper")));
    }

    /** The same read {@code BusinessAuditLogController} already exposes. */
    public BusinessAuditLogPageVO list(BusinessAuditLogQueryDTO query, boolean platformAdmin) {
        return auditLogQuery.query(query, platformAdmin);
    }

    public SupportEventLocator.LocatedEvent locateOrder(Long orderId,
                                                        SupportEventLocator.CurrentProjection projection) {
        return locator.locateByOrderId(orderId, projection);
    }

    public SupportEventLocator.LocatedEvent locateRun(Long runId,
                                                      SupportEventLocator.CurrentProjection projection) {
        return locator.locateByRunId(runId, projection);
    }
}
