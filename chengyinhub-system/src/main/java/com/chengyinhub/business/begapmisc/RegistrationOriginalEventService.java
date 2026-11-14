package com.chengyinhub.business.begapmisc;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/registration/original-event} (app {@code Core/ProfileOrderEventHost}): the original audit events whose
 * target is the caller's own order. The rows are {@code business_audit_event} facts with {@code target_type ORDER} and
 * {@code target_id} = the registration id; nothing is derived from the order's own fields (the app refuses that too).
 *
 * <p>Wire: {@code code 200, data = [{requestId, targetType, targetId, action, afterJson, createTime}]}, oldest first. An
 * order with no audit row answers an empty list (the app then shows nothing). Someone else's or an unknown order is
 * {@code code 404 REGISTRATION_NOT_FOUND}, so ownership cannot be probed. A malformed id is {@code code 400}.
 */
@Service
public class RegistrationOriginalEventService {
    static final int LIMIT = 50;
    private final JdbcTemplate jdbc;

    public RegistrationOriginalEventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AjaxResult read(Long memberId, String rawId) {
        if (rawId == null || !rawId.matches("[1-9][0-9]{0,17}")) return AjaxResult.error(400, "REGISTRATION_ID_INVALID");
        long id = Long.parseLong(rawId);
        Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration WHERE id = ? AND member_id = ?",
                Integer.class, id, memberId);
        if (owned == null || owned == 0) return AjaxResult.error(404, "REGISTRATION_NOT_FOUND");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_id, target_type, target_id, action,"
                + " after_json, create_time FROM business_audit_event"
                + " WHERE target_type = 'ORDER' AND target_id = ? ORDER BY create_time, id LIMIT " + LIMIT, id);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("requestId", row.get("request_id"));
            event.put("targetType", row.get("target_type"));
            event.put("targetId", ((Number) row.get("target_id")).longValue());
            event.put("action", row.get("action"));
            event.put("afterJson", row.get("after_json"));
            event.put("createTime", row.get("create_time"));
            events.add(event);
        }
        return AjaxResult.success(events);
    }
}
