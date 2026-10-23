package com.chengyinhub.business.service.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 队伍进度矩阵 + 完成阈值 + 排名(Doc2 F1d)。纯函数,不读库。
 *
 * <p>成员退出后仍然正确的关键:一切都<b>按当前在岗成员现算</b>,不存「队伍已完成」快照。
 * <ul>
 *   <li>阈值 required = max(1, ceil(ratio × 在岗人数));退队后在岗人数变小,阈值随之重算。</li>
 *   <li>退出成员的完成记录<b>不计入</b>任何任务的完成人数(否则「人走了分还在」,
 *       队伍可以靠换人刷满阈值);但他的原始完成事实不删,回来重新入队即恢复。</li>
 *   <li>任务的队伍完成时刻 = 在岗成员中第 required 个完成的时刻;排名平局按「最后一个队伍完成任务的时刻」早者在前。</li>
 * </ul>
 */
public final class OrientTeamProgressCalculator {

    private OrientTeamProgressCalculator() {
    }

    /** 单个任务的队伍视角。 */
    public static final class TaskCell {
        public final long taskId;
        public final int completedCount;
        public final boolean teamDone;
        public final Date teamDoneTime;

        TaskCell(long taskId, int completedCount, boolean teamDone, Date teamDoneTime) {
            this.taskId = taskId;
            this.completedCount = completedCount;
            this.teamDone = teamDone;
            this.teamDoneTime = teamDoneTime;
        }
    }

    /** 一支队伍的进度。 */
    public static final class TeamProgress {
        public final long teamId;
        public final int activeCount;
        public final int required;
        /** 在岗成员 → 已完成的任务 id(按任务顺序)。 */
        public final Map<Long, List<Long>> memberDone;
        public final List<TaskCell> tasks;
        public final int teamDoneCount;
        public final Date lastTeamDoneTime;
        /** 被排除的退出成员完成记录条数(透明给前端,方便解释「为什么少了」)。 */
        public final int excludedDepartedFacts;

        TeamProgress(long teamId, int activeCount, int required, Map<Long, List<Long>> memberDone,
                     List<TaskCell> tasks, int teamDoneCount, Date lastTeamDoneTime, int excludedDepartedFacts) {
            this.teamId = teamId;
            this.activeCount = activeCount;
            this.required = required;
            this.memberDone = memberDone;
            this.tasks = tasks;
            this.teamDoneCount = teamDoneCount;
            this.lastTeamDoneTime = lastTeamDoneTime;
            this.excludedDepartedFacts = excludedDepartedFacts;
        }
    }

    /** 在岗人数为 0 时没有阈值可言,返回 0 且任何任务都不算队伍完成。 */
    public static int required(int activeCount, double ratio) {
        if (activeCount <= 0) return 0;
        double r = (ratio > 0.0 && ratio <= 1.0) ? ratio : 1.0;
        int need = (int) Math.ceil(r * activeCount - 1e-9);
        return Math.max(1, Math.min(activeCount, need));
    }

    /**
     * @param facts 成员 → (任务 → 最早完成时刻,可为 null);可以包含已退出成员,这里会排除。
     */
    public static TeamProgress compute(long teamId, List<Long> activeMemberIds, List<Long> taskIds,
                                       Map<Long, Map<Long, Date>> facts, double ratio) {
        int active = activeMemberIds.size();
        int required = required(active, ratio);
        int excluded = 0;
        for (Map.Entry<Long, Map<Long, Date>> e : facts.entrySet()) {
            if (!activeMemberIds.contains(e.getKey())) {
                for (Long taskId : e.getValue().keySet()) {
                    if (taskIds.contains(taskId)) excluded++;
                }
            }
        }
        Map<Long, List<Long>> memberDone = new LinkedHashMap<>();
        for (Long memberId : activeMemberIds) {
            Map<Long, Date> mine = facts.getOrDefault(memberId, Map.of());
            List<Long> done = new ArrayList<>();
            for (Long taskId : taskIds) {
                if (mine.containsKey(taskId)) done.add(taskId);
            }
            memberDone.put(memberId, done);
        }
        List<TaskCell> cells = new ArrayList<>();
        int teamDoneCount = 0;
        Date last = null;
        for (Long taskId : taskIds) {
            List<Date> times = new ArrayList<>();
            int count = 0;
            for (Long memberId : activeMemberIds) {
                Map<Long, Date> mine = facts.getOrDefault(memberId, Map.of());
                if (mine.containsKey(taskId)) {
                    count++;
                    if (mine.get(taskId) != null) times.add(mine.get(taskId));
                }
            }
            boolean done = required > 0 && count >= required;
            Date doneTime = null;
            if (done && times.size() >= required) {
                times.sort(Comparator.naturalOrder());
                doneTime = times.get(required - 1);
            }
            if (done) {
                teamDoneCount++;
                if (doneTime != null && (last == null || doneTime.after(last))) last = doneTime;
            }
            cells.add(new TaskCell(taskId, count, done, doneTime));
        }
        return new TeamProgress(teamId, active, required, memberDone, cells, teamDoneCount, last, excluded);
    }

    /** 排名:队伍完成任务数降序 → 最后完成时刻升序(未知时刻垫后)→ teamId 升序。在岗 0 人的队伍不参与排名。 */
    public static List<TeamProgress> rank(List<TeamProgress> teams) {
        List<TeamProgress> ranked = new ArrayList<>();
        for (TeamProgress t : teams) {
            if (t.activeCount > 0) ranked.add(t);
        }
        ranked.sort(Comparator.<TeamProgress>comparingInt(t -> -t.teamDoneCount)
                .thenComparing(t -> t.lastTeamDoneTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(t -> t.teamId));
        return ranked;
    }
}
