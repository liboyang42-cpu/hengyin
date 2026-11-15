package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.impl.E2eTestWeChatMiniSessionService;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** B4(acceptance-r3):e2e-test profile 下 /api/login/code 的 e2e-code-* 走 TEST-only 会话桩,不打微信。 */
class ApiLoginE2eSessionStubTest {

    private final AtomicInteger httpCalls = new AtomicInteger();

    private ApiLoginController controller(E2eTestWeChatMiniSessionService stub) {
        ApiLoginController controller = new ApiLoginController() {
            @Override protected String sendHttpGet(String url) {
                httpCalls.incrementAndGet();
                throw new RuntimeException("network blocked in test");
            }
        };
        ReflectionTestUtils.setField(controller, "configService", mock(ISysConfigService.class));
        ReflectionTestUtils.setField(controller, "e2eTestMiniSession", stub);
        return controller;
    }

    @Test
    void e2eCodeUsesTheTestOnlyStubWhenItIsLoaded() {
        assertEquals("e2e-openid-9004", controller(new E2eTestWeChatMiniSessionService()).getOpenID("e2e-code-9004"));
        assertEquals(0, httpCalls.get(), "e2e 桩不发真实微信请求");
    }

    @Test
    void withoutTheStubBeanE2eCodesStillGoToWeChat() {
        assertThrows(RuntimeException.class, () -> controller(null).getOpenID("e2e-code-9004"));
        assertEquals(1, httpCalls.get(), "默认/生产 profile 没有桩 bean,行为不变");
    }

    @Test
    void ordinaryCodesNeverUseTheStub() {
        assertThrows(RuntimeException.class, () -> controller(new E2eTestWeChatMiniSessionService()).getOpenID("real-code"));
        assertEquals(1, httpCalls.get());
    }
}
