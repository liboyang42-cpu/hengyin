package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.TopicRouteSessionScope;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.JourneyCheckActionId;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩法 Beat(Encounter)只读解析(旅程体验模组化 B7)。
 *
 * <p>一局 = {@code topic_route_session}(会员 + 主题);状态只读本局
 * {@code state_json}({@link TopicRouteEngine.State} 的 variables / tags / completedNodeIds),
 * 到店事实复用 {@link IShopDayProofService#hasArrived}(店内静态码打卡写下的 checkin_time;
 * 线性模式的 GPS/打卡完成同样落这一列)。所有判定细节(骰点、答案、判中与否)都不在本响应里。
 */
@Service
public class PlayEncounterServiceImpl implements IPlayEncounterService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** scope_type=2 门店分身;节点级是 {@link NpcProfileService#SCOPE_NODE}。 */
    private static final int SCOPE_MERCHANT = 2;

    private final TopicRouteEngine engine = new TopicRouteEngine();

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private TopicRouteSessionMapper topicRouteSessionMapper;

    /** R16-DUO:「在不在队伍里、是 A 还是 B」的判定来源,复用现有队伍机制,不新建角色表。 */
    @Autowired
    private PlayTeamMapper playTeamMapper;

    @Autowired
    private IShopDayProofService shopDayProofService;

    @Autowired
    private NpcProfileService npcProfileService;

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;

