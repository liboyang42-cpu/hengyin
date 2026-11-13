package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

import static com.chengyinhub.business.appgaps.AppOriginalRequestLedger.sha256Hex;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubBadgeOriginalTest {
    static final String RID = "bbbbbbbb-1111-4111-8111-111111111111";

    @Test void sourceHashFollowsAppFormula() {
        String create = sha256Hex(String.join("\u001f", "CLUB_BADGE_V1", "CREATE", "7", sha256Hex("Trail"), sha256Hex("")));
        assertEquals(create, ClubBadgeOriginal.sourceHash("CREATE", Map.of("clubId", 7, "badgeName", "Trail")));
        String grant = sha256Hex(String.join("\u001f", "CLUB_BADGE_V1", "GRANT", "7", "12", sha256Hex("CLUB7_1")));
        assertEquals(grant, ClubBadgeOriginal.sourceHash("GRANT", Map.of("clubId", 7, "memberId", 12, "badgeCode", "CLUB7_1")));
        assertThrows(RuntimeException.class, () -> ClubBadgeOriginal.sourceHash("CREATE", Map.of("clubId", 7, "badgeName", " pad ")));
        assertThrows(RuntimeException.class, () -> ClubBadgeOriginal.sourceHash("CREATE", Map.of("clubId", 7, "badgeName", "x", "iconUrl", "u")));
    }

    @Test void wrongHeaderIsRefusedBeforeLedger() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        AjaxResult r = new ClubBadgeOriginal(ledger).write(9, "CREATE", RID, "f".repeat(64), Map.of("clubId", 7, "badgeName", "T"), c -> 1L, AjaxResult::success);
        assertEquals(400, r.get("code"));
        verifyNoInteractions(ledger);
    }

    @SuppressWarnings("unchecked")
    @Test void grantProjectsTerminalReceiptWithAuditId() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        Map<String, Object> body = Map.of("clubId", 7, "memberId", 12, "badgeCode", "CLUB7_1");
        String sha = ClubBadgeOriginal.sourceHash("GRANT", body);
        when(ledger.run(eq(ClubBadgeOriginal.PROTOCOL), eq(9L), eq(RID), eq("club:7:GRANT"), eq(sha), any(LongFunction.class))).thenAnswer(a -> {
            Map<String, Object> res = ((LongFunction<Map<String, Object>>) a.getArgument(5)).apply(55L);
            return new AppOriginalRequestLedger.Fact("COMMITTED", RID, "club:7:GRANT", sha, res, null, null, false);
        });
        AjaxResult r = new ClubBadgeOriginal(ledger).write(9, "GRANT", RID, sha, body, c -> 3L, () -> AjaxResult.success("已颁发过"));
        Map<String, Object> d = (Map<String, Object>) r.get("data");
        assertEquals("ALREADY_GRANTED", d.get("state")); assertEquals(3L, d.get("badgeId")); assertEquals(12L, d.get("memberId"));
        assertEquals(55L, d.get("auditId")); assertEquals(1, d.get("protocolVersion")); assertTrue(((Long) d.get("appliedAt")) > 0);
    }

    @Test void receiptForUnknownRequestHasOnlyNulls() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = "a".repeat(64);
        when(ledger.read(ClubBadgeOriginal.PROTOCOL, 9L, RID, "club:7:CREATE", sha))
                .thenReturn(new AppOriginalRequestLedger.Fact("NOT_FOUND", RID, "", sha, null, null, null, false));
        Map<String, Object> body = new HashMap<>(Map.of("clubId", 7, "requestId", RID, "operation", "CREATE", "sourceSHA256", sha));
        Map<?, ?> d = (Map<?, ?>) new ClubBadgeOriginal(ledger).receipt(9, body).get("data");
        assertEquals("UNKNOWN", d.get("state")); assertNull(d.get("badgeId")); assertNull(d.get("auditId"));
        body.put("extra", 1);
        assertEquals(400, new ClubBadgeOriginal(ledger).receipt(9, body).get("code"));
    }
}
