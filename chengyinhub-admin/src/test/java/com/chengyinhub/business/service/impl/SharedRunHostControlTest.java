package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 现场主持台（X028/X030/X031/X032）服务级断言：暂停冻结、中止不计结局、到达核验/撤销审计、
 * 迁点保留任务、指导完成同奖励、队伍退出不征用物资、导出最小化。
 */
class SharedRunHostControlTest {
    private final ObjectMapper json = new ObjectMapper();
    private final long base = System.currentTimeMillis();
    private final long[] now = {base};
    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper mapper;
    private ITopicRouteRuntimeService route;
    private GameSession session;
    private GameStoryState owner, run;
    private GameStationRun station;
    private final Map<String, GameCommandReceipt> receipts = new HashMap<>();

    @BeforeEach void setUp() throws Exception {
        service = new GameSessionRuntimeServiceImpl(); mapper = mock(GameSessionRuntimeMapper.class);
        PlayTeamMapper teams = mock(PlayTeamMapper.class); route = mock(ITopicRouteRuntimeService.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        IPlayerNodeProgressService progress = mock(IPlayerNodeProgressService.class);
        ReflectionTestUtils.setField(service, "runtimeMapper", mapper); ReflectionTestUtils.setField(service, "playTeamMapper", teams);
        ReflectionTestUtils.setField(service, "activityMapper", activities); ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        ReflectionTestUtils.setField(service, "playerNodeProgressService", progress);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(now[0]));
        CmsActivity activity = new CmsActivity(); activity.setId(70L); activity.setTopicId(80L); activity.setClubId(40L);
        activity.setStatus(1L); activity.setDelFlag(0); activity.setStartDate(new Date(base - 3600000)); activity.setEndDate(new Date(base + 3600000));
        when(activities.selectCmsActivityById(70L)).thenReturn(activity);
        session = new GameSession(); session.setId(50L); session.setActivityId(70L); session.setTopicId(80L); session.setStatus("RUNNING"); session.setRevision(0);
        ObjectNode snapshot = json.createObjectNode().put("playMode", "ROUTE").put("runtimeSnapshotVersion", 1).put("startNodeId", 11);
        snapshot.putArray("terminalNodeIds").add(12); snapshot.set("journeyRules", json.readTree(GenericRouteRuntimeServiceTest.rules()));
        ObjectNode theme = (ObjectNode) json.readTree(GenericRouteRuntimeServiceTest.config());
        ((ObjectNode) theme.path("gameplayRuntime").path("quests").path(0)).putArray("approvedFallbackNodeIds").add(21);
        snapshot.set("themeConfig", theme);
        session.setModuleConfigSnapshotJson(snapshot.toString());
        when(mapper.selectSessionByActivity(70L)).thenReturn(session); when(mapper.selectSessionByActivityForUpdate(70L)).thenReturn(session);
        when(teams.hasPaidRegistration(anyLong(), eq(2), eq(70L))).thenReturn(1);
        when(mapper.selectActiveTeamId(eq(70L), anyLong())).thenReturn(90L);
        when(mapper.isActiveTeam(70L, 90L)).thenReturn(1);
        PlayTeam team = new PlayTeam(); team.setId(90L); team.setOwnerType(2); team.setOwnerId(70L); team.setLeaderMemberId(7L); team.setStatus(2);
        when(teams.selectPlayTeamById(90L)).thenReturn(team); when(teams.selectPlayTeamByIdForUpdate(90L)).thenReturn(team);
        owner = new GameStoryState(); owner.setId(91L); owner.setGameSessionId(50L); owner.setOwnerType("TEAM"); owner.setOwnerId(90L); owner.setRevision(0); owner.setStatus("INITIAL");
        TopicRouteEngine.State state = new TopicRouteEngine().initial(snapshot.toString());
        state.getVariables().put("sys.hp", 10); JourneyInitialState.initialize(JourneyRules.parse(GenericRouteRuntimeServiceTest.rules()), state);
        new ThemeRuntimeEngine().initialize(snapshot.path("themeConfig"), state);
        state.getThemeRuntime().with("inventory").put("cold", 1); state.getThemeRuntime().with("inventory").put("food", 1);
        state.getThemeRuntime().put("authorityEpoch", 1); state.getThemeRuntime().with("gameplay").put("actIndex", 0);
        owner.setVariablesJson(json.writeValueAsString(state)); when(mapper.selectStoryState(50L, "TEAM", 90L)).thenReturn(owner);
        run = new GameStoryState(); run.setId(92L); run.setGameSessionId(50L); run.setOwnerType("RUN"); run.setOwnerId(50L); run.setRevision(0);
        run.setVariablesJson("{\"operationStatus\":\"RUNNING\",\"authorityMode\":\"ONLINE\",\"authorityEpoch\":1}");
        when(mapper.selectStoryState(50L, "RUN", 50L)).thenReturn(run); when(mapper.selectStoryStates(50L)).thenReturn(Collections.singletonList(owner));
        when(mapper.updateStoryStateCas(any())).thenAnswer(call -> { GameStoryState saved = call.getArgument(0); saved.setRevision(saved.getRevision() + 1); return 1; });
        when(mapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.selectReceipt(eq(70L), anyLong(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(2)));
        when(mapper.selectReceiptForUpdate(eq(70L), anyLong(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(2)));
        when(mapper.insertReceiptClaim(any())).thenAnswer(call -> { GameCommandReceipt receipt = call.getArgument(0); receipt.setId((long) receipts.size() + 1); receipts.put(receipt.getRequestId(), receipt); return 1; });
        when(mapper.completeReceipt(any())).thenReturn(1);
        station = new GameStationRun(); station.setGameSessionId(50L); station.setActivityId(70L); station.setNodeId(11L); station.setStatus("ACTIVE");
        station.setServiceStartAt(activity.getStartDate()); station.setServiceEndAt(activity.getEndDate()); station.setSourceType("CHAPTER_NODE");
        when(mapper.selectStation(eq(50L), anyLong())).thenAnswer(call -> { station.setNodeId(call.getArgument(1)); return station; });
        when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call -> { PlayerNodeProgress q = call.getArgument(0); q.setCheckinTime(new Date(now[0])); return Collections.singletonList(q); });
        when(route.trustedChoiceEffects(any(), anyLong(), anyString(), anyString())).thenReturn(json.createArrayNode());
        IClubAccessService access = mock(IClubAccessService.class); ReflectionTestUtils.setField(service, "clubAccessService", access);
        when(access.require(eq(9L), eq(40L), eq(70L), anyString())).thenReturn(new ClubAccessContext(9L, 40L, 9L, 70L, Collections.singleton("CLUB_OWNER")));
    }

    private GameCommandReceiptView command(GameActorContext actor, String id, String action, Object... pairs) {
        GameSessionCommand command = new GameSessionCommand(); command.setActivityId(70L); command.setRequestId(id);
        command.setExpectedRevision(session.getRevision()); command.setAction(action); command.setAuthorityEpoch(1);
        for (int i = 0; i < pairs.length; i += 2) command.getPayload().put((String) pairs[i], pairs[i + 1]);
        return service.command(actor, command);
    }
    private GameCommandReceiptView host(String id, String action, Object... pairs) { return command(GameActorContext.club(9L), id, action, pairs); }
    private GameCommandReceiptView player(String id, String action, Object... pairs) { return command(GameActorContext.player(7L), id, action, pairs); }
    private String rejected(Runnable call) { return assertThrows(GameSessionException.class, call::run).getErrorCode(); }
    private JsonNode stored() throws Exception { return json.readTree(owner.getVariablesJson()); }
    private JsonNode runState() throws Exception { return json.readTree(run.getVariablesJson()); }
    private String instance(long node) { return service.sharedRouteSnapshot(7L, 80L, 70L, node).path("runtimeInstance").asText("game:50"); }
    private void survival(String policy, ObjectNode zero) throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree(session.getModuleConfigSnapshotJson());
        ObjectNode configured = ((ObjectNode) snapshot.path("themeConfig").path("gameplayRuntime")).putObject("survival").put("zeroHpPolicy", policy);
        configured.putObject("zeroWait").put("minutes", 1).put("limit", 2).put("restoreTo", 3);
        session.setModuleConfigSnapshotJson(snapshot.toString());
        ObjectNode state = (ObjectNode) stored(); state.with("variables").put("sys.hp", 0);
        if (zero != null) state.with("themeRuntime").set("zeroHp", zero);
        owner.setVariablesJson(state.toString());
    }

