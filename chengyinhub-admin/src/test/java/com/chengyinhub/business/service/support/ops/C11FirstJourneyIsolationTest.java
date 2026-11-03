package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.vo.AuditTrailEntryVO;
import com.chengyinhub.business.service.support.ExtraApproverReachability;
import com.chengyinhub.business.service.support.FinalCompensationFacts;

/**
 * C11 首个可跑旅程（隔离）：支持台按 requestId 定位原事件 → 不是当前投影成功 →
 * 原补偿入口有既有权限且可审计回查 → 删除证据表列出已删 vs 保留。
 */
class C11FirstJourneyIsolationTest {

    @Test
    void supportLocatesOriginalThenPermissionedCompensationThenDeletionEvidence() {
        String requestId = "c11-journey-req";
        BusinessAuditEvent original = new BusinessAuditEvent();
        original.setRequestId(requestId);
        original.setDomainType("ORDER");
        original.setDomainId(501L);
        original.setTargetType("ORDER");
        original.setTargetId(501L);
        original.setRelationId(77L);
        original.setAction("COMPENSATION_FREEZE");
        original.setAfterJson("UNKNOWN");
        original.setCreateTime(new Date(1L));
        BusinessAuditEvent runLink = new BusinessAuditEvent();
        runLink.setRequestId(requestId);
        runLink.setTargetType("RUN");
        runLink.setTargetId(77L);
        runLink.setAction("RUN_ATTACH");
        runLink.setAfterJson("UNKNOWN");

        SupportEventLocator locator = new SupportEventLocator(
                new InMemoryOriginalEventSource().add(original).add(runLink));
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                requestId, SupportEventLocator.PROJECTION_APPLIED_SUCCESS);

        SupportEventLocator.LocatedEvent located = locator.locateByRequestId(requestId, projection);
        assertEquals(requestId, located.getRequestId());
        assertEquals("UNKNOWN", located.getOriginalOutcome());
        assertTrue(located.currentProjectionClaimsSuccess());
        assertTrue(located.originalIsNotProjectionSuccess());
        assertEquals(77L, located.getRunId());
        assertEquals(501L, located.getOrderId());
        assertEquals(located.getOriginalOutcome(), locator.requery(requestId, projection).getOriginalOutcome());

        ClubEditionCompensationTerms frozen = new ClubEditionCompensationTerms();
        frozen.setTopicId(42L);
        frozen.setExecutingClubId(7L);
        frozen.setFixedCompensation(new BigDecimal("200.00"));
        frozen.setExtraApproverUserId(88L);
        frozen.setConfigHash("c11-hash");
        frozen.setFrozenAt(new Date(2L));
        AuditTrailEntryVO trailRow = new AuditTrailEntryVO();
        trailRow.setSource(AuditTrailEntryVO.SOURCE_BUSINESS_AUDIT_EVENT);
        trailRow.setAction("COMPENSATION_FREEZE");
        trailRow.setSourceId(1L);
        CompensationEntryProjection compensation = new CompensationEntryProjection(
                topicId -> frozen,
                new CompensationEntryProjection.CompensationAuditSource() {
                    @Override
                    public List<BusinessAuditEvent> eventsForRequest(String id) {
                        return locator.locateByRequestId(id, projection).getOriginalEvents();
                    }

                    @Override
                    public List<AuditTrailEntryVO> trailForTopic(Long topicId) {
                        return List.of(trailRow);
                    }
                });
        CompensationEntryProjection.ViewerGrant granted = new CompensationEntryProjection.ViewerGrant(
                88L, Set.of(ExtraApproverReachability.APPROVE_PERMISSION));
        FinalCompensationFacts currentFinal = FinalCompensationFacts.settled(
                7L, new BigDecimal("999.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        CompensationEntryProjection.View view = compensation.project(requestId, 42L, granted, currentFinal);
        assertTrue(view.permissioned());
        assertTrue(view.auditable());
        assertEquals(new BigDecimal("200.00"), view.getOriginalTerms().getFixedCompensation());
        assertNotEquals(new BigDecimal("999.00"), view.getOriginalTerms().getFixedCompensation());
        assertTrue(view.originalEntryNotReplacedByCurrentFinal());
        assertEquals(view.asRequeryableView(),
                compensation.requery(requestId, 42L, granted, currentFinal).asRequeryableView());

        CompensationEntryProjection.ViewerGrant denied = new CompensationEntryProjection.ViewerGrant(
                3L, Set.of());
        assertThrows(SecurityException.class, () -> compensation.project(requestId, 42L, denied, currentFinal));

        List<DataDeletionEvidenceTable.Row> deregister = DataDeletionEvidenceTable.byRequestKind("ACCOUNT_DEREGISTER");
        assertTrue(deregister.stream().anyMatch(row -> "member_identity".equals(row.artifact) && row.removed()));
        assertTrue(deregister.stream().anyMatch(row -> "oms_order".equals(row.artifact) && row.retained()));
        assertTrue(deregister.stream().anyMatch(row ->
                "business_audit_event".equals(row.artifact)
                        && row.disposition == DataDeletionEvidenceTable.Disposition.RETAINED_LEGAL));

        List<ReviewResultVisibilityAdapter.ReviewRound> review =
                ReviewResultVisibilityAdapter.flowSubmitRejectReviseApprove(
                        "c11-rev-1", 11L, 9L, "需修订", "c11-rev-2", 12L);
        assertTrue(ReviewResultVisibilityAdapter.visibility(review.get(3),
                ReviewResultVisibilityAdapter.Audience.PUBLIC).approvedResultVisible());
        assertEquals("2026-10-23 20:00:00",
                VersionedLocaleDisplay.format(VersionedLocaleDisplay.Market.CN,
                        Instant.parse("2026-10-23T12:00:00Z"), java.time.ZoneId.of("Asia/Shanghai")));
        assertEquals(VersionedLocaleDisplay.NOT_RUN,
                VersionedLocaleDisplay.legalGateStatus(VersionedLocaleDisplay.LegalGate.APP_STORE_REVIEW));
        assertEquals(VersionedLocaleDisplay.NOT_RUN,
                VersionedLocaleDisplay.legalGateStatus(VersionedLocaleDisplay.LegalGate.ICP_FILING));
    }
}
