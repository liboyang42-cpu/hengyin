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
 * <p>W7 MERCHANT-15: switching an area ON needs two people. One admin asks ({@link #requestOpen}); a different admin
 * approves ({@link #approveOpen}); only the approval switches the area on and writes the audit row. A request expires
 * after {@link #REQUEST_TTL_HOURS} hours. The shared super-admin account {@code admin} can neither ask nor approve.
 * Switching off stays one step ({@link #set} with {@code false}).
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
    public static final int REQUEST_TTL_HOURS = 24;
    /** RuoYi's built-in super admin ({@code *:*:*}) passes every permission check; it may not open money. */
    static final String SHARED_ADMIN = "admin";
    public static final String TWO_PERSON_REQUIRED = "打开资金区域需要两个人：请先提交打开申请，再由另一位管理员批准";

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
        if (enabled) {
            // MERCHANT-15: opening goes through requestOpen + approveOpen by a different person.
            throw new IllegalStateException(TWO_PERSON_REQUIRED);
        }
        mapper.upsert(area.key(), newValue, operator);
        mapper.insertAudit(area.key(), oldValue, newValue, operator, why);
        log.warn("money switch {} changed {} -> {} by {}", area.key(), oldValue, newValue, operator);
        return true;
    }

    /** First person: ask to open an area. Nothing changes until a different admin approves. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> requestOpen(String areaKey, String operator, String reason) {
        MoneyArea area = requireArea(areaKey);
        String by = requirePerson(operator);
        String why = requireReason(reason);
        if (!serverCeiling) {
            throw new IllegalStateException("server ceiling is off; money areas cannot be switched on here");
        }
        Integer current = mapper.selectEnabledForUpdate(area.key());
        if (current != null && current == 1) {
            throw new IllegalStateException("这个资金区域已经是打开的");
        }
        Map<String, Object> pending = mapper.selectPendingRequest(area.key(), REQUEST_TTL_HOURS);
        if (pending != null && !pending.isEmpty()) {
            throw new IllegalStateException("这个资金区域已有待批准的打开申请 #" + pending.get("id"));
        }
        mapper.insertRequest(area.key(), by, why);
        log.warn("money switch {} open requested by {}", area.key(), by);
        Map<String, Object> created = mapper.selectPendingRequest(area.key(), REQUEST_TTL_HOURS);
        return created == null ? Map.of("area", area.key(), "status", "PENDING") : created;
    }

    /**
     * Second person: approve someone else's request. Refused for the requester, for the shared {@code admin} account,
     * for an expired or decided request, and while the server ceiling is off. Writes the switch and its audit row.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean approveOpen(long requestId, String approver, String reason) {
        String by = requirePerson(approver);
        String why = requireReason(reason);
        Map<String, Object> request = pendingRequest(requestId);
        String requester = String.valueOf(request.get("requestedBy"));
        if (requester.trim().equalsIgnoreCase(by)) {
            throw new IllegalStateException("不能批准自己提交的申请，需要另一位管理员");
        }
        if (truthy(request.get("expired"))) {
            mapper.decideRequest(requestId, "EXPIRED", by, "超过 " + REQUEST_TTL_HOURS + " 小时未批准");
            throw new IllegalStateException("申请已超过 " + REQUEST_TTL_HOURS + " 小时，请重新提交");
        }
        if (!serverCeiling) {
            throw new IllegalStateException("server ceiling is off; money areas cannot be switched on here");
        }
        MoneyArea area = requireArea(String.valueOf(request.get("area")));
        Integer current = mapper.selectEnabledForUpdate(area.key());
        int oldValue = current != null && current == 1 ? 1 : 0;
        if (mapper.decideRequest(requestId, "APPROVED", by, why) != 1) {
            throw new IllegalStateException("申请已被处理，请刷新");
        }
        if (oldValue == 1) {
            return false;
        }
        mapper.upsert(area.key(), 1, by);
        String auditReason = ("批准打开申请 #" + requestId + "（申请人 " + requester + "）：" + why);
        mapper.insertAudit(area.key(), 0, 1, by, auditReason.length() > REASON_MAX ? auditReason.substring(0, REASON_MAX) : auditReason);
        log.warn("money switch {} changed 0 -> 1 by {} (requested by {})", area.key(), by, requester);
        return true;
    }

    /** Turn down (or, for the requester, withdraw) an open request. Moves nothing. */
    @Transactional(rollbackFor = Exception.class)
    public boolean rejectOpen(long requestId, String operator, String reason) {
        String by = requirePerson(operator);
        String why = requireReason(reason);
        pendingRequest(requestId);
        return mapper.decideRequest(requestId, "REJECTED", by, why) == 1;
    }

    public List<Map<String, Object>> requests(int limit) {
        int bounded = Math.max(1, Math.min(limit, AUDIT_LIMIT_MAX));
        List<Map<String, Object>> rows = mapper.selectRequests(bounded);
        return rows == null ? Collections.emptyList() : rows;
    }

    private Map<String, Object> pendingRequest(long requestId) {
        Map<String, Object> request = requestId <= 0 ? null : mapper.selectRequestForUpdate(requestId, REQUEST_TTL_HOURS);
        if (request == null || request.isEmpty()) {
            throw new IllegalArgumentException("没有这条申请");
        }
        if (!"PENDING".equals(request.get("status"))) {
            throw new IllegalStateException("申请已被处理，请刷新");
        }
        return request;
    }

    private static MoneyArea requireArea(String areaKey) {
        MoneyArea area = MoneyArea.fromKey(areaKey);
        if (area == null) {
            throw new IllegalArgumentException("unknown money area");
        }
        return area;
    }

    private static String requirePerson(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        String by = operator.trim();
        if (SHARED_ADMIN.equalsIgnoreCase(by)) {
            throw new IllegalStateException("共用的 admin 账号不能打开资金区域，请用实名管理员账号");
        }
        return by;
    }

    private static String requireReason(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new IllegalArgumentException("reason is required");
        }
        return why.length() > REASON_MAX ? why.substring(0, REASON_MAX) : why;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() == 1;
        return value != null && "1".equals(String.valueOf(value).trim());
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
