package com.chengyinhub.business.service.support.qmod;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Real ce61 domain/validator bridge, deliberately not a bean or endpoint.
 * Caller must load both entities from existing authorized server reads in one consistent scope,
 * and obtain fresh owner/activity/opening/node/target-policy approval before every inspection.
 * This does not implement those decisions, and cannot be supplied with author/request entities.
 */
public final class QmodExistingBranchProjection {
    private final TopicRouteConfigValidator validator = new TopicRouteConfigValidator();

    /** Computes a fresh context only; it grants no access and returns no player options. */
    public QmodChoiceBinding.Context context(Long actorMemberId, GameRuntimeSession game,
            TopicRouteSession route, Date trustedNow, String trustedProfile, String frozenPackageDigest,
            Set<String> completeNodeKeys, Map<String, Long> serverNodeIds, Set<Long> topicNodeIds,
            QmodChoiceBinding.ChoiceSpec choice) {
        QmodChoiceBinding.need(actorMemberId != null && game != null && route != null && trustedNow != null,
                "FROZEN_RUNTIME_AND_CLOCK_REQUIRED");
        QmodChoiceBinding.need(QmodChoiceFallbackAdapter.PROFILE.equals(trustedProfile), "PROFILE_NOT_SUPPORTED");
        QmodChoiceBinding.need(frozenPackageDigest != null && frozenPackageDigest.matches("[0-9a-f]{64}"),
                "FROZEN_PACKAGE_SOURCE_REQUIRED");
        QmodChoiceBinding.need(game.getOwnerType() != null && game.getOwnerType() == GameRuntimeSession.OWNER_PLAYER
                && actorMemberId.equals(game.getOwnerId()) && actorMemberId.equals(route.getMemberId()),
                "OWNER_UNKNOWN_TEAM_OR_MISMATCH");
        QmodChoiceBinding.need(game.getTopicId() != null && game.getTopicId().equals(route.getTopicId())
                && game.getActivityId() != null && game.getActivityId().equals(route.getActivityId())
                && game.getNodeId() != null && game.getNodeId().equals(route.getCurrentNodeId()),
                "RUNTIME_SCOPE_MISMATCH");
        QmodChoiceBinding.need(Integer.valueOf(GameRuntimeSession.STATUS_RUNNING).equals(game.getStatus())
                && Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(route.getStatus()), "RUNTIME_NOT_RUNNING");
        QmodChoiceBinding.need(game.getCompletedAt() == null && route.getCompletedAt() == null,
                "RUNTIME_ALREADY_COMPLETED");
        QmodChoiceBinding.need(game.getDeadlineAt() == null || trustedNow.before(game.getDeadlineAt()), "RUNTIME_EXPIRED");
        JsonNode gameConfig = object(game.getConfigSnapshotJson());
        JsonNode gameState = object(game.getStateJson());
        JsonNode routeConfig = object(route.getConfigSnapshotJson());
        JsonNode routeState = object(route.getStateJson());
        QmodChoiceBinding.need(routeConfig.path("edges").isArray(), "LINEAR_OR_UNKNOWN_ROUTE_UNSUPPORTED");
        QmodChoiceBinding.need("ACTIVE".equals(text(routeState, "status"))
                && number(routeState, "currentNodeId") == game.getNodeId(), "ROUTE_CURRENT_STATE_MISMATCH");
        QmodChoiceBinding.need(gameConfig.path("branch").path("enabled").isBoolean()
                && gameConfig.path("branch").path("enabled").asBoolean(), "EXISTING_BRANCH_REQUIRED");
        QmodChoiceBinding.need(!gameConfig.path("multiplayer").path("enabled").asBoolean(false),
                "TEAM_BRANCH_UNSUPPORTED");
        String currentStep = text(gameState.path("branch"), "currentStepId");
        Map<String, JsonNode> steps = rows(gameConfig.path("branch").path("steps"), "BRANCH_STEPS_INVALID");
        JsonNode step = steps.get(currentStep);
        QmodChoiceBinding.need(step != null && step.path("terminal").isBoolean()
                && !step.path("terminal").asBoolean(), "CURRENT_STEP_NONTERMINAL_REQUIRED");
        QmodChoiceBinding.need(game.getId() != null && game.getTemplateId() != null && game.getVersion() != null
                && route.getId() != null && route.getVersion() != null && route.getConfigVersion() != null,
                "SESSION_ID_OR_VERSION_MISSING");
        String digest = digest(game, route, trustedProfile, frozenPackageDigest, completeNodeKeys,
                serverNodeIds, topicNodeIds, choice);
        return new QmodChoiceBinding.Context(actorMemberId, game.getOwnerType(), game.getOwnerId(), route.getMemberId(),
                game.getTopicId(), game.getActivityId(), game.getId(), game.getTemplateId(), game.getVersion(),
                route.getId(), route.getVersion(), route.getConfigVersion(), game.getNodeId(), currentStep, digest);
    }

