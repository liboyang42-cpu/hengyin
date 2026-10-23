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
        if (ReflectionTestUtils.getField(controller, "playCommandReceiptMapper") == null) {
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
            ReflectionTestUtils.setField(controller, "playCommandReceiptMapper", receipts);
        }
        if (ReflectionTestUtils.getField(controller, "playCommandRouteMapper") == null) {
            Object runtime = ReflectionTestUtils.getField(controller, "topicRouteRuntimeService");
            TopicRouteSessionMapper routes = runtime instanceof TopicRouteRuntimeServiceImpl
                    ? (TopicRouteSessionMapper) ReflectionTestUtils.getField(runtime, "routeMapper")
                    : mock(TopicRouteSessionMapper.class);
            ReflectionTestUtils.setField(controller, "playCommandRouteMapper", routes);
        }
    }

    private static String receiptKey(Long activity, Long member, String request) { return activity + ":" + member + ":" + request; }

    static void scope(ApiPlayProgressController controller, Long topic) {
        TOPICS.put(controller, topic); install(controller);
    }

    static void routeVersion(ApiPlayProgressController controller, Long activity, Long topic, int version) {
        install(controller);
        TopicRouteSession session = new TopicRouteSession(); session.setId(99L); session.setVersion(version);
        TopicRouteSessionMapper routes = (TopicRouteSessionMapper) ReflectionTestUtils.getField(controller, "playCommandRouteMapper");
        when(routes.selectCurrentByOwner(activity, topic, controller.getAppUserId())).thenReturn(session);
        when(routes.selectAccessibleByIdForUpdate(99L, controller.getAppUserId())).thenReturn(session);
    }

    private static Map<?, ?> context(ApiPlayProgressController controller, String activity, String topic, String node) {
        install(controller);
        long activityId = activity == null || activity.isBlank() ? 0 : Long.parseLong(activity);
        Long topicId = topic == null ? TOPICS.get(controller) : Long.valueOf(topic);
        if (topicId == null) throw new IllegalStateException("测试必须声明原主题 scope");
        Long member = controller.getAppUserId();
        Map<String, Object> fields = new HashMap<>();
        IGameSessionRuntimeService shared = (IGameSessionRuntimeService) ReflectionTestUtils.getField(controller, "gameSessionRuntimeService");
        var snapshot = shared == null ? null : shared.sharedRouteSnapshot(member, topicId, activityId, Long.valueOf(node));
        if (snapshot != null) {
            GameSessionRuntimeMapper receipts = (GameSessionRuntimeMapper) ReflectionTestUtils.getField(controller, "playCommandReceiptMapper");
            GameSession session = receipts.selectSessionByActivity(activityId);
            if (session == null) throw new IllegalStateException("共享局夹具必须声明原 session/revision/runtime/epoch");
            fields.put("routeSessionId", session.getId()); fields.put("expectedRouteVersion", session.getRevision());
            fields.put("runtimeInstance", snapshot.path("runtimeInstance").asText()); fields.put("authorityEpoch", snapshot.path("authorityEpoch").asInt());
        } else {
            TopicRouteSessionMapper routes = (TopicRouteSessionMapper) ReflectionTestUtils.getField(controller, "playCommandRouteMapper");
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
}
