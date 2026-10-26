package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.IFreeExploreBundleRefundService;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit non-F3 fixture for pre-existing funds tests; real F3 tests inject the actual service separately. */
public final class RefundF3TestSupport {
    private RefundF3TestSupport() { }

    public static RefundServiceImpl nonBundle(RefundServiceImpl target) {
        IFreeExploreBundleRefundService f3 = mock(IFreeExploreBundleRefundService.class);
        when(f3.prepareFullRefund(anyLong(), any())).thenAnswer(call ->
                new FreeExploreRefundContext(call.getArgument(0), call.getArgument(1), List.of(), Set.of()));
        ReflectionTestUtils.setField(target, "freeExploreBundleRefundService", f3);
        return target;
    }
}
