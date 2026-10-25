package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fenced mutation unit boundary; original verified-HTTP/MySQL suites remain required. */
class RestoreDispatchCallbackTest {
    @Test void restoreDoesNotDiscardAnOriginalFencedCallbackOrApplyItTwice() {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setTraceHash("hash"); row.setBizType(9); row.setBizId(42L); row.setMediaHash("media-hash");
        row.setState(WxContentCallbackInbox.STATE_PROCESSING); row.setOwnerToken("owner"); row.setFenceToken(8L);
        when(mapper.selectOwnedForUpdate("hash", "owner", 8L)).thenAnswer(call ->
                Integer.valueOf(WxContentCallbackInbox.STATE_PROCESSING).equals(row.getState()) ? row : null);
        when(mapper.lockBusinessTarget(9, 42L)).thenReturn(42L);
        AtomicInteger applied = new AtomicInteger();
        IWxContentCallbackInboxService.Mutation mutation = new IWxContentCallbackInboxService.Mutation() {
            @Override public boolean isCurrent(WxContentCallbackInbox original) { return original == row; }
            @Override public boolean apply(WxContentCallbackInbox original, boolean risky) {
                assertSame(row, original); assertTrue(risky); applied.incrementAndGet(); return true;
            }
        };
        when(mapper.markDone("hash", "owner", 8L, 1)).thenAnswer(call -> {
            row.setState(WxContentCallbackInbox.STATE_DONE); return 1;
        });
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        WxContentCallbackClaim original = WxContentCallbackClaim.acquired("hash", "owner", 8L);
        try (var mode = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS)) {
            mode.when(RestoreDispatchGuard::isRestoreMode).thenReturn(true);
            assertTrue(service.apply(original, true, mutation));
            assertFalse(service.apply(original, true, mutation));
            mode.verify(RestoreDispatchGuard::requireDispatchAllowed, never());
        }
        assertEquals(1, applied.get());
        verify(mapper).lockBusinessTarget(9, 42L);
        verify(mapper).markDone("hash", "owner", 8L, 1);
    }
}
