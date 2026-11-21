package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

class PushPayloadBuilderTest
{
    @Test
    void chatPayloadMatchesTheAppContract()
    {
        String json = PushPayloadBuilder.build(new PushMessage("chat", "小李", "你好", "chat-12",
                "/subpackageB/pages/im/chat/index?conversationId=12&type=1", "12"));
        JSONObject root = JSON.parseObject(json);
        JSONObject aps = root.getJSONObject("aps");
        assertEquals("小李", aps.getJSONObject("alert").getString("title"));
        assertEquals("你好", aps.getJSONObject("alert").getString("body"));
        assertEquals("default", aps.getString("sound"));
        assertEquals("chat-12", aps.getString("thread-id"));
        assertEquals("/subpackageB/pages/im/chat/index?conversationId=12&type=1", root.getString("page"));
        assertEquals("chat", root.getString("kind"));
        assertEquals("12", root.get("conversation_id"));
    }

    @Test
    void chatPayloadCarriesTheMessageIdOnlyWhenNumeric()
    {
        JSONObject withId = JSON.parseObject(PushPayloadBuilder.build(new PushMessage("chat", "小李", "你好", "chat-12",
                "/subpackageB/pages/im/chat/index?conversationId=12&type=1&messageId=88", "12", "88")));
        assertEquals("88", withId.getString("message_id"));
        JSONObject legacy = JSON.parseObject(PushPayloadBuilder.build(new PushMessage("chat", "小李", "你好", "chat-12",
                "/subpackageB/pages/im/chat/index?conversationId=12&type=1", "12")));
        assertFalse(legacy.containsKey("message_id"));
        JSONObject bogus = JSON.parseObject(PushPayloadBuilder.build(new PushMessage("chat", "小李", "你好", "chat-12",
                "/subpackageB/pages/im/chat/index?conversationId=12&type=1", "12", "x")));
        assertFalse(bogus.containsKey("message_id"));
        JSONObject club = JSON.parseObject(PushPayloadBuilder.build(
                new PushMessage("club", "集合提醒", "请提前到场", "club-21", "/pages/club/detail/index?id=21", null, "5")));
        assertFalse(club.containsKey("message_id"));
    }

    @Test
    void clubAndEventPayloadsHaveNoConversationId()
    {
        JSONObject club = JSON.parseObject(PushPayloadBuilder.build(
                new PushMessage("club", "集合提醒", "请提前到场", "club-21", "/pages/club/detail/index?id=21", null)));
        assertEquals("club", club.getString("kind"));
        assertEquals("/pages/club/detail/index?id=21", club.getString("page"));
        assertFalse(club.containsKey("conversation_id"));
        JSONObject event = JSON.parseObject(PushPayloadBuilder.build(
                new PushMessage("event", "出发提醒", "x", "event-5", "/pages/activity/detail/index?id=5", null)));
        assertEquals("event", event.getString("kind"));
    }

    @Test
    void rejectsAbsoluteOrSchemePagesAndUnknownKinds()
    {
        assertThrows(IllegalArgumentException.class, () -> PushPayloadBuilder.build(
                new PushMessage("club", "t", "b", "x", "https://www.chengyinhub.com/pages/club/detail/index?id=1", null)));
        assertThrows(IllegalArgumentException.class, () -> PushPayloadBuilder.build(
                new PushMessage("club", "t", "b", "x", "//evil.example/pages", null)));
        assertThrows(IllegalArgumentException.class, () -> PushPayloadBuilder.build(
                new PushMessage("club", "t", "b", "x", "pages/club/detail/index", null)));
        assertThrows(IllegalArgumentException.class, () -> PushPayloadBuilder.build(
                new PushMessage("promo", "t", "b", "x", "/pages/index/index", null)));
        assertThrows(IllegalArgumentException.class, () -> PushPayloadBuilder.build(
                new PushMessage("chat", "t", "b", "x", "/subpackageB/pages/im/chat/index", null)));
    }

    @Test
    void longBodiesAreTruncatedAndPayloadStaysUnderFourKilobytes()
    {
        String huge = "长".repeat(5000);
        String json = PushPayloadBuilder.build(new PushMessage("chat", "标".repeat(500), huge, "chat-1",
                "/subpackageB/pages/im/chat/index?conversationId=1&type=4", "1"));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= PushPayloadBuilder.MAX_BYTES);
        String body = JSON.parseObject(json).getJSONObject("aps").getJSONObject("alert").getString("body");
        assertTrue(body.endsWith("…"));
        assertEquals(PushPayloadBuilder.MAX_BODY_CHARS, body.codePointCount(0, body.length()));
        String title = JSON.parseObject(json).getJSONObject("aps").getJSONObject("alert").getString("title");
        assertEquals(PushPayloadBuilder.MAX_TITLE_CHARS, title.codePointCount(0, title.length()));
    }

    @Test
    void cutKeepsSurrogatePairsIntact()
    {
        String emoji = "😀".repeat(10);
        String cut = PushPayloadBuilder.cut(emoji, 5);
        assertEquals("😀😀😀😀…", cut);
    }
}
