package com.chengyinhub.framework.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TEST-only SMS sender for the e2e TEST backend: never contacts a provider, only reports success,
 * so {@link SmsVerificationService} stores the code in Redis ({@code sms_<phone>_code}) where the
 * e2e harness reads it. Loaded only with profile {@code e2e-test} AND
 * {@code chengyin.e2e.test-providers=true}; the default and production profiles keep the Aliyun sender.
 */
@Component
@Primary
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestSmsCodeSender implements SmsCodeSender
{
    private static final Logger log = LoggerFactory.getLogger(E2eTestSmsCodeSender.class);

    public E2eTestSmsCodeSender()
    {
        log.warn("[e2e-test] TEST-only SMS sender active: codes are kept in Redis and never sent");
    }

    @Override
    public boolean send(String accessKeyId, String accessKeySecret, String signName,
                        String templateCode, String phone, String code)
    {
        log.info("[e2e-test] SMS code stored for ****{} (not sent)",
                phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4));
        return true;
    }
}
