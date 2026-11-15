package com.chengyinhub.business.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * TEST-only mini-program session exchange for explicit {@code e2e-code-<suffix>} test codes
 * (mapping deterministically to {@code e2e-openid-<suffix>}); arbitrary codes continue to hit the real path
 * so unconfigured mini-login error paths remain testable.
 * Loaded only with profile {@code e2e-test} AND {@code chengyin.e2e.test-providers=true}.
 */
@Service
@Primary
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestWeChatMiniSessionService extends WeChatMiniSessionServiceImpl
{
    private static final Logger log = LoggerFactory.getLogger(E2eTestWeChatMiniSessionService.class);
    private static final String CODE_PREFIX = "e2e-code-";

    public E2eTestWeChatMiniSessionService()
    {
        log.warn("[e2e-test] TEST-only WeChat mini session exchange active for e2e-code-* test codes");
    }

    @Override
    public WeChatMiniSession exchange(String jsCode)
    {
        if (jsCode != null && jsCode.startsWith(CODE_PREFIX))
        {
            String suffix = jsCode.substring(CODE_PREFIX.length());
            if (!suffix.matches("[A-Za-z0-9_-]{1,64}"))
            {
                throw new IllegalArgumentException("非法的测试微信临时凭证");
            }
            return new WeChatMiniSession("e2e-openid-" + suffix, "e2e-session-" + suffix);
        }
        return super.exchange(jsCode);
    }
}
