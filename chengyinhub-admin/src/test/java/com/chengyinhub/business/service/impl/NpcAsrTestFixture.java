package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcAsrIntent;
import com.chengyinhub.business.mapper.NpcAsrIntentMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Persistence/transaction doubles only; both controller fixtures and owner tests run the real ASR algorithm. */
public class NpcAsrTestFixture {
    static final String RID="e2596106-82d9-4a4f-9ff2-058414599f90";
    static final byte[] AUDIO={1,2,3};
        final NpcAsrIntentMapper rows=mock(NpcAsrIntentMapper.class);
        final NpcChatRequestMapper users=mock(NpcChatRequestMapper.class);
        final SpeechToTextService speech=mock(SpeechToTextService.class);
        final com.chengyinhub.business.service.ai.AiGatewayService quota=mock(com.chengyinhub.business.service.ai.AiGatewayService.class);
        final Map<String,NpcAsrIntent> stored=new HashMap<>();
        final AtomicBoolean lostCommit=new AtomicBoolean();
        final ReentrantLock lock=new ReentrantLock();
        final AbstractPlatformTransactionManager manager=new AbstractPlatformTransactionManager(){
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){lock.lock();}
            protected void doCommit(DefaultTransactionStatus status){if(lostCommit.getAndSet(false)) throw new IllegalStateException("controlled commit acknowledgement loss");}
            protected void doRollback(DefaultTransactionStatus status){}
            protected void doCleanupAfterCompletion(Object tx){lock.unlock();}
        };
        NpcAsrIntentService service(){return new NpcAsrIntentService(rows,users,manager,speech,quota);}
        String key(Long user,String rid){return user+":"+rid.toLowerCase(Locale.ROOT);}
        NpcAsrIntent original(){return stored.get(key(7L,RID));}
        public NpcAsrTestFixture(){
            when(quota.planQuotaReservation(anyLong(),eq("npc_shop_chat"),anyString())).thenAnswer(c ->
                    "ai:gen:limit:gen:"+c.getArgument(0)+":20261013|"+c.getArgument(2));
            when(quota.consumePlannedQuota(anyLong(),eq("npc_shop_chat"),anyString())).thenAnswer(c -> c.getArgument(2));
            when(rows.finishNotSent(any())).thenAnswer(c -> {
                NpcAsrIntent value=c.getArgument(0),old=stored.get(key(value.getUserId(),value.getRequestId()));
                if(old==null || old.isHandoffStarted() || old.isTextAdmitted()
                        || !java.util.Set.of("IN_FLIGHT","UNKNOWN").contains(old.getState())
                        || !old.getClaimToken().equals(value.getClaimToken())
                        || !Objects.equals(old.getQuotaReservation(),value.getQuotaReservation())) return 0;
                old.setState("NOT_SENT");old.setTranscript(null);old.setBodyExpiresAt(null);old.setQuotaRefundPending(true);return 1;
            });
            when(users.lockChatUser(anyLong())).thenAnswer(c->c.getArgument(0));
            when(rows.selectForUpdate(anyLong(),anyString())).thenAnswer(c->{
                NpcAsrIntent old=stored.get(key(c.getArgument(0),c.getArgument(1)));
                if(old==null)return null;NpcAsrIntent copy=new NpcAsrIntent();BeanUtils.copyProperties(old,copy);
                if(copy.isBodyErased() || copy.getBodyExpiresAt()!=null && copy.getBodyExpiresAt().before(new Date())) {
                    copy.setTranscript(null);copy.setBodyErased(true);
                }
                return copy;
            });
            when(rows.insert(any())).thenAnswer(c->{NpcAsrIntent row=c.getArgument(0);row.setId((long)stored.size()+1);
                assertNull(stored.putIfAbsent(key(row.getUserId(),row.getRequestId()),row));return 1;});
            when(rows.finish(any())).thenAnswer(c->{
                NpcAsrIntent value=c.getArgument(0),old=stored.get(key(value.getUserId(),value.getRequestId()));
                if(old.isBodyErased() || !"IN_FLIGHT".equals(old.getState()) || !old.getClaimToken().equals(value.getClaimToken()))return 0;
                old.setState(value.getState());old.setTranscript(value.getTranscript());old.setBodyExpiresAt(new Date(System.currentTimeMillis()+172800000));return 1;
            });
            when(rows.beginHandoff(anyLong(),anyString())).thenAnswer(c->{
                NpcAsrIntent old=stored.get(key(c.getArgument(0),c.getArgument(1)));
                if(!"RECEIVED".equals(old.getState()) || old.isBodyErased() || old.isHandoffStarted()
                        || old.getTranscript()==null || old.getTranscript().isEmpty())return 0;
                old.setHandoffStarted(true);return 1;
            });
            when(rows.redactForUser(anyLong())).thenAnswer(c->{long user=c.getArgument(0);int n=0;
                for(var row:stored.values())if(row.getUserId()==user && !row.isBodyErased()){
                    row.setBodyErased(true);row.setTranscript(null);if("IN_FLIGHT".equals(row.getState()))row.setState("UNKNOWN");n++;}return n;});
            when(rows.redactExpiredForUser(anyLong())).thenAnswer(c->{long user=c.getArgument(0);int n=0;
                for(var row:stored.values())if(row.getUserId()==user && row.getBodyExpiresAt()!=null
                        && row.getBodyExpiresAt().before(new Date()) && !row.isBodyErased()){
                    row.setBodyErased(true);row.setTranscript(null);n++;}return n;});
            when(speech.transcribe(any(),anyString())).thenReturn("原转写");
        }
        NpcAsrIntent recognize(){return service().recognize(7L,RID,21L,41L,AUDIO,"wav");}

    public NpcAsrIntentService service(SpeechToTextService supplied) { return new NpcAsrIntentService(rows,users,manager,supplied,quota); }
    public static void wireController(Object controller,SpeechToTextService speech) {
        NpcAsrTestFixture fixture=new NpcAsrTestFixture();
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"npcAsrIntentService",
                new NpcAsrIntentService(fixture.rows,fixture.users,fixture.manager,speech,fixture.quota));
    }
}
