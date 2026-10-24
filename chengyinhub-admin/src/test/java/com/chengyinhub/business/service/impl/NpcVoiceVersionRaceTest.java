package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.Map;
import java.util.LinkedHashMap;
import org.mockito.MockedStatic;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiMerchantController;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Detached reads and interleaved callbacks; MyBatis IT separately verifies the version predicate. */
class NpcVoiceVersionRaceTest {
    private static final String SAMPLE = "media-asset:12";
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.mp3";
    private static final String APPROVED_INPUT = "https://media.invalid/"+KEY+"?expires=300";
    MockedStatic<FileOssUploadUtils> oss;
    @BeforeEach void privateProviderInput() {
        oss=mockStatic(FileOssUploadUtils.class);
        oss.when(()->FileOssUploadUtils.generateShortLivedPresignedUrl(KEY,300)).thenReturn(APPROVED_INPUT);
    }
    @AfterEach void closeProviderInput() {oss.close();}

    private static class Harness {
        final boolean merchant;
        final NpcProfileMapper mapper = mock(NpcProfileMapper.class);
        final VoiceCloneClient voice = mock(VoiceCloneClient.class);
        final AppMediaAssetMapper mediaMapper=mock(AppMediaAssetMapper.class);
        final Map<String,Object> approvedAudio=new LinkedHashMap<>();
        final com.chengyinhub.business.service.ConsentGuard consent=mock(com.chengyinhub.business.service.ConsentGuard.class);
        final IChapterMerchantNodeService nodeAccess=mock(IChapterMerchantNodeService.class);
        final AtomicInteger enrollCalls = new AtomicInteger();
        final NodeNpcService node;
        final com.chengyinhub.business.service.ai.NpcVoiceEnrollmentFixture enrollment;
        final ApiMerchantController controller;
        NpcProfile stored = new NpcProfile();
        Runnable onRead, onEnroll, onReady;

        Harness(boolean merchant, int status, String voiceId) {
            this.merchant = merchant;
            stored.setId(555L); stored.setScopeId(31L); stored.setScopeType(merchant ? 2 : 4);
            stored.setVoiceStatus(status); stored.setVoiceId(voiceId);
            if(status!=0 || voiceId!=null) stored.setVoiceSample(SAMPLE);
            when(mapper.selectNodeNpcByNodeId(31L)).thenAnswer(i -> read());
            when(mapper.selectMerchantNpcList(31L, null)).thenAnswer(i -> Collections.singletonList(read()));
            when(mapper.updateNodeNpcVoice(any())).thenAnswer(i -> update(i.getArgument(0)));
            when(mapper.updateMerchantNpcVoice(any())).thenAnswer(i -> update(i.getArgument(0)));
            when(voice.usable()).thenReturn(true);
            when(voice.enroll(anyString())).thenAnswer(i -> {
                String result = "voice-" + enrollCalls.incrementAndGet();
                Runnable action = onEnroll; onEnroll = null;
                if (action != null) action.run();
                return result;
            });
            when(voice.isReady(anyString())).thenAnswer(i -> {
                Runnable action = onReady; onReady = null;
                if (action != null) action.run();
                return true;
            });
            approvedAudio.put("id",12L);approvedAudio.put("memberId",88L);approvedAudio.put("ownerType","APP");
            approvedAudio.put("storageType","OSS");approvedAudio.put("objectKey",KEY);approvedAudio.put("mediaType","AUDIO");approvedAudio.put("reviewState","APPROVED");
            when(mediaMapper.select(12L)).thenReturn(approvedAudio);when(mediaMapper.selectByObjectKey(KEY)).thenReturn(approvedAudio);
            AppMediaReferenceGuard references=new AppMediaReferenceGuard();ReflectionTestUtils.setField(references,"mapper",mediaMapper);
            ReflectionTestUtils.setField(references,"assets",mock(AppMediaAssetService.class));
            node = new NodeNpcService(nodeAccess, mapper,
                    mock(IWxContentSecurityService.class), voice);
            controller = new ApiMerchantController() {
                @Override public Long getAppUserId() { return 88L; }
            };
            ReflectionTestUtils.setField(node, "consentGuard", consent);
            ReflectionTestUtils.setField(controller, "consentGuard", consent);
            ReflectionTestUtils.setField(node, "mediaReferences", references);
            ReflectionTestUtils.setField(controller, "mediaReferences", references);
            IMerchantAccessService access = mock(IMerchantAccessService.class);
            when(access.require(88L, MerchantPermission.PROFILE_WRITE)).thenReturn(
                    new MerchantAccessContext(88L, 31L, 88L, MerchantRoleCode.MERCHANT_OWNER));
            ReflectionTestUtils.setField(controller, "merchantAccessService", access);
            ReflectionTestUtils.setField(controller, "npcProfileMapper", mapper);
            ReflectionTestUtils.setField(controller, "voiceCloneClient", voice);
            enrollment=new com.chengyinhub.business.service.ai.NpcVoiceEnrollmentFixture(
                    mapper,voice,merchant?2:4,31L,this::read);
            ReflectionTestUtils.setField(node,"voiceEnrollmentService",enrollment.service);
            ReflectionTestUtils.setField(controller,"voiceEnrollmentService",enrollment.service);
        }

