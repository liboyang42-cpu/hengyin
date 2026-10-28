package com.chengyinhub.business.service.impl;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.notify.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Frozen per-recipient attempts. No automatic retries without an approved backoff/expiry policy. */
@Service
public class OfficialBroadcastDeliveryService {
 private final OfficialBroadcastLogMapper logs;
 private final OfficialBroadcastMapper broadcasts;
 private final IImService im;
 private final WxSubscribeChannel subscribe;
 private final TransactionTemplate tx;
 public OfficialBroadcastDeliveryService(OfficialBroadcastLogMapper logs,OfficialBroadcastMapper broadcasts,IImService im,WxSubscribeChannel subscribe,PlatformTransactionManager manager){
  this.logs=logs;this.broadcasts=broadcasts;this.im=im;this.subscribe=subscribe;
  tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public void prepare(OfficialBroadcast bc,List<Map<String,Object>> recipients){
  tx.execute(status->{Map<String,Object> batch=broadcasts.lockDispatch(bc.getId());
   if(batch==null || ((Number)batch.get("status")).intValue()==OfficialBroadcast.ST_REVOKED || ((Number)batch.get("audience_frozen")).intValue()==1)return null;
   // A historical processed broadcast cannot acquire new recipients on recovery.
   if(!Integer.valueOf(OfficialBroadcast.ST_SENT).equals(bc.getStatus()) && logs.deliveryRows(bc.getId()).isEmpty()) for(Map<String,Object> row:recipients)
    logs.snapshot(bc.getId(),(Long)row.get("member"),(String)row.get("role"),(String)row.get("channel"),(String)row.get("title"),(String)row.get("sub"),(String)row.get("state"));
   broadcasts.freezeAudience(bc.getId());return null;
  });
 }
 public void resume(OfficialBroadcast bc){
  if(bc==null || Integer.valueOf(OfficialBroadcast.ST_REVOKED).equals(bc.getStatus()))return;
  for(Map<String,Object> row:logs.deliveryRows(bc.getId())) {
   Long id=((Number)row.get("id")).longValue();String state=(String)row.get("delivery_state");
   if("inapp".equals(row.get("channel")) && ("READY".equals(state)||"RETRYABLE".equals(state))) inapp(bc.getId(),id);
   if("subscribe".equals(row.get("channel")) && ("READY".equals(state)||state!=null && state.startsWith("NOT_SUBMITTED_"))) subscription(bc.getId(),id);
  }
 }
 private void inapp(Long broadcastId,Long id){
  try {
   tx.execute(status->{if(!active(broadcastId))return null;Map<String,Object> row=logs.lockDelivery(id);String state=(String)row.get("delivery_state");
    if(!Set.of("READY","RETRYABLE").contains(state))return null;
    Long member=((Number)row.get("member_id")).longValue(),bc=((Number)row.get("broadcast_id")).longValue();
    String title=(String)row.get("frozen_title"),sub=(String)row.get("frozen_sub");
    String extra=JSON.toJSONString(Map.of("title",title,"sub",sub,"bcId",String.valueOf(bc)));
    Long intent=im.enqueueSystemNotification(member,ImMessage.MSG_CARD,title,extra,"official_bc:"+bc+":"+member+":inapp");
    if(intent==null)throw new IllegalStateException("站内原意图未确认");
    Long message=im.deliverSystemNotification(intent);
    if(message==null)throw new IllegalStateException("站内消息未确认");
    logs.deliveryState(id,"INAPP_STORED",intent,message,1);return null;
   });
  }catch(RuntimeException failure){
   // May be an uncertain commit: lock and read current state; never overwrite a committed success.
   // Retrying uses the SAME durable IM event key, so an unknown local outcome cannot duplicate a message.
   tx.execute(status->{if(!active(broadcastId))return null;Map<String,Object> row=logs.lockDelivery(id);String state=(String)row.get("delivery_state");
    if("READY".equals(state)||"RETRYABLE".equals(state))logs.deliveryState(id,"RETRYABLE",number(row.get("intent_id")),number(row.get("message_id")),1);return null;});
  }
 }
 private void subscription(Long broadcastId,Long id){
  Map<String,Object> frozen=tx.execute(status->{if(!active(broadcastId))return null;Map<String,Object> row=logs.lockDelivery(id);
   String state=(String)row.get("delivery_state");
   if(!"READY".equals(state) && !(state!=null && state.startsWith("NOT_SUBMITTED_")))return null;
   // Commit UNKNOWN before network IO. Process death/timeout must not turn into a fresh send.
   logs.deliveryState(id,"UNKNOWN",null,null,1);return row;});
  if(frozen==null)return;
  com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult outcome;
  try{outcome=subscribe.sendOfficialBroadcast(number(frozen.get("member_id")),
      (String)frozen.get("frozen_title"),(String)frozen.get("frozen_sub"));}
  catch(RuntimeException failure){return;} // UNKNOWN remains durable; never grant retry on an exception.
  if(outcome==null)return;
  String next=switch(outcome.state()) {
   case ACCEPTED -> "CHANNEL_ACCEPTED";
   case REJECTED -> "REJECTED_"+outcome.errcode();
   case UNKNOWN -> "UNKNOWN";
   case NOT_SUBMITTED -> "NOT_SUBMITTED_"+switch(outcome.reason()) {
    case TEMPLATE_MISSING -> "TEMPLATE"; case IDENTITY_MISSING -> "IDENTITY";
    case TOKEN_MISSING -> "TOKEN"; default -> "LOCAL";
   };
  };
  tx.execute(status->{Map<String,Object> row=logs.lockDelivery(id);
   if("UNKNOWN".equals(row.get("delivery_state")))logs.deliveryState(id,next,null,null,0);return null;});
  // Existing VARCHAR(32) state retains only fixed reasons/numeric codes, never provider diagnostics.

 }
 private boolean active(Long id){Map<String,Object> batch=broadcasts.lockDispatch(id);return batch!=null && ((Number)batch.get("status")).intValue()!=OfficialBroadcast.ST_REVOKED;}
 private Long number(Object value){return value==null?null:((Number)value).longValue();}
 public int finish(OfficialBroadcast bc){
  return tx.execute(status->{
   Map<String,Object> batch=broadcasts.lockDispatch(bc.getId());Map<String,Object> result=summary(bc.getId());
   @SuppressWarnings("unchecked") Map<String,Integer> counts=(Map<String,Integer>)result.get("deliveryCounts");
   int unresolved=counts.get("UNKNOWN")+counts.get("RETRYABLE")+counts.get("READY")+counts.get("LEGACY_UNCONFIRMED")+counts.get("CAPPED")+counts.get("NOT_SUBMITTED");
   int reached=counts.get("INAPP_STORED");OfficialBroadcast update=new OfficialBroadcast();update.setId(bc.getId());update.setReachedCount(reached);update.setStatus(unresolved==0?OfficialBroadcast.ST_SENT:OfficialBroadcast.ST_PENDING);
   if(batch==null || ((Number)batch.get("status")).intValue()==OfficialBroadcast.ST_REVOKED)return reached;
   broadcasts.updateOfficialBroadcast(update);bc.setReachedCount(reached);bc.setStatus(update.getStatus());return reached;
  });
 }
 public Map<String,Object> summary(Long id){
  Map<String,Integer> counts=new LinkedHashMap<>();for(String s:List.of("INAPP_STORED","CHANNEL_ACCEPTED","REJECTED","NOT_SUBMITTED","RETRYABLE","UNKNOWN","READY","CAPPED","LEGACY_UNCONFIRMED"))counts.put(s,0);
  for(Map<String,Object> row:logs.deliveryRows(id)){String state=(String)row.get("delivery_state");if(state==null)state="LEGACY_UNCONFIRMED";else if(state.startsWith("REJECTED_"))state="REJECTED";else if(state.startsWith("NOT_SUBMITTED_"))state="NOT_SUBMITTED";counts.put(state,counts.getOrDefault(state,0)+1);}
  Map<String,Object> out=new LinkedHashMap<>();out.put("deliveryCounts",counts);out.put("reached",counts.get("INAPP_STORED"));out.put("clicked",logs.countClicked(id));
  out.put("canRecoverInapp",counts.get("RETRYABLE")+counts.get("READY")+counts.get("NOT_SUBMITTED")>0);
  out.put("autoRetryPolicy","DEFERRED_BY_USER");out.put("expiryPolicy","DEFERRED_BY_USER");
  out.put("deliveryNote","站内已入库不等于已读；订阅仅表示渠道受理。UNKNOWN及历史未确认不会重发。");return out;
 }
}
