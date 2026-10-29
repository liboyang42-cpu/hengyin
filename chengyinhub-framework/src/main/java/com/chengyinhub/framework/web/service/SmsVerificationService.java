package com.chengyinhub.framework.web.service;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class SmsVerificationService
{
    private static final int PHONE_SEND_WINDOW_SECONDS = 60;
    private static final int IP_SEND_WINDOW_SECONDS = 60;
    private static final long IP_SEND_WINDOW_LIMIT = 10L;
    private static final int CODE_MIN = 100000;
    private static final int CODE_RANGE = 900000;
    private static final int PHONE_VERIFY_ATTEMPT_LIMIT = 5;
    private static final int VERIFY_WINDOW_MINUTES = 5;
    private static final long IP_VERIFY_WINDOW_LIMIT = 100L;

    private final RedisCache redisCache;
    private final ISysConfigService configService;
    private final Environment environment;
    private final SmsCodeSender sender;
    private final SecureRandom secureRandom;

    @Autowired
    public SmsVerificationService(RedisCache redisCache, ISysConfigService configService,
                                  Environment environment, SmsCodeSender sender)
    {
        this(redisCache, configService, environment, sender, new SecureRandom());
    }

    SmsVerificationService(RedisCache redisCache, ISysConfigService configService,
                           Environment environment, SmsCodeSender sender, SecureRandom secureRandom)
    {
        this.redisCache = redisCache;
        this.configService = configService;
        this.environment = environment;
        this.sender = sender;
        this.secureRandom = secureRandom;
    }

    public SendResult send(String phone, String ipAddress)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (StringUtils.isEmpty(phone) || !phone.matches("^1\\d{10}$"))
        {
            return SendResult.error("手机号格式不正确");
        }

        SmsConfiguration configuration = loadConfiguration();
        if (!configuration.isComplete())
        {
            return SendResult.error("短信服务未配置");
        }

        String owner = UUID.randomUUID().toString();
        String phoneLimitKey = "sms_limit_phone_" + phone;
        String ipWindowKey = "sms_send_ip_window_" + normalizeIp(ipAddress);
        if (!Boolean.TRUE.equals(redisCache.setIfAbsent(
                phoneLimitKey, owner, PHONE_SEND_WINDOW_SECONDS, TimeUnit.SECONDS)))
        {
            return SendResult.error("发送过于频繁,请稍后再试");
        }
        Long ipCount = redisCache.incrementWithinWindow(
                ipWindowKey, IP_SEND_WINDOW_SECONDS, TimeUnit.SECONDS);
        if (ipCount == null || ipCount > IP_SEND_WINDOW_LIMIT)
        {
            redisCache.compareAndDelete(phoneLimitKey, owner);
            return SendResult.error("发送过于频繁,请稍后再试");
        }

        Long dailyCount = redisCache.incrementWithinWindow("sms_send_phone_day_" + phone, 1, TimeUnit.DAYS);
        if (dailyCount == null || dailyCount > 10) {
            redisCache.compareAndDelete(phoneLimitKey, owner);
            return SendResult.error("今日验证码发送次数已达上限，请明天再试");
        }

        String code = Integer.toString(CODE_MIN + secureRandom.nextInt(CODE_RANGE));
        boolean sent;
        try
        {
            sent = sender.send(configuration.accessKeyId, configuration.accessKeySecret,
                    configuration.signName, configuration.templateCode, phone, code);
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (RuntimeException exception)
        {
            sent = false;
        }
        if (!sent)
        {
            redisCache.compareAndDelete(phoneLimitKey, owner);
            return SendResult.error("验证码发送失败");
        }

        redisCache.setCacheObject("sms_" + phone + "_code", code, 5, TimeUnit.MINUTES);
        return SendResult.success("验证码已发送");
    }

    /**
     * Reserves a bounded phone attempt slot and increments the shared-IP fixed window before
     * atomically consuming the OTP on success.
     */
    public boolean verify(String phone, String code, String ipAddress)
    {
        if (StringUtils.isEmpty(phone) || !phone.matches("^1\\d{10}$") || StringUtils.isEmpty(code))
        {
            return false;
        }

        String owner = UUID.randomUUID().toString();
        AttemptSlot phoneSlot = acquireAttemptSlot(
                "sms_verify_attempt_phone_" + phone, owner, PHONE_VERIFY_ATTEMPT_LIMIT);
        if (phoneSlot == null)
        {
            redisCache.deleteObject("sms_" + phone + "_code");
            return false;
        }

        Long ipCount = redisCache.incrementWithinWindow(
                "sms_verify_ip_window_" + normalizeIp(ipAddress), VERIFY_WINDOW_MINUTES, TimeUnit.MINUTES);
        if (ipCount == null || ipCount > IP_VERIFY_WINDOW_LIMIT)
        {
            redisCache.compareAndDelete(phoneSlot.key, owner);
            return false;
        }

        boolean verified = redisCache.compareAndDelete("sms_" + phone + "_code", code);
        if (verified)
        {
            redisCache.compareAndDelete(phoneSlot.key, owner);
            return true;
        }
        if (phoneSlot.number == PHONE_VERIFY_ATTEMPT_LIMIT)
        {
            redisCache.deleteObject("sms_" + phone + "_code");
        }
        return false;
    }

    private AttemptSlot acquireAttemptSlot(String keyPrefix, String owner, int limit)
    {
        for (int attempt = 1; attempt <= limit; attempt++)
        {
            String key = keyPrefix + "_" + attempt;
            if (Boolean.TRUE.equals(redisCache.setIfAbsent(
                    key, owner, VERIFY_WINDOW_MINUTES, TimeUnit.MINUTES)))
            {
                return new AttemptSlot(key, attempt);
            }
        }
        return null;
    }

    private SmsConfiguration loadConfiguration()
    {
        return new SmsConfiguration(
                environment.getProperty("CHENGYIN_SMS_ACCESS_KEY_ID"),
                environment.getProperty("CHENGYIN_SMS_ACCESS_KEY_SECRET"),
                envOrConfig("CHENGYIN_SMS_SIGN_NAME", "sms.aliyun.signName"),
                envOrConfig("CHENGYIN_SMS_TEMPLATE_CODE", "sms.aliyun.templateCode"));
    }

    private String envOrConfig(String environmentKey, String configKey)
    {
        String value = trimToNull(environment.getProperty(environmentKey));
        return value == null ? trimToNull(configService.selectConfigByKey(configKey)) : value;
    }

    private String normalizeIp(String ipAddress)
    {
        String normalized = trimToNull(ipAddress);
        return normalized == null ? "unknown" : normalized;
    }

    private String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private final class SmsConfiguration
    {
        private final String accessKeyId;
        private final String accessKeySecret;
        private final String signName;
        private final String templateCode;

        private SmsConfiguration(String accessKeyId, String accessKeySecret,
                                 String signName, String templateCode)
        {
            this.accessKeyId = trimToNull(accessKeyId);
            this.accessKeySecret = trimToNull(accessKeySecret);
            this.signName = trimToNull(signName);
            this.templateCode = trimToNull(templateCode);
        }

        private boolean isComplete()
        {
            return accessKeyId != null && accessKeySecret != null
                    && signName != null && templateCode != null;
        }
    }

    private static final class AttemptSlot
    {
        private final String key;
        private final int number;

        private AttemptSlot(String key, int number)
        {
            this.key = key;
            this.number = number;
        }
    }

    public static final class SendResult
    {
        private final boolean success;
        private final String message;

        private SendResult(boolean success, String message)
        {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess()
        {
            return success;
        }

        public String getMessage()
        {
            return message;
        }

        private static SendResult success(String message)
        {
            return new SendResult(true, message);
        }

        private static SendResult error(String message)
        {
            return new SendResult(false, message);
        }
    }
}
