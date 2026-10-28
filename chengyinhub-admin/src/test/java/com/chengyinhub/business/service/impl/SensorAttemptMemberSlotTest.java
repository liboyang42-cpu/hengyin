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
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lane BF4(W48R SHOULD-FIX 2/3):传感器挑战身份按成员分槽。
 * <ul>
 *   <li>归零挑战:开表即按成员存一份(zeroChallengeAttempts),只绑本人、会过期、交过即移除;
 *       存量客户端不带 attemptId 照常能交,但该局证据仍是设备自报 ⇒ 奖励口径封顶。</li>
 *   <li>队伍共享局:两名队友各开各的表,谁也不作废谁的号,也拿不到/用不了对方的号。</li>
 * </ul>
 */
class SensorAttemptMemberSlotTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_A = 7L;
    private static final long MEMBER_B = 8L;
    private static final long TEAM_ID = 66L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long FIRST_ATTEMPT = 5_151_001L;
    private static final String STOPWATCH = "{\"schemaVersion\":1,\"stopwatch\":{\"enabled\":true,\"targetSeconds\":10,"
            + "\"toleranceMs\":300,\"tries\":3,\"xp\":10}}";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(NOW);
    private final AtomicLong nextAttempt = new AtomicLong(FIRST_ATTEMPT);
    private GameRuntimeSessionMapper sessionMapper;
    private ITopicRouteRuntimeService route;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
        route = mock(ITopicRouteRuntimeService.class);
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
        when(teamMapper.hasPaidRegistration(MEMBER_A, 2, ACTIVITY_ID)).thenReturn(1);
        when(teamMapper.hasPaidRegistration(MEMBER_B, 2, ACTIVITY_ID)).thenReturn(1);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    /* ==================== 归零挑战:分成员 attempt 路径 ==================== */

    @Test
    void zeroChallengeIssuesAttemptIntoTheStartersOwnSlot() throws Exception {
        GameRuntimeSession session = zeroChallengeSession();

        Map<String, Object> started = stopwatchKit(service.act(MEMBER_A, 51L, 0, "a-start", "START_CHALLENGE", game()));

        assertEquals(FIRST_ATTEMPT, ((Number) started.get("attemptId")).longValue());
        JsonNode own = json.readTree(session.getStateJson()).path("zeroChallengeAttempts").path(String.valueOf(MEMBER_A)).path("stopwatch");
        assertEquals(FIRST_ATTEMPT, own.path("id").asLong());
        assertEquals(MEMBER_A, own.path("memberId").asLong());
        assertFalse(own.path("used").asBoolean());
        assertTrue(own.path("expiresAtMs").asLong() > NOW + 10_000L);
    }

    @Test
    void zeroChallengeAttemptIsBoundToItsMemberAndSurvivesTeammateStart() throws Exception {
        GameRuntimeSession session = zeroChallengeSession();
        service.act(MEMBER_A, 51L, 0, "a-start", "START_CHALLENGE", game());
        session.setVersion(0);
        service.act(MEMBER_B, 51L, 0, "b-start", "START_CHALLENGE", game());
        session.setVersion(0);

        // 共享段上现在是 B 的号,但 A 的投影仍是 A 自己那份;B 看不到 A 的号
        assertEquals(FIRST_ATTEMPT, ((Number) stopwatchKit(service.state(MEMBER_A, 51L)).get("attemptId")).longValue());
        assertEquals(FIRST_ATTEMPT + 1, ((Number) stopwatchKit(service.state(MEMBER_B, 51L)).get("attemptId")).longValue());
        now.addAndGet(10_000L);

        IllegalStateException foreign = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_A, 51L, 0, "a-uses-b", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT + 1)));
        assertEquals("这一次挑战已经作废，请重新开始", foreign.getMessage());
        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_B, 51L, 0, "b-uses-a", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));

        // 各交各的号都能结算
        service.act(MEMBER_A, 51L, 0, "a-submit", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT));
        session.setVersion(0);
        service.act(MEMBER_B, 51L, 0, "b-submit", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT + 1));
        JsonNode slots = json.readTree(session.getStateJson()).path("zeroChallengeAttempts");
        assertTrue(slots.path(String.valueOf(MEMBER_A)).path("stopwatch").isMissingNode(), "交过即移除");
        assertTrue(slots.path(String.valueOf(MEMBER_B)).path("stopwatch").isMissingNode(), "交过即移除");
    }

    @Test
    void zeroChallengeAttemptIsSingleUse() {
        GameRuntimeSession session = zeroChallengeSession();
        service.act(MEMBER_A, 51L, 0, "a-start", "START_CHALLENGE", game());
        session.setVersion(0);
        now.addAndGet(10_000L);
        service.act(MEMBER_A, 51L, 0, "a-submit", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT));
        session.setVersion(0);

        assertNull(stopwatchKit(service.state(MEMBER_A, 51L)).get("attemptId"), "交过的号不再下发");
        IllegalStateException replay = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_A, 51L, 0, "a-replay", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));
        assertEquals("这一次挑战已经作废，请重新开始", replay.getMessage());
    }

    @Test
    void zeroChallengeAttemptExpires() {
        GameRuntimeSession session = zeroChallengeSession();
        service.act(MEMBER_A, 51L, 0, "a-start", "START_CHALLENGE", game());
        session.setVersion(0);
        now.addAndGet(30L * 60L * 1000L);   // 超过配置时长 + 10 分钟余量,活动仍在进行

        IllegalStateException expired = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_A, 51L, 0, "a-late", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));
        assertEquals("这一次挑战已经过期，请重新开始", expired.getMessage());
        assertNull(stopwatchKit(service.state(MEMBER_A, 51L)).get("attemptId"), "过期的号不再下发");
    }

    @Test
    void zeroChallengeLegacyClientWithoutAttemptIdStillPlaysButEvidenceStaysCapped() throws Exception {
        GameRuntimeSession session = zeroChallengeSession();
        service.act(MEMBER_A, 51L, 0, "a-start", "START_CHALLENGE", game());
        session.setVersion(0);
        now.addAndGet(10_000L);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("stoppedMs", 10_000);

        Map<String, Object> kit = stopwatchKit(service.act(MEMBER_A, 51L, 0, "a-legacy", "SUBMIT_STOPWATCH", legacy));

        assertEquals(true, kit.get("passed"));
        assertEquals("XP_BADGE_ONLY", kit.get("rewardCeiling"));
        assertEquals(Collections.singletonList("stopwatch:SELF_REPORTED"), AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(
                json.readTree(session.getConfigSnapshotJson()), json.readTree(session.getStateJson())));
    }

    /* ==================== 队伍共享局:每名成员一个槽位 ==================== */

    @Test
    void teamSharedSessionGivesEachMemberTheirOwnAttemptSlot() throws Exception {
        GameRuntimeSession session = teamSession();
        service.act(MEMBER_A, 51L, 0, "a-start", "START_CHALLENGE", game());
        session.setVersion(0);
        now.addAndGet(1_000L);
        service.act(MEMBER_B, 51L, 0, "b-start", "START_CHALLENGE", game());
        session.setVersion(0);

        // 队友后开表不再作废本人手里的号;各自只看得到自己的
        assertEquals(FIRST_ATTEMPT, ((Number) stopwatchKit(service.state(MEMBER_A, 51L)).get("attemptId")).longValue());
        assertEquals(FIRST_ATTEMPT + 1, ((Number) stopwatchKit(service.state(MEMBER_B, 51L)).get("attemptId")).longValue());
        now.addAndGet(9_000L);   // A 开表后 10s、B 开表后 9s

        // 谁也用不了对方的号
        IllegalStateException aUsesB = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_A, 51L, 0, "a-uses-b", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT + 1)));
        assertEquals("这一次挑战已经作废，请重新开始", aUsesB.getMessage());
        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_B, 51L, 0, "b-uses-a", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));

        // A 交一次(没停准,还有次数):按 A 自己的开表时间复核,只消费 A 的号
        Map<String, Object> aMiss = new LinkedHashMap<>(stopwatch(FIRST_ATTEMPT));
        aMiss.put("stoppedMs", 9_000);
        service.act(MEMBER_A, 51L, 0, "a-submit", "SUBMIT_STOPWATCH", aMiss);
        session.setVersion(0);
        IllegalStateException aReplay = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_A, 51L, 0, "a-replay", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));
        assertEquals("这一次挑战已经交过了，请重新开始", aReplay.getMessage());
        // A 用过的号,B 拿去交也不行
        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_B, 51L, 0, "b-uses-used-a", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));

        // B 的号与开表时间没被 A 的提交动过,照样能结算一次
        Map<String, Object> bHit = new LinkedHashMap<>(stopwatch(FIRST_ATTEMPT + 1));
        bHit.put("stoppedMs", 9_000);
        Map<String, Object> afterB = stopwatchKit(service.act(MEMBER_B, 51L, 0, "b-submit", "SUBMIT_STOPWATCH", bHit));
        assertEquals(2, ((Number) afterB.get("attempts")).intValue(), "次数仍是全队共享口径");
        session.setVersion(0);
        IllegalStateException bReplay = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_B, 51L, 0, "b-replay", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT + 1)));
        assertEquals("这一次挑战已经交过了，请重新开始", bReplay.getMessage());

        JsonNode slots = json.readTree(session.getStateJson()).path("teamMemberChallenges");
        assertEquals(MEMBER_A, slots.path(String.valueOf(MEMBER_A)).path("stopwatch").path("attempt").path("memberId").asLong());
        assertEquals(MEMBER_B, slots.path(String.valueOf(MEMBER_B)).path("stopwatch").path("attempt").path("memberId").asLong());
    }

    @Test
    void teamSessionStartedBeforeUpgradeKeepsTheSharedSlotBehaviour() {
        // 升级前已开的表(只有共享段上的号、没有按成员的槽):原口径照旧可交,别人照旧用不了
        GameRuntimeSession session = teamSession();
        session.setStateJson("{\"stopwatch\":{\"startedAtMs\":" + NOW + ",\"attempts\":0,\"attempt\":{\"id\":" + FIRST_ATTEMPT
                + ",\"memberId\":" + MEMBER_A + ",\"issuedAtMs\":" + NOW + ",\"expiresAtMs\":" + (NOW + 700_000L) + ",\"used\":false}}}");
        now.addAndGet(10_000L);

        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_B, 51L, 0, "b-uses-a", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));
        Map<String, Object> kit = stopwatchKit(service.act(MEMBER_A, 51L, 0, "a-submit", "SUBMIT_STOPWATCH", stopwatch(FIRST_ATTEMPT)));
        assertEquals(true, kit.get("passed"));
    }

    private GameRuntimeSession zeroChallengeSession() {
        GameRuntimeSession session = baseSession();
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_A);
        session.setPlayInstanceId("challenge:one");
        session.setStateJson("{\"originalRouteSessionId\":88,\"zeroChallengeInstance\":\"one\",\"challenge\":{\"instanceId\":\"one\"}}");
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        for (long member : new long[]{MEMBER_A, MEMBER_B}) {
            when(route.genericPlayer(member, TOPIC_ID, ACTIVITY_ID)).thenReturn(true);
            when(route.canPlay(member, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
            when(route.gameplayInstance(member, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn("challenge:one");
        }
        // 未通关、不要求重来:局保持进行中,好让同一局里继续验后续提交
        when(route.recordZeroChallengeResult(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(json.createObjectNode().put("instanceId", "one").put("success", false).put("retry", false));
        when(sessionMapper.selectAccessibleById(51L, MEMBER_A)).thenReturn(session);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_B)).thenReturn(session);
        return session;
    }

    private GameRuntimeSession teamSession() {
        GameRuntimeSession session = baseSession();
        session.setOwnerType(GameRuntimeSession.OWNER_TEAM);
        session.setOwnerId(TEAM_ID);
        session.setPlayInstanceId("team:one");
        session.setStateJson("{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_A)).thenReturn(session);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_B)).thenReturn(session);
        return session;
    }

    private GameRuntimeSession baseSession() {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(51L);
        session.setActivityId(ACTIVITY_ID);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(39L);
        session.setConfigSnapshotJson(STOPWATCH);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStartedAt(new Date(NOW));
        return session;
    }

    private Map<String, Object> game() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("game", "stopwatch");
        return payload;
    }

    private Map<String, Object> stopwatch(long attemptId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stoppedMs", 10_000);
        payload.put("attemptId", attemptId);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stopwatchKit(Map<String, Object> view) {
        Map<String, Object> kit = (Map<String, Object>) view.get("playKit");
        return kit == null ? null : (Map<String, Object>) kit.get("stopwatch");
    }
}