    @Test void pausedRunRejectsPlayerWritesFreezesBalancesAndResumeDefersTimer() throws Exception {
        survival("WAIT", json.createObjectNode().put("version", 1).put("episodeId", "wait-1").put("zeroAt", base).put("used", 0)
                .put("policy", "WAIT").put("effectivePolicy", "WAIT").put("resolved", false).put("phase", "RECOVERING")
                .put("limit", 2).put("restoreTo", 3).put("deadlineAt", base + 60000));
        host("pause", "RUN_PAUSE", "reason", "现场突发，暂停");
        assertEquals("PAUSED", runState().path("operationStatus").asText()); assertEquals(base, runState().path("pausedAt").asLong());
        now[0] = base + 120000;
        String frozen = owner.getVariablesJson(); int receiptCount = receipts.size();
        assertEquals("GAME_RUN_PAUSED", rejected(() -> player("paused-start", "QUEST_START", "questKey", "q1")));
        assertEquals("GAME_RUN_PAUSED", rejected(() -> player("paused-use", "ITEM_USE", "contentId", "food")));
        assertEquals("GAME_RUN_PAUSED", rejected(() -> service.recordSharedRouteResult(7L, 80L, 70L, 11L, "game:50", "paused-proof", true, Collections.emptyList())));
        JsonNode pausedView = json.valueToTree(service.view(GameActorContext.player(7L), 70L).getPlayer());
        assertEquals("PAUSED", pausedView.path("run").path("operationStatus").asText());
        assertEquals(frozen, owner.getVariablesJson(), "暂停中 HP/资源/配给与倒计时都不变，读取也不推进");
        assertEquals(receiptCount, receipts.size(), "被拒的玩家提交不落回执");
        assertEquals("GAME_RUN_PAUSED", rejected(() -> host("paused-unlock", "ACT_UNLOCK", "actKey", "first", "reason", "暂停中开放")));

        host("resume", "RUN_RESUME", "reason", "现场恢复");
        assertEquals("RUNNING", runState().path("operationStatus").asText()); assertFalse(runState().has("pausedAt"));
        assertEquals(120000, runState().path("pausedTotalMs").asLong());
        assertEquals(base + 180000, stored().path("themeRuntime").path("zeroHp").path("deadlineAt").asLong(), "恢复后倒计时整体顺延暂停时长");
        service.view(GameActorContext.player(7L), 70L);
        assertEquals(0, stored().path("variables").path("sys.hp").asInt(), "顺延后的截止前不恢复");
        now[0] = base + 180000;
        service.view(GameActorContext.player(7L), 70L);
        assertTrue(stored().path("variables").path("sys.hp").asInt() > 0, "顺延后的截止到达后按规则恢复");
    }

