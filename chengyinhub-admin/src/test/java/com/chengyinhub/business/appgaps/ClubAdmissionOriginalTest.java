package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubAdmissionOriginalTest {
    static final String RID = "aaaaaaaa-1111-4111-8111-111111111111";

    @Test void digestMatchesAppFormula() {
        // App: SHA256(action + "\n" + message)
        assertEquals(AppOriginalRequestLedger.sha256Hex("apply\nhi"), ClubAdmissionOriginal.digest("apply", "hi"));
        assertEquals(AppOriginalRequestLedger.sha256Hex("join\n"), ClubAdmissionOriginal.digest("join", null));
    }

    @Test void refusesMismatchedActionRouteOrDigestWithoutCallingLegacy() {
        ClubAdmissionOriginal o = new ClubAdmissionOriginal(mock(AppOriginalRequestLedger.class));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(400, o.write(9, 5L, true, RID, "join", ClubAdmissionOriginal.digest("join", ""), null, () -> { calls.incrementAndGet(); return AjaxResult.success(); }).get("code"));
        assertEquals(400, o.write(9, 5L, false, RID, "leave", ClubAdmissionOriginal.digest("leave", ""), null, AjaxResult::success).get("code"));
        assertEquals(400, o.write(9, 5L, false, RID, "apply", ClubAdmissionOriginal.digest("apply", "x"), "y", AjaxResult::success).get("code"));
        assertEquals(400, o.write(9, 5L, false, RID, "join", ClubAdmissionOriginal.digest("join", ""), "sneaky", AjaxResult::success).get("code"));
        assertEquals(0, calls.get());
    }

    @SuppressWarnings("unchecked")
    @Test void committedWriteProjectsAppReceipt() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = ClubAdmissionOriginal.digest("apply", "hi");
        when(ledger.run(eq(ClubAdmissionOriginal.PROTOCOL), eq(9L), eq(RID), eq("club:5"), eq(sha), any(java.util.function.Supplier.class))).thenAnswer(a -> {
            Map<String, Object> result = ((java.util.function.Supplier<Map<String, Object>>) a.getArgument(5)).get();
            return new AppOriginalRequestLedger.Fact("COMMITTED", RID, "club:5", sha, result, null, null, false);
        });
        AjaxResult r = new ClubAdmissionOriginal(ledger).write(9, 5L, false, RID, "apply", sha, "hi",
                () -> new AjaxResult(200, "申请已提交，等待主理人审核", Map.of("state", "pending")));
        assertEquals(200, r.get("code")); assertEquals("申请已提交，等待主理人审核", r.get("msg"));
        assertEquals(Map.of("actorMemberId", 9L, "clubId", 5L, "requestId", RID, "action", "apply", "state", "APPLIED",
                "membershipState", "pending", "payloadSHA256", sha), r.get("data"));
    }

    @SuppressWarnings("unchecked")
    @Test void legacyErrorBecomesRecordedRejection() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = ClubAdmissionOriginal.digest("leave", "");
        when(ledger.run(any(), anyLong(), any(), any(), any(), any(java.util.function.Supplier.class))).thenAnswer(a -> {
            try { ((java.util.function.Supplier<Map<String, Object>>) a.getArgument(5)).get(); fail(); }
            catch (com.chengyinhub.common.exception.ServiceException e) {
                return new AppOriginalRequestLedger.Fact("REJECTED", RID, "club:5", sha, null, e.getCode(), e.getMessage(), false);
            }
            return null;
        });
        AjaxResult r = new ClubAdmissionOriginal(ledger).write(9, 5L, true, RID, "leave", sha, null,
                () -> AjaxResult.error("主理人不能直接退出"));
        assertEquals(500, r.get("code"));
    }

    @Test void receiptUnknownWhenNeverRecorded() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = "e".repeat(64);
        when(ledger.read(ClubAdmissionOriginal.PROTOCOL, 9L, RID, "club:5", sha))
                .thenReturn(new AppOriginalRequestLedger.Fact("NOT_FOUND", RID, "club:5", sha, null, null, null, false));
        AjaxResult r = new ClubAdmissionOriginal(ledger).receipt(9, Map.of("clubId", 5, "requestId", RID, "action", "join", "payloadSHA256", sha));
        assertEquals(200, r.get("code"));
        assertEquals("UNKNOWN", ((Map<?, ?>) r.get("data")).get("state"));
        assertNull(((Map<?, ?>) r.get("data")).get("membershipState"));
        assertEquals(400, new ClubAdmissionOriginal(ledger).receipt(9, Map.of("clubId", 5)).get("code"));
    }
}
