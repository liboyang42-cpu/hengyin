package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStoryState;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.GameplayRuntimeConfig;
import com.chengyinhub.business.service.support.GameplayCooperationEngine;
import com.chengyinhub.business.service.support.GameplaySurvivalEngine;
import com.chengyinhub.business.service.support.GameplayTaskEngine;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Request-local collaborator for the persisted shared RUN ledger, manual authority and route reservations.
 * Caller owns authorization, session-row transaction, command receipt and business dispatch. No callbacks
 * or transaction boundary here: every CAS/resource write joins that original transaction. The existing
 * mapper/clock/engines are supplied directly so production and existing fixtures use the same instances.
 */
final class GameSharedRunControl {
    private final GameSessionRuntimeMapper runtimeMapper;
    private final PlayTeamMapper playTeamMapper;
    private final ObjectMapper objectMapper;
    private final AdvancedGameClock clock;
    private final ThreadLocal<Date> manualEvaluationTime;
    private final GameplayCooperationEngine cooperationEngine;
    private final GameplaySurvivalEngine survivalEngine;
    private final GameplayTaskEngine taskEngine;

    GameSharedRunControl(GameSessionRuntimeMapper runtimeMapper, PlayTeamMapper playTeamMapper,
            ObjectMapper objectMapper, AdvancedGameClock clock, ThreadLocal<Date> manualEvaluationTime,
            GameplayCooperationEngine cooperationEngine, GameplaySurvivalEngine survivalEngine, GameplayTaskEngine taskEngine) {
        this.runtimeMapper = runtimeMapper; this.playTeamMapper = playTeamMapper;
        this.objectMapper = objectMapper; this.clock = clock; this.manualEvaluationTime = manualEvaluationTime;
        this.cooperationEngine = cooperationEngine; this.survivalEngine = survivalEngine; this.taskEngine = taskEngine;
    }
    static final Set<String> RUN_CONTROL_ACTIONS = new HashSet<>(Arrays.asList(
            "RUN_PAUSE", "RUN_RESUME", "RUN_INTERRUPT", "RUN_CANCEL", "LOCATION_REBIND", "TEAM_EXIT", "STAFF_TEAM_EXIT",
            "ACT_UNLOCK", "QUEST_GUIDE", "PLAN_FALLBACK", "ARRIVAL_CONFIRM", "ARRIVAL_ATTEST", "ARRIVAL_REVOKE", "MANUAL_BEGIN", "MANUAL_IMPORT",
            "MANUAL_REVIEW", "AUTHORITY_RESUME", "STAFF_MANUAL_SIGN"));

    static final Set<String> SHARED_PLAYER_ACTIONS = new HashSet<>(Arrays.asList("ZERO_HP_STEPS", "ZERO_HP_SYNC",
            "ITEM_USE", "ITEM_DISCARD", "ITEM_RECEIVE", "QUEST_START", "QUEST_CANCEL", "QUEST_RESOLVE",
            "ARRIVAL_ISSUE", "MANUAL_SIGN", "OPERATION_START", "OPERATION_CANCEL", "RECOVER"));

    void initializeRunControl(GameSession session) {
        GameStoryState owner = new GameStoryState();
        owner.setGameSessionId(session.getId()); owner.setOwnerType("RUN"); owner.setOwnerId(session.getId());
        owner.setVariablesJson(writeJson(initialRunControl())); owner.setAppliedChoicesJson("[]"); owner.setStatus("INITIAL"); owner.setRevision(0);
        owner.setCreateTime(clock.now()); owner.setUpdateTime(clock.now());
        if (runtimeMapper.insertStoryState(owner) != 1) throw error("GAME_STATE_CONFLICT", "运行控制账初始化失败");
    }

    void initializeWorldOwner(GameSession session) {
        TopicRouteEngine.State world = new TopicRouteEngine.State();
        GameStoryState owner = new GameStoryState();
        owner.setGameSessionId(session.getId()); owner.setOwnerType("WORLD"); owner.setOwnerId(session.getId());
        owner.setVariablesJson(writeJson(world)); owner.setAppliedChoicesJson("[]"); owner.setStatus("INITIAL"); owner.setRevision(0);
        owner.setCreateTime(clock.now()); owner.setUpdateTime(clock.now());
        if (runtimeMapper.insertStoryState(owner) != 1) throw error("GAME_STATE_CONFLICT", "公共世界账初始化失败");
    }

    ObjectNode runControl(GameSession session) {
        GameStoryState owner = runOwner(session);
        return owner == null ? initialRunControl() : readObject(owner.getVariablesJson());
    }

    /** Unlike the legacy command-side default, a query cannot invent a missing RUN ledger. */
    ObjectNode persistedRunControl(GameSession session) {
        GameStoryState owner = runOwner(session);
        return owner == null ? null : readObject(owner.getVariablesJson());
    }

    void saveRun(GameSession session, ObjectNode run) {
        GameStoryState owner = runOwner(session);
        if (owner == null) { initializeRunControl(session); owner = runOwner(session); }
        owner.setVariablesJson(writeJson(run)); owner.setUpdateTime(clock.now());
        if (runtimeMapper.updateStoryStateCas(owner) != 1) throw error("GAME_STATE_CONFLICT", "运行控制账已更新");
    }

    int runEpoch(GameSession session) { return runControl(session).path("authorityEpoch").asInt(1); }

    void requireSharedWrite(GameSession session, GameSessionCommand command) {
        ObjectNode run = runControl(session);
        if (command.getAuthorityEpoch() == null || command.getAuthorityEpoch() != run.path("authorityEpoch").asInt(1)) {
            throw error("GAME_AUTHORITY_CONFLICT", "本局权威版本已更新，请刷新");
        }
        if (Arrays.asList("INTERRUPTED", "CANCELLED").contains(run.path("operationStatus").asText())) throw error("GAME_RUN_TERMINAL", "本局已运营中止，只能读取");
        if (RUN_CONTROL_ACTIONS.contains(command.getAction()) || "MANUAL_SIGN".equals(command.getAction())) return;
        requireOnlineRun(run);
    }

    void requireOnlineRun(JsonNode run) {
        if (manualEvaluationTime.get() != null) return;
        if (!"RUNNING".equals(run.path("operationStatus").asText()) || !"ONLINE".equals(run.path("authorityMode").asText())) {
            throw error("GAME_RUN_PAUSED", "本局暂停或由人工账本接管，当前只能读取");
        }
    }

