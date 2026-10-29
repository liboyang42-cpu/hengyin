package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
import com.chengyinhub.business.service.support.CoopSettlementContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSettlementContractTest {

    private static final Date CUTOFF = new Date(1_000_000L);

    @Test
    void salesPercentUsesOneHeaderRoundThenLargestRemainderByRegistrationId() {
        CoopSettlementSnapshot snapshot = snapshot("MERCHANT", 88L, "MERCHANT_PARTICIPATION",
                "SALES_PERCENT", null, "50");
        List<CoopSettlementCandidate> candidates = Arrays.asList(
                candidate(3L, "SELF_PLAY_PASS", "0.01", 900_000L, true, false),
                candidate(1L, "SELF_PLAY_PASS", "0.01", 900_000L, true, false),
                candidate(2L, "SELF_PLAY_PASS", "0.01", 900_000L, true, false));

        List<CoopSettlementOriginLine> lines = CoopSettlementContract.originLines(snapshot, candidates, CUTOFF);

        assertAmount("0.01", line(lines, 1L).getOriginAmount(), "同余数按 registration.id 升序先补");
        assertAmount("0.01", line(lines, 2L).getOriginAmount(), "第二个 registration 得到第二分");
        assertAmount("0.00", line(lines, 3L).getOriginAmount(), "第三个不能逐行 HALF_UP 成 0.01");
        assertAmount("0.02", CoopSettlementContract.sumOrigin(lines),
                "3×¥0.01×50% 的头目标额只 ROUND 一次为 ¥0.02");
    }

    @Test
    void merchantAndOrganizerUsePerRegistrationEvidenceWithoutTopicLevelSwitch() {
        CoopSettlementSnapshot merchant = snapshot("MERCHANT", 88L, "MERCHANT_PARTICIPATION",
                "FIXED_PER_FULFILLMENT", "5.00", null);
        CoopSettlementCandidate guided = candidate(1L, "GUIDED_TICKET", "10", 900_000L, false, true);
        guided.setOrganizerScopeCovered(true);
        guided.setGroupEvidenceMatchesOrder(true);
        guided.setGroupVerifyTime(new Date(800_000L));
        guided.setGroupMemberVerifyTime(new Date(850_000L));
        CoopSettlementCandidate selfPlay = candidate(2L, "SELF_PLAY_PASS", "10", 900_000L, true, false);

        List<CoopSettlementOriginLine> merchantLines = CoopSettlementContract.originLines(
                merchant, Arrays.asList(guided, selfPlay), CUTOFF);

        assertEquals(2, merchantLines.size(), "商家对 GUIDED 认团证据、对 SELF_PLAY 认逐票 scan,不能 topic 级二选一");

        CoopSettlementSnapshot organizer = snapshot("CLUB", 99L, "ORGANIZER_LEAD",
                "FIXED_PER_FULFILLMENT", "3.00", null);
        List<CoopSettlementOriginLine> organizerLines = CoopSettlementContract.originLines(
                organizer, Arrays.asList(guided, selfPlay), CUTOFF);
        assertEquals(1, organizerLines.size(), "ORGANIZER_LEAD 只计冻结 scope 内 GUIDED_TICKET");
        assertEquals(Long.valueOf(1L), organizerLines.get(0).getRegistrationId(), "自玩票不得混入带队费");
    }

    @Test
    void cutoffStatusScopeKindsAndDistinctAreFailClosed() {
        CoopSettlementSnapshot snapshot = snapshot("MERCHANT", 88L, "MERCHANT_PARTICIPATION",
                "FIXED_PER_FULFILLMENT", "2.00", null);
        CoopSettlementCandidate valid = candidate(1L, "SELF_PLAY_PASS", "10", 999_999L, true, false);
        CoopSettlementCandidate duplicate = candidate(1L, "SELF_PLAY_PASS", "10", 999_999L, true, false);
        CoopSettlementCandidate atCutoff = candidate(2L, "SELF_PLAY_PASS", "10", 1_000_000L, true, false);
        CoopSettlementCandidate missingKind = candidate(3L, null, "10", 900_000L, true, false);
        CoopSettlementCandidate wrongState = candidate(4L, "SELF_PLAY_PASS", "10", 900_000L, true, false);
        wrongState.setPaymentStatus(1);
        CoopSettlementCandidate outsideScope = candidate(5L, "SELF_PLAY_PASS", "10", 900_000L, true, false);
        outsideScope.setScopeCovered(false);

        List<CoopSettlementOriginLine> lines = CoopSettlementContract.originLines(snapshot,
                Arrays.asList(valid, duplicate, atCutoff, missingKind, wrongState, outsideScope), CUTOFF);

        assertEquals(1, lines.size(), "重复 join 去重;截止等于端点/缺 kind/三状态错/越 scope 全部不入选");
        assertEquals(Long.valueOf(1L), lines.get(0).getRegistrationId(), "只留下唯一合格 registration");
    }

    @Test
    void guidedMerchantEvidenceMustMatchFrozenOrderAndBeStrictlyBeforeCutoff() {
        CoopSettlementSnapshot snapshot = snapshot("MERCHANT", 88L, "MERCHANT_PARTICIPATION",
                "FIXED_PER_FULFILLMENT", "2.00", null);
        CoopSettlementCandidate wrongOrder = candidate(1L, "GUIDED_TICKET", "10", 900_000L, false, true);
        wrongOrder.setOrganizerScopeCovered(true);
        wrongOrder.setGroupEvidenceMatchesOrder(false);
        wrongOrder.setGroupVerifyTime(new Date(800_000L));
        wrongOrder.setGroupMemberVerifyTime(new Date(850_000L));
        CoopSettlementCandidate memberAtCutoff = candidate(2L, "GUIDED_TICKET", "10", 900_000L, false, true);
        memberAtCutoff.setOrganizerScopeCovered(true);
        memberAtCutoff.setGroupEvidenceMatchesOrder(true);
        memberAtCutoff.setGroupVerifyTime(new Date(800_000L));
        memberAtCutoff.setGroupMemberVerifyTime(CUTOFF);
        CoopSettlementCandidate exactIntersection = candidate(3L, "GUIDED_TICKET", "10", 900_000L, false, true);
        exactIntersection.setOrganizerScopeCovered(true);
        exactIntersection.setGroupEvidenceMatchesOrder(true);
        exactIntersection.setGroupVerifyTime(new Date(800_000L));
        exactIntersection.setGroupMemberVerifyTime(new Date(850_000L));

        List<CoopSettlementOriginLine> lines = CoopSettlementContract.originLines(snapshot,
                Arrays.asList(wrongOrder, memberAtCutoff, exactIntersection), CUTOFF);

        assertEquals(1, lines.size(), "GUIDED 商家证据必须同时命中冻结 order/scope 且两段证据都早于 cutoff");
        assertEquals(Long.valueOf(3L), lines.get(0).getRegistrationId(), "order/scope 交集不可被 topic 级证据替代");
    }

    @Test
    void missingFrozenSnapshotIsManualRepairNotGuessing() {
        CoopSettlementSnapshot snapshot = snapshot("MERCHANT", 88L, "MERCHANT_PARTICIPATION",
                "FIXED_PER_FULFILLMENT", "2.00", null);
        snapshot.setEligiblePurchaseKinds(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CoopSettlementContract.originLines(snapshot,
                        Arrays.asList(candidate(1L, "SELF_PLAY_PASS", "10", 900_000L, true, false)), CUTOFF));

        assertTrue(error.getMessage().contains(CoopSettlementContract.MANUAL_REPAIR),
                "缺冻结快照必须进人工修复清单,实际:" + error.getMessage());
    }

    @Test
    void detailHeaderMismatchUsesLedgerConflictContract() {
        CoopSettlementSnapshot snapshot = snapshot("MERCHANT", 88L, "MERCHANT_PARTICIPATION",
                "FIXED_PER_FULFILLMENT", "2.00", null);
        CoopSettlementOriginLine line = new CoopSettlementOriginLine(1L, new BigDecimal("2.00"));
        line.setBeneficiaryId(99L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CoopSettlementContract.requireHeaderEquality(snapshot, line));

        assertTrue(error.getMessage().contains(CoopSettlementContract.LEDGER_CONFLICT),
                "detail/header 归属不一致必须 LEDGER_CONFLICT");
    }

    private static CoopSettlementOriginLine line(List<CoopSettlementOriginLine> lines, Long id) {
        for (CoopSettlementOriginLine line : lines) {
            if (id.equals(line.getRegistrationId())) return line;
        }
        throw new AssertionError("missing registration " + id);
    }

    private static CoopSettlementSnapshot snapshot(String role, Long beneficiaryId, String unit,
            String compensationMode, String fixedFee, String shareRate) {
        CoopSettlementSnapshot snapshot = new CoopSettlementSnapshot();
        snapshot.setOrderId(701L);
        snapshot.setTopicId(11L);
        snapshot.setFulfillmentStartInclusive(new Date(0));
        snapshot.setFulfillmentEndExclusive(CUTOFF);
        snapshot.setCommercialContextType("TOPIC_EDITION");
        snapshot.setCommercialContextId(11L);
        snapshot.setBeneficiaryRole(role);
        snapshot.setBeneficiaryId(beneficiaryId);
        snapshot.setServiceUnit(unit);
        snapshot.setScopeType("ORGANIZER");
        snapshot.setScopeId(501L);
        snapshot.setEligiblePurchaseKinds(Arrays.asList("GUIDED_TICKET", "SELF_PLAY_PASS"));
        snapshot.setFulfillmentEvidenceRule("V1_PER_REGISTRATION");
        snapshot.setCompensationMode(compensationMode);
        snapshot.setFixedFee(fixedFee == null ? null : new BigDecimal(fixedFee));
        snapshot.setShareRate(shareRate == null ? null : new BigDecimal(shareRate));
        snapshot.setSettlementSnapshotAt(new Date(1_100_000L));
        return snapshot;
    }

    private static CoopSettlementCandidate candidate(Long id, String purchaseKind, String sales,
            long evidenceAt, boolean scan, boolean guided) {
        CoopSettlementCandidate c = new CoopSettlementCandidate();
        c.setRegistrationId(id);
        c.setSourceTopicId(11L);
        c.setOrderQuantity(1L);
        c.setTicketOwnerMatches(true);
        c.setFulfillmentStartInclusive(new Date(0));
        c.setFulfillmentEndExclusive(CUTOFF);
        c.setOwnerType(1L);
        c.setOwnerId(11L);
        c.setRegistrationStatus(2);
        c.setPaymentStatus(2);
        c.setVerificationStatus(1);
        c.setPurchaseKind(purchaseKind);
        c.setEligibleSalesSnapshot(new BigDecimal(sales));
        c.setScopeCovered(true);
        c.setOrganizerScopeCovered(guided);
        c.setMerchantScanPresent(scan);
        c.setMerchantScanTime(new Date(evidenceAt));
        c.setVerificationTime(new Date(evidenceAt));
        return c;
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ",实际=" + actual);
    }
}
