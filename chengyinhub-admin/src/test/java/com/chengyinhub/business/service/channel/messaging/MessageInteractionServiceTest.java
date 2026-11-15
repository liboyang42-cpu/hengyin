package com.chengyinhub.business.service.channel.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.mapper.channel.messaging.ImMessagingMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.service.IImRealtimePublisher;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.common.exception.ServiceException;

class MessageInteractionServiceTest
{
    private final ImMessagingMapper mapper = mock(ImMessagingMapper.class);
    private final ChannelMessageMapper messages = mock(ChannelMessageMapper.class);
    private final IImService im = mock(IImService.class);
    private final ChannelMessageService channels = mock(ChannelMessageService.class);
    private final IImRealtimePublisher realtime = mock(IImRealtimePublisher.class);
    private final MessageInteractionService service = new MessageInteractionService(mapper, messages, im, channels, realtime);

    private static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void reactingChecksReadAccessTheChannelPermissionAndPublishesOnlyOnChange()
    {
        when(channels.isChannel(9L)).thenReturn(true);
        when(mapper.selectMessageHead(50L)).thenReturn(Map.of("conversationId", 9L, "status", 0, "checkStatus", 1));
        when(mapper.insertReaction(50L, 1L, "heart", 9L)).thenReturn(1, 0);
        when(mapper.selectReactionCounts(List.of(50L), 1L)).thenReturn(List.of(Map.of("messageId", 50L, "emoji", "heart", "total", 2L, "mine", 1)));

        Map<String, Object> r = service.react(1, body("conversation_id", "9", "message_id", "50", "emoji", "heart"));
        assertEquals(true, r.get("changed"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("reactions");
        assertEquals(2L, list.get(0).get("count"));
        assertEquals(true, list.get(0).get("mine"));
        verify(im).requireReadable(1L, 9L);
        verify(channels).requireReact(1L, 9L);

        assertEquals(false, service.react(1, body("conversation_id", "9", "message_id", "50", "emoji", "heart")).get("changed"));
        verify(realtime, times(1)).publish(eq(9L), eq(IImRealtimePublisher.REACTION_UPDATED), any());
    }

    @Test
    void reactionsRejectUnknownEmojiForeignMessagesAndExtraFields()
    {
        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.react(1, body("conversation_id", "9", "message_id", "50", "emoji", "skull"))).getCode());
        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.react(1, body("conversation_id", "9", "message_id", "50", "emoji", "heart", "x", 1))).getCode());
        when(mapper.selectMessageHead(50L)).thenReturn(Map.of("conversationId", 8L, "status", 0, "checkStatus", 1));
        assertEquals(404, assertThrows(ServiceException.class,
                () -> service.react(1, body("conversation_id", "9", "message_id", "50", "emoji", "heart"))).getCode());
        verify(mapper, never()).insertReaction(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    void readGateFailureStopsEveryRead()
    {
        doThrow(new ServiceException("无权查看该会话", 403)).when(im).requireReadable(1L, 9L);
        assertThrows(ServiceException.class, () -> service.extras(1, 9, "50"));
        assertThrows(ServiceException.class, () -> service.thread(1, 9, 50, null, 10));
        assertThrows(ServiceException.class, () -> service.typing(1, 9));
        verify(realtime, never()).publish(anyLong(), anyString(), any());
    }

    @Test
    void extrasJoinReactionsQuotesAndThreadCountsForThisConversationOnly()
    {
        when(messages.selectMessages(List.of(50L, 51L, 99L))).thenReturn(List.of(
                Map.of("id", 50L, "conversationId", 9L), Map.of("id", 51L, "conversationId", 9L), Map.of("id", 99L, "conversationId", 8L)));
        when(mapper.selectRefs(List.of(50L, 51L))).thenReturn(List.of(Map.of("messageId", 51L, "replyToId", 40L, "threadRootId", 0L)));
        when(messages.selectMessages(List.of(40L))).thenReturn(List.of(Map.of("id", 40L, "conversationId", 9L, "status", 0,
                "checkStatus", 1, "senderId", 4L, "senderName", "小李", "msgType", 1, "content", "x".repeat(100))));
        when(mapper.selectThreadSummaries(9L, List.of(50L, 51L))).thenReturn(List.of(Map.of("rootId", 50L, "replyCount", 3L, "lastReplyId", 60L)));
        when(mapper.selectReactionCounts(List.of(50L, 51L), 1L)).thenReturn(List.of());

        Map<String, Object> data = service.extras(1, 9, "50,51,99");
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> items = (Map<String, Map<String, Object>>) data.get("items");
        assertEquals(2, items.size());
        assertNull(items.get("50").get("reply_to"));
        @SuppressWarnings("unchecked") Map<String, Object> thread = (Map<String, Object>) items.get("50").get("thread");
        assertEquals(3L, thread.get("reply_count"));
        @SuppressWarnings("unchecked") Map<String, Object> quote = (Map<String, Object>) items.get("51").get("reply_to");
        assertEquals("OK", quote.get("state"));
        assertEquals("小李", quote.get("sender_name"));
        assertTrue(((String) quote.get("excerpt")).endsWith("…"));
    }

    @Test
    void recalledQuotesShowNoContent()
    {
        when(messages.selectMessages(List.of(51L))).thenReturn(List.of(Map.of("id", 51L, "conversationId", 9L)));
        when(mapper.selectRefs(List.of(51L))).thenReturn(List.of(Map.of("messageId", 51L, "replyToId", 40L, "threadRootId", 0L)));
        when(messages.selectMessages(List.of(40L))).thenReturn(List.of(Map.of("id", 40L, "conversationId", 9L, "status", 1,
                "checkStatus", 1, "senderId", 4L, "msgType", 1, "content", "secret")));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> items =
                (Map<String, Map<String, Object>>) service.extras(1, 9, "51").get("items");
        @SuppressWarnings("unchecked") Map<String, Object> quote = (Map<String, Object>) items.get("51").get("reply_to");
        assertEquals("RECALLED", quote.get("state"));
        assertNull(quote.get("excerpt"));
    }

    @Test
    void extrasLimitTheBatch()
    {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 51; i++) ids.append(i).append(',');
        assertEquals(400, assertThrows(ServiceException.class, () -> service.extras(1, 9, ids.toString())).getCode());
    }

    @Test
    void typingIsThrottledPerMemberAndConversation()
    {
        assertEquals(true, service.typing(1, 9).get("sent"));
        assertEquals(false, service.typing(1, 9).get("sent"));
        assertEquals(true, service.typing(2, 9).get("sent"));
        verify(realtime, times(2)).publish(eq(9L), eq(IImRealtimePublisher.TYPING), any());
    }

    @Test
    void threadListsVisibleRepliesAfterTheCursor()
    {
        when(mapper.selectMessageHead(50L)).thenReturn(Map.of("conversationId", 9L, "status", 0, "checkStatus", 1));
        when(mapper.selectThreadMessageIds(9L, 50L, 0L, 3)).thenReturn(List.of(61L, 62L, 63L));
        when(messages.selectMessages(List.of(50L, 61L, 62L))).thenReturn(List.of(
                Map.of("id", 50L, "conversationId", 9L, "status", 0, "checkStatus", 1, "senderId", 4L, "msgType", 1, "content", "root"),
                Map.of("id", 61L, "conversationId", 9L, "status", 0, "checkStatus", 1, "senderId", 5L, "msgType", 1, "content", "a"),
                Map.of("id", 62L, "conversationId", 9L, "status", 0, "checkStatus", 1, "senderId", 6L, "msgType", 1, "content", "b")));
        Map<String, Object> data = service.thread(1, 9, 50, null, 2);
        assertEquals(true, data.get("has_more"));
        assertEquals("62", data.get("next_after"));
        assertEquals(2, ((List<?>) data.get("list")).size());
    }
}
