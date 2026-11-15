package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.business.service.IRewardDropRuleService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M6 掉落规则、基础概率（万分）及期望预览管理服务。
 */
@Service
public class RewardDropRuleServiceImpl implements IRewardDropRuleService {

    private final RewardDropRuleMapper ruleMapper;
    private final ISysConfigService configService;

    @Autowired
    public RewardDropRuleServiceImpl(RewardDropRuleMapper ruleMapper,
                                     @Autowired(required = false) ISysConfigService configService) {
        this.ruleMapper = ruleMapper;
        this.configService = configService;
    }

    @Override
    public List<RewardDropRule> selectRuleList(RewardDropRule filter) {
        return ruleMapper.selectRewardDropRuleList(filter == null ? new RewardDropRule() : filter);
    }

    @Override
    public RewardDropRule selectRuleById(Long id) {
        return id == null ? null : ruleMapper.selectRewardDropRuleById(id);
    }

    @Override
    public RewardDropRule insertRule(RewardDropRule rule) {
        normalizeAndValidateRule(rule, false);
        ruleMapper.insertRewardDropRule(rule);
        return ruleMapper.selectRewardDropRuleById(rule.getId());
    }

    @Override
    public RewardDropRule updateRule(RewardDropRule rule) {
        normalizeAndValidateRule(rule, true);
        ruleMapper.updateRewardDropRule(rule);
        return ruleMapper.selectRewardDropRuleById(rule.getId());
    }

    @Override
    public int deleteRuleById(Long id) {
        return id == null ? 0 : ruleMapper.deleteRewardDropRuleById(id);
    }

    @Override
    public int getBaseProbBp() {
        if (configService == null) {
            return DEFAULT_BASE_PROB_BP;
        }
        try {
            String raw = configService.selectConfigByKey(BASE_PROB_BP_CONFIG_KEY);
            if (raw == null || raw.isBlank()) {
                return DEFAULT_BASE_PROB_BP;
            }
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(0, Math.min(10000, parsed));
        } catch (Exception e) {
            return DEFAULT_BASE_PROB_BP;
        }
    }

    @Override
    public int updateBaseProbBp(int probBp, String updateBy) {
        if (probBp < 0 || probBp > 10000) {
            throw new IllegalArgumentException("baseProbBp 必须在 0~10000 之间（万分比）");
        }
        if (configService == null) {
            throw new IllegalStateException("ISysConfigService 不可用");
        }
        SysConfig query = new SysConfig();
        query.setConfigKey(BASE_PROB_BP_CONFIG_KEY);
        List<SysConfig> matches = configService.selectConfigList(query);
        SysConfig target = null;
        if (matches != null) {
            for (SysConfig c : matches) {
                if (BASE_PROB_BP_CONFIG_KEY.equals(c.getConfigKey())) {
                    target = c;
                    break;
                }
            }
        }
        String newValue = String.valueOf(probBp);
        if (target == null || target.getConfigId() == null) {
            SysConfig created = new SysConfig();
            created.setConfigName("随机掉落-基础概率(万分)");
            created.setConfigKey(BASE_PROB_BP_CONFIG_KEY);
            created.setConfigValue(newValue);
            created.setConfigType("N");
            created.setCreateBy(updateBy == null ? "admin" : updateBy);
            created.setRemark("M6 掉落基础概率,万分数(1500=15%)");
            configService.insertConfig(created);
        } else {
            target.setConfigValue(newValue);
            target.setUpdateBy(updateBy == null ? "admin" : updateBy);
            configService.updateConfig(target);
        }
        return getBaseProbBp();
    }

