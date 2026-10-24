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
    }
}
