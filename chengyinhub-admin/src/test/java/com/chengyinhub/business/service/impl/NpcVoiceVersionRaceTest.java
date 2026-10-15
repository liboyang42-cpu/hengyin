package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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
    private static final String SAMPLE = "/profile/race.mp3";

    private static class Harness {
        final boolean merchant;
        final NpcProfileMapper mapper = mock(NpcProfileMapper.class);
        final VoiceCloneClient voice = mock(VoiceCloneClient.class);
        final AtomicInteger enrollCalls = new AtomicInteger();
        final NodeNpcService node;
        final ApiMerchantController controller;
        NpcProfile stored = new NpcProfile();
        Runnable onRead, onEnroll, onReady;

        Harness(boolean merchant, int status, String voiceId) {
            this.merchant = merchant;
            stored.setId(555L); stored.setScopeId(31L); stored.setScopeType(merchant ? 2 : 4);
            stored.setVoiceStatus(status); stored.setVoiceId(voiceId); stored.setVoiceSample(SAMPLE);
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
            node = new NodeNpcService(mock(IChapterMerchantNodeService.class), mapper,
                    mock(IWxContentSecurityService.class), voice);
            controller = new ApiMerchantController() {
                @Override public Long getAppUserId() { return 88L; }
            };
            IMerchantAccessService access = mock(IMerchantAccessService.class);
            when(access.require(88L, MerchantPermission.PROFILE_WRITE)).thenReturn(
                    new MerchantAccessContext(88L, 31L, 88L, MerchantRoleCode.MERCHANT_OWNER));
            ReflectionTestUtils.setField(controller, "merchantAccessService", access);
            ReflectionTestUtils.setField(controller, "npcProfileMapper", mapper);
            ReflectionTestUtils.setField(controller, "voiceCloneClient", voice);
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

        void enroll() {
            if (!merchant) { node.voiceEnroll(88L, 31L, SAMPLE); return; }
            NpcProfile body = new NpcProfile(); body.setVoiceSample(SAMPLE); body.setVoiceConsent(true);
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
            assertEquals(2, h.stored.getVoiceStatus()); assertEquals(2L, h.stored.getVoiceVersion());
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
            assertThrows(IllegalStateException.class, h::enroll);
            assertEquals(0, h.stored.getVoiceStatus()); assertNull(h.stored.getVoiceId());
        }
    }

    @Test void oldResultCannotOverwriteAReplacementUsingTheSameSample() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null); h.onEnroll = () -> { h.reset(); h.enroll(); };
            assertThrows(IllegalStateException.class, h::enroll);
            assertEquals("voice-2", h.stored.getVoiceId()); assertEquals(2, h.stored.getVoiceStatus());
        }
    }

    @Test void oldFailureCannotClearTheNewSuccessfulVoice() {
        for (boolean merchant : new boolean[]{false, true}) {
            Harness h = new Harness(merchant, 0, null);
            h.onEnroll = () -> { h.reset(); h.enroll(); throw new AiServiceException("old failure"); };
            assertThrows(IllegalStateException.class, h::enroll);
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
}
