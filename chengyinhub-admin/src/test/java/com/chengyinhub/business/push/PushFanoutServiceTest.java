package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.push.PushMapper;

class PushFanoutServiceTest
{
    private PushMapper mapper;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private PushFanoutService service;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        mapper = mock(PushMapper.class);
        ApnsTransport transport = (host, path, headers, body) ->
        {
            paths.add(path);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return new ApnsTransport.ApnsResponse(200, null);
        };
        ApnsSender sender = new ApnsSender("K", "T", pem, "com.chengyinhub.chengyinApp", "sandbox", mapper, transport, () -> 1L);
        service = new PushFanoutService(mapper, sender, new PushCollapseWindow(30_000L, clock::get));
    }

    private static Map<String, Object> message(long id, int convType, String bizKey)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("messageId", id);
        m.put("conversationId", 9L);
        m.put("senderId", 1L);
        m.put("msgType", 1);
        m.put("content", "今晚七点集合");
        m.put("status", 0);
        m.put("checkStatus", 1);
        m.put("conversationType", convType);
        m.put("bizKey", bizKey);
        m.put("senderName", "小李");
        return m;
    }

    private static Map<String, Object> member(long id, Object... kv)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("memberId", id);
        m.put("memberMuted", 0);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static PushDevice device(long member, char c)
    {
        return new PushDevice(member, member, String.valueOf(c).repeat(64), "SANDBOX", "zh-Hans");
    }

    @Test
    void groupMessagePushesOnlyToRecipientsWhosePreferencesAllowIt()
    {
        when(mapper.selectChatMessage(100L)).thenReturn(message(100L, 4, "team_3"));
        when(mapper.selectMentions(100L)).thenReturn(List.of(Map.of("mentionType", "USER", "targetId", 4L)));
        when(mapper.selectChatAudience(9L, 1L, false)).thenReturn(List.of(
                member(2L),                          // 默认全部
                member(3L, "memberMuted", 1),        // 会话免打扰
                member(4L, "convLevel", 1),          // 仅@，被@了
                member(5L, "convLevel", 1)));        // 仅@，没被@
        when(mapper.selectEnabledDevices(any(), any())).thenAnswer(inv ->
        {
            java.util.Collection<Long> ids = inv.getArgument(0);
            assertEquals(List.of(2L, 4L), new ArrayList<>(ids));
            return List.of(device(2L, 'b'), device(4L, 'd'));
        });

        assertEquals(2, service.fanOutChat(100L));
        assertEquals(List.of("/3/device/" + "b".repeat(64), "/3/device/" + "d".repeat(64)), paths);
        JSONObject first = JSON.parseObject(bodies.get(0));
        assertEquals("chat", first.getString("kind"));
        assertEquals("9", first.getString("conversation_id"));
        assertEquals("/subpackageB/pages/im/chat/index?conversationId=9&type=4", first.getString("page"));
        assertEquals("chat-9", first.getJSONObject("aps").getString("thread-id"));
        assertEquals("小李：今晚七点集合", first.getJSONObject("aps").getJSONObject("alert").getString("body"));
        assertTrue(JSON.parseObject(bodies.get(1)).getJSONObject("aps").getJSONObject("alert").getString("body").startsWith("[有人@你]"));
    }

    @Test
    void burstsInTheSameConversationCollapseWithinThirtySeconds()
    {
        when(mapper.selectChatMessage(anyLong())).thenAnswer(inv -> message(inv.getArgument(0), 1, "single_1_2"));
        when(mapper.selectMentions(anyLong())).thenReturn(List.of());
        when(mapper.selectChatAudience(9L, 1L, true)).thenReturn(List.of(member(2L)));
        when(mapper.selectEnabledDevices(any(), any())).thenReturn(List.of(device(2L, 'b')));

        assertEquals(1, service.fanOutChat(100L));
        clock.addAndGet(10_000L);
        assertEquals(0, service.fanOutChat(101L));
        clock.addAndGet(20_000L);
        assertEquals(1, service.fanOutChat(102L));
        JSONObject dm = JSON.parseObject(bodies.get(0));
        assertEquals("小李", dm.getJSONObject("aps").getJSONObject("alert").getString("title"));
    }

    @Test
    void channelMessagesAreNotPushedWhenReadAccessCannotBeConfirmed()
    {
        when(mapper.selectChatMessage(100L)).thenReturn(message(100L, 5, null));
        when(mapper.selectMentions(100L)).thenReturn(List.of());
        when(mapper.selectChannelClubId(9L)).thenReturn(null);
        when(mapper.selectChatAudience(9L, 1L, false)).thenReturn(List.of(member(2L)));
        assertEquals(0, service.fanOutChat(100L));
        assertTrue(paths.isEmpty());
    }

    @Test
    void unreviewedOrRecalledMessagesAreNotPushed()
    {
        Map<String, Object> pending = message(100L, 1, null);
        pending.put("checkStatus", 0);
        when(mapper.selectChatMessage(100L)).thenReturn(pending);
        assertEquals(0, service.fanOutChat(100L));
        assertTrue(paths.isEmpty());
    }

    @Test
    void unconfiguredSenderDoesNotTouchTheDatabase()
    {
        PushMapper untouched = mock(PushMapper.class);
        ApnsSender off = new ApnsSender("", "", "", "", "", untouched, (h, p, hd, b) -> { throw new AssertionError(); }, () -> 1L);
        PushFanoutService idle = new PushFanoutService(untouched, off, new PushCollapseWindow());
        idle.chatMessageSent(1L);
        idle.clubNoticeDelivered(1L, 2L, null, "t", "c", 3L);
        idle.eventReminder(1L, 2L, "x", null, 2L, 5L);
        verifyNoInteractions(untouched);
        assertEquals(false, idle.apnsConfigured());
    }

    @Test
    void clubNoticePushOpensTheClubOrActivityPage()
    {
        when(mapper.selectEnabledDevices(any(), any())).thenReturn(List.of(device(3L, 'c')));
        service.clubNoticeDelivered(61L, 21L, null, "集合提醒", "请提前到场", 3L);
        service.clubNoticeDelivered(62L, 22L, 31L, "活动通知", "改期", 3L);
        JSONObject club = JSON.parseObject(bodies.get(0));
        assertEquals("club", club.getString("kind"));
        assertEquals("/pages/club/detail/index?id=21", club.getString("page"));
        assertEquals("/pages/activity/detail/index?id=31", JSON.parseObject(bodies.get(1)).getString("page"));
    }

    @Test
    void eventReminderIsPushedOncePerTicketAndMember()
    {
        when(mapper.selectEnabledDevices(any(), any())).thenReturn(List.of(device(3L, 'c')));
        service.eventReminder(7L, 3L, "城市夜跑", new java.util.Date(), 2L, 55L);
        service.eventReminder(7L, 3L, "城市夜跑", new java.util.Date(), 2L, 55L);
        assertEquals(1, bodies.size());
        JSONObject event = JSON.parseObject(bodies.get(0));
        assertEquals("event", event.getString("kind"));
        assertEquals("/pages/activity/detail/index?id=55", event.getString("page"));
        service.eventReminder(8L, 3L, "主题", null, 1L, 66L);
        assertEquals("/pages/topic/index/index?id=66", JSON.parseObject(bodies.get(1)).getString("page"));
    }
}
