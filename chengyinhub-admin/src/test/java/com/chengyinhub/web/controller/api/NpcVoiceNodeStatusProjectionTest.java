package com.chengyinhub.web.controller.api;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.ai.NpcVoiceEnrollmentService;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.common.core.domain.AjaxResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual controller projection only; root must apply node-status.review.patch. No JWT/provider claim. */
class NpcVoiceNodeStatusProjectionTest
{
    private final NodeNpcService npc=mock(NodeNpcService.class);
    private ApiChapterMerchantNodeController controller() {
        ApiChapterMerchantNodeController result=new ApiChapterMerchantNodeController(){@Override public Long getAppUserId(){return 88L;}};
        IMerchantAccessService access=mock(IMerchantAccessService.class);
        when(access.require(88L,MerchantPermission.PROJECT_MANAGE)).thenReturn(new MerchantAccessContext(88L,11L,900L,MerchantRoleCode.MERCHANT_MANAGER));
        ReflectionTestUtils.setField(result,"merchantAccessService",access);ReflectionTestUtils.setField(result,"nodeNpcService",npc);return result;
    }
    private ApiChapterMerchantNodeController.NodeNpcRequest request() {
        ApiChapterMerchantNodeController.NodeNpcRequest req=new ApiChapterMerchantNodeController.NodeNpcRequest();req.setNodeId(31L);req.setVoiceSample("media-asset:73");return req;
    }
    @Test void unknownOriginalIntentIsNotLostByTheNodeStatusWhitelist() {
        NpcProfile row=new NpcProfile();row.setVoiceStatus(1);row.setVoiceSample("media-asset:73");
        row.setVoiceIntentId(17L);row.setVoiceIntentState("UNKNOWN");row.setVoiceReasonCode("VOICE_PROVIDER_OUTCOME_UNKNOWN");
        row.setVoiceId("private-known-id");when(npc.voiceStatus(88L,900L,31L,null)).thenReturn(row);
        AjaxResult result=controller().npcVoiceStatus(request());assertEquals(200,result.get(AjaxResult.CODE_TAG));
        Map<?,?> data=assertInstanceOf(Map.class,result.get(AjaxResult.DATA_TAG));assertEquals(9,data.size());
        assertNull(data.get("voicePreviousIntentId"));assertNull(data.get("voiceRequestId"));assertNull(data.get("voiceSelected"));
        assertEquals(java.util.List.of(),data.get("voiceHistory"));
        assertEquals(1,data.get("voiceStatus"));assertEquals("media-asset:73",data.get("voiceSample"));
        assertEquals(17L,data.get("voiceIntentId"));assertEquals("UNKNOWN",data.get("voiceIntentState"));
        assertEquals("VOICE_PROVIDER_OUTCOME_UNKNOWN",data.get("voiceReasonCode"));assertFalse(data.containsKey("voiceId"));
        verify(npc).voiceStatus(88L,900L,31L,null);verify(npc,never()).voiceEnroll(any(),any(),any(),any(),any(),any(),anyBoolean());
    }
    @Test void absentProfileStillReturnsNoneWithoutFabricatingAnIntent() {
        AjaxResult result=controller().npcVoiceStatus(request());Map<?,?> data=assertInstanceOf(Map.class,result.get(AjaxResult.DATA_TAG));
        assertEquals(0,data.get("voiceStatus"));assertNull(data.get("voiceSample"));
        assertNull(data.get("voiceIntentId"));assertNull(data.get("voiceIntentState"));assertNull(data.get("voiceReasonCode"));
        verify(npc,never()).voiceEnroll(any(),any(),any(),any(),any(),any(),anyBoolean());
    }
    @Test void stateUnavailableIsAnErrorRatherThanSuccessfulNone() {
        when(npc.voiceStatus(88L,900L,31L,null)).thenThrow(new NpcVoiceEnrollmentService.StateUnavailableException());
        AjaxResult result=controller().npcVoiceStatus(request());assertEquals(500,result.get(AjaxResult.CODE_TAG));
        assertNull(result.get(AjaxResult.DATA_TAG));verify(npc,never()).voiceEnroll(any(),any(),any(),any(),any(),any(),anyBoolean());
    }
    @Test void nodeEnrollmentAlreadyReturnsTheOriginalMetadataWithoutASecondProjection() throws Exception {
        NpcProfile row=new NpcProfile();row.setVoiceStatus(1);row.setVoiceIntentId(17L);row.setVoiceIntentState("UNKNOWN");
        when(npc.voiceEnroll(88L,900L,31L,"media-asset:73",null,null,false)).thenReturn(row);
        AjaxResult result=controller().npcVoiceEnroll(request());assertSame(row,result.get(AjaxResult.DATA_TAG));
        String json=new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));
        assertTrue(json.contains("\"voiceIntentId\":17"));assertTrue(json.contains("\"voiceIntentState\":\"UNKNOWN\""));assertFalse(json.contains("voiceVersion"));
        verify(npc,times(1)).voiceEnroll(88L,900L,31L,"media-asset:73",null,null,false);
    }
}
