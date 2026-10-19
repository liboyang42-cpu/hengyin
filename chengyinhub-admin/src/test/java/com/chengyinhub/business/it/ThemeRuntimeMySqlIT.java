package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.impl.GameplayTemplateSlotRuntimeTest;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.io.InputStream;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真 MySQL：迁移、归属、同事务回滚、最后一件并发、重玩存档隔离。 */
class ThemeRuntimeMySqlIT {
    private static PooledDataSource ds;
    private static SqlSessionTemplate sql;
    private static ITopicRouteRuntimeService runtime;
<<<<<<< public
    private static TopicRouteRuntimeServiceImpl target;
=======
    private static com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced;
    private static CmsActivity activityRow;
    private static com.chengyinhub.web.service.PreferencePlayService preference;
    private static com.chengyinhub.business.service.IPlayerTopicTagService preferenceTags;
>>>>>>> private-github/featu
    private static com.chengyinhub.business.service.IImService im;
    private static com.chengyinhub.business.service.IShopDayProofService arrival;
    private static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1",MybatisItDatabase.host());
        ds=new PooledDataSource("com.mysql.cj.jdbc.Driver",MybatisItDatabase.url("theme_runtime"),MybatisItDatabase.user(),MybatisItDatabase.password());
        try(Connection c=ds.getConnection()) {
            ItSchema.apply(c,"it-schema/cms-topic-it-schema.sql");ItSchema.apply(c,"it-schema/journey-full-flow-it-schema.sql");
<<<<<<< public
            exec(c,"CREATE TABLE IF NOT EXISTS ums_member(id BIGINT PRIMARY KEY,nickname VARCHAR(255))");
            ItSchema.applyMigrations(c,"sql/migration_20260823_topic_route_graph.sql","sql/migration_20260930_registration_first_successful_scan.sql","sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
            exec(c,"ALTER TABLE cms_topic DROP COLUMN theme_runtime_config_json, DROP COLUMN published_theme_runtime_config_json");
            exec(c,"ALTER TABLE player_node_progress DROP COLUMN play_instance_id, ADD UNIQUE KEY uk_progress(member_id,topic_id,node_id,activity_id), ADD UNIQUE KEY uk_member_activity_node(member_id,activity_id,node_id)");
            String advancedMigration=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_20260822_advanced_game_runtime.sql")),java.nio.charset.StandardCharsets.UTF_8);int advancedCreate=advancedMigration.indexOf("CREATE TABLE IF NOT EXISTS game_runtime_session");exec(c,ItSchema.splitStatements(advancedMigration.substring(advancedCreate)).get(0));
            ItSchema.applyMigrations(c,"sql/migration_m9_play_attempt_log.sql","sql/migration_20260820_new_city_preference_chain.sql","sql/migration_20260917_play_note.sql");
            exec(c,"CREATE TABLE play_team_member (team_id BIGINT, member_id BIGINT, status INT) ENGINE=InnoDB");
            ItSchema.applyMigrations(c,"sql/migration_theme_runtime_20261004.sql","sql/migration_play_predict_20260906.sql");
=======
            ItSchema.apply(c,"it-schema/merchant-chapter-offer-it-schema.sql");
            ItSchema.applyMigrations(c,"sql/migration_20260823_topic_route_graph.sql","sql/migration_20260930_registration_first_successful_scan.sql","sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
            exec(c,"ALTER TABLE cms_topic DROP COLUMN theme_runtime_config_json, DROP COLUMN published_theme_runtime_config_json");
            exec(c,"ALTER TABLE player_node_progress DROP COLUMN play_instance_id, ADD UNIQUE KEY uk_progress(member_id,topic_id,node_id,activity_id), ADD UNIQUE KEY uk_member_activity_node(member_id,activity_id,node_id)");
            exec(c,"CREATE TABLE game_runtime_session(id bigint PRIMARY KEY AUTO_INCREMENT,activity_id bigint,topic_id bigint,node_id bigint,owner_type int,owner_id bigint,UNIQUE KEY uk_runtime_owner(activity_id,topic_id,node_id,owner_type,owner_id)) ENGINE=InnoDB");
            ItSchema.applyMigrations(c,"sql/migration_theme_runtime_20261004.sql","sql/migration_theme_team_replay_20261009.sql");
            exec(c,"ALTER TABLE game_runtime_session ADD template_id bigint, ADD config_snapshot_json json, ADD state_json json, ADD status int, ADD score int, ADD completed_units int, ADD version int, ADD started_at datetime, ADD deadline_at datetime, ADD completed_at datetime, ADD create_time datetime, ADD update_time datetime");
            exec(c,"ALTER TABLE cms_member_template ADD w18_provenance_kind varchar(32)");
            exec(c,"CREATE TABLE w18_cms_template_provenance(template_id bigint PRIMARY KEY) ENGINE=InnoDB");
            exec(c,"CREATE TABLE play_attempt_log(id bigint PRIMARY KEY AUTO_INCREMENT,member_id bigint,topic_id bigint,node_id bigint,activity_id bigint,attempt_type int,result int,wrong_answer varchar(120),duration_s int,result_json json,idempotency_key varchar(128),created_at datetime,UNIQUE KEY uk_play_attempt_idempotency(idempotency_key)) ENGINE=InnoDB");
            exec(c,"CREATE TABLE ums_member(id bigint PRIMARY KEY,nickname varchar(64),avatar varchar(500)) ENGINE=InnoDB");
            exec(c,"CREATE TABLE cms_activity(id bigint PRIMARY KEY,topic_id bigint,del_flag int) ENGINE=InnoDB");
            exec(c,"CREATE TABLE reward_drop_log(member_id bigint,activity_id bigint,topic_id bigint,node_id bigint,scene_key varchar(40)) ENGINE=InnoDB");
            exec(c,"CREATE TABLE business_audit_event(domain_type varchar(40),domain_id bigint,actor_type varchar(40),actor_member_id bigint,action varchar(40),target_type varchar(40),relation_id bigint) ENGINE=InnoDB");
            exec(c,"CREATE TABLE synthetic_first_award_receipt(id bigint PRIMARY KEY AUTO_INCREMENT,member_id bigint,activity_id bigint,node_id bigint) ENGINE=InnoDB");
            exec(c,"CREATE TABLE play_team(id bigint PRIMARY KEY,owner_type int,owner_id bigint,leader_member_id bigint,invite_code varchar(80),title varchar(100),join_mode int,max_members int,joined_count int,status int,expire_time datetime,create_time datetime,update_time datetime) ENGINE=InnoDB");
            exec(c,"CREATE TABLE play_team_member(id bigint PRIMARY KEY AUTO_INCREMENT,team_id bigint,member_id bigint,role int,status int,apply_message varchar(500),apply_expire_time datetime,create_time datetime,update_time datetime,UNIQUE KEY uk_team_member(team_id,member_id)) ENGINE=InnoDB");
>>>>>>> private-github/featu
            String medalMigration=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_20260902_node_medal_claim.sql")),java.nio.charset.StandardCharsets.UTF_8);
            int start=medalMigration.indexOf("CREATE TABLE IF NOT EXISTS `node_medal_claim`");
            exec(c,"DROP TABLE IF EXISTS node_medal_claim");
            exec(c,medalMigration.substring(start,medalMigration.indexOf(';',start)+1));
        }
        Configuration cfg=new Configuration();cfg.setEnvironment(new Environment("theme",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
<<<<<<< public
        for(String mapper:Arrays.asList("CmsTopicMapper","CmsTopicNodeMapper","CmsTopicChapterMapper","CmsMemberTemplateMapper","PlayerNodeProgressMapper","CmsRegistrationMapper","GameRuntimeSessionMapper","PlayAttemptLogMapper","TopicRouteSessionMapper","ThemeBroadcastMapper","NodeMedalClaimMapper","PlayNoteMapper","PlayPredictMapper")) {
=======
        for(String mapper:Arrays.asList("CmsTopicMapper","CmsTopicNodeMapper","CmsTopicChapterMapper","CmsMemberTemplateMapper","PlayerNodeProgressMapper","CmsRegistrationMapper","TopicRouteSessionMapper","ThemeBroadcastMapper","NodeMedalClaimMapper","ThemeTeamRunMapper","PlayTeamMapper","UmsMemberMapper","LegacyCmsTemplateReadMapper","GameRuntimeSessionMapper","PlayAttemptLogMapper")) {
>>>>>>> private-github/featu
            String resource="mapper/business/"+mapper+".xml";
            try(InputStream in=ThemeRuntimeMySqlIT.class.getClassLoader().getResourceAsStream(resource)){new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);sql=new SqlSessionTemplate(factory.getObject());
<<<<<<< public
        arrival=org.mockito.Mockito.mock(com.chengyinhub.business.service.IShopDayProofService.class);
        org.mockito.Mockito.when(arrival.hasArrived(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        target=new TopicRouteRuntimeServiceImpl(sql.getMapper(TopicRouteSessionMapper.class),sql.getMapper(CmsTopicMapper.class),sql.getMapper(CmsTopicNodeMapper.class),sql.getMapper(CmsMemberTemplateMapper.class),sql.getMapper(PlayerNodeProgressMapper.class),null,sql.getMapper(CmsRegistrationMapper.class),null,arrival,()->123L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
=======
        CmsActivityMapper activity=org.mockito.Mockito.mock(CmsActivityMapper.class);
        activityRow=new CmsActivity();activityRow.setId(5L);activityRow.setTopicId(10L);activityRow.setStatus(1L);activityRow.setDelFlag(0);
        org.mockito.Mockito.when(activity.selectCmsActivityById(5L)).thenReturn(activityRow);
        TopicRouteRuntimeServiceImpl target=new TopicRouteRuntimeServiceImpl(sql.getMapper(TopicRouteSessionMapper.class),sql.getMapper(CmsTopicMapper.class),sql.getMapper(CmsTopicNodeMapper.class),sql.getMapper(CmsMemberTemplateMapper.class),sql.getMapper(PlayerNodeProgressMapper.class),activity,sql.getMapper(CmsRegistrationMapper.class),null,null,()->123L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
        com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy policy = new com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy();
        ReflectionTestUtils.setField(policy,"enabled",true);
        ReflectionTestUtils.setField(target,"themeRuntimePolicy",policy);
        com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader legacy=new com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader(sql.getMapper(LegacyCmsTemplateReadMapper.class),sql.getMapper(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(target,"legacyTemplateBodyReader",legacy);
        ReflectionTestUtils.setField(target,"themeTeamRunMapper",sql.getMapper(ThemeTeamRunMapper.class));
        ReflectionTestUtils.setField(target,"themeTeamCoordinator",new com.chengyinhub.business.service.support.ThemeTeamRunCoordinator(sql.getMapper(PlayTeamMapper.class),sql.getMapper(ThemeTeamRunMapper.class)));
>>>>>>> private-github/featu
        ReflectionTestUtils.setField(target,"cmsTopicChapterMapper",sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(target,"themeNodeMedals",sql.getMapper(NodeMedalClaimMapper.class));
        ReflectionTestUtils.setField(target,"originalAwardMemberMapper",sql.getMapper(UmsMemberMapper.class));
        im=org.mockito.Mockito.mock(com.chengyinhub.business.service.IImService.class);
        com.chengyinhub.business.service.support.ThemeBroadcastService broadcasts = new com.chengyinhub.business.service.support.ThemeBroadcastService(sql.getMapper(ThemeBroadcastMapper.class),im);
        ReflectionTestUtils.setField(broadcasts,"themeRuntimePolicy",policy);
        ReflectionTestUtils.setField(target,"themeBroadcastService",broadcasts);
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(ITopicRouteRuntimeService.class);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));runtime=(ITopicRouteRuntimeService)proxy.getProxy();
        ReflectionTestUtils.setField(broadcasts,"routeRuntime",runtime);ReflectionTestUtils.setField(broadcasts,"routeMapper",sql.getMapper(TopicRouteSessionMapper.class));
        com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl game=new com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(game,"nodeMapper",sql.getMapper(CmsTopicNodeMapper.class));ReflectionTestUtils.setField(game,"templateMapper",sql.getMapper(CmsMemberTemplateMapper.class));ReflectionTestUtils.setField(game,"sessionMapper",sql.getMapper(GameRuntimeSessionMapper.class));ReflectionTestUtils.setField(game,"topicRouteRuntimeService",runtime);ReflectionTestUtils.setField(game,"registrationMapper",sql.getMapper(CmsRegistrationMapper.class));ReflectionTestUtils.setField(game,"teamMapper",sql.getMapper(PlayTeamMapper.class));ReflectionTestUtils.setField(game,"activityMapper",activity);
        ProxyFactory gameProxy=new ProxyFactory(game);gameProxy.setInterfaces(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);gameProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));advanced=(com.chengyinhub.business.service.IAdvancedGameRuntimeService)gameProxy.getProxy();
        com.chengyinhub.web.service.PreferencePlayService pref=new com.chengyinhub.web.service.PreferencePlayService();preferenceTags=org.mockito.Mockito.mock(com.chengyinhub.business.service.IPlayerTopicTagService.class);ReflectionTestUtils.setField(pref,"routeRuntimeService",runtime);ReflectionTestUtils.setField(pref,"attemptMapper",sql.getMapper(PlayAttemptLogMapper.class));ReflectionTestUtils.setField(pref,"tagService",preferenceTags);
        ProxyFactory prefProxy=new ProxyFactory(pref);prefProxy.setProxyTargetClass(true);prefProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));preference=(com.chengyinhub.web.service.PreferencePlayService)prefProxy.getProxy();
    }
    @BeforeEach void seed() throws Exception {
<<<<<<< public
        org.mockito.Mockito.reset(im);
        ReflectionTestUtils.setField(target,"clock",Clock.systemUTC());
        try(Connection c=ds.getConnection()) {
            for(String table:Arrays.asList("play_predict_bet","play_predict_result","play_attempt_log","game_runtime_session","node_medal_claim","theme_broadcast_delivery","topic_route_action","topic_route_session","player_node_progress","cms_registration","cms_topic_node","cms_member_template","cms_topic_chapter","cms_topic"))exec(c,"DELETE FROM "+table);
=======
        org.mockito.Mockito.reset(im,preferenceTags);activityRow.setTopicId(10L);
        try(Connection c=ds.getConnection()) {
            for(String table:Arrays.asList("play_attempt_log","w18_cms_template_provenance","synthetic_first_award_receipt","reward_drop_log","business_audit_event","ums_member","game_runtime_session","theme_team_run_member","theme_team_run","play_team_member","play_team","node_medal_claim","theme_broadcast_delivery","topic_route_action","topic_route_session","player_node_progress","cms_registration","cms_activity","cms_topic_node","cms_member_template","cms_topic_chapter","cms_topic"))exec(c,"DELETE FROM "+table);
            exec(c,"INSERT INTO ums_member(id) VALUES(7),(8)");
            exec(c,"INSERT INTO cms_activity(id,topic_id,del_flag) VALUES(5,10,0)");
>>>>>>> private-github/featu
            String config="{\"schemaVersion\":1,\"capacity\":3,\"replayEnabled\":true,\"items\":[{\"id\":\"food\",\"kind\":\"ITEM\",\"name\":\"口粮\",\"image\":\"https://example.test/food.png\",\"stat\":\"HP\",\"delta\":2}],\"endings\":[{\"id\":\"end\",\"title\":\"结束\",\"fallback\":true,\"success\":false}]}";
            try(PreparedStatement st=c.prepareStatement("INSERT INTO cms_topic(id,member_id,name,del_flag,route_mode,published_config_version,journey_rules_json,theme_runtime_config_json,published_theme_runtime_config_json) VALUES(10,7,'测试',0,'LINEAR',1,?,?,?)")) {
                st.setString(1,"{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":6},\"recovery\":{\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0,\"stationEndIfHurt\":0}}");st.setString(2,config);st.setString(3,config);st.executeUpdate();
            }
            exec(c,"INSERT INTO cms_topic_node(id,topic_id,name,status,sort_id) VALUES(11,10,'第一站',1,1),(12,10,'第二站',1,2)");
            exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,ticket_id,purchase_kind,create_time) VALUES(7,1,10,10,2,1,0,NOW())");
        }
    }
    private Map<String,Object> personalTask() throws Exception {return personalTask(false);}
    @Test void oneTemplateResourceSlotsPublishFreezeAndChargeDifferentThemes() throws Exception {
        String rules = GameplayTemplateSlotRuntimeTest.journeyRules();
        try (Connection c = ds.getConnection()) {
            exec(c, "DELETE FROM cms_topic_node");
            exec(c, "INSERT INTO cms_topic_node(id,topic_id,name,status,sort_id,template_id,merchant_member_id,node_audit_status) VALUES(11,10,'第一站',1,1,501,30,1),(21,20,'第二站',1,1,501,30,1)");
            exec(c, "INSERT INTO cms_topic(id,member_id,name,del_flag,route_mode,published_config_version) VALUES(20,7,'主题B',0,'LINEAR',1)");
            exec(c, "INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,ticket_id,purchase_kind,create_time) VALUES(7,1,20,20,2,1,0,NOW())");
            try (PreparedStatement st = c.prepareStatement("INSERT INTO cms_member_template(id,validation_method,advanced_config_json) VALUES(501,4,?)")) {
                st.setString(1, GameplayTemplateSlotRuntimeTest.templateConfig().toString()); st.executeUpdate();
            }
            for (long topicId : new long[]{10L, 20L}) {
                String config = GameplayTemplateSlotRuntimeTest.definition(topicId + 1, topicId == 10 ? "sys.hp" : "counter.x", topicId == 10 ? "a" : "b").toString();
                try (PreparedStatement st = c.prepareStatement("UPDATE cms_topic SET journey_rules_json=?,theme_runtime_config_json=? WHERE id=?")) {
                    st.setString(1, rules); st.setString(2, config); st.setLong(3, topicId); st.executeUpdate();
                }
                CmsTopic topic = sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(topicId);
                String compiled = runtime.compileTemplateRules(topic);
                try (PreparedStatement st = c.prepareStatement("UPDATE cms_topic SET theme_runtime_config_json=? WHERE id=?")) {
                    st.setString(1, compiled); st.setLong(2, topicId); st.executeUpdate();
                }
                topic = sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(topicId);
                String snapshot = runtime.freezePublication(topic, null);
                assertFalse(snapshot.contains("templateBindings")); assertFalse(snapshot.contains("resourceSlots"));
                assertEquals(1, sql.getMapper(CmsTopicMapper.class).publishRouteSnapshot(topicId, snapshot, new java.util.Date()));
            }
            com.fasterxml.jackson.databind.node.ObjectNode changed = GameplayTemplateSlotRuntimeTest.templateConfig();
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("interactionRules").path("statCosts").path(0)).put("amount", 9);
            try (PreparedStatement st = c.prepareStatement("UPDATE cms_member_template SET advanced_config_json=? WHERE id=501")) {
                st.setString(1, changed.toString()); st.executeUpdate();
            }
        }
        for (long topicId : new long[]{10L, 20L}) {
            long nodeId = topicId + 1;
            CmsTopic topic = sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(topicId);
            assertEquals(JSON.readTree(topic.getPublishedRouteGraphJson()).path("nodeConfigs"), JSON.readTree(runtime.freezePublishedJourney(topic)).path("nodeConfigs"));
            Map<String, Object> view = runtime.themeView(7L, topicId, 0L, null); long sessionId = ((Number) view.get("sessionId")).longValue();
            Map<String, Object> arrived = runtime.recordPlayerArrival(7L, topicId, 0L, nodeId, "scan", null);
            Map<String, Object> started = runtime.gameplayAction(7L, sessionId, ((Number) arrived.get("version")).intValue(), "start", "QUEST_START", "node_" + nodeId, null);
            Map<String, Object> done = runtime.gameplayAction(7L, sessionId, ((Number) started.get("version")).intValue(), "resolve", "QUEST_RESOLVE", "node_" + nodeId, null);
            TopicRouteSession stored = sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(sessionId, 7L);
            com.fasterxml.jackson.databind.JsonNode state = JSON.readTree(stored.getStateJson());
            assertEquals(topicId == 10 ? 5 : 6, state.path("variables").path("sys.hp").asInt());
            assertEquals(topicId == 10 ? 5 : 4, state.path("variables").path("counter.x").asInt());
            assertEquals(1, state.path("themeRuntime").path("inventory").path(topicId == 10 ? "a" : "b").asInt());
            assertEquals(0, state.path("themeRuntime").path("inventory").path(topicId == 10 ? "b" : "a").asInt());
            assertEquals(JSON.readTree(JSON.writeValueAsString(done)), JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L, sessionId, 0, "resolve", "QUEST_RESOLVE", "node_" + nodeId, null))));
            assertEquals(stored.getStateJson(), sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(sessionId, 7L).getStateJson());
        }
    }
    private Map<String,Object> personalTask(boolean withOperation) throws Exception {return personalTask(withOperation,null);}
    private Map<String,Object> personalTask(boolean withOperation,Integer basicMethod) throws Exception {
        return personalTask(withOperation,basicMethod,null);
    }
    private Map<String,Object> personalTask(boolean withOperation,Integer basicMethod,String r5Policy) throws Exception {return personalTask(withOperation,basicMethod,r5Policy,false);}
    private Map<String,Object> personalTask(boolean withOperation,Integer basicMethod,String r5Policy,boolean r5Check) throws Exception {return personalTask(withOperation,basicMethod,r5Policy,r5Check,false);}
    private Map<String,Object> personalTask(boolean withOperation,Integer basicMethod,String r5Policy,boolean r5Check,boolean timer) throws Exception {return personalTask(withOperation,basicMethod,r5Policy,r5Check,timer,false);}
    private Map<String,Object> personalTask(boolean withOperation,Integer basicMethod,String r5Policy,boolean r5Check,boolean timer,boolean timeoutCompleted) throws Exception {return personalTask(withOperation,basicMethod,r5Policy,r5Check,timer,timeoutCompleted,null);}
    private Map<String,Object> personalTask(boolean withOperation,Integer basicMethod,String r5Policy,boolean r5Check,boolean timer,boolean timeoutCompleted,String mainKit) throws Exception {
        String config="{\"schemaVersion\":1,\"capacity\":8,\"actionLimit\":8,\"items\":[{\"id\":\"food\",\"kind\":\"ITEM\",\"name\":\"口粮\",\"image\":\"https://static.chengyinhub.com/food.png\",\"stat\":\"HP\",\"delta\":2}],\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\",\"initialGrants\":[{\"itemId\":\"food\",\"quantity\":1}],\"survival\":{\"zeroHpPolicy\":\"BLOCK_PAID\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\"},\"acts\":[{\"actKey\":\"a\",\"questKeys\":[\"north\",\"east\"],\"completionRule\":{\"type\":\"ANY_N\",\"quantity\":1}}],\"quests\":[{\"questKey\":\"north\",\"actKey\":\"a\",\"nodeId\":11,\"locationKey\":\"gate\",\"settlementSlot\":\"north\",\"hpCost\":1,\"actionCost\":1,\"grants\":[{\"itemId\":\"food\",\"quantity\":1}]},{\"questKey\":\"east\",\"actKey\":\"a\",\"nodeId\":12,\"locationKey\":\"gate\",\"settlementSlot\":\"east\"}]}}";
        if(withOperation) {com.fasterxml.jackson.databind.node.ObjectNode body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(config);com.fasterxml.jackson.databind.node.ObjectNode facility=body.with("gameplayRuntime").putArray("facilities").addObject().put("facilityKey","warm").put("actKey","a").put("scope","OWNER").put("targetScope","OWNER").put("settlementSlot","warm").put("limit",2).put("operationNodeId",12).put("hpCost",1).put("hpDelta",2);facility.putArray("resourceCosts").addObject().put("itemId","food").put("quantity",1);facility.putArray("grants").addObject().put("itemId","food").put("quantity",1);config=body.toString();}
        if(r5Policy!=null) {
            com.fasterxml.jackson.databind.node.ObjectNode body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(config);
            for(com.fasterxml.jackson.databind.JsonNode q:body.path("gameplayRuntime").path("quests"))((com.fasterxml.jackson.databind.node.ObjectNode)q).remove(Arrays.asList("hpCost","actionCost"));
            body.with("gameplayRuntime").with("survival").put("zeroHpPolicy",r5Policy);
            if("STEPS".equals(r5Policy))body.with("gameplayRuntime").with("survival").putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");
            if("WAIT".equals(r5Policy))body.with("gameplayRuntime").with("survival").putObject("zeroWait").put("version",1).put("minutes",1).put("restoreTo",4).put("limit",1).put("thenPolicy","THEME_FAIL");
            if("CHALLENGE".equals(r5Policy))body.with("gameplayRuntime").with("survival").putObject("zeroChallenge").put("version",2).put("challengeNodeId",13).put("gameplayKey","coinFlip").put("requiredCompletions",2).put("withinSeconds",60).put("restoreTo",4).put("limit",1).put("thenPolicy","THEME_FAIL");
            config=body.toString();
        }
        if("qaFeedback".equals(mainKit)){com.fasterxml.jackson.databind.node.ObjectNode body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(config);((com.fasterxml.jackson.databind.node.ObjectNode)body.path("gameplayRuntime").path("acts").path(0).path("completionRule")).put("quantity",2);config=body.toString();}
        config(config);
        try(Connection c=ds.getConnection()) {
            exec(c,"UPDATE cms_topic_node SET template_id=101,merchant_member_id=30,node_audit_status=1 WHERE topic_id=10");
            try(PreparedStatement st=c.prepareStatement("INSERT INTO cms_member_template(id,advanced_config_json) VALUES(101,?)")) {st.setString(1,"{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"答案\",\"answerText\":\"正确\"}}");st.executeUpdate();}
            if(r5Policy!=null) {
                com.fasterxml.jackson.databind.node.ObjectNode template=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"Answer\",\"answerText\":\"correct\"}}");
                com.fasterxml.jackson.databind.node.ObjectNode outcomes=template.putObject("outcomeRules").put("version",1);
                outcomes.putObject("pass").put("reply","pass").putArray("costs").addObject().put("statKey","sys.hp").put("amount",1);
                outcomes.putObject("fail").put("reply","fail").putArray("costs").addObject().put("statKey","sys.hp").put("amount","BLOCK_HP_COST".equals(r5Policy)?3:9);
                if(mainKit!=null){template.remove("qa");if(Arrays.asList("profile","note","dailySign","album").contains(mainKit))outcomes.remove("fail");com.fasterxml.jackson.databind.node.ObjectNode main=template.putObject(("qaShot".equals(mainKit)||"qaFeedback".equals(mainKit))?"qa":"objectCard".equals(mainKit)?"photoCheck":mainKit).put("enabled",true);
                    if("qaFeedback".equals(mainKit)){main.put("title","答案").put("mode","TYPE").put("answerText","correct");outcomes.with("pass").put("nextStepId","feedback");outcomes.with("fail").put("nextStepId","feedback");com.fasterxml.jackson.databind.node.ArrayNode steps=template.putObject("branch").put("enabled",true).put("startStepId","feedback").putArray("steps");steps.addObject().put("id","feedback").put("title","反馈").putArray("options").addObject().put("id","continue").put("label","继续").put("nextStepId","done").putArray("effects").addObject().put("var","vital").put("op","INC").put("value",1);steps.addObject().put("id","done").put("terminal",true).put("outcomeCode","DIFFERENT").putArray("options");}

                    if("predict".equals(mainKit)){main.put("question","明天会下雨吗").put("closeAtHour",23).put("revealDays",1).put("revealHour",0);main.putArray("options").addObject().put("key","rain").put("label","下雨").putObject("after").putArray("costs").addObject().put("statKey","vital").put("amount",2);main.withArray("options").addObject().put("key","sun").put("label","晴天");}
                    if("typeIn".equals(mainKit))main.put("title","Typing").put("target","hello").put("seconds",3);
                    if("steps".equals(mainKit))main.put("goal",100);
                    if("qaShot".equals(mainKit))main.put("title","拍一张").put("mode","SHOT");
                    if(Arrays.asList("photoCheck","objectCard").contains(mainKit))main.put("title","拍一张").put("requirement","拍摄目标").put("minConfidence",60).put("maxTries",1).put("fallback","pass").put("mode","objectCard".equals(mainKit)?"CARD":"");
                    if("profile".equals(mainKit))main.putArray("questions").addObject().put("key","name").put("label","姓名").put("kind","text").put("required",true).put("maxLength",12);
                    if("dailySign".equals(mainKit))main.putArray("poems").addArray().add("城市签文");
                    if("note".equals(mainKit))main.put("title","一句留言").put("prompt","留下一句话").put("maxLength",40).putArray("presets").add("留下一句话");}
                if(timer)template.putObject("timer").put("enabled",true).put("durationSeconds",30).put("timeoutResult",timeoutCompleted?"COMPLETED":"FAILED");
                if(r5Check){template.remove("qa");com.fasterxml.jackson.databind.node.ObjectNode check=template.putObject("check").put("enabled",true).put("checkId","c").put("tier","hard").put("failCostTag","tag.lost");check.putArray("failEffects").addObject().put("var","sys.hp").put("op","INC").put("value",1);
                    CmsTopic checkTopic=sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(10L);com.fasterxml.jackson.databind.node.ObjectNode checkRules=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(checkTopic.getJourneyRulesJson());checkRules.putObject("luck").put("enabled",true).put("init",1).put("max",3);try(PreparedStatement st=c.prepareStatement("UPDATE cms_topic SET journey_rules_json=? WHERE id=10")){st.setString(1,checkRules.toString());st.executeUpdate();}}
                if(r5Check || Arrays.asList("qaFeedback","predict","typeIn").contains(mainKit)){
                    template.putObject("interactionRules").putArray("resourceSlots").addObject().put("key","vital").put("kind","VALUE").put("label","生命").put("required",true);for(String result:Arrays.asList("pass","fail"))((com.fasterxml.jackson.databind.node.ObjectNode)outcomes.path(result).path("costs").path(0)).put("statKey","vital");CmsTopic boundTopic=sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(10L);com.fasterxml.jackson.databind.node.ObjectNode bound=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(boundTopic.getThemeRuntimeConfigJson());for(String node:Arrays.asList("11","12")){com.fasterxml.jackson.databind.node.ObjectNode binding=bound.with("templateBindings").putObject(node).put("templateId",101).put("slotDigest",com.chengyinhub.business.service.support.GameplayTemplateRules.slotDigest(template.path("interactionRules").path("resourceSlots")));binding.putObject("values").put("vital","sys.hp");binding.putObject("items");}try(PreparedStatement bindingUpdate=c.prepareStatement("UPDATE cms_topic SET theme_runtime_config_json=? WHERE id=10")){bindingUpdate.setString(1,bound.toString());bindingUpdate.executeUpdate();}
                    if(r5Check)((com.fasterxml.jackson.databind.node.ObjectNode)template.path("check").path("failEffects").path(0)).put("var","vital");
                }
                try(PreparedStatement st=c.prepareStatement("UPDATE cms_member_template SET advanced_config_json=? WHERE id=101")){st.setString(1,template.toString());st.executeUpdate();}
                if("CHALLENGE".equals(r5Policy)) {
                    exec(c,"INSERT INTO cms_topic_node(id,topic_id,name,status,sort_id,template_id,merchant_member_id,node_audit_status) VALUES(13,10,'恢复挑战',1,3,103,30,1)");
                    try(PreparedStatement st=c.prepareStatement("INSERT INTO cms_member_template(id,advanced_config_json) VALUES(103,?)")){st.setString(1,"{\"schemaVersion\":1,\"coinFlip\":{\"enabled\":true,\"heads\":{\"label\":\"正面\",\"action\":\"继续\"},\"tails\":{\"label\":\"反面\",\"action\":\"继续\"}},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}");st.executeUpdate();}
                }
            }
            if(basicMethod!=null){try(PreparedStatement st=c.prepareStatement("UPDATE cms_member_template SET validation_method=?,advanced_config_json=?,question_answer='正确',correct_answer='C' WHERE id=101")){st.setInt(1,basicMethod);st.setString(2,"{\"schemaVersion\":1}");st.executeUpdate();}exec(c,"UPDATE cms_topic_node SET longitude=121.47,latitude=31.23 WHERE topic_id=10");}
            CmsTopic topic=sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(10L);
            try(PreparedStatement st=c.prepareStatement("UPDATE cms_topic SET published_route_graph_json=? WHERE id=10")){st.setString(1,runtime.freezePublication(topic,null));st.executeUpdate();}
        }
        runtime.themeView(7L,10L,0L,null);
        return runtime.recordPlayerArrival(7L,10L,0L,11L,"scan",null,basicMethod!=null && basicMethod==5?"GPS":basicMethod!=null && (basicMethod==1 || basicMethod==3)?"ANSWER":"SCAN");
    }
    private final java.util.concurrent.atomic.AtomicLong advancedNow=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    @Test void r5OutcomeOnlyTemplateAdoptsRealTasksWithoutTheRemovedCostTool() throws Exception {
        config("{\"schemaVersion\":1,\"capacity\":0,\"items\":[]}");
        try(Connection c=ds.getConnection()) {
            exec(c,"UPDATE cms_topic_node SET template_id=101,merchant_member_id=30,node_audit_status=1 WHERE topic_id=10");
            try(PreparedStatement st=c.prepareStatement("INSERT INTO cms_member_template(id,advanced_config_json) VALUES(101,?)")){st.setString(1,"{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"题目\",\"answerText\":\"正确\"},\"outcomeRules\":{\"version\":1,\"pass\":{\"costs\":[{\"statKey\":\"sys.hp\",\"amount\":1}]},\"fail\":{\"costs\":[{\"statKey\":\"sys.hp\",\"amount\":3}]}}}");st.executeUpdate();}
            CmsTopic topic=sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(10L);assertEquals(2,JSON.readTree(runtime.compileTemplateRules(topic)).path("gameplayRuntime").path("quests").size());
            try(PreparedStatement st=c.prepareStatement("UPDATE cms_topic SET published_route_graph_json=? WHERE id=10")){st.setString(1,runtime.freezePublication(topic,null));st.executeUpdate();}
        }
        runtime.themeView(7L,10L,0L,null);Map<String,Object> arrived=runtime.recordPlayerArrival(7L,10L,0L,11L,"scan-r5",null);long id=((Number)arrived.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)arrived.get("version")).intValue(),"start-r5","QUEST_START","node_11",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> started=advanced.start(7L,0L,10L,11L,null);
        advanced.act(7L,((Number)started.get("sessionId")).longValue(),((Number)started.get("version")).intValue(),"wrong-r5","SUBMIT_QA",Collections.singletonMap("input","错误"));
        assertEquals(3,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }
    private long advancedId,personalId;
    private com.chengyinhub.business.service.IAdvancedGameRuntimeService readyTimedAdvanced() throws Exception {return readyTimedAdvanced(false);}
    private com.chengyinhub.business.service.IAdvancedGameRuntimeService readyTimedAdvanced(boolean completed) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,true,completed);personalId=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,personalId,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService service=advancedService();advancedId=((Number)service.start(7L,0L,10L,11L,null).get("sessionId")).longValue();return service;
    }
    private com.chengyinhub.business.service.IAdvancedGameRuntimeService advancedService() {
        com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl advanced=new com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(advanced,"predictMapper",sql.getMapper(PlayPredictMapper.class));ReflectionTestUtils.setField(advanced,"sessionMapper",sql.getMapper(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class));ReflectionTestUtils.setField(advanced,"playNoteMapper",sql.getMapper(com.chengyinhub.business.mapper.PlayNoteMapper.class));ReflectionTestUtils.setField(advanced,"attemptMapper",sql.getMapper(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));ReflectionTestUtils.setField(advanced,"registrationMapper",sql.getMapper(CmsRegistrationMapper.class));ReflectionTestUtils.setField(advanced,"nodeMapper",sql.getMapper(CmsTopicNodeMapper.class));ReflectionTestUtils.setField(advanced,"templateMapper",sql.getMapper(CmsMemberTemplateMapper.class));ReflectionTestUtils.setField(advanced,"topicRouteRuntimeService",runtime);ReflectionTestUtils.setField(advanced,"clock",(com.chengyinhub.business.service.support.AdvancedGameClock)()->new java.util.Date(advancedNow.get()));
        ProxyFactory proxy=new ProxyFactory(advanced);proxy.setInterfaces(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));return (com.chengyinhub.business.service.IAdvancedGameRuntimeService)proxy.getProxy();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","VOID","CLOSED","TIMEOUT","CLOSED_TIMEOUT","CONCURRENT"})
    void r5PredictActualRoundSettlementWaitsForRevealAndUsesOriginalAttempt(String result) throws Exception {
        long betTime=java.time.ZonedDateTime.of(2026,10,1,12,0,0,0,java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();advancedNow.set(betTime);
        Map<String,Object> arrived=personalTask(false,null,"BLOCK_HP_COST",false,result.endsWith("TIMEOUT"),false,"predict");long routeId=((Number)arrived.get("sessionId")).longValue();
        runtime.gameplayAction(7L,routeId,((Number)arrived.get("version")).intValue(),"predict-start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long id=((Number)opened.get("sessionId")).longValue();
        advanced.act(7L,id,0,"bet","SUBMIT_PREDICT",Collections.singletonMap("optionKey","rain"));
        com.chengyinhub.business.mapper.PlayPredictMapper predictions=sql.getMapper(PlayPredictMapper.class);java.sql.Date day=java.sql.Date.valueOf("2026-10-01");PlayPredictBet bet=predictions.selectBet(11L,day,7L);assertNotNull(bet.getId());assertEquals(0,bet.getRewardStatus());
        assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        if("CLOSED_TIMEOUT".equals(result))try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET status=2 WHERE id="+routeId);}
        if(result.endsWith("TIMEOUT")){
            advancedNow.set(betTime+31000);JsonNode settled=JSON.valueToTree(advanced.state(7L,id));
            if("TIMEOUT".equals(result)){
                String routeBefore=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson();
                assertEquals(settled,JSON.valueToTree(advanced.start(7L,0L,10L,11L,null)));
                assertEquals(routeBefore,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson());
            }
        }
        com.chengyinhub.business.service.impl.PlayPredictServiceImpl merchantTarget=new com.chengyinhub.business.service.impl.PlayPredictServiceImpl();ReflectionTestUtils.setField(merchantTarget,"predictMapper",predictions);ReflectionTestUtils.setField(merchantTarget,"nodeMapper",sql.getMapper(CmsTopicNodeMapper.class));ReflectionTestUtils.setField(merchantTarget,"templateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        ProxyFactory merchantProxy=new ProxyFactory(merchantTarget);merchantProxy.setInterfaces(com.chengyinhub.business.service.IPlayPredictService.class);merchantProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));com.chengyinhub.business.service.IPlayPredictService merchant=(com.chengyinhub.business.service.IPlayPredictService)merchantProxy.getProxy();
        if("VOID".equals(result))merchant.voidExpiredRounds(24);else merchant.settle(11L,day,"PASS".equals(result)||"CONCURRENT".equals(result)?"rain":"sun",30L);
        JsonNode early=JSON.valueToTree(advanced.state(7L,id));assertEquals(0,early.path("playKit").path("predict").path("settleStatus").asInt());assertFalse(early.path("playKit").path("predict").has("settledOption"));
        if("CLOSED".equals(result))try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET status=2 WHERE id="+routeId);}
        advancedNow.set(betTime+13*3600000);
        if("CONCURRENT".equals(result)){ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> a=pool.submit(()->advanced.state(7L,id));Future<?> b=pool.submit(()->advanced.state(7L,id));a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
        JsonNode revealed=JSON.valueToTree(advanced.state(7L,id));JsonNode owner=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson());JsonNode advancedState=JSON.readTree(sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(id,7L).getStateJson());
        assertTrue(advancedState.path("predict").path("revealRecorded").asBoolean());assertEquals(bet.getId().longValue(),advancedState.path("predict").path("betId").asLong());
        if(Arrays.asList("PASS","CONCURRENT").contains(result)){assertEquals("PASS",advancedState.path("gameplayDecision").path("outcome").asText());assertEquals(6,owner.path("variables").path("sys.hp").asInt());runtime.gameplayAction(7L,routeId,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getVersion(),"predict-confirm","QUEST_RESOLVE","north",null);assertEquals(4,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()).path("variables").path("sys.hp").asInt());}
        else if("FAIL".equals(result)){assertEquals(4,owner.path("variables").path("sys.hp").asInt());assertEquals("FAIL",advancedState.path("gameplayDecision").path("outcome").asText());}
        else if(result.endsWith("TIMEOUT")){assertEquals("TIMEOUT",advancedState.path("gameplayDecision").path("reason").asText());assertEquals("CLOSED_TIMEOUT".equals(result)?6:3,owner.path("variables").path("sys.hp").asInt());}
        else {assertEquals(6,owner.path("variables").path("sys.hp").asInt());assertFalse(advancedState.has("gameplayDecision"));assertFalse(owner.path("themeRuntime").has("outcomeAttempts"));}
        int version=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(id,7L).getVersion();advanced.state(7L,id);assertEquals(version,sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(id,7L).getVersion());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void r5TypeInActualFrozenTimerOverridesLegacySeconds(boolean timer) throws Exception {
        long started=System.currentTimeMillis();advancedNow.set(started);Map<String,Object> arrived=personalTask(false,null,"BLOCK_HP_COST",false,timer,false,"typeIn");long routeId=((Number)arrived.get("sessionId")).longValue();runtime.gameplayAction(7L,routeId,((Number)arrived.get("version")).intValue(),"typein-start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long id=((Number)opened.get("sessionId")).longValue();Map<String,Object> begun=advanced.act(7L,id,0,"typein-clock","START_CHALLENGE",Collections.singletonMap("game","typeIn"));advancedNow.set(started+20000);Map<String,Object> answer=new LinkedHashMap<>();answer.put("text","hello");answer.put("elapsedMs",20000);assertEquals("COMPLETED",advanced.act(7L,id,((Number)begun.get("version")).intValue(),"typein-submit","SUBMIT_TYPE_IN",answer).get("status"));assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }

    @Test void r5ChallengeTwoActualCoinSessionsRestoreOnceWithoutCompletingTheRegularTask() throws Exception {
        Map<String,Object> initial=personalTask(false,null,"CHALLENGE");long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        runtime.recordGameplayOutcome(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"advanced:1:1",JSON.createObjectNode().put("nodeId",11).put("outcome","FAIL"),Collections.emptyList());
        runtime.recordPlayerArrival(7L,10L,0L,13L,"recovery-arrival",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> first=advanced.start(7L,0L,10L,13L,null);long firstId=((Number)first.get("sessionId")).longValue();
        JsonNode before=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());long deadline=before.path("themeRuntime").path("challenge").path("deadlineAt").asLong();
        Map<String,Object> firstCompleted=advanced.act(7L,firstId,((Number)first.get("version")).intValue(),"first-coin","FLIP_COIN",Collections.emptyMap());
        assertEquals("COMPLETED",firstCompleted.get("status"));assertEquals(Boolean.TRUE,firstCompleted.get("canRetry"));assertEquals(Boolean.FALSE,firstCompleted.get("readyForBase"));
        assertEquals(1,JSON.valueToTree(firstCompleted).path("challenge").path("completions").asInt());
        JsonNode middle=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());assertEquals(0,middle.path("variables").path("sys.hp").asInt());assertEquals(deadline,middle.path("themeRuntime").path("challenge").path("deadlineAt").asLong());
        TopicRouteSession middleRow=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        GameRuntimeSession oldFirst=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(firstId,7L);
        Map<String,Object> resumed=advanced.start(7L,0L,10L,13L,null);
        assertEquals(firstId,((Number)resumed.get("sessionId")).longValue());assertEquals("COMPLETED",resumed.get("status"));assertEquals(Boolean.TRUE,resumed.get("canRetry"));
        TopicRouteSession afterResume=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        GameRuntimeSession afterFirstResume=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(firstId,7L);
        assertEquals(middleRow.getVersion(),afterResume.getVersion());assertEquals(middleRow.getStateJson(),afterResume.getStateJson());
        assertEquals(oldFirst.getVersion(),afterFirstResume.getVersion());assertEquals(oldFirst.getStateJson(),afterFirstResume.getStateJson());
        int retryVersion=((Number)firstCompleted.get("version")).intValue();
        Map<String,Object> second=advanced.act(7L,firstId,retryVersion,"retry-next-coin","RETRY",Collections.emptyMap());long secondId=((Number)second.get("sessionId")).longValue();assertNotEquals(firstId,secondId);
        assertEquals(firstId,((Number)second.get("retrySourceSessionId")).longValue());assertEquals("RUNNING",second.get("status"));
        assertEquals(oldFirst.getConfigSnapshotJson(),sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(secondId,7L).getConfigSnapshotJson());
        GameRuntimeSession secondBeforeReplay=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(secondId,7L);
        Map<String,Object> retryReplay=advanced.act(7L,firstId,retryVersion,"retry-next-coin","RETRY",Collections.emptyMap());
        assertEquals(secondId,((Number)retryReplay.get("sessionId")).longValue());assertEquals(firstId,((Number)retryReplay.get("retrySourceSessionId")).longValue());
        GameRuntimeSession secondAfterReplay=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(secondId,7L);
        assertEquals(secondBeforeReplay.getVersion(),secondAfterReplay.getVersion());assertEquals(secondBeforeReplay.getStateJson(),secondAfterReplay.getStateJson());
        TopicRouteSession afterRetryReplay=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        assertEquals(middleRow.getVersion(),afterRetryReplay.getVersion());assertEquals(middleRow.getStateJson(),afterRetryReplay.getStateJson());
        int secondVersion=((Number)second.get("version")).intValue();
        Map<String,Object> secondCompleted=advanced.act(7L,secondId,secondVersion,"second-coin","FLIP_COIN",Collections.emptyMap());
        assertEquals("COMPLETED",secondCompleted.get("status"));
        JsonNode restored=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());assertEquals(4,restored.path("variables").path("sys.hp").asInt());assertEquals(1,restored.path("themeRuntime").path("zeroHp").path("used").asInt());assertEquals("north",restored.path("themeRuntime").path("gameplay").path("activeQuest").asText());assertFalse(restored.path("themeRuntime").path("gameplay").path("slots").has("north"));assertEquals(0,restored.path("variables").path("sys.passed.13").asInt());
        TopicRouteSession restoredRow=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        GameRuntimeSession completedSecond=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(secondId,7L);
        advanced.act(7L,secondId,secondVersion,"second-coin","FLIP_COIN",Collections.emptyMap());
        TopicRouteSession afterCoinReplay=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        GameRuntimeSession afterSecondReplay=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(secondId,7L);
        assertEquals(restoredRow.getVersion(),afterCoinReplay.getVersion());assertEquals(restoredRow.getStateJson(),afterCoinReplay.getStateJson());
        assertEquals(completedSecond.getVersion(),afterSecondReplay.getVersion());assertEquals(completedSecond.getStateJson(),afterSecondReplay.getStateJson());
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("SELECT count(*) FROM game_runtime_session WHERE topic_id=10 AND node_id=13 AND owner_type=? AND owner_id=7")) {
            st.setInt(1,GameRuntimeSession.OWNER_PLAYER);try(ResultSet rows=st.executeQuery()){assertTrue(rows.next());assertEquals(2,rows.getInt(1));}
        }
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"profile","note","dailySign"})
    void r5ActualCompletionSaveUsesOnePendingConfirmationAndReplaysNoSecondFee(String kit) throws Exception {
        try(Connection c=ds.getConnection()){exec(c,"DELETE FROM play_note");}
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,kit);long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();String action="profile".equals(kit)?"SUBMIT_PROFILE":"note".equals(kit)?"SUBMIT_NOTE":"CLAIM_DAILY_SIGN";
        Map<String,Object> payload=new java.util.LinkedHashMap<>();if("profile".equals(kit))payload.put("answers",Collections.singletonMap("name","小满"));if("note".equals(kit))payload.put("text","留下一句话");
        assertEquals(true,advanced.act(7L,advancedId,((Number)opened.get("version")).intValue(),"complete",action,payload).get("readyForBase"));
        assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        advanced.act(7L,advancedId,1,"complete-replay",action,payload);
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);
        assertEquals(5,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        if("note".equals(kit))try(Connection c=ds.getConnection();Statement st=c.createStatement();ResultSet result=st.executeQuery("SELECT count(*) FROM play_note WHERE member_id=7 AND node_id=11")){result.next();assertEquals(1,result.getInt(1));}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"steps","qaShot","photoCheck","objectCard","album"})
    void r5PhotoAlbumAndStepsPublishFreezeAndConfirmTheirRealSave(String kit) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,kit);long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();Object advancedTarget=((org.springframework.aop.framework.Advised)advanced).getTargetSource().getTarget();
        ReflectionTestUtils.setField(advancedTarget,"verifiedWeRunService",R5WeRunFixture.service());ReflectionTestUtils.setField(advancedTarget,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->{if(!url.startsWith("fixture-owned-"+member))throw new IllegalArgumentException("foreign upload");});
        com.chengyinhub.business.service.ai.PhotoCheckVisionService vision=mock(com.chengyinhub.business.service.ai.PhotoCheckVisionService.class);when(vision.review(any(),any(),any())).thenReturn(com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"passed"));ReflectionTestUtils.setField(advancedTarget,"photoCheckVisionService",vision);
        Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();Map<String,Object> payload=new java.util.LinkedHashMap<>();String action;
        if("steps".equals(kit)){payload=JSON.convertValue(R5WeRunFixture.today(advancedNow.get(),7,"",100),Map.class);payload.remove("episodeId");action="SUBMIT_STEPS";}
        else if("album".equals(kit)){payload.put("imageUrls",Arrays.asList("fixture-owned-7-a","fixture-owned-7-b"));action="SUBMIT_ALBUM";}
        else{payload.put("imageUrl","fixture-owned-7-a");action="qaShot".equals(kit)?"SUBMIT_QA":"SUBMIT_PHOTO_CHECK";}
        assertEquals(true,advanced.act(7L,advancedId,((Number)opened.get("version")).intValue(),"complete",action,payload).get("readyForBase"));assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);assertEquals(5,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","ENDED"})
    void r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult(String outcome) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,"qaFeedback");long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();assertNotNull(runtime.publishedR5NodeConfig(7L,10L,11L,0L),"published R5 provenance missing");Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();assertEquals("",JSON.valueToTree(opened).path("branch").path("currentStepId").asText());assertThrows(IllegalStateException.class,()->advanced.act(7L,advancedId,0,"early-branch","CHOOSE",Collections.singletonMap("optionId","continue")));
        Map<String,Object> judged=advanced.act(7L,advancedId,0,"judge","SUBMIT_QA",Collections.singletonMap("input","FAIL".equals(outcome)?"wrong":"correct"));if(!"FAIL".equals(outcome)){assertEquals("",JSON.valueToTree(judged).path("branch").path("currentStepId").asText());TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);}
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);String receipt=JSON.readTree(row.getStateJson()).path("themeRuntime").path("lastOutcome").toString();int hp=JSON.readTree(row.getStateJson()).path("variables").path("sys.hp").asInt();
        if("ENDED".equals(outcome)){row.setStatus(TopicRouteSession.STATUS_COMPLETED);sql.getMapper(TopicRouteSessionMapper.class).updateStateCas(row);assertFalse(JSON.valueToTree(advanced.state(7L,advancedId)).path("branch").path("writable").asBoolean());assertThrows(RuntimeException.class,()->advanced.act(7L,advancedId,1,"ended-choice","CHOOSE",Collections.singletonMap("optionId","continue")));}
        else{Map<String,Object> done=advanced.act(7L,advancedId,1,"feedback-choice","CHOOSE",Collections.singletonMap("optionId","continue"));assertTrue(JSON.valueToTree(done).path("branch").path("ended").asBoolean());advanced.act(7L,advancedId,0,"feedback-choice","CHOOSE",Collections.singletonMap("optionId","continue"));assertEquals(hp+1,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());assertThrows(IllegalStateException.class,()->advanced.act(7L,advancedId,2,"new-key-terminal","CHOOSE",Collections.singletonMap("optionId","continue")));}
        assertEquals(receipt,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("themeRuntime").path("lastOutcome").toString());
    }
    @Test void r5TamperedAuthorHpWriteCannotAcquirePublishedValidationMarker() throws Exception {
        personalTask(false,null,"BLOCK_HP_COST",false,false,false,"qaFeedback");CmsTopic before=sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(10L);com.fasterxml.jackson.databind.node.ObjectNode draft=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(sql.getMapper(CmsMemberTemplateMapper.class).selectCmsMemberTemplateById(101L).getAdvancedConfigJson());((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("branch").path("steps").path(0).path("options").path(0).path("effects").path(0)).put("var","sys.hp");
        try(Connection c=ds.getConnection();PreparedStatement change=c.prepareStatement("UPDATE cms_member_template SET advanced_config_json=? WHERE id=101")){change.setString(1,draft.toString());change.executeUpdate();}
        assertThrows(IllegalArgumentException.class,()->runtime.freezePublication(before,null));assertEquals(before.getPublishedRouteGraphJson(),sql.getMapper(CmsTopicMapper.class).selectCmsTopicById(10L).getPublishedRouteGraphJson());assertEquals(1,JSON.readTree(before.getPublishedRouteGraphJson()).path("r5AuthorValidationVersion").asInt());
    }
    @Test void r5PersonalZeroViewUsesCurrentMemberAndDoesNotPublishWeRunReadings() throws Exception {
        long now=System.currentTimeMillis();ReflectionTestUtils.setField(target,"clock",Clock.fixed(java.time.Instant.ofEpochMilli(now),java.time.ZoneId.of("Asia/Shanghai")));ReflectionTestUtils.setField(target,"verifiedWeRunService",R5WeRunFixture.service());
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO ums_member(id,nickname) VALUES(7,'本人') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)");}
        Map<String,Object> initial=personalTask(false,null,"STEPS");long id=((Number)initial.get("sessionId")).longValue();
        try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET state_json=JSON_SET(state_json,'$.variables.\"sys.hp\"',0) WHERE id="+id);}
        JsonNode zero=JSON.valueToTree(runtime.themeView(7L,10L,0L,id)).path("zeroHp");assertEquals(7,zero.path("recoveryMemberId").asLong());assertEquals("本人",zero.path("recoveryMemberName").asText());assertEquals(JSON.createArrayNode().add("ZERO_HP_STEPS"),zero.path("allowedActions"));assertEquals("",zero.path("disabledReason").asText());assertEquals("THEME_FAIL",zero.path("thenPolicy").asText());
        R5ZeroViewFixtures.write("personal-steps-awaiting-baseline",runtime.themeView(7L,10L,0L,id));
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.syncZeroHpSteps(7L,id,row.getVersion(),"public-baseline",R5WeRunFixture.today(now,7,zero.path("episodeId").asText(),1234));
        zero=JSON.valueToTree(runtime.themeView(7L,10L,0L,id)).path("zeroHp");assertEquals(7,zero.path("recoveryMemberId").asLong());for(String field:Arrays.asList("baselineDay","baselineSteps","highWaterByDay","openid","encryptedData"))assertFalse(zero.has(field),field);
        R5ZeroViewFixtures.write("personal-steps-recovering",runtime.themeView(7L,10L,0L,id));
    }

    @Test void r5PersonalStepsUsesVerifiedBaselineReplaysAndRollsBackFailedRestore() throws Exception {
        long now=System.currentTimeMillis();java.time.Clock fixed=java.time.Clock.fixed(java.time.Instant.ofEpochMilli(now),java.time.ZoneId.of("Asia/Shanghai"));ReflectionTestUtils.setField(target,"clock",fixed);ReflectionTestUtils.setField(target,"verifiedWeRunService",R5WeRunFixture.service());
        Map<String,Object> initial=personalTask(false,null,"STEPS");long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        runtime.recordGameplayOutcome(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"advanced:1:1",JSON.createObjectNode().put("nodeId",11).put("outcome","FAIL"),Collections.emptyList());
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);String episode=JSON.readTree(row.getStateJson()).path("themeRuntime").path("zeroHp").path("episodeId").asText();assertFalse(episode.isEmpty());
        com.fasterxml.jackson.databind.node.ObjectNode base=R5WeRunFixture.today(now,7,episode,1000);Map<String,Object> first=runtime.syncZeroHpSteps(7L,id,row.getVersion(),"steps-base",base);assertEquals(0,JSON.valueToTree(first).path("zeroHp").path("progress").asInt());assertEquals(JSON.readTree(JSON.writeValueAsString(first)),JSON.readTree(JSON.writeValueAsString(runtime.syncZeroHpSteps(7L,id,0,"steps-base",base))));
        row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);int version=row.getVersion();assertThrows(IllegalArgumentException.class,()->runtime.syncZeroHpSteps(7L,id,version,"other-identity",R5WeRunFixture.today(now,8,episode,10000)));
        runtime.syncZeroHpSteps(7L,id,row.getVersion(),"steps-progress",R5WeRunFixture.today(now,7,episode,1050));row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);String before=row.getStateJson();int expected=row.getVersion();
        try(Connection c=ds.getConnection()){exec(c,"CREATE TRIGGER fail_steps_restore BEFORE UPDATE ON topic_route_session FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='local restore rollback'");}
        try{assertThrows(RuntimeException.class,()->runtime.syncZeroHpSteps(7L,id,expected,"steps-complete",R5WeRunFixture.today(now,7,episode,1100)));assertEquals(before,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());}
        finally{try(Connection c=ds.getConnection()){exec(c,"DROP TRIGGER fail_steps_restore");}}
        runtime.syncZeroHpSteps(7L,id,expected,"steps-complete",R5WeRunFixture.today(now,7,episode,1100));String restored=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson();assertEquals(4,JSON.readTree(restored).path("variables").path("sys.hp").asInt());assertEquals(1,JSON.readTree(restored).path("themeRuntime").path("zeroHp").path("used").asInt());assertFalse(restored.contains("fixture-code"));assertFalse(restored.contains("encryptedData"));assertFalse(restored.contains("fixture-player"));
    }

    @Test void r5TimeoutCompletedUsesPassPendingAndRetainsFixedProofWhenBalanceFalls() throws Exception {
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=readyTimedAdvanced(true);advancedNow.addAndGet(30001);
        assertEquals(true,advanced.state(7L,advancedId).get("readyForBase"));
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(personalId,7L);
        com.fasterxml.jackson.databind.node.ObjectNode state=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(row.getStateJson());assertEquals(6,state.path("variables").path("sys.hp").asInt());assertFalse(state.path("themeRuntime").path("gameplay").path("slots").has("north"));
        state.with("variables").put("sys.hp",0);
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE topic_route_session SET state_json=? WHERE id=?")){st.setString(1,state.toString());st.setLong(2,personalId);st.executeUpdate();}
        Map<String,Object> blocked=runtime.gameplayAction(7L,personalId,row.getVersion(),"timeout-confirm","QUEST_RESOLVE","north",null);
        JsonNode stored=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(personalId,7L).getStateJson());
        assertTrue(JSON.writeValueAsString(blocked).contains("BLOCKED_COST"));assertEquals("PASS",stored.path("themeRuntime").path("lastOutcome").path("outcome").asText());assertEquals(0,stored.path("variables").path("sys.hp").asInt());assertFalse(stored.path("themeRuntime").path("gameplay").path("slots").has("north"));
        assertEquals(Integer.valueOf(1),sql.getMapper(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L).getStatus());
    }
    @Test void r5TimedStateSettlesBothSessionsOnceWithoutOpeningAnotherAttempt() throws Exception {
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=readyTimedAdvanced();advancedNow.addAndGet(30001);advanced.state(7L,advancedId);
        String settled=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(personalId,7L).getStateJson();assertEquals(3,JSON.readTree(settled).path("variables").path("sys.hp").asInt());advanced.state(7L,advancedId);assertEquals(settled,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(personalId,7L).getStateJson());
        assertEquals(Integer.valueOf(2),sql.getMapper(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L).getStatus());
    }
    @Test void r5TimerDatabaseFailureRollsBackAdvancedCasAndOutcomeFeeTogether() throws Exception {
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=readyTimedAdvanced();advancedNow.addAndGet(30001);
        try(Connection c=ds.getConnection()){exec(c,"CREATE TRIGGER fail_r5_timer BEFORE UPDATE ON topic_route_session FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='local test rollback'");}
        try {assertThrows(RuntimeException.class,()->advanced.state(7L,advancedId));assertEquals(Integer.valueOf(0),sql.getMapper(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L).getStatus());assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(personalId,7L).getStateJson()).path("variables").path("sys.hp").asInt());}
        finally {try(Connection c=ds.getConnection()){exec(c,"DROP TRIGGER fail_r5_timer");}}
        advanced.state(7L,advancedId);assertEquals(3,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(personalId,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }

    @Test void r5CheckUsesRealFrozenDiceAndRerollPaysOnceWithNoLegacyDamage() throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",true);long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);String instance=runtime.gameplayInstance(7L,10L,0L,11L);
        com.chengyinhub.business.domain.route.PlayCheckReceipt first=runtime.rollGameplayCheck(7L,10L,0L,11L,instance,"advanced:1:1",false);assertFalse(first.getSuccess());
        assertEquals(first.getDice(),runtime.rollGameplayCheck(7L,10L,0L,11L,instance,"advanced:1:1",false).getDice());
        runtime.rollGameplayCheck(7L,10L,0L,11L,instance,"advanced:1:1",true);runtime.rollGameplayCheck(7L,10L,0L,11L,instance,"advanced:1:1",true);
        TopicRouteSession owner=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);assertEquals(0,JSON.readTree(owner.getStateJson()).path("variables").path("sys.luck").asInt());
        com.fasterxml.jackson.databind.node.ObjectNode decision=JSON.createObjectNode().put("nodeId",11).put("outcome","FAIL");decision.putObject("selection").put("kit","check");runtime.recordGameplayOutcome(7L,10L,0L,11L,instance,"advanced:1:1",decision,Collections.emptyList());
        owner=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);assertEquals(4,JSON.readTree(owner.getStateJson()).path("variables").path("sys.hp").asInt());assertTrue(JSON.readTree(owner.getStateJson()).path("tags").toString().contains("tag.lost"));
    }
    @Test void r5TrustedFailureChargesOnceAndPassWaitsForConfirmation() throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST");long id=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"r5-start","QUEST_START","north",null);
        String instance=runtime.gameplayInstance(7L,10L,0L,11L);
        com.fasterxml.jackson.databind.JsonNode fail=JSON.createObjectNode().put("nodeId",11).put("outcome","FAIL");
        runtime.recordGameplayOutcome(7L,10L,0L,11L,instance,"advanced:1:1",fail,Collections.emptyList());
        TopicRouteSession stored=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        assertEquals(3,JSON.readTree(stored.getStateJson()).path("variables").path("sys.hp").asInt());
        runtime.recordGameplayOutcome(7L,10L,0L,11L,instance,"advanced:1:1",fail,Collections.emptyList());
        assertEquals(stored.getStateJson(),sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());
        assertThrows(IllegalStateException.class,()->runtime.recordGameplayOutcome(7L,10L,0L,11L,instance,"advanced:1:1",JSON.createObjectNode().put("nodeId",11).put("outcome","PASS"),Collections.emptyList()));
        runtime.recordGameplayOutcome(7L,10L,0L,11L,instance,"advanced:1:2",JSON.createObjectNode().put("nodeId",11).put("outcome","PASS"),Collections.emptyList());
        stored=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        assertEquals(3,JSON.readTree(stored.getStateJson()).path("variables").path("sys.hp").asInt());
        Map<String,Object> done=runtime.gameplayAction(7L,id,stored.getVersion(),"r5-confirm","QUEST_RESOLVE","north",null);
        assertEquals(2,((Number)done.get("hp")).intValue());
        assertEquals(JSON.readTree(JSON.writeValueAsString(done)),JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L,id,0,"r5-confirm","QUEST_RESOLVE","north",null))));
        try(Connection independent=ds.getConnection();Statement st=independent.createStatement();ResultSet rs=st.executeQuery("SELECT state_json FROM topic_route_session WHERE id="+id)){assertTrue(rs.next());assertEquals(2,JSON.readTree(rs.getString(1)).path("variables").path("sys.hp").asInt());}
    }
    @Test void r5ZeroFailureFreezesFailureCardFromActualFeeTransaction() throws Exception {
        Map<String,Object> initial=personalTask(false,null,"THEME_FAIL");long id=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"r5-start","QUEST_START","north",null);
        runtime.recordGameplayOutcome(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"advanced:1:1",JSON.createObjectNode().put("nodeId",11).put("outcome","FAIL"),Collections.emptyList());
        assertFalse(Boolean.TRUE.equals(runtime.themeCard(7L,id).get("success")));
        com.fasterxml.jackson.databind.JsonNode state=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());
        assertEquals(0,state.path("variables").path("sys.hp").asInt());assertEquals(6,state.path("themeRuntime").path("lastOutcome").path("costs").path(0).path("charged").asInt());
        assertEquals("HP_ZERO",state.path("themeRuntime").path("result").path("reason").asText());
    }
    @Test void r5WaitReadRestoresOnceAndPersistsTheServerDeadline() throws Exception {
        long now=System.currentTimeMillis();ReflectionTestUtils.setField(target,"clock",Clock.fixed(java.time.Instant.ofEpochMilli(now),java.time.ZoneOffset.UTC));
        Map<String,Object> initial=personalTask(false,null,"WAIT");long id=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"r5-start","QUEST_START","north",null);
        runtime.recordGameplayOutcome(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"advanced:1:1",JSON.createObjectNode().put("nodeId",11).put("outcome","FAIL"),Collections.emptyList());
        ReflectionTestUtils.setField(target,"clock",Clock.fixed(java.time.Instant.ofEpochMilli(now+59999),java.time.ZoneOffset.UTC));assertEquals(0,((Number)runtime.themeView(7L,10L,0L,id).get("hp")).intValue());
        ReflectionTestUtils.setField(target,"clock",Clock.fixed(java.time.Instant.ofEpochMilli(now+60000),java.time.ZoneOffset.UTC));assertEquals(4,((Number)runtime.themeView(7L,10L,0L,id).get("hp")).intValue());
        TopicRouteSession restored=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);assertEquals(1,JSON.readTree(restored.getStateJson()).path("themeRuntime").path("zeroHp").path("used").asInt());
        runtime.themeView(7L,10L,0L,id);assertEquals(restored.getStateJson(),sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());
    }
    @Test void scanOnlyTaskUsesTheValidatedRunReceiptWithoutInventingAnObjectiveQuiz() throws Exception {basicTaskFromArrival(4);}
    @Test void gpsOnlyTaskUsesTheValidatedRunReceiptAndFrozenStationCoordinates() throws Exception {basicTaskFromArrival(5);}
    @Test void textOnlyTaskSettlesFromControlledFrozenAnswerProof() throws Exception {basicTaskFromArrival(1);}
    @Test void choiceOnlyTaskSettlesFromControlledFrozenAnswerProof() throws Exception {basicTaskFromArrival(3);}
    private void basicTaskFromArrival(int method) throws Exception {
        Map<String,Object> initial=personalTask(false,method);long id=((Number)initial.get("sessionId")).longValue();
        Map<String,Object> start=runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"basic-start","QUEST_START","north",null);
        assertEquals("quest:",runtime.gameplayInstance(7L,10L,0L,11L).substring(0,6));Map<String,Object> done=runtime.gameplayAction(7L,id,((Number)start.get("version")).intValue(),"basic-done","QUEST_RESOLVE","north",null);
        assertEquals("COMPLETED",done.get("status"));assertEquals(5,((Number)done.get("hp")).intValue());assertEquals(2,((Number)done.get("used")).intValue());
        if(method==5)assertEquals("121.47",runtime.nodeArrivalConfig(7L,10L,0L,11L).path("longitude").asText());
        com.fasterxml.jackson.databind.JsonNode stored=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());assertEquals("gameplay:basic-done",stored.path("themeRuntime").path("gameplay").path("arrivalFacts").path("11").path("consumedBy").asText());
    }
    private long readyPersonalOperation() throws Exception {
        Map<String,Object> initial=personalTask(true);long id=((Number)initial.get("sessionId")).longValue();
        Map<String,Object> arrived=runtime.recordPlayerArrival(7L,10L,0L,12L,"operation-scan",null);
        runtime.gameplayAction(7L,id,((Number)arrived.get("version")).intValue(),"operation-start","OPERATION_START","warm",null);
        runtime.recordNodeResult(7L,10L,0L,12L,runtime.gameplayInstance(7L,10L,0L,12L),"operation-proof",true,false,0,Collections.emptyList());return id;
    }
    @Test void personalOperationCommitsOnlyTheCurrentProofWithCostsEffectsAndReceipt() throws Exception {
        long id=readyPersonalOperation();Map<String,Object> before=runtime.themeView(7L,10L,0L,id);
        Map<String,Object> result=runtime.gameplayAction(7L,id,((Number)before.get("version")).intValue(),"operation-use","FACILITY_USE","warm",null);
        assertEquals(7,((Number)result.get("hp")).intValue());assertEquals(1,((Number)result.get("used")).intValue());
        assertNull(result.get("activeOperation"));assertThrows(RuntimeException.class,()->runtime.gameplayAction(7L,id,((Number)result.get("version")).intValue(),"operation-again","FACILITY_USE","warm",null));
        assertEquals(JSON.readTree(JSON.writeValueAsString(result)),JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L,id,0,"operation-use","FACILITY_USE","warm",null))));
    }
    @Test void concurrentPersonalOperationCannotUseTheSameProofTwice() throws Exception {
        long id=readyPersonalOperation();int version=((Number)runtime.themeView(7L,10L,0L,id).get("version")).intValue();CountDownLatch go=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {List<Future<Boolean>> work=new ArrayList<>();for(String key:Arrays.asList("facility-one","facility-two"))work.add(pool.submit(()->{go.await();try{runtime.gameplayAction(7L,id,version,key,"FACILITY_USE","warm",null);return true;}catch(RuntimeException conflict){return false;}}));go.countDown();int winners=0;for(Future<Boolean> result:work)if(result.get())winners++;assertEquals(1,winners);}
        finally {pool.shutdownNow();}
        Map<String,Object> finalView=runtime.themeView(7L,10L,0L,id);assertEquals(7,((Number)finalView.get("hp")).intValue());
        com.fasterxml.jackson.databind.JsonNode state=JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());assertEquals(1,state.path("themeRuntime").path("cooperation").path("facilities").path("warm").path("count").asInt());
    }
    @Test void personalAnyTaskCommitsCostWholePackReceiptAndTerminalTogether() throws Exception {
        Map<String,Object> initial=personalTask();long id=((Number)initial.get("sessionId")).longValue();
        Map<String,Object> start=runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        runtime.recordNodeResult(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"trusted-group",true,false,0,Collections.emptyList());
        Map<String,Object> ready=runtime.themeView(7L,10L,0L,id);
        Map<String,Object> done=runtime.gameplayAction(7L,id,((Number)ready.get("version")).intValue(),"done","QUEST_RESOLVE","north",null);
        assertEquals("COMPLETED",done.get("status"));assertEquals(5,((Number)done.get("hp")).intValue());assertEquals(2,((Number)done.get("used")).intValue());
        assertEquals(JSON.readTree(JSON.writeValueAsString(done)),JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L,id,0,"done","QUEST_RESOLVE","north",null))));
        assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,((Number)done.get("version")).intValue(),"bypass","CHAPTER_END","0"));
    }
    @Test void personalTaskDatabaseFailureRollsBackInventoryStateAndReceipt() throws Exception {
        Map<String,Object> initial=personalTask();long id=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        runtime.recordNodeResult(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"trusted-group",true,false,0,Collections.emptyList());
        TopicRouteSession before=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        try(Connection c=ds.getConnection()){exec(c,"CREATE TRIGGER fail_personal_task BEFORE UPDATE ON topic_route_session FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='local test rollback'");}
        try {
            assertThrows(RuntimeException.class,()->runtime.gameplayAction(7L,id,before.getVersion(),"rollback","QUEST_RESOLVE","north",null));
            assertEquals(before.getStateJson(),sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());
            assertNull(sql.getMapper(TopicRouteSessionMapper.class).selectActionByAction(id,7L,"gameplay:rollback"));
        } finally {try(Connection c=ds.getConnection()){exec(c,"DROP TRIGGER fail_personal_task");}}
    }
    @Test void concurrentPersonalRequestsCannotDoubleChargeBusinessSlot() throws Exception {
        Map<String,Object> initial=personalTask();long id=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        runtime.recordNodeResult(7L,10L,0L,11L,runtime.gameplayInstance(7L,10L,0L,11L),"trusted-group",true,false,0,Collections.emptyList());
        int version=((Number)runtime.themeView(7L,10L,0L,id).get("version")).intValue();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {
            List<Future<Boolean>> work=new ArrayList<>();
            for(String key:Arrays.asList("one","two"))work.add(pool.submit(()->{go.await();try{runtime.gameplayAction(7L,id,version,key,"QUEST_RESOLVE","north",null);return true;}catch(RuntimeException conflict){return false;}}));
            go.countDown();int success=0;for(Future<Boolean> result:work)if(result.get(15,TimeUnit.SECONDS))success++;
            assertEquals(1,success);Map<String,Object> view=runtime.themeView(7L,10L,0L,id);assertEquals(5,((Number)view.get("hp")).intValue());assertEquals(2,((Number)view.get("used")).intValue());
        }finally{pool.shutdownNow();}
    }

    @Test void mobileChapterConfigGrantsUsesAndReloadsWithoutRepeating() throws Exception {
        String authored="{\"schemaVersion\":1,\"capacity\":6,\"allowDiscard\":true,\"items\":[{\"id\":\"drink\",\"kind\":\"ITEM\",\"name\":\"饮品\",\"image\":\"https://example.test/asset.png\",\"stat\":\"HP\",\"delta\":2},{\"id\":\"forecast\",\"kind\":\"ITEM\",\"name\":\"天气图\",\"image\":\"https://example.test/asset.png\"},{\"id\":\"mark\",\"kind\":\"MEDAL\",\"name\":\"印记\",\"image\":\"https://example.test/asset.png\"}],\"chapters\":[{\"index\":0,\"hpCost\":1,\"luckCost\":0,\"grants\":[{\"itemId\":\"drink\",\"quantity\":1,\"timing\":\"START\"},{\"itemId\":\"mark\",\"quantity\":1,\"timing\":\"END\"}],\"eggs\":[{\"id\":\"time\",\"name\":\"预报\",\"content\":\"气温下降\",\"trigger\":\"TIME\",\"threshold\":1,\"rewards\":[{\"itemId\":\"forecast\",\"quantity\":1}]},{\"id\":\"steps\",\"name\":\"足迹\",\"content\":\"继续前进\",\"trigger\":\"STEPS\",\"threshold\":20,\"rewards\":[]}]}],\"grants\":[],\"events\":[]}";
        com.fasterxml.jackson.databind.node.ObjectNode draft=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(authored);
        draft.withArray("chapters").addObject().put("index",1).put("hpCost",0).put("luckCost",0);
        config(draft.toString());
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id,required) VALUES(21,10,'第一章',1,1),(22,10,'第二章',2,1)");
            exec(c,"UPDATE cms_topic_node SET chapter_id=IF(id=11,21,22) WHERE topic_id=10");
        }
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);long id=((Number)first.get("sessionId")).longValue();
        assertEquals(1,((Number)first.get("used")).intValue());assertEquals(6,((Number)first.get("hp")).intValue());
        assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,((Number)first.get("version")).intValue(),"future","CHAPTER_START","1"));
        assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,((Number)first.get("version")).intValue(),"early","CHAPTER_END","0"));
        Map<String,Object> started=runtime.themeAction(7L,id,((Number)first.get("version")).intValue(),"start_again","CHAPTER_START","0");
        assertEquals(1,((Number)started.get("used")).intValue());
        Map<String,Object> used=runtime.themeAction(7L,id,((Number)started.get("version")).intValue(),"drink","USE","drink");assertEquals(8,((Number)used.get("hp")).intValue());
        runtime.syncThoughts(7L,10L,0L,Collections.emptyList(),100,20000L);runtime.syncThoughts(7L,10L,0L,Collections.emptyList(),120,20000L);
        try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET state_json=JSON_SET(state_json,'$.themeRuntime.chapters.\"0\".startedAt',"+(System.currentTimeMillis()-61000)+") WHERE id="+id);}
        Map<String,Object> eggs=runtime.themeView(7L,10L,0L,id);assertEquals(2,((List)eggs.get("events")).size());assertEquals(1,((Number)eggs.get("used")).intValue());
        Map<String,Object> reload=runtime.themeView(7L,10L,0L,id);assertEquals(eggs.get("version"),reload.get("version"));assertEquals(2,((List)reload.get("events")).size());
        completedCurrentNode(7,10,0,11);
        runtime.advance(7L,10L,0L,((Number)reload.get("version")).intValue(),"node11","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11"));
        Map<String,Object> ended=runtime.themeView(7L,10L,0L,id);assertEquals(7,((Number)ended.get("hp")).intValue());assertEquals(2,((List)ended.get("contents")).size());
        Map<String,Object> discarded=runtime.themeAction(7L,id,((Number)ended.get("version")).intValue(),"drop","DISCARD","forecast");assertEquals(0,((Number)discarded.get("used")).intValue());
        Map<String,Object> endAgain=runtime.themeAction(7L,id,((Number)discarded.get("version")).intValue(),"end_again","CHAPTER_END","0");assertEquals(7,((Number)endAgain.get("hp")).intValue());assertEquals(1,((List)endAgain.get("contents")).size());
    }

    @Test void migrationAddsRunToBothUniqueKeys() throws Exception {
        try(Connection c=ds.getConnection()) {
            String insert="INSERT INTO game_runtime_session(activity_id,topic_id,node_id,template_id,owner_type,owner_id,play_instance_id,config_snapshot_json,state_json,started_at,create_time,update_time) VALUES(0,10,11,101,1,7,";
            exec(c,insert+"'default','{}','{}',NOW(),NOW(),NOW()),(0,10,11,101,1,7,'replay:1','{}','{}',NOW(),NOW(),NOW())");
            assertThrows(SQLException.class,()->exec(c,insert+"'default','{}','{}',NOW(),NOW(),NOW())"));
            exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0),(7,0,10,11,'replay:1',1,0)");
            assertThrows(SQLException.class,()->exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0)"));
        }
    }
    private Map<String,Object> stocked() throws Exception {
        Map<String,Object> view=runtime.themeView(7L,10L,0L,null);long id=((Number)view.get("sessionId")).longValue();
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE topic_route_session SET state_json=JSON_SET(state_json,'$.themeRuntime.inventory.food',1,'$.themeRuntime.acquired.food',1) WHERE id=?")){st.setLong(1,id);st.executeUpdate();}return view;
    }
    @Test void lastItemConcurrentUseHasOneWinnerAndNoPartialWrites() throws Exception {
        Map<String,Object> first=stocked();long id=((Number)first.get("sessionId")).longValue();int version=((Number)first.get("version")).intValue();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        for(int i=0;i<2;i++){final int action=i;futures.add(pool.submit(()->{start.await();try{runtime.themeAction(7L,id,version,"use"+action,"USE","food");return true;}catch(RuntimeException expected){return false;}}));}
        start.countDown();int winners=0;for(Future<Boolean> f:futures)if(f.get(10,TimeUnit.SECONDS))winners++;pool.shutdownNow();assertEquals(1,winners);
        Map<String,Object> read=runtime.themeView(7L,10L,0L,id);assertEquals(8,((Number)read.get("hp")).intValue());assertEquals(0,((Number)read.get("used")).intValue());
        try(Connection c=ds.getConnection();Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT count(*) FROM topic_route_action WHERE outcome_code='USE'")){rs.next();assertEquals(1,rs.getInt(1));}
    }
    @Test void ownReplayIsFreshAndKeepsOldResult() throws Exception {
        Map<String,Object> first=stocked();long id=((Number)first.get("sessionId")).longValue();runtime.themeAction(7L,id,0,"end","END",null);
        Map<String,Object> old=runtime.themeView(7L,10L,0L,id);Map<String,Object> fresh=runtime.replayTheme(7L,id);assertNotEquals(id,((Number)fresh.get("sessionId")).longValue());assertEquals(0,((Number)fresh.get("used")).intValue());assertEquals(6,((Number)fresh.get("hp")).intValue());
        assertEquals(old.get("result"),runtime.themeView(7L,10L,0L,id).get("result"));assertEquals(fresh.get("sessionId"),runtime.replayTheme(7L,id).get("sessionId"));assertEquals(2,runtime.themeHistory(7L,10L).size());
        List<Map<String,Object>> page=runtime.themeHistory(7L,10L,null,1);assertEquals(1,page.size());
        assertEquals(id,((Number)runtime.themeHistory(7L,10L,((Number)page.get(0).get("sessionId")).longValue(),1).get(0).get("sessionId")).longValue());
        assertTrue(runtime.themeHistory(8L,10L,null,10).isEmpty());
        assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,1,"old","USE","food"));assertThrows(RuntimeException.class,()->runtime.themeView(8L,10L,0L,id));
        TopicRouteState route=runtime.state(7L,10L,0L);assertEquals(11L,route.getCurrentNodeId());assertEquals(fresh.get("sessionId"),route.getSessionId());
    }
    @Test void sameRequestReturnsReceiptAndCannotChangePayload() throws Exception {
        Map<String,Object> first=stocked();long id=((Number)first.get("sessionId")).longValue();Map<String,Object> used=runtime.themeAction(7L,id,0,"same","USE","food");assertEquals(JSON.readTree(JSON.writeValueAsString(used)),JSON.readTree(JSON.writeValueAsString(runtime.themeAction(7L,id,0,"same","USE","food"))));assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,0,"same","DISCARD","food"));
    }

    private void config(String raw) throws Exception {
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("UPDATE cms_topic SET theme_runtime_config_json=?,published_theme_runtime_config_json=? WHERE id=10")){st.setString(1,raw);st.setString(2,raw);st.executeUpdate();}
    }
    @Test void automaticBroadcastRetriesMessageWithoutRepeatingInventory() throws Exception {
        config("{\"schemaVersion\":1,\"items\":[{\"id\":\"permit\",\"kind\":\"MEDAL\",\"name\":\"通行凭证\",\"image\":\"https://example.test/permit.png\"}],\"capacity\":1,\"events\":[{\"id\":\"opening\",\"content\":\"请带凭证前往下一站\",\"grants\":[{\"itemId\":\"permit\",\"quantity\":1}]}]}");
        org.mockito.Mockito.when(im.sendSystemInNestedTransaction(org.mockito.ArgumentMatchers.eq(7L),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("站内消息暂不可用")).thenReturn(88L);
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);Long id=((Number)first.get("sessionId")).longValue();assertEquals("FAILED",((Map)((List)first.get("broadcasts")).get(0)).get("status"));
        Map<String,Object> retry=runtime.themeView(7L,10L,0L,id);assertEquals("REACHED",((Map)((List)retry.get("broadcasts")).get(0)).get("status"));assertEquals(1,((List)retry.get("contents")).size());assertEquals(1,((Number)((Map)((List)retry.get("contents")).get(0)).get("quantity")).intValue());
        runtime.themeView(7L,10L,0L,id);org.mockito.Mockito.verify(im,org.mockito.Mockito.times(2)).sendSystemInNestedTransaction(org.mockito.ArgumentMatchers.eq(7L),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        assertThrows(RuntimeException.class,()->runtime.themeView(8L,10L,0L,id));
    }
    @Test void verifiedNodeCompletionGrantsAndFreezesFinalCard() throws Exception {
        config("{\"schemaVersion\":1,\"capacity\":1,\"replayEnabled\":true,\"items\":[{\"id\":\"stamp\",\"kind\":\"MEDAL\",\"name\":\"城市印记\",\"image\":\"https://example.test/stamp.png\"}],\"grants\":[{\"id\":\"first_stop\",\"source\":\"NODE\",\"nodeId\":11,\"itemId\":\"stamp\",\"quantity\":1}],\"card\":{\"fields\":[\"ending\",\"medals\",\"hp\"],\"showUnlocks\":true}}");
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);long id=((Number)first.get("sessionId")).longValue();assertEquals(0,((List)first.get("contents")).size());
        assertThrows(RuntimeException.class,()->runtime.advance(7L,10L,0L,0,"illegal","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11")));
        completedCurrentNode(7,10,0,11);
        runtime.advance(7L,10L,0L,0,"verified11","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11"));
        Map<String,Object> midpoint=runtime.themeView(7L,10L,0L,id);assertEquals(1,((List)midpoint.get("contents")).size());
        completedCurrentNode(7,10,0,12);
        runtime.advance(7L,10L,0L,1,"verified12","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(12L,"COMPLETED","proof12"));
        Map<String,Object> card=runtime.themeCard(7L,id);assertEquals(true,card.get("success"));assertEquals(1,((List)card.get("medals")).size());assertFalse(card.containsKey("sessionId"));
        config("{\"schemaVersion\":1,\"capacity\":2,\"replayEnabled\":true}");assertEquals(JSON.valueToTree(card),JSON.valueToTree(runtime.themeCard(7L,id)));
        Map<String,Object> replay=runtime.replayTheme(7L,id);assertEquals("ACTIVE",replay.get("status"));assertEquals(0,((List)replay.get("contents")).size());
        PlayerNodeProgress probe=new PlayerNodeProgress();probe.setMemberId(7L);probe.setTopicId(10L);probe.setActivityId(0L);assertEquals(0,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressListForInstance(probe,runtime.playInstance(7L,10L,0L)).size());
        assertEquals(2,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressListForInstance(probe,(String)first.get("playInstanceId")).size(),"原 FIRST 历史仍在自己的实例");
        assertEquals(0,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(probe).size(),"旧 default 读取不能混入 FIRST");
    }
    @Test void negativeItemEndsAtomicallyAndOldResultIsReadOnly() throws Exception {
        Map<String,Object> first=stocked();long id=((Number)first.get("sessionId")).longValue();
        try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET config_snapshot_json=JSON_SET(config_snapshot_json,'$.themeConfig.items[0].delta',-6) WHERE id="+id);}
        Map<String,Object> ended=runtime.themeAction(7L,id,0,"end_by_item","USE","food");assertEquals("ENDED",ended.get("status"));assertEquals(0,((Number)ended.get("hp")).intValue());assertEquals("EXHAUSTED",((Map)ended.get("result")).get("reason"));
        assertEquals(0,((Number)ended.get("used")).intValue());assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,1,"after_end","USE","food"));assertNotNull(runtime.themeCard(7L,id));
        assertEquals(JSON.valueToTree(ended),JSON.valueToTree(runtime.themeAction(7L,id,99,"another_end","END",null)));
    }

    @Test void rescueLimitReusesRecoveryAndDoesNotResetInventoryOrActions() throws Exception {
        Map<String,Object> first=stocked();long id=((Number)first.get("sessionId")).longValue();
        try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET config_snapshot_json=JSON_SET(config_snapshot_json,'$.themeConfig.rescueLimit',1,'$.themeConfig.items[0].delta',-6) WHERE id="+id);}
        Map<String,Object> waiting=runtime.themeAction(7L,id,0,"danger","USE","food");assertEquals(true,waiting.get("awaitingRescue"));assertFalse(waiting.containsKey("result"));assertEquals(0,((Number)waiting.get("used")).intValue());
        Map<String,Object> rescued=runtime.themeAction(7L,id,1,"rescue","RESCUE",null);assertEquals(false,rescued.get("awaitingRescue"));assertEquals(5,((Number)rescued.get("hp")).intValue());assertEquals(1,((Number)rescued.get("rescues")).intValue());
        assertEquals(JSON.readTree(JSON.writeValueAsString(rescued)),JSON.readTree(JSON.writeValueAsString(runtime.themeAction(7L,id,1,"rescue","RESCUE",null))));
        assertThrows(RuntimeException.class,()->runtime.themeAction(7L,id,2,"second","RESCUE",null));
        try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET state_json=JSON_SET(state_json,'$.themeRuntime.inventory.food',1) WHERE id="+id);}
        Map<String,Object> ended=runtime.themeAction(7L,id,2,"again","USE","food");assertEquals("ENDED",ended.get("status"));assertEquals(1,((Number)((Map)ended.get("result")).get("rescues")).intValue());
    }
    @Test void draftAndPublishedConfigRoundTripKeepExistingRunFrozen() throws Exception {
        Map<String,Object> original=runtime.themeView(7L,10L,0L,null);
        CmsTopicMapper topics=sql.getMapper(CmsTopicMapper.class);
        CmsTopic patch=new CmsTopic();patch.setId(10L);patch.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":7,\"hpName\":\"体力\",\"replayEnabled\":true}");
        assertEquals(1,topics.updateCmsTopic(patch));
        assertEquals(7,JSON.readTree(topics.selectCmsTopicById(10L).getThemeRuntimeConfigJson()).path("capacity").asInt());
        assertEquals(3,JSON.readTree(topics.selectCmsTopicById(10L).getPublishedThemeRuntimeConfigJson()).path("capacity").asInt());
        assertEquals(1,topics.publishRouteSnapshot(10L,null,new java.util.Date()));
        assertEquals(7,JSON.readTree(topics.selectCmsTopicById(10L).getPublishedThemeRuntimeConfigJson()).path("capacity").asInt());
        long id=((Number)original.get("sessionId")).longValue();
        assertEquals(3,((Number)runtime.themeView(7L,10L,0L,id).get("capacity")).intValue());
        runtime.themeAction(7L,id,0,"end","END",null);
        assertEquals(7,((Number)runtime.replayTheme(7L,id).get("capacity")).intValue());
    }
    @Test void completedRouteWithInsufficientEndingIsNotSuccessfulCompletion() throws Exception {
        runtime.themeView(7L,10L,0L,null);
        completedCurrentNode(7,10,0,11);completedCurrentNode(7,10,0,12);
        runtime.advance(7L,10L,0L,0,"first","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11"));
        runtime.advance(7L,10L,0L,1,"second","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(12L,"COMPLETED","proof12"));
        assertFalse(runtime.isCompleted(7L,10L,0L,2,2));assertEquals(0,runtime.countCompletedParticipants(10L));
        assertEquals("ENDED",runtime.themeView(7L,10L,0L,null).get("status"));
    }
    @Test void legacyRankNeedsCurrentRunClaimAndDoesNotReappearOnReplay() throws Exception {
        config("{\"schemaVersion\":1,\"replayEnabled\":true,\"card\":{\"fields\":[\"ending\",\"medals\"]}}");
        try(Connection c=ds.getConnection()) {
            exec(c,"UPDATE cms_topic SET finish_medal_name='通关章',finish_medal_img='https://example.test/finish.png' WHERE id=10");
            exec(c,"INSERT INTO cms_member_template(id,medal_name,medal_img) VALUES(20,'节点奖牌','https://example.test/rank.png')");
            exec(c,"UPDATE cms_topic_node SET template_id=20 WHERE id=11");
        }
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);long id=((Number)first.get("sessionId")).longValue();
        assertEquals(0,((List)first.get("contents")).size());
        completedCurrentNode(7,10,0,11);
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO node_medal_claim(node_id,medal_rank,member_id,activity_id,topic_id,progress_id) SELECT 11,1,7,0,10,id FROM player_node_progress WHERE member_id=7 AND node_id=11");
        }
        runtime.advance(7L,10L,0L,0,"rank_node","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11"));
        assertEquals(1,((List)runtime.themeView(7L,10L,0L,id).get("contents")).size());
        completedCurrentNode(7,10,0,12);
        runtime.advance(7L,10L,0L,1,"finish_node","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(12L,"COMPLETED","proof12"));
        assertEquals(2,((List)runtime.themeCard(7L,id).get("medals")).size());
        Map<String,Object> fresh=runtime.replayTheme(7L,id);String instance=(String)fresh.get("playInstanceId");
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,?,1,0)")){st.setString(1,instance);st.executeUpdate();}
        runtime.advance(7L,10L,0L,0,"rank_again","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","freshproof"));
        assertEquals(0,((List)runtime.themeView(7L,10L,0L,((Number)fresh.get("sessionId")).longValue()).get("contents")).size());
        assertEquals(2,((List)runtime.themeCard(7L,id).get("medals")).size());
    }
    @Test void repeatableReadGuardUsesCurrentLockingReadAfterReplayCommit() throws Exception {
        Map<String,Object> original=runtime.themeView(7L,10L,0L,null);
        long previous=((Number)original.get("sessionId")).longValue();
        runtime.themeAction(7L,previous,((Number)original.get("version")).intValue(),"end_before_rr","END",null);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            tx.execute(status -> {
                // Establish an old consistent-read view before another transaction creates a successor.
                assertEquals(previous,sql.getMapper(TopicRouteSessionMapper.class).selectCurrentByOwner(0L,10L,7L).getId().longValue());
                try {
                    Map<String,Object> replay=pool.submit(() -> runtime.replayTheme(7L,previous)).get(10,TimeUnit.SECONDS);
                    assertNotEquals(previous,((Number)replay.get("sessionId")).longValue());
                } catch(Exception failure) { throw new IllegalStateException(failure); }
                assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                        () -> runtime.assertCurrentPlayInstance(7L,10L,0L,(String)original.get("playInstanceId")));
                status.setRollbackOnly();return null;
            });
        } finally {pool.shutdownNow();}
    }

    private static void exec(Connection c,String text)throws SQLException{try(Statement st=c.createStatement()){st.execute(text);}}
    private long[] endedTeam() throws Exception {
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES(33,2,5,7,0)");
            exec(c,"INSERT INTO play_team_member(team_id,member_id,role,status) VALUES(33,7,1,1),(33,8,0,1)");
            exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,ticket_id,purchase_kind,create_time) VALUES(7,2,5,10,2,1,0,NOW()),(8,2,5,10,2,1,0,NOW())");
        }
        long[] ids=new long[2];for(int i=0;i<2;i++) {
            long member=7L+i;Map<String,Object> view=runtime.themeView(member,10L,5L,null);ids[i]=((Number)view.get("sessionId")).longValue();
            runtime.themeAction(member,ids[i],((Number)view.get("version")).intValue(),"end-team-"+member,"END",null);
        }
        return ids;
    }
    @Test void teamReplayCreatesOneSharedGenerationAndPreservesOldPersonalRows() throws Exception {
        long[] old=endedTeam();Map<String,Object> first=runtime.replayThemeTeam(7L,33L,5L,10L,old[0],null,"team-once");
        assertEquals(first,runtime.replayThemeTeam(7L,33L,5L,10L,old[0],null,"team-once"));
        try(Connection c=ds.getConnection()) {
            assertEquals(1,scalar(c,"SELECT COUNT(*) FROM theme_team_run"));assertEquals(2,scalar(c,"SELECT COUNT(*) FROM theme_team_run_member"));
            assertEquals(2,scalar(c,"SELECT COUNT(*) FROM topic_route_session WHERE activity_id=5 AND play_instance_id='theme-first:10:5' AND status=2"));
            assertEquals(2,scalar(c,"SELECT COUNT(*) FROM topic_route_session WHERE activity_id=5 AND play_instance_id LIKE 'team-replay:%'"));
        }
    }
    @Test void teamReplayFailureAfterFirstMemberInsertRollsBackTheEntireCohort() throws Exception {
        long[] old=endedTeam();
        try(Connection c=ds.getConnection()){exec(c,"CREATE TRIGGER reject_second_team_route BEFORE INSERT ON topic_route_session FOR EACH ROW BEGIN IF NEW.member_id=8 AND NEW.play_instance_id LIKE 'team-replay:%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test second member failure'; END IF; END");}
        try {
            assertThrows(RuntimeException.class,()->runtime.replayThemeTeam(7L,33L,5L,10L,old[0],null,"team-fail"));
            try(Connection c=ds.getConnection()){assertEquals(0,scalar(c,"SELECT COUNT(*) FROM theme_team_run"));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM theme_team_run_member"));assertEquals(2,scalar(c,"SELECT COUNT(*) FROM topic_route_session WHERE activity_id=5"));}
        } finally {try(Connection c=ds.getConnection()){exec(c,"DROP TRIGGER reject_second_team_route");}}
    }
    @Test void teamReplayConcurrentRetriesRemainIdempotentUnderOuterRepeatableRead() throws Exception {
        long[] old=endedTeam();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        try {
            List<Future<Map<String,Object>>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{
                org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return tx.execute(status->{jdbc.queryForObject("SELECT COUNT(*) FROM theme_team_run",Integer.class);ready.countDown();try{if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    return runtime.replayThemeTeam(7L,33L,5L,10L,old[0],null,"team-rr");});
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();assertEquals(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
            try(Connection c=ds.getConnection()){assertEquals(1,scalar(c,"SELECT COUNT(*) FROM theme_team_run"));assertEquals(2,scalar(c,"SELECT COUNT(*) FROM theme_team_run_member"));}
        } finally {start.countDown();pool.shutdownNow();}
    }
    private void completedCurrentNode(long member,long topic,long activity,long node) throws Exception {
        String instance=runtime.playInstance(member,topic,activity);
        assertTrue(instance.startsWith("theme-first:") || instance.startsWith("replay:") || instance.startsWith("team-replay:"));
        try(Connection c=ds.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(?,?,?,?,?,1,0)")) {
            s.setLong(1,member);s.setLong(2,activity);s.setLong(3,topic);s.setLong(4,node);s.setString(5,instance);s.executeUpdate();
        }
    }
    private org.springframework.transaction.support.TransactionTemplate awardTx(int isolation) {
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(isolation);return tx;
    }
    @Test void concurrentFirstStartElectsOnePersistedIdentityAndDoesNotSeedOldDefaultFacts() throws Exception {
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0)");}
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();try{return runtime.themeView(7L,10L,0L,null);}catch(com.chengyinhub.common.exception.ServiceException conflict){assertEquals(409,conflict.getCode());return null;}}));start.countDown();
            Map<String,Object> a=futures.get(0).get(20,TimeUnit.SECONDS),b=futures.get(1).get(20,TimeUnit.SECONDS);assertTrue(a!=null || b!=null);if(a==null)a=runtime.themeView(7L,10L,0L,null);if(b==null)b=runtime.themeView(7L,10L,0L,null);assertEquals(a.get("sessionId"),b.get("sessionId"));assertEquals("theme-first:10:0",a.get("playInstanceId"));assertEquals(a.get("playInstanceId"),b.get("playInstanceId"));
            TopicRouteSession persisted=sql.getMapper(TopicRouteSessionMapper.class).selectByOwner(0L,10L,7L,"theme-first:10:0");assertTrue(com.chengyinhub.business.service.support.ThemeFirstIdentity.isFirst(persisted));assertEquals(11L,persisted.getCurrentNodeId());assertEquals(0,JSON.readTree(persisted.getStateJson()).path("completedNodeIds").size());
            try(Connection c=ds.getConnection()){assertEquals(1,scalar(c,"SELECT count(*) FROM topic_route_session"));assertEquals(1,scalar(c,"SELECT count(*) FROM player_node_progress WHERE play_instance_id='default'"));}
            assertThrows(RuntimeException.class,()->runtime.advance(7L,10L,0L,0,"old-default-not-proof","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","old")));
            assertEquals(a.get("sessionId"),runtime.themeView(7L,10L,0L,null).get("sessionId"));
        } finally {pool.shutdownNow();}
    }
    @Test void firstNamespaceCoexistsWithOldProgressAndGameRowsWithoutCollision() throws Exception {
        runtime.themeView(7L,10L,0L,null);
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0),(7,0,10,11,'theme-first:10:0',0,0)");
            exec(c,"INSERT INTO game_runtime_session(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id) VALUES(0,10,11,1,7,'default'),(0,10,11,1,7,'theme-first:10:0')");
            assertThrows(SQLException.class,()->exec(c,"INSERT INTO game_runtime_session(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id) VALUES(0,10,11,1,7,'theme-first:10:0')"));
        }
        PlayerNodeProgress p=new PlayerNodeProgress();p.setMemberId(7L);p.setActivityId(0L);p.setTopicId(10L);assertEquals(0,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressListForInstance(p,"theme-first:10:0").get(0).getStatus());assertEquals(1,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(p).get(0).getStatus());
    }
    @Test void existingLegacyDefaultSessionRemainsReadableWithoutRewritingIdentity() throws Exception {
        Map<String,Object> first=stocked();long id=((Number)first.get("sessionId")).longValue();
        // Simulate an already persisted pre-upgrade theme session, not a new default start.
        try(Connection c=ds.getConnection()){exec(c,"UPDATE topic_route_session SET play_instance_id='default',config_snapshot_json=JSON_REMOVE(config_snapshot_json,'$._serverThemeOrigin') WHERE id="+id);}
        TopicRouteSession before=sql.getMapper(TopicRouteSessionMapper.class).selectByOwner(0L,10L,7L,"default");assertEquals("default",runtime.themeView(7L,10L,0L,id).get("playInstanceId"));assertEquals("default",runtime.playInstance(7L,10L,0L));
        TopicRouteSession after=sql.getMapper(TopicRouteSessionMapper.class).selectByOwner(0L,10L,7L,"default");assertEquals(before.getConfigSnapshotJson(),after.getConfigSnapshotJson());assertEquals(before.getStateJson(),after.getStateJson());
    }
    @Test void originalAwardKeepsActivityScopeAndContradictoryReceiptFailsClosed() throws Exception {
        runtime.themeView(7L,10L,0L,null);
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,5,10,11,'default',1,0)");}
        assertTrue(awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0").isEligible()).booleanValue());
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO reward_drop_log VALUES(7,0,10,11,'topic_node')");}
        assertThrows(RuntimeException.class,()->awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0")));
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,0,11,'default',1,1)");}
        assertFalse(awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0").isEligible()).booleanValue());
    }
    @Test void originalAwardRequiresActualOuterRcAndRollsBackProgressAndSyntheticReceipt() throws Exception {
        runtime.themeView(7L,10L,0L,null);
        assertThrows(RuntimeException.class,()->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0"));
        assertThrows(RuntimeException.class,()->awardTx(Connection.TRANSACTION_REPEATABLE_READ).execute(s->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0")));
        org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        assertThrows(IllegalStateException.class,()->awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->{assertTrue(runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0").isEligible());jdbc.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'theme-first:10:0',1,0)");jdbc.update("INSERT INTO synthetic_first_award_receipt(member_id,activity_id,node_id) VALUES(7,0,11)");throw new IllegalStateException("injected downstream failure");}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM player_node_progress",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM synthetic_first_award_receipt",Integer.class));assertTrue(awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0").isEligible()).booleanValue());
    }
    @Test void concurrentOriginalAwardAttemptsHaveOneCommittedOpportunity() throws Exception {
        runtime.themeView(7L,10L,0L,null);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->{boolean eligible=runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0").isEligible();if(eligible){jdbc.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'theme-first:10:0',1,0)");jdbc.update("INSERT INTO synthetic_first_award_receipt(member_id,activity_id,node_id) VALUES(7,0,11)");}return eligible;});}));start.countDown();int winners=0;for(Future<Boolean> r:results)if(r.get(20,TimeUnit.SECONDS))winners++;assertEquals(1,winners);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM synthetic_first_award_receipt",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void firstStartWaitsForPriorDefaultAwardBarrierAndNeverReusesItsCompletion() throws Exception {
        String published;try(Connection c=ds.getConnection();Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT published_theme_runtime_config_json FROM cms_topic WHERE id=10")){assertTrue(rs.next());published=rs.getString(1);}
        try(Connection c=ds.getConnection()){exec(c,"UPDATE cms_topic SET theme_runtime_config_json=NULL,published_theme_runtime_config_json=NULL WHERE id=10");}
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch awarded=new CountDownLatch(1),release=new CountDownLatch(1);org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        try {
            Future<Boolean> old=pool.submit(()->awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->{
                assertTrue(runtime.beginOriginalNodeAward(7L,10L,0L,11L,"default").isEligible());
                jdbc.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0)");jdbc.update("INSERT INTO synthetic_first_award_receipt(member_id,activity_id,node_id) VALUES(7,0,11)");awarded.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return true;
            }));
            assertTrue(awarded.await(10,TimeUnit.SECONDS));config(published);
            Future<Map<String,Object>> started=pool.submit(()->runtime.themeView(7L,10L,0L,null));
            assertThrows(TimeoutException.class,()->started.get(200,TimeUnit.MILLISECONDS),"new FIRST cannot commit while the prior default award owns the member barrier");
            release.countDown();assertTrue(old.get(10,TimeUnit.SECONDS));assertEquals("theme-first:10:0",started.get(10,TimeUnit.SECONDS).get("playInstanceId"));
            assertEquals(11L,runtime.state(7L,10L,0L).getCurrentNodeId());assertFalse(awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(s->runtime.beginOriginalNodeAward(7L,10L,0L,11L,"theme-first:10:0").isEligible()).booleanValue());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM synthetic_first_award_receipt",Integer.class));
        } finally {release.countDown();pool.shutdownNow();}
    }
    private void gameTemplate(String config) throws Exception {
        try(Connection c=ds.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO cms_member_template(id,advanced_config_json) VALUES(20,?)")){ps.setString(1,config);ps.executeUpdate();exec(c,"UPDATE cms_topic_node SET template_id=20 WHERE id=11");}
    }
    @Test void advancedProxyColdStartUsesRcButCannotOverrideAnExistingRrTransaction() throws Exception {
        gameTemplate("{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"A\",\"weight\":1}]}}");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->advanced.start(7L,0L,10L,11L,null),"no opening flow still requires an explicit theme start");
        try(Connection c=ds.getConnection()){exec(c,"UPDATE cms_topic SET journey_story_json='{\"schemaVersion\":1,\"openingChapterId\":90}' WHERE id=10");exec(c,"INSERT INTO cms_topic_chapter(id,topic_id,name,blocks) VALUES(90,10,'Opening','[{\"type\":\"node\",\"nodeId\":11}]')");exec(c,"UPDATE cms_topic_node SET chapter_id=90 WHERE id=11");}
        assertThrows(IllegalStateException.class,()->awardTx(Connection.TRANSACTION_REPEATABLE_READ).execute(s->advanced.start(7L,0L,10L,11L,null)));
        try(Connection c=ds.getConnection()){assertEquals(0,scalar(c,"SELECT count(*) FROM topic_route_session"));assertEquals(0,scalar(c,"SELECT count(*) FROM game_runtime_session"));exec(c,"INSERT INTO game_runtime_session(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id,template_id,config_snapshot_json,state_json,status,score,completed_units,version) VALUES(0,10,11,1,7,'default',20,'{\"schemaVersion\":1}','{\"legacy\":true}',1,99,99,7)");}
        Map<String,Object> game=advanced.start(7L,0L,10L,11L,null);assertEquals("theme-first:10:0",game.get("playInstanceId"));
        GameRuntimeSession stored=sql.getMapper(GameRuntimeSessionMapper.class).selectByOwnerForInstance(0L,10L,11L,1,7L,"theme-first:10:0");assertEquals(0,stored.getStatus());assertEquals(0,stored.getScore());assertEquals(0,stored.getVersion());
        Map<String,Object> retry=advanced.start(7L,0L,10L,11L,null);assertEquals(game.get("sessionId"),retry.get("sessionId"));assertEquals(7,sql.getMapper(GameRuntimeSessionMapper.class).selectByOwner(0L,10L,11L,1,7L).getVersion());
    }
    @Test void concurrentFirstTeamGamesShareOneExactNamespaceAndFrozenConfig() throws Exception {
        gameTemplate("{\"schemaVersion\":1,\"multiplayer\":{\"enabled\":true,\"mode\":\"ROLE_BASED\",\"minPlayers\":2,\"maxPlayers\":4,\"assignment\":\"AUTO\",\"requiredTurns\":2,\"roles\":[{\"id\":\"pacer\",\"label\":\"领跑\",\"min\":1,\"max\":2},{\"id\":\"closer\",\"label\":\"收尾\",\"min\":1,\"max\":2}],\"turnOrder\":[\"pacer\",\"closer\"]}}");
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES(33,2,5,7,0)");exec(c,"INSERT INTO play_team_member(team_id,member_id,role,status) VALUES(33,7,1,1),(33,8,0,1)");exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,payment_status,ticket_id,purchase_kind,create_time) VALUES(7,2,5,10,2,2,1,0,NOW()),(8,2,5,10,2,2,1,0,NOW())");}
        runtime.themeView(7L,10L,5L,null);runtime.themeView(8L,10L,5L,null);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {Future<Map<String,Object>> one=pool.submit(()->{start.await();return advanced.start(7L,5L,10L,11L,33L);}),two=pool.submit(()->{start.await();return advanced.start(8L,5L,10L,11L,33L);});start.countDown();Map<String,Object> a=one.get(20,TimeUnit.SECONDS),b=two.get(20,TimeUnit.SECONDS);assertEquals(a.get("sessionId"),b.get("sessionId"));assertEquals("theme-first:10:5",a.get("playInstanceId"));
            GameRuntimeSession game=sql.getMapper(GameRuntimeSessionMapper.class).selectByOwnerForInstance(5L,10L,11L,2,33L,"theme-first:10:5");assertNotNull(game);assertEquals(0,game.getVersion());assertTrue(JSON.readTree(game.getConfigSnapshotJson()).has("_serverFirstTeamConfigFingerprint"));try(Connection c=ds.getConnection()){assertEquals(1,scalar(c,"SELECT count(*) FROM game_runtime_session"));assertEquals(0,scalar(c,"SELECT count(*) FROM theme_team_run"));}
        } finally {pool.shutdownNow();}
    }
    @Test void persistedNewTeamEntrantAwardBlocksSameOldOpportunityButTrueReplayDoesNot() throws Exception {
        long[] old=endedTeam();try(Connection c=ds.getConnection()){exec(c,"INSERT INTO ums_member(id) VALUES(9)");exec(c,"INSERT INTO play_team_member(team_id,member_id,role,status) VALUES(33,9,0,1)");exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,ticket_id,purchase_kind,create_time) VALUES(9,2,5,10,2,1,0,NOW())");}
        Map<String,Object> group=runtime.replayThemeTeam(7L,33L,5L,10L,old[0],null,"new-entrant");String groupInstance=runtime.playInstance(9L,10L,5L);assertTrue(groupInstance.startsWith("team-replay:"));
        org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(status->{assertTrue(runtime.beginOriginalNodeAward(9L,10L,5L,11L,groupInstance).isEligible());assertFalse(runtime.beginOriginalNodeAward(7L,10L,5L,11L,groupInstance).isEligible());jdbc.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(9,5,10,11,?,1,0),(7,5,10,11,?,1,0)",groupInstance,groupInstance);jdbc.update("INSERT INTO synthetic_first_award_receipt(member_id,activity_id,node_id) VALUES(9,5,11)");return null;});
        // Current owner topology changes; only negative old-key reward evidence may cross topics.
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO cms_topic(id,member_id,name,del_flag,route_mode,published_config_version,journey_rules_json,theme_runtime_config_json,published_theme_runtime_config_json) SELECT 11,member_id,'Moved scope',0,route_mode,published_config_version,journey_rules_json,theme_runtime_config_json,published_theme_runtime_config_json FROM cms_topic WHERE id=10");exec(c,"UPDATE cms_topic_node SET topic_id=11 WHERE id=11");}activityRow.setTopicId(11L);
        runtime.themeView(9L,11L,5L,null);runtime.themeView(7L,11L,5L,null);
        assertFalse(awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(status->runtime.beginOriginalNodeAward(9L,11L,5L,11L,"theme-first:11:5").isEligible()).booleanValue());assertTrue(awardTx(Connection.TRANSACTION_READ_COMMITTED).execute(status->runtime.beginOriginalNodeAward(7L,11L,5L,11L,"theme-first:11:5").isEligible()).booleanValue());
        assertEquals(11L,runtime.state(9L,11L,5L).getCurrentNodeId());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM synthetic_first_award_receipt",Integer.class));
    }
    private CmsMemberTemplate preferenceTemplate(boolean tag) {
        CmsMemberTemplate t=new CmsMemberTemplate();t.setId(20L);t.setValidationMethod(6);
        String config="{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}}";
        if(tag)config+=",\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,\"purpose\":\"Avoid repeated questions\",\"recipientLabel\":\"Next node\",\"revocable\":true}";
        t.setPreferenceJson(config+"}");return t;
    }
    private Map<String,Object> submitPreference(CmsMemberTemplate template) {
        com.chengyinhub.business.domain.dto.PreferenceSubmitRequest request=new com.chengyinhub.business.domain.dto.PreferenceSubmitRequest();request.setChoices(Collections.singletonMap("q","A"));
        return preference.submit(7L,10L,0L,11L,template,request,new com.chengyinhub.web.service.PreferencePlayService.CompletionFunction() {
            private com.chengyinhub.business.service.ITopicRouteRuntimeService.NodeAwardPermit prepared;
            public com.chengyinhub.business.service.ITopicRouteRuntimeService.NodeAwardPermit prepare() {
                advanced.completeGate(7L,0L,10L,11L);
                String instance=runtime.playInstance(7L,10L,0L);
                prepared=runtime.beginOriginalNodeAward(7L,10L,0L,11L,instance);return prepared;
            }
            public Map<String,Object> apply(String outcome) {
                assertNotNull(prepared);String instance=prepared.getPlayInstanceId();boolean eligible=prepared.isEligible();org.springframework.jdbc.core.JdbcTemplate jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
                jdbc.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,?,1,0)",instance);if(eligible)jdbc.update("INSERT INTO synthetic_first_award_receipt(member_id,activity_id,node_id) VALUES(7,0,11)");
                Map<String,Object> progress=new LinkedHashMap<>();progress.put("playInstanceId",instance);progress.put("completed",false);progress.put("xp",eligible?12:0);return progress;
            }
        });
    }
    @Test void preferenceConcurrentFirstSubmitsUseOneReceiptAndReplayDoesNotReceiveOriginalReward() throws Exception {
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {Future<Map<String,Object>> a=pool.submit(()->{start.await();return submitPreference(preferenceTemplate(false));}),b=pool.submit(()->{start.await();return submitPreference(preferenceTemplate(false));});start.countDown();Map<String,Object> one=a.get(20,TimeUnit.SECONDS),two=b.get(20,TimeUnit.SECONDS);assertEquals(JSON.valueToTree(one.get("progress")),JSON.valueToTree(two.get("progress")));assertNotEquals(one.get("firstTime"),two.get("firstTime"));}finally{pool.shutdownNow();}
        try(Connection c=ds.getConnection()){assertEquals(1,scalar(c,"SELECT count(*) FROM play_attempt_log"));assertEquals(1,scalar(c,"SELECT count(*) FROM player_node_progress"));assertEquals(1,scalar(c,"SELECT count(*) FROM synthetic_first_award_receipt"));}
        long session=((Number)first.get("sessionId")).longValue();runtime.themeAction(7L,session,0,"end-pref","END",null);Map<String,Object> replay=runtime.replayTheme(7L,session);Map<String,Object> fresh=submitPreference(preferenceTemplate(false));assertEquals(0,((Map<?,?>)fresh.get("progress")).get("xp"));assertEquals(replay.get("playInstanceId"),((Map<?,?>)fresh.get("progress")).get("playInstanceId"));
        try(Connection c=ds.getConnection()){assertEquals(2,scalar(c,"SELECT count(*) FROM play_attempt_log"));assertEquals(2,scalar(c,"SELECT count(*) FROM player_node_progress"));assertEquals(1,scalar(c,"SELECT count(*) FROM synthetic_first_award_receipt"));}
    }
    @Test void preferenceTagFailureRollsBackReceiptProgressAndSyntheticAwardBeforeSafeRetry() throws Exception {
        runtime.themeView(7L,10L,0L,null);org.mockito.Mockito.when(preferenceTags.upsertPending(7L,10L,"space_constraints","compact",11L)).thenThrow(new IllegalStateException("injected tag failure"));
        assertThrows(IllegalStateException.class,()->submitPreference(preferenceTemplate(true)));
        try(Connection c=ds.getConnection()){assertEquals(0,scalar(c,"SELECT count(*) FROM play_attempt_log"));assertEquals(0,scalar(c,"SELECT count(*) FROM player_node_progress"));assertEquals(0,scalar(c,"SELECT count(*) FROM synthetic_first_award_receipt"));}
        org.mockito.Mockito.reset(preferenceTags);assertEquals(12,((Map<?,?>)submitPreference(preferenceTemplate(true)).get("progress")).get("xp"));
        try(Connection c=ds.getConnection()){assertEquals(1,scalar(c,"SELECT count(*) FROM play_attempt_log"));assertEquals(1,scalar(c,"SELECT count(*) FROM player_node_progress"));assertEquals(1,scalar(c,"SELECT count(*) FROM synthetic_first_award_receipt"));}
    }
    private static int scalar(Connection c,String statement) throws SQLException {try(Statement s=c.createStatement();ResultSet rows=s.executeQuery(statement)){rows.next();return rows.getInt(1);}}
}
