package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.TopicRouteSessionScope;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.domain.vo.PublicCouponSummaryVO;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayJourneyReadMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.IPlayJourneyReadService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 案件册 / 背包只读读侧(R8-CASEBOOK)。
 *
 * <p>一局的权威事实只有两处:本局 {@code topic_route_session.state_json}(变量 / tag)
 * 与 {@code topic_route_action}(动作流水,含检定回执里的 hp/luck 状态视图)。
 * 奖励与领取状态复用既有记录,不新建表、不写任何行。
 *
 * <p>口径写死在这里,别在别处再推一遍:
 * <ul>
 *   <li>facts = 注册表里 {@code clue.*} 且本局变量 ≥1 的键;costs = 注册表里 {@code tag.*} 且本局持有的 tag。</li>
 *   <li>relations 三态:{@code ≥1 ally / 0 或缺 neutral / ≤-1 guarded}。npcId 只认
 *       {@code journey_story_json.ending.epilogues} 的 relationKey→npcId 显式映射;
 *       没配 epilogues 的 relation.* 键 npcId=null、名字用注册 label。不再按名字模糊匹配。</li>
 *   <li>log 按动作 id 前缀分类:节点完成只认节点推进动作;检定 settle/reroll 与力竭休整
 *       照旧;{@code stage-rest:} 归 rest、{@code mistake:} 归 mistake(只给「生命 -N」,
 *       首错免罚不进日志);{@code ask:} / {@code call} 不进日志。同 actionId 只算一次(重放不重复计)。
 *       hpDelta / luckDelta = 相邻两次状态视图(检定回执里的 hp / luck 快照)相减,
 *       首个视图与主题规则初值相减;没有视图的事件计 0。</li>
 *   <li>保留变量 {@code sys.*}(含 {@code sys.damage.{nodeId}})只由服务端写、玩家只读,
 *       案件册只枚举注册表里的 clue./relation./tag. 键,任何响应都不下发 sys.* 键名。</li>
 *   <li>found / total 读 {@code cms_topic.journey_story_json.ending.optionalClues}
 *       (列已存在,真实库错误不吞,原样抛给上层)。</li>
 * </ul>
 */
