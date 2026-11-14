package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelMessageServiceTest
{
    static final long OWNER = 11, MEMBER = 22, OTHER = 23, STRANGER = 33, CLUB = 42, CH = 1001, MSG = 5001;

    ChannelPlatformMapper channels;
    ChannelMessageMapper messages;
    ChannelMessageService service;
    ChannelRow channel;
    Map<String, Object> message;

    @BeforeEach void setUp()
    {
        channels = mock(ChannelPlatformMapper.class);
        messages = mock(ChannelMessageMapper.class);
        ReceiptFake.install(channels);
        when(channels.selectClubGuild(CLUB)).thenReturn(Map.of("id", CLUB, "ownerId", OWNER, "status", 1, "delFlag", 0));
        when(channels.countClubMember(eq(CLUB), anyLong())).thenAnswer(i -> (long) i.getArgument(1) == STRANGER ? 0 : 1);
        when(channels.selectMemberRoles(anyInt(), anyLong(), anyLong())).thenReturn(List.of());
        when(channels.selectOverwrites(anyLong())).thenReturn(List.of());
        when(channels.selectConversationType(CH)).thenReturn(5);
        channel = ChannelPlatformServiceTest.row(CH, 0, 1, "general");
        when(channels.selectChannel(CH)).thenAnswer(i -> channel);
        service = new ChannelMessageService(messages, channels, new ChannelGuildAccess(channels));
        message = new HashMap<>(Map.of("id", MSG, "conversationId", CH, "senderId", MEMBER, "msgType", 1,
                "content", "hello", "status", 0, "checkStatus", 1, "version", 2));
        when(messages.lockMessage(MSG)).thenAnswer(i -> message);
        when(messages.selectMentions(anyList())).thenReturn(List.of());
    }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String code(Runnable r) { return assertThrows(ChannelRejection.class, r::run).getErrorCode(); }

    @Test void gateRejectsStrangersAndArchivedWrites()
    {
        assertTrue(service.isChannel(CH));
        ServiceException denied = assertThrows(ServiceException.class, () -> service.requireRead(STRANGER, CH));
        assertEquals(403, denied.getCode());
        service.requireRead(MEMBER, CH);
        verify(channels).ensureConversationMember(CH, MEMBER);
        channel.setStatus(1);
        ServiceException readOnly = assertThrows(ServiceException.class, () -> service.requireSend(MEMBER, CH));
        assertEquals(409, readOnly.getCode());
        assertEquals("CHANNEL_READ_ONLY", readOnly.getDetailMessage());
    }

    @Test void slowmodeAppliesToMembersButNotOwner()
    {
        channel.setSlowmodeSec(60);
        when(messages.lastSentAt(eq(CH), anyLong())).thenReturn(new java.util.Date());
        assertEquals(429, assertThrows(ServiceException.class, () -> service.requireSend(MEMBER, CH)).getCode());
        service.requireSend(OWNER, CH);
    }

    @Test void editWritesRevisionMutationAndBumpsVersion()
    {
        when(messages.updateContent(MSG, 2L, "hello world")).thenReturn(1);
        Map<String, Object> b = body("conversation_id", "1001", "message_id", "5001", "expected_version", 2,
                "content", "hello world", "mention_member_ids", List.of("23"), "mention_role_ids", List.of(),
                "mention_everyone", false, "request_id", "edit-request-000001");
        Map<String, Object> r = service.edit(MEMBER, b);
        assertEquals(Boolean.TRUE, r.get("changed"));
        assertEquals(3, ((Number) r.get("version")).intValue());
        assertEquals(r.get("version"), r.get("revision"));
        verify(messages).insertRevision(MSG, 2L, "hello", 1, MEMBER);
        verify(messages).insertMention(MSG, "USER", 23L);
        verify(messages).insertMutation(argThat(m -> "EDIT".equals(m.get("kind")) && Long.valueOf(3).equals(m.get("version"))));
    }

    @Test void editRejectsOthersStaleVersionAndEveryoneWithoutPermission()
    {
        Map<String, Object> stale = body("conversation_id", "1001", "message_id", "5001", "expected_version", 1,
                "content", "x", "request_id", "edit-request-000002");
        assertEquals("MESSAGE_VERSION_CONFLICT", code(() -> service.edit(MEMBER, stale)));
        Map<String, Object> notMine = body("conversation_id", "1001", "message_id", "5001", "expected_version", 2,
                "content", "x", "request_id", "edit-request-000003");
        assertEquals("MESSAGE_FORBIDDEN", code(() -> service.edit(OTHER, notMine)));
        Map<String, Object> everyone = body("conversation_id", "1001", "message_id", "5001", "expected_version", 2,
                "content", "x", "mention_everyone", true, "request_id", "edit-request-000004");
        assertEquals("MESSAGE_FORBIDDEN", code(() -> service.edit(MEMBER, everyone)));
        Map<String, Object> shortId = body("conversation_id", "1001", "message_id", "5001", "expected_version", 2,
                "content", "x", "request_id", "short");
        assertEquals("INVALID_FIELD", code(() -> service.edit(MEMBER, shortId)));
    }

    @Test void recallByModeratorAndAlreadyRecalledIsUnchanged()
    {
        Map<String, Object> b = body("conversation_id", "1001", "message_id", "5001", "expected_version", 2,
                "reason", "", "request_id", "recall-request-0001");
        assertEquals("MESSAGE_FORBIDDEN", code(() -> service.recall(OTHER, b)));
        when(messages.recall(MSG, 2L)).thenReturn(1);
        Map<String, Object> r = service.recall(OWNER, body("conversation_id", "1001", "message_id", "5001",
                "expected_version", 2, "reason", "spam", "request_id", "recall-request-0002"));
        assertEquals(1, r.get("status"));
        assertEquals(Boolean.TRUE, r.get("changed"));
        verify(messages).deletePin(CH, MSG);
        message.put("status", 1);
        message.put("version", 3);
        Map<String, Object> again = service.recall(MEMBER, body("conversation_id", "1001", "message_id", "5001",
                "expected_version", 2, "reason", "", "request_id", "recall-request-0003"));
        assertEquals(Boolean.FALSE, again.get("changed"));
    }

    @Test void pinRequiresPermissionAndCapsAtTwenty()
    {
        Map<String, Object> b = body("conversation_id", "1001", "message_id", "5001", "request_id", "pin-request-000001");
        assertEquals("MESSAGE_FORBIDDEN", code(() -> service.pin(MEMBER, b, true)));
        List<Map<String, Object>> full = new ArrayList<>();
        for (int i = 0; i < 20; i++) full.add(Map.of("id", (long) i + 1, "messageId", 9000L + i, "sortNo", 2000 - i * 100));
        when(messages.lockPins(CH)).thenReturn(full);
        assertEquals("PIN_LIMIT_REACHED", code(() -> service.pin(OWNER, body("conversation_id", "1001", "message_id", "5001",
                "request_id", "pin-request-000002"), true)));
    }

    @Test void sortRequiresExactPinSet()
    {
        when(messages.lockPins(CH)).thenReturn(List.of(Map.of("id", 1L, "messageId", 5L, "sortNo", 100),
                Map.of("id", 2L, "messageId", 6L, "sortNo", 200)));
        assertEquals("PIN_SET_CHANGED", code(() -> service.sortPins(OWNER, body("conversation_id", "1001",
                "ordered_pin_ids", List.of("1"), "request_id", "sort-request-000001"))));
        Map<String, Object> r = service.sortPins(OWNER, body("conversation_id", "1001", "ordered_pin_ids", List.of("2", "1"),
                "request_id", "sort-request-000002"));
        assertEquals(2, r.get("updated"));
        assertEquals(Map.of("2", 200, "1", 100), r.get("sort_nos"));
    }

    @Test void mutationPageInvalidatesRecallsAndAdvancesCursor()
    {
        when(messages.selectMutations(CH, 10L, 51)).thenReturn(List.of(
                Map.of("id", 11L, "messageId", MSG, "version", 3L, "kind", "EDIT"),
                Map.of("id", 12L, "messageId", 5002L, "version", 2L, "kind", "RECALL")));
        Map<String, Object> row = new HashMap<>(message);
        row.put("version", 3);
        when(messages.selectMessages(anyList())).thenReturn(List.of(row));
        when(messages.selectPinnedIds(eq(CH), anyList())).thenReturn(List.of());
        Map<String, Object> page = service.mutations(MEMBER, CH, "10", 50);
        assertEquals("12", page.get("next_mutation_cursor"));
        assertEquals("VISIBLE_ORDER_PLUS_MUTATIONS_V1", page.get("sync_contract"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) page.get("list");
        assertNotNull(list.get(0).get("message"));
        assertEquals(Boolean.TRUE, list.get(1).get("invalidate"));
        assertFalse(list.get(1).containsKey("message"));
    }

    @Test void snapshotUsesDecimalStringsAndHidesRecalledBody()
    {
        Map<String, Object> recalled = new HashMap<>(message);
        recalled.put("status", 1);
        Map<String, Object> v = ChannelMessageService.snapshotView(recalled, false, null);
        assertEquals("5001", v.get("id"));
        assertEquals("1001", v.get("conversationId"));
        assertEquals("22", v.get("senderId"));
        assertFalse(v.containsKey("content"));
    }
}
