package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.domain.route.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PersonalGameplayRuntimeServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper mapper;
    private IShopDayProofService arrival;
    private CmsTopicNodeMapper nodes;private CmsMemberTemplateMapper templates;
    private CmsTopic topic;
    private TopicRouteSession session;
    private final Map<String,TopicRouteAction> receipts=new HashMap<>();
    private final Map<String,Integer> originalVersions=new HashMap<>();
    static String config() {
        return "{\"schemaVersion\":1,\"capacity\":8,\"actionLimit\":8,\"replayEnabled\":true,\"items\":[{\"id\":\"food\",\"kind\":\"ITEM\",\"name\":\"口粮\",\"image\":\"https://static.chengyinhub.com/food.png\",\"stat\":\"HP\",\"delta\":3}],\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\",\"initialGrants\":[{\"itemId\":\"food\",\"quantity\":1}],\"survival\":{\"zeroHpPolicy\":\"BLOCK_PAID\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\",\"recoveries\":[{\"recoveryKey\":\"rest\",\"kind\":\"REST\",\"limit\":2,\"hpDelta\":2}]},\"acts\":[{\"actKey\":\"a\",\"questKeys\":[\"north\",\"east\"],\"completionRule\":{\"type\":\"ANY_N\",\"quantity\":1}}],\"quests\":[{\"questKey\":\"north\",\"actKey\":\"a\",\"nodeId\":11,\"locationKey\":\"gate\",\"settlementSlot\":\"north\",\"hpCost\":1,\"actionCost\":1,\"choices\":[{\"choiceKey\":\"food\",\"grants\":[{\"itemId\":\"food\",\"quantity\":1}]}]},{\"questKey\":\"east\",\"actKey\":\"a\",\"nodeId\":12,\"locationKey\":\"gate\",\"settlementSlot\":\"east\"}]}}";
    }
    @BeforeEach void setup() throws Exception {
        originalVersions.clear();receipts.clear();
        mapper=mock(TopicRouteSessionMapper.class);CmsTopicMapper topics=mock(CmsTopicMapper.class);nodes=mock(CmsTopicNodeMapper.class);templates=mock(CmsMemberTemplateMapper.class);
        CmsRegistrationMapper registrations=mock(CmsRegistrationMapper.class);arrival=mock(IShopDayProofService.class);
        service=new TopicRouteRuntimeServiceImpl(mapper,topics,nodes,templates,mock(PlayerNodeProgressMapper.class),null,registrations,null,arrival,()->1L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
        topic=new CmsTopic();topic.setId(10L);topic.setRouteMode("LINEAR");topic.setPublishedConfigVersion(1L);topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());topic.setThemeRuntimeConfigJson(config());
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        List<CmsTopicNode> list=new ArrayList<>();List<CmsMemberTemplate> tpl=new ArrayList<>();
        for(long id:Arrays.asList(11L,12L)) {CmsTopicNode n=new CmsTopicNode();n.setId(id);n.setTopicId(10L);n.setStatus(1L);n.setMerchantMemberId(30L);n.setTemplateId(id);list.add(n);when(nodes.selectCmsTopicNodeById(id)).thenReturn(n);CmsMemberTemplate t=new CmsMemberTemplate();t.setId(id);t.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"答案\",\"answerText\":\"正确\"}}");tpl.add(t);}
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(list);when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(tpl);
        topic.setPublishedRouteGraphJson(service.freezePublication(topic,null));
        CurrentRegistrationStubs.currentFromProbe(registrations);
        when(arrival.hasArrived(anyLong(),anyLong(),anyLong())).thenReturn(true);
        when(mapper.selectByOwner(0L,10L,7L,"default")).thenAnswer(i->session);when(mapper.selectCurrentByOwner(0L,10L,7L)).thenAnswer(i->session);
        when(mapper.selectAccessibleById(anyLong(),eq(7L))).thenAnswer(i->session!=null && i.getArgument(0).equals(session.getId())?session:null);
        when(mapper.insertSession(any())).thenAnswer(i->{session=i.getArgument(0);session.setId(99L);return 1;});
        when(mapper.insertAction(any())).thenAnswer(i->{TopicRouteAction a=i.getArgument(0);a.setId((long)receipts.size()+1);receipts.put(a.getActionId(),a);return 1;});
        when(mapper.selectActionByAction(eq(99L),eq(7L),anyString())).thenAnswer(i->receipts.get(i.getArgument(2)));
        when(mapper.updateActionResponse(anyLong(),anyString())).thenAnswer(i->{for(TopicRouteAction a:receipts.values())if(a.getId().equals(i.getArgument(0)))a.setResponseJson(i.getArgument(1));return 1;});
        when(mapper.updateStateCas(any())).thenReturn(1);
        service.state(7L,10L,0L);
        service.recordPlayerArrival(7L,10L,0L,11L,"scan-north",session.getVersion());
        service.recordPlayerArrival(7L,10L,0L,12L,"scan-east",session.getVersion());
    }
    @Test void gameplayBridgeKeepsActionCasResponseOrderAndReplayHasNoScoring() {
        OrientRouteSettlement bridge=mock(OrientRouteSettlement.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"orientRouteSettlement",bridge);
        when(bridge.active(topic)).thenReturn(true);when(bridge.lock(session)).thenReturn(session);
        clearInvocations(mapper);
        service.gameplayAction(7L,99L,session.getVersion(),"bridge-start","QUEST_START","north",null);
        var order=inOrder(bridge,mapper);
        order.verify(bridge).lock(session);
        order.verify(mapper).insertAction(any());
        order.verify(mapper).updateStateCas(session);
        order.verify(bridge).complete(eq(session),any());
        order.verify(mapper).updateActionResponse(anyLong(),anyString());
        clearInvocations(bridge);
        service.gameplayAction(7L,99L,0,"bridge-start","QUEST_START","north",null);
        verifyNoInteractions(bridge);
        assertThrows(ServiceException.class,()->service.gameplayAction(7L,99L,0,"bridge-start","QUEST_START","east",null));
        verifyNoInteractions(bridge);
    }
    @Test void endedThemeReadNeverInvokesSettlementOrReopensRun() throws Exception {
        finishForReplay();
        OrientRouteSettlement bridge=mock(OrientRouteSettlement.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"orientRouteSettlement",bridge);
        when(bridge.active(topic)).thenReturn(true);when(bridge.lock(session)).thenReturn(session);
        clearInvocations(mapper);
        service.themeView(7L,10L,0L,99L);
        verify(bridge,never()).complete(any(),any());
        verify(mapper,never()).updateStateCas(any());
        assertEquals(TopicRouteSession.STATUS_COMPLETED,session.getStatus());
    }
    @Test void personalR5CheckActionProjectionReadsFrozenAttemptAndLuckWithoutWriting() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());
        frozen.with("journeyRules").putObject("luck").put("enabled",true).put("init",1).put("max",1);
        ObjectNode node=frozen.with("nodeConfigs").putObject("11");node.putObject("check").put("enabled",true).put("tier","easy");node.putObject("outcomeRules").put("version",1).putObject("pass");
        session.setConfigSnapshotJson(frozen.toString());
        ObjectNode state=(ObjectNode)stored();state.with("variables").put("sys.luck",1);session.setStateJson(state.toString());
        service.gameplayAction(7L,99L,session.getVersion(),"check-start","QUEST_START","north",null);
        String instance=service.gameplayInstance(7L,10L,0L,11L);
        service.rollGameplayCheck(7L,10L,0L,11L,instance,"advanced:1:1",false);
        String before=session.getStateJson();
        assertTrue(service.gameplayCheckActions(7L,10L,0L,11L,instance,"advanced:1:1").path("allowedActions").toString().contains("REROLL_CHECK"));
        assertEquals(before,session.getStateJson());
        service.rollGameplayCheck(7L,10L,0L,11L,instance,"advanced:1:1",true);
        assertEquals("重掷次数已用完",service.gameplayCheckActions(7L,10L,0L,11L,instance,"advanced:1:1").path("disabledReason").asText());
    }
    private void finishForReplay() throws Exception {
        ObjectNode state=(ObjectNode)stored();state.put("status","COMPLETED");
        session.setStateJson(state.toString());session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        topic.setPublishedThemeRuntimeConfigJson(config());
    }
    @Test void replayReadbackRequiresFinishedSessionAndCurrentPublishedPermission() throws Exception {
        assertEquals(false,service.themeView(7L,10L,0L,99L).get("replayAllowed"));
        finishForReplay();String before=session.getStateJson();clearInvocations(mapper);
        assertEquals(true,service.themeView(7L,10L,0L,99L).get("replayAllowed"));
        ObjectNode disabled=(ObjectNode)json.readTree(config());disabled.put("replayEnabled",false);topic.setPublishedThemeRuntimeConfigJson(disabled.toString());
        assertEquals(false,service.themeView(7L,10L,0L,99L).get("replayAllowed"));
        assertEquals("此主题未开放重玩",assertThrows(ServiceException.class,()->service.replayTheme(7L,99L)).getMessage());
        assertEquals(before,session.getStateJson());verify(mapper,never()).updateStateCas(any());
    }
    @Test void replayReadbackAndCommandShareLatestSessionAndIdempotentChildRules() throws Exception {
        finishForReplay();TopicRouteSession other=new TopicRouteSession();other.setId(100L);
        when(mapper.selectCurrentByOwner(0L,10L,7L)).thenReturn(other);
        assertEquals(false,service.themeView(7L,10L,0L,99L).get("replayAllowed"));
        assertEquals("请继续或查看最新一局",assertThrows(ServiceException.class,()->service.replayTheme(7L,99L)).getMessage());
        TopicRouteSession previous=session;
        when(mapper.selectByOwner(0L,10L,7L,"replay:99")).thenReturn(previous);
        assertEquals(true,service.themeView(7L,10L,0L,99L).get("replayAllowed"));
        assertEquals(99L,service.replayTheme(7L,99L).get("sessionId"));
        verify(mapper,times(1)).insertSession(any());
    }
    @Test void replayReadbackDeniesExpiredEntitlementWithoutBlockingOwnedHistory() throws Exception {
        finishForReplay();
        CmsRegistrationMapper registrations=(CmsRegistrationMapper)org.springframework.test.util.ReflectionTestUtils.getField(service,"registrationMapper");
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertEquals(false,service.themeView(7L,10L,0L,99L).get("replayAllowed"));
        assertThrows(IllegalStateException.class,()->service.replayTheme(7L,99L));
        assertThrows(ServiceException.class,()->service.themeView(8L,10L,0L,99L));
    }
    private Map<String,Object> send(String key,String action,String content,String choice) {return service.gameplayAction(7L,99L,originalVersions.computeIfAbsent(key,k->session.getVersion()),key,action,content,choice);}
    @Test void personalFallbackCannotOpenVotingOrBypassSessionOwnership() {
        String before = session.getStateJson(); int count = receipts.size();
        assertEquals("该模式不支持投票", assertThrows(ServiceException.class, () -> service.gameplayAction(7L, 99L, session.getVersion(), "close", "PLAN_FALLBACK", null, null)).getMessage());
        assertEquals("本局不存在或无权操作", assertThrows(ServiceException.class, () -> service.gameplayAction(8L, 99L, session.getVersion(), "close", "PLAN_FALLBACK", null, null)).getMessage());
        assertEquals(before, session.getStateJson()); assertEquals(count, receipts.size());
    }
    private JsonNode stored() throws Exception {return json.readTree(session.getStateJson());}
    private JsonNode readbackQuest(String key) {
        for(JsonNode row:json.valueToTree(service.themeView(7L,10L,0L,99L)).path("quests"))if(key.equals(row.path("questKey").asText()))return row;
        throw new AssertionError("Missing quest "+key);
    }

    @Test void personalCatalogMatchesTeamCatalogWithoutRevealingInventory() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson()),theme=(ObjectNode)frozen.path("themeConfig");
        theme.withArray("items").addObject().put("id","spare").put("kind","ITEM").put("name","备用物资").put("slotCost",2);
        theme.withArray("items").addObject().put("id","medal").put("kind","MEDAL").put("name","完成勋章");
        theme.withArray("items").addObject().put("id","gear").put("kind","ITEM").put("name","穿戴物资").put("wear",true);
        session.setConfigSnapshotJson(frozen.toString());String before=session.getStateJson();
        JsonNode catalog=json.valueToTree(service.themeView(7L,10L,0L,99L)).path("itemCatalog");
        assertEquals(GameplayCooperationEngine.itemCatalog(theme),catalog);assertEquals(2,catalog.size());
        assertEquals(json.createObjectNode().put("id","food").put("name","口粮").put("slotCost",1),catalog.path(0));
        assertEquals(json.createObjectNode().put("id","spare").put("name","备用物资").put("slotCost",2),catalog.path(1));
        for(JsonNode item:catalog)assertEquals(3,item.size());assertEquals(before,session.getStateJson());
        ObjectNode state=(ObjectNode)stored();state.with("themeRuntime").with("inventory").put("food",999);session.setStateJson(state.toString());
        assertEquals(catalog,json.valueToTree(service.themeView(7L,10L,0L,99L)).path("itemCatalog"));
    }

    @Test void readbackTracksArrivalAndOnlyTheCurrentTrustedInstancePass() throws Exception {
        assertTrue(readbackQuest("north").path("enabled").asBoolean());assertFalse(readbackQuest("north").path("trustedPass").asBoolean());
        ObjectNode state=(ObjectNode)stored();state.with("themeRuntime").with("gameplay").with("arrivals").remove("11");session.setStateJson(state.toString());
        assertEquals("NOT_ARRIVED",readbackQuest("north").path("disabledReason").asText());
        service.recordPlayerArrival(7L,10L,0L,11L,"reentry-arrival",session.getVersion());
        send("reentry-start","QUEST_START","north",null);
        JsonNode pending=readbackQuest("north");assertEquals("PROOF_PENDING",pending.path("disabledReason").asText());assertFalse(pending.path("trustedPass").asBoolean());
        state=(ObjectNode)stored();state.with("variables").put("sys.passed.11",1);session.setStateJson(state.toString());
        assertFalse(readbackQuest("north").path("trustedPass").asBoolean());
        service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"reentry-pass",true,false,0,Collections.emptyList());
        JsonNode passed=readbackQuest("north");assertTrue(passed.path("trustedPass").asBoolean());assertTrue(passed.path("enabled").asBoolean());assertTrue(passed.path("disabledReason").isNull());
        assertFalse(passed.toString().contains("answerText"));assertFalse(passed.toString().contains("sources"));
        assertEquals(passed,readbackQuest("north"));
        send("reentry-cancel","QUEST_CANCEL","north",null);send("reentry-restart","QUEST_START","north",null);
        assertFalse(readbackQuest("north").path("trustedPass").asBoolean());assertEquals("PROOF_PENDING",readbackQuest("north").path("disabledReason").asText());
    }

    @Test void readbackReportsCostsCountsAndFinishedSessions() throws Exception {
        ObjectNode state=(ObjectNode)stored();state.with("variables").put("sys.hp",0);session.setStateJson(state.toString());
        assertEquals("INSUFFICIENT_RESOURCES",readbackQuest("north").path("disabledReason").asText());
        state=(ObjectNode)stored();state.with("variables").put("sys.hp",1);session.setStateJson(state.toString());
        send("recover-one","RECOVER","rest",null);send("recover-two","RECOVER","rest",null);
        JsonNode recovery=json.valueToTree(service.themeView(7L,10L,0L,99L)).path("recoveries").path(0);
        assertEquals(2,recovery.path("limit").asInt());assertEquals(2,recovery.path("count").asInt());assertEquals(0,recovery.path("remainingCount").asInt());
        assertEquals("OWNER",recovery.path("countScope").asText());assertEquals("LIMIT_REACHED",recovery.path("disabledReason").asText());
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertEquals("SESSION_ENDED",readbackQuest("north").path("disabledReason").asText());
    }

    @Test void personalToolsAndSessionToolsShareTheCommonReadbackShape() throws Exception {
        operationFacility();JsonNode view=json.valueToTree(service.themeView(7L,10L,0L,99L));
        assertEquals(view.path("tools"),view.path("interactionTools"));
        for(String field:Arrays.asList("enabled","disabledReason","limit","count","remainingCount","countScope","trustedPass","operation"))assertTrue(view.path("tools").path(0).has(field),field);
        assertEquals("OWNER",view.path("tools").path(0).path("countScope").asText());
        assertFalse(view.path("tools").path(0).path("trustedPass").asBoolean());
        send("tool-reentry","OPERATION_START","warm",null);
        assertEquals("PROOF_PENDING",json.valueToTree(service.themeView(7L,10L,0L,99L)).path("tools").path(0).path("disabledReason").asText());
        service.recordNodeResult(7L,10L,0L,12L,service.gameplayInstance(7L,10L,0L,12L),"tool-reentry-pass",true,false,0,Collections.emptyList());
        assertTrue(json.valueToTree(service.themeView(7L,10L,0L,99L)).path("tools").path(0).path("trustedPass").asBoolean());
    }
    static ObjectNode trustedEffectFixture(ObjectMapper json) throws Exception {
        return (ObjectNode)json.readTree("{\"effects\":[{\"op\":\"INC\",\"var\":\"sys.hp\",\"value\":2}],\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"options\":[{\"id\":\"yes\",\"correct\":true,\"effects\":[{\"op\":\"INC\",\"var\":\"sys.hp\",\"value\":1}]}]}}");
    }
    @Test void realFrozenRootAndChoiceEffectsMatchGuidedExtractionAndSettleOnce() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson()), block=trustedEffectFixture(json);
        frozen.with("nodeConfigs").set("11",block); NodeJourneySnapshot.copyMechanicSegments(block,frozen.with("nodeJourney").putObject("11"));
        session.setConfigSnapshotJson(frozen.toString());
        ObjectNode state=(ObjectNode)stored(); state.with("variables").put("sys.hp",4);session.setStateJson(state.toString());
        send("effects-start","QUEST_START","north",null);String instance=service.gameplayInstance(7L,10L,0L,11L);
        List<String[]> choices=Collections.singletonList(new String[]{"qa","yes"});
        service.recordNodeResult(7L,10L,0L,11L,instance,"not-ready",false,false,0,choices);
        assertFalse(stored().path("themeRuntime").path("gameplay").path("pendingTaskEffects").has("north"));
        for(String action:Arrays.asList("passed","passed","new-receipt"))service.recordNodeResult(7L,10L,0L,11L,instance,action,true,false,0,choices);
        JsonNode pending=stored().path("themeRuntime").path("gameplay").path("pendingTaskEffects").path("north").path("effects");
        assertEquals(2,pending.size());assertEquals(4,stored().path("variables").path("sys.hp").asInt());
        assertEquals(pending,service.trustedGuidedRouteEffects(frozen,11L,json.readTree("{\"qa\":{\"optionId\":\"yes\"}}")));
        send("effects-resolve","QUEST_RESOLVE","north","food");assertEquals(6,stored().path("variables").path("sys.hp").asInt());
        send("effects-resolve","QUEST_RESOLVE","north","food");assertEquals(6,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void basicRootEffectOfTwoRemainsTwoAndIsNotPaidAtArrival() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson()), block=trustedEffectFixture(json);block.remove("qa");
        frozen.with("nodeConfigs").set("11",block);frozen.with("nodeAnswers").putObject("11").put("validationMethod",4);
        NodeJourneySnapshot.copyMechanicSegments(block,frozen.with("nodeJourney").putObject("11"));session.setConfigSnapshotJson(frozen.toString());
        ObjectNode state=(ObjectNode)stored();state.with("variables").put("sys.hp",4);session.setStateJson(state.toString());
        service.recordPlayerArrival(7L,10L,0L,11L,"effects-scan",session.getVersion(),"SCAN");send("basic-effects-start","QUEST_START","north",null);
        assertEquals(4,stored().path("variables").path("sys.hp").asInt());
        send("basic-effects-resolve","QUEST_RESOLVE","north","food");assertEquals(5,stored().path("variables").path("sys.hp").asInt());
        send("basic-effects-resolve","QUEST_RESOLVE","north","food");assertEquals(5,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void frozenChoiceProjectionTakesPrecedenceWithoutBreakingLegacyDirectBlocks() throws Exception {
        ObjectNode frozen=json.createObjectNode(), block=trustedEffectFixture(json);
        ObjectNode node=frozen.putObject("nodeJourney").putObject("11");node.set("qa",block.path("qa").deepCopy());
        assertEquals(1,service.trustedChoiceEffects(frozen,11L,"qa","yes").get(0).path("value").asInt());
        NodeJourneySnapshot.copyMechanicSegments(block,node);
        ((ObjectNode)node.path("qa").path("options").get(0).path("effects").get(0)).put("value",99);
        JsonNode effects=service.trustedChoiceEffects(frozen,11L,"qa","yes");
        assertEquals(1,effects.size());assertEquals(1,effects.get(0).path("value").asInt());
    }
    @Test void anyTaskCompletesFromTrustedGroupOnlyWithOneReceiptAndWholePack() throws Exception {
        assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());
        service.themeView(7L,10L,0L,99L);assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());
        send("start","QUEST_START","east",null);assertTrue(service.canPlay(7L,10L,0L,12L));assertTrue(service.canPlay(7L,10L,0L,11L));
        assertThrows(ServiceException.class,()->service.requireOpeningAccess(7L,10L,0L,11L));
        assertThrows(ServiceException.class,()->send("early","QUEST_RESOLVE","east",null));send("cancel","QUEST_CANCEL","east",null);
        assertEquals(0,stored().path("variables").path("sys.actions").asInt());
        send("start-north","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"pass",true,false,0,Collections.emptyList());
        Map<String,Object> result=send("done","QUEST_RESOLVE","north","food");assertEquals("COMPLETED",result.get("status"));assertEquals(2,stored().path("themeRuntime").path("inventory").path("food").asInt());assertEquals(1,stored().path("variables").path("sys.actions").asInt());
        assertEquals(json.writeValueAsString(result),json.writeValueAsString(service.gameplayAction(7L,99L,originalVersions.get("done"),"done","QUEST_RESOLVE","north","food")));
        assertThrows(ServiceException.class,()->send("done","QUEST_RESOLVE","north",null));
    }
    @Test void ownerVersionArrivalAndOldRoutesFailClosedWithoutMaterialChanges() throws Exception {
        String before=session.getStateJson();
        assertThrows(ServiceException.class,()->service.gameplayAction(8L,99L,0,"owner","QUEST_START","north",null));
        assertThrows(ServiceException.class,()->service.gameplayAction(7L,99L,44,"version","QUEST_START","north",null));
        ObjectNode noArrival=(ObjectNode)stored();noArrival.with("themeRuntime").with("gameplay").remove("arrivals");session.setStateJson(noArrival.toString());before=session.getStateJson();
        assertThrows(ServiceException.class,()->send("arrival","QUEST_START","north",null));
        assertEquals(before,session.getStateJson());
        assertThrows(ServiceException.class,()->service.themeAction(7L,99L,session.getVersion(),"old","CHAPTER_END","0"));
        assertThrows(ServiceException.class,()->service.advance(7L,10L,0L,0,"old","CHOICE",new NodeOutcome(11L,"COMPLETED","receipt")));
        assertThrows(ServiceException.class,()->service.roll(7L,10L,0L,11L,"check"));
    }
    @Test void arrivalRequiresOriginalIdentityAndRejectsChangedExpectedOrKind() {
        String before=session.getStateJson();int count=receipts.size();
        assertThrows(IllegalArgumentException.class,()->service.recordPlayerArrival(7L,10L,0L,11L,null,session.getVersion()));
        assertThrows(IllegalArgumentException.class,()->service.recordPlayerArrival(7L,10L,0L,11L,"missing-version",null));
        assertEquals(before,session.getStateJson());assertEquals(count,receipts.size());
        Integer expected=session.getVersion();
        Map<String,Object> original=service.recordPlayerArrival(7L,10L,0L,11L,"arrival-identity",expected,"SCAN");
        assertEquals(original,service.recordPlayerArrival(7L,10L,0L,11L,"arrival-identity",expected,"SCAN"));
        assertThrows(ServiceException.class,()->service.recordPlayerArrival(7L,10L,0L,11L,"arrival-identity",session.getVersion(),"SCAN"));
        assertThrows(ServiceException.class,()->service.recordPlayerArrival(7L,10L,0L,11L,"arrival-identity",expected,"GPS"));
    }
    @Test void gameplayReplayRequiresOriginalExpectedEvenAfterEntityVersionChanges() {
        Integer expected=session.getVersion();
        Map<String,Object> first=service.gameplayAction(7L,99L,expected,"stable-version","QUEST_START","north",null);
        assertEquals(first,service.gameplayAction(7L,99L,expected,"stable-version","QUEST_START","north",null));
        assertThrows(ServiceException.class,()->service.gameplayAction(7L,99L,session.getVersion(),"stable-version","QUEST_START","north",null));
    }
    @Test void oldArrivalNamespaceNeverReleasesAnExecutedIdentity() {
        when(mapper.selectActionByAction(99L,7L,"arrival:SCAN:historic")).thenReturn(new TopicRouteAction());
        String before=session.getStateJson();int count=receipts.size();
        assertThrows(ServiceException.class,()->service.recordPlayerArrival(7L,10L,0L,11L,"historic",session.getVersion(),"SCAN"));
        assertEquals(before,session.getStateJson());assertEquals(count,receipts.size());
    }
    @Test void arrivalsAreBoundToThisRunAndReplayReturnsTheOriginalScanReceipt() throws Exception {
        Integer expected=session.getVersion();
        Map<String,Object> receipt=service.recordPlayerArrival(7L,10L,0L,11L,"second-scan",expected);
        assertEquals(json.writeValueAsString(receipt),json.writeValueAsString(service.recordPlayerArrival(7L,10L,0L,11L,"second-scan",expected)));
        assertThrows(ServiceException.class,()->service.recordPlayerArrival(7L,10L,0L,12L,"second-scan",session.getVersion()));
        ObjectNode fresh=(ObjectNode)stored();fresh.with("themeRuntime").with("gameplay").remove("arrivals");session.setStateJson(fresh.toString());
        assertThrows(ServiceException.class,()->send("old-proof","QUEST_START","north",null));
        verify(arrival,never()).hasArrived(anyLong(),anyLong(),anyLong());
    }
    @Test void trustedTaskEffectsWaitForResolveAndProduceOneNetDecline() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode quest=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("quests").path(0);quest.put("hpCost",0);
        quest.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",4);
        frozen.with(NodeJourneySnapshot.NODE_JOURNEY_KEY).with("11").with(NodeJourneySnapshot.CHOICE_EFFECTS_KEY).with("sort").putArray("effects").addObject().put("op","INC").put("var","counter.temperature").put("value",2);
        session.setConfigSnapshotJson(frozen.toString());send("start-net","QUEST_START","north",null);
        String instance=service.gameplayInstance(7L,10L,0L,11L);
        service.recordNodeResult(7L,10L,0L,11L,instance,"pass-net",true,false,0,Collections.singletonList(new String[]{"sort",null}));
        assertEquals(8,stored().path("variables").path("counter.temperature").asInt());assertEquals(10,stored().path("variables").path("sys.hp").asInt());
        send("done-net","QUEST_RESOLVE","north","food");assertEquals(6,stored().path("variables").path("counter.temperature").asInt());assertEquals(6,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void cancelledTaskCannotReuseOldTrustedProofOrAdvancedInstance() throws Exception {
        send("first","QUEST_START","north",null);String first=service.gameplayInstance(7L,10L,0L,11L);
        service.recordNodeResult(7L,10L,0L,11L,first,"first-pass",true,false,0,Collections.emptyList());send("cancel","QUEST_CANCEL","north",null);send("second","QUEST_START","north",null);
        assertNotEquals(first,service.gameplayInstance(7L,10L,0L,11L));assertThrows(ServiceException.class,()->service.recordNodeResult(7L,10L,0L,11L,first,"stale-pass",true,false,0,Collections.emptyList()));
        assertThrows(ServiceException.class,()->send("stale-resolve","QUEST_RESOLVE","north","food"));
    }
    @Test void fullRecoveryIsFreeAndRealRecoveryUsesOneLimit() throws Exception {
        ObjectNode state=(ObjectNode)stored();state.with("variables").put("sys.hp",12);session.setStateJson(state.toString());
        Map<String,Object> noEffect=send("full","RECOVER","rest",null);assertEquals(false,((Map)noEffect.get("settlement")).get("consumed"));
        assertEquals(0,stored().path("themeRuntime").path("recoveries").path("rest").asInt());
        state=(ObjectNode)stored();state.with("variables").put("sys.hp",0);session.setStateJson(state.toString());
        send("recover","RECOVER","rest",null);assertEquals(2,stored().path("variables").path("sys.hp").asInt());assertEquals(1,stored().path("themeRuntime").path("recoveries").path("rest").asInt());
        assertEquals(0,stored().path("variables").path("sys.actions").asInt());
    }
    @Test void anyNChargesWholeChapterOnceWithoutCompletingEveryOptionalNode() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode chapter=((ObjectNode)frozen.path("themeConfig")).putArray("chapters").addObject().put("index",0).put("hpCost",1);
        chapter.putObject("binding").putArray("nodeIds").add(11).add(12);session.setConfigSnapshotJson(frozen.toString());
        send("start","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"pass",true,false,0,Collections.emptyList());
        Map<String,Object> result=send("finish","QUEST_RESOLVE","north","food");assertEquals(8,((Number)result.get("hp")).intValue());
        assertTrue(stored().path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());assertEquals(1,stored().path("completedNodeIds").size());
    }
    @Test void ownerFacilityAndBoundQuestCommitAllCostsOrNeither() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");
        ObjectNode facility=game.putArray("facilities").addObject().put("facilityKey","warm").put("actKey","a").put("scope","OWNER").put("targetScope","OWNER").put("settlementSlot","warm").put("limit",1).put("hpDelta",2);
        facility.putArray("resourceCosts").addObject().put("itemId","food").put("quantity",1);
        ObjectNode quest=(ObjectNode)game.path("quests").path(0);quest.put("facilityKey","warm");quest.putArray("resourceCosts").addObject().put("itemId","food").put("quantity",1);session.setConfigSnapshotJson(frozen.toString());
        send("start","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"pass",true,false,0,Collections.emptyList());
        String unchanged=session.getStateJson();assertThrows(ServiceException.class,()->send("shortage","QUEST_RESOLVE","north","food"));assertEquals(unchanged,session.getStateJson());
        ObjectNode state=(ObjectNode)stored();state.with("themeRuntime").with("inventory").put("food",2);session.setStateJson(state.toString());
        Map<String,Object> result=send("finish","QUEST_RESOLVE","north","food");assertEquals(11,((Number)result.get("hp")).intValue());assertEquals(1,((Number)result.get("used")).intValue());
        assertEquals(1,stored().path("themeRuntime").path("cooperation").path("facilities").path("warm").path("count").asInt());
    }
    @Test void rewardDiscardNeedsFreshOwnerBoundPreviewAndNeverDropsExistingInventory() throws Exception {
        send("start","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"pass",true,false,0,Collections.emptyList());
        JsonNode discard=json.readTree("[{\"itemId\":\"food\",\"quantity\":1}]");
        Map<String,Object> preview=service.gameplayPreview(7L,99L,session.getVersion(),"north","food",discard);String token=(String)preview.get("previewVersion");
        assertThrows(ServiceException.class,()->service.gameplayAction(7L,99L,session.getVersion(),"missing","QUEST_RESOLVE","north","food",discard,null));
        ObjectNode changed=(ObjectNode)stored();changed.with("variables").put("sys.hp",9);session.setStateJson(changed.toString());
        assertThrows(ServiceException.class,()->service.gameplayAction(7L,99L,session.getVersion(),"stale","QUEST_RESOLVE","north","food",discard,token));
        preview=service.gameplayPreview(7L,99L,session.getVersion(),"north","food",discard);
        Map<String,Object> result=service.gameplayAction(7L,99L,session.getVersion(),"fresh","QUEST_RESOLVE","north","food",discard,(String)preview.get("previewVersion"));
        assertEquals(1,((Number)result.get("used")).intValue());assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());
    }
    @Test void previewsAndAllowedActionsDoNotRevealHiddenAttributesOrChangeState() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());((ObjectNode)frozen.path("journeyRules").path("attributes").path(0)).put("visible",false);
        ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("quests").path(0)).putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",1);session.setConfigSnapshotJson(frozen.toString());
        String before=session.getStateJson();Map<String,Object> preview=service.gameplayPreview(7L,99L,session.getVersion(),"north","food",null);
        assertFalse(json.writeValueAsString(preview).contains("counter.temperature"));assertFalse(json.writeValueAsString(service.themeView(7L,10L,0L,99L)).contains("counter.temperature"));assertEquals(before,session.getStateJson());
    }

    @Test void activeTaskKeepsReservedInventoryAndBalanceUntilResolveOrCancel() throws Exception {
        send("start-lock","QUEST_START","north",null);String before=session.getStateJson();
        assertThrows(ServiceException.class,()->service.themeAction(7L,99L,session.getVersion(),"use-locked","USE","food"));
        assertThrows(ServiceException.class,()->service.themeAction(7L,99L,session.getVersion(),"discard-locked","DISCARD","food"));
        assertThrows(ServiceException.class,()->send("rest-locked","RECOVER","rest",null));assertThrows(ServiceException.class,()->send("facility-locked","FACILITY_USE","warm",null));assertEquals(before,session.getStateJson());
        Map<String,Object> projection=service.themeView(7L,10L,0L,99L);String view=json.writeValueAsString(projection.get("allowedActions"));assertFalse(view.contains("RECOVER"));assertFalse(view.contains("FACILITY_USE"));
        assertEquals("TASK_ACTIVE",json.valueToTree(projection.get("recoveries")).path(0).path("disabledReason").asText());
        send("cancel-lock","QUEST_CANCEL","north",null);send("rest-unlocked","RECOVER","rest",null);
    }

    private void baseMethod(long nodeId,int method) throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());frozen.with("nodeConfigs").set(String.valueOf(nodeId),json.createObjectNode().put("schemaVersion",1));frozen.with(NodeJourneySnapshot.NODE_ANSWERS_KEY).with(String.valueOf(nodeId)).put("validationMethod",method);session.setConfigSnapshotJson(frozen.toString());
    }
    @Test void scanIsTheBasicTaskAndItsCurrentStartConsumesTheRealRunReceipt() throws Exception {
        baseMethod(11L,4);service.recordPlayerArrival(7L,10L,0L,11L,"base-scan",session.getVersion(),"SCAN");send("scan-task","QUEST_START","north",null);
        assertTrue(stored().path("themeRuntime").path("verifiedNodes").path("11").asLong()>0);assertEquals("gameplay:scan-task",stored().path("themeRuntime").path("gameplay").path("arrivalFacts").path("11").path("consumedBy").asText());
        send("cancel-scan","QUEST_CANCEL","north",null);assertThrows(ServiceException.class,()->send("reuse-scan","QUEST_START","north",null));
        service.recordPlayerArrival(7L,10L,0L,11L,"fresh-scan",session.getVersion(),"SCAN");send("fresh-start","QUEST_START","north",null);Map<String,Object> result=send("scan-resolve","QUEST_RESOLVE","north","food");assertEquals("COMPLETED",result.get("status"));assertEquals(9,((Number)result.get("hp")).intValue());
    }
    @Test void publishedScanAndObjectiveIgnoreEnabledAddonsFromEditorDefaults() throws Exception {
        for(CmsMemberTemplate template:templates.selectCmsMemberTemplateByIds(Arrays.asList(11L,12L))) {
            if(template.getId()==11L) {
                template.setValidationMethod(4);
                template.setAdvancedConfigJson("{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"欢迎\",\"arMode\":\"NONE\"},\"timer\":{\"enabled\":true,\"durationSeconds\":300,\"timeoutResult\":\"FAILED\"},\"musicCorner\":{\"enabled\":true}}");
            } else template.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"答案\",\"answerText\":\"正确\"},\"leaderboard\":{\"enabled\":true},\"dailySign\":{\"enabled\":true}}");
        }
        assertDoesNotThrow(()->service.freezePublication(topic,null));
    }
    @Test void personalReceiveUsesOnlyOwnedPendingSourceAndRechecksCapacity() throws Exception {
        ObjectNode state=(ObjectNode)stored();state.with("themeRuntime").with("pending").putObject("quest:reward").put("itemId","food").put("quantity",1);session.setStateJson(state.toString());
        assertThrows(IllegalStateException.class,()->service.themeAction(7L,99L,session.getVersion(),"wrong-source","RECEIVE","food"));
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());((ObjectNode)frozen.path("themeConfig")).put("capacity",1);session.setConfigSnapshotJson(frozen.toString());
        assertThrows(IllegalStateException.class,()->service.themeAction(7L,99L,session.getVersion(),"full-receive","RECEIVE","quest:reward"));
        assertEquals(1,stored().path("themeRuntime").path("pending").path("quest:reward").path("quantity").asInt());
        ((ObjectNode)frozen.path("themeConfig")).put("capacity",8);session.setConfigSnapshotJson(frozen.toString());
        int version=session.getVersion();Map<String,Object> received=service.themeAction(7L,99L,version,"receive","RECEIVE","quest:reward");
        assertEquals(2,stored().path("themeRuntime").path("inventory").path("food").asInt());
        assertFalse(stored().path("themeRuntime").path("pending").has("quest:reward"));
        assertEquals(json.writeValueAsString(received),json.writeValueAsString(service.themeAction(7L,99L,version,"receive","RECEIVE","quest:reward")));
        assertThrows(ServiceException.class,()->service.themeAction(7L,99L,version,"receive","RECEIVE","other"));
    }
    @Test void standaloneFacilityPreviewAndExplicitRewardDiscardUseSameSettlement() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson()),theme=(ObjectNode)frozen.path("themeConfig");theme.put("capacity",1);
        ObjectNode facility=((ObjectNode)theme.path("gameplayRuntime")).putArray("facilities").addObject().put("facilityKey","supplies").put("actKey","a").put("scope","OWNER").put("targetScope","OWNER").put("settlementSlot","supplies").put("limit",1);
        facility.putArray("resourceCosts").addObject().put("itemId","food").put("quantity",1);facility.putArray("grants").addObject().put("itemId","food").put("quantity",2);session.setConfigSnapshotJson(frozen.toString());
        JsonNode preview=json.valueToTree(service.gameplayPreview(7L,99L,session.getVersion(),"supplies",null,null,null,"FACILITY_USE"));assertTrue(preview.path("overflow").asBoolean());assertEquals(2,preview.path("rewardOwners").path(0).path("grants").path("food").asInt());
        ArrayNode discard=json.createArrayNode();discard.addObject().put("itemId","food").put("quantity",1);preview=json.valueToTree(service.gameplayPreview(7L,99L,session.getVersion(),"supplies",null,discard,null,"FACILITY_USE"));assertFalse(preview.path("overflow").asBoolean());
        service.gameplayAction(7L,99L,session.getVersion(),"use-supplies","FACILITY_USE","supplies",null,discard,preview.path("previewVersion").asText());assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());assertTrue(stored().path("themeRuntime").path("pending").isEmpty());
    }

    @Test void fullBagChapterEndPreviewMustResolveBeforePersonalCompletes() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson()),theme=(ObjectNode)frozen.path("themeConfig");theme.put("capacity",1);
        ObjectNode chapter=theme.putArray("chapters").addObject().put("index",0);chapter.putObject("binding").putArray("nodeIds").add(11).add(12);chapter.putArray("grants").addObject().put("timing","END").put("itemId","food").put("quantity",1);session.setConfigSnapshotJson(frozen.toString());
        send("start-last","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"passed-last",true,false,0,Collections.emptyList());
        JsonNode preview=json.valueToTree(service.gameplayPreview(7L,99L,session.getVersion(),"north","food",null));assertEquals(2,preview.path("grants").path("food").asInt());assertTrue(preview.path("overflow").asBoolean());
        String before=session.getStateJson();assertThrows(ServiceException.class,()->send("full-last","QUEST_RESOLVE","north","food"));assertEquals(before,session.getStateJson());assertEquals(TopicRouteSession.STATUS_RUNNING,session.getStatus());
        ArrayNode discard=json.createArrayNode();discard.addObject().put("itemId","food").put("quantity",2);preview=json.valueToTree(service.gameplayPreview(7L,99L,session.getVersion(),"north","food",discard));
        service.gameplayAction(7L,99L,session.getVersion(),"done-last","QUEST_RESOLVE","north","food",discard,preview.path("previewVersion").asText());
        assertEquals(TopicRouteSession.STATUS_COMPLETED,session.getStatus());assertTrue(stored().path("themeRuntime").path("pending").isEmpty());assertTrue(stored().path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
    }

    @Test void completedPendingClaimWorksAfterReplayAndRejectsOtherActions() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());((ObjectNode)frozen.path("themeConfig")).put("capacity",1);session.setConfigSnapshotJson(frozen.toString());
        ObjectNode state=(ObjectNode)stored();state.put("status","COMPLETED");state.with("themeRuntime").with("sources").put("chapter:0:END:food",true);state.with("themeRuntime").with("pending").putObject("chapter:0:END:food").put("itemId","food").put("quantity",1);state.with("themeRuntime").putObject("result").put("hp",5);session.setStateJson(state.toString());session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        TopicRouteSession newer=new TopicRouteSession();newer.setId(100L);when(mapper.selectCurrentByOwner(0L,10L,7L)).thenReturn(newer);
        assertThrows(ServiceException.class,()->service.themeAction(7L,99L,session.getVersion(),"use-after","USE","food"));assertThrows(ServiceException.class,()->service.themeAction(7L,99L,session.getVersion(),"discard-after","DISCARD","food"));
        String before=session.getStateJson();assertThrows(IllegalStateException.class,()->service.themeAction(7L,99L,session.getVersion(),"full-after","RECEIVE","chapter:0:END:food"));assertEquals(before,session.getStateJson());
        ArrayNode owned=json.createArrayNode();owned.addObject().put("itemId","food").put("quantity",1);int version=session.getVersion();Map<String,Object> receipt=service.themeAction(7L,99L,version,"claim-after","RECEIVE","chapter:0:END:food",owned);
        assertEquals(1,stored().path("themeRuntime").path("inventory").path("food").asInt());assertTrue(stored().path("themeRuntime").path("pending").isEmpty());assertEquals(state.path("themeRuntime").path("result"),stored().path("themeRuntime").path("result"));assertEquals(state.path("variables").path("sys.hp"),stored().path("variables").path("sys.hp"));
        assertEquals(json.writeValueAsString(receipt),json.writeValueAsString(service.themeAction(7L,99L,version,"claim-after","RECEIVE","chapter:0:END:food",owned)));
        assertThrows(ServiceException.class,()->service.themeAction(7L,99L,version,"claim-after","RECEIVE","chapter:0:END:food",null));
    }

    @Test void personalCostOptionReadbackAndCommandUseOnlyFrozenGroup() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode q=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("quests").get(0);q.remove("hpCost");q.remove("actionCost");
        ArrayNode options=q.putArray("costOptions");options.addObject().put("label","物资").putArray("resourceCosts").addObject().put("itemId","food").put("quantity",2);options.addObject().put("label","数值").put("hpCost",2);session.setConfigSnapshotJson(frozen.toString());
        JsonNode actions=json.valueToTree(service.themeView(7L,10L,0L,99L)).path("allowedActions");JsonNode action=null;for(JsonNode row:actions)if("north".equals(row.path("contentId").asText()))action=row;
        assertNotNull(action);assertFalse(action.path("costOptions").get(0).path("affordable").asBoolean());assertTrue(action.path("costOptions").get(1).path("affordable").asBoolean());
        assertEquals("ITEM",action.path("costOptions").path(0).path("shortfall").path(0).path("type").asText());assertEquals("food",action.path("costOptions").path(0).path("shortfall").path(0).path("key").asText());assertEquals(1,action.path("costOptions").path(0).path("shortfall").path(0).path("missing").asInt());assertTrue(action.path("costOptions").path(1).path("shortfall").isEmpty());
        service.gameplayAction(7L,99L,session.getVersion(),"selected","QUEST_START","north",null,null,null,1);
        assertThrows(ServiceException.class,()->service.gameplayAction(7L,99L,0,"selected","QUEST_START","north",null,null,null,0));
        service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"passed-option",true,false,0,Collections.emptyList());
        ObjectNode before=(ObjectNode)stored(),unavailable=before.deepCopy();unavailable.with("variables").put("sys.hp",0);unavailable.with("themeRuntime").with("inventory").put("food",2);session.setStateJson(unavailable.toString());
        JsonNode locked=readbackQuest("north");assertTrue(locked.path("costOptions").path(0).path("affordable").asBoolean());assertEquals("INSUFFICIENT_RESOURCES",locked.path("disabledReason").asText());
        session.setStateJson(before.toString());
        Map<String,Object> result=send("resolve-option","QUEST_RESOLVE","north","food");assertEquals(1,((Map)result.get("settlement")).get("costOption"));
        assertEquals(8,stored().path("variables").path("sys.hp").asInt());assertEquals(2,stored().path("themeRuntime").path("inventory").path("food").asInt());
    }
    @Test void gpsKindDoesNotReuseScanAndBasicOperationNeedsFreshGpsEachTime() throws Exception {
        operationFacility();baseMethod(12L,5);assertThrows(ServiceException.class,()->send("scan-is-not-gps","OPERATION_START","warm",null));
        service.recordPlayerArrival(7L,10L,0L,12L,"gps-first",session.getVersion(),"GPS");send("gps-op","OPERATION_START","warm",null);assertTrue(stored().path("themeRuntime").path("gameplay").path("activeOperation").path("verified").asBoolean());
        send("gps-use","FACILITY_USE","warm",null);assertThrows(ServiceException.class,()->send("reuse-gps","OPERATION_START","warm",null));
        service.recordPlayerArrival(7L,10L,0L,12L,"gps-next",session.getVersion(),"GPS");send("gps-new-op","OPERATION_START","warm",null);send("gps-new-use","FACILITY_USE","warm",null);assertEquals(2,stored().path("themeRuntime").path("cooperation").path("facilities").path("warm").path("count").asInt());
    }
    @Test void basicScanAndGpsPublicationFreezeTheRealValidationWithoutAddingQa() throws Exception {
        for(CmsMemberTemplate template:templates.selectCmsMemberTemplateByIds(Arrays.asList(11L,12L))){template.setValidationMethod(4);template.setAdvancedConfigJson("{\"schemaVersion\":1}");}
        String scan=service.freezePublication(topic,null);assertEquals(4,json.readTree(scan).path(NodeJourneySnapshot.NODE_ANSWERS_KEY).path("11").path("validationMethod").asInt());
        for(CmsMemberTemplate template:templates.selectCmsMemberTemplateByIds(Arrays.asList(11L,12L)))template.setValidationMethod(5);
        assertThrows(IllegalArgumentException.class,()->service.freezePublication(topic,null));
        for(long id:Arrays.asList(11L,12L)){nodes.selectCmsTopicNodeById(id).setLongitude("121.47");nodes.selectCmsTopicNodeById(id).setLatitude("31.23");}
        session.setConfigSnapshotJson(service.freezePublication(topic,null));nodes.selectCmsTopicNodeById(11L).setLongitude("NaN");assertEquals("121.47",service.nodeArrivalConfig(7L,10L,0L,11L).path("longitude").asText());assertThrows(IllegalArgumentException.class,()->service.freezePublication(topic,null));
    }

    private void operationFacility() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");
        ObjectNode facility=game.putArray("facilities").addObject().put("facilityKey","warm").put("actKey","a").put("scope","OWNER").put("targetScope","OWNER").put("settlementSlot","warm").put("limit",2).put("operationNodeId",12);
        facility.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",4);
        ((ArrayNode)frozen.path("journeyRules").path("statLinks").path(0).path("allowedReasons")).add("FACILITY_EFFECT");
        frozen.with(NodeJourneySnapshot.NODE_JOURNEY_KEY).with("12").with(NodeJourneySnapshot.CHOICE_EFFECTS_KEY).with("sort").putArray("effects").addObject().put("op","INC").put("var","counter.temperature").put("value",2);
        session.setConfigSnapshotJson(frozen.toString());
    }
    @Test void standaloneOperationUsesRealArrivalFreshInstanceAndAtomicNetEffects() throws Exception {
        operationFacility();assertEquals(12,json.valueToTree(service.themeView(7L,10L,0L,99L)).path("tools").path(0).path("operationNodeId").asInt());
        ObjectNode missing=(ObjectNode)stored();missing.with("themeRuntime").with("gameplay").with("arrivals").remove("12");session.setStateJson(missing.toString());
        assertThrows(ServiceException.class,()->send("missing-arrival","OPERATION_START","warm",null));service.recordPlayerArrival(7L,10L,0L,12L,"operation-scan",session.getVersion());
        send("op-start","OPERATION_START","warm",null);String first=service.gameplayInstance(7L,10L,0L,12L);service.requireOpeningAccess(7L,10L,0L,12L);
        assertThrows(ServiceException.class,()->send("early-use","FACILITY_USE","warm",null));assertThrows(ServiceException.class,()->send("task-locked","QUEST_START","north",null));assertThrows(ServiceException.class,()->send("recover-locked","RECOVER","rest",null));
        service.recordNodeResult(7L,10L,0L,12L,first,"operation-pass",true,false,0,Collections.singletonList(new String[]{"sort",null}));
        assertEquals(8,stored().path("variables").path("counter.temperature").asInt());assertEquals(10,stored().path("variables").path("sys.hp").asInt());
        assertFalse(json.writeValueAsString(service.themeView(7L,10L,0L,99L)).contains("sources"));
        send("op-cancel","OPERATION_CANCEL","warm",null);send("op-restart","OPERATION_START","warm",null);String next=service.gameplayInstance(7L,10L,0L,12L);assertNotEquals(first,next);
        assertThrows(ServiceException.class,()->service.recordNodeResult(7L,10L,0L,12L,first,"stale-op",true,false,0,Collections.emptyList()));assertThrows(ServiceException.class,()->send("old-use","FACILITY_USE","warm",null));
        service.recordNodeResult(7L,10L,0L,12L,next,"current-op",true,false,0,Collections.singletonList(new String[]{"sort",null}));
        send("op-use","FACILITY_USE","warm",null);assertEquals(6,stored().path("variables").path("counter.temperature").asInt());assertEquals(6,stored().path("variables").path("sys.hp").asInt());assertFalse(stored().path("themeRuntime").path("gameplay").has("activeOperation"));assertFalse(stored().path("themeRuntime").path("operationFacts").has("FACILITY:warm"));
        assertThrows(ServiceException.class,()->send("proof-twice","FACILITY_USE","warm",null));
    }
    @Test void operationChoiceDeclineUsesTemplateReasonWithoutFacilityCosts() throws Exception {
        operationFacility();ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0)).remove("statCosts");
        ((ObjectNode)frozen.path(NodeJourneySnapshot.NODE_JOURNEY_KEY).path("12").path(NodeJourneySnapshot.CHOICE_EFFECTS_KEY).path("sort").path("effects").path(0)).put("value",-2);
        ((ObjectNode)frozen.path("journeyRules").path("statLinks").path(0)).putArray("allowedReasons").add("TEMPLATE_EFFECT");session.setConfigSnapshotJson(frozen.toString());
        send("template-start","OPERATION_START","warm",null);service.recordNodeResult(7L,10L,0L,12L,service.gameplayInstance(7L,10L,0L,12L),"template-pass",true,false,0,Collections.singletonList(new String[]{"sort",null}));
        send("template-use","FACILITY_USE","warm",null);assertEquals(6,stored().path("variables").path("counter.temperature").asInt());assertEquals(6,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void stateEnabledValuesAndOverflowPacksKeepARealPreviewEntry() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());((ObjectNode)frozen.path("journeyRules")).put("checkEnabled",false);((ObjectNode)frozen.path("themeConfig")).put("capacity",1);session.setConfigSnapshotJson(frozen.toString());
        send("start-overflow","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"pass-overflow",true,false,0,Collections.emptyList());
        Map<String,Object> view=service.themeView(7L,10L,0L,99L);assertEquals(true,view.get("hpEnabled"));assertTrue(json.writeValueAsString(view.get("allowedActions")).contains("GAMEPLAY_PREVIEW"));
        Map<String,Object> preview=service.gameplayPreview(7L,99L,session.getVersion(),"north","food",null);assertEquals(true,preview.get("overflow"));
        JsonNode discard=json.readTree("[{\"itemId\":\"food\",\"quantity\":1}]");preview=service.gameplayPreview(7L,99L,session.getVersion(),"north","food",discard);
        service.gameplayAction(7L,99L,session.getVersion(),"drop-overflow","QUEST_RESOLVE","north","food",discard,(String)preview.get("previewVersion"));assertEquals(9,stored().path("variables").path("sys.hp").asInt());
    }
    @Test void guidedEffectsComeOnlyFromVerifiedFrozenOptions() throws Exception {
        ObjectNode frozen=json.createObjectNode();frozen.with("nodeConfigs").set("11",json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"options\":[{\"id\":\"yes\",\"correct\":true},{\"id\":\"no\",\"correct\":false}]}}"));
        frozen.with(NodeJourneySnapshot.NODE_JOURNEY_KEY).with("11").with(NodeJourneySnapshot.CHOICE_EFFECTS_KEY).with("qa").putArray("options").addObject().put("id","yes").putArray("effects").addObject().put("op","INC").put("var","counter.temperature").put("value",2);
        JsonNode answer=json.readTree("{\"qa\":{\"optionId\":\"yes\",\"effects\":[{\"op\":\"INC\",\"var\":\"sys.hp\",\"value\":999}]}}");JsonNode effects=service.trustedGuidedRouteEffects(frozen,11L,answer);assertEquals(2,effects.path(0).path("value").asInt());assertEquals("counter.temperature",effects.path(0).path("var").asText());
        assertThrows(IllegalArgumentException.class,()->service.trustedGuidedRouteEffects(frozen,11L,json.readTree("{\"qa\":{\"optionId\":\"no\"}}")));
    }

    @Test void publicationFreezeAndCopyRemapKeepTheActualTaskRules() throws Exception {
        topic.setThemeRuntimeConfigJson("{}");topic.setJourneyRulesJson(null);
        send("frozen-start","QUEST_START","north",null);service.recordNodeResult(7L,10L,0L,11L,service.gameplayInstance(7L,10L,0L,11L),"frozen-pass",true,false,0,Collections.emptyList());
        assertEquals(9,((Number)send("frozen-done","QUEST_RESOLVE","north","food").get("hp")).intValue());
        Map<Long,Long> ids=new HashMap<>();ids.put(11L,111L);ids.put(12L,112L);
        JsonNode copied=json.readTree(ThemeRuntimeConfig.remapAll(config(),ids,Collections.emptyMap(),Collections.emptyMap()));
        assertEquals(111,copied.path("gameplayRuntime").path("quests").path(0).path("nodeId").asInt());assertEquals(112,copied.path("gameplayRuntime").path("quests").path(1).path("nodeId").asInt());
        assertEquals(1,copied.path("gameplayRuntime").path("initialGrants").path(0).path("quantity").asInt());
    }

    @Test void guidedGroupReusesAllFourJudgesRejectingPartialAndForgedPassed() throws Exception {
        ObjectNode config=(ObjectNode)json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"correct\"},\"sort\":{\"enabled\":true,\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"answerOrder\":[\"b\",\"a\"]},\"match\":{\"enabled\":true,\"left\":[{\"id\":\"a\"}],\"right\":[{\"id\":\"b\"}],\"pairs\":[[\"a\",\"b\"]]},\"classify\":{\"enabled\":true,\"items\":[{\"id\":\"a\"}],\"bins\":[{\"id\":\"b\"}],\"answer\":{\"a\":\"b\"}}}");
        ObjectNode answers=(ObjectNode)json.readTree("{\"qa\":{\"input\":\"correct\"},\"sort\":{\"order\":[\"b\",\"a\"]},\"match\":{\"pairs\":[[\"a\",\"b\"]]},\"classify\":{\"placement\":{\"a\":\"b\"}}}");
        assertTrue(AdvancedGameRuntimeServiceImpl.verifyObjectiveAnswers(config,answers));answers.with("qa").put("input","wrong");assertFalse(AdvancedGameRuntimeServiceImpl.verifyObjectiveAnswers(config,answers));
        answers.remove("sort");assertThrows(IllegalArgumentException.class,()->AdvancedGameRuntimeServiceImpl.verifyObjectiveAnswers(config,answers));answers.putObject("sort").put("passed",true);assertThrows(IllegalArgumentException.class,()->AdvancedGameRuntimeServiceImpl.verifyObjectiveAnswers(config,answers));
    }
    @Test void templateRulesCompileRealNodesOnceAndKeepLegacyWithoutRules() throws Exception {
        topic.setThemeRuntimeConfigJson(null);assertNull(service.compileTemplateRules(topic));
        for(long id:Arrays.asList(11L,12L)) {CmsMemberTemplate template=templates.selectCmsMemberTemplateByIds(Collections.emptyList()).stream().filter(row->row.getId()==id).findFirst().get();template.setValidationMethod(4);template.setAdvancedConfigJson("{\"schemaVersion\":1}");}
        CmsMemberTemplate first=templates.selectCmsMemberTemplateByIds(Collections.emptyList()).get(0);first.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":2,\"limit\":2}}");
        String compiled=service.compileTemplateRules(topic);JsonNode config=json.readTree(compiled);assertEquals(2,config.path("gameplayRuntime").path("quests").size());assertEquals("ALL",config.path("gameplayRuntime").path("acts").path(0).path("completionRule").path("type").asText());
        assertEquals(11,config.path("gameplayRuntime").path("facilities").path(0).path("operationNodeId").asInt());assertEquals("tpl_node_11",config.path("gameplayRuntime").path("quests").path(0).path("facilityKey").asText());assertFalse(config.path("gameplayRuntime").path("quests").path(0).has("hpCost"));
        topic.setThemeRuntimeConfigJson(compiled);assertEquals(config,json.readTree(service.compileTemplateRules(topic)));JsonNode frozen=json.readTree(service.freezePublication(topic,null));assertEquals(2,frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
        first.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":3,\"limit\":2}}");assertEquals(3,json.readTree(service.compileTemplateRules(topic)).path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
        first.setAdvancedConfigJson("{\"schemaVersion\":1}");assertEquals(0,json.readTree(service.compileTemplateRules(topic)).path("gameplayRuntime").path("facilities").size());
    }
    @Test void basicTextAndPickAnswersBindFreshControlledProofWithoutAdvanced() throws Exception {
        for(int method:Arrays.asList(1,3)) {
            ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());frozen.with("nodeConfigs").putObject("11");frozen.with(NodeJourneySnapshot.NODE_ANSWERS_KEY).putObject("11").put("validationMethod",method).put("questionAnswer","Ａ B").put("correctAnswer","C");session.setConfigSnapshotJson(frozen.toString());
            assertTrue(service.verifyGuidedRouteAnswer(frozen,11L,json.createObjectNode().put("answer",method==1?"ab":"c")));assertFalse(service.verifyGuidedRouteAnswer(frozen,11L,json.createObjectNode().put("passed",true)));
            assertThrows(ServiceException.class,()->send("answer-no-origin-"+method,"QUEST_START","north",null));service.recordPlayerArrival(7L,10L,0L,11L,"answer-origin-"+method,session.getVersion(),"ANSWER");
            send("answer-start-"+method,"QUEST_START","north",null);assertTrue(stored().path("themeRuntime").path("verifiedNodes").path("11").asLong()>0);send("answer-cancel-"+method,"QUEST_CANCEL","north",null);assertThrows(ServiceException.class,()->send("answer-stale-"+method,"QUEST_START","north",null));
        }
    }

    @Test void boundTemplateFirstUseThenRepeatUsesFreshProofAndNeverRepeatsChapterProgress() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");((ObjectNode)game.path("acts").path(0)).putObject("completionRule").put("type","ALL");((ObjectNode)game.path("quests").path(0)).put("facilityKey","tpl_node_11");
        game.putArray("facilities").addObject().put("facilityKey","tpl_node_11").put("settlementSlot","tpl_node_11").put("actKey","a").put("scope","OWNER").put("targetScope","OWNER").put("limit",2).put("operationNodeId",11).put("hpCost",1);
        frozen.with("nodeConfigs").putObject("11");frozen.with(NodeJourneySnapshot.NODE_ANSWERS_KEY).putObject("11").put("validationMethod",4);session.setConfigSnapshotJson(frozen.toString());
        assertThrows(ServiceException.class,()->send("early-adapter","OPERATION_START","tpl_node_11",null));send("first-adapter","QUEST_START","north",null);send("first-adapter-done","QUEST_RESOLVE","north","food");assertEquals(1,stored().path("completedNodeIds").size());assertEquals(8,stored().path("variables").path("sys.hp").asInt());
        assertTrue(service.canPlay(7L,10L,0L,11L));assertThrows(ServiceException.class,()->send("stale-repeat","OPERATION_START","tpl_node_11",null));service.recordPlayerArrival(7L,10L,0L,11L,"fresh-repeat",session.getVersion());
        send("repeat-adapter","OPERATION_START","tpl_node_11",null);send("repeat-adapter-done","FACILITY_USE","tpl_node_11",null);assertEquals(7,stored().path("variables").path("sys.hp").asInt());assertEquals(1,stored().path("completedNodeIds").size());assertEquals(2,stored().path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_11").path("count").asInt());assertFalse(service.canPlay(7L,10L,0L,11L));
    }

    @Test void sceneTemplateRulesExecuteTheSelectedFrozenSceneAndKeepRealNodeIdentity() throws Exception {
        for(CmsMemberTemplate template:templates.selectCmsMemberTemplateByIds(Collections.emptyList())){template.setValidationMethod(4);template.setAdvancedConfigJson("{\"schemaVersion\":1}");}
        ObjectNode definition=json.createObjectNode().put("schemaVersion",1).put("currentScene","cold");ObjectNode cold=definition.putArray("scenes").addObject().put("id","cold").put("name","场景");cold.with("nodeConfigs").putObject("11").put("schemaVersion",1).putObject("interactionRules").put("hpCost",2).put("limit",2);
        topic.setThemeRuntimeConfigJson(definition.toString());String compiled=service.compileTemplateRules(topic);topic.setThemeRuntimeConfigJson(compiled);JsonNode frozen=json.readTree(service.freezePublication(topic,null));assertEquals(2,frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());assertEquals("tpl_node_11",frozen.path("themeConfig").path("gameplayRuntime").path("quests").path(0).path("facilityKey").asText());
        topic.setPublishedRouteGraphJson(frozen.toString());JsonNode refrozen=json.readTree(service.freezePublishedJourney(topic));assertEquals(1,refrozen.path("themeConfig").path("gameplayRuntime").path("facilities").size());assertEquals(2,refrozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
    }
    @Test void cmsPersistenceReadsBackActualNodesAndPersistsCompiledRules() throws Exception {
        CmsMemberTemplate first=templates.selectCmsMemberTemplateByIds(Collections.emptyList()).get(0);first.setValidationMethod(4);first.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":2}}");topic.setThemeRuntimeConfigJson(null);
        CmsTopicServiceImpl cms=new CmsTopicServiceImpl();CmsTopicMapper topics=mock(CmsTopicMapper.class);when(topics.selectCmsTopicById(10L)).thenReturn(topic);when(topics.updateCmsTopic(any())).thenAnswer(call->{topic.setThemeRuntimeConfigJson(((CmsTopic)call.getArgument(0)).getThemeRuntimeConfigJson());return 1;});
        org.springframework.test.util.ReflectionTestUtils.setField(cms,"cmsTopicMapper",topics);com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(cms, "topicRouteRuntimeService",service);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.graph(cms),"persistTemplateInteractionRules",10L);JsonNode persisted=json.readTree(topic.getThemeRuntimeConfigJson());assertEquals(11,persisted.path("gameplayRuntime").path("facilities").path(0).path("operationNodeId").asInt());assertEquals("tpl_node_11",persisted.path("gameplayRuntime").path("quests").path(0).path("facilityKey").asText());assertEquals(2,persisted.path("gameplayRuntime").path("facilities").path(0).path("hpCost").asInt());
    }

    @Test void templateTradeAndVoteToolsSaveAndFreezeTheActualWorldScopeAndCurrentScene() throws Exception {
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1);theme.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        templates.selectCmsMemberTemplateByIds(Collections.emptyList()).get(0).setValidationMethod(4);
        CmsMemberTemplate first=templates.selectCmsMemberTemplateByIds(Collections.emptyList()).get(0);first.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"题目\",\"answerText\":\"正确\"},\"interactionTools\":{\"allowTrade\":true,\"votePolicy\":\"MAJORITY\",\"plans\":[{\"title\":\"共同操作\",\"materialCost\":[]}]}}");
        topic.setThemeRuntimeConfigJson(theme.toString());JsonNode compiled=json.readTree(service.compileTemplateRules(topic));assertEquals("TEAM",compiled.path("gameplayRuntime").path("runtimeScope").asText());assertEquals(2,compiled.path("gameplayRuntime").path("quests").size());assertTrue(compiled.path("gameplayRuntime").path("allowTrade").asBoolean());assertEquals("tpl_vote_11_1",compiled.path("gameplayRuntime").path("plans").path(0).path("endingKey").asText());assertFalse(compiled.path("gameplayRuntime").path("plans").path(0).path("terminal").asBoolean(true));
        ObjectNode sceneTheme=((ObjectNode)compiled).deepCopy();sceneTheme.put("currentScene","scene");ObjectNode scene=sceneTheme.putArray("scenes").addObject().put("id","scene").put("name","场景");scene.with("nodeConfigs").set("11",json.readTree(first.getAdvancedConfigJson()));((ObjectNode)scene.path("nodeConfigs").path("11").path("interactionTools").path("plans").path(0)).put("title","场景共同操作");topic.setThemeRuntimeConfigJson(sceneTheme.toString());JsonNode frozen=json.readTree(service.freezePublication(topic,null));assertEquals(1,frozen.path("themeConfig").path("gameplayRuntime").path("plans").size());assertEquals("场景共同操作",frozen.path("themeConfig").path("gameplayRuntime").path("plans").path(0).path("title").asText());
        first.setAdvancedConfigJson("{\"schemaVersion\":1}");topic.setThemeRuntimeConfigJson(compiled.toString());JsonNode cleared=json.readTree(service.compileTemplateRules(topic));assertFalse(cleared.path("gameplayRuntime").path("allowTrade").asBoolean());assertEquals(0,cleared.path("gameplayRuntime").path("plans").size());
    }
    @Test void actionSummariesShowCanonicalCostsCountsAndScopesBeforeStartWithoutHiddenEffects() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");((ObjectNode)game.path("quests").path(0)).put("facilityKey","warm");ObjectNode facility=game.putArray("facilities").addObject().put("facilityKey","warm").put("settlementSlot","warm").put("actKey","a").put("scope","OWNER").put("targetScope","OWNER").put("limit",4).put("hpCost",2);
        facility.putArray("statCosts").addObject().put("statKey","counter.temperature").put("amount",3);facility.putArray("effects").addObject().put("op","INC").put("var","counter.temperature").put("value",20);((ObjectNode)frozen.path("journeyRules").path("attributes").path(0)).put("visible",false);session.setConfigSnapshotJson(frozen.toString());
        JsonNode actions=json.valueToTree(service.themeView(7L,10L,0L,99L).get("allowedActions"));JsonNode start=null;for(JsonNode action:actions)if("QUEST_START".equals(action.path("operation").asText()) && "north".equals(action.path("contentId").asText()))start=action;
        assertNotNull(start);assertEquals(3,start.path("costs").path("hpCost").asInt());assertEquals(1,start.path("limit").asInt());assertEquals(4,start.path("facility").path("remainingCount").asInt());assertEquals("OWNER",start.path("scope").asText());assertFalse(start.toString().contains("counter.temperature"));assertFalse(start.has("effects"));assertFalse(start.has("when"));assertFalse(start.has("grants"));
        TopicRouteEngine.State owner=json.treeToValue(stored(),TopicRouteEngine.State.class),world=owner.copy();owner.getThemeRuntime().with("cooperation").with("facilities").with("warm").put("count",1);world.getThemeRuntime().with("cooperation").with("facilities").with("warm").put("count",2);facility.put("scope","WORLD").put("targetScope","PARTICIPANTS");
        JsonNode shared=TopicRouteRuntimeServiceImpl.gameplayActionSummary(frozen.path("themeConfig"),JourneyRules.parse(frozen.path("journeyRules").toString()),owner,world,"FACILITY_USE","warm");assertEquals(2,shared.path("remainingCount").asInt());assertEquals("PARTICIPANTS",shared.path("targetScope").asText());assertFalse(shared.toString().contains("counter.temperature"));assertEquals(0,TopicRouteRuntimeServiceImpl.gameplayActionSummary(frozen.path("themeConfig"),JourneyRules.parse(frozen.path("journeyRules").toString()),owner,world,"OPERATION_CANCEL","warm").path("costs").size());
    }

    @Test void personalChallengeBypassesArrivalAndRetriesWithoutRewardsOrTaskCompletion() throws Exception {
        ObjectNode frozen=(ObjectNode)json.readTree(session.getConfigSnapshotJson());
        ObjectNode policy=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("survival");policy.put("zeroHpPolicy","CHALLENGE");
        policy.putObject("zeroChallenge").put("challengeNodeId",101).put("restoreTo",4).put("limit",1).putArray("alternativeNodeIds").add(102);
        frozen.with("nodeConfigs").with("101").putObject("ballShake").put("enabled",true).put("goal",10);
        session.setConfigSnapshotJson(frozen.toString());
        TopicRouteEngine.State state=json.readValue(session.getStateJson(),TopicRouteEngine.State.class);state.getVariables().put("sys.hp",0);
        new GameplaySurvivalEngine().syncChallenge(frozen,JourneyRules.parse(frozen.path("journeyRules").toString()),state);session.setStateJson(json.writeValueAsString(state));
        assertEquals(4,json.valueToTree(service.themeView(7L,10L,0L,99L)).path("challenge").path("restoreTo").asInt());assertEquals(4,service.zeroChallenge(7L,10L,0L,101L).path("restoreTo").asInt());
        CmsTopicNode alternative=new CmsTopicNode();alternative.setId(102L);alternative.setTopicId(10L);alternative.setStatus(1L);when(nodes.selectCmsTopicNodeById(102L)).thenReturn(alternative);
        service.requireOpeningAccess(7L,10L,0L,101L);assertTrue(service.canPlay(7L,10L,0L,102L));
        assertThrows(ServiceException.class,()->send("paid-at-zero","QUEST_START","north",null));
        String first=service.gameplayInstance(7L,10L,0L,101L);
        JsonNode wrong=service.recordZeroChallengeResult(7L,10L,0L,102L,first,"failed",false,null,true);
        assertTrue(wrong.path("retry").asBoolean());assertEquals(0,stored().path("themeRuntime").path("zeroChallenges").path("used").asInt());
        String next=service.gameplayInstance(7L,10L,0L,102L);assertNotEquals(first,next);
        JsonNode success=service.recordZeroChallengeResult(7L,10L,0L,102L,next,"passed",true,null,false);
        assertTrue(success.path("success").asBoolean());assertEquals(4,success.path("restoreTo").asInt());assertEquals(success.path("goal").asInt(),success.path("progress").asInt());assertEquals(4,stored().path("variables").path("sys.hp").asInt());
        assertEquals(json.writeValueAsString(success),json.writeValueAsString(service.recordZeroChallengeResult(7L,10L,0L,102L,next,"passed",true,null,false)));
        assertEquals(1,stored().path("themeRuntime").path("zeroChallenges").path("used").asInt());assertEquals(0,stored().path("completedNodeIds").size());
        assertThrows(ServiceException.class,()->service.recordZeroChallengeResult(8L,10L,0L,102L,next,"injected",true,null,false));
    }

}
