package com.chengyinhub.business.service;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.service.impl.PlayerProgressionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerProgressionServiceImplTest {

    private PlayerGrowthMapper growthMapper;
    private PlayerBadgeMapper badgeMapper;
    private GrowthBadgeMapper defMapper;
    private PlayerProgressionServiceImpl svc;

    private GrowthBadge def(String code, String name, String ruleJson) {
        GrowthBadge g = new GrowthBadge();
        g.setBadgeCode(code); g.setBadgeName(name); g.setIconUrl("icon/" + code);
        g.setUnlockRuleJson(ruleJson);
        return g;
    }

    @BeforeEach
    void setUp() {
        growthMapper = mock(PlayerGrowthMapper.class);
        badgeMapper = mock(PlayerBadgeMapper.class);
        defMapper = mock(GrowthBadgeMapper.class);
        svc = new PlayerProgressionServiceImpl();
        ReflectionTestUtils.setField(svc, "playerGrowthMapper", growthMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(svc, "growthBadgeMapper", defMapper);
        ReflectionTestUtils.setField(svc, "ruleEvaluator", new RuleEvaluator());
        when(defMapper.selectByCode("FIRST_STEP"))
            .thenReturn(def("FIRST_STEP", "初次启程", "{\"aggregate\":\"exists\",\"threshold\":1}"));
    }

    @Test
    void firstNodeDone_awardsFirstStep_withNameFromDef() {
        when(growthMapper.selectByMember(1L)).thenReturn(null); // 新用户,checkins=1
        List<PlayerBadge> newly = svc.onNodeDone(1L);
        assertTrue(newly.stream().anyMatch(b -> "FIRST_STEP".equals(b.getBadgeCode())));
        assertEquals("初次启程", newly.stream()
            .filter(b -> "FIRST_STEP".equals(b.getBadgeCode())).findFirst().get().getBadgeName());
        verify(badgeMapper).insertPlayerBadge(argThat(b -> "FIRST_STEP".equals(b.getBadgeCode())));
    }

    @Test
    void secondNodeDone_doesNotReAwardFirstStep() {
        PlayerGrowth g = new PlayerGrowth();
        g.setMemberId(1L); g.setTotalCheckins(5);
        when(growthMapper.selectByMember(1L)).thenReturn(g);
        when(badgeMapper.selectOne(eq(1L), eq("FIRST_STEP"), anyLong())).thenReturn(new PlayerBadge());
        List<PlayerBadge> newly = svc.onNodeDone(1L);
        assertTrue(newly.stream().noneMatch(b -> "FIRST_STEP".equals(b.getBadgeCode())));
    }

    private PlayerGrowth growthRow(Long memberId, Integer level) {
        PlayerGrowth g = new PlayerGrowth();
        g.setMemberId(memberId);
        g.setLevel(level);
        return g;
    }

    /** RUN-13 批量读口:无行成员兜底 Lv.1(与 getGrowth 同口径),有行取库值,map 覆盖每个入参。 */
    @Test
    void levelsByMemberIds_missingRow_defaultsToLevel1() {
        when(growthMapper.selectLevelsByMemberIds(anyList()))
            .thenReturn(java.util.Arrays.asList(growthRow(7L, 4), growthRow(8L, 0)));
        java.util.Map<Long, Integer> levels = svc.levelsByMemberIds(java.util.Arrays.asList(7L, 8L, 9L));
        assertEquals(4, levels.get(7L));
        assertEquals(1, levels.get(8L), "库里脏值 0 也兜到 Lv.1,不出 Lv.0");
        assertEquals(1, levels.get(9L), "无行 = Lv.1");
        assertTrue(svc.levelsByMemberIds(null).isEmpty());
        assertTrue(svc.levelsByMemberIds(java.util.Arrays.asList(null, 0L, -1L)).isEmpty(),
                "非法 id 不进 IN 查询");
    }
}
