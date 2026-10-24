package com.chengyinhub.business.service.support.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;

/**
 * C11 支持台定位器：按 requestId 找回同一 request / run / order 的<strong>原事件</strong>。
 *
 * <p>原事件真源是既有不可变 {@link BusinessAuditEvent}（只追加、不可改，见该类注释）。
 * 本适配器不写第二套审计表，也不把当前投影 APPLIED/SUCCESS 当成原请求成功。
 * 原 afterJson / action 为 UNKNOWN 时保持 UNKNOWN，可按同一 requestId 再查。
 */
public final class SupportEventLocator {

    /** 当前投影把原请求标成已成功时使用的标记；定位器必须忽略它。 */
    public static final String PROJECTION_APPLIED_SUCCESS = "APPLIED+SUCCESS";
    /**
     * 全局 selectByRequestId 不是缺口，保持拒绝。
     * 授权后的多事件页走 {@link AuthorizedTypedResourceEventPage}。
     */
    public static final String REQUEST_ID_QUERY = "businessAuditEvent.selectByRequestId";

    private final OriginalEventSource originalEvents;

    public SupportEventLocator(OriginalEventSource originalEvents) {
        this.originalEvents = Objects.requireNonNull(originalEvents, "originalEvents");
    }

    /**
     * 按 requestId 定位原事件。currentProjection 只作对照，不得覆盖原结果。
     *
     * Missing requestId stays Java {@code null}, never {@code ""}.
     *
     * @throws IllegalStateException 原事件不存在
     */
    public LocatedEvent locateByRequestId(String requestId, CurrentProjection currentProjection) {
        String id = parseRequestId(requestId);
        if (id == null) {
            return null;
        }
        List<BusinessAuditEvent> originals = originalEvents.findByRequestId(id);
        return locateFromOriginals(id, originals, currentProjection);
    }

    /** 同一 requestId 再查必须得到同一原结果，不能变成当前投影。 */
    public LocatedEvent requery(String requestId, CurrentProjection currentProjection) {
        return locateByRequestId(requestId, currentProjection);
    }

    public LocatedEvent recoverOriginal(String requestId, CurrentProjection currentProjection) {
        return requery(requestId, currentProjection);
    }

    public LocatedEvent locateByRunId(Long runId, CurrentProjection currentProjection) {
        if (runId == null || runId <= 0L) {
            throw new IllegalArgumentException("runId 不合法");
        }
        BusinessAuditEvent found = originalEvents.findByTarget("RUN", runId);
        if (found == null || isBlank(found.getRequestId())) {
            throw new IllegalStateException("原事件不存在 runId=" + runId);
        }
        LocatedEvent located = locateByRequestIdOrTarget(found.getRequestId(), found, currentProjection);
        if (!runId.equals(located.getRunId())) {
            throw new IllegalStateException("原事件不存在 runId=" + runId);
        }
        return located;
    }

    public LocatedEvent locateByOrderId(Long orderId, CurrentProjection currentProjection) {
        if (orderId == null || orderId <= 0L) {
            throw new IllegalArgumentException("orderId 不合法");
        }
        BusinessAuditEvent found = originalEvents.findByTarget("ORDER", orderId);
        if (found == null || isBlank(found.getRequestId())) {
            throw new IllegalStateException("原事件不存在 orderId=" + orderId);
        }
        LocatedEvent located = locateByRequestIdOrTarget(found.getRequestId(), found, currentProjection);
        if (!orderId.equals(located.getOrderId())) {
            throw new IllegalStateException("原事件不存在 orderId=" + orderId);
        }
        return located;
    }

    private LocatedEvent locateByRequestIdOrTarget(String requestId, BusinessAuditEvent targetFallback,
                                                   CurrentProjection currentProjection) {
        try {
            return locateByRequestId(requestId, currentProjection);
        } catch (WaitingSourceException ignored) {
            return locateFromOriginals(requestId, List.of(targetFallback), currentProjection);
        }
    }

    private LocatedEvent locateFromOriginals(String id, List<BusinessAuditEvent> originals,
                                             CurrentProjection currentProjection) {
        if (originals == null || originals.isEmpty()) {
            throw new IllegalStateException("原事件不存在 requestId=" + id);
        }
        BusinessAuditEvent head = originals.get(0);
        String originalOutcome = originalOutcome(head);
        boolean projectionClaimsSuccess = currentProjection != null
                && id.equals(currentProjection.requestId)
                && PROJECTION_APPLIED_SUCCESS.equals(currentProjection.appliedState);
        return new LocatedEvent(id, originals, originalOutcome, projectionClaimsSuccess,
                correlatedRunId(originals), correlatedOrderId(originals));
    }

    /** Missing requestId stays Java {@code null}, never {@code ""}. */
    static String parseRequestId(String requestId) {
        if (isBlank(requestId)) {
            return null;
        }
        String id = requestId.trim();
        if (id.length() > 64) {
            return null;
        }
        return id;
    }

