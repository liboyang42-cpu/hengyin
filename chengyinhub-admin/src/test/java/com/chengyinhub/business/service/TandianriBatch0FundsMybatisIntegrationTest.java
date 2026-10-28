package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TandianriBatch0FundsMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("tandianri_batch0_it");
    private static final long TOPIC = 7001L;
    private static final long ACTIVITY = 7101L;
    private static final long MERCHANT = 7201L;
    private static final long TOPIC_REGISTRATION = 7301L;
    private static final long ACTIVITY_REGISTRATION = 7302L;
    private static final long COOP_ORDER = 7501L;

    private DataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeAll
    void createDatabaseAndFactory() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            // ★ 2026-08-19:先 DROP 再 CREATE,不接受"环境里恰好剩下一个同名库"。
            // 原来是 CREATE DATABASE IF NOT EXISTS ⇒ 本类默默继承上一次(可能是崩在半路的那次)留下的表,
            // 于是 @BeforeEach 建表撞 "Table 'xxx' already exists" —— #741 的资金闸就是这么连红的:
            // 本地怎么跑都绿,只有 runner 上那个库有残留。库名本来就一类一个(见 MysqlIntegrationTestConfig),
            // 这里补的是另一半:**每次开跑都从零建**,隔离才是完整的。
            // 同一条规矩在 business/it/ExploreItDatabase 已经这么写了,这里对齐。
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            statement.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        configuration.getTypeAliasRegistry().registerAlias("CoopGroupVerify", CoopGroupVerify.class);
        configuration.setEnvironment(new Environment(
                "tandianri-batch0-it", new JdbcTransactionFactory(), dataSource));
        for (String resource : Arrays.asList(
                "mapper/business/CmsActivityMapper.xml",
                "mapper/business/CmsTopicMapper.xml",
                "mapper/business/CmsRegistrationMapper.xml",
                "mapper/business/CoopGroupVerifyMapper.xml",
                "mapper/business/CoopSettlementEvidenceMapper.xml")) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void resetAndSeed() throws Exception {
        execute(
                "DROP TABLE IF EXISTS coop_group_verify",
                "DROP TABLE IF EXISTS coop_group_member_verify",
                "DROP TABLE IF EXISTS cms_registration_scan",
                "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS oms_ticket",
                "DROP TABLE IF EXISTS cms_activity",
                "DROP TABLE IF EXISTS cms_topic_node",
                "DROP TABLE IF EXISTS cms_topic",
                "CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY,topic_id BIGINT)",
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, name VARCHAR(100), product_type INT, del_flag INT, status INT, is_settlement INT, end_date DATETIME, update_by VARCHAR(64), update_time DATETIME)",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, member_id BIGINT, name VARCHAR(100), del_flag INT, status INT, is_settlement INT, end_date DATETIME, start_date DATETIME)",
                "CREATE TABLE oms_ticket (id BIGINT PRIMARY KEY AUTO_INCREMENT, owner_type BIGINT, owner_id BIGINT, mode INT, sub_type VARCHAR(16), del_flag INT, start_time DATETIME, end_time DATETIME)",
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type BIGINT, owner_id BIGINT, member_id BIGINT, payable_amount DECIMAL(12,2), payment_status INT, registration_status INT, verification_status INT, purchase_kind INT, verification_time DATETIME, order_num BIGINT DEFAULT 1, ticket_id BIGINT, refund_deadline DATETIME)",
                "CREATE TABLE cms_registration_scan (id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_id BIGINT, member_id BIGINT, registration_type INT, status INT DEFAULT 0, create_time DATETIME)",
                "CREATE TABLE coop_group_member_verify (id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_id BIGINT, topic_id BIGINT, activity_id BIGINT, verify_time DATETIME)",
                // 2026-10-22 TF1:与生产 DDL(sql/base_schema_20260818.sql、migration_coop_group_activity_settlement_20260722)
                //   对齐 NOT NULL 与 uk_group_verify_activity_merchant。团码幂等靠这把唯一键 + INSERT IGNORE,
                //   夹具缺键时生产会拒的重复接待行在这里照样写得进去,读口径的断言就可能在重复数据上假绿。
                "CREATE TABLE coop_group_verify (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT NOT NULL, activity_id BIGINT DEFAULT NULL, coop_order_id BIGINT NOT NULL, merchant_member_id BIGINT NOT NULL, leader_member_id BIGINT NOT NULL, verify_time DATETIME NOT NULL, "
                        + "UNIQUE KEY uk_group_verify_activity_merchant (activity_id, merchant_member_id), KEY idx_group_verify_topic (topic_id), KEY idx_group_verify_activity (activity_id))",
                "INSERT INTO cms_topic(id,name,product_type,del_flag,status,is_settlement,end_date) VALUES (7001,'FREE_EXPLORE',2,0,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY)),(7002,'经典定向',1,0,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY)),(7003,'类型未决主题',NULL,0,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY))",
                // 2026-09-17 客诉期:活动结束满 7 天才入结算选单,活动夹具统一放在结束 8 天前。
                "INSERT INTO cms_activity(id,topic_id,member_id,name,del_flag,status,is_settlement,end_date) VALUES (7101,7001,1,'FREE_EXPLORE壳',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY)),(7102,7002,1,'经典活动',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY)),(7103,NULL,1,'独立旧活动',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY)),(7104,7003,1,'类型未决活动壳',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY))",
                "INSERT INTO cms_registration(id,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status,purchase_kind,verification_time) VALUES (7301,1,7001,7401,69.00,2,2,1,1,NOW()),(7302,2,7101,7402,100.00,2,2,1,3,NOW()),(7303,2,7102,7403,88.00,2,2,1,3,NOW())",
                "INSERT INTO cms_registration_scan(registration_id,member_id,registration_type,create_time) VALUES (7301,7201,1,NOW()),(7302,7201,1,NOW())",
                "INSERT INTO coop_group_member_verify(registration_id,topic_id,activity_id,verify_time) VALUES (7301,7001,7101,NOW()),(7302,7001,7101,NOW())",
                "INSERT INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time) VALUES (7001,7101,7501,7201,7601,NOW())");
        // 夹具负控:同场次同商家的第二条接待必须被唯一键吞掉,证明上面的 DDL 真的带着生产的键。
        assertEquals(0, updateCount("INSERT IGNORE INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time) VALUES (7001,7101,7501,7201,7602,NOW())"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM coop_group_verify"));
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void activitySettlementSelectionExcludesFreeExploreButKeepsLegacyAndIndependentActivities() {
        try (SqlSession session = factory.openSession()) {
            List<CmsActivity> selected = session.getMapper(CmsActivityMapper.class)
                    .selectActivitiesForSettlement();
            java.util.Set<Long> selectedIds = new HashSet<>();
            for (CmsActivity activity : selected) {
                selectedIds.add(activity.getId());
            }
            assertEquals(new HashSet<>(Arrays.asList(7102L, 7103L, 7104L)), selectedIds,
                    "SQL 只止损显式 FREE_EXPLORE；NULL 必须进入 Java fail-closed 而非静默永久跳过");
        }
    }

    @Test
    void topicSettlementSelectionExcludesFreeExploreButKeepsOrienteeringAndNullForJavaFailClosed() {
        try (SqlSession session = factory.openSession()) {
            List<CmsTopic> selected = session.getMapper(CmsTopicMapper.class)
                    .selectTopicsForSettlement();
            java.util.Set<Long> selectedIds = new HashSet<>();
            for (CmsTopic topic : selected) {
                selectedIds.add(topic.getId());
            }
            assertEquals(new HashSet<>(Arrays.asList(7002L, 7003L)), selectedIds,
                    "ProductType=1 照旧入选；NULL 也必须进入 Java fail-closed 并留下错误日志");
        }
    }

    @Test
    void topicSettlementClaimRejectsEligibilityChangesAfterTheScanAndStillClaimsValidTopicOnce() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            CmsTopicMapper mapper = session.getMapper(CmsTopicMapper.class);
            assertTrue(mapper.selectTopicsForSettlement().stream().anyMatch(t -> Long.valueOf(7002L).equals(t.getId())));
            execute("UPDATE cms_topic SET del_flag=2 WHERE id=7002");
            assertEquals(0, mapper.claimTopicForSettlement(7002L));
            execute("UPDATE cms_topic SET del_flag=0,status=0 WHERE id=7002");
            assertEquals(0, mapper.claimTopicForSettlement(7002L));
            execute("UPDATE cms_topic SET status=1,end_date=NOW() WHERE id=7002");
            assertEquals(0, mapper.claimTopicForSettlement(7002L));
            execute("UPDATE cms_topic SET end_date=DATE_SUB(NOW(),INTERVAL 1 DAY),product_type=2 WHERE id=7002");
            assertEquals(0, mapper.claimTopicForSettlement(7002L));
            execute("UPDATE cms_topic SET product_type=1 WHERE id=7002",
                    "INSERT INTO cms_registration(id,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status,refund_deadline) "
                            + "VALUES(7399,1,7002,7499,10.00,2,2,0,DATE_ADD(NOW(),INTERVAL 1 DAY))");
            assertEquals(0, mapper.claimTopicForSettlement(7002L));
            assertEquals(0, scalarInt("SELECT is_settlement FROM cms_topic WHERE id=7002"));
            execute("UPDATE cms_registration SET verification_status=1 WHERE id=7399");
            assertEquals(1, mapper.claimTopicForSettlement(7002L));
            assertEquals(0, mapper.claimTopicForSettlement(7002L));
            assertEquals(1, scalarInt("SELECT is_settlement FROM cms_topic WHERE id=7002"));
        }
    }

    /**
     * 2026-09-16 用户硬规则:收款方的钱要等所有玩家都退不了才可提现。活动结算即入余额,
     * 所以 end_date 已过、但集合前 24h 退款截止还没到(结束时间填得比集合早)时不许结。
     * 7106 只差在票已核销(核销后不可退),证明闸只挡「还能退的票」,不是把整类活动挡死。
     */
    @Test
    void activitySettlementWaitsUntilEveryPaidTicketIsPastItsRefundDeadline() throws Exception {
        execute(
                "INSERT INTO cms_activity(id,topic_id,member_id,name,del_flag,status,is_settlement,end_date,start_date) VALUES "
                        + "(7105,NULL,1,'结束早于集合',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY),DATE_ADD(NOW(),INTERVAL 3 DAY)),"
                        + "(7106,NULL,1,'结束早于集合但已核销',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY),DATE_ADD(NOW(),INTERVAL 3 DAY)),"
                        + "(7107,NULL,1,'集合已过但快照截止未到',0,1,0,DATE_SUB(NOW(),INTERVAL 8 DAY),DATE_SUB(NOW(),INTERVAL 9 DAY))",
                "INSERT INTO cms_registration(id,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status,purchase_kind,refund_deadline) VALUES "
                        + "(7305,2,7105,7405,100.00,2,2,0,3,NULL),"
                        + "(7306,2,7106,7406,100.00,2,2,1,3,NULL),"
                        + "(7307,2,7107,7407,100.00,2,2,0,3,DATE_ADD(NOW(),INTERVAL 1 DAY))");
        try (SqlSession session = factory.openSession()) {
            java.util.Set<Long> selectedIds = new HashSet<>();
            for (CmsActivity activity : session.getMapper(CmsActivityMapper.class).selectActivitiesForSettlement()) {
                selectedIds.add(activity.getId());
            }
            assertEquals(new HashSet<>(Arrays.asList(7102L, 7103L, 7104L, 7106L)), selectedIds,
                    "¥100 场次票集合在 3 天后、玩家还能退:活动不得结算入余额;已核销的照常结");
        }
    }

    /**
     * 同一条硬规则的主题侧:合作结算在主题结束 +12h 冻结发起人保底/毛利、T+7 入余额。
     * 自由定向票(mode=2)有效期晚于主题结束时,玩家在冻结后仍可退 ⇒ 不许结。
     * 7005 的票已退(registration_status=3),证明闸只看「已付未退未核销」的票。
     */
    @Test
    void topicSettlementWaitsUntilEveryPaidDirectTicketIsPastItsRefundDeadline() throws Exception {
        execute(
                "INSERT INTO cms_topic(id,name,product_type,del_flag,status,is_settlement,end_date) VALUES "
                        + "(7004,'票期长于主题',1,0,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY)),"
                        + "(7005,'票期长于主题但已退',1,0,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY)),"
                        + "(7006,'票期已过但快照截止未到',1,0,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY))",
                "INSERT INTO oms_ticket(id,owner_type,owner_id,mode,del_flag,start_time,end_time) VALUES "
                        + "(7404,1,7004,2,0,DATE_SUB(NOW(),INTERVAL 5 DAY),DATE_ADD(NOW(),INTERVAL 10 DAY)),"
                        + "(7405,1,7005,2,0,DATE_SUB(NOW(),INTERVAL 5 DAY),DATE_ADD(NOW(),INTERVAL 10 DAY)),"
                        + "(7406,1,7006,1,0,DATE_SUB(NOW(),INTERVAL 5 DAY),DATE_SUB(NOW(),INTERVAL 2 DAY))",
                "INSERT INTO cms_registration(id,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status,purchase_kind,ticket_id,refund_deadline) VALUES "
                        + "(7308,1,7004,7408,69.00,2,2,0,1,7404,NULL),"
                        + "(7309,1,7005,7409,69.00,2,3,0,1,7405,NULL),"
                        + "(7310,1,7006,7410,69.00,2,2,0,1,7406,DATE_ADD(NOW(),INTERVAL 1 DAY))");
        try (SqlSession session = factory.openSession()) {
            java.util.Set<Long> selectedIds = new HashSet<>();
            for (CmsTopic topic : session.getMapper(CmsTopicMapper.class).selectTopicsForSettlement()) {
                selectedIds.add(topic.getId());
            }
            assertEquals(new HashSet<>(Arrays.asList(7002L, 7003L, 7005L)), selectedIds,
                    "¥69 自由定向票 10 天后才过期、玩家还能退:主题不得冻结结算;已退的照常结");
        }
    }

    @Test
    void legacyCashBasisExcludesActivityShellWhileEvidenceIncludesIt() {
        try (SqlSession session = factory.openSession()) {
            CmsRegistrationMapper mapper = session.getMapper(CmsRegistrationMapper.class);
            assertEquals(0, new BigDecimal("69.00").compareTo(mapper.selectTotalSalesByTopicId(TOPIC)));
            assertEquals(0, new BigDecimal("169.00").compareTo(
                    mapper.selectTotalSalesEvidenceByTopicIdIncludingActivityShell(TOPIC)));
        }
    }

    @Test
    void verifiedSalesByTopicIncludesTopicAndActivityShellTickets() {
        try (SqlSession session = factory.openSession()) {
            assertEquals(0, new BigDecimal("169.00").compareTo(session.getMapper(CmsRegistrationMapper.class)
                    .selectVerifiedSalesEvidenceByTopicIdIncludingActivityShell(TOPIC)));
        }
    }

    @Test
    void verifiedHeadsByTopicIncludesTopicAndActivityShellTickets() {
        try (SqlSession session = factory.openSession()) {
            assertEquals(2, session.getMapper(CmsRegistrationMapper.class)
                    .selectVerifiedHeadsEvidenceByTopicIdIncludingActivityShell(TOPIC));
        }
    }

    @Test
    void verifiedHeadsByTopicAndMerchantIncludesBothOwnerTypes() {
        try (SqlSession session = factory.openSession()) {
            assertEquals(2, session.getMapper(CmsRegistrationMapper.class)
                    .selectVerifiedHeadsEvidenceByTopicAndMerchantIncludingActivityShell(TOPIC, MERCHANT));
        }
    }

    @Test
    void verifiedSalesByTopicAndMerchantIncludesBothOwnerTypes() {
        try (SqlSession session = factory.openSession()) {
            assertEquals(0, new BigDecimal("169.00").compareTo(session.getMapper(CmsRegistrationMapper.class)
                    .selectVerifiedSalesEvidenceByTopicAndMerchantIncludingActivityShell(TOPIC, MERCHANT)));
        }
    }

    @Test
    void verifiedRegistrationIdsByTopicIncludesBothOwnerTypes() {
        try (SqlSession session = factory.openSession()) {
            assertEquals(Arrays.asList(TOPIC_REGISTRATION, ACTIVITY_REGISTRATION),
                    session.getMapper(CmsRegistrationMapper.class)
                            .selectVerifiedRegistrationIdsEvidenceByTopicIdIncludingActivityShell(TOPIC));
        }
    }

    @Test
    void groupSettlementBasisIncludesBothOwnerTypes() {
        try (SqlSession session = factory.openSession()) {
            CoopGroupSettlementBasis basis = session.getMapper(CoopGroupVerifyMapper.class)
                    .selectSettlementEvidenceBasisByTopicAndMerchantIncludingActivityShell(TOPIC, MERCHANT);
            assertEquals(2, basis.getVerifiedHeads());
            assertEquals(0, new BigDecimal("169.00").compareTo(basis.getVerifiedSales()));
        }
    }

    @Test
    void settlementEvidenceServiceResolvesActiveActivityIdsBeforeRegistrationAndTicketQuery() {
        CoopSettlementSnapshot snapshot = new CoopSettlementSnapshot();
        snapshot.setTopicId(TOPIC);
        snapshot.setCommercialContextId(TOPIC);
        snapshot.setScopeType("TOPIC");
        snapshot.setScopeId(TOPIC);
        snapshot.setOrderId(COOP_ORDER);
        snapshot.setBeneficiaryId(MERCHANT);
        try (SqlSession session = factory.openSession(false)) {
            TopicSettlementEvidenceService service = evidenceService(session);
            List<CoopSettlementCandidate> candidates = service.lockCandidates(snapshot, new Date());
            java.util.Set<Long> registrationIds = new HashSet<>();
            for (CoopSettlementCandidate candidate : candidates) {
                registrationIds.add(candidate.getRegistrationId());
            }
            assertEquals(new HashSet<>(Arrays.asList(TOPIC_REGISTRATION, ACTIVITY_REGISTRATION)),
                    registrationIds);
        }
    }

    @Test
    void nullActivityIdsStillKeepDirectTopicRegistrations() {
        assertDirectTopicRegistrationRemainsWhenActivityIdsAre(null);
    }

    @Test
    void emptyActivityIdsStillKeepDirectTopicRegistrations() {
        assertDirectTopicRegistrationRemainsWhenActivityIdsAre(Collections.<Long>emptyList());
    }

    @Test
    void settlementEvidenceServiceConsumesAllSevenReadOnlyActivityShellCapabilities() {
        try (SqlSession session = factory.openSession()) {
            TopicSettlementEvidenceService.Summary evidence = evidenceService(session).read(TOPIC, MERCHANT);
            assertEquals(0, new BigDecimal("169.00").compareTo(evidence.getTotalSales()));
            assertEquals(0, new BigDecimal("169.00").compareTo(evidence.getVerifiedSales()));
            assertEquals(2, evidence.getVerifiedHeads());
            assertEquals(2, evidence.getMerchantVerifiedHeads());
            assertEquals(0, new BigDecimal("169.00").compareTo(evidence.getMerchantVerifiedSales()));
            assertEquals(Arrays.asList(TOPIC_REGISTRATION, ACTIVITY_REGISTRATION),
                    evidence.getVerifiedRegistrationIds());
            assertEquals(2, evidence.getGroupBasis().getVerifiedHeads());
        }
    }

    @Test
    void productTypeBackfillTargetsOnlyActiveMixedTopicAndIsReplaySafe() throws Exception {
        execute(
                "INSERT INTO cms_topic(id,name,product_type,del_flag,status,is_settlement,end_date) VALUES (23,'目标混票主题',NULL,0,1,0,NOW()),(24,'非活跃混票主题',NULL,0,0,0,NOW())",
                "INSERT INTO oms_ticket(owner_type,owner_id,mode,del_flag) VALUES (1,23,1,0),(1,23,2,0),(1,24,1,0),(1,24,2,0)");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_20260814_tandianri_product_type_backfill.sql"));
        }

        assertEquals(1, scalarInt("SELECT product_type FROM cms_topic WHERE id=23"),
                "目标必须回填为 ProductType.ORIENTEERING=1");
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM cms_topic WHERE id=24 AND product_type IS NULL"),
                "逐目标 SQL 不得扩大到非活跃主题");
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM cms_topic t WHERE t.id=23 AND t.del_flag=0 AND t.product_type IS NULL"),
                "第一次执行后，同一 UPDATE 重放必须命中 0 行");
    }

    private DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, MYSQL.user(), MYSQL.password());
    }

    private TopicSettlementEvidenceService evidenceService(SqlSession session) {
        return new TopicSettlementEvidenceService(
                session.getMapper(CmsActivityMapper.class),
                session.getMapper(CmsRegistrationMapper.class),
                session.getMapper(CoopGroupVerifyMapper.class),
                session.getMapper(CoopSettlementEvidenceMapper.class));
    }

    private void assertDirectTopicRegistrationRemainsWhenActivityIdsAre(List<Long> activityIds) {
        CoopSettlementSnapshot snapshot = new CoopSettlementSnapshot();
        snapshot.setTopicId(TOPIC);
        snapshot.setCommercialContextId(TOPIC);
        snapshot.setScopeType("TOPIC");
        snapshot.setScopeId(TOPIC);
        snapshot.setOrderId(COOP_ORDER);
        snapshot.setBeneficiaryId(MERCHANT);
        try (SqlSession session = factory.openSession(false)) {
            List<CoopSettlementCandidate> candidates = session.getMapper(CoopSettlementEvidenceMapper.class)
                    .selectTopicEvidenceCandidatesForUpdate(snapshot, activityIds, new Date());
            assertEquals(1, candidates.size());
            assertEquals(TOPIC_REGISTRATION, candidates.get(0).getRegistrationId());
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String item : sql) {
                statement.execute(item);
            }
        }
    }

    private int updateCount(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
