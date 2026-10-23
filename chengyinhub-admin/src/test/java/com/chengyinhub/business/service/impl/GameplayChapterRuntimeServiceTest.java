package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.common.exception.ServiceException;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameplayChapterRuntimeServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    TopicRouteRuntimeServiceImpl runtime;
    CmsTopic topic;
    ObjectNode author;
    private final List<CmsTopicChapter> chapters = new ArrayList<>();
    private final Map<Long,CmsTopicNode> nodes = new LinkedHashMap<>();
    private final Map<Long,TopicRouteSession> sessions = new HashMap<>();
    private CmsMemberTemplate template;
    private int request;

    @BeforeEach void setup() {
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(registrations);
        runtime = new TopicRouteRuntimeServiceImpl(routes, topics, nodeMapper, templates, mock(PlayerNodeProgressMapper.class), null,
                registrations, null, mock(IShopDayProofService.class), () -> 1L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime,"cmsTopicChapterMapper",chapterMapper);
        template = new CmsMemberTemplate(); template.setId(501L); template.setValidationMethod(4);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":1}}");
        when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        topic = new CmsTopic(); topic.setId(10L); topic.setRouteMode("LINEAR"); topic.setPublishedConfigVersion(1L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":20,\"max\":20}}");
        author = JSON.createObjectNode().put("schemaVersion",1).put("capacity",0);
        // 故意乱序且前两章 sortId 相同，真实章节按 sortId、id 绑定，而非配置行位置。
        chapter(30,1,201,202,203); chapter(10,2,301); chapter(20,1,101,102,103,104);
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(chapters);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenAnswer(call -> new ArrayList<>(nodes.values()));
        when(nodeMapper.selectCmsTopicNodeById(anyLong())).thenAnswer(call -> nodes.get(call.getArgument(0)));
        when(routes.selectByOwner(eq(0L),eq(10L),anyLong(),eq("default"))).thenAnswer(call -> sessions.get(call.getArgument(2)));
        when(routes.selectCurrentByOwner(eq(0L),eq(10L),anyLong())).thenAnswer(call -> sessions.get(call.getArgument(2)));
        when(routes.selectAccessibleById(anyLong(),anyLong())).thenAnswer(call -> {
            TopicRouteSession s=sessions.get(call.getArgument(1));return s!=null && s.getId().equals(call.getArgument(0))?s:null;
        });
        when(routes.insertSession(any())).thenAnswer(call -> {TopicRouteSession s=call.getArgument(0);s.setId(s.getMemberId()+100);sessions.put(s.getMemberId(),s);return 1;});
        when(routes.updateStateCas(any())).thenReturn(1);when(routes.insertAction(any())).thenReturn(1);when(routes.updateActionResponse(any(),any())).thenReturn(1);
    }
    private void chapter(long id,long sort,long... ids) {
        CmsTopicChapter chapter=new CmsTopicChapter();chapter.setId(id);chapter.setTopicId(10L);chapter.setSortId(sort);chapter.setName("章节"+id);chapters.add(chapter);
        for(long nodeId:ids){CmsTopicNode node=new CmsTopicNode();node.setId(nodeId);node.setTopicId(10L);node.setChapterId(id);node.setName("节点"+nodeId);node.setStatus(1L);node.setSortId(nodeId);node.setTemplateId(501L);node.setMerchantMemberId(30L);nodes.put(nodeId,node);}
    }
    ObjectNode settings(int index) {
        for(JsonNode row:author.path("chapters"))if(row.path("index").asInt()==index)return (ObjectNode)row;
        return author.withArray("chapters").addObject().put("index",index).put("hpCost",0).put("luckCost",0);
    }
    JsonNode publish() throws Exception {
        topic.setThemeRuntimeConfigJson(author.toString());topic.setThemeRuntimeConfigJson(runtime.compileTemplateRules(topic));
        JsonNode frozen=JSON.readTree(runtime.freezePublication(topic,null));topic.setPublishedRouteGraphJson(frozen.toString());return frozen;
    }
    private JsonNode stored(long member) throws Exception {return JSON.readTree(sessions.get(member).getStateJson());}
    private void start(long member,long node) {
        TopicRouteSession s=sessions.get(member);
        runtime.recordPlayerArrival(member,10L,0L,node,"scan-"+(++request),s.getVersion());
        runtime.gameplayAction(member,s.getId(),s.getVersion(),"start"+(++request),"QUEST_START","node_"+node,null);
    }
    private void resolve(long member,long node) {
        start(member,node);TopicRouteSession s=sessions.get(member);
        runtime.gameplayAction(member,s.getId(),s.getVersion(),"resolve"+(++request),"QUEST_RESOLVE","node_"+node,null);
    }
    @Test void anyNUsesRealChapterOrderAndEndsOnceAtQuantity() throws Exception {
        settings(2).putObject("completion").put("type","ALL");
        settings(0).put("hpCost",2).putObject("completion").put("type","ANY_N").put("quantity",2);
        JsonNode frozen=publish();JsonNode act=frozen.path("themeConfig").path("gameplayRuntime").path("acts").path(0);
        assertEquals("chapter_20",act.path("actKey").asText());assertEquals(2,act.path("completionRule").path("quantity").asInt());
        runtime.state(7L,10L,0L);resolve(7,101);assertEquals(0,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());
        resolve(7,102);assertEquals(1,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());
        assertEquals(16,stored(7).path("variables").path("sys.hp").asInt());assertTrue(stored(7).path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
        assertEquals(201,stored(7).path("currentNodeId").asLong());
        TopicRouteSession s=sessions.get(7L);runtime.gameplayAction(7L,s.getId(),s.getVersion(),"replay","QUEST_RESOLVE","node_102",null);
        assertEquals(16,stored(7).path("variables").path("sys.hp").asInt());
    }
    @Test void allCountsOnePerExclusiveGroupAndLocksTheOtherChoice() throws Exception {
        settings(0).putArray("exclusiveGroups").addArray().add(101).add(102);
        settings(0).putArray("extraNodes").addObject().put("nodeId",104).put("slot","mobile");
        JsonNode frozen=publish();assertEquals("ALL",frozen.path("themeConfig").path("gameplayRuntime").path("acts").path(0).path("completionRule").path("type").asText());
        runtime.state(7L,10L,0L);resolve(7,101);
        String before=sessions.get(7L).getStateJson();assertEquals("该站当前不可到达",assertThrows(ServiceException.class,()->start(7,102)).getMessage());assertEquals(before,sessions.get(7L).getStateJson());
        assertEquals("DISCOVERED_LOCKED",stored(7).path("nodeStates").path("102").asText());resolve(7,103);
        assertEquals(1,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());assertTrue(stored(7).path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
        assertEquals(201,stored(7).path("currentNodeId").asLong());
    }
    @Test void extraIsExcludedFromAnyNAndSlotIsSharedAcrossChaptersButNotOwners() throws Exception {
        settings(0).putObject("completion").put("type","ANY_N").put("quantity",2);
        settings(0).putArray("extraNodes").addObject().put("nodeId",104).put("slot","mobile");
        settings(1).putArray("extraNodes").addObject().put("nodeId",203).put("slot","mobile");
        JsonNode frozen=publish();assertTrue(frozen.path("themeConfig").path("gameplayRuntime").path("quests").path(3).path("extraOnly").asBoolean());
        runtime.state(7L,10L,0L);resolve(7,104);resolve(7,101);
        assertEquals(0,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());assertEquals("node_104",stored(7).path("themeRuntime").path("gameplay").path("extraSlots").path("mobile").asText());
        resolve(7,102);String before=sessions.get(7L).getStateJson();assertEquals("该站当前不可到达",assertThrows(ServiceException.class,()->start(7,203)).getMessage());assertEquals(before,sessions.get(7L).getStateJson());
        assertEquals("DISCOVERED_LOCKED",stored(7).path("nodeStates").path("203").asText());
        resolve(7,201);resolve(7,202);assertEquals(2,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());
        runtime.state(8L,10L,0L);resolve(8,101);resolve(8,102);resolve(8,203);
        assertEquals("node_203",stored(8).path("themeRuntime").path("gameplay").path("extraSlots").path("mobile").asText());assertEquals(1,stored(8).path("themeRuntime").path("gameplay").path("actIndex").asInt());
    }
    @Test void overflowUsesRemainingCountedQuestAndSharesItsSlotWithAnotherChapterPerOwner() throws Exception {
        settings(0).putObject("completion").put("type","ANY_N").put("quantity",2).put("overflowSlot","mobile");
        settings(0).putArray("extraNodes").addObject().put("nodeId",104).put("slot","bonus");
        settings(1).putArray("extraNodes").addObject().put("nodeId",203).put("slot","mobile");
        JsonNode frozen=publish();JsonNode game=frozen.path("themeConfig").path("gameplayRuntime");
        assertFalse(game.path("acts").path(0).path("completionRule").has("overflowSlot"));
        for(JsonNode q:game.path("quests"))if(q.path("actKey").asText().equals("chapter_20")) {
            if(q.path("nodeId").asLong()==104){assertEquals("bonus",q.path("extraSlot").asText());assertTrue(q.path("extraOnly").asBoolean());}
            else {assertEquals("mobile",q.path("extraSlot").asText());assertFalse(q.has("extraOnly"));}
        }
        runtime.state(7L,10L,0L);resolve(7,104);resolve(7,101);
        assertEquals(0,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());
        resolve(7,102);assertEquals(1,stored(7).path("themeRuntime").path("gameplay").path("actIndex").asInt());
        assertFalse(stored(7).path("themeRuntime").path("gameplay").path("extraSlots").has("mobile"));
        resolve(7,103);assertEquals("node_103",stored(7).path("themeRuntime").path("gameplay").path("extraSlots").path("mobile").asText());
        String before=sessions.get(7L).getStateJson();assertThrows(ServiceException.class,()->start(7,203));assertEquals(before,sessions.get(7L).getStateJson());
        runtime.state(8L,10L,0L);resolve(8,102);resolve(8,103);resolve(8,203);
        assertEquals("node_203",stored(8).path("themeRuntime").path("gameplay").path("extraSlots").path("mobile").asText());
        before=sessions.get(8L).getStateJson();assertThrows(ServiceException.class,()->start(8,101));assertEquals(before,sessions.get(8L).getStateJson());
    }
    @Test void overflowWithAllRejectsPublishWithChapterAndNodesNamed() {
        settings(0).putObject("completion").put("type","ALL").put("overflowSlot","mobile");
        topic.setThemeRuntimeConfigJson(author.toString());
        String message=assertThrows(IllegalArgumentException.class,()->runtime.freezePublication(topic,null)).getMessage();
        assertTrue(message.contains("章节20") && message.contains("101") && message.contains("overflowSlot"),message);
    }
    @Test void foreignChapterOverlapAndUnreachableQuantityNameChapterAndNode() {
        for(String fault:Arrays.asList("foreignGroup","foreignExtra","overlap","quantity","zero")) {
            author.remove("chapters");ObjectNode chapter=settings(0);
            if(fault.equals("foreignGroup"))chapter.putArray("exclusiveGroups").addArray().add(101).add(201);
            if(fault.equals("foreignExtra"))chapter.putArray("extraNodes").addObject().put("nodeId",201).put("slot","mobile");
            if(fault.equals("overlap")){chapter.putArray("exclusiveGroups").addArray().add(101).add(102);chapter.putArray("extraNodes").addObject().put("nodeId",101).put("slot","mobile");}
            if(fault.equals("quantity")){chapter.putObject("completion").put("type","ANY_N").put("quantity",3);chapter.putArray("exclusiveGroups").addArray().add(101).add(102);chapter.putArray("extraNodes").addObject().put("nodeId",104).put("slot","mobile");}
            if(fault.equals("zero"))chapter.putObject("completion").put("type","ANY_N").put("quantity",0);
            topic.setThemeRuntimeConfigJson(author.toString());String message=assertThrows(IllegalArgumentException.class,()->runtime.compileTemplateRules(topic)).getMessage();
            assertTrue(message.contains("章节20") && message.contains(fault.startsWith("foreign")?"201":"101"),message);
        }
    }
    @Test void republishClearsRemovedGroupsAndExtrasAndKeepsHostUnlock() throws Exception {
        settings(0).putObject("completion").put("type","ANY_N").put("quantity",2).put("overflowSlot","mobile");
        settings(0).putArray("exclusiveGroups").addArray().add(101).add(102);settings(0).putArray("extraNodes").addObject().put("nodeId",104).put("slot","mobile");
        publish();author=(ObjectNode)JSON.readTree(topic.getThemeRuntimeConfigJson());settings(0).putArray("exclusiveGroups");settings(0).putArray("extraNodes");settings(0).putObject("completion").put("type","ALL");
        ((ObjectNode)author.path("gameplayRuntime").path("acts").path(1)).put("hostUnlock",true);topic.setThemeRuntimeConfigJson(author.toString());
        JsonNode compiled=JSON.readTree(runtime.compileTemplateRules(topic));for(JsonNode q:compiled.path("gameplayRuntime").path("quests")){assertFalse(q.has("exclusiveGroup"));assertFalse(q.has("extraSlot"));assertFalse(q.has("extraOnly"));}
        assertTrue(compiled.path("gameplayRuntime").path("acts").path(1).path("hostUnlock").asBoolean());
        assertTrue(assertThrows(IllegalArgumentException.class,()->runtime.freezePublication(topic,null)).getMessage().contains("工作人员解锁"));
    }
    @Test void chapterRulesCompileWithoutTemplateInteractionAndWithEscapedJsonField() throws Exception {
        template.setAdvancedConfigJson("{\"schemaVersion\":1}");settings(0).putObject("completion").put("type","ANY_N").put("quantity",1);
        topic.setThemeRuntimeConfigJson(author.toString().replace("completion","\\u0063ompletion"));
        JsonNode compiled=JSON.readTree(runtime.compileTemplateRules(topic));assertEquals(8,compiled.path("gameplayRuntime").path("quests").size());
        assertEquals(1,compiled.path("gameplayRuntime").path("acts").path(0).path("completionRule").path("quantity").asInt());
    }
    @Test void emptyOpeningChapterStillOccupiesTheAuthorIndex() throws Exception {
        chapter(5,0);settings(1).putObject("completion").put("type","ANY_N").put("quantity",1);
        JsonNode frozen=publish();assertEquals("chapter_20",frozen.path("themeConfig").path("gameplayRuntime").path("acts").path(0).path("actKey").asText());
        assertEquals(1,frozen.path("themeConfig").path("gameplayRuntime").path("acts").path(0).path("completionRule").path("quantity").asInt());
        assertEquals(101,frozen.path("themeConfig").path("chapters").path(0).path("binding").path("nodeIds").path(0).asLong());
    }
    @Test void allWithOnlyExtrasRejectsPublishWithChapterAndEveryNodeNamed() {
        ObjectNode chapter=settings(0);for(long id:new long[]{101,102,103,104})chapter.withArray("extraNodes").addObject().put("nodeId",id).put("slot","mobile");
        topic.setThemeRuntimeConfigJson(author.toString());String message=assertThrows(IllegalArgumentException.class,()->runtime.freezePublication(topic,null)).getMessage();
        assertTrue(message.contains("章节20") && message.contains("没有计数任务"),message);for(long id:new long[]{101,102,103,104})assertTrue(message.contains(String.valueOf(id)),message);
    }
    @Test void legacyWithoutChapterFieldsCompilesByteIdentically() throws Exception {
        topic.setThemeRuntimeConfigJson(author.toString());String compiled=runtime.compileTemplateRules(topic);
        String expected=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/test/resources/gameplay-chapter-legacy.json")),java.nio.charset.StandardCharsets.UTF_8).trim();
        assertEquals(expected,compiled);
        topic.setThemeRuntimeConfigJson(compiled);assertEquals(compiled,runtime.compileTemplateRules(topic));
    }
}
