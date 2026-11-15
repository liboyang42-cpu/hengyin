package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.badge.BadgeRule;
import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.support.DailyLoopSettings;
import com.chengyinhub.business.service.support.StreakLedger;

/**
 * 玩家成长体系实现。徽章名称/图标/阈值均从 badge_def(growth_badge)驱动,经 RuleEvaluator 判定。
 *
 * @author cy
 * @date 2026-06-21
 */
@Service
public class PlayerProgressionServiceImpl implements IPlayerProgressionService
{
    @Autowired
    private PlayerGrowthMapper playerGrowthMapper;

    @Autowired
    private PlayerBadgeMapper playerBadgeMapper;

    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired(required = false)
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;

    @Autowired
    private GrowthBadgeMapper growthBadgeMapper;

    @Autowired
    private RuleEvaluator ruleEvaluator;

    /** P7 配置;单测不注入时按默认值(每 7 天 1 张补签卡,最多 2 张)。 */
    @Autowired(required = false)
    private DailyLoopSettings dailyLoopSettings;

    private static final DailyLoopSettings FALLBACK_SETTINGS = new DailyLoopSettings(null);

    @Override
    public PlayerGrowth getGrowth(Long memberId)
    {
        PlayerGrowth g = playerGrowthMapper.selectByMember(memberId);
        if (g == null) {
            g = new PlayerGrowth();
            g.setMemberId(memberId);
            g.setLevel(1);
            g.setTotalCheckins(0);
            g.setTotalMileage(BigDecimal.ZERO);
            g.setStreakDays(0);
            g.setStreakFreezes(0);
            g.setStreakFreezesUsed(0);
        }
        return g;
    }

    @Override
    public int countCheckinPlaces(Long memberId)
    {
        if (memberId == null || memberId <= 0) return 0;
        Integer shops = playerGrowthMapper.countRedeemedShops(memberId);
        Integer stations = playerGrowthMapper.countCompletedCityStations(memberId);
        return (shops == null ? 0 : shops) + (stations == null ? 0 : stations);
    }

    @Override
    public Map<Long, Integer> levelsByMemberIds(Collection<Long> memberIds)
    {
        Map<Long, Integer> out = new HashMap<Long, Integer>();
        if (memberIds == null || memberIds.isEmpty()) return out;
        Set<Long> ids = new LinkedHashSet<Long>();
        for (Long id : memberIds) {
            if (id != null && id > 0) ids.add(id);
        }
        if (ids.isEmpty()) return out;
        for (Long id : ids) {
            out.put(id, 1);   // 兜底口径与 getGrowth 一致:无行 = Lv.1
        }
        List<PlayerGrowth> rows = playerGrowthMapper.selectLevelsByMemberIds(new ArrayList<Long>(ids));
        if (rows != null) {
            for (PlayerGrowth row : rows) {
                if (row != null && row.getMemberId() != null && row.getLevel() != null
                        && out.containsKey(row.getMemberId())) {
                    out.put(row.getMemberId(), Math.max(1, row.getLevel()));
                }
            }
        }
        return out;
    }

