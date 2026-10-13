package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.badge.BadgeRule;
import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.vo.BadgeWallItemVO;
import com.chengyinhub.business.domain.vo.BadgeWallV2VO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.service.IBadgeReadModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@Service
public class BadgeReadModelServiceImpl implements IBadgeReadModelService {

    @Autowired private GrowthBadgeMapper growthBadgeMapper;
    @Autowired private PlayerBadgeMapper playerBadgeMapper;
    @Autowired private PlayerGrowthMapper playerGrowthMapper;
    @Autowired private RuleEvaluator ruleEvaluator;

    @Override
    public BadgeWallV2VO buildWallV2(Long memberId) {
        // 1) 已获得事实:code -> unlockTime
        Map<String, Date> owned = new HashMap<>();
        List<PlayerBadge> pbs = playerBadgeMapper.selectListByMember(memberId);
        if (pbs != null) for (PlayerBadge b : pbs) owned.putIfAbsent(b.getBadgeCode(), b.getObtainTime());
        PlayerGrowth growth = playerGrowthMapper == null ? null : playerGrowthMapper.selectByMember(memberId);
        Set<Long> clearedTopics = new HashSet<>();
        if (pbs != null) for (PlayerBadge b : pbs) {
            if ("TOPIC_CLEAR".equals(b.getBadgeCode()) && b.getSourceId() != null) {
                clearedTopics.add(b.getSourceId());
            }
        }

        // 2) 全集定义 -> 按 asset_type 分组 + 标 unlocked
        BadgeWallV2VO vo = new BadgeWallV2VO();
        List<GrowthBadge> defs = growthBadgeMapper.selectAllActive();
        if (defs != null) for (GrowthBadge d : defs) {
            BadgeWallItemVO item = new BadgeWallItemVO();
            item.setBadgeCode(d.getBadgeCode());
            item.setBadgeName(d.getBadgeName());
            item.setNameEn(d.getNameEn());
            item.setStatement(d.getStatement());
            item.setIconUrl(d.getIconUrl());
            item.setAssetType(d.getAssetType());
            item.setCategory(d.getCategory());
            item.setUnlockHint(d.getUnlockHint());
            boolean has = owned.containsKey(d.getBadgeCode());
            item.setUnlocked(has);
            item.setUnlockTime(has ? owned.get(d.getBadgeCode()) : null);
            projectProgress(item, d, growth, clearedTopics.size(), has);
            String t = d.getAssetType() == null ? "" : d.getAssetType();
            switch (t) {
                case "IDENTITY":   vo.getIdentity().add(item); break;
                case "GROWTH":     vo.getGrowth().add(item); break;
                case "COLLECTION": vo.getCollection().add(item); break;
                case "HONOR":      vo.getHonor().add(item); break;
                default: /* 无 asset_type 的旧行归 growth 兜底 */ vo.getGrowth().add(item);
            }
        }
        markNextMilestone(vo);
        return vo;
    }

    private void projectProgress(BadgeWallItemVO item, GrowthBadge definition, PlayerGrowth growth,
                                 int clearedTopicCount, boolean unlocked) {
        if (ruleEvaluator == null) return;
        BadgeRule rule = ruleEvaluator.parse(definition.getUnlockRuleJson());
        if (rule.getThreshold() == Long.MAX_VALUE || rule.getThreshold() <= 0) return;
        Long current = observedValue(definition, rule, growth, clearedTopicCount, unlocked);
        if (current == null) return;
        long target = rule.getThreshold();
        item.setCurrentValue(Math.min(current, target));
        item.setTargetValue(target);
        item.setProgressPercent((int) Math.min(100L, current * 100L / target));
        item.setNextMilestone(false);
    }

    private Long observedValue(GrowthBadge definition, BadgeRule rule, PlayerGrowth growth,
                               int clearedTopicCount, boolean unlocked) {
        String field = rule.getAggregateField();
        if ("mileage".equals(field)) {
            return growth == null || growth.getTotalMileage() == null
                    ? null : growth.getTotalMileage().longValue();
        }
        if ("active_day".equals(field)) {
            return growth == null || growth.getStreakDays() == null
                    ? null : growth.getStreakDays().longValue();
        }
        if ("topicId".equals(field)) return (long) clearedTopicCount;
        if ("exists".equals(rule.getAggregate())) {
            if ("node_done".equals(definition.getTriggerEvent())) {
                return growth == null || growth.getTotalCheckins() == null
                        ? null : Math.min(1L, growth.getTotalCheckins().longValue());
            }
            if ("topic_complete".equals(definition.getTriggerEvent())) {
                return Math.min(1L, clearedTopicCount);
            }
            return unlocked ? 1L : null;
        }
        return null;
    }

    private void markNextMilestone(BadgeWallV2VO wall) {
        BadgeWallItemVO next = null;
        List<List<BadgeWallItemVO>> groups = java.util.Arrays.asList(
                wall.getIdentity(), wall.getGrowth(), wall.getCollection(), wall.getHonor());
        for (List<BadgeWallItemVO> group : groups) {
            for (BadgeWallItemVO item : group) {
                if (item.isUnlocked() || item.getProgressPercent() == null) continue;
                if (next == null || item.getProgressPercent() > next.getProgressPercent()) next = item;
            }
        }
        if (next != null) next.setNextMilestone(true);
    }
}