    @Test void interruptStopsAllSettlementAndNeverRecordsAnEnding() throws Exception {
        player("start", "QUEST_START", "questKey", "q1");
        survival("THEME_FAIL", null);
        JsonNode before = stored();
        host("interrupt", "RUN_INTERRUPT", "reason", "恶劣天气，安全中止");
        assertEquals("INTERRUPTED", runState().path("operationStatus").asText());
        service.view(GameActorContext.player(7L), 70L);
        JsonNode after = stored();
        assertFalse(after.path("themeRuntime").has("result"), "中止不记 HP 归零结局");
        assertNotEquals("ENDED", after.path("status").asText());
        assertFalse(after.path("themeRuntime").path("gameplay").has("activeQuest"), "未执行占用释放");
        assertEquals(before.path("themeRuntime").path("inventory"), after.path("themeRuntime").path("inventory"));
        assertEquals(before.path("variables"), after.path("variables"));
        assertEquals("GAME_RUN_TERMINAL", rejected(() -> host("fallback", "PLAN_FALLBACK", "reason", "想记 END05")));
        assertEquals("GAME_RUN_TERMINAL", rejected(() -> host("resume", "RUN_RESUME", "reason", "恢复")));
        assertEquals("GAME_RUN_TERMINAL", rejected(() -> player("after", "ITEM_USE", "contentId", "food")));
        assertEquals("RUNNING", session.getStatus(), "中止只停运行账，不把场次记为正常结束");
    }

