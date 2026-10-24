package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Apply controller.review.patch first. Real ASR algorithm over unit persistence doubles; no HTTP/provider/DB. */
class NpcAsrControllerReadbackTest {
    static final String RID="e2596106-82d9-4a4f-9ff2-058414599f90";
    SpeechToTextService speech=mock(SpeechToTextService.class);
    NpcChatService text=mock(NpcChatService.class);
    NpcAsrTestFixture fixture=new NpcAsrTestFixture();
    NpcAsrIntentService asr=fixture.service(speech);
    ApiAiNpcController controller(){
        ApiAiNpcController c=spy(new ApiAiNpcController());doReturn(7L).when(c).getAppUserId();
        ReflectionTestUtils.setField(c,"npcChatService",text);ReflectionTestUtils.setField(c,"npcAsrIntentService",asr);
        ReflectionTestUtils.setField(c,"speechToTextService",speech);
        ReflectionTestUtils.setField(c,"cmsMemberTemplateService",mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class));
        NpcFeatureFlags flags=mock(NpcFeatureFlags.class);when(flags.shopChatOn()).thenReturn(true);
        ReflectionTestUtils.setField(c,"npcFeatureFlags",flags);
        ICmsTopicNodeService nodes=mock(ICmsTopicNodeService.class);CmsTopicNode node=new CmsTopicNode();
        node.setId(21L);node.setTopicId(31L);node.setMerchantMemberId(41L);when(nodes.selectCmsTopicNodeById(21L)).thenReturn(node);
        ReflectionTestUtils.setField(c,"cmsTopicNodeService",nodes);PlayTeamMapper gate=mock(PlayTeamMapper.class);
        when(gate.hasCurrentNpcRegistration(7L,31L)).thenReturn(1);ReflectionTestUtils.setField(c,"playTeamMapper",gate);
        return c;
    }
    @Test void getNotFoundComposesTheSavedAsrStageWithoutCallingProviderOrTextHandoff(){
        when(speech.transcribe(any(),anyString())).thenReturn("original transcript");asr.recognize(7L,RID,21L,41L,new byte[]{1},"wav");
        clearInvocations(speech);NpcChatResp missing=new NpcChatResp();missing.setOutcomeStatus("NOT_FOUND");
        when(text.readOriginalRequest("shop",21L,RID,7L)).thenReturn(missing);
        var result=controller().chatRequest(RID,"shop",21L);var data=(NpcChatResp)result.get("data");
        assertEquals("ASR_RECEIVED",data.getAsrReason());assertFalse(data.isRetryable());assertFalse(data.getSafeText().contains("original transcript"));
        verifyNoInteractions(speech);verify(text,never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
        assertFalse(asr.read(7L,RID,21L).isHandoffStarted());
    }
    @Test void oldTextReceiptHasPriorityEvenWhenAsrLookupWouldFail(){
        NpcChatResp original=new NpcChatResp();original.setOutcomeStatus("SUCCEEDED");
        when(text.readOriginalRequest("shop",21L,RID,7L)).thenReturn(original);
        var c=controller();NpcAsrIntentService unavailable=mock(NpcAsrIntentService.class);
        ReflectionTestUtils.setField(c,"npcAsrIntentService",unavailable);
        assertSame(original,c.chatRequest(RID,"shop",21L).get("data"));verifyNoInteractions(unavailable,speech);
    }
    @Test void coldExplicitPostResumesSavedTranscriptionOnceWithoutResendingAudio(){
        when(speech.transcribe(any(),anyString())).thenReturn("saved original");asr.recognize(7L,RID,21L,41L,new byte[]{1},"wav");
        clearInvocations(speech);NpcChatResp reply=new NpcChatResp();reply.setOutcomeStatus("SUCCEEDED");
        when(text.chatWithShop(eq(RID),eq(7L),eq(21L),eq(41L),anyString(),eq("saved original"))).thenReturn(reply);
        var result=controller().voiceChat(null,21L,RID);assertSame(reply,result.get("data"));assertEquals("saved original",result.get("asr"));
        // Simulate the text receipt no longer existing: the permanent ASR handoff still prevents another model entry.
        var second=controller().voiceChat(null,21L,RID);
        assertEquals("ASR_TEXT_HANDOFF_STARTED",((NpcChatResp)second.get("data")).getAsrReason());
        verifyNoInteractions(speech);verify(text,times(1)).chatWithShop(eq(RID),eq(7L),eq(21L),eq(41L),anyString(),eq("saved original"));
    }
    @Test void deletionDuringTextHandoffCannotEchoTheOldAsrBody(){
        when(speech.transcribe(any(),anyString())).thenReturn("body removed by its owner");
        asr.recognize(7L,RID,21L,41L,new byte[]{1},"wav");clearInvocations(speech);
        NpcChatResp deletedReply=new NpcChatResp();deletedReply.setOutcomeStatus("FAILED");
        when(text.chatWithShop(eq(RID),eq(7L),eq(21L),eq(41L),anyString(),eq("body removed by its owner")))
                .thenAnswer(call->{
                    ((com.chengyinhub.business.mapper.NpcAsrIntentMapper)ReflectionTestUtils.getField(asr,"intents")).redactForUser(7L);
                    return deletedReply;
                });
        var result=controller().voiceChat(null,21L,RID);
        assertSame(deletedReply,result.get("data"));assertFalse(result.containsKey("asr"));
        assertEquals("ASR_TEXT_UNAVAILABLE",asr.readOutcome("shop",21L,RID,7L).getErrorCode());
        verifyNoInteractions(speech);
    }
    @Test void knownUnknownVoiceStageKeepsTheLegacyPendingContractUnderASuccessEnvelope(){
        when(speech.transcribe(any(),anyString())).thenThrow(new IllegalStateException("controlled provider outcome loss"));
        asr.recognize(7L,RID,21L,41L,new byte[]{1},"wav");clearInvocations(speech);
        var result=controller().voiceChat(null,21L,RID);var data=(NpcChatResp)result.get("data");
        assertEquals(200,result.get("code"));assertEquals("PROCESSING",data.getOutcomeStatus());
        assertEquals("REQUEST_OUTCOME_UNKNOWN",data.getErrorCode());assertEquals("UNKNOWN",data.getAsrState());
        assertEquals("ASR_OUTCOME_UNKNOWN",data.getAsrReason());assertFalse(data.isAsrResumable());
        verifyNoInteractions(speech);verify(text,never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }
    @Test void lookupFailureStaysPendingForBothPostAndGetInsteadOfClaimingNotFound(){
        var c=controller();NpcAsrIntentService failed=mock(NpcAsrIntentService.class);
        when(failed.read(7L,RID,21L)).thenThrow(new IllegalStateException("controlled persistence unavailable"));
        when(failed.readOutcome("shop",21L,RID,7L)).thenReturn(NpcAsrIntentService.unavailable(RID,"ASR_STATE_UNAVAILABLE"));
        ReflectionTestUtils.setField(c,"npcAsrIntentService",failed);
        var post=c.voiceChat(null,21L,RID);var postData=(NpcChatResp)post.get("data");
        assertEquals(200,post.get("code"));assertEquals("PROCESSING",postData.getOutcomeStatus());
        assertEquals("REQUEST_OUTCOME_UNKNOWN",postData.getErrorCode());assertEquals("ASR_STATE_UNAVAILABLE",postData.getAsrReason());assertNull(postData.getAsrState());
        NpcChatResp missing=new NpcChatResp();missing.setOutcomeStatus("NOT_FOUND");when(text.readOriginalRequest("shop",21L,RID,7L)).thenReturn(missing);
        var get=c.chatRequest(RID,"shop",21L);assertEquals(200,get.get("code"));
        assertEquals("REQUEST_OUTCOME_UNKNOWN",((NpcChatResp)get.get("data")).getErrorCode());
        verifyNoInteractions(speech);verify(text,never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }
}
