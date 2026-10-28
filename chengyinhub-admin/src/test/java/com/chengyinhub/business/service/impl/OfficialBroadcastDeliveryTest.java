package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialBroadcastLog;
import com.chengyinhub.business.mapper.OfficialAudienceMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastLogMapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit-only state-machine evidence. Does NOT establish MySQL locking/rollback or remote delivery. */
class OfficialBroadcastDeliveryTest {
    static class Fixture {
        final OfficialBroadcast broadcast;
        final OfficialAudienceMapper audience = mock(OfficialAudienceMapper.class);
        final OfficialBroadcastLogMapper logs = mock(OfficialBroadcastLogMapper.class);
        final OfficialBroadcastMapper broadcasts = mock(OfficialBroadcastMapper.class);
        final UmsMemberMapper members = mock(UmsMemberMapper.class);
        final IImService im = mock(IImService.class);
        final IWxSubscribeMsgService wx = mock(IWxSubscribeMsgService.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final Map<Long, OfficialBroadcastLog> rows = new LinkedHashMap<>();
        OfficialBroadcastServiceImpl service;

        Fixture() { this(broadcast("inapp,subscribe")); }

        Fixture(OfficialBroadcast bc) {
            broadcast = bc;
            broadcast.setStatus(OfficialBroadcast.ST_PENDING);
            when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            when(logs.lockBroadcast(bc.getId())).thenReturn(bc);
            when(broadcasts.selectOfficialBroadcastById(bc.getId())).thenReturn(bc);
            when(broadcasts.insertOfficialBroadcast(any())).thenReturn(1);
            when(logs.selectRecoverableBroadcasts()).thenReturn(Collections.singletonList(bc));
            when(logs.selectByBroadcast(bc.getId())).thenAnswer(call -> new ArrayList<>(rows.values()));
            when(logs.insertPending(any())).thenAnswer(call -> {
                OfficialBroadcastLog row = call.getArgument(0);
                assertFalse(rows.values().stream().anyMatch(old -> old.getMemberId().equals(row.getMemberId())
                        && old.getChannel().equals(row.getChannel())), "business identity must not change");
                row.setId((long) rows.size() + 1);
                row.setReached(0);
                rows.put(row.getId(), row);
                return 1;
            });
            when(logs.lockDeliveryIntent(anyLong())).thenAnswer(call -> rows.get(call.<Long>getArgument(0)));
            when(logs.transition(anyLong(), anyString(), anyString(), nullable(String.class), nullable(Long.class)))
                    .thenAnswer(call -> {
                        OfficialBroadcastLog row = rows.get(call.<Long>getArgument(0));
                        if (!row.getDeliveryState().equals(call.<String>getArgument(1))) return 0;
                        row.setDeliveryState(call.getArgument(2));
                        row.setFailureCode(call.getArgument(3));
                        row.setMessageId(call.getArgument(4));
                        row.setReached(OfficialBroadcastLog.SUCCESS.equals(row.getDeliveryState()) ? 1 : 0);
                        return 1;
                    });
            when(logs.countInappReached(bc.getId())).thenAnswer(call -> (int) rows.values().stream()
                    .filter(row -> "inapp".equals(row.getChannel()) && success(row)).count());
            when(logs.countReached(bc.getId())).thenAnswer(call -> (int) rows.values().stream()
                    .filter(Fixture::success).count());
            when(logs.countUnsuccessful(bc.getId())).thenAnswer(call -> (int) rows.values().stream()
                    .filter(row -> !success(row)).count());
            when(broadcasts.updateOfficialBroadcast(any())).thenAnswer(call -> {
                OfficialBroadcast patch = call.getArgument(0);
                bc.setStatus(patch.getStatus());
                bc.setReachedCount(patch.getReachedCount());
                return 1;
            });
            when(audience.selectPlayerIds(any())).thenReturn(Collections.singletonList(10L));
            when(members.lockMemberById(anyLong())).thenAnswer(call -> call.getArgument(0));
            when(im.sendSystem(anyLong(), anyInt(), anyString(), anyString())).thenReturn(501L);
            when(wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString())).thenReturn(true);
            restart();
        }

        static boolean success(OfficialBroadcastLog row) {
            return OfficialBroadcastLog.SUCCESS.equals(row.getDeliveryState());
        }

