package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R14-PLAY:act() 把「选择结果」汇报给旅程运行时 —— 分支选项、问答选项、通过类玩法各一次;
 * 没通过 / 没选择 / 旅程运行时缺失时不汇报(非旅程主题零变化)。
 */
class AdvancedGameChoiceHookTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;

    private static final String BRANCH_CONFIG = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,"
            + "\"startStepId\":\"ask_linzhou\",\"steps\":["
            + "{\"id\":\"ask_linzhou\",\"title\":\"写进公开记录前\",\"options\":["
            + "{\"id\":\"to_correct\",\"label\":\"更正\",\"nextStepId\":\"correct\"},"
            + "{\"id\":\"to_exhibit\",\"label\":\"展示\",\"nextStepId\":\"exhibit\"}]},"
            + "{\"id\":\"correct\",\"terminal\":true,\"outcomeCode\":\"correct\",\"options\":[]},"
            + "{\"id\":\"exhibit\",\"terminal\":true,\"outcomeCode\":\"exhibit\",\"options\":[]}]}}";
    private static final String QA_CONFIG = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\","
            + "\"title\":\"题\",\"options\":[{\"id\":\"a\",\"label\":\"甲\",\"correct\":false},"
            + "{\"id\":\"b\",\"label\":\"乙\",\"correct\":true}],\"xp\":5}}";
    private static final String SORT_CONFIG = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,"
            + "\"prompt\":\"排一下\",\"items\":[{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
            + "\"answerOrder\":[\"a\",\"b\"],\"xp\":5}}";
    private static final String HIDDEN_CONFIG = "{\"schemaVersion\":1,\"hiddenObject\":{\"enabled\":true,"
            + "\"title\":\"找\",\"imageUrl\":\"https://x/y.png\","
            + "\"hotspots\":[{\"id\":\"h1\",\"label\":\"一\",\"x\":0.2,\"y\":0.2,\"r\":0.1},"
            + "{\"id\":\"h2\",\"label\":\"二\",\"x\":0.8,\"y\":0.8,\"r\":0.1},"
            + "{\"id\":\"h3\",\"label\":\"三\",\"x\":0.5,\"y\":0.5,\"r\":0.1}],\"xp\":5}}";
    private static final String PRICE_PAIR_CONFIG = "{\"schemaVersion\":1,\"pricePair\":{\"enabled\":true,"
            + "\"title\":\"猜图\",\"items\":[{\"id\":\"p1\",\"name\":\"甲\",\"imageUrl\":\"https://x/1.png\"},"
            + "{\"id\":\"p2\",\"name\":\"乙\",\"imageUrl\":\"https://x/2.png\",\"correct\":true},"
            + "{\"id\":\"p3\",\"name\":\"丙\",\"imageUrl\":\"https://x/3.png\"}],\"xp\":5,\"maxTries\":0}}";

    private GameRuntimeSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private PlayTeamMapper teamMapper;
    private PlayAttemptLogMapper attemptMapper;
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        teamMapper = mock(PlayTeamMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        topicRouteRuntimeService = mock(ITopicRouteRuntimeService.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", topicRouteRuntimeService);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void branchChoiceReportsStepAndOption() {
        act(BRANCH_CONFIG, "{\"branch\":{\"currentStepId\":\"ask_linzhou\",\"visitedStepIds\":[\"ask_linzhou\"]}}",
                "CHOOSE", payload("optionId", "to_exhibit"), "branch-choice");

        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "ask_linzhou", "to_exhibit");
    }

    /** 负控:qa 选项的 hook 漏挂时本用例红 —— 选项 effects 永远写不进本局状态。 */
    @Test
    void qaPickReportsTheChosenOptionEvenWhenWrong() {
        act(QA_CONFIG, "{}", "SUBMIT_QA", payload("optionId", "a"), "qa-wrong-choice");

        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "qa", "a");
    }

    @Test
    void sortPassReportsKitPass() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", Arrays.asList("a", "b"));

        act(SORT_CONFIG, "{}", "SUBMIT_SORT", payload, "sort-pass");

        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "sort", null);
    }

    @Test
    void sortFailDoesNotReport() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", Arrays.asList("b", "a"));

        act(SORT_CONFIG, "{}", "SUBMIT_SORT", payload, "sort-fail");

        verify(topicRouteRuntimeService, never()).recordChoice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void hiddenObjectAllFoundReportsKitPass() {
        act(HIDDEN_CONFIG,
                "{\"hiddenObject\":{\"foundIds\":[\"h1\",\"h2\"]}}",
                "SUBMIT_HIDDEN_OBJECT", payload("x", Double.valueOf(0.5), "y", Double.valueOf(0.5)),
                "hidden-done");

        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "hiddenObject", null);
    }

    @Test
    void pricePairCorrectPickReportsKitPass() {
        act(PRICE_PAIR_CONFIG, "{\"pricePair\":{\"attempts\":0,\"finished\":false}}",
                "SUBMIT_PRICE_PAIR", payload("pickId", "p2"), "guess-hit");

        verify(topicRouteRuntimeService).recordChoice(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "pricePair", null);
    }

    @Test
    void qaTypedAnswerReportsNothing() {
        String config = QA_CONFIG.replace("\"mode\":\"PICK\"", "\"mode\":\"TYPE\",\"answerText\":\"乙\"");
        act(config, "{}", "SUBMIT_QA", payload("input", "乙"), "qa-typed");

        verify(topicRouteRuntimeService, never()).recordChoice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingJourneyRuntimeKeepsChoicesWorking() {
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", null);
        GameRuntimeSession session = runningSession(BRANCH_CONFIG,
                "{\"branch\":{\"currentStepId\":\"ask_linzhou\",\"visitedStepIds\":[\"ask_linzhou\"]}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        assertDoesNotThrow(() -> service.act(MEMBER_ID, 51L, 0, "branch-no-journey", "CHOOSE",
                payload("optionId", "to_correct")));
    }

    /** 分支步骤 id 与玩法段名不冲突时不会误判;这里同时确认 recordChoice 收到的是 null optionId 的形式。 */
    @Test
    void kitPassUsesNullOptionIdMatcher() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", Arrays.asList("a", "b"));

        act(SORT_CONFIG, "{}", "SUBMIT_SORT", payload, "sort-pass-null");

        verify(topicRouteRuntimeService).recordChoice(eq(MEMBER_ID), eq(TOPIC_ID), eq(ACTIVITY_ID), eq(NODE_ID), eq("sort"),
                isNull());
    }

    // ==================== helpers ====================

    private void act(String config, String state, String action, Map<String, Object> payload, String key) {
        GameRuntimeSession session = runningSession(config, state);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, key, action, payload);
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    private static Map<String, Object> payload(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key1, value1);
        payload.put(key2, value2);
        return payload;
    }

    private GameRuntimeSession runningSession(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(51L);
        session.setActivityId(ACTIVITY_ID);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStartedAt(new Date(NOW));
        return session;
    }
}