        NpcProfile read() {
            NpcProfile copy = new NpcProfile();
            copy.setId(stored.getId()); copy.setScopeId(stored.getScopeId()); copy.setScopeType(stored.getScopeType());
            copy.setVoiceVersion(stored.getVoiceVersion()); copy.setVoiceStatus(stored.getVoiceStatus());
            copy.setVoiceId(stored.getVoiceId()); copy.setVoiceSample(stored.getVoiceSample());
            Runnable action = onRead; onRead = null;
            if (action != null) action.run();
            return copy;
        }

        int update(NpcProfile change) {
            if (!stored.getVoiceVersion().equals(change.getVoiceVersion())) return 0;
            stored.setVoiceVersion(stored.getVoiceVersion() + 1);
            stored.setVoiceId(change.getVoiceId());
            if (change.getVoiceStatus() != null) stored.setVoiceStatus(change.getVoiceStatus());
            if (change.getVoiceSample() != null) stored.setVoiceSample(change.getVoiceSample());
            return 1;
        }

        void enroll() {enroll(SAMPLE);}
        void enroll(String sample) {
            if (!merchant) { node.voiceEnroll(88L, 88L, 31L, sample); return; }
            NpcProfile body = new NpcProfile(); body.setVoiceSample(sample); body.setVoiceConsent(true);
            requireSuccess(controller.npcVoiceEnroll(body));
        }

        void reset() {
            if (merchant) requireSuccess(controller.npcVoiceReset()); else node.voiceReset(88L, 31L);
        }

        int status() {
            if (!merchant) return node.voiceStatus(88L, 31L).getVoiceStatus();
            AjaxResult response = controller.npcVoiceStatus(); requireSuccess(response);
            return (Integer) response.get("voiceStatus");
        }

        void requireSuccess(AjaxResult result) {
            if (!Integer.valueOf(200).equals(result.get(AjaxResult.CODE_TAG)))
                throw new IllegalStateException(String.valueOf(result.get(AjaxResult.MSG_TAG)));
        }
    }

