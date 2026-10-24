package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import com.chengyinhub.business.service.support.ExtraApproverReachability;
import com.chengyinhub.business.service.support.FinalCompensationFacts;

class CompensationEntryProjectionTest {

    @Test
    void permissionedViewerSeesFrozenOriginalNotCurrentFinalSuccess() {
        ClubEditionCompensationTerms frozen = frozenTerms();
        BusinessAuditEvent original = new BusinessAuditEvent();
        original.setRequestId("comp-req-1");
        original.setAction("COMPENSATION_FREEZE");
        original.setAfterJson("FROZEN");
        AuditTrailEntryVO trail = new AuditTrailEntryVO();
        trail.setSource(AuditTrailEntryVO.SOURCE_BUSINESS_AUDIT_EVENT);
        trail.setAction("COMPENSATION_FREEZE");
        CompensationEntryProjection projection = new CompensationEntryProjection(
                topicId -> frozen,
                new CompensationAuditSource() {
                    @Override
                    public List<BusinessAuditEvent> eventsForRequest(String requestId) {
                        return List.of(original);
                    }

                    @Override
                    public List<AuditTrailEntryVO> trailForTopic(Long topicId) {
                        return List.of(trail);
                    }
                });
        FinalCompensationFacts current = FinalCompensationFacts.settled(
                7L, new BigDecimal("999"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        CompensationEntryProjection.ViewerGrant viewer = new CompensationEntryProjection.ViewerGrant(
                88L, Set.of(ExtraApproverReachability.APPROVE_PERMISSION));

        CompensationEntryProjection.View view = projection.project("comp-req-1", 10L, viewer, current);
        CompensationEntryProjection.View again = projection.requery("comp-req-1", 10L, viewer, current);

        assertTrue(view.permissioned());
        assertTrue(view.auditable());
        assertEquals(new BigDecimal("200.00"), view.getOriginalTerms().getFixedCompensation());
        assertEquals("hash-v1", view.getOriginalTerms().getConfigHash());
        assertTrue(view.originalEntryNotReplacedByCurrentFinal());
        assertEquals(view.asRequeryableView(), again.asRequeryableView());
        assertEquals(ExtraApproverReachability.APPROVE_PERMISSION, view.getPermissionCode());
    }

    @Test
    void missingExistingPermissionIsDeniedAndDoesNotLeakTerms() {
        CompensationEntryProjection projection = new CompensationEntryProjection(
                topicId -> frozenTerms(),
                new CompensationAuditSource() {
                    @Override
                    public List<BusinessAuditEvent> eventsForRequest(String requestId) {
                        throw new AssertionError("denied viewer must not read audit");
                    }

                    @Override
                    public List<AuditTrailEntryVO> trailForTopic(Long topicId) {
                        throw new AssertionError("denied viewer must not read trail");
                    }
                });
        CompensationEntryProjection.ViewerGrant stranger = new CompensationEntryProjection.ViewerGrant(
                3L, Set.of("business:topic:list"));
        SecurityException denied = assertThrows(SecurityException.class,
                () -> projection.project("comp-req-1", 10L, stranger, null));
        assertEquals(CompensationEntryProjection.DENIED, denied.getMessage());
    }

    @Test
    void inventedPermissionCodeIsNotAccepted() {
        CompensationEntryProjection projection = new CompensationEntryProjection(
                topicId -> frozenTerms(),
                new CompensationAuditSource() {
                    @Override
                    public List<BusinessAuditEvent> eventsForRequest(String requestId) {
                        return List.of();
                    }

                    @Override
                    public List<AuditTrailEntryVO> trailForTopic(Long topicId) {
                        return List.of();
                    }
                });
        CompensationEntryProjection.ViewerGrant forged = new CompensationEntryProjection.ViewerGrant(
                88L, Set.of("business:compensation:view"));
        assertThrows(SecurityException.class, () -> projection.project("comp-req-1", 10L, forged, null));
    }

    @Test
    void missingRequestIdStaysNullNeverEmptyString() {
        CompensationEntryProjection projection = new CompensationEntryProjection(
                topicId -> {
                    throw new AssertionError("missing requestId must not read terms");
                },
                new CompensationAuditSource() {
                    @Override
                    public List<BusinessAuditEvent> eventsForRequest(String requestId) {
                        throw new AssertionError("missing requestId must not read audit");
                    }

                    @Override
                    public List<AuditTrailEntryVO> trailForTopic(Long topicId) {
                        throw new AssertionError("missing requestId must not read trail");
                    }
                });
        CompensationEntryProjection.ViewerGrant viewer = new CompensationEntryProjection.ViewerGrant(
                88L, Set.of(ExtraApproverReachability.APPROVE_PERMISSION));
        assertNull(projection.project(null, 10L, viewer, null));
        assertNull(projection.project("  ", 10L, viewer, null));
        assertNull(projection.requery("", 10L, viewer, null));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAuditApplies() {
        Map<String, List<BusinessAuditEvent>> byRequest = new LinkedHashMap<String, List<BusinessAuditEvent>>();
        CompensationEntryProjection projection = new CompensationEntryProjection(
                topicId -> frozenTerms(),
                new CompensationAuditSource() {
                    @Override
                    public List<BusinessAuditEvent> eventsForRequest(String requestId) {
                        List<BusinessAuditEvent> found = byRequest.get(requestId);
                        return found == null ? List.of() : found;
                    }

                    @Override
                    public List<AuditTrailEntryVO> trailForTopic(Long topicId) {
                        return List.of();
                    }
                });
        CompensationEntryProjection.ViewerGrant viewer = new CompensationEntryProjection.ViewerGrant(
                88L, Set.of(ExtraApproverReachability.APPROVE_PERMISSION));
        assertNull(projection.recoverOriginal(null, 10L, viewer, null));
        assertNull(projection.recoverOriginal("  ", 10L, viewer, null));
        CompensationEntryProjection.View recovered = projection.recoverOriginal("c11-comp-never", 10L, viewer, null);
        assertEquals("c11-comp-never", recovered.getRequestId());
        assertTrue(recovered.getOriginalAudit().isEmpty());
        assertTrue(byRequest.isEmpty());
        BusinessAuditEvent row = new BusinessAuditEvent();
        row.setRequestId("c11-comp-never");
        row.setAction("COMPENSATION_FREEZE");
        row.setAfterJson("FROZEN");
        byRequest.put("c11-comp-never", List.of(row));
        CompensationEntryProjection.View first = projection.recoverOriginal("c11-comp-never", 10L, viewer, null);
        assertEquals("c11-comp-never", first.getRequestId());
        assertEquals(1, first.getOriginalAudit().size());
        assertEquals("FROZEN", first.getOriginalAudit().get(0).getAfterJson());
        assertEquals(1, byRequest.get("c11-comp-never").size());
        assertEquals(1, byRequest.size());
    }

    private static ClubEditionCompensationTerms frozenTerms() {
        ClubEditionCompensationTerms terms = new ClubEditionCompensationTerms();
        terms.setTopicId(10L);
        terms.setExecutingClubId(7L);
        terms.setFixedCompensation(new BigDecimal("200.00"));
        terms.setExtraApproverUserId(88L);
        terms.setConfigHash("hash-v1");
        terms.setFrozenAt(new Date(1_700_000_000_000L));
        return terms;
    }

    private interface CompensationAuditSource extends CompensationEntryProjection.CompensationAuditSource {
    }
}
