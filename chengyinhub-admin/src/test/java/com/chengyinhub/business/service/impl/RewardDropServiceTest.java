package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RewardDropLog;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropLogMapper;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M6 随机掉落契约测试(纯 Mockito):概率无偏 / 加权分布 / 幂等(重放·并发) / 描述组装。
 */
class RewardDropServiceTest {

    private RewardDropRule rule(int dropType, int weight, String param) {
        RewardDropRule r = new RewardDropRule();
        r.setDropType(dropType);
        r.setWeight(weight);
        r.setParamJson(param);
        r.setStatus(1);
        return r;
    }

    // ---------- 概率无偏(规格:1000 次误差 <±2%;此处放大 N 稳定验证无偏) ----------
    @Test
    void hitRateMatchesBaseProbabilityWithinTolerance() {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        int probBp = 1500; // 15%
        int n = 50000, hits = 0;
        for (int i = 0; i < n; i++) {
            if (s.hit(probBp)) hits++;
        }
        double rate = (double) hits / n;
        assertTrue(Math.abs(rate - 0.15) < 0.01, "hit rate ~15% (spec ±2%), got " + rate);
    }

    @Test
    void hitEdgeProbabilities() {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        assertTrue(!s.hit(0), "0 概率永不命中");
        assertTrue(s.hit(10000), "满概率必命中");
    }

    // ---------- 加权抽取分布 ≈ 权重比 ----------
    @Test
    void weightedPickFollowsWeights() {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        List<RewardDropRule> rules = Arrays.asList(
                rule(1, 60, "{\"mult\":2}"),
                rule(2, 30, "{\"badgeCode\":\"F\"}"),
                rule(3, 10, "{\"couponTemplateId\":1}"));
        int n = 60000;
        int[] cnt = new int[4];
        for (int i = 0; i < n; i++) {
            RewardDropRule p = s.weightedPick(rules);
            cnt[p.getDropType()]++;
        }
        double p1 = (double) cnt[1] / n, p2 = (double) cnt[2] / n, p3 = (double) cnt[3] / n;
        assertTrue(Math.abs(p1 - 0.60) < 0.03, "type1 ~0.60, got " + p1);
        assertTrue(Math.abs(p2 - 0.30) < 0.03, "type2 ~0.30, got " + p2);
        assertTrue(Math.abs(p3 - 0.10) < 0.03, "type3 ~0.10, got " + p3);
    }

    @Test
    void weightedPickHandlesEmptyOrZeroWeight() {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        assertNull(s.weightedPick(null));
        assertNull(s.weightedPick(new ArrayList<>()));
        assertNull(s.weightedPick(Arrays.asList(rule(1, 0, "{}"))));
    }

    // ---------- 描述组装 ----------
    @Test
    void buildDropXpMultiplierComputesExtraXp() {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        Map<String, Object> d = s.buildDrop(rule(1, 1, "{\"mult\":3}"), 10L);
        assertEquals(1, d.get("dropType"));
        assertEquals(3, d.get("mult"));
        assertEquals(20L, d.get("extraXp")); // 10 * (3-1)
    }

    @Test
    void buildDropBadgeAndCoupon() {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        Map<String, Object> frag = s.buildDrop(rule(2, 1, "{\"badgeCode\":\"STAR\"}"), 10L);
        assertEquals("STAR", frag.get("badgeCode"));
        Map<String, Object> coupon = s.buildDrop(rule(3, 1, "{\"couponTemplateId\":99}"), 10L);
        assertEquals(99, ((Number) coupon.get("couponTemplateId")).intValue());
    }

    // ---------- rollDrop 幂等 / 灰度 / 并发 ----------
    private RewardDropServiceImpl serviceWith(RewardDropRuleMapper rm, RewardDropLogMapper lm, ISysConfigService cs) {
        RewardDropServiceImpl s = new RewardDropServiceImpl();
        ReflectionTestUtils.setField(s, "ruleMapper", rm);
        ReflectionTestUtils.setField(s, "logMapper", lm);
        ReflectionTestUtils.setField(s, "couponSourceGrantMapper", mock(com.chengyinhub.business.mapper.CouponSourceGrantMapper.class));
        ReflectionTestUtils.setField(s, "configService", cs);
        return s;
    }

