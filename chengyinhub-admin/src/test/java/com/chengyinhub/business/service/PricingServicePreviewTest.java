package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * previewTopic 阵容折算单测:share_rate 百分数→小数、Σ固定费、guided 带队摊薄。mock CoopOrderMapper。
 */
class PricingServicePreviewTest {

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

    private CoopOrder share(BigDecimal rate) {
        CoopOrder o = new CoopOrder();
        o.setShareMode(1);
        o.setShareRate(rate);
        return o;
    }

    private CoopOrder fixed(BigDecimal fee) {
        CoopOrder o = new CoopOrder();
        o.setShareMode(2);
        o.setFixedFee(fee);
        return o;
    }

    @Test
    void self_shareAndFixed_pMin() {
        // 分成30% + 固定15 = 2 个非club被邀商家 → maxN=2,guaranteeRate=0.09(§3.4 进地板);
        // C=15,denom=1-0.10-0.09-0.30=0.51,ceil(15/0.51)=29.42(旧漏保底=25.00)
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Arrays.asList(share(new BigDecimal("30.00")), fixed(new BigDecimal("15"))));

        PricingResult r = svc.previewTopic(100L, null, null, "self");

        assertEquals(0, new BigDecimal("0.30").compareTo(r.getSumShareRate()));
        assertEquals(0, new BigDecimal("15").compareTo(r.getSumFixedFee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getLeadCostPerHead()));
        assertEquals(0, new BigDecimal("29.42").compareTo(r.getPriceMin()));
        assertEquals(2, r.getLineup().size());
    }

    @Test
    void guided_addsLeadCostPerHead() {
        // 同上阵容(maxN=2,guaranteeRate=0.09)+ guided 带队 100/10=10 → C=25,denom=0.51,ceil(25/0.51)=49.02
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Arrays.asList(share(new BigDecimal("30.00")), fixed(new BigDecimal("15"))));

        PricingResult r = svc.previewTopic(100L, new BigDecimal("100"), 10, "guided");

        assertEquals(0, new BigDecimal("10").compareTo(r.getLeadCostPerHead()));
        assertEquals(0, new BigDecimal("49.02").compareTo(r.getPriceMin()));
    }

    @Test
    void perkCost_foldsIntoFloor_fromDeclaredPerks() {
        // T10:同 self 阵容(share30%+fixed15,maxN=2→guaranteeRate=0.09),已申报权益单人成本合计6
        // → C = 15 + 6 = 21,denom = 1-0.10-0.09-0.30 = 0.51,ceil(21/0.51) = 41.18(底价随权益上升)
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Arrays.asList(share(new BigDecimal("30.00")), fixed(new BigDecimal("15"))));
        when(coopPerkMapper.sumUnitCostByTopicId(100L)).thenReturn(new BigDecimal("6"));

        PricingResult r = svc.previewTopic(100L, null, null, "self");

        assertEquals(0, new BigDecimal("6").compareTo(r.getPerkCost()), "权益成本进成本构成");
        assertEquals(0, new BigDecimal("41.18").compareTo(r.getPriceMin()), "底价随权益上升");
    }

    // CU-M-32:预览回带 canConfirm,与 confirmPrice 的 lifecycle==2 闸同口径;未到定价阶段前端事先置灰。
    @Test
    void canConfirm_onlyInPricingLifecycle() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topics);
        CmsTopic reviewing = new CmsTopic();
        reviewing.setLifecycle(1);
        when(topics.selectCmsTopicLifecycleById(100L)).thenReturn(reviewing);
        assertFalse(svc.previewTopic(100L, null, null, "self").getCanConfirm());

        CmsTopic pricing = new CmsTopic();
        pricing.setLifecycle(2);
        when(topics.selectCmsTopicLifecycleById(100L)).thenReturn(pricing);
        assertTrue(svc.previewTopic(100L, null, null, "self").getCanConfirm());

        when(topics.selectCmsTopicLifecycleById(100L)).thenReturn(null);
        assertFalse(svc.previewTopic(100L, null, null, "self").getCanConfirm());
    }

    /* CU-C-70(2026-09-24 走查):有人带时填带队成本 120、成团人数 4,地板价 35.30;
       把人数改成 0,地板价与终价都变 0 且「确认终价并开售」仍可点。
       根因是 `guided && teamSize > 0` 这个短路条件把「人数非正」当成了「无人带」,
       带队成本被静默摊成 0 → 地板价归零。这一档必须显式报错。 */

    @Test
    void guided_leadCostWithoutTeamSizeIsRejectedInsteadOfSilentlyZeroed() {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any())).thenReturn(Collections.emptyList());

        IllegalStateException zero = assertThrows(IllegalStateException.class,
                () -> svc.previewTopic(100L, new BigDecimal("120"), 0, "guided"));
        assertTrue(zero.getMessage().contains("成团人数"), zero.getMessage());

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> svc.previewTopic(100L, new BigDecimal("120"), null, "guided"));
        assertTrue(missing.getMessage().contains("成团人数"), missing.getMessage());

        IllegalStateException negative = assertThrows(IllegalStateException.class,
                () -> svc.previewTopic(100L, new BigDecimal("120"), -2, "guided"));
        assertTrue(negative.getMessage().contains("成团人数"), negative.getMessage());
    }

    /* 集成复审:负的带队成本会把地板价往下拉,confirmPrice 又按这份地板校验 —— 资损路径必须拒。 */
    @Test
    void guided_negativeLeadCostIsRejected() {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any())).thenReturn(Collections.emptyList());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.previewTopic(100L, new BigDecimal("-500"), 1, "guided"));
        assertTrue(ex.getMessage().contains("不能为负"), ex.getMessage());
    }

    @Test
    void guided_withoutAnyLeadCostStillPreviewsWithZeroHeadCost() {
        // 带队成本还没填时不该报错:此时没有被静默抹掉的成本,地板价照常只由固定费/权益构成。
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any())).thenReturn(Collections.emptyList());

        PricingResult r = svc.previewTopic(100L, null, null, "guided");

        assertEquals(0, BigDecimal.ZERO.compareTo(r.getLeadCostPerHead()));
    }

    @Test
    void self_ignoresTeamSizeEntirely() {
        // self 票不摊带队成本,人数怎么填都不该影响地板(也不该因人数为 0 被拦)。
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(any()))
                .thenReturn(Collections.singletonList(fixed(new BigDecimal("20"))));

        assertEquals(0, BigDecimal.ZERO.compareTo(svc.previewTopic(100L, new BigDecimal("120"), 0, "self").getLeadCostPerHead()));
        assertEquals(0, BigDecimal.ZERO.compareTo(svc.previewTopic(100L, null, null, "self").getLeadCostPerHead()));
    }
}