    @Test void arrivalCredentialIsSingleUseCorruptIsRejectedAndRevokeIsAudited() throws Exception {
        station.setSourceType("HOST_NODE");
        String nonce = String.valueOf(player("issue", "ARRIVAL_ISSUE", "questKey", "q1").getResult().get("nonce"));
        assertEquals("GAME_ARRIVAL_INVALID", rejected(() -> host("corrupt", "ARRIVAL_CONFIRM", "teamId", 90, "questKey", "q1", "nonce", "broken")));
        String forged = new String(new char[43]).replace('\0', 'A');
        assertNotEquals(nonce, forged);
        assertEquals("GAME_ARRIVAL_INVALID", rejected(() -> host("unknown", "ARRIVAL_CONFIRM", "teamId", 90, "questKey", "q1", "nonce", forged)));
        host("confirm", "ARRIVAL_CONFIRM", "teamId", 90, "questKey", "q1", "nonce", nonce);
        assertEquals(9, runState().path("arrivals").path("90:park").path("verifiedBy").asLong());
        assertEquals("GAME_ARRIVAL_REPLAY", rejected(() -> host("replay", "ARRIVAL_CONFIRM", "teamId", 90, "questKey", "q1", "nonce", nonce)));
        assertEquals("GAME_FORBIDDEN_SCOPE", rejected(() -> host("paper-online", "ARRIVAL_ATTEST", "teamId", 90, "questKey", "q1", "reason", "无信号")),
                "纸账核验只能在人工账内重放");
        assertEquals("GAME_BAD_REQUEST", rejected(() -> host("revoke-no-reason", "ARRIVAL_REVOKE", "teamId", 90, "questKey", "q1", "reason", " ")));
        host("revoke", "ARRIVAL_REVOKE", "teamId", 90, "questKey", "q1", "reason", "核验错队");
        assertFalse(runState().path("arrivals").has("90:park"));
        JsonNode audit = runState().path("arrivalRevocations").elements().next();
        assertEquals("核验错队", audit.path("reason").asText()); assertEquals(9, audit.path("revokedBy").asLong()); assertEquals(9, audit.path("verifiedBy").asLong());
        assertFalse(audit.toString().contains(nonce));
        assertEquals("GAME_STATE_CONFLICT", rejected(() -> host("revoke-again", "ARRIVAL_REVOKE", "teamId", 90, "questKey", "q1", "reason", "重复撤销")));
    }

    @Test void rebindKeepsQuestIdentityAndSlotWithoutHpPenalty() throws Exception {
        player("start", "QUEST_START", "questKey", "q1");
        JsonNode before = stored();
        assertEquals("GAME_RUN_PAUSED", rejected(() -> host("rebind-running", "LOCATION_REBIND", "questKey", "q1", "nodeId", 21, "reason", "原站关闭")));
        host("pause", "RUN_PAUSE", "reason", "迁点前暂停");
        assertEquals("GAME_LOCATION_NOT_APPROVED", rejected(() -> host("rebind-bad", "LOCATION_REBIND", "questKey", "q1", "nodeId", 99, "reason", "未批准")));
        host("rebind", "LOCATION_REBIND", "questKey", "q1", "nodeId", 21, "reason", "原站关闭");
        assertEquals(21, runState().path("bindings").path("park").path("nodeId").asLong()); assertEquals(2, runState().path("bindings").path("park").path("version").asLong());
        JsonNode after = stored();
        assertEquals(before.path("variables"), after.path("variables"), "迁点不扣 HP/资源");
        assertEquals(before.path("themeRuntime").path("inventory"), after.path("themeRuntime").path("inventory"));
        assertEquals(before.path("completedNodeIds"), after.path("completedNodeIds"));
        host("resume", "RUN_RESUME", "reason", "迁点完成");
        player("restart", "QUEST_START", "questKey", "q1");
        assertEquals("q1", stored().path("themeRuntime").path("gameplay").path("activeQuest").asText(), "任务标识不变");
        JsonNode quest = json.readTree(session.getModuleConfigSnapshotJson()).path("themeConfig").path("gameplayRuntime").path("quests").path(0);
        assertEquals("q1", quest.path("settlementSlot").asText(), "领取槽沿用冻结配置");
    }

