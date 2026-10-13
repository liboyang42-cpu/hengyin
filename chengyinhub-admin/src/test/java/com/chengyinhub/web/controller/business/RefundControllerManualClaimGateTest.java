package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ManualPayoutDTO;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H-A:业务号冲突单(payout_status=2 且 refund_no 为空)在人工队列里的可处理性闸。
 *
 * 不变量(修法的前提,已按代码逐点核对):refund_no 为空 ⟹ 本单从未向微信提交过原路退款 ——
 * submitRefund 全仓唯一生产调用点在派发编排 doPayout 里,且必须先把本行 refund_no 持久化成
 * 预期值才会发起;refund_no 一旦写入不会被任何 SQL 清空(ensureRefundNo 只在空/同值时写,
 * 通用 updateRefundApplication 已于 FX-14 删除)。ensureRefundNo 的 1062 冲突发生在 submitRefund 之前,
 * 冲突单落人工队列时 refund_no 仍为 NULL。
 *
 * 旧行为:manualClaim/manualTakeover 对这类单也先 refreshPayoutFromWx(null),微信恒查不到
 * 终态 ⇒ 永久拒绝进入人工处理,又没有任何补号入口 —— 这笔钱既不能自动退也不能人工退。
 * 修后:无 refund_no 直接放行领取(微信侧没有可查询的退款单,不存在原路双花可比对的对象,
 * 冲突占号方已写进 payout_remark 供人工核对);有 refund_no 的单微信终态闸一寸不松。
 */
class RefundControllerManualClaimGateTest {

    private static final Long OPERATOR = 99L;

    private IRefundService refundService;
    private IWechatPayService wechatPayService;
    private RefundController controller;

    @BeforeEach
    void setUp() {
        refundService = mock(IRefundService.class);
        wechatPayService = mock(IWechatPayService.class);
        controller = spy(new RefundController());
        ReflectionTestUtils.setField(controller, "refundService", refundService);
        ReflectionTestUtils.setField(controller, "wechatPayService", wechatPayService);
        doReturn(OPERATOR).when(controller).getUserId();
    }

    private ManualPayoutDTO dto() {
        ManualPayoutDTO dto = new ManualPayoutDTO();
        dto.setRefundId(5L);
        return dto;
    }

    private RefundApplication manualRefund(String refundNo) {
        RefundApplication refund = new RefundApplication();
        refund.setId(5L);
        refund.setPayoutStatus(RefundApplication.PAYOUT_PENDING_MANUAL);
        refund.setRefundNo(refundNo);
        when(refundService.getRefundById(5L)).thenReturn(refund);
        return refund;
    }

    // ===== H-A 修复目标(修前红):无 refund_no = 从未提交微信,必须能进入人工处理 =====

    @Test
    void claimWithoutRefundNoGrantsTokenWithoutAskingWechat() {
        manualRefund(null);
        when(refundService.claimManualPayout(5L, OPERATOR)).thenReturn("tok-1");

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG),
                "无 refund_no 的冲突单从未提交过微信,不该被微信终态闸永久挡死");
        assertEquals("tok-1", result.get(AjaxResult.DATA_TAG));
        verify(wechatPayService, never()).refreshPayoutFromWx(any());
    }

    @Test
    void claimTreatsEmptyRefundNoAsNeverSubmitted() {
        manualRefund("");
        when(refundService.claimManualPayout(5L, OPERATOR)).thenReturn("tok-2");

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(wechatPayService, never()).refreshPayoutFromWx(any());
    }

    @Test
    void takeoverWithoutRefundNoGrantsTokenWithoutAskingWechat() {
        manualRefund(null);
        when(refundService.takeoverExpiredManualPayout(5L, OPERATOR)).thenReturn("tok-3");

        AjaxResult result = controller.manualTakeover(dto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG),
                "接管闸对无 refund_no 冲突单同样不该拿 null 去问微信后永久拒绝");
        assertEquals("tok-3", result.get(AjaxResult.DATA_TAG));
        verify(wechatPayService, never()).refreshPayoutFromWx(any());
    }

    // ===== 微信终态闸负控(修前修后都必须绿:已有 refund_no 的单一寸不松) =====

    @Test
    void claimWithRefundNoStillRejectedWhileWxProcessing() {
        manualRefund("RF11");
        when(wechatPayService.refreshPayoutFromWx("RF11")).thenReturn("PROCESSING");

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(refundService, never()).claimManualPayout(anyLong(), anyLong());
    }

    @Test
    void claimWithRefundNoStillRejectedWhenWxSucceeded() {
        manualRefund("RF11");
        when(wechatPayService.refreshPayoutFromWx("RF11")).thenReturn("SUCCESS");

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(refundService, never()).claimManualPayout(anyLong(), anyLong());
    }

    @Test
    void claimWithRefundNoStillFailsClosedOnUnknownWxStatus() {
        manualRefund("RF11");
        when(wechatPayService.refreshPayoutFromWx("RF11")).thenReturn(null);

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(500, result.get(AjaxResult.CODE_TAG),
                "查询失败/UNKNOWN 必须保持 fail-closed:提交过微信的单终态未知就不许人工打款");
        verify(refundService, never()).claimManualPayout(anyLong(), anyLong());
    }

    @Test
    void claimWithRefundNoClosedTerminalStillGrantsToken() {
        // 绿基线配对:证明上面的红不是"有 refund_no 恒拒",微信 CLOSED 终态照常放行。
        manualRefund("RF11");
        when(wechatPayService.refreshPayoutFromWx("RF11")).thenReturn("CLOSED");
        when(refundService.claimManualPayout(5L, OPERATOR)).thenReturn("tok-4");

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(wechatPayService).refreshPayoutFromWx("RF11");
    }

    @Test
    void takeoverWithRefundNoStillFailsClosedOnUnknownWxStatus() {
        manualRefund("RF11");
        when(wechatPayService.refreshPayoutFromWx("RF11")).thenReturn(null);

        AjaxResult result = controller.manualTakeover(dto());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(refundService, never()).takeoverExpiredManualPayout(anyLong(), anyLong());
    }

    // ===== 领取/接管本身的 fail-closed 不因绕过微信闸而松动 =====

    @Test
    void claimWithoutRefundNoStillFailsClosedWhenLeaseHeldByOther() {
        manualRefund(null);
        when(refundService.claimManualPayout(5L, OPERATOR)).thenReturn(null);

        AjaxResult result = controller.manualClaim(dto());

        assertEquals(500, result.get(AjaxResult.CODE_TAG),
                "绕过微信闸只对'从未提交微信'成立,租约竞争的 CAS fail-closed 必须原样保留");
    }

    @Test
    void takeoverWithoutRefundNoStillFailsClosedWhenLeaseNotExpired() {
        manualRefund(null);
        when(refundService.takeoverExpiredManualPayout(5L, OPERATOR)).thenReturn(null);

        AjaxResult result = controller.manualTakeover(dto());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }
}