    public QmodChoiceBinding.Candidate analyze(Long actorMemberId, GameRuntimeSession game,
            TopicRouteSession route, Date trustedNow, String trustedProfile, String frozenPackageDigest,
            Set<String> completeNodeKeys, Map<String, Long> serverNodeIds, Set<Long> topicNodeIds,
            QmodChoiceBinding.ChoiceSpec choice, QmodChoiceBinding.Access freshExistingPolicyAccess) {
        QmodChoiceBinding.Context context = context(actorMemberId, game, route, trustedNow, trustedProfile,
                frozenPackageDigest, completeNodeKeys, serverNodeIds, topicNodeIds, choice);
        JsonNode config = object(game.getConfigSnapshotJson());
        JsonNode state = object(game.getStateJson());
        // Calls the actual production validator; no local clone or substitute.
        // Current-node outcomes below come from its actual frozen branch. This two-argument call
        // is structural validation, not a claim to have reverified every other node's declarations.
        JsonNode graph = object(validator.validateAndNormalize(route.getConfigSnapshotJson(), topicNodeIds));
        JsonNode routeState = object(route.getStateJson());
        emptyArrayIfPresent(graph, "nodeRequirements", "REAL_WORLD_GATED_ROUTE_UNSUPPORTED");
        Map<String, JsonNode> steps = rows(config.path("branch").path("steps"), "BRANCH_STEPS_INVALID");
        String stepId = text(state.path("branch"), "currentStepId");
        JsonNode step = steps.get(stepId);
        Map<String, JsonNode> options = rows(step.path("options"), "BRANCH_OPTIONS_INVALID");
        List<QmodChoiceBinding.FrozenOption> frozenOptions = new ArrayList<>();
        for (JsonNode option : options.values()) {
            String next = text(option, "nextStepId");
            JsonNode terminal = steps.get(next);
            QmodChoiceBinding.need(terminal != null && terminal.path("terminal").isBoolean()
                    && terminal.path("terminal").asBoolean(), "MULTISTEP_OR_NONTERMINAL_UNSUPPORTED");
            QmodChoiceBinding.need(terminal.path("options").isArray() && terminal.path("options").size() == 0,
                    "TERMINAL_WITH_OPTIONS_UNSUPPORTED");
            frozenOptions.add(new QmodChoiceBinding.FrozenOption(text(option, "id"), text(option, "label"),
                    next, true, text(terminal, "outcomeCode")));
        }
        Set<Long> completed = ids(routeState.path("completedNodeIds"), "COMPLETION_STATE_REQUIRED");
        QmodChoiceBinding.need(!completed.contains(game.getNodeId()), "CURRENT_NODE_ALREADY_COMPLETED");
        JsonNode visits = routeState.path("edgeVisits");
        QmodChoiceBinding.need(visits.isObject(), "EDGE_VISITS_REQUIRED");
        List<QmodChoiceBinding.FrozenEdge> edges = new ArrayList<>();
        for (JsonNode edge : graph.path("edges")) {
            emptyArrayIfPresent(edge, "conditions", "CONDITIONAL_ROUTE_UNSUPPORTED");
            emptyArrayIfPresent(edge, "effects", "EFFECTFUL_ROUTE_UNSUPPORTED");
            QmodChoiceBinding.need(!edge.path("allowLoop").asBoolean(false), "LOOP_ROUTE_UNSUPPORTED");
            if (number(edge, "fromNodeId") != game.getNodeId()) continue;
            String id = text(edge, "id");
            JsonNode used = visits.get(id);
            QmodChoiceBinding.need(used == null || (used.isIntegralNumber() && used.canConvertToInt()
                    && used.intValue() == 0), "USED_OR_UNKNOWN_EDGE_UNSUPPORTED");
            long target = number(edge, "toNodeId");
            QmodChoiceBinding.need(!completed.contains(target), "COMPLETED_TARGET_UNSUPPORTED");
            JsonNode trigger = edge.path("trigger");
            edges.add(new QmodChoiceBinding.FrozenEdge(id, game.getNodeId(), target,
                    text(trigger, "type"), text(trigger, "outcomeCode"), true, true, false));
        }
        Long fallback = null;
        for (JsonNode edge : graph.path("fallbacks")) {
            emptyArrayIfPresent(edge, "conditions", "CONDITIONAL_FALLBACK_UNSUPPORTED");
            emptyArrayIfPresent(edge, "effects", "EFFECTFUL_FALLBACK_UNSUPPORTED");
            if (number(edge, "fromNodeId") != game.getNodeId()) continue;
            QmodChoiceBinding.need(fallback == null, "FALLBACK_AMBIGUOUS");
            fallback = number(edge, "toNodeId");
            QmodChoiceBinding.need(!completed.contains(fallback), "FALLBACK_ALREADY_COMPLETED");
        }
        return new QmodChoiceBinding().bind(trustedProfile, completeNodeKeys, serverNodeIds, topicNodeIds,
                choice, context, freshExistingPolicyAccess, frozenOptions, edges, fallback);
    }

