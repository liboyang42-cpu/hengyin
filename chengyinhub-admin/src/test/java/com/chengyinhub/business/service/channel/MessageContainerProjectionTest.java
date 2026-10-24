package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageContainerProjectionTest {

    @Test
    void type5KeepsItsContainerAndDoesNotInventUnreadOrSend() {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("id", "200");
        detail.put("guild_kind", 1);
        detail.put("kind", 1);
        detail.put("status", 0);
        detail.put("version", "11");
        detail.put("perm_version", "20");
        detail.put("name", "大厅");
        detail.put("topic", "集合");
        detail.put("my_permissions", "0x1");
        Map<String, Object> view = MessageContainerProjection.project(detail, 5);
        assertEquals("200", view.get("id"));
        assertEquals("5", view.get("conversation_type"));
        assertEquals("1", view.get("kind"));
        assertEquals("1", view.get("guild_kind"));
        assertEquals(MessageContainerProjection.READ_STATE_UNKNOWN, view.get("read_state"));
        assertFalse((Boolean) view.get("grants_send"));
        assertFalse((Boolean) view.get("grants_receipt_write"));
        assertNull(MessageContainerProjection.project(detail, 4));
        detail.put("kind", 4);
        assertNull(MessageContainerProjection.project(detail, 5));

        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("message_container", view);
        Map<String, Object> consumed = MessageContainerProjection.consumeAuthorizedDetail(wrapped);
        assertEquals("5", consumed.get("conversation_type"));
        assertFalse((Boolean) consumed.get("grants_send"));
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("message_container", Map.of("conversation_type", "4", "id", "200", "kind", "1", "guild_kind", "1"));
        assertNull(MessageContainerProjection.consumeAuthorizedDetail(group));
        assertNull(MessageContainerProjection.consumeAuthorizedDetail(Map.of("type", 4, "bizKey", "club_42")));
        detail.put("kind", 1);
        detail.put("read_state", "READ");
        assertEquals(MessageContainerProjection.READ_STATE_UNKNOWN,
                MessageContainerProjection.project(detail, 5).get("read_state"));
    }

    @Test
    void myStateKeepsOriginalCursorAndDoesNotInventUnread() {
        Map<String, Object> ready = new LinkedHashMap<String, Object>();
        ready.put("pinned", 1);
        ready.put("hidden", 0);
        ready.put("muted", 0);
        ready.put("permanent_muted", Boolean.FALSE);
        ready.put("muted_until", null);
        ready.put("effective_level", 1);
        ready.put("read_state_available", 1);
        ready.put("indexed", Boolean.TRUE);
        ready.put("last_read_msg_id", "42");
        ready.put("last_read_visible_at", "2026-10-24T09:00:00Z");
        ready.put("unread_count", 3);
        ready.put("unread_mention_count", 1);
        ready.put("perm_version", "20");
        ready.put("lastAck", "99");
        Map<String, Object> read = MessageContainerProjection.readMyState(ready);
        assertEquals(Long.valueOf(42L), read.get("last_read_msg_id"));
        assertEquals(Long.valueOf(3L), read.get("unread_count"));
        assertEquals(Integer.valueOf(1), read.get("read_state_available"));
        assertFalse(read.containsKey("perm_version"));
        assertFalse(read.containsKey("lastAck"));
        assertFalse((Boolean) read.get("grants_send"));
        ready.put("read_state_available", 0);
        Map<String, Object> hidden = MessageContainerProjection.readMyState(ready);
        assertNull(hidden.get("unread_count"));
        assertNull(hidden.get("last_read_msg_id"));
        assertEquals(Integer.valueOf(0), hidden.get("read_state_available"));
        ready.put("read_state", "NOT_APPLICABLE");
        Map<String, Object> category = MessageContainerProjection.readMyState(ready);
        assertEquals("NOT_APPLICABLE", category.get("read_state"));
        assertNull(category.get("unread_count"));
        assertNull(MessageContainerProjection.readMyState(Map.of("pinned", 1, "hidden", 0, "muted", 0,
                "permanent_muted", 1, "effective_level", 1)));
        ready.put("read_state", null);
        ready.put("read_state_available", 1);
        ready.put("indexed", Boolean.FALSE);
        ready.put("unread_count", 7);
        Map<String, Object> contradiction = MessageContainerProjection.readMyState(ready);
        assertNull(contradiction.get("unread_count"));
        assertEquals(Integer.valueOf(0), contradiction.get("read_state_available"));
        ready.put("indexed", Integer.valueOf(1));
        Map<String, Object> numericIndex = MessageContainerProjection.readMyState(ready);
        assertNull(numericIndex.get("unread_count"));
    }
}
