package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Controller + MyBatis XML + MySQL transactions; readback uses separate connections. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamSessionWriteMybatisIntegrationTest {
    private String database;
    private boolean databaseCreated;
    private com.chengyinhub.business.service.impl.RoamBadgeDeliveryService badgeDelivery;
    private PlayerBadgeMapper badgeWriter;
    private JdbcTemplate admin, db;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager transactions;
    private RoamMapper mapper;
    private IGrowthService growth;
    private com.chengyinhub.business.service.IRewardDropService drops;
    private com.chengyinhub.business.service.ISmsCouponHistoryService coupons;
    private ApiRoamController owner, stranger;

    @BeforeAll void database() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("f13");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                || config.adminUrl().startsWith("jdbc:mysql://localhost:"), "localhost scratch only");
        database = "chengyin_f13_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        databaseCreated = true;
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        db.update("INSERT INTO ums_member(id) VALUES(77),(88)");
        transactions = new DataSourceTransactionManager(source);
        db.execute("create table consent_record(id bigint auto_increment primary key,member_id bigint not null,doc_type varchar(32) not null,doc_version varchar(32) not null,scene varchar(32) not null,scope_type varchar(16),scope_id bigint,event_type varchar(16) not null,occurred_at datetime not null,request_id varchar(64) not null unique,ip_address varchar(64),create_time datetime not null) engine=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ConsentRecordMapper.xml"),new ClassPathResource("mapper/business/RoamMapper.xml"),
                new ClassPathResource("mapper/business/RoamSessionRecordMapper.xml"),
                new ClassPathResource("mapper/business/MemberGrowthMapper.xml"),
                new ClassPathResource("mapper/business/RoamRewardDeliveryMapper.xml"),
                new ClassPathResource("mapper/business/RoamBadgeDeliveryMapper.xml"),
                new ClassPathResource("mapper/business/PlayerBadgeMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        db.execute("CREATE TABLE roam_session(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, started_at DATETIME,"
                + "finished_at DATETIME, distance_m INT, tiles_revealed INT, pois_found INT, xp_awarded INT, status INT,"
                + "recall_sent_at DATETIME, recall_attempts INT, recall_last_attempt_at DATETIME, create_time DATETIME) ENGINE=InnoDB");
        db.execute(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "sql/migration_roam_session_recovery_20260913.sql")), java.nio.charset.StandardCharsets.UTF_8));
        db.execute(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "sql/migration_20261013_roam_session_record_archive.sql")), java.nio.charset.StandardCharsets.UTF_8));
        db.execute("CREATE TABLE reward_drop_log(id BIGINT PRIMARY KEY, member_id BIGINT, topic_id BIGINT, node_id BIGINT, activity_id BIGINT,"
                + "scene_key VARCHAR(48), drop_type INT, param_json TEXT, created_at DATETIME) ENGINE=InnoDB");
        String deliveryDdl = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "sql/migration_20261013_roam_reward_delivery.sql")), java.nio.charset.StandardCharsets.UTF_8);
        for (String statement : withoutLineComments(deliveryDdl.substring(0, deliveryDdl.indexOf("INSERT INTO sys_job"))).split(";")) {
            if (statement.trim().length() > 0) db.execute(statement);
        }
        String badgeDdl = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "sql/migration_20261013_roam_badge_delivery.sql")), java.nio.charset.StandardCharsets.UTF_8);
        for (String statement : withoutLineComments(badgeDdl.substring(0, badgeDdl.indexOf("-- readback:"))).split(";")) {
            if (statement.trim().length() > 0) db.execute(statement);
        }
        db.execute("CREATE TABLE player_badge(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, badge_code VARCHAR(64),"
                + "badge_name VARCHAR(200), icon_url VARCHAR(500), source_type INT, source_id BIGINT, obtain_time DATETIME(3),"
                + "del_flag INT, create_time DATETIME(3), UNIQUE KEY(member_id,badge_code,source_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE member_growth(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT UNIQUE, level_no INT,"
                + "exp_value INT, last_active_time DATETIME, create_time DATETIME, update_time DATETIME) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_fog_tile(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, session_id BIGINT,"
                + "geohash7 VARCHAR(12), revealed_at DATETIME, UNIQUE KEY(member_id,geohash7)) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_poi_found(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, poi_id BIGINT,"
                + "session_id BIGINT, found_at DATETIME, UNIQUE KEY(member_id,poi_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_shop_visit(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, session_id BIGINT,"
                + "source_type INT, source_id BIGINT, shop_name VARCHAR(100), visited_at DATETIME,"
                + "UNIQUE KEY(session_id,source_type,source_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_poi(id BIGINT PRIMARY KEY, name VARCHAR(100), type INT, lat DECIMAL(12,7), lng DECIMAL(12,7),"
                + "radius_m INT, address VARCHAR(100), description TEXT, xp INT, coupon_template_id BIGINT, city_code VARCHAR(30),"
                + "status INT, audit_status INT, application_type VARCHAR(30), applicant_merchant_id BIGINT, audit_reason TEXT,"
                + "create_time DATETIME, merchant_id BIGINT, template_id BIGINT, node_level INT, tags TEXT, cover_img TEXT, sort INT) ENGINE=InnoDB");
