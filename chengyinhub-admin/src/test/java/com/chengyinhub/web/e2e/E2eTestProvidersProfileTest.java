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
import com.chengyinhub.business.service.channel.media.E2eTestImMediaStore;
import com.chengyinhub.business.service.impl.E2eTestContentSecurityService;
import com.chengyinhub.business.service.impl.E2eTestWeChatMiniSessionService;
import com.chengyinhub.business.service.impl.E2eTestWxCodeGateway;
import com.chengyinhub.business.service.impl.E2eTestWxCodeStorage;
import com.chengyinhub.business.service.support.rpg.E2eTestTopicWeatherProvider;
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
                com.chengyinhub.business.merchantgap.E2eTestMerchantAvatarProvider.class,
                E2eTestTopicWeatherProvider.class,
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

    /**
     * R9 BE-16: the providers live in the chengyinhub-e2e module, which chengyinhub-admin only takes with test scope
     * (runtime only under the explicit `e2e-test-providers` Maven profile). No production module may carry one.
     */
    @Test
    void productionModulesCarryNoE2eProviders() throws Exception
    {
        java.nio.file.Path root = java.nio.file.Paths.get("").toAbsolutePath();
        if (!java.nio.file.Files.isDirectory(root.resolve("../chengyinhub-system"))) return; // not run from the reactor
        for (String module : new String[] {"chengyinhub-admin", "chengyinhub-framework", "chengyinhub-system",
                "chengyinhub-common", "chengyinhub-quartz", "chengyinhub-generator"})
        {
            java.nio.file.Path main = root.resolve("../" + module + "/src/main").normalize();
            if (!java.nio.file.Files.isDirectory(main)) continue;
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(main))
            {
                java.util.List<String> offenders = files.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> {
                            try
                            {
                                String src = new String(java.nio.file.Files.readAllBytes(f), StandardCharsets.UTF_8);
                                return f.getFileName().toString().startsWith("E2eTest") || src.contains("@Profile(\"e2e-test\")");
                            }
                            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                        })
                        .map(java.nio.file.Path::toString).collect(java.util.stream.Collectors.toList());
                assertTrue(offenders.isEmpty(), module + " ships E2E providers: " + offenders);
            }
        }
        String adminPom = new String(java.nio.file.Files.readAllBytes(root.resolve("pom.xml")), StandardCharsets.UTF_8);
        assertTrue(adminPom.contains("<chengyin.e2e.providers.scope>test</chengyin.e2e.providers.scope>"),
                "chengyinhub-e2e must default to test scope");
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

        E2eTestTopicWeatherProvider weather = new E2eTestTopicWeatherProvider("");
        assertEquals("rain", weather.currentWeather("雨城"));
        assertEquals("clear", weather.currentWeather("杭州"));
        assertNull(weather.currentWeather(null));
        assertEquals("fog", new E2eTestTopicWeatherProvider("fog").currentWeather("杭州"));
    }
}
