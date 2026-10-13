package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.service.impl.SettlementRuleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HF-01(A-30) 结算规则比例上限校验单测。rate 为小数(0.1=10%)。
 * 验收:四方比例之和 &gt;1.0 拒绝保存(且不落库);=1.0 与 &lt;1.0 通过。
 */
class SettlementRuleServiceImplTest {

    private SettlementRuleMapper mapper;
    private SettlementRuleServiceImpl svc;

    @BeforeEach
    void setUp() {
        mapper = mock(SettlementRuleMapper.class);
        svc = new SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(svc, "settlementRuleMapper", mapper);
        when(mapper.insertSettlementRule(any())).thenReturn(1);
    }

    private SettlementRule rule(String platform, String merchant, String creator, String inviter) {
        return rule("activity", platform, merchant, creator, inviter);
    }

    private SettlementRule rule(String bizType, String platform, String merchant,
                                String creator, String inviter) {
        SettlementRule r = new SettlementRule();
        r.setBizType(bizType);
        r.setPlatformCommissionRate(new BigDecimal(platform));
        r.setMerchantRate(new BigDecimal(merchant));
        r.setCreatorRate(new BigDecimal(creator));
        r.setInviterRate(new BigDecimal(inviter));
        return r;
    }

    @Test
    void sumOverOne_rejected_andNotPersisted() {
        // merchant 0.9 + creator 0.5 + platform 0.1 = 1.5 > 1.0 → 超分,拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.saveRule(rule("0.1", "0.9", "0.5", "0")));
        assertEquals(true, ex.getMessage().contains("不得超过"));
        verify(mapper, never()).insertSettlementRule(any());
    }

    @Test
    void sumExactlyOne_ok() {
        // 默认口径 platform 0.1 + merchant 0.9 = 1.0 → 通过
        int rows = svc.saveRule(rule("0.1", "0.9", "0", "0"));
        assertEquals(1, rows);
        verify(mapper, times(1)).insertSettlementRule(any());
    }

    @Test
    void sumBelowOne_ok_platformKeepsRemainder() {
        // platform 0.1 + merchant 0.8 = 0.9 < 1.0 → 允许(平台留存 0.1)
        int rows = svc.saveRule(rule("0.1", "0.8", "0", "0"));
        assertEquals(1, rows);
        verify(mapper, times(1)).insertSettlementRule(any());
    }

    @Test
    void negativeRate_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.saveRule(rule("0.1", "-0.2", "0", "0")));
        verify(mapper, never()).insertSettlementRule(any());
    }

    /**
     * H8「creatorRate 全并 merchant」是持久属性,不是一次性数据动作。
     * 一次性迁移只覆盖 merchant_id=0 的三条规则、两条读侧结算闸也只读全局规则 ——
     * 后台随手建一条 merchant_id=77 的 topic 规则(creator_rate=0.20)就能重新造出发布者份额,
     * 且四方和 0.9 &lt;= 1.0 完全过得了既有的 requireSumWithinOne。写侧必须自己拦。
     */
    @Test
    void h8BizTypes_creatorRateNonZero_rejected_andNotPersisted() {
        for (String bizType : new String[] {"topic", "topic_club", "activity"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> svc.saveRule(rule(bizType, "0.1", "0.6", "0.2", "0")),
                    bizType + " 必须拒绝非零 creator_rate");
            assertEquals(true, ex.getMessage().contains("creatorRate"),
                    "错误文案必须指明是 creator_rate 这道闸,别被相邻的和值闸接住:" + ex.getMessage());
        }
        verify(mapper, never()).insertSettlementRule(any());
    }

    @Test
    void nonH8BizType_creatorRateNonZero_stillAllowed() {
        // H8 只裁决 topic / topic_club / activity 三类;优惠券等其余 biz_type 不在裁决范围内,
        // 闸不许扩大到它们身上(扩大 = 越界改别的玩法的资金语义)。
        int rows = svc.saveRule(rule("coupon", "0.1", "0.7", "0.2", "0"));
        assertEquals(1, rows);
        verify(mapper, times(1)).insertSettlementRule(any());
    }
}
