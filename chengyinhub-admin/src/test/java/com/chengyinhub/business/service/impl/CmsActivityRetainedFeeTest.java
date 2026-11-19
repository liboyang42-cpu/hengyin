package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MC-007:部分退款留存部分的平台费按报名冻结费率计,不按结算时的当前规则费率。
 * 当前规则费率只作为冻结费率为空的存量单的回落值传进 SQL。
 */
class CmsActivityRetainedFeeTest {

    private CmsRegistrationMapper registrations;
    private CmsActivityServiceImpl service;
    private SettlementRule rule;

    @BeforeEach
    void setUp() {
        registrations = mock(CmsRegistrationMapper.class);
        service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.20")); // 结算时已调高的 live 费率
    }

    @Test
    void retainedFeeUsesFrozenRegistrationRateNotCurrentRule() {
        // 留存 50 元、报名冻结 0.10 ⇒ 5.00;若按当前 0.20 会是 10.00
        when(registrations.selectRetainedPlatformFeeAfterPartialRefundByActivityId(9L, new BigDecimal("0.20")))
                .thenReturn(new BigDecimal("5.0000"));

        BigDecimal fee = service.retainedPlatformFee(9L, new BigDecimal("50"), rule);

        assertEquals(new BigDecimal("5.00"), fee);
        verify(registrations).selectRetainedPlatformFeeAfterPartialRefundByActivityId(9L, new BigDecimal("0.20"));
    }

    @Test
    void roundsOnceHalfUpToCents() {
        when(registrations.selectRetainedPlatformFeeAfterPartialRefundByActivityId(9L, new BigDecimal("0.20")))
                .thenReturn(new BigDecimal("3.335"));
        assertEquals(new BigDecimal("3.34"), service.retainedPlatformFee(9L, new BigDecimal("33.35"), rule));
    }

    @Test
    void unreadableFeeNeverBecomesZero() {
        when(registrations.selectRetainedPlatformFeeAfterPartialRefundByActivityId(9L, new BigDecimal("0.20")))
                .thenReturn(null);
        assertEquals(new BigDecimal("10.00"), service.retainedPlatformFee(9L, new BigDecimal("50"), rule));
    }
}