<<<<<<< public
        db.update("INSERT INTO roam_poi(id,name,type,lat,lng,radius_m,xp,status) VALUES(1,'test poi',1,31,121,100,20,1)");
        db.execute("CREATE TABLE roam_poi_reward_log(id bigint primary key auto_increment,member_id bigint,poi_id bigint,coupon_id bigint,trigger_lat decimal(10,6),trigger_lng decimal(10,6),status int,create_time datetime) engine=InnoDB");
        try(java.sql.Connection connection=db.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource("sql/migration_20261012_roam_claim_fingerprints.sql"));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource("sql/migration_20261012_roam_anonymous_shop_visitors.sql"));
        }
=======
        // Exact columns used by playerLegacyTemplateReadable. This is a canonical legacy
        // source row, not a license or a substitute permission provider.
        db.execute("CREATE TABLE cms_member_template(id BIGINT PRIMARY KEY, member_id BIGINT, title VARCHAR(255),"
                + "w18_provenance_kind VARCHAR(32) NULL) ENGINE=InnoDB");
        db.execute("CREATE TABLE w18_cms_template_provenance(template_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        db.update("INSERT INTO cms_member_template(id,member_id,title,w18_provenance_kind) VALUES(501,77,'ordinary legacy source',NULL)");
        db.update("INSERT INTO roam_poi(id,name,type,lat,lng,radius_m,xp,status,template_id) VALUES(1,'test poi',1,31,121,100,20,1,501)");
>>>>>>> private-github/featu
    }

    @AfterAll void cleanup() { if (databaseCreated && database != null && admin != null) admin.execute("DROP DATABASE " + database); }

    @BeforeEach void setup() {
        db.execute("delete from consent_record");db.execute("delete from roam_shop_visitor_claim");db.execute("delete from roam_shop_visitor_total");
        db.execute("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(77,'privacy_policy','v-test','roam_location','AGREE',now(),'initial77',now()),(88,'privacy_policy','v-test','roam_location','AGREE',now(),'initial88',now())");
        db.update("DELETE FROM roam_poi_reward_log");
        db.update("DELETE FROM roam_session_record_archive");
        db.update("DELETE FROM member_growth");
        db.update("DELETE FROM roam_badge_delivery");
        db.update("DELETE FROM roam_badge_reconcile_cursor");
        db.update("DELETE FROM player_badge");
        db.update("DELETE FROM roam_reward_delivery");
        db.update("DELETE FROM roam_reward_reconcile_cursor");
        db.update("DELETE FROM reward_drop_log");
        db.update("DELETE FROM roam_fog_tile");
        db.update("DELETE FROM roam_poi_found");
        db.update("DELETE FROM roam_shop_visit");
        db.update("DELETE FROM roam_session");
        db.update("INSERT INTO roam_session(id,member_id,status,started_at) VALUES(9,77,0,now())");
        mapper = mock(RoamMapper.class, org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(RoamMapper.class)));
        growth = mock(IGrowthService.class);
        drops = mock(com.chengyinhub.business.service.IRewardDropService.class);
        com.chengyinhub.business.service.ISmsCouponHistoryService couponTarget = mock(com.chengyinhub.business.service.ISmsCouponHistoryService.class);
        when(couponTarget.grantCouponBySource(77L, 123L, "reward_drop", 7L)).thenThrow(new IllegalStateException("injected optional coupon failure"));
        org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource couponTx = new org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource();
        couponTx.addTransactionalMethod("grantCouponBySource", new org.springframework.transaction.interceptor.DefaultTransactionAttribute());
        ProxyFactory couponProxy = new ProxyFactory(couponTarget);
        couponProxy.addAdvice(new TransactionInterceptor(transactions, couponTx));
        coupons = (com.chengyinhub.business.service.ISmsCouponHistoryService) couponProxy.getProxy();
        db.update("UPDATE roam_poi SET type=1 WHERE id=1");
<<<<<<< public
        new com.chengyinhub.business.security.PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]),"test-roam-claim-hmac").register();
        badgeWriter = mock(PlayerBadgeMapper.class, org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(PlayerBadgeMapper.class)));
        badgeDelivery = new com.chengyinhub.business.service.impl.RoamBadgeDeliveryService(
                sql.getMapper(com.chengyinhub.business.mapper.RoamBadgeDeliveryMapper.class), badgeWriter, mapper, transactions);
