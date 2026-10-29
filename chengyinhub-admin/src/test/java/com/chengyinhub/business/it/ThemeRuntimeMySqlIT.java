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
    private static TopicRouteRuntimeServiceImpl target;
    private static com.chengyinhub.business.service.IImService im;
    private static com.chengyinhub.business.service.IShopDayProofService arrival;
    private static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1",MybatisItDatabase.host());
        ds=new PooledDataSource("com.mysql.cj.jdbc.Driver",MybatisItDatabase.url("theme_runtime"),MybatisItDatabase.user(),MybatisItDatabase.password());
        try(Connection c=ds.getConnection()) {
            ItSchema.apply(c,"it-schema/cms-topic-it-schema.sql");ItSchema.apply(c,"it-schema/journey-full-flow-it-schema.sql");
            exec(c,"CREATE TABLE IF NOT EXISTS ums_member(id BIGINT PRIMARY KEY,nickname VARCHAR(255))");
            ItSchema.applyMigrations(c,"sql/migration_20260823_topic_route_graph.sql","sql/migration_20260930_registration_first_successful_scan.sql","sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
            exec(c,"ALTER TABLE cms_topic DROP COLUMN theme_runtime_config_json, DROP COLUMN published_theme_runtime_config_json");
            exec(c,"ALTER TABLE player_node_progress DROP COLUMN play_instance_id, ADD UNIQUE KEY uk_progress(member_id,topic_id,node_id,activity_id), ADD UNIQUE KEY uk_member_activity_node(member_id,activity_id,node_id)");
            String advancedMigration=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_20260822_advanced_game_runtime.sql")),java.nio.charset.StandardCharsets.UTF_8);int advancedCreate=advancedMigration.indexOf("CREATE TABLE IF NOT EXISTS game_runtime_session");exec(c,ItSchema.splitStatements(advancedMigration.substring(advancedCreate)).get(0));
            ItSchema.applyMigrations(c,"sql/migration_m9_play_attempt_log.sql","sql/migration_20260820_new_city_preference_chain.sql","sql/migration_20260917_play_note.sql","sql/migration_20261023_advanced_command_fingerprint.sql");
            exec(c,"CREATE TABLE play_team_member (team_id BIGINT, member_id BIGINT, status INT) ENGINE=InnoDB");
            ItSchema.applyMigrations(c,"sql/migration_theme_runtime_20261004.sql","sql/migration_play_predict_20260906.sql","sql/migration_photo_check_intent_20261023.sql");
            ItSchema.applyMigrations(c,"sql/migration_theme_state_key_20260916.sql","sql/migration_20261022_theme_state_key_attribute_def.sql");
            String medalMigration=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_20260902_node_medal_claim.sql")),java.nio.charset.StandardCharsets.UTF_8);
            int start=medalMigration.indexOf("CREATE TABLE IF NOT EXISTS `node_medal_claim`");
            exec(c,"DROP TABLE IF EXISTS node_medal_claim");
            exec(c,medalMigration.substring(start,medalMigration.indexOf(';',start)+1));
        }
        Configuration cfg=new Configuration();cfg.setEnvironment(new Environment("theme",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for(String mapper:Arrays.asList("CmsTopicMapper","CmsTopicNodeMapper","CmsTopicChapterMapper","CmsMemberTemplateMapper","PlayerNodeProgressMapper","CmsRegistrationMapper","GameRuntimeSessionMapper","PlayAttemptLogMapper","TopicRouteSessionMapper","ThemeBroadcastMapper","NodeMedalClaimMapper","PlayNoteMapper","PlayPredictMapper","PhotoCheckIntentMapper","ThemeStateKeyMapper")) {
            String resource="mapper/business/"+mapper+".xml";
            try(InputStream in=ThemeRuntimeMySqlIT.class.getClassLoader().getResourceAsStream(resource)){new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);sql=new SqlSessionTemplate(factory.getObject());
        arrival=org.mockito.Mockito.mock(com.chengyinhub.business.service.IShopDayProofService.class);
        org.mockito.Mockito.when(arrival.hasArrived(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        target=new TopicRouteRuntimeServiceImpl(sql.getMapper(TopicRouteSessionMapper.class),sql.getMapper(CmsTopicMapper.class),sql.getMapper(CmsTopicNodeMapper.class),sql.getMapper(CmsMemberTemplateMapper.class),sql.getMapper(PlayerNodeProgressMapper.class),null,sql.getMapper(CmsRegistrationMapper.class),null,arrival,()->123L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(target,"cmsTopicChapterMapper",sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(target,"themeStateKeyMapper",sql.getMapper(ThemeStateKeyMapper.class));
        ReflectionTestUtils.setField(target,"themeNodeMedals",sql.getMapper(NodeMedalClaimMapper.class));
        im=org.mockito.Mockito.mock(com.chengyinhub.business.service.IImService.class);
        ReflectionTestUtils.setField(target,"themeBroadcastService",new com.chengyinhub.business.service.support.ThemeBroadcastService(sql.getMapper(ThemeBroadcastMapper.class),im));
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(ITopicRouteRuntimeService.class);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));runtime=(ITopicRouteRuntimeService)proxy.getProxy();
    }
    @BeforeEach void seed() throws Exception {
        org.mockito.Mockito.reset(im);
        ReflectionTestUtils.setField(target,"clock",Clock.systemUTC());
        try(Connection c=ds.getConnection()) {
            for(String table:Arrays.asList("theme_state_key","photo_check_intent","play_predict_bet","play_predict_result","play_attempt_log","game_runtime_session","node_medal_claim","theme_broadcast_delivery","topic_route_action","topic_route_session","player_node_progress","cms_registration","cms_topic_node","cms_member_template","cms_topic_chapter","cms_topic"))exec(c,"DELETE FROM "+table);
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
            Map<String, Object> arrived = runtime.recordPlayerArrival(7L, topicId, 0L, nodeId, "scan", ((Number) view.get("version")).intValue());
            Map<String, Object> started = runtime.gameplayAction(7L, sessionId, ((Number) arrived.get("version")).intValue(), "start", "QUEST_START", "node_" + nodeId, null);
            Map<String, Object> done = runtime.gameplayAction(7L, sessionId, ((Number) started.get("version")).intValue(), "resolve", "QUEST_RESOLVE", "node_" + nodeId, null);
            TopicRouteSession stored = sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(sessionId, 7L);
            com.fasterxml.jackson.databind.JsonNode state = JSON.readTree(stored.getStateJson());
            assertEquals(topicId == 10 ? 5 : 6, state.path("variables").path("sys.hp").asInt());
            assertEquals(topicId == 10 ? 5 : 4, state.path("variables").path("counter.x").asInt());
            assertEquals(1, state.path("themeRuntime").path("inventory").path(topicId == 10 ? "a" : "b").asInt());
            assertEquals(0, state.path("themeRuntime").path("inventory").path(topicId == 10 ? "b" : "a").asInt());
            assertEquals(JSON.readTree(JSON.writeValueAsString(done)), JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L, sessionId, ((Number) started.get("version")).intValue(), "resolve", "QUEST_RESOLVE", "node_" + nodeId, null))));
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
        Map<String,Object> original=runtime.themeView(7L,10L,0L,null);
        return runtime.recordPlayerArrival(7L,10L,0L,11L,"scan",((Number)original.get("version")).intValue(),basicMethod!=null && basicMethod==5?"GPS":basicMethod!=null && (basicMethod==1 || basicMethod==3)?"ANSWER":"SCAN");
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
        Map<String,Object> original=runtime.themeView(7L,10L,0L,null);Map<String,Object> arrived=runtime.recordPlayerArrival(7L,10L,0L,11L,"scan-r5",((Number)original.get("version")).intValue());long id=((Number)arrived.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)arrived.get("version")).intValue(),"start-r5","QUEST_START","node_11",null);
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
        ReflectionTestUtils.setField(advanced, "transactionManager", new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
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
        Map<String,Object> recoveryContext=runtime.themeView(7L,10L,0L,id);
        runtime.recordPlayerArrival(7L,10L,0L,13L,"recovery-arrival",((Number)recoveryContext.get("version")).intValue());
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
        AdvancedPhotoMySqlFixture.install((com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl)advancedTarget, sql, ds, com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"passed"));
        Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();Map<String,Object> payload=new java.util.LinkedHashMap<>();String action;
        if("steps".equals(kit)){payload=JSON.convertValue(R5WeRunFixture.today(advancedNow.get(),7,"",100),Map.class);payload.remove("episodeId");action="SUBMIT_STEPS";}
        else if("album".equals(kit)){payload.put("imageUrls",Arrays.asList("fixture-owned-7-a","fixture-owned-7-b"));action="SUBMIT_ALBUM";}
        else{payload.put("imageUrl","fixture-owned-7-a");action="qaShot".equals(kit)?"SUBMIT_QA":"SUBMIT_PHOTO_CHECK";}
        assertEquals(true,advanced.act(7L,advancedId,((Number)opened.get("version")).intValue(),"complete",action,payload).get("readyForBase"));assertEquals(6,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);assertEquals(5,JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson()).path("variables").path("sys.hp").asInt());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CAS","RECEIPT","APPLIED","CONCURRENT"})
    void photoOriginalResultSurvivesLocalRollbackAndSameCommandAppliesOnce(String fault) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,"photoCheck");
        long routeId=((Number)initial.get("sessionId")).longValue();
        runtime.gameplayAction(7L,routeId,((Number)initial.get("version")).intValue(),"photo-route-start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();
        com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl advancedTarget=
                (com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl)((org.springframework.aop.framework.Advised)advanced).getTargetSource().getTarget();
        com.chengyinhub.business.service.ai.PhotoCheckVisionService vision=AdvancedPhotoMySqlFixture.install(advancedTarget,sql,ds,
                com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"first saved result"));
        ReflectionTestUtils.setField(advancedTarget,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()));
        Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);
        long sessionId=((Number)opened.get("sessionId")).longValue();int version=((Number)opened.get("version")).intValue();
        GameRuntimeSessionMapper realSessions=sql.getMapper(GameRuntimeSessionMapper.class);
        PlayAttemptLogMapper realAttempts=sql.getMapper(PlayAttemptLogMapper.class);
        GameRuntimeSession before=realSessions.selectAccessibleById(sessionId,7L);
        String routeBefore=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson();
        com.chengyinhub.business.service.ai.PhotoCheckIntentService actualIntents =
                (com.chengyinhub.business.service.ai.PhotoCheckIntentService)
                        ReflectionTestUtils.getField(advancedTarget, "photoCheckIntentService");
        PhotoCheckIntentMapper realPhotoRows = sql.getMapper(PhotoCheckIntentMapper.class);
        if("CAS".equals(fault)) {
            GameRuntimeSessionMapper failing=mock(GameRuntimeSessionMapper.class,org.mockito.AdditionalAnswers.delegatesTo(realSessions));
            doReturn(0).when(failing).updateStateCas(any());ReflectionTestUtils.setField(advancedTarget,"sessionMapper",failing);
        } else if ("RECEIPT".equals(fault)) {
            PlayAttemptLogMapper failing=mock(PlayAttemptLogMapper.class,org.mockito.AdditionalAnswers.delegatesTo(realAttempts));
            doThrow(new IllegalStateException("fixture receipt insert failed")).when(failing).insertPlayAttemptLog(any());
            ReflectionTestUtils.setField(advancedTarget,"attemptMapper",failing);
        }
        if ("APPLIED".equals(fault)) {
            PhotoCheckIntentMapper failing = mock(PhotoCheckIntentMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(realPhotoRows));
            doAnswer(i -> {
                assertEquals(1, realPhotoRows.markApplied(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
                throw new IllegalStateException("fixture failure after actual APPLIED update");
            }).when(failing).markApplied(anyLong(), anyString(), anyLong());
            ReflectionTestUtils.setField(actualIntents, "mapper", failing);
        }
        Map<String,Object> payload=Collections.singletonMap("imageUrl","media-asset:701");
        if ("CONCURRENT".equals(fault)) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            com.chengyinhub.business.service.ai.PhotoCheckVisionService raceVision=AdvancedPhotoMySqlFixture.install(advancedTarget,sql,ds,
                    com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(true,99,"first saved result"),()->{
                        entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                    });
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<Map<String,Object>> first=pool.submit(()->advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                Future<Throwable> second=pool.submit(()->{
                    try{advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload);return null;}
                    catch(Throwable outcome){return outcome;}
                });
                assertInstanceOf(com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException.class,second.get(5,TimeUnit.SECONDS),
                        "loser must read IN_FLIGHT without waiting on provider-held business locks or inventing fallback");
                sql.clearCache();assertEquals(before.getVersion(),realSessions.selectAccessibleById(sessionId,7L).getVersion());
                assertEquals(JSON.readTree(routeBefore),JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()));
                String originalKey=ReflectionTestUtils.invokeMethod(advancedTarget,"scopedActionKey",before,7L,"photo-fault");
                assertNull(realAttempts.selectByIdempotencyKey(originalKey));
                PhotoCheckIntentRow inFlight=sql.getMapper(PhotoCheckIntentMapper.class).selectByKey(7L,sessionId,originalKey);
                assertEquals("IN_FLIGHT",inFlight.getState());String originalNonce=inFlight.getNonce();
                release.countDown();Map<String,Object> applied=first.get(10,TimeUnit.SECONDS);
                assertEquals(JSON.valueToTree(applied),JSON.valueToTree(advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload)));
                sql.clearCache();PhotoCheckIntentRow saved=sql.getMapper(PhotoCheckIntentMapper.class).selectByKey(7L,sessionId,originalKey);
                assertEquals("APPLIED",saved.getState());assertEquals(originalNonce,saved.getNonce());
                assertEquals(realAttempts.selectByIdempotencyKey(originalKey).getId(),saved.getAttemptLogId());
                verify(raceVision,times(1)).reviewPrepared(anyLong(),anyString(),anyString(),anyString(),any());
            } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
            return;
        }
        assertThrows(com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException.class,
                ()->advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload));
        sql.clearCache();GameRuntimeSession rolledBack=realSessions.selectAccessibleById(sessionId,7L);
        assertEquals(before.getVersion(),rolledBack.getVersion());assertEquals(before.getScore(),rolledBack.getScore());
        assertEquals(JSON.readTree(before.getStateJson()),JSON.readTree(rolledBack.getStateJson()));
        assertEquals(JSON.readTree(routeBefore),JSON.readTree(sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(routeId,7L).getStateJson()));
        String scopedKey=ReflectionTestUtils.invokeMethod(advancedTarget,"scopedActionKey",before,7L,"photo-fault");
        assertNull(realAttempts.selectByIdempotencyKey(scopedKey));
        PhotoCheckIntentMapper photoRows=sql.getMapper(PhotoCheckIntentMapper.class);
        PhotoCheckIntentRow ready=photoRows.selectByKey(7L,sessionId,scopedKey);
        assertNotNull(ready);assertEquals("RESULT_READY",ready.getState());assertEquals("RECEIVED",ready.getProviderState());
        String originalNonce=ready.getNonce();assertEquals("first saved result",ready.getResultReason());
        ReflectionTestUtils.setField(actualIntents, "mapper", realPhotoRows);
        ReflectionTestUtils.setField(advancedTarget,"sessionMapper",realSessions);ReflectionTestUtils.setField(advancedTarget,"attemptMapper",realAttempts);
        Map<String,Object> applied=advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload);
        Map<String,Object> replay=advanced.act(7L,sessionId,version,"photo-fault","SUBMIT_PHOTO_CHECK",payload);
        assertEquals(JSON.valueToTree(applied),JSON.valueToTree(replay));sql.clearCache();
        PhotoCheckIntentRow saved=photoRows.selectByKey(7L,sessionId,scopedKey);
        assertEquals("APPLIED",saved.getState());assertEquals(originalNonce,saved.getNonce());
        assertEquals(realAttempts.selectByIdempotencyKey(scopedKey).getId(),saved.getAttemptLogId());
        verify(vision,times(1)).reviewPrepared(anyLong(),anyString(),anyString(),anyString(),any());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","ENDED"})
    void r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult(String outcome) throws Exception {
        Map<String,Object> initial=personalTask(false,null,"BLOCK_HP_COST",false,false,false,"qaFeedback");long id=((Number)initial.get("sessionId")).longValue();runtime.gameplayAction(7L,id,((Number)initial.get("version")).intValue(),"start","QUEST_START","north",null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced=advancedService();assertNotNull(runtime.publishedR5NodeConfig(7L,10L,11L,0L),"published R5 provenance missing");Map<String,Object> opened=advanced.start(7L,0L,10L,11L,null);long advancedId=((Number)opened.get("sessionId")).longValue();assertEquals("",JSON.valueToTree(opened).path("branch").path("currentStepId").asText());assertThrows(IllegalStateException.class,()->advanced.act(7L,advancedId,0,"early-branch","CHOOSE",Collections.singletonMap("optionId","continue")));
        Map<String,Object> judged=advanced.act(7L,advancedId,0,"judge","SUBMIT_QA",Collections.singletonMap("input","FAIL".equals(outcome)?"wrong":"correct"));if(!"FAIL".equals(outcome)){assertEquals("",JSON.valueToTree(judged).path("branch").path("currentStepId").asText());TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);runtime.gameplayAction(7L,id,row.getVersion(),"confirm","QUEST_RESOLVE","north",null);}
        TopicRouteSession row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);String receipt=JSON.readTree(row.getStateJson()).path("themeRuntime").path("lastOutcome").toString();int hp=JSON.readTree(row.getStateJson()).path("variables").path("sys.hp").asInt();
        if("ENDED".equals(outcome)){row.setStatus(TopicRouteSession.STATUS_COMPLETED);sql.getMapper(TopicRouteSessionMapper.class).updateStateCas(row);assertFalse(JSON.valueToTree(advanced.state(7L,advancedId)).path("branch").path("writable").asBoolean());assertThrows(RuntimeException.class,()->advanced.act(7L,advancedId,1,"ended-choice","CHOOSE",Collections.singletonMap("optionId","continue")));}
        else {
            int choiceExpected=((Number)judged.get("version")).intValue();
            Map<String,Object> choicePayload=Collections.singletonMap("optionId","continue");
            GameRuntimeSession originalChoice=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L);
            Object advancedTarget=((org.springframework.aop.framework.Advised)advanced).getTargetSource().getTarget();
            String choiceKey=ReflectionTestUtils.invokeMethod(advancedTarget,"scopedActionKey",originalChoice,7L,"feedback-choice");
            String choiceFingerprint=ReflectionTestUtils.invokeMethod(advancedTarget,"commandFingerprint",originalChoice,7L,choiceExpected,"feedback-choice","CHOOSE",choicePayload);
            Map<String,Object> done=advanced.act(7L,advancedId,choiceExpected,"feedback-choice","CHOOSE",choicePayload);
            assertTrue(JSON.valueToTree(done).path("branch").path("ended").asBoolean());
            PlayAttemptLog choiceReceipt=sql.getMapper(PlayAttemptLogMapper.class).selectByIdempotencyKey(choiceKey);
            assertNotNull(choiceReceipt);assertEquals(Integer.valueOf(8),choiceReceipt.getAttemptType());
            assertEquals(choiceFingerprint,choiceReceipt.getRequestFingerprint(),"receipt must bind original CHOOSE, not its feedback effects");
            Map<String,Object> replay=advanced.act(7L,advancedId,choiceExpected,"feedback-choice","CHOOSE",choicePayload);
            assertEquals(JSON.readTree(JSON.writeValueAsString(done)),JSON.readTree(JSON.writeValueAsString(replay)));
            GameRuntimeSession afterChoice=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L);
            TopicRouteSession afterFeedback=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
            assertConflict409(()->advanced.act(7L,advancedId,choiceExpected+1,"feedback-choice","CHOOSE",choicePayload));
            assertConflict409(()->advanced.act(7L,advancedId,choiceExpected,"feedback-choice","CHOOSE",Collections.singletonMap("optionId","different")));
            assertConflict409(()->advanced.act(7L,advancedId,choiceExpected,"feedback-choice","SUBMIT_QA",choicePayload));
            GameRuntimeSession afterRefusals=sql.getMapper(GameRuntimeSessionMapper.class).selectAccessibleById(advancedId,7L);
            assertEquals(afterChoice.getVersion(),afterRefusals.getVersion());assertEquals(afterChoice.getStateJson(),afterRefusals.getStateJson());
            assertEquals(afterChoice.getScore(),afterRefusals.getScore());assertEquals(afterChoice.getStatus(),afterRefusals.getStatus());
            TopicRouteSession routeAfterRefusals=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
            assertEquals(afterFeedback.getVersion(),routeAfterRefusals.getVersion());assertEquals(afterFeedback.getStateJson(),routeAfterRefusals.getStateJson());
            PlayAttemptLog unchangedReceipt=sql.getMapper(PlayAttemptLogMapper.class).selectByIdempotencyKey(choiceKey);
            assertEquals(choiceReceipt.getId(),unchangedReceipt.getId());assertEquals(choiceReceipt.getRequestFingerprint(),unchangedReceipt.getRequestFingerprint());assertEquals(choiceReceipt.getResultJson(),unchangedReceipt.getResultJson());
            assertEquals(hp+1,JSON.readTree(routeAfterRefusals.getStateJson()).path("variables").path("sys.hp").asInt());
            assertThrows(IllegalStateException.class,()->advanced.act(7L,advancedId,((Number)done.get("version")).intValue(),"new-key-terminal","CHOOSE",choicePayload));
        }
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
        int baselineExpected=row.getVersion();
        com.fasterxml.jackson.databind.node.ObjectNode base=R5WeRunFixture.today(now,7,episode,1000);
        Map<String,Object> first=runtime.syncZeroHpSteps(7L,id,baselineExpected,"steps-base",base);
        assertEquals(0,JSON.valueToTree(first).path("zeroHp").path("progress").asInt());
        assertEquals(JSON.readTree(JSON.writeValueAsString(first)),JSON.readTree(JSON.writeValueAsString(runtime.syncZeroHpSteps(7L,id,baselineExpected,"steps-base",base))));
        TopicRouteSession afterBaseline=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        TopicRouteAction baselineReceipt=sql.getMapper(TopicRouteSessionMapper.class).selectActionByAction(id,7L,"gameplay:steps-base");
        assertNotNull(baselineReceipt);assertEquals("ZERO_HP_STEPS",baselineReceipt.getOutcomeCode());
        assertConflict409(()->runtime.syncZeroHpSteps(7L,id,baselineExpected+1,"steps-base",base));
        JsonNode changedReading=R5WeRunFixture.today(now,7,episode,1050);
        assertConflict409(()->runtime.syncZeroHpSteps(7L,id,baselineExpected,"steps-base",changedReading));
        assertConflict409(()->runtime.gameplayAction(7L,id,baselineExpected,"steps-base","ZERO_HP_SYNC",episode,null));
        TopicRouteSession afterRefusals=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);
        assertEquals(afterBaseline.getVersion(),afterRefusals.getVersion());assertEquals(afterBaseline.getStateJson(),afterRefusals.getStateJson());
        TopicRouteAction unchangedBaseline=sql.getMapper(TopicRouteSessionMapper.class).selectActionByAction(id,7L,"gameplay:steps-base");
        assertEquals(baselineReceipt.getId(),unchangedBaseline.getId());assertEquals(baselineReceipt.getReceiptId(),unchangedBaseline.getReceiptId());assertEquals(baselineReceipt.getResponseJson(),unchangedBaseline.getResponseJson());
        row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);int version=row.getVersion();assertThrows(IllegalArgumentException.class,()->runtime.syncZeroHpSteps(7L,id,version,"other-identity",R5WeRunFixture.today(now,8,episode,10000)));
        runtime.syncZeroHpSteps(7L,id,row.getVersion(),"steps-progress",R5WeRunFixture.today(now,7,episode,1050));row=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L);String before=row.getStateJson();int expected=row.getVersion();
        JsonNode completeReading=R5WeRunFixture.today(now,7,episode,1100);
        try(Connection c=ds.getConnection()){exec(c,"CREATE TRIGGER fail_steps_restore BEFORE UPDATE ON topic_route_session FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='local restore rollback'");}
        try{assertThrows(RuntimeException.class,()->runtime.syncZeroHpSteps(7L,id,expected,"steps-complete",completeReading));assertEquals(before,sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson());}
        finally{try(Connection c=ds.getConnection()){exec(c,"DROP TRIGGER fail_steps_restore");}}
        runtime.syncZeroHpSteps(7L,id,expected,"steps-complete",completeReading);String restored=sql.getMapper(TopicRouteSessionMapper.class).selectAccessibleById(id,7L).getStateJson();assertEquals(4,JSON.readTree(restored).path("variables").path("sys.hp").asInt());assertEquals(1,JSON.readTree(restored).path("themeRuntime").path("zeroHp").path("used").asInt());assertFalse(restored.contains("fixture-code"));assertFalse(restored.contains("encryptedData"));assertFalse(restored.contains("fixture-player"));
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
        assertEquals(JSON.readTree(JSON.writeValueAsString(done)),JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L,id,stored.getVersion(),"r5-confirm","QUEST_RESOLVE","north",null))));
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
        Map<String,Object> arrived=runtime.recordPlayerArrival(7L,10L,0L,12L,"operation-scan",((Number)initial.get("version")).intValue());
        runtime.gameplayAction(7L,id,((Number)arrived.get("version")).intValue(),"operation-start","OPERATION_START","warm",null);
        runtime.recordNodeResult(7L,10L,0L,12L,runtime.gameplayInstance(7L,10L,0L,12L),"operation-proof",true,false,0,Collections.emptyList());return id;
    }
    @Test void personalOperationCommitsOnlyTheCurrentProofWithCostsEffectsAndReceipt() throws Exception {
        long id=readyPersonalOperation();Map<String,Object> before=runtime.themeView(7L,10L,0L,id);
        Map<String,Object> result=runtime.gameplayAction(7L,id,((Number)before.get("version")).intValue(),"operation-use","FACILITY_USE","warm",null);
        assertEquals(7,((Number)result.get("hp")).intValue());assertEquals(1,((Number)result.get("used")).intValue());
        assertNull(result.get("activeOperation"));assertThrows(RuntimeException.class,()->runtime.gameplayAction(7L,id,((Number)result.get("version")).intValue(),"operation-again","FACILITY_USE","warm",null));
        assertEquals(JSON.readTree(JSON.writeValueAsString(result)),JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L,id,((Number)before.get("version")).intValue(),"operation-use","FACILITY_USE","warm",null))));
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
        assertEquals(JSON.readTree(JSON.writeValueAsString(done)),JSON.readTree(JSON.writeValueAsString(runtime.gameplayAction(7L,id,((Number)ready.get("version")).intValue(),"done","QUEST_RESOLVE","north",null))));
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
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0)");}
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
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0)");}
        runtime.advance(7L,10L,0L,0,"verified11","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11"));
        Map<String,Object> midpoint=runtime.themeView(7L,10L,0L,id);assertEquals(1,((List)midpoint.get("contents")).size());
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,12,'default',1,0)");}
        runtime.advance(7L,10L,0L,1,"verified12","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(12L,"COMPLETED","proof12"));
        Map<String,Object> card=runtime.themeCard(7L,id);assertEquals(true,card.get("success"));assertEquals(1,((List)card.get("medals")).size());assertFalse(card.containsKey("sessionId"));
        config("{\"schemaVersion\":1,\"capacity\":2,\"replayEnabled\":true}");assertEquals(JSON.valueToTree(card),JSON.valueToTree(runtime.themeCard(7L,id)));
        Map<String,Object> replay=runtime.replayTheme(7L,id);assertEquals("ACTIVE",replay.get("status"));assertEquals(0,((List)replay.get("contents")).size());
        PlayerNodeProgress probe=new PlayerNodeProgress();probe.setMemberId(7L);probe.setTopicId(10L);probe.setActivityId(0L);assertEquals(0,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(probe).size());
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
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0),(7,0,10,12,'default',1,0)");}
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
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,'default',1,0)");
            exec(c,"INSERT INTO node_medal_claim(node_id,medal_rank,member_id,activity_id,topic_id,progress_id) SELECT 11,1,7,0,10,id FROM player_node_progress WHERE member_id=7 AND node_id=11");
        }
        runtime.advance(7L,10L,0L,0,"rank_node","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","proof11"));
        assertEquals(1,((List)runtime.themeView(7L,10L,0L,id).get("contents")).size());
        try(Connection c=ds.getConnection()){exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,12,'default',1,0)");}
        runtime.advance(7L,10L,0L,1,"finish_node","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(12L,"COMPLETED","proof12"));
        assertEquals(2,((List)runtime.themeCard(7L,id).get("medals")).size());
        Map<String,Object> fresh=runtime.replayTheme(7L,id);String instance=(String)fresh.get("playInstanceId");
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,del_flag) VALUES(7,0,10,11,?,1,0)")){st.setString(1,instance);st.executeUpdate();}
        runtime.advance(7L,10L,0L,0,"rank_again","CHOICE",new com.chengyinhub.business.domain.route.NodeOutcome(11L,"COMPLETED","freshproof"));
        assertEquals(0,((List)runtime.themeView(7L,10L,0L,((Number)fresh.get("sessionId")).longValue()).get("contents")).size());
        assertEquals(2,((List)runtime.themeCard(7L,id).get("medals")).size());
    }
    private static void assertConflict409(org.junit.jupiter.api.function.Executable action) {
        assertEquals(Integer.valueOf(409),assertThrows(com.chengyinhub.common.exception.ServiceException.class,action).getCode());
    }
    private static void exec(Connection c,String text)throws SQLException{try(Statement st=c.createStatement()){st.execute(text);}}
    // G WP05 negative-only additions: production unchanged; not executed here.
    @Test void replayArrivalMustBeReadableFromTheCurrentPlayInstance() throws Exception {
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);
        long oldId=((Number)first.get("sessionId")).longValue();
        runtime.themeAction(7L,oldId,((Number)first.get("version")).intValue(),"g-end","END",null);
        Map<String,Object> replay=runtime.replayTheme(7L,oldId);
        String expected=(String)replay.get("playInstanceId");
        assertNotEquals("default",expected);
        com.chengyinhub.business.service.impl.ShopDayProofServiceImpl realArrival=new com.chengyinhub.business.service.impl.ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(realArrival,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(realArrival,"playerNodeProgressService",arrivalProgressOwner());
        com.chengyinhub.business.domain.CmsTopicNode node=sql.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeById(11L);
        realArrival.markArrived(7L,0L,node);
        com.chengyinhub.business.domain.PlayerNodeProgress probe=new com.chengyinhub.business.domain.PlayerNodeProgress();
        probe.setMemberId(7L);probe.setActivityId(0L);probe.setTopicId(10L);probe.setNodeId(11L);
        List<com.chengyinhub.business.domain.PlayerNodeProgress> visible=sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(probe);
        assertEquals(1,visible.size(),"a newly accepted arrival must be visible in this same current replay");
        assertEquals(expected,visible.get(0).getPlayInstanceId());
        assertEquals(0,visible.get(0).getStatus(),"arrival is not node completion or reward eligibility");
    }
    @Test void replayArrivalMustNotCollideWithTheOldDefaultArrival() throws Exception {
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);
        long oldId=((Number)first.get("sessionId")).longValue();
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,checkin_time,del_flag) VALUES(7,0,10,11,'default',0,'2026-01-01 12:00:00',0)");
        }
        runtime.themeAction(7L,oldId,((Number)first.get("version")).intValue(),"g-end-old-arrival","END",null);
        Map<String,Object> replay=runtime.replayTheme(7L,oldId);
        com.chengyinhub.business.service.impl.ShopDayProofServiceImpl realArrival=new com.chengyinhub.business.service.impl.ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(realArrival,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(realArrival,"playerNodeProgressService",arrivalProgressOwner());
        com.chengyinhub.business.domain.CmsTopicNode node=sql.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeById(11L);
        assertDoesNotThrow(()->realArrival.markArrived(7L,0L,node),"old-run arrival must not make the replay action fail");
        com.chengyinhub.business.domain.PlayerNodeProgress probe=new com.chengyinhub.business.domain.PlayerNodeProgress();
        probe.setMemberId(7L);probe.setActivityId(0L);probe.setTopicId(10L);probe.setNodeId(11L);probe.setPlayInstanceId((String)replay.get("playInstanceId"));
        assertEquals(1,sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(probe).size());
        probe.setPlayInstanceId("default");
        List<com.chengyinhub.business.domain.PlayerNodeProgress> old=sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(probe);
        assertEquals(1,old.size());assertEquals(0,old.get(0).getStatus());
    }


    @Test void repeatedArrivalPreservesAllStoredColumnsAndPhotoWithinOneReplay() throws Exception {
        Map<String,Object> replay=arrivalReplay();
        String instance=(String)replay.get("playInstanceId");
        com.chengyinhub.business.service.impl.ShopDayProofServiceImpl realArrival=arrivalService();
        CmsTopicNode node=sql.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeById(11L);
        PlayerNodeProgress first=realArrival.markArrived(7L,0L,node);
        realArrival.submitPhoto(7L,0L,11L,"https://img/same-run-proof.jpg");
        List<List<String>> before=arrivalStoredRows(instance,null);

        PlayerNodeProgress again=realArrival.markArrived(7L,0L,node);

        assertEquals(first.getId(),again.getId());
        assertEquals(instance,again.getPlayInstanceId());
        assertEquals(Integer.valueOf(0),again.getStatus());
        assertEquals("https://img/same-run-proof.jpg",again.getPicUrl());
        assertEquals(1,before.size());
        assertEquals(before,arrivalStoredRows(instance,null),"repeat arrival must not change any stored column");
        assertTrue(arrivalStoredRows("default",null).isEmpty());
    }

    @Test void replaySiblingArrivalSharesOneInstanceWithoutMovingOldPhotosOrStatuses() throws Exception {
        configureArrivalSiblings();
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,checkin_time,pic_url,review_status,puzzle_score,completion_mode,del_flag,create_time) VALUES(7,0,10,11,'default',1,'2026-01-01 12:00:00','https://img/old-main.jpg',1,80,'SOLVED',0,'2026-01-01 12:00:00'),(7,0,10,12,'default',0,'2026-01-01 12:00:00','https://img/old-sibling.jpg',0,NULL,NULL,0,'2026-01-01 12:00:00')");
        }
        List<List<String>> old=arrivalStoredRows("default",null);
        Map<String,Object> replay=arrivalReplay();String instance=(String)replay.get("playInstanceId");
        com.chengyinhub.business.service.impl.ShopDayProofServiceImpl realArrival=arrivalService();
        CmsTopicNode node=sql.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeById(11L);

        realArrival.markArrived(7L,0L,node);

        PlayerNodeProgress probe=new PlayerNodeProgress();probe.setMemberId(7L);probe.setActivityId(0L);
        probe.setTopicId(10L);probe.setPlayInstanceId(instance);
        List<PlayerNodeProgress> current=sql.getMapper(PlayerNodeProgressMapper.class).selectPlayerNodeProgressList(probe);
        assertEquals(2,current.size());
        Set<Long> nodes=new HashSet<>();
        for(PlayerNodeProgress row:current) {
            nodes.add(row.getNodeId());assertEquals(instance,row.getPlayInstanceId());
            assertEquals(Integer.valueOf(0),row.getStatus());assertNull(row.getPicUrl());
            assertNotNull(row.getCheckinTime());assertEquals(current.get(0).getCheckinTime(),row.getCheckinTime());
        }
        assertEquals(new HashSet<>(Arrays.asList(11L,12L)),nodes);
        assertEquals(old,arrivalStoredRows("default",null),"every old column, completion and photo must remain unchanged");
        List<List<String>> once=arrivalStoredRows(instance,null);
        realArrival.markArrived(7L,0L,node);
        assertEquals(once,arrivalStoredRows(instance,null));
        assertEquals(old,arrivalStoredRows("default",null));
    }

    @Test void anExistingSameRunSiblingKeepsItsCompletionAndPhoto() throws Exception {
        configureArrivalSiblings();
        Map<String,Object> replay=arrivalReplay();String instance=(String)replay.get("playInstanceId");
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,checkin_time,pic_url,review_status,del_flag,create_time) VALUES(7,0,10,12,?,1,'2026-01-01 12:00:00','https://img/current-sibling.jpg',1,0,'2026-01-01 12:00:00')")) {
            st.setString(1,instance);assertEquals(1,st.executeUpdate());
        }
        List<List<String>> sibling=arrivalStoredRows(instance,12L);
        com.chengyinhub.business.service.impl.ShopDayProofServiceImpl realArrival=arrivalService();

        PlayerNodeProgress main=realArrival.markArrived(7L,0L,sql.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeById(11L));

        assertEquals(instance,main.getPlayInstanceId());assertEquals(Integer.valueOf(0),main.getStatus());assertNull(main.getPicUrl());
        assertEquals(1,sibling.size());assertEquals(sibling,arrivalStoredRows(instance,12L));
        assertEquals(2,arrivalStoredRows(instance,null).size());
        assertTrue(arrivalStoredRows("default",null).isEmpty());
    }

    private com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl arrivalProgressOwner() {
        com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl owner=new com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(owner,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(owner,"routeSessions",sql.getMapper(TopicRouteSessionMapper.class));
        return owner;
    }

    private com.chengyinhub.business.service.impl.ShopDayProofServiceImpl arrivalService() {
        com.chengyinhub.business.service.impl.ShopDayProofServiceImpl service=new com.chengyinhub.business.service.impl.ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(service,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(service,"playerNodeProgressService",arrivalProgressOwner());
        ReflectionTestUtils.setField(service,"cmsTopicNodeMapper",sql.getMapper(CmsTopicNodeMapper.class));
        return service;
    }

    private Map<String,Object> arrivalReplay() throws Exception {
        Map<String,Object> first=runtime.themeView(7L,10L,0L,null);
        long oldId=((Number)first.get("sessionId")).longValue();
        runtime.themeAction(7L,oldId,((Number)first.get("version")).intValue(),"arrival-test-end","END",null);
        Map<String,Object> replay=runtime.replayTheme(7L,oldId);
        assertNotNull(replay.get("playInstanceId"));assertNotEquals("default",replay.get("playInstanceId"));
        return replay;
    }

    private void configureArrivalSiblings() throws Exception {
        // Existing host-node tables: one real chapter, same address/coordinates; no merchant evidence fabricated.
        try(Connection c=ds.getConnection()) {
            exec(c,"INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id) VALUES(100,10,'arrival-chapter',1)");
            exec(c,"UPDATE cms_topic_node SET chapter_id=100,address='same-place',latitude='31.200000',longitude='121.400000' WHERE topic_id=10 AND id IN (11,12)");
        }
    }

    private List<List<String>> arrivalStoredRows(String instance,Long nodeId) throws Exception {
        List<List<String>> rows=new ArrayList<>();
        String query="SELECT * FROM player_node_progress WHERE member_id=7 AND activity_id=0 AND topic_id=10 AND play_instance_id=?"+(nodeId==null?"":" AND node_id=?")+" ORDER BY id";
        try(Connection c=ds.getConnection();PreparedStatement st=c.prepareStatement(query)) {
            st.setString(1,instance);if(nodeId!=null)st.setLong(2,nodeId);
            try(ResultSet rs=st.executeQuery()) {
                int columns=rs.getMetaData().getColumnCount();
                while(rs.next()) {
                    List<String> row=new ArrayList<>();
                    for(int column=1;column<=columns;column++)row.add(rs.getString(column));
                    rows.add(row);
                }
            }
        }
        return rows;
    }
}
