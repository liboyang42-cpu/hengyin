package com.chengyinhub.web.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.ai.impl.E2eTestSpeechToTextService;
import com.chengyinhub.business.service.impl.E2eTestNpcDialogueResponder;
import com.chengyinhub.business.service.channel.media.E2eTestImMediaStore;
import com.chengyinhub.business.service.impl.E2eTestContentSecurityService;
import com.chengyinhub.business.service.impl.E2eTestWeChatMiniSessionService;
import com.chengyinhub.business.service.impl.E2eTestWxCodeGateway;
import com.chengyinhub.business.service.impl.E2eTestWxCodeStorage;
import com.chengyinhub.framework.web.service.E2eTestSmsCodeSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

/** TEST-only providers must stay behind the e2e-test profile plus an explicit opt-in property. */
class E2eTestProvidersProfileTest
{
    @Test
    void testProvidersAreGatedByProfileAndProperty()
    {
        for (Class<?> type : new Class<?>[] {
                E2eTestSmsCodeSender.class,
                E2eTestContentSecurityService.class,
                E2eTestWxCodeGateway.class,
                E2eTestWxCodeStorage.class,
                E2eTestImMediaStore.class,
                E2eTestSpeechToTextService.class,
                E2eTestWeChatMiniSessionService.class,
                E2eTestNpcDialogueResponder.class,
        })
        {
            Profile profile = type.getAnnotation(Profile.class);
            assertNotNull(profile, type.getSimpleName());
            assertArrayEquals(new String[] {"e2e-test"}, profile.value());
            ConditionalOnProperty property = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(property, type.getSimpleName());
            assertArrayEquals(new String[] {"chengyin.e2e.test-providers"}, property.name());
            assertEquals("true", property.havingValue());
        }
    }

    @Test
    void defaultProfileDoesNotActivateE2eTest() throws Exception
    {
        try (InputStream in = getClass().getResourceAsStream("/application.yml"))
        {
            assertNotNull(in);
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(yml.contains("e2e-test"));
            assertFalse(yml.contains("chengyin.e2e") || yml.contains("test-providers"));
        }
    }

    @Test
    void passThroughModerationAndTestProvidersWorkWithoutExternalKeys()
    {
        E2eTestContentSecurityService service = new E2eTestContentSecurityService();
        assertNull(service.checkText("你好，E2E", "any-openid"));
        assertNull(service.checkText("", null));
        assertFalse(service.canSubmitMedia("openid"));
        assertTrue(service.checkMediaAsync("https://example.invalid/a.jpg", null).startsWith("e2e-test-"));
        assertEquals("e2e-test-access-token", service.getAccessToken());
        assertEquals("e2e-test-access-token", service.refreshAccessToken());
        assertTrue(new E2eTestSmsCodeSender().send(null, null, null, null, "13900000004", "123456"));

        E2eTestSpeechToTextService stt = new E2eTestSpeechToTextService();
        assertTrue(stt.available());
        assertNotNull(stt.transcribe(new byte[] {1, 2, 3}, "wav"));
        assertNull(stt.transcribe(new byte[0], "wav"));

        E2eTestWeChatMiniSessionService mini = new E2eTestWeChatMiniSessionService();
        IWeChatMiniSessionService.WeChatMiniSession session = mini.exchange("e2e-code-9003");
        assertEquals("e2e-openid-9003", session.getOpenId());
    }
}