        OfficialBroadcastServiceImpl restart() {
            service = new OfficialBroadcastServiceImpl();
            ReflectionTestUtils.setField(service, "broadcastMapper", broadcasts);
            ReflectionTestUtils.setField(service, "logMapper", logs);
            ReflectionTestUtils.setField(service, "audienceMapper", audience);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "imService", im);
            ReflectionTestUtils.setField(service, "wxSubscribeMsgService", wx);
            ReflectionTestUtils.setField(service, "transactionManager", transactions);
            ReflectionTestUtils.setField(service, "officialTemplate", "configured");
            return service;
        }

        OfficialBroadcastLog row(String channel) {
            return rows.values().stream().filter(row -> channel.equals(row.getChannel())).findFirst().orElseThrow();
        }
    }

    static OfficialBroadcast broadcast(String channels) {
        OfficialBroadcast bc = new OfficialBroadcast();
        bc.setId(1L);
        bc.setTitle("官方通知");
        bc.setAudience("player");
        bc.setChannels(channels);
        return bc;
    }

    @Test
    void stalePrecheckCannotSendSixthInappAndKeepsOriginalPendingIdentity() {
        Fixture f = new Fixture(broadcast("inapp"));
        // Another broadcast commits the fifth receipt after this dispatch's advisory precheck.
        when(f.logs.countRecentByMember(10L, 60)).thenReturn(4, 5);
        assertEquals(0, f.service.dispatch(f.broadcast));
        Long originalId = f.row("inapp").getId();
        assertEquals(OfficialBroadcastLog.PENDING, f.row("inapp").getDeliveryState());
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
        verifyNoInteractions(f.im, f.wx);
        when(f.logs.countRecentByMember(10L, 60)).thenReturn(0);
        assertEquals(1, f.restart().dispatch(f.broadcast));
        assertEquals(originalId, f.row("inapp").getId());
        verify(f.im, times(1)).sendSystem(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void memberLockPrecedesDeliveryLockFrequencyReadMessageAndSuccess() {
        Fixture f = new Fixture(broadcast("inapp"));
        when(f.logs.countRecentByMember(10L, 60)).thenReturn(4);
        assertEquals(1, f.service.dispatch(f.broadcast));
        org.mockito.InOrder ordered = inOrder(f.members, f.logs, f.im);
        ordered.verify(f.members).lockMemberById(10L);
        ordered.verify(f.logs).lockDeliveryIntent(f.row("inapp").getId());
        ordered.verify(f.logs).countRecentByMember(10L, 60);
        ordered.verify(f.im).sendSystem(eq(10L), anyInt(), anyString(), anyString());
        ordered.verify(f.logs).transition(f.row("inapp").getId(), OfficialBroadcastLog.PENDING,
                OfficialBroadcastLog.SUCCESS, null, 501L);
    }

    @Test
    void missingMemberCannotAcquireDeliveryOrWriteMessageOrSuccess() {
        Fixture f = new Fixture(broadcast("inapp"));
        when(f.members.lockMemberById(10L)).thenReturn(null);
        assertEquals(0, f.service.dispatch(f.broadcast));
        assertEquals(OfficialBroadcastLog.PENDING, f.row("inapp").getDeliveryState());
        verify(f.logs, never()).lockDeliveryIntent(anyLong());
        verifyNoInteractions(f.im, f.wx);
    }

    @Test
    void missingTemplateIsDefiniteFailureAndCanRecoverWithoutResendingInapp() {
        Fixture f = new Fixture();
        ReflectionTestUtils.setField(f.service, "officialTemplate", "");
        assertEquals(1, f.service.dispatch(f.broadcast));
        assertEquals(OfficialBroadcastLog.FAILED, f.row("subscribe").getDeliveryState());
        assertEquals("TEMPLATE_MISSING", f.row("subscribe").getFailureCode());
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
        verifyNoInteractions(f.wx);
        assertEquals(1, f.restart().dispatch(f.broadcast));
        assertEquals(OfficialBroadcast.ST_SENT, f.broadcast.getStatus());
        verify(f.im, times(1)).sendSystem(anyLong(), anyInt(), anyString(), anyString());
        verify(f.wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void falseCannotProveSuccessOrSafeRetryAcrossRestart() {
        Fixture f = new Fixture();
        when(f.wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString())).thenReturn(false);
        f.service.dispatch(f.broadcast);
        assertEquals(OfficialBroadcastLog.UNKNOWN, f.row("subscribe").getDeliveryState());
        assertEquals(0, f.row("subscribe").getReached());
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
        f.restart().runDueScheduled();
        verify(f.wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
        verify(f.im, times(1)).sendSystem(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void remoteExceptionRetainsUnknownIntentAndOriginalIdentity() {
        Fixture f = new Fixture(broadcast("subscribe"));
        when(f.wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("response lost after acceptance"));
        f.service.dispatch(f.broadcast);
        Long original = f.row("subscribe").getId();
        f.restart().dispatch(f.broadcast);
        assertEquals(original, f.row("subscribe").getId());
        assertEquals(OfficialBroadcastLog.UNKNOWN, f.row("subscribe").getDeliveryState());
        assertEquals(0, f.broadcast.getReachedCount());
        verify(f.wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void successFollowedByReceiptFailureDoesNotRepeatExternalAction() {
        Fixture f = new Fixture(broadcast("subscribe"));
        doThrow(new IllegalStateException("DB receipt unavailable")).when(f.logs)
                .transition(anyLong(), eq(OfficialBroadcastLog.UNKNOWN), eq(OfficialBroadcastLog.SUCCESS),
                        isNull(), isNull());
        assertThrows(IllegalStateException.class, () -> f.service.dispatch(f.broadcast));
        assertEquals(OfficialBroadcastLog.UNKNOWN, f.row("subscribe").getDeliveryState());
        f.restart().dispatch(f.broadcast);
        verify(f.wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void localFailureRetriesOnlyUnsuccessfulChannelAndKeepsConfirmedCounts() {
        Fixture f = new Fixture();
        when(f.im.sendSystem(anyLong(), anyInt(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("local write failed")).thenReturn(502L);
        assertEquals(0, f.service.dispatch(f.broadcast));
        assertEquals(OfficialBroadcastLog.FAILED, f.row("inapp").getDeliveryState());
        assertEquals(OfficialBroadcastLog.SUCCESS, f.row("subscribe").getDeliveryState());
        assertEquals(1, f.restart().dispatch(f.broadcast));
        assertEquals(502L, f.row("inapp").getMessageId());
        assertEquals(OfficialBroadcast.ST_SENT, f.broadcast.getStatus());
        verify(f.wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void nullMessageIdCannotBecomeSuccess() {
        Fixture f = new Fixture(broadcast("inapp"));
        when(f.im.sendSystem(anyLong(), anyInt(), anyString(), anyString())).thenReturn(null);
        assertEquals(0, f.service.dispatch(f.broadcast));
        assertEquals(OfficialBroadcastLog.FAILED, f.row("inapp").getDeliveryState());
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
    }

    @Test
    void remoteClaimIsDurableAndCallOccursOutsideTransactionTemplate() {
        Fixture f = new Fixture(broadcast("subscribe"));
        AtomicBoolean insideTemplate = new AtomicBoolean(false);
        doAnswer(call -> { insideTemplate.set(true); return new SimpleTransactionStatus(); })
                .when(f.transactions).getTransaction(argThat(definition -> definition != null
                        && definition.getPropagationBehavior() != org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED));
        doAnswer(call -> { insideTemplate.set(false); return null; }).when(f.transactions).commit(any());
        when(f.wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString())).thenAnswer(call -> {
            assertEquals(OfficialBroadcastLog.UNKNOWN, f.row("subscribe").getDeliveryState());
            assertFalse(insideTemplate.get(), "claim template must have committed before remote call");
            return true;
        });
        f.service.dispatch(f.broadcast);
        assertEquals(OfficialBroadcastLog.SUCCESS, f.row("subscribe").getDeliveryState());
    }

    @Test
    void recoveryUsesFrozenAudienceAndCopy() {
        Fixture f = new Fixture(broadcast("inapp"));
        when(f.im.sendSystem(anyLong(), anyInt(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("local failed")).thenReturn(503L);
        f.service.dispatch(f.broadcast);
        f.broadcast.setTitle("后来修改的标题");
        when(f.audience.selectPlayerIds(any())).thenReturn(List.of(20L));
        f.restart().dispatch(f.broadcast);
        assertEquals(1, f.rows.size());
        verify(f.im, times(2)).sendSystem(eq(10L), anyInt(), eq("官方通知"), anyString());
        verify(f.audience, times(1)).selectPlayerIds(any());
    }

    @Test
    void statsSeparateInappMembersFromSuccessfulChannelReceipts() {
        Fixture f = new Fixture();
        f.service.dispatch(f.broadcast);
        Map<String, Object> stats = f.service.reachStats(1L, null);
        assertEquals(1, stats.get("reached"));
        assertEquals(2, stats.get("channelReached"));
        assertEquals(1, f.broadcast.getReachedCount());
        verify(f.logs).channelStats(1L);
    }

    @Test
    void invalidOrRepeatedChannelsRejectBeforePersistingBroadcast() {
        Fixture f = new Fixture();
        for (String channels : List.of("subscribe,subscribe", "not-inapp", "inapp,")) {
            OfficialBroadcast invalid = broadcast(channels);
            assertThrows(RuntimeException.class, () -> f.service.publish(invalid, null));
        }
        verify(f.broadcasts, never()).insertOfficialBroadcast(any());
        verifyNoInteractions(f.im, f.wx);
    }

    @Test
    void unknownLegacyRowsNeverRetryEvenWhenReachedWasZero() {
        Fixture f = new Fixture(broadcast("inapp"));
        OfficialBroadcastLog old = new OfficialBroadcastLog();
        old.setId(88L); old.setBroadcastId(1L); old.setMemberId(10L); old.setChannel("inapp");
        old.setDeliveryState(OfficialBroadcastLog.UNKNOWN); old.setReached(0);
        f.rows.put(88L, old);
        f.restart().dispatch(f.broadcast);
        assertEquals(1, f.rows.size());
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
        verifyNoInteractions(f.im, f.wx);
    }

    @Test
    void committedPublishRemainsAcceptedPendingWhenWakeFails() {
        Fixture f = new Fixture();
        when(f.logs.lockBroadcast(1L)).thenThrow(new IllegalStateException("wake unavailable"));
        f.broadcast.setReachedCount(999);
        assertSame(f.broadcast, f.service.publish(f.broadcast, null));
        assertEquals(OfficialBroadcast.ST_PENDING, f.broadcast.getStatus());
        assertEquals(0, f.broadcast.getReachedCount());
        verify(f.broadcasts).insertOfficialBroadcast(f.broadcast);
        verifyNoInteractions(f.im, f.wx);
    }


    /** Original master durable-intent cases coexist with the original direct-receipt cases. */
    @Nested
    class DurableIntentProtocol {
 UmsMemberMapper members;
 OfficialBroadcastLogMapper logs;OfficialBroadcastMapper batches;IImService im;WxSubscribeChannel channel;OfficialBroadcastDeliveryService service;Map<String,Object> row;OfficialBroadcast bc;
 @org.junit.jupiter.api.BeforeEach void setup(){
  logs=mock(OfficialBroadcastLogMapper.class);batches=mock(OfficialBroadcastMapper.class);im=mock(IImService.class);channel=mock(WxSubscribeChannel.class);PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
  when(tx.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());service=new OfficialBroadcastDeliveryService(logs,batches,im,channel,tx);
  members=mock(UmsMemberMapper.class);when(members.lockMemberById(anyLong())).thenAnswer(c->c.getArgument(0));ReflectionTestUtils.setField(service,"members",members);
  bc=new OfficialBroadcast();bc.setId(9L);bc.setStatus(OfficialBroadcast.ST_PENDING);row=new HashMap<>();row.put("id",1L);row.put("broadcast_id",9L);row.put("member_id",7L);row.put("channel","subscribe");row.put("delivery_state","READY");row.put("frozen_title","frozen");row.put("frozen_sub","original");
  when(batches.lockDispatch(9L)).thenReturn(Map.of("status",OfficialBroadcast.ST_PENDING,"audience_frozen",1));
  when(logs.deliveryRows(9L)).thenAnswer(c->List.of(new HashMap<>(row)));when(logs.lockDelivery(1L)).thenReturn(row);
  doAnswer(c->{row.put("delivery_state",c.getArgument(1));row.put("intent_id",c.getArgument(2));row.put("message_id",c.getArgument(3));return 1;}).when(logs).deliveryState(anyLong(),anyString(),nullable(Long.class),nullable(Long.class),anyInt());
 }
 @Test void falseIsUnknownAndNeverBlindlyResent(){when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.UNKNOWN,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.TRANSPORT_UNCONFIRMED,null));service.resume(bc);assertEquals("UNKNOWN",row.get("delivery_state"));service.resume(bc);verify(channel,times(1)).sendOfficialBroadcast(anyLong(),anyString(),anyString());assertEquals(0,service.summary(9L).get("reached"));}
 @Test void trueMeansThisChannelAcceptedNotReached(){when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.ACCEPTED,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.PROVIDER_ACCEPTED,0));service.resume(bc);assertEquals("CHANNEL_ACCEPTED",row.get("delivery_state"));assertEquals(0,service.summary(9L).get("reached"));verify(logs,never()).markReached(anyLong(),anyLong(),anyString());}
 @Test void exceptionAndLegacyPlaceholderRemainUnknownWithoutReplay(){when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenThrow(new RuntimeException("timeout"));service.resume(bc);service.resume(bc);verify(channel,times(1)).sendOfficialBroadcast(anyLong(),anyString(),anyString());row.remove("delivery_state");service.resume(bc);verify(channel,times(1)).sendOfficialBroadcast(anyLong(),anyString(),anyString());}
 @Test void inappFailureRecoversSameDurableEventIdentity(){row.put("channel","inapp");when(im.enqueueSystemNotification(anyLong(),anyInt(),anyString(),anyString(),anyString())).thenReturn(31L);when(im.deliverSystemNotification(31L)).thenThrow(new RuntimeException("rollback")).thenReturn(81L);
  service.resume(bc);assertEquals("RETRYABLE",row.get("delivery_state"));service.resume(bc);assertEquals("INAPP_STORED",row.get("delivery_state"));service.resume(bc);
  verify(im,times(2)).enqueueSystemNotification(eq(7L),anyInt(),eq("frozen"),anyString(),eq("official_bc:9:7:inapp"));verify(im,times(2)).deliverSystemNotification(31L);
 }
 @Test void staleBroadcastCannotSendAfterRevocation(){when(batches.lockDispatch(9L)).thenReturn(Map.of("status",OfficialBroadcast.ST_REVOKED));service.resume(bc);service.finish(bc);verifyNoInteractions(im,channel);verify(batches,never()).updateOfficialBroadcast(any());}
 @Test void cancelledBroadcastDoesNotSend(){bc.setStatus(OfficialBroadcast.ST_REVOKED);service.resume(bc);verifyNoInteractions(im,channel);}
 @Test void onlyDefiniteNonSubmissionAllowsManualRetry(){
  when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.NOT_SUBMITTED,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.TEMPLATE_MISSING,null)).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.REJECTED,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.PROVIDER_REJECTED,43101));
  service.resume(bc);assertEquals("NOT_SUBMITTED_TEMPLATE",row.get("delivery_state"));assertEquals(true,service.summary(9L).get("canRecoverInapp"));
  service.resume(bc);assertEquals("REJECTED_43101",row.get("delivery_state"));service.resume(bc);verify(channel,times(2)).sendOfficialBroadcast(anyLong(),anyString(),anyString());assertEquals(false,service.summary(9L).get("canRecoverInapp"));assertEquals(0,service.summary(9L).get("reached"));
 }

 @Test void durableInappRechecksTheSharedCapBeforeIntentOrMessage(){
  row.put("channel","inapp");when(logs.countRecentByMember(7L,60)).thenReturn(5);
  service.resume(bc);
  assertEquals("READY",row.get("delivery_state"));
  verifyNoInteractions(im,channel);
  verify(members).lockMemberById(7L);
 }
 @Test void durableInappKeepsMemberFenceThroughIntentMessageAndReceipt(){
  row.put("channel","inapp");
  when(im.enqueueSystemNotification(anyLong(),anyInt(),anyString(),anyString(),anyString())).thenReturn(31L);
  when(im.deliverSystemNotification(31L)).thenReturn(81L);
  service.resume(bc);
  org.mockito.InOrder locks=inOrder(members,logs,im);
  locks.verify(members).lockMemberById(7L);
  locks.verify(logs).lockDelivery(1L);
  locks.verify(logs).countRecentByMember(7L,60);
  locks.verify(im).enqueueSystemNotification(eq(7L),anyInt(),eq("frozen"),anyString(),eq("official_bc:9:7:inapp"));
  locks.verify(im).deliverSystemNotification(31L);
  locks.verify(logs).deliveryState(1L,"INAPP_STORED",31L,81L,1);
 }

    }
}
