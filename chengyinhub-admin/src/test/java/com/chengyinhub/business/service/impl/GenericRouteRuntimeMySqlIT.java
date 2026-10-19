package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.it.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenericRouteRuntimeMySqlIT {
    private static PooledDataSource ds;
    private static GameSessionRuntimeMapper mapper;
    private static GameSessionRuntimeServiceImpl target;
    private static IGameSessionRuntimeService runtime;
    private static IClubAccessService access;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        int port = Integer.parseInt(MybatisItDatabase.port()); assertTrue(port > 0 && port <= 65535);
        assertTrue(MybatisItDatabase.databaseName("generic_route_worker_b").matches("chengyin_mybatis_generic_route_worker_b[a-zA-Z0-9_]*"));
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("generic_route_worker_b"), MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection c=ds.getConnection()) { ItSchema.apply(c,"it-schema/generic-route-runtime-it-schema.sql"); exec(c,"CREATE TABLE IF NOT EXISTS ums_member (id BIGINT PRIMARY KEY,nickname VARCHAR(255),avatar VARCHAR(1024))"); }
        Configuration config = new Configuration(); config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String name:Arrays.asList("GameSessionRuntimeMapper","PlayTeamMapper")) {
            String resource="mapper/business/"+name+".xml";
            try(InputStream in=GenericRouteRuntimeMySqlIT.class.getClassLoader().getResourceAsStream(resource)) { new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse(); }
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        SqlSessionTemplate sql=new SqlSessionTemplate(factory.getObject()); mapper=sql.getMapper(GameSessionRuntimeMapper.class);
        target=new GameSessionRuntimeServiceImpl(); ReflectionTestUtils.setField(target,"runtimeMapper",mapper);
        ReflectionTestUtils.setField(target,"playTeamMapper",sql.getMapper(PlayTeamMapper.class));
        CmsActivityMapper activityMapper=mock(CmsActivityMapper.class);
        when(activityMapper.selectCmsActivityById(anyLong())).thenAnswer(call->{CmsActivity a=new CmsActivity(); a.setId(call.getArgument(0));a.setTopicId(80L);a.setClubId(40L);a.setStatus(1L);a.setDelFlag(0);a.setStartDate(new java.util.Date(System.currentTimeMillis()-3600000));a.setEndDate(new java.util.Date(System.currentTimeMillis()+3600000));return a;});
        ReflectionTestUtils.setField(target,"activityMapper",activityMapper);
        IPlayerNodeProgressService progress=mock(IPlayerNodeProgressService.class);
        when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);p.setCheckinTime(new java.util.Date());return Collections.singletonList(p);});
        ReflectionTestUtils.setField(target,"playerNodeProgressService",progress);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);
        when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(JSON.createArrayNode());
        ReflectionTestUtils.setField(target,"topicRouteRuntimeService",route);
        when(route.verifyGuidedRouteAnswer(any(), anyLong(), any())).thenReturn(true);
        when(route.trustedGuidedRouteEffects(any(),anyLong(),any())).thenReturn(JSON.createArrayNode());
        access = mock(IClubAccessService.class); ReflectionTestUtils.setField(target,"clubAccessService",access);
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(IGameSessionRuntimeService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));runtime=(IGameSessionRuntimeService)proxy.getProxy();
    }
    @BeforeEach void seed() throws Exception {
        ReflectionTestUtils.setField(target,"runtimeMapper",mapper);
        ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock) java.util.Date::new);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(target,"topicRouteRuntimeService",route);
        when(route.verifyGuidedRouteAnswer(any(),anyLong(),any())).thenReturn(true);
        when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(JSON.createArrayNode());
        when(route.trustedGuidedRouteEffects(any(),anyLong(),any())).thenReturn(JSON.createArrayNode());
        reset(access);
        when(access.require(eq(88L),eq(40L),eq(70L),anyString())).thenReturn(new ClubAccessContext(88L,40L,88L,70L,Collections.singleton(ClubRoleCode.EVENT_LEAD)));
        try(Connection c=ds.getConnection()) {
            for(String table:Arrays.asList("game_command_receipt","game_story_state","game_station_run","game_session","play_team_member","play_team","cms_registration","cms_activity")) exec(c,"DELETE FROM "+table);
            exec(c,"INSERT INTO cms_activity(id,topic_id) VALUES(70,80),(71,80)");
            exec(c,"INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES(90,2,70,7,2),(99,2,70,9,2),(100,2,71,10,2)");
            exec(c,"INSERT INTO play_team_member(team_id,member_id,status) VALUES(90,7,1),(90,8,1),(99,9,1),(100,10,1)");
            exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,payment_status,registration_status) VALUES(7,2,70,80,2,2),(8,2,70,80,2,2),(9,2,70,80,2,2),(10,2,71,80,2,2)");
            ObjectNode snapshot=JSON.createObjectNode().put("playMode","ROUTE").put("runtimeSnapshotVersion",1).put("startNodeId",11);
            snapshot.putArray("terminalNodeIds").add(12);snapshot.set("journeyRules",JSON.readTree(GenericRouteRuntimeServiceTest.rules()));snapshot.set("themeConfig",JSON.readTree(GenericRouteRuntimeServiceTest.config()));
            for(long[] scope:new long[][]{{50,70},{51,71}}) {
                try(PreparedStatement st=c.prepareStatement("INSERT INTO game_session(id,activity_id,topic_id,club_id,module_config_snapshot_json,status,revision,prepared_by,create_time,update_time) VALUES(?,?,80,40,?,'RUNNING',0,7,NOW(),NOW())")) {st.setLong(1,scope[0]);st.setLong(2,scope[1]);st.setString(3,snapshot.toString());st.executeUpdate();}
                for(long node:new long[]{11,12}) {
                    try(PreparedStatement st=c.prepareStatement("INSERT INTO game_station_run(game_session_id,activity_id,node_id,node_name,station_code,merchant_member_id,source_type,source_id,status,revision,service_start_at,service_end_at,create_time,update_time) VALUES(?,?,?,'任务',?,310,'CHAPTER_NODE',?,'ACTIVE',0,?,?,NOW(),NOW())")) {
                        st.setLong(1,scope[0]);st.setLong(2,scope[1]);st.setLong(3,node);st.setString(4,"N"+node);st.setLong(5,node);
                        st.setTimestamp(6,new Timestamp(System.currentTimeMillis()-3600000));st.setTimestamp(7,new Timestamp(System.currentTimeMillis()+3600000));st.executeUpdate();
                    }
                }
            }
            for(long[] scope:new long[][]{{50,90},{50,99},{51,100}}) {
                TopicRouteEngine.State state=new TopicRouteEngine().initial(snapshot.toString());JourneyRules rules=JourneyRules.parse(GenericRouteRuntimeServiceTest.rules());state.getVariables().put("sys.hp",10);JourneyInitialState.initialize(rules,state);new ThemeRuntimeEngine().initialize(snapshot.path("themeConfig"),state);
                state.getThemeRuntime().with("inventory").put("cold",1);state.getThemeRuntime().with("inventory").put("food",1);state.getThemeRuntime().put("authorityEpoch",1);state.getThemeRuntime().with("gameplay").put("actIndex",0);
                try(PreparedStatement st=c.prepareStatement("INSERT INTO game_story_state(game_session_id,owner_type,owner_id,variables_json,applied_choices_json,status,revision,create_time,update_time) VALUES(?,'TEAM',?,?,'[]','INITIAL',0,NOW(),NOW())")) {st.setLong(1,scope[0]);st.setLong(2,scope[1]);st.setString(3,JSON.writeValueAsString(state));st.executeUpdate();}
            }
        }
    }
    private static void exec(Connection c,String sql) throws Exception {try(Statement st=c.createStatement()){st.execute(sql);}}
    private GameSessionCommand command(String id,int revision,String action,String key) {
        GameSessionCommand c=new GameSessionCommand();c.setActivityId(70L);c.setRequestId(id);c.setExpectedRevision(revision);c.setAuthorityEpoch(1);c.setAction(action);c.getPayload().put(action.startsWith("QUEST")?"questKey":"contentId",key);if("QUEST_RESOLVE".equals(action))c.getPayload().put("choiceKey","supply");return c;
    }
    private String instance(long member, long node) { return runtime.sharedRouteSnapshot(member,80L,70L,node).path("runtimeInstance").asText("game:50"); }
    private JsonNode state(long session,long team) throws Exception {return JSON.readTree(mapper.selectStoryState(session,"TEAM",team).getVariablesJson());}
    private int receipts() throws Exception {try(Connection c=ds.getConnection();Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT count(*) FROM game_command_receipt")){rs.next();return rs.getInt(1);}}
    private ObjectNode payload() { return JSON.createObjectNode(); }
    private ArrayNode amounts(String item, int quantity) { ArrayNode a=JSON.createArrayNode(); a.addObject().put("itemId",item).put("quantity",quantity); return a; }
    private GameSessionCommand envelope(long actor, String action, ObjectNode payload) {
        GameSessionCommand c=new GameSessionCommand(); c.setActivityId(70L); c.setRequestId(UUID.randomUUID().toString());
        c.setExpectedRevision(mapper.selectSessionByActivity(70L).getRevision()); c.setAuthorityEpoch(runState().path("authorityEpoch").asInt(1)); c.setAction(action);
        c.setPayload(JSON.convertValue(payload, Map.class)); return c;
    }
    private GameCommandReceiptView player(long member, String action, ObjectNode p) { return runtime.command(GameActorContext.player(member), envelope(member,action,p)); }
    private GameCommandReceiptView staff(String action, ObjectNode p) { return runtime.command(GameActorContext.club(88L),envelope(88,action,p)); }
    private JsonNode runState() { GameStoryState row=mapper.selectStoryState(50L,"RUN",50L); try { return row==null?payload().put("authorityEpoch",1):JSON.readTree(row.getVariablesJson()); } catch(Exception e) { throw new IllegalStateException(e); } }
    private JsonNode worldState() throws Exception { return JSON.readTree(mapper.selectStoryState(50L,"WORLD",50L).getVariablesJson()).path("themeRuntime").path("cooperation"); }
    private void writeOwner(String type,long ownerId,Object value) throws Exception {
        String raw=JSON.writeValueAsString(value);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("INSERT INTO game_story_state(game_session_id,owner_type,owner_id,variables_json,applied_choices_json,status,revision,create_time,update_time) VALUES(50,?,?,?,'[]','INITIAL',0,NOW(),NOW()) ON DUPLICATE KEY UPDATE variables_json=VALUES(variables_json)")) {
            st.setString(1,type); st.setLong(2,ownerId); st.setString(3,raw); st.executeUpdate();
        }
    }
    private void sharedWorld(int count) throws Exception {
        GameSession session=mapper.selectSessionByActivity(70L); ObjectNode snapshot=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());
        ObjectNode theme=JSON.createObjectNode().put("schemaVersion",1).put("capacity",8).put("actionLimit",8);
        theme.putArray("items").addObject().put("id","tool").put("name","工具").put("kind","ITEM").put("slotCost",1);
        theme.withArray("items").addObject().put("id","clue").put("name","线索片").put("kind","ITEM").put("slotCost",1);
        ObjectNode g=theme.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED").put("allowTrade",true).put("votePolicy","MAJORITY_ACCEPTANCE");
        g.putObject("survival").put("zeroHpPolicy","BLOCK_PAID");
        g.with("survival").putArray("recoveries").addObject().put("recoveryKey","break").put("kind","REST").put("limit",2).put("hpDelta",2);
        ObjectNode act=g.putArray("acts").addObject().put("actKey","build"); act.putArray("questKeys").add("q1").add("q2");act.putObject("completionRule").put("type","ALL");
        ObjectNode q=g.putArray("quests").addObject().put("questKey","q1").put("actKey","build").put("nodeId",11).put("locationKey","yard").put("settlementSlot","q1").put("actionCost",1);
        q.set("resourceCosts",amounts("tool",1)); q.putArray("approvedFallbackNodeIds").add(12);
        g.withArray("quests").addObject().put("questKey","q2").put("actKey","build").put("nodeId",12).put("locationKey","square").put("settlementSlot","q2");
        ObjectNode f=g.putArray("facilities").addObject().put("facilityKey","shelter").put("actKey","build").put("scope","WORLD").put("targetScope","WORLD").put("settlementSlot","shelter_once").put("limit",1).put("hpDelta",2); f.set("resourceCosts",amounts("tool",1));
        ObjectNode plan=g.putArray("plans").addObject().put("endingKey","bridge").put("title","修桥").put("summary","联合修复道路").put("operationNodeId",12); plan.set("materialCost",amounts("tool",2));
        plan=g.withArray("plans").addObject().put("endingKey","trail").put("title","修步道").put("operationNodeId",12);plan.set("materialCost",amounts("clue",2));
        snapshot.set("themeConfig",theme); snapshot.set("journeyRules",JSON.readTree("{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"init\":8,\"max\":12}}"));
        snapshot.putObject("nodeConfigs").putObject("11").put("frozen",true); snapshot.with("nodeConfigs").putObject("12").put("frozen",true);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,snapshot.toString());st.executeUpdate();}
        if(count==4)try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES(101,2,70,11,2),(102,2,70,12,2)");
            exec(c,"INSERT INTO play_team_member(team_id,member_id,status) VALUES(101,11,1),(102,12,1)");
            exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,payment_status,registration_status) VALUES(11,2,70,80,2,2),(12,2,70,80,2,2)");
        }
        for(long team:count==4?new long[]{90,99,101,102}:new long[]{90,99}) {
            TopicRouteEngine.State state=new TopicRouteEngine().initial(snapshot.toString()); JourneyInitialState.initialize(JourneyRules.parse(snapshot.path("journeyRules").toString()),state);new ThemeRuntimeEngine().initialize(theme,state);
            state.getThemeRuntime().with("inventory").put("tool",2).put("clue",2);state.getThemeRuntime().with("gameplay").put("actIndex",0);state.getVariables().put("sys.hp",8);writeOwner("TEAM",team,state);
        }
        TopicRouteEngine.State world=new TopicRouteEngine.State();new ThemeRuntimeEngine().initialize(theme,world);writeOwner("WORLD",50,world);
        writeOwner("RUN",50,payload().put("operationStatus","RUNNING").put("authorityMode","ONLINE").put("authorityEpoch",1).put("eventSeq",0));
    }


    @Test void r5TeamStepsRetainsOriginalMemberAcrossCaptainChangeAndResetsAfterExit() throws Exception {
        long now=System.currentTimeMillis();ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->new java.util.Date(now));ReflectionTestUtils.setField(target,"verifiedWeRunService",R5WeRunFixture.service());
        ObjectNode frozen=frozenReadback();frozen.with("themeConfig").with("gameplayRuntime").with("survival").put("zeroHpPolicy","STEPS").putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");saveReadbackFixture(frozen);
        TopicRouteEngine.State owner=JSON.readValue(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson(),TopicRouteEngine.State.class);owner.getVariables().put("sys.hp",0);writeOwner("TEAM",90,owner);runtime.sharedRouteSnapshot(7L,80L,70L,null);String episode=state(50,90).path("themeRuntime").path("zeroHp").path("episodeId").asText();
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO ums_member(id,nickname) VALUES(7,'原队长'),(8,'新队长') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)");}
        assertZeroStepsMember(7,7,"原队长",true);assertZeroStepsMember(8,7,"原队长",false);
        R5ZeroViewFixtures.write("team-steps-awaiting-baseline",readback(7));
        ObjectNode other=payload();other.set("weRun",R5WeRunFixture.today(now,8,episode,5000));assertThrows(GameSessionException.class,()->player(8,"ZERO_HP_STEPS",other));
        ObjectNode base=payload();base.set("weRun",R5WeRunFixture.today(now,7,episode,1000));player(7,"ZERO_HP_STEPS",base);
        try(Connection c=ds.getConnection()){exec(c,"UPDATE play_team SET leader_member_id=8 WHERE id=90");}
        assertZeroStepsMember(7,7,"原队长",true);assertZeroStepsMember(8,7,"原队长",false);
        ObjectNode progress=payload();progress.set("weRun",R5WeRunFixture.today(now,7,episode,1040));player(7,"ZERO_HP_STEPS",progress);assertEquals(40,state(50,90).path("themeRuntime").path("zeroHp").path("progress").asInt());assertThrows(GameSessionException.class,()->player(8,"ZERO_HP_STEPS",other));
        R5ZeroViewFixtures.write("team-steps-recovering",readback(7));R5ZeroViewFixtures.write("team-steps-member-disabled",readback(8));
        try(Connection c=ds.getConnection()){exec(c,"UPDATE play_team_member SET status=0 WHERE team_id=90 AND member_id=7");}
        assertZeroStepsMember(8,8,"新队长",true);
        R5ZeroViewFixtures.write("team-steps-baseline-left-new-captain",readback(8));
        player(8,"ZERO_HP_STEPS",other);assertEquals(0,state(50,90).path("themeRuntime").path("zeroHp").path("progress").asInt());assertEquals(8,state(50,90).path("themeRuntime").path("zeroHp").path("recoveryMemberId").asLong());
        other.set("weRun",R5WeRunFixture.today(now,8,episode,5100));player(8,"ZERO_HP_STEPS",other);assertEquals(4,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(1,state(50,90).path("themeRuntime").path("zeroHp").path("used").asInt());
        assertEquals(0,readback(8).path("zeroHp").path("allowedActions").size());
        R5ZeroViewFixtures.write("team-healthy",readback(8));
    }

    private void assertZeroStepsMember(long actor,long recovery,String name,boolean allowed) {
        GameSessionView actual=runtime.view(GameActorContext.player(actor),70L);assertEquals(allowed,actual.getAvailableActions().contains("ZERO_HP_STEPS"));assertFalse(actual.getAvailableActions().contains("ZERO_HP_SYNC"));
        JsonNode zero=JSON.valueToTree(actual.getPlayer()).path("zeroHp");assertEquals(recovery,zero.path("recoveryMemberId").asLong());assertEquals(name,zero.path("recoveryMemberName").asText());
        assertEquals(allowed?JSON.createArrayNode().add("ZERO_HP_STEPS"):JSON.createArrayNode(),zero.path("allowedActions"));
        assertEquals(allowed?"":"需由 "+name+" 同步微信运动",zero.path("disabledReason").asText());assertEquals("THEME_FAIL",zero.path("thenPolicy").asText());
        for(String field:Arrays.asList("baselineDay","baselineSteps","highWaterByDay","openid","encryptedData"))assertFalse(zero.has(field),field);
    }

    @Test void r5SharedFailureCardUsesThePersonalWhitelistAndStaysWithinTheOwner() throws Exception {
        ObjectNode frozen=frozenReadback();frozen.with("themeConfig").with("gameplayRuntime").with("survival").put("zeroHpPolicy","THEME_FAIL");saveReadbackFixture(frozen);
        assertFalse(readback(7).has("resultCard"));
        TopicRouteEngine.State owner=JSON.readValue(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson(),TopicRouteEngine.State.class);owner.getVariables().put("sys.hp",0);writeOwner("TEAM",90,owner);
        runtime.sharedRouteSnapshot(7L,80L,70L,null);JsonNode result=state(50,90).path("themeRuntime").path("result");assertEquals("HP_ZERO",result.path("reason").asText());
        assertEquals(new ThemeRuntimeEngine().card(result),readback(7).path("resultCard"));assertEquals(readback(7).path("resultCard"),readback(8).path("resultCard"));assertFalse(readback(9).has("resultCard"));
        assertFalse(readback(7).path("resultCard").has("conditions"));assertFalse(readback(7).path("resultCard").has("nodeAnswers"));
        R5ZeroViewFixtures.write("team-hp-zero-result-card",readback(7));
    }

    @Test void r5StaffGuideRejectsBeforeWritingAnyFactOrFee() throws Exception {
        sharedWorld(2);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());
        frozen.with("nodeConfigs").with("11").putObject("outcomeRules").put("version",1).putObject("pass");saveReadbackFixture(frozen);
        player(7,"QUEST_START",payload().put("questKey","q1"));String before=mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson();int receipts=receipts();
        ObjectNode guide=payload().put("teamId",90).put("questKey","q1").put("reason","现场核验");guide.putObject("answers");
        assertTrue(assertThrows(GameSessionException.class,()->staff("QUEST_GUIDE",guide)).getMessage().contains("结果规则"));
        assertEquals(before,mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson());assertEquals(receipts,receipts());
    }
    @Test void r5TeamMemberCanRollButOnlyCurrentCaptainCanPayForReroll() throws Exception {
        ObjectNode frozen=frozenReadback();frozen.with("journeyRules").put("checkEnabled",true).putObject("luck").put("enabled",true).put("init",2).put("max",3);
        ObjectNode block=frozen.with("nodeConfigs").with("11");block.putObject("check").put("enabled",true).put("checkId","c").put("tier","easy");block.putObject("outcomeRules").put("version",1).putObject("pass");block.with("outcomeRules").putObject("fail");saveReadbackFixture(frozen);
        TopicRouteEngine.State owner=JSON.readValue(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson(),TopicRouteEngine.State.class);owner.getVariables().put("sys.luck",2);writeOwner("TEAM",90,owner);
        player(7,"QUEST_START",payload().put("questKey","q1"));String instance=instance(8,11);
        assertNotNull(runtime.rollSharedGameplayCheck(8L,80L,70L,11L,instance,"advanced:1:1",false,1));String before=mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson();
        assertThrows(GameSessionException.class,()->runtime.rollSharedGameplayCheck(8L,80L,70L,11L,instance,"advanced:1:1",true,1));assertEquals(before,mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson());
        assertTrue(runtime.rollSharedGameplayCheck(7L,80L,70L,11L,instance,"advanced:1:1",true,1).isRerolled());assertEquals(1,state(50,90).path("variables").path("sys.luck").asInt());
    }
    @Test void r5SharedOutcomeRejectsOldEpochAndOtherOwnerAndSettlesOneAttemptOnce() throws Exception {
        ObjectNode frozen=frozenReadback();for(JsonNode q:frozen.path("themeConfig").path("gameplayRuntime").path("quests"))((ObjectNode)q).remove(Arrays.asList("statCosts","actionCost"));
        ObjectNode block=frozen.with("nodeConfigs").with("11");block.putObject("qa").put("enabled",true).put("mode","TYPE").put("title","Answer").put("answerText","correct");
        ObjectNode outcomes=block.putObject("outcomeRules").put("version",1);outcomes.putObject("pass").putArray("costs").addObject().put("statKey","sys.hp").put("amount",1);outcomes.putObject("fail").putArray("costs").addObject().put("statKey","sys.hp").put("amount",3);saveReadbackFixture(frozen);
        player(7,"QUEST_START",payload().put("questKey","q1"));String instance=instance(7,11);
        ObjectNode fail=payload().put("nodeId",11).put("outcome","FAIL");String before=mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson();
        assertThrows(GameSessionException.class,()->runtime.recordSharedGameplayOutcome(7L,80L,70L,11L,instance,"advanced:1:1",fail,Collections.emptyList(),0));
        assertThrows(GameSessionException.class,()->runtime.recordSharedGameplayOutcome(9L,80L,70L,11L,instance,"advanced:1:1",fail,Collections.emptyList(),1));assertEquals(before,mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson());
        runtime.recordSharedGameplayOutcome(7L,80L,70L,11L,instance,"advanced:1:1",fail,Collections.emptyList(),1);
        String failed=mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson();assertEquals(7,JSON.readTree(failed).path("variables").path("sys.hp").asInt());
        runtime.recordSharedGameplayOutcome(7L,80L,70L,11L,instance,"advanced:1:1",fail,Collections.emptyList(),1);assertEquals(failed,mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson());
        ObjectNode pass=payload().put("nodeId",11).put("outcome","PASS");assertThrows(IllegalStateException.class,()->runtime.recordSharedGameplayOutcome(7L,80L,70L,11L,instance,"advanced:1:1",pass,Collections.emptyList(),1));assertEquals(failed,mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson());
        runtime.recordSharedGameplayOutcome(7L,80L,70L,11L,instance,"advanced:1:2",pass,Collections.emptyList(),1);assertEquals(7,JSON.readTree(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson()).path("variables").path("sys.hp").asInt());
        player(7,"QUEST_RESOLVE",payload().put("questKey","q1").put("choiceKey","supply"));assertEquals(6,JSON.readTree(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson()).path("variables").path("sys.hp").asInt());
        assertEquals(10,JSON.readTree(mapper.selectStoryState(50L,"TEAM",99L).getVariablesJson()).path("variables").path("sys.hp").asInt());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EPOCH","OTHER_RUN","CANCELLED","ENDED"})
    void r5PredictOriginalSharedPlayThroughCannotChargeAfterItIsClosed(String mode) throws Exception {
        CmsActivityMapper original=(CmsActivityMapper)ReflectionTestUtils.getField(target,"activityMapper");try {
        sharedWorld(2);if("CANCELLED".equals(mode)){CmsActivityMapper activities=mock(CmsActivityMapper.class);ReflectionTestUtils.setField(target,"activityMapper",activities);CmsActivity activity=new CmsActivity();activity.setId(70L);activity.setTopicId(80L);activity.setStatus(1L);activity.setDelFlag(0);activity.setCancelTime(new java.util.Date());when(activities.selectCmsActivityById(70L)).thenReturn(activity);}
        if("ENDED".equals(mode)){ObjectNode owner=state(50,90).deepCopy();owner.with("themeRuntime").putObject("result").put("success",false);writeOwner("TEAM",90,owner);}
        String originalInstance="game:50";JsonNode before=state(50,90);int revision=mapper.selectSessionByActivity(70L).getRevision();assertFalse(runtime.recordOriginalSharedGameplayOutcome(7L,70L,"OTHER_RUN".equals(mode)?51L:50L,90L,"EPOCH".equals(mode)?2:1,12L,originalInstance,"advanced:123:1",payload().put("nodeId",12).put("outcome","FAIL"),Collections.emptyList()));assertEquals(before,state(50,90));assertEquals(revision,mapper.selectSessionByActivity(70L).getRevision());
        }finally{ReflectionTestUtils.setField(target,"activityMapper",original);}
    }

    @Test void r5SharedWaitAuthorizedReadPersistsRecoveryAndReplaysNoSecondRestore() throws Exception {
        long now=System.currentTimeMillis();ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->new java.util.Date(now));
        ObjectNode frozen=frozenReadback();frozen.with("themeConfig").with("gameplayRuntime").with("survival").put("zeroHpPolicy","WAIT").putObject("zeroWait").put("version",1).put("minutes",1).put("restoreTo",4).put("limit",1).put("thenPolicy","THEME_FAIL");saveReadbackFixture(frozen);
        GameStoryState owner=mapper.selectStoryState(50L,"TEAM",90L);TopicRouteEngine.State state=JSON.readValue(owner.getVariablesJson(),TopicRouteEngine.State.class);state.getVariables().put("sys.hp",0);writeOwner("TEAM",90,state);
        runtime.sharedRouteSnapshot(7L,80L,70L,null);
        R5ZeroViewFixtures.write("team-wait",readback(7));
        JsonNode zero=JSON.readTree(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson()).path("themeRuntime").path("zeroHp");assertEquals(now+60000,zero.path("deadlineAt").asLong());
        ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->new java.util.Date(now+60000));assertEquals(4,readback(7).path("hp").asInt());
        String restored=mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson();assertEquals(1,JSON.readTree(restored).path("themeRuntime").path("zeroHp").path("used").asInt());
        runtime.sharedRouteSnapshot(7L,80L,70L,null);assertEquals(restored,mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson());
        assertEquals(10,JSON.readTree(mapper.selectStoryState(50L,"TEAM",99L).getVariablesJson()).path("variables").path("sys.hp").asInt());
    }
    private JsonNode readback(long member) {return JSON.valueToTree(runtime.view(GameActorContext.player(member),70L).getPlayer());}
    private ObjectNode frozenReadback() throws Exception {return (ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());}
    private void saveReadbackFixture(ObjectNode frozen) throws Exception {
        try(Connection connection=ds.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {statement.setString(1,frozen.toString());statement.executeUpdate();}
    }

    @Test void reentryShowsOnlyOwnPrivateIntentsAndCurrentDatabaseCaptainSignatures() throws Exception {
        sharedWorld(4);player(7,"PLAN_PROPOSE",payload().put("proposalKey","own_intent").put("endingKey","bridge"));
        for(long member:new long[]{7,9}) {ObjectNode intent=payload().put("proposalKey","own_intent").put("version",1);intent.set("amounts",amounts("tool",1));player(member,"PLAN_INTENT",intent);}
        ObjectNode trade=payload().put("tradeKey","private_quote").put("toTeamId",99);trade.set("give",amounts("tool",1));trade.set("take",amounts("clue",1));player(7,"TRADE_OPEN",trade);player(7,"TRADE_CONFIRM",payload().put("tradeKey","private_quote").put("version",1));
        JsonNode own=readback(7).path("world"),other=readback(11).path("world");
        assertEquals(amounts("tool",1),own.path("proposals").path(0).path("ownIntent").path("amounts"));assertTrue(own.path("proposals").path(0).path("ownIntent").path("signatureCurrent").asBoolean());
        assertEquals(own,readback(7).path("world"));assertFalse(other.path("proposals").path(0).has("ownIntent"));assertEquals(0,other.path("trades").size());
        assertEquals(amounts("tool",1),own.path("trades").path(0).path("give"));assertTrue(own.path("trades").path(0).path("ownSigned").asBoolean());
        assertFalse(own.toString().contains("memberId"));assertFalse(own.toString().contains("fromSnapshot"));
        try(Connection connection=ds.getConnection()) {exec(connection,"UPDATE play_team SET leader_member_id=8 WHERE id=90");}
        JsonNode former=readback(7),current=readback(8);
        assertFalse(former.path("isCaptain").asBoolean());assertTrue(current.path("isCaptain").asBoolean());
        assertEquals("NOT_CAPTAIN",former.path("world").path("trades").path(0).path("disabledReason").asText());
        assertFalse(current.path("world").path("trades").path(0).path("ownSigned").asBoolean());assertEquals(0,current.path("world").path("trades").path(0).path("confirmedTeams").size());
        assertFalse(current.path("world").path("proposals").path(0).path("ownIntent").path("signatureCurrent").asBoolean());
        assertEquals("GAME_FORBIDDEN_SCOPE",assertThrows(GameSessionException.class,()->player(7,"TRADE_CONFIRM",payload().put("tradeKey","private_quote").put("version",1))).getErrorCode());
        player(9,"TRADE_CONFIRM",payload().put("tradeKey","private_quote").put("version",1));assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        player(8,"TRADE_CONFIRM",payload().put("tradeKey","private_quote").put("version",1));assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
    }

    @Test void questReadbackUsesCurrentTrustedPassAndReturnsTheSameFieldsAtBothLevels() throws Exception {
        sharedWorld(2);
        JsonNode initial=readback(7).path("quests").path(0);assertEquals("QUEST_START",initial.path("operation").asText());assertTrue(initial.path("enabled").asBoolean());assertFalse(initial.path("trustedPass").asBoolean());
        for(String field:Arrays.asList("enabled","disabledReason","limit","count","remainingCount","countScope","trustedPass"))assertEquals(initial.path(field),initial.path("actionSummary").path(field),field);
        player(7,"QUEST_START",payload().put("questKey","q1"));
        JsonNode pending=readback(7).path("quests").path(0);assertEquals("PROOF_PENDING",pending.path("disabledReason").asText());assertFalse(pending.path("trustedPass").asBoolean());
        assertEquals("TASK_ACTIVE",readback(7).path("recoveries").path(0).path("disabledReason").asText());
        ObjectNode owner=(ObjectNode)state(50,90);owner.with("variables").put("sys.passed.11",1);writeOwner("TEAM",90,owner);assertFalse(readback(7).path("quests").path(0).path("trustedPass").asBoolean());
        runtime.recordSharedRouteResult(7L,80L,70L,11L,instance(7,11),"readback-verified",true,Collections.emptyList(),1);
        JsonNode passed=readback(7).path("quests").path(0);assertTrue(passed.path("trustedPass").asBoolean());assertTrue(passed.path("enabled").asBoolean());
        assertFalse(passed.toString().contains("sources"));assertFalse(passed.toString().contains("pendingTaskEffects"));
        player(7,"QUEST_CANCEL",payload().put("questKey","q1"));player(7,"QUEST_START",payload().put("questKey","q1"));
        assertFalse(readback(7).path("quests").path(0).path("trustedPass").asBoolean());
        assertEquals("NOT_CAPTAIN",readback(8).path("quests").path(0).path("disabledReason").asText());
    }

    @Test void readbackDistinguishesMissingArrivalResourcesRulesAndWorldLocks() throws Exception {
        sharedWorld(2);
        IPlayerNodeProgressService progress=(IPlayerNodeProgressService)ReflectionTestUtils.getField(target,"playerNodeProgressService");
        doReturn(Collections.emptyList()).when(progress).selectPlayerNodeProgressList(any());
        assertEquals("NOT_ARRIVED",readback(7).path("quests").path(0).path("disabledReason").asText());
        doAnswer(call->{PlayerNodeProgress query=call.getArgument(0);query.setCheckinTime(new java.util.Date());return Collections.singletonList(query);}).when(progress).selectPlayerNodeProgressList(any());
        ObjectNode owner=(ObjectNode)state(50,90);owner.with("themeRuntime").with("inventory").put("tool",0);writeOwner("TEAM",90,owner);
        assertEquals("INSUFFICIENT_RESOURCES",readback(7).path("quests").path(0).path("disabledReason").asText());
        owner.with("themeRuntime").with("inventory").put("tool",2);writeOwner("TEAM",90,owner);
        ObjectNode frozen=frozenReadback();((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("survival").path("recoveries").path(0)).putArray("actKeys").add("later");saveReadbackFixture(frozen);
        assertEquals("RULE_BLOCKED",readback(7).path("recoveries").path(0).path("disabledReason").asText());
        ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("survival").path("recoveries").path(0)).remove("actKeys");saveReadbackFixture(frozen);
        player(7,"PLAN_PROPOSE",payload().put("proposalKey","funded").put("endingKey","bridge"));
        for(long member:new long[]{7,9}) {ObjectNode intent=payload().put("proposalKey","funded").put("version",1);intent.set("amounts",amounts("tool",1));player(member,"PLAN_INTENT",intent);}
        player(7,"VOTE_OPEN",payload());assertEquals("WORLD_LOCKED",readback(7).path("recoveries").path(0).path("disabledReason").asText());
        assertEquals("WORLD_LOCKED",readback(7).path("world").path("tradeTargets").path(0).path("disabledReason").asText());
        for(JsonNode tool:readback(7).path("interactionTools"))if("PLAN".equals(tool.path("kind").asText()))assertEquals("WORLD_LOCKED",tool.path("disabledReason").asText());
    }

    @Test void toolCountsFollowTheirDeclaredOwnerOrWorldScope() throws Exception {
        sharedWorld(2);ObjectNode frozen=frozenReadback();ObjectNode facility=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0);facility.put("operationNodeId",11).put("limit",3);saveReadbackFixture(frozen);
        ObjectNode world=(ObjectNode)JSON.readTree(mapper.selectStoryState(50L,"WORLD",50L).getVariablesJson());world.with("themeRuntime").with("cooperation").with("facilities").putObject("shelter_once").put("count",2);writeOwner("WORLD",50,world);
        JsonNode shared=readback(7).path("interactionTools").path(0);assertEquals("WORLD",shared.path("countScope").asText());assertEquals(2,shared.path("count").asInt());assertEquals(3,shared.path("limit").asInt());assertEquals(1,shared.path("remainingCount").asInt());assertEquals(2,readback(9).path("interactionTools").path(0).path("count").asInt());
        facility.put("scope","OWNER").put("targetScope","OWNER");saveReadbackFixture(frozen);
        ObjectNode owner=(ObjectNode)state(50,90);owner.with("themeRuntime").with("cooperation").with("facilities").putObject("shelter_once").put("count",1);writeOwner("TEAM",90,owner);
        JsonNode own=readback(7).path("interactionTools").path(0);assertEquals("OWNER",own.path("countScope").asText());assertEquals(1,own.path("count").asInt());assertEquals(2,own.path("remainingCount").asInt());assertEquals(0,readback(9).path("interactionTools").path(0).path("count").asInt());
        owner.with("themeRuntime").with("cooperation").with("facilities").with("shelter_once").put("count",3);writeOwner("TEAM",90,owner);
        assertEquals("LIMIT_REACHED",readback(7).path("interactionTools").path(0).path("disabledReason").asText());
    }

    @Test void toolReadbackDistinguishesPausedEndedAndNaturallyCompletedTeams() throws Exception {
        sharedWorld(2);staff("RUN_PAUSE",payload().put("reason","测试暂停回读"));assertEquals("SESSION_PAUSED",readback(7).path("recoveries").path(0).path("disabledReason").asText());
        assertEquals("SESSION_PAUSED",readback(7).path("world").path("tradeTargets").path(0).path("disabledReason").asText());
        staff("RUN_RESUME",payload().put("reason","测试继续回读"));staff("RUN_INTERRUPT",payload().put("reason","测试结束回读"));assertEquals("SESSION_ENDED",readback(7).path("recoveries").path(0).path("disabledReason").asText());
        assertEquals("SESSION_ENDED",readback(7).path("world").path("tradeTargets").path(0).path("disabledReason").asText());
        seed();sharedWorld(2);ObjectNode frozen=frozenReadback();((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime")).remove("plans");saveReadbackFixture(frozen);
        ObjectNode owner=(ObjectNode)state(50,90);owner.put("status","COMPLETED");writeOwner("TEAM",90,owner);
        assertEquals("TEAM_COMPLETED",readback(7).path("recoveries").path(0).path("disabledReason").asText());assertFalse(readback(7).path("recoveries").path(0).path("enabled").asBoolean());
    }

    @Test void worldNodeSlotsShareOnlyTheSameStationAndNeverConsumeTheSiblingStation() throws Exception {
        sharedWorld(2); basicFrozen(4, false); ObjectNode frozen = frozenReadback();
        frozen.with("nodeAnswers").putObject("12").put("validationMethod", 4);
        ObjectNode first = (ObjectNode) frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0);
        first.put("operationNodeId", 11);
        ObjectNode second = first.deepCopy().put("facilityKey", "second").put("settlementSlot", "second_once").put("operationNodeId", 12);
        ((ObjectNode) frozen.path("themeConfig").path("gameplayRuntime")).withArray("facilities").add(second); saveReadbackFixture(frozen);
        for (String key : Arrays.asList("shelter", "second")) {
            long node = "shelter".equals(key) ? 11 : 12;
            player(7, "OPERATION_START", payload().put("operationKind", "FACILITY").put("operationKey", key));
            runtime.recordSharedBasicArrival(7L, 80L, 70L, node, "scan-" + key, "SCAN", instance(7, node), 1);
            player(7, "FACILITY_USE", payload().put("facilityKey", key));
            if (node == 11) assertFalse(worldState().path("facilities").has("second_once"));
        }
        assertEquals(1, worldState().path("facilities").path("shelter_once").path("count").asInt());
        assertEquals(1, worldState().path("facilities").path("second_once").path("count").asInt());
        assertEquals(0, state(50, 90).path("themeRuntime").path("inventory").path("tool").asInt());
        assertThrows(GameSessionException.class, () -> player(9, "FACILITY_USE", payload().put("facilityKey", "shelter")));
        assertEquals(2, state(50, 99).path("themeRuntime").path("inventory").path("tool").asInt());
    }

    @Test void facilityReadbackKeepsGlobalToolsAndResetsTrustedPassPerInstance() throws Exception {
        sharedWorld(2);assertEquals("FACILITY",readback(7).path("interactionTools").path(0).path("kind").asText());
        basicFrozen(4,false);ObjectNode frozen=frozenReadback();
        ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0)).put("operationNodeId",11).put("limit",2);saveReadbackFixture(frozen);
        ObjectNode start=payload().put("operationKind","FACILITY").put("operationKey","shelter");player(7,"OPERATION_START",start);
        assertFalse(readback(7).path("interactionTools").path(0).path("trustedPass").asBoolean());
        runtime.recordSharedBasicArrival(7L,80L,70L,11L,"readback-tool-scan","SCAN",instance(7,11),1);
        assertTrue(readback(7).path("interactionTools").path(0).path("trustedPass").asBoolean());
        assertTrue(readback(7).path("activeOperation").path("verified").asBoolean());
        assertFalse(readback(7).path("activeOperation").has("verifiedReceiptId"));
        player(7,"FACILITY_USE",payload().put("facilityKey","shelter"));
        JsonNode used=readback(7).path("interactionTools").path(0);assertEquals(1,used.path("count").asInt());assertFalse(used.path("trustedPass").asBoolean());
        player(7,"OPERATION_START",start);assertFalse(readback(7).path("interactionTools").path(0).path("trustedPass").asBoolean());
    }

    @Test void freeNoEffectWorldRecoveryIsEnabledWithoutPaymentResources() throws Exception {
        sharedWorld(2);
        for(long team:new long[]{90,99}) {
            ObjectNode owner=(ObjectNode)state(50,team);owner.with("variables").put("sys.hp",12);owner.with("themeRuntime").with("inventory").put("tool",0);writeOwner("TEAM",team,owner);
        }
        JsonNode free=readback(7).path("interactionTools").path(0);assertTrue(free.path("enabled").asBoolean());assertEquals(0,free.path("count").asInt());
        ObjectNode owner=(ObjectNode)state(50,99);owner.with("variables").put("sys.hp",8);writeOwner("TEAM",99,owner);
        assertEquals("INSUFFICIENT_RESOURCES",readback(7).path("interactionTools").path(0).path("disabledReason").asText());
    }


    @Test void twoMembersBallShakeCombineInOneTransactionAndReplayDoesNotRestoreTwice() throws Exception {
        GameSession session=mapper.selectSessionByActivity(70L);ObjectNode snapshot=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());
        ObjectNode policy=(ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("survival");policy.put("zeroHpPolicy","CHALLENGE");
        policy.putObject("zeroChallenge").put("challengeNodeId",101).put("restoreTo",4).put("limit",3).putArray("alternativeNodeIds").add(102);
        snapshot.putObject("nodeConfigs").putObject("101").putObject("ballShake").put("enabled",true).put("goal",10);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,snapshot.toString());st.executeUpdate();}
        TopicRouteEngine.State state=JSON.readValue(mapper.selectStoryState(50L,"TEAM",90L).getVariablesJson(),TopicRouteEngine.State.class);state.getVariables().put("sys.hp",0);
        new GameplaySurvivalEngine().syncChallenge(snapshot,JourneyRules.parse(snapshot.path("journeyRules").toString()),state);writeOwner("TEAM",90,state);
        String instance=instance(7,101);CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        JsonNode first,second;
        try {
            Future<JsonNode> one=pool.submit(()->{start.await();return runtime.recordSharedZeroChallengeResult(7L,80L,70L,101L,instance,"same-id",false,6L,false,1);});
            Future<JsonNode> two=pool.submit(()->{start.await();return runtime.recordSharedZeroChallengeResult(8L,80L,70L,101L,instance,"same-id",false,6L,false,1);});
            start.countDown();first=one.get(10,TimeUnit.SECONDS);second=two.get(10,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,(first.path("success").asBoolean()?1:0)+(second.path("success").asBoolean()?1:0));
        assertEquals(12,state(50,90).path("themeRuntime").path("challenge").path("progress").asInt());
        assertEquals(4,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(1,state(50,90).path("themeRuntime").path("zeroChallenges").path("used").asInt());
        assertEquals(0,state(50,90).path("completedNodeIds").size());assertEquals(2,receipts());
        assertEquals(JSON.readTree(first.toString()),JSON.readTree(runtime.recordSharedZeroChallengeResult(7L,80L,70L,101L,instance,"same-id",false,6L,false,1).toString()));
        assertEquals(JSON.readTree(second.toString()),JSON.readTree(runtime.recordSharedZeroChallengeResult(8L,80L,70L,101L,instance,"same-id",false,6L,false,1).toString()));
        assertEquals(1,state(50,90).path("themeRuntime").path("zeroChallenges").path("used").asInt());assertEquals(2,receipts());
        assertThrows(GameSessionException.class,()->runtime.recordSharedZeroChallengeResult(8L,80L,70L,101L,instance,"same-id",false,7L,false,1));
        assertThrows(GameSessionException.class,()->runtime.recordSharedZeroChallengeResult(10L,80L,70L,101L,instance,"other-activity",true,10L,false,1));
    }

    private void enableFallback(String policy) throws Exception {
        sharedWorld(2); ObjectNode frozen = frozenReadback(); ObjectNode theme = (ObjectNode) frozen.path("themeConfig");
        theme.with("gameplayRuntime").put("votePolicy", policy).putObject("templateTools").putArray("nodes").addObject().put("decidesEnding", true);
        theme.putArray("endings").addObject().put("id", "none").put("title", "无方案通过").put("fallback", true).put("success", false);
        saveReadbackFixture(frozen);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MAJORITY", "UNANIMOUS", "MAJORITY_ACCEPTANCE"})
    void staffFallbackRequiresTheFinishedFinalRoundAndPersistsOneReceipt(String policy) throws Exception {
        enableFallback(policy);
        assertThrows(GameSessionException.class, () -> staff("PLAN_FALLBACK", payload().put("reason", "尚未投票")));
        player(7, "PLAN_PROPOSE", payload().put("proposalKey", "ending").put("endingKey", "bridge"));
        for (long member : new long[]{7, 9}) { ObjectNode p = payload().put("proposalKey", "ending").put("version", 1); p.set("amounts", amounts("tool", 1)); player(member, "PLAN_INTENT", p); }
        player(7, "VOTE_OPEN", payload()); int round = worldState().path("round").asInt();
        for (long member : new long[]{7, 9}) player(member, "VOTE_CAST", payload().put("round", round).put("proposalKey", "REJECT"));
        if ("MAJORITY_ACCEPTANCE".equals(policy)) {
            assertThrows(GameSessionException.class, () -> staff("PLAN_FALLBACK", payload().put("reason", "接受轮尚未结束")));
            for (long member : new long[]{7, 9}) { ObjectNode p = payload().put("round", round); p.putArray("proposalKeys"); player(member, "ACCEPT_SET", p); }
        }
        assertTrue(JSON.valueToTree(runtime.sharedControlView(GameActorContext.club(88L), 70L)).path("world").path("fallbackAvailable").asBoolean());
        assertEquals("NOT_STAFF", readback(7).path("world").path("fallbackDisabledReason").asText());
        GameSessionCommand close = envelope(88, "PLAN_FALLBACK", payload().put("reason", "最终表决没有通过方案"));
        GameCommandReceiptView first = runtime.command(GameActorContext.club(88L), close);
        int count = receipts(); String saved = worldState().toString();
        GameCommandReceiptView replay = runtime.command(GameActorContext.club(88L), close);
        assertEquals(first.getReceiptId(), replay.getReceiptId()); assertEquals(count, receipts()); assertEquals(saved, worldState().toString());
        assertEquals("none", mapper.selectStoryState(50L, "WORLD", 50L).getEndingCode());
        assertEquals("ENDING_RESOLVED", mapper.selectStoryState(50L, "WORLD", 50L).getStatus());
        for (long team : new long[]{90, 99}) assertEquals(2, state(50, team).path("themeRuntime").path("inventory").path("tool").asInt());
        when(access.require(eq(88L), eq(40L), eq(70L), anyString())).thenReturn(null);
        assertThrows(GameSessionException.class, () -> runtime.command(GameActorContext.club(88L), close));
    }

    @Test void unanimousRejectionFallbackChecksStaffScopeReleasesReservationsAndBlocksNewWrites() throws Exception {
        enableFallback("MAJORITY_ACCEPTANCE");
        ObjectNode offer = payload().put("tradeKey", "pending").put("toTeamId", 99); offer.set("give", amounts("tool", 1)); offer.set("take", amounts("clue", 1));
        player(7, "TRADE_OPEN", offer); player(7, "TRADE_CONFIRM", payload().put("tradeKey", "pending").put("version", 1));
        player(7, "PLAN_REJECT_ALL", payload());
        assertThrows(GameSessionException.class, () -> staff("PLAN_FALLBACK", payload().put("reason", "另队尚未拒绝")));
        player(9, "PLAN_REJECT_ALL", payload());
        assertThrows(GameSessionException.class, () -> player(7, "PLAN_FALLBACK", payload().put("reason", "队长不能收尾")));
        assertThrows(GameSessionException.class, () -> runtime.command(GameActorContext.club(89L), envelope(89, "PLAN_FALLBACK", payload().put("reason", "外来工作人员"))));
        assertThrows(GameSessionException.class, () -> staff("PLAN_FALLBACK", payload()));
        staff("PLAN_FALLBACK", payload().put("reason", "全部当前队长拒绝剩余方案"));
        assertEquals("CANCELLED", worldState().path("trades").path("pending").path("status").asText());
        assertFalse(worldState().has("locks")); assertEquals(2, state(50, 90).path("themeRuntime").path("inventory").path("tool").asInt());
        assertThrows(GameSessionException.class, () -> player(7, "RECOVER", payload().put("recoveryKey", "break")));
        assertThrows(GameSessionException.class, () -> player(9, "QUEST_START", payload().put("questKey", "q1")));
    }

    @Test void interruptionCannotBeReclassifiedAsAFallbackEnding() throws Exception {
        enableFallback("MAJORITY"); player(7, "PLAN_REJECT_ALL", payload()); player(9, "PLAN_REJECT_ALL", payload());
        staff("RUN_INTERRUPT", payload().put("reason", "现场天气中止"));
        assertThrows(GameSessionException.class, () -> staff("PLAN_FALLBACK", payload().put("reason", "不能改为失败结局")));
        assertFalse(worldState().has("result"));
        assertEquals("SESSION_ENDED", JSON.valueToTree(runtime.sharedControlView(GameActorContext.club(88L), 70L)).path("world").path("fallbackDisabledReason").asText());
    }

    @Test void independentTeamFallbackDoesNotOpenVotingOrWriteAReceipt() throws Exception {
        int count = receipts();
        assertEquals("该模式不支持投票", assertThrows(GameSessionException.class, () -> staff("PLAN_FALLBACK", payload().put("reason", "独立局不支持投票"))).getMessage());
        assertEquals(count, receipts()); assertNull(mapper.selectStoryState(50L, "WORLD", 50L));
    }

    @Test void fallbackReceiptFailureRollsBackEndingAndAllReservationReleasesBeforeRetry() throws Exception {
        enableFallback("MAJORITY");
        ObjectNode offer = payload().put("tradeKey", "pending").put("toTeamId", 99); offer.set("give", amounts("tool", 1)); offer.set("take", amounts("clue", 1));
        player(7, "TRADE_OPEN", offer); player(7, "TRADE_CONFIRM", payload().put("tradeKey", "pending").put("version", 1));
        player(7, "PLAN_REJECT_ALL", payload()); player(9, "PLAN_REJECT_ALL", payload());
        GameSessionCommand close = envelope(88, "PLAN_FALLBACK", payload().put("reason", "全体拒绝"));
        JsonNode before = worldState(), owner = state(50, 90); int revision = mapper.selectSessionByActivity(70L).getRevision(), count = receipts();
        GameSessionRuntimeMapper failing = (GameSessionRuntimeMapper) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{GameSessionRuntimeMapper.class}, (proxy, method, args) -> {
            if (method.getName().equals("completeReceipt")) throw new IllegalStateException("test receipt failure");
            try { return method.invoke(mapper, args); } catch (java.lang.reflect.InvocationTargetException cause) { throw cause.getCause(); }
        });
        ReflectionTestUtils.setField(target, "runtimeMapper", failing);
        try { assertThrows(IllegalStateException.class, () -> runtime.command(GameActorContext.club(88L), close)); }
        finally { ReflectionTestUtils.setField(target, "runtimeMapper", mapper); }
        assertEquals(before, worldState()); assertEquals(owner, state(50, 90)); assertEquals(count, receipts());
        assertEquals(revision, mapper.selectSessionByActivity(70L).getRevision()); assertNull(mapper.selectStoryState(50L, "WORLD", 50L).getEndingCode());
        runtime.command(GameActorContext.club(88L), close); assertEquals("none", worldState().path("result").path("endingKey").asText());
    }

    @Test void twoTeamsTradeOnlyAfterBothActualCaptainsSignAndExitReleasesLocks() throws Exception {
        sharedWorld(2); ObjectNode open=payload().put("tradeKey","swap").put("toTeamId",99);open.set("give",amounts("tool",1));open.set("take",amounts("clue",1));
        player(7,"TRADE_OPEN",open);player(7,"TRADE_CONFIRM",payload().put("tradeKey","swap").put("version",1));
        assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        assertThrows(GameSessionException.class,()->player(8,"TRADE_CONFIRM",payload().put("tradeKey","swap").put("version",1)));
        player(9,"TRADE_CONFIRM",payload().put("tradeKey","swap").put("version",1));
        assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(3,state(50,99).path("themeRuntime").path("inventory").path("tool").asInt());
        open=payload().put("tradeKey","gift").put("toTeamId",99);open.set("give",amounts("tool",1));open.set("take",JSON.createArrayNode());player(7,"TRADE_OPEN",open);player(7,"TRADE_CONFIRM",payload().put("tradeKey","gift").put("version",1));
        player(7,"TEAM_EXIT",payload().put("teamId",90).put("reason","现场退出"));assertEquals("CANCELLED",worldState().path("trades").path("gift").path("status").asText());
        assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertThrows(GameSessionException.class,()->player(7,"ITEM_DISCARD",payload().put("contentId","tool")));
    }
    @Test void fourTeamsResolveTieThroughAcceptanceConsensusEscrowAndTrustedOperation() throws Exception {
        sharedWorld(4);player(7,"PLAN_PROPOSE",payload().put("proposalKey","p1").put("endingKey","bridge"));player(9,"PLAN_PROPOSE",payload().put("proposalKey","p2").put("endingKey","trail"));
        for(long member:new long[]{7,9})for(String proposal:new String[]{"p1","p2"}) {ObjectNode p=payload().put("proposalKey",proposal).put("version",1);p.set("amounts",amounts(proposal.equals("p1")?"tool":"clue",1));player(member,"PLAN_INTENT",p);}
        player(7,"VOTE_OPEN",payload());int round=worldState().path("round").asInt();
        for(long member:new long[]{7,9,11,12})player(member,"VOTE_CAST",payload().put("round",round).put("proposalKey",member<10?"p1":"p2"));assertEquals("TIED",worldState().path("stage").asText());
        for(long member:new long[]{7,9,11,12}) {ObjectNode p=payload().put("round",round);p.putArray("proposalKeys").add("p1").add("p2");player(member,"ACCEPT_SET",p);}assertEquals("CONSENSUS",worldState().path("stage").asText());
        for(long member:new long[]{7,9,11,12})player(member,"CONSENSUS_CAST",payload().put("round",round).put("proposalKey","p1"));assertEquals("SELECTED",worldState().path("stage").asText());
        for(long member:new long[]{7,9})player(member,"PLAN_CONFIRM",payload().put("round",round).put("proposalKey","p1"));
        assertThrows(GameSessionException.class,()->player(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","p1")));
        player(7,"OPERATION_START",payload().put("operationKind","PLAN").put("operationKey","bridge"));
        runtime.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"final",true,Collections.emptyList(),1);
        player(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","p1"));assertEquals("bridge",worldState().path("result").path("endingKey").asText());
        assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(1,state(50,99).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(2,state(50,101).path("themeRuntime").path("inventory").path("tool").asInt());
        assertThrows(GameSessionException.class,()->player(9,"FACILITY_USE",payload().put("facilityKey","shelter")));
    }
    @Test void planPreviewCommandAndHistoricalReceiptsNeverExposeContributionSigners() throws Exception {
        sharedWorld(2);player(7,"PLAN_PROPOSE",payload().put("proposalKey","private_signers").put("endingKey","bridge"));
        for(long member:new long[]{7,9}) {ObjectNode intent=payload().put("proposalKey","private_signers").put("version",1);intent.set("amounts",amounts("tool",1));player(member,"PLAN_INTENT",intent);}
        player(7,"VOTE_OPEN",payload());int round=worldState().path("round").asInt();
        for(long member:new long[]{7,9})player(member,"VOTE_CAST",payload().put("round",round).put("proposalKey","private_signers"));
        for(long member:new long[]{7,9})player(member,"PLAN_CONFIRM",payload().put("round",round).put("proposalKey","private_signers"));
        player(7,"OPERATION_START",payload().put("operationKind","PLAN").put("operationKey","bridge"));
        runtime.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"private-signer-proof",true,Collections.emptyList(),1);
        ObjectNode selected=payload().put("round",round).put("proposalKey","private_signers");
        JsonNode preview=JSON.valueToTree(runtime.sharedPreview(GameActorContext.player(7L),70L,"PLAN_EXECUTE",JSON.convertValue(selected,Map.class)));
        assertFalse(preview.toString().contains("memberId"));assertEquals(amounts("tool",1),preview.path("result").path("contributions").path("99"));
        GameSessionCommand command=envelope(7,"PLAN_EXECUTE",selected);GameCommandReceiptView applied=runtime.command(GameActorContext.player(7L),command);
        assertFalse(JSON.valueToTree(applied.getResult()).toString().contains("memberId"));
        assertTrue(worldState().path("result").path("contributions").path("99").has("memberId"));
        ObjectNode historical=JSON.valueToTree(applied.getResult());historical.set("result",worldState().path("result"));
        try(Connection connection=ds.getConnection();PreparedStatement update=connection.prepareStatement("UPDATE game_command_receipt SET result_json=? WHERE id=?")) {
            update.setString(1,historical.toString());update.setLong(2,applied.getReceiptId());assertEquals(1,update.executeUpdate());
        }
        GameCommandReceiptView replay=runtime.command(GameActorContext.player(7L),command);
        JsonNode readback=JSON.valueToTree(runtime.receipt(GameActorContext.player(7L),70L,command.getRequestId()).getResult());
        assertTrue(replay.isReplayed());assertFalse(JSON.valueToTree(replay.getResult()).toString().contains("memberId"));
        assertFalse(readback.toString().contains("memberId"));assertEquals(amounts("tool",1),readback.path("result").path("contributions").path("99"));
    }

    @Test void operationArrivalMatchesPersistedActiveOperationByValue() throws Exception {
        // 已开始的操作存库再读回后节点号可能是 int/long 不同类型；到达凭证须按值匹配，否则需要到达的设施与方案永远无法核验
        sharedWorld(2);try(Connection c=ds.getConnection()) {exec(c,"UPDATE game_station_run SET source_type='HOST_NODE' WHERE game_session_id=50 AND node_id=11");}
        basicFrozen(4,false);ObjectNode frozen=frozenReadback();
        ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0)).put("operationNodeId",11);saveReadbackFixture(frozen);
        ObjectNode op=payload().put("operationKind","FACILITY").put("operationKey","shelter");
        assertThrows(GameSessionException.class,()->player(7,"ARRIVAL_ISSUE",op.deepCopy()));
        player(7,"OPERATION_START",op.deepCopy());
        String nonce=(String)player(7,"ARRIVAL_ISSUE",op.deepCopy()).getResult().get("nonce");
        assertNotNull(nonce);
        staff("ARRIVAL_CONFIRM",op.deepCopy().put("teamId",90).put("nonce",nonce));
    }

    @Test void hostCredentialBindsActualTeamEpochExpiryAndOneUse() throws Exception {
        sharedWorld(2);try(Connection c=ds.getConnection()) {exec(c,"UPDATE game_station_run SET source_type='HOST_NODE' WHERE game_session_id=50 AND node_id=11");}
        assertThrows(GameSessionException.class,()->player(7,"QUEST_START",payload().put("questKey","q1")));
        GameCommandReceiptView issued=player(7,"ARRIVAL_ISSUE",payload().put("questKey","q1"));String nonce=(String)issued.getResult().get("nonce");
        ObjectNode confirm=payload().put("teamId",90).put("questKey","q1").put("nonce",nonce);
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.club(89L),envelope(89,"ARRIVAL_CONFIRM",confirm)));
        assertThrows(GameSessionException.class,()->staff("ARRIVAL_CONFIRM",payload().put("teamId",99).put("questKey","q1").put("nonce",nonce)));
        staff("ARRIVAL_CONFIRM",confirm);assertThrows(GameSessionException.class,()->staff("ARRIVAL_CONFIRM",confirm));player(7,"QUEST_START",payload().put("questKey","q1"));
        assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());
        staff("RUN_PAUSE",payload().put("reason","到达过期校验"));ObjectNode run=(ObjectNode)runState();for(JsonNode a:run.path("arrivals"))((ObjectNode)a).put("expiresAt",0);writeOwner("RUN",50,run);staff("RUN_RESUME",payload().put("reason","继续"));
        assertThrows(GameSessionException.class,()->runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"late",true,Collections.emptyList(),1));
    }
    @Test void pausedAndInterruptedRunRejectLateSettlementWithoutCostOrChapterRations() throws Exception {
        sharedWorld(2);
        ObjectNode snapshot = (ObjectNode) JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());
        ArrayNode chapters = ((ObjectNode) snapshot.path("themeConfig")).putArray("chapters");
        ObjectNode first = chapters.addObject().put("index", 0);
        first.putObject("binding").putArray("nodeIds").add(11);
        first.putArray("grants").addObject().put("timing", "END").put("itemId", "clue").put("quantity", 1);
        ObjectNode next = chapters.addObject().put("index", 1);
        next.putObject("binding").putArray("nodeIds").add(12);
        next.putArray("grants").addObject().put("timing", "START").put("itemId", "tool").put("quantity", 1);
        try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {
            st.setString(1, snapshot.toString()); st.executeUpdate();
        }
        player(7, "QUEST_START", payload().put("questKey", "q1"));
        String activeInstance = instance(8, 11);
        for (String stop : Arrays.asList("RUN_PAUSE", "RUN_INTERRUPT")) {
            staff(stop, payload().put("reason", "现场停止后核对迟到任务"));
            JsonNode owner = state(50, 90), other = state(50, 99), world = worldState(), run = runState();
            int revision = mapper.selectSessionByActivity(70L).getRevision(), count = receipts();
            assertThrows(GameSessionException.class, () -> player(7, "FACILITY_USE", payload().put("facilityKey", "shelter")));
            assertThrows(GameSessionException.class, () -> player(7, "QUEST_RESOLVE", payload().put("questKey", "q1")));
            assertThrows(GameSessionException.class, () -> runtime.recordSharedRouteResult(
                    8L, 80L, 70L, 11L, activeInstance, "late-success-" + stop, true, Collections.emptyList(), 1));
            assertEquals(owner, state(50, 90)); assertEquals(other, state(50, 99));
            assertEquals(world, worldState()); assertEquals(run, runState());
            assertEquals(revision, mapper.selectSessionByActivity(70L).getRevision()); assertEquals(count, receipts());
            assertEquals(2, state(50, 90).path("themeRuntime").path("inventory").path("clue").asInt());
        }
    }

    @Test void pauseAndResourceUseRaceCommitsOnlyOneWholeCommand() throws Exception {
        sharedWorld(2);
        GameSessionCommand pause = envelope(88, "RUN_PAUSE", payload().put("reason", "资源操作竞态暂停"));
        GameSessionCommand use = envelope(7, "FACILITY_USE", payload().put("facilityKey", "shelter"));
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> stopped = pool.submit(() -> { start.await(); try {
                runtime.command(GameActorContext.club(88L), pause); return true;
            } catch (GameSessionException conflict) { return false; } });
            Future<Boolean> consumed = pool.submit(() -> { start.await(); try {
                runtime.command(GameActorContext.player(7L), use); return true;
            } catch (GameSessionException conflict) { return false; } });
            start.countDown();
            boolean pauseWon = stopped.get(15, TimeUnit.SECONDS), useWon = consumed.get(15, TimeUnit.SECONDS);
            assertNotEquals(pauseWon, useWon);
            assertEquals(1, receipts()); assertEquals(1, mapper.selectSessionByActivity(70L).getRevision());
            assertEquals(pauseWon ? "PAUSED" : "RUNNING", runState().path("operationStatus").asText());
            assertEquals(useWon ? 1 : 0, worldState().path("facilities").path("shelter_once").path("count").asInt());
            assertEquals(useWon ? 1 : 2, state(50, 90).path("themeRuntime").path("inventory").path("tool").asInt());
            assertEquals(2, state(50, 99).path("themeRuntime").path("inventory").path("tool").asInt());
            for (long team : new long[]{90, 99}) assertEquals(useWon ? 10 : 8, state(50, team).path("variables").path("sys.hp").asInt());
            if (useWon) staff("RUN_PAUSE", payload().put("reason", "资源提交后暂停"));
            JsonNode owner = state(50, 90), world = worldState(); int count = receipts();
            assertThrows(GameSessionException.class, () -> player(7, "FACILITY_USE", payload().put("facilityKey", "shelter")));
            assertEquals(owner, state(50, 90)); assertEquals(world, worldState()); assertEquals(count, receipts());
        } finally { pool.shutdownNow(); }
    }

    @Test void consumedManualSignatureAndOriginalReceiptNeverAddBalanceAfterEpochChange() throws Exception {
        sharedWorld(2); staff("RUN_PAUSE", payload().put("reason", "人工接管"));
        Map<String, Object> exported = runtime.exportSharedRun(GameActorContext.club(88L), 70L);
        ObjectNode begin = payload().put("reason", "纸账核对").put("configHash", (String) exported.get("configHash"))
                .put("highWaterHash", (String) exported.get("highWaterHash"));
        begin.putArray("unknownRequests"); staff("MANUAL_BEGIN", begin);
        ObjectNode signed = payload().put("sequence", 1).put("occurredAt", System.currentTimeMillis());
        signed.putObject("command").put("action", "RECOVER").putObject("payload").put("recoveryKey", "break");
        String signature = String.valueOf(player(7, "MANUAL_SIGN", signed).getResult().get("signatureId"));
        GameSessionCommand original = envelope(88, "MANUAL_IMPORT", payload().put("signatureId", signature));
        GameCommandReceiptView applied = runtime.command(GameActorContext.club(88L), original);
        JsonNode once = state(50, 90);
        GameCommandReceiptView duplicate = staff("MANUAL_IMPORT", payload().put("signatureId", signature));
        assertEquals(applied.getResult().get("manualResult"), duplicate.getResult().get("manualResult"));
        assertEquals(once, state(50, 90));
        assertTrue(runState().path("manual").path("signatures").path(signature).path("used").asBoolean());
        assertEquals(2, runState().path("manual").path("nextSequence").asInt());
        staff("AUTHORITY_RESUME", payload().put("reason", "纸账完成")); staff("RUN_RESUME", payload().put("reason", "恢复在线"));
        JsonNode resumedOwner = state(50, 90), resumedRun = runState(); int count = receipts();
        GameSessionCommand oldDevice = envelope(88, "MANUAL_IMPORT", payload().put("signatureId", signature));
        oldDevice.setAuthorityEpoch(1);
        assertThrows(GameSessionException.class, () -> runtime.command(GameActorContext.club(88L), oldDevice));
        GameCommandReceiptView replay = runtime.command(GameActorContext.club(88L), original);
        assertTrue(replay.isReplayed()); assertEquals(applied.getReceiptId(), replay.getReceiptId());
        assertEquals(resumedOwner, state(50, 90)); assertEquals(resumedRun, runState()); assertEquals(count, receipts());
        assertEquals(10, state(50, 90).path("variables").path("sys.hp").asInt());
        assertEquals(1, state(50, 90).path("themeRuntime").path("recoveries").path("break").asInt());
    }

    @Test void manualImportReceiptFailureRollsBackBalanceUsedFlagAndSequenceBeforeRetry() throws Exception {
        sharedWorld(2); staff("RUN_PAUSE", payload().put("reason", "人工接管回滚验收"));
        Map<String, Object> exported = runtime.exportSharedRun(GameActorContext.club(88L), 70L);
        ObjectNode begin = payload().put("reason", "纸账核对").put("configHash", (String) exported.get("configHash"))
                .put("highWaterHash", (String) exported.get("highWaterHash"));
        begin.putArray("unknownRequests"); staff("MANUAL_BEGIN", begin);
        ObjectNode signed = payload().put("sequence", 1).put("occurredAt", System.currentTimeMillis());
        signed.putObject("command").put("action", "RECOVER").putObject("payload").put("recoveryKey", "break");
        String signature = String.valueOf(player(7, "MANUAL_SIGN", signed).getResult().get("signatureId"));
        GameSessionCommand command = envelope(88, "MANUAL_IMPORT", payload().put("signatureId", signature));
        JsonNode owner = state(50, 90), run = runState(), world = worldState();
        int revision = mapper.selectSessionByActivity(70L).getRevision(), count = receipts();
        GameSessionRuntimeMapper failing = (GameSessionRuntimeMapper) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{GameSessionRuntimeMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("completeReceipt") && "MANUAL_IMPORT".equals(((GameCommandReceipt) args[0]).getAction()))
                        throw new IllegalStateException("test outer manual receipt failure");
                    try { return method.invoke(mapper, args); }
                    catch (java.lang.reflect.InvocationTargetException cause) { throw cause.getCause(); }
                });
        ReflectionTestUtils.setField(target, "runtimeMapper", failing);
        try { assertThrows(IllegalStateException.class, () -> runtime.command(GameActorContext.club(88L), command)); }
        finally { ReflectionTestUtils.setField(target, "runtimeMapper", mapper); }
        assertEquals(owner, state(50, 90)); assertEquals(run, runState()); assertEquals(world, worldState());
        assertEquals(count, receipts()); assertEquals(revision, mapper.selectSessionByActivity(70L).getRevision());
        assertFalse(runState().path("manual").path("signatures").path(signature).path("used").asBoolean());
        assertEquals(1, runState().path("manual").path("nextSequence").asInt());
        GameCommandReceiptView applied = runtime.command(GameActorContext.club(88L), command);
        assertEquals(10, state(50, 90).path("variables").path("sys.hp").asInt());
        assertEquals(1, state(50, 90).path("themeRuntime").path("recoveries").path("break").asInt());
        assertTrue(runState().path("manual").path("signatures").path(signature).path("used").asBoolean());
        assertEquals(2, runState().path("manual").path("nextSequence").asInt());
        JsonNode after = state(50, 90); int afterCount = receipts();
        assertEquals(applied.getReceiptId(), runtime.command(GameActorContext.club(88L), command).getReceiptId());
        assertEquals(after, state(50, 90)); assertEquals(afterCount, receipts());
    }

    @Test void manualReplayRequiresActualSignerContinuousSequenceAndRejectsOldEpoch() throws Exception {
        sharedWorld(2);staff("RUN_PAUSE",payload().put("reason","人工接管"));Map<String,Object> exported=runtime.exportSharedRun(GameActorContext.club(88L),70L);
        ObjectNode begin=payload().put("reason","断网接管").put("configHash",(String)exported.get("configHash")).put("highWaterHash",(String)exported.get("highWaterHash"));begin.putArray("unknownRequests");staff("MANUAL_BEGIN",begin);
        ObjectNode signed=payload().put("sequence",1).put("occurredAt",System.currentTimeMillis());signed.putObject("command").put("action","RECOVER").putObject("payload").put("recoveryKey","break");
        assertThrows(GameSessionException.class,()->player(8,"MANUAL_SIGN",signed));Object signature=player(7,"MANUAL_SIGN",signed).getResult().get("signatureId");
        assertThrows(GameSessionException.class,()->player(7,"MANUAL_SIGN",signed));staff("MANUAL_IMPORT",payload().put("signatureId",String.valueOf(signature)));assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());
        staff("MANUAL_IMPORT",payload().put("signatureId",String.valueOf(signature)));assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());staff("AUTHORITY_RESUME",payload().put("reason","纸账核对完成"));
        assertEquals(2,runState().path("authorityEpoch").asInt());staff("RUN_RESUME",payload().put("reason","在线恢复"));GameSessionCommand stale=envelope(7,"RECOVER",payload().put("recoveryKey","break"));stale.setAuthorityEpoch(1);
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),stale));
        assertThrows(GameSessionException.class,()->runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"old",true,Collections.emptyList(),1));
    }
    @Test void manualAuthorityRejectsRepeatedPauseWithoutMutatingAnchorAndKeepsEmergencyStops() throws Exception {
        sharedWorld(2);
        staff("RUN_PAUSE",payload().put("reason","正常暂停"));
        assertEquals("PAUSED",runState().path("operationStatus").asText());
        assertEquals("正常暂停",runState().path("operationReason").asText());
        staff("RUN_RESUME",payload().put("reason","正常恢复"));
        assertEquals("RUNNING",runState().path("operationStatus").asText());

        staff("RUN_PAUSE",payload().put("reason","切换人工账"));
        Map<String,Object> exported=runtime.exportSharedRun(GameActorContext.club(88L),70L);
        ObjectNode begin=payload().put("reason","现场断网")
                .put("configHash",(String)exported.get("configHash"))
                .put("highWaterHash",(String)exported.get("highWaterHash"));
        begin.putArray("unknownRequests");staff("MANUAL_BEGIN",begin);
        JsonNode before=runState().deepCopy();
        GameSessionException rejected=assertThrows(GameSessionException.class,
                ()->staff("RUN_PAUSE",payload().put("reason","旧端重复暂停")));
        assertEquals("GAME_RUN_PAUSED",rejected.getErrorCode());
        JsonNode after=runState();
        assertEquals(before.path("operationReason"),after.path("operationReason"));
        assertEquals(before.path("manual").path("highWaterHash"),after.path("manual").path("highWaterHash"));
        assertEquals(before.path("authorityEpoch"),after.path("authorityEpoch"));
        assertEquals(before,after,"被拒绝的重复暂停不得留下任何运行态副作用");
        staff("RUN_INTERRUPT",payload().put("reason","天气紧急中止"));
        assertEquals("INTERRUPTED",runState().path("operationStatus").asText());

        seed();sharedWorld(2);staff("RUN_PAUSE",payload().put("reason","取消前暂停"));
        exported=runtime.exportSharedRun(GameActorContext.club(88L),70L);
        begin=payload().put("reason","取消前人工接管")
                .put("configHash",(String)exported.get("configHash"))
                .put("highWaterHash",(String)exported.get("highWaterHash"));
        begin.putArray("unknownRequests");staff("MANUAL_BEGIN",begin);
        staff("RUN_CANCEL",payload().put("reason","安全取消"));
        assertEquals("CANCELLED",runState().path("operationStatus").asText());
    }
    @Test void coupledFacilityQuestHasOneWorldWinnerAndLoserKeepsMaterialAndTaskSlots() throws Exception {
        sharedWorld(2);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode snapshot=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());
        ((ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("quests").path(0)).put("facilityKey","shelter").put("extraSlot","mobile");
        ((ObjectNode)snapshot.path("themeConfig").path("gameplayRuntime").path("facilities").path(0)).put("operationNodeId",11);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,snapshot.toString());st.executeUpdate();}
        player(7,"QUEST_START",payload().put("questKey","q1"));player(9,"QUEST_START",payload().put("questKey","q1"));
        runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"winner-answer",true,Collections.emptyList(),1);
        runtime.recordSharedRouteResult(9L,80L,70L,11L,instance(9,11),"other-answer",true,Collections.emptyList(),1);
        player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));GameCommandReceiptView lost=player(9,"QUEST_RESOLVE",payload().put("questKey","q1"));
        assertEquals(Boolean.TRUE,lost.getResult().get("competitionLost"));assertEquals(Boolean.FALSE,lost.getResult().get("settled"));
        assertEquals(0,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(2,state(50,99).path("themeRuntime").path("inventory").path("tool").asInt());
        assertEquals(1,state(50,90).path("variables").path("sys.actions").asInt());assertEquals(0,state(50,99).path("variables").path("sys.actions").asInt());
        assertFalse(state(50,99).path("themeRuntime").path("gameplay").path("quests").has("q1"));assertFalse(state(50,99).path("themeRuntime").path("gameplay").path("extraSlots").has("mobile"));assertEquals(1,worldState().path("facilities").path("shelter_once").path("count").asInt());
        JsonNode tools=JSON.valueToTree(runtime.view(GameActorContext.player(9L),70L).getPlayer().get("interactionTools"));JsonNode facility=tools.path(0);assertEquals("shelter",facility.path("operationKey").asText());assertEquals("WORLD",facility.path("scope").asText());assertEquals(0,facility.path("remainingCount").asInt());assertEquals(1,facility.path("costs").path("resourceCosts").path(0).path("quantity").asInt());
    }
    @Test void lateReceiptHashConflictCannotResumeWithoutExplicitReviewAndNeverReplaysBalance() throws Exception {
        sharedWorld(2);GameSessionCommand recovered=envelope(7,"RECOVER",payload().put("recoveryKey","break"));runtime.command(GameActorContext.player(7L),recovered);
        staff("RUN_PAUSE",payload().put("reason","核对未知请求"));Map<String,Object> exported=runtime.exportSharedRun(GameActorContext.club(88L),70L);
        ObjectNode begin=payload().put("reason","人工核对").put("configHash",(String)exported.get("configHash")).put("highWaterHash",(String)exported.get("highWaterHash"));
        begin.putArray("unknownRequests").addObject().put("memberId",7).put("requestId",recovered.getRequestId()).put("requestHash",String.join("",Collections.nCopies(64,"0")));staff("MANUAL_BEGIN",begin);
        staff("MANUAL_REVIEW",payload().put("acceptCurrentHighWater",true).put("reason","余额回读正确"));
        assertThrows(GameSessionException.class,()->staff("AUTHORITY_RESUME",payload().put("reason","仍有冲突")));
        assertThrows(GameSessionException.class,()->staff("MANUAL_REVIEW",payload().put("memberId",7).put("requestId",recovered.getRequestId()).put("reason","未授权忽略指纹")));
        staff("MANUAL_REVIEW",payload().put("memberId",7).put("requestId",recovered.getRequestId()).put("acceptLateConflict",true).put("reason","已逐条核对迟到回执，不重放"));
        staff("AUTHORITY_RESUME",payload().put("reason","复核完成"));assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(1,state(50,90).path("themeRuntime").path("recoveries").path("break").asInt());
    }
    @Test void reboundHostLocationReadsFrozenQuestionAndRejectsPreviousLocationProof() throws Exception {
        sharedWorld(2);try(Connection c=ds.getConnection()) {exec(c,"UPDATE game_station_run SET source_type='HOST_NODE' WHERE game_session_id=50");}
        staff("RUN_PAUSE",payload().put("reason","批准备用点"));staff("LOCATION_REBIND",payload().put("questKey","q1").put("nodeId",12).put("reason","现场迁点"));staff("RUN_RESUME",payload().put("reason","备用点开始"));
        assertThrows(GameSessionException.class,()->runtime.sharedRouteSnapshot(8L,80L,70L,11L));
        String nonce=(String)player(7,"ARRIVAL_ISSUE",payload().put("questKey","q1")).getResult().get("nonce");staff("ARRIVAL_CONFIRM",payload().put("teamId",90).put("questKey","q1").put("nonce",nonce));
        player(7,"QUEST_START",payload().put("questKey","q1"));JsonNode snapshot=runtime.sharedRouteSnapshot(8L,80L,70L,12L);assertTrue(snapshot.path("nodeConfigs").path("12").path("frozen").asBoolean());runtime.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"rebound",true,Collections.emptyList(),1);player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));
        assertTrue(state(50,90).path("themeRuntime").path("gameplay").path("quests").has("q1"));assertEquals(1,state(50,90).path("variables").path("sys.actions").asInt());
    }

    @Test void concurrentWorldFacilityCommitsOneChargeAndRetryReadsSameWorldSlot() throws Exception {
        sharedWorld(2);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> work=new ArrayList<>();
        for(long member:new long[]{7,9}) {GameSessionCommand c=envelope(member,"FACILITY_USE",payload().put("facilityKey","shelter"));work.add(pool.submit(()->{start.await();try{runtime.command(GameActorContext.player(member),c);return true;}catch(GameSessionException conflict){return false;}}));}
        start.countDown();int applied=0;for(Future<Boolean> f:work)if(f.get(15,TimeUnit.SECONDS))applied++;pool.shutdownNow();assertEquals(1,applied);
        player(9,"FACILITY_USE",payload().put("facilityKey","shelter"));assertEquals(1,worldState().path("facilities").path("shelter_once").path("count").asInt());
        assertEquals(3,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt()+state(50,99).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(10,state(50,99).path("variables").path("sys.hp").asInt());
    }
    @Test void operationalInterruptionReleasesEscrowPreservesAssetsAndNeverCreatesGameFailureEnding() throws Exception {
        sharedWorld(2);ObjectNode open=payload().put("tradeKey","interrupt").put("toTeamId",99);open.set("give",amounts("tool",1));open.set("take",amounts("clue",1));player(7,"TRADE_OPEN",open);player(7,"TRADE_CONFIRM",payload().put("tradeKey","interrupt").put("version",1));
        staff("RUN_INTERRUPT",payload().put("reason","天气中止运营"));assertEquals("INTERRUPTED",runState().path("operationStatus").asText());assertEquals("CANCELLED",worldState().path("trades").path("interrupt").path("status").asText());
        assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertFalse(worldState().has("result"));assertFalse(state(50,90).path("themeRuntime").has("result"));
        assertThrows(GameSessionException.class,()->player(7,"RECOVER",payload().put("recoveryKey","break")));assertThrows(GameSessionException.class,()->staff("RUN_RESUME",payload().put("reason","禁止恢复真实中止")));
        assertEquals("INTERRUPTED",runtime.exportSharedRun(GameActorContext.club(88L),70L).get("operationStatus"));assertTrue(runtime.view(GameActorContext.player(7L),70L).getAvailableActions().isEmpty());
    }

    @Test void realOfflinePaperEventsUsePriorServerAnchorAndRejectUntrustedOrOldEpochTime() throws Exception {
        sharedWorld(2);long base=System.currentTimeMillis();java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(base);ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->new java.util.Date(now.get()));
        int revision=mapper.selectSessionByActivity(70L).getRevision();Map<String,Object> exported=runtime.anchorSharedRun(GameActorContext.club(88L),70L);assertEquals(revision,mapper.selectSessionByActivity(70L).getRevision());assertEquals(0,receipts());
        JsonNode anchor=(JsonNode)exported.get("anchor");assertEquals(base,anchor.path("at").asLong());now.set(base+60000);staff("RUN_PAUSE",payload().put("reason","断网恢复后冻结"));
        ObjectNode begin=payload().put("reason","接纳断网期间纸账").put("configHash",(String)exported.get("configHash")).put("highWaterHash",(String)exported.get("highWaterHash")).put("offlineStartedAt",base-1);begin.putArray("unknownRequests");
        assertThrows(GameSessionException.class,()->staff("MANUAL_BEGIN",begin));begin.put("offlineStartedAt",base+120000);assertThrows(GameSessionException.class,()->staff("MANUAL_BEGIN",begin));
        begin.put("offlineStartedAt",base+1000);staff("MANUAL_BEGIN",begin);assertEquals(base+1000,runState().path("manual").path("startedAt").asLong());assertTrue(runState().path("manual").path("needsReview").asBoolean());
        ObjectNode signed=payload().put("sequence",1).put("occurredAt",base+5000);signed.putObject("command").put("action","RECOVER").putObject("payload").put("recoveryKey","break");
        assertThrows(GameSessionException.class,()->player(7,"MANUAL_SIGN",signed));staff("MANUAL_REVIEW",payload().put("acceptCurrentHighWater",true).put("reason","最后确认锚点与在线余额逐条一致"));
        String signature=String.valueOf(player(7,"MANUAL_SIGN",signed).getResult().get("signatureId"));staff("MANUAL_IMPORT",payload().put("signatureId",signature));assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());
        assertTrue(base+5000<runState().path("manual").path("serverStartedAt").asLong());staff("AUTHORITY_RESUME",payload().put("reason","纸账完成"));assertEquals(2,runState().path("authorityEpoch").asInt());
        assertThrows(GameSessionException.class,()->staff("MANUAL_BEGIN",begin));
    }

    @Test void offlineHostArrivalReplaysOnlyStaffSignedPaperAndRetainsOnlineNonceRules() throws Exception {
        sharedWorld(2);try(Connection c=ds.getConnection()) {exec(c,"UPDATE game_station_run SET source_type='HOST_NODE' WHERE game_session_id=50 AND node_id=11");}
        long base=System.currentTimeMillis();java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(base);ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->new java.util.Date(now.get()));
        Map<String,Object> exported=runtime.anchorSharedRun(GameActorContext.club(88L),70L);now.set(base+600000);staff("RUN_PAUSE",payload().put("reason","恢复后冻结"));
        ObjectNode begin=payload().put("reason","断网纸账").put("configHash",(String)exported.get("configHash")).put("highWaterHash",(String)exported.get("highWaterHash")).put("offlineStartedAt",base+1000);begin.putArray("unknownRequests");staff("MANUAL_BEGIN",begin);staff("MANUAL_REVIEW",payload().put("acceptCurrentHighWater",true).put("reason","锚点与余额逐条一致"));
        ObjectNode attest=payload().put("sequence",1).put("occurredAt",base+5000);attest.putObject("command").put("action","ARRIVAL_ATTEST").putObject("payload").put("teamId",90).put("questKey","q1").put("reason","纸账记录本人现场核验");
        assertThrows(GameSessionException.class,()->player(7,"MANUAL_SIGN",attest));String id=String.valueOf(staff("STAFF_MANUAL_SIGN",attest).getResult().get("signatureId"));staff("MANUAL_IMPORT",payload().put("signatureId",id));
        assertEquals(base+5000,runState().path("arrivals").path("90:yard").path("verifiedAt").asLong());assertEquals(88,runState().path("arrivals").path("90:yard").path("verifiedBy").asInt());
        ObjectNode start=payload().put("sequence",2).put("occurredAt",base+6000);start.putObject("command").put("action","QUEST_START").putObject("payload").put("questKey","q1");id=String.valueOf(player(7,"MANUAL_SIGN",start).getResult().get("signatureId"));staff("MANUAL_IMPORT",payload().put("signatureId",id));
        ObjectNode guide=payload().put("sequence",3).put("occurredAt",base+7000);ObjectNode args=guide.putObject("command").put("action","QUEST_GUIDE").putObject("payload").put("teamId",90).put("questKey","q1").put("reason","纸账核对真实题组答案");args.putObject("answers");id=String.valueOf(staff("STAFF_MANUAL_SIGN",guide).getResult().get("signatureId"));staff("MANUAL_IMPORT",payload().put("signatureId",id));
        ObjectNode finish=payload().put("sequence",4).put("occurredAt",base+8000);finish.putObject("command").put("action","QUEST_RESOLVE").putObject("payload").put("questKey","q1");id=String.valueOf(player(7,"MANUAL_SIGN",finish).getResult().get("signatureId"));staff("MANUAL_IMPORT",payload().put("signatureId",id));assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        assertThrows(GameSessionException.class,()->staff("ARRIVAL_ATTEST",payload().put("teamId",90).put("questKey","q2").put("reason","不能在线绕凭证")));
    }

    @Test void receiptReplayChecksCurrentMembershipAndStaffAccessWithoutRequiringOldCaptainOrEpoch() throws Exception {
        sharedWorld(2);GameSessionCommand used=envelope(7,"RECOVER",payload().put("recoveryKey","break"));runtime.command(GameActorContext.player(7L),used);
        try(Connection c=ds.getConnection()) {exec(c,"UPDATE play_team SET leader_member_id=8 WHERE id=90");}
        assertTrue(runtime.command(GameActorContext.player(7L),used).isReplayed());assertTrue(runtime.receipt(new GameActorContext(7L,null),70L,used.getRequestId()).isReplayed());
        try(Connection c=ds.getConnection()) {exec(c,"UPDATE play_team_member SET status=0 WHERE member_id=7 AND team_id=90");}
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),used));assertThrows(GameSessionException.class,()->runtime.receipt(new GameActorContext(7L,null),70L,used.getRequestId()));
        GameSessionCommand pause=envelope(88,"RUN_PAUSE",payload().put("reason","测试回执权限"));runtime.command(GameActorContext.club(88L),pause);when(access.require(eq(88L),eq(40L),eq(70L),anyString())).thenReturn(null);
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.club(88L),pause));assertThrows(GameSessionException.class,()->runtime.receipt(new GameActorContext(88L,null),70L,pause.getRequestId()));
    }

    @Test void receiptTeamChangeIsRejectedAndLegacyActorOnlyHistoryStaysPrivate() throws Exception {
        sharedWorld(2);GameSessionCommand command=envelope(7,"RECOVER",payload().put("recoveryKey","break"));
        GameCommandReceiptView applied=runtime.command(GameActorContext.player(7L),command);
        try(Connection connection=ds.getConnection()) {exec(connection,"UPDATE play_team_member SET team_id=99 WHERE member_id=7 AND team_id=90");}
        assertEquals("GAME_FORBIDDEN_SCOPE",assertThrows(GameSessionException.class,
                ()->runtime.receipt(GameActorContext.player(7L),70L,command.getRequestId())).getErrorCode());
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),command));
        ObjectNode legacy=JSON.valueToTree(applied.getResult());legacy.remove("receiptOwnerTeamId");legacy.remove("teamId");
        try(Connection connection=ds.getConnection();PreparedStatement update=connection.prepareStatement("UPDATE game_command_receipt SET result_json=? WHERE id=?")) {
            update.setString(1,legacy.toString());update.setLong(2,applied.getReceiptId());assertEquals(1,update.executeUpdate());
        }
        assertEquals("APPLIED",runtime.receipt(GameActorContext.player(7L),70L,command.getRequestId()).getOutcome());
        assertTrue(runtime.command(GameActorContext.player(7L),command).isReplayed());
        GameCommandReceiptView other=runtime.receipt(GameActorContext.player(9L),70L,command.getRequestId());
        assertEquals("PENDING",other.getOutcome());assertTrue(other.getResult()==null || other.getResult().isEmpty());
    }

    @Test void staffGuideCachesTrustedEffectsOnceAndSettlesThroughSameTaskEngine() throws Exception {
        sharedWorld(2);ITopicRouteRuntimeService route=(ITopicRouteRuntimeService)ReflectionTestUtils.getField(target,"topicRouteRuntimeService");ArrayNode effects=JSON.createArrayNode();effects.addObject().put("op","INC").put("var","sys.hp").put("value",1);when(route.trustedGuidedRouteEffects(any(),anyLong(),any())).thenReturn(effects);when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(effects);
        player(7,"QUEST_START",payload().put("questKey","q1"));ObjectNode guide=payload().put("teamId",90).put("questKey","q1").put("reason","依据冻结题组核验");guide.putObject("answers");staff("QUEST_GUIDE",guide);staff("QUEST_GUIDE",guide);
        assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"after-guided",true,Collections.singletonList(new String[]{"qa","correct"}),1);
        player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(9,state(50,90).path("variables").path("sys.hp").asInt());
    }

    private void basicFrozen(int validationMethod, boolean advanced) throws Exception {
        GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());frozen.putObject("nodeAnswers").putObject("11").put("validationMethod",validationMethod);
        frozen.with("nodeConfigs").with("11").putObject("qa").put("enabled",advanced);frozen.putObject("nodeJourney").putObject("11").putArray("effects").addObject().put("op","INC").put("var","sys.hp").put("value",1);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,frozen.toString());st.executeUpdate();}
    }

    private void trustedEffectsFrozen(long nodeId, boolean advanced) throws Exception {
        ObjectNode frozen=(ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());
        ObjectNode block=PersonalGameplayRuntimeServiceTest.trustedEffectFixture(JSON);
        if(!advanced)block.remove("qa");
        frozen.with("nodeConfigs").set(String.valueOf(nodeId),block);
        frozen.with("nodeAnswers").putObject(String.valueOf(nodeId)).put("validationMethod",1).put("questionAnswer","correct");
        NodeJourneySnapshot.copyMechanicSegments(block,frozen.with("nodeJourney").putObject(String.valueOf(nodeId)));
        try(Connection connection=ds.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {statement.setString(1,frozen.toString());statement.executeUpdate();}
        ReflectionTestUtils.setField(target,"topicRouteRuntimeService",mock(TopicRouteRuntimeServiceImpl.class,CALLS_REAL_METHODS));
    }

    @Test void nativeTrustedRootAndOptionWaitForSuccessAndConsumeOnlyOnce() throws Exception {
        sharedWorld(2);trustedEffectsFrozen(11,true);player(7,"QUEST_START",payload().put("questKey","q1"));
        String instance=instance(7,11);List<String[]> choices=Collections.singletonList(new String[]{"qa","yes"});
        runtime.recordSharedRouteResult(7L,80L,70L,11L,instance,"not-ready",false,choices,1);
        assertEquals(0,state(50,90).path("themeRuntime").path("gameplay").path("pendingTaskEffects").path("q1").path("effects").size());
        for(String action:Arrays.asList("passed","passed","new-receipt"))runtime.recordSharedRouteResult(7L,80L,70L,11L,instance,action,true,choices,1);
        assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());
        assertEquals(2,state(50,90).path("themeRuntime").path("gameplay").path("pendingTaskEffects").path("q1").path("effects").size());
        ObjectNode guide=payload().put("teamId",90).put("questKey","q1").put("reason","已核验题组");guide.putObject("answers").putObject("qa").put("optionId","yes");staff("QUEST_GUIDE",guide);
        GameSessionCommand resolve=envelope(7,"QUEST_RESOLVE",payload().put("questKey","q1"));runtime.command(GameActorContext.player(7L),resolve);
        assertEquals(11,state(50,90).path("variables").path("sys.hp").asInt());assertTrue(runtime.command(GameActorContext.player(7L),resolve).isReplayed());
        assertThrows(GameSessionException.class,()->runtime.recordSharedRouteResult(7L,80L,70L,11L,instance,"after-resolve",true,choices,1));
        assertEquals(11,state(50,90).path("variables").path("sys.hp").asInt());
    }

    @Test void guidedBasicRootAndAdvancedRootPlusOptionShareNativeConsumption() throws Exception {
        for(boolean advanced:new boolean[]{false,true}) {
            seed();sharedWorld(2);trustedEffectsFrozen(11,advanced);player(7,"QUEST_START",payload().put("questKey","q1"));
            ObjectNode guide=payload().put("teamId",90).put("questKey","q1").put("reason","根据冻结答案核验");
            if(advanced)guide.putObject("answers").putObject("qa").put("optionId","yes");else guide.putObject("answers").put("answer","correct");
            GameSessionCommand command=envelope(88,"QUEST_GUIDE",guide);runtime.command(GameActorContext.club(88L),command);assertTrue(runtime.command(GameActorContext.club(88L),command).isReplayed());staff("QUEST_GUIDE",guide);
            runtime.recordSharedRouteResult(7L,80L,70L,11L,instance(7,11),"after-guide",true,advanced?Collections.singletonList(new String[]{"qa","yes"}):Collections.emptyList(),1);
            assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());
            assertEquals(advanced?2:1,state(50,90).path("themeRuntime").path("gameplay").path("pendingTaskEffects").path("q1").path("effects").size());
            player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(advanced?11:10,state(50,90).path("variables").path("sys.hp").asInt());
        }
    }

    @Test void nativeRootOnlyResultAddsTwoEvenWithoutChoiceEffects() throws Exception {
        sharedWorld(2);trustedEffectsFrozen(11,true);player(7,"QUEST_START",payload().put("questKey","q1"));
        for(String action:Arrays.asList("root-pass","root-pass","root-retry"))runtime.recordSharedRouteResult(7L,80L,70L,11L,instance(7,11),action,true,Collections.emptyList(),1);
        assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());
        player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());
    }

    @Test void basicScanBindsFreshArrivalToTaskAndCannotReuseCancelledOrOldEpochProof() throws Exception {
        sharedWorld(2);basicFrozen(4,false);String before=instance(7,11);Map<String,Object> arrived=runtime.recordSharedBasicArrival(7L,80L,70L,11L,"real-scan-1","SCAN",before,1);assertEquals(false,arrived.get("basicVerified"));
        player(7,"QUEST_START",payload().put("questKey","q1"));assertTrue(state(50,90).path("themeRuntime").path("verifiedNodes").path("11").asLong()>0);assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());
        player(7,"QUEST_CANCEL",payload().put("questKey","q1"));player(7,"QUEST_START",payload().put("questKey","q1"));assertThrows(GameSessionException.class,()->player(7,"QUEST_RESOLVE",payload().put("questKey","q1")));
        assertThrows(GameSessionException.class,()->runtime.recordSharedBasicArrival(7L,80L,70L,11L,"real-scan-1","SCAN",instance(7,11),1));assertThrows(GameSessionException.class,()->runtime.recordSharedBasicArrival(7L,80L,70L,11L,"real-scan-old","SCAN",instance(7,11),0));
        runtime.recordSharedBasicArrival(7L,80L,70L,11L,"real-scan-2","SCAN",instance(7,11),1);player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(9,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
    }

    @Test void basicGpsRequiresFrozenModeAndAdvancedGroupsStillRequireFullServerVerdict() throws Exception {
        sharedWorld(2);basicFrozen(5,false);player(7,"QUEST_START",payload().put("questKey","q1"));runtime.recordSharedBasicArrival(7L,80L,70L,11L,"scan-is-not-gps","SCAN",instance(7,11),1);assertThrows(GameSessionException.class,()->player(7,"QUEST_RESOLVE",payload().put("questKey","q1")));
        runtime.recordSharedBasicArrival(7L,80L,70L,11L,"actual-gps","GPS",instance(7,11),1);player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(9,state(50,90).path("variables").path("sys.hp").asInt());
        seed();sharedWorld(2);basicFrozen(4,true);player(7,"QUEST_START",payload().put("questKey","q1"));runtime.recordSharedBasicArrival(7L,80L,70L,11L,"advanced-scan","SCAN",instance(7,11),1);assertThrows(GameSessionException.class,()->player(7,"QUEST_RESOLVE",payload().put("questKey","q1")));
        runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"full-advanced",true,Collections.emptyList(),1);player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(9,state(50,90).path("variables").path("sys.hp").asInt());
    }

    @Test void existingAnswerValidationBindsControlledBasicProofWithoutClientPassed() throws Exception {
        sharedWorld(2);basicFrozen(1,false);player(7,"QUEST_START",payload().put("questKey","q1"));runtime.recordSharedBasicArrival(7L,80L,70L,11L,"answer-scan","SCAN",instance(7,11),1);assertThrows(GameSessionException.class,()->player(7,"QUEST_RESOLVE",payload().put("questKey","q1")));
        runtime.recordSharedBasicArrival(7L,80L,70L,11L,"actual-answer","ANSWER",instance(7,11),1);player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(9,state(50,90).path("variables").path("sys.hp").asInt());
    }

    @Test void repeatableFacilityUsesExistingBasicScanOncePerRealOperation() throws Exception {
        sharedWorld(2);basicFrozen(4,false);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0)).put("operationNodeId",11).put("limit",2);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,frozen.toString());st.executeUpdate();}
        ObjectNode start=payload().put("operationKind","FACILITY").put("operationKey","shelter");player(7,"OPERATION_START",start);runtime.recordSharedBasicArrival(7L,80L,70L,11L,"facility-scan-1","SCAN",instance(7,11),1);player(7,"FACILITY_USE",payload().put("facilityKey","shelter"));assertFalse(state(50,90).path("themeRuntime").has("activeOperation"));
        player(7,"OPERATION_START",start);assertThrows(GameSessionException.class,()->player(7,"FACILITY_USE",payload().put("facilityKey","shelter")));runtime.recordSharedBasicArrival(7L,80L,70L,11L,"facility-scan-2","SCAN",instance(7,11),1);player(7,"FACILITY_USE",payload().put("facilityKey","shelter"));assertEquals(0,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(2,worldState().path("facilities").path("shelter_once").path("count").asInt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"OWNER","WORLD","MISSING","BROKE"})
    void r5PlanRealTeamsRequireTheirOwnPassAndCommitAllCostsOnce(String mode) throws Exception {
        sharedWorld(2);ObjectNode frozen=JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson()).deepCopy();ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");((ObjectNode)game.path("plans").path(0)).put("terminal",false).put("endingKey","tpl_vote_12_1");((ArrayNode)game.path("plans")).remove(1);game.putObject("templateTools").putArray("nodes").addObject().put("nodeId",12).put("votePolicy","MAJORITY_ACCEPTANCE").putArray("planKeys").add("tpl_vote_12_1");
        ObjectNode block=frozen.with("nodeConfigs").putObject("12");block.putObject("qa").put("enabled",true).put("mode","TYPE").put("title","title").put("answerText","correct");block.putObject("interactionRules").put("scope","WORLD".equals(mode)?"WORLD":"OWNER").put("targetScope","OWNER").put("limit",1);ObjectNode outcomes=block.putObject("outcomeRules").put("version",1);ObjectNode after=outcomes.putObject("pass");outcomes.putObject("fail");after.putArray("costs").addObject().put("statKey","sys.hp").put("amount",3);after.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",1);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")){st.setString(1,frozen.toString());st.executeUpdate();}
        player(7,"PLAN_PROPOSE",payload().put("proposalKey","mid").put("endingKey","tpl_vote_12_1"));for(long member:new long[]{7,9}){ObjectNode intent=payload().put("proposalKey","mid").put("version",1);intent.set("amounts",amounts("tool",1));player(member,"PLAN_INTENT",intent);}
        for(long member:new long[]{7,9})player(member,"QUEST_START",payload().put("questKey","q2"));player(7,"VOTE_OPEN",payload());int round=worldState().path("round").asInt();for(long member:new long[]{7,9})player(member,"VOTE_CAST",payload().put("round",round).put("proposalKey","mid"));for(long member:new long[]{7,9})player(member,"PLAN_CONFIRM",payload().put("round",round).put("proposalKey","mid"));
        for(long member:new long[]{7,9}){player(member,"OPERATION_START",payload().put("operationKind","PLAN").put("operationKey","tpl_vote_12_1"));if(member==9 && "MISSING".equals(mode))continue;runtime.recordSharedGameplayOutcome(member,80L,70L,12L,instance(member,12),"advanced:"+member+":1",payload().put("nodeId",12).put("outcome","PASS"),Collections.emptyList(),1);}
        if("BROKE".equals(mode)){ObjectNode owner=state(50,99).deepCopy();owner.with("variables").put("sys.hp",0);writeOwner("TEAM",99,owner);}
        int before=mapper.selectSessionByActivity(70L).getRevision();GameSessionCommand command=envelope(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","mid"));
        if(Arrays.asList("MISSING","BROKE").contains(mode)){assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),command));assertEquals(before,mapper.selectSessionByActivity(70L).getRevision());assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertFalse(worldState().path("executedPlans").has("tpl_vote_12_1"));}
        else {runtime.command(GameActorContext.player(7L),command);for(long team:new long[]{90,99}){assertEquals(6,state(50,team).path("variables").path("sys.hp").asInt());assertEquals(1,state(50,team).path("themeRuntime").path("inventory").path("tool").asInt());assertTrue(state(50,team).path("themeRuntime").path("gameplay").path("quests").has("q2"));}assertEquals(1,("WORLD".equals(mode)?worldState():state(50,90).path("themeRuntime").path("cooperation")).path("facilities").path("plan:12").path("count").asInt());assertTrue(runtime.command(GameActorContext.player(7L),command).isReplayed());assertEquals(6,state(50,90).path("variables").path("sys.hp").asInt());}
    }

    @Test void nonTerminalPlanExecutesOnceThenReleasesCurrentOperationAndContinuesRealTasks() throws Exception {
        sharedWorld(2);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");((ObjectNode)game.path("plans").path(0)).put("terminal",false).put("endingKey","tpl_vote_12_1");((ObjectNode)game.path("plans").path(1)).put("terminal",false).put("endingKey","tpl_vote_12_2");game.putObject("templateTools").putArray("nodes").addObject().put("nodeId",12).put("votePolicy","MAJORITY_ACCEPTANCE").putArray("planKeys").add("tpl_vote_12_1").add("tpl_vote_12_2");
        ObjectNode chapter=((ObjectNode)frozen.path("themeConfig")).putArray("chapters").addObject().put("index",0);chapter.putObject("binding").putArray("nodeIds").add(12);
        ITopicRouteRuntimeService route=(ITopicRouteRuntimeService)ReflectionTestUtils.getField(target,"topicRouteRuntimeService");ArrayNode effects=JSON.createArrayNode();effects.addObject().put("op","INC").put("var","sys.hp").put("value",1);when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(effects);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,frozen.toString());st.executeUpdate();}
        player(7,"PLAN_PROPOSE",payload().put("proposalKey","mid").put("endingKey","tpl_vote_12_1"));for(long member:new long[]{7,9}){ObjectNode intent=payload().put("proposalKey","mid").put("version",1);intent.set("amounts",amounts("tool",1));player(member,"PLAN_INTENT",intent);}
        for(long member:new long[]{7,9})player(member,"QUEST_START",payload().put("questKey","q2"));
        runtime.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"native-is-not-vote",true,Collections.emptyList(),1);int beforeVoteRevision=mapper.selectSessionByActivity(70L).getRevision();assertEquals("GAME_TEMPLATE_VOTE_REQUIRED",assertThrows(GameSessionException.class,()->player(7,"QUEST_RESOLVE",payload().put("questKey","q2"))).getErrorCode());assertEquals(beforeVoteRevision,mapper.selectSessionByActivity(70L).getRevision());assertFalse(state(50,90).path("themeRuntime").path("chapters").path("0").has("ended"));assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        player(7,"VOTE_OPEN",payload());int round=worldState().path("round").asInt();for(long member:new long[]{7,9})player(member,"VOTE_CAST",payload().put("round",round).put("proposalKey","mid"));for(long member:new long[]{7,9})player(member,"PLAN_CONFIRM",payload().put("round",round).put("proposalKey","mid"));
        player(7,"OPERATION_START",payload().put("operationKind","PLAN").put("operationKey","tpl_vote_12_1"));runtime.recordSharedRouteResult(8L,80L,70L,12L,instance(8,12),"mid-operation",true,Collections.singletonList(new String[]{"qa","correct"}),1);GameSessionCommand execute=envelope(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","mid"));runtime.command(GameActorContext.player(7L),execute);
        assertFalse(worldState().has("result"));assertEquals("DRAFT",worldState().path("stage").asText());assertTrue(worldState().path("executedPlans").has("tpl_vote_12_1"));assertFalse(state(50,90).path("themeRuntime").has("activeOperation"));assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        for(long team:new long[]{90,99}){JsonNode owner=state(50,team);assertTrue(owner.path("themeRuntime").path("gameplay").path("quests").has("q2"));assertFalse(owner.path("themeRuntime").path("gameplay").has("activeQuestReceiptId"));assertTrue(owner.path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());}
        assertEquals(9,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(8,state(50,99).path("variables").path("sys.hp").asInt());
        assertTrue(runtime.command(GameActorContext.player(7L),execute).isReplayed());assertThrows(GameSessionException.class,()->player(7,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","mid")));assertThrows(GameSessionException.class,()->player(7,"PLAN_PROPOSE",payload().put("proposalKey","again").put("endingKey","tpl_vote_12_1")));assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        assertThrows(GameSessionException.class,()->player(7,"PLAN_PROPOSE",payload().put("proposalKey","other-option").put("endingKey","tpl_vote_12_2")));assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(JSON.createArrayNode());
        player(7,"QUEST_START",payload().put("questKey","q1"));runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"continue",true,Collections.emptyList(),1);player(7,"QUEST_RESOLVE",payload().put("questKey","q1"));assertEquals(0,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertFalse(worldState().has("result"));
    }

    @Test void cancelledJointPlanEffectsCannotBeConsumedByAnotherTeamsCollectiveExecution() throws Exception {
        sharedWorld(2);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("plans").path(0)).put("terminal",false);
        frozen.with("nodeJourney").putObject("12").putArray("effects").addObject().put("op","INC").put("var","sys.hp").put("value",2);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")){st.setString(1,frozen.toString());st.executeUpdate();}
        for(long member:new long[]{7,9}){player(member,"QUEST_START",payload().put("questKey","q1"));runtime.recordSharedRouteResult(member,80L,70L,11L,instance(member,11),"finished-other-node-"+member,true,Collections.emptyList(),1);player(member,"QUEST_RESOLVE",payload().put("questKey","q1"));player(member,"QUEST_START",payload().put("questKey","q2"));}
        long priorNodeProof=state(50,90).path("themeRuntime").path("verifiedNodes").path("11").asLong();long questReceipt=state(50,90).path("themeRuntime").path("gameplay").path("activeQuestReceiptId").asLong();
        player(7,"PLAN_PROPOSE",payload().put("proposalKey","shared_cancel").put("endingKey","bridge"));for(long member:new long[]{7,9}){ObjectNode intent=payload().put("proposalKey","shared_cancel").put("version",1);intent.set("amounts",amounts("tool",1));player(member,"PLAN_INTENT",intent);}
        player(7,"VOTE_OPEN",payload());int round=worldState().path("round").asInt();for(long member:new long[]{7,9}){player(member,"VOTE_CAST",payload().put("round",round).put("proposalKey","shared_cancel"));}for(long member:new long[]{7,9})player(member,"PLAN_CONFIRM",payload().put("round",round).put("proposalKey","shared_cancel"));
        ITopicRouteRuntimeService route=(ITopicRouteRuntimeService)ReflectionTestUtils.getField(target,"topicRouteRuntimeService");ArrayNode effects=JSON.createArrayNode();effects.addObject().put("op","INC").put("var","sys.hp").put("value",1);when(route.trustedChoiceEffects(any(),anyLong(),anyString(),anyString())).thenReturn(effects);
        ObjectNode start=payload().put("operationKind","PLAN").put("operationKey","bridge");player(7,"OPERATION_START",start);String cancelledInstance=instance(7,12);runtime.recordSharedRouteResult(7L,80L,70L,12L,cancelledInstance,"cancelled-native-effect",true,Collections.singletonList(new String[]{"qa","correct"}),1);assertEquals(2,state(50,90).path("themeRuntime").path("gameplay").path("pendingTaskEffects").path("q2").path("effects").size());
        player(7,"OPERATION_CANCEL",payload());JsonNode cancelled=state(50,90);assertFalse(cancelled.path("themeRuntime").path("gameplay").path("pendingTaskEffects").has("q2"));assertFalse(cancelled.path("themeRuntime").path("verifiedNodes").has("12"));assertFalse(cancelled.path("themeRuntime").path("operationFacts").has("PLAN:bridge"));assertEquals(priorNodeProof,cancelled.path("themeRuntime").path("verifiedNodes").path("11").asLong());assertTrue(cancelled.path("completedNodeIds").toString().contains("11"));assertEquals(questReceipt,cancelled.path("themeRuntime").path("gameplay").path("activeQuestReceiptId").asLong());assertEquals(8,cancelled.path("variables").path("sys.hp").asInt());
        player(7,"OPERATION_START",start);assertNotEquals(cancelledInstance,instance(7,12));assertThrows(GameSessionException.class,()->runtime.recordSharedRouteResult(7L,80L,70L,12L,cancelledInstance,"stale-cancelled-proof",true,Collections.emptyList(),1));assertFalse(state(50,90).path("themeRuntime").path("operationFacts").has("PLAN:bridge"));player(7,"OPERATION_CANCEL",payload());
        player(9,"OPERATION_START",start);runtime.recordSharedRouteResult(9L,80L,70L,12L,instance(9,12),"other-team-native",true,Collections.singletonList(new String[]{"qa","correct"}),1);player(9,"PLAN_EXECUTE",payload().put("round",round).put("proposalKey","shared_cancel"));
        assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(11,state(50,99).path("variables").path("sys.hp").asInt());for(long team:new long[]{90,99}){assertTrue(state(50,team).path("themeRuntime").path("gameplay").path("quests").has("q2"));assertEquals(0,state(50,team).path("themeRuntime").path("inventory").path("tool").asInt());}assertEquals(priorNodeProof,state(50,90).path("themeRuntime").path("verifiedNodes").path("11").asLong());
    }

    @Test void futureTemplateVoteCannotProposeOrStartEvenWhenPhysicalStationIsActive() throws Exception {
        sharedWorld(2);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");
        ((ObjectNode)game.path("acts").path(0)).putArray("questKeys").add("q1");ObjectNode later=game.withArray("acts").addObject().put("actKey","later");later.putArray("questKeys").add("q2");later.putObject("completionRule").put("type","ALL");((ObjectNode)game.path("quests").path(1)).put("actKey","later");
        ObjectNode plan=(ObjectNode)game.path("plans").path(0);plan.put("terminal",false).put("endingKey","tpl_vote_12_1");((ArrayNode)game.path("plans")).remove(1);game.putObject("templateTools").putArray("nodes").addObject().put("nodeId",12).put("votePolicy","MAJORITY_ACCEPTANCE").putArray("planKeys").add("tpl_vote_12_1");
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")){st.setString(1,frozen.toString());st.executeUpdate();}
        assertEquals("ACTIVE",mapper.selectStation(50L,12L).getStatus());int revision=mapper.selectSessionByActivity(70L).getRevision();
        assertEquals("GAME_ROUTE_LOCKED",assertThrows(GameSessionException.class,()->player(7,"PLAN_PROPOSE",payload().put("proposalKey","future").put("endingKey","tpl_vote_12_1"))).getErrorCode());
        GameStoryState world=mapper.selectStoryState(50L,"WORLD",50L);ObjectNode worldValue=(ObjectNode)JSON.readTree(world.getVariablesJson());ObjectNode cooperation=worldValue.with("themeRuntime").with("cooperation");cooperation.put("stage","SELECTED").put("selected","future").put("round",1);cooperation.with("proposals").putObject("future").put("endingKey","tpl_vote_12_1").put("status","OPEN");writeOwner("WORLD",50,worldValue);
        assertEquals("GAME_ROUTE_LOCKED",assertThrows(GameSessionException.class,()->player(7,"OPERATION_START",payload().put("operationKind","PLAN").put("operationKey","tpl_vote_12_1"))).getErrorCode());assertEquals(revision,mapper.selectSessionByActivity(70L).getRevision());assertFalse(state(50,90).path("themeRuntime").has("activeOperation"));assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        JsonNode tools=JSON.valueToTree(runtime.view(GameActorContext.player(7L),70L).getPlayer().get("interactionTools"));for(JsonNode tool:tools)assertFalse("PLAN".equals(tool.path("kind").asText()));
    }

    @Test void participantTemplateFacilityCompletesOnlyCurrentVerifiedFreeTasksWithOneEffectSource() throws Exception {
        sharedWorld(2);basicFrozen(4,false);GameSession session=mapper.selectSessionByActivity(70L);ObjectNode frozen=(ObjectNode)JSON.readTree(session.getModuleConfigSnapshotJson());ObjectNode q=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("quests").path(0);q.remove("resourceCosts");q.put("actionCost",0);ObjectNode f=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0);f.put("targetScope","PARTICIPANTS").put("operationNodeId",11).put("arrivalLocationKey","yard");
        ObjectNode game=(ObjectNode)frozen.path("themeConfig").path("gameplayRuntime");game.remove("plans");((ArrayNode)game.path("quests")).remove(1);((ObjectNode)game.path("acts").path(0)).putArray("questKeys").add("q1");
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {st.setString(1,frozen.toString());st.executeUpdate();}
        for(long member:new long[]{7,9})player(member,"QUEST_START",payload().put("questKey","q1"));player(7,"OPERATION_START",payload().put("operationKind","FACILITY").put("operationKey","shelter"));runtime.recordSharedBasicArrival(7L,80L,70L,11L,"payer-facility","SCAN",instance(7,11),1);
        ObjectNode use=payload().put("facilityKey","shelter");use.putArray("participants").add(90).add(99);assertThrows(GameSessionException.class,()->player(7,"FACILITY_USE",use));assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        runtime.recordSharedBasicArrival(9L,80L,70L,11L,"participant-facility","SCAN",instance(9,11),1);player(7,"FACILITY_USE",use);
        for(long team:new long[]{90,99}){JsonNode owner=state(50,team);assertTrue(owner.path("themeRuntime").path("gameplay").path("quests").has("q1"));assertFalse(owner.path("themeRuntime").path("gameplay").has("activeQuest"));assertFalse(owner.path("themeRuntime").path("gameplay").has("activeQuestReceiptId"));assertEquals(11,owner.path("variables").path("sys.hp").asInt());assertEquals(0,owner.path("variables").path("sys.actions").asInt());}
        assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(2,state(50,99).path("themeRuntime").path("inventory").path("tool").asInt());assertFalse(state(50,90).path("themeRuntime").has("activeOperation"));assertEquals(1,worldState().path("facilities").path("shelter_once").path("count").asInt());
        assertEquals("COMPLETED",runtime.view(GameActorContext.player(7L),70L).getPlayer().get("status"));assertTrue(runtime.view(GameActorContext.player(7L),70L).getAvailableActions().isEmpty());assertThrows(GameSessionException.class,()->player(7,"ITEM_DISCARD",payload().put("contentId","tool")));assertEquals("RUNNING",mapper.selectSessionByActivity(70L).getStatus());
    }

    @Test void multiTargetRecoveryChargesAndCountsOnceOnlyForActualGain() throws Exception {
        for(String scope:Arrays.asList("WORLD","PARTICIPANTS")) {
            seed();sharedWorld(2);
            ObjectNode frozen=(ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());
            ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime").path("facilities").path(0)).put("targetScope",scope);
            try(Connection connection=ds.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {statement.setString(1,frozen.toString());statement.executeUpdate();}
            for(long team:new long[]{90,99}) {ObjectNode owner=(ObjectNode)state(50,team);owner.with("variables").put("sys.hp",12);writeOwner("TEAM",team,owner);}
            ObjectNode use=payload().put("facilityKey","shelter");if("PARTICIPANTS".equals(scope))use.putArray("participants").add(90).add(99);
            player(7,"FACILITY_USE",use);
            assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertFalse(worldState().path("facilities").has("shelter_once"));
            ObjectNode owner=(ObjectNode)state(50,99);owner.with("variables").put("sys.hp",10);writeOwner("TEAM",99,owner);
            GameSessionCommand paid=envelope(7,"FACILITY_USE",use);runtime.command(GameActorContext.player(7L),paid);
            assertEquals(12,state(50,99).path("variables").path("sys.hp").asInt());assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
            assertEquals(2,state(50,99).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(1,worldState().path("facilities").path("shelter_once").path("count").asInt());
            JsonNode before=state(50,90),worldBefore=worldState();runtime.command(GameActorContext.player(7L),paid);
            assertEquals(before,state(50,90));assertEquals(worldBefore,worldState());
        }
    }

    @Test void completedTeamIsReadOnlyAndUntouchedByAnotherTeamsWorldFacility() throws Exception {
        sharedWorld(2);
        ObjectNode frozen=(ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());
        ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime")).remove("plans");
        try(Connection connection=ds.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {statement.setString(1,frozen.toString());statement.executeUpdate();}
        ObjectNode completed=(ObjectNode)state(50,99);completed.put("status","COMPLETED");writeOwner("TEAM",99,completed);
        String before=mapper.selectStoryState(50L,"TEAM",99L).getVariablesJson();
        player(7,"FACILITY_USE",payload().put("facilityKey","shelter"));
        assertEquals(before,mapper.selectStoryState(50L,"TEAM",99L).getVariablesJson());
        assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());
        assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
        assertTrue(runtime.view(GameActorContext.player(9L),70L).getAvailableActions().isEmpty());
        assertEquals("GAME_ROUTE_FINISHED",assertThrows(GameSessionException.class,()->player(9,"FACILITY_USE",payload().put("facilityKey","shelter"))).getErrorCode());
        assertEquals("GAME_ROUTE_FINISHED",assertThrows(GameSessionException.class,()->player(9,"ITEM_DISCARD",payload().put("contentId","tool"))).getErrorCode());
    }

    @Test void nodeTradeUsesServerArrivalsForBothTeamsAndReportsTheOpeningNode() throws Exception {
        sharedWorld(2);basicFrozen(4,false);
        ObjectNode frozen=(ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());
        ((ObjectNode)frozen.path("themeConfig").path("gameplayRuntime")).putObject("templateTools").putArray("nodes").addObject().put("nodeId",11).put("allowTrade",true);
        try(Connection connection=ds.getConnection();PreparedStatement statement=connection.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")) {statement.setString(1,frozen.toString());statement.executeUpdate();}
        ObjectNode open=payload().put("tradeKey","same_node").put("toTeamId",99);open.set("give",amounts("tool",1));open.set("take",amounts("clue",1));
        runtime.recordSharedBasicArrival(7L,80L,70L,11L,"trade-arrival-a","SCAN",instance(7,11),1);
        JsonNode disabled=JSON.valueToTree(runtime.view(GameActorContext.player(7L),70L).getPlayer().get("world"));
        assertTrue(disabled.path("tradeTargets").path(0).path("disabled").asBoolean());
        assertTrue(disabled.path("tradeTargets").path(0).path("disabledMessage").asText().contains("对方"));assertEquals("NOT_ARRIVED",disabled.path("tradeTargets").path(0).path("disabledReason").asText());
        String before=mapper.selectStoryState(50L,"WORLD",50L).getVariablesJson();
        assertThrows(GameSessionException.class,()->player(7,"TRADE_OPEN",open));
        assertEquals(before,mapper.selectStoryState(50L,"WORLD",50L).getVariablesJson());
        runtime.recordSharedBasicArrival(9L,80L,70L,11L,"trade-arrival-b","SCAN",instance(9,11),1);
        JsonNode enabled=JSON.valueToTree(runtime.view(GameActorContext.player(7L),70L).getPlayer().get("world"));
        assertFalse(enabled.path("tradeTargets").path(0).path("disabled").asBoolean());assertEquals(11,enabled.path("tradeTargets").path(0).path("nodeId").asLong());
        player(7,"TRADE_OPEN",open);player(7,"TRADE_CONFIRM",payload().put("tradeKey","same_node").put("version",1));player(9,"TRADE_CONFIRM",payload().put("tradeKey","same_node").put("version",1));
        assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());
    }

    @Test void tradeFailureAfterBothOwnersAndWorldWritesRollsBackAtomically() throws Exception {
        sharedWorld(2);ObjectNode open=payload().put("tradeKey","rollback").put("toTeamId",99);open.set("give",amounts("tool",1));open.set("take",amounts("clue",1));player(7,"TRADE_OPEN",open);player(7,"TRADE_CONFIRM",payload().put("tradeKey","rollback").put("version",1));
        String beforeA=state(50,90).toString(),beforeB=state(50,99).toString(),beforeWorld=worldState().toString();int beforeReceipts=receipts(),revision=mapper.selectSessionByActivity(70L).getRevision();
        GameSessionRuntimeMapper failing=(GameSessionRuntimeMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GameSessionRuntimeMapper.class},(proxy,method,args)->{if(method.getName().equals("completeReceipt"))throw new IllegalStateException("injected transaction interruption");try{return method.invoke(mapper,args);}catch(java.lang.reflect.InvocationTargetException cause){throw cause.getCause();}});
        ReflectionTestUtils.setField(target,"runtimeMapper",failing);try {assertThrows(IllegalStateException.class,()->player(9,"TRADE_CONFIRM",payload().put("tradeKey","rollback").put("version",1)));}finally {ReflectionTestUtils.setField(target,"runtimeMapper",mapper);}
        assertEquals(beforeA,state(50,90).toString());assertEquals(beforeB,state(50,99).toString());assertEquals(beforeWorld,worldState().toString());assertEquals(beforeReceipts,receipts());assertEquals(revision,mapper.selectSessionByActivity(70L).getRevision());
    }

    @Test void concurrentSameRequestHasOneMutationAndOriginalReceipt() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        List<Future<GameCommandReceiptView>> work=new ArrayList<>();
        for(int i=0;i<2;i++)work.add(pool.submit(()->{start.await();return runtime.command(GameActorContext.player(7L),command("same",0,"ITEM_USE","cold"));}));
        start.countDown();GameCommandReceiptView a=work.get(0).get(15,TimeUnit.SECONDS),b=work.get(1).get(15,TimeUnit.SECONDS);pool.shutdownNow();
        assertEquals(a.getReceiptId(),b.getReceiptId());assertEquals(1,receipts());assertEquals(1,mapper.selectSessionByActivity(70L).getRevision());assertEquals(6,state(50,90).path("variables").path("sys.hp").asInt());
        Map<String,Object> captain=runtime.view(GameActorContext.player(7L),70L).getPlayer(),member=runtime.view(GameActorContext.player(8L),70L).getPlayer();
        assertEquals(captain.get("inventory"),member.get("inventory"));assertEquals(captain.get("attributes"),member.get("attributes"));
        assertEquals(true,captain.get("isCaptain"));assertEquals(false,member.get("isCaptain"));
        assertTrue(JSON.valueToTree(member.get("recoveries")).isEmpty());
    }
    @Test void differentRequestsRaceOnLastStockAndOnlyOneCommits() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> work=new ArrayList<>();
        for(int i=0;i<2;i++){final int n=i;work.add(pool.submit(()->{start.await();try{runtime.command(GameActorContext.player(7L),command("race"+n,0,"ITEM_USE","cold"));return true;}catch(GameSessionException conflict){return false;}}));}
        start.countDown();int winners=0;for(Future<Boolean> f:work)if(f.get(15,TimeUnit.SECONDS))winners++;pool.shutdownNow();assertEquals(1,winners);assertEquals(1,receipts());assertEquals(6,state(50,90).path("variables").path("sys.hp").asInt());
    }
    @Test void lastChapterEndRewardPreviewBlocksCompletionUntilExplicitDiscardCommits() throws Exception {
        ObjectNode snapshot=(ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson()),theme=(ObjectNode)snapshot.path("themeConfig");theme.put("capacity",3);((ObjectNode)theme.path("gameplayRuntime").path("acts").get(0)).putObject("completionRule").put("type","ANY_N").put("quantity",1);
        ObjectNode chapter=theme.putArray("chapters").addObject().put("index",0);chapter.putObject("binding").putArray("nodeIds").add(11).add(12);chapter.putArray("grants").addObject().put("timing","END").put("itemId","food").put("quantity",1);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")){st.setString(1,snapshot.toString());st.executeUpdate();}
        runtime.command(GameActorContext.player(7L),command("start-last",0,"QUEST_START","q1"));runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"proof-last",true,Collections.emptyList());
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("questKey","q1");payload.put("choiceKey","supply");JsonNode preview=JSON.valueToTree(runtime.sharedPreview(GameActorContext.player(7L),70L,"QUEST_RESOLVE",payload));assertEquals(2,preview.path("settlement").path("grants").path("food").asInt());assertTrue(preview.path("settlement").path("overflow").asBoolean());
        JsonNode before=state(50,90);assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),command("full-last",mapper.selectSessionByActivity(70L).getRevision(),"QUEST_RESOLVE","q1")));assertEquals(before,state(50,90));
        payload.put("discardRewards",amounts("food",2));preview=JSON.valueToTree(runtime.sharedPreview(GameActorContext.player(7L),70L,"QUEST_RESOLVE",payload));payload.put("previewVersion",preview.path("settlement").path("previewVersion").asText());GameSessionCommand done=envelope(7,"QUEST_RESOLVE",JSON.valueToTree(payload));runtime.command(GameActorContext.player(7L),done);assertTrue(runtime.command(GameActorContext.player(7L),done).isReplayed());
        assertEquals("COMPLETED",state(50,90).path("status").asText());assertTrue(state(50,90).path("themeRuntime").path("pending").isEmpty());assertTrue(state(50,90).path("themeRuntime").path("chapters").path("0").path("ended").asBoolean());
    }

    @Test void completedPendingClaimCannotDiscardEscrowLockedStock() throws Exception {
        sharedWorld(2);ObjectNode saved=(ObjectNode)state(50,90);saved.put("status","COMPLETED");saved.with("themeRuntime").with("sources").put("quest:legacy:0",true);saved.with("themeRuntime").with("pending").putObject("quest:legacy:0").put("itemId","clue").put("quantity",1);writeOwner("TEAM",90,saved);
        TopicRouteEngine.State world=JSON.readValue(mapper.selectStoryState(50L,"WORLD",50L).getVariablesJson(),TopicRouteEngine.State.class);world.getThemeRuntime().with("cooperation").putObject("result").put("endingKey","bridge");world.getThemeRuntime().with("cooperation").with("locks").with("90").putArray("amounts").addObject().put("itemId","tool").put("quantity",2);writeOwner("WORLD",50,world);
        GameSessionCommand claim=command("claim-locked",0,"ITEM_RECEIVE","quest:legacy:0");claim.getPayload().put("discardOwned",amounts("tool",1));assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),claim));assertEquals(saved,state(50,90));assertEquals(0,receipts());
        claim.setRequestId("claim-unlocked");claim.getPayload().put("discardOwned",amounts("clue",1));runtime.command(GameActorContext.player(7L),claim);assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("tool").asInt());assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("clue").asInt());assertTrue(state(50,90).path("themeRuntime").path("pending").isEmpty());
    }

    @Test void completedPendingDiscardAndClaimRollbackWithReceiptThenRetryExactlyOnce() throws Exception {
        ObjectNode snapshot=(ObjectNode)JSON.readTree(mapper.selectSessionByActivity(70L).getModuleConfigSnapshotJson());((ObjectNode)snapshot.path("themeConfig")).put("capacity",3);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE game_session SET module_config_snapshot_json=? WHERE id=50")){st.setString(1,snapshot.toString());st.executeUpdate();}
        ObjectNode saved=(ObjectNode)state(50,90);saved.put("status","COMPLETED");saved.with("themeRuntime").putObject("result").put("hp",10);saved.with("themeRuntime").with("sources").put("chapter:0:END:food",true);saved.with("themeRuntime").with("pending").putObject("chapter:0:END:food").put("itemId","food").put("quantity",1);writeOwner("TEAM",90,saved);
        GameSessionCommand claim=command("claim-legacy",0,"ITEM_RECEIVE","chapter:0:END:food");claim.getPayload().put("discardOwned",amounts("cold",1));
        GameSessionRuntimeMapper failing=(GameSessionRuntimeMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GameSessionRuntimeMapper.class},(proxy,method,args)->{if(method.getName().equals("completeReceipt"))throw new IllegalStateException("test receipt write failure");try{return method.invoke(mapper,args);}catch(java.lang.reflect.InvocationTargetException cause){throw cause.getCause();}});
        ReflectionTestUtils.setField(target,"runtimeMapper",failing);try {assertThrows(IllegalStateException.class,()->runtime.command(GameActorContext.player(7L),claim));}finally {ReflectionTestUtils.setField(target,"runtimeMapper",mapper);}
        assertEquals(saved,state(50,90));assertEquals(0,receipts());assertEquals(0,mapper.selectSessionByActivity(70L).getRevision());
        runtime.command(GameActorContext.player(7L),claim);assertTrue(runtime.command(GameActorContext.player(7L),claim).isReplayed());assertEquals(1,receipts());assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("food").asInt());assertFalse(state(50,90).path("themeRuntime").path("inventory").has("cold"));assertTrue(state(50,90).path("themeRuntime").path("pending").isEmpty());assertEquals(saved.path("themeRuntime").path("result"),state(50,90).path("themeRuntime").path("result"));
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),command("no-use",1,"ITEM_USE","food")));
    }

    @Test void failureAfterStateAndRevisionWritesRollsBackLinksStockAndReceipt() throws Exception {
        GameSessionRuntimeMapper failing=(GameSessionRuntimeMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GameSessionRuntimeMapper.class},(proxy,method,args)->{if(method.getName().equals("completeReceipt"))throw new IllegalStateException("test receipt write failure");try{return method.invoke(mapper,args);}catch(java.lang.reflect.InvocationTargetException cause){throw cause.getCause();}});
        ReflectionTestUtils.setField(target,"runtimeMapper",failing);
        try {assertThrows(IllegalStateException.class,()->runtime.command(GameActorContext.player(7L),command("rollback",0,"ITEM_USE","cold")));}
        finally {ReflectionTestUtils.setField(target,"runtimeMapper",mapper);}
        assertEquals(0,receipts());assertEquals(0,mapper.selectSessionByActivity(70L).getRevision());assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(8,state(50,90).path("variables").path("counter.temperature").asInt());assertEquals(1,state(50,90).path("themeRuntime").path("inventory").path("cold").asInt());assertFalse(state(50,90).path("themeRuntime").has("statLinks"));
    }
    @Test void successfulRetryIgnoresOldRevisionAndRejectsChangedContent() throws Exception {
        runtime.command(GameActorContext.player(7L),command("use",0,"ITEM_USE","cold"));assertTrue(runtime.command(GameActorContext.player(7L),command("use",0,"ITEM_USE","cold")).isReplayed());
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(7L),command("use",0,"ITEM_USE","food")));assertEquals(1,receipts());
    }
    @Test void teamsAndSessionsAreIsolatedAndOutsiderCannotWrite() throws Exception {
        runtime.command(GameActorContext.player(7L),command("use",0,"ITEM_USE","cold"));assertEquals(10,state(50,99).path("variables").path("sys.hp").asInt());assertEquals(10,state(51,100).path("variables").path("sys.hp").asInt());
        assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(8L),command("member",1,"ITEM_USE","food")));assertThrows(GameSessionException.class,()->runtime.command(GameActorContext.player(10L),command("outsider",1,"ITEM_USE","food")));assertEquals(1,receipts());
    }
    @Test void verifiedQuestSlotSurvivesRequestIdChangeAndFreeWrongAnswer() throws Exception {
        runtime.command(GameActorContext.player(7L),command("start",0,"QUEST_START","q1"));runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"wrong",false,Collections.emptyList());
        assertEquals(0,state(50,90).path("variables").path("sys.actions").asInt());assertEquals(10,state(50,90).path("variables").path("sys.hp").asInt());
        runtime.recordSharedRouteResult(8L,80L,70L,11L,instance(8,11),"correct",true,Collections.emptyList());int version=mapper.selectSessionByActivity(70L).getRevision();
        GameCommandReceiptView applied=runtime.command(GameActorContext.player(7L),command("resolve",version,"QUEST_RESOLVE","q1"));version=mapper.selectSessionByActivity(70L).getRevision();
        GameCommandReceiptView retry=runtime.command(GameActorContext.player(7L),command("new-id",version,"QUEST_RESOLVE","q1"));assertEquals(applied.getResult().get("receiptId"),retry.getResult().get("receiptId"));assertEquals(version,mapper.selectSessionByActivity(70L).getRevision());
        assertEquals(1,state(50,90).path("variables").path("sys.actions").asInt());assertEquals(8,state(50,90).path("variables").path("sys.hp").asInt());assertEquals(2,state(50,90).path("themeRuntime").path("inventory").path("food").asInt());
    }
}
