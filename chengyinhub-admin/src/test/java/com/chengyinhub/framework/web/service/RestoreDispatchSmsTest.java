package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreDispatchSmsTest {
    @Test void restoredSmsEntryDoesNotConsumeCountersOrSend() {
        RedisCache redis = mock(RedisCache.class);
        ISysConfigService config = mock(ISysConfigService.class);
        Environment environment = mock(Environment.class);
        SmsCodeSender sender = mock(SmsCodeSender.class);
        SmsVerificationService service = new SmsVerificationService(redis, config, environment, sender);
        try (var mode = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS)) {
            mode.when(RestoreDispatchGuard::isRestoreMode).thenReturn(true);
            assertEquals("RECOVERY_DISPATCH_BLOCKED", assertThrows(RestoreDispatchGuard.Blocked.class,
                    () -> service.send("13800000000", "127.0.0.1")).getMessage());
        }
        verifyNoInteractions(redis, config, environment, sender);
    }
}
