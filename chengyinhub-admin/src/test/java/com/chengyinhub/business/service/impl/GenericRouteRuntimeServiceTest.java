package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenericRouteRuntimeServiceTest {
    @Test void endedTeamResultCardUsesOnlyTheExistingPersonalCardProjection() throws Exception {
        assertFalse(json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer()).has("resultCard"));
        TopicRouteEngine.State state=json.readValue(owner.getVariablesJson(),TopicRouteEngine.State.class);state.setStatus("ENDED");
        ObjectNode result=state.getThemeRuntime().putObject("result").put("reason","HP_ZERO").put("success",false).put("endedAt",123).put("privateAnswer","secret");
        result.putObject("card").putArray("fields").add("hp");result.put("hp",0);result.putObject("conditions").put("secret",true);
        owner.setVariablesJson(json.writeValueAsString(state));JsonNode view=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer());
        assertEquals(new ThemeRuntimeEngine().card(result),view.path("resultCard"));assertFalse(view.path("resultCard").has("privateAnswer"));assertFalse(view.path("resultCard").has("conditions"));
    }
    private final ObjectMapper json = new ObjectMapper();
    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper mapper;
    private PlayTeamMapper teams;
    private GameSession session;
    private GameStoryState owner;
    private GameStationRun station;
    private ITopicRouteRuntimeService route;
    private final Map<String, GameCommandReceipt> receipts = new HashMap<>();

    private void assertSameSharedStateWithActorSpecificAvailability() {
        ObjectNode captain=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer()),member=json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer());
        assertTrue(captain.path("isCaptain").asBoolean());assertFalse(member.path("isCaptain").asBoolean());
        for(ObjectNode view:Arrays.asList(captain,member)) {
            view.remove("isCaptain");
            for(String field:Arrays.asList("quests","recoveries","interactionTools"))removeActorAvailability(view.path(field));
        }
        assertEquals(captain,member);
    }
    private void removeActorAvailability(JsonNode view) {
        if(view.isObject())((ObjectNode)view).remove(Arrays.asList("enabled","disabledReason"));
        for(JsonNode child:view)removeActorAvailability(child);
    }

    static String rules() {
        return "{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"init\":10,\"max\":12},\"attributes\":[{\"key\":\"counter.temperature\",\"label\":\"体温\",\"initial\":8,\"min\":0,\"max\":10,\"visible\":true}],\"statLinks\":[{\"id\":\"cold\",\"sourceKey\":\"counter.temperature\",\"targetKey\":\"sys.hp\",\"trigger\":\"PER_POINT\",\"frequency\":\"EVENT\",\"multiplier\":2,\"allowedReasons\":[\"TASK_COST\",\"CHAPTER_COST\",\"ITEM_USE\",\"TEMPLATE_EFFECT\"]}]}";
    }
    static String config() {
        return "{\"schemaVersion\":1,\"capacity\":14,\"actionLimit\":8,\"items\":[{\"id\":\"food\",\"name\":\"口粮\",\"image\":\"https://static.chengyinhub.com/food.png\",\"kind\":\"ITEM\",\"stat\":\"HP\",\"delta\":3},{\"id\":\"cold\",\"name\":\"冷包\",\"image\":\"https://static.chengyinhub.com/cold.png\",\"kind\":\"ITEM\",\"statKey\":\"counter.temperature\",\"delta\":-2,\"slotCost\":2}],\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"TEAM_INDEPENDENT\",\"survival\":{\"zeroHpPolicy\":\"BLOCK_PAID\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\"},\"acts\":[{\"actKey\":\"first\",\"questKeys\":[\"q1\",\"q2\"],\"completionRule\":{\"type\":\"ALL\"}}],\"quests\":[{\"questKey\":\"q1\",\"actKey\":\"first\",\"nodeId\":11,\"locationKey\":\"park\",\"settlementSlot\":\"q1\",\"statCosts\":[{\"statKey\":\"counter.temperature\",\"amount\":1}],\"actionCost\":1,\"choices\":[{\"choiceKey\":\"supply\",\"grants\":[{\"itemId\":\"food\",\"quantity\":1}]}]},{\"questKey\":\"q2\",\"actKey\":\"first\",\"nodeId\":12,\"locationKey\":\"lake\",\"settlementSlot\":\"q2\",\"actionCost\":1}]}}";
    }

    @BeforeEach void setUp() throws Exception {
        receipts.clear(); service = new GameSessionRuntimeServiceImpl(); mapper = mock(GameSessionRuntimeMapper.class);
        com.chengyinhub.business.service.ICapabilityService capabilities = mock(com.chengyinhub.business.service.ICapabilityService.class);
        ReflectionTestUtils.setField(service, "capabilityService", capabilities);
        when(capabilities.hasCapability(9L, com.chengyinhub.business.domain.MemberCapability.CAP_CLUB_LEADER)).thenReturn(true);
        teams = mock(PlayTeamMapper.class); route = mock(ITopicRouteRuntimeService.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        IPlayerNodeProgressService progress = mock(IPlayerNodeProgressService.class);
        ReflectionTestUtils.setField(service, "runtimeMapper", mapper); ReflectionTestUtils.setField(service, "playTeamMapper", teams);
        ReflectionTestUtils.setField(service, "activityMapper", activities); ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        ReflectionTestUtils.setField(service, "playerNodeProgressService", progress);
        CmsActivity activity = new CmsActivity(); activity.setId(70L); activity.setTopicId(80L); activity.setClubId(40L);
        activity.setStatus(1L); activity.setDelFlag(0); activity.setStartDate(new Date(System.currentTimeMillis()-3600000)); activity.setEndDate(new Date(System.currentTimeMillis()+3600000)); when(activities.selectCmsActivityById(70L)).thenReturn(activity);
        session = new GameSession(); session.setId(50L); session.setActivityId(70L); session.setTopicId(80L); session.setStatus("RUNNING"); session.setRevision(0);
        ObjectNode snapshot = json.createObjectNode().put("playMode", "ROUTE").put("runtimeSnapshotVersion", 1).put("startNodeId", 11);
        snapshot.putArray("terminalNodeIds").add(12); snapshot.set("journeyRules", json.readTree(rules())); snapshot.set("themeConfig", json.readTree(config()));
        session.setModuleConfigSnapshotJson(snapshot.toString());
        when(mapper.selectSessionByActivity(70L)).thenReturn(session); when(mapper.selectSessionByActivityForUpdate(70L)).thenReturn(session);
        when(teams.hasPaidRegistration(anyLong(), eq(2), eq(70L))).thenReturn(1);
        when(mapper.selectActiveTeamId(eq(70L), anyLong())).thenReturn(90L);
        PlayTeam team = new PlayTeam(); team.setId(90L); team.setOwnerType(2); team.setOwnerId(70L); team.setLeaderMemberId(7L); team.setStatus(2);
        when(teams.selectPlayTeamById(90L)).thenReturn(team); when(teams.selectPlayTeamByIdForUpdate(90L)).thenReturn(team);
        owner = new GameStoryState(); owner.setId(91L); owner.setGameSessionId(50L); owner.setOwnerType("TEAM"); owner.setOwnerId(90L); owner.setRevision(0); owner.setStatus("INITIAL");
        TopicRouteEngine.State state = new TopicRouteEngine().initial(snapshot.toString());
        state.getVariables().put("sys.hp", 10); JourneyInitialState.initialize(JourneyRules.parse(rules()), state);
        new ThemeRuntimeEngine().initialize(snapshot.path("themeConfig"), state);
        state.getThemeRuntime().with("inventory").put("cold", 1); state.getThemeRuntime().with("inventory").put("food", 1);
        state.getThemeRuntime().put("authorityEpoch", 1); state.getThemeRuntime().with("gameplay").put("actIndex", 0);
        owner.setVariablesJson(json.writeValueAsString(state)); when(mapper.selectStoryState(50L, "TEAM", 90L)).thenReturn(owner);
        when(mapper.updateStoryStateCas(any())).thenAnswer(call -> { GameStoryState saved = call.getArgument(0); saved.setRevision(saved.getRevision() + 1); return 1; });
        when(mapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(mapper.selectReceipt(eq(70L), anyLong(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(2)));
        when(mapper.selectReceiptForUpdate(eq(70L), anyLong(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(2)));
        when(mapper.insertReceiptClaim(any())).thenAnswer(call -> { GameCommandReceipt receipt = call.getArgument(0); receipt.setId((long)receipts.size()+1); receipts.put(receipt.getRequestId(),receipt); return 1; });
        when(mapper.completeReceipt(any())).thenReturn(1);
        station = new GameStationRun(); station.setGameSessionId(50L); station.setActivityId(70L); station.setNodeId(11L); station.setStatus("ACTIVE"); station.setServiceStartAt(activity.getStartDate()); station.setServiceEndAt(activity.getEndDate()); station.setSourceType("CHAPTER_NODE");
        when(mapper.selectStation(eq(50L), anyLong())).thenAnswer(call -> { station.setNodeId(call.getArgument(1)); return station; });
        when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call -> { PlayerNodeProgress q=call.getArgument(0); q.setCheckinTime(new Date()); return Collections.singletonList(q); });
        when(route.trustedChoiceEffects(any(), anyLong(), anyString(), anyString())).thenReturn(json.createArrayNode());
    }
    private GameCommandReceiptView send(String id, String action, String key) {
        GameSessionCommand command = new GameSessionCommand(); command.setActivityId(70L); command.setRequestId(id); command.setExpectedRevision(session.getRevision());
        command.setAction(action); command.setAuthorityEpoch(1); command.getPayload().put(action.startsWith("QUEST")?"questKey":"contentId", key);
        if ("QUEST_RESOLVE".equals(action) && "q1".equals(key)) command.getPayload().put("choiceKey", "supply");
        return service.command(GameActorContext.player(7L), command);
    }
    private String instance(long member, long node) { return service.sharedRouteSnapshot(member,80L,70L,node).path("runtimeInstance").asText("game:50"); }
    private JsonNode stored() throws Exception { return json.readTree(owner.getVariablesJson()); }

    @Test void r5CheckActionsUseCurrentCaptainAndDoNotSpendOrRepeatReroll() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());
        snapshot.with("journeyRules").putObject("luck").put("enabled",true).put("init",2).put("max",2);
        ObjectNode node=snapshot.with("nodeConfigs").putObject("11");node.putObject("check").put("enabled",true).put("tier","easy");node.putObject("outcomeRules").put("version",1).putObject("pass");
        session.setModuleConfigSnapshotJson(snapshot.toString());
        TopicRouteEngine.State initial=json.readValue(owner.getVariablesJson(),TopicRouteEngine.State.class);initial.getVariables().put("sys.luck",2);owner.setVariablesJson(json.writeValueAsString(initial));
        send("check-start","QUEST_START","q1");String instance=instance(7,11);
        assertEquals("ROLL_CHECK",service.sharedGameplayCheckActions(8L,80L,70L,11L,instance,"advanced:1:1",1).path("allowedActions").path(0).asText());
        service.rollSharedGameplayCheck(8L,80L,70L,11L,instance,"advanced:1:1",false,1);
        String before=owner.getVariablesJson();int revision=session.getRevision();
        assertTrue(service.sharedGameplayCheckActions(7L,80L,70L,11L,instance,"advanced:1:1",1).path("allowedActions").toString().contains("REROLL_CHECK"));
        ObjectNode member=service.sharedGameplayCheckActions(8L,80L,70L,11L,instance,"advanced:1:1",1);
        assertFalse(member.path("allowedActions").toString().contains("REROLL_CHECK"));assertTrue(member.path("disabledReason").asText().contains("队长"));
        assertEquals(before,owner.getVariablesJson());assertEquals(revision,session.getRevision());
        assertThrows(GameSessionException.class,()->service.rollSharedGameplayCheck(8L,80L,70L,11L,instance,"advanced:1:1",true,1));
        service.rollSharedGameplayCheck(7L,80L,70L,11L,instance,"advanced:1:1",true,1);
        assertEquals("重掷次数已用完",service.sharedGameplayCheckActions(7L,80L,70L,11L,instance,"advanced:1:1",1).path("disabledReason").asText());
    }

    @Test void staffControlExposesOnlyFrozenGuideQuestionsAfterExistingScopeChecks() throws Exception {
        chapterGame(false);
        com.chengyinhub.business.service.ICapabilityService capabilities =
                (com.chengyinhub.business.service.ICapabilityService) ReflectionTestUtils.getField(service, "capabilityService");
        when(capabilities.hasCapability(9L, com.chengyinhub.business.domain.MemberCapability.CAP_CLUB_LEADER)).thenReturn(false);
        ObjectNode snapshot = (ObjectNode) json.readTree(session.getModuleConfigSnapshotJson());
        long node = snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0).path("nodeId").asLong();
        snapshot.with("nodeAnswers").putObject(String.valueOf(node)).put("validationMethod", 3).put("correctAnswer", "private");
        session.setModuleConfigSnapshotJson(snapshot.toString());
        JsonNode view = json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L));
        assertTrue(view.path("guideTasks").size() > 0);
        assertEquals(node, view.path("guideTasks").path(0).path("nodeId").asLong());
        assertEquals("TYPE", view.path("guideTasks").path(0).path("basic").path("mode").asText());
        assertFalse(view.toString().contains("correctAnswer"));
        assertFalse(view.toString().contains("private"));
        assertThrows(GameSessionException.class, () -> service.sharedControlView(GameActorContext.player(7L), 70L));
        assertThrows(GameSessionException.class, () -> service.sharedControlView(GameActorContext.club(8L), 70L));
        verify(mapper, never()).updateStoryStateCas(any());
    }

    @Test void offlineAnchorReadbackUsesOnlySavedCurrentEpochConfigAndDoesNotExposeCreator() throws Exception {
        chapterGame(false);
        JsonNode before = json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L));
        assertTrue(before.path("offlineAnchorAvailable").asBoolean());
        assertTrue(before.path("offlineAnchor").isNull());
        String inventory = owner.getVariablesJson(); int revision = session.getRevision();
        service.anchorSharedRun(GameActorContext.club(9L), 70L);
        JsonNode control = json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L));
        assertEquals(6, control.path("offlineAnchor").size());
        assertEquals(1, control.path("offlineAnchor").path("epoch").asInt());
        assertFalse(control.path("offlineAnchor").has("createdBy"));
        assertEquals(inventory, owner.getVariablesJson()); assertEquals(revision, session.getRevision());
        assertThrows(GameSessionException.class, () -> service.anchorSharedRun(GameActorContext.player(7L), 70L));
        assertThrows(GameSessionException.class, () -> service.anchorSharedRun(GameActorContext.club(8L), 70L));
        GameStoryState run = mapper.selectStoryState(50L, "RUN", 50L);
        ObjectNode data = (ObjectNode) json.readTree(run.getVariablesJson()); data.put("authorityEpoch", 2); run.setVariablesJson(data.toString());
        assertTrue(json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L)).path("offlineAnchor").isNull());
        data.put("authorityEpoch", 1); ((ObjectNode) data.path("exports").elements().next()).put("configHash", "stale"); run.setVariablesJson(data.toString());
        assertTrue(json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L)).path("offlineAnchor").isNull());
    }

    @Test void offlineAnchorAvailabilityKeepsOnlineStatusWindowAndCapacityGates() throws Exception {
        chapterGame(false);
        GameStoryState run = mapper.selectStoryState(50L, "RUN", 50L);
        for (String mode : Arrays.asList("MANUAL", "ONLINE")) for (String status : Arrays.asList("INTERRUPTED", "CANCELLED", "RUNNING", "PAUSED")) {
            run.setVariablesJson("{\"authorityEpoch\":1,\"authorityMode\":\"" + mode + "\",\"operationStatus\":\"" + status + "\"}");
            boolean available = json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L)).path("offlineAnchorAvailable").asBoolean();
            assertEquals("ONLINE".equals(mode) && ("RUNNING".equals(status) || "PAUSED".equals(status)), available);
        }
        ObjectNode data = (ObjectNode) json.readTree(run.getVariablesJson());
        ObjectNode exports = data.putObject("exports"); for (int index = 0; index < 128; index++) exports.putObject("saved" + index);
        run.setVariablesJson(data.toString());
        assertFalse(json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L)).path("offlineAnchorAvailable").asBoolean());
        run.setVariablesJson("{\"authorityEpoch\":1,\"authorityMode\":\"ONLINE\",\"operationStatus\":\"RUNNING\"}");
        session.setStatus("FINISHED");
        assertFalse(json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L)).path("offlineAnchorAvailable").asBoolean());
        session.setStatus("RUNNING");
        CmsActivityMapper activities = (CmsActivityMapper) ReflectionTestUtils.getField(service, "activityMapper");
        activities.selectCmsActivityById(70L).setEndDate(new Date(System.currentTimeMillis() - 60000));
        assertFalse(json.valueToTree(service.sharedControlView(GameActorContext.club(9L), 70L)).path("offlineAnchorAvailable").asBoolean());
    }

    @Test void participantCatalogUsesOnlyCommandScopeTeamsAndTrustedCurrentArrival() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode game=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime");
        game.put("worldMode","WORLD_SHARED");game.putArray("facilities").addObject().put("facilityKey","gather").put("actKey","first").put("scope","WORLD").put("targetScope","PARTICIPANTS").put("settlementSlot","gather").put("limit",1).put("arrivalLocationKey","park");
        session.setModuleConfigSnapshotJson(snapshot.toString());
        GameStoryState world=new GameStoryState();world.setOwnerType("WORLD");world.setOwnerId(50L);world.setVariablesJson(json.writeValueAsString(new TopicRouteEngine.State()));
        GameStoryState otherWorld=new GameStoryState();otherWorld.setOwnerType("WORLD");otherWorld.setOwnerId(999L);otherWorld.setVariablesJson("{}");
        ObjectNode run=json.createObjectNode().put("authorityEpoch",1).put("authorityMode","ONLINE").put("operationStatus","RUNNING");run.with("exitedTeams").put("95",true);
        List<GameStoryState> owners=new ArrayList<>(Arrays.asList(owner,world,otherWorld));
        for(long id=90;id<=95;id++) {
            PlayTeam team=new PlayTeam();team.setId(id);team.setTitle("真实队伍"+id);team.setOwnerType(2);team.setOwnerId(id==94?999L:70L);team.setLeaderMemberId(id==90?7L:id);
            when(teams.selectPlayTeamById(id)).thenReturn(team);when(mapper.isActiveTeam(70L,id)).thenReturn(1);when(mapper.isActiveTeamMember(eq(70L),eq(id),anyLong())).thenReturn(1);
            if(id!=90) {
                GameStoryState row=new GameStoryState();row.setOwnerType("TEAM");row.setOwnerId(id);row.setGameSessionId(50L);
                ObjectNode state=(ObjectNode)stored();if(id==93)state.put("status","COMPLETED");row.setVariablesJson(state.toString());owners.add(row);
            }
            if(id!=92)run.with("arrivals").putObject(id+":park").put("used",true).put("sessionId",50).put("teamId",id).put("nodeId",11).put("version",1).put("epoch",1).put("expiresAt",System.currentTimeMillis()+60000);
        }
        GameStoryState control=new GameStoryState();control.setOwnerType("RUN");control.setOwnerId(50L);control.setVariablesJson(run.toString());
        when(mapper.selectStoryState(50L,"RUN",50L)).thenReturn(control);when(mapper.selectStoryStates(50L)).thenReturn(owners);
        String unchanged=owner.getVariablesJson();JsonNode view=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer());
        JsonNode tool=view.path("interactionTools").get(0),catalog=tool.path("participantCatalog");
        assertEquals("PARTICIPANTS",tool.path("targetScope").asText());assertTrue(tool.path("payerRequired").asBoolean());assertEquals(4,catalog.size());
        assertEquals(json.readTree("[{\"teamId\":90,\"teamName\":\"真实队伍90\",\"eligible\":true,\"disabledReason\":null},{\"teamId\":91,\"teamName\":\"真实队伍91\",\"eligible\":true,\"disabledReason\":null},{\"teamId\":92,\"teamName\":\"真实队伍92\",\"eligible\":false,\"disabledReason\":\"NOT_ARRIVED\"},{\"teamId\":93,\"teamName\":\"真实队伍93\",\"eligible\":false,\"disabledReason\":\"TEAM_COMPLETED\"}]"),json.readTree(catalog.toString()));
        assertEquals(catalog,json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer()).path("interactionTools").path(0).path("participantCatalog"));
        assertEquals(unchanged,owner.getVariablesJson());verify(mapper,never()).updateStoryStateCas(any());
        run.with("arrivals").with("90:park").put("epoch",0);control.setVariablesJson(run.toString());
        assertEquals("NOT_ARRIVED",json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer()).path("interactionTools").path(0).path("participantCatalog").path(0).path("disabledReason").asText());
    }

    @Test void participantCatalogRequiresSameNodeFreeTaskAndTrustedSubmission() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode game=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime");game.put("worldMode","WORLD_SHARED");
        ObjectNode quest=(ObjectNode)game.path("quests").get(0);quest.remove(Arrays.asList("statCosts","actionCost","choices"));
        game.putArray("facilities").addObject().put("facilityKey","gather").put("actKey","first").put("scope","WORLD").put("targetScope","PARTICIPANTS").put("settlementSlot","gather").put("limit",1).put("operationNodeId",11);
        session.setModuleConfigSnapshotJson(snapshot.toString());
        ObjectNode state=(ObjectNode)stored();state.with("themeRuntime").with("gameplay").put("activeQuest","q1");owner.setVariablesJson(state.toString());
        GameStoryState world=new GameStoryState();world.setOwnerType("WORLD");world.setOwnerId(50L);world.setVariablesJson(json.writeValueAsString(new TopicRouteEngine.State()));
        when(mapper.selectStoryStates(50L)).thenReturn(Arrays.asList(owner,world));when(mapper.isActiveTeam(70L,90L)).thenReturn(1);when(mapper.isActiveTeamMember(eq(70L),eq(90L),anyLong())).thenReturn(1);
        JsonNode pending=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer()).path("interactionTools").path(0).path("participantCatalog").path(0);
        assertFalse(pending.path("eligible").asBoolean());assertEquals("PROOF_PENDING",pending.path("disabledReason").asText());
        state.with("themeRuntime").with("verifiedNodes").put("11",123L);owner.setVariablesJson(state.toString());
        JsonNode passed=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer()).path("interactionTools").path(0).path("participantCatalog").path(0);
        assertTrue(passed.path("eligible").asBoolean());assertTrue(passed.path("disabledReason").isNull());
        quest.put("actionCost",1);session.setModuleConfigSnapshotJson(snapshot.toString());
        JsonNode paid=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer()).path("interactionTools").path(0).path("participantCatalog").path(0);
        assertFalse(paid.path("eligible").asBoolean());assertEquals("TASK_ACTIVE",paid.path("disabledReason").asText());
    }

    @Test void sharedVoteActionsUseDatabaseCaptainAndSessionGates() throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree(session.getModuleConfigSnapshotJson());
        ObjectNode gameplay = (ObjectNode) snapshot.path("themeConfig").path("gameplayRuntime");
        gameplay.put("worldMode", "WORLD_SHARED").put("votePolicy", "MAJORITY");
        gameplay.putArray("plans").addObject().put("endingKey", "decision").put("title", "方案").put("operationNodeId", 11).putArray("materialCost")
                .addObject().put("itemId", "food").put("quantity", 1);
        session.setModuleConfigSnapshotJson(snapshot.toString());
        GameStoryState world = new GameStoryState();world.setOwnerType("WORLD");world.setOwnerId(50L);
        world.setVariablesJson(json.writeValueAsString(new TopicRouteEngine.State()));
        when(mapper.selectStoryStates(50L)).thenReturn(Arrays.asList(owner, world));
        when(mapper.isActiveTeam(70L,90L)).thenReturn(1);when(mapper.isActiveTeamMember(eq(70L),eq(90L),anyLong())).thenReturn(1);
        JsonNode captain = json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer());
        assertTrue(captain.path("world").path("plans").path(0).path("actions").path("canFund").asBoolean());
        JsonNode tool = captain.path("interactionTools").path(0);
        assertEquals(1, tool.path("remainingCount").asInt());
        assertEquals("food", tool.path("materialCost").path(0).path("itemId").asText());
        assertEquals(1, tool.path("materialCost").path(0).path("quantity").asInt());
        JsonNode member = json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer());
        JsonNode actions = member.path("world").path("plans").path(0).path("actions");
        for (String field : Arrays.asList("canFund","canVote","canAccept","canExecute")) assertFalse(actions.path(field).asBoolean());
        assertEquals("NOT_CAPTAIN",actions.path("disabledReason").asText());
        PlayTeam team = teams.selectPlayTeamById(90L);team.setLeaderMemberId(8L);
        JsonNode oldCaptain = json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer());
        assertEquals("NOT_CAPTAIN",oldCaptain.path("world").path("plans").path(0).path("actions").path("disabledReason").asText());
        assertTrue(json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer()).path("world").path("plans").path(0).path("actions").path("canFund").asBoolean());
        session.setStatus("COMPLETED");
        assertEquals("SESSION_ENDED",json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer()).path("world").path("plans").path(0).path("actions").path("disabledReason").asText());
    }

    @Test void everySharedPerspectiveReturnsTheCommandAuthorityEpoch() throws Exception {
        chapterGame(false);
        GameStoryState run=mapper.selectStoryState(50L,"RUN",50L);
        ObjectNode control=(ObjectNode)json.readTree(run.getVariablesJson());
        control.put("authorityEpoch",4);run.setVariablesJson(control.toString());
        IClubMemberService clubs=mock(IClubMemberService.class);ReflectionTestUtils.setField(service,"clubMemberService",clubs);
        when(clubs.isTopicOwner(80L,40L,9L)).thenReturn(true);
        station.setMerchantMemberId(6L);when(mapper.selectMerchantStations(50L,6L)).thenReturn(Collections.singletonList(station));
        for(GameActorContext actor:Arrays.asList(GameActorContext.player(7L),GameActorContext.club(9L),GameActorContext.merchant(6L)))
            assertEquals(Integer.valueOf(4),service.view(actor,70L).getAuthorityEpoch());
        assertEquals(4,((Number)service.view(GameActorContext.player(7L),70L).getPlayer().get("authorityEpoch")).intValue());
        GameSessionCommand command=new GameSessionCommand();command.setActivityId(70L);command.setExpectedRevision(session.getRevision());
        command.setRequestId("old-view-epoch");command.setAction("QUEST_START");command.setAuthorityEpoch(3);command.getPayload().put("questKey","node_101");
        assertEquals("GAME_AUTHORITY_CONFLICT",assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(7L),command)).getErrorCode());
        command.setRequestId("current-view-epoch");command.setAuthorityEpoch(service.view(GameActorContext.player(7L),70L).getAuthorityEpoch());
        assertEquals("APPLIED",service.command(GameActorContext.player(7L),command).getOutcome());
    }

    @Test void unauthorizedFailuresCannotWriteReceiptsForAnotherScope() throws Exception {
        chapterGame(false);
        when(teams.hasPaidRegistration(6L,2,70L)).thenReturn(0);
        for(GameActorContext actor:Arrays.asList(GameActorContext.player(6L),GameActorContext.player(8L),GameActorContext.club(10L))) {
            GameSessionCommand command=new GameSessionCommand();command.setActivityId(70L);command.setExpectedRevision(session.getRevision());
            command.setRequestId("denied-"+actor.getMemberId());command.setAuthorityEpoch(1);
            command.setAction("CLUB".equals(actor.getPerspective())?"RUN_PAUSE":"QUEST_START");
            command.getPayload().put("questKey","node_101");
            GameSessionException denied=assertThrows(GameSessionException.class,()->service.command(actor,command));
            assertEquals("GAME_FORBIDDEN_SCOPE",denied.getErrorCode());
            service.recordFailure(actor,command,denied);
        }
        assertTrue(receipts.isEmpty());verify(mapper,never()).insertReceiptClaim(any());
    }

    @Test void legacySharedAppliedReceiptRequiresItsReliableRecordedActor() throws Exception {
        chapterGame(false);
        GameCommandReceipt legacy=new GameCommandReceipt();legacy.setId(1L);legacy.setActivityId(70L);
        legacy.setActorMemberId(7L);legacy.setActorPerspective("PLAYER");legacy.setRequestId("legacy-owner");
        legacy.setOutcome("APPLIED");legacy.setResultJson("{\"player\":{\"inventory\":{\"tool\":1}}}");
        receipts.put(legacy.getRequestId(),legacy);
        assertEquals("APPLIED",service.receipt(GameActorContext.player(7L),70L,legacy.getRequestId()).getOutcome());
        assertEquals("GAME_FORBIDDEN_SCOPE",assertThrows(GameSessionException.class,
                ()->service.receipt(GameActorContext.player(8L),70L,legacy.getRequestId())).getErrorCode());
        legacy.setActorMemberId(null);
        assertThrows(GameSessionException.class,()->service.receipt(GameActorContext.player(7L),70L,legacy.getRequestId()));
        legacy.setOutcome("FAILED");legacy.setResultJson("{\"reason\":\"旧业务失败\"}");
        assertEquals("FAILED",service.receipt(GameActorContext.player(7L),70L,legacy.getRequestId()).getOutcome());
        legacy.setOutcome("APPLIED");session.setModuleConfigSnapshotJson("{}");
        assertEquals("APPLIED",service.receipt(GameActorContext.player(7L),70L,legacy.getRequestId()).getOutcome());
    }

    @Test void completedTeamClaimWithOwnedDiscardIsRestrictedAndIdempotent() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)snapshot.path("themeConfig")).put("capacity",3);session.setModuleConfigSnapshotJson(snapshot.toString());
        ObjectNode state=(ObjectNode)stored();state.put("status","COMPLETED");state.with("themeRuntime").putObject("result").put("hp",10);state.with("themeRuntime").with("sources").put("chapter:0:END:food",true);state.with("themeRuntime").with("pending").putObject("chapter:0:END:food").put("itemId","food").put("quantity",1);owner.setVariablesJson(state.toString());
        assertThrows(GameSessionException.class,()->send("use-completed","ITEM_USE","food"));assertThrows(GameSessionException.class,()->send("discard-completed","ITEM_DISCARD","food"));
        String before=owner.getVariablesJson();assertThrows(GameSessionException.class,()->send("claim-full","ITEM_RECEIVE","chapter:0:END:food"));assertEquals(before,owner.getVariablesJson());
        GameSessionCommand command=new GameSessionCommand();command.setActivityId(70L);command.setRequestId("claim-pending");command.setExpectedRevision(session.getRevision());command.setAuthorityEpoch(1);command.setAction("ITEM_RECEIVE");command.getPayload().put("contentId","chapter:0:END:food");command.getPayload().put("discardOwned",Collections.singletonList(new LinkedHashMap<String,Object>(){{put("itemId","cold");put("quantity",1);}}));
        assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(8L),command));
        GameCommandReceiptView receipt=service.command(GameActorContext.player(7L),command);assertTrue(stored().path("themeRuntime").path("pending").isEmpty());assertEquals(2,stored().path("themeRuntime").path("inventory").path("food").asInt());assertEquals(state.path("variables").path("sys.hp"),stored().path("variables").path("sys.hp"));assertEquals(state.path("themeRuntime").path("result"),stored().path("themeRuntime").path("result"));
        before=owner.getVariablesJson();assertEquals(json.writeValueAsString(receipt.getResult()),json.writeValueAsString(service.command(GameActorContext.player(7L),command).getResult()));assertEquals(before,owner.getVariablesJson());
        command.getPayload().put("discardOwned",Collections.emptyList());assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(7L),command));
    }

    private void chapterGame(boolean hostUnlock) throws Exception {
        GameplayChapterRuntimeServiceTest fixture=new GameplayChapterRuntimeServiceTest();fixture.setup();
        fixture.author.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","TEAM_INDEPENDENT");
        fixture.author.put("capacity",2).putArray("items").addObject().put("id","supplies").put("name","物资").put("kind","ITEM").put("image","https://www.chengyinhub.com/favicon.ico");
        fixture.settings(0).putObject("completion").put("type","ANY_N").put("quantity",2);
        fixture.settings(0).putArray("exclusiveGroups").addArray().add(101).add(102);
        fixture.settings(0).putArray("extraNodes").addObject().put("nodeId",104).put("slot","mobile");
        fixture.settings(1).putArray("extraNodes").addObject().put("nodeId",203).put("slot","mobile");
        fixture.settings(1).putArray("grants").addObject().put("itemId","supplies").put("quantity",1).put("timing","START");
        fixture.publish();fixture.author=(ObjectNode)json.readTree(fixture.topic.getThemeRuntimeConfigJson());
        ((ObjectNode)fixture.author.path("gameplayRuntime").path("acts").path(1)).put("hostUnlock",hostUnlock);
        ObjectNode snapshot=(ObjectNode)fixture.publish();snapshot.put("playMode","ROUTE");session.setModuleConfigSnapshotJson(snapshot.toString());
        TopicRouteEngine.State state=new TopicRouteEngine().initial(snapshot.toString());JourneyRules rules=JourneyRules.parse(snapshot.path("journeyRules").toString());
        state.getVariables().put("sys.hp",20);JourneyInitialState.initialize(rules,state);new GameplayTaskEngine().initialize(snapshot.path("themeConfig"),rules,state,System.currentTimeMillis());
        state.getThemeRuntime().put("authorityEpoch",1);owner.setVariablesJson(json.writeValueAsString(state));
        GameStoryState run=new GameStoryState();run.setId(92L);run.setGameSessionId(50L);run.setOwnerType("RUN");run.setOwnerId(50L);run.setRevision(0);run.setVariablesJson("{\"operationStatus\":\"RUNNING\",\"authorityMode\":\"ONLINE\",\"authorityEpoch\":1}");
        when(mapper.selectStoryState(50L,"RUN",50L)).thenReturn(run);when(mapper.selectStoryStates(50L)).thenReturn(Collections.singletonList(owner));
        IClubAccessService access=mock(IClubAccessService.class);ReflectionTestUtils.setField(service,"clubAccessService",access);
        when(access.require(eq(9L),eq(40L),eq(70L),anyString())).thenReturn(new com.chengyinhub.business.access.club.ClubAccessContext(9L,40L,9L,70L,Collections.singleton("CLUB_OWNER")));
    }
    private void completeChapterNode(long node) {
        send("start-"+node,"QUEST_START","node_"+node);
        service.recordSharedRouteResult(8L,80L,70L,node,instance(8,node),"proof-"+node,true,Collections.emptyList());
        send("done-"+node,"QUEST_RESOLVE","node_"+node);
    }
    private GameCommandReceiptView unlock(String request,String act,GameActorContext actor) {
        GameSessionCommand command=new GameSessionCommand();command.setActivityId(70L);command.setExpectedRevision(session.getRevision());command.setAuthorityEpoch(1);
        command.setRequestId(request);command.setAction("ACT_UNLOCK");command.getPayload().put("actKey",act);command.getPayload().put("reason","章节开放");
        return service.command(actor,command);
    }

    @Test void compiledChapterRulesSettleForTeamAndHostUnlockStillGuardsNextAct() throws Exception {
        chapterGame(true);completeChapterNode(104);completeChapterNode(101);
        assertEquals(0,stored().path("themeRuntime").path("gameplay").path("actIndex").asInt());
        assertThrows(GameSessionException.class,()->send("locked-choice","QUEST_START","node_102"));
        completeChapterNode(103);assertEquals(1,stored().path("themeRuntime").path("gameplay").path("actIndex").asInt());
        assertTrue(stored().path("themeRuntime").path("gameplay").path("awaitingUnlock").asBoolean());
        assertEquals(0,stored().path("themeRuntime").path("inventory").path("supplies").asInt());
        assertThrows(GameSessionException.class,()->send("locked-next","QUEST_START","node_201"));
        assertThrows(GameSessionException.class,()->unlock("player-unlock","chapter_30",GameActorContext.player(7L)));
        unlock("host-unlock","chapter_30",GameActorContext.club(9L));
        assertFalse(stored().path("themeRuntime").path("gameplay").path("awaitingUnlock").asBoolean());
        assertEquals(1,stored().path("themeRuntime").path("inventory").path("supplies").asInt());
        unlock("host-unlock-again","chapter_30",GameActorContext.club(9L));assertEquals(1,stored().path("themeRuntime").path("inventory").path("supplies").asInt());
        assertThrows(GameSessionException.class,()->send("shared-extra","QUEST_START","node_203"));
        completeChapterNode(201);completeChapterNode(202);
        assertEquals(2,stored().path("themeRuntime").path("gameplay").path("actIndex").asInt());assertEquals(15,stored().path("variables").path("sys.hp").asInt());
        assertSameSharedStateWithActorSpecificAvailability();
    }
    @Test void priorExtraDoesNotUnlockNextActOrRepeatChapterAndPreUnlockIsHonored() throws Exception {
        chapterGame(true);completeChapterNode(101);completeChapterNode(103);
        completeChapterNode(104);assertEquals(1,stored().path("themeRuntime").path("gameplay").path("actIndex").asInt());
        assertTrue(stored().path("themeRuntime").path("gameplay").path("awaitingUnlock").asBoolean());
        int hp=stored().path("variables").path("sys.hp").asInt();send("extra-replay","QUEST_RESOLVE","node_104");assertEquals(hp,stored().path("variables").path("sys.hp").asInt());
        unlock("unlock-after-extra","chapter_30",GameActorContext.club(9L));assertEquals(201,stored().path("currentNodeId").asLong());
        setUp();chapterGame(true);unlock("pre-unlock","chapter_30",GameActorContext.club(9L));completeChapterNode(101);completeChapterNode(103);
        assertFalse(stored().path("themeRuntime").path("gameplay").path("awaitingUnlock").asBoolean());assertEquals(201,stored().path("currentNodeId").asLong());
        assertEquals(1,stored().path("themeRuntime").path("inventory").path("supplies").asInt());
        assertEquals("PLAYABLE",stored().path("nodeStates").path("104").asText());
    }

    @Test void sharedScanWithEditorModifiersBindsBasicCompletionAtStart() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());
        ObjectNode config=snapshot.putObject("nodeConfigs").putObject("11").put("schemaVersion",1);
        config.putObject("scan").put("enabled",true).put("kind","TEXT").put("reply","到达").put("arMode","NONE");
        config.putObject("timer").put("enabled",true).put("durationSeconds",300).put("timeoutResult","FAILED");
        for(String modifier:Arrays.asList("leaderboard","multiplayer","timeWindow","blindTaste","silentOrder","diyName","musicCorner","dailySign","slowTask","random","branch"))config.putObject(modifier).put("enabled",true);
        snapshot.putObject("nodeAnswers").putObject("11").put("validationMethod",4);session.setModuleConfigSnapshotJson(snapshot.toString());
        TopicRouteEngine.State state=json.readValue(owner.getVariablesJson(),TopicRouteEngine.State.class);
        state.getThemeRuntime().with("gameplay").with("arrivalFacts").putObject("11").put("sourceNodeId",11).put("epoch",1).put("bindingVersion",1).put("expiresAt",System.currentTimeMillis()+60000).put("kind","SCAN").put("receiptId",123);
        owner.setVariablesJson(json.writeValueAsString(state));send("scan-start","QUEST_START","q1");
        assertEquals(123,stored().path("themeRuntime").path("verifiedNodes").path("11").asInt());
        assertTrue(stored().path("themeRuntime").path("gameplay").path("arrivalFacts").path("11").has("consumedBy"));
        send("scan-resolve","QUEST_RESOLVE","q1");assertEquals(1,stored().path("completedNodeIds").size());
    }
    @Test void teamCommandFreezesCostOptionAndOrdinaryMemberCanProvideTrustedProof() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode q=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").get(0);q.remove("statCosts");q.remove("actionCost");
        ArrayNode options=q.putArray("costOptions");options.addObject().putArray("resourceCosts").addObject().put("itemId","food").put("quantity",2);options.addObject().put("hpCost",2);session.setModuleConfigSnapshotJson(snapshot.toString());
        GameSessionCommand start=new GameSessionCommand();start.setActivityId(70L);start.setExpectedRevision(0);start.setAuthorityEpoch(1);start.setRequestId("selected-start");start.setAction("QUEST_START");start.getPayload().put("questKey","q1");start.getPayload().put("costOption",1);
        service.command(GameActorContext.player(7L),start);assertEquals(1,stored().path("themeRuntime").path("gameplay").path("activeQuestCostOption").asInt());
        start.getPayload().put("costOption",0);assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(7L),start));
        service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"option-proof",true,Collections.emptyList());
        GameCommandReceiptView settled=send("option-resolve","QUEST_RESOLVE","q1");assertEquals(1,settled.getResult().get("costOption"));
        assertEquals(8,stored().path("variables").path("sys.hp").asInt());assertEquals(2,stored().path("themeRuntime").path("inventory").path("food").asInt());
    }
    @Test void teamCostOptionReadbackNamesShortfallAndAffordableHasEmptyArray() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode q=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0);q.remove("statCosts");q.remove("actionCost");
        ArrayNode options=q.putArray("costOptions");options.addObject().putArray("resourceCosts").addObject().put("itemId","food").put("quantity",3);options.addObject().put("hpCost",2);session.setModuleConfigSnapshotJson(snapshot.toString());
        JsonNode visible=json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer().get("quests")).path(0).path("costOptions");
        assertFalse(visible.path(0).path("affordable").asBoolean());assertEquals("ITEM",visible.path(0).path("shortfall").path(0).path("type").asText());assertEquals("food",visible.path(0).path("shortfall").path(0).path("key").asText());assertEquals(2,visible.path(0).path("shortfall").path(0).path("missing").asInt());assertTrue(visible.path(1).path("affordable").asBoolean());assertTrue(visible.path(1).path("shortfall").isArray());assertTrue(visible.path(1).path("shortfall").isEmpty());
        ((ObjectNode)options.path(0).path("resourceCosts").path(0)).put("quantity",1);session.setModuleConfigSnapshotJson(snapshot.toString());
        TopicRouteEngine.State world=new TopicRouteEngine.State();world.getThemeRuntime().with("cooperation").with("locks").with("90").putArray("amounts").addObject().put("itemId","food").put("quantity",1);
        ObjectNode summary=ReflectionTestUtils.invokeMethod(service,"routeActionSummary",session,JourneyRules.parse(rules()),json.readValue(owner.getVariablesJson(),TopicRouteEngine.State.class),world,90L,null,"QUEST_RESOLVE","q1");
        assertNotNull(summary);assertFalse(summary.path("costOptions").path(0).path("affordable").asBoolean());assertEquals(1,summary.path("costOptions").path(0).path("shortfall").path(0).path("missing").asInt());assertTrue(summary.path("costOptions").path(1).path("shortfall").isEmpty());
    }
    @Test void sameTeamPhonesReadSameStateAndLinkRunsOnceOnlyOnTrustedUse() throws Exception {
        assertSameSharedStateWithActorSpecificAvailability();
        send("use", "ITEM_USE", "cold"); assertEquals(6, stored().path("variables").path("sys.hp").asInt());
        assertEquals(6, stored().path("variables").path("counter.temperature").asInt());
        GameSessionCommand replay = new GameSessionCommand(); replay.setActivityId(70L); replay.setExpectedRevision(0); replay.setAuthorityEpoch(1);
        replay.setRequestId("use"); replay.setAction("ITEM_USE"); replay.getPayload().put("contentId","cold");
        assertTrue(service.command(GameActorContext.player(7L), replay).isReplayed());
        service.view(GameActorContext.player(8L),70L); assertEquals(6, stored().path("variables").path("sys.hp").asInt());
        assertEquals(1, session.getRevision());
    }
    @Test void ordinaryMemberCannotConsumeTeamStock() {
        GameSessionCommand cmd = new GameSessionCommand(); cmd.setActivityId(70L); cmd.setRequestId("member"); cmd.setExpectedRevision(0); cmd.setAuthorityEpoch(1); cmd.setAction("ITEM_USE"); cmd.getPayload().put("contentId","cold");
        assertEquals("GAME_FORBIDDEN_SCOPE", assertThrows(GameSessionException.class, ()->service.command(GameActorContext.player(8L),cmd)).getErrorCode());
        verify(mapper,never()).updateStoryStateCas(any());
    }
    @Test void epochAndInjectedBalanceAreRejected() {
        GameSessionCommand cmd = new GameSessionCommand(); cmd.setActivityId(70L); cmd.setRequestId("old"); cmd.setExpectedRevision(0); cmd.setAuthorityEpoch(0); cmd.setAction("ITEM_USE"); cmd.getPayload().put("contentId","cold");
        assertEquals("GAME_AUTHORITY_CONFLICT", assertThrows(GameSessionException.class, ()->service.command(GameActorContext.player(7L),cmd)).getErrorCode());
        cmd.setRequestId("injected"); cmd.setAuthorityEpoch(1); cmd.getPayload().put("hp",999);
        assertEquals("GAME_BAD_REQUEST", assertThrows(GameSessionException.class, ()->service.command(GameActorContext.player(7L),cmd)).getErrorCode());
    }
    @Test void startReservesWithoutCostAndCancelReleases() throws Exception {
        send("start", "QUEST_START", "q1"); assertEquals(10, stored().path("variables").path("sys.hp").asInt()); assertEquals(0,stored().path("variables").path("sys.actions").asInt());
        assertThrows(GameSessionException.class, ()->send("spend", "ITEM_USE", "food"));
        send("cancel", "QUEST_CANCEL", "q1"); assertFalse(stored().path("themeRuntime").path("gameplay").has("activeQuest"));
    }
    @Test void onlyFullServerVerdictSettlesOneBusinessSlotAndPaysOnce() throws Exception {
        send("start", "QUEST_START", "q1"); assertThrows(GameSessionException.class, ()->send("unverified", "QUEST_RESOLVE", "q1"));
        service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"answer",true,Collections.emptyList());
        GameCommandReceiptView settled = send("resolve", "QUEST_RESOLVE", "q1");
        assertEquals(8,stored().path("variables").path("sys.hp").asInt()); assertEquals(7,stored().path("variables").path("counter.temperature").asInt());
        assertEquals(1,stored().path("variables").path("sys.actions").asInt()); assertEquals(2,stored().path("themeRuntime").path("inventory").path("food").asInt());
        int revision=session.getRevision(); GameCommandReceiptView otherId=send("other-id","QUEST_RESOLVE","q1");
        assertEquals(settled.getResult().get("receiptId"),otherId.getResult().get("receiptId")); assertEquals(revision,session.getRevision());
        assertEquals(1,stored().path("variables").path("sys.actions").asInt());
    }
    @Test void completionAdvancesActualNodesAndFreezesOnlyThisTeamResult() throws Exception {
        send("first-start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"first-pass",true,Collections.emptyList());send("first-done","QUEST_RESOLVE","q1");
        assertEquals(12,stored().path("currentNodeId").asLong());
        send("second-start","QUEST_START","q2");service.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"second-pass",true,Collections.emptyList());send("second-done","QUEST_RESOLVE","q2");
        assertEquals("COMPLETED",stored().path("status").asText());assertTrue(stored().path("themeRuntime").has("result"));assertTrue(stored().path("currentNodeId").isNull());
        assertTrue(service.view(GameActorContext.player(7L),70L).getAvailableActions().isEmpty());assertEquals("RUNNING",session.getStatus());
        assertThrows(GameSessionException.class,()->send("after-ending","ITEM_USE","food"));
    }
    @Test void anyOneMutuallyExclusiveQuestCompletesActWithoutSecondFee() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode game=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime");
        ((ObjectNode)game.path("acts").path(0).path("completionRule")).put("type","ANY_N").put("quantity",1);
        for(JsonNode q:game.path("quests"))((ObjectNode)q).put("exclusiveGroup","choose-one");session.setModuleConfigSnapshotJson(snapshot.toString());
        send("start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"pass",true,Collections.emptyList());send("done","QUEST_RESOLVE","q1");
        assertTrue(stored().path("themeRuntime").has("result"));assertEquals(1,stored().path("variables").path("sys.actions").asInt());assertThrows(GameSessionException.class,()->send("exclusive","QUEST_START","q2"));
    }
    @Test void chapterCostAndTaskCostLinkOnceAndNextChapterGrantArrivesAtEntry() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ArrayNode chapters=((ObjectNode)snapshot.path("themeConfig")).putArray("chapters");
        ObjectNode first=chapters.addObject().put("index",0);first.putObject("binding").putArray("nodeIds").add(11);first.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",1);
        ObjectNode second=chapters.addObject().put("index",1);second.putObject("binding").putArray("nodeIds").add(12);second.putArray("grants").addObject().put("timing","START").put("itemId","food").put("quantity",1);session.setModuleConfigSnapshotJson(snapshot.toString());
        send("start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"pass",true,Collections.emptyList());send("done","QUEST_RESOLVE","q1");
        assertEquals(6,stored().path("variables").path("counter.temperature").asInt());assertEquals(6,stored().path("variables").path("sys.hp").asInt());assertEquals(3,stored().path("themeRuntime").path("inventory").path("food").asInt());
        assertTrue(stored().path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());assertTrue(stored().path("themeRuntime").path("chapters").path("1").has("startedAt"));
        assertEquals(1,stored().path("themeRuntime").path("statLinks").path("audit").size());assertEquals(4,stored().path("themeRuntime").path("statLinks").path("audit").path(0).path("actualDamage").asInt());send("other-request","QUEST_RESOLVE","q1");assertEquals(6,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void eighthActionCanSucceedAndNinthCannot() throws Exception {
        ObjectNode state=(ObjectNode)stored(); state.with("variables").put("sys.actions",7); state.with("themeRuntime").with("verifiedNodes").put("11",1); state.with("themeRuntime").with("verifiedNodes").put("12",2); owner.setVariablesJson(state.toString());
        send("start","QUEST_START","q1"); service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"eight-pass",true,Collections.emptyList()); send("eight","QUEST_RESOLVE","q1"); assertEquals(8,stored().path("variables").path("sys.actions").asInt());
        assertThrows(GameSessionException.class,()->send("start-nine","QUEST_START","q2")); assertEquals(8,stored().path("variables").path("sys.actions").asInt());
    }
    @Test void zeroHpKeepsReadAndNoAutomaticEnding() throws Exception {
        ObjectNode state=(ObjectNode)stored(); state.with("variables").put("sys.hp",0); owner.setVariablesJson(state.toString());
        service.view(GameActorContext.player(8L),70L); assertFalse(stored().path("themeRuntime").has("result")); assertEquals("RUNNING",session.getStatus());
        send("recover","ITEM_USE","food"); assertEquals(3,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void zeroHpRejectsAllPaidCostsButAllowsFreeTaskAndRecovery() throws Exception {
        ObjectNode saved=(ObjectNode)stored();saved.with("variables").put("sys.hp",0);owner.setVariablesJson(saved.toString());
        for(String fee:Arrays.asList("hpCost","luckCost","actionCost","resourceCosts","statCosts")) {
            ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode quest=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0);
            quest.remove(Arrays.asList("hpCost","luckCost","actionCost","resourceCosts","statCosts"));
            if(fee.endsWith("Cost"))quest.put(fee,1);
            else if(fee.equals("resourceCosts"))quest.putArray(fee).addObject().put("itemId","food").put("quantity",1);
            else quest.putArray(fee).addObject().put("statKey","counter.temperature").put("amount",1);
            session.setModuleConfigSnapshotJson(snapshot.toString());assertEquals("GAME_HP_REQUIRED",assertThrows(GameSessionException.class,()->send("paid-"+fee,"QUEST_START","q1")).getErrorCode());
            assertEquals("GAME_HP_REQUIRED",assertThrows(GameSessionException.class,()->send("resolve-"+fee,"QUEST_RESOLVE","q1")).getErrorCode());
        }
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0)).remove(Arrays.asList("hpCost","luckCost","actionCost","resourceCosts","statCosts"));session.setModuleConfigSnapshotJson(snapshot.toString());
        send("free-start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"free-pass",true,Collections.emptyList());send("free-done","QUEST_RESOLVE","q1");
        assertEquals(0,stored().path("variables").path("sys.hp").asInt());assertEquals(0,stored().path("variables").path("sys.actions").asInt());assertFalse(stored().path("themeRuntime").has("result"));
        send("restore","ITEM_USE","food");assertEquals(3,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void zeroHpFreeLastNodeCannotBypassPaidChapterButFreeChapterCanEnd() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());
        ((ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0)).remove(Arrays.asList("hpCost","luckCost","actionCost","resourceCosts","statCosts"));
        ObjectNode chapter=((ObjectNode)snapshot.path("themeConfig")).putArray("chapters").addObject().put("index",0);
        chapter.putObject("binding").putArray("nodeIds").add(11);chapter.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",1);
        session.setModuleConfigSnapshotJson(snapshot.toString());ObjectNode saved=(ObjectNode)stored();saved.with("variables").put("sys.hp",0);owner.setVariablesJson(saved.toString());
        send("free-start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"free-pass",true,Collections.emptyList());
        String unchanged=owner.getVariablesJson();assertEquals("GAME_TASK_COST_UNAVAILABLE",assertThrows(GameSessionException.class,()->send("paid-chapter","QUEST_RESOLVE","q1")).getErrorCode());assertEquals(unchanged,owner.getVariablesJson());
        chapter.remove("statCosts");session.setModuleConfigSnapshotJson(snapshot.toString());send("free-chapter","QUEST_RESOLVE","q1");
        assertEquals(0,stored().path("variables").path("sys.hp").asInt());assertTrue(stored().path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
    }
    @Test void paidChapterCanFinishWhenTaskEnteredWithPositiveHp() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode quest=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0);
        quest.remove("statCosts");quest.put("hpCost",1);
        ObjectNode chapter=((ObjectNode)snapshot.path("themeConfig")).putArray("chapters").addObject().put("index",0);chapter.putObject("binding").putArray("nodeIds").add(11);chapter.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",1);
        session.setModuleConfigSnapshotJson(snapshot.toString());ObjectNode saved=(ObjectNode)stored();saved.with("variables").put("sys.hp",1);owner.setVariablesJson(saved.toString());
        send("start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"pass",true,Collections.emptyList());send("done","QUEST_RESOLVE","q1");
        assertEquals(0,stored().path("variables").path("sys.hp").asInt());assertEquals(7,stored().path("variables").path("counter.temperature").asInt());assertTrue(stored().path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
    }
    @Test void disabledHpPermitsFeesButStateOnlyEnabledHpStillGuardsZeroHp() throws Exception {
        for(String disabled:Arrays.asList("hp","check")) {
            setUp();ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());ObjectNode rule=(ObjectNode)snapshot.path("journeyRules");
            if(disabled.equals("hp"))rule.with("hp").put("enabled",false);else rule.put("checkEnabled",false);
            rule.remove("statLinks");
            ObjectNode quest=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0);quest.remove("statCosts");
            ((ObjectNode)quest.path("choices").path(0)).putArray("grants");
            session.setModuleConfigSnapshotJson(snapshot.toString());ObjectNode saved=(ObjectNode)stored();saved.with("variables").put("sys.hp",0);owner.setVariablesJson(saved.toString());
            if (disabled.equals("check")) {
                assertEquals("GAME_HP_REQUIRED", assertThrows(GameSessionException.class, () -> send("state-only-paid", "QUEST_START", "q1")).getErrorCode());
                quest.remove("actionCost"); session.setModuleConfigSnapshotJson(snapshot.toString());
            }
            send("start-"+disabled,"QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"pass-"+disabled,true,Collections.emptyList());send("done-"+disabled,"QUEST_RESOLVE","q1");assertEquals(disabled.equals("check") ? 0 : 1,stored().path("variables").path("sys.actions").asInt());
        }
    }
    @Test void loadPreviewAllowsDiscardOnlyFromCurrentRewardAndRequiresUnchangedVersion() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)snapshot.path("themeConfig")).put("capacity",3);session.setModuleConfigSnapshotJson(snapshot.toString());
        send("start","QUEST_START","q1");service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"pass",true,Collections.emptyList());
        ArrayNode discard=json.createArrayNode();discard.addObject().put("itemId","food").put("quantity",1);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("questKey","q1");payload.put("choiceKey","supply");payload.put("discardRewards",discard);
        String unchanged=owner.getVariablesJson();Map<String,Object> preview=service.sharedPreview(GameActorContext.player(7L),70L,"QUEST_RESOLVE",payload);assertEquals(unchanged,owner.getVariablesJson());
        Map<?,?> settlement=(Map<?,?>)preview.get("settlement");assertEquals(3,settlement.get("finalUsed"));String version=(String)settlement.get("previewVersion");
        GameSessionCommand cmd=new GameSessionCommand();cmd.setActivityId(70L);cmd.setAuthorityEpoch(1);cmd.setExpectedRevision(session.getRevision());cmd.setAction("QUEST_RESOLVE");cmd.setPayload(payload);cmd.setRequestId("no-version");
        assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(7L),cmd));assertEquals(unchanged,owner.getVariablesJson());
        cmd.setRequestId("stale-version");cmd.getPayload().put("previewVersion","stale");assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(7L),cmd));
        cmd.setRequestId("load");cmd.getPayload().put("previewVersion",version);service.command(GameActorContext.player(7L),cmd);assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());
        cmd.setExpectedRevision(session.getRevision());cmd.setRequestId("load-again");service.command(GameActorContext.player(7L),cmd);assertEquals(1,stored().path("variables").path("sys.actions").asInt());
        cmd.setRequestId("changed-discard");cmd.getPayload().put("discardRewards",json.createArrayNode());assertEquals("GAME_SLOT_COMPLETED",assertThrows(GameSessionException.class,()->service.command(GameActorContext.player(7L),cmd)).getErrorCode());
    }
    @Test void taskPreviewAndQuestProjectionRedactHiddenCounterDeltas() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)snapshot.path("journeyRules").path("attributes").path(0)).put("visible",false);
        ObjectNode game=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime");((ObjectNode)game.path("quests").path(0)).put("facilityKey","tool");
        ObjectNode facility=game.putArray("facilities").addObject().put("facilityKey","tool").put("actKey","first").put("operationNodeId",11).put("scope","OWNER").put("targetScope","OWNER").put("limit",2).put("settlementSlot","tool");
        facility.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",1);facility.putArray("when").addObject().put("var","counter.temperature").put("op","GTE").put("value",1);facility.putArray("effects").addObject().put("op","INC").put("var","counter.temperature").put("value",1);
        game.withArray("facilities").addObject().put("facilityKey","future").put("actKey","later").put("operationNodeId",13).put("scope","OWNER").put("targetScope","OWNER").put("limit",1).put("settlementSlot","future");
        game.withArray("acts").addObject().put("actKey","later").putArray("questKeys").add("future");game.withArray("quests").addObject().put("questKey","future").put("actKey","later").put("nodeId",13).put("locationKey","later").put("settlementSlot","future");
        session.setModuleConfigSnapshotJson(snapshot.toString());ObjectNode saved=(ObjectNode)stored();saved.with("themeRuntime").with("cooperation").with("facilities").putObject("tool").put("count",1);owner.setVariablesJson(saved.toString());
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("questKey","q1");payload.put("choiceKey","supply");
        String projected=json.writeValueAsString(service.sharedPreview(GameActorContext.player(7L),70L,"QUEST_RESOLVE",payload));assertFalse(projected.contains("counter.temperature"));
        String quests=json.writeValueAsString(service.view(GameActorContext.player(7L),70L).getPlayer().get("quests"));assertFalse(quests.contains("counter.temperature"));
        JsonNode projectedTools=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer().get("interactionTools"));assertEquals(1,projectedTools.size());assertEquals("tool",projectedTools.path(0).path("operationKey").asText());assertEquals(11,projectedTools.path(0).path("nodeId").asInt());assertEquals(11,projectedTools.path(0).path("operationNodeId").asInt());assertEquals(1,projectedTools.path(0).path("remainingCount").asInt());assertEquals("OWNER",projectedTools.path(0).path("scope").asText());assertFalse(projectedTools.toString().contains("counter.temperature"));assertFalse(projectedTools.toString().contains("never-project"));assertFalse(projectedTools.path(0).has("effects"));assertFalse(projectedTools.path(0).has("when"));
        JsonNode projectedQuests=json.valueToTree(service.view(GameActorContext.player(7L),70L).getPlayer().get("quests"));assertEquals("tool",projectedQuests.path(0).path("facilityKey").asText());assertEquals(1,projectedQuests.path(0).path("actionSummary").path("facility").path("remainingCount").asInt());
    }

    @Test void trustedQuestionRecoveryWaitsForTaskSettlementAndLinksOnlyNetDecrease() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0).path("statCosts").path(0)).put("amount",4);session.setModuleConfigSnapshotJson(snapshot.toString());
        ObjectNode saved=(ObjectNode)stored();saved.with("variables").put("counter.temperature",10);owner.setVariablesJson(saved.toString());
        ArrayNode effects=json.createArrayNode();effects.addObject().put("op","INC").put("var","counter.temperature").put("value",2);when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(effects);
        send("start","QUEST_START","q1");List<String[]> choice=Collections.singletonList(new String[]{"part","answer"});service.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"recover-answer",true,choice);
        assertEquals(10,stored().path("variables").path("counter.temperature").asInt());assertEquals(10,stored().path("variables").path("sys.hp").asInt());
        send("resolve","QUEST_RESOLVE","q1");assertEquals(8,stored().path("variables").path("counter.temperature").asInt());assertEquals(6,stored().path("variables").path("sys.hp").asInt());assertEquals(1,stored().path("themeRuntime").path("statLinks").path("audit").size());
        assertFalse(stored().path("themeRuntime").path("gameplay").path("pendingTaskEffects").has("q1"));
    }
    @Test void cancellingVerifiedTaskDropsPendingEffectsAndOldInstanceCannotVerifyRestart() throws Exception {
        ArrayNode effects=json.createArrayNode();effects.addObject().put("op","INC").put("var","counter.temperature").put("value",2);when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(effects);
        send("start","QUEST_START","q1");String old=instance(8,11);service.recordSharedRouteResult(8L,80L,70L,11L,old,"before-cancel",true,Collections.singletonList(new String[]{"part","answer"}));
        send("cancel","QUEST_CANCEL","q1");assertEquals(8,stored().path("variables").path("counter.temperature").asInt());assertEquals(10,stored().path("variables").path("sys.hp").asInt());assertFalse(stored().path("themeRuntime").path("gameplay").path("pendingTaskEffects").has("q1"));
        send("restart","QUEST_START","q1");assertNotEquals(old,instance(8,11));assertThrows(GameSessionException.class,()->service.recordSharedRouteResult(8L,80L,70L,11L,old,"old-proof",true,Collections.emptyList(),1));
    }

    @Test void independentOwnerFacilityDefersTrustedEffectsUntilAtomicCostAndCancelDropsThem() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());((ArrayNode)snapshot.path("journeyRules").path("statLinks").path(0).path("allowedReasons")).add("FACILITY_EFFECT");
        ObjectNode facility=((ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime")).putArray("facilities").addObject().put("facilityKey","shelter").put("actKey","first").put("scope","OWNER").put("targetScope","OWNER").put("settlementSlot","own_shelter").put("limit",1).put("operationNodeId",12);
        facility.putArray("resourceCosts").addObject().put("itemId","food").put("quantity",1);facility.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",4);session.setModuleConfigSnapshotJson(snapshot.toString());
        ArrayNode effects=json.createArrayNode();effects.addObject().put("op","INC").put("var","counter.temperature").put("value",2);when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(effects);
        GameSessionCommand cmd=new GameSessionCommand();cmd.setActivityId(70L);cmd.setAuthorityEpoch(1);cmd.setExpectedRevision(session.getRevision());cmd.setAction("OPERATION_START");cmd.setRequestId("op-start");cmd.getPayload().put("operationKind","FACILITY");cmd.getPayload().put("operationKey","shelter");service.command(GameActorContext.player(7L),cmd);
        service.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"facility-answer",true,Collections.singletonList(new String[]{"part","answer"}));assertEquals(8,stored().path("variables").path("counter.temperature").asInt());assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());
        cmd.setRequestId("op-cancel");cmd.setExpectedRevision(session.getRevision());cmd.setAction("OPERATION_CANCEL");cmd.setPayload(Collections.emptyMap());service.command(GameActorContext.player(7L),cmd);assertFalse(stored().path("themeRuntime").path("operationFacts").has("FACILITY:shelter"));
        cmd.setRequestId("op-again");cmd.setExpectedRevision(session.getRevision());cmd.setAction("OPERATION_START");Map<String,Object> p=new LinkedHashMap<>();p.put("operationKind","FACILITY");p.put("operationKey","shelter");cmd.setPayload(p);service.command(GameActorContext.player(7L),cmd);
        service.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"facility-answer-again",true,Collections.singletonList(new String[]{"part","answer"}));
        cmd.setRequestId("facility-use");cmd.setExpectedRevision(session.getRevision());cmd.setAction("FACILITY_USE");cmd.setPayload(Collections.singletonMap("facilityKey","shelter"));service.command(GameActorContext.player(7L),cmd);
        assertEquals(6,stored().path("variables").path("counter.temperature").asInt());assertEquals(6,stored().path("variables").path("sys.hp").asInt());assertEquals(0,stored().path("themeRuntime").path("inventory").path("food").asInt());assertEquals(1,stored().path("themeRuntime").path("statLinks").path("audit").size());
    }

    @Test void hostNodeCannotBypassMissingStaffCredential() throws Exception {
        station.setSourceType("HOST_NODE"); assertEquals("GAME_STATION_ARRIVAL_REQUIRED", assertThrows(GameSessionException.class,()->send("host","QUEST_START","q1")).getErrorCode());
        assertEquals(0,session.getRevision()); assertEquals(10,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void differentTeamsAndSessionsCannotReadThisOwner() {
        when(mapper.selectActiveTeamId(70L,9L)).thenReturn(99L);
        assertEquals("GAME_TEAM_REQUIRED",assertThrows(GameSessionException.class,()->service.view(GameActorContext.player(9L),70L)).getErrorCode());
        assertThrows(GameSessionException.class,()->service.sharedRouteSnapshot(8L,81L,70L,null));
        assertThrows(GameSessionException.class,()->service.recordSharedRouteResult(8L,80L,70L,11L,"game:49","stale",true,Collections.emptyList()));
    }
    @Test void teamChallengeAnyMemberCombinesHitsOnceWithoutStationOrLeader() throws Exception {
        ObjectNode snapshot=(ObjectNode)json.readTree(session.getModuleConfigSnapshotJson());
        ObjectNode policy=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("survival");policy.put("zeroHpPolicy","CHALLENGE");
        policy.putObject("zeroChallenge").put("challengeNodeId",101).put("restoreTo",4).put("limit",1).putArray("alternativeNodeIds").add(102);
        snapshot.putObject("nodeConfigs").putObject("101").putObject("ballShake").put("enabled",true).put("goal",10);session.setModuleConfigSnapshotJson(snapshot.toString());
        TopicRouteEngine.State state=json.readValue(owner.getVariablesJson(),TopicRouteEngine.State.class);state.getVariables().put("sys.hp",0);
        new GameplaySurvivalEngine().syncChallenge(snapshot,JourneyRules.parse(rules()),state);owner.setVariablesJson(json.writeValueAsString(state));
        when(mapper.selectStation(50L,101L)).thenReturn(null);when(mapper.selectReceiptForUpdate(eq(70L),eq(8L),anyString())).thenAnswer(call->receipts.get(call.getArgument(2)));
        String instance=instance(7,101);assertEquals(101,service.sharedRouteSnapshot(8L,80L,70L,101L).path("challenge").path("nodeId").asInt());assertEquals(4,service.sharedRouteSnapshot(8L,80L,70L,101L).path("challenge").path("restoreTo").asInt());assertEquals(4,json.valueToTree(service.view(GameActorContext.player(8L),70L).getPlayer().get("challenge")).path("restoreTo").asInt());
        service.assertRoutePlayable(70L,101L);service.assertSharedRouteWritable(8L,80L,70L,101L,1);
        JsonNode first=service.recordSharedZeroChallengeResult(7L,80L,70L,101L,instance,"first",false,6L,false,1);
        assertEquals(6,first.path("progress").asInt());assertEquals(0,stored().path("variables").path("sys.hp").asInt());
        JsonNode second=service.recordSharedZeroChallengeResult(8L,80L,70L,101L,instance,"second",false,4L,false,1);
        assertTrue(second.path("success").asBoolean());assertEquals(4,stored().path("variables").path("sys.hp").asInt());
        assertEquals(json.writeValueAsString(second),json.writeValueAsString(service.recordSharedZeroChallengeResult(8L,80L,70L,101L,instance,"second",false,4L,false,1)));
        assertEquals(1,stored().path("themeRuntime").path("zeroChallenges").path("used").asInt());assertEquals(0,stored().path("completedNodeIds").size());
    }

}
