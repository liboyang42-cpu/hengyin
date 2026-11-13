package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static com.chengyinhub.business.appgaps.AppOriginalRequestLedger.sha256Hex;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubWelcomeNoteOriginalTest {
    static final String RID = "cccccccc-1111-4111-8111-111111111111";

    @Test void sourceMatchesAppFormulaOnTrimmedText() {
        assertEquals(sha256Hex("CLUB_WELCOME_NOTE_V1\u001f7\u001f" + sha256Hex("hi")), ClubWelcomeNoteOriginal.source(7, "hi"));
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        assertEquals(400, new ClubWelcomeNoteOriginal(ledger).write(9, 7L, " hi ", RID, ClubWelcomeNoteOriginal.source(7, " hi "), AjaxResult::success).get("code"));
        verifyNoInteractions(ledger);
    }

    @SuppressWarnings("unchecked")
    @Test void appliedAndRejectedReceiptsHaveExactKeySets() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = ClubWelcomeNoteOriginal.source(7, "hi");
        when(ledger.read(ClubWelcomeNoteOriginal.PROTOCOL, 9L, RID, "club:7", sha)).thenReturn(
                new AppOriginalRequestLedger.Fact("COMMITTED", RID, "club:7", sha, Map.of("welcomeNote", "hi"), null, null, true, 31L, 1700000000000L));
        Map<String, Object> d = (Map<String, Object>) new ClubWelcomeNoteOriginal(ledger)
                .receipt(9, Map.of("clubId", 7, "requestId", RID, "sourceSHA256", sha)).get("data");
        assertEquals(java.util.Set.of("protocolVersion", "actorMemberId", "clubId", "requestId", "sourceSHA256", "state", "welcomeNote", "auditId", "recordedAt"), d.keySet());
        assertEquals("APPLIED", d.get("state")); assertEquals(31L, d.get("auditId"));
        when(ledger.read(ClubWelcomeNoteOriginal.PROTOCOL, 9L, RID, "club:7", sha)).thenReturn(
                new AppOriginalRequestLedger.Fact("REJECTED", RID, "club:7", sha, null, 500, "内容违规", true, 32L, 1700000000001L));
        d = (Map<String, Object>) new ClubWelcomeNoteOriginal(ledger).receipt(9, Map.of("clubId", 7, "requestId", RID, "sourceSHA256", sha)).get("data");
        assertEquals("REJECTED", d.get("state")); assertEquals("内容违规", d.get("message")); assertFalse(d.containsKey("welcomeNote"));
        when(ledger.read(ClubWelcomeNoteOriginal.PROTOCOL, 9L, RID, "club:7", sha)).thenReturn(
                new AppOriginalRequestLedger.Fact("NOT_FOUND", RID, "club:7", sha, null, null, null, false));
        d = (Map<String, Object>) new ClubWelcomeNoteOriginal(ledger).receipt(9, Map.of("clubId", 7, "requestId", RID, "sourceSHA256", sha)).get("data");
        assertEquals(6, d.size()); assertEquals("UNKNOWN", d.get("state"));
    }

    @SuppressWarnings("unchecked")
    @Test void writeReturnsLegacyShapeAfterLedgerCommit() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = ClubWelcomeNoteOriginal.source(7, "hi");
        when(ledger.run(eq(ClubWelcomeNoteOriginal.PROTOCOL), eq(9L), eq(RID), eq("club:7"), eq(sha), any(Supplier.class))).thenAnswer(a ->
                new AppOriginalRequestLedger.Fact("COMMITTED", RID, "club:7", sha, ((Supplier<Map<String, Object>>) a.getArgument(5)).get(), null, null, false));
        AjaxResult r = new ClubWelcomeNoteOriginal(ledger).write(9, 7L, "hi", RID, sha,
                () -> AjaxResult.success("已保存", Map.of("welcomeNote", "hi")));
        assertEquals(200, r.get("code")); assertEquals(Map.of("welcomeNote", "hi"), r.get("data"));
    }
}