    static String requireRequestId(String requestId) {
        String id = parseRequestId(requestId);
        if (id == null) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        return id;
    }

    private static String originalOutcome(BusinessAuditEvent event) {
        if (event == null) {
            return "UNKNOWN";
        }
        if (!isBlank(event.getAfterJson())) {
            return event.getAfterJson().trim();
        }
        if (!isBlank(event.getAction())) {
            return event.getAction().trim();
        }
        return "UNKNOWN";
    }

    private static Long correlatedRunId(List<BusinessAuditEvent> events) {
        return firstTargetId(events, "RUN");
    }

    private static Long correlatedOrderId(List<BusinessAuditEvent> events) {
        return firstTargetId(events, "ORDER");
    }

    private static Long firstTargetId(List<BusinessAuditEvent> events, String targetType) {
        for (BusinessAuditEvent event : events) {
            if (event != null && targetType.equals(event.getTargetType()) && event.getTargetId() != null) {
                return event.getTargetId();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 既有 business_audit_event 的只读查找；实现不得用当前投影表冒充原行。 */
    public interface OriginalEventSource {
        List<BusinessAuditEvent> findByRequestId(String requestId);

        BusinessAuditEvent findByTarget(String targetType, Long targetId);
    }

    /**
     * 生产消费者：走既有 {@link BusinessAuditEventMapper#selectByTarget}。
     * 全局 {@code selectByRequestId} 继续拒绝。授权页不从这里走。
     */
    public static final class MapperOriginalEventSource implements OriginalEventSource {
        private final BusinessAuditEventMapper mapper;

        public MapperOriginalEventSource(BusinessAuditEventMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "businessAuditEventMapper");
        }

        @Override
        public List<BusinessAuditEvent> findByRequestId(String requestId) {
            throw new WaitingSourceException(REQUEST_ID_QUERY);
        }

        @Override
        public BusinessAuditEvent findByTarget(String targetType, Long targetId) {
            if (isBlank(targetType) || targetId == null || targetId <= 0L) {
                return null;
            }
            List<BusinessAuditEvent> rows = mapper.selectByTarget(targetType, targetId);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            for (BusinessAuditEvent row : rows) {
                if (row != null && targetType.equals(row.getTargetType()) && targetId.equals(row.getTargetId())) {
                    return row;
                }
            }
            return null;
        }
    }

    public static final class WaitingSourceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String waitingCapability;

        public WaitingSourceException(String waitingCapability) {
            super("WAITING_SOURCE " + waitingCapability);
            this.waitingCapability = waitingCapability;
        }

        public String getWaitingCapability() {
            return waitingCapability;
        }
    }

    /** 当前读模型，不是原请求证明。 */
    public static final class CurrentProjection {
        public final String requestId;
        public final String appliedState;

        public CurrentProjection(String requestId, String appliedState) {
            this.requestId = requestId;
            this.appliedState = appliedState;
        }
    }

    public static final class LocatedEvent {
        private final String requestId;
        private final List<BusinessAuditEvent> originalEvents;
        private final String originalOutcome;
        private final boolean currentProjectionClaimsSuccess;
        private final Long runId;
        private final Long orderId;

        LocatedEvent(String requestId, List<BusinessAuditEvent> originalEvents, String originalOutcome,
                     boolean currentProjectionClaimsSuccess, Long runId, Long orderId) {
            this.requestId = requestId;
            this.originalEvents = Collections.unmodifiableList(new ArrayList<BusinessAuditEvent>(originalEvents));
            this.originalOutcome = originalOutcome;
            this.currentProjectionClaimsSuccess = currentProjectionClaimsSuccess;
            this.runId = runId;
            this.orderId = orderId;
        }

        public String getRequestId() {
            return requestId;
        }

        public List<BusinessAuditEvent> getOriginalEvents() {
            return originalEvents;
        }

        public String getOriginalOutcome() {
            return originalOutcome;
        }

        public boolean currentProjectionClaimsSuccess() {
            return currentProjectionClaimsSuccess;
        }

        /** 原结果不是成功；当前投影声称成功时仍以原结果为准。 */
        public boolean originalIsNotProjectionSuccess() {
            return !"SUCCESS".equals(originalOutcome)
                    && !PROJECTION_APPLIED_SUCCESS.equals(originalOutcome);
        }

        public Long getRunId() {
            return runId;
        }

        public Long getOrderId() {
            return orderId;
        }

        public Map<String, Object> asRequeryableView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("requestId", requestId);
            view.put("originalOutcome", originalOutcome);
            view.put("runId", runId);
            view.put("orderId", orderId);
            view.put("originalEventCount", originalEvents.size());
            view.put("usedCurrentProjectionAsSuccess", Boolean.FALSE);
            return Collections.unmodifiableMap(view);
        }
    }
}
