package com.chengyinhub.web.controller.api;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.NpcVoiceEnrollmentServiceTest.Fixture;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.impl.NodeNpcService;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;

import static com.chengyinhub.business.service.ai.NpcVoiceEnrollmentServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual two production callers -> actual intent service and media guard; DB/provider are controlled doubles. */
class NpcVoiceEnrollmentCallerTest
{
    private static final String OBJECT_PATH="upload/0123456789abcdef0123456789abcdef.mp3";
    private static final String PRIVATE_INPUT="https://media.invalid/private-approved-audio";

    private static class Caller
    {
        final Fixture fixture;
        final ApiMerchantController merchant;
        final NodeNpcService node;
        final ConsentGuard consent=mock(ConsentGuard.class);
        final IChapterMerchantNodeService nodeAccess=mock(IChapterMerchantNodeService.class);
        final IMerchantAccessService merchantAccess=mock(IMerchantAccessService.class);
        final AppMediaAssetMapper assets=mock(AppMediaAssetMapper.class);
        final boolean isMerchant;
        Caller(boolean isMerchant)
        {
            this.isMerchant=isMerchant;fixture=new Fixture(isMerchant?2:4);
            when(fixture.profiles.selectMerchantNpcList(SCOPE,null)).thenAnswer(i->Collections.singletonList(Fixture.copy(fixture.stored)));
            when(fixture.profiles.selectNodeNpcByNodeId(SCOPE)).thenAnswer(i->Fixture.copy(fixture.stored));
            when(merchantAccess.require(ACTOR,MerchantPermission.PROFILE_WRITE)).thenReturn(
                    new MerchantAccessContext(ACTOR,SCOPE,ACTOR,MerchantRoleCode.MERCHANT_OWNER));
            reviewedAudio("APPROVED",ACTOR);
            AppMediaReferenceGuard guard=new AppMediaReferenceGuard();ReflectionTestUtils.setField(guard,"mapper",assets);
            ReflectionTestUtils.setField(guard,"assets",mock(AppMediaAssetService.class));
            merchant=new ApiMerchantController(){@Override public Long getAppUserId(){return ACTOR;}};
            ReflectionTestUtils.setField(merchant,"merchantAccessService",merchantAccess);
            ReflectionTestUtils.setField(merchant,"npcProfileMapper",fixture.profiles);
            ReflectionTestUtils.setField(merchant,"consentGuard",consent);
            ReflectionTestUtils.setField(merchant,"voiceEnrollmentService",fixture.service);
            ReflectionTestUtils.setField(merchant,"mediaReferences",guard);
            node=new NodeNpcService(nodeAccess,fixture.profiles,mock(IWxContentSecurityService.class),fixture.provider);
            ReflectionTestUtils.setField(node,"consentGuard",consent);
            ReflectionTestUtils.setField(node,"voiceEnrollmentService",fixture.service);
            ReflectionTestUtils.setField(node,"mediaReferences",guard);
        }
        void reviewedAudio(String review,Long uploader) {
            Map<String,Object> fact=Map.of("id",73L,"memberId",uploader,"ownerType","APP","storageType","OSS",
                    "objectKey",OBJECT_PATH,"mediaType","AUDIO","reviewState",review);
            when(assets.select(73L)).thenReturn(fact);when(assets.selectByObjectKey(OBJECT_PATH)).thenReturn(fact);
        }
        String enroll() {
            if(!isMerchant)return node.voiceEnroll(ACTOR,ACTOR,SCOPE,SAMPLE).getVoiceIntentState();
            NpcProfile input=new NpcProfile();input.setVoiceSample(SAMPLE);input.setVoiceConsent(true);
            AjaxResult response=merchant.npcVoiceEnroll(input);assertEquals(200,response.get("code"));
            return (String)response.get("voiceIntentState");
        }
        String status() {
            if(!isMerchant)return node.voiceStatus(ACTOR,SCOPE).getVoiceIntentState();
            AjaxResult response=merchant.npcVoiceStatus();assertEquals(200,response.get("code"));return (String)response.get("voiceIntentState");
        }
    }

    @Test void lostEnrollmentResponseReplaysOriginalUnknownThroughBothRealCallers() {
        try(MockedStatic<FileOssUploadUtils> signatures=mockStatic(FileOssUploadUtils.class)) {
            signatures.when(()->FileOssUploadUtils.generateShortLivedPresignedUrl(OBJECT_PATH,300)).thenReturn(PRIVATE_INPUT);
            for(boolean merchant:new boolean[]{true,false}) {
                Caller caller=new Caller(merchant);doThrow(new AiServiceException("response lost")).when(caller.fixture.provider).enroll(PRIVATE_INPUT);
                assertEquals("UNKNOWN",caller.enroll());Long identity=caller.fixture.row().getId();
                assertEquals("UNKNOWN",caller.enroll());assertEquals("UNKNOWN",caller.status());
                assertEquals(identity,caller.fixture.row().getId());assertEquals(SAMPLE,caller.fixture.stored.getVoiceSample());
                assertEquals(1,caller.fixture.stored.getVoiceStatus());verify(caller.fixture.provider,times(1)).enroll(PRIVATE_INPUT);
                verify(caller.fixture.provider,never()).isReady(anyString());
                verify(caller.consent,times(2)).requireCurrentAgreement(ACTOR,"voice_clone_processing","merchant_voice");
                if(!merchant){verify(caller.nodeAccess,times(2)).requireOwnLiveNode(ACTOR,SCOPE);verify(caller.nodeAccess).requireOwnNode(ACTOR,SCOPE);}
            }
        }
    }

