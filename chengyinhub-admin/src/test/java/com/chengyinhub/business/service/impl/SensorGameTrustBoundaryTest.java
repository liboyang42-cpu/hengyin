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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1 传感器小游戏的可信边界(选项 A):设备自报成绩只算娱乐成绩(奖励封顶探索值+徽章);
 * 每次开表发服务端挑战身份(attemptId),绑本人+本局+本节点、单次消费、会过期;
 * 拍照兜底放行与静态节点码不算可信完成。
 */
class SensorGameTrustBoundaryTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long TEAMMATE_ID = 8L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long FIRST_ATTEMPT = 4_242_001L;

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(NOW);
    private final AtomicLong nextAttempt = new AtomicLong(FIRST_ATTEMPT);
    private GameRuntimeSessionMapper sessionMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.install(activityMapper, registrationMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(now.get()));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        ReflectionTestUtils.setField(service, "attemptIdSource", (LongSupplier) nextAttempt::getAndIncrement);

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
        when(teamMapper.hasPaidRegistration(TEAMMATE_ID, 2, ACTIVITY_ID)).thenReturn(1);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    @Test
    void everySelfReportedSensorGameCapsNodeRewardsToXpAndBadge() throws Exception {
        for (String section : Arrays.asList("reaction", "ballShake", "quietHold", "shout", "stopwatch", "compass")) {
            List<String> reasons = AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                    json.readTree("{\"" + section + "\":{\"enabled\":true}}"), json.readTree("{}"));
            assertEquals(Collections.singletonList(section + ":SELF_REPORTED"), reasons);
        }
        // 服务端自己计时 / 服务端判内容的玩法不受影响 —— 这些存量主题的奖励口径一字不改
        assertTrue(AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree("{\"countdown\":{\"enabled\":true},\"qa\":{\"enabled\":true},\"typeIn\":{\"enabled\":true}}"),
                json.readTree("{}")).isEmpty());
    }

    @Test
    void startChallengeIssuesAFreshMemberBoundAttemptEachTime() throws Exception {
        GameRuntimeSession session = session(stopwatchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> first = playKit(service.act(MEMBER_ID, 51L, 0, "sw-start-1", "START_CHALLENGE", game("stopwatch")), "stopwatch");
        assertEquals(FIRST_ATTEMPT, ((Number) first.get("attemptId")).longValue());
        assertEquals("SELF_REPORTED", first.get("evidence"));
        assertEquals("XP_BADGE_ONLY", first.get("rewardCeiling"));
        session.setVersion(0);
        Map<String, Object> second = playKit(service.act(MEMBER_ID, 51L, 0, "sw-start-2", "START_CHALLENGE", game("stopwatch")), "stopwatch");
        assertEquals(FIRST_ATTEMPT + 1, ((Number) second.get("attemptId")).longValue());

        JsonNode attempt = json.readTree(session.getStateJson()).path("stopwatch").path("attempt");
        assertEquals(MEMBER_ID, attempt.path("memberId").asLong());
        assertEquals(NOW, attempt.path("issuedAtMs").asLong());
        assertTrue(attempt.path("expiresAtMs").asLong() > NOW + 10_000L, "到期留足配置时长");
        assertFalse(attempt.path("used").asBoolean());
    }

    @Test
    void serverTimedChallengeGetsNoAttemptAndKeepsFullCeiling() {
        GameRuntimeSession session = session("{\"schemaVersion\":1,\"countdown\":{\"enabled\":true,\"seconds\":5,\"xp\":5}}", "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> countdown = playKit(service.act(MEMBER_ID, 51L, 0, "cd-start", "START_CHALLENGE", game("countdown")), "countdown");

        assertNull(countdown.get("attemptId"));
        assertEquals("SERVER_TIMED", countdown.get("evidence"));
        assertEquals("FULL", countdown.get("rewardCeiling"));
    }

    @Test
    void staleAttemptAfterRestartCannotSettle() {
        GameRuntimeSession session = session(stopwatchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, "sw-start-1", "START_CHALLENGE", game("stopwatch"));
        session.setVersion(0);
        service.act(MEMBER_ID, 51L, 0, "sw-start-2", "START_CHALLENGE", game("stopwatch"));
        session.setVersion(0);
        now.addAndGet(10_000L);

        // 后台切走/重开之前那一次的成绩:不白赚
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "sw-stale", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));
        assertEquals("这一次挑战已经作废，请重新开始", stale.getMessage());
        verify(sessionMapper, times(2)).updateStateCas(any());   // 只有两次开表落了库,作废的那次提交没落
    }

    @Test
    void replayedAttemptIsSingleUse() throws Exception {
        GameRuntimeSession session = session(reactionConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, "rx-start", "START_CHALLENGE", game("reaction"));
        session.setVersion(1);
        now.addAndGet(9_000L);

        Map<String, Object> rx = playKit(service.act(MEMBER_ID, 51L, 1, "rx-1", "SUBMIT_REACTION", reaction(FIRST_ATTEMPT)), "reaction");
        assertEquals(true, rx.get("submitted"));
        assertNull(rx.get("attemptId"), "交过的挑战身份不再下发");
        assertTrue(json.readTree(session.getStateJson()).path("reaction").path("attempt").path("used").asBoolean());

        session.setVersion(2);
        IllegalStateException replay = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 2, "rx-2", "SUBMIT_REACTION", reaction(FIRST_ATTEMPT)));
        assertEquals("这一次挑战已经交过了，请重新开始", replay.getMessage());
    }

    @Test
    void stopwatchMissConsumesTheAttemptSoTheNextTryNeedsANewStart() throws Exception {
        GameRuntimeSession session = session(stopwatchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, "sw-start", "START_CHALLENGE", game("stopwatch"));
        session.setVersion(0);
        now.addAndGet(5_000L);
        Map<String, Object> miss = new LinkedHashMap<>(stopwatch(FIRST_ATTEMPT));
        miss.put("stoppedMs", 4_000);
        service.act(MEMBER_ID, 51L, 0, "sw-miss", "SUBMIT_STOPWATCH", miss);
        session.setVersion(0);

        Map<String, Object> retry = playKit(service.act(MEMBER_ID, 51L, 0, "sw-restart", "START_CHALLENGE", game("stopwatch")), "stopwatch");
        assertEquals(FIRST_ATTEMPT + 1, ((Number) retry.get("attemptId")).longValue());
        assertEquals(1, json.readTree(session.getStateJson()).path("stopwatch").path("attempts").asInt());
    }

    @Test
    void expiredAttemptCannotSettle() {
        GameRuntimeSession session = session(quietConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, "qh-start", "START_CHALLENGE", game("quietHold"));
        session.setVersion(0);
        now.addAndGet(30L * 60L * 1000L);   // 半小时后才交:超过配置时长 + 余量(活动还没结束)

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 5_000);
        payload.put("attemptId", FIRST_ATTEMPT);
        IllegalStateException expired = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "qh-late", "SUBMIT_QUIET_HOLD", payload));
        assertEquals("这一次挑战已经过期，请重新开始", expired.getMessage());
        assertNull(playKit(service.state(MEMBER_ID, 51L), "quietHold").get("attemptId"), "过期的挑战身份不再下发");
    }

    @Test
    void foreignAttemptIdIsRejectedAndNeverProjectedToAnotherMember() {
        // 同一局对两名队友都可访问(团局):成员 7 开的表,成员 8 拿它的 attemptId 来交
        GameRuntimeSession session = session(reactionConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.selectAccessibleById(51L, TEAMMATE_ID)).thenReturn(session);
        service.act(MEMBER_ID, 51L, 0, "rx-start", "START_CHALLENGE", game("reaction"));
        session.setVersion(1);
        now.addAndGet(9_000L);

        assertNull(playKit(service.state(TEAMMATE_ID, 51L), "reaction").get("attemptId"), "别人的挑战身份不下发");
        IllegalStateException foreign = assertThrows(IllegalStateException.class,
                () -> service.act(TEAMMATE_ID, 51L, 1, "rx-foreign", "SUBMIT_REACTION", reaction(FIRST_ATTEMPT)));
        assertEquals("这一次挑战已经作废，请重新开始", foreign.getMessage());
        // 编造一个从没发过的号也一样
        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 1, "rx-forged", "SUBMIT_REACTION", reaction(FIRST_ATTEMPT + 99)));
        assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 1, "rx-garbage", "SUBMIT_REACTION", reaction("abc")));
    }

    @Test
    void legacyClientWithoutAttemptIdStillPlaysButRewardStaysCapped() {
        // 存量客户端不带 attemptId:照原复核口径能交、能过关 —— 奖励上限在完成关卡那一步封顶
        GameRuntimeSession session = session(reactionConfig(), "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", Arrays.asList(121, 121, 121));   // 改出来的「神反应」,时间上对得上

        Map<String, Object> rx = playKit(service.act(MEMBER_ID, 51L, 0, "rx-legacy", "SUBMIT_REACTION", payload), "reaction");

        assertEquals(true, rx.get("passed"));
        assertTrue(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
    }

    @Test
    void compassCanIssueAnAttemptAndStaysXpOnly() {
        GameRuntimeSession session = session("{\"schemaVersion\":1,\"compass\":{\"enabled\":true,\"bearing\":90,\"tolerance\":15,\"holdSeconds\":3,\"xp\":5}}", "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> compass = playKit(service.act(MEMBER_ID, 51L, 0, "cp-start", "START_CHALLENGE", game("compass")), "compass");
        assertEquals(FIRST_ATTEMPT, ((Number) compass.get("attemptId")).longValue());
        assertEquals("XP_BADGE_ONLY", compass.get("rewardCeiling"));

        session.setVersion(1);
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("bearing", 92);
        wrong.put("attemptId", FIRST_ATTEMPT + 5);
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 1, "cp-forged", "SUBMIT_COMPASS", wrong));
        wrong.put("attemptId", FIRST_ATTEMPT);
        assertEquals(true, playKit(service.act(MEMBER_ID, 51L, 1, "cp-ok", "SUBMIT_COMPASS", wrong), "compass").get("done"));
    }

    @Test
    void photoFallbackPassIsNotAServerPhotoVerdict() throws Exception {
        String config = "{\"photoCheck\":{\"enabled\":true}}";
        assertEquals(Collections.singletonList("photoCheck:FALLBACK"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree(config), json.readTree("{\"photoCheck\":{\"passed\":true,\"flagged\":true,\"verdict\":\"FALLBACK_PASS\"}}")));
        assertEquals(Collections.singletonList("photoCheck:FALLBACK"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
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
    void realPhotoPassIsVerifiedAndUnsubmittedPhotoPromisesNothing() {
        GameRuntimeSession verified = session("{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,\"requirement\":\"招牌\"}}",
                "{\"photoCheck\":{\"passed\":true,\"verdict\":\"PASS\",\"tries\":1}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(verified);
        Map<String, Object> photo = playKit(service.state(MEMBER_ID, 51L), "photoCheck");
        assertEquals(true, photo.get("photoVerified"));
        assertEquals("FULL", photo.get("rewardCeiling"));

        GameRuntimeSession fresh = session("{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,\"requirement\":\"招牌\"}}", "{}");
        when(sessionMapper.selectAccessibleById(52L, MEMBER_ID)).thenReturn(fresh);
        fresh.setId(52L);
        Map<String, Object> pending = playKit(service.state(MEMBER_ID, 52L), "photoCheck");
        assertEquals(false, pending.get("photoVerified"));
        assertFalse(pending.containsKey("rewardCeiling"), "还没结果就不预判奖励");
    }

    @Test
    void staticNodeCodeIsOnlyATaskCodeMatch() throws Exception {
        assertEquals(Collections.singletonList("scan:TASK_CODE_MATCH"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree("{\"scan\":{\"enabled\":true}}"), json.readTree("{\"scan\":{\"scanned\":true}}")));
        GameRuntimeSession session = session("{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"hi\"}}", "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> scan = playKit(service.state(MEMBER_ID, 51L), "scan");
        assertEquals("TASK_CODE_MATCH", scan.get("evidence"));
        assertEquals("XP_BADGE_ONLY", scan.get("rewardCeiling"));
    }

    @Test
    void trustedOnlyNodeAndMissingSessionAreNotCapped() {
        // 查不到本人局时不改调用方的奖励口径;只有服务端可判玩法的局也不封顶
        assertFalse(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertFalse(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID + 1, NODE_ID));
        GameRuntimeSession countdownOnly = session("{\"schemaVersion\":1,\"countdown\":{\"enabled\":true,\"seconds\":5}}",
                "{\"countdown\":{\"submitted\":true,\"passed\":true}}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(countdownOnly);
        assertFalse(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
    }

    private Map<String, Object> game(String game) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("game", game);
        return payload;
    }

    private Map<String, Object> reaction(Object attemptId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", Arrays.asList(250, 260, 270));
        payload.put("attemptId", attemptId);
        return payload;
    }

    private Map<String, Object> stopwatch(long attemptId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stoppedMs", 10_000);
        payload.put("attemptId", attemptId);
        return payload;
    }

    private String reactionConfig() {
        return "{\"schemaVersion\":1,\"reaction\":{\"enabled\":true,\"rounds\":3,\"goalMs\":320,\"xp\":10}}";
    }

    private String stopwatchConfig() {
        return "{\"schemaVersion\":1,\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,\"toleranceMs\":300,\"tries\":3,\"xp\":10}}";
    }

    private String quietConfig() {
        return "{\"schemaVersion\":1,\"quietHold\":{\"enabled\":true,\"seconds\":5,\"xp\":10}}";
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
