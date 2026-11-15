package com.chengyinhub.business.growthboard;

import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.mapper.GrowthScopedBoardMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * G1 game-loop (GRO-15):成长榜的好友范围与同区范围。
 *
 * <ul>
 *   <li>好友 = 我关注的人 + 同一俱乐部的成员;同区 = 与我同一所在地(ums_member.area_id)。</li>
 *   <li>隐私:别人只有在自己开启「在好友与同区榜上显示我」后才会出现;进度隐私选了「不上榜」的人永不出现。
 *       看榜的本人始终在自己的榜上(只给本人看),无论开没开。</li>
 *   <li>名次口径与全站榜一致:分数降序,同分按注册时间、再按会员号;0 分不上榜也没有名次。</li>
 *   <li>没设所在地时同区榜返回 state = "noArea",不编造一个区。</li>
 * </ul>
 */
@Service
public class GrowthScopedLeaderboard {
    public static final String FRIENDS = "friends";
    public static final String DISTRICT = "district";
    /** 候选上限:好友 / 同区的人再多,也只取这么多来算分。 */
    static final int CANDIDATE_CAP = 2000;

    private final GrowthScopedBoardMapper mapper;
    private Clock clock = Clock.systemDefaultZone();

    public GrowthScopedLeaderboard(GrowthScopedBoardMapper mapper) { this.mapper = mapper; }

    void setClock(Clock clock) { this.clock = clock; }

    /** 规范化范围;不是 friends / district 的一律 null(= 全站榜,走原路径)。 */
    public static String normalizeScope(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return FRIENDS.equals(value) || DISTRICT.equals(value) ? value : null;
    }

    public Map<String, Object> read(long memberId, LeaderboardQueryDTO query, String scope) {
        String metric = "exp".equalsIgnoreCase(query == null ? null : query.getMetric()) ? "exp" : "point";
        String period = "week".equalsIgnoreCase(query == null ? null : query.getPeriod()) ? "week" : "total";
        int limit = clampLimit(query == null ? null : query.getLimit());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", metric);
        out.put("period", period);
        out.put("scope", scope);
        out.put("visible", isVisible(memberId));

        List<Long> others;
        if (DISTRICT.equals(scope)) {
            Long area = mapper.selectAreaId(memberId);
            if (area == null || area <= 0) {
                out.put("state", "noArea");
                out.put("list", List.of());
                out.put("me", emptyMe(memberId));
                return out;
            }
            others = mapper.selectDistrictCandidates(memberId, area, CANDIDATE_CAP);
        } else {
            others = mapper.selectFriendCandidates(memberId, CANDIDATE_CAP);
        }
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(memberId);
        if (others != null) for (Long id : others) if (id != null && id > 0) ids.add(id);

        Date weekStart = null, weekEnd = null;
        if ("week".equals(period)) {
            LocalDate monday = LocalDate.now(clock).with(DayOfWeek.MONDAY);
            ZoneId zone = clock.getZone();
            weekStart = Date.from(monday.atStartOfDay(zone).toInstant());
            weekEnd = Date.from(monday.plusWeeks(1).atStartOfDay(zone).toInstant());
        }
        List<Map<String, Object>> rows = mapper.selectScores(metric, period, weekStart, weekEnd, ids);
        Ranked ranked = rank(rows, memberId, limit);
        out.put("state", "ready");
        out.put("list", ranked.list);
        out.put("me", ranked.me);
        return out;
    }

    public boolean isVisible(long memberId) {
        Integer value = mapper.selectVisible(memberId);
        return value != null && value == 1;
    }

    public boolean setVisible(long memberId, boolean visible) {
        mapper.upsertVisible(memberId, visible ? 1 : 0, Date.from(clock.instant()));
        return isVisible(memberId);
    }

    static final class Ranked {
        final List<LeaderboardItemVO> list;
        final LeaderboardItemVO me;
        Ranked(List<LeaderboardItemVO> list, LeaderboardItemVO me) { this.list = list; this.me = me; }
    }

    /** 纯排名:分数降序,同分按注册时间、再按会员号;0 分不上榜。本人名次按同一顺序数出。 */
    static Ranked rank(List<Map<String, Object>> rows, long viewer, int limit) {
        List<Map<String, Object>> scored = new ArrayList<>();
        Map<String, Object> viewerRow = null;
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row == null || toLong(row.get("memberId")) <= 0) continue;
                if (toLong(row.get("memberId")) == viewer) viewerRow = row;
                if (toLong(row.get("score")) > 0) scored.add(row);
            }
        }
        scored.sort(Comparator
                .comparingLong((Map<String, Object> row) -> toLong(row.get("score"))).reversed()
                .thenComparingLong(row -> createdMillis(row.get("createdAt")))
                .thenComparingLong(row -> toLong(row.get("memberId"))));
        List<LeaderboardItemVO> list = new ArrayList<>();
        Integer viewerRank = null;
        for (int i = 0; i < scored.size(); i++) {
            Map<String, Object> row = scored.get(i);
            if (toLong(row.get("memberId")) == viewer) viewerRank = i + 1;
            if (i < limit) list.add(item(row, i + 1));
        }
        LeaderboardItemVO me = viewerRow == null ? new LeaderboardItemVO() : item(viewerRow, viewerRank);
        me.setMemberId(viewer);
        if (viewerRow == null) me.setScore(0L);
        me.setRank(viewerRank);
        me.setRankPercentage(rankBucket(viewerRank, scored.size()));
        return new Ranked(list, me);
    }

    private static LeaderboardItemVO item(Map<String, Object> row, Integer rank) {
        LeaderboardItemVO vo = new LeaderboardItemVO();
        vo.setRank(rank);
        vo.setMemberId(toLong(row.get("memberId")));
        vo.setNickname(row.get("nickname") == null ? null : String.valueOf(row.get("nickname")));
        vo.setAvatar(row.get("avatar") == null ? null : String.valueOf(row.get("avatar")));
        vo.setScore(toLong(row.get("score")));
        return vo;
    }

    private LeaderboardItemVO emptyMe(long memberId) {
        LeaderboardItemVO me = new LeaderboardItemVO();
        me.setMemberId(memberId);
        me.setScore(0L);
        return me;
    }

    /** 与全站榜同一「TOP X%」分桶。 */
    static String rankBucket(Integer rank, int total) {
        if (rank == null || total <= 0) return null;
        double pct = (double) rank / total;
        if (pct <= 0.2) return "TOP20%";
        if (pct <= 0.4) return "TOP40%";
        if (pct <= 0.6) return "TOP60%";
        if (pct <= 0.8) return "TOP80%";
        return "TOP100%";
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return 50;
        return Math.min(limit, 100);
    }

    private static long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return new java.math.BigDecimal(((String) value).trim()).longValue(); } catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    private static long createdMillis(Object value) {
        if (value instanceof Date) return ((Date) value).getTime();
        if (value instanceof java.time.LocalDateTime) return ((java.time.LocalDateTime) value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Long.MAX_VALUE;
    }
}
