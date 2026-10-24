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
    }
}
