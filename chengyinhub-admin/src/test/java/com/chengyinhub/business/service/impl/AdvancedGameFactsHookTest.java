package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayNote;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayNoteMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ai.PhotoCheckVisionResult;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扣血清单之外的玩法也把成败写成事实 sys.passed.{nodeId}(**只记事实、不扣血**),
 * 随机类玩法把结果写成 sys.outcome.{nodeId}。
 *
 * <p>每条都同时断言「没扣血」—— 这批玩法 9-21 拍板不扣血,事实写入不许顺手把扣血也带上。
 */
class AdvancedGameFactsHookTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;

    private static final String COUNTDOWN = "{\"schemaVersion\":1,\"countdown\":{\"enabled\":true,\"seconds\":60}}";
    private static final String ESTIMATE = "{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"min\":0,"
            + "\"max\":1000,\"answer\":100,\"tolerance\":5,\"xp\":5}}";
    private static final String PHOTO_RETAKE = "{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,"
            + "\"requirement\":\"拍门牌\",\"maxTries\":1,\"fallback\":\"retake\",\"xp\":5}}";
    private static final String PHOTO_FALLBACK = PHOTO_RETAKE.replace("retake", "pass");
    private static final String COIN = "{\"schemaVersion\":1,\"coinFlip\":{\"enabled\":true,"
            + "\"heads\":{\"action\":\"左\"},\"tails\":{\"action\":\"右\"}}}";
    private static final String DICE = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":1,"
            + "\"faces\":[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\"]}}";
    private static final String DRAW = "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,"
            + "\"items\":[{\"id\":\"c1\",\"label\":\"甲\",\"weight\":1},{\"id\":\"c2\",\"label\":\"乙\",\"weight\":1}]}}";

    private GameRuntimeSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private PlayTeamMapper teamMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayNoteMapper playNoteMapper;
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    private AdvancedGameRuntimeServiceImpl service;
    private PhotoCheckVisionService vision;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        teamMapper = mock(PlayTeamMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        playNoteMapper = mock(PlayNoteMapper.class);
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
        ReflectionTestUtils.setField(service, "playNoteMapper", playNoteMapper);
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
        when(playNoteMapper.insert(any(PlayNote.class))).thenReturn(1);
        vision = mock(PhotoCheckVisionService.class);
        AdvancedActionTestFixtures.photos(service, () -> vision.review(MEMBER_ID, "unit-photo", "unit-requirement"));
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    // ==================== 成败事实:只写 sys.passed,不扣血 ====================

    @Test
    void countdownFinishedWritesPassedWithoutDamage() {
        act(COUNTDOWN, "{\"countdown\":{\"startedAtMs\":" + (NOW - 61_000L) + "}}", "cd-1", "SUBMIT_COUNTDOWN", payload());
        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "cd-1", true);
        verify(topicRouteRuntimeService, never()).recordMistake(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void estimateHitIsPassedAndMissIsFailedWithoutDamage() {
        act(ESTIMATE, "{}", "est-hit", "SUBMIT_ESTIMATE", payload("value", 102));
        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "est-hit", true);

        act(ESTIMATE, "{}", "est-miss", "SUBMIT_ESTIMATE", payload("value", 900));
        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "est-miss", false);
        verify(topicRouteRuntimeService, never()).recordMistake(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void photoRealPassIsPassed() {
        when(vision.review(anyLong(), anyString(), anyString())).thenReturn(PhotoCheckVisionResult.concluded(true, 90, "是门牌"));
        act(PHOTO_RETAKE, "{}", "ph-pass", "SUBMIT_PHOTO_CHECK", payload("imageUrl", "https://x/a.jpg"));
        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "ph-pass", true);
        verify(topicRouteRuntimeService, never()).recordMistake(anyLong(), anyLong(), anyLong(), anyLong());
    }

    /** 次数用完被「放过」:人过去了,但照片没过审 —— 事实是没过。 */
    @Test
    void photoFallbackAfterTriesIsNotPassed() {
        when(vision.review(anyLong(), anyString(), anyString())).thenReturn(PhotoCheckVisionResult.concluded(false, 90, "不是门牌"));
        act(PHOTO_FALLBACK, "{}", "ph-fb", "SUBMIT_PHOTO_CHECK", payload("imageUrl", "https://x/a.jpg"));
        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "ph-fb", false);
    }

    /** 通道降级:结论不可信,既不记过也不记没过(哪怕配置是「放过他」、人也放过去了)。 */
    @Test
    void photoDegradedWritesNothing() {
        when(vision.review(anyLong(), anyString(), anyString())).thenReturn(PhotoCheckVisionResult.degraded("超时"));
        act(PHOTO_FALLBACK, "{}", "ph-deg", "SUBMIT_PHOTO_CHECK", payload("imageUrl", "https://x/a.jpg"));
        verify(topicRouteRuntimeService, never()).recordPassed(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
    }

    /** 降级之后再拍一张真过了:结论是 PASS,不被上一次的降级标记带偏。 */
    @Test
    void photoRealPassAfterDegradedIsPassed() {
        when(vision.review(anyLong(), anyString(), anyString())).thenReturn(PhotoCheckVisionResult.concluded(true, 90, "是门牌"));
        act(PHOTO_RETAKE, "{\"photoCheck\":{\"tries\":1,\"degraded\":true,\"flagged\":true,\"verdict\":\"DEGRADED\"}}",
                "ph-2", "SUBMIT_PHOTO_CHECK", payload("imageUrl", "https://x/b.jpg"));
        verify(topicRouteRuntimeService).recordPassed(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "ph-2", true);
    }

    /** 还有次数的一次没过:不是终局,不写。 */
    @Test
    void photoFailWithTriesLeftWritesNothing() {
        when(vision.review(anyLong(), anyString(), anyString())).thenReturn(PhotoCheckVisionResult.concluded(false, 90, "不是"));
        act(PHOTO_RETAKE.replace("\"maxTries\":1", "\"maxTries\":3"), "{}", "ph-try", "SUBMIT_PHOTO_CHECK",
                payload("imageUrl", "https://x/a.jpg"));
        verify(topicRouteRuntimeService, never()).recordPassed(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
    }

    // ==================== 随机结果:写 sys.outcome ====================

    @Test
    void coinHeadsWritesOutcomeOne() {
        act(COIN, "{}", "coin-1", "FLIP_COIN", payload());   // random 恒 0 → 正面
        verify(topicRouteRuntimeService).recordOutcome(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "coin-1", 1);
        verify(topicRouteRuntimeService, never()).recordPassed(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void diceWritesPipSum() {
        act(DICE, "{}", "dice-1", "ROLL_DICE", payload());   // random 恒 0 → 1 点
        verify(topicRouteRuntimeService).recordOutcome(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "dice-1", 1);
    }

    @Test
    void drawWritesCardOrdinalInPool() {
        act(DRAW, "{}", "draw-1", "DRAW", payload());   // random 恒 0 → 卡池第一张
        verify(topicRouteRuntimeService).recordOutcome(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID, "draw-1", 1);
    }

    /** 负控:判定型动作不写 outcome(sys.outcome 仍只属分支玩法与随机类)。 */
    @Test
    void judgedActionWritesNoOutcome() {
        act(ESTIMATE, "{}", "est-x", "SUBMIT_ESTIMATE", payload("value", 100));
        verify(topicRouteRuntimeService, never()).recordOutcome(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyInt());
    }

    private void act(String config, String state, String key, String action, Map<String, Object> payload) {
        GameRuntimeSession session = runningSession(config, state);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, key, action, payload);
    }

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
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
