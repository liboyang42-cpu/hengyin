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

    @Autowired
    private GrowthBadgeMapper growthBadgeMapper;

    @Autowired
    private RuleEvaluator ruleEvaluator;

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
        }
        return g;
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

        // 连续打卡
        LocalDate today = toLocalDate(checkinTime);
        LocalDate last = toLocalDate(g.getLastCheckinDate());
        int streak;
        if (last == null) streak = 1;
        else if (last.isEqual(today)) streak = Math.max(1, nz(g.getStreakDays()));
        else if (last.plusDays(1).isEqual(today)) streak = nz(g.getStreakDays()) + 1;
        else streak = 1;

        PlayerGrowth changes = new PlayerGrowth();
        changes.setMemberId(memberId);
        changes.setTotalCheckins(checkins);
        changes.setStreakDays(streak);
        changes.setLastCheckinDate(checkinTime);
        changes.setLevel(levelFor(checkins));
        changes.setUpdateTime(checkinTime);
        playerGrowthMapper.updatePlayerGrowth(changes);

        List<PlayerBadge> newly = new ArrayList<>();
        awardByThreshold(newly, memberId, "FIRST_STEP", 1, 0L, checkins);
        awardByThreshold(newly, memberId, "STREAK_7", 4, 0L, streak);
        awardByThreshold(newly, memberId, "STREAK_3", 4, 0L, streak);
        return newly;
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
