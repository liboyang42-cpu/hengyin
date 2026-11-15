package com.chengyinhub.business.service.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.vo.DailyMissionBoardVO;
import com.chengyinhub.business.mapper.DailyLoopMapper;
import com.chengyinhub.business.mapper.GrowthMissionMapper;

/**
 * P7(GRO-06)每日任务:哪些任务由系统按真实事件自动记录、今天数到多少。只读,不发奖。
 * 发奖在 DailyLoopRecorder → IGrowthService.completeDailyMission。
 */
@Component
public class DailyMissionBoardReader
{
    /** 自动记录的每日任务事件。growth_mission.trigger_event 取这些值的任务不接受客户端自报。 */
    public enum Event
    {
        WALK("daily_walk"), REVEAL("daily_reveal"), PLAY("daily_play"), SHOP("daily_shop");

        private final String code;
        Event(String code) { this.code = code; }
        public String code() { return code; }

        public static Event of(String code)
        {
            if (code == null) return null;
            for (Event e : values()) {
                if (e.code.equalsIgnoreCase(code.trim())) return e;
            }
            return null;
        }
    }

    public static boolean isAutoTracked(String triggerEvent) { return Event.of(triggerEvent) != null; }

    @Autowired
    private GrowthMissionMapper growthMissionMapper;

    @Autowired
    private DailyLoopMapper dailyLoopMapper;

    @Autowired(required = false)
    private DailyLoopSettings settings;

    private static final DailyLoopSettings FALLBACK = new DailyLoopSettings(null);

    /** 今天这个事件的真实次数。 */
    public int todayCount(Long memberId, Event event)
    {
        if (memberId == null || memberId <= 0 || event == null) return 0;
        DailyLoopSettings cfg = settings != null ? settings : FALLBACK;
        switch (event) {
            case WALK: return dailyLoopMapper.countQualifyingWalksToday(memberId, cfg.walkMinMeters(), cfg.walkMinCells());
            case REVEAL: return dailyLoopMapper.countRevealedCellsToday(memberId);
            case PLAY: return dailyLoopMapper.countNodesPlayedToday(memberId);
            case SHOP: return dailyLoopMapper.countShopsVisitedToday(memberId);
            default: return 0;
        }
    }

    /** 今天已记完成的每日任务编码。 */
    public List<String> completedToday(Long memberId)
    {
        if (memberId == null || memberId <= 0) return Collections.emptyList();
        List<String> codes = dailyLoopMapper.selectMissionCodesCompletedToday(memberId);
        return codes == null ? Collections.<String>emptyList() : codes;
    }

    public static int target(GrowthMission mission)
    {
        Integer t = mission == null ? null : mission.getTargetCount();
        return t == null || t < 1 ? 1 : t;
    }

    /** 今日任务板:只列启用的自动记录任务,按 sort_no。没有配置任务时 missions 为空(客户端显示诚实的空态)。 */
    public DailyMissionBoardVO board(Long memberId)
    {
        DailyMissionBoardVO vo = new DailyMissionBoardVO();
        vo.setDate(LocalDate.now().toString());
        if (memberId == null || memberId <= 0) return vo;
        List<GrowthMission> active = growthMissionMapper.selectActiveMissionList();
        if (active == null || active.isEmpty()) return vo;
        Set<String> done = new HashSet<>(completedToday(memberId));
        java.util.Map<Event, Integer> counts = new java.util.EnumMap<>(Event.class);
        List<DailyMissionBoardVO.Item> items = new ArrayList<>();
        int completed = 0;
        for (GrowthMission m : active) {
            Event event = Event.of(m.getTriggerEvent());
            if (event == null) continue;
            int count = counts.computeIfAbsent(event, e -> todayCount(memberId, e));
            int target = target(m);
            DailyMissionBoardVO.Item item = new DailyMissionBoardVO.Item();
            item.setMissionCode(m.getMissionCode());
            item.setMissionName(m.getMissionName());
            item.setMissionDesc(m.getMissionDesc());
            item.setEvent(event.code());
            item.setTarget(target);
            boolean isDone = done.contains(m.getMissionCode());
            item.setCompleted(isDone);
            item.setProgress(isDone ? target : Math.min(Math.max(0, count), target));
            item.setExpReward(m.getExpReward() == null ? 0 : Math.max(0, m.getExpReward()));
            if (isDone) completed++;
            items.add(item);
        }
        vo.setMissions(items);
        vo.setCompletedCount(completed);
        vo.setAllDone(!items.isEmpty() && completed == items.size());
        return vo;
    }
}