    private JsonNode object(String raw) {
        JsonNode node = validator.readTree(raw);
        QmodChoiceBinding.need(node != null && node.isObject(), "FROZEN_OBJECT_REQUIRED");
        return node;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        QmodChoiceBinding.need(value != null && value.isTextual() && !value.textValue().trim().isEmpty(),
                "FROZEN_TEXT_REQUIRED:" + field);
        return value.textValue();
    }
    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        QmodChoiceBinding.need(value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() > 0, "FROZEN_NODE_ID_INVALID:" + field);
        return value.longValue();
    }
    private static Map<String, JsonNode> rows(JsonNode array, String code) {
        QmodChoiceBinding.need(array.isArray() && array.size() > 0, code);
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (JsonNode row : array) {
            QmodChoiceBinding.need(row.isObject(), code);
            String id = text(row, "id");
            QmodChoiceFallbackAdapter.requireKey(id);
            QmodChoiceBinding.need(rows.put(id, row) == null, code + ":DUPLICATE");
        }
        return rows;
    }
    private static Set<Long> ids(JsonNode array, String code) {
        QmodChoiceBinding.need(array.isArray(), code);
        Set<Long> result = new LinkedHashSet<>();
        for (JsonNode item : array) {
            QmodChoiceBinding.need(item.isIntegralNumber() && item.canConvertToLong() && item.longValue() > 0
                    && result.add(item.longValue()), code);
        }
        return result;
    }
    private static void emptyArrayIfPresent(JsonNode node, String field, String code) {
        JsonNode value = node.get(field);
        QmodChoiceBinding.need(value == null || (value.isArray() && value.size() == 0), code);
    }

    private static String digest(GameRuntimeSession game, TopicRouteSession route, String profile,
            String frozenPackageDigest, Set<String> keys, Map<String, Long> nodeIds, Set<Long> topicNodeIds,
            QmodChoiceBinding.ChoiceSpec choice) {
        QmodChoiceBinding.need(keys != null && nodeIds != null && topicNodeIds != null && choice != null
                && choice.options != null && !keys.contains(null) && !nodeIds.containsKey(null)
                && !nodeIds.containsValue(null) && !topicNodeIds.contains(null), "FROZEN_AUTHOR_INPUT_REQUIRED");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, profile); add(digest, frozenPackageDigest);
            add(digest, game.getConfigSnapshotJson()); add(digest, game.getStateJson());
            add(digest, route.getConfigSnapshotJson()); add(digest, route.getStateJson());
            add(digest, route.getPlayInstanceId());
            add(digest, game.getStartedAt() == null ? null : String.valueOf(game.getStartedAt().getTime()));
            add(digest, game.getDeadlineAt() == null ? null : String.valueOf(game.getDeadlineAt().getTime()));
            add(digest, choice.nodeKey); add(digest, choice.fallbackChoiceId);
            for (QmodChoiceBinding.Option option : choice.options) {
                QmodChoiceBinding.need(option != null, "OPTION_REQUIRED");
                add(digest, option.id); add(digest, option.next);
            }
            add(digest, "NODE_KEYS");
            for (String key : new TreeSet<>(keys)) add(digest, key);
            add(digest, "NODE_MAP");
            for (Map.Entry<String, Long> item : new TreeMap<>(nodeIds).entrySet()) {
                add(digest, item.getKey()); add(digest, String.valueOf(item.getValue()));
            }
            add(digest, "TOPIC_IDS");
            for (Long id : new TreeSet<>(topicNodeIds)) add(digest, String.valueOf(id));
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) {
                out.append(Character.forDigit((value >>> 4) & 15, 16));
                out.append(Character.forDigit(value & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void add(MessageDigest digest, String value) {
        if (value == null) { digest.update(new byte[] {-1, -1, -1, -1}); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[] {(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8), (byte) bytes.length});
        digest.update(bytes);
    }
}
