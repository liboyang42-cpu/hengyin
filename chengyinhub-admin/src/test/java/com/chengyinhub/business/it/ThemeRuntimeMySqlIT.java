package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 真 MySQL：迁移、归属、同事务回滚、最后一件并发、重玩存档隔离。 */
class ThemeRuntimeMySqlIT {
    private static PooledDataSource ds;
    private static SqlSessionTemplate sql;
    private static ITopicRouteRuntimeService runtime;
    private static com.chengyinhub.business.service.IImService im;
    private static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1",MybatisItDatabase.host());
        ds=new PooledDataSource("com.mysql.cj.jdbc.Driver",MybatisItDatabase.url("theme_runtime"),MybatisItDatabase.user(),MybatisItDatabase.password());
        try(Connection c=ds.getConnection()) {
            ItSchema.apply(c,"it-schema/cms-topic-it-schema.sql");ItSchema.apply(c,"it-schema/journey-full-flow-it-schema.sql");
            ItSchema.applyMigrations(c,"sql/migration_20260823_topic_route_graph.sql","sql/migration_20260930_registration_first_successful_scan.sql","sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
            exec(c,"ALTER TABLE cms_topic DROP COLUMN theme_runtime_config_json, DROP COLUMN published_theme_runtime_config_json");
            exec(c,"ALTER TABLE player_node_progress DROP COLUMN play_instance_id, ADD UNIQUE KEY uk_progress(member_id,topic_id,node_id,activity_id), ADD UNIQUE KEY uk_member_activity_node(member_id,activity_id,node_id)");
            exec(c,"CREATE TABLE game_runtime_session(id bigint PRIMARY KEY AUTO_INCREMENT,activity_id bigint,topic_id bigint,node_id bigint,owner_type int,owner_id bigint,UNIQUE KEY uk_runtime_owner(activity_id,topic_id,node_id,owner_type,owner_id)) ENGINE=InnoDB");
            ItSchema.applyMigrations(c,"sql/migration_theme_runtime_20261004.sql");
            String medalMigration=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_20260902_node_medal_claim.sql")),java.nio.charset.StandardCharsets.UTF_8);
            int start=medalMigration.indexOf("CREATE TABLE IF NOT EXISTS `node_medal_claim`");
            exec(c,"DROP TABLE IF EXISTS node_medal_claim");
            exec(c,medalMigration.substring(start,medalMigration.indexOf(';',start)+1));
        }
        Configuration cfg=new Configuration();cfg.setEnvironment(new Environment("theme",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for(String mapper:Arrays.asList("CmsTopicMapper","CmsTopicNodeMapper","CmsTopicChapterMapper","CmsMemberTemplateMapper","PlayerNodeProgressMapper","CmsRegistrationMapper","TopicRouteSessionMapper","ThemeBroadcastMapper","NodeMedalClaimMapper")) {
            String resource="mapper/business/"+mapper+".xml";
            try(InputStream in=ThemeRuntimeMySqlIT.class.getClassLoader().getResourceAsStream(resource)){new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);sql=new SqlSessionTemplate(factory.getObject());
        TopicRouteRuntimeServiceImpl target=new TopicRouteRuntimeServiceImpl(sql.getMapper(TopicRouteSessionMapper.class),sql.getMapper(CmsTopicMapper.class),sql.getMapper(CmsTopicNodeMapper.class),sql.getMapper(CmsMemberTemplateMapper.class),sql.getMapper(PlayerNodeProgressMapper.class),null,sql.getMapper(CmsRegistrationMapper.class),null,null,()->123L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(target,"cmsTopicChapterMapper",sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(target,"themeNodeMedals",sql.getMapper(NodeMedalClaimMapper.class));
        im=org.mockito.Mockito.mock(com.chengyinhub.business.service.IImService.class);
        ReflectionTestUtils.setField(target,"themeBroadcastService",new com.chengyinhub.business.service.support.ThemeBroadcastService(sql.getMapper(ThemeBroadcastMapper.class),im));
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(ITopicRouteRuntimeService.class);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));runtime=(ITopicRouteRuntimeService)proxy.getProxy();
    }
    @BeforeEach void seed() throws Exception {
        org.mockito.Mockito.reset(im);
        try(Connection c=ds.getConnection()) {
            for(String table:Arrays.asList("node_medal_claim","theme_broadcast_delivery","topic_route_action","topic_route_session","player_node_progress","cms_registration","cms_topic_node","cms_member_template","cms_topic_chapter","cms_topic"))exec(c,"DELETE FROM "+table);
            String config="{\"schemaVersion\":1,\"capacity\":3,\"replayEnabled\":true,\"items\":[{\"id\":\"food\",\"kind\":\"ITEM\",\"name\":\"口粮\",\"image\":\"https://example.test/food.png\",\"stat\":\"HP\",\"delta\":2}],\"endings\":[{\"id\":\"end\",\"title\":\"结束\",\"fallback\":true,\"success\":false}]}";
            try(PreparedStatement st=c.prepareStatement("INSERT INTO cms_topic(id,member_id,name,del_flag,route_mode,published_config_version,journey_rules_json,theme_runtime_config_json,published_theme_runtime_config_json) VALUES(10,7,'测试',0,'LINEAR',1,?,?,?)")) {
                st.setString(1,"{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":6},\"recovery\":{\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0,\"stationEndIfHurt\":0}}");st.setString(2,config);st.setString(3,config);st.executeUpdate();
            }
            exec(c,"INSERT INTO cms_topic_node(id,topic_id,name,status,sort_id) VALUES(11,10,'第一站',1,1),(12,10,'第二站',1,2)");
            exec(c,"INSERT INTO cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,ticket_id,purchase_kind,create_time) VALUES(7,1,10,10,2,1,0,NOW())");
        }
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
            exec(c,"INSERT INTO game_runtime_session(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id) VALUES(0,10,11,1,7,'default'),(0,10,11,1,7,'replay:1')");
            assertThrows(SQLException.class,()->exec(c,"INSERT INTO game_runtime_session(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id) VALUES(0,10,11,1,7,'default')"));
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
    private static void exec(Connection c,String text)throws SQLException{try(Statement st=c.createStatement()){st.execute(text);}}
}
