package com.chengyinhub.business.moneyswitch;

import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiMoneySwitchController;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Q5 money switch: every area is off unless the server ceiling is on AND the stored row is exactly on. */
class MoneySwitchServiceTest {

    private static final String[] KEYS = {"tickets", "pass", "merchantMoney", "payout", "withdrawal", "refunds"};

    private static Map<String, Object> row(String area, Object enabled) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("area", area);
        row.put("enabled", enabled);
        return row;
    }

    private static List<Map<String, Object>> allOn() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : KEYS) {
            rows.add(row(key, 1));
        }
        return rows;
    }

    private static void assertAllOff(Map<String, ?> areas) {
        for (String key : KEYS) {
            assertEquals(Boolean.FALSE, areas.get(key), key);
        }
    }

    @Test
    void defaultIsAllOffWithNoRows() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectAll()).thenReturn(new ArrayList<>());
        Map<String, Boolean> snapshot = new MoneySwitchService(mapper, true).snapshot();
        assertEquals(List.of(KEYS), new ArrayList<>(snapshot.keySet()));
        assertAllOff(snapshot);
    }

    @Test
    void serverCeilingOffClosesEveryStoredOnArea() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectAll()).thenReturn(allOn());
        assertAllOff(new MoneySwitchService(mapper, false).snapshot());
    }

    @Test
    void readFailureClosesEveryArea() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectAll()).thenThrow(new RuntimeException("db down"));
        assertAllOff(new MoneySwitchService(mapper, true).snapshot());
    }

    @Test
    void onlyExactlyOneOrTrueIsOn() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("tickets", 1));
        rows.add(row("pass", 2));
        rows.add(row("merchantMoney", "1"));
        rows.add(row("payout", null));
        rows.add(row("withdrawal", Boolean.TRUE));
        rows.add(row("unknownArea", 1));
        when(mapper.selectAll()).thenReturn(rows);
        Map<String, Boolean> snapshot = new MoneySwitchService(mapper, true).snapshot();
        assertTrue(snapshot.get("tickets"));
        assertFalse(snapshot.get("pass"));
        assertFalse(snapshot.get("merchantMoney"));
        assertFalse(snapshot.get("payout"));
        assertTrue(snapshot.get("withdrawal"));
        assertFalse(snapshot.get("refunds"));
        assertEquals(6, snapshot.size());
    }

    @Test
    void ceilingOnAndStoredOnIsTheOnlyOpenState() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectAll()).thenReturn(allOn());
        Map<String, Boolean> snapshot = new MoneySwitchService(mapper, true).snapshot();
        for (String key : KEYS) {
            assertTrue(snapshot.get(key), key);
        }
    }

    @Test
    void switchingOnIsRefusedWhileServerCeilingIsOff() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        MoneySwitchService service = new MoneySwitchService(mapper, false);
        assertThrows(IllegalStateException.class, () -> service.set("tickets", true, "owner", "rehearsal"));
        verify(mapper, never()).upsert(anyString(), anyInt(), anyString());
        verify(mapper, never()).insertAudit(anyString(), anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void switchingOffIsAlwaysAllowedAndAudited() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectEnabledForUpdate("payout")).thenReturn(1);
        MoneySwitchService service = new MoneySwitchService(mapper, false);
        assertTrue(service.set("payout", false, "finance-a", "  refund backlog over threshold "));
        verify(mapper).upsert("payout", 0, "finance-a");
        verify(mapper).insertAudit("payout", 1, 0, "finance-a", "refund backlog over threshold");
    }

    @Test
    void changeWritesWhoOldNewAndReason() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectEnabledForUpdate("tickets")).thenReturn(null);
        MoneySwitchService service = new MoneySwitchService(mapper, true);
        assertTrue(service.set("TICKETS", true, "owner", "staging rehearsal"));
        verify(mapper).upsert("tickets", 1, "owner");
        verify(mapper).insertAudit("tickets", 0, 1, "owner", "staging rehearsal");
    }

    @Test
    void unchangedValueWritesNothing() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectEnabledForUpdate("pass")).thenReturn(1);
        assertFalse(new MoneySwitchService(mapper, true).set("pass", true, "owner", "again"));
        verify(mapper, never()).upsert(anyString(), anyInt(), anyString());
        verify(mapper, never()).insertAudit(anyString(), anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void badInputIsRefusedBeforeAnyWrite() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        MoneySwitchService service = new MoneySwitchService(mapper, true);
        assertThrows(IllegalArgumentException.class, () -> service.set("cash", true, "owner", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.set(null, true, "owner", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.set("tickets", null, "owner", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.set("tickets", true, " ", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.set("tickets", true, "owner", "   "));
        verify(mapper, never()).selectEnabledForUpdate(anyString());
        verify(mapper, never()).upsert(anyString(), anyInt(), anyString());
    }

    @Test
    void appRouteAnswersAllOffWhenTheServiceThrows() {
        MoneySwitchService service = mock(MoneySwitchService.class);
        when(service.snapshot()).thenThrow(new IllegalStateException("boom"));
        AjaxResult result = new ApiMoneySwitchController(service).moneySwitch();
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertAllOff(data);
        assertEquals(300, data.get("ttlSeconds"));
    }

    @Test
    void appRouteAnswersAllOffByDefault() {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        when(mapper.selectAll()).thenReturn(allOn());
        AjaxResult result = new ApiMoneySwitchController(new MoneySwitchService(mapper, false)).moneySwitch();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertAllOff(data);
    }

    @Test
    void shippedConfigDefaultsTheServerCeilingOff() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.exists(yml)) {
            yml = Path.of("chengyinhub-admin/src/main/resources/application.yml");
        }
        String text = Files.readString(yml, StandardCharsets.UTF_8);
        assertTrue(text.contains("server-ceiling: ${CHENGYIN_MONEY_SWITCH_SERVER_CEILING:false}"));
    }
}
