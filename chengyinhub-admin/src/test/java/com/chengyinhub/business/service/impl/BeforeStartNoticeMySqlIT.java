package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper;
import com.chengyinhub.business.service.IWxSubscribeMsgService.PreparedBeforeStart;
import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Actual canonical migrations, Mapper, independent transaction commits and owner. WX is a provider
 * boundary double; neither provider delivery nor complete TeamGroup ticket/payment authorization is claimed. */
class BeforeStartNoticeMySqlIT {
    private static final String TITLE="原票次";
    private static Date soon() { return new Date(System.currentTimeMillis()+3600000); }
    private void prepare(CommittedOfficialNoticeMySqlFixture f) {
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Long recipient=call.getArgument(0);
            return new PreparedBeforeStart(recipient,"original-openid-"+recipient,"before-start-template","pages/play/index",
                    Map.of("thing1",call.<String>getArgument(1),"date5",call.<String>getArgument(2),
                            "thing8","详见活动详情","thing7","请准时到场","thing12","原昵称"));
        }).when(f.beforeStartWx).prepareBeforeStart(anyLong(),anyString(),anyString());
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("UNKNOWN",f.db.queryForObject("select wx_state from committed_official_notice_wx",String.class),
                    "the external boundary sees the already committed original attempt");
            return true;
        }).when(f.beforeStartWx).sendPreparedBeforeStart(any());
    }
    private Long id(CommittedOfficialNoticeMySqlFixture f) {
        return f.db.queryForObject("select id from committed_official_notice_wx where event_key='before-start:7:100'",Long.class);
    }
    private void noIm(CommittedOfficialNoticeMySqlFixture f) {
        assertEquals(0,f.count("im_notification_intent"));assertEquals(0,f.count("im_message"));verifyNoInteractions(f.wx);
    }
    private CommittedOfficialNoticeService interruptBeforeClaim(CommittedOfficialNoticeMySqlFixture f) {
        CommittedOfficialNoticeWxMapper interrupted=mock(CommittedOfficialNoticeWxMapper.class,delegatesTo(f.mapper));
        doThrow(new IllegalStateException("worker stopped after original intent commit"))
                .when(interrupted).selectByIdForUpdate(anyLong());
        return f.freshOwner(interrupted);
    }

    @Test void originalIntentAndUnknownAreCommittedBeforeWxAndNoImIsCreated() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            var result=f.notice.beforeStart(7L,100L,TITLE,start);
            assertTrue(result.deliveredNow());assertEquals("ACCEPTED",result.state());
            assertEquals(id(f),result.intentId());assertEquals(1,f.count("committed_official_notice_wx"));
            assertNull(f.db.queryForObject("select im_intent_id from committed_official_notice_wx",Long.class));
            assertEquals("BEFORE_START",f.db.queryForObject("select notice_type from committed_official_notice_wx",String.class));
            noIm(f);verify(f.beforeStartWx).sendPreparedBeforeStart(any());
        }
    }
    @Test void committedPendingSurvivesWorkerExitAndFreshEligibleWakeUsesFirstFrozenInputs() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            assertThrows(IllegalStateException.class,() -> interruptBeforeClaim(f).beforeStart(7L,100L,TITLE,start));
            Long id=id(f);assertEquals("PENDING",f.state(id));
            String originalPayload=f.db.queryForObject("select wx_payload_json from committed_official_notice_wx",String.class);
            verify(f.beforeStartWx,never()).sendPreparedBeforeStart(any());noIm(f);
            // Generic official recovery has no current paid-ticket admission and must not send this template.
            f.recover();f.notice.deliver(id);assertEquals("PENDING",f.state(id));
            doThrow(new AssertionError("existing intent must not re-read template/profile/config"))
                    .when(f.beforeStartWx).prepareBeforeStart(anyLong(),anyString(),anyString());
            var recovered=f.freshOwner(f.mapper).beforeStart(7L,100L,TITLE,start);
            assertTrue(recovered.deliveredNow());assertEquals(id,recovered.intentId());
            assertEquals(originalPayload,f.db.queryForObject("select wx_payload_json from committed_official_notice_wx",String.class));
            noIm(f);verify(f.beforeStartWx).sendPreparedBeforeStart(any());
        }
    }
    @Test void sequentialRetryReadbackAndFreshOwnerNeverSendOrCountAcceptedAgain() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();var first=f.notice.beforeStart(7L,100L,TITLE,start);
            var replay=f.freshOwner(f.mapper).beforeStart(7L,100L,TITLE,start);
            assertEquals(first.intentId(),replay.intentId());assertFalse(replay.deliveredNow());assertTrue(replay.alreadyDelivered());
            assertEquals(replay,f.notice.readBeforeStart(7L,100L));
            f.recover();assertEquals(1,f.count("committed_official_notice_wx"));noIm(f);
            verify(f.beforeStartWx).prepareBeforeStart(anyLong(),anyString(),anyString());
            verify(f.beforeStartWx).sendPreparedBeforeStart(any());
        }
    }
    @Test void providerFalseIsUnknownAndNeitherRetryNorReadbackRearmsIt() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            doReturn(false).when(f.beforeStartWx).sendPreparedBeforeStart(any());
            var first=f.notice.beforeStart(7L,100L,TITLE,start);assertEquals("UNKNOWN",first.state());assertFalse(first.deliveredNow());
            Map<String,Object> original=f.db.queryForMap("select * from committed_official_notice_wx");
            doReturn(true).when(f.beforeStartWx).sendPreparedBeforeStart(any());
            assertEquals("UNKNOWN",f.freshOwner(f.mapper).beforeStart(7L,100L,TITLE,start).state());
            assertEquals("UNKNOWN",f.notice.readBeforeStart(7L,100L).state());f.recover();
            assertEquals(original,f.db.queryForMap("select * from committed_official_notice_wx"));
            verify(f.beforeStartWx).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void thrownResponseLossIsUnknownAndRetainsOriginalAttemptWithoutAnotherSend() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            doThrow(new NoClassDefFoundError("provider response lost")).when(f.beforeStartWx).sendPreparedBeforeStart(any());
            assertEquals("UNKNOWN",f.notice.beforeStart(7L,100L,TITLE,start).state());
            f.freshOwner(f.mapper).beforeStart(7L,100L,TITLE,start);f.notice.readBeforeStart(7L,100L);f.recover();
            verify(f.beforeStartWx).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void acceptedAckRollbackLeavesUnknownAndLateReceiptRequiresTheSameOwner() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            CommittedOfficialNoticeWxMapper failing=mock(CommittedOfficialNoticeWxMapper.class,delegatesTo(f.mapper));
            // Delegate a real update, then fail inside that same actual transaction so it must roll back.
            doAnswer(call -> { int updated=f.mapper.accepted(call.getArgument(0),call.getArgument(1));
                assertEquals(1,updated);throw new IllegalStateException("local terminal ack lost"); })
                    .when(failing).accepted(anyLong(),anyString());
            var result=f.freshOwner(failing).beforeStart(7L,100L,TITLE,start);
            assertEquals("UNKNOWN",result.state());assertEquals("UNKNOWN",f.state(id(f)));
            String owner=f.db.queryForObject("select owner_token from committed_official_notice_wx",String.class);
            String payload=f.db.queryForObject("select wx_payload_json from committed_official_notice_wx",String.class);
            assertEquals("UNKNOWN",f.notice.beforeStart(7L,100L,TITLE,start).state());
            f.tx.executeWithoutResult(tx -> {
                assertEquals(0,f.mapper.accepted(id(f),"wrong-owner"));
                assertEquals(1,f.mapper.accepted(id(f),owner));
            });
            assertTrue(f.notice.readBeforeStart(7L,100L).alreadyDelivered());
            assertFalse(f.notice.beforeStart(7L,100L,TITLE,start).deliveredNow());
            assertEquals(payload,f.db.queryForObject("select wx_payload_json from committed_official_notice_wx",String.class));
            verify(f.beforeStartWx).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void twoConcurrentEligibleWakesHaveOneCommittedClaimOneSendAndOneNewAcceptance() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();AtomicInteger calls=new AtomicInteger();
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("UNKNOWN",f.state(id(f)));calls.incrementAndGet();return true;
            }).when(f.beforeStartWx).sendPreparedBeforeStart(any());
            ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
            try {
                Callable<com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery> run=() -> {
                    assertTrue(go.await(10,TimeUnit.SECONDS));return f.freshOwner(f.mapper).beforeStart(7L,100L,TITLE,start);
                };
                var one=workers.submit(run);var two=workers.submit(run);go.countDown();
                var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);
                assertEquals(1,(a.deliveredNow()?1:0)+(b.deliveredNow()?1:0));
                assertEquals(a.intentId(),b.intentId());assertEquals(1,calls.get());
                assertEquals(1,f.count("committed_official_notice_wx"));assertEquals("ACCEPTED",f.state(id(f)));noIm(f);
            } finally { go.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS)); }
        }
    }
    @Test void changedOriginalTitleOrPlannedTimeConflictsWithoutChangingAnyCommittedFact() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();f.notice.beforeStart(7L,100L,TITLE,start);
            Map<String,Object> original=f.db.queryForMap("select * from committed_official_notice_wx");
            ServiceException title=assertThrows(ServiceException.class,() -> f.notice.beforeStart(7L,100L,"changed",start));
            ServiceException time=assertThrows(ServiceException.class,() -> f.notice.beforeStart(7L,100L,TITLE,new Date(start.getTime()+60000)));
            assertEquals(409,title.getCode());assertEquals(409,time.getCode());
            assertEquals(original,f.db.queryForMap("select * from committed_official_notice_wx"));
            verify(f.beforeStartWx).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void recipientAndTicketAreIndependentPermanentIdentities() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            doReturn(true).when(f.beforeStartWx).sendPreparedBeforeStart(any());
            assertTrue(f.notice.beforeStart(7L,100L,TITLE,start).deliveredNow());
            assertTrue(f.notice.beforeStart(7L,101L,TITLE,start).deliveredNow());
            assertTrue(f.notice.beforeStart(8L,100L,TITLE,start).deliveredNow());
            assertFalse(f.notice.beforeStart(7L,100L,TITLE,start).deliveredNow());
            assertEquals(3,f.count("committed_official_notice_wx"));
            assertEquals(3,f.db.queryForObject("select count(distinct event_key) from committed_official_notice_wx",Integer.class));
            verify(f.beforeStartWx,times(3)).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void notPreparedCanRecoverButOutsideWindowAndExpiredPendingNeverReachProvider() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            doReturn(null).when(f.beforeStartWx).prepareBeforeStart(anyLong(),anyString(),anyString());
            assertEquals("NOT_READY",f.notice.beforeStart(7L,100L,TITLE,start).state());assertEquals(0,f.count("committed_official_notice_wx"));
            assertEquals("OUTSIDE_WINDOW",f.notice.beforeStart(7L,100L,TITLE,new Date(System.currentTimeMillis()+25L*3600000)).state());
            prepare(f);
            assertThrows(IllegalStateException.class,() -> interruptBeforeClaim(f).beforeStart(7L,100L,TITLE,start));
            assertEquals("PENDING",f.state(id(f)));
            f.beforeStartClock=Clock.fixed(Instant.ofEpochMilli(start.getTime()+1),ZoneOffset.UTC);
            assertEquals("PENDING",f.freshOwner(f.mapper).beforeStart(7L,100L,TITLE,start).state());
            f.recover();verify(f.beforeStartWx,never()).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void tamperedPayloadAndDatabaseReadFailureNeverClaimOrSendAnotherAttempt() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            assertThrows(IllegalStateException.class,() -> interruptBeforeClaim(f).beforeStart(7L,100L,TITLE,start));
            CommittedOfficialNoticeWxMapper broken=mock(CommittedOfficialNoticeWxMapper.class,delegatesTo(f.mapper));
            doThrow(new IllegalStateException("read failed")).when(broken).selectByKeyForUpdate(anyString());
            assertThrows(IllegalStateException.class,() -> f.freshOwner(broken).readBeforeStart(7L,100L));
            f.db.update("update committed_official_notice_wx set wx_payload_json=json_set(wx_payload_json,'$.message.openid','different')");
            assertThrows(IllegalStateException.class,() -> f.notice.beforeStart(7L,100L,TITLE,start));
            assertThrows(IllegalStateException.class,() -> f.notice.readBeforeStart(7L,100L));
            assertEquals("PENDING",f.state(id(f)));verify(f.beforeStartWx,never()).sendPreparedBeforeStart(any());noIm(f);
        }
    }
    @Test void outerTransactionAndMissingReadbackNeverPrepareOrClaim() throws Exception {
        try(var f=new CommittedOfficialNoticeMySqlFixture()) {
            f.open();prepare(f);Date start=soon();
            assertThrows(RuntimeException.class,() -> f.tx.executeWithoutResult(tx -> f.notice.beforeStart(7L,100L,TITLE,start)));
            assertEquals("NOT_FOUND",f.notice.readBeforeStart(7L,100L).state());
            assertEquals(0,f.count("committed_official_notice_wx"));verifyNoInteractions(f.beforeStartWx);noIm(f);
        }
    }
}
