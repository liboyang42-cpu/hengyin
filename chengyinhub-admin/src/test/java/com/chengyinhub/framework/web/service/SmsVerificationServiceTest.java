package com.chengyinhub.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collection;

import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.env.MockEnvironment;

class SmsVerificationServiceTest
{
    private RedisCache redisCache;
    private ISysConfigService configService;
    private SmsCodeSender sender;
    private MockEnvironment environment;

    @BeforeEach
    void setUp()
    {
        redisCache = mock(RedisCache.class);
        configService = mock(ISysConfigService.class);
        sender = mock(SmsCodeSender.class);
        when(redisCache.incrementWithinWindow(org.mockito.ArgumentMatchers.startsWith("sms_send_phone_day_"), eq(1), eq(TimeUnit.DAYS))).thenReturn(1L);
        environment = new MockEnvironment()
                .withProperty("CHENGYIN_SMS_ACCESS_KEY_ID", "env-id")
                .withProperty("CHENGYIN_SMS_ACCESS_KEY_SECRET", "env-secret")
                .withProperty("CHENGYIN_SMS_SIGN_NAME", "env-sign")
                .withProperty("CHENGYIN_SMS_TEMPLATE_CODE", "env-template");
    }

    @Test
    void usesEnvironmentCredentialsAndAppliesPhoneLockAndIpWindowBeforeSending()
    {
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(redisCache.incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS))
                .thenReturn(1L);
        when(sender.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        RecordingSecureRandom secureRandom = new RecordingSecureRandom(234567);
        SmsVerificationService service = service(secureRandom);

        SmsVerificationService.SendResult result = service.send("13800138000", "203.0.113.8");

        assertTrue(result.isSuccess());
        InOrder order = inOrder(redisCache, sender);
        order.verify(redisCache).setIfAbsent(eq("sms_limit_phone_13800138000"), anyString(), eq(60), eq(TimeUnit.SECONDS));
        order.verify(redisCache).incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS);
        order.verify(sender).send(eq("env-id"), eq("env-secret"), eq("env-sign"), eq("env-template"),
                eq("13800138000"), anyString());
        verify(configService, never()).selectConfigByKey("sms.aliyun.signName");
        verify(configService, never()).selectConfigByKey("sms.aliyun.templateCode");
        verify(configService, never()).selectConfigByKey("sms.aliyun.accessKeySecret");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setCacheObject(eq("sms_13800138000_code"), code.capture(), eq(5), eq(TimeUnit.MINUTES));
        verify(redisCache, never()).deleteObject(any(Collection.class));
        verify(redisCache).incrementWithinWindow("sms_send_phone_day_13800138000", 1, TimeUnit.DAYS);
        assertEquals("334567", code.getValue());
        assertEquals(900000, secureRandom.lastBound);
        assertEquals(1, secureRandom.calls);
    }

    @Test
    void invalidPhoneKeepsExistingMessageAndDoesNotTouchConfigurationOrRedis()
    {
        SmsVerificationService.SendResult result = service(new SecureRandom()).send("123", "203.0.113.8");

        assertEquals("手机号格式不正确", result.getMessage());
        verify(configService, never()).selectConfigByKey(anyString());
        verify(redisCache, never()).setIfAbsent(anyString(), anyString(), eq(60), eq(TimeUnit.SECONDS));
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToSysConfigOnlyForNonSecretPresentationFields()
    {
        environment = new MockEnvironment()
                .withProperty("CHENGYIN_SMS_ACCESS_KEY_ID", "env-id")
                .withProperty("CHENGYIN_SMS_ACCESS_KEY_SECRET", "env-secret");
        when(configService.selectConfigByKey("sms.aliyun.signName")).thenReturn("db-sign");
        when(configService.selectConfigByKey("sms.aliyun.templateCode")).thenReturn("db-template");
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redisCache.incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS))
                .thenReturn(1L);
        when(sender.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        service(new SecureRandom()).send("13800138000", "203.0.113.8");

        verify(sender).send(eq("env-id"), eq("env-secret"), eq("db-sign"), eq("db-template"),
                eq("13800138000"), anyString());
        verify(configService, never()).selectConfigByKey("sms.aliyun.accessKeyId");
        verify(configService, never()).selectConfigByKey("sms.aliyun.accessKeySecret");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CHENGYIN_SMS_ACCESS_KEY_ID",
            "CHENGYIN_SMS_ACCESS_KEY_SECRET",
            "CHENGYIN_SMS_SIGN_NAME",
            "CHENGYIN_SMS_TEMPLATE_CODE"
    })
    void anyMissingConfigurationFailsClosedWithExistingMessageWithoutLockingOrSending(String missingKey)
    {
        environment.setProperty(missingKey, " ");

        SmsVerificationService.SendResult result = service(new SecureRandom()).send("13800138000", "203.0.113.8");

        assertEquals("短信服务未配置", result.getMessage());
        verify(redisCache, never()).setIfAbsent(anyString(), anyString(), eq(60), eq(TimeUnit.SECONDS));
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(configService, never()).selectConfigByKey("sms.aliyun.accessKeySecret");
    }

    @Test
    void occupiedPhoneLockRejectsBeforeIpLockAndSend()
    {
        when(redisCache.setIfAbsent(eq("sms_limit_phone_13800138000"), anyString(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        SmsVerificationService.SendResult result = service(new SecureRandom()).send("13800138000", "203.0.113.8");

        assertEquals("发送过于频繁,请稍后再试", result.getMessage());
        verify(redisCache, never()).incrementWithinWindow(anyString(), eq(60), eq(TimeUnit.SECONDS));
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void eleventhIpWindowHitReleasesOnlyOwnedPhoneLock()
    {
        when(redisCache.setIfAbsent(eq("sms_limit_phone_13800138000"), anyString(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(redisCache.incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS))
                .thenReturn(11L);

        ArgumentCaptor<String> acquiredOwner = ArgumentCaptor.forClass(String.class);
        SmsVerificationService.SendResult result = service(new SecureRandom()).send("13800138000", "203.0.113.8");

        assertEquals("发送过于频繁,请稍后再试", result.getMessage());
        verify(redisCache).setIfAbsent(eq("sms_limit_phone_13800138000"), acquiredOwner.capture(),
                eq(60), eq(TimeUnit.SECONDS));
        verify(redisCache).compareAndDelete("sms_limit_phone_13800138000", acquiredOwner.getValue());
        verify(redisCache, never()).compareAndDelete("sms_limit_phone_13800138000", "different-owner");
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void eleventhDailyPhoneAttemptCannotSendOrResetVerificationFailures() {
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redisCache.incrementWithinWindow(anyString(), eq(60), eq(TimeUnit.SECONDS))).thenReturn(1L);
        when(redisCache.incrementWithinWindow("sms_send_phone_day_13800138000", 1, TimeUnit.DAYS)).thenReturn(11L);
        SmsVerificationService.SendResult result = service(new SecureRandom()).send("13800138000", "203.0.113.8");
        assertFalse(result.isSuccess());
        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(redisCache, never()).deleteObject(any(Collection.class));
    }

    @Test
    void providerFailureReleasesPhoneButKeepsIpAbuseCount()
    {
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redisCache.incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS))
                .thenReturn(1L);
        when(sender.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        ArgumentCaptor<String> acquiredOwner = ArgumentCaptor.forClass(String.class);
        SmsVerificationService.SendResult result = service(new SecureRandom()).send("13800138000", "203.0.113.8");

        assertEquals("验证码发送失败", result.getMessage());
        verify(redisCache).setIfAbsent(
                eq("sms_limit_phone_13800138000"), acquiredOwner.capture(), eq(60), eq(TimeUnit.SECONDS));
        String owner = acquiredOwner.getValue();
        verify(redisCache).compareAndDelete("sms_limit_phone_13800138000", owner);
        verify(redisCache).incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS);
        verify(redisCache, never()).compareAndDelete(eq("sms_send_ip_window_203.0.113.8"), anyString());
    }

    @Test
    void fifthWrongVerificationAttemptDeletesOtpAndFurtherAttemptsStayRejected()
    {
        ConcurrentHashMap<String, String> locks = new ConcurrentHashMap<>();
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(5), eq(TimeUnit.MINUTES)))
                .thenAnswer(invocation -> locks.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        when(redisCache.incrementWithinWindow("sms_verify_ip_window_203.0.113.8", 5, TimeUnit.MINUTES))
                .thenReturn(1L);
        when(redisCache.compareAndDelete("sms_13800138000_code", "000000")).thenReturn(false);
        SmsVerificationService service = service(new SecureRandom());

        for (int attempt = 0; attempt < 6; attempt++)
        {
            assertEquals(false, service.verify("13800138000", "000000", "203.0.113.8"));
        }

        verify(redisCache, org.mockito.Mockito.times(2)).deleteObject("sms_13800138000_code");
        verify(redisCache, org.mockito.Mockito.times(5))
                .compareAndDelete("sms_13800138000_code", "000000");
    }

    @Test
    void successfulVerificationAtomicallyConsumesOtpAndReleasesItsAttemptSlots()
    {
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(5), eq(TimeUnit.MINUTES))).thenReturn(true);
        when(redisCache.incrementWithinWindow("sms_verify_ip_window_203.0.113.8", 5, TimeUnit.MINUTES))
                .thenReturn(1L);
        when(redisCache.compareAndDelete("sms_13800138000_code", "334567")).thenReturn(true);
        SmsVerificationService service = service(new SecureRandom());

        assertTrue(service.verify("13800138000", "334567", "203.0.113.8"));

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setIfAbsent(
                eq("sms_verify_attempt_phone_13800138000_1"), owner.capture(), eq(5), eq(TimeUnit.MINUTES));
        verify(redisCache).compareAndDelete("sms_verify_attempt_phone_13800138000_1", owner.getValue());
        verify(redisCache).incrementWithinWindow("sms_verify_ip_window_203.0.113.8", 5, TimeUnit.MINUTES);
        verify(redisCache, never()).compareAndDelete(eq("sms_verify_ip_window_203.0.113.8"), anyString());
        verify(redisCache, never()).deleteObject(org.mockito.ArgumentMatchers.any(Collection.class));
    }

    @Test
    void sharedIpAllowsMoreThanFivePhonesToVerify()
    {
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(5), eq(TimeUnit.MINUTES))).thenReturn(true);
        AtomicLong ipCount = new AtomicLong();
        when(redisCache.incrementWithinWindow("sms_verify_ip_window_203.0.113.8", 5, TimeUnit.MINUTES))
                .thenAnswer(invocation -> ipCount.incrementAndGet());
        when(redisCache.compareAndDelete(anyString(), anyString()))
                .thenAnswer(invocation -> "334567".equals(invocation.getArgument(1)));
        SmsVerificationService service = service(new SecureRandom());

        for (int attempt = 0; attempt < 5; attempt++)
        {
            assertEquals(false, service.verify(String.format("13%09d", attempt), "000000", "203.0.113.8"));
        }
        assertTrue(service.verify("13900139000", "334567", "203.0.113.8"));
    }

    @Test
    void sharedIpIsLimitedOnlyOnOneHundredFirstAttempt()
    {
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(5), eq(TimeUnit.MINUTES))).thenReturn(true);
        AtomicLong ipCount = new AtomicLong();
        when(redisCache.incrementWithinWindow("sms_verify_ip_window_203.0.113.8", 5, TimeUnit.MINUTES))
                .thenAnswer(invocation -> ipCount.incrementAndGet());
        when(redisCache.compareAndDelete(anyString(), anyString())).thenReturn(false);
        SmsVerificationService service = service(new SecureRandom());

        for (int attempt = 0; attempt < 100; attempt++)
        {
            assertEquals(false, service.verify(String.format("13%09d", attempt), "000000", "203.0.113.8"));
        }
        assertEquals(false, service.verify("13900139000", "334567", "203.0.113.8"));

        verify(redisCache, org.mockito.Mockito.times(100)).compareAndDelete(
                org.mockito.ArgumentMatchers.startsWith("sms_13"), eq("000000"));
        verify(redisCache, never()).compareAndDelete("sms_13900139000_code", "334567");
    }

    @Test
    void concurrentRequestsForSamePhoneSendOnlyOnce() throws Exception
    {
        AtomicBoolean phoneLock = new AtomicBoolean();
        when(redisCache.setIfAbsent(eq("sms_limit_phone_13800138000"), anyString(), eq(60), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> phoneLock.compareAndSet(false, true));
        when(redisCache.incrementWithinWindow(anyString(), eq(60), eq(TimeUnit.SECONDS))).thenReturn(1L);
        when(sender.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        SmsVerificationService service = service(new SecureRandom());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            List<Callable<SmsVerificationService.SendResult>> calls = Arrays.asList(
                    () -> service.send("13800138000", "203.0.113.8"),
                    () -> service.send("13800138000", "203.0.113.9"));
            List<Future<SmsVerificationService.SendResult>> results = executor.invokeAll(calls);

            int successCount = 0;
            for (Future<SmsVerificationService.SendResult> result : results)
            {
                if (result.get().isSuccess())
                {
                    successCount++;
                }
            }
            assertEquals(1, successCount);
            verify(sender, org.mockito.Mockito.times(1)).send(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    void sameIpDifferentPhonesAllowsTenSendsAndRejectsEleventh()
    {
        when(redisCache.setIfAbsent(org.mockito.ArgumentMatchers.startsWith("sms_limit_phone_"),
                anyString(), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        AtomicLong ipCount = new AtomicLong();
        when(redisCache.incrementWithinWindow("sms_send_ip_window_203.0.113.8", 60, TimeUnit.SECONDS))
                .thenAnswer(invocation -> ipCount.incrementAndGet());
        when(sender.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        SmsVerificationService service = service(new SecureRandom());

        for (int attempt = 0; attempt < 10; attempt++)
        {
            assertTrue(service.send(String.format("13%09d", attempt), "203.0.113.8").isSuccess());
        }
        assertEquals("发送过于频繁,请稍后再试",
                service.send("13900139000", "203.0.113.8").getMessage());
        verify(sender, org.mockito.Mockito.times(10)).send(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private SmsVerificationService service(SecureRandom secureRandom)
    {
        return new SmsVerificationService(redisCache, configService, environment, sender, secureRandom);
    }

    private static final class RecordingSecureRandom extends SecureRandom
    {
        private static final long serialVersionUID = 1L;
        private final int value;
        private int lastBound;
        private int calls;

        private RecordingSecureRandom(int value)
        {
            this.value = value;
        }

        @Override
        public int nextInt(int bound)
        {
            lastBound = bound;
            calls++;
            return value;
        }
    }
}
