package com.chengyinhub.business.service.channel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Additive read of an authorized channel detail. Conversation type 5 stays 5.
 * Channel kind and guild kind stay separate. A missing read state is UNKNOWN.
 * This projection does not grant send or receipt writes.
 */
public final class MessageContainerProjection {

    public static final String READ_STATE_UNKNOWN = "UNKNOWN";

    private MessageContainerProjection() {
    }

    /** Reads the additive message_container. A club/group row is not accepted here. */
    public static Map<String, Object> consumeAuthorizedDetail(Map<String, Object> detail) {
        if (detail == null) {
            return null;
        }
        Object nested = detail.get("message_container");
        if (!(nested instanceof Map)) {
            return null;
        }
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) nested).entrySet()) {
            raw.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return project(raw, raw.get("conversation_type"));
    }

    public static Map<String, Object> project(Map<String, Object> detail, Object conversationType) {
        Long type = exactPositiveLong(conversationType);
        if (detail == null || type == null || type.longValue() != 5L) {
            return null;
        }
        String id = decimal(detail.get("id"));
        Long kind = exactPositiveLong(detail.get("kind"));
        Long guildKind = exactPositiveLong(detail.get("guild_kind"));
        if (id == null || kind == null || guildKind == null || kind.longValue() > 2L || guildKind.longValue() > 3L) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", id);
        view.put("conversation_type", "5");
        view.put("kind", kind.toString());
        view.put("guild_kind", guildKind.toString());
        view.put("status", decimal(detail.get("status")));
        view.put("version", decimal(detail.get("version")));
        view.put("perm_version", decimal(detail.get("perm_version")));
        view.put("name", detail.get("name"));
        view.put("topic", detail.get("topic"));
        view.put("my_permissions", detail.get("my_permissions"));
        Object readState = detail.get("read_state");
        view.put("read_state", readState == null ? READ_STATE_UNKNOWN : readState);
        view.put("grants_send", Boolean.FALSE);
        view.put("grants_receipt_write", Boolean.FALSE);
        return view;
    }

    private static String decimal(Object value) {
        Long exact = exactPositiveLong(value);
        if (exact != null) {
            return exact.toString();
        }
        if (value instanceof Integer && ((Integer) value).intValue() == 0) {
            return "0";
        }
        if (value instanceof Long && ((Long) value).longValue() == 0L) {
            return "0";
        }
        if (value instanceof String && ((String) value).matches("[0-9]{1,19}")) {
            return (String) value;
        }
        return null;
    }

    private static Long exactPositiveLong(Object value) {
        if (value instanceof Long) {
            long id = ((Long) value).longValue();
            return id > 0L ? Long.valueOf(id) : null;
        }
        if (value instanceof Integer) {
            int id = ((Integer) value).intValue();
            return id > 0 ? Long.valueOf(id) : null;
        }
        if (value instanceof String && ((String) value).matches("[0-9]{1,19}")) {
            try {
                long id = Long.parseLong((String) value);
                return id > 0L ? Long.valueOf(id) : null;
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        return null;
    }
}
