package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [资损] 定价地板须含发起人保底率(§3.4:P_min=C/(1−平台率−保底率−Σ分成率))。
 * 漏减保底率会放行一个结算时 margin<0、守恒断言拒结、销售款悬空转人工的价格。
 */
class PricingGuaranteeFloorTest {

    private CoopOrderMapper coopOrderMapper;
    private CoopPerkMapper coopPerkMapper;
    private PricingServiceImpl svc;

    @BeforeEach
    void setUp() {
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopPerkMapper = mock(CoopPerkMapper.class);
        svc = new PricingServiceImpl();
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopPerkMapper", coopPerkMapper);
    }

    private CoopOrder share(String rate) { // toType 为 null → 非 club → 计入 maxMerchantCount
        CoopOrder o = new CoopOrder();
        o.setShareMode(1);
        o.setShareRate(new BigDecimal(rate));
        return o;
    }

    private CoopOrder fixed(String fee) {
        CoopOrder o = new CoopOrder();
        o.setShareMode(2);
        o.setFixedFee(new BigDecimal(fee));
        return o;
    }

    @Test
    void floorIncludesGuaranteeRate() {
        // 2 个非club被邀商家(share30%+fixed15)→ maxN=2 → guaranteeRate=0.05+0.02×2=0.09。
        // C=15,denom=1−0.10−0.09−0.30=0.51,P_min=ceil(15/0.51)=29.42(漏保底旧值=25.00)。
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Arrays.asList(share("30.00"), fixed("15")));

        PricingResult r = svc.previewTopic(100L, null, null, "self");

        assertEquals(0, new BigDecimal("29.42").compareTo(r.getPriceMin()),
                "地板须含保底率(2商家→9%):15/(1−0.10−0.09−0.30)=29.42,而非漏保底的25.00");
    }

    @Test
    void unsettleableLineupRefusedAtPricing() {
        // 5 个非club分成商家 S=0.80 → maxN=5 → guaranteeRate=0.15;
        // denom=1−0.10−0.15−0.80=−0.05≤0 → 定价阶段即拒(漏保底旧值 denom=0.10 会放行 P_min=100 → 结算才爆)。
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Arrays.asList(share("16.00"), share("16.00"), share("16.00"), share("16.00"), share("16.00")));
        when(coopPerkMapper.sumUnitCostByTopicId(any())).thenReturn(new BigDecimal("10"));

        assertThrows(IllegalStateException.class,
                () -> svc.previewTopic(100L, null, null, "self"),
                "分成+保底≥100%的阵容应在定价阶段就被拒,而非结算才抛守恒异常");
    }

    @Test
    void floorCoversCostAfterAllRateDeductions() {
        // 根因契约:P_min×denom ≥ C(地板保证按销售额扣完平台+保底+分成后仍能覆盖成本 → 结算 margin≥0)。
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Arrays.asList(share("30.00"), fixed("15")));

        PricingResult r = svc.previewTopic(100L, null, null, "self");
        BigDecimal denom = BigDecimal.ONE
                .subtract(new BigDecimal("0.10"))   // platform
                .subtract(new BigDecimal("0.09"))   // guaranteeRate(maxN=2)
                .subtract(new BigDecimal("0.30"));  // sumShareRate
        BigDecimal covered = r.getPriceMin().multiply(denom);
        assertTrue(covered.compareTo(r.getCostC()) >= 0,
                "P_min×(1−平台−保底−分成) 须 ≥ 成本C,否则结算 margin<0");
    }
}