    @Test void guidedCompletionPaysTheSameRewardAndRecordsAssistedVerifier() throws Exception {
        when(route.verifyGuidedRouteAnswer(any(), anyLong(), any())).thenReturn(true);
        when(route.trustedGuidedRouteEffects(any(), anyLong(), any())).thenReturn(json.createArrayNode());
        player("start", "QUEST_START", "questKey", "q1");
        assertEquals("GAME_BAD_REQUEST", rejected(() -> host("guide-no-reason", "QUEST_GUIDE", "teamId", 90, "questKey", "q1", "answers", Collections.singletonMap("answer", "x"), "reason", "")));
        host("guide", "QUEST_GUIDE", "teamId", 90, "questKey", "q1", "answers", Collections.singletonMap("answer", "x"), "reason", "队员行动不便，现场口述");
        JsonNode guided = stored().path("themeRuntime").path("guidedNodes").path("11");
        assertTrue(guided.path("assisted").asBoolean()); assertEquals(9, guided.path("verifiedBy").asLong());
        player("resolve", "QUEST_RESOLVE", "questKey", "q1", "choiceKey", "supply");
        // 与 GenericRouteRuntimeServiceTest.onlyFullServerVerdictSettlesOneBusinessSlotAndPaysOnce 的正常路径同值
        assertEquals(8, stored().path("variables").path("sys.hp").asInt()); assertEquals(7, stored().path("variables").path("counter.temperature").asInt());
        assertEquals(1, stored().path("variables").path("sys.actions").asInt()); assertEquals(2, stored().path("themeRuntime").path("inventory").path("food").asInt());
    }

    @Test void staffTeamExitReleasesOnlyReservationsAndKeepsHeldItems() throws Exception {
        player("start", "QUEST_START", "questKey", "q1");
        JsonNode before = stored();
        assertEquals("GAME_FORBIDDEN_SCOPE", rejected(() -> command(GameActorContext.club(8L), "stranger-exit", "STAFF_TEAM_EXIT", "teamId", 90, "reason", "越权")));
        host("exit", "STAFF_TEAM_EXIT", "teamId", 90, "reason", "队员不适，确认退出");
        assertTrue(runState().path("exitedTeams").has("90"));
        JsonNode after = stored();
        assertFalse(after.path("themeRuntime").path("gameplay").has("activeQuest"), "未承诺的任务占用释放");
        assertEquals(before.path("themeRuntime").path("inventory"), after.path("themeRuntime").path("inventory"), "退出队已持有物资不被征用");
        assertEquals(before.path("variables"), after.path("variables"));
        assertEquals("GAME_TEAM_EXITED", rejected(() -> host("exit-again", "STAFF_TEAM_EXIT", "teamId", 90, "reason", "重复确认")));
        assertEquals("GAME_TEAM_EXITED", rejected(() -> player("after-exit", "QUEST_START", "questKey", "q1")));
        assertTrue((Boolean) ((List<Map<String, Object>>) service.sharedControlView(GameActorContext.club(9L), 70L).get("teams")).get(0).get("exited"));
    }

    @Test void sharedExportIsHostOnlyAndMinimized() throws Exception {
        ObjectNode control = (ObjectNode) runState(); control.putArray("events");
        control.with("nonces").putObject("digest").put("teamId", 90);
        control.with("arrivals").putObject("90:park").put("used", true);
        control.with("manual").with("signatures").putObject("sig-1").put("sequence", 1).with("command").put("action", "ARRIVAL_CONFIRM")
                .with("payload").put("teamId", 90).put("nonce", "SECRET-NONCE-VALUE");
        run.setVariablesJson(control.toString());
        assertEquals("GAME_FORBIDDEN_SCOPE", rejected(() -> service.exportSharedRun(GameActorContext.player(7L), 70L)));
        assertEquals("GAME_FORBIDDEN_SCOPE", rejected(() -> service.exportSharedRun(GameActorContext.club(8L), 70L)));
        Map<String, Object> exported = service.exportSharedRun(GameActorContext.club(9L), 70L);
        assertEquals(new HashSet<>(Arrays.asList("sessionId", "activityId", "revision", "authorityEpoch", "eventSeq", "configHash", "highWaterAt",
                "balances", "highWaterHash", "events", "operationStatus", "authorityMode", "manual")), exported.keySet());
        String text = json.writeValueAsString(exported);
        for (String secret : Arrays.asList("SECRET-NONCE-VALUE", "nonces", "arrivals", "phone", "mobile", "correctAnswer"))
            assertFalse(text.contains(secret), "导出不得包含 " + secret);
        assertEquals("ARRIVAL_CONFIRM", json.valueToTree(exported).path("manual").path("signatures").path("sig-1").path("command").path("action").asText());
    }
}
