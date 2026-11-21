package com.chengyinhub.business.moneyswitch;

import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test helpers for the W7 MERCHANT-3 money gate. */
public final class MoneyGateTestSupport {
    private MoneyGateTestSupport() {
    }

    /** A Q5 switch with the given ceiling and the listed areas stored on (every other area stored off). */
    public static MoneySwitchService moneySwitch(boolean ceiling, MoneyArea... storedOn) {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MoneyArea area : storedOn) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("area", area.key());
            row.put("enabled", 1);
            rows.add(row);
        }
        when(mapper.selectAll()).thenReturn(rows);
        return new MoneySwitchService(mapper, ceiling);
    }

    /** Every area effectively on. */
    public static MoneySwitchService allOpenSwitch() {
        return moneySwitch(true, MoneyArea.values());
    }

    /** A gate with every area open (ceiling on, every row on). */
    public static MoneyGate openGate() {
        return new MoneyGate(allOpenSwitch());
    }

    /** A gate with every area closed (ceiling off). */
    public static MoneyGate closedGate() {
        return new MoneyGate(moneySwitch(false));
    }
}
