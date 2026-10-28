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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 业主 10-28：结算不需要监护授权。结算服务不再持有未成年挂点，建单 / 发起支付不按年龄段拦截。 */
class MinorCheckoutGateTest {
    private static final Long MEMBER = 9901L;

    private ICmsRegistrationService registrationService;
    private IRegistrationOrderService orderService;
    private IWechatPayService wechatPayService;
    private RegistrationCheckoutServiceImpl checkout;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        orderService = mock(IRegistrationOrderService.class);
        wechatPayService = mock(IWechatPayService.class);
        checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", registrationService);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", orderService);
        ReflectionTestUtils.setField(checkout, "wechatPayService", wechatPayService);
    }

    @Test
    void checkoutHoldsNoMinorProtectionHook() {
        Field[] fields = RegistrationCheckoutServiceImpl.class.getDeclaredFields();
        assertTrue(Arrays.stream(fields).noneMatch(f -> IMinorProtectionService.class.isAssignableFrom(f.getType())),
                "结算不需要监护授权，不得再注入未成年挂点");
    }

    @Test
    void orderCreationGoesStraightToTheOrderService() {
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
    }
}