=======
        assertEquals(1, sql.getMapper(RoamMapper.class).selectPlayerPoisByIds(java.util.Collections.singletonList(1L)).size(),
                "Every action/race fixture must begin with a real readable canonical legacy source");
>>>>>>> private-github/featu
        owner = controller(77L);
        stranger = controller(88L);
    }

    private ApiRoamController controller(Long memberId) {
        ApiRoamController target = new ApiRoamController() { @Override Long uid() { return memberId; } };
        ReflectionTestUtils.setField(target, "roamMapper", mapper);
        com.chengyinhub.common.config.ComplianceProperties properties=new com.chengyinhub.common.config.ComplianceProperties();
        properties.setDocVersions(java.util.Map.of("privacy_policy","v-test"));
        com.chengyinhub.business.service.impl.RoamLocationConsentService location = new com.chengyinhub.business.service.impl.RoamLocationConsentService(mapper,sql.getMapper(com.chengyinhub.business.mapper.ConsentRecordMapper.class),properties);
        ReflectionTestUtils.setField(target,"locationConsent",location);
        com.chengyinhub.business.service.support.RoamRecoveryService recovery = new com.chengyinhub.business.service.support.RoamRecoveryService(mapper);
        ReflectionTestUtils.setField(recovery, "badges", badgeDelivery);
        ReflectionTestUtils.setField(target, "roamRecoveryService", recovery);
        ReflectionTestUtils.setField(target, "roamBadgeDeliveryService", badgeDelivery);
        com.chengyinhub.business.service.impl.RoamSessionWriteService writer = new com.chengyinhub.business.service.impl.RoamSessionWriteService();
        ReflectionTestUtils.setField(writer, "roamMapper", mapper);
        ReflectionTestUtils.setField(writer,"locationConsent",location);
        ReflectionTestUtils.setField(writer, "recordMapper", sql.getMapper(com.chengyinhub.business.mapper.RoamSessionRecordMapper.class));
        ProxyFactory writerProxy = new ProxyFactory(writer);
        writerProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(target, "roamSessionWriteService", writerProxy.getProxy());
        ReflectionTestUtils.setField(target, "growthService", growth);
        ReflectionTestUtils.setField(target, "rewardDropService", drops);
        ReflectionTestUtils.setField(target, "smsCouponHistoryService", coupons);
        ReflectionTestUtils.setField(target, "roamRewardDeliveryService",
                new com.chengyinhub.business.service.impl.RoamRewardDeliveryService(
                        sql.getMapper(com.chengyinhub.business.mapper.RoamRewardDeliveryMapper.class), coupons, transactions, drops, growth));
        ReflectionTestUtils.setField(target, "playerBadgeMapper", badgeWriter);
        ReflectionTestUtils.setField(target, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(target, "npcFeatureFlags", mock(NpcFeatureFlags.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ApiRoamController) proxy.getProxy();
    }

    @Test void optionalCouponFailureDoesNotUndoVerifiedPoi() {
        db.update("UPDATE roam_poi SET type=2 WHERE id=1");
        java.util.Map<String, Object> drop = new java.util.HashMap<>();
        drop.put("dropType", 3);
        drop.put("couponTemplateId", 123L); drop.put("dropLogId", 7L);
        db.update("INSERT INTO reward_drop_log(id,member_id,topic_id,node_id,activity_id,scene_key,drop_type,param_json)"
                + " VALUES(7,77,0,1,9,'roam_merchant_poi',3,'{\"couponTemplateId\":123}')");
        when(drops.rollDrop(77L, 0L, 1L, 9L,
                com.chengyinhub.business.service.IRewardDropService.SCENE_ROAM_MERCHANT_POI, 20L)).thenReturn(drop);
        success(assertDoesNotThrow(() -> owner.discoverPoi("9", 1L, 31D, 121D)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_poi_found WHERE session_id=9", Integer.class));
        assertEquals("DUE", db.queryForObject("SELECT state FROM roam_reward_delivery WHERE drop_log_id=7", String.class));
    }

    @Test void finishedArchiveIsIdempotentAndPreservesIndependentSettlementReadback() throws Exception {
        success(owner.reveal("9", "wtw3sjq"));
        success(owner.finish("9", "", 120));
        db.update("UPDATE roam_session SET started_at=FROM_UNIXTIME(1), finished_at=FROM_UNIXTIME(3) WHERE id=9");
        java.util.Map<String, Object> before = db.queryForMap("SELECT * FROM roam_session WHERE id=9");
        String records = "[{\"tile\":\"wtw3sjr\",\"collectedAt\":2000}]";
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> first = workers.submit(() -> owner.archiveRecords("9", null, records));
            Future<AjaxResult> second = workers.submit(() -> owner.archiveRecords("9", null, records));
            success(first.get(10, TimeUnit.SECONDS)); success(second.get(10, TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
        assertEquals(before, db.queryForMap("SELECT * FROM roam_session WHERE id=9"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_session_record_archive WHERE session_id=9 AND member_id=77", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_fog_tile WHERE member_id=77", Integer.class));
        assertEquals("CLIENT_REPORTED", db.queryForObject("SELECT evidence_type FROM roam_session_record_archive", String.class));
        verify(growth, times(1)).addExp(77L, 1, "城市漫游");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> stranger.archiveRecords("9", null, records));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.archiveRecords("999", null, records));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.archiveRecords("9", null,
                "[{\"tile\":\"wtw3sjt\",\"collectedAt\":2000},{\"tile\":\"wtw3sjv\",\"collectedAt\":3001}]"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_session_record_archive", Integer.class));
        // 撤回定位后不再接收新的采集记录(与 master 定位门禁同口径)。
        db.update("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(77,'privacy_policy','v-test','roam_location','REVOKE',now(),'revoke77',now())");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.archiveRecords("9", null,
                "[{\"tile\":\"wtw3sjt\",\"collectedAt\":2000}]"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_session_record_archive", Integer.class));
    }

    @Test void badgeFailureKeepsSuccessfulOriginalFinishAndWorkerDoesNotAwardXpAgain() throws Exception {
        com.chengyinhub.business.service.impl.GrowthServiceImpl realGrowth = new com.chengyinhub.business.service.impl.GrowthServiceImpl();
        ReflectionTestUtils.setField(realGrowth, "memberGrowthMapper", sql.getMapper(com.chengyinhub.business.mapper.MemberGrowthMapper.class));
        doAnswer(call -> realGrowth.addExp(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(growth).addExp(anyLong(), anyInt(), anyString());
        success(owner.reveal("9", "wtw3sjq"));
        doReturn(50).when(mapper).countTilesEverByMember(77L);
        doThrow(new IllegalStateException("injected badge failure")).when(badgeWriter).insertPlayerBadge(any());
        success(owner.finish("9", "", 120)); // afterCommit failure remains a durable badge DUE.
        java.util.Map<String, Object> settled = db.queryForMap("SELECT * FROM roam_session WHERE id=9");
        assertEquals(1, db.queryForObject("SELECT status FROM roam_session WHERE id=9", Integer.class));
        assertEquals(1, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=77", Integer.class));
        assertEquals("DUE", db.queryForObject("SELECT state FROM roam_badge_delivery", String.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM player_badge", Integer.class));
        doAnswer(call -> sql.getMapper(PlayerBadgeMapper.class).insertPlayerBadge(call.getArgument(0)))
                .when(badgeWriter).insertPlayerBadge(any());
        assertTrue(badgeDelivery.deliver(77L, 9L, "ROAM_TILES_50", 50L));
        assertTrue(badgeDelivery.deliver(77L, 9L, "ROAM_TILES_50", 50L));
        rejected(owner.finish("9", "", 120));
        assertEquals(settled, db.queryForMap("SELECT * FROM roam_session WHERE id=9"));
        assertEquals(1, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=77", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM player_badge WHERE member_id=77 AND badge_code='ROAM_TILES_50'", Integer.class));
        assertEquals("DELIVERED", db.queryForObject("SELECT state FROM roam_badge_delivery", String.class));
        verify(growth, times(1)).addExp(77L, 1, "城市漫游");
    }

    @Test void originalXpSnapshotSurvivesCompletionFailureAndConcurrentWorkersAwardOnce() throws Exception {
        com.chengyinhub.business.service.impl.GrowthServiceImpl realGrowth = new com.chengyinhub.business.service.impl.GrowthServiceImpl();
        ReflectionTestUtils.setField(realGrowth, "memberGrowthMapper", sql.getMapper(com.chengyinhub.business.mapper.MemberGrowthMapper.class));
        com.chengyinhub.business.mapper.RoamRewardDeliveryMapper deliveryMapper = mock(
                com.chengyinhub.business.mapper.RoamRewardDeliveryMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(com.chengyinhub.business.mapper.RoamRewardDeliveryMapper.class)));
        com.chengyinhub.business.service.impl.RoamRewardDeliveryService delivery =
                new com.chengyinhub.business.service.impl.RoamRewardDeliveryService(deliveryMapper, coupons, transactions, drops, realGrowth);
        doAnswer(call -> {
            db.update("INSERT INTO reward_drop_log(id,member_id,topic_id,node_id,activity_id,scene_key,drop_type,param_json)"
                    + " VALUES(7,77,0,1,9,'roam_merchant_poi',1,'{\"mult\":2}')");
            java.util.Map<String, Object> drop = new java.util.HashMap<>(); drop.put("dropType", 1);
            drop.put("dropLogId", 7L); drop.put("extraXp", 20L); return drop;
        }).when(drops).rollDrop(77L, 0L, 1L, 9L, "roam_merchant_poi", 20L);
        delivery.rollOriginalDrop(77L, 1L, 9L, 20);
        assertEquals(20, db.queryForObject("SELECT extra_xp FROM roam_reward_delivery WHERE drop_log_id=7", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
        doThrow(new IllegalStateException("injected original XP completion failure"))
                .when(deliveryMapper).completeIfDue(7L, 77L, null, "DELIVERED", null);
        assertThrows(IllegalStateException.class, () -> delivery.deliverOriginal(77L, 7L));
        assertEquals("DUE", db.queryForObject("SELECT state FROM roam_reward_delivery WHERE drop_log_id=7", String.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM member_growth", Integer.class));
        doAnswer(call -> sql.getMapper(com.chengyinhub.business.mapper.RoamRewardDeliveryMapper.class)
                .completeIfDue(7L, 77L, null, "DELIVERED", null))
                .when(deliveryMapper).completeIfDue(7L, 77L, null, "DELIVERED", null);
        db.update("UPDATE roam_session SET status=1,xp_awarded=7,distance_m=120 WHERE id=9");
        java.util.Map<String, Object> settled = db.queryForMap("SELECT * FROM roam_session WHERE id=9");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> delivery.deliverOriginal(77L, 7L));
            Future<Boolean> second = workers.submit(() -> delivery.deliverOriginal(77L, 7L));
            assertTrue(first.get(10, TimeUnit.SECONDS)); assertTrue(second.get(10, TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
        assertEquals(20, db.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=77", Integer.class));
        assertEquals("DELIVERED", db.queryForObject("SELECT state FROM roam_reward_delivery WHERE drop_log_id=7", String.class));
        assertEquals(settled, db.queryForMap("SELECT * FROM roam_session WHERE id=9"));
        verify(drops, times(1)).rollDrop(77L, 0L, 1L, 9L, "roam_merchant_poi", 20L);
    }

    enum Action { TILE, POI, SHOP }

    private AjaxResult write(Action action, ApiRoamController controller, String sessionId) {
        switch (action) {
            case TILE: return controller.reveal(sessionId, "wtw3sjq");
            case POI: return controller.discoverPoi(sessionId, 1L, 31D, 121D);
            case SHOP: return controller.shopVisit(sessionId, 1, 1L, 31D, 121D);
            default: throw new AssertionError(action);
        }
    }

    private int rows(Action action) {
        String table = action == Action.TILE ? "roam_fog_tile" : action == Action.POI ? "roam_poi_found" : "roam_shop_visit";
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @ParameterizedTest @EnumSource(Action.class)
    void activeOwnerCanWriteAndRetryIsIdempotent(Action action) {
        success(write(action, owner, "9"));
        success(write(action, owner, "9"));
        assertEquals(1, rows(action));
        success(owner.finish("9", "", 12));
        assertEquals(action == Action.TILE ? 1 : 0, db.queryForObject("SELECT tiles_revealed FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.POI ? 1 : 0, db.queryForObject("SELECT pois_found FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.TILE ? 1 : action == Action.POI ? 20 : 0,
                db.queryForObject("SELECT xp_awarded FROM roam_session WHERE id=9", Integer.class));
        rejected(owner.finish("9", "", 12));
        if (action == Action.SHOP) verifyNoInteractions(growth);
        else verify(growth, times(1)).addExp(77L, action == Action.TILE ? 1 : 20, "城市漫游");
    }

    @ParameterizedTest @EnumSource(Action.class)
    void rejectsOtherOwnerMissingAndEndedWithoutRows(Action action) {
        rejected(write(action, stranger, "9"));
        rejected(write(action, owner, "999"));
        assertEquals(0, rows(action));
        success(owner.finish("9", "", 0));
        rejected(write(action, owner, "9"));
        assertEquals(0, rows(action));
    }

    @Test void concurrentBootstrapUsesOneOwnerKeyAndKeepsLegacyAndOtherOwnersIndependent() throws Exception {
        String key = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> attempt = () -> {
                start.await();
                return ((java.util.Map<?, ?>) owner.reveal("0", "wtw3sjq", key).get("data")).get("sessionId");
            };
            Future<Object> first = pool.submit(attempt), second = pool.submit(attempt);
            start.countDown();
            Object id = first.get(10, TimeUnit.SECONDS);
            assertEquals(id, second.get(10, TimeUnit.SECONDS));
            assertTrue(id instanceof String);
            success(stranger.reveal("0", "wtw3sjq", key));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM roam_session WHERE client_session_key=?", Integer.class, key));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_fog_tile WHERE member_id=77", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void lookupValidatesIdentityWithoutWritingAndKeepsLargeIdsExact() {
        db.update("INSERT INTO roam_session(id,member_id,status,client_session_key) VALUES(9007199254740993,77,0,?)",
                "cccccccccccccccccccccccccccccccc");
        java.util.Map<?, ?> fact = (java.util.Map<?, ?>) owner.session("9007199254740993", null).get("data");
        assertEquals("9007199254740993", fact.get("sessionId"));
        assertEquals("ACTIVE", fact.get("state"));
        assertEquals("NOT_FOUND", ((java.util.Map<?, ?>) stranger.session("9007199254740993", null).get("data")).get("state"));
        for (String bad : new String[]{"", "0", "-1", "1e3", "9223372036854775808"})
            assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session(bad, null));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session(null, null));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session("9", "cccccccccccccccccccccccccccccccc"));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session("999", "cccccccccccccccccccccccccccccccc"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM roam_fog_tile", Integer.class));
        verifyNoInteractions(growth);
    }

    @Test void snapshotFailureRollsBackSessionAndGrowthTogether() {
        db.execute("CREATE TABLE IF NOT EXISTS recovery_growth(member_id BIGINT, xp INT) ENGINE=InnoDB");
        db.update("DELETE FROM recovery_growth");
        doAnswer(call -> { db.update("INSERT INTO recovery_growth VALUES(?,?)", new Object[] { call.getArgument(0), call.getArgument(1) }); return null; })
                .when(growth).addExp(anyLong(), anyInt(), anyString());
        success(owner.reveal("9", "wtw3sjq"));
        doAnswer(call -> {
            sql.getMapper(RoamMapper.class).saveFinishResult(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            db.update("INSERT INTO ums_member(id) VALUES(77)"); // Actual post-growth SQL failure within the same controller transaction.
            return null;
        }).when(mapper).saveFinishResult(anyLong(), anyLong(), anyString());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> owner.finish("9", "", 12));
        assertEquals(0, db.queryForObject("SELECT status FROM roam_session WHERE id=9", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM recovery_growth", Integer.class));
        assertNull(db.queryForObject("SELECT finish_result_json FROM roam_session WHERE id=9", String.class));
    }

    @Test void finishSnapshotCanBeReadExactlyWithoutAwardingAgain() {
        success(owner.reveal("9", "wtw3sjq"));
        AjaxResult finished = owner.finish("9", "", 12);
        success(finished);
        java.util.Map<?, ?> restored = (java.util.Map<?, ?>) owner.session("9", null).get("data");
        assertEquals(Boolean.TRUE, restored.get("resultComplete"));
        assertEquals(com.alibaba.fastjson2.JSON.toJSONString(finished.get("data")),
                com.alibaba.fastjson2.JSON.toJSONString(restored.get("result")));
        rejected(owner.finish("9", "", 12));
        verify(growth, times(1)).addExp(77L, 1, "城市漫游");
    }

    @Test void exactLookupReadsFinishedFactsWithoutExecutingFinish() throws Exception {
        org.springframework.test.web.servlet.MockMvc http =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(owner).build();
        db.update("UPDATE roam_session SET status=1,xp_awarded=7 WHERE id=9");
        String response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/roam/session").param("sessionId", "9"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        assertEquals("FINISHED", json.at("/data/state").asText());
        assertTrue(json.at("/data/sessionId").isTextual());
        assertEquals(7, json.at("/data/result/totalXp").asInt());
        assertFalse(json.at("/data/resultComplete").asBoolean());
        verifyNoInteractions(growth);
    }

    @Test void sameClientKeyRecoversTheExactBootstrapSession() throws Exception {
        org.springframework.test.web.servlet.MockMvc http =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(owner).build();
        String key = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String first = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/roam/reveal").param("sessionId", "0").param("tiles", "wtw3sjq")
                .param("clientSessionKey", key)).andReturn().getResponse().getContentAsString();
        String retry = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/roam/reveal").param("sessionId", "0").param("tiles", "wtw3sjq")
                .param("clientSessionKey", key)).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(200, json.readTree(first).get("code").asInt(), first);
        assertEquals(json.readTree(first).at("/data/sessionId"), json.readTree(retry).at("/data/sessionId"),
                "lost bootstrap response must not create another session");
        assertEquals(2, db.queryForObject("SELECT count(*) FROM roam_session", Integer.class),
                "one pre-existing legacy session plus exactly one keyed session");
    }

    @Test void lazySessionUsesAuthenticatedOwnerAndRemembersTilesAcrossSessions() {
        AjaxResult result = owner.reveal(null, "wtw3sjq");
        success(result);
        Long id = ((Number) ((java.util.Map<?, ?>) result.get("data")).get("sessionId")).longValue();
        assertEquals(77L, db.queryForObject("SELECT member_id FROM roam_session WHERE id=?", Long.class, id));
        success(owner.finish(String.valueOf(id), "", 12));
        success(owner.reveal(null, "wtw3sjq"));
        assertEquals(1, tiles());
        assertEquals(1, sql.getMapper(RoamMapper.class).countTilesEverByMember(77L));
        assertEquals(1, sql.getMapper(RoamMapper.class).selectTilePageByMember(77L, 0L, 10).size());
    }

    @ParameterizedTest @EnumSource(Action.class)
    void writeWinningLockIsIncludedInFinalSettlement(Action action) throws Exception {
        race(action, false);
        assertEquals(1, rows(action));
        assertEquals(action == Action.TILE ? 1 : 0, db.queryForObject("SELECT tiles_revealed FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.POI ? 1 : 0, db.queryForObject("SELECT pois_found FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.TILE ? 1 : action == Action.POI ? 20 : 0,
                db.queryForObject("SELECT xp_awarded FROM roam_session WHERE id=9", Integer.class));
        assertEquals(1, db.queryForObject("SELECT status FROM roam_session WHERE id=9", Integer.class));
    }

    @ParameterizedTest @EnumSource(Action.class)
    void finishWinningLockRejectsLateWrite(Action action) throws Exception {
        race(action, true);
        assertEquals(0, rows(action));
        assertEquals(0, db.queryForObject("SELECT tiles_revealed+pois_found+xp_awarded FROM roam_session WHERE id=9", Integer.class));
        assertEquals(1, db.queryForObject("SELECT status FROM roam_session WHERE id=9", Integer.class));
        rejected(owner.finish("9", "", 12));
    }

    private void race(Action action, boolean finishWins) throws Exception {
        CountDownLatch locked = new CountDownLatch(1), contenderEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong winnerConnection = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong contenderConnection = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            java.sql.Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(db.getDataSource());
            assertEquals(database, connection.getCatalog());
            Long connectionId = db.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertNotNull(connectionId);
            if (Thread.currentThread().getName().equals("f13-first")) winnerConnection.set(connectionId);
            else if (Thread.currentThread().getName().equals("f13-second")) {
                contenderConnection.set(connectionId);
                contenderEntered.countDown();
            }
            // The contender's real SQL blocks here, before it can enter the session SELECT hook.
            return sql.getMapper(RoamMapper.class).lockClaimOwner(77L);
        }).when(mapper).lockClaimOwner(77L);
        doAnswer(call -> {
            boolean first = Thread.currentThread().getName().equals("f13-first");
            RoamSession session = sql.getMapper(RoamMapper.class).selectOwnedSessionForUpdate(9L, 77L);
            if (first) {
                locked.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return session;
        }).when(mapper).selectOwnedSessionForUpdate(9L, 77L);
        ExecutorService first = Executors.newSingleThreadExecutor(r -> new Thread(r, "f13-first"));
        ExecutorService second = Executors.newSingleThreadExecutor(r -> new Thread(r, "f13-second"));
        try {
            Future<AjaxResult> winner = first.submit(() -> finishWins ? owner.finish("9", "", 12) : write(action, owner, "9"));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<AjaxResult> contender = second.submit(() -> finishWins ? write(action, owner, "9") : owner.finish("9", "", 12));
            assertTrue(contenderEntered.await(10, TimeUnit.SECONDS));
            assertTrue(winnerConnection.get() > 0 && contenderConnection.get() > 0);
            assertNotEquals(winnerConnection.get(), contenderConnection.get(), "race must use different real transactions");
            boolean waited = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // DriverManagerDataSource gives the observer its own connection, never either contender's transaction.
            try (java.sql.Connection observer = db.getDataSource().getConnection()) {
                assertEquals(database, observer.getCatalog());
                try (java.sql.Statement statement = observer.createStatement();
                     java.sql.ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertTrue(result.next());
                    assertNotEquals(winnerConnection.get(), result.getLong(1));
                    assertNotEquals(contenderConnection.get(), result.getLong(1));
                }
                while (System.nanoTime() < deadline) {
                    assertFalse(winner.isDone(), "winner must keep its real owner/session locks until release");
                    assertFalse(contender.isDone(), "contender must not complete before owner lock release");
                    if (actualOwnerLockWait(observer, winnerConnection.get(), contenderConnection.get())) {
                        waited = true;
                        break;
                    }
                    // InnoDB transaction metadata refresh needs >100ms idle between reads on MySQL 8.0.45.
                    Thread.sleep(150);
                }
            }
            assertTrue(waited, "contender must be a real LOCK WAIT on owner 77 in this scratch schema");
            assertThrows(TimeoutException.class, () -> contender.get(100, TimeUnit.MILLISECONDS), "same owner writer must wait on InnoDB row lock");
            release.countDown();
            success(winner.get(10, TimeUnit.SECONDS));
            AjaxResult result = contender.get(10, TimeUnit.SECONDS);
            if (finishWins) rejected(result); else success(result);
        } finally { release.countDown(); first.shutdownNow(); second.shutdownNow(); }
    }

    private boolean actualOwnerLockWait(java.sql.Connection observer, long winnerId, long contenderId) throws java.sql.SQLException {
        String ownerSelect = "select id from ums_member where id=77 for update";
        try (java.sql.PreparedStatement statement = observer.prepareStatement(
                "select count(*) from information_schema.innodb_trx t join information_schema.processlist p "
                + "on p.id=t.trx_mysql_thread_id where p.db=? and t.trx_mysql_thread_id=? "
                + "and t.trx_state='LOCK WAIT' and t.trx_requested_lock_id is not null and lower(t.trx_query)=?")) {
            statement.setString(1, database);
            statement.setLong(2, contenderId);
            statement.setString(3, ownerSelect);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                if (result.getInt(1) != 1) return false;
            }
        }
        // The hook pauses after two real SELECT FOR UPDATEs, before any fact insert/settlement write.
        // Rows modified must not be required: at this exact point the holder owns locks without writes.
        try (java.sql.PreparedStatement statement = observer.prepareStatement(
                "select count(*) from information_schema.innodb_trx t join information_schema.processlist p "
                + "on p.id=t.trx_mysql_thread_id where p.db=? and t.trx_mysql_thread_id=? "
                + "and t.trx_state='RUNNING' and t.trx_rows_locked>0")) {
            statement.setString(1, database);
            statement.setLong(2, winnerId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                if (result.getInt(1) != 1) return false;
            }
        }
        try (java.sql.PreparedStatement statement = observer.prepareStatement(
                "select count(*) from information_schema.innodb_trx t join information_schema.processlist p "
                + "on p.id=t.trx_mysql_thread_id where p.db=? and t.trx_mysql_thread_id not in (?,?) "
                + "and (t.trx_rows_locked>0 or t.trx_rows_modified>0)")) {
            statement.setString(1, database);
            statement.setLong(2, winnerId);
            statement.setLong(3, contenderId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1) == 0;
            }
        }
    }

    private int tiles() { return db.queryForObject("SELECT count(*) FROM roam_fog_tile", Integer.class); }
    private void success(AjaxResult result) { assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString()); }
    private void rejected(AjaxResult result) { assertNotEquals(200, result.get(AjaxResult.CODE_TAG), result.toString()); }

    @Test void strangerCannotInsertTileIntoOwnersSession() {
        rejected(stranger.reveal("9", "wtw3sjq"));
        assertEquals(0, tiles());
    }

    @Test void finishedSessionRejectsNewTiles() {
        success(owner.finish("9", "", 0));
        rejected(owner.reveal("9", "wtw3sjq"));
        assertEquals(0, tiles());
        assertEquals(0, db.queryForObject("SELECT tiles_revealed FROM roam_session WHERE id=9", Integer.class));
    }

    @Test void concurrentValidatedVisitsCountOneVisitorAcrossSessionsAndTwoAcrossOwners() throws Exception {
        db.update("insert into roam_session(id,member_id,status,started_at) values(10,77,0,now()),(11,88,0,now())");
        var pool=Executors.newFixedThreadPool(3);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return owner.shopVisit("9",1,1L,31D,121D);});
            var two=pool.submit(()->{start.await();return owner.shopVisit("10",1,1L,31D,121D);});
            var other=pool.submit(()->{start.await();return stranger.shopVisit("11",1,1L,31D,121D);});
            start.countDown();success(one.get(10,TimeUnit.SECONDS));success(two.get(10,TimeUnit.SECONDS));success(other.get(10,TimeUnit.SECONDS));
            assertEquals(3,db.queryForObject("select count(*) from roam_shop_visit",Integer.class));
            assertEquals(2,db.queryForObject("select total from roam_shop_visitor_total where source_type=1 and source_id=1",Integer.class));
            assertEquals(2,db.queryForObject("select count(*) from roam_shop_visitor_claim",Integer.class));
            success(owner.shopVisit("9",1,1L,31D,121D));
            assertEquals(2,db.queryForObject("select total from roam_shop_visitor_total where source_type=1 and source_id=1",Integer.class));
            verifyNoInteractions(growth);
        } finally {pool.shutdownNow();}
    }
    @Test void revokedLocationRejectsValidatedShopAndPresenceWithoutAnyNewAnonymousOrLocationFact() {
        db.update("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(77,'privacy_policy','v-test','roam_location','REVOKE',now(),'revoke77',now())");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->owner.shopVisit("9",1,1L,31D,121D));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->owner.presence("9",31D,121D,1));
        assertEquals(0,db.queryForObject("select count(*) from roam_shop_visit",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_shop_visitor_claim",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_shop_visitor_total",Integer.class));
        verifyNoInteractions(growth);
    }

    /** 迁移文件头注释里可含「;」，按分号切分前先去掉整行 -- 注释(真实 MySQL 客户端同样忽略注释)。 */
    private static String withoutLineComments(String sql) {
        StringBuilder kept = new StringBuilder();
        for (String line : sql.split("\n")) if (!line.trim().startsWith("--")) kept.append(line).append('\n');
        return kept.toString();
    }
}
