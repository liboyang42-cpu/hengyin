package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超额退款闸(方案 §3.3「应用层先校验累计」):退款额不得超过实付额。
 *
 * 缺陷:saveRefund 的 HF-04 闸条件含 `paidAmount > 0`,而 enrichRegistrationRefund 会把
 * payableAmount=0 的多票从单填成 paidAmount=0 ⇒ 闸门整条短路,任意金额都能落库。
 * 实付 0 恰恰是最该拦的情形 —— 它是"这条记录不该退出任何钱"的信号,不是"随便退"的通行证。
 */
class RefundOverpayGuardTest {

    private static final Long SUB_ORDER_ID = 7L;
    private static final Long MEMBER_ID = 88L;

    private RefundServiceImpl newService(CmsRegistration reg, RefundApplicationMapper appMapper) {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        when(regMapper.selectCmsRegistrationById(reg.getId())).thenReturn(reg);
        when(regMapper.selectCmsRegistrationByIdForUpdate(reg.getId())).thenReturn(reg);
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "refundApplicationMapper", appMapper);
        return service;
    }

    private CmsRegistration subOrder() {
        CmsRegistration sub = new CmsRegistration();
        sub.setId(SUB_ORDER_ID);
        sub.setMemberId(MEMBER_ID);
        sub.setPaymentStatus(2);
        sub.setPayableAmount(BigDecimal.ZERO);              // 多票从单:应付 0(汇总在首单)
        sub.setTotalAmount(new BigDecimal("100.00"));       // 本票票面 100
        sub.setOriginalAmount(new BigDecimal("100.00"));
        return sub;
    }

    private RefundApplication refundOf(String amount) {
        RefundApplication app = new RefundApplication();
        app.setSourceType("registration");
        app.setSourceId(SUB_ORDER_ID);
        app.setRefundAmount(new BigDecimal(amount));
        app.setRefundNo("RF" + SUB_ORDER_ID);
        return app;
    }

    @Test
    void zeroPaidRegistrationRejectsPositiveRefund() {
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = newService(subOrder(), appMapper);

        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(refundOf("100.00")),
                "实付 0 的报名不得落 100 元退款单");
        // 闸门必须在落库【之前】拦住,而不是拦了但已经写进去
        verify(appMapper, never()).insertRefundApplication(any());
    }

    @Test
    void zeroAmountRefundIsRejectedByTheEarlierRequiredArgsGuard() {
        // 钉住一条让「超额闸不需要 0 值特例」成立的前提:refundAmount<=0 在 saveRefund:164 的
        // 前置闸就被拒了,根本到不了超额闸。⇒ 进到超额闸的 refundAmount 恒 > 0,
        // 于是 paidAmount=0 时 refundAmount>paidAmount 必然成立,拦截无死角。
        // 这条前提若哪天被改掉(允许 0 元退款单),超额闸的语义要重新评估 —— 故立此测试为哨兵。
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = newService(subOrder(), appMapper);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.saveRefund(refundOf("0.00")));
        assertEquals("source and refundAmount are required", e.getMessage(),
                "退 0 应被前置闸(而非超额闸)拒绝");
        verify(appMapper, never()).insertRefundApplication(any());
    }

    @Test
    void normalPaidRegistrationRejectsOverRefund() {
        // 负控:实付 > 0 时闸门本就该生效(修复前也过)。它在这里是为了证明修复没把正常路径改坏。
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);
        CmsRegistration first = subOrder();
        first.setPayableAmount(new BigDecimal("200.00"));   // 首单:实付 200
        RefundServiceImpl service = newService(first, appMapper);

        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(refundOf("300.00")),
                "实付 200 不得退 300");
        verify(appMapper, never()).insertRefundApplication(any());
    }

    @Test
    void normalPaidRegistrationAllowsExactRefund() {
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);
        when(appMapper.insertRefundApplication(any())).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });
        CmsRegistration first = subOrder();
        first.setPayableAmount(new BigDecimal("200.00"));
        RefundServiceImpl service = newService(first, appMapper);

        assertEquals(1, service.saveRefund(refundOf("200.00")), "全额退应放行");
    }

    /**
     * FX-06(9-18 拍板「后端不认前端金额」):后台建单请求里手填 paidAmount 不能抬高上限。
     * 修复前 enrichRegistrationRefund 只在 paidAmount 为空或 ≤0 时才用服务端值覆盖 ⇒
     * 从单(payable=0)手填 paidAmount=100 就能建出 100 元退款单。
     */
    @Test
    void clientPaidAmountOnSubOrderIsIgnored() {
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);
        RefundServiceImpl service = newService(subOrder(), appMapper);
        RefundApplication forged = refundOf("100.00");
        forged.setPaidAmount(new BigDecimal("100.00"));

        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(forged),
                "从单实付 0,手填 paidAmount 不能让它退出钱");
        verify(appMapper, never()).insertRefundApplication(any());
    }

    /** FX-06:单票同样按服务端实付封顶,手填更大的 paidAmount 无效,落库的 paidAmount 也是服务端值。 */
    @Test
    void clientPaidAmountAboveServerPaidIsOverwritten() {
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);
        when(appMapper.insertRefundApplication(any())).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });
        CmsRegistration single = subOrder();
        single.setPayableAmount(new BigDecimal("50.00"));
        RefundServiceImpl service = newService(single, appMapper);
        RefundApplication forged = refundOf("80.00");
        forged.setPaidAmount(new BigDecimal("999.00"));

        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(forged), "实付 50 不得退 80");
        RefundApplication honest = refundOf("50.00");
        honest.setPaidAmount(new BigDecimal("999.00"));
        assertEquals(1, service.saveRefund(honest));
        assertEquals(0, new BigDecimal("50.00").compareTo(honest.getPaidAmount()), "落库实付必须是服务端值");
    }
}
