package com.chengyinhub.business.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * TEST-only content moderation for the e2e TEST backend, which has no WeChat mini-program
 * credentials (the real service fails closed there). Text passes unless the self-hosted
 * sensitive-word library blocks it; media is never submitted and asynchronous checks are not
 * started. Loaded only with profile {@code e2e-test} AND {@code chengyin.e2e.test-providers=true};
 * the default and production profiles keep {@link WxContentSecurityServiceImpl}.
 */
@Service
@Primary
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestContentSecurityService extends WxContentSecurityServiceImpl
{
    private static final Logger log = LoggerFactory.getLogger(E2eTestContentSecurityService.class);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.service.ISensitiveContentLibrary e2eSensitiveLibrary;

    public E2eTestContentSecurityService()
    {
        log.warn("[e2e-test] TEST-only content moderation active: WeChat msgSecCheck is bypassed");
    }

    @Override
    public String checkText(String content, String openid)
    {
        if (content == null || content.isEmpty())
        {
            return null;
        }
        if (e2eSensitiveLibrary != null && e2eSensitiveLibrary.blockingCategory(content, "TEXT_CHECK", null) != null)
        {
            return com.chengyinhub.business.service.ISensitiveContentLibrary.VIOLATION_MESSAGE;
        }
        return null;
    }

    @Override
    public String checkMediaAsync(String mediaUrl, String openid)
    {
        return "e2e-test-" + java.util.UUID.randomUUID();
    }

    @Override
    public boolean canSubmitMedia(String openid)
    {
        return false;
    }
}