    @Override
    public Map<String, Object> previewProbabilities(Long topicId, String sceneKey) {
        String effectiveScene = (sceneKey == null || sceneKey.isBlank())
                ? IRewardDropService.SCENE_TOPIC_NODE
                : sceneKey.trim();
        int baseProbBp = getBaseProbBp();
        List<RewardDropRule> rules = ruleMapper.selectApplicableRules(topicId, effectiveScene);
        int totalWeight = 0;
        if (rules != null) {
            for (RewardDropRule r : rules) {
                if (r.getWeight() != null && r.getWeight() > 0) {
                    totalWeight += r.getWeight();
                }
            }
        }
        List<Map<String, Object>> breakdown = new ArrayList<>();
        double expectedExtraXpRatio = 0.0;
        if (rules != null && totalWeight > 0) {
            double hitProb = baseProbBp / 10000.0;
            for (RewardDropRule r : rules) {
                int weight = r.getWeight() == null ? 0 : Math.max(0, r.getWeight());
                if (weight == 0) {
                    continue;
                }
                double share = (double) weight / totalWeight;
                double effectiveProbBp = baseProbBp * share;
                double extraXpRatio = 0.0;
                if (Integer.valueOf(1).equals(r.getDropType())) {
                    int mult = parseMultiplier(r.getParamJson());
                    extraXpRatio = Math.max(0, mult - 1);
                    expectedExtraXpRatio += hitProb * share * extraXpRatio;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.getId());
                row.put("scope", r.getScope());
                row.put("topicId", r.getTopicId());
                row.put("sceneKey", r.getSceneKey());
                row.put("dropType", r.getDropType());
                row.put("weight", weight);
                row.put("paramJson", r.getParamJson());
                row.put("sharePercent", Math.round(share * 10000.0) / 100.0);
                row.put("effectiveProbBp", Math.round(effectiveProbBp * 100.0) / 100.0);
                row.put("extraXpRatio", extraXpRatio);
                breakdown.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseProbBp", baseProbBp);
        result.put("baseProbPercent", Math.round((baseProbBp / 10000.0) * 10000.0) / 100.0);
        result.put("sceneKey", effectiveScene);
        result.put("topicId", topicId);
        result.put("totalWeight", totalWeight);
        result.put("expectedExtraXpPercent", Math.round(expectedExtraXpRatio * 10000.0) / 100.0);
        result.put("rules", breakdown);
        return result;
    }

    private static void normalizeAndValidateRule(RewardDropRule rule, boolean requireId) {
        if (rule == null) {
            throw new IllegalArgumentException("规则不能为空");
        }
        if (requireId && rule.getId() == null) {
            throw new IllegalArgumentException("规则 ID 不能为空");
        }
        int scope = rule.getScope() == null ? 0 : rule.getScope();
        if (scope < 0 || scope > 2) {
            throw new IllegalArgumentException("scope 仅支持 0(全局)/1(主题)/2(场景)");
        }
        rule.setScope(scope);
        if (scope == 1 && (rule.getTopicId() == null || rule.getTopicId() <= 0)) {
            throw new IllegalArgumentException("指定主题规则必须填写 topicId");
        }
        if (scope == 2 && (rule.getSceneKey() == null || rule.getSceneKey().isBlank())) {
            throw new IllegalArgumentException("指定场景规则必须填写 sceneKey");
        }
        if (scope != 1) {
            rule.setTopicId(null);
        }
        if (scope != 2) {
            rule.setSceneKey(null);
        } else {
            rule.setSceneKey(rule.getSceneKey().trim());
        }
        if (rule.getDropType() == null || rule.getDropType() < 1 || rule.getDropType() > 3) {
            throw new IllegalArgumentException("dropType 仅支持 1(XP倍率)/2(徽章碎片)/3(商家彩蛋券)");
        }
        if (rule.getWeight() == null || rule.getWeight() <= 0) {
            throw new IllegalArgumentException("weight 必须大于 0");
        }
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        if (rule.getParamJson() == null || rule.getParamJson().isBlank()) {
            rule.setParamJson("{}");
        } else {
            JSONObject parsed = JSON.parseObject(rule.getParamJson());
            if (parsed == null) {
                throw new IllegalArgumentException("paramJson 必须为合法 JSON 对象");
            }
            rule.setParamJson(parsed.toJSONString());
        }
    }

    private static int parseMultiplier(String paramJson) {
        if (paramJson == null || paramJson.isBlank()) {
            return 2;
        }
        try {
            JSONObject obj = JSON.parseObject(paramJson);
            int mult = obj == null ? 2 : obj.getIntValue("mult", 2);
            return Math.max(1, mult);
        } catch (Exception e) {
            return 2;
        }
    }
}
