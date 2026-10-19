package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeFeaturePolicyTest {
    @Test void directConstructionIsDisabled() {
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        assertFalse(policy.isEnabled());
        assertThrows(ServiceException.class, policy::requireEnabled);
    }

    @Test void springBindingDefaultsToDisabledWithoutProperty() {
        try (AnnotationConfigApplicationContext context = context(null)) {
            ThemeRuntimeFeaturePolicy policy = context.getBean(ThemeRuntimeFeaturePolicy.class);
            assertFalse(policy.isEnabled());
            assertThrows(ServiceException.class, policy::requireEnabled);
        }
    }

    @Test void springBindingRequiresExplicitTrueAndAllowsThemeChecks() {
        try (AnnotationConfigApplicationContext context = context("true")) {
            ThemeRuntimeFeaturePolicy policy = context.getBean(ThemeRuntimeFeaturePolicy.class);
            assertTrue(policy.isEnabled());
            assertDoesNotThrow(policy::requireEnabled);
            assertDoesNotThrow(() -> policy.requireConfiguredEnabled("{\"schemaVersion\":1}"));
            assertDoesNotThrow(() -> policy.requireSessionEnabled(session("{\"themeConfig\":{\"schemaVersion\":1}}")));
        }
    }

    @Test void springExplicitFalseKeepsConfiguredAndFrozenThemeDisabled() {
        try (AnnotationConfigApplicationContext context = context("false")) {
            ThemeRuntimeFeaturePolicy policy = context.getBean(ThemeRuntimeFeaturePolicy.class);
            assertFalse(policy.isEnabled());
            assertThrows(ServiceException.class, () -> policy.requireConfiguredEnabled("{}"));
            assertThrows(ServiceException.class, () -> policy.requireSessionEnabled(session("{\"themeConfig\":{}}")));
        }
    }

    @Test void disabledPolicyLeavesUnconfiguredLegacyInputsAlone() {
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        for (String raw : new String[] { null, "", " \n\t" }) {
            assertFalse(ThemeRuntimeFeaturePolicy.isConfigured(raw));
            assertDoesNotThrow(() -> policy.requireConfiguredEnabled(raw));
            assertDoesNotThrow(() -> policy.requireSessionEnabled(session(raw)));
        }
        assertDoesNotThrow(() -> policy.requireSessionEnabled(null));
        assertDoesNotThrow(() -> policy.requireSessionEnabled(session("{\"journeyRules\":{},\"nodeConfigs\":{}}")));
    }

    @Test void invalidNewConfigurationCannotBypassDisabledGate() {
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        for (String raw : new String[] { "null", "{}", "[]", "false", "{broken" }) {
            assertTrue(ThemeRuntimeFeaturePolicy.isConfigured(raw));
            assertThrows(ServiceException.class, () -> policy.requireConfiguredEnabled(raw));
        }
    }

    @Test void frozenDetectionIgnoresPlayInstanceNameAndLiveState() {
        TopicRouteSession themeDefault = session("{\"themeConfig\":{\"schemaVersion\":1}}");
        themeDefault.setPlayInstanceId("default");
        assertTrue(ThemeRuntimeFeaturePolicy.hasFrozenTheme(themeDefault));
        TopicRouteSession legacy = session("{\"journeyRules\":{}}");
        legacy.setPlayInstanceId("theme:untrusted-name");
        legacy.setStateJson("{\"themeRuntime\":{\"inventory\":{\"item\":1}}}");
        assertFalse(ThemeRuntimeFeaturePolicy.hasFrozenTheme(legacy));
    }

    @Test void corruptNonemptySnapshotsFailClosedWhetherEnabledOrDisabled() {
        try (AnnotationConfigApplicationContext context = context("true")) {
            ThemeRuntimeFeaturePolicy enabled = context.getBean(ThemeRuntimeFeaturePolicy.class);
            ThemeRuntimeFeaturePolicy disabled = new ThemeRuntimeFeaturePolicy();
            for (String raw : new String[] { "{broken", "null", "[]", "42", "\"text\"",
                    "{\"themeConfig\":null}", "{\"themeConfig\":false}", "{\"themeConfig\":[]}" }) {
                TopicRouteSession session = session(raw);
                assertThrows(ServiceException.class, () -> ThemeRuntimeFeaturePolicy.hasFrozenTheme(session), raw);
                assertThrows(ServiceException.class, () -> disabled.requireSessionEnabled(session), raw);
                assertThrows(ServiceException.class, () -> enabled.requireSessionEnabled(session), raw);
            }
        }
    }

    @Test void completedFrozenSessionDoesNotBecomeLegacyWhenSwitchIsOff() {
        TopicRouteSession session = session("{\"themeConfig\":{\"schemaVersion\":1}}");
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertThrows(ServiceException.class, () -> new ThemeRuntimeFeaturePolicy().requireSessionEnabled(session));
    }

    @Test void trailingGarbageAndSecondJsonRootCannotHideFrozenTheme() {
        try (AnnotationConfigApplicationContext context = context("true")) {
            ThemeRuntimeFeaturePolicy enabled = context.getBean(ThemeRuntimeFeaturePolicy.class);
            ThemeRuntimeFeaturePolicy disabled = new ThemeRuntimeFeaturePolicy();
            for (String raw : new String[] { "{\"journeyRules\":{}}garbage",
                    "{\"journeyRules\":{}}{\"themeConfig\":{}}",
                    "{\"journeyRules\":{}} {\"themeConfig\":{}}",
                    "{\"themeConfig\":{}} trailing", "{\"themeConfig\":{}} null" }) {
                TopicRouteSession session = session(raw);
                assertThrows(ServiceException.class, () -> ThemeRuntimeFeaturePolicy.hasFrozenTheme(session), raw);
                assertThrows(ServiceException.class, () -> disabled.requireSessionEnabled(session), raw);
                assertThrows(ServiceException.class, () -> enabled.requireSessionEnabled(session), raw);
            }
        }
    }

    @Test void legalTrailingWhitespaceKeepsLegacySnapshotBytesAndBehavior() {
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        for (String raw : new String[] { "{}", "{\"journeyRules\":{},\"nodeConfigs\":{}}",
                "{\"journeyRules\":{},\"nodeConfigs\":{}} \t\r\n" }) {
            TopicRouteSession session = session(raw);
            assertFalse(ThemeRuntimeFeaturePolicy.hasFrozenTheme(session));
            assertDoesNotThrow(() -> policy.requireSessionEnabled(session));
            assertEquals(raw, session.getConfigSnapshotJson());
        }
        assertTrue(ThemeRuntimeFeaturePolicy.hasFrozenTheme(session("{\"themeConfig\":{}} \t\r\n")));
    }

    @Test void policyChecksDoNotChangeSnapshotStateOrVersion() {
        try (AnnotationConfigApplicationContext context = context("true")) {
            TopicRouteSession session = session("{\"themeConfig\":{\"schemaVersion\":1}}");
            session.setStateJson("{\"themeRuntime\":{\"inventory\":{}}}");
            session.setVersion(7);
            String snapshot = session.getConfigSnapshotJson();
            String state = session.getStateJson();
            context.getBean(ThemeRuntimeFeaturePolicy.class).requireSessionEnabled(session);
            assertEquals(snapshot, session.getConfigSnapshotJson());
            assertEquals(state, session.getStateJson());
            assertEquals(Integer.valueOf(7), session.getVersion());
        }
    }

    private static AnnotationConfigApplicationContext context(String enabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 环境完全受本测试控制，不能被开发机环境变量意外打开。
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        if (enabled != null) context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "theme-test", Collections.<String, Object>singletonMap("chengyin.theme-runtime.enabled", enabled)));
        context.register(ThemeRuntimeFeaturePolicy.class);
        context.refresh();
        return context;
    }

    private static TopicRouteSession session(String snapshot) {
        TopicRouteSession session = new TopicRouteSession();
        session.setConfigSnapshotJson(snapshot);
        return session;
    }
}
