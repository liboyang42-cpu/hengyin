package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-2 的接线锁:后台运营审核通过后必须真的触发打款。
 *
 * 为什么单独一个类:RefundPayoutServiceTest 只覆盖 payoutAfterReview 内部,
 * 摘掉 RefundController 里那行调用时【没有任何测试会红】—— 实测负控确认过。
 * 而那行正是 D-2 的正主(运营点「通过」钱不退)。这个类专门钉住它。
 */
class RefundControllerReviewPayoutTest {

    private static final Long OPERATOR = 99L;

    private IRefundService refundService;
    private IRefundPayoutService refundPayoutService;
    private RefundController controller;

    @BeforeEach
    void setUp() {
        refundService = mock(IRefundService.class);
        refundPayoutService = mock(IRefundPayoutService.class);
        controller = spy(new RefundController());
        ReflectionTestUtils.setField(controller, "refundService", refundService);
        ReflectionTestUtils.setField(controller, "refundPayoutService", refundPayoutService);
        // 绕开 SecurityContext(BaseController.getUserId 走 SecurityUtils,单测里没有登录态)
        doReturn(OPERATOR).when(controller).getUserId();
    }

    private RefundReviewDTO req(Integer status) {
        RefundReviewDTO r = new RefundReviewDTO();
        r.setId(7L);
        r.setStatus(status);
        return r;
    }

    /** 审核通过 ⇒ 必须打款。这是 D-2 的核心:少了这行,账全改完了钱一分没退。 */
    @Test
    void approvedReview_actuallyTriggersPayout() {
        when(refundService.reviewRefund(any(), eq(OPERATOR))).thenReturn(1);

        controller.review(req(1));

        verify(refundPayoutService).payoutAfterReview(7L);
    }

    /** 驳回 ⇒ 绝不打款。漏了这条就是白送钱。 */
    @Test
    void rejectedReview_neverPaysOut() {
        when(refundService.reviewRefund(any(), eq(OPERATOR))).thenReturn(1);

        controller.review(req(2));

        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }

    /**
     * 审核未生效(rows=0)⇒ 不打款。
     * updateRefundStatusIfPending 带 WHERE status=0 守卫,重复点「通过」/并发时返回 0 行 ——
     * 此时若照样打款,同一单会被退第二次。
     */
    @Test
    void reviewNotApplied_neverPaysOut() {
        when(refundService.reviewRefund(any(), eq(OPERATOR))).thenReturn(0);

        controller.review(req(1));

        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }
    /** FX-03:报名已被别的路径退掉,reviewRefund 抛错 ⇒ 异常冒出、绝不打款。 */
    @Test
    void reviewOfAlreadyRefundedRegistration_neverPaysOut() {
        when(refundService.reviewRefund(any(), eq(OPERATOR)))
                .thenThrow(new IllegalStateException("该报名已退款,不能重复批准退款,请驳回本退款单"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> controller.review(req(1)));

        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }

    /** FX-04:后台建单必须走带「同单已退额」口径的入口,不能直接 saveRefund(首单会按整单汇总额退)。 */
    @Test
    void backOfficeAdd_goesThroughOrderCashGate() {
        com.chengyinhub.business.domain.RefundApplication refund = new com.chengyinhub.business.domain.RefundApplication();
        when(refundService.saveBackOfficeRefund(refund)).thenReturn(1);

        controller.add(refund);

        verify(refundService).saveBackOfficeRefund(refund);
        verify(refundService, never()).saveRefund(any());
        org.junit.jupiter.api.Assertions.assertEquals(OPERATOR, refund.getCreatedByUserId());
    }
}
