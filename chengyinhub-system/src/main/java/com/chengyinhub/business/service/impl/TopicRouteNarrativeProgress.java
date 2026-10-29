package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen opening and thought progress. Mutates only the supplied state/view, never persistence.
 * Reuses the runtime's existing engines and rule policy; no runtime callbacks or independent transaction.
 */
final class TopicRouteNarrativeProgress {

    private final CmsTopicMapper topicMapper;
    private final ObjectMapper JSON;
    private final TopicRouteEngine engine;
    private final TopicCompletionRulePolicy completionRulePolicy;
    private final ThemeRuntimeEngine themeEngine;

    TopicRouteNarrativeProgress(CmsTopicMapper topicMapper, ObjectMapper json, TopicRouteEngine engine,
            TopicCompletionRulePolicy completionRulePolicy, ThemeRuntimeEngine themeEngine) {
        this.topicMapper = topicMapper; this.JSON = json; this.engine = engine;
        this.completionRulePolicy = completionRulePolicy; this.themeEngine = themeEngine;
    }

    /** 思维阁抬高后的幸运上限;没有这个变量时以配置值为准。 */
    private static final String SYS_LUCK_MAX = "sys.luck.max";
    /** 念头进度变量前缀;想通了再往 tags 里写 {@code thought.<key>.done}。 */
    private static final String THOUGHT_PREFIX = "thought.";
    /** 上一次同步读到的微信运动今日步数,用来算这一段走了多少。 */
    private static final String SYS_STEPS_LAST = "sys.steps.last";
    private static final String SYS_STEPS_DAY = "sys.steps.day";

    JsonNode openingSnapshot(TopicRouteSession session) {
        if (session == null) return null;
        try { return JSON.readTree(session.getConfigSnapshotJson()).get("openingChapter"); }
        catch (Exception e) { throw new IllegalStateException("开局快照不可用", e); }
    }

