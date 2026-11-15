package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.RewardDropLog;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropLogMapper;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M6 随机掉落实现。
 *
 * <p>核心保证:
 * <ul>
 *   <li>幂等:reward_drop_log 的 uk(member,topic,node,activity) + 掷骰前 countByUk 预检 + 并发 DuplicateKey 兜底
 *       → 同一节点重放/并发绝不多掉;</li>
 *   <li>无偏:统一可审计随机核心 {@link GameRandom}（SecureRandom 默认）掷骰 + 按 weight 抽取类型。</li>
 * </ul>
 */
@Service
public class RewardDropServiceImpl implements IRewardDropService {

    private static final int DEFAULT_PROB_BP = 1500; // 15%

    @Autowired
    private RewardDropRuleMapper ruleMapper;
    @Autowired
    private RewardDropLogMapper logMapper;
    @Autowired
    private ISysConfigService configService;
    @Autowired
    private com.chengyinhub.business.mapper.CouponSourceGrantMapper couponSourceGrantMapper;
    @Autowired(required = false)
    private GameRandom gameRandom;

    private GameRandom effectiveRandom() {
        if (gameRandom == null) {
            gameRandom = new GameRandom();
        }
        return gameRandom;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollDrop(Long memberId, Long topicId, Long nodeId, Long activityId,
                                        String sceneKey, long nodeXp) {
        if (memberId == null || nodeId == null || sceneKey == null || sceneKey.trim().isEmpty()) {
            return null;
        }
        if (!dropsEnabled()) {
            return null;
        }
        Long tId = topicId == null ? 0L : topicId;
        Long aId = activityId == null ? 0L : activityId;

        // 1) 幂等预检:已掉过不再掷
        if (logMapper.countByUk(memberId, tId, nodeId, aId, sceneKey) > 0) {
            return null;
        }
        String receiptPrefix = "drop:" + memberId + ":" + tId + ":" + nodeId + ":" + aId + ":" + sceneKey;
        // 2) 掷骰（写入 game_random_audit REWARD_DROP）
        if (!effectiveRandom().hitBp(baseProbBp(), receiptPrefix + ":hit", memberId, "reward_drop", String.valueOf(nodeId))) {
            return null;
        }
        // 3) 按 weight 抽类型
        List<RewardDropRule> rules = ruleMapper.selectApplicableRules(tId, sceneKey);
        IGameRandomService.PickResult<RewardDropRule> picked = effectiveRandom().weightedPick(
                IGameRandomService.DOMAIN_REWARD_DROP,
                rules,
                this::weightOf,
                receiptPrefix + ":pick",
                memberId,
                "reward_drop_rule",
                String.valueOf(nodeId),
                r -> r.getDropType() + ":" + r.getId());
        if (picked == null || picked.item() == null) {
            return null;
        }
        RewardDropRule pick = picked.item();
        // 4) 组描述 + 占位(uk 抢占,失败=并发已掉)
        Map<String, Object> drop = buildDrop(pick, nodeXp);
        drop.put("sceneKey", sceneKey);
        drop.put("randomReceiptId", picked.draw().receiptId());
        RewardDropLog log = new RewardDropLog();
        log.setMemberId(memberId);
        log.setTopicId(tId);
        log.setNodeId(nodeId);
        log.setActivityId(aId);
        log.setSceneKey(sceneKey);
        log.setDropType(pick.getDropType());
        log.setParamJson(pick.getParamJson());
        log.setCreatedAt(new Date());
        try {
            int rows = logMapper.insertRewardDropLog(log);
            if (rows <= 0) {
                return null;
            }
        } catch (DuplicateKeyException e) {
            return null; // 并发已占位,不重复掉
        }
        if (log.getId() == null) throw new IllegalStateException("掉落流水缺少原ID");
        if (Integer.valueOf(3).equals(log.getDropType())) {
            Long couponId = parseParam(log.getParamJson()).getLong("couponTemplateId");
            if (couponId != null && couponId > 0) {
                if (couponSourceGrantMapper.ensureIntent("reward_drop", log.getId(), memberId, couponId) != 1) {
                    throw new IllegalStateException("掉落原券交付意图写入失败");
                }
            }
        }
        drop.put("dropLogId", log.getId());
        return drop;
    }

    @Override
    public Map<String, Object> findOriginalDrop(Long memberId, Long topicId, Long nodeId, Long activityId, String sceneKey) {
        if (memberId == null || nodeId == null || sceneKey == null) return null;
        RewardDropLog log = logMapper.selectByUk(memberId, topicId == null ? 0L : topicId, nodeId,
                activityId == null ? 0L : activityId, sceneKey);
        if (log == null || log.getId() == null || !Integer.valueOf(3).equals(log.getDropType())) return null;
        RewardDropRule original = new RewardDropRule();
        original.setDropType(log.getDropType());
        original.setParamJson(log.getParamJson());
        Map<String, Object> drop = buildDrop(original, 0L);
        drop.put("sceneKey", log.getSceneKey());
        drop.put("dropLogId", log.getId());
        return drop;
    }

    // ---------- 可测方法(包级) ----------

    /** 掷骰命中:GameRandom 万分判定 */
    boolean hit(int probBp) {
        if (probBp <= 0) {
            return false;
        }
        if (probBp >= 10000) {
            return true;
        }
        return effectiveRandom().nextInt(10000) < probBp;
    }

    /** 按 weight 加权抽取 */
    RewardDropRule weightedPick(List<RewardDropRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        int total = 0;
        for (RewardDropRule r : rules) {
            total += weightOf(r);
        }
        if (total <= 0) {
            return null;
        }
        int r = effectiveRandom().nextInt(total);
        int acc = 0;
        for (RewardDropRule rule : rules) {
            acc += weightOf(rule);
            if (r < acc) {
                return rule;
            }
        }
        return rules.get(rules.size() - 1);
    }

    /** 按类型组掉落描述(供前端展示 + 上层入账) */
    Map<String, Object> buildDrop(RewardDropRule rule, long nodeXp) {
        Map<String, Object> d = new HashMap<>();
        int type = rule.getDropType() == null ? 0 : rule.getDropType();
        d.put("dropType", type);
        JSONObject param = parseParam(rule.getParamJson());
        if (type == 1) { // XP 倍率
            int mult = param.getIntValue("mult", 2);
            if (mult < 1) {
                mult = 2;
            }
            d.put("mult", mult);
            d.put("extraXp", nodeXp * (mult - 1));
            d.put("label", "探索值 ×" + mult);
        } else if (type == 2) { // 徽章碎片
            String badgeCode = param.getString("badgeCode");
            d.put("badgeCode", badgeCode == null ? "FRAGMENT" : badgeCode);
            d.put("label", "徽章碎片");
        } else if (type == 3) { // 商家彩蛋券
            d.put("couponTemplateId", param.get("couponTemplateId"));
            d.put("label", "商家彩蛋券");
        } else {
            d.put("label", "神秘奖励");
        }
        return d;
    }

    private int weightOf(RewardDropRule r) {
        if (r == null || r.getWeight() == null) {
            return 0;
        }
        return Math.max(0, r.getWeight());
    }

    private boolean dropsEnabled() {
        String v = safeConfig("feature.flag.drops");
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private int baseProbBp() {
        String v = safeConfig("reward.drop.base.prob.bp");
        if (v == null) {
            return DEFAULT_PROB_BP;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PROB_BP;
        }
    }

    private String safeConfig(String key) {
        try {
            return configService.selectConfigByKey(key);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject parseParam(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            JSONObject o = JSON.parseObject(json);
            return o == null ? new JSONObject() : o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
