package com.chengyinhub.business.service.support.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.support.AuditTrailProjectionService;
import com.chengyinhub.business.service.support.ExtraApproverReachability;
import com.chengyinhub.business.service.support.FinalCompensationFacts;

/**
 * C11 原补偿入口投影：读开售冻结条款，不把当前 FINAL 投影当成原补偿成功。
 *
 * <p>权限不另造政策：只认既有 {@link ExtraApproverReachability#APPROVE_PERMISSION}
 * （与 {@code ClubEditionCompensationController} EXTRA 端点同一串），由调用方传入已经解析好的权限码，
 * 本适配器不查 JWT / 菜单 / RBAC。审计不另写表，只挂既有 {@link BusinessAuditEvent} /
 * {@link AuditTrailEntryVO} 留痕，同一 requestId 可回查。
 */
public final class CompensationEntryProjection {

    public static final String DENIED = "COMPENSATION_VIEW_DENIED";
    public static final String TERMS_MISSING = "EDITION_TERMS_MISSING";
    public static final String FROZEN_TERMS = "clubEditionCompensation.requireFrozenTerms";
    public static final String EXTRA_APPROVE = "extraApprover.canApproveExtraHours";
    public static final String AUDIT_TRAIL = "auditTrail.trail";
    /** 同一数字在不同 bizType 不是同一授权资源。公开 trail 不提供这个绑定。 */
    public static final String AUDIT_RESOURCE_BINDING = "auditTrail.resourceBinding";

    private final FrozenTermsSource termsSource;
    private final CompensationAuditSource auditSource;

    public CompensationEntryProjection(FrozenTermsSource termsSource, CompensationAuditSource auditSource) {
        this.termsSource = Objects.requireNonNull(termsSource, "termsSource");
        this.auditSource = Objects.requireNonNull(auditSource, "auditSource");
    }

    /**
     * 按 requestId + topicId 读原冻结补偿入口。viewer 必须持有既有 EXTRA 审批权限码。
     *
     * Missing requestId stays Java {@code null}, never {@code ""}.
     *
     * @param currentFinal 当前 FINAL 投影，仅作对照，不得覆盖冻结条款
     */
    public View project(String requestId, Long topicId, ViewerGrant viewer, FinalCompensationFacts currentFinal) {
        String id = SupportEventLocator.parseRequestId(requestId);
        if (id == null) {
            return null;
        }
        if (topicId == null || topicId <= 0L) {
            throw new IllegalArgumentException("topicId 不合法");
        }
        if (viewer == null || viewer.viewerId == null || viewer.viewerId <= 0L) {
            throw new SecurityException(DENIED);
        }
        if (!hasExistingApprovePermission(viewer.existingPermissionCodes)) {
            throw new SecurityException(DENIED);
        }
        ClubEditionCompensationTerms terms = termsSource.requireFrozenTerms(topicId);
        if (terms == null) {
            throw new IllegalStateException(TERMS_MISSING);
        }
        List<BusinessAuditEvent> originalAudit = auditSource.eventsForRequest(id);
        List<AuditTrailEntryVO> trail = auditSource.trailForTopic(topicId);
        return new View(id, topicId, viewer.viewerId, ExtraApproverReachability.APPROVE_PERMISSION,
                terms, originalAudit, trail, currentFinal);
    }

    public View requery(String requestId, Long topicId, ViewerGrant viewer, FinalCompensationFacts currentFinal) {
        return project(requestId, topicId, viewer, currentFinal);
    }

    public View recoverOriginal(String requestId, Long topicId, ViewerGrant viewer, FinalCompensationFacts currentFinal) {
        return requery(requestId, topicId, viewer, currentFinal);
    }

    /**
     * 用既有 ExtraApproverReachability.canApproveExtraHours 签发 viewer，再读冻结条款。
     * 缺端口 WAITING_SOURCE {@link #EXTRA_APPROVE}。不查 JWT，不改共享 Reachability。
     */
    public View projectFromReachability(String requestId, Long topicId, Long viewerId,
                                        ExtraApproverSource reachability, FinalCompensationFacts currentFinal) {
        if (reachability == null) {
            throw new WaitingSourceException(EXTRA_APPROVE);
        }
        if (viewerId == null || viewerId <= 0L || !reachability.canApproveExtraHours(viewerId)) {
            throw new SecurityException(DENIED);
        }
        return project(requestId, topicId,
                new ViewerGrant(viewerId, Set.of(ExtraApproverReachability.APPROVE_PERMISSION)),
                currentFinal);
    }

    /** 消费公开 {@link IClubEditionCompensationService#requireFrozenTerms}；缺服务 WAITING_SOURCE。 */
    public static FrozenTermsSource fromClubCompensation(IClubEditionCompensationService compensation) {
        if (compensation == null) {
            return topicId -> {
                throw new WaitingSourceException(FROZEN_TERMS);
            };
        }
        return compensation::requireFrozenTerms;
    }

    /**
     * 只读既有 {@link AuditTrailProjectionService#trail}。必须消费已经过
     * {@link #project} 的授权对象，并复核当前 viewer 的既有审批权限。
     * 无权限、账号切换或资源不属于该授权对象时不进 reader。bizType 仍由调用方给出。
     * 缺端口 WAITING_SOURCE。空列表是真的空留痕，不另写审计表。
     */
    public List<AuditTrailEntryVO> recoverTrail(View authorized, ViewerGrant viewer, String bizType, Long bizId,
            AuditTrailReader reader) {
        if (authorized == null || viewer == null || viewer.viewerId == null || viewer.viewerId.longValue() <= 0L
                || !viewer.viewerId.equals(authorized.getViewerId())
                || !hasExistingApprovePermission(viewer.existingPermissionCodes)) {
            throw new SecurityException(DENIED);
        }
        if (bizType == null || bizType.isBlank() || bizId == null || bizId.longValue() <= 0L) {
            return null;
        }
        if (!bizId.equals(authorized.getTopicId())) {
            throw new SecurityException(DENIED);
        }
        throw new WaitingSourceException(AUDIT_RESOURCE_BINDING);
    }