    /** 只认建局冻结的章节、节点顺序和条件；旧局缺证据时不接受客户端认领。 */
    private Set<String> claimableThoughts(TopicRouteSession session, TopicRouteEngine.State state) {
        Set<String> keys = new LinkedHashSet<String>();
        final JsonNode root;
        try { root = JSON.readTree(session.getConfigSnapshotJson()); }
        catch (Exception invalid) { throw new IllegalStateException("故事快照不可用", invalid); }
        JsonNode chapters = root == null ? null : root.get("thoughtChapters");
        if (chapters == null || !chapters.isArray()) return keys;
        boolean openingPending = root.has("openingChapter") && !state.getTags().contains("sys.opening.done");
        for (JsonNode chapter : chapters) {
            if (openingPending && !chapter.path("opening").asBoolean(false)) continue;
            boolean reached = chapter.path("opening").asBoolean(false);
            for (JsonNode id : chapter.path("nodeIds")) {
                if (Long.valueOf(id.asLong()).equals(state.getCurrentNodeId())
                        || state.getCompletedNodeIds().contains(id.asLong())) reached = true;
            }
            // 无节点的过场依靠此前必经节点的完成事实证明已到达。
            if (!reached && chapter.path("nodeIds").size() == 0) {
                reached = true;
                for (JsonNode id : chapter.path("requiredBefore")) {
                    if (!state.getCompletedNodeIds().contains(id.asLong())) reached = false;
                }
            }
            if (!reached) continue;
            List<Object> blocks = JSON.convertValue(chapter.path("blocks"), new TypeReference<List<Object>>() { });
            for (Object item : ChapterBlockReadSupport.projectForPlayer(blocks,
                    state.getCompletedNodeIds(), state.getTags())) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> block = (Map<?, ?>) item;
                if ("thought".equals(block.get("type")) && block.get("thoughtKey") instanceof String) {
                    keys.add((String) block.get("thoughtKey"));
                }
            }
        }
        return keys;
    }

    /** 只累计当日单调增长的读数；日期推进才重置，旧局首次记录日期仅建立基线。 */
    private void accumulateThoughtSteps(TopicRouteEngine.State state, JourneyRules rules, int todaySteps, long day) {
        Number previousDay = state.getVariables().get(SYS_STEPS_DAY);
        Number last = state.getVariables().get(SYS_STEPS_LAST);
        if (previousDay != null && day < previousDay.longValue()) return;
        boolean sameDay = previousDay != null && day == previousDay.longValue();
        int delta = sameDay && last != null ? Math.max(0, todaySteps - last.intValue())
                : previousDay != null && day > previousDay.longValue() ? todaySteps : 0;
        state.getVariables().put(SYS_STEPS_DAY, Long.valueOf(day));
        state.getVariables().put(SYS_STEPS_LAST,
                Integer.valueOf(sameDay && last != null ? Math.max(todaySteps, last.intValue()) : todaySteps));
        if (delta <= 0) return;
        for (JsonNode row : rules.getThoughts()) {
            if (!row.hasNonNull("need")) continue;              // 只靠选项想通的念头不走步数
            String var = THOUGHT_PREFIX + row.path("key").asText();
            if (state.getTags().contains(var + ".done")) continue;
            Number progress = state.getVariables().get(var);
            if (progress == null) continue;
            int need = row.path("need").asInt();
            int next = Math.min(need, progress.intValue() + delta);
            state.getVariables().put(var, Integer.valueOf(next));
            if (next >= need) markThoughtDone(state, rules, var);
        }
    }

    /**
     * 伏笔的条件结算(2026-09-22 用户裁决「伏笔不能只是微信步数,回答出特定选项和条件时也有」):
     * when 满足即得到、doneWhen 满足即想通(条件间任一满足)。没得到的不会被想通 —— 伏笔没埋就应验
     * 是剧情错乱。幂等:已得到的不重置进度,已想通的不重复加成。
     */
    void settleThoughts(TopicRouteEngine.State state, JourneyRules rules) {
        for (JsonNode row : rules.getThoughts()) {
            String var = THOUGHT_PREFIX + row.path("key").asText();
            if (state.getTags().contains(var + ".done")) continue;
            boolean obtained = state.getVariables().containsKey(var);
            if (!obtained && row.has("when") && conditionPasses(row.get("when"), state)) {
                state.getVariables().put(var, Integer.valueOf(0));
                obtained = true;
            }
            if (obtained && row.has("doneWhen") && conditionPasses(row.get("doneWhen"), state)) {
                markThoughtDone(state, rules, var);
            }
        }
    }

    /** 想通了:打标签,幸运上限 +1。运行时上限可以超过配置值(2026-09-22 用户拍板)。 */
    private void markThoughtDone(TopicRouteEngine.State state, JourneyRules rules, String var) {
        if (!state.getTags().add(var + ".done")) return;
        state.getVariables().put(SYS_LUCK_MAX, Integer.valueOf(luckMaxOf(state, rules) + 1));
    }

    Long nextOpeningNode(JsonNode opening, TopicRouteEngine.State state) {
        for (JsonNode block : opening.path("blocks")) {
            if ("node".equals(block.path("type").asText())
                    && !state.getCompletedNodeIds().contains(block.path("nodeId").asLong())) {
                return block.path("nodeId").asLong();
            }
        }
        return null;
    }

    boolean openingAllows(TopicRouteSession session, Long nodeId) {
        JsonNode opening = openingSnapshot(session);
        if (opening == null) return true;
        TopicRouteEngine.State state = readEngineState(session.getStateJson());
        // nodeId 为空 = 没带节点上下文,开局没做完就不是开局里那一个,按拒绝算。
        return state.getTags().contains("sys.opening.done")
                || (nodeId != null && nodeId.equals(nextOpeningNode(opening, state)));
    }

    void keepOpeningRunning(TopicRouteSession session, TopicRouteEngine.State state) {
        if (!state.getThemeRuntime().has("result") && openingSnapshot(session) != null && !state.getTags().contains("sys.opening.done")) {
            session.setStatus(TopicRouteSession.STATUS_RUNNING);
            session.setCompletedAt(null);
        }
    }

    /**
     * 思维阁:把玩家**已经得到**的念头连同进度挂到视图上。
     *
     * <p>没得到的念头一个都不下发 —— 玩家还没在故事里读到那个 thought 块,提前看见名字就是剧透
     * (与未完成节点之后的正文一样的口径)。</p>
     */
    void appendThoughts(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        JourneyRules rules = sessionRules(session);
        view.setThoughtCount(rules.getThoughts().size());
        if (rules.getThoughts().size() == 0) return;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (JsonNode row : rules.getThoughts()) {
            String key = row.path("key").asText();
            String var = THOUGHT_PREFIX + key;
            boolean done = state.getTags().contains(var + ".done");
            Number progress = state.getVariables().get(var);
            if (!done && progress == null) continue;          // 还没得到
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", key);
            item.put("name", row.path("name").asText());
            if (row.hasNonNull("desc")) item.put("desc", row.path("desc").asText());
            int need = row.path("need").asInt(0);           // 只靠选项想通的念头 need=0,菜单不显示步数
            item.put("need", need);
            item.put("progress", done ? need : (progress == null ? 0 : progress.intValue()));
            item.put("done", done);
            rows.add(item);
        }
        if (!rows.isEmpty()) view.setThoughts(rows);
    }

    void appendOpening(TopicRouteState view, TopicRouteSession session, TopicRouteEngine.State state) {
        JsonNode frozen = openingSnapshot(session);
        if (frozen == null) return;
        Map<String, Object> opening = JSON.convertValue(frozen, new TypeReference<Map<String, Object>>() { });
        boolean complete = state.getTags().contains("sys.opening.done");
        opening.put("complete", complete);
        opening.put("ready", nextOpeningNode(frozen, state) == null);
        boolean themeComplete = TopicRouteState.COMPLETED.equals(view.getStatus());
        if (complete && !TopicRouteState.BRANCH_GRAPH.equals(view.getRouteMode())) {
            try {
                JsonNode snapshot = JSON.readTree(session.getConfigSnapshotJson());
                JsonNode nodes = snapshot.path(NodeJourneySnapshot.NODE_ANSWERS_KEY);
                int done = 0;
                for (Long id : state.getCompletedNodeIds()) if (nodes.has(String.valueOf(id))) done++;
                themeComplete = completionRulePolicy.isCompleted(
                        snapshot.path("topicCompleteRule").asText(null), nodes.size(), done);
            } catch (Exception e) { throw new IllegalStateException("通关规则不可用", e); }
        }
        opening.put("themeComplete", complete && themeComplete);
        JsonNode lastGame = null;
        for (JsonNode block : frozen.path("blocks")) {
            if ("node".equals(block.path("type").asText())) {
                lastGame = NodeJourneySnapshot.answer(session.getConfigSnapshotJson(), block.path("nodeId").asLong());
            }
        }
        opening.put("rewardPolicy", lastGame != null && SensorChallengePolicy.isPhotoFilter(
                lastGame.path("validationMethod").asInt(), lastGame.path("sensorType").asText())
                ? RewardPolicy.XP_BADGE_ONLY.name() : RewardPolicy.FULL.name());
        List<Object> blocks = JSON.convertValue(frozen.path("blocks"), new TypeReference<List<Object>>() { });
        opening.put("blocks", ChapterBlockReadSupport.projectForPlayer(blocks, state.getCompletedNodeIds(), state.getTags()));
        view.setOpening(opening);
        if (complete) return;
        // A completed route may still await opening confirmation while its session is RUNNING.
        // Only persisted session terminal states suppress the opening's playable projection.
        boolean terminal = Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus())
                || Integer.valueOf(TopicRouteSession.STATUS_ENDED).equals(session.getStatus());
        Long next = nextOpeningNode(frozen, state);
        if (terminal) {
            view.setStatus(state.getStatus());
            view.setCurrentNodeId(state.getCurrentNodeId()); view.setRecommendedNodeId(state.getRecommendedNodeId());
        } else {
            view.setStatus("ACTIVE");
            view.setCurrentNodeId(next); view.setRecommendedNodeId(next);
        }
        for (Map.Entry<Long, String> node : view.getNodeStates().entrySet()) {
            if (!TopicRouteState.COMPLETED.equals(node.getValue())) {
                node.setValue(!terminal && node.getKey().equals(next) ? TopicRouteState.PLAYABLE : TopicRouteState.HIDDEN);
            }
        }
        if (!terminal && next != null) view.getNodeStates().put(next, TopicRouteState.PLAYABLE);
    }

    /** 本局适用的数值规则:冻结快照优先;老局快照没有该键时才查实时主题列。 */
    JourneyRules sessionRules(TopicRouteSession session) {
        JsonNode snapshot = null;
        if (session != null && session.getConfigSnapshotJson() != null) {
            try { snapshot = JSON.readTree(session.getConfigSnapshotJson()); }
            catch (Exception unusableSnapshot) { /* Preserve legacy whole-snapshot fallback. */ }
        }
        if (snapshot != null && snapshot.has(NodeJourneySnapshot.JOURNEY_RULES_KEY)
                && snapshot.get(NodeJourneySnapshot.JOURNEY_RULES_KEY).isNull()) {
            throw new IllegalStateException("旅程数值规则不可用");
        }
        String frozen = NodeJourneySnapshot.journeyRules(
                session == null ? null : session.getConfigSnapshotJson());
        if (frozen != null) return JourneyRules.parse(frozen);
        return TopicRoutePublicationCompiler.journeyRules(session == null ? null : topicMapper.selectCmsTopicById(session.getTopicId()));
    }

    TopicRouteEngine.State readEngineState(String json) {
        try { return JSON.readValue(json, TopicRouteEngine.State.class); }
        catch (Exception e) { throw new IllegalStateException("路线局状态不可用", e); }
    }

    /**
     * advantageIf / disadvantageIf 是「任一通过」的 OR 语义(与 requires 的
     * 全通过不同);空数组 = 不成立,不是无条件成立。
     */
    boolean anyConditionPasses(JsonNode conditions, TopicRouteEngine.State state) {
        if (conditions == null || !conditions.isArray() || conditions.size() == 0) return false;
        ArrayNode single = JSON.createArrayNode();
        for (JsonNode condition : conditions) {
            single.removeAll();
            single.add(condition);
            if (engine.conditionsPass(single, state)) return true;
        }
        return false;
    }

    boolean conditionPasses(JsonNode condition, TopicRouteEngine.State state) {
        if (condition == null || condition.isNull()) return true;
        if (condition.isArray()) return anyConditionPasses(condition, state);
        ArrayNode single = JSON.createArrayNode();
        single.add(condition);
        return engine.conditionsPass(single, state);
    }

    /**
     * 运行时幸运上限。配置值是**起点**,思维阁里想通的念头可以把它抬高
     * (2026-09-22 用户拍板:运行时上限可以超过配置值)—— 否则 luck.max 已经配满的
     * 主题里,思维阁的奖励等于不存在。只抬不降:取两者较大值。
     */
    int luckMaxOf(TopicRouteEngine.State state, JourneyRules rules) {
        int configured = rules.getLuck().getMax();
        Number raised = state.getVariables().get(SYS_LUCK_MAX);
        return raised == null ? configured : Math.max(configured, raised.intValue());
    }

    /** Original claim/step transition; transaction, authorization and the session CAS stay at the caller. */
    void progressThoughts(TopicRouteSession session, TopicRouteEngine.State state, JourneyRules rules,
            List<String> claim, int todaySteps, long readingDay) {
        // 得到念头 = 在故事里读到了那个 thought 块。没得到的不替玩家开始累积。
        Set<String> known = new LinkedHashSet<String>();
        for (JsonNode row : rules.getThoughts()) known.add(row.path("key").asText());
        if (claim != null && !claim.isEmpty()) {
            Set<String> available = claimableThoughts(session, state);
            for (String key : claim) {
                String var = THOUGHT_PREFIX + key;
                if (!known.contains(key)) throw new IllegalArgumentException("念头不存在");
                if (!state.getVariables().containsKey(var) && !state.getTags().contains(var + ".done")) {
                    if (!available.contains(key)) throw new IllegalArgumentException("尚未读到这个念头");
                    state.getVariables().put(var, Integer.valueOf(0));
                }
            }
        }

        if (todaySteps >= 0) {
            Number last=state.getVariables().get(SYS_STEPS_LAST),day=state.getVariables().get(SYS_STEPS_DAY);
            int delta = day == null ? 0 : readingDay > day.longValue() ? todaySteps
                    : readingDay == day.longValue() && last != null ? Math.max(0, todaySteps - last.intValue()) : 0;
            themeEngine.chapterSteps(state,delta);
            accumulateThoughtSteps(state, rules, todaySteps, readingDay);
        }

    }

}
