package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.task.ImNotificationDeliveryTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No EnabledIf/Assumptions: missing controlled-storage configuration fails, not skips.
 * Business CAS is a JDBC fact adapter; actual financial mappers are not claimed by these tests. */
class CommittedOfficialNoticeMySqlIT {
    @Test void originalBusinessRollbackLeavesNeitherIntentNorWakeNorChannelEffect() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open();
            assertThrows(IllegalStateException.class, () -> f.tx.executeWithoutResult(status -> {
                f.winner(); throw new IllegalStateException("original business failure");
            }));
            assertEquals(0, f.db.queryForObject("select status from complaint_notice_fact where id=1", Integer.class));
            assertEquals(0, f.count("im_notification_intent"));
            assertEquals(0, f.count("committed_official_notice_wx"));
            assertEquals(0, f.count("im_message")); assertTrue(f.wakes.isEmpty());
            verifyNoInteractions(f.wx);
        }
    }

    @Test void rejectedWakeAndFreshTaskReconstructionRecoverTheOriginalCommittedFact() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open();
            doAnswer(call -> {
                assertEquals(1, new JdbcTemplate(f.manager.getDataSource()).queryForObject(
                        "select status from complaint_notice_fact where id=1", Integer.class));
                throw new RejectedExecutionException("fixture executor rejected after commit");
            }).when(f.executor).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS));
            Long id = f.winner(); assertEquals("PENDING", f.state(id));
            assertEquals(0, f.count("im_message")); verifyNoInteractions(f.wx);
            f.recover(); f.oneMessage(); assertEquals("ACCEPTED", f.state(id));
            verify(f.wx).send(any());
        }
    }

    @Test void repeatedBusinessWinnerCannotRegisterOrSendAnotherNotice() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner();
            assertThrows(IllegalStateException.class, f::winner);
            assertEquals(1, f.count("im_notification_intent"));
            assertEquals(1, f.count("committed_official_notice_wx")); assertEquals(1, f.wakes.size());
            assertEquals(0, f.count("im_message")); verifyNoInteractions(f.wx);
            f.recover(); f.oneMessage(); assertEquals("ACCEPTED", f.state(id));
        }
    }

    @Test void sameIdentitySamePayloadReturnsTheOriginalIntentAndOneMessage() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.enqueue("complaint-advance:1", 100L, "title", "body");
            assertEquals(id, f.enqueue("complaint-advance:1", 100L, "title", "body"));
            assertEquals(1, f.count("committed_official_notice_wx"));
            assertEquals(1, f.count("im_notification_intent"));
            f.notice.deliver(id); f.notice.deliver(id); f.oneMessage(); verify(f.wx).send(any());
        }
    }

    @Test void ambiguousImTextStillRejectsDifferentFrozenTitleAndBodyAndRollsBackBusiness() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.enqueue("complaint-advance:1", 100L, "a\nb", "c");
            assertThrows(RuntimeException.class, () -> f.tx.executeWithoutResult(status -> {
                f.db.update("update complaint_notice_fact set status=1 where id=1");
                // Same flattened IM text, different semantic title/body: companion fingerprint must reject.
                f.notice.enqueueOfficial("complaint-advance:1", 100L, "a", "b\nc");
            }));
            assertEquals(0, f.db.queryForObject("select status from complaint_notice_fact where id=1", Integer.class));
            assertEquals("a\nb", f.db.queryForObject("select title from committed_official_notice_wx where id=?", String.class, id));
            assertThrows(RuntimeException.class, () -> f.enqueue("complaint-advance:1", 999L, "a\nb", "c"));
            assertEquals(1, f.count("committed_official_notice_wx")); assertEquals(1, f.count("im_notification_intent"));
            assertEquals(0, f.count("im_message")); verifyNoInteractions(f.wx);
        }
    }

    @Test void frozenRecipientAndPayloadDoNotFollowLaterBusinessEdits() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner();
            f.db.update("update complaint_notice_fact set recipient=999,title='new title',body='new body' where id=1");
            f.recover(); f.oneMessage();
            assertEquals("投诉处理有进展\n工单#1已先行垫付 ¥50，请在投诉记录查看处理进度。",
                    f.db.queryForObject("select content from im_message", String.class));
            assertEquals(100L, f.db.queryForObject("select receiver_id from im_message", Long.class));
            ArgumentCaptor<NotifyMessage> sent = ArgumentCaptor.forClass(NotifyMessage.class);
            verify(f.wx).send(sent.capture()); assertEquals(100L, sent.getValue().memberId());
            assertEquals("投诉处理有进展", sent.getValue().get("title"));
            assertEquals("工单#1已先行垫付 ¥50，请在投诉记录查看处理进度。", sent.getValue().get("sub"));
            assertEquals("ACCEPTED", f.state(id));
        }
    }

    @Test void falseOrResponseLostWxStaysUnknownAndNeverAutomaticallyResends() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner();
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("UNKNOWN", f.state(id), "attempt identity is committed before crossing WX");
                return false; // Real channel also collapses missing-template and swallowed response loss to false.
            }).when(f.wx).send(any());
            f.notice.deliver(id); f.oneMessage(); assertEquals("UNKNOWN", f.state(id));
            f.recover(); f.freshOwner(f.mapper).deliver(id); f.oneMessage();
            verify(f.wx).send(any()); assertEquals("UNKNOWN", f.state(id));
        }
    }

    @Test void successfulExternalActionWithLostReceiptRetainsOriginalUnknownOwner() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner(); CommittedOfficialNoticeWxMapper failing = spy(f.mapper);
            doThrow(new IllegalStateException("fixture ACK persistence lost")).when(failing).accepted(eq(id), anyString());
            f.freshOwner(failing).deliver(id); assertEquals("UNKNOWN", f.state(id)); f.oneMessage();
            String owner = f.db.queryForObject("select owner_token from committed_official_notice_wx where id=?", String.class, id);
            f.recover(); f.freshOwner(f.mapper).deliver(id);
            assertEquals(owner, f.db.queryForObject("select owner_token from committed_official_notice_wx where id=?", String.class, id));
            verify(f.wx).send(any()); assertEquals("UNKNOWN", f.state(id)); f.oneMessage();
        }
    }

    @Test void externalResponseExceptionNeverRearmsTheOriginalAttempt() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner();
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("UNKNOWN", f.state(id));
                throw new IllegalStateException("fixture provider accepted but response was lost");
            }).when(f.wx).send(any());
            f.notice.deliver(id); f.oneMessage(); assertEquals("UNKNOWN", f.state(id));
            f.recover(); f.notice.deliver(id); verify(f.wx).send(any());
            assertEquals("UNKNOWN", f.state(id));
        }
    }

    @Test void failedLocalTransactionCanRecoverWithoutRepeatingAcceptedWx() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner();
            doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("fixture IM before commit failure"); })
                    .when(f.imTarget).deliverSystemNotification(anyLong());
            f.notice.deliver(id); assertEquals("ACCEPTED", f.state(id));
            assertEquals(0, f.count("im_message"));
            assertEquals("PENDING", f.db.queryForObject("select status from im_notification_intent", String.class));
            doCallRealMethod().when(f.imTarget).deliverSystemNotification(anyLong());
            // Existing local-only recovery source is sufficient, even when the WX row is terminal.
            ImNotificationDeliveryTask localRecovery = new ImNotificationDeliveryTask();
            ReflectionTestUtils.setField(localRecovery, "mapper", f.imIntents);
            ReflectionTestUtils.setField(localRecovery, "imService", f.im);
            localRecovery.deliverPending();
            f.oneMessage(); f.notice.deliver(id); verify(f.wx).send(any());
        }
    }

    @Test void twoWorkersAndLostLocalAckStillProduceOneMessageAndOneExternalAttempt() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner(); ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                attempts.incrementAndGet(); return true; }).when(f.wx).send(any());
            try {
                Callable<Void> run = () -> { assertTrue(start.await(10, TimeUnit.SECONDS));
                    f.freshOwner(f.mapper).deliver(id); return null; };
                Future<Void> one = workers.submit(run), two = workers.submit(run); start.countDown();
                one.get(20, TimeUnit.SECONDS); two.get(20, TimeUnit.SECONDS);
                // Discard task ACK and reconstruct its owner, as after caller response loss.
                f.freshOwner(f.mapper).deliver(id); f.oneMessage();
                assertEquals(1, attempts.get()); assertEquals("ACCEPTED", f.state(id));
            } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void wrongOwnerCannotFinalizeAndUnknownCannotBeClaimedAgain() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open(); Long id = f.winner();
            f.tx.executeWithoutResult(status -> assertEquals(1, f.mapper.claimUnknown(id, "11111111-1111-1111-1111-111111111111")));
            f.tx.executeWithoutResult(status -> {
                assertEquals(0, f.mapper.accepted(id, "22222222-2222-2222-2222-222222222222"));
                assertEquals(0, f.mapper.claimUnknown(id, "33333333-3333-3333-3333-333333333333"));
            });
            f.recover(); assertEquals("UNKNOWN", f.state(id)); verifyNoInteractions(f.wx);
        }
    }

    @Test void originalTransactionIsMandatoryAndRecoveryRegistrationPreservesExistingPause() throws Exception {
        try (var f = new CommittedOfficialNoticeMySqlFixture()) {
            f.open();
            assertThrows(RuntimeException.class, () -> f.notice.enqueueOfficial("complaint-advance:1", 100L, "title", "body"));
            assertEquals(0, f.count("im_notification_intent")); assertEquals(0, f.count("committed_official_notice_wx"));
            assertTrue(f.wakes.isEmpty()); verifyNoInteractions(f.wx);
            assertThrows(RuntimeException.class, () -> f.enqueue("complaint-advance:1", 100L, "t".repeat(201), "body"));
            assertEquals(0, f.count("im_notification_intent")); assertEquals(0, f.count("committed_official_notice_wx"));
            assertTrue(f.wakes.isEmpty()); verifyNoInteractions(f.wx);
            String target = "committedOfficialNoticeTask.deliverPending()";
            assertEquals("1", f.db.queryForObject("select status from sys_job where invoke_target=?", String.class, target));
            String migration = Files.readString(f.sqlRoot.resolve("migration_20261024_d_committed_official_notice_wx.sql"));
            String jobRegistration = migration.substring(migration.indexOf("INSERT INTO sys_job"));
            f.db.execute(jobRegistration);
            assertEquals("1", f.db.queryForObject("select status from sys_job where invoke_target=?", String.class, target));
            f.db.update("update sys_job set status='0' where invoke_target=?", target);
            f.db.execute(jobRegistration);
            assertEquals(1, f.db.queryForObject("select count(*) from sys_job where invoke_target=?", Integer.class, target));
            assertEquals("0", f.db.queryForObject("select status from sys_job where invoke_target=?", String.class, target));
        }
    }
}
