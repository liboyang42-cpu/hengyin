package com.chengyinhub.business.moneyswitch;

import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q5 money switch: the server half of the app's "money on" switch (app effective = compiled ceiling AND this flag).
 *
 * <p>Fail closed everywhere: a missing row is off, an unreadable row is off, a read error turns every area off, and while
 * {@code chengyin.money-switch.server-ceiling} is false (the default, and the production value until the owner says
 * "money on") every area reads off and no area can be switched on. Switching off is always allowed. Every change writes
 * one audit row (who, when, old, new, reason) in the same transaction.
 *
 * <p>This flag is advisory for the app. The existing server-side properties (for example
 * {@code chengyin.release.money-payments}, {@code chengyin.release.payout}, {@code wx.pay.enabled},
 * {@code payout.ecommerce.enabled}) still gate the money writes on the server and are not changed here.
 */
@Service
public class MoneySwitchService {
    private static final Logger log = LoggerFactory.getLogger(MoneySwitchService.class);
    public static final int AUDIT_LIMIT_MAX = 200;
    public static final int REASON_MAX = 255;

    private final MoneySwitchMapper mapper;
    private final boolean serverCeiling;

    public MoneySwitchService(MoneySwitchMapper mapper,
                              @Value("${chengyin.money-switch.server-ceiling:false}") boolean serverCeiling) {
        this.mapper = mapper;
        this.serverCeiling = serverCeiling;
    }

    public boolean serverCeiling() {
        return serverCeiling;
    }

    /** All six areas, in wire order. Off unless the ceiling is on and the stored row is exactly on. */
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> stored = storedOrAllOff();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (MoneyArea area : MoneyArea.values()) {
            out.put(area.key(), serverCeiling && Boolean.TRUE.equals(stored.get(area.key())));
        }
        return out;
    }

    /** Admin view: the stored value and the effective value per area, plus the ceiling. */
    public Map<String, Object> adminView() {
        Map<String, Boolean> stored = storedOrAllOff();
        Map<String, Boolean> effective = snapshot();
        Map<String, Object> areas = new LinkedHashMap<>();
        for (MoneyArea area : MoneyArea.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stored", Boolean.TRUE.equals(stored.get(area.key())));
            row.put("effective", effective.get(area.key()));
            areas.put(area.key(), row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serverCeiling", serverCeiling);
        view.put("areas", areas);
        return view;
    }

    /**
     * Change one area. Returns true when the stored value changed (and one audit row was written), false when it was
     * already at the requested value.
     *
     * @throws IllegalArgumentException unknown area, missing value, operator or reason
     * @throws IllegalStateException    switching on while the server ceiling is off
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean set(String areaKey, Boolean enabled, String operator, String reason) {
        MoneyArea area = MoneyArea.fromKey(areaKey);
        if (area == null) {
            throw new IllegalArgumentException("unknown money area");
        }
        if (enabled == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (why.length() > REASON_MAX) {
            why = why.substring(0, REASON_MAX);
        }
        if (enabled && !serverCeiling) {
            throw new IllegalStateException("server ceiling is off; money areas cannot be switched on here");
        }
        Integer current = mapper.selectEnabledForUpdate(area.key());
        int oldValue = current != null && current == 1 ? 1 : 0;
        int newValue = enabled ? 1 : 0;
        if (oldValue == newValue && current != null) {
            return false;
        }
        mapper.upsert(area.key(), newValue, operator);
        mapper.insertAudit(area.key(), oldValue, newValue, operator, why);
        log.warn("money switch {} changed {} -> {} by {}", area.key(), oldValue, newValue, operator);
        return true;
    }

    public List<Map<String, Object>> audit(int limit) {
        int bounded = Math.max(1, Math.min(limit, AUDIT_LIMIT_MAX));
        List<Map<String, Object>> rows = mapper.selectAudit(bounded);
        return rows == null ? Collections.emptyList() : rows;
    }

    private Map<String, Boolean> storedOrAllOff() {
        Map<String, Boolean> stored = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = mapper.selectAll();
            if (rows == null) {
                return stored;
            }
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    continue;
                }
                MoneyArea area = MoneyArea.fromKey(asString(row.get("area")));
                if (area != null) {
                    stored.put(area.key(), isExactlyOn(row.get("enabled")));
                }
            }
            return stored;
        } catch (RuntimeException readFailure) {
            log.error("money switch read failed; every area reads off", readFailure);
            return new LinkedHashMap<>();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** Only 1 or true is on. Null, 0, 2, "yes" and anything else is off. */
    static boolean isExactlyOn(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.longValue() == 1L && number.doubleValue() == 1.0d;
        }
        return false;
    }
}