    private ISysConfigService configEnabledAlwaysHit() {
        ISysConfigService cs = mock(ISysConfigService.class);
        when(cs.selectConfigByKey("feature.flag.drops")).thenReturn("true");
        when(cs.selectConfigByKey("reward.drop.base.prob.bp")).thenReturn("10000"); // 必命中,去随机性
        return cs;
    }

    @Test
    void rollDropDisabledByFeatureFlagReturnsNull() {
        ISysConfigService cs = mock(ISysConfigService.class);
        when(cs.selectConfigByKey("feature.flag.drops")).thenReturn("false");
        RewardDropServiceImpl s = serviceWith(mock(RewardDropRuleMapper.class), mock(RewardDropLogMapper.class), cs);
        assertNull(s.rollDrop(1L, 2L, 3L, 0L, 10L));
    }

    @Test
    void rollDropReturnsDropWhenHitAndClaimed() {
        RewardDropRuleMapper rm = mock(RewardDropRuleMapper.class);
        RewardDropLogMapper lm = mock(RewardDropLogMapper.class);
        when(lm.countByUk(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(0);
        when(rm.selectApplicableRules(anyLong(), any())).thenReturn(Arrays.asList(rule(1, 100, "{\"mult\":2}")));
        when(lm.insertRewardDropLog(any())).thenAnswer(invocation -> { ((RewardDropLog) invocation.getArgument(0)).setId(44L); return 1; });
        RewardDropServiceImpl s = serviceWith(rm, lm, configEnabledAlwaysHit());

        Map<String, Object> d = s.rollDrop(1L, 2L, 3L, 0L, 10L);
        assertNotNull(d);
        assertEquals(1, d.get("dropType"));
        assertEquals(10L, d.get("extraXp"));
    }

    @Test
    void rollDropRoutesRoamRulesAndPersistsTheExactScene() {
        RewardDropRuleMapper rm = mock(RewardDropRuleMapper.class);
        RewardDropLogMapper lm = mock(RewardDropLogMapper.class);
        when(lm.countByUk(eq(1L), eq(0L), eq(3L), eq(9L),
                eq(IRewardDropService.SCENE_ROAM_MERCHANT_POI))).thenReturn(0);
        when(rm.selectApplicableRules(eq(0L), eq(IRewardDropService.SCENE_ROAM_MERCHANT_POI)))
                .thenReturn(Arrays.asList(rule(2, 100, "{\"badgeCode\":\"ROAM\"}")));
        when(lm.insertRewardDropLog(any())).thenAnswer(invocation -> { ((RewardDropLog) invocation.getArgument(0)).setId(44L); return 1; });
        RewardDropServiceImpl s = serviceWith(rm, lm, configEnabledAlwaysHit());

        Map<String, Object> d = s.rollDrop(1L, 0L, 3L, 9L,
                IRewardDropService.SCENE_ROAM_MERCHANT_POI, 20L);

        assertEquals("ROAM", d.get("badgeCode"));
        assertEquals(IRewardDropService.SCENE_ROAM_MERCHANT_POI, d.get("sceneKey"));
        ArgumentCaptor<RewardDropLog> log = ArgumentCaptor.forClass(RewardDropLog.class);
        verify(lm).insertRewardDropLog(log.capture());
        assertEquals(IRewardDropService.SCENE_ROAM_MERCHANT_POI, log.getValue().getSceneKey());
    }

    @Test
    void legacyRollDropUsesTopicNodeScene() {
        RewardDropRuleMapper rm = mock(RewardDropRuleMapper.class);
        RewardDropLogMapper lm = mock(RewardDropLogMapper.class);
        when(lm.countByUk(eq(1L), eq(2L), eq(3L), eq(0L),
                eq(IRewardDropService.SCENE_TOPIC_NODE))).thenReturn(0);
        when(rm.selectApplicableRules(eq(2L), eq(IRewardDropService.SCENE_TOPIC_NODE)))
                .thenReturn(Arrays.asList(rule(1, 100, "{\"mult\":2}")));
        when(lm.insertRewardDropLog(any())).thenAnswer(invocation -> { ((RewardDropLog) invocation.getArgument(0)).setId(44L); return 1; });
        RewardDropServiceImpl s = serviceWith(rm, lm, configEnabledAlwaysHit());

        Map<String, Object> d = s.rollDrop(1L, 2L, 3L, 0L, 10L);

        assertEquals(IRewardDropService.SCENE_TOPIC_NODE, d.get("sceneKey"));
    }

    @Test
    void rollDropIdempotentWhenAlreadyDropped() {
        RewardDropLogMapper lm = mock(RewardDropLogMapper.class);
        when(lm.countByUk(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(1); // 已掉过
        RewardDropServiceImpl s = serviceWith(mock(RewardDropRuleMapper.class), lm, configEnabledAlwaysHit());
        assertNull(s.rollDrop(1L, 2L, 3L, 0L, 10L)); // 重放不再掉
    }

    @Test
    void rollDropConcurrentDuplicateKeyReturnsNull() {
        RewardDropRuleMapper rm = mock(RewardDropRuleMapper.class);
        RewardDropLogMapper lm = mock(RewardDropLogMapper.class);
        when(lm.countByUk(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(0);
        when(rm.selectApplicableRules(anyLong(), any())).thenReturn(Arrays.asList(rule(1, 100, "{\"mult\":2}")));
        when(lm.insertRewardDropLog(any())).thenThrow(new DuplicateKeyException("uk"));
        RewardDropServiceImpl s = serviceWith(rm, lm, configEnabledAlwaysHit());
        assertNull(s.rollDrop(1L, 2L, 3L, 0L, 10L)); // 并发抢占失败,不多掉
    }
    @Test
    void recoveryUsesOnlyOriginalCouponPayloadAndLogId() {
        RewardDropLogMapper logs = mock(RewardDropLogMapper.class);
        RewardDropLog original = new RewardDropLog(); original.setId(44L); original.setMemberId(7L);
        original.setDropType(3); original.setParamJson("{\"couponTemplateId\":88}"); original.setSceneKey("roam_merchant_poi");
        when(logs.selectByUk(7L,0L,8L,9L,"roam_merchant_poi")).thenReturn(original);
        RewardDropServiceImpl service = serviceWith(mock(RewardDropRuleMapper.class), logs, mock(ISysConfigService.class));
        Map<String,Object> drop = service.findOriginalDrop(7L,0L,8L,9L,"roam_merchant_poi");
        assertEquals(44L, drop.get("dropLogId"));
        assertEquals(88, drop.get("couponTemplateId"));
        org.mockito.Mockito.verify(logs, org.mockito.Mockito.never()).insertRewardDropLog(any());
        original.setDropType(1);
        assertNull(service.findOriginalDrop(7L,0L,8L,9L,"roam_merchant_poi"));
    }
    @Test
    void newCouponDropMustPersistPendingIntentWithSameOriginalLogId() {
        RewardDropRuleMapper rules = mock(RewardDropRuleMapper.class);
        RewardDropLogMapper logs = mock(RewardDropLogMapper.class);
        com.chengyinhub.business.mapper.CouponSourceGrantMapper receipts = mock(com.chengyinhub.business.mapper.CouponSourceGrantMapper.class);
        when(rules.selectApplicableRules(anyLong(), any())).thenReturn(Arrays.asList(rule(3,100,"{\"couponTemplateId\":88}")));
        when(logs.insertRewardDropLog(any())).thenAnswer(i -> { ((RewardDropLog)i.getArgument(0)).setId(44L); return 1; });
        when(receipts.ensureIntent("reward_drop",44L,7L,88L)).thenReturn(1);
        RewardDropServiceImpl service = serviceWith(rules,logs,configEnabledAlwaysHit());
        ReflectionTestUtils.setField(service,"couponSourceGrantMapper",receipts);
        assertEquals(44L,service.rollDrop(7L,0L,8L,9L,"roam_merchant_poi",20L).get("dropLogId"));
        verify(receipts).ensureIntent("reward_drop",44L,7L,88L);
        when(receipts.ensureIntent("reward_drop",44L,7L,88L)).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.rollDrop(7L,0L,8L,9L,"roam_merchant_poi",20L));
    }
}
