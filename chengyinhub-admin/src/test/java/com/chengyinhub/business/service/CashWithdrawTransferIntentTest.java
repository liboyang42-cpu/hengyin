package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CashWithdrawTransferIntentTest {
    @Test
    void immutableDebitIdentityIncludesBalancesAndOriginalChannelParameters() {
        MemberCashWithdraw row = original();
        CashWithdrawTransferIntent intent = intent(row);
        assertDoesNotThrow(() -> intent.requireMatches(row));
        assertEquals(3000L, intent.amountFen());
        assertEquals(new BigDecimal("30.00"), intent.amountYuan());
        assertTrue(intent.requestHash().matches("[0-9a-f]{64}"));
        assertEquals(intent.requestHash(), intent(original()).requestHash());
        assertNotEquals(intent.requestHash(), CashWithdrawTransferIntent.create(row, "other-merchant", "wx-original",
                "original remark", new BigDecimal("100.00"), new BigDecimal("70.00")).requestHash());
        assertThrows(IllegalStateException.class, () -> CashWithdrawTransferIntent.create(row, "merchant", "wx-original",
                "original remark", new BigDecimal("100.00"), new BigDecimal("71.00")));
    }

    @Test
    void changedOriginalRowOrHashCannotAuthorizeAnyRecovery() {
        CashWithdrawTransferIntent original = intent(original());
        for (String changed : new String[]{"member", "amount", "openid", "batch", "detail"}) {
            MemberCashWithdraw row = original();
            switch (changed) {
                case "member" -> row.setMemberId(99L);
                case "amount" -> row.setAmount(new BigDecimal("30.01"));
                case "openid" -> row.setOpenid("other-openid");
                case "batch" -> row.setOutBatchNo("CW99");
                case "detail" -> row.setOutDetailNo("CD99");
                default -> throw new AssertionError(changed);
            }
            assertThrows(IllegalStateException.class, () -> original.requireMatches(row), changed);
        }
        CashWithdrawTransferIntent corrupted = new CashWithdrawTransferIntent(original.withdrawId(), original.memberId(),
                original.mchid(), original.appid(), original.outBatchNo(), original.outDetailNo(), original.amountFen(),
                original.openid(), original.remark(), original.requestVersion(), "0".repeat(64), original.balanceBefore(),
                original.balanceAfter(), true, false);
        assertThrows(IllegalStateException.class, corrupted::requireValid);
    }

    @Test
    void channelTerminalObservationMustMatchEveryOriginalFactAndActualFinalState() {
        CashWithdrawTransferIntent intent = intent(original());
        for (String state : new String[]{"FAIL", "SUCCESS"}) {
            Map<String, String> facts = facts(state);
            assertTrue(intent.matchesObservation(facts));
            for (String key : new String[]{"mchid", "appid", "outBatchNo", "outDetailNo", "openid", "transferAmount",
                    "batchStatus", "detailStatus", "result", "batchId", "detailId"}) {
                Map<String, String> missing = new HashMap<>(facts);
                missing.remove(key);
                assertFalse(intent.matchesObservation(missing), "missing " + key);
                if (!"batchId".equals(key) && !"detailId".equals(key)) {
                    Map<String, String> wrong = new HashMap<>(facts);
                    wrong.put(key, "transferAmount".equals(key) ? "3001" : "OTHER");
                    assertFalse(intent.matchesObservation(wrong), "wrong " + key);
                } else {
                    // Provider-assigned IDs are unknown at DEBIT time; existing receipts bind their identity.
                    Map<String, String> allocated = new HashMap<>(facts);
                    allocated.put(key, "another-nonempty-provider-id");
                    assertTrue(intent.matchesObservation(allocated), "provider assigns " + key);
                    allocated.put(key, " ");
                    assertFalse(intent.matchesObservation(allocated), "blank " + key);
                }
            }
            Map<String, String> inconsistent = new HashMap<>(facts);
            inconsistent.put("detailStatus", "FAIL".equals(state) ? "SUCCESS" : "FAIL");
            assertFalse(intent.matchesObservation(inconsistent));
        }
        assertFalse(intent.matchesObservation(null));
        assertFalse(intent.matchesObservation(Map.of("result", "UNKNOWN")));
        Map<String, String> closed = facts("FAIL");
        closed.put("batchStatus", "CLOSED");
        assertFalse(intent.matchesObservation(closed), "a closed batch is not a failed original detail");
    }

    @Test
    void localNotSentEvidenceRequiresAnOriginalSubmissionClaim() {
        CashWithdrawTransferIntent original = intent(original());
        CashWithdrawTransferIntent impossible = new CashWithdrawTransferIntent(original.withdrawId(), original.memberId(),
                original.mchid(), original.appid(), original.outBatchNo(), original.outDetailNo(), original.amountFen(),
                original.openid(), original.remark(), original.requestVersion(), original.requestHash(), original.balanceBefore(),
                original.balanceAfter(), false, true);
        assertThrows(IllegalStateException.class, impossible::requireValid);
    }

    static MemberCashWithdraw original() {
        MemberCashWithdraw row = new MemberCashWithdraw();
        row.setId(7L); row.setMemberId(42L); row.setAmount(new BigDecimal("30.00"));
        row.setOpenid("original-openid"); row.setOutBatchNo("CW7"); row.setOutDetailNo("CD7");
        row.setStatus(MemberCashWithdraw.STATUS_PROCESSING); row.setDelFlag(0); row.setFrozen(0);
        return row;
    }

    static CashWithdrawTransferIntent intent(MemberCashWithdraw row) {
        return CashWithdrawTransferIntent.create(row, "merchant", "wx-original", "original remark",
                new BigDecimal("100.00"), new BigDecimal("70.00"));
    }

    static Map<String, String> facts(String result) {
        Map<String, String> facts = new HashMap<>();
        facts.put("mchid", "merchant"); facts.put("appid", "wx-original");
        facts.put("outBatchNo", "CW7"); facts.put("outDetailNo", "CD7");
        facts.put("openid", "original-openid"); facts.put("transferAmount", "3000");
        facts.put("batchId", "original-batch"); facts.put("detailId", "original-detail");
        facts.put("batchStatus", "FINISHED"); facts.put("detailStatus", result); facts.put("result", result);
        facts.put("detailId", "original-detail");
        return facts;
    }
}
