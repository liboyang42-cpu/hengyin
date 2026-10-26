package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.web.service.PreferencePlayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.chengyinhub.business.service.impl.CmsPointsSettingServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberPointsDetailServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.concurrent.atomic.AtomicInteger;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W09-B1: real MySQL missing-table admission failure and rollback of an already-inserted action claim.
 * Reuses public canonical DDL and actual mapper/service SQL; node/template/topic catalogue reads are doubles. */
@Timeout(45)
class PlayLeadAdmissionFailureMySqlIT {
    private static final long MEMBER = 7, TOPIC = 300, ACTIVITY = 500, NODE = 401;
    private static final int COST = 10;
    private static PooledDataSource ds;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactions;
    private static String ownedSchema;
    private UmsMemberServiceImpl members;
    private UmsMemberPointsDetailServiceImpl ledger;
    private final AtomicInteger claims = new AtomicInteger();
    private GameSessionRuntimeMapper observedReceipts;

    @BeforeAll static void schema() throws Exception {
        for (String name : new String[]{"HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX"}) {
            String value = System.getenv("CHENGYIN_MYBATIS_IT_" + name);
            assertNotNull(value, "Explicit isolated MySQL variable required: " + name);
            if (!"PASSWORD".equals(name)) assertFalse(value.isEmpty(), name);
        }
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        int port = Integer.parseInt(MybatisItDatabase.port()); assertTrue(port > 0 && port <= 65535);
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("_[a-z0-9_]{1,20}"));
        String url = MybatisItDatabase.url("c_lead_admission_failure");
        ownedSchema = MybatisItDatabase.databaseName("c_lead_admission_failure");
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection connection = ds.getConnection()) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
            // Reuse the public canonical DDL verbatim, including the production uniqueness indexes.
            Path baseline = Path.of("..", "sql", "base_schema_20260818.sql");
            if (!Files.isRegularFile(baseline)) baseline = Path.of("sql", "base_schema_20260818.sql");
            String ddl = Files.readString(baseline);
            try (Statement statement = connection.createStatement()) {
                for (String table : List.of("ums_member_points_detail", "cms_points_setting", "player_node_progress")) {
                    java.util.regex.Matcher match = Pattern.compile("(?s)CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(ddl);
                    assertTrue(match.find(), "Missing canonical DDL: " + table);
                    statement.execute(match.group()); assertFalse(match.find(), "Ambiguous canonical DDL: " + table);
                }
            }
            // Only the two canonical route tables are needed: topic/node/template catalogue reads remain explicit doubles.
            Path routeDdl = Path.of("sql", "migration_20260823_topic_route_graph.sql");
            String routeSchema = Files.readString(routeDdl);
            try (Statement statement = connection.createStatement()) {
                for (String table : List.of("topic_route_session", "topic_route_action")) {
                    java.util.regex.Matcher match = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + " \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(routeSchema);
                    assertTrue(match.find(), "Missing canonical route DDL: " + table);
                    statement.execute(match.group()); assertFalse(match.find(), "Ambiguous canonical route DDL: " + table);
                }
            }
            ItSchema.applyMigrations(connection, "sql/migration_20260930_registration_first_successful_scan.sql", "sql/migration_club_lead.sql", "sql/migration_20260823_game_session_runtime.sql");
        }
        Configuration config = new Configuration(new Environment("c-lead-admission-failure", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : List.of("CmsRegistrationMapper", "CmsActivityMapper", "ClubLeadMapper", "CmsPointsSettingMapper",
                "UmsMemberMapper", "UmsMemberPointsDetailMapper", "GameSessionRuntimeMapper", "TopicRouteSessionMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream input = PlayLeadAdmissionFailureMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject()); transactions = new DataSourceTransactionManager(ds); jdbc = new JdbcTemplate(ds);
        assertEquals("0:member_id,event_type,event_id", jdbc.queryForObject("SELECT CONCAT(MIN(non_unique),':',"
                + "GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                + " AND table_name='ums_member_points_detail' AND index_name='uk_member_event_once'", String.class));
    }

    @AfterAll static void cleanupOwnedSchema() throws Exception {
        if (ds != null) ds.forceCloseAll();
        if (ownedSchema == null) return;
        assertTrue(ownedSchema.matches("chengyin_mybatis_c_lead_admission_failure_[a-z0-9_]{1,20}"));
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true", MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + ownedSchema + "`");
        }
    }

    @BeforeEach void seedOnlyOwnedSchema() {
        for (String table : List.of("topic_route_action", "topic_route_session", "game_command_receipt", "player_node_progress", "ums_member_points_detail", "cms_points_setting", "cms_registration", "club_lead_state", "cms_activity", "cms_topic", "ums_member")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO ums_member(id,point) VALUES(7,100),(8,100)");
        jdbc.update("INSERT INTO cms_topic(id,name,product_type,status,user_status,del_flag) VALUES(300,'hint fixture',2,1,1,0)");
        jdbc.update("INSERT INTO cms_activity(id,topic_id,start_date,end_date,del_flag) VALUES(500,300,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),0)");
        jdbc.update("INSERT INTO cms_points_setting(id,title,value,type,event_type,status,del_flag) VALUES(14,'hint',10,1,14,1,0)");
        for (long[] scope : new long[][]{{1,7,1,300,2},{2,7,2,500,1},{3,8,1,300,2}}) {
            jdbc.update("INSERT INTO cms_registration(id,registration_no,member_id,owner_type,owner_id,purchase_kind,topic_id,"
                    + "ticket_id,ticket_price,original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time,expires_at)"
                    + " VALUES(?,?,?,?,?,?,300,1,10,10,10,10,2,2,DATE_ADD(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY))",
                    scope[0], "hint-" + scope[0], scope[1], scope[2], scope[3], scope[4]);
        }
        members = new UmsMemberServiceImpl(); ReflectionTestUtils.setField(members, "umsMemberMapper", sql.getMapper(UmsMemberMapper.class));
        ledger = new UmsMemberPointsDetailServiceImpl(); ReflectionTestUtils.setField(ledger, "umsMemberPointsDetailMapper", sql.getMapper(UmsMemberPointsDetailMapper.class));
        claims.set(0);
    }

    private ApiPlayProgressController controller(long actor, IUmsMemberService memberService, IUmsMemberPointsDetailService details) {
        ApiPlayProgressController target = new ApiPlayProgressController() {
            @Override public Long getAppUserId() {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "identity extraction is outside the application transaction");
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return actor;
            }
        };
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode(); node.setId(NODE); node.setTopicId(TOPIC); node.setTemplateId(39L); node.setSortId(1L); node.setStatus(1L);
        when(nodes.selectCmsTopicNodeById(NODE)).thenReturn(node);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(39L); template.setHint1("hint-one"); template.setHint2("hint-two");
        when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC); topic.setProductType(2);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsPointsSettingServiceImpl settings = new CmsPointsSettingServiceImpl(); ReflectionTestUtils.setField(settings, "cmsPointsSettingMapper", sql.getMapper(CmsPointsSettingMapper.class));
        CmsActivityServiceImpl activities = new CmsActivityServiceImpl(); ReflectionTestUtils.setField(activities, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "cmsTopicNodeService", nodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "cmsMemberTemplateService", templates);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "cmsTopicService", topics);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "cmsActivityService", activities);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "currentPlayActivityMapper", sql.getMapper(CmsActivityMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "clubLeadMapper", sql.getMapper(ClubLeadMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "umsMemberPointsDetailService", details);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(i -> sql.getMapper(CmsRegistrationMapper.class).selectCmsRegistrationList(i.getArgument(0)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "cmsRegistrationService", registrations);
        GameSessionRuntimeMapper receipts = sql.getMapper(GameSessionRuntimeMapper.class);
        observedReceipts = spy(receipts);
        doAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            int inserted = receipts.insertReceiptClaim(i.getArgument(0));
            assertEquals(1, inserted); claims.incrementAndGet(); return inserted;
        }).when(observedReceipts).insertReceiptClaim(any());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "playCommandReceiptMapper", observedReceipts);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(target, "playCommandRouteMapper", mock(TopicRouteSessionMapper.class));
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.wire(target,settings,memberService,details,transactions);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.installTransactions(target, transactions);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ApiPlayProgressController) proxy.getProxy();
    }
    private ApiPlayProgressController controller(long actor) { return controller(actor, members, ledger); }
    private ApiPlayProgressController hintController(long actor) {
        ApiPlayProgressController controller=controller(actor,members,ledger);
        ICmsTopicNodeService nodes=(ICmsTopicNodeService)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"cmsTopicNodeService");
        CmsTopicNode node=nodes.selectCmsTopicNodeById(NODE);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return node;
        }).when(nodes).selectCmsTopicNodeById(NODE);
        return controller;
    }

    @Test void preferenceLeadStorageFaultCannotCreateBranchSessionBeforeEitherHttpAdmission() throws Exception {
        assertPreferenceAdmissionBeforeRoute(true);
    }

    @Test void preferenceLeadStorageFaultCannotCreateJourneySessionBeforeEitherHttpAdmission() throws Exception {
        assertPreferenceAdmissionBeforeRoute(false);
    }

    private void assertPreferenceAdmissionBeforeRoute(boolean branch) throws Exception {
        jdbc.update("UPDATE cms_activity SET status=1 WHERE id=500");
        jdbc.update("UPDATE cms_topic SET product_type=1 WHERE id=300");
        ApiPlayProgressController controller = controller(MEMBER, members, ledger);
        ICmsTopicNodeService nodeService = (ICmsTopicNodeService) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "cmsTopicNodeService");
        CmsTopicNode first = nodeService.selectCmsTopicNodeById(NODE);
        CmsTopicNode last = new CmsTopicNode(); last.setId(402L); last.setTopicId(TOPIC); last.setSortId(2L); last.setStatus(1L);
        ICmsTopicService topics = (ICmsTopicService) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "cmsTopicService");
        CmsTopic topic = topics.selectCmsTopicById(TOPIC); topic.setProductType(1); topic.setPublishedConfigVersion(1L);
        if (branch) topic.setPublishedRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":401,\"terminalNodeIds\":[402],"
                + "\"edges\":[{\"id\":\"next\",\"fromNodeId\":401,\"toNodeId\":402,"
                + "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        else topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true}");
        ICmsMemberTemplateService templates = (ICmsMemberTemplateService) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "cmsMemberTemplateService");
        CmsMemberTemplate template = templates.selectCmsMemberTemplateById(39L);
        template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class); when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(first);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(List.of(first, last));
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(List.of(template));
        PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
        when(progressMapper.selectPlayerNodeProgressList(any())).thenReturn(List.of());
        TopicRouteRuntimeServiceImpl routeTarget = spy(new TopicRouteRuntimeServiceImpl(
                sql.getMapper(TopicRouteSessionMapper.class), topicMapper, nodeMapper, templateMapper, progressMapper,
                sql.getMapper(CmsActivityMapper.class), sql.getMapper(CmsRegistrationMapper.class),
                mock(PlayerActivityRoleMapper.class), () -> 123L, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN));
        ReflectionTestUtils.setField(routeTarget, "cmsTopicChapterMapper", mock(CmsTopicChapterMapper.class));
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return call.callRealMethod(); // Actual canPlay, session creation, canonical SQL and commit.
        }).when(routeTarget).canPlay(anyLong(), anyLong(), anyLong(), anyLong(), nullable(String.class));
        ProxyFactory routeProxy = new ProxyFactory(routeTarget); routeProxy.setInterfaces(ITopicRouteRuntimeService.class);
        routeProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routeProxy.getProxy());
        PreferencePlayService preferences = mock(PreferencePlayService.class);
        when(preferences.load(eq(MEMBER), eq(TOPIC), eq(NODE), any())).thenReturn(Map.of("question", "original-preference"));
        when(preferences.submit(eq(MEMBER), eq(TOPIC), eq(ACTIVITY), eq(NODE), any(), any(), any()))
                .thenReturn(Map.of("needsTiebreak", false));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "preferencePlayService", preferences);
        PreferenceSubmitRequest command = new PreferenceSubmitRequest(); command.setActivityId("500");
        command.setRouteActionId("preference-original"); command.setExpectedRouteVersion(0); command.setChoices(Map.of("one", "A"));
        Map<String,Object> before = vector();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        jdbc.execute("RENAME TABLE club_lead_state TO c_hidden_lead_state");
        try {
            unavailable(() -> controller.preference(NODE, "500", null));
            unavailable(() -> controller.submitPreference(NODE, command));
            verifyNoInteractions(routeTarget, preferences);
            assertEquals(before, vector()); assertEquals(0, claims.get());
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM topic_route_session", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM topic_route_action", Long.class));
        } finally { jdbc.execute("RENAME TABLE c_hidden_lead_state TO club_lead_state"); }
        // No lead row after recovery is legal. The same real route now commits a session, proving the former side effect is reachable.
        AjaxResult read = controller.preference(NODE, "500", null);
        assertEquals(200, read.get("code")); assertEquals(Map.of("question", "original-preference"), read.get("data"));
        assertEquals(200, controller.submitPreference(NODE, command).get("code"));
        verify(routeTarget).canPlay(MEMBER, TOPIC, ACTIVITY, NODE, null);
        verify(routeTarget).canPlay(MEMBER, TOPIC, ACTIVITY, NODE, "preference-original");
        verify(preferences).submit(eq(MEMBER), eq(TOPIC), eq(ACTIVITY), eq(NODE), any(), same(command), any());
        assertEquals("preference-original", command.getRouteActionId()); assertEquals(Integer.valueOf(0), command.getExpectedRouteVersion());
        assertEquals(Map.of("one", "A"), command.getChoices());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM topic_route_session", Long.class));
        assertEquals("500:300:7:default:0", jdbc.queryForObject("SELECT CONCAT(activity_id,':',topic_id,':',member_id,':',play_instance_id,':',version) FROM topic_route_session", String.class));
        String snapshot = jdbc.queryForObject("SELECT config_snapshot_json FROM topic_route_session", String.class);
        assertEquals(branch, new ObjectMapper().readTree(snapshot).has("edges"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM topic_route_action", Long.class));
        assertEquals(before, vector(), "route admission alone must not complete preference, debit or grant rewards");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "GET/POST must not gain an outer transaction");
    }

    private Map<String,Object> vector() {
        return Map.of("points", jdbc.queryForObject("SELECT point FROM ums_member WHERE id=7", Long.class),
                "flows", jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Long.class),
                "progress", jdbc.queryForObject("SELECT COUNT(*) FROM player_node_progress", Long.class),
                "receipts", jdbc.queryForObject("SELECT COUNT(*) FROM game_command_receipt", Long.class),
                "registration", jdbc.queryForList("SELECT id,member_id,owner_type,owner_id,payment_status,registration_status,first_successful_scan_at FROM cms_registration ORDER BY id"));
    }
    private void unavailable(org.junit.jupiter.api.function.Executable operation) {
        ServiceException failure = assertThrowsExactly(ServiceException.class, operation);
        assertEquals(Integer.valueOf(503), failure.getCode()); assertEquals("带领状态暂时不可用，请稍后重试", failure.getMessage());
        assertEquals(Map.of("outcome", "NOT_RUN"), failure.getData()); assertNull(failure.getCause()); assertNull(failure.getDetailMessage());
        AjaxResult response = new GlobalExceptionHandler().handleServiceException(failure, new MockHttpServletRequest());
        assertEquals(503, response.get("code")); assertEquals(failure.getMessage(), response.get("msg"));
        assertEquals(Map.of("outcome", "NOT_RUN"), response.get("data"));
    }

    @Test void realLeadStorageFaultReturnsNoHintAndHasZeroEconomicOrRegistrationEffect() {
        Map<String,Object> before = vector(); ApiPlayProgressController controller = hintController(MEMBER);
        jdbc.execute("RENAME TABLE club_lead_state TO c_hidden_lead_state");
        try {
            unavailable(() -> controller.unlockHint(NODE, "500", null));
            assertEquals(before, vector()); assertEquals(0, claims.get());
        } finally { jdbc.execute("RENAME TABLE c_hidden_lead_state TO club_lead_state"); }
        // Successful query with no lead row remains a legitimate non-lead activity after recovery.
        AjaxResult recovered = controller.unlockHint(NODE, "500", null);
        assertEquals(200, recovered.get("code"));
        assertEquals(Map.of("hint1", "hint-one", "hint2", "hint-two", "cost", COST), recovered.get("data"));
        assertEquals(90L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=7", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Long.class));
    }

    @Test void realLeadStorageFaultRollsBackTheOriginalBasicActionClaimWithoutTerminalReceipt() {
        Map<String,Object> before = vector(); ApiPlayProgressController controller = controller(MEMBER);
        jdbc.execute("RENAME TABLE club_lead_state TO c_hidden_lead_state");
        try {
            unavailable(() -> controller.answer("500", "401", "answer", null, "lead-storage-fault", 0, 0L, null, null));
            assertEquals(1, claims.get(), "must reach real claim INSERT, not an earlier command/authorization rejection");
            verify(observedReceipts, never()).completeReceipt(any());
            assertEquals(before, vector(), "the actual transaction must roll back the new original command claim");
        } finally { jdbc.execute("RENAME TABLE c_hidden_lead_state TO club_lead_state"); }
    }
}
