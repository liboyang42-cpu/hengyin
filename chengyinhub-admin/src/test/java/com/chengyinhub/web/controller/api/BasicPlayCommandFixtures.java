package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 旧业务场景的合法客户端命令夹具。资格仍来自各测试自己的报名/活动/节点，绝不跳过入口。 */
final class BasicPlayCommandFixtures {
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final Map<ApiPlayProgressController, Long> TOPICS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private BasicPlayCommandFixtures() { }

    static void install(ApiPlayProgressController controller) {
        if (com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playCommandReceiptMapper") == null) {
            GameSessionRuntimeMapper receipts = mock(GameSessionRuntimeMapper.class);
            Map<String, GameCommandReceipt> saved = new HashMap<>();
            when(receipts.selectReceiptForUpdate(anyLong(), anyLong(), anyString())).thenAnswer(c -> saved.get(receiptKey(c.getArgument(0), c.getArgument(1), c.getArgument(2))));
            when(receipts.selectReceipt(anyLong(), anyLong(), anyString())).thenAnswer(c -> saved.get(receiptKey(c.getArgument(0), c.getArgument(1), c.getArgument(2))));
            when(receipts.insertReceiptClaim(any())).thenAnswer(c -> {
                GameCommandReceipt receipt = c.getArgument(0);
                String key = receiptKey(receipt.getActivityId(), receipt.getActorMemberId(), receipt.getRequestId());
                if (saved.containsKey(key)) return 0;
                receipt.setId((long) saved.size() + 1); saved.put(key, receipt); return 1;
            });
            when(receipts.completeReceipt(any())).thenReturn(1);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCommandReceiptMapper", receipts);
        }
        if (com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playCommandRouteMapper") == null) {
            Object runtime = com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "topicRouteRuntimeService");
            TopicRouteSessionMapper routes = runtime instanceof TopicRouteRuntimeServiceImpl
                    ? (TopicRouteSessionMapper) ReflectionTestUtils.getField(runtime, "routeMapper")
                    : mock(TopicRouteSessionMapper.class);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCommandRouteMapper", routes);
        }
    }

    private static String receiptKey(Long activity, Long member, String request) { return activity + ":" + member + ":" + request; }

    static void scope(ApiPlayProgressController controller, Long topic) {
        TOPICS.put(controller, topic); install(controller);
    }

    static void routeVersion(ApiPlayProgressController controller, Long activity, Long topic, int version) {
        install(controller);
        TopicRouteSession session = new TopicRouteSession(); session.setId(99L); session.setVersion(version);
        session.setMemberId(controller.getAppUserId()); session.setActivityId(activity); session.setTopicId(topic);
        session.setStatus(TopicRouteSession.STATUS_RUNNING); session.setPlayInstanceId("default");
        TopicRouteSessionMapper routes = (TopicRouteSessionMapper) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playCommandRouteMapper");
        when(routes.selectCurrentByOwner(activity, topic, controller.getAppUserId())).thenReturn(session);
        when(routes.selectAccessibleByIdForUpdate(99L, controller.getAppUserId())).thenReturn(session);
    }

    static void frozenRun(ApiPlayProgressController c, Long activity, Long topic, Long node,
            int version, com.fasterxml.jackson.databind.JsonNode answer) {
        routeVersion(c, activity, topic, version);
        TopicRouteSessionMapper routes = (TopicRouteSessionMapper) PlayNodeAccessTestFixtures.getField(c, "playCommandRouteMapper");
        TopicRouteSession session = routes.selectCurrentByOwner(activity, topic, c.getAppUserId());
        var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        snapshot.putObject("nodeAnswers").set(node.toString(), answer.deepCopy());
        session.setConfigSnapshotJson(snapshot.toString());
    }

    private static Map<?, ?> context(ApiPlayProgressController controller, String activity, String topic, String node) {
        install(controller);
        long activityId = activity == null || activity.isBlank() ? 0 : Long.parseLong(activity);
        Long topicId = topic == null ? TOPICS.get(controller) : Long.valueOf(topic);
        if (topicId == null) throw new IllegalStateException("测试必须声明原主题 scope");
        Long member = controller.getAppUserId();
        Map<String, Object> fields = new HashMap<>();
        IGameSessionRuntimeService shared = (IGameSessionRuntimeService) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "gameSessionRuntimeService");
        var snapshot = shared == null ? null : shared.sharedRouteSnapshot(member, topicId, activityId, Long.valueOf(node));
        if (snapshot != null) {
            GameSessionRuntimeMapper receipts = (GameSessionRuntimeMapper) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playCommandReceiptMapper");
            GameSession session = receipts.selectSessionByActivity(activityId);
            if (session == null) throw new IllegalStateException("共享局夹具必须声明原 session/revision/runtime/epoch");
            fields.put("routeSessionId", session.getId()); fields.put("expectedRouteVersion", session.getRevision());
            fields.put("runtimeInstance", snapshot.path("runtimeInstance").asText()); fields.put("authorityEpoch", snapshot.path("authorityEpoch").asInt());
        } else {
            TopicRouteSessionMapper routes = (TopicRouteSessionMapper) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playCommandRouteMapper");
            TopicRouteSession session = routes.selectCurrentByOwner(activityId, topicId, member);
            if (session == null) session = routes.selectByOwner(activityId, topicId, member, "default");
            fields.put("routeSessionId", session == null ? 0L : session.getId());
            fields.put("expectedRouteVersion", session == null ? 0 : session.getVersion());
        }
        return fields;
    }
    private static String id(String original) { return original == null ? "test-intent-" + ATTEMPTS.incrementAndGet() : original; }
    private static Integer version(Map<?, ?> context, Integer original) {
        if (original != null) return original;
        return context.get("expectedRouteVersion") instanceof Number ? ((Number) context.get("expectedRouteVersion")).intValue() : 0;
    }
    private static Long session(Map<?, ?> context) { return context.get("routeSessionId") instanceof Number ? ((Number) context.get("routeSessionId")).longValue() : 0L; }
    private static String runtime(Map<?, ?> context) { return (String) context.get("runtimeInstance"); }
    private static Integer epoch(Map<?, ?> context) { return (Integer) context.get("authorityEpoch"); }

    static AjaxResult answer(ApiPlayProgressController c, String a, String n, String input, String t) {
        return answer(c, a, n, input, t, null, null);
    }
    static AjaxResult answer(ApiPlayProgressController c, String a, String n, String input, String t, String action, Integer expected) {
        Map<?, ?> x = context(c, a, t, n);
        return c.answer(a, n, input, t, id(action), version(x, expected), session(x), runtime(x), epoch(x));
    }
    static AjaxResult answerAt(ApiPlayProgressController c, String a, String n, String input, String t, String lng, String lat, String accuracy) {
        Map<?, ?> x = context(c, a, t, n);
        return c.answer(a, n, input, t, id(null), version(x, null), session(x), runtime(x), epoch(x), lng, lat, accuracy);
    }
    static AjaxResult checkin(ApiPlayProgressController c, String a, String input, String t, String action, Integer expected, Long registration, String node) {
        Map<?, ?> x = context(c, a, t, node);
        return c.checkin(a, input, t, id(action), version(x, expected), registration, node, session(x), runtime(x), epoch(x));
    }
    static AjaxResult arrive(ApiPlayProgressController c, String a, String n, String lng, String lat, String t) {
        return arrive(c, a, n, lng, lat, t, null, null, null);
    }
    static AjaxResult arrive(ApiPlayProgressController c, String a, String n, String lng, String lat, String t, String action, Integer expected) {
        return arrive(c, a, n, lng, lat, t, action, expected, null);
    }
    static AjaxResult arrive(ApiPlayProgressController c, String a, String n, String lng, String lat, String t, String action, Integer expected, String accuracy) {
        Map<?, ?> x = context(c, a, t, n);
        return c.arrive(a, n, lng, lat, t, id(action), version(x, expected), accuracy, session(x), runtime(x), epoch(x));
    }
    static AjaxResult photo(ApiPlayProgressController c, String a, String n, String pic, String t) {
        return photo(c, a, n, pic, t, null, null);
    }
    static AjaxResult photo(ApiPlayProgressController c, String a, String n, String pic, String t, String action, Integer expected) {
        Map<?, ?> x = context(c, a, t, n);
        return c.photo(a, n, pic, t, id(action), version(x, expected), session(x), runtime(x), epoch(x));
    }
    static AjaxResult revealPuzzle(ApiPlayProgressController c, String a, String n, String t) {
        return revealPuzzle(c, a, n, t, null, null);
    }
    static AjaxResult revealPuzzle(ApiPlayProgressController c, String a, String n, String t, String action, Integer expected) {
        Map<?, ?> x = context(c, a, t, n);
        return c.revealPuzzle(a, n, t, id(action), version(x, expected), session(x), runtime(x), epoch(x));
    }

    // Populate only absent client identity. Explicit stale versions/IDs remain negative controls.
    static void identity(ApiPlayProgressController c, com.chengyinhub.business.domain.dto.SensorResultRequest request) {
        Map<?, ?> x = context(c, request.getActivityId() == null ? null : request.getActivityId().toString(),
                request.getTopicId() == null ? null : request.getTopicId().toString(), request.getNodeId().toString());
        request.setRouteActionId(id(request.getRouteActionId()));
        request.setExpectedRouteVersion(version(x, request.getExpectedRouteVersion()));
        if (request.getRouteSessionId() == null) {
            request.setRouteSessionId(session(x)); request.setRuntimeInstance(runtime(x)); request.setAuthorityEpoch(epoch(x));
        }
    }
    static AjaxResult sensorResult(ApiPlayProgressController c, com.chengyinhub.business.domain.dto.SensorResultRequest request) {
        identity(c, request); return c.sensorResult(request);
    }
    static AjaxResult submitPreference(ApiPlayProgressController c, Long node, com.chengyinhub.business.domain.dto.PreferenceSubmitRequest request) {
        Map<?, ?> x = context(c, request.getActivityId(), request.getTopicId(), node.toString());
        request.setRouteActionId(id(request.getRouteActionId()));
        request.setExpectedRouteVersion(version(x, request.getExpectedRouteVersion()));
        if (request.getRouteSessionId() == null) {
            request.setRouteSessionId(session(x)); request.setRuntimeInstance(runtime(x)); request.setAuthorityEpoch(epoch(x));
        }
        return c.submitPreference(node, request);
    }

}
