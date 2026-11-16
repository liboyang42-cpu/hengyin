package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RewardDropRuleServiceTest {

    @Test
    void validatesScopesWeightsAndJson() {
        RewardDropRuleMapper mapper = mock(RewardDropRuleMapper.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        RewardDropRuleServiceImpl service = new RewardDropRuleServiceImpl(mapper, configService);

        RewardDropRule badTopic = new RewardDropRule();
        badTopic.setScope(1);
        badTopic.setDropType(1);
        badTopic.setWeight(100);
        assertThrows(IllegalArgumentException.class, () -> service.insertRule(badTopic));

        RewardDropRule badWeight = new RewardDropRule();
        badWeight.setScope(0);
        badWeight.setDropType(1);
        badWeight.setWeight(0);
        assertThrows(IllegalArgumentException.class, () -> service.insertRule(badWeight));

        RewardDropRule valid = new RewardDropRule();
        valid.setScope(2);
        valid.setSceneKey(" roam_merchant_poi ");
        valid.setDropType(1);
        valid.setWeight(60);
        valid.setParamJson("{\"mult\":2}");
        when(mapper.insertRewardDropRule(any())).thenAnswer(inv -> {
            RewardDropRule r = inv.getArgument(0);
            r.setId(5L);
            return 1;
        });
        when(mapper.selectRewardDropRuleById(5L)).thenReturn(valid);

        RewardDropRule saved = service.insertRule(valid);
        assertEquals("roam_merchant_poi", saved.getSceneKey());
        assertNull(saved.getTopicId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewsWeightsSharesAndExpectedXp() {
        RewardDropRuleMapper mapper = mock(RewardDropRuleMapper.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("reward.drop.base.prob.bp")).thenReturn("2000");

        RewardDropRule r1 = new RewardDropRule();
        r1.setId(1L);
        r1.setScope(0);
        r1.setDropType(1);
        r1.setWeight(50);
        r1.setParamJson("{\"mult\":3}");

        RewardDropRule r2 = new RewardDropRule();
        r2.setId(2L);
        r2.setScope(0);
        r2.setDropType(2);
        r2.setWeight(50);
        r2.setParamJson("{\"badgeCode\":\"FRAG\"}");

        when(mapper.selectApplicableRules(null, "topic_node")).thenReturn(List.of(r1, r2));

        RewardDropRuleServiceImpl service = new RewardDropRuleServiceImpl(mapper, configService);
        Map<String, Object> preview = service.previewProbabilities(null, "topic_node");

        assertEquals(2000, preview.get("baseProbBp"));
        assertEquals(100, preview.get("totalWeight"));
        // 20% drop chance * 50% share * (+200% extra XP for mult=3) = +20.0%
        assertEquals(20.0, ((Number) preview.get("expectedExtraXpPercent")).doubleValue(), 0.01);
        List<Map<String, Object>> breakdown = (List<Map<String, Object>>) preview.get("rules");
        assertEquals(2, breakdown.size());
        assertEquals(50.0, ((Number) breakdown.get(0).get("sharePercent")).doubleValue(), 0.01);
    }

    @Test
    void updatesExistingSysConfigRowOrInsertsNewOne() {
        RewardDropRuleMapper mapper = mock(RewardDropRuleMapper.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        RewardDropRuleServiceImpl service = new RewardDropRuleServiceImpl(mapper, configService);

        assertThrows(IllegalArgumentException.class, () -> service.updateBaseProbBp(10001, "admin"));

        SysConfig existing = new SysConfig();
        existing.setConfigId(99L);
        existing.setConfigKey("reward.drop.base.prob.bp");
        existing.setConfigValue("1500");
        when(configService.selectConfigList(any())).thenReturn(List.of(existing));
        when(configService.selectConfigByKey("reward.drop.base.prob.bp")).thenReturn("2500");

        int saved = service.updateBaseProbBp(2500, "ops");
        assertEquals(2500, saved);
        verify(configService).updateConfig(argThat(c -> c.getConfigId().equals(99L) && "2500".equals(c.getConfigValue())));
    }
}