    Map<String, JsonNode> routeBalances(GameSession session) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) {
            if (!Arrays.asList("TEAM", "WORLD").contains(owner.getOwnerType())) continue;
            TopicRouteEngine.State state = routeOwnerState(owner);
            ObjectNode balance = objectMapper.createObjectNode();
            balance.set("variables", objectMapper.valueToTree(state.getVariables()));
            balance.set("inventory", state.getThemeRuntime().path("inventory").deepCopy());
            result.put(owner.getOwnerType() + ":" + owner.getOwnerId(), balance);
        }
        return result;
    }

    void appendRunEvent(GameSession session, GameActorContext actor, GameSessionCommand command, Long receiptId,
                                Map<String, JsonNode> before) {
        if (runOwner(session) == null) return;
        ObjectNode run = runControl(session);
        ArrayNode events = run.withArray("events");
        if (events.size() >= 5000) throw error("GAME_EVENT_LIMIT", "本局事件账已达到支持容量，请暂停并导出");
        int sequence = run.path("eventSeq").asInt() + 1; run.put("eventSeq", sequence);
        ObjectNode event = events.addObject().put("sequence", sequence).put("epoch", runEpoch(session));
        event.put("receiptId", receiptId); event.put("actorMemberId", actor.getMemberId()); event.put("action", command.getAction());
        event.put("requestHash", command.getRequestHash()); event.put("revision", session.getRevision()); event.put("at", clock.now().getTime());
        ObjectNode changes = event.putObject("changes");
        for (Map.Entry<String, JsonNode> balance : routeBalances(session).entrySet()) {
            JsonNode old = before.get(balance.getKey());
            if (!Objects.equals(old, balance.getValue())) {
                ObjectNode change = changes.putObject(balance.getKey());
                if (old != null) change.set("before", old); change.set("after", balance.getValue());
            }
        }
        saveRun(session, run);
    }

    Map<String, Object> publicRunControl(GameSession session) {
        ObjectNode run = runControl(session), visible = objectMapper.createObjectNode();
        for (String key : Arrays.asList("operationStatus", "authorityMode", "authorityEpoch", "eventSeq", "bindings", "exitedTeams", "openedActs", "operationReason")) {
            if (run.has(key)) visible.set(key, run.get(key).deepCopy());
        }
        return objectMapper.convertValue(visible, Map.class);
    }

    ObjectNode playerManualView(ObjectNode run, Long memberId) {
        JsonNode manual = run.path("manual");
        if (!manual.isObject()) return null;
        ObjectNode visible = objectMapper.createObjectNode();
        visible.put("nextSequence", manual.path("nextSequence").asInt(1));
        ArrayNode rows = visible.putArray("signatures");
        java.util.Iterator<Map.Entry<String, JsonNode>> signatures = manual.path("signatures").fields();
        while (signatures.hasNext()) {
            Map.Entry<String, JsonNode> entry = signatures.next();
            JsonNode signature = entry.getValue();
            if (!"PLAYER".equals(signature.path("perspective").asText()) || !Objects.equals(memberId, signature.path("actorMemberId").asLong())) continue;
            ObjectNode row = rows.addObject();
            row.put("signatureId", entry.getKey()).put("sequence", signature.path("sequence").asInt())
                .put("occurredAt", signature.path("occurredAt").asLong()).put("action", signature.path("command").path("action").asText())
                .put("status", signature.path("rejected").asBoolean() ? "REJECTED" : signature.path("used").asBoolean() ? "VERIFIED" : "PENDING");
            if (signature.path("rejected").asBoolean() && signature.has("reason")) row.set("reason", signature.get("reason").deepCopy());
        }
        return visible;
    }

    void saveRouteOwner(GameSession session, GameStoryState owner, TopicRouteEngine.State state) {
        if ("TEAM".equals(owner.getOwnerType())) survivalEngine.syncChallenge(sessionConfig(session), routeRules(session), state, survivalTime(session));
        saveOwnerWithoutSurvival(owner, state);
    }

    void saveOwnerWithoutSurvival(GameStoryState owner, TopicRouteEngine.State state) {
        owner.setVariablesJson(writeJson(state)); owner.setUpdateTime(clock.now());
        if (runtimeMapper.updateStoryStateCas(owner) != 1) throw error("GAME_STATE_CONFLICT", "归属账已更新");
    }

    long survivalTime(GameSession session) {
        JsonNode run = runControl(session);
        if ("PAUSED".equals(run.path("operationStatus").asText()) && "ONLINE".equals(run.path("authorityMode").asText()) && run.path("pausedAt").isIntegralNumber()) return run.path("pausedAt").asLong();
        return clock.now().getTime();
    }

    Long requireFrozenTeam(GameSession session, Object raw) {
        Long teamId = positiveLong(raw);
        if (teamId == null || runtimeMapper.selectStoryState(session.getId(), "TEAM", teamId) == null) throw error("GAME_FORBIDDEN_SCOPE", "队伍不属于本场冻结名单");
        if (runtimeMapper.isActiveTeam(session.getActivityId(), teamId) < 1) throw error("GAME_TEAM_REQUIRED", "队伍已不在本场有效范围");
        return teamId;
    }

    Map<String, Object> sharedExport(GameSession session) {
        ObjectNode run = runControl(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId()); data.put("activityId", session.getActivityId());
        data.put("revision", session.getRevision()); data.put("authorityEpoch", runEpoch(session)); data.put("eventSeq", run.path("eventSeq").asInt());
        data.put("configHash", digest(session.getModuleConfigSnapshotJson())); data.put("highWaterAt", clock.now().getTime());
        data.put("balances", routeBalances(session));
        data.put("highWaterHash", digest(writeJson(routeBalances(session)) + ":" + session.getRevision() + ":" + run.path("eventSeq").asInt()));
        data.put("events", run.path("events").deepCopy()); data.put("operationStatus", run.path("operationStatus").asText());
        // 导出不带一次性到达凭证：人工签署中的 nonce 只用于当时核验
        JsonNode manual = run.path("manual").deepCopy();
        for (JsonNode signature : manual.path("signatures")) if (signature.path("command").path("payload").isObject()) ((ObjectNode) signature.path("command").path("payload")).remove("nonce");
        data.put("authorityMode", run.path("authorityMode").asText()); data.put("manual", manual);
        return data;
    }

    ObjectNode latestOfflineAnchor(GameSession session) {
        JsonNode latest = null;
        String configHash = digest(session.getModuleConfigSnapshotJson());
        for (JsonNode anchor : runControl(session).path("exports")) {
            if (anchor.path("epoch").asInt() == runEpoch(session) && configHash.equals(anchor.path("configHash").asText())
                    && (latest == null || anchor.path("at").asLong() > latest.path("at").asLong())) latest = anchor;
        }
        if (latest == null) return null;
        ObjectNode projection = objectMapper.createObjectNode();
        for (String field : Arrays.asList("highWaterHash", "configHash", "epoch", "at", "revision", "eventSeq")) projection.set(field, latest.path(field).deepCopy());
        return projection;
    }

    CooperationScope cooperationScope(GameSession session, boolean forUpdate) {
        CooperationScope scope = new CooperationScope(); scope.session = session;
        ObjectNode run = runControl(session);
        for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) {
            if ("WORLD".equals(owner.getOwnerType()) && session.getId().equals(owner.getOwnerId())) {
                scope.worldOwner = owner; scope.world = routeOwnerState(owner);
            } else if ("TEAM".equals(owner.getOwnerType())) {
                Long id = owner.getOwnerId();
                if (run.path("exitedTeams").has(String.valueOf(id)) || runtimeMapper.isActiveTeam(session.getActivityId(), id) < 1) continue;
                com.chengyinhub.business.domain.PlayTeam team = forUpdate ? playTeamMapper.selectPlayTeamByIdForUpdate(id) : playTeamMapper.selectPlayTeamById(id);
                if (team == null || !Integer.valueOf(2).equals(team.getOwnerType()) || !session.getActivityId().equals(team.getOwnerId())
                        || team.getLeaderMemberId() == null || runtimeMapper.isActiveTeamMember(session.getActivityId(), id, team.getLeaderMemberId()) < 1
                        || playTeamMapper.hasPaidRegistration(team.getLeaderMemberId(), 2, session.getActivityId()) < 1) continue;
                scope.owners.put(id, owner); scope.teams.put(id, routeOwnerState(owner)); scope.leaders.put(id, team.getLeaderMemberId());
                scope.teamNames.put(id,team.getTitle());
            }
        }
        if (scope.world == null) throw error("GAME_CONFIG_INVALID", "本局公共世界账尚未初始化");
        for (Map.Entry<Long, TopicRouteEngine.State> team : scope.teams.entrySet()) {
            if (naturallyCompleted(session, team.getValue())) continue;
            ObjectNode arrivals = team.getValue().getThemeRuntime().putObject("hostArrivals");
            for (JsonNode quest : routeGameplay(session).path("quests")) {
                String location = quest.path("locationKey").asText();
                JsonNode proof = run.path("arrivals").path(arrivalKey(team.getKey(), location));
                if (proof.path("used").asBoolean() && proof.path("sessionId").asLong() == session.getId() && proof.path("teamId").asLong() == team.getKey()
                        && proof.path("nodeId").asLong() == bindingNode(session, quest) && proof.path("version").asLong() == bindingVersion(session, quest)
                        && proof.path("epoch").asInt() == runEpoch(session) && proof.path("expiresAt").asLong() > evaluationTime().getTime()) {
                    ObjectNode verifiedArrival = proof.deepCopy();
                    verifiedArrival.put("sourceNodeId", quest.path("nodeId").asLong());
                    arrivals.set(location, verifiedArrival);
                }
            }
        }
        return scope;
    }

    GameStoryState requireRouteOwner(GameSession session, Long teamId) {
        GameStoryState owner = runtimeMapper.selectStoryState(session.getId(), "TEAM", teamId);
        if (owner == null) throw error("GAME_TEAM_REQUIRED", "该队伍不在本局冻结名单中");
        return owner;
    }

    String requestHash(GameSessionCommand command) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("activityId", command.getActivityId());
        canonical.put("nodeId", command.getNodeId());
        canonical.put("expectedRevision", command.getExpectedRevision());
        canonical.put("action", command.getAction().trim().toUpperCase(Locale.ROOT));
        if (command.getAuthorityEpoch() != null) canonical.put("authorityEpoch", command.getAuthorityEpoch());
        canonical.put("payload", command.getPayload());
        try {
            String json = canonicalJson(objectMapper.valueToTree(canonical));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format("%02x", b));
            return value.toString();
        } catch (Exception ex) {
            throw error("GAME_BAD_REQUEST", "命令内容无法生成校验指纹");
        }
    }

    String jsonDigest(JsonNode node) {
        try { return digest(canonicalJson(node)); }
        catch (Exception invalid) { throw error("GAME_BAD_REQUEST", "人工事件无法生成稳定指纹"); }
    }

    String canonicalJson(JsonNode node) throws Exception {
        if (node == null || node.isNull()) return "null";
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalJson(node.get(i)));
            }
            return out.append(']').toString();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(objectMapper.writeValueAsString(entry.getKey())).append(':')
                        .append(canonicalJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        return objectMapper.writeValueAsString(node);
    }

    String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    void runPayload(GameSessionCommand command, String... allowed) {
        if (!new HashSet<>(Arrays.asList(allowed)).containsAll(command.getPayload().keySet())) throw error("GAME_BAD_REQUEST", "运行操作包含不支持的参数");
    }

    String runReason(GameSessionCommand command) {
        String reason = text(command.getPayload().get("reason"), 200);
        if (StringUtils.isBlank(reason)) throw error("GAME_BAD_REQUEST", "请填写实际操作原因");
        return reason;
    }

    private ObjectNode initialRunControl() {
        ObjectNode run = objectMapper.createObjectNode();
        run.put("operationStatus", "RUNNING"); run.put("authorityMode", "ONLINE"); run.put("authorityEpoch", 1); run.put("eventSeq", 0);
        return run;
    }

    private GameStoryState runOwner(GameSession session) {
        return runtimeMapper.selectStoryState(session.getId(), "RUN", session.getId());
    }

    private void resumeFrozenTimers(GameSession session, ObjectNode run) {
        if (!run.path("pausedAt").isIntegralNumber()) return;
        long paused = Math.max(0L, clock.now().getTime() - run.path("pausedAt").asLong());
        run.remove("pausedAt");
        if (paused == 0L) return;
        for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) if ("TEAM".equals(owner.getOwnerType())) {
            TopicRouteEngine.State state = routeOwnerState(owner); ObjectNode runtime = state.getThemeRuntime(); boolean shifted = false;
            JsonNode zero = runtime.path("zeroHp");
            if (zero.isObject() && !zero.path("resolved").asBoolean() && zero.path("deadlineAt").isIntegralNumber()) { ((ObjectNode) zero).put("deadlineAt", Math.addExact(zero.path("deadlineAt").asLong(), paused)); shifted = true; }
            JsonNode challenge = runtime.path("challenge");
            if (challenge.isObject() && !challenge.path("resolved").asBoolean() && challenge.path("deadlineAt").isIntegralNumber()) { ((ObjectNode) challenge).put("deadlineAt", Math.addExact(challenge.path("deadlineAt").asLong(), paused)); shifted = true; }
            if (shifted) saveOwnerWithoutSurvival(owner, state);
        }
    }

    private void releaseTeamReservations(GameSession session, Long teamId) {
        GameStoryState owner = requireRouteOwner(session, teamId);
        TopicRouteEngine.State state = routeOwnerState(owner); state.getThemeRuntime().with("gameplay").remove("activeQuest"); state.getThemeRuntime().with("gameplay").remove("pendingTaskEffects"); state.getThemeRuntime().remove("activeOperation"); state.getThemeRuntime().remove("operationFacts");
        saveOwnerWithoutSurvival(owner, state);
    }

    private void releaseUnexecutedRouteReservations(GameSession session, ObjectNode run) {
        for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) if ("TEAM".equals(owner.getOwnerType())) releaseTeamReservations(session, owner.getOwnerId());
        releaseCooperationReservations(session, null);
        run.remove("nonces"); run.remove("arrivals");
    }

    private void releaseCooperationReservations(GameSession session, Long exitingTeam) {
        if (!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) return;
        CooperationScope scope = cooperationScope(session, true);
        if (exitingTeam != null) cooperationEngine.exit(scope.world, exitingTeam);
        else for (Long teamId : scope.teams.keySet()) cooperationEngine.exit(scope.world, teamId);
        persistCooperation(scope, false);
    }

    private void requireManualResolved(JsonNode run) {
        if (run.path("manual").path("needsReview").asBoolean()) throw error("GAME_MANUAL_UNRESOLVED", "请先解决人工账高水位冲突");
        for (JsonNode unknown : run.path("manual").path("unknownRequests")) if (Arrays.asList("UNKNOWN", "PENDING", "CONFLICT").contains(unknown.path("outcome").asText())) throw error("GAME_MANUAL_UNRESOLVED", "请先核对未知在线回执指纹");
    }

    void persistCooperation(CooperationScope scope) { persistCooperation(scope, true); }

    void persistCooperation(CooperationScope scope, boolean survival) {
        for (Map.Entry<Long, TopicRouteEngine.State> team : scope.teams.entrySet()) {
            GameStoryState owner = scope.owners.get(team.getKey());
            if (writeJson(team.getValue()).equals(owner.getVariablesJson())) continue;
            if (survival) saveRouteOwner(scope.session, owner, team.getValue()); else saveOwnerWithoutSurvival(owner, team.getValue());
        }
        if (scope.world.getThemeRuntime().path("cooperation").has("result")) {
            JsonNode ending=scope.world.getThemeRuntime().path("cooperation").path("result");
            scope.worldOwner.setEndingCode(ending.path("storyEndingId").asText(ending.path("endingKey").asText()));
            scope.worldOwner.setStatus("ENDING_RESOLVED");
        }
        if (!writeJson(scope.world).equals(scope.worldOwner.getVariablesJson())) saveRouteOwner(scope.session, scope.worldOwner, scope.world);
    }

    private TopicRouteEngine.State routeOwnerState(GameStoryState owner) {
        try { return objectMapper.readValue(owner.getVariablesJson(), TopicRouteEngine.State.class); }
        catch (Exception invalid) { throw error("GAME_CONFIG_INVALID", "本队运行状态不可用"); }
    }

    private JourneyRules routeRules(GameSession session) {
        return JourneyRules.parse(sessionConfig(session).path("journeyRules").toString());
    }

    private JsonNode routeGameplay(GameSession session) {
        return sessionConfig(session).path("themeConfig").path("gameplayRuntime");
    }

    private JsonNode sessionConfig(GameSession session) {
        try { return objectMapper.readTree(session.getModuleConfigSnapshotJson()); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "本场游戏配置快照不可用"); }
    }

    private boolean naturallyCompleted(GameSession session, TopicRouteEngine.State state) {
        return !GameplayCooperationEngine.isEffectiveTarget(sessionConfig(session).path("themeConfig"), state);
    }

    private String arrivalKey(Long teamId, String location) { return teamId + ":" + location; }

    private JsonNode binding(GameSession session, JsonNode quest) { return runControl(session).path("bindings").path(quest.path("locationKey").asText()); }

    private long bindingVersion(GameSession session, JsonNode quest) { return binding(session, quest).path("version").asLong(1); }

    private long bindingNode(GameSession session, JsonNode quest) { return binding(session, quest).path("nodeId").asLong(quest.path("nodeId").asLong()); }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "游戏状态无法序列化"); }
    }

    private ObjectNode readObject(String json) {
        try {
            JsonNode value = StringUtils.isBlank(json) ? objectMapper.createObjectNode() : objectMapper.readTree(json);
            return value instanceof ObjectNode ? (ObjectNode) value : objectMapper.createObjectNode();
        } catch (Exception ex) {
            throw error("GAME_CONFIG_INVALID", "剧情变量不可用");
        }
    }

    private String text(Object value, int max) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.length() > max) throw error("GAME_BAD_REQUEST", "文本内容过长");
        return text;
    }

    private Long positiveLong(Object value) {
        Long parsed = Convert.toLong(value, 0L);
        return parsed > 0 ? parsed : null;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private GameSessionException error(String code, String message) {
        return new GameSessionException(code, message);
    }

    private void requireSessionStatus(GameSession session, String... allowed) {
        if (session == null) throw error("GAME_SESSION_NOT_PREPARED", "本场游戏尚未准备");
        for (String status : allowed) if (status.equals(session.getStatus())) return;
        throw error("GAME_STATE_CONFLICT", "当前游戏状态不能执行该操作");
    }

    private void requireActivityServiceWindow(CmsActivity activity, Date now) {
        if (!isWithinActivityServiceWindow(activity, now)) {
            throw error("GAME_ACTIVITY_OUTSIDE_SERVICE_WINDOW", "当前不在活动服务时段");
        }
    }

    private boolean isWithinActivityServiceWindow(CmsActivity activity, Date now) {
        return activity != null && activity.getStartDate() != null && activity.getEndDate() != null
                && now != null && !now.before(activity.getStartDate()) && now.before(activity.getEndDate());
    }

    private Date evaluationTime() { Date imported = manualEvaluationTime.get(); return imported == null ? clock.now() : imported; }

    Long requirePlayerTeam(GameActorContext actor, CmsActivity activity) {
        if (playTeamMapper.hasPaidRegistration(actor.getMemberId(), 2, activity.getId()) < 1) {
            throw error("GAME_FORBIDDEN_SCOPE", "仅本场有效报名玩家可参与");
        }
        Long teamId = runtimeMapper.selectActiveTeamId(activity.getId(), actor.getMemberId());
        if (teamId == null || teamId <= 0) throw error("GAME_TEAM_REQUIRED", "请先加入本场队伍");
        return teamId;
    }

    static final class CooperationScope {
        GameSession session;
        GameStoryState worldOwner;
        TopicRouteEngine.State world;
        final Map<Long, GameStoryState> owners = new LinkedHashMap<>();
        final Map<Long, TopicRouteEngine.State> teams = new LinkedHashMap<>();
        final Map<Long, Long> leaders = new LinkedHashMap<>();
        final Map<Long, String> teamNames = new LinkedHashMap<>();
    }

    void applyLifecycle(GameActorContext actor, CmsActivity activity, GameSession session,
                        GameSessionCommand command, ObjectNode run, Long receiptId) {
        String action = command.getAction();
        switch (action) {
            case "RUN_PAUSE":
            case "RUN_INTERRUPT":
            case "RUN_CANCEL":
                runPayload(command, "reason");
                if (Arrays.asList("CANCELLED", "INTERRUPTED").contains(run.path("operationStatus").asText())) throw error("GAME_RUN_TERMINAL", "本局已运营中止");
                run.put("operationReason", runReason(command));
                if ("RUN_PAUSE".equals(action) && !"PAUSED".equals(run.path("operationStatus").asText())) run.put("pausedAt", clock.now().getTime());
                run.put("operationStatus", "RUN_PAUSE".equals(action) ? "PAUSED" : "RUN_CANCEL".equals(action) ? "CANCELLED" : "INTERRUPTED");
                if (!"RUN_PAUSE".equals(action)) releaseUnexecutedRouteReservations(session, run);
                break;
            case "RUN_RESUME":
                runPayload(command, "reason");
                if (!"PAUSED".equals(run.path("operationStatus").asText()) || !"ONLINE".equals(run.path("authorityMode").asText())) throw error("GAME_STATE_CONFLICT", "人工模式须先完成校账");
                requireActivityServiceWindow(activity, clock.now());
                resumeFrozenTimers(session, run);
                run.put("operationReason", runReason(command)); run.put("operationStatus", "RUNNING");
                break;
            case "TEAM_EXIT":
            case "STAFF_TEAM_EXIT":
                runPayload(command, "teamId", "reason"); runReason(command);
                Long exiting = requireFrozenTeam(session, command.getPayload().get("teamId"));
                if (run.path("exitedTeams").has(String.valueOf(exiting))) throw error("GAME_STATE_CONFLICT", "该队已退出本局");
                run.with("exitedTeams").put(String.valueOf(exiting), receiptId);
                releaseTeamReservations(session, exiting);
                releaseCooperationReservations(session, exiting);
                break;
            case "MANUAL_REVIEW":
                runPayload(command, "signatureId", "memberId", "requestId", "acceptCurrentHighWater", "acceptLateConflict", "reason"); runReason(command);
                if (!"MANUAL".equals(run.path("authorityMode").asText())) throw error("GAME_STATE_CONFLICT", "本局没有人工账待复核");
                if (Boolean.TRUE.equals(command.getPayload().get("acceptCurrentHighWater"))) {
                    if (command.getPayload().containsKey("signatureId") || command.getPayload().containsKey("requestId")) throw error("GAME_BAD_REQUEST", "复核一次只能作一个明确决定");
                    run.with("manual").put("needsReview", false).put("reviewedBy", actor.getMemberId()).put("reviewReason", runReason(command));
                    break;
                }
                if (command.getPayload().containsKey("requestId")) {
                    boolean reviewed = false;
                    for (JsonNode pending : run.path("manual").path("unknownRequests")) {
                        if (!pending.path("requestId").asText().equals(command.getPayload().get("requestId")) || pending.path("memberId").asLong() != objectMapper.valueToTree(command.getPayload().get("memberId")).asLong()) continue;
                        GameCommandReceipt known = runtimeMapper.selectReceiptForUpdate(activity.getId(), pending.path("memberId").asLong(), pending.path("requestId").asText());
                        if (known != null && "PENDING".equals(known.getOutcome())) throw error("GAME_MANUAL_UNRESOLVED", "在线命令尚未确认");
                        boolean hashConflict = known != null && !known.getRequestHash().equals(pending.path("requestHash").asText());
                        if (hashConflict && !Boolean.TRUE.equals(command.getPayload().get("acceptLateConflict"))) throw error("GAME_MANUAL_UNRESOLVED", "迟到回执与原指纹冲突，须明确复核且不得重放");
                        ((ObjectNode)pending).put("outcome", hashConflict ? "REVIEWED_CONFLICT_NO_REPLAY" : known == null ? "NOT_APPLIED_OLD_EPOCH_FROZEN" : known.getOutcome()).put("reviewedBy", actor.getMemberId()).put("reason", runReason(command)); reviewed = true;
                    }
                    if (!reviewed) throw error("GAME_BAD_REQUEST", "未知请求不在本局冻结清单中");
                    break;
                }
                String signatureId = text(command.getPayload().get("signatureId"), 128);
                JsonNode rejected = run.path("manual").path("signatures").path(signatureId);
                if (!rejected.isObject() || rejected.path("used").asBoolean() || rejected.path("sequence").asInt() != run.path("manual").path("nextSequence").asInt(1)) throw error("GAME_MANUAL_SEQUENCE", "只能复核当前未执行的人工事件");
                ((ObjectNode)rejected).put("used", true).put("rejected", true).put("reviewedBy", actor.getMemberId()).put("reason", runReason(command));
                run.with("manual").put("nextSequence", rejected.path("sequence").asInt() + 1);
                break;
            case "AUTHORITY_RESUME":
                runPayload(command, "reason"); runReason(command);
                if (!"MANUAL".equals(run.path("authorityMode").asText())) throw error("GAME_STATE_CONFLICT", "本局未由人工账接管");
                if (run.path("manual").path("needsReview").asBoolean()) throw error("GAME_MANUAL_UNRESOLVED", "高水位冲突尚未复核");
                for (JsonNode unknown : run.path("manual").path("unknownRequests")) if (Arrays.asList("UNKNOWN", "PENDING", "CONFLICT").contains(unknown.path("outcome").asText())) throw error("GAME_MANUAL_UNRESOLVED", "未知在线回执尚未复核");
                for (JsonNode signature : run.path("manual").path("signatures")) if (!signature.path("used").asBoolean()) throw error("GAME_MANUAL_UNRESOLVED", "人工事件尚未逐条执行或复核");
                run.with("manual").put("resolvedBy", actor.getMemberId()).put("closedAt", clock.now().getTime());
                run.put("authorityEpoch", run.path("authorityEpoch").asInt(1) + 1).put("authorityMode", "ONLINE").put("operationStatus", "PAUSED").put("pausedAt", clock.now().getTime());
                run.remove("nonces"); run.remove("arrivals");
                for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) if ("TEAM".equals(owner.getOwnerType())) {
                    TopicRouteEngine.State state = routeOwnerState(owner);
                    state.getThemeRuntime().remove(Arrays.asList("activeOperation", "verifiedNodes", "operationFacts")); state.getThemeRuntime().with("gameplay").remove("activeQuest"); state.getThemeRuntime().with("gameplay").remove("pendingTaskEffects");
                    saveRouteOwner(session, owner, state);
                }
                break;
            case "ARRIVAL_REVOKE":
                runPayload(command, "teamId", "questKey", "operationKind", "operationKey", "reason"); runReason(command);
                JsonNode revokeQuest = arrivalTarget(session, command);
                Long revokeTeam = requireFrozenTeam(session, command.getPayload().get("teamId"));
                String revokeKey = arrivalKey(revokeTeam, revokeQuest.path("locationKey").asText());
                if (!run.path("arrivals").path(revokeKey).isObject()) throw error("GAME_STATE_CONFLICT", "该队在此地点没有可撤销的到达记录");
                if (run.path("arrivalRevocations").size() >= 500) throw error("GAME_EVENT_LIMIT", "本局撤销记录达到支持容量");
                run.with("arrivals").remove(revokeKey);
                run.with("arrivalRevocations").putObject(String.valueOf(receiptId)).put("teamId", revokeTeam).put("locationKey", revokeQuest.path("locationKey").asText())
                        .put("revokedBy", actor.getMemberId()).put("reason", runReason(command)).put("at", clock.now().getTime());
                break;
            case "ACT_UNLOCK":
                runPayload(command, "actKey", "reason"); requireOnlineRun(run); runReason(command);
                String actKey = text(command.getPayload().get("actKey"), 64); boolean found = false;
                for (JsonNode act : routeGameplay(session).path("acts")) if (act.path("actKey").asText().equals(actKey)) found = true;
                if (!found) throw error("GAME_BAD_REQUEST", "幕不属于本局发布规则");
                run.with("openedActs").put(actKey, receiptId);
                for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) {
                    if (!"TEAM".equals(owner.getOwnerType())) continue;
                    TopicRouteEngine.State state = routeOwnerState(owner);
                    JsonNode act = routeGameplay(session).path("acts").path(state.getThemeRuntime().path("gameplay").path("actIndex").asInt());
                    if (act.path("actKey").asText().equals(actKey)) { state.getThemeRuntime().with("gameplay").put("awaitingUnlock", false); refreshRouteQuestNodes(session, state); saveRouteOwner(session, owner, state); }
                }
                break;
            case "LOCATION_REBIND":
                rebindLocation(session, command, run);
                break;
            default: throw error("GAME_ACTION_UNSUPPORTED", "运行操作不受支持");
        }
    }

    Map<String, Object> beginManual(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run) {
        runPayload(command, "highWaterHash", "configHash", "unknownRequests", "offlineStartedAt", "reason"); runReason(command);
        if (!"PAUSED".equals(run.path("operationStatus").asText()) || !"ONLINE".equals(run.path("authorityMode").asText())) throw error("GAME_RUN_PAUSED", "先暂停在线写入并读取高水位");
        if (!digest(session.getModuleConfigSnapshotJson()).equals(command.getPayload().get("configHash"))) throw error("GAME_CONFIG_INVALID", "人工账规则版本不匹配");
        Map<String, Object> snapshot = sharedExport(session);
        long startedAt = clock.now().getTime(); JsonNode anchor = null;
        if (command.getPayload().containsKey("offlineStartedAt")) {
            JsonNode offline = objectMapper.valueToTree(command.getPayload().get("offlineStartedAt"));
            anchor = run.path("exports").path(String.valueOf(command.getPayload().get("highWaterHash")));
            if (!offline.isIntegralNumber() || !anchor.isObject() || anchor.path("epoch").asInt() != runEpoch(session)
                    || !digest(session.getModuleConfigSnapshotJson()).equals(anchor.path("configHash").asText())
                    || offline.asLong() < anchor.path("at").asLong() || offline.asLong() > startedAt || !isWithinActivityServiceWindow(activity, new Date(offline.asLong()))) {
                throw error("GAME_MANUAL_ANCHOR_INVALID", "离线开始时间必须在本场已确认服务器锚点之后且属于当前权威版本");
            }
            startedAt = offline.asLong();
        }
        ObjectNode manual = run.putObject("manual");
        manual.put("startedAt", startedAt).put("serverStartedAt", clock.now().getTime()).put("startedBy", actor.getMemberId()).put("nextSequence", 1);
        if (anchor != null) manual.set("offlineAnchor", anchor.deepCopy());
        manual.put("baseRevision", session.getRevision()).put("baseEventSeq", run.path("eventSeq").asInt());
        manual.put("baseHash", String.valueOf(snapshot.get("highWaterHash")));
        manual.put("needsReview", !Objects.equals(snapshot.get("highWaterHash"), command.getPayload().get("highWaterHash")));
        JsonNode unknown = objectMapper.valueToTree(command.getPayload().get("unknownRequests"));
        if (!unknown.isNull() && (!unknown.isArray() || unknown.size() > 100)) throw error("GAME_BAD_REQUEST", "未知回执清单无效");
        ArrayNode pending = manual.putArray("unknownRequests");
        for (JsonNode input : unknown) {
            if (!input.isObject() || input.size() != 3 || !input.has("memberId") || !input.has("requestId") || !input.path("requestHash").asText().matches("[a-f0-9]{64}")) throw error("GAME_BAD_REQUEST", "未知回执须带实际演员、原请求号和原请求指纹");
            long member = input.path("memberId").asLong(); String requestId = input.path("requestId").asText();
            if (member <= 0 || requestId.isEmpty() || requestId.length() > 64) throw error("GAME_BAD_REQUEST", "未知回执定位无效");
            Long teamId = requirePlayerTeam(GameActorContext.player(member), activity); requireRouteOwner(session, teamId);
            GameCommandReceipt receipt = runtimeMapper.selectReceiptForUpdate(activity.getId(), member, requestId);
            ObjectNode item = pending.addObject().put("memberId", member).put("requestId", requestId).put("requestHash", input.path("requestHash").asText());
            boolean conflict = receipt != null && !receipt.getRequestHash().equals(input.path("requestHash").asText());
            item.put("outcome", conflict ? "CONFLICT" : receipt == null ? "UNKNOWN" : receipt.getOutcome());
            if (conflict) manual.put("needsReview", true);
        }
        resumeFrozenTimers(session, run);
        run.put("authorityMode", "MANUAL");
        saveRun(session, run);
        return singleton("manual", manual.deepCopy());
    }

    ManualCommand prepareManualSign(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run, Long receiptId) {
        runPayload(command, "sequence", "occurredAt", "command", "originalRequestId", "originalExpectedRevision");
        if (!"MANUAL".equals(run.path("authorityMode").asText())) throw error("GAME_MANUAL_UNRESOLVED", "本局尚未进入人工权威");
        requireManualResolved(run);
        JsonNode raw = objectMapper.valueToTree(command.getPayload().get("command"));
        if (!raw.isObject() || !new HashSet<>(Arrays.asList("action", "nodeId", "payload")).containsAll(objectMapper.convertValue(raw, Map.class).keySet())
                || !raw.path("action").isTextual() || !raw.path("payload").isObject()) throw error("GAME_BAD_REQUEST", "人工事件只接受受控业务命令，不接余额");
        String action = raw.path("action").asText();
        if (!(SHARED_PLAYER_ACTIONS.contains(action) && !Arrays.asList("MANUAL_SIGN", "ARRIVAL_ISSUE", "ZERO_HP_STEPS", "ZERO_HP_SYNC").contains(action)) && !cooperationAction(action)
                && !Arrays.asList("ARRIVAL_CONFIRM", "ARRIVAL_ATTEST", "QUEST_GUIDE").contains(action)) throw error("GAME_ACTION_UNSUPPORTED", "人工账不能重放运营切换或任意状态写入");
        GameSessionCommand source = new GameSessionCommand(); source.setActivityId(activity.getId()); source.setNodeId(raw.path("nodeId").isNumber() ? raw.path("nodeId").asLong() : null);
        source.setAction(action); source.setPayload(objectMapper.convertValue(raw.path("payload"), Map.class));
        GameActorContext signer = Arrays.asList("ARRIVAL_CONFIRM", "ARRIVAL_ATTEST", "QUEST_GUIDE").contains(action) ? GameActorContext.club(actor.getMemberId()) : GameActorContext.player(actor.getMemberId());
        if (!signer.getPerspective().equals(actor.getPerspective())) throw error("GAME_FORBIDDEN_SCOPE", "请由实际代表或核验工作人员本人签署");
        return new ManualCommand(raw, signer, source);
    }

    Map<String, Object> completeManualSign(GameActorContext actor, CmsActivity activity, GameSession session,
            GameSessionCommand command, ObjectNode run, Long receiptId, ManualCommand signedCommand) {
        if ("ARRIVAL_ATTEST".equals(signedCommand.command().getAction())) runReason(signedCommand.command());
        JsonNode sequenceNode = objectMapper.valueToTree(command.getPayload().get("sequence"));
        if (!sequenceNode.isIntegralNumber() || !sequenceNode.canConvertToInt()) throw error("GAME_MANUAL_SEQUENCE", "人工序号须为整数");
        int sequence = sequenceNode.asInt();
        if (sequence != run.path("manual").path("nextSequence").asInt(1)) throw error("GAME_MANUAL_SEQUENCE", "只能签署当前连续事件");
        for (JsonNode pending : run.path("manual").path("signatures")) if (!pending.path("used").asBoolean() && pending.path("sequence").asInt() == sequence) throw error("GAME_MANUAL_SEQUENCE", "当前序号已有签署，请先执行或复核");
        JsonNode occurred = objectMapper.valueToTree(command.getPayload().get("occurredAt"));
        if (!occurred.isIntegralNumber()) throw error("GAME_BAD_REQUEST", "请填写实际事件时间");
        long at = occurred.asLong();
        if (at < run.path("manual").path("lastOccurredAt").asLong(run.path("manual").path("startedAt").asLong()) || at > clock.now().getTime() || !isWithinActivityServiceWindow(activity, new Date(at))) throw error("GAME_BAD_REQUEST", "事件不在人工账及活动有效时段");
        String id = String.valueOf(receiptId);
        if (run.path("manual").path("signatures").size() >= 500) throw error("GAME_EVENT_LIMIT", "人工事件达到支持容量");
        ObjectNode signed = run.with("manual").with("signatures").putObject(id);
        signed.put("sequence", sequence).put("actorMemberId", actor.getMemberId()).put("perspective", signedCommand.signer().getPerspective()).put("occurredAt", at);
        signed.put("configHash", digest(session.getModuleConfigSnapshotJson())).put("epoch", runEpoch(session)).put("used", false);
        signed.set("command", signedCommand.raw().deepCopy()); signed.put("commandHash", jsonDigest(signedCommand.raw()));
        String original = text(command.getPayload().get("originalRequestId"), 64); if (original != null) {
            JsonNode version = objectMapper.valueToTree(command.getPayload().get("originalExpectedRevision"));
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 0) throw error("GAME_BAD_REQUEST", "原在线命令须携带其准确版本号");
            signed.put("originalRequestId", original).put("originalExpectedRevision", version.asInt());
        }
        saveRun(session, run);
        return singleton("signatureId", id);
    }

    ManualReplay prepareManualReplay(GameActorContext actor, CmsActivity activity, GameSession session, GameSessionCommand command, ObjectNode run, Long receiptId) {
        runPayload(command, "signatureId");
        if (!"MANUAL".equals(run.path("authorityMode").asText())) throw error("GAME_MANUAL_UNRESOLVED", "本局未达到可重放的人工权威状态");
        requireManualResolved(run);
        String key = text(command.getPayload().get("signatureId"), 128); JsonNode signature = run.path("manual").path("signatures").path(key == null ? "" : key);
        if (!signature.isObject()) throw error("GAME_FORBIDDEN_SCOPE", "人工事件没有实际演员签署");
        if (signature.path("used").asBoolean()) return new ManualReplay(key, signature, null, null);
        if (signature.path("epoch").asInt() != runEpoch(session) || !digest(session.getModuleConfigSnapshotJson()).equals(signature.path("configHash").asText())
                || !jsonDigest(signature.path("command")).equals(signature.path("commandHash").asText())
                || signature.path("sequence").asInt() != run.path("manual").path("nextSequence").asInt(1)) throw error("GAME_MANUAL_SEQUENCE", "人工事件漏序、内容或版本不匹配");
        JsonNode raw = signature.path("command");
        GameActorContext signer = new GameActorContext(signature.path("actorMemberId").asLong(), signature.path("perspective").asText());
        GameSessionCommand source = new GameSessionCommand(); source.setActivityId(activity.getId()); source.setNodeId(raw.path("nodeId").isNumber() ? raw.path("nodeId").asLong() : null);
        source.setAction(raw.path("action").asText()); source.setPayload(objectMapper.convertValue(raw.path("payload"), Map.class)); source.setAuthorityEpoch(runEpoch(session));
        source.setExpectedRevision(session.getRevision()); source.setRequestId("offline:" + key); source.setRequestHash(requestHash(source));
        return new ManualReplay(key, signature, signer, source);
    }

    Map<String, Object> completeManualImport(GameSession session, GameActorContext actor, Long receiptId,
            ManualReplay replay, Map<String, Object> applied) {
        ObjectNode run = runControl(session);
        ObjectNode completed = (ObjectNode)run.path("manual").path("signatures").path(replay.key());
        completed.put("used", true).put("importedBy", actor.getMemberId()).put("importReceiptId", receiptId); completed.set("result", objectMapper.valueToTree(applied));
        run.with("manual").put("nextSequence", replay.signature().path("sequence").asInt() + 1).put("lastOccurredAt", replay.signature().path("occurredAt").asLong());
        saveRun(session, run);
        return singleton("manualResult", applied);
    }

    Map<String, Object> anchorSharedRun(GameActorContext actor, CmsActivity activity, GameSession session) {
        requireSessionStatus(session, GameSession.RUNNING); requireActivityServiceWindow(activity, clock.now());
        ObjectNode run = runControl(session);
        if (!"ONLINE".equals(run.path("authorityMode").asText()) || Arrays.asList("INTERRUPTED", "CANCELLED").contains(run.path("operationStatus").asText())) throw error("GAME_MANUAL_UNRESOLVED", "当前权威不允许建立新离线锚点");
        Map<String, Object> exported = sharedExport(session); String hash = String.valueOf(exported.get("highWaterHash"));
        ObjectNode anchors = run.with("exports");
        if (!anchors.has(hash)) {
            if (anchors.size() >= 128) throw error("GAME_EVENT_LIMIT", "本局锚点达到支持容量，请复用最后确认锚点");
            ObjectNode saved = anchors.putObject(hash).put("highWaterHash", hash).put("at", clock.now().getTime()).put("revision", session.getRevision())
                    .put("eventSeq", run.path("eventSeq").asInt()).put("configHash", digest(session.getModuleConfigSnapshotJson())).put("epoch", runEpoch(session)).put("createdBy", actor.getMemberId());
            saveRun(session, run);
        }
        exported.put("anchor", anchors.path(hash).deepCopy()); return exported;
    }

    private Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    record ManualCommand(JsonNode raw, GameActorContext signer, GameSessionCommand command) { }
    record ManualReplay(String key, JsonNode signature, GameActorContext signer, GameSessionCommand command) {
        boolean alreadyApplied() { return signature.path("used").asBoolean(); }
        Map<String, Object> resultData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("manualResult", signature.path("result").deepCopy());
            return data;
        }
    }

    private boolean cooperationAction(String action) { return cooperationEngine.supports(action); }

    JsonNode operationTarget(GameSession session, String kind, Object key) {
        if (!Arrays.asList("FACILITY", "PLAN").contains(kind)) throw error("GAME_BAD_REQUEST", "操作种类无效");
        JsonNode configured = configuredCooperation(session, "FACILITY".equals(kind) ? "facilities" : "plans", "FACILITY".equals(kind) ? "facilityKey" : "endingKey", key);
        if (!configured.has("operationNodeId")) throw error("GAME_ACTION_UNSUPPORTED", "此能力没有操作题组");
        ObjectNode target = objectMapper.createObjectNode(); target.put("nodeId", configured.path("operationNodeId").asLong()).put("sourceNodeId", configured.path("operationNodeId").asLong());
        String location = configured.path("arrivalLocationKey").asText(null);
        if (location == null) for (JsonNode quest : routeGameplay(session).path("quests")) if (quest.path("nodeId").asLong() == target.path("nodeId").asLong()) { location = quest.path("locationKey").asText(); break; }
        target.put("locationKey", location == null ? "node_" + target.path("nodeId").asLong() : location);
        target.put("bindingVersion", bindingVersion(session, target)).put("nodeId", bindingNode(session, target));
        target.put("operationKind", kind); target.put("operationKey", String.valueOf(key));
        return target;
    }

    JsonNode arrivalTarget(GameSession session, GameSessionCommand command) {
        if (command.getPayload().containsKey("questKey")) {
            if (command.getPayload().containsKey("operationKind") || command.getPayload().containsKey("operationKey")) throw error("GAME_BAD_REQUEST", "到达凭证只能选择一种目标");
            return configuredQuest(session, text(command.getPayload().get("questKey"), 64));
        }
        return operationTarget(session, text(command.getPayload().get("operationKind"), 32), command.getPayload().get("operationKey"));
    }

    JsonNode configuredQuest(GameSession session, String key) {
        for (JsonNode quest : routeGameplay(session).path("quests")) if (Objects.equals(key, quest.path("questKey").asText())) return quest;
        throw error("GAME_BAD_REQUEST", "本局没有这个任务");
    }

    JsonNode configuredCooperation(GameSession session, String field, String idField, Object id) {
        for (JsonNode config : routeGameplay(session).path(field)) if (config.path(idField).asText().equals(id)) return config;
        throw error("GAME_BAD_REQUEST", "协作能力不属于本场冻结配置");
    }

    void refreshRouteQuestNodes(GameSession session, TopicRouteEngine.State state) {
        taskEngine.initialize(sessionConfig(session).path("themeConfig"), routeRules(session), state, evaluationTime().getTime());
    }

    private void rebindLocation(GameSession session, GameSessionCommand command, ObjectNode run) {
        runPayload(command, "questKey", "nodeId", "reason"); runReason(command);
        if (!"PAUSED".equals(run.path("operationStatus").asText()) || !"ONLINE".equals(run.path("authorityMode").asText())) throw error("GAME_RUN_PAUSED", "请先暂停在线写入再迁点");
        JsonNode quest = configuredQuest(session, text(command.getPayload().get("questKey"), 64));
        Long nodeId = positiveLong(command.getPayload().get("nodeId")); boolean approved = nodeId != null && nodeId == quest.path("nodeId").asLong();
        for (JsonNode candidate : quest.path("approvedFallbackNodeIds")) approved |= Objects.equals(nodeId, candidate.asLong());
        if (!approved || runtimeMapper.selectStation(session.getId(), nodeId) == null) throw error("GAME_LOCATION_NOT_APPROVED", "该备用点不属于发布时批准的绑定");
        long previousNode = bindingNode(session, quest), version = bindingVersion(session, quest) + 1;
        run.with("bindings").putObject(quest.path("locationKey").asText()).put("nodeId", nodeId).put("version", version);
        Iterator<Map.Entry<String, JsonNode>> arrivals = run.with("arrivals").fields();
        while (arrivals.hasNext()) if (quest.path("locationKey").asText().equals(arrivals.next().getValue().path("locationKey").asText())) arrivals.remove();
        for (GameStoryState owner : safe(runtimeMapper.selectStoryStates(session.getId()))) if ("TEAM".equals(owner.getOwnerType())) {
            TopicRouteEngine.State state = routeOwnerState(owner);
            state.getThemeRuntime().with("verifiedNodes").remove(String.valueOf(previousNode));
            for (JsonNode related : routeGameplay(session).path("quests")) if (quest.path("locationKey").asText().equals(related.path("locationKey").asText())) {
                state.getThemeRuntime().with("verifiedNodes").remove(related.path("nodeId").asText());
                state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").remove(related.path("questKey").asText());
                if (related.path("questKey").asText().equals(state.getThemeRuntime().path("gameplay").path("activeQuest").asText())) state.getThemeRuntime().with("gameplay").remove(Arrays.asList("activeQuest", "activeQuestReceiptId"));
            }
            Iterator<Map.Entry<String, JsonNode>> facts = state.getThemeRuntime().with("operationFacts").fields();
            while (facts.hasNext()) if (quest.path("locationKey").asText().equals(facts.next().getValue().path("locationKey").asText())) facts.remove();
            if (quest.path("locationKey").asText().equals(state.getThemeRuntime().path("activeOperation").path("locationKey").asText())) state.getThemeRuntime().remove("activeOperation");
            saveRouteOwner(session, owner, state);
        }
    }

    Map<String, Object> closeFallback(GameActorContext actor, CmsActivity activity, GameSession session,
            GameSessionCommand command, ObjectNode run, Long receiptId) {
        runPayload(command, "reason"); requireSessionStatus(session, GameSession.RUNNING);
        if (!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(sessionConfig(session).path("themeConfig")))) throw error("GAME_ACTION_UNSUPPORTED", "该模式不支持投票");
        requireOnlineRun(run); requireActivityServiceWindow(activity, evaluationTime());
        CooperationScope scope = cooperationScope(session, true);
        ObjectNode applied;
        try { applied = cooperationEngine.closeFallback(sessionConfig(session).path("themeConfig"), scope.world,
                scope.teams, scope.leaders, actor.getMemberId(), runReason(command), "receipt:" + receiptId, routeRules(session), clock.now().getTime()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw error("GAME_FALLBACK_UNAVAILABLE", invalid.getMessage()); }
        for (TopicRouteEngine.State state : scope.teams.values()) {
            state.getThemeRuntime().with("gameplay").remove(Arrays.asList("activeQuest", "activeQuestReceiptId", "activeQuestCostOption", "pendingTaskEffects"));
            state.getThemeRuntime().remove(Arrays.asList("activeOperation", "operationFacts"));
        }
        persistCooperation(scope); run.remove(Arrays.asList("nonces", "arrivals")); saveRun(session, run);
        return singleton("result", applied);
    }
}