    @Test void normalEnrollResetAndPollRemainUsable() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null); h.enroll();
            assertEquals(2, h.stored.getVoiceStatus()); assertEquals(3L, h.stored.getVoiceVersion());
            assertEquals(1, h.enrollCalls.get()); h.reset();
            assertEquals(0, h.status()); assertNull(h.stored.getVoiceId());
            h = new Harness(merchant, 1, "old"); assertEquals(2, h.status());
        }
    }

    @Test void oldReadyPollCannotResurrectAResetVoice() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 1, "old"); h.onReady = h::reset;
            assertEquals(0, h.status()); assertNull(h.stored.getVoiceId());
        }
    }

    @Test void oldEnrollmentCannotResurrectAResetVoice() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null); h.onEnroll = h::reset;
            h.enroll();
            assertEquals(0, h.stored.getVoiceStatus()); assertNull(h.stored.getVoiceId());
            assertEquals("READY",h.enrollment.state(555L,SAMPLE));
            assertEquals(1,h.enrollCalls.get());
        }
    }

    @Test void oldResultCannotOverwriteAReplacementUsingTheSameSample() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null);
            // Adversarial committed profile fixture, NOT a second permitted provider creation.
            h.onEnroll = () -> { h.reset(); h.stored.setVoiceId("voice-2");
                h.stored.setVoiceStatus(2); h.stored.setVoiceVersion(h.stored.getVoiceVersion()+1); };
            h.enroll();
            assertEquals(1,h.enrollCalls.get());
            assertEquals("voice-2", h.stored.getVoiceId()); assertEquals(2, h.stored.getVoiceStatus());
        }
    }

    @Test void oldFailureCannotClearTheNewSuccessfulVoice() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null);
            // Existing replacement fact is an adversarial DB fixture, not an authorized extra charge.
            h.onEnroll = () -> { h.reset(); h.stored.setVoiceId("voice-2");
                h.stored.setVoiceStatus(2); h.stored.setVoiceVersion(h.stored.getVoiceVersion()+1);
                throw new AiServiceException("old failure"); };
            h.enroll();
            assertEquals("UNKNOWN",h.enrollment.state(555L,SAMPLE));
            assertEquals(1,h.enrollCalls.get());
            assertEquals("voice-2", h.stored.getVoiceId()); assertEquals(2, h.stored.getVoiceStatus());
        }
    }

    @Test void concurrentSameSampleRequestsEnrollOnlyOnce() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null); h.onRead = h::enroll; h.enroll();
            assertEquals(1, h.enrollCalls.get()); assertEquals("voice-1", h.stored.getVoiceId());
        }
    }

    @Test void failedReadinessStillReturnsTheResetState() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 1, "old");
            h.onReady = () -> { h.reset(); throw new AiServiceException("poll unavailable"); };
            assertEquals(0, h.status()); assertNull(h.stored.getVoiceId());
        }
    }
    @Test void realApprovedMediaGateProducesOnlyPrivateProviderUrlAndChecksSeparateConsent() {
        for(boolean merchant:new boolean[]{false,true}) {
            Harness h=new Harness(merchant,0,null);h.enroll(APPROVED_INPUT);
            verify(h.voice).enroll(APPROVED_INPUT);
            verify(h.consent).requireCurrentAgreement(88L,"voice_clone_processing","merchant_voice");
            if(!merchant)verify(h.nodeAccess).requireOwnLiveNode(88L,31L);
            assertEquals(SAMPLE,h.stored.getVoiceSample());
        }
    }
    @Test void renewedApprovedUrlReusesReadyOrPendingCanonicalVoiceWithoutSecondProviderCall() {
        for(boolean merchant:new boolean[]{false,true})for(int state:new int[]{1,2}) {
            Harness h=new Harness(merchant,state,"existing");h.enroll(APPROVED_INPUT);
            assertEquals(0,h.enrollCalls.get());assertEquals(state,h.stored.getVoiceStatus());assertEquals("existing",h.stored.getVoiceId());
        }
    }
    @Test void unknownHoldForeignHoldAndWrongTypeCannotReachProviderOrVoiceCas() {
        for(boolean merchant:new boolean[]{false,true})for(String rejection:new String[]{"UNKNOWN","HOLD","FOREIGN_HOLD","WRONG_TYPE"}) {
            Harness h=new Harness(merchant,0,null);
            if("HOLD".equals(rejection)||"FOREIGN_HOLD".equals(rejection))h.approvedAudio.put("reviewState","HOLD");
            if("FOREIGN_HOLD".equals(rejection))h.approvedAudio.put("memberId",99L);
            if("WRONG_TYPE".equals(rejection))h.approvedAudio.put("mediaType","IMAGE");
            assertThrows(ServiceException.class,()->h.enroll("UNKNOWN".equals(rejection)?"media-asset:999":SAMPLE));
            assertEquals(0,h.enrollCalls.get());assertEquals(0,h.stored.getVoiceStatus());assertEquals(0L,h.stored.getVoiceVersion());
            verify(h.mapper,never()).updateNodeNpcVoice(any());verify(h.mapper,never()).updateMerchantNpcVoice(any());
        }
    }
    @Test void missingSeparateVoiceConsentStopsBeforeMediaAndProvider() {
        for(boolean merchant:new boolean[]{false,true}) {
            Harness h=new Harness(merchant,0,null);
            doThrow(new ServiceException("voice consent required")).when(h.consent).requireCurrentAgreement(88L,"voice_clone_processing","merchant_voice");
            assertThrows(ServiceException.class,h::enroll);verifyNoInteractions(h.mediaMapper);assertEquals(0,h.enrollCalls.get());
        }
    }
}
