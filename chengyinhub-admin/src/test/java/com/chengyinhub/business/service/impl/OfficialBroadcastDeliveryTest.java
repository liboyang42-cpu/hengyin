package com.chengyinhub.business.service.impl;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.notify.*;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class OfficialBroadcastDeliveryTest {
 OfficialBroadcastLogMapper logs;OfficialBroadcastMapper batches;IImService im;WxSubscribeChannel channel;OfficialBroadcastDeliveryService service;Map<String,Object> row;OfficialBroadcast bc;
 @BeforeEach void setup(){
  logs=mock(OfficialBroadcastLogMapper.class);batches=mock(OfficialBroadcastMapper.class);im=mock(IImService.class);channel=mock(WxSubscribeChannel.class);PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
  when(tx.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());service=new OfficialBroadcastDeliveryService(logs,batches,im,channel,tx);
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
}
