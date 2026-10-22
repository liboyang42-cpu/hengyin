package com.chengyinhub.business.service.compliance;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IMinorProtectionService;
import com.chengyinhub.business.service.IRegistrationOrderService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.business.service.impl.RegistrationCheckoutServiceImpl;
import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.MinorDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** DOC2 F7 结算挂点：拒绝时在建单/发起支付之前带 MINOR_* 机器码返回 403；放行（含开关关闭）时行为不变。 */
class MinorCheckoutGateTest {
    private static final Long MEMBER = 9901L;

    private ICmsRegistrationService registrationService;
    private IRegistrationOrderService orderService;
    private IWechatPayService wechatPayService;
    private IMinorProtectionService minor;
    private RegistrationCheckoutServiceImpl checkout;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        orderService = mock(IRegistrationOrderService.class);
        wechatPayService = mock(IWechatPayService.class);
        minor = mock(IMinorProtectionService.class);
        checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", registrationService);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", orderService);
        ReflectionTestUtils.setField(checkout, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(checkout, "minorProtectionService", minor);
    }

    @Test
    void deniedMinorNeverReachesOrderCreation() {
        when(minor.enforcePurchaseIfEnabled(MEMBER)).thenReturn(MinorDecision.deny(AgeBand.UNKNOWN,
                MinorDecision.AGE_UNKNOWN, "请先在个人资料中填写出生日期"));

        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.createOrReplay(MEMBER, new RegistrationRequest()));

        assertEquals(403, e.getCode());
        assertEquals(MinorDecision.AGE_UNKNOWN, e.getErrorCode());
        verifyNoInteractions(orderService);
    }

    @Test
    void deniedMinorNeverStartsAPaymentAttempt() {
        when(wechatPayService.isReady()).thenReturn(true);
        when(minor.enforcePurchaseIfEnabled(MEMBER)).thenReturn(MinorDecision.deny(AgeBand.AGE_8_15,
                MinorDecision.PURCHASE_CONSENT_REQUIRED, "未成年人购买需监护人授权"));

        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.retryPayment(MEMBER, 77L, false));

        assertEquals(MinorDecision.PURCHASE_CONSENT_REQUIRED, e.getErrorCode());
        verify(registrationService, never()).beginPaymentAttempt(anyLong(), anyLong());
    }

    @Test
    void allowedDecisionKeepsTheExistingFlow() {
        when(minor.enforcePurchaseIfEnabled(MEMBER)).thenReturn(MinorDecision.allow(null));
        CmsRegistration r = new CmsRegistration();
        r.setId(77L);
        r.setRegistrationNo("REG-FREE-77");
        r.setOwnerType(2L);
        r.setPayableAmount(BigDecimal.ZERO);
        r.setPaymentStatus(1);
        RegistrationRequest request = new RegistrationRequest();
        when(orderService.createOrReplay(MEMBER, request))
                .thenReturn(new RegistrationOrderResult(Collections.singletonList(r), false));
        when(registrationService.completeZeroAmountPayment("REG-FREE-77")).thenReturn("SUCCESS");

        assertEquals("报名创建成功", checkout.createOrReplay(MEMBER, request).getMessage());
        verify(orderService).createOrReplay(MEMBER, request);
    }

    @Test
    void legacyRejectionsCarryNoErrorCode() {
        assertNull(new RegistrationCheckoutException(409, "冲突").getErrorCode());
        assertNull(new RegistrationCheckoutException(500, "x", new IllegalStateException()).getErrorCode());
        verify(minor, never()).enforcePurchaseIfEnabled(any());
    }
}
