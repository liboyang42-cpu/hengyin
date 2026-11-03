package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * C03 原 requestId 绑定:玩家显式选择已启用机制后,服务端按<strong>原 requestId</strong>
 * 只落一次效果;同 id 回读原结果;UNKNOWN 恢复不得 mint 新请求。
 *
 * <p>不是第二套骰子 / 钱包 / request 唯一键。运行入口仍用现有
 * {@link TopicRouteAction#getActionId()}(与检定 actionId 同一条账),本对象只在
 * mechanism 包内做独占绑定,供隔离测试与 dot 接线。
 */
public final class MechanismRequestIdBinder {
    public static final String APPLIED = "APPLIED";
    public static final String REPLAY = "REPLAY";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";

    public static final String MECH_PROGRESS_CLOCK = "progressClock";
    public static final String MECH_TRADE_BARGAIN = "tradeBargain";
    public static final String MECH_ORACLE_BOUNTY = "oracleBounty";
    public static final String MECH_OATH_FLASHBACK = "oathFlashback";
    public static final String MECH_ROUTE_COMMUNITY_DAY = "routeCommunityDay";
    public static final String MECH_CLUE_SECRET_GOAL = "clueSecretGoal";

    static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, LedgerEntry> byRequestId = new LinkedHashMap<String, LedgerEntry>();

    /** 一次玩家选择的工作上下文。冻结快照由作者 select→validate→publish freeze 写入。 */
    public static final class Play {
        private JourneyRules rules;
        private TopicRouteEngine.State state;
        private final TopicRouteEngine engine = new TopicRouteEngine();
        private JsonNode check;
        private ObjectNode frozen = JSON.createObjectNode();
        private JsonNode playerChoice;
        private String originalRequestId;
        private TopicRouteAction originalAction;
        private IntSupplier dice;
        private Map<String, Object> lastResult;

        public JourneyRules getRules() { return rules; }
        public void setRules(JourneyRules rules) { this.rules = rules; }
        public TopicRouteEngine.State getState() { return state; }
        public void setState(TopicRouteEngine.State state) { this.state = state; }
        public TopicRouteEngine engine() { return engine; }
        public JsonNode getCheck() { return check; }
        public void setCheck(JsonNode check) { this.check = check; }
        public ObjectNode getFrozen() { return frozen; }
        public void setFrozen(ObjectNode frozen) { this.frozen = frozen == null ? JSON.createObjectNode() : frozen; }
        public JsonNode getPlayerChoice() { return playerChoice; }
        public void setPlayerChoice(JsonNode playerChoice) { this.playerChoice = playerChoice; }
        public String getOriginalRequestId() { return originalRequestId; }
        public void setOriginalRequestId(String originalRequestId) { this.originalRequestId = originalRequestId; }
        public TopicRouteAction getOriginalAction() { return originalAction; }
        public void setOriginalAction(TopicRouteAction originalAction) { this.originalAction = originalAction; }
        public IntSupplier getDice() { return dice; }
        public void setDice(IntSupplier dice) { this.dice = dice; }
        public Map<String, Object> getLastResult() { return lastResult; }
        public void setLastResult(Map<String, Object> lastResult) { this.lastResult = lastResult; }
    }

    /** 原 requestId 的一次效果账。status=UNKNOWN 时仍占用原 id,不另开 retry。 */
    public static final class LedgerEntry {
        private String requestId;
        private String mechanism;
        private String choice;
        private String status = PENDING;
        private Map<String, Object> result;
        private TopicRouteAction originalAction;

        public String getRequestId() { return requestId; }
        public String getMechanism() { return mechanism; }
        public String getChoice() { return choice; }
        public String getStatus() { return status; }
        public Map<String, Object> getResult() {
            return result == null ? null : Collections.unmodifiableMap(result);
        }
        public TopicRouteAction getOriginalAction() { return originalAction; }
    }

    public static ObjectNode newFreeze() {
        ObjectNode freeze = JSON.createObjectNode();
        freeze.put("schemaVersion", 1);
        return freeze;
    }

    public static void putFrozen(Play play, String mechanism, JsonNode selected) {
        if (play == null) throw new IllegalArgumentException("play 不能为空");
        if (play.getFrozen() == null) play.setFrozen(newFreeze());
        play.getFrozen().set(mechanism, selected == null ? disabled() : selected);
    }

    public static ObjectNode disabled() {
        return JSON.createObjectNode().put("enabled", false);
    }

    public static boolean enabled(JsonNode block) {
        return block != null && block.path("enabled").asBoolean(false);
    }

    public static void requireExplicitChoice(Play play, String mechanism) {
        if (play == null || play.getPlayerChoice() == null || !play.getPlayerChoice().isObject()) {
            throw new IllegalArgumentException("必须由玩家显式选择机制,不能默认触发");
        }
        String chosen = play.getPlayerChoice().path("mechanism").asText("");
        if (!mechanism.equals(chosen)) {
            throw new IllegalArgumentException("玩家选择的机制不是 " + mechanism);
        }
        String action = play.getPlayerChoice().path("action").asText("").trim();
        if (action.isEmpty()) throw new IllegalArgumentException("玩家必须显式给出 action");
    }

    public static void requireEnabled(Play play, String mechanism) {
        if (play == null || play.getFrozen() == null || !enabled(play.getFrozen().path(mechanism))) {
            throw new IllegalArgumentException("机制未在发布冻结中启用:" + mechanism);
        }
    }

    public LedgerEntry existing(String requestId) {
        return requestId == null ? null : byRequestId.get(requestId);
    }

    /**
     * 绑定原 requestId。已存在则返回原条目,绝不 mint 新 id。
     */
    public LedgerEntry bind(String requestId, String mechanism, String choice) {
        String id = requireRequestId(requestId);
        LedgerEntry existing = byRequestId.get(id);
        if (existing != null) return existing;
        LedgerEntry entry = new LedgerEntry();
        entry.requestId = id;
        entry.mechanism = mechanism;
        entry.choice = choice;
        entry.status = PENDING;
        byRequestId.put(id, entry);
        return entry;
    }

    public LedgerEntry completeApplied(String requestId, Map<String, Object> result, TopicRouteAction action) {
        LedgerEntry entry = bind(requestId, mechanismOf(result), choiceOf(result));
        entry.status = APPLIED;
        entry.result = copy(result);
        entry.originalAction = action;
        return entry;
    }

    /** 效果结果未知:保持原 requestId,不造 retry。未见过的 id 不得占用账本。 */
    public LedgerEntry markUnknown(String requestId) {
        String id = requireRequestId(requestId);
        LedgerEntry entry = byRequestId.get(id);
        if (entry == null) {
            LedgerEntry unseen = new LedgerEntry();
            unseen.requestId = id;
            unseen.status = UNKNOWN;
            return unseen;
        }
        if (!APPLIED.equals(entry.status)) entry.status = UNKNOWN;
        return entry;
    }

    /**
     * UNKNOWN 恢复:只读原 requestId。已有 APPLIED 结果或原 {@link TopicRouteAction} 则回放;
     * 仍未知则保持 UNKNOWN。禁止 mint 新请求。未见过的 id 不得 {@code put} 进账本,
     * 否则后续同一 id 的唯一写入会变成对 mint 出的 UNKNOWN 的 recover。
     */
    public Map<String, Object> recoverUnknown(Play play) {
        String id = requireRequestId(play == null ? null : play.getOriginalRequestId());
        if (play.getOriginalAction() != null && play.getOriginalAction().getActionId() != null
                && !id.equals(play.getOriginalAction().getActionId())) {
            throw new IllegalArgumentException("UNKNOWN 恢复必须使用原 requestId,不得 mint 新请求");
        }
        LedgerEntry entry = byRequestId.get(id);
        if (entry == null) {
            if (play.getOriginalAction() != null) {
                LedgerEntry restoredEntry = new LedgerEntry();
                restoredEntry.requestId = id;
                Map<String, Object> restored = restoreFromOriginalAction(play, restoredEntry);
                if (restored != null) {
                    byRequestId.put(id, restoredEntry);
                    return restored;
                }
            }
            return unknownMap(id);
        }
        Map<String, Object> restored = restoreFromOriginalAction(play, entry);
        if (restored != null) return restored;
        if (APPLIED.equals(entry.status) && entry.result != null) return replay(entry);
        return unknownMap(id);
    }

    /**
     * 对齐 iOS {@code recoverOriginal}:只读原 requestId。未见过的 id 不 {@code put} 进账本。
     */
    public Map<String, Object> recoverOriginal(Play play) {
        return recoverUnknown(play);
    }

    public Map<String, Object> readback(String requestId) {
        LedgerEntry entry = existing(requireRequestId(requestId));
        if (entry == null) throw new IllegalStateException("没有原 requestId 的效果账:" + requestId);
        if (APPLIED.equals(entry.status) && entry.result != null) return replay(entry);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("requestId", entry.requestId);
        out.put("status", entry.status);
        out.put("replay", Boolean.TRUE);
        out.put("mintedNewRequest", Boolean.FALSE);
        if (entry.result != null) out.putAll(entry.result);
        return out;
    }

    /**
     * 一次效果:已 APPLIED 则回放且不再执行;UNKNOWN 走恢复;新 id 才真正跑效果。
     */
    public Map<String, Object> oneShot(Play play, String mechanism, String choice, Supplier<Map<String, Object>> effect) {
        String id = requireRequestId(play == null ? null : play.getOriginalRequestId());
        LedgerEntry existing = byRequestId.get(id);
        if (existing != null) {
            if (APPLIED.equals(existing.status) && existing.result != null) return replay(existing);
            if (UNKNOWN.equals(existing.status) || PENDING.equals(existing.status)) {
                return recoverUnknown(play);
            }
        }
        LedgerEntry entry = bind(id, mechanism, choice);
        entry.status = PENDING;
        try {
            Map<String, Object> result = effect.get();
            if (result == null) result = new LinkedHashMap<String, Object>();
            else result = copy(result);
            result.put("requestId", id);
            result.put("status", APPLIED);
            result.put("mechanism", mechanism);
            result.put("choice", choice);
            result.put("replay", Boolean.FALSE);
            result.put("mintedNewRequest", Boolean.FALSE);
            TopicRouteAction action = new TopicRouteAction();
            action.setActionId(id);
            action.setReceiptId(id);
            try {
                action.setResponseJson(JSON.writeValueAsString(result));
            } catch (Exception encode) {
                throw new IllegalStateException("无法序列化机制回执");
            }
            completeApplied(id, result, action);
            play.setOriginalAction(action);
            play.setLastResult(result);
            return result;
        } catch (RuntimeException failed) {
            markUnknown(id);
            throw failed;
        }
    }

    private Map<String, Object> restoreFromOriginalAction(Play play, LedgerEntry entry) {
        TopicRouteAction action = play.getOriginalAction() != null ? play.getOriginalAction() : entry.originalAction;
        if (action == null || action.getResponseJson() == null || action.getResponseJson().trim().isEmpty()) {
            return null;
        }
        if (action.getActionId() != null && !entry.requestId.equals(action.getActionId())) {
            throw new IllegalArgumentException("UNKNOWN 恢复必须使用原 requestId,不得 mint 新请求");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(action.getResponseJson(), Map.class);
            Map<String, Object> restored = copy(parsed);
            restored.put("requestId", entry.requestId);
            restored.put("status", APPLIED);
            restored.put("replay", Boolean.TRUE);
            restored.put("mintedNewRequest", Boolean.FALSE);
            entry.status = APPLIED;
            entry.result = copy(restored);
            entry.originalAction = action;
            return restored;
        } catch (Exception bad) {
            entry.status = UNKNOWN;
            return null;
        }
    }

    private static Map<String, Object> replay(LedgerEntry entry) {
        Map<String, Object> out = copy(entry.result);
        out.put("requestId", entry.requestId);
        out.put("status", APPLIED);
        out.put("replay", Boolean.TRUE);
        out.put("mintedNewRequest", Boolean.FALSE);
        return out;
    }

    static String requireRequestId(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("必须提供原 requestId");
        }
        return requestId.trim();
    }

    static JsonNode choice(Play play) {
        return play.getPlayerChoice();
    }

    static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String mechanismOf(Map<String, Object> result) {
        Object value = result == null ? null : result.get("mechanism");
        return value == null ? null : String.valueOf(value);
    }

    private static String choiceOf(Map<String, Object> result) {
        Object value = result == null ? null : result.get("choice");
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> unknownMap(String id) {
        Map<String, Object> unknown = new LinkedHashMap<String, Object>();
        unknown.put("requestId", id);
        unknown.put("status", UNKNOWN);
        unknown.put("replay", Boolean.FALSE);
        unknown.put("mintedNewRequest", Boolean.FALSE);
        return unknown;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }
}
