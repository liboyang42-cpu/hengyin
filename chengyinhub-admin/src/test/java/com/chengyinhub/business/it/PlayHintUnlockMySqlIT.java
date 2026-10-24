package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.CmsPointsSettingServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberPointsDetailServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W09-T before extraction: real MySQL authorization locks, unique ledger, debit and rollback.
 * Catalogue node/template/topic inputs are doubles. This is not HTTP, application-service or migration acceptance. */
@Timeout(45)
class PlayHintUnlockMySqlIT {
    private static final long MEMBER = 7, TOPIC = 300, ACTIVITY = 500, NODE = 401;
    private static final int COST = 10;
    private static PooledDataSource ds;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactions;
    private static String ownedSchema;
    private UmsMemberServiceImpl members;
    private UmsMemberPointsDetailServiceImpl ledger;
    private final Set<Long> connectionIds = ConcurrentHashMap.newKeySet();
    private CountDownLatch callerEntered;

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
        String url = MybatisItDatabase.url("c_hint_unlock");
        ownedSchema = MybatisItDatabase.databaseName("c_hint_unlock");
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection connection = ds.getConnection()) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
            // Reuse the public canonical DDL verbatim, including the production uniqueness indexes.
            Path baseline = Path.of("..", "sql", "base_schema_20260818.sql");
            if (!Files.isRegularFile(baseline)) baseline = Path.of("sql", "base_schema_20260818.sql");
            String ddl = Files.readString(baseline);
            try (Statement statement = connection.createStatement()) {
                for (String table : List.of("ums_member_points_detail", "cms_points_setting")) {
                    java.util.regex.Matcher match = Pattern.compile("(?s)CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(ddl);
                    assertTrue(match.find(), "Missing canonical DDL: " + table);
                    statement.execute(match.group()); assertFalse(match.find(), "Ambiguous canonical DDL: " + table);
                }
            }
            ItSchema.applyMigrations(connection, "sql/migration_20260930_registration_first_successful_scan.sql", "sql/migration_club_lead.sql");
        }
        Configuration config = new Configuration(new Environment("c-hint-unlock", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : List.of("CmsRegistrationMapper", "CmsActivityMapper", "ClubLeadMapper", "CmsPointsSettingMapper",
                "UmsMemberMapper", "UmsMemberPointsDetailMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream input = PlayHintUnlockMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
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
        assertTrue(ownedSchema.matches("chengyin_mybatis_c_hint_unlock_[a-z0-9_]{1,20}"));
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true", MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + ownedSchema + "`");
        }
    }

    @BeforeEach void seedOnlyOwnedSchema() {
        for (String table : List.of("ums_member_points_detail", "cms_points_setting", "cms_registration", "club_lead_state", "cms_activity", "cms_topic", "ums_member")) {
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
        connectionIds.clear(); callerEntered = null;
    }

    private ApiPlayProgressController controller(long actor, IUmsMemberService memberService, IUmsMemberPointsDetailService details) {
        ApiPlayProgressController target = new ApiPlayProgressController() {
            @Override public Long getAppUserId() {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "identity extraction is outside the application transaction");
                return actor;
            }
        };
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode(); node.setId(NODE); node.setTopicId(TOPIC); node.setTemplateId(39L); node.setSortId(1L); node.setStatus(1L);
        doAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                // Test-only connection evidence, never part of production query budgets.
                connectionIds.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                if (callerEntered != null) callerEntered.countDown();
                return node;
        }).when(nodes).selectCmsTopicNodeById(NODE);
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
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.wire(target, settings, memberService, details, transactions);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ApiPlayProgressController) proxy.getProxy();
    }
    private ApiPlayProgressController controller(long actor) { return controller(actor, members, ledger); }
    private AjaxResult selfPlay(ApiPlayProgressController controller) { return controller.unlockHint(NODE, null, String.valueOf(TOPIC)); }
    private long points() { return jdbc.queryForObject("SELECT point FROM ums_member WHERE id=7", Long.class); }
    private int hintFlows() { return jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=7 AND event_type=14 AND event_id=401", Integer.class); }
    private void hints(AjaxResult result, int cost) {
        assertEquals(200, result.get("code")); assertEquals(Map.of("hint1", "hint-one", "hint2", "hint-two", "cost", cost), result.get("data"));
    }
    private void unchanged() {
        assertEquals(100, points()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration WHERE registration_status=2 AND payment_status=2", Integer.class));
    }
    private void refused(AjaxResult result, int code, String message) {
        assertEquals(code, result.get("code")); assertEquals(message, result.get("msg")); assertFalse(result.containsKey("data"));
    }

    @Test void legalSelfPlayUsesOneRealDebitAndOriginalEventIdentity() {
        assertLegalDebit(false);
    }

    @Test void legalActivityUsesOneRealDebitAndOriginalEventIdentity() {
        assertLegalDebit(true);
    }

    private void assertLegalDebit(boolean activity) {
        ApiPlayProgressController controller = controller(MEMBER);
        hints(controller.unlockHint(NODE, activity ? String.valueOf(ACTIVITY) : null, activity ? null : String.valueOf(TOPIC)), COST);
        assertEquals(90, points()); assertEquals(1, hintFlows());
        Map<String,Object> flow = jdbc.queryForMap("SELECT current_points,after_points,change_points,change_type,event_type,event_id,member_id FROM ums_member_points_detail");
        assertEquals(100, ((Number)flow.get("current_points")).longValue()); assertEquals(90, ((Number)flow.get("after_points")).longValue());
        assertEquals(10, ((Number)flow.get("change_points")).longValue()); assertEquals(2, ((Number)flow.get("change_type")).intValue());
        assertEquals(14, ((Number)flow.get("event_type")).intValue()); assertEquals(NODE, ((Number)flow.get("event_id")).longValue());
        assertEquals(MEMBER, ((Number)flow.get("member_id")).longValue());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration WHERE registration_status=2 AND payment_status=2", Integer.class));
    }

    @Test void unauthorizedOrWrongScopeNeverReturnsHintsOrChangesEconomicState() {
        refused(selfPlay(controller(0)), 500, "请先登录");
        refused(controller(MEMBER).unlockHint(999L, null, "300"), 500, "该节点无提示");
        refused(controller(MEMBER).unlockHint(NODE, null, "301"), 500, "该节点不属于本主题");
        refused(selfPlay(controller(9)), 402, "请先购买自玩通行证"); unchanged();
    }

    @Test void duplicateAfterLostReplyAndReconstructionKeepsOneDebitAcrossAuthorizedScopes() {
        hints(selfPlay(controller(MEMBER)), COST);
        hints(selfPlay(controller(MEMBER)), 0); // Simulated lost response; new controller/mapper-backed services read the same ledger.
        hints(controller(MEMBER).unlockHint(NODE, "500", null), 0);
        assertEquals(90, points()); assertEquals(1, hintFlows());
    }

    @Test void twoConnectionsSerializeOriginalRegistrationAndDebitOnce() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        callerEntered = new CountDownLatch(2);
        UmsMemberPointsDetailServiceImpl held = spy(ledger);
        doAnswer(i -> {
            int changed = ledger.insertUmsMemberPointsDetail(i.getArgument(0)); inserted.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS)); return changed;
        }).when(held).insertUmsMemberPointsDetail(any());
        ApiPlayProgressController first = controller(MEMBER, members, held), second = controller(MEMBER);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> winner = pool.submit(() -> selfPlay(first)); assertTrue(inserted.await(10, TimeUnit.SECONDS));
            Future<AjaxResult> retry = pool.submit(() -> selfPlay(second)); assertTrue(callerEntered.await(10, TimeUnit.SECONDS));
            assertEquals(2, connectionIds.size());
            assertThrows(TimeoutException.class, () -> retry.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); hints(winner.get(15, TimeUnit.SECONDS), COST); hints(retry.get(15, TimeUnit.SECONDS), 0);
            assertEquals(90, points()); assertEquals(1, hintFlows());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void balanceLostAfterPrecheckRollsBackHintLedgerButRetainsOtherCommittedSpend() {
        UmsMemberPointsDetailServiceImpl interleaved = spy(ledger);
        doAnswer(i -> {
            TransactionTemplate otherSpend = new TransactionTemplate(transactions);
            otherSpend.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            otherSpend.execute(status -> {
                UmsMemberPointsDetail other = new UmsMemberPointsDetail(); other.setMemberId(MEMBER); other.setEventType(99L); other.setEventId(700L);
                other.setCurrentPoints(100L); other.setAfterPoints(5L); other.setChangePoints(95L); other.setChangeType(2L); other.setChangeReason("test competing spend");
                ledger.insertUmsMemberPointsDetail(other); members.deductPoints(MEMBER, 95); return null;
            });
            return ledger.insertUmsMemberPointsDetail(i.getArgument(0));
        }).when(interleaved).insertUmsMemberPointsDetail(any());
        refused(selfPlay(controller(MEMBER, members, interleaved)), 500, "积分不足,请重试");
        assertEquals(5, points()); assertEquals(0, hintFlows());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail WHERE event_type=99 AND change_points=95 AND after_points=5", Integer.class));
    }

    @Test void ledgerWriteThenThrowRollsBackRealRowAndLeavesDebitUntouched() {
        UmsMemberPointsDetailServiceImpl failed = spy(ledger);
        doAnswer(i -> { ledger.insertUmsMemberPointsDetail(i.getArgument(0)); throw new IllegalStateException("fixture after ledger insert"); })
                .when(failed).insertUmsMemberPointsDetail(any());
        IllegalStateException error = assertThrowsExactly(IllegalStateException.class, () -> selfPlay(controller(MEMBER, members, failed)));
        assertEquals("fixture after ledger insert", error.getMessage()); unchanged();
    }

    @Test void debitWriteThenThrowRollsBackBothRealBalanceAndLedgerBeforeOriginalRetry() {
        UmsMemberServiceImpl failed = spy(members);
        doAnswer(i -> { assertTrue(members.deductPoints(MEMBER, COST)); throw new IllegalStateException("fixture after debit"); })
                .when(failed).deductPoints(MEMBER, COST);
        refused(selfPlay(controller(MEMBER, failed, ledger)), 500, "积分不足,请重试"); unchanged();
        hints(selfPlay(controller(MEMBER)), COST); assertEquals(90, points()); assertEquals(1, hintFlows());
    }

    @Test void revokedRegistrationBlocksPreviouslyPurchasedHintWithoutChangingItsOriginalLedger() {
        hints(selfPlay(controller(MEMBER)), COST);
        jdbc.update("UPDATE cms_registration SET registration_status=3,payment_status=4 WHERE id=1");
        refused(selfPlay(controller(MEMBER)), 402, "请先购买自玩通行证");
        assertEquals(90, points()); assertEquals(1, hintFlows());
        assertEquals(4, jdbc.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void disabledRuleStillRequiresEntitlementAndWritesNoPoints() {
        jdbc.update("UPDATE cms_points_setting SET status=0 WHERE id=14");
        refused(selfPlay(controller(9)), 402, "请先购买自玩通行证");
        hints(selfPlay(controller(MEMBER)), 0); unchanged();
    }
}
