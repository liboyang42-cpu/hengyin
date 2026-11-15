package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.support.DailyLoopSettings;
import com.chengyinhub.business.service.support.DailyMissionBoardReader;
import com.chengyinhub.business.service.support.DailyMissionBoardReader.Event;

/**
 * P7 daily-loop:把真实事件(漫游结算 / 点亮迷雾格 / 完成节点 / 到店)接到连续天数和每日任务上。
 *
 * <p>都在<strong>调用方事务提交之后</strong>、另起一个事务做:每日任务和连续天数是附带效果,
 * 它们出错只记日志,绝不回滚漫游结算或节点完成;提交后再数,也保证刚写入的事实已经可见。
 * 没有事务同步(单测、脚本)时直接执行。</p>
 */
@Service
public class DailyLoopRecorder
{
    private static final Logger log = LoggerFactory.getLogger(DailyLoopRecorder.class);

    @Autowired
    private DailyMissionBoardReader boardReader;

    @Autowired
    private GrowthMissionMapper growthMissionMapper;

    @Autowired
    private IGrowthService growthService;

    @Autowired
    private IPlayerProgressionService progressionService;

    @Autowired(required = false)
    private DailyLoopSettings settings;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    private static final DailyLoopSettings FALLBACK = new DailyLoopSettings(null);

    /**
     * 漫游结算后:达标(里程或新格子)就算今天活跃,推进连续天数;再记「今日漫游」「点亮迷雾」任务。
     * sink 收到 {qualified, streak..., newBadges, missionsCompleted},调用方可放进结算回执;可为 null。
     */
    public void afterWalkFinished(Long memberId, Integer distanceM, Integer newCells, Consumer<Map<String, Object>> sink)
    {
        afterCommit("walk", memberId, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean qualified = cfg().walkQualifies(distanceM, newCells);
            out.put("qualified", qualified);
            List<String> badges = new ArrayList<>();
            if (qualified) {
                for (PlayerBadge b : progressionService.onActiveDay(memberId)) badges.add(b.getBadgeCode());
            }
            out.put("newBadges", badges);
            int done = trackNow(memberId, Event.WALK) + trackNow(memberId, Event.REVEAL);
            out.put("missionsCompleted", done);
            out.putAll(progressionService.streakSummary(memberId));
            if (sink != null) sink.accept(out);
        });
    }

    /** 点亮迷雾格后:记「点亮迷雾」任务(数今天的真实格子数)。 */
    public void afterCellsRevealed(Long memberId, int newlyRevealed)
    {
        if (newlyRevealed <= 0) return;
        afterCommit("reveal", memberId, () -> trackNow(memberId, Event.REVEAL));
    }

    /** 完成游戏节点后:记「玩一个节点」任务。连续天数已由 onNodeDone 在原事务里推进。 */
    public void afterNodePlayed(Long memberId)
    {
        afterCommit("play", memberId, () -> trackNow(memberId, Event.PLAY));
    }

    /** 漫游到店入账后:记「逛一家店」任务。 */
    public void afterShopVisited(Long memberId, boolean recorded)
    {
        if (!recorded) return;
        afterCommit("shop", memberId, () -> trackNow(memberId, Event.SHOP));
    }

    /** 数今天的事实,够目标就记完成(幂等:同一任务同一天只发一次)。返回本次新记完成的条数。 */
    int trackNow(Long memberId, Event event)
    {
        List<GrowthMission> missions = growthMissionMapper.selectActiveByEvent(event.code());
        if (missions == null || missions.isEmpty()) return 0;
        // 点亮批次很密:今天已记完的任务直接跳过,不再数事实、不再写。
        java.util.Set<String> done = new java.util.HashSet<>(boardReader.completedToday(memberId));
        if (missions.stream().allMatch(m -> done.contains(m.getMissionCode()))) return 0;
        int count = boardReader.todayCount(memberId, event);
        int accepted = 0;
        for (GrowthMission m : missions) {
            if (!done.contains(m.getMissionCode()) && count >= DailyMissionBoardReader.target(m)) {
                accepted += growthService.completeDailyMission(memberId, m);
            }
        }
        return accepted;
    }

    private void afterCommit(String what, Long memberId, Runnable work)
    {
        if (memberId == null || memberId <= 0) return;
        Runnable guarded = () -> runIsolated(what, memberId, work);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { guarded.run(); }
            });
        } else {
            guarded.run();
        }
    }

    private void runIsolated(String what, Long memberId, Runnable work)
    {
        try {
            if (transactionManager == null) {
                work.run();
                return;
            }
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            log.warn("event=DAILY_LOOP_SKIPPED kind={} member={} reason={}", what, memberId, e.getClass().getSimpleName());
        }
    }

    private DailyLoopSettings cfg() { return settings != null ? settings : FALLBACK; }
}
