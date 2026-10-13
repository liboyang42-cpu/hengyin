package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * releasePayoutDispatchOrVerify 的 safe-noop 白名单契约。
 *
 * release CAS 失败(返回 0)只有两类原因:①记录已被回调/对账推进到某个存活终态 —— 安全,静默放行;
 * ②记录仍在 DISPATCHING(6),但被另一个 worker 在 30 分钟 stale 窗口后重新领取(lease token 变了),
 * 我方 release 因 token 不匹配而失败。第 ② 类同样是安全的:另一个 worker 现在合法持有派发权,我方输掉
 * 竞争不该报错。原白名单漏了 DISPATCHING(6),会把这条正常 stale-reclaim 竞争误判成不可恢复状态而抛错。
 */
class RefundPayoutServiceImplTest {

    private RefundPayoutServiceImpl serviceWith(RefundApplicationMapper mapper) {
        RefundPayoutServiceImpl service = new RefundPayoutServiceImpl();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", mapper);
        return service;
    }

    private void invokeRelease(RefundPayoutServiceImpl service, Long refundId, String leaseToken, String remark) {
        ReflectionTestUtils.invokeMethod(service, "releasePayoutDispatchOrVerify", refundId, leaseToken, remark);
    }

    private RefundApplication withPayoutStatus(int payoutStatus) {
        RefundApplication refund = new RefundApplication();
        refund.setId(20L);
        refund.setPayoutStatus(payoutStatus);
        return refund;
    }

    /** ② 另一个 worker 已重新领取 → 记录停在 DISPATCHING(6),我方 release 失败必须安全放行,不抛错。 */
    @Test
    void releaseLostToReclaimingWorkerIsSafeNoopWhenStillDispatching() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.releasePayoutDispatch(20L, "old-lease", "重试")).thenReturn(0);
        when(mapper.selectRefundApplicationById(20L))
                .thenReturn(withPayoutStatus(RefundApplication.PAYOUT_DISPATCHING));

        assertDoesNotThrow(() -> invokeRelease(serviceWith(mapper), 20L, "old-lease", "重试"),
                "DISPATCHING(6) 是另一个 worker 合法持有派发权,输掉 release 竞争不该抛错");
    }

    /** ① 回调已把记录推进到 PROCESSING → 依旧安全放行(白名单既有分支,守住不回退)。 */
    @Test
    void releaseLostToCallbackTerminalIsSafeNoop() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.releasePayoutDispatch(20L, "old-lease", "重试")).thenReturn(0);
        when(mapper.selectRefundApplicationById(20L))
                .thenReturn(withPayoutStatus(RefundApplication.PAYOUT_PROCESSING));

        assertDoesNotThrow(() -> invokeRelease(serviceWith(mapper), 20L, "old-lease", "重试"));
    }

    /** 负控:release 失败且读回一个既非存活派发态、也非终态的坏状态(如 3 待复核)——白名单不能被放宽到吞掉真异常。 */
    @Test
    void releaseFailureWithGenuinelyUnexpectedStateStillThrows() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.releasePayoutDispatch(20L, "old-lease", "重试")).thenReturn(0);
        when(mapper.selectRefundApplicationById(20L))
                .thenReturn(withPayoutStatus(RefundApplication.PAYOUT_MANUAL_HANDLED));

        assertThrows(IllegalStateException.class,
                () -> invokeRelease(serviceWith(mapper), 20L, "old-lease", "重试"));
    }

    /** release 成功(返回 1)时直接放行,不再读回。 */
    @Test
    void releaseSuccessReturnsWithoutReadBack() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.releasePayoutDispatch(20L, "my-lease", "重试")).thenReturn(1);

        assertDoesNotThrow(() -> invokeRelease(serviceWith(mapper), 20L, "my-lease", "重试"));
    }
}