    public static AuditTrailReader fromAuditTrail(AuditTrailProjectionService service) {
        if (service == null) {
            return (bizType, bizId) -> {
                throw new WaitingSourceException(AUDIT_TRAIL);
            };
        }
        return service::trail;
    }

    public static ExtraApproverSource fromReachability(ExtraApproverReachability reachability) {
        if (reachability == null) {
            return userId -> {
                throw new WaitingSourceException(EXTRA_APPROVE);
            };
        }
        return reachability::canApproveExtraHours;
    }

    static boolean hasExistingApprovePermission(Set<String> existingPermissionCodes) {
        if (existingPermissionCodes == null || existingPermissionCodes.isEmpty()) {
            return false;
        }
        return existingPermissionCodes.contains(ExtraApproverReachability.APPROVE_PERMISSION);
    }

    /** 开售冻结条款真源，复用 {@code IClubEditionCompensationService#requireFrozenTerms} 合同。 */
    public interface FrozenTermsSource {
        ClubEditionCompensationTerms requireFrozenTerms(Long topicId);
    }

    /** 只读统一留痕；不写审计表。 */
    public interface AuditTrailReader {
        List<AuditTrailEntryVO> trail(String bizType, Long bizId);
    }

    /** 只回答权限位可达，不代替冻结审批人。 */
    public interface ExtraApproverSource {
        boolean canApproveExtraHours(Long userId);
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

    /** 既有审计真源；不得新建补偿审计表。 */
    public interface CompensationAuditSource {
        List<BusinessAuditEvent> eventsForRequest(String requestId);

        List<AuditTrailEntryVO> trailForTopic(Long topicId);
    }

    /** 调用方已经从既有 RBAC 解析出的权限码，本类不再查库。 */
    public static final class ViewerGrant {
        public final Long viewerId;
        public final Set<String> existingPermissionCodes;

        public ViewerGrant(Long viewerId, Set<String> existingPermissionCodes) {
            this.viewerId = viewerId;
            this.existingPermissionCodes = existingPermissionCodes == null
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(existingPermissionCodes);
        }
    }

    public static final class View {
        private final String requestId;
        private final Long topicId;
        private final Long viewerId;
        private final String permissionCode;
        private final ClubEditionCompensationTerms originalTerms;
        private final List<BusinessAuditEvent> originalAudit;
        private final List<AuditTrailEntryVO> trail;
        private final FinalCompensationFacts currentFinal;

        View(String requestId, Long topicId, Long viewerId, String permissionCode,
             ClubEditionCompensationTerms originalTerms, List<BusinessAuditEvent> originalAudit,
             List<AuditTrailEntryVO> trail, FinalCompensationFacts currentFinal) {
            this.requestId = requestId;
            this.topicId = topicId;
            this.viewerId = viewerId;
            this.permissionCode = permissionCode;
            this.originalTerms = originalTerms;
            this.originalAudit = originalAudit == null
                    ? Collections.<BusinessAuditEvent>emptyList()
                    : Collections.unmodifiableList(new ArrayList<BusinessAuditEvent>(originalAudit));
            this.trail = trail == null
                    ? Collections.<AuditTrailEntryVO>emptyList()
                    : Collections.unmodifiableList(new ArrayList<AuditTrailEntryVO>(trail));
            this.currentFinal = currentFinal;
        }

        public String getRequestId() {
            return requestId;
        }

        public Long getTopicId() {
            return topicId;
        }

        public Long getViewerId() {
            return viewerId;
        }

        public String getPermissionCode() {
            return permissionCode;
        }

        public ClubEditionCompensationTerms getOriginalTerms() {
            return originalTerms;
        }

        public List<BusinessAuditEvent> getOriginalAudit() {
            return originalAudit;
        }

        public List<AuditTrailEntryVO> getTrail() {
            return trail;
        }

        public boolean permissioned() {
            return ExtraApproverReachability.APPROVE_PERMISSION.equals(permissionCode);
        }

        public boolean auditable() {
            return !originalAudit.isEmpty() || !trail.isEmpty();
        }

        /** 当前 FINAL 即使 settled，也不能把冻结条款改写成「原操作已成功」。 */
        public boolean originalEntryNotReplacedByCurrentFinal() {
            if (currentFinal == null) {
                return true;
            }
            if (currentFinal.isPending()) {
                return true;
            }
            return originalTerms.getFixedCompensation() != null
                    && originalTerms.getConfigHash() != null;
        }

        public Map<String, Object> asRequeryableView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("requestId", requestId);
            view.put("topicId", topicId);
            view.put("permissionCode", permissionCode);
            view.put("frozenAt", originalTerms.getFrozenAt());
            view.put("configHash", originalTerms.getConfigHash());
            view.put("fixedCompensation", originalTerms.getFixedCompensation());
            view.put("extraApproverUserId", originalTerms.getExtraApproverUserId());
            view.put("auditEventCount", originalAudit.size());
            view.put("trailCount", trail.size());
            view.put("currentFinalPending", currentFinal != null && currentFinal.isPending());
            return Collections.unmodifiableMap(view);
        }
    }
}
