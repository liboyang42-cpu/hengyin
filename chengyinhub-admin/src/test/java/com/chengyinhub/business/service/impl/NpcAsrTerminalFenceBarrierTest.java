package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real NPC/ASR/write owners sharing the original member-fence transaction double; no DB/provider claim. */
class NpcAsrTerminalFenceBarrierTest {
    @Test void emptyMessageCannotMaskAnAsrThatWonAfterTheInitialEmptyReads() throws Exception {
        interleaving(true,21L,41L,false);
    }
    @Test void missingProfileCannotMaskAnAsrThatWonAfterTheInitialEmptyReads() throws Exception {
        interleaving(false,21L,41L,false);
    }
    @Test void lateTerminalWithConflictingNodeOrShopRejectsWithoutMaskingTheOriginal() throws Exception {
        interleaving(true,22L,41L,true);
        interleaving(true,21L,42L,true);
    }

    private void interleaving(boolean empty,Long textNode,Long textShop,boolean conflict) throws Exception {
        var f=new NpcAsrTestFixture();
        Map<String,NpcChatRequest> textRows=new ConcurrentHashMap<>();
        when(f.users.selectByUserAndRequestId(anyLong(),anyString()))
                .thenAnswer(call->textRows.get(call.getArgument(0)+":"+call.getArgument(1)));
        when(f.users.insertNpcChatRequest(any())).thenAnswer(call->{
            NpcChatRequest row=call.getArgument(0);textRows.put(row.getUserId()+":"+row.getRequestId(),row);return 1;
        });
        NpcChatWriteService writes=spy(new NpcChatWriteService(f.users,mock(NpcChatLogMapper.class),f.manager));
        ReflectionTestUtils.setField(writes,"asrIntents",f.rows);
        NpcChatService text=new NpcChatService();
        ReflectionTestUtils.setField(text,"npcChatWriteService",writes);
        ReflectionTestUtils.setField(text,"npcChatRequestMapper",f.users);
        ReflectionTestUtils.setField(text,"aiGatewayService",f.quota);
        NpcProfileMapper profiles=mock(NpcProfileMapper.class);
        when(profiles.selectEnabledByScope(anyInt(),anyLong())).thenReturn(List.of());
        ReflectionTestUtils.setField(text,"npcProfileMapper",profiles);
        CountDownLatch emptyReads=new CountDownLatch(1),allowTerminal=new CountDownLatch(1);
        CountDownLatch speechStarted=new CountDownLatch(1),finishSpeech=new CountDownLatch(1);
        doAnswer(call->{
            // The text owner has completed its empty receipt read; model the caller's empty ASR read too.
            assertNull(f.service().read(7L,NpcAsrTestFixture.RID,21L));
            emptyReads.countDown();assertTrue(allowTerminal.await(10,TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(writes).insertTerminal(any());
        when(f.speech.transcribe(any(),anyString())).thenAnswer(call->{
            speechStarted.countDown();assertTrue(finishSpeech.await(10,TimeUnit.SECONDS));return "原转写";
        });
        try(var workers=Executors.newFixedThreadPool(2)) {
            try {
                var pendingText=workers.submit(()->text.chatWithShop(NpcAsrTestFixture.RID,7L,textNode,textShop,
                        "冻结店铺事实",empty?"":"有效文本"));
                assertTrue(emptyReads.await(10,TimeUnit.SECONDS));
                var pendingVoice=workers.submit(f::recognize);
                assertTrue(speechStarted.await(10,TimeUnit.SECONDS));
                var original=f.service().read(7L,NpcAsrTestFixture.RID,21L);
                String reservation=original.getQuotaReservation();assertNotNull(reservation);
                assertEquals("IN_FLIGHT",original.getState());assertTrue(textRows.isEmpty());
                allowTerminal.countDown();var result=pendingText.get(10,TimeUnit.SECONDS);
                if(conflict) assertEquals("INVALID_REQUEST",result.getErrorCode());
                else {
                    assertEquals("PROCESSING",result.getOutcomeStatus());assertEquals("IN_FLIGHT",result.getAsrState());
                    assertEquals("REQUEST_IN_PROGRESS",result.getErrorCode());assertEquals("PROCESSING",result.getQuotaStatus());
                }
                verify(f.users,never()).insertNpcChatRequest(any());assertTrue(textRows.isEmpty());
                finishSpeech.countDown();assertEquals("原转写",pendingVoice.get(10,TimeUnit.SECONDS).getTranscript());
                assertNull(writes.read(7L,NpcAsrTestFixture.RID));
                var visible=f.service().readOutcome("shop",21L,NpcAsrTestFixture.RID,7L);
                assertEquals("ASR_RECEIVED",visible.getAsrReason());assertTrue(visible.isAsrResumable());
                assertEquals(reservation,f.service().read(7L,NpcAsrTestFixture.RID,21L).getQuotaReservation());
                verify(f.quota,times(1)).consumePlannedQuota(7L,"npc_shop_chat",reservation);
                verify(f.quota,never()).refundNpcAsrNotSent(anyLong(),anyString());
                verify(f.quota,never()).generate(anyLong(),anyString(),anyString(),anyString(),anyString());
                verify(f.quota,never()).generateNpcAsrHandoff(any(),anyString(),anyString());
            } finally { allowTerminal.countDown();finishSpeech.countDown(); }
        }
    }
}
