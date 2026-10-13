package com.chengyinhub.business.service.support;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 宾果九格的定义与达成判定。
 *
 * <p>★ 九个格子**全部只依据玩家在这个主题下的节点完成记录**(时间与去重节点数)。
 * 这是有意收窄的:分享次数、微信步数、印章这些格子听起来更丰富,但它们的事件源
 * 在别的模块,现在接不上 —— 放进来就是「格子永远填不满」的假保证。
 * 等那些事件源接通了再加格,加格只改这个类。
 *
 * <p>纯函数,不碰 IO,所以判定规则能被一个不连库的测试逐条盯住。
 */
public final class BingoCells {

    /** 一格的定义。position 与 BingoCard 的位序一一对应。 */
    public static final class Cell {
        private final int position;
        private final String code;
        private final int target;
        private final String label;

        Cell(int position, String code, int target, String label) {
            this.position = position;
            this.code = code;
            this.target = target;
            this.label = label;
        }

        public int getPosition() { return position; }
        public String getCode() { return code; }
        public int getTarget() { return target; }
        public String getLabel() { return label; }
    }

    /** 玩家在这个主题下的完成情况。只有两项 —— 多的现在也算不出来。 */
    public static final class Progress {
        private final Set<Long> doneNodeIds;
        private final List<Date> checkinTimes;

        public Progress(Collection<Long> doneNodeIds, Collection<Date> checkinTimes) {
            this.doneNodeIds = doneNodeIds == null ? new HashSet<>() : new HashSet<>(doneNodeIds);
            this.checkinTimes = checkinTimes == null ? new ArrayList<>() : new ArrayList<>(checkinTimes);
        }

        int nodeCount() { return doneNodeIds.size(); }
        List<Date> times() { return checkinTimes; }
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 九格,顺序即位序。target 是达成阈值。 */
    private static final Cell[] CELLS = {
        new Cell(0, "NODE_1",   1, "完成第 1 个点"),
        new Cell(1, "NODE_3",   3, "完成 3 个点"),
        new Cell(2, "NODE_5",   5, "完成 5 个点"),
        new Cell(3, "BURST_3",  3, "一天内完成 3 个点"),
        new Cell(4, "DAYS_2",   2, "两天各来一次"),
        new Cell(5, "STREAK_2", 2, "连续两天来"),
        new Cell(6, "WEEKEND",  1, "周末来一次"),
        new Cell(7, "NODE_8",   8, "完成 8 个点"),
        new Cell(8, "EARLY",    1, "中午前完成一次"),
    };

    private BingoCells() {
    }

    public static List<Cell> cells() {
        List<Cell> list = new ArrayList<>();
        for (Cell cell : CELLS) list.add(cell);
        return list;
    }

    /**
     * 按当前进度算出**应该被填上**的格子位图。
     *
     * <p>返回的是"当前该是什么样",不是"这次新填了哪些" —— 调用方把它与库里已有的
     * filled_mask 位或即可。这样即使某次事件漏掉了,下一次完成会把它补上,
     * 不需要补偿任务。
     */
    public static int evaluate(Progress progress) {
        if (progress == null) return 0;
        int mask = 0;
        int nodes = progress.nodeCount();
        if (nodes <= 0) return 0;

        // 按自然日归拢完成时间
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        boolean weekend = false;
        boolean early = false;
        for (Date time : progress.times()) {
            if (time == null) continue;
            java.time.ZonedDateTime local = Instant.ofEpochMilli(time.getTime()).atZone(ZONE);
            LocalDate day = local.toLocalDate();
            perDay.merge(day, 1, Integer::sum);
            DayOfWeek dow = day.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) weekend = true;
            if (local.getHour() < 12) early = true;
        }

        for (Cell cell : CELLS) {
            boolean hit;
            switch (cell.getCode()) {
                case "NODE_1":
                case "NODE_3":
                case "NODE_5":
                case "NODE_8":
                    hit = nodes >= cell.getTarget();
                    break;
                case "BURST_3":
                    hit = perDay.values().stream().anyMatch(n -> n >= cell.getTarget());
                    break;
                case "DAYS_2":
                    hit = perDay.size() >= cell.getTarget();
                    break;
                case "STREAK_2":
                    hit = hasConsecutiveDays(perDay.keySet(), cell.getTarget());
                    break;
                case "WEEKEND":
                    hit = weekend;
                    break;
                case "EARLY":
                    hit = early;
                    break;
                default:
                    // 不认识的格子一律不填 —— 加了 code 却忘了判定分支时,
                    // 表现成"这一格永远填不上",而不是"莫名其妙就满了"
                    hit = false;
            }
            if (hit) mask = BingoCard.fill(mask, cell.getPosition());
        }
        return mask;
    }

    /** 有没有连着 n 天。 */
    private static boolean hasConsecutiveDays(Collection<LocalDate> days, int need) {
        if (days.size() < need) return false;
        List<LocalDate> sorted = new ArrayList<>(new java.util.TreeSet<>(days));
        int run = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).plusDays(1).equals(sorted.get(i))) {
                run++;
                if (run >= need) return true;
            } else {
                run = 1;
            }
        }
        return run >= need;
    }
}
