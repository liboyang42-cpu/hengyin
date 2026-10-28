package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicKeyRequestDTO;
import com.chengyinhub.business.domain.dto.TopicPlaySetupDTO;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.ThemeAttributeService;
import com.chengyinhub.business.service.support.ThemeKeyNamingService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.business.service.support.TopicPlaySetupService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual author endpoints retain their original admission and service-error response contract. */
class TopicAuthorErrorProjectionTest {
    private static final class Fixture {
        final CmsTopic topic = new CmsTopic();
        final TopicAuthorGate gate = mock(TopicAuthorGate.class);
        final ThemeAttributeService attributes = mock(ThemeAttributeService.class);
        final ThemeKeyNamingService naming = mock(ThemeKeyNamingService.class);
        final TopicPlaySetupService setup = mock(TopicPlaySetupService.class);
        final IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        final ApiTopicAttributeController attributeController = spy(new ApiTopicAttributeController());
        final ApiTopicPlaySetupController setupController = spy(new ApiTopicPlaySetupController());
        final TopicKeyRequestDTO key = new TopicKeyRequestDTO();
        final TopicPlaySetupDTO playSetup = new TopicPlaySetupDTO();

        Fixture(ServiceException failure) {
            topic.setId(7L);
            when(gate.topic(7L)).thenReturn(topic);
            doReturn(11L).when(attributeController).getAppUserId();
            doReturn(11L).when(setupController).getAppUserId();
            ReflectionTestUtils.setField(attributeController, "topicAuthorGate", gate);
            ReflectionTestUtils.setField(attributeController, "themeAttributeService", attributes);
            ReflectionTestUtils.setField(attributeController, "themeKeyNamingService", naming);
            ReflectionTestUtils.setField(attributeController, "wxContentSecurityService", security);
            ReflectionTestUtils.setField(setupController, "topicAuthorGate", gate);
            ReflectionTestUtils.setField(setupController, "topicPlaySetupService", setup);
            ReflectionTestUtils.setField(setupController, "wxContentSecurityService", security);
            key.setTopicId(7L); key.setScope("personal"); key.setKey("counter.attr_1");
            key.setMin(0); key.setMax(5); key.setInitial(1);
            playSetup.setTopicId(7L); playSetup.setScope("personal");
            when(attributes.view(topic, true)).thenThrow(failure);
            when(attributes.updateRange(topic, "counter.attr_1", 0, 5, 1)).thenThrow(failure);
            when(setup.save(eq(topic), any(TopicPlaySetupDTO.class))).thenThrow(failure);
        }

        List<AjaxResult> invokeAll() {
            return List.of(attributeController.attributes(7L, "personal"),
                    attributeController.updateAttribute(key), setupController.save(playSetup));
        }
    }

    @Test
    void bothAuthorControllersPreserveCodedFailureStatusMachineCodeAndData() {
        for (int code : new int[] {409, 520}) {
            Map<String, Object> data = Map.of("state", "UNKNOWN", "requestId", "original");
            Fixture f = new Fixture(new ServiceException("原服务拒绝", code)
                    .setDetailMessage("ORIGINAL_CONFLICT").setData(data));
            for (AjaxResult result : f.invokeAll()) {
                assertEquals(code, result.get("code"));
                assertEquals("原服务拒绝", result.get("msg"));
                assertEquals("ORIGINAL_CONFLICT", result.get("errorCode"));
                assertSame(data, result.get(AjaxResult.DATA_TAG));
            }
            verify(f.attributes).view(f.topic, true);
            verify(f.attributes).updateRange(f.topic, "counter.attr_1", 0, 5, 1);
            verify(f.setup).save(f.topic, f.playSetup);
            verifyNoInteractions(f.security, f.naming);
        }
    }

    @Test
    void noCodeKeepsOriginalLegacyEarlyReturn() {
        Fixture f = new Fixture(new ServiceException("原无代码错误")
                .setDetailMessage("NOT_PROJECTED_WITHOUT_CODE").setData(Map.of("state", "UNKNOWN")));
        for (AjaxResult result : f.invokeAll()) {
            assertEquals(500, result.get("code"));
            assertEquals("原无代码错误", result.get("msg"));
            assertFalse(result.containsKey("errorCode"));
            assertFalse(result.containsKey(AjaxResult.DATA_TAG));
        }
    }

    @Test
    void authorDenialAndFrozenWriteKeepOwnersUncalled() {
        Fixture f = new Fixture(new ServiceException("不应触达"));
        when(f.gate.denial(11L, f.topic, "personal")).thenReturn("原归属拒绝");
        for (AjaxResult result : f.invokeAll()) assertEquals("原归属拒绝", result.get("msg"));
        verifyNoInteractions(f.attributes, f.setup, f.naming, f.security);
        when(f.gate.denial(11L, f.topic, "personal")).thenReturn(null);
        when(f.gate.frozen(f.topic)).thenReturn(true);
        for (AjaxResult result : List.of(f.attributeController.updateAttribute(f.key), f.setupController.save(f.playSetup))) {
            assertEquals(TopicAuthorGate.MSG_FROZEN, result.get("msg"));
            assertEquals(TopicAuthorGate.ERR_FROZEN, result.get("errorCode"));
        }
        verifyNoInteractions(f.attributes, f.setup, f.naming, f.security);
    }
}