@Service
public class PlayJourneyReadServiceImpl implements IPlayJourneyReadService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PREFIX_CLUE = "clue.";
    private static final String PREFIX_RELATION = "relation.";
    private static final String PREFIX_TAG = "tag.";

    /** 动作 id 前缀:内部动作按此分类,不再把 R9 新动作当成节点完成。 */
    private static final String PREFIX_CHECK = "check:";
    private static final String PREFIX_REST = "rest:";
    private static final String PREFIX_STAGE_REST = "stage-rest:";
    private static final String PREFIX_MISTAKE = "mistake:";
    /** Doc2 F1a 定向会话回执(prepare/start/终局/任务结果)与节点推进无关,不进属性日志。 */
    private static final String PREFIX_ORIENT = "orient:";
    private static final String PREFIX_ASK = "ask:";
    private static final String ID_CALL = "call";

    static final String STATE_ALLY = "ally";
    static final String STATE_NEUTRAL = "neutral";
    static final String STATE_GUARDED = "guarded";

    static final String STATUS_CLAIMED = "claimed";
    static final String STATUS_PENDING = "pending";
    static final String STATUS_NONE = "none";

    static final String KIND_NODE = "node";
    static final String KIND_CHECK = "check";
    static final String KIND_REROLL = "reroll";
    static final String KIND_REST = "rest";
    static final String KIND_MISTAKE = "mistake";
    /** 内部标记:掷骰只推进 hp/luck 视图链,不是一条日志。 */
    static final String KIND_ROLL = "roll";

    private static final String OUTCOME_CHECK_ROLL = "CHECK_ROLL";
    private static final String OUTCOME_CHECK_REROLL = "CHECK_REROLL";
    private static final String OUTCOME_CHECK_SETTLE = "CHECK_SETTLE";
    private static final String OUTCOME_CHECK_REST = "CHECK_REST";

    private final CmsTopicMapper topicMapper;
    private final CmsTopicNodeMapper nodeMapper;
    private final CmsMemberTemplateMapper templateMapper;
    private final TopicRouteSessionMapper routeMapper;
    private final PlayerNodeProgressMapper progressMapper;
    private final SmsCouponHistoryMapper couponHistoryMapper;
    private final PlayJourneyReadMapper readMapper;
    private final NpcProfileService npcProfileService;
    private final INodeMerchantOverrideService nodeMerchantOverrideService;
    private final ThemeStateKeyService themeStateKeyService;
    private final ISmsCouponService couponService;
    private final IXpBudgetService xpBudgetService;

    @Autowired
    public PlayJourneyReadServiceImpl(CmsTopicMapper topicMapper, CmsTopicNodeMapper nodeMapper,
            CmsMemberTemplateMapper templateMapper, TopicRouteSessionMapper routeMapper,
            PlayerNodeProgressMapper progressMapper, SmsCouponHistoryMapper couponHistoryMapper,
            PlayJourneyReadMapper readMapper,
            NpcProfileService npcProfileService, INodeMerchantOverrideService nodeMerchantOverrideService,
            ThemeStateKeyService themeStateKeyService, ISmsCouponService couponService,
            IXpBudgetService xpBudgetService) {
        this.topicMapper = topicMapper;
        this.nodeMapper = nodeMapper;
        this.templateMapper = templateMapper;
        this.routeMapper = routeMapper;
        this.progressMapper = progressMapper;
        this.couponHistoryMapper = couponHistoryMapper;
        this.readMapper = readMapper;
        this.npcProfileService = npcProfileService;
        this.nodeMerchantOverrideService = nodeMerchantOverrideService;
        this.themeStateKeyService = themeStateKeyService;
        this.couponService = couponService;
        this.xpBudgetService = xpBudgetService;
    }

    // ==================== 案件册 ====================

    @Override
    public Map<String, Object> casebook(Long memberId, Long topicId, Long activityId) {
        requireScope(memberId, topicId);
        CmsTopic topic = requireTopic(topicId);
        TopicRouteSession session = TopicRouteSessionScope.findCurrent(routeMapper, memberId, topicId, activityId);
        if (session == null) throw new ServiceException("这一局还没开始");
        // 查询和本地校验都限定归属，禁止读取别人的局。
        if (!memberId.equals(session.getMemberId())) throw new ServiceException("无权访问这一局");

        TopicRouteEngine.State state = readState(session);
        List<ThemeStateKey> definitions = JourneyInitialState.frozenDefinitions(session.getConfigSnapshotJson());
        String frozenRules = definitions == null ? NodeJourneySnapshot.journeyRules(session.getConfigSnapshotJson())
                : JourneyInitialState.frozenDefinitionRules(session.getConfigSnapshotJson());
        JourneyRules rules = JourneyRules.parse(frozenRules != null ? frozenRules : topic.getJourneyRulesJson());
        List<ThemeStateKey> keys = definitions == null
                ? JourneyInitialState.visibleKeys(themeStateKeyService.listByTopic(topicId), rules, session.getConfigSnapshotJson())
                : JourneyInitialState.visibleKeys(definitions, rules);
        String storyJson = casebookStoryJson(topicId, session);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("facts", facts(keys, state));
        data.put("relations", relations(keys, state, topicId, storyJson));
        data.put("costs", costs(keys, state));
        data.put("log", log(session, rules));
        int[] optional = optionalClueProgress(storyJson, state);
        data.put("found", Integer.valueOf(optional[0]));
        data.put("total", Integer.valueOf(optional[1]));
        return data;
    }

    /** 已持有(变量 ≥1)且已注册的 clue.* 事实,按注册顺序。 */
    private List<Map<String, Object>> facts(List<ThemeStateKey> keys, TopicRouteEngine.State state) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (ThemeStateKey key : keys) {
            if (!key.getStateKey().startsWith(PREFIX_CLUE)) continue;
            Number value = state.getVariables().get(key.getStateKey());
            if (value == null || value.intValue() < 1) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", key.getStateKey());
            item.put("label", labelOf(key));
            out.add(item);
        }
        return out;
    }

    /** 已持有的代价 tag(注册表里有声明才带得出 label),按注册顺序。 */
    private List<Map<String, Object>> costs(List<ThemeStateKey> keys, TopicRouteEngine.State state) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (ThemeStateKey key : keys) {
            if (!key.getStateKey().startsWith(PREFIX_TAG)) continue;
            if (!state.getTags().contains(key.getStateKey())) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("tag", key.getStateKey());
            item.put("label", labelOf(key));
            out.add(item);
        }
        return out;
    }

    /**
     * 全部已注册的 relation.* 键,按三态口径给状态,按注册顺序。
     * ≤-1 一律 guarded(值域是 [-99,99] 整数,不存在低于 -1 的第四态)。
     */
    private List<Map<String, Object>> relations(List<ThemeStateKey> keys, TopicRouteEngine.State state,
            Long topicId, String storyJson) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<ThemeStateKey> relationKeys = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey key : keys) {
            if (key.getStateKey().startsWith(PREFIX_RELATION)) relationKeys.add(key);
        }
        if (relationKeys.isEmpty()) return out;
        Map<String, Long> npcByRelationKey = epilogueNpcIds(storyJson);
        Map<Long, String> npcNames = npcNames(topicId);
        for (ThemeStateKey key : relationKeys) {
            Long npcId = npcByRelationKey.get(key.getStateKey());
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("npcId", npcId);
            item.put("name", npcName(npcNames, npcId, key));
            Number value = state.getVariables().get(key.getStateKey());
            item.put("state", threeState(value == null ? 0 : value.intValue()));
            out.add(item);
        }
        return out;
    }

    /** 三态映射:≥1 ally / 0 或缺 neutral / ≤-1 guarded。 */
    static String threeState(int value) {
        if (value >= 1) return STATE_ALLY;
        if (value <= -1) return STATE_GUARDED;
        return STATE_NEUTRAL;
    }

    /**
     * journey_story_json.ending.epilogues 的 relationKey→npcId 显式映射;未配置 / 坏 JSON = 空映射
     * (没有映射的 relation.* 键仍然出现,npcId=null、名字回落注册 label)。
     */
    private static Map<String, Long> epilogueNpcIds(String storyJson) {
        Map<String, Long> mapping = new LinkedHashMap<String, Long>();
        if (StringUtils.isBlank(storyJson)) return mapping;
        try {
            JsonNode epilogues = JSON.readTree(storyJson).path("ending").path("epilogues");
            if (!epilogues.isArray()) return mapping;
            for (JsonNode item : epilogues) {
                if (item == null || !item.isObject()) continue;
                String relationKey = item.path("relationKey").asText("").trim();
                long npcId = item.path("npcId").asLong(0L);
                if (!relationKey.isEmpty() && npcId > 0L) {
                    mapping.put(relationKey, Long.valueOf(npcId));
                }
            }
        } catch (Exception unusableStory) {
            return mapping;
        }
        return mapping;
    }

    /** 展示名:epilogues 映射到 NPC 就用该 NPC 的名字,否则回落注册 label(再回落键后缀,不露前缀)。 */
    private static String npcName(Map<Long, String> npcNames, Long npcId, ThemeStateKey key) {
        if (npcId != null) {
            String name = npcNames.get(npcId);
            if (StringUtils.isNotBlank(name)) return name;
        }
        return key.displayLabel();
    }

    /**
     * 本主题可用 NPC 的 id→name:节点级(scope 4)优先、门店分身(scope 2)兜底,沿用
     * {@link NpcProfileService#resolveForNodeOrMerchant}(读闸 = 已过审且启用)。
     */
    private Map<Long, String> npcNames(Long topicId) {
        Map<Long, String> names = new LinkedHashMap<Long, String>();
        for (CmsTopicNode node : liveNodes(topicId)) {
            NpcProfileVO profile = npcProfileService.resolveForNodeOrMerchant(node.getId(), node.getMerchantMemberId());
            if (profile == null || profile.getProfileId() == null) continue;
            names.put(profile.getProfileId(), profile.getName());
        }
        return names;
    }

    // ==================== 属性日志 ====================

    /**
     * 按时间从 topic_route_action 还原日志。四类事件:节点完成 / 检定结算 / 幸运重掷 / 力竭休整;
     * 纯掷骰(roll)不进日志。同 actionId 只收第一条 —— 重放返回同一份回执、库里也不会有第二行,
     * 这里再去重一次,保证「重放算两次」这种退化不会从读侧漏出来。
     */
    private List<Map<String, Object>> log(TopicRouteSession session, JourneyRules rules) {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        List<TopicRouteAction> actions = readMapper.selectActionsBySession(session.getId());
        if (actions == null || actions.isEmpty()) return events;

        Map<Long, String> names = nodeNames(session.getTopicId());
        Integer prevHp = rules.isCheckEnabled() && rules.getHp().isEnabled()
                ? Integer.valueOf(rules.getHp().getInit()) : null;
        Integer prevLuck = rules.isCheckEnabled() && rules.getLuck().isEnabled()
                ? Integer.valueOf(rules.getLuck().getInit()) : null;

        Set<String> seen = new HashSet<String>();
        for (TopicRouteAction action : actions) {
            if (action == null || StringUtils.isBlank(action.getActionId())) continue;
            if (!seen.add(action.getActionId())) continue;
            String kind = kindOf(action.getActionId(), action.getOutcomeCode());
            if (kind == null) continue;
            if (KIND_ROLL.equals(kind)) {
                // 掷骰也要推进 hp/luck 视图链,但本身不是一条日志
                PlayCheckReceipt roll = readReceipt(action);
                if (roll != null) { prevHp = nextHp(prevHp, roll); prevLuck = nextLuck(prevLuck, roll); }
                continue;
            }
            boolean stageRest = action.getActionId().startsWith(PREFIX_STAGE_REST);
            // 节点推进的 routeState 视图没有检定位,不解析(保持原口径)
            PlayCheckReceipt receipt = KIND_NODE.equals(kind) ? null : readReceipt(action);
            int hpDelta = 0;
            int luckDelta = 0;
            if (receipt != null) {
                if (receipt.getHp() != null) {
                    hpDelta = prevHp == null ? 0 : receipt.getHp().intValue() - prevHp.intValue();
                    prevHp = receipt.getHp();
                }
                if (receipt.getLuck() != null) {
                    luckDelta = prevLuck == null ? 0 : receipt.getLuck().intValue() - prevLuck.intValue();
                    prevLuck = receipt.getLuck();
                }
            }
            // 首错免罚 / 阶段休整没动属性:不值得占一行日志
            if (KIND_MISTAKE.equals(kind) && hpDelta == 0) continue;
            if (stageRest && hpDelta == 0 && luckDelta == 0) continue;
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("at", action.getCreateTime() == null ? null : Long.valueOf(action.getCreateTime().getTime()));
            event.put("kind", kind);
            event.put("text", KIND_MISTAKE.equals(kind)
                    ? "答错 · 生命 -" + (-hpDelta)
                    : stageRest ? "阶段休整"
                    : eventText(kind, receipt, nodeName(names, action.getNodeId())));
            event.put("hpDelta", Integer.valueOf(hpDelta));
            event.put("luckDelta", Integer.valueOf(luckDelta));
            events.add(event);
        }
        return events;
    }

    private static Integer nextHp(Integer prev, PlayCheckReceipt receipt) {
        return receipt.getHp() == null ? prev : receipt.getHp();
    }

    private static Integer nextLuck(Integer prev, PlayCheckReceipt receipt) {
        return receipt.getLuck() == null ? prev : receipt.getLuck();
    }

    /**
     * 事件种类,按动作 id 前缀分(节点推进动作的 id 是客户端 routeActionId,没有固定前缀):
     * mistake: / stage-rest: / ask: / call / check:* / rest:* 是内部动作,其余 = 节点推进。
     * 返回 null = 不进日志;{@link #KIND_ROLL} = 仅推进 hp/luck 视图链。
     */
    static String kindOf(String actionId, String outcomeCode) {
        String id = actionId == null ? "" : actionId;
        if (id.startsWith(PREFIX_ORIENT)) return null;
        if (id.startsWith(PREFIX_MISTAKE)) return KIND_MISTAKE;
        if (id.startsWith(PREFIX_STAGE_REST)) return KIND_REST;
        if (id.startsWith(PREFIX_ASK) || ID_CALL.equals(id)) return null;
        if (id.startsWith(PREFIX_CHECK) || id.startsWith(PREFIX_REST)) {
            if (OUTCOME_CHECK_ROLL.equals(outcomeCode)) return KIND_ROLL;
            if (OUTCOME_CHECK_REROLL.equals(outcomeCode)) return KIND_REROLL;
            if (OUTCOME_CHECK_SETTLE.equals(outcomeCode)) return KIND_CHECK;
            if (OUTCOME_CHECK_REST.equals(outcomeCode)) return KIND_REST;
            return null;
        }
        if (outcomeCode != null && outcomeCode.startsWith("CHECK_")) return null;
        return KIND_NODE;
    }

    private static String eventText(String kind, PlayCheckReceipt receipt, String nodeName) {
        String prefix = "「" + nodeName + "」";
        if (KIND_NODE.equals(kind)) return "完成节点" + prefix;
        if (KIND_REROLL.equals(kind)) return prefix + "幸运重掷";
        if (KIND_REST.equals(kind)) return prefix + "力竭休整";
        String verdict = receipt != null && Boolean.TRUE.equals(receipt.getSuccess()) ? "成功" : "失败";
        String line = prefix + "检定" + verdict;
        String detail = receipt == null ? null : receipt.getText();
        return StringUtils.isBlank(detail) ? line : line + ":" + detail;
    }

    /**
     * 动作回执的 hp/luck 状态视图(检定 / 判错 / 休整的 response_json);
     * 解析不了返回 null,该事件属性变化计 0(保持原口径:坏回执不让案件册整体失败)。
     */
    private PlayCheckReceipt readReceipt(TopicRouteAction action) {
        if (StringUtils.isBlank(action.getResponseJson())) return null;
        try {
            return JSON.readValue(action.getResponseJson(), PlayCheckReceipt.class);
        } catch (Exception unusableReceipt) {
            return null;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chengyinhub.business.service.ITopicRouteRuntimeService themeRuntime;

    // ==================== 背包 ====================

    @Override
    public Map<String, Object> backpack(Long memberId, Long topicId, Long activityId) {
        requireScope(memberId, topicId);
        CmsTopic topic = requireTopic(topicId);
        TopicRouteSession session = TopicRouteSessionScope.findCurrent(routeMapper, memberId, topicId, activityId);
        requireReadableBackpack(topic, memberId, session);

        CompletionFacts progress = progress(memberId, topicId, session == null ? activityId : session.getActivityId());
        Set<Long> heldCouponIds = heldCouponIds(memberId);
        Map<Long, CmsMemberTemplate> templates = new HashMap<Long, CmsMemberTemplate>();
        // R22-A1:交付文案(叙事)真源 = 本局建局冻结的故事流叙事(与 Encounter 读 deliver 同源)。


        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (CmsTopicNode node : liveNodes(topicId)) {
            CmsMemberTemplate template = template(node, templates);
            Long couponId = effectiveCouponId(node, template);
            String deliver = deliverText(session, node.getId());
            String medalName = template == null ? null : trimToNull(template.getMedalName());
            String medalImg = template == null ? null : trimToNull(template.getMedalImg());
            int xp = xpBudgetService.resolveNodeXp(node);

            boolean couponConfigured = couponId != null && couponId.longValue() > 0L;
            String reward = rewardText(deliver, couponConfigured ? couponId : null, medalName, medalImg, xp);

            // 模板勋章没有独立领取记录:勋章墙( ApiMedalController )口径 = 节点完成 + 模板有勋章图 ⇒
            // 「完成」就是勋章的到手事实,只有券要另查领取记录(拍照完成等不发券的路径会停在 pending)。
            String status;
            if (reward == null) {
                status = STATUS_NONE;
            } else if (!progress.completed.contains(node.getId())) {
                status = STATUS_PENDING;
            } else if (couponConfigured && !heldCouponIds.contains(couponId)) {
                status = STATUS_PENDING;
            } else {
                status = STATUS_CLAIMED;
            }

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("nodeId", node.getId());
            item.put("reward", reward);
            item.put("status", status);
            // claimableAt 只在「已完成、奖励却还没到手」时有意义 = 完成时刻
            Date doneAt = progress.doneAt.get(node.getId());
            if (STATUS_PENDING.equals(status) && doneAt != null) item.put("claimableAt", Long.valueOf(doneAt.getTime()));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("nodes", items);
        if(themeRuntime!=null && session!=null && NodeJourneySnapshot.journeyStory(session.getConfigSnapshotJson())!=null) {
            try {JsonNode root=JSON.readTree(session.getConfigSnapshotJson());if(root.has("themeConfig"))data.put("theme",themeRuntime.themeView(memberId,topicId,session.getActivityId(),session.getId()));}
            catch(java.io.IOException invalid){throw new ServiceException("本局配置不可读取");}
        }
        return data;
    }

    /**
     * 奖励文案:优先节点块交付文案(deliver.reward),没配才按券 / 勋章 / 探索值拼;
     * 一项都没配返回 null = 这个节点没有交付。
     */
    private String rewardText(String deliver, Long couponId, String medalName, String medalImg, int xp) {
        if (StringUtils.isNotBlank(deliver)) return deliver.trim();
        List<String> parts = new ArrayList<String>();
        if (couponId != null && couponId.longValue() > 0L) {
            String couponName = couponName(couponId);
            parts.add(couponName == null ? "到店券" : "到店券:" + couponName);
        }
        if (medalName != null) parts.add("勋章:" + medalName);
        else if (medalImg != null) parts.add("纪念勋章");
        if (xp > 0) parts.add("探索值 " + xp);
        if (parts.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(" · ");
            out.append(part);
        }
        return out.toString();
    }

    /** 券名取公开投影(下架/删除的券不给名字);查不到返回 null,调用方回落通用名。 */
    private String couponName(Long couponId) {
        try {
            PublicCouponSummaryVO summary =
                    PublicCouponSummaryVO.from(couponService.selectSmsCouponById(couponId));
            if (summary != null && StringUtils.isNotBlank(summary.getName())) return summary.getName().trim();
        } catch (RuntimeException unavailableCoupon) {
            // 券查不到不影响背包:回落通用名
        }
        return null;
    }

    /** 到店券取承接商家覆写、回落模板 couponId(与 advanceProgress 发券同一条口径)。 */
    private Long effectiveCouponId(CmsTopicNode node, CmsMemberTemplate template) {
        Long templateCoupon = template == null ? null : template.getCouponId();
        if (node.getId() == null) return templateCoupon;
        return nodeMerchantOverrideService.forNode(node.getId()).couponIdOr(templateCoupon);
    }

    /** 本会员已持有的券模板 id 集合(领取记录即领取事实,核销与否不影响「已到手」)。 */
    private Set<Long> heldCouponIds(Long memberId) {
        Set<Long> ids = new LinkedHashSet<Long>();
        SmsCouponHistory probe = new SmsCouponHistory();
        probe.setMemberId(memberId);
        List<SmsCouponHistory> rows = couponHistoryMapper.selectSmsCouponHistoryList(probe);
        if (rows == null) return ids;
        for (SmsCouponHistory row : rows) {
            if (row == null || row.getCouponId() == null) continue;
            if (Integer.valueOf(1).equals(row.getDelFlag())) continue;
            ids.add(row.getCouponId());
        }
        return ids;
    }

    private static final class CompletionFacts {
        private final Set<Long> completed = new LinkedHashSet<Long>();
        private final Map<Long, Date> doneAt = new LinkedHashMap<Long, Date>();
    }

    /** 本主题完成事实:node_id → 完成时间。status=1 才算完成(与 /nodes、运行时同口径)。 */
    private CompletionFacts progress(Long memberId, Long topicId, Long activityId) {
        CompletionFacts facts = new CompletionFacts();
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setTopicId(topicId);
        if (activityId == null) return facts;
        probe.setActivityId(activityId);
        List<PlayerNodeProgress> rows = progressMapper.selectPlayerNodeProgressList(probe);
        if (rows == null) return facts;
        for (PlayerNodeProgress row : rows) {
            if (row == null || row.getNodeId() == null) continue;
            if (!Integer.valueOf(1).equals(row.getStatus())) continue;
            facts.completed.add(row.getNodeId());
            Date at = row.getCheckinTime() == null ? row.getCreateTime() : row.getCheckinTime();
            Date old = facts.doneAt.get(row.getNodeId());
            if (at != null && (old == null || at.before(old))) facts.doneAt.put(row.getNodeId(), at);
        }
        return facts;
    }

    // ==================== 共用 ====================

    private void requireScope(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L) {
            throw new ServiceException("参数有误");
        }
    }

    private CmsTopic requireTopic(Long topicId) {
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) throw new ServiceException("主题不存在");
        return topic;
    }

    /**
     * R22-SEC S19:背包读的是奖励与领取事实,主题必须在架/已发布(status=1 且 userStatus=1,
     * 与公开面同一口径),或本人已有本局 —— 否则下架 / 草稿主题的奖励清单仍会被任意登录者按 id 读出。
     */
    private void requireReadableBackpack(CmsTopic topic, Long memberId, TopicRouteSession session) {
        if (Integer.valueOf(1).equals(topic.getStatus()) && Integer.valueOf(1).equals(topic.getUserStatus())) {
            return;
        }

        if (session == null || !memberId.equals(session.getMemberId())) {
            throw new ServiceException("主题不存在或未上架");
        }
    }

    /** 本主题在架节点(status=1),按 sortId 升序(与运行时 nodes() 同口径)。 */
    private List<CmsTopicNode> liveNodes(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> result = nodeMapper.selectCmsTopicNodeList(probe);
        if (result == null) return Collections.emptyList();
        List<CmsTopicNode> nodes = new ArrayList<CmsTopicNode>(result);
        Collections.sort(nodes, Comparator.comparingLong(
                n -> n.getSortId() == null ? 0L : n.getSortId().longValue()));
        return nodes;
    }

    private Map<Long, String> nodeNames(Long topicId) {
        Map<Long, String> names = new LinkedHashMap<Long, String>();
        for (CmsTopicNode node : liveNodes(topicId)) names.put(node.getId(), node.getName());
        return names;
    }

    /** 日志里的节点名:在架节点查表;已下架的历史节点按 id 补查;都拿不到回落 nodeId。 */
    private String nodeName(Map<Long, String> names, Long nodeId) {
        if (nodeId == null) return "";
        String name = names.get(nodeId);
        if (name != null) return name;
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        if (node != null && StringUtils.isNotBlank(node.getName())) {
            names.put(nodeId, node.getName());
            return node.getName();
        }
        return "节点" + nodeId;
    }

    private TopicRouteEngine.State readState(TopicRouteSession session) {
        try {
            TopicRouteEngine.State state = JSON.readValue(session.getStateJson(), TopicRouteEngine.State.class);
            return state == null ? new TopicRouteEngine.State() : state;
        } catch (Exception unusableState) {
            throw new ServiceException("本局状态不可用");
        }
    }

    /**
     * 案件册用的剧情配置:建局冻结值优先(R11-A),老快照回退实时列(R8-STORY 新增)。
     * 列已存在,库错误不吞,原样抛给上层(R11-C)。
     */
    private String casebookStoryJson(Long topicId, TopicRouteSession session) {
        String frozen = NodeJourneySnapshot.journeyStory(
                session == null ? null : session.getConfigSnapshotJson());
        return frozen != null ? frozen : readMapper.selectJourneyStoryJson(topicId);
    }

    /**
     * 可选发现进度:读剧情配置 journey_story_json.ending.optionalClues,建局冻结值优先(R11-A),
     * 老快照回退实时列;found = optionalClues 里变量 ≥1 的条数,total = 条数。
     * 未配置 → 0/0,剧情 JSON 坏 = 0/0(读侧容错);库错误不吞,由上层按 500 暴露。
     */
    private int[] optionalClueProgress(String storyJson, TopicRouteEngine.State state) {
        if (StringUtils.isBlank(storyJson)) return new int[] {0, 0};
        try {
            JsonNode clues = JSON.readTree(storyJson).path("ending").path("optionalClues");
            if (!clues.isArray()) return new int[] {0, 0};
            int total = 0;
            int found = 0;
            for (JsonNode clue : clues) {
                String key = clue == null ? "" : clue.asText("");
                if (StringUtils.isBlank(key)) continue;
                total++;
                Number value = state.getVariables().get(key.trim());
                if (value != null && value.intValue() >= 1) found++;
            }
            return new int[] {found, total};
        } catch (Exception unusableStory) {
            return new int[] {0, 0};
        }
    }

    private CmsMemberTemplate template(CmsTopicNode node, Map<Long, CmsMemberTemplate> cache) {
        Long templateId = node.getTemplateId();
        if (templateId == null || templateId <= 0L) return null;
        if (cache.containsKey(templateId)) return cache.get(templateId);
        CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(templateId);
        cache.put(templateId, template);
        return template;
    }

    /**
     * 节点交付文案 deliver.reward:读本局建局冻结的故事流叙事(与 Encounter 的 deliver 同一来源),
     * 老局快照没有 nodeJourney 键 / 建局时该站没配 deliver = 没有交付文案,不再回退模板
     * (R18 起模板叙事段已废弃,运行期不再从模板读)。坏 JSON 当没配,不让背包整体失败。
     */
    private String deliverText(TopicRouteSession session, Long nodeId) {
        JsonNode block = NodeJourneySnapshot.block(
                session == null ? null : session.getConfigSnapshotJson(), nodeId);
        if (block == null) return null;
        JsonNode deliver = block.get("deliver");
        if (deliver == null || !deliver.isObject()) return null;
        return trimToNull(deliver.path("reward").asText(""));
    }

    private static String labelOf(ThemeStateKey key) {
        return key.displayLabel();
    }

    private static String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }
}
