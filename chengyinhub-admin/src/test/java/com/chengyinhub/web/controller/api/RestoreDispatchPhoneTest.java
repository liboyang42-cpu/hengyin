package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.config.RestoreDispatchGuard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreDispatchPhoneTest {
    @Test void paidPhoneLookupIsBlockedBeforeTokenOrProviderWithoutChangingAnonymousRefusal() throws Exception {
        ApiLoginController member = new ApiLoginController() {
            @Override public Long getAppUserId() { return 7L; }
            @Override protected String sendHttpGet(String url) { throw new AssertionError("unexpected GET"); }
            @Override protected String sendHttpPost(String url, String body) { throw new AssertionError("unexpected POST"); }
        };
        ApiLoginController anonymous = new ApiLoginController() {
            @Override public Long getAppUserId() { return 0L; }
        };
        try (var flag = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS)) {
            flag.when(RestoreDispatchGuard::isRestoreMode).thenReturn(true);
            assertEquals("RECOVERY_DISPATCH_BLOCKED", assertThrows(RestoreDispatchGuard.Blocked.class,
                    () -> member.getPhoneNumber("original-code")).getMessage());
            assertEquals(500, anonymous.getPhoneNumber("original-code").get("code"));
        }
    }
}
