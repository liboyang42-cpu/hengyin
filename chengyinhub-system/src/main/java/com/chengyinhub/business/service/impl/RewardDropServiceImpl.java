package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.RewardDropLog;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropLogMapper;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
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
 *   <li>无偏:SecureRandom 掷骰(万分基础概率)+ 按 weight 抽取类型。</li>
 * </ul>
 */
@Service
public class RewardDropServiceImpl implements IRewardDropService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_PROB_BP = 1500; // 15%

    @Autowired
    private RewardDropRuleMapper ruleMapper;
    @Autowired
    private RewardDropLogMapper logMapper;
    @Autowired
    private ISysConfigService configService;

    @Override
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
        // 2) 掷骰
        if (!hit(baseProbBp())) {
            return null;
        }
        // 3) 按 weight 抽类型
        List<RewardDropRule> rules = ruleMapper.selectApplicableRules(tId, sceneKey);
        RewardDropRule pick = weightedPick(rules);
        if (pick == null) {
            return null;
        }
        // 4) 组描述 + 占位(uk 抢占,失败=并发已掉)
        Map<String, Object> drop = buildDrop(pick, nodeXp);
        drop.put("sceneKey", sceneKey);
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
        return drop;
    }

    // ---------- 可测方法(包级) ----------

    /** 掷骰命中:SecureRandom 万分判定 */
    boolean hit(int probBp) {
        if (probBp <= 0) {
            return false;
        }
        if (probBp >= 10000) {
            return true;
        }
        return RANDOM.nextInt(10000) < probBp;
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
        int r = RANDOM.nextInt(total);
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
