package com.chengyinhub.business.service;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.CmsTopicChapter;
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
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerProgressionServiceImplTest {

    private PlayerGrowthMapper growthMapper;
    private PlayerBadgeMapper badgeMapper;
    private GrowthBadgeMapper defMapper;
    private ICmsTopicChapterService chapters;
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
        chapters = mock(ICmsTopicChapterService.class);
        svc = new PlayerProgressionServiceImpl();
        ReflectionTestUtils.setField(svc, "playerGrowthMapper", growthMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(svc, "growthBadgeMapper", defMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(svc, "ruleEvaluator", new RuleEvaluator());
        when(defMapper.selectByCode("FIRST_STEP"))
            .thenReturn(def("FIRST_STEP", "初次启程", "{\"aggregate\":\"exists\",\"threshold\":1}"));
    }

    @Test
    void firstNodeDone_awardsFirstStep_withNameFromDef() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(growthRow(1L, 1)); // 新用户,checkins=1
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
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(g);
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

    @Test
    void nodeUpdateKeepsMileageOutOfWritebackAndPreservesLevelRule() {
        PlayerGrowth row = growthRow(1L, 1);
        row.setTotalCheckins(4);
        row.setTotalMileage(new BigDecimal("27.50"));
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row);

        svc.onNodeDone(1L);

        PlayerGrowth change = writtenGrowth();
        assertEquals(5, change.getTotalCheckins());
        assertEquals(2, change.getLevel(), "等级仍按 checkins / 5 + 1");
        assertEquals(1, change.getStreakDays());
        assertNotNull(change.getLastCheckinDate());
        assertNull(change.getTotalMileage(), "节点写入不得回写读到的旧里程");
        verify(growthMapper).ensureGrowthRow(eq(1L), any(Date.class));
        verify(growthMapper, never()).selectByMember(anyLong());
        verify(growthMapper, never()).insertPlayerGrowth(any());
    }

    @Test
    void sameDayCheckinStillCountsEachCallWithoutExtendingStreak() {
        PlayerGrowth row = growthRow(1L, 2);
        row.setTotalCheckins(8);
        row.setStreakDays(3);
        row.setLastCheckinDate(day(LocalDate.now()));
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row);

        svc.onNodeDone(1L);

        PlayerGrowth change = writtenGrowth();
        assertEquals(9, change.getTotalCheckins());
        assertEquals(3, change.getStreakDays());
        assertEquals(2, change.getLevel());
    }

    @Test
    void yesterdayCheckinExtendsExistingStreak() {
        PlayerGrowth row = growthRow(1L, 2);
        row.setTotalCheckins(9);
        row.setStreakDays(6);
        row.setLastCheckinDate(day(LocalDate.now().minusDays(1)));
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row);

        svc.onNodeDone(1L);

        assertEquals(7, writtenGrowth().getStreakDays());
        assertEquals(3, writtenGrowth().getLevel());
    }

    @Test
    void interruptedCheckinResetsStreakUsingExistingLocalDateRule() {
        PlayerGrowth row = growthRow(1L, 2);
        row.setTotalCheckins(9);
        row.setStreakDays(6);
        row.setLastCheckinDate(day(LocalDate.now().minusDays(2)));
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row);

        svc.onNodeDone(1L);

        assertEquals(1, writtenGrowth().getStreakDays());
    }

    @Test
    void topicUpdateWritesOnlyMileageAndKeepsDataDrivenBadges() {
        PlayerGrowth row = growthRow(1L, 4);
        row.setTotalCheckins(17);
        row.setStreakDays(3);
        row.setLastCheckinDate(day(LocalDate.now()));
        row.setTotalMileage(new BigDecimal("9.75"));
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTotalMileage(new BigDecimal("1.25"));
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(defMapper.selectByCode("TOPIC_CLEAR"))
                .thenReturn(def("TOPIC_CLEAR", "主题通关", "{\"threshold\":1}"));
        when(defMapper.selectByCode("MILE_10"))
                .thenReturn(def("MILE_10", "十公里", "{\"threshold\":10}"));

        List<PlayerBadge> newly = svc.onTopicComplete(1L, 20L);

        PlayerGrowth change = writtenGrowth();
        assertEquals(new BigDecimal("11.00"), change.getTotalMileage());
        assertNull(change.getTotalCheckins());
        assertNull(change.getLevel());
        assertNull(change.getStreakDays());
        assertNull(change.getLastCheckinDate());
        assertTrue(newly.stream().anyMatch(b -> "TOPIC_CLEAR".equals(b.getBadgeCode()) && b.getSourceId().equals(20L)));
        assertTrue(newly.stream().anyMatch(b -> "MILE_10".equals(b.getBadgeCode())));
    }

    @Test
    void existingTopicClearSkipsMileageAndBadgesAfterGrowthLock() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(growthRow(1L, 1));
        when(badgeMapper.selectOne(1L, "TOPIC_CLEAR", 20L)).thenReturn(new PlayerBadge());

        assertTrue(svc.onTopicComplete(1L, 20L).isEmpty());

        org.mockito.InOrder order = inOrder(growthMapper, badgeMapper);
        order.verify(growthMapper).ensureGrowthRow(eq(1L), any(Date.class));
        order.verify(growthMapper).selectByMemberForUpdate(1L);
        order.verify(badgeMapper).selectOne(1L, "TOPIC_CLEAR", 20L);
        verify(growthMapper, never()).updatePlayerGrowth(any());
        verifyNoInteractions(chapters, defMapper);
        verify(badgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void missingTopicClearDefinitionDoesNotInventIdempotencyMarker() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(growthRow(1L, 1));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTotalMileage(BigDecimal.ONE);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));

        assertTrue(svc.onTopicComplete(1L, 20L).isEmpty());

        assertEquals(BigDecimal.ONE, writtenGrowth().getTotalMileage());
        verify(badgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void failedCurrentReadDoesNotReturnAnUnpersistedGrowthRow() {
        assertThrows(IllegalStateException.class, () -> svc.onNodeDone(1L));
        verify(growthMapper, never()).updatePlayerGrowth(any());
        verifyNoInteractions(badgeMapper);
    }

    @Test
    void getGrowthRemainsAnOrdinaryReadWithoutCreatingRows() {
        PlayerGrowth row = svc.getGrowth(1L);
        assertEquals(1, row.getLevel());
        assertEquals(0, row.getTotalCheckins());
        assertEquals(BigDecimal.ZERO, row.getTotalMileage());
        verify(growthMapper).selectByMember(1L);
        verify(growthMapper, never()).ensureGrowthRow(anyLong(), any());
        verify(growthMapper, never()).selectByMemberForUpdate(anyLong());
    }

    private PlayerGrowth writtenGrowth() {
        ArgumentCaptor<PlayerGrowth> changes = ArgumentCaptor.forClass(PlayerGrowth.class);
        verify(growthMapper).updatePlayerGrowth(changes.capture());
        return changes.getValue();
    }

    private Date day(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
