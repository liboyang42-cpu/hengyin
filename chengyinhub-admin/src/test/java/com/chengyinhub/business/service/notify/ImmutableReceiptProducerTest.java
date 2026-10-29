package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.common.exception.ServiceException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        original.setConversationId(12L);
        original.setSenderId(7L);
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
        ImMessage otherConversation = new ImMessage();
        otherConversation.setClientMessageId("c09-im-1");
        otherConversation.setConversationId(99L);
        otherConversation.setSenderId(7L);
        otherConversation.setRequestHash("hash-im-1");
        otherConversation.setContent("other-conversation");
        ImMessage otherSender = new ImMessage();
        otherSender.setClientMessageId("c09-im-other-sender");
        otherSender.setConversationId(12L);
        otherSender.setSenderId(8L);
        otherSender.setRequestHash("hash-im-other");
        otherSender.setContent("other-sender");
        ImmutableReceiptProducer mismatched = new ImmutableReceiptProducer(store,
                (memberId, conversationId, clientMessageId) -> {
                    if ("c09-im-other-sender".equals(clientMessageId)) {
                        return otherSender;
                    }
                    return otherConversation;
                }, null);
        ImmutableReceiptProducer.OriginalReceipt wrongConversation =
                mismatched.recoverFromJournal(7L, 12L, "c09-im-1");
        assertEquals("c09-im-1", wrongConversation.requestId);
        assertNull(wrongConversation.originalBody);
        ImmutableReceiptProducer.OriginalReceipt wrongSender =
                mismatched.recoverFromJournal(7L, 12L, "c09-im-other-sender");
        assertEquals("c09-im-other-sender", wrongSender.requestId);
        assertNull(wrongSender.originalBody);
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
        for (ImmutableReceiptProducer.Kind kind : new ImmutableReceiptProducer.Kind[] {
                ImmutableReceiptProducer.Kind.SEARCH, ImmutableReceiptProducer.Kind.EDIT,
                ImmutableReceiptProducer.Kind.WITHDRAW, ImmutableReceiptProducer.Kind.PIN }) {
            ImmutableReceiptProducer.OriginalRequest request = new ImmutableReceiptProducer.OriginalRequest(
                    "c09-op-01", kind, "{\"fp\":1}", ImmutableReceiptProducer.OUTCOME_ACCEPTED);
            ImmutableReceiptProducer.NotASendException refused = assertThrows(
                    ImmutableReceiptProducer.NotASendException.class,
                    () -> producer.sendOriginal(request, 7L, 12L, Integer.valueOf(1), "hello", null, 88120L,
                            java.util.List.of(), java.util.List.of(), false));
            assertEquals(kind, refused.getKind());
        }
        ImmutableReceiptProducer.OriginalRequest reply = new ImmutableReceiptProducer.OriginalRequest(
                "c09-send-key-0001", ImmutableReceiptProducer.Kind.REPLY, "{\"fp\":1}",
                ImmutableReceiptProducer.OUTCOME_ACCEPTED);
        ImmutableReceiptProducer.WaitingSourceException fingerprint = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1), "{\"fp\":1}", null, 88120L,
                        java.util.List.of(), java.util.List.of(), false));
        assertEquals(ImmutableReceiptProducer.IM_SEND_COMMAND, fingerprint.getWaitingCapability());
        ImmutableReceiptProducer.WaitingSourceException missingReply = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1), "hello", null, null,
                        java.util.List.of(8L), java.util.List.of(), false));
        assertEquals(ImmutableReceiptProducer.IM_SEND_WIRE, missingReply.getWaitingCapability());
        ImmutableReceiptProducer.WaitingSourceException missingWriter = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1), "hello", null, 88120L,
                        java.util.List.of(), java.util.List.of(), false));
        assertEquals(ImmutableReceiptProducer.CHANNEL_MESSAGE_SEND, missingWriter.getWaitingCapability());
        assertEquals(0, store.size());
    }

    @Test
    void sendOriginalForwardsTheReplyToTheChannelWriterAndDoesNotInsert() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        java.util.List<Object[]> calls = new java.util.ArrayList<Object[]>();
        ImmutableReceiptProducer.ChannelMessageOriginalSend writer = (actor, conversation, client, type, content, extra,
                reply, members, roles, everyone) -> {
            calls.add(new Object[] { actor, conversation, client, type, content, extra, reply, members, roles,
                    Boolean.valueOf(everyone) });
            java.util.Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
            sent.put("id", Long.valueOf(42L));
            sent.put("request_id", client);
            sent.put("replayed", Boolean.FALSE);
            return sent;
        };
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store, null, null, writer);
        String key = "c09-send-key-0001";
        ImmutableReceiptProducer.OriginalRequest reply = new ImmutableReceiptProducer.OriginalRequest(
                key, ImmutableReceiptProducer.Kind.REPLY, "{\"fp\":1}",
                ImmutableReceiptProducer.OUTCOME_ACCEPTED);
        ImmutableReceiptProducer.OriginalReceipt receipt = producer.sendOriginal(reply, 7L, 12L, Integer.valueOf(1),
                "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false);
        assertEquals(1, calls.size());
        assertEquals(Long.valueOf(7L), calls.get(0)[0]);
        assertEquals(Long.valueOf(12L), calls.get(0)[1]);
        assertEquals(key, calls.get(0)[2]);
        assertEquals("hello", calls.get(0)[4]);
        assertEquals(Long.valueOf(88120L), calls.get(0)[6]);
        assertEquals(key, receipt.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, receipt.outcome);
        assertNull(receipt.payloadHash);
        assertNull(receipt.originalBody);
        assertFalse(receipt.currentProjectionClaimsApplied);
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalRequest shortKey = new ImmutableReceiptProducer.OriginalRequest(
                "c09-send-01", ImmutableReceiptProducer.Kind.REPLY, "{\"fp\":1}",
                ImmutableReceiptProducer.OUTCOME_ACCEPTED);
        ServiceException shortRejected = assertThrows(ServiceException.class, () -> producer.sendOriginal(shortKey, 7L, 12L,
                Integer.valueOf(1), "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false));
        assertEquals(Integer.valueOf(400), shortRejected.getCode());
        assertEquals("INVALID_REQUEST_ID", shortRejected.getDetailMessage());
        assertEquals(1, calls.size());
        ImmutableReceiptProducer.OriginalRequest padded = new ImmutableReceiptProducer.OriginalRequest(
                " c09-send-key-0001 ", ImmutableReceiptProducer.Kind.REPLY, "{\"fp\":1}",
                ImmutableReceiptProducer.OUTCOME_ACCEPTED);
        ServiceException paddedRejected = assertThrows(ServiceException.class, () -> producer.sendOriginal(padded, 7L,
                12L, Integer.valueOf(1), "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false));
        assertEquals("INVALID_REQUEST_ID", paddedRejected.getDetailMessage());
        assertEquals(1, calls.size());
        ImmutableReceiptProducer.ChannelMessageOriginalSend splitIds = (actor, conversation, client, type, content,
                extra, replyTo, members, roles, everyone) -> {
            java.util.Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
            sent.put("message_id", Long.valueOf(42L));
            sent.put("id", Long.valueOf(43L));
            sent.put("request_id", client);
            return sent;
        };
        ImmutableReceiptProducer contradicted = new ImmutableReceiptProducer(store, null, null, splitIds);
        assertThrows(IllegalStateException.class, () -> contradicted.sendOriginal(reply, 7L, 12L, Integer.valueOf(1),
                "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false));
        assertEquals(0, store.size());
        ImmutableReceiptProducer.ChannelMessageOriginalSend illegalAlias = (actor, conversation, client, type,
                content, extra, replyTo, members, roles, everyone) -> {
            java.util.Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
            sent.put("message_id", Boolean.TRUE);
            sent.put("id", Long.valueOf(42L));
            sent.put("request_id", client);
            return sent;
        };
        ImmutableReceiptProducer badAlias = new ImmutableReceiptProducer(store, null, null, illegalAlias);
        assertThrows(IllegalStateException.class, () -> badAlias.sendOriginal(reply, 7L, 12L, Integer.valueOf(1),
                "hello", null, 88120L, java.util.List.of(), java.util.List.of(), false));
        assertEquals(0, store.size());
    }

    @Test
    void recoverChannelReceiptReadsTheSameKeyAndDoesNotInsert() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        String key = "c09-send-key-0001";
        int[] receipts = new int[] { 0 };
        ImmutableReceiptProducer.ChannelMessageOriginalSend writer =
                new ImmutableReceiptProducer.ChannelMessageOriginalSend() {
                    @Override
                    public java.util.Map<String, Object> send(Long actor, Long conversation, String client, Integer type,
                            String content, String extra, Long replyTo, java.util.List<Long> members,
                            java.util.List<Long> roles, boolean everyone) {
                        java.util.Map<String, Object> sent = new java.util.LinkedHashMap<String, Object>();
                        sent.put("id", Long.valueOf(42L));
                        sent.put("request_id", client);
                        return sent;
                    }

                    @Override
                    public java.util.Map<String, Object> receipt(Long actor, String requestId) {
                        receipts[0]++;
                        java.util.Map<String, Object> receipt = new java.util.LinkedHashMap<String, Object>();
                        receipt.put("request_id", requestId);
                        receipt.put("state", "PENDING");
                        receipt.put("outcome_confirmed", Boolean.FALSE);
                        return receipt;
                    }
                };
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store, null, null, writer);
        producer.sendOriginal(new ImmutableReceiptProducer.OriginalRequest(key, ImmutableReceiptProducer.Kind.REPLY,
                "{\"fp\":1}", ImmutableReceiptProducer.OUTCOME_ACCEPTED), 7L, 12L, Integer.valueOf(1), "hello", null,
                88120L, java.util.List.of(), java.util.List.of(), false);
        java.util.Map<String, Object> recovered = producer.recoverChannelReceipt(7L, key);
        assertEquals(1, receipts[0]);
        assertEquals(key, recovered.get("request_id"));
        assertEquals("PENDING", recovered.get("state"));
        assertEquals(Boolean.FALSE, recovered.get("outcome_confirmed"));
        assertNull(recovered.get("content"));
        assertEquals(0, store.size());
        assertThrows(IllegalArgumentException.class, () -> producer.recoverChannelReceipt(7L, "c09-send-01"));
        assertThrows(IllegalArgumentException.class,
                () -> producer.recoverChannelReceipt(7L, " c09-send-key-0001 "));
        assertEquals(1, receipts[0]);
        java.util.Map<String, Object> applied = new java.util.LinkedHashMap<String, Object>();
        applied.put("request_id", key);
        applied.put("state", "APPLIED");
        applied.put("outcome_confirmed", Boolean.TRUE);
        applied.put("operation", "SEND");
        applied.put("conversation_id", Long.valueOf(12L));
        applied.put("result_redacted", Boolean.TRUE);
        ImmutableReceiptProducer.ChannelMessageOriginalSend appliedRead =
                new ImmutableReceiptProducer.ChannelMessageOriginalSend() {
                    @Override
                    public java.util.Map<String, Object> send(Long actor, Long conversation, String client, Integer type,
                            String content, String extra, Long replyTo, java.util.List<Long> members,
                            java.util.List<Long> roles, boolean everyone) {
                        throw new AssertionError("applied read does not send");
                    }

                    @Override
                    public java.util.Map<String, Object> receipt(Long actor, String requestId) {
                        return applied;
                    }
                };
        java.util.Map<String, Object> passed = new ImmutableReceiptProducer(store, null, null, appliedRead)
                .recoverChannelReceipt(7L, key);
        assertEquals("APPLIED", passed.get("state"));
        assertEquals("SEND", passed.get("operation"));
        assertEquals(Long.valueOf(12L), passed.get("conversation_id"));
        assertEquals(Boolean.TRUE, passed.get("result_redacted"));
        assertEquals(Boolean.TRUE, passed.get("outcome_confirmed"));
        assertEquals(0, store.size());
        ImmutableReceiptProducer.WaitingSourceException missing = assertThrows(
                ImmutableReceiptProducer.WaitingSourceException.class,
                () -> new ImmutableReceiptProducer(store).recoverChannelReceipt(7L, key));
        assertEquals(ImmutableReceiptProducer.CHANNEL_MESSAGE_RECEIPT, missing.getWaitingCapability());
    }

    @Test
    void projectOriginalSendReadsStoredIdentityAndRejectsRebuiltBody() {
        String key = "c09-send-key-0001";
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("message_id", 42L);
        result.put("client_message_id", key);
        result.put("content", "edited body");
        java.util.Map<String, Object> applied = new java.util.LinkedHashMap<String, Object>();
        applied.put("channelId", 12L);
        applied.put("operation", "SEND");
        applied.put("digest", digest);
        applied.put("state", "APPLIED");
        applied.put("content", "edited body");
        applied.put("result", result);
        ImmutableReceiptProducer.OriginalSendProof proof = ImmutableReceiptProducer.projectOriginalSend(
                7L, 12L, key, applied, false);
        assertEquals("APPLIED", proof.state);
        assertTrue(proof.outcomeConfirmed);
        assertEquals(digest, proof.digest);
        assertEquals("42", proof.asView().get("message_id"));
        assertEquals("12", proof.asView().get("conversation_id"));
        assertEquals("7", proof.asView().get("actor_member_id"));
        assertFalse(proof.asView().containsKey("content"));

        java.util.Map<String, Object> missingDigest = new java.util.LinkedHashMap<String, Object>(applied);
        missingDigest.remove("digest");
        assertFalse(ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, missingDigest, false).outcomeConfirmed);
        java.util.Map<String, Object> shortDigest = new java.util.LinkedHashMap<String, Object>(applied);
        shortDigest.put("digest", "abc123digest");
        assertFalse(ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, shortDigest, false).outcomeConfirmed);
        java.util.Map<String, Object> decimal = new java.util.LinkedHashMap<String, Object>(result);
        decimal.put("message_id", Double.valueOf(42.5d));
        java.util.Map<String, Object> decimalRow = new java.util.LinkedHashMap<String, Object>(applied);
        decimalRow.put("result", decimal);
        assertFalse(ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, decimalRow, false).outcomeConfirmed);
        java.util.Map<String, Object> nested = new java.util.LinkedHashMap<String, Object>();
        nested.put("message_id", 42L);
        java.util.Map<String, Object> nestedRow = new java.util.LinkedHashMap<String, Object>(applied);
        nestedRow.put("result", java.util.Map.of("message_id", nested, "client_message_id", key));
        assertFalse(ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, nestedRow, false).outcomeConfirmed);

        java.util.Map<String, Object> pending = new java.util.LinkedHashMap<String, Object>(applied);
        pending.put("state", "PENDING");
        pending.remove("result");
        ImmutableReceiptProducer.OriginalSendProof waiting = ImmutableReceiptProducer.projectOriginalSend(
                7L, 12L, key, pending, false);
        assertEquals("PENDING", waiting.state);
        assertFalse(waiting.outcomeConfirmed);
        assertNull(waiting.messageId);

        assertEquals("NOT_FOUND", ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, null, false).state);
        assertEquals("LEGACY", ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, null, true).state);
        assertFalse(ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, null, true).outcomeConfirmed);
        assertEquals("NOT_FOUND", ImmutableReceiptProducer.projectOriginalSend(7L, 12L, "short", applied, false).state);
        java.util.Map<String, Object> otherActor = new java.util.LinkedHashMap<String, Object>(applied);
        otherActor.put("actorMemberId", 7L);
        assertEquals("ACTOR_MISMATCH", ImmutableReceiptProducer.projectOriginalSend(8L, 12L, key, otherActor, false).state);
        assertEquals("CHANNEL_MISMATCH", ImmutableReceiptProducer.projectOriginalSend(7L, 13L, key, applied, false).state);

        java.util.Map<String, Object> edited = new java.util.LinkedHashMap<String, Object>(applied);
        edited.put("operation", "EDIT");
        assertEquals("WRONG_OPERATION", ImmutableReceiptProducer.projectOriginalSend(7L, 12L, key, edited, false).state);
        java.util.Map<String, Object> recalled = new java.util.LinkedHashMap<String, Object>(applied);
        recalled.put("operation", "RECALL");
        ImmutableReceiptProducer.OriginalSendProof recall = ImmutableReceiptProducer.projectOriginalSend(
                7L, 12L, key, recalled, false);
        assertEquals("WRONG_OPERATION", recall.state);
        assertNull(recall.messageId);
        assertFalse(recall.outcomeConfirmed);
    }

    @Test
    void recoverFromJournalUsesOriginalReadSentMessageAndDoesNotInsert() {
        ImConversationMapper conversations = mock(ImConversationMapper.class);
        ImConversationMemberMapper members = mock(ImConversationMemberMapper.class);
        ImMessageMapper messages = mock(ImMessageMapper.class);
        ImServiceImpl im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "conversationMapper", conversations);
        ReflectionTestUtils.setField(im, "memberMapper", members);
        ReflectionTestUtils.setField(im, "messageMapper", messages);
        ImConversation conversation = new ImConversation();
        conversation.setId(100L);
        conversation.setType(ImConversation.TYPE_SINGLE);
        conversation.setStatus(0);
        when(conversations.selectImConversationByIdForUpdate(100L)).thenReturn(conversation);
        ImConversationMember member = new ImConversationMember();
        member.setConversationId(100L);
        member.setMemberId(1L);
        member.setDeleted(0);
        when(members.selectByConvAndMemberForUpdate(100L, 1L)).thenReturn(member);
        ImMessage sent = new ImMessage();
        sent.setConversationId(100L);
        sent.setSenderId(1L);
        sent.setClientMessageId("original-req");
        sent.setRequestHash("abc123digest");
        sent.setContent("hello");
        sent.setMsgType(1);
        when(messages.selectByClientMessageIdForUpdate(100L, 1L, "original-req")).thenReturn(sent);

        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer waiting = new ImmutableReceiptProducer(store,
                new ImmutableReceiptProducer.ImServiceOriginalReceiptJournal(null), null);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> waiting.recoverFromJournal(1L, 100L, "original-req"));
        assertTrue(missing.getMessage().contains(ImmutableReceiptProducer.IM_READ_SENT));
        verify(messages, never()).selectByClientMessageIdForUpdate(eq(100L), eq(1L), eq("original-req"));

        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store,
                new ImmutableReceiptProducer.ImServiceOriginalReceiptJournal(im), null);
        ImmutableReceiptProducer.OriginalReceipt found = producer.recoverFromJournal(1L, 100L, "original-req");
        assertEquals("original-req", found.requestId);
        assertEquals("hello", found.originalBody);
        assertEquals("abc123digest", found.payloadHash);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, found.outcome);
        assertNull(store.find("original-req"));
        verify(messages).selectByClientMessageIdForUpdate(100L, 1L, "original-req");
        verify(messages, never()).insertImMessage(org.mockito.ArgumentMatchers.any());

        assertThrows(ServiceException.class, () -> producer.recoverFromJournal(2L, 100L, "original-req"));
        verify(messages, never()).selectByClientMessageIdForUpdate(eq(100L), eq(2L), eq("original-req"));
    }
}
