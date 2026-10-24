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
        view.put("read_state", READ_STATE_UNKNOWN);
        view.put("grants_send", Boolean.FALSE);
        view.put("grants_receipt_write", Boolean.FALSE);
        return view;
    }

    /**
     * Original directory read state lives on {@code my_state}. A top-level
     * {@code read_state} is not that fact. Missing availability stays null, not unread 0.
     * {@code perm_version} is not an authority revision. Last-read cursors are not unread counts.
     */
    public static Map<String, Object> readMyState(Map<String, Object> myState) {
        if (myState == null) {
            return unavailableRead();
        }
        Integer pinned = bit(myState.get("pinned"));
        Integer hidden = bit(myState.get("hidden"));
        Integer muted = bit(myState.get("muted"));
        Integer level = level(myState.get("effective_level"));
        if (pinned == null || hidden == null || muted == null || level == null
                || !(myState.get("permanent_muted") instanceof Boolean)) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("pinned", pinned);
        view.put("hidden", hidden);
        view.put("muted", muted);
        view.put("permanent_muted", myState.get("permanent_muted"));
        view.put("muted_until", myState.get("muted_until"));
        view.put("effective_level", level);
        boolean category = "NOT_APPLICABLE".equals(myState.get("read_state"));
        boolean readable = !category && integerOne(myState.get("read_state_available"))
                && Boolean.TRUE.equals(myState.get("indexed"));
        if (readable) {
            view.put("read_state_available", Integer.valueOf(1));
            view.put("indexed", Boolean.TRUE);
            view.put("last_read_msg_id", nullableLong(myState.get("last_read_msg_id")));
            view.put("last_read_visible_at", myState.get("last_read_visible_at"));
            view.put("unread_count", nullableLong(myState.get("unread_count")));
            view.put("unread_mention_count", nullableLong(myState.get("unread_mention_count")));
        } else {
            view.put("read_state_available", Integer.valueOf(0));
            view.put("indexed", Boolean.FALSE);
            view.put("last_read_msg_id", null);
            view.put("last_read_visible_at", null);
            view.put("unread_count", null);
            view.put("unread_mention_count", null);
            if (category) {
                view.put("read_state", "NOT_APPLICABLE");
            }
        }
        view.put("grants_send", Boolean.FALSE);
        view.put("grants_receipt_write", Boolean.FALSE);
        return view;
    }

    private static Map<String, Object> unavailableRead() {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("read_state_available", Integer.valueOf(0));
        view.put("indexed", Boolean.FALSE);
        view.put("last_read_msg_id", null);
        view.put("last_read_visible_at", null);
        view.put("unread_count", null);
        view.put("unread_mention_count", null);
        view.put("grants_send", Boolean.FALSE);
        view.put("grants_receipt_write", Boolean.FALSE);
        return view;
    }

    private static boolean integerOne(Object value) {
        return value instanceof Integer && ((Integer) value).intValue() == 1
                || value instanceof Long && ((Long) value).longValue() == 1L;
    }

    private static Integer bit(Object value) {
        if (value instanceof Integer && (((Integer) value).intValue() == 0 || ((Integer) value).intValue() == 1)) {
            return (Integer) value;
        }
        if (value instanceof Long && (((Long) value).longValue() == 0L || ((Long) value).longValue() == 1L)) {
            return Integer.valueOf(((Long) value).intValue());
        }
        return null;
    }

    private static Integer level(Object value) {
        Integer exact = bit(value);
        if (exact != null) {
            return exact;
        }
        if (value instanceof Integer && ((Integer) value).intValue() == 2) {
            return (Integer) value;
        }
        if (value instanceof Long && ((Long) value).longValue() == 2L) {
            return Integer.valueOf(2);
        }
        return null;
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return Long.valueOf(((Integer) value).longValue());
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof String && ((String) value).matches("[0-9]{1,19}")) {
            try {
                return Long.valueOf((String) value);
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        return null;
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
