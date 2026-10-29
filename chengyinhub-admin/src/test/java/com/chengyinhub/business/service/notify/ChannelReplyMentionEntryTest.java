package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.chengyinhub.common.exception.ServiceException;

class ChannelReplyMentionEntryTest {

    @Test
    void nullChannelIsNotTheProductionEntry() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer.WaitingSourceException missing = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> new ChannelReplyMentionEntry(store, null));
        assertEquals(ImmutableReceiptProducer.CHANNEL_MESSAGE_SEND, missing.getWaitingCapability());
        assertEquals(0, store.size());
    }

    @Test
    void replyUsesTheSuppliedChannelAndRecoversTheSameKey() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        String key = "c09-send-key-0001";
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        ImmutableReceiptProducer.ChannelMessageOriginalSend channel = new ImmutableReceiptProducer.ChannelMessageOriginalSend() {
            @Override
            public Map<String, Object> send(Long actor, Long conversation, String client, Integer type, String content,
                    String extra, Long replyTo, List<Long> members, List<Long> roles, boolean everyone) {
                sends.incrementAndGet();
                if (replyTo != null) {
                    assertEquals(Long.valueOf(88120L), replyTo);
                } else {
                    assertEquals(List.of(Long.valueOf(8L)), members);
                }
                Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
                sent.put("id", Long.valueOf(42L));
                sent.put("request_id", client);
                return sent;
            }

            @Override
            public Map<String, Object> receipt(Long actor, String requestId) {
                reads.incrementAndGet();
                assertEquals(key, requestId);
                Map<String, Object> receipt = new java.util.LinkedHashMap<String, Object>();
                receipt.put("request_id", requestId);
                receipt.put("state", "PENDING");
                receipt.put("outcome_confirmed", Boolean.FALSE);
                return receipt;
            }
        };
        ChannelReplyMentionEntry entry = new ChannelReplyMentionEntry(store, channel);
        ImmutableReceiptProducer.OriginalReceipt sent = entry.reply(key, 7L, 12L, Integer.valueOf(1), "hello", null,
                88120L);
        assertEquals(1, sends.get());
        assertEquals(key, sent.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, sent.outcome);
        assertNull(sent.payloadHash);
        assertNull(sent.originalBody);
        Map<String, Object> recovered = entry.recover(7L, key);
        assertEquals(1, reads.get());
        assertEquals(key, recovered.get("request_id"));
        assertEquals("PENDING", recovered.get("state"));
        assertEquals(0, store.size());
        ServiceException padded = assertThrows(ServiceException.class, () -> entry.reply(" c09-send-key-0001 ", 7L, 12L,
                Integer.valueOf(1), "hello", null, 88120L));
        assertEquals("INVALID_REQUEST_ID", padded.getDetailMessage());
        assertEquals(1, sends.get());
        String mentionKey = "c09-send-key-0002";
        ImmutableReceiptProducer.OriginalReceipt mentioned = entry.mention(mentionKey, 7L, 12L, Integer.valueOf(1),
                "hello", null, List.of(Long.valueOf(8L)), List.of(), false);
        assertEquals(mentionKey, mentioned.requestId);
        assertEquals(2, sends.get());
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, mentioned.outcome);
        assertEquals(0, store.size());
    }

    @Test
    void forwardReturnsTheOriginalSendMapOnce() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        String key = "c09-send-key-0001";
        AtomicInteger sends = new AtomicInteger();
        ImmutableReceiptProducer.ChannelMessageOriginalSend channel =
                (actor, conversation, client, type, content, extra, replyTo, members, roles, everyone) -> {
                    sends.incrementAndGet();
                    Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
                    sent.put("request_id", client);
                    if ("c09-send-key-0003".equals(client)) {
                        sent.put("message_unavailable", Boolean.TRUE);
                        return sent;
                    }
                    sent.put("id", Long.valueOf(42L));
                    return sent;
                };
        ChannelReplyMentionEntry entry = new ChannelReplyMentionEntry(store, channel);
        Map<String, Object> sent = entry.forward(7L, 12L, key, Integer.valueOf(1), "hello", null, 88120L, List.of(),
                List.of(), false);
        assertEquals(1, sends.get());
        assertEquals(Long.valueOf(42L), sent.get("id"));
        assertEquals(key, sent.get("request_id"));
        assertEquals(0, store.size());
        Map<String, Object> unavailable = entry.forward(7L, 12L, "c09-send-key-0003", Integer.valueOf(1), "hello",
                null, 88120L, List.of(), List.of(), false);
        assertEquals(Boolean.TRUE, unavailable.get("message_unavailable"));
        assertNull(unavailable.get("id"));
        ServiceException blank = assertThrows(ServiceException.class, () -> entry.forward(7L, 12L, " ", Integer.valueOf(1),
                "hello", null, 88120L, List.of(), List.of(), false));
        assertEquals(Integer.valueOf(400), blank.getCode());
        assertEquals("INVALID_REQUEST_ID", blank.getDetailMessage());
        ServiceException empty = assertThrows(ServiceException.class, () -> entry.forward(7L, 12L, key, Integer.valueOf(1),
                "  ", null, 88120L, List.of(), List.of(), false));
        assertEquals("INVALID_MESSAGE_CONTENT", empty.getDetailMessage());
        assertEquals(2, sends.get());
    }
}
