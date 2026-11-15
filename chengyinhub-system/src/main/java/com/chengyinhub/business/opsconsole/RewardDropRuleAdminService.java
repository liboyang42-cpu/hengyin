package com.chengyinhub.business.opsconsole;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R10 BE-21 随机掉落规则编辑器：列表 / 新建 / 编辑 / 启停，带校验与审计。
 *
 * <p>校验口径对齐运行期 {@code RewardDropServiceImpl#describe}：scope 0 全局 / 1 指定主题 / 2 指定场景；
 * drop_type 1 XP倍率 {"mult":2..10} / 2 徽章碎片 {"badgeCode":"..."} / 3 商家彩蛋券 {"couponTemplateId":n}。
 * 参数规范化成只含该类型字段的 JSON，未知字段拒绝，避免运营写进运行期读不到的配置。
 * 掉落总开关仍是 feature.flag.drops，本页不碰。
 */
@Service
public class RewardDropRuleAdminService
{
    public static final Set<String> SCENES = Set.of(IRewardDropService.SCENE_TOPIC_NODE, IRewardDropService.SCENE_ROAM_MERCHANT_POI);
    static final int MAX_WEIGHT = 10000;
    static final int MAX_MULT = 10;
    private static final Pattern BADGE = Pattern.compile("^[A-Za-z0-9_\\-]{1,32}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private RewardDropRuleMapper mapper;
    @Autowired
    private OpsConsoleAudit audit;

    public RewardDropRuleAdminService() {}

    RewardDropRuleAdminService(RewardDropRuleMapper mapper, OpsConsoleAudit audit)
    {
        this.mapper = mapper;
        this.audit = audit;
    }

    public List<RewardDropRule> list(RewardDropRule filter)
    {
        return mapper.selectAdminList(filter == null ? new RewardDropRule() : filter);
    }

    public RewardDropRule get(Long id)
    {
        RewardDropRule rule = id == null ? null : mapper.selectById(id);
        if (rule == null) throw new ServiceException("掉落规则不存在", HttpStatus.NOT_FOUND);
        return rule;
    }

    /** 新建规则默认停用：运营核对后再显式启用，避免保存即上线。 */
    @Transactional(rollbackFor = Exception.class)
    public RewardDropRule create(Long sysUserId, RewardDropRule input)
    {
        RewardDropRule rule = normalize(input);
        rule.setId(null);
        rule.setStatus(0);
        rule.setCreateTime(new Date());
        if (mapper.insertRule(rule) != 1) throw new IllegalStateException("掉落规则保存失败");
        audit.record(sysUserId, "REWARD_DROP_RULE_CREATE", "REWARD_DROP_RULE", rule.getId(), null, view(rule), null);
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public RewardDropRule update(Long sysUserId, Long id, RewardDropRule input)
    {
        RewardDropRule before = get(id);
        RewardDropRule rule = normalize(input);
        rule.setId(id);
        rule.setStatus(before.getStatus());
        rule.setCreateTime(before.getCreateTime());
        if (mapper.updateRule(rule) != 1) throw new ServiceException("掉落规则已变化，请刷新", HttpStatus.CONFLICT);
        audit.record(sysUserId, "REWARD_DROP_RULE_UPDATE", "REWARD_DROP_RULE", id, view(before), view(rule), null);
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public RewardDropRule setStatus(Long sysUserId, Long id, Integer status)
    {
        if (status == null || (status != 0 && status != 1)) throw bad("状态只能是启用或停用");
        RewardDropRule before = get(id);
        if (status == 1) normalize(before); // 历史坏行不允许直接启用，先编辑修正
        if (mapper.updateStatus(id, status) != 1) throw new ServiceException("掉落规则已变化，请刷新", HttpStatus.CONFLICT);
        RewardDropRule after = get(id);
        audit.record(sysUserId, status == 1 ? "REWARD_DROP_RULE_ENABLE" : "REWARD_DROP_RULE_DISABLE",
                "REWARD_DROP_RULE", id, view(before), view(after), null);
        return after;
    }

    /** 纯校验 + 规范化：不读库。返回新对象，不改入参。 */
    public static RewardDropRule normalize(RewardDropRule in)
    {
        if (in == null) throw bad("规则不能为空");
        RewardDropRule out = new RewardDropRule();
        Integer scope = in.getScope();
        if (scope == null || scope < 0 || scope > 2) throw bad("适用范围只能是全局、指定主题或指定场景");
        out.setScope(scope);
        if (scope == 1)
        {
            if (in.getTopicId() == null || in.getTopicId() <= 0) throw bad("指定主题时必须填写主题 ID");
            out.setTopicId(in.getTopicId());
        }
        if (scope == 2)
        {
            String scene = in.getSceneKey() == null ? "" : in.getSceneKey().trim();
            if (!SCENES.contains(scene)) throw bad("场景只能是 " + String.join(" / ", SCENES.stream().sorted().toList()));
            out.setSceneKey(scene);
        }
        Integer type = in.getDropType();
        if (type == null || type < 1 || type > 3) throw bad("掉落类型只能是探索值倍率、徽章碎片或商家彩蛋券");
        out.setDropType(type);
        Integer weight = in.getWeight();
        if (weight == null || weight < 1 || weight > MAX_WEIGHT) throw bad("权重必须在 1 到 " + MAX_WEIGHT + " 之间");
        out.setWeight(weight);
        out.setParamJson(normalizeParam(type, in.getParamJson()));
        return out;
    }

    static String normalizeParam(int type, String raw)
    {
        JsonNode node;
        try { node = raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw); }
        catch (Exception e) { throw bad("类型参数不是合法 JSON"); }
        if (node == null || !node.isObject()) throw bad("类型参数必须是 JSON 对象");
        ObjectNode out = JSON.createObjectNode();
        String field = type == 1 ? "mult" : type == 2 ? "badgeCode" : "couponTemplateId";
        node.fieldNames().forEachRemaining(name -> { if (!field.equals(name)) throw bad("类型参数含有不认识的字段：" + name); });
        JsonNode value = node.get(field);
        if (type == 1)
        {
            if (value == null || !value.canConvertToInt() || !value.isIntegralNumber()) throw bad("倍率 mult 必须是整数");
            int mult = value.intValue();
            if (mult < 2 || mult > MAX_MULT) throw bad("倍率 mult 必须在 2 到 " + MAX_MULT + " 之间");
            out.put("mult", mult);
        }
        else if (type == 2)
        {
            if (value == null || !value.isTextual() || !BADGE.matcher(value.asText()).matches())
                throw bad("徽章编码 badgeCode 只能是 1 到 32 位字母、数字、下划线或短横线");
            out.put("badgeCode", value.asText());
        }
        else
        {
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
                throw bad("彩蛋券 couponTemplateId 必须是正整数");
            out.put("couponTemplateId", value.longValue());
        }
        String text = out.toString();
        if (text.length() > 200) throw bad("类型参数过长");
        return text;
    }

    static Map<String, Object> view(RewardDropRule r)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", r.getScope());
        m.put("topicId", r.getTopicId());
        m.put("sceneKey", r.getSceneKey());
        m.put("dropType", r.getDropType());
        m.put("weight", r.getWeight());
        m.put("paramJson", r.getParamJson());
        m.put("status", r.getStatus());
        return m;
    }

    private static ServiceException bad(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