    @Override
    public List<PlayerBadge> listBadges(Long memberId)
    {
        return playerBadgeMapper.selectListByMember(memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PlayerBadge> onNodeDone(Long memberId)
    {
        PlayerGrowth g = ensureGrowth(memberId);
        Date checkinTime = currentCheckinTime();
        int checkins = nz(g.getTotalCheckins()) + 1;

        // 连续活跃(P7:与漫游共用一套规则,含补签卡)
        StreakLedger.Outcome streak = advanceStreak(g, checkinTime);

        PlayerGrowth changes = streakChanges(memberId, streak, checkinTime, g);
        changes.setTotalCheckins(checkins);
        changes.setLevel(levelFor(checkins));
        playerGrowthMapper.updatePlayerGrowth(changes);

        List<PlayerBadge> newly = new ArrayList<>();
        awardByThreshold(newly, memberId, "FIRST_STEP", 1, 0L, checkins);
        awardStreakLadder(newly, memberId, streak.streak());
        return newly;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PlayerBadge> onActiveDay(Long memberId)
    {
        List<PlayerBadge> newly = new ArrayList<>();
        if (memberId == null || memberId <= 0) return newly;
        PlayerGrowth g = ensureGrowth(memberId);
        Date activeTime = currentCheckinTime();
        StreakLedger.Outcome streak = advanceStreak(g, activeTime);
        if (!streak.advanced()) return newly;   // 今天已经算过,不重复写
        // 只推进连续天数/补签卡;累计打卡数与等级仍只由节点完成驱动。
        playerGrowthMapper.updatePlayerGrowth(streakChanges(memberId, streak, activeTime, g));
        awardStreakLadder(newly, memberId, streak.streak());
        return newly;
    }

    @Override
    public Map<String, Object> streakSummary(Long memberId)
    {
        PlayerGrowth g = getGrowth(memberId);
        StreakLedger.Policy policy = settings().policy();
        StreakLedger.View view = StreakLedger.view(
                new StreakLedger.State(toLocalDate(g.getLastCheckinDate()), nz(g.getStreakDays()), nz(g.getStreakFreezes())),
                toLocalDate(currentCheckinTime()), policy);
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("currentStreak", view.currentStreak());
        out.put("streakActiveToday", view.activeToday());
        out.put("streakAtRisk", view.atRisk());
        out.put("streakBroken", view.broken());
        out.put("streakFreezes", view.freezesAvailable());
        out.put("streakFreezesPending", view.freezesPending());
        out.put("streakFreezesUsed", nz(g.getStreakFreezesUsed()));
        out.put("streakFreezeMax", policy.freezeMax());
        out.put("streakFreezeEveryDays", policy.freezeEveryDays());
        out.put("streakLadder", DailyLoopSettings.STREAK_LADDER.clone());
        out.put("nextStreakBadgeDays", StreakLedger.nextLadderStep(view.currentStreak(), DailyLoopSettings.STREAK_LADDER));
        out.put("walkMinMeters", settings().walkMinMeters());
        out.put("walkMinCells", settings().walkMinCells());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PlayerBadge> onTopicComplete(Long memberId, Long topicId)
    {
        List<PlayerBadge> newly = new ArrayList<>();
        Long sourceId = topicId == null ? 0L : topicId;
        PlayerGrowth g = ensureGrowth(memberId);

        // 幂等:已通关过该主题(已有 TOPIC_CLEAR 徽章)则里程不再叠加、徽章不再发。
        // 否则重复 checkin/answer/arrive 触发的 completed 会无限累加 totalMileage,污染 MILE_50/排行榜。
        // 实际调用者 grantOnce 先用 TOPIC_FINISH_<topic> 唯一事实裁定首次通关。
        // 此处保持普通读:RR 下对缺失徽章加 gap lock 会与外层先插通关事实的锁序相反。
        if (playerBadgeMapper.selectOne(memberId, "TOPIC_CLEAR", sourceId) != null) {
            return newly;
        }

        BigDecimal mileage = sumChapterMileage(topicId);
        BigDecimal total = nz(g.getTotalMileage()).add(mileage);
        PlayerGrowth changes = new PlayerGrowth();
        changes.setMemberId(memberId);
        changes.setTotalMileage(total);
        changes.setUpdateTime(new Date());
        playerGrowthMapper.updatePlayerGrowth(changes);

        awardByThreshold(newly, memberId, "TOPIC_CLEAR", 2, sourceId, 1L);
        awardByThreshold(newly, memberId, "MILE_50", 3, 0L, total.longValue());
        awardByThreshold(newly, memberId, "MILE_10", 3, 0L, total.longValue());
        return newly;
    }

    // ===== helpers =====

    private StreakLedger.Outcome advanceStreak(PlayerGrowth g, Date activeTime)
    {
        StreakLedger.State state = new StreakLedger.State(
                toLocalDate(g.getLastCheckinDate()), nz(g.getStreakDays()), nz(g.getStreakFreezes()));
        return StreakLedger.onActive(state, toLocalDate(activeTime), settings().policy());
    }

    private PlayerGrowth streakChanges(Long memberId, StreakLedger.Outcome streak, Date activeTime, PlayerGrowth before)
    {
        PlayerGrowth changes = new PlayerGrowth();
        changes.setMemberId(memberId);
        changes.setStreakDays(streak.streak());
        changes.setLastCheckinDate(activeTime);
        changes.setStreakFreezes(streak.freezes());
        if (streak.freezesUsed() > 0) {
            changes.setStreakFreezesUsed(nz(before.getStreakFreezesUsed()) + streak.freezesUsed());
        }
        changes.setUpdateTime(activeTime);
        return changes;
    }

    /** R11 growth-loops 邀请徽章阶梯 1/3/10,source_type 7 = 邀请。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PlayerBadge> onInviteRewarded(Long inviterId, int grantedInvites)
    {
        List<PlayerBadge> newly = new ArrayList<>();
        if (inviterId == null || inviterId <= 0 || grantedInvites <= 0) return newly;
        for (int threshold : new int[] { 10, 3, 1 }) {
            awardByThreshold(newly, inviterId, "INVITE_" + threshold, 7, 0L, grantedInvites);
        }
        return newly;
    }

    /** GAM-04 徽章阶梯 3/7/14/30:按从高到低发(与原 STREAK_7 先于 STREAK_3 的次序一致)。 */
    private void awardStreakLadder(List<PlayerBadge> newly, Long memberId, int streak)
    {
        int[] ladder = DailyLoopSettings.STREAK_LADDER;
        for (int i = ladder.length - 1; i >= 0; i--) {
            awardByThreshold(newly, memberId, "STREAK_" + ladder[i], 4, 0L, streak);
        }
    }

    private DailyLoopSettings settings()
    {
        return dailyLoopSettings != null ? dailyLoopSettings : FALLBACK_SETTINGS;
    }

    /**
     * 写入口专用:先用 uk_member 原子建行/取得行锁,再当前读。
     * 不先锁读缺失行,避免两个首次请求同时持有 gap lock 后争抢 INSERT。
     * REQUIRED 事务延续调用者已有锁,本地锁序为成长行 → 成长徽章写入;普通读 API 不走这里。
     * 上游可能已有积分会员锁或 TOPIC_FINISH 事实锁,本方法不改变上游锁序/重试策略。
     */
    private PlayerGrowth ensureGrowth(Long memberId)
    {
        playerGrowthMapper.ensureGrowthRow(memberId, new Date());
        PlayerGrowth g = playerGrowthMapper.selectByMemberForUpdate(memberId);
        if (g == null) {
            throw new IllegalStateException("玩家成长行建行后回读失败 memberId=" + memberId);
        }
        return g;
    }

    /** 数据驱动阈值发章:读 def 的 unlock_rule_json,observedValue >= threshold 才发。 */
    private void awardByThreshold(List<PlayerBadge> newly, Long memberId, String code,
                                  int sourceType, Long sourceId, long observedValue)
    {
        GrowthBadge d = growthBadgeMapper.selectByCode(code);
        if (d == null) return;
        BadgeRule rule = ruleEvaluator.parse(d.getUnlockRuleJson());
        if (ruleEvaluator.isThresholdMet(rule, observedValue)) {
            addIfNew(newly, memberId, code, sourceType, sourceId);
        }
    }

    /** 幂等发徽章:已有则跳过;新发则加入 newly。名称/图标来自 badge_def,取不到时兜底 code/空串。 */
    private void addIfNew(List<PlayerBadge> newly, Long memberId, String code, int sourceType, Long sourceId)
    {
        if (playerBadgeMapper.selectOne(memberId, code, sourceId) != null) return;
        GrowthBadge d = growthBadgeMapper.selectByCode(code);
        String name = d != null ? d.getBadgeName() : code;
        String icon = d != null ? d.getIconUrl() : "";
        PlayerBadge b = new PlayerBadge();
        b.setMemberId(memberId);
        b.setBadgeCode(code);
        b.setBadgeName(name);
        b.setIconUrl(icon);
        b.setSourceType(sourceType);
        b.setSourceId(sourceId == null ? 0L : sourceId);
        b.setObtainTime(new Date());
        b.setDelFlag(0);
        b.setCreateTime(new Date());
        try {
            playerBadgeMapper.insertPlayerBadge(b);
            newly.add(b);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一键并发兜底:已存在则忽略
        }
    }

    private BigDecimal sumChapterMileage(Long topicId)
    {
        BigDecimal sum = BigDecimal.ZERO;
        if (topicId == null) return sum;
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        List<CmsTopicChapter> chapters = cmsTopicChapterService.selectCmsTopicChapterList(probe);
        if (chapters != null) {
            for (CmsTopicChapter c : chapters) {
                if (c.getTotalMileage() != null) sum = sum.add(c.getTotalMileage());
            }
        }
        // R11 j3 F10:编辑器只把站点间距离算到主题(cms_topic.total_mileage,公里),章节列常为空。
        // 章节没有距离时取主题那一份,否则通关后「累计距离」恒为 0。
        if (sum.signum() == 0 && cmsTopicMapper != null) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
            if (topic != null && topic.getTotalMileage() != null && topic.getTotalMileage().signum() > 0) {
                sum = topic.getTotalMileage();
            }
        }
        return sum;
    }

    /** 锁读后采样一次,使连续天数与持久化打卡日期在跨午夜时仍取同一时点。 */
    protected Date currentCheckinTime() { return new Date(); }

    private int levelFor(int checkins) { return checkins / 5 + 1; }

    private int nz(Integer v) { return v == null ? 0 : v; }

    private BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private LocalDate toLocalDate(Date d)
    {
        if (d == null) return null;
        return Instant.ofEpochMilli(d.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