    @Test void queryFailureKeepsKnownOriginalIdAndNeitherCallerRebuilds() {
        try(MockedStatic<FileOssUploadUtils> signatures=mockStatic(FileOssUploadUtils.class)) {
            signatures.when(()->FileOssUploadUtils.generateShortLivedPresignedUrl(OBJECT_PATH,300)).thenReturn(PRIVATE_INPUT);
            for(boolean merchant:new boolean[]{true,false}) {
                Caller caller=new Caller(merchant);doThrow(new AiServiceException("query lost")).when(caller.fixture.provider).isReady("voice-original");
                assertEquals("PENDING",caller.enroll());assertEquals("PENDING",caller.status());assertEquals("PENDING",caller.enroll());
                assertEquals("voice-original",caller.fixture.row().getVoiceId());assertEquals("voice-original",caller.fixture.stored.getVoiceId());
                verify(caller.fixture.provider,times(1)).enroll(PRIVATE_INPUT);verify(caller.fixture.provider,times(2)).isReady("voice-original");
            }
        }
    }

    @Test void reviewedButForeignAudioCannotClaimOrCallProviderThroughEitherCaller() {
        for(boolean merchant:new boolean[]{true,false}) {
            Caller caller=new Caller(merchant);caller.reviewedAudio("APPROVED",99L);
            ServiceException rejected=assertThrows(ServiceException.class,caller::enroll);assertEquals(403,rejected.getCode());
            assertTrue(caller.fixture.rows.isEmpty());assertEquals(0L,caller.fixture.stored.getVoiceVersion());
            verify(caller.fixture.provider,never()).enroll(anyString());verify(caller.fixture.intents,never()).insert(any());
        }
    }

    @Test void missingConsentAndHeldAudioRemainZeroEffectAtOriginalCallers() {
        for(boolean merchant:new boolean[]{true,false}) {
            Caller caller=new Caller(merchant);doThrow(new ServiceException("consent required",403)).when(caller.consent)
                    .requireCurrentAgreement(ACTOR,"voice_clone_processing","merchant_voice");
            assertThrows(ServiceException.class,caller::enroll);verifyNoInteractions(caller.assets,caller.fixture.intents);
            assertTrue(caller.fixture.rows.isEmpty());verify(caller.fixture.provider,never()).enroll(anyString());
            Caller held=new Caller(merchant);held.reviewedAudio("HOLD",ACTOR);
            assertEquals(422,assertThrows(ServiceException.class,held::enroll).getCode());
            verifyNoInteractions(held.fixture.intents);verify(held.fixture.provider,never()).enroll(anyString());
        }
    }
    @Test void explicitIndependentRequestKeepsOriginalThroughActualMediaAndCallerBoundaries() {
        try(MockedStatic<FileOssUploadUtils> signatures=mockStatic(FileOssUploadUtils.class)) {
            signatures.when(()->FileOssUploadUtils.generateShortLivedPresignedUrl(OBJECT_PATH,300)).thenReturn(PRIVATE_INPUT);
            for(boolean merchant:new boolean[]{true,false}) {
                Caller caller=new Caller(merchant);
                doThrow(new AiServiceException("unknown")).when(caller.fixture.provider).enroll(PRIVATE_INPUT);
                assertEquals("UNKNOWN",caller.enroll());Long original=caller.fixture.row().getId();
                String request="00000000-0000-4000-8000-000000000003";
                NpcProfile input=new NpcProfile();input.setVoiceSample(SAMPLE);input.setVoiceConsent(true);
                input.setVoiceRequestId(request);input.setVoicePreviousIntentId(original);input.setVoiceDuplicateChargeConsent(false);
                if(merchant)assertEquals(500,caller.merchant.npcVoiceEnroll(input).get("code"));
                else assertThrows(com.chengyinhub.business.service.ai.NpcVoiceEnrollmentService.Rejected.class,
                        ()->caller.node.voiceEnroll(ACTOR,ACTOR,SCOPE,SAMPLE,request,original,false));
                verify(caller.fixture.provider,times(1)).enroll(PRIVATE_INPUT);
                doReturn("voice-confirmed-new").when(caller.fixture.provider).enroll(PRIVATE_INPUT);
                input.setVoiceDuplicateChargeConsent(true);
                if(merchant) {
                    AjaxResult reply=caller.merchant.npcVoiceEnroll(input);
                    assertEquals(200,reply.get("code"));assertEquals(request,reply.get("voiceRequestId"));
                } else assertEquals(request,caller.node.voiceEnroll(ACTOR,ACTOR,SCOPE,SAMPLE,request,original,true).getVoiceRequestId());
                assertEquals("UNKNOWN",caller.fixture.row().getState());assertEquals(original,caller.fixture.row().getId());
                assertEquals(2,caller.fixture.rows.size());verify(caller.fixture.provider,times(2)).enroll(PRIVATE_INPUT);
                if(merchant)verify(caller.merchantAccess,atLeastOnce()).require(ACTOR,MerchantPermission.PROFILE_WRITE);
                else verify(caller.nodeAccess,atLeastOnce()).requireOwnLiveNode(ACTOR,SCOPE);
            }
        }
    }

}
