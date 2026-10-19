package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1 传感器小游戏的可信边界:设备自报成绩只算娱乐成绩(奖励封顶探索值+徽章),
 * 每次开表有服务端挑战身份,旧 attempt 不能结算;拍照旧兜底与静态码不算可信完成。
 */
class SensorGameTrustBoundaryTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;

    private final ObjectMapper json = new ObjectMapper();
    private GameRuntimeSessionMapper sessionMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(39L);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        activity.setProductType(1);
        activity.setStartDate(new Date(NOW - 60_000));
        activity.setEndDate(new Date(NOW + 3_600_000));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    @Test
    void everySelfReportedSensorGameCapsNodeRewardsToXpAndBadge() throws Exception {
        for (String section : Arrays.asList("reaction", "ballShake", "quietHold", "shout", "stopwatch", "compass")) {
            List<String> reasons = AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                    json.readTree("{\"" + section + "\":{\"enabled\":true}}"), json.readTree("{}"));
            assertEquals(Arrays.asList(section + ":SELF_REPORTED"), reasons);
        }
        // 服务端自己计时 / 服务端判内容的玩法不受影响
        assertTrue(AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree("{\"countdown\":{\"enabled\":true},\"qa\":{\"enabled\":true}}"), json.readTree("{}")).isEmpty());
    }

    @Test
    void tamperedReactionScoreStillCompletesButCannotUnlockFullRewards() {
        GameRuntimeSession session = session(reactionConfig(), "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + ",\"attemptId\":1}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", Arrays.asList(121, 121, 121));   // 改出来的「神反应」,时间上对得上
        payload.put("attemptId", 1);

        Map<String, Object> rx = playKit(service.act(MEMBER_ID, 51L, 0, "rx-tamper", "SUBMIT_REACTION", payload), "reaction");

        assertEquals(true, rx.get("passed"));
        assertEquals("SELF_REPORTED", rx.get("evidence"));
        assertEquals("XP_BADGE_ONLY", rx.get("rewardCeiling"));
        assertTrue(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
    }

    @Test
    void staleAttemptCannotSettleAndCurrentAttemptSettlesOnce() {
        GameRuntimeSession session = session(reactionConfig(), "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + ",\"attemptId\":2}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", Arrays.asList(250, 260, 270));
        payload.put("attemptId", 1);

        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "rx-stale", "SUBMIT_REACTION", payload));
        assertEquals("这一次挑战已经作废，请重新开始", stale.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());

        payload.put("attemptId", 2);
        assertEquals(true, playKit(service.act(MEMBER_ID, 51L, 0, "rx-current", "SUBMIT_REACTION", payload), "reaction").get("submitted"));
        session.setVersion(0);
        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "rx-again", "SUBMIT_REACTION", payload));
        assertEquals("这一局已经交过了", again.getMessage());
    }

    @Test
    void startChallengeIssuesANewServerAttemptEachTime() throws Exception {
        GameRuntimeSession session = session(stopwatchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("game", "stopwatch");
        Map<String, Object> first = playKit(service.act(MEMBER_ID, 51L, 0, "sw-start-1", "START_CHALLENGE", start), "stopwatch");
        assertEquals(1L, ((Number) first.get("attemptId")).longValue());
        session.setVersion(0);
        Map<String, Object> second = playKit(service.act(MEMBER_ID, 51L, 0, "sw-start-2", "START_CHALLENGE", start), "stopwatch");
        assertEquals(2L, ((Number) second.get("attemptId")).longValue());
        assertEquals(2, json.readTree(session.getStateJson()).path("stopwatch").path("attemptId").asInt());

        // 暂停后台后拿第一次开表的成绩来交:旧 attempt 不白赚
        session.setVersion(0);
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("stoppedMs", 10_000);
        replay.put("attemptId", 1);
        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "sw-replay", "SUBMIT_STOPWATCH", replay));
    }

    @Test
    void photoFallbackPassIsNotARealPhotoVerdict() throws Exception {
        String config = "{\"photoCheck\":{\"enabled\":true}}";
        assertEquals(Arrays.asList("photoCheck:FALLBACK"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree(config), json.readTree("{\"photoCheck\":{\"passed\":true,\"flagged\":true,\"verdict\":\"FALLBACK_PASS\"}}")));
        assertEquals(Arrays.asList("photoCheck:FALLBACK"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree(config), json.readTree("{\"photoCheck\":{\"passed\":true,\"degraded\":true,\"verdict\":\"DEGRADED\"}}")));
        assertTrue(AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree(config), json.readTree("{\"photoCheck\":{\"passed\":true,\"verdict\":\"PASS\"}}")).isEmpty());

        GameRuntimeSession session = session("{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,\"requirement\":\"招牌\",\"fallback\":\"pass\"}}",
                "{\"photoCheck\":{\"passed\":true,\"flagged\":true,\"verdict\":\"FALLBACK_PASS\",\"tries\":1}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> photo = playKit(service.state(MEMBER_ID, 51L), "photoCheck");
        assertEquals(false, photo.get("photoVerified"));
        assertEquals("FALLBACK_PASS", photo.get("verdict"));
        assertEquals("XP_BADGE_ONLY", photo.get("rewardCeiling"));
    }

    @Test
    void staticNodeCodeIsOnlyATaskCodeMatch() throws Exception {
        assertEquals(Arrays.asList("scan:TASK_CODE_MATCH"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree("{\"scan\":{\"enabled\":true}}"), json.readTree("{\"scan\":{\"scanned\":true}}")));
        GameRuntimeSession session = session("{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"hi\"}}", "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> scan = playKit(service.state(MEMBER_ID, 51L), "scan");
        assertEquals("TASK_CODE_MATCH", scan.get("evidence"));
    }

    @Test
    void otherMembersSessionIsNeverConsultedAndMissingSessionIsNotCapped() {
        // 只按 memberId 取本人局;查不到局时不改调用方的奖励口径
        assertFalse(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertFalse(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID + 1, NODE_ID));
    }

    private String reactionConfig() {
        return "{\"schemaVersion\":1,\"reaction\":{\"enabled\":true,\"rounds\":3,\"goalMs\":320,\"xp\":10}}";
    }

    private String stopwatchConfig() {
        return "{\"schemaVersion\":1,\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,\"toleranceMs\":300,\"tries\":3,\"xp\":10}}";
    }

    private GameRuntimeSession session(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(51L);
        session.setActivityId(ACTIVITY_ID);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(39L);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStartedAt(new Date(NOW));
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> playKit(Map<String, Object> view, String game) {
        Map<String, Object> kit = (Map<String, Object>) view.get("playKit");
        return kit == null ? null : (Map<String, Object>) kit.get(game);
    }
}