    @Override
    public Map<String, Object> resolve(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L || nodeId == null || nodeId <= 0L) {
            throw new ServiceException("参数有误");
        }
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) {
            throw new ServiceException("节点不存在");
        }
        CmsMemberTemplate template = node.getTemplateId() == null ? null
                : cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());

        TopicRouteSession run = TopicRouteSessionScope.findCurrent(topicRouteSessionMapper, memberId, topicId, activityId);
        if (run == null) {
            throw new ServiceException("这一局还没开始");
        }
        if (!memberId.equals(run.getMemberId())) {
            throw new ServiceException("无权访问这一局");
        }
        // 旅程段(requires / check)优先读建局冻结块;老局快照没有 nodeJourney 键时回退实时模板配置。
        JsonNode frozen = NodeJourneySnapshot.block(run.getConfigSnapshotJson(), node.getId());
        JsonNode liveConfig = parseConfigOrNull(template == null ? null : template.getAdvancedConfigJson());
        JsonNode runSnapshot=parseConfigOrNull(run.getConfigSnapshotJson());
        JsonNode themeNodeConfig=runSnapshot==null || !runSnapshot.has("themeConfig")?null:runSnapshot.path("nodeConfigs").get(String.valueOf(nodeId));
        if(themeNodeConfig!=null && template!=null){CmsMemberTemplate frozenTemplate=new CmsMemberTemplate();org.springframework.beans.BeanUtils.copyProperties(template,frozenTemplate);frozenTemplate.setAdvancedConfigJson(themeNodeConfig.toString());template=frozenTemplate;liveConfig=themeNodeConfig;}

        if (frozen == null && liveConfig == null) {
            throw new ServiceException("该节点未配置玩法");
        }
        JsonNode journey = frozen == null ? liveConfig : frozen;
        TopicRouteEngine.State state = readState(run);
        JsonNode requires = journey.get("requires");
        // 缺 requires = 无条件(与 advantage/disadvantage 同一口径);engine.conditionsPass
        // 只吃数组,直接传 null 会 NPE —— 老配置里根本没有这个字段。
        boolean locked = hasConditions(requires) && !engine.conditionsPass(requires, state);
        activityId = run.getActivityId() == null ? 0L : run.getActivityId();
        boolean arrived = shopDayProofService.hasArrived(memberId, activityId, nodeId);

        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("nodeId", node.getId());
        view.put("runId", run.getId());
        view.put("stateVersion", run.getVersion());
        view.put("arrived", Boolean.valueOf(arrived));
        view.put("locked", Boolean.valueOf(locked));
        view.put("missingKeys", missingKeys(requires, state));
        Map<String, Object> presentation = presentation(node, template, memberId, topicId, activityId);
        view.put("presentation", presentation);
        view.put("allowedActions", arrived && !locked
                ? allowedActions(liveConfig, journey, state, topicCheckEnabled(run, topicId)) : Collections.emptyList());
        view.put("check", check(journey, state));
        if(themeNodeConfig!=null && themeNodeConfig.path("outcomeRules").path("version").asInt()==1)
            view.put("config",Collections.singletonMap("outcomeRulesVersion",1));
        view.put("outcomeContract", outcomeContract(journey, liveConfig, run, node.getId(), arrived));
        view.put("fallbackPlan", fallbackPlan());
        view.put("preload", preload(template, node, presentation));
        view.put("beat", beat(journey, state, node.getId(), arrived,
                Boolean.TRUE.equals(presentation.get("revisit"))));
        appendNarrative(view, journey, state, node, run, memberId, topicId, activityId);
        appendRoleView(view, journey, run, memberId);
        return view;
    }

    /** 本局权威状态的唯一读侧:topic_route_session.state_json。 */
    private TopicRouteEngine.State readState(TopicRouteSession run) {
        try {
            TopicRouteEngine.State state = JSON.readValue(run.getStateJson(), TopicRouteEngine.State.class);
            return state == null ? new TopicRouteEngine.State() : state;
        } catch (Exception e) {
            throw new ServiceException("本局状态不可用");
        }
    }

    /** advancedConfigJson 是玩法块的唯一来源;缺配置/非对象返回 null,坏 JSON 仍直接暴露业务错误。 */
    private JsonNode parseConfigOrNull(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        JsonNode config;
        try {
            config = JSON.readTree(raw);
        } catch (Exception e) {
            throw new ServiceException("玩法配置不可用");
        }
        return config == null || !config.isObject() ? null : config;
    }

    /**
     * 八拍编排里的「这一拍」(R22-A3,规划 §16.2 X1 brief/route/enter/investigate/outcome/deliver/unlock):
     * 按服务端已知状态给出,客户端不再自己按模板类型选画面。
     *
     * <p>口径(拿不到的状态见交付说明的不确定清单):
     * <ul>
     *   <li>未到店:块里有 brief 段 → brief,否则 route;「brief 未读」服务端没有记录(brief 不是
     *       状态键),用「有 brief 段」近似 —— 翻页节奏由客户端掌握。</li>
     *   <li>到店、本局未完成:有 enter 段且一个问题都没问过(sys.asked.* 无标记)→ enter,
     *       否则 investigate;没有 enter 段直接 investigate。</li>
     *   <li>本局已完成(state.completedNodeIds):有 outcome 段 → outcome;否则有 deliver 段 →
     *       deliver(「交付未领」在 encounter 侧没有领取记录数据源,按有交付段下发);否则 unlock。</li>
     *   <li>复访:这一站已有完成事实(PlayerNodeProgress status=1,与 presentation.revisit 同源)
     *       但本局 state 还没登记完成(推进回执未提交)→ revisit。</li>
     * </ul>
     */
    private String beat(JsonNode journey, TopicRouteEngine.State state, Long nodeId,
            boolean arrived, boolean revisit) {
        if (!completed(state, nodeId)) {
            if (revisit) return "revisit";
            if (!arrived) return hasSection(journey, "brief") ? "brief" : "route";
            return hasSection(journey, "enter") && !anyQuestionAsked(state, nodeId) ? "enter" : "investigate";
        }
        if (hasSection(journey, "outcome")) return "outcome";
        if (hasSection(journey, "deliver")) return "deliver";
        return "unlock";
    }

    /** 叙事段在这里就是对象(组装期空段不写);没配该段 = 这一拍不演它。 */
    private static boolean hasSection(JsonNode journey, String section) {
        JsonNode value = journey == null ? null : journey.get(section);
        return value != null && value.isObject();
    }

    /** 这一站问过问题没有:服务端保留变量 sys.asked.{nodeId}.{questionId}=1(与 enter 的 asked 同源)。 */
    private static boolean anyQuestionAsked(TopicRouteEngine.State state, Long nodeId) {
        if (nodeId == null || state == null || state.getVariables() == null) return false;
        String prefix = "sys.asked." + nodeId + ".";
        for (Map.Entry<String, Number> entry : state.getVariables().entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(prefix)
                    && entry.getValue() != null && entry.getValue().intValue() == 1) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> presentation(CmsTopicNode node, CmsMemberTemplate template,
            Long memberId, Long topicId, Long activityId) {
        Map<String, Object> presentation = new LinkedHashMap<String, Object>();
        String title = template == null ? null : template.getTitle();
        presentation.put("title", StringUtils.isBlank(title) ? node.getName() : title);
        presentation.put("hook", node.getHookText());
        presentation.put("storyText", template == null ? null : template.getStoryText());
        presentation.put("npc", npc(node));
        presentation.put("revisit", Boolean.valueOf(revisit(memberId, topicId, activityId, node.getId())));
        return presentation;
    }

    /** 节点 NPC 优先、门店分身兜底(与 /nodes 的 nodeNpcCache 同一条 resolveForNodeOrMerchant);persona 永不下发。 */
    private Map<String, Object> npc(CmsTopicNode node) {
        NpcProfileVO profile = npcProfileService.resolveForNodeOrMerchant(node.getId(), node.getMerchantMemberId());
        if (profile == null) return null;
        Map<String, Object> npc = new LinkedHashMap<String, Object>();
        npc.put("id", profile.getProfileId());
        npc.put("name", profile.getName());
        npc.put("avatar", profile.getAvatar());
        npc.put("role", npcRole(profile.getScopeType()));
        return npc;
    }

    /** role 只暴露出场形态,不透 scopeId(它是商家/活动 id,不是玩家身份)。 */
    private static String npcRole(Integer scopeType) {
        if (scopeType == null) return null;
        if (scopeType.intValue() == NpcProfileService.SCOPE_NODE) return "node";
        if (scopeType.intValue() == SCOPE_MERCHANT) return "merchant";
        return null;
    }

    /**
     * 该节点地点在本局中此前已完成过(status=1),按本局活动域收窄(自玩=0)。
     *
     * <p>无重玩:同一节点再次解析只会发生在该拍已完成后回看时,即「复访」。mode2 的
     * status=1 是「游戏答完」,同样是这一拍演过的记录。
     */
    private boolean revisit(Long memberId, Long topicId, Long activityId, Long nodeId) {
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(activityId);
        probe.setTopicId(topicId);
        probe.setNodeId(nodeId);
        List<PlayerNodeProgress> rows = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        if (rows == null) return false;
        for (PlayerNodeProgress row : rows) {
            if (Integer.valueOf(1).equals(row.getStatus())) return true;
        }
        return false;
    }

    /**
     * 本局的主题开没开检定:建局冻结值优先,老局快照没有时读实时主题。
     * 都读不到(手工装配的单测没有主题读取)时按「开」处理 —— 保持原行为,由 roll() 那一侧兜底。
     */
    private boolean topicCheckEnabled(TopicRouteSession run, Long topicId) {
        String frozen = NodeJourneySnapshot.journeyRules(run == null ? null : run.getConfigSnapshotJson());
        if (frozen != null) return com.chengyinhub.business.domain.JourneyRules.parse(frozen).isCheckEnabled();
        if (cmsTopicMapper == null) return true;
        com.chengyinhub.business.domain.CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        return com.chengyinhub.business.domain.JourneyRules.parse(
                topic == null ? null : topic.getJourneyRulesJson()).isCheckEnabled();
    }

    /**
     * 已启用的玩法段落 + check;未到店或 locked 时调用方不会取用。R22-C4:玩法段名单读建局冻结的
     * {@code playables}(与 check 同源),会话中途新增玩法不穿透进行中的局;老快照没有该键才回退
     * 实时模板配置。check 同样取旅程段(冻结优先)。
     */
    private List<String> allowedActions(JsonNode liveConfig, JsonNode journey, TopicRouteEngine.State state,
                                        boolean topicCheckOn) {
        List<String> actions = new ArrayList<String>();
        JsonNode playables = journey == null ? null : journey.get(NodeJourneySnapshot.PLAYABLES_KEY);
        if (journey != null && journey.has(NodeJourneySnapshot.PLAYABLES_KEY)) {
            if (!playables.isObject()) throw new ServiceException("玩法配置不可用");
            for (String section : NodeJourneySnapshot.PLAYABLE_SECTIONS) {
                if (playables.path(section).asBoolean(false)) actions.add(section);
            }
        } else if (liveConfig != null && liveConfig.isObject()) {
            for (String section : NodeJourneySnapshot.PLAYABLE_SECTIONS) {
                if (enabled(liveConfig.get(section))) actions.add(section);
            }
        }
        // 阶段 3:力竭时不把 check 列进可做动作 —— roll() 那边会拒,这边还摆着就是「灰着不给理由」。
        // ⚠️ 只摘 check,不动玩法:一个节点可能既有 qa 又有 check,力竭挡的是检定不是整站,
        // 把整站 locked 会过度封锁 —— 玩家明明还能答题。
        // 2026-09-24 C-05:主题没开检定时不摆 —— roll() 会报「本主题未启用检定」,摆着就是一点就错。
        if (topicCheckOn && enabled(journey == null ? null : journey.get("check"))
                && !(state != null && state.isExhausted())) {
            actions.add("check");
        }
        return actions;
    }

    /**
     * 没通过的条件里引用的键(var,或 HAS_TAG 的 tag 值),按出现顺序去重;
     * NODE_COMPLETED 不引用状态键,不进 missingKeys。
     */
    private List<String> missingKeys(JsonNode requires, TopicRouteEngine.State state) {
        List<String> missing = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (requires == null || !requires.isArray()) return missing;
        for (JsonNode condition : requires) {
            if (condition == null || !condition.isObject()) continue;
            if (conditionPass(condition, state)) continue;
            String key = condition.path("var").asText("");
            if (key.isEmpty() && "HAS_TAG".equals(condition.path("op").asText())) {
                key = condition.path("value").asText("");
            }
            if (!key.isEmpty() && seen.add(key)) missing.add(key);
        }
        return missing;
    }

    private boolean conditionPass(JsonNode condition, TopicRouteEngine.State state) {
        ArrayNode single = JSON.createArrayNode();
        single.add(condition);
        return engine.conditionsPass(single, state);
    }

    /**
     * 判定的公开面:标识/技能/档位/条件修正/优劣势。successText/failText 等收尾文案与任何
     * 判定细节(骰点、DC、答案)都不在这里,判定由服务端算。
     */
    private Map<String, Object> check(JsonNode config, TopicRouteEngine.State state) {
        JsonNode raw = config.get("check");
        if (!enabled(raw)) return null;
        Map<String, Object> check = new LinkedHashMap<String, Object>();
        // 阶段 3:力竭时 roll() 会拒。这里把状态一并下发,否则前端只能等玩家点下去吃一个报错,
        // 看不出「为什么这一站掷不了」—— 灰着不给理由是本仓明令禁止的那种交互。
        check.put("exhausted", Boolean.valueOf(state != null && state.isExhausted()));
        check.put("checkId", textOrNull(raw, "checkId"));
        // 检定走 encounter,同样沿用玩法默认表和本局冻结的作者选择。
        check.put("present", AdvancedGameConfigValidator.resolvePresent(config));
        check.put("skill", textOrNull(raw, "skill"));
        check.put("tier", textOrNull(raw, "tier"));
        List<Map<String, Object>> mods = new ArrayList<Map<String, Object>>();
        for (JsonNode mod : raw.path("mods")) {
            if (mod == null || !mod.isObject()) continue;
            JsonNode when = mod.path("when");
            if (when == null || !when.isObject()) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("when", conditionView(when));
            item.put("value", Integer.valueOf(mod.path("value").asInt(0)));
            item.put("label", textOrNull(mod, "label"));
            item.put("held", Boolean.valueOf(conditionPass(when, state)));
            mods.add(item);
        }
        check.put("mods", mods);
        JsonNode advantage = raw.get("advantageIf");
        JsonNode disadvantage = raw.get("disadvantageIf");
        check.put("advantage", Boolean.valueOf(hasConditions(advantage) && engine.conditionsPass(advantage, state)));
        check.put("disadvantage", Boolean.valueOf(hasConditions(disadvantage) && engine.conditionsPass(disadvantage, state)));
        return check;
    }

    private static boolean hasConditions(JsonNode conditions) {
        return conditions != null && conditions.isArray() && conditions.size() > 0;
    }

    /** 条件条目的公开面:var/op/value,或 op/nodeId,或 op/value(HAS_TAG);契约外字段一律不出。 */
    private Map<String, Object> conditionView(JsonNode condition) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        String op = condition.path("op").asText("");
        view.put("op", op);
        if ("NODE_COMPLETED".equals(op)) {
            view.put("nodeId", Long.valueOf(condition.path("nodeId").asLong(0L)));
        } else if ("HAS_TAG".equals(op)) {
            view.put("value", condition.path("value").asText(""));
        } else {
            view.put("var", condition.path("var").asText(""));
            view.put("value", Integer.valueOf(condition.path("value").asInt(0)));
        }
        return view;
    }

    /**
     * outcomeContract 的结果行:稳定 outcomeCode + 玩家可见标签 + 路线图口径的下一站。
     *
     * <p>来源两处(现成契约,不另造):终点 outcomeCode 与 outcomeLabel 来自本局建局冻结的
     * branchOutcomes(R22-C1,与 nextNodeIds 同源;老快照没有该键才回退实时模板的 branch 终点
     * 步骤);下一站来自本局建局冻结的路线图 {@code edges} 里 {@code fromNodeId=本站} 的边
     * (trigger.outcomeCode → toNodeId),老快照 / 线性快照没有边时 nextNodeIds 为空数组。
     *
     * <p>不得泄露答案:哪个选项对应哪个结果、终点正文 / 选项 / 跳转(步骤 id)一律不下发 ——
     * 到店前连终点标签与下一站去向也不给(名字和去向会剧透选择),outcomeCodes 形状原样保留。
     */
    private Map<String, Object> outcomeContract(JsonNode journey, JsonNode liveConfig, TopicRouteSession run,
            Long nodeId, boolean arrived) {
        List<String> codes = new ArrayList<String>();
        Map<String, String> labels = new LinkedHashMap<String, String>();
        // R22-C1:终点声明(与下一站同源)读本局建局冻结的 branchOutcomes;键在就以快照为准,
        // 老快照没有该键才回退实时模板的 branch 段。
        JsonNode frozen = journey == null ? null : journey.get(NodeJourneySnapshot.BRANCH_OUTCOMES_KEY);
        if (journey != null && journey.has(NodeJourneySnapshot.BRANCH_OUTCOMES_KEY)) {
            if (!frozen.isArray()) throw new ServiceException("玩法配置不可用");
            addFrozenOutcomes(frozen, codes, labels);
        } else {
            addDeclaredOutcomes(liveConfig, codes, labels);
        }
        Map<String, List<Long>> destinations = outcomeDestinations(snapshotOrNull(run), nodeId);
        for (String code : destinations.keySet()) {
            if (!codes.contains(code)) codes.add(code);
        }
        Map<String, Object> contract = new LinkedHashMap<String, Object>();
        contract.put("outcomeCodes", codes);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String code : codes) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("outcomeCode", code);
            row.put("label", arrived ? labels.get(code) : null);
            List<Long> targets = destinations.get(code);
            row.put("nextNodeIds", !arrived || targets == null ? Collections.<Long>emptyList() : targets);
            rows.add(row);
        }
        contract.put("outcomes", rows);
        return contract;
    }

    /** 冻结的终点声明:[{outcomeCode, outcomeLabel}] 逐条收,重复 code 只算一次。 */
    private static void addFrozenOutcomes(JsonNode rows, List<String> codes, Map<String, String> labels) {
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) continue;
            String code = row.path("outcomeCode").asText("").trim();
            if (code.isEmpty() || labels.containsKey(code)) continue;
            codes.add(code);
            labels.put(code, textOrNull(row, "outcomeLabel"));
        }
    }

    /** 实时模板的终点声明:branch 段 terminal 步骤的 outcomeCode → outcomeLabel(没配 = null)。 */
    private static void addDeclaredOutcomes(JsonNode config, List<String> codes, Map<String, String> labels) {
        JsonNode branch = config == null ? null : config.get("branch");
        if (!enabled(branch)) return;
        for (JsonNode step : branch.path("steps")) {
            if (step == null || !step.path("terminal").asBoolean(false)) continue;
            String code = step.path("outcomeCode").asText("").trim();
            if (code.isEmpty() || labels.containsKey(code)) continue;
            codes.add(code);
            labels.put(code, textOrNull(step, "outcomeLabel"));
        }
    }

    // ==================== v4-A 叙事段:enter / outcome / deliver / unlock / revisit ====================
    // 只追加字段,不改已有字段。时机敏感的内容全卡在这里:
    // outcome 只在本节点已完成时出现;questions[].a 只在该问题问过时出现;deliver.line 只在完成后出现。
    // R11-A:enter / outcome 的 npc 严格按各自 npcId 解析(必须是本主题可用 NPC),不再回落到
    // 节点 NPC / 门店分身 —— 那两段挂的是「谁在说话」,说话人不由地点决定。

    private void appendNarrative(Map<String, Object> view, JsonNode config, TopicRouteEngine.State state,
            CmsTopicNode node, TopicRouteSession run, Long memberId, Long topicId, Long activityId) {
        JsonNode enterRaw = config == null ? null : config.get("enter");
        JsonNode outcomeRaw = config == null ? null : config.get("outcome");
        Map<Long, NpcProfileVO> servedNpcs = null;
        if (hasNpcId(enterRaw) || hasNpcId(outcomeRaw)) servedNpcs = servedNpcs(topicId);
        Map<String, Object> enter = enter(enterRaw, state, node, narrativeNpc(enterRaw, servedNpcs));
        if (enter != null) view.put("enter", enter);
        Map<String, Object> unlock = unlock(config);
        if (unlock != null) view.put("unlock", unlock);
        Map<String, Object> outcome = outcome(outcomeRaw, config, state, node, run, memberId,
                narrativeNpc(outcomeRaw, servedNpcs));
        if (outcome != null) view.put("outcome", outcome);
        Map<String, Object> deliver = deliver(config, state, node.getId());
        if (deliver != null) view.put("deliver", deliver);
        Map<String, Object> revisit = revisitView(config, memberId, topicId, activityId, node.getId(), state);
        if (revisit != null) view.put("revisit", revisit);
        // v4-B:委托 brief 与路上 route —— 两段都是到店前就演的内容,不加完成/到店闸。
        Map<String, Object> brief = brief(config, state);
        if (brief != null) view.put("brief", brief);
        Map<String, Object> route = route(config);
        if (route != null) view.put("route", route);
    }

    /** 段配置里配了有效 npcId 才需要查「本主题可用 NPC」;没配的段落一次库都不查。 */
    private static boolean hasNpcId(JsonNode section) {
        return npcIdOf(section) != null;
    }

    private static Long npcIdOf(JsonNode section) {
        if (section == null || !section.isObject()) return null;
        JsonNode npcId = section.get("npcId");
        if (npcId == null || !npcId.isIntegralNumber()) return null;
        long value = npcId.asLong();
        return value <= 0L ? null : Long.valueOf(value);
    }

    /**
     * 叙事段 NPC:严格按 npcId 在「本主题可用 NPC」里找(启用节点上的节点级 NPC、门店分身)。
     *
     * <p>不回落节点 NPC / 门店分身:段配置没配 npcId、或 npcId 指向本主题外的 NPC,一律 null。
     * 发布期同一口径(见 CmsTopicServiceImpl 的发布闸)已拦下配错的 npcId,这里只是运行期兜底。
     */
    private static Map<String, Object> narrativeNpc(JsonNode section, Map<Long, NpcProfileVO> servedNpcs) {
        Long npcId = npcIdOf(section);
        if (npcId == null || servedNpcs == null) return null;
        NpcProfileVO profile = servedNpcs.get(npcId);
        if (profile == null) return null;
        Map<String, Object> npc = new LinkedHashMap<String, Object>();
        npc.put("id", profile.getProfileId());
        npc.put("name", profile.getName());
        npc.put("avatar", profile.getAvatar());
        npc.put("role", npcRole(profile.getScopeType()));
        return npc;
    }

    /** 本主题可用 NPC(启用节点上的节点级 NPC + 门店分身),口径与发布期剧情配置同一份。 */
    private Map<Long, NpcProfileVO> servedNpcs(Long topicId) {
        Map<Long, NpcProfileVO> profiles = new LinkedHashMap<Long, NpcProfileVO>();
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(probe);
        if (nodes == null) return profiles;
        for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null) continue;
            putProfiles(profiles, npcProfileService.resolveEnabledList(NpcProfileService.SCOPE_NODE, node.getId()));
            if (node.getMerchantMemberId() != null) {
                putProfiles(profiles, npcProfileService.resolveEnabledListForMerchantMember(node.getMerchantMemberId()));
            }
        }
        return profiles;
    }

    private static void putProfiles(Map<Long, NpcProfileVO> target, List<NpcProfileVO> profiles) {
        if (profiles == null) return;
        for (NpcProfileVO profile : profiles) {
            if (profile != null && profile.getProfileId() != null) target.put(profile.getProfileId(), profile);
        }
    }

    /**
     * enter:到店前的一拍。questions[].a 只在该问题问过(sys.asked.{nodeId}.{id}=1)后随响应给出;
     * 没问过连字段都没有 —— null 也是一种「泄题」,客户端会照着画一个能点的东西。
     */
    private Map<String, Object> enter(JsonNode raw, TopicRouteEngine.State state, CmsTopicNode node,
            Map<String, Object> npc) {
        if (raw == null || !raw.isObject()) return null;
        Map<String, Object> enter = new LinkedHashMap<String, Object>();
        enter.put("npc", npc);
        enter.put("opener", textOrNull(raw, "opener"));
        enter.put("aside", textOrNull(raw, "aside"));
        List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
        for (JsonNode question : raw.path("questions")) {
            if (question == null || !question.isObject()) continue;
            String id = question.path("id").asText("");
            boolean asked = asked(state, node.getId(), id);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", id);
            item.put("q", question.path("q").asText(""));
            item.put("asked", Boolean.valueOf(asked));
            if (asked) item.put("a", question.path("a").asText(""));
            questions.add(item);
        }
        enter.put("questions", questions);
        return enter;
    }

    /** 问题问过没有:服务端保留变量 sys.asked.{nodeId}.{questionId}=1(玩家只读)。 */
    private static boolean asked(TopicRouteEngine.State state, Long nodeId, String questionId) {
        if (questionId.isEmpty() || state.getVariables() == null) return false;
        Number flag = state.getVariables().get("sys.asked." + nodeId + "." + questionId);
        return flag != null && flag.intValue() == 1;
    }

    /** unlock:那句吊人的引子,到店前就随响应下发。 */
    private Map<String, Object> unlock(JsonNode config) {
        JsonNode raw = config.get("unlock");
        if (raw == null || !raw.isObject()) return null;
        Map<String, Object> unlock = new LinkedHashMap<String, Object>();
        unlock.put("hook", textOrNull(raw, "hook"));
        return unlock;
    }

    /**
     * outcome:只在本节点已完成(completedNodeIds)时出现。line 按本节点最近一次检定成败
     * 取 line / lineIfFail(失败文案缺省回落 line);无检定或回执取不到一律 line。
     */
    private Map<String, Object> outcome(JsonNode raw, JsonNode config, TopicRouteEngine.State state,
            CmsTopicNode node, TopicRouteSession run, Long memberId, Map<String, Object> npc) {
        if (raw == null || !raw.isObject() || !completed(state, node.getId())) return null;
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        outcome.put("title", textOrNull(raw, "title"));
        outcome.put("npc", npc);
        outcome.put("line", outcomeLine(raw, config, run, memberId, node.getId()));
        outcome.put("facts", passingTexts(raw.get("facts"), "text", state));
        outcome.put("open", textOrNull(raw, "open"));
        return outcome;
    }

    /** 成败两句的选取:只有「有检定且结算过且失败」才换 lineIfFail,其余全走 line。 */
    private String outcomeLine(JsonNode outcome, JsonNode config, TopicRouteSession run, Long memberId,
            Long nodeId) {
        String line = textOrNull(outcome, "line");
        if (Boolean.FALSE.equals(settledCheckSuccess(config, run, memberId, nodeId))) {
            String failLine = textOrNull(outcome, "lineIfFail");
            if (failLine != null) line = failLine;
        }
        return line;
    }

    /**
     * 本节点最近一次检定成败:读 topic_route_action 的 settle 回执(B5 与叙事段共用
     * {@link JourneyCheckActionId#settle},同一检定只会结算一次)。
     * 没配检定、还没结算或回执读不出来都返回 null(按「无检定」处理)。
     */
    private Boolean settledCheckSuccess(JsonNode config, TopicRouteSession run, Long memberId, Long nodeId) {
        JsonNode check = config.get("check");
        if (!enabled(check)) return null;
        String checkId = check.path("checkId").asText("");
        if (checkId.isEmpty()) return null;
        TopicRouteAction action = topicRouteSessionMapper.selectActionByAction(run.getId(), memberId,
                JourneyCheckActionId.settle(nodeId, checkId));
        if (action == null || StringUtils.isBlank(action.getResponseJson())) return null;
        try {
            JsonNode success = JSON.readTree(action.getResponseJson()).get("success");
            return success != null && success.isBoolean() ? Boolean.valueOf(success.asBoolean()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * deliver:奖励与领取方式到店前可见;line 只在本节点完成后才给 ——
     * 它是「拿到手」时的那句话,提前给等于把还没发生的兑付先演了。
     */
    private Map<String, Object> deliver(JsonNode config, TopicRouteEngine.State state, Long nodeId) {
        JsonNode raw = config.get("deliver");
        if (raw == null || !raw.isObject()) return null;
        Map<String, Object> deliver = new LinkedHashMap<String, Object>();
        deliver.put("reward", textOrNull(raw, "reward"));
        deliver.put("claimLater", Boolean.valueOf(raw.path("claimLater").asBoolean(false)));
        if (completed(state, nodeId)) {
            String line = textOrNull(raw, "line");
            if (line != null) deliver.put("line", line);
        }
        return deliver;
    }

    /** 复访才出的那几句:revisit=false 或没配时整段不出,when 缺省或通过才收进 changes。 */
    private Map<String, Object> revisitView(JsonNode config, Long memberId, Long topicId, Long activityId,
            Long nodeId, TopicRouteEngine.State state) {
        JsonNode raw = config.get("revisit");
        if (raw == null || !raw.isObject()) return null;
        if (!revisit(memberId, topicId, activityId, nodeId)) return null;
        Map<String, Object> revisit = new LinkedHashMap<String, Object>();
        revisit.put("changes", passingTexts(raw.get("changes"), "text", state));
        return revisit;
    }

    /**
     * brief:委托页那一段,到店前就下发。carry 只收 when 缺省 / null 或条件通过的 text ——
     * 没通过的条目不是「现在该带的东西」,下发出去等于把还没成立的委托条件当成了事实。
     */
    private Map<String, Object> brief(JsonNode config, TopicRouteEngine.State state) {
        JsonNode raw = config.get("brief");
        if (raw == null || !raw.isObject()) return null;
        Map<String, Object> brief = new LinkedHashMap<String, Object>();
        brief.put("question", textOrNull(raw, "question"));
        brief.put("body", textOrNull(raw, "body"));
        brief.put("carry", passingTexts(raw.get("carry"), "text", state));
        return brief;
    }

    /** route:路上页那两句(walk 步行时间 / todo 到店待办),到店前就下发。 */
    private Map<String, Object> route(JsonNode config) {
        JsonNode raw = config.get("route");
        if (raw == null || !raw.isObject()) return null;
        Map<String, Object> route = new LinkedHashMap<String, Object>();
        route.put("walk", textOrNull(raw, "walk"));
        route.put("todo", textOrNull(raw, "todo"));
        return route;
    }

    /** 条件文案列表:when 缺省或通过才收 text(与 requires 同一套 conditionsPass)。 */
    private List<String> passingTexts(JsonNode rows, String textField, TopicRouteEngine.State state) {
        List<String> texts = new ArrayList<String>();
        if (rows == null || !rows.isArray()) return texts;
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) continue;
            JsonNode when = row.get("when");
            if (when != null && !when.isNull() && !conditionPass(when, state)) continue;
            String text = row.path(textField).asText("");
            if (!text.isEmpty()) texts.add(text);
        }
        return texts;
    }

    /** 本节点在本局已完成:权威状态只有 topic_route_session.state_json 的 completedNodeIds。 */
    private static boolean completed(TopicRouteEngine.State state, Long nodeId) {
        return state.getCompletedNodeIds() != null && state.getCompletedNodeIds().contains(nodeId);
    }

    private static boolean enabled(JsonNode node) {
        return node != null && node.isObject() && node.path("enabled").asBoolean(false);
    }

    private static String textOrNull(JsonNode node, String field) {
        String text = node.path(field).asText("");
        return StringUtils.isBlank(text) ? null : text;
    }

    // ==================== R16-DUO 双人视角:roleView 按角色分半 ====================
    // 判定来源全部复用现有多人 / 角色机制,自己不建表、不引跨设备共享状态:
    //   在不在队伍里 = play_team(owner_type=2, owner_id=本局 activity_id)且本人在册
    //     (play_team_member.status=1),与 AdvancedGameRuntimeServiceImpl.inferTeamId 同一过滤;
    //   是 A 还是 B = 本人在 selectActiveMembers 里的次序映射 roleViews.roles 的次序
    //     (队员列表按 role desc, create_time asc, id asc 排),与 multiplayer 的 assignAutomaticRoles
    //     「按队员次序轮转」同一口径;队员比角色多时按 roles.size() 回卷(第 1 人 A、
    //     第 2 人 B、第 3 人 A、第 4 人 B……),与 assignAutomaticRoles 的 i % order.size() 一致。
    // 单人 / 不在队伍(含自玩局 activityId=0)→ role=SOLO,两半都给;队伍里只给本人那一半,
    // 另给一个对方角色的 label;多队歧义 / 队员行在在册名单里查不到 → roleMissing=true,
    // 一个 view 都不给。

    /** 角色视角段:配了且 enabled 才出;老配置(没这一段)响应形状一字不变。 */
    private void appendRoleView(Map<String, Object> view, JsonNode config, TopicRouteSession run, Long memberId) {
        JsonNode raw = config == null ? null : config.get("roleViews");
        if (!enabled(raw)) return;
        List<JsonNode> roles = new ArrayList<JsonNode>();
        for (JsonNode role : raw.path("roles")) {
            if (role != null && role.isObject()) roles.add(role);
        }
        // 形状由发布期校验器保证;冻结快照万一不自洽,宁可不演也不给半份错的
        if (roles.size() < 2) return;
        Long activityId = run.getActivityId();
        if (activityId == null || activityId <= 0L || playTeamMapper == null) {
            view.put("roleView", soloRoleView(raw, roles));
            return;
        }
        List<PlayTeam> teams = playTeamMapper.selectMyTeams(memberId);
        PlayTeam team = null;
        for (PlayTeam row : teams == null ? Collections.<PlayTeam>emptyList() : teams) {
            if (row == null || !Integer.valueOf(2).equals(row.getOwnerType())
                    || !activityId.equals(row.getOwnerId())
                    || row.getStatus() == null || row.getStatus() > PlayTeam.STATUS_STARTED) continue;
            if (team != null && !team.getId().equals(row.getId())) {
                // 同一场次多队:分不清该按哪支队算,按未分配处理(绝不把对方那一半漏出去)
                view.put("roleView", missingRoleView());
                return;
            }
            team = row;
        }
        if (team == null) {
            view.put("roleView", soloRoleView(raw, roles));
            return;
        }
        int index = activeMemberIndex(team.getId(), memberId);
        if (index < 0) {
            view.put("roleView", missingRoleView());
            return;
        }
        String roleId = roles.get(index % roles.size()).path("id").asText("");
        Map<String, Object> own = roleViewOf(raw, roleId);
        if (own == null) {
            view.put("roleView", missingRoleView());
            return;
        }
        Map<String, Object> mine = new LinkedHashMap<String, Object>();
        mine.put("role", roleId);
        mine.put("views", Collections.singletonList(own));
        JsonNode other = roles.get((index + 1) % roles.size());
        mine.put("otherRoleLabel", textOrNull(other, "label"));
        view.put("roleView", mine);
    }

    /** 单人版:两半都下发,roll 成同一条 roleView 形状(views 是数组,role=SOLO)。 */
    private Map<String, Object> soloRoleView(JsonNode raw, List<JsonNode> roles) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (JsonNode role : roles) {
            Map<String, Object> one = roleViewOf(raw, role.path("id").asText(""));
            if (one != null) views.add(one);
        }
        Map<String, Object> solo = new LinkedHashMap<String, Object>();
        solo.put("role", "SOLO");
        solo.put("views", views);
        return solo;
    }

    /** 队伍里分配不到本人角色:两个 view 都不给,只留一个可渲染的空态标记。 */
    private static Map<String, Object> missingRoleView() {
        Map<String, Object> missing = new LinkedHashMap<String, Object>();
        missing.put("roleMissing", Boolean.TRUE);
        missing.put("views", Collections.emptyList());
        return missing;
    }

    /** 本人这条角色对应的 view(标题 / 正文 / items),配置怎么写就怎么下发。 */
    private Map<String, Object> roleViewOf(JsonNode raw, String roleId) {
        for (JsonNode entry : raw.path("views")) {
            if (entry == null || !entry.isObject() || !roleId.equals(entry.path("roleId").asText(""))) continue;
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("roleId", roleId);
            view.put("title", textOrNull(entry, "title"));
            view.put("body", textOrNull(entry, "body"));
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (JsonNode item : entry.path("items")) {
                if (item == null || !item.isObject()) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("label", item.path("label").asText(""));
                row.put("text", item.path("text").asText(""));
                items.add(row);
            }
            view.put("items", items);
            return view;
        }
        return null;
    }

    /** 本人在册队员里的次序;查不到返回 -1(selectActiveMembers 的口径即分配次序)。 */
    private int activeMemberIndex(Long teamId, Long memberId) {
        List<PlayTeamMember> members = playTeamMapper.selectActiveMembers(teamId);
        if (members == null) return -1;
        for (int i = 0; i < members.size(); i++) {
            PlayTeamMember member = members.get(i);
            if (member != null && memberId.equals(member.getMemberId())) return i;
        }
        return -1;
    }

    // ==================== R18-ENC:结果契约结果行 / 兜底方案 / 预加载 ====================
    // 只追加,不改已有字段的形状:outcomeCodes 原样保留,新增的 outcomes 是它的逐条展开。

    /** 本局冻结路线图里本站每条 trigger.outcomeCode 的下一站(按边序去重);没有边 = 空表。 */
    private Map<String, List<Long>> outcomeDestinations(JsonNode snapshot, Long nodeId) {
        Map<String, List<Long>> destinations = new LinkedHashMap<String, List<Long>>();
        if (snapshot == null || nodeId == null) return destinations;
        for (JsonNode edge : snapshot.path("edges")) {
            if (edge == null || edge.path("fromNodeId").asLong(-1L) != nodeId.longValue()) continue;
            String code = edge.path("trigger").path("outcomeCode").asText("");
            long to = edge.path("toNodeId").asLong(0L);
            if (code.isEmpty() || to <= 0L) continue;
            List<Long> targets = destinations.get(code);
            if (targets == null) {
                targets = new ArrayList<Long>();
                destinations.put(code, targets);
            }
            if (!targets.contains(Long.valueOf(to))) targets.add(Long.valueOf(to));
        }
        return destinations;
    }

    /** 本局建局冻结的路线图;无快照 / 坏 JSON / 非对象都当没有(结果行退化成只有 code)。 */
    private JsonNode snapshotOrNull(TopicRouteSession run) {
        if (run == null || StringUtils.isBlank(run.getConfigSnapshotJson())) return null;
        try {
            JsonNode snapshot = JSON.readTree(run.getConfigSnapshotJson());
            return snapshot != null && snapshot.isObject() ? snapshot : null;
        } catch (Exception unusableSnapshot) {
            return null;
        }
    }

    /**
     * fallbackPlan:到不了 / 玩不了时的兜底(闭店、坏码、定位漂移),恒定结构,与到店无关
     * (正是在「还没到 / 到不了」时要用)。
     *
     * <p>现码核查结论:旅程子系统没有节点级的闭店 / 替代地点 / 人工核销配置
     * ({@code GameSessionRuntimeService} 的 fallbackPlans 属 game session 另一子系统;
     * 节点营业时间 businessTime 是自由文本,三态计算在 admin 模块且明确「纯展示,不做门禁」),
     * 因此按固定结构返回缺省值 —— 配置落地后再把对应值接进来。
     */
    private Map<String, Object> fallbackPlan() {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("canSkip", Boolean.FALSE);
        plan.put("manualVerify", Boolean.FALSE);
        plan.put("message", null);
        return plan;
    }

    /** 公开投影里可能出现的媒体字段名;投影本身已是白名单,这里只认这三个键。 */
    private static final List<String> MEDIA_FIELDS = Collections.unmodifiableList(
            Arrays.asList("imageUrl", "audioUrl", "img"));

    /**
     * preload:这一拍该预载的资源 URL,按 封面(节点 → 玩法)→ 章节音频 → 节点语音 →
     * 玩法素材 → 出场 NPC 头像 收集,去重、保持顺序。
     *
     * <p>玩法素材只从 {@link AdvancedGamePublicProjection#safeConfigJson} 的玩家可见投影里扫 ——
     * 盲盒内容、扫码回复、答案键这些「玩家还没权限看到」的资源不在投影里,也就不会进 preload;
     * 节点停用段(如 hiddenObject.enabled=false)同样不进。
     */
    private List<String> preload(CmsMemberTemplate template, CmsTopicNode node,
            Map<String, Object> presentation) {
        List<String> urls = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        addUrl(urls, seen, node == null ? null : node.getImgUrl());
        addUrl(urls, seen, template == null ? null : template.getImgUrl());
        CmsTopicChapter chapter = node == null || node.getChapterId() == null ? null
                : cmsTopicChapterService.selectCmsTopicChapterById(node.getChapterId());
        addUrl(urls, seen, chapter == null ? null : chapter.getAudioUrl());
        addUrl(urls, seen, template == null ? null : template.getAudioUrl());
        collectMedia(publicMediaConfig(template), urls, seen);
        Object npc = presentation == null ? null : presentation.get("npc");
        if (npc instanceof Map) {
            Object avatar = ((Map<?, ?>) npc).get("avatar");
            if (avatar instanceof String) addUrl(urls, seen, (String) avatar);
        }
        return urls;
    }

    /** 块配置的玩家可见投影(与 /nodes 出参同一道白名单);投影不可用时返回 null。 */
    private JsonNode publicMediaConfig(CmsMemberTemplate template) {
        String raw = template == null ? null : template.getAdvancedConfigJson();
        if (StringUtils.isBlank(raw)) return null;
        try {
            String safe = AdvancedGamePublicProjection.safeConfigJson(raw);
            return StringUtils.isBlank(safe) ? null : JSON.readTree(safe);
        } catch (Exception unusableProjection) {
            return null;
        }
    }

    /** 递归收集公开投影里的媒体字段值;非白名单字段继续下钻,原样忽略。 */
    private static void collectMedia(JsonNode node, List<String> urls, Set<String> seen) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (MEDIA_FIELDS.contains(field.getKey()) && value != null && value.isTextual()) {
                    addUrl(urls, seen, value.asText());
                } else {
                    collectMedia(value, urls, seen);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) collectMedia(item, urls, seen);
        }
    }

    private static void addUrl(List<String> urls, Set<String> seen, String url) {
        if (StringUtils.isBlank(url)) return;
        String trimmed = url.trim();
        if (seen.add(trimmed)) urls.add(trimmed);
    }
}
