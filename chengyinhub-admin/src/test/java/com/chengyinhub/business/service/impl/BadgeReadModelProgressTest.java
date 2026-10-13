package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.badge.BadgeRule;
import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.domain.vo.BadgeWallItemVO;
import com.chengyinhub.business.domain.vo.BadgeWallV2VO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BadgeReadModelProgressTest {
    private static final Long MEMBER_ID = 73L;
    private static final long CURRENT_MILEAGE = 12L;
    private static final long TARGET_MILEAGE = 50L;
    private static final int EXPECTED_PERCENT = 24;

    @Test
    void lockedThresholdBadgeExposesServerCalculatedProgressAndNextMilestone() {
        GrowthBadgeMapper definitions = mock(GrowthBadgeMapper.class);
        PlayerBadgeMapper awards = mock(PlayerBadgeMapper.class);
        PlayerGrowthMapper growth = mock(PlayerGrowthMapper.class);
        RuleEvaluator evaluator = new RuleEvaluator();
        BadgeReadModelServiceImpl service = new BadgeReadModelServiceImpl();
        ReflectionTestUtils.setField(service, "growthBadgeMapper", definitions);
        ReflectionTestUtils.setField(service, "playerBadgeMapper", awards);
        ReflectionTestUtils.setField(service, "playerGrowthMapper", growth);
        ReflectionTestUtils.setField(service, "ruleEvaluator", evaluator);

        GrowthBadge definition = new GrowthBadge();
        definition.setBadgeCode("MILESTONE_TEST");
        definition.setAssetType("GROWTH");
        definition.setUnlockRuleJson("{\"aggregate\":\"sum\",\"aggregate_field\":\"mileage\",\"threshold\":"
                + TARGET_MILEAGE + "}");
        PlayerGrowth current = new PlayerGrowth();
        current.setTotalMileage(BigDecimal.valueOf(CURRENT_MILEAGE));
        when(definitions.selectAllActive()).thenReturn(Collections.singletonList(definition));
        when(awards.selectListByMember(MEMBER_ID)).thenReturn(Collections.emptyList());
        when(growth.selectByMember(MEMBER_ID)).thenReturn(current);

        BadgeWallV2VO wall = service.buildWallV2(MEMBER_ID);
        BadgeWallItemVO item = wall.getGrowth().get(0);

        assertFalse(item.isUnlocked());
        assertEquals(Long.valueOf(CURRENT_MILEAGE), item.getCurrentValue());
        assertEquals(Long.valueOf(TARGET_MILEAGE), item.getTargetValue());
        assertEquals(Integer.valueOf(EXPECTED_PERCENT), item.getProgressPercent());
        assertEquals(Boolean.TRUE, item.getNextMilestone());
    }
}
