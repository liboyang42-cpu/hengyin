package com.chengyinhub.business.sponsoredquest;

import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.sponsoredquest.mapper.SponsoredQuestMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S3b: the label is always present, campaign validation, status rules, and charges only behind the money switch. */
class SponsoredQuestServicesTest {

    private static Map<String, Boolean> money(boolean merchantMoney) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : List.of("tickets", "pass", "merchantMoney", "payout", "withdrawal", "refunds")) m.put(k, false);
        m.put("merchantMoney", merchantMoney);
        return m;
    }

    private static SponsoredQuestCampaign campaign() {
        SponsoredQuestCampaign c = new SponsoredQuestCampaign();
        c.setSponsorName(" 城市咖啡 ");
        c.setTitle("咖啡散步");
        c.setContent("走访三家咖啡馆");
        c.setBudgetCent(100_000L);
        c.setStartTime(new Date(System.currentTimeMillis() - 3_600_000L));
        c.setEndTime(new Date(System.currentTimeMillis() + 7 * 86_400_000L));
        c.setPlaceIds(new ArrayList<>(List.of(3L, 5L, 3L)));
        c.setReason("新品推广");
        return c;
    }

    // ---- label ----

    @Test
    void everyPlayerRowCarriesTheAdLabelEvenIfAStoredRowTriesToReplaceIt() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        Map<String, Object> row = new HashMap<>();
        row.put("id", 9L); row.put("title", "T"); row.put("content", "C"); row.put("sponsorName", "S");
        row.put("label", "赞助"); row.put("sponsored", false);
        when(mapper.selectLiveInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(row));
        Map<String, Object> place = Map.of("campaignId", 9L, "poiId", 3L, "name", "P", "lat", 30.1, "lng", 120.1);
        when(mapper.selectLivePlaces(anyList())).thenReturn(List.of(place));
        List<Map<String, Object>> out = new SponsoredQuestService(mapper).liveInBox(30.0, 30.2, 120.0, 120.2);
        assertEquals(1, out.size());
        assertEquals("广告", out.get(0).get("label"));
        assertEquals("Ad", out.get(0).get("labelEn"));
        assertEquals(Boolean.TRUE, out.get(0).get("sponsored"));
    }

    @Test
    void campaignWithoutAShowablePlaceIsDroppedNotShownUnplaced() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        when(mapper.selectLiveInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(new HashMap<>(Map.of("id", 9L, "title", "T"))));
        when(mapper.selectLivePlaces(anyList())).thenReturn(List.of());
        assertTrue(new SponsoredQuestService(mapper).liveInBox(30.0, 30.2, 120.0, 120.2).isEmpty());
    }

    @Test
    void adminListAndDetailAlsoShowTheLabel() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        when(mapper.selectAdminList(anyInt())).thenReturn(List.of(new HashMap<>(Map.of("id", 1L))));
        when(mapper.selectById(1L)).thenReturn(new HashMap<>(Map.of("id", 1L, "status", 0)));
        SponsoredQuestAdminService admin = new SponsoredQuestAdminService(mapper, mock(OpsConsoleAudit.class));
        assertEquals("广告", admin.list().get(0).get("label"));
        assertEquals("广告", admin.detail(1L).get("label"));
    }

    @Test
    void boxIsBoundedLikeTheDealsLayer() {
        SponsoredQuestService service = new SponsoredQuestService(mock(SponsoredQuestMapper.class));
        assertThrows(IllegalArgumentException.class, () -> service.liveInBox(30.0, 31.0, 120.0, 120.2));
        assertThrows(IllegalArgumentException.class, () -> service.liveInBox(null, 30.2, 120.0, 120.2));
        assertThrows(IllegalArgumentException.class, () -> service.liveInBox(30.2, 30.0, 120.0, 120.2));
    }

    // ---- admin ----

    @Test
    void createNormalizesDedupesPlacesAndAudits() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        when(mapper.countPublishedPlaces(anyList())).thenReturn(2);
        doAnswer(inv -> { ((SponsoredQuestCampaign) inv.getArgument(0)).setId(42L); return 1; })
                .when(mapper).insertCampaign(any(), anyString());
        when(mapper.insertPlace(anyLong(), anyLong(), anyInt())).thenReturn(1);
        SponsoredQuestCampaign c = campaign();
        assertEquals(42L, new SponsoredQuestAdminService(mapper, audit).create(c, "ops", 1L));
        assertEquals("城市咖啡", c.getSponsorName());
        assertEquals(List.of(3L, 5L), c.getPlaceIds());
        verify(mapper).insertPlace(42L, 3L, 0);
        verify(mapper).insertPlace(42L, 5L, 1);
        verify(audit).record(eq(1L), eq("SPONSORED_QUEST_CREATE"), eq("SPONSORED_QUEST"), eq(42L), any(), any(), eq("新品推广"));
    }

    @Test
    void createRefusesBadInputBeforeWriting() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        when(mapper.countPublishedPlaces(anyList())).thenReturn(1);
        SponsoredQuestAdminService admin = new SponsoredQuestAdminService(mapper, mock(OpsConsoleAudit.class));
        SponsoredQuestCampaign unpublished = campaign();
        assertThrows(IllegalArgumentException.class, () -> admin.create(unpublished, "ops", 1L), "a place is unpublished");
        SponsoredQuestCampaign noPlaces = campaign(); noPlaces.setPlaceIds(List.of());
        assertThrows(IllegalArgumentException.class, () -> admin.create(noPlaces, "ops", 1L));
        SponsoredQuestCampaign backwards = campaign(); backwards.setEndTime(backwards.getStartTime());
        assertThrows(IllegalArgumentException.class, () -> admin.create(backwards, "ops", 1L));
        SponsoredQuestCampaign negative = campaign(); negative.setBudgetCent(-1L);
        assertThrows(IllegalArgumentException.class, () -> admin.create(negative, "ops", 1L));
        SponsoredQuestCampaign noTitle = campaign(); noTitle.setTitle("  ");
        assertThrows(IllegalArgumentException.class, () -> admin.create(noTitle, "ops", 1L));
        SponsoredQuestCampaign httpCover = campaign(); httpCover.setCoverImg("http://x/y.png");
        assertThrows(IllegalArgumentException.class, () -> admin.create(httpCover, "ops", 1L));
        verify(mapper, never()).insertCampaign(any(), anyString());
    }

    @Test
    void statusRules() {
        assertTrue(SponsoredQuestAdminService.allowed(0, 1));
        assertTrue(SponsoredQuestAdminService.allowed(1, 2));
        assertTrue(SponsoredQuestAdminService.allowed(2, 1));
        assertTrue(SponsoredQuestAdminService.allowed(1, 3));
        assertFalse(SponsoredQuestAdminService.allowed(3, 1), "ended is final");
        assertFalse(SponsoredQuestAdminService.allowed(0, 2));
        assertFalse(SponsoredQuestAdminService.allowed(1, 0));

        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        when(mapper.selectById(5L)).thenReturn(new HashMap<>(Map.of("id", 5L, "status", 0,
                "endTime", new Date(System.currentTimeMillis() - 1000))));
        SponsoredQuestAdminService admin = new SponsoredQuestAdminService(mapper, mock(OpsConsoleAudit.class));
        assertThrows(IllegalArgumentException.class, () -> admin.changeStatus(5L, 1, "ops", 1L, "上线"), "window already over");
        verify(mapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyString());
    }

    // ---- money ----

    @Test
    void chargeIsRefusedAndWritesNothingWhileTheMoneySwitchIsClosed() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        MoneySwitchService moneySwitch = mock(MoneySwitchService.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        when(moneySwitch.snapshot()).thenReturn(money(false));
        SponsoredQuestBudgetService budget = new SponsoredQuestBudgetService(mapper, moneySwitch, audit);
        assertFalse(budget.chargingOpen());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> budget.charge(1L, "ref-0001", 100L, "week 1", "ops", 1L));
        assertEquals(SponsoredQuestBudgetService.MONEY_OFF, e.getMessage());
        verifyNoInteractions(mapper, audit);
    }

    @Test
    void chargeIsRefusedWhenOnlyAnotherMoneyAreaIsOpenOrTheSwitchFails() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        MoneySwitchService moneySwitch = mock(MoneySwitchService.class);
        Map<String, Boolean> others = money(false);
        others.put("tickets", true); others.put("pass", true);
        when(moneySwitch.snapshot()).thenReturn(others);
        SponsoredQuestBudgetService budget = new SponsoredQuestBudgetService(mapper, moneySwitch, mock(OpsConsoleAudit.class));
        assertThrows(IllegalStateException.class, () -> budget.charge(1L, "ref-0001", 100L, "x", "ops", 1L));
        when(moneySwitch.snapshot()).thenThrow(new RuntimeException("db down"));
        assertFalse(budget.chargingOpen());
        assertThrows(IllegalStateException.class, () -> budget.charge(1L, "ref-0001", 100L, "x", "ops", 1L));
        verifyNoInteractions(mapper);
    }

    @Test
    void openSwitchChargesOnceWithinBudget() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        MoneySwitchService moneySwitch = mock(MoneySwitchService.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        when(moneySwitch.snapshot()).thenReturn(money(true));
        when(mapper.selectForUpdate(1L)).thenReturn(new HashMap<>(Map.of("id", 1L, "budgetCent", 1000L, "spentCent", 0L)));
        when(mapper.insertChargeIgnore(eq(1L), eq("ref-0001"), eq(100L), anyString(), anyString())).thenReturn(1, 0);
        when(mapper.addSpentWithinBudget(1L, 100L)).thenReturn(1);
        when(mapper.selectChargeCampaign("ref-0001")).thenReturn(1L);
        SponsoredQuestBudgetService budget = new SponsoredQuestBudgetService(mapper, moneySwitch, audit);
        assertEquals(false, budget.charge(1L, "ref-0001", 100L, "week 1", "ops", 1L).get("duplicate"));
        assertEquals(true, budget.charge(1L, "ref-0001", 100L, "week 1", "ops", 1L).get("duplicate"));
        verify(mapper).addSpentWithinBudget(1L, 100L);
        verify(audit).record(eq(1L), eq("SPONSORED_QUEST_CHARGE"), eq("SPONSORED_QUEST"), eq(1L), any(), any(), eq("week 1"));
    }

    @Test
    void openSwitchStillRefusesOverBudgetAndForeignRefs() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        MoneySwitchService moneySwitch = mock(MoneySwitchService.class);
        when(moneySwitch.snapshot()).thenReturn(money(true));
        when(mapper.selectForUpdate(1L)).thenReturn(new HashMap<>(Map.of("id", 1L)));
        when(mapper.insertChargeIgnore(eq(1L), anyString(), anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.addSpentWithinBudget(1L, 5000L)).thenReturn(0);
        SponsoredQuestBudgetService budget = new SponsoredQuestBudgetService(mapper, moneySwitch, mock(OpsConsoleAudit.class));
        assertThrows(IllegalArgumentException.class, () -> budget.charge(1L, "ref-0002", 5000L, "x", "ops", 1L));
        when(mapper.insertChargeIgnore(eq(1L), eq("ref-0003"), anyLong(), anyString(), anyString())).thenReturn(0);
        when(mapper.selectChargeCampaign("ref-0003")).thenReturn(2L);
        assertThrows(IllegalArgumentException.class, () -> budget.charge(1L, "ref-0003", 10L, "x", "ops", 1L));
        assertThrows(IllegalArgumentException.class, () -> budget.charge(1L, "bad ref!", 10L, "x", "ops", 1L));
        assertThrows(IllegalArgumentException.class, () -> budget.charge(1L, "ref-0004", 0L, "x", "ops", 1L));
        assertThrows(IllegalArgumentException.class, () -> budget.charge(1L, "ref-0004", 10L, " ", "ops", 1L));
    }

    @Test
    void stateReportsTheSwitchAndTheLabel() {
        MoneySwitchService moneySwitch = mock(MoneySwitchService.class);
        when(moneySwitch.snapshot()).thenReturn(money(false));
        Map<String, Object> state = new SponsoredQuestBudgetService(mock(SponsoredQuestMapper.class), moneySwitch,
                mock(OpsConsoleAudit.class)).state();
        assertEquals(false, state.get("chargingOpen"));
        assertEquals("广告", state.get("label"));
    }

    // W7 MERCHANT-15: create, edit and status changes need a reason, stored in the audit row.
    @Test
    void everyAdminWriteNeedsAReason() {
        SponsoredQuestMapper mapper = mock(SponsoredQuestMapper.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        SponsoredQuestAdminService admin = new SponsoredQuestAdminService(mapper, audit);
        SponsoredQuestCampaign noReason = campaign(); noReason.setReason("  ");
        assertThrows(IllegalArgumentException.class, () -> admin.create(noReason, "ops", 1L));
        SponsoredQuestCampaign edit = campaign(); edit.setId(5L); edit.setReason(null);
        assertThrows(IllegalArgumentException.class, () -> admin.update(edit, "ops", 1L));
        assertThrows(IllegalArgumentException.class, () -> admin.changeStatus(5L, 2, "ops", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> admin.changeStatus(5L, 2, "ops", 1L, " "));
        verify(mapper, never()).insertCampaign(any(), anyString());
        verify(mapper, never()).updateCampaign(any(), anyString());
        verify(mapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyString());
        verifyNoInteractions(audit);
    }
}
