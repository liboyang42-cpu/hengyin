package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.ImMessage;

class ImmutableReceiptProducerTest {

    @Test
    void replyMentionEditWithdrawPinSearchKeepOriginalReceipt() {
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(new InMemoryNotifyFixtures.MemoryReceiptStore());
        ImmutableReceiptProducer.AppliedProjection applied = new ImmutableReceiptProducer.AppliedProjection(
                "req-reply", ImmutableReceiptProducer.PROJECTION_APPLIED);
        for (ImmutableReceiptProducer.Kind kind : ImmutableReceiptProducer.Kind.values()) {
            String requestId = "req-" + kind.name().toLowerCase();
            ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                    new ImmutableReceiptProducer.OriginalRequest(requestId, kind, "body-" + kind, "UNKNOWN"));
            ImmutableReceiptProducer.OriginalReceipt replay = producer.accept(
                    new ImmutableReceiptProducer.OriginalRequest(requestId, kind, "body-" + kind, "UNKNOWN"));
            assertEquals(first.payloadHash, replay.payloadHash);
            assertEquals("UNKNOWN", producer.offlineReopen(requestId,
                    new ImmutableReceiptProducer.AppliedProjection(requestId,
                            ImmutableReceiptProducer.PROJECTION_APPLIED)).outcome);
            assertEquals(ImmutableReceiptProducer.UNKNOWN_NOT_RECOVERED,
                    producer.unknownRecover(requestId, applied).recoveryNote);
            assertTrue(producer.offlineReopen(requestId, applied).originalIsNotProjectionSuccess());
            assertFalse(producer.treatsAppliedProjectionAsOriginalProof(applied));
        }
        assertThrows(IllegalStateException.class, () -> producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("req-reply", ImmutableReceiptProducer.Kind.REPLY,
                        "tampered", "UNKNOWN")));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAcceptApplies() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        ImmutableReceiptProducer.OriginalReceipt blank = producer.recoverOriginal(null, null);
        assertNull(blank.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, blank.outcome);
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt recovered = producer.recoverOriginal("c09-rcpt-never", null);
        assertEquals("c09-rcpt-never", recovered.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, recovered.outcome);
        assertNull(store.find("c09-rcpt-never"), "未见 id 的 recoverOriginal 不得占账");
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("c09-rcpt-never",
                        ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED));
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED, first.outcome);
        assertEquals("c09-rcpt-never", first.requestId);
        assertEquals(1, store.size());
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED,
                producer.recoverOriginal("c09-rcpt-never", null).outcome);
        assertEquals(1, store.size());
    }

    @Test
    void unusedOfflineReopenOfNeverSeenDoesNotOccupyThenUniqueAcceptApplies() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        assertThrows(IllegalStateException.class, () -> producer.offlineReopen("c09-reopen-never", null));
        assertEquals(0, store.size(), "未见回执 offlineReopen 观察不得占账");
        assertNull(store.find("c09-reopen-never"));
        ImmutableReceiptProducer.OriginalReceipt unused = producer.recoverOriginal("c09-reopen-never", null);
        assertEquals("c09-reopen-never", unused.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, unused.outcome);
        ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("c09-reopen-never",
                        ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED));
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED, first.outcome);
        assertEquals("c09-reopen-never", first.requestId);
        assertEquals(1, store.size());
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED,
                producer.recoverOriginal("c09-reopen-never", null).outcome);
        assertEquals(1, store.size());
    }

    @Test
    void persistOriginalRequiresImSendAndRecoverFromJournalReadsExistingClientMessageId() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer missing = new ImmutableReceiptProducer(store);
        ImmutableReceiptProducer.WaitingSourceException sendMissing = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> missing.persistOriginal(new ImmutableReceiptProducer.OriginalRequest(
                        "c09-im-1", ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED)));
        assertEquals(ImmutableReceiptProducer.IM_SEND, sendMissing.getWaitingCapability());
        ImmutableReceiptProducer.WaitingSourceException readMissing = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> missing.recoverFromJournal(7L, 12L, "c09-im-1"));
        assertEquals(ImmutableReceiptProducer.IM_READ_SENT, readMissing.getWaitingCapability());
        assertEquals(0, store.size());
        assertNull(missing.recoverFromJournal(7L, 12L, null).requestId);

        ImMessage bare = new ImMessage();
        bare.setClientMessageId("c09-im-bare");
        bare.setContent("visible-without-digest");
        ImMessage original = new ImMessage();
        original.setClientMessageId("c09-im-1");
        original.setRequestHash("hash-im-1");
        original.setContent("{\"op\":\"pin\"}");
        ImmutableReceiptProducer wired = new ImmutableReceiptProducer(store, (memberId, conversationId, clientMessageId) -> {
            if ("c09-im-bare".equals(clientMessageId)) {
                return bare;
            }
            if (!"c09-im-1".equals(clientMessageId)) {
                return null;
            }
            return original;
        }, null);
        ImmutableReceiptProducer.OriginalReceipt noDigest = wired.recoverFromJournal(7L, 12L, "c09-im-bare");
        assertEquals("c09-im-bare", noDigest.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, noDigest.outcome);
        assertNull(noDigest.originalBody);
        ImmutableReceiptProducer.OriginalReceipt neverSeen = wired.recoverFromJournal(7L, 12L, "c09-im-missing");
        assertEquals("c09-im-missing", neverSeen.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, neverSeen.outcome);
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt recovered = wired.recoverFromJournal(7L, 12L, "c09-im-1");
        assertEquals("c09-im-1", recovered.requestId);
        assertEquals("hash-im-1", recovered.payloadHash);
        assertEquals("{\"op\":\"pin\"}", recovered.originalBody);
        assertNull(recovered.kind, "不把 IM msgType 翻译成本组 Kind");
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, recovered.outcome);
        assertEquals(0, store.size(), "journal 回读不得占内存 store");
        ImmutableReceiptProducer.WaitingSourceException stillNoSend = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> wired.persistOriginal(new ImmutableReceiptProducer.OriginalRequest(
                        "c09-im-1", ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED)));
        assertEquals(ImmutableReceiptProducer.IM_SEND, stillNoSend.getWaitingCapability());
    }

    @Test
    void sendOriginalDoesNotSendNonMessageKindsOrFingerprintBody() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ImmutableReceiptProducer.ChannelOriginalSend refuse = (actorId, conversationId, clientMessageId, msgType,
                content, extraJson, replyToId, mentionMemberIds, mentionRoleIds, mentionEveryone) -> {
            calls.incrementAndGet();
            throw new AssertionError("non-send kind must not call channel send");
        };
        for (ImmutableReceiptProducer.Kind kind : new ImmutableReceiptProducer.Kind[] {
                ImmutableReceiptProducer.Kind.SEARCH, ImmutableReceiptProducer.Kind.EDIT,
                ImmutableReceiptProducer.Kind.WITHDRAW, ImmutableReceiptProducer.Kind.PIN }) {
            ImmutableReceiptProducer.OriginalRequest request = new ImmutableReceiptProducer.OriginalRequest(
                    "c09-op-01", kind, "{\"fp\":1}", ImmutableReceiptProducer.OUTCOME_ACCEPTED);
            ImmutableReceiptProducer.NotASendException refused = assertThrows(
                    ImmutableReceiptProducer.NotASendException.class,
                    () -> producer.sendOriginal(request, 7L, 12L, Integer.valueOf(1), "hello", null, 88120L,
                            java.util.List.of(), java.util.List.of(), false, refuse));
            assertEquals(kind, refused.getKind());
        }
        assertEquals(0, calls.get());
        ImmutableReceiptProducer.OriginalRequest reply = new ImmutableReceiptProducer.OriginalRequest(
                "c09-send-01", ImmutableReceiptProducer.Kind.REPLY, "{\"fp\":1}",
                ImmutableReceiptProducer.OUTCOME_ACCEPTED);
        ImmutableReceiptProducer.WaitingSourceException fingerprint = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1), "{\"fp\":1}", null, 88120L,
                        java.util.List.of(), java.util.List.of(), false, refuse));
        assertEquals(ImmutableReceiptProducer.IM_SEND_COMMAND, fingerprint.getWaitingCapability());
        ImmutableReceiptProducer.WaitingSourceException missingReply = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1), "hello", null, null,
                        java.util.List.of(8L), java.util.List.of(), false, refuse));
        assertEquals(ImmutableReceiptProducer.IM_SEND_WIRE, missingReply.getWaitingCapability());
        ImmutableReceiptProducer.WaitingSourceException missingWriter = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1), "hello", null, 88120L,
                        java.util.List.of(), java.util.List.of(), false, null));
        assertEquals(ImmutableReceiptProducer.CHANNEL_MESSAGE_SEND, missingWriter.getWaitingCapability());
        assertEquals(0, calls.get());
        assertEquals(0, store.size());

        java.util.Map<String, Object> withoutDigest = new java.util.LinkedHashMap<String, Object>();
        withoutDigest.put("client_message_id", "c09-send-01");
        withoutDigest.put("operation", "SEND");
        withoutDigest.put("content", "hello");
        ImmutableReceiptProducer.OriginalReceipt ungarded = producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1),
                "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false,
                (actorId, conversationId, clientMessageId, msgType, content, extraJson, replyToId, mentionMemberIds,
                        mentionRoleIds, mentionEveryone) -> withoutDigest);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, ungarded.outcome);
        assertNull(ungarded.originalBody);
        java.util.Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
        sent.put("client_message_id", "c09-send-01");
        sent.put("operation", "SEND");
        sent.put("digest", "send-digest-01");
        sent.put("content", "hello");
        ImmutableReceiptProducer.OriginalReceipt receipt = producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1),
                "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false,
                (actorId, conversationId, clientMessageId, msgType, content, extraJson, replyToId, mentionMemberIds,
                        mentionRoleIds, mentionEveryone) -> {
                    calls.incrementAndGet();
                    assertEquals(Long.valueOf(7L), actorId);
                    assertEquals(Long.valueOf(12L), conversationId);
                    assertEquals("hello", content);
                    assertEquals("c09-send-01", clientMessageId);
                    assertEquals(Long.valueOf(88120L), replyToId);
                    return sent;
                });
        assertEquals("hello", receipt.originalBody);
        assertEquals(1, calls.get());
        assertThrows(ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.recoverFromJournal(7L, 12L, "c09-send-01"));
        assertEquals(1, calls.get());
        assertEquals(0, store.size());
    }
}
