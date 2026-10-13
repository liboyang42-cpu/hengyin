package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementRuleServiceImplTest {

    @Test
    void getActiveRuleFallsBackToPlatformDefault() {
        SettlementRuleMapper mapper = mock(SettlementRuleMapper.class);
        SettlementRuleServiceImpl service = new SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(service, "settlementRuleMapper", mapper);
        SettlementRule platform = new SettlementRule();
        platform.setRuleName("平台默认活动规则");
        platform.setPlatformCommissionRate(new BigDecimal("0.1000"));
        when(mapper.selectActiveRule(9L, "activity")).thenReturn(null);
        when(mapper.selectActiveRule(0L, "activity")).thenReturn(platform);

        SettlementRule rule = service.getActiveRule(9L, "activity");

        assertEquals("平台默认活动规则", rule.getRuleName());
        verify(mapper).selectActiveRule(9L, "activity");
        verify(mapper).selectActiveRule(0L, "activity");
    }

    @Test
    void saveRuleRequiresBizTypeAndRates() {
        SettlementRuleServiceImpl service = new SettlementRuleServiceImpl();
        SettlementRule rule = new SettlementRule();

        assertThrows(IllegalArgumentException.class, () -> service.saveRule(rule));
    }
}
