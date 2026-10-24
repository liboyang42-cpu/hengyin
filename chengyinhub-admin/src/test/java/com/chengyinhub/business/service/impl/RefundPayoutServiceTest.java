package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RefundRequestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-2 后台退款只动账不动钱。
 *
 * 病:reviewRefund 不调微信退款、不调 markPayoutResult ⇒ 运营点「通过」后钱没退、
 * payout_status 永远停在 0(处理中),连待人工队列都捞不到。
 * 修复:payoutAfterReview 成为两路(后台审核 / 玩家自退)共用的唯一打款入口。
 */
class RefundPayoutServiceTest {

    private RefundApplicationMapper refundApplicationMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private IRefundService refundService;
    private IWechatPayService wechatPayService;
    private RefundPayoutServiceImpl svc;

    @BeforeEach
    void setUp() {
        refundApplicationMapper = mock(RefundApplicationMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        refundService = mock(IRefundService.class);
        wechatPayService = mock(IWechatPayService.class);

        svc = new RefundPayoutServiceImpl();
        ReflectionTestUtils.setField(svc, "refundApplicationMapper", refundApplicationMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "refundService", refundService);
        ReflectionTestUtils.setField(svc, "wechatPayService", wechatPayService);
    }

    /**
     * @param status        退款单审核态 1=通过 2=驳回
     * @param refundAmount  本次退款额
     * @param paidCash      报名单原始现金实付额(= 微信侧 total_amount)
     */
    private void given(Integer status, String refundAmount, String paidCash) {
        given(status, refundAmount, paidCash, null);
    }

    private void given(Integer status, String refundAmount, String paidCash, Integer payoutStatus) {
        RefundApplication refund = new RefundApplication();
        refund.setId(7L);
        refund.setStatus(status);
        refund.setSourceType("registration");
        refund.setSourceId(10L);
        refund.setRefundAmount(new BigDecimal(refundAmount));
        refund.setReason("主办方取消");
        refund.setPayoutStatus(payoutStatus);
        when(refundApplicationMapper.selectRefundApplicationById(7L)).thenReturn(refund);
        when(refundApplicationMapper.claimPayoutDispatch(eq(7L), anyLong(), anyString())).thenReturn(1);
        when(refundApplicationMapper.ensureRefundNo(7L, "RF10")).thenReturn(1);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(10L);
        reg.setRegistrationNo("REG20260716001");
        reg.setWechatPaymentAmount(paidCash == null ? null : new BigDecimal(paidCash));
        when(cmsRegistrationMapper.selectCmsRegistrationById(10L)).thenReturn(reg);
    }

    /** 核心:审核通过 ⇒ 必须真调微信原路退 + 回写打款态。修复前两个 verify 都不发生 → RED。 */
    @Test
    void approvedRefund_actuallyCallsWechatRefund_andMarksPayout() {
        given(1, "100.00", "100.00");
        when(wechatPayService.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.ACCEPTED);

        svc.payoutAfterReview(7L);

        // out_trade_no=registrationNo,out_refund_no=RF+regId(回调按此反查,必须确定性)
        verify(wechatPayService).submitRefund(eq("REG20260716001"), eq("RF10"),
                eq(new BigDecimal("100.00")), eq(new BigDecimal("100.00")), eq("主办方取消"));
        verify(refundService).markPayoutResult(eq(7L), anyString(), eq(true), any());
    }

    /** 本地事务提交后留下的待派发退款，必须能进入唯一的微信出金入口。 */
    @Test
    void dispatchPendingRefund_actuallyCallsWechatRefund() {
        given(1, "100.00", "100.00", RefundApplication.PAYOUT_DISPATCH_PENDING);
        when(wechatPayService.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.ACCEPTED);

        svc.payoutAfterReview(7L);

        verify(wechatPayService).submitRefund(eq("REG20260716001"), eq("RF10"),
                eq(new BigDecimal("100.00")), eq(new BigDecimal("100.00")), eq("主办方取消"));
        verify(refundService).markPayoutResult(eq(7L), anyString(), eq(true), any());
    }

    /**
     * 部分退款:refund_amount=30 但微信的 total_amount 必须是原订单实付 100,不是 30。
     * 传错会被微信按「退款额>订单额」拒掉 —— 全额退时两值恰好相等,所以这条只有部分退能暴露。
     */
    @Test
    void subsequentRefundKeepsPersistedNumberInsteadOfReusingFirstRefund() {
        given(1, "20.00", "100.00");
        RefundApplication refund = refundApplicationMapper.selectRefundApplicationById(7L);
        refund.setRefundNo("RF10-6");
        when(refundApplicationMapper.ensureRefundNo(7L, "RF10-6")).thenReturn(1);
        when(wechatPayService.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.ACCEPTED);
        svc.payoutAfterReview(7L);
        verify(wechatPayService).submitRefund(eq("REG20260716001"), eq("RF10-6"),
                eq(new BigDecimal("20.00")), eq(new BigDecimal("100.00")), any());
        verify(refundApplicationMapper, never()).ensureRefundNo(7L, "RF10");
    }

    @Test
    void partialRefund_passesOriginalPaidAmountAsTotal_notRefundAmount() {
        given(1, "30.00", "100.00");
        when(wechatPayService.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.ACCEPTED);

        svc.payoutAfterReview(7L);

        ArgumentCaptor<BigDecimal> refundAmt = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> totalAmt = ArgumentCaptor.forClass(BigDecimal.class);
        verify(wechatPayService).submitRefund(anyString(), anyString(), refundAmt.capture(), totalAmt.capture(), any());
        assertEquals(0, new BigDecimal("30.00").compareTo(refundAmt.getValue()), "退款额应为 30");
        assertEquals(0, new BigDecimal("100.00").compareTo(totalAmt.getValue()),
                "total_amount 必须是原订单实付 100,实际 " + totalAmt.getValue());
    }

    /** 微信受理失败 ⇒ 进待人工队列(markPayoutResult(false)),不能静默当成功。 */
    @Test
    void wechatRejects_marksPendingManual() {
        given(1, "100.00", "100.00");
        when(wechatPayService.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.DEFINITIVE_FAILED);

        svc.payoutAfterReview(7L);

        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(refundService).markPayoutResult(eq(7L), anyString(), eq(false), remark.capture());
        assertTrue(remark.getValue() != null && remark.getValue().contains("受理失败"),
                "受理失败须写明原因,实际 remark=" + remark.getValue());
    }

    /** 微信调用抛异常 ⇒ 结果不确定，释放待重试且不能穿出。 */
    @Test
    void wechatThrows_releasesDispatchForIdempotentRetry_andDoesNotPropagate() {
        given(1, "100.00", "100.00");
        when(wechatPayService.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("connect timeout"));

        svc.payoutAfterReview(7L);

        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(refundApplicationMapper).releasePayoutDispatch(eq(7L), anyString(), remark.capture());
        assertTrue(remark.getValue() != null && remark.getValue().contains("异常"),
                "异常须落进 remark,实际 remark=" + remark.getValue());
        verify(refundService, never()).markPayoutResult(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    /** 驳回的退款单绝不能打款 —— 这条要是漏了就是白送钱。 */
    @Test
    void rejectedRefund_neverPaysOut() {
        given(2, "100.00", "100.00");

        svc.payoutAfterReview(7L);

        verify(wechatPayService, never()).submitRefund(anyString(), anyString(), any(), any(), any());
        verify(refundService, never()).markPayoutResult(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    /**
     * 报名无现金实付却存在现金退款单 = 数据矛盾。不发起原路退,但【不能静默】——
     * 必须转人工,否则就是 D-2 的形状(钱不退且没人知道)。
     */
    @Test
    void pointsOnlyRegistration_noWechatCall_butGoesToManualQueue() {
        given(1, "100.00", "0.00");

        svc.payoutAfterReview(7L);

        verify(wechatPayService, never()).submitRefund(anyString(), anyString(), any(), any(), any());
        verify(refundService).markPayoutResult(eq(7L), anyString(), eq(false), any());
    }

    /**
     * 查不到报名单 ⇒ 转人工队列,绝不静默 return。
     * 静默的后果:payout_status 停在 0(处理中),而人工队列只捞 payout_status=2 ⇒ 这笔单无人可见。
     */
    @Test
    void missingRegistration_goesToManualQueue_notSilent() {
        given(1, "100.00", "100.00");
        when(cmsRegistrationMapper.selectCmsRegistrationById(10L)).thenReturn(null);

        svc.payoutAfterReview(7L);

        verify(wechatPayService, never()).submitRefund(anyString(), anyString(), any(), any(), any());
        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(refundService).markPayoutResult(eq(7L), anyString(), eq(false), remark.capture());
        assertTrue(remark.getValue() != null && remark.getValue().contains("报名单不存在"),
                "必须写明原因供人工排查,实际 remark=" + remark.getValue());
    }

    /**
     * 已到终态(原路已确认到账)的单不得再次发起 —— 微信单笔订单退款有 1 次/分钟频控,
     * 重复调会被拒 → 被误判「受理失败」→ 把一笔已到账的退款推进人工队列 → 人工再退一次 = 双重退款。
     */
    @Test
    void alreadySucceeded_doesNotRecallWechat() {
        given(1, "100.00", "100.00");
        when(refundApplicationMapper.selectRefundApplicationById(7L))
                .thenAnswer(inv -> {
                    RefundApplication r = new RefundApplication();
                    r.setId(7L);
                    r.setStatus(1);
                    r.setSourceType("registration");
                    r.setSourceId(10L);
                    r.setRefundAmount(new BigDecimal("100.00"));
                    r.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);   // 已确认到账
                    return r;
                });
        when(refundApplicationMapper.claimPayoutDispatch(eq(7L), anyLong(), anyString())).thenReturn(0);

        svc.payoutAfterReview(7L);

        verify(wechatPayService, never()).submitRefund(anyString(), anyString(), any(), any(), any());
        verify(refundService, never()).markPayoutResult(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    /**
     * 编排自身出错(DB 抖动)⇒ 异常不得穿出。
     * 穿出的后果:controller 路径 500 → 运营重试被 status 守卫挡成 rows=0 → 钱永远退不出去;
     * refundOne 路径则会把同事务里已成立的冲销带崩。
     */
    @Test
    void orchestrationError_doesNotPropagate_orOverwritePayoutState() {
        when(refundApplicationMapper.selectRefundApplicationById(7L))
                .thenThrow(new RuntimeException("connection reset"));

        svc.payoutAfterReview(7L);   // 不抛 = 通过

        verify(refundService, never()).markPayoutResult(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    /** 退款单不存在 ⇒ 安静 no-op,不 NPE(这条是真·正常场景:id 无效)。 */
    @Test
    void missingRefund_isNoOp() {
        when(refundApplicationMapper.selectRefundApplicationById(999L)).thenReturn(null);

        svc.payoutAfterReview(999L);

        verify(wechatPayService, never()).submitRefund(anyString(), anyString(), any(), any(), any());
    }
}
