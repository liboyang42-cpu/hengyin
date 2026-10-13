package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicEditionFunds;
import com.chengyinhub.business.domain.TopicEditionFundsRawFacts;
import com.chengyinhub.business.mapper.TopicEditionFundsMapper;
import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.TopicEditionFundsServiceImpl;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
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
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第3批资金核心真实 MySQL 读回；类名 *IntegrationTest 仍由资金 gate 显式点名。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TandianriBatch3FundsMybatisIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("tandianri_batch3_funds_it");
    private static final long TOPIC = 23L;
    private static final String SINGLETON_MIGRATION =
            "sql/migration_20260817_tandianri_r7_distribution_singleton.sql";
    private static final String PRODUCT_MIGRATION =
            "sql/migration_20260817_tandianri_r6_product_contracts.sql";
    private static final String CLOSURE_MIGRATION =
            "sql/migration_20260817_tandianri_r6_funds_final_closure.sql";

    private DataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeAll
    void createDatabase() throws Exception {
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
        factory = factory(dataSource);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS topic_edition_funds_attribution",
                "DROP TABLE IF EXISTS oms_ticket",
                "DROP TABLE IF EXISTS topic_completion_xp_award",
                "DROP TABLE IF EXISTS product_role",
                "DROP TABLE IF EXISTS club_payable", "DROP TABLE IF EXISTS topic_edition_finalization",
                "DROP TABLE IF EXISTS club_edition_hours", "DROP TABLE IF EXISTS topic_edition_cost",
                "DROP TABLE IF EXISTS topic_edition_funds", "DROP TABLE IF EXISTS registration_entitlement",
                "DROP TABLE IF EXISTS refund_application", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS coop_complaint",
                "DROP TABLE IF EXISTS cms_activity", "DROP TABLE IF EXISTS cms_topic",
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT NULL,"
                        + " lifecycle INT NULL,del_flag INT NOT NULL DEFAULT 0) ENGINE=InnoDB",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT NOT NULL,"
                        + " del_flag INT NOT NULL DEFAULT 0) ENGINE=InnoDB",
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type BIGINT NOT NULL,"
                        + " owner_id BIGINT NOT NULL, ticket_id BIGINT NULL, purchase_kind INT NULL,"
                        + " payment_status INT NOT NULL, wechat_payment_amount DECIMAL(14,2) NULL,"
                        + " refund_deadline DATETIME(3) NULL, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE refund_application (id BIGINT PRIMARY KEY AUTO_INCREMENT, source_type VARCHAR(32) NOT NULL,"
                        + " source_id BIGINT NOT NULL, payout_status INT NULL, refund_amount DECIMAL(14,2) NULL,"
                        + " del_flag INT NULL) ENGINE=InnoDB",
                "CREATE TABLE registration_entitlement (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " registration_id BIGINT NOT NULL, chapter_id BIGINT NOT NULL, status INT NOT NULL,"
                        + " UNIQUE KEY uk_reg_chapter(registration_id,chapter_id)) ENGINE=InnoDB",
                "CREATE TABLE coop_complaint (id BIGINT PRIMARY KEY AUTO_INCREMENT,registration_id BIGINT NOT NULL,"
                        + " status INT NOT NULL,del_flag INT NOT NULL) ENGINE=InnoDB",
                "CREATE TABLE product_role (id BIGINT AUTO_INCREMENT PRIMARY KEY,topic_id BIGINT NOT NULL,"
                        + "slot VARCHAR(16) NOT NULL,entity_type VARCHAR(16) NOT NULL,entity_id BIGINT NULL,"
                        + "revenue_share DECIMAL(8,4) NULL,status INT NOT NULL,declared_by BIGINT NULL,"
                        + "created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB",
                "INSERT INTO cms_topic(id,product_type,lifecycle,del_flag) VALUES (23,2,2,0),(24,1,2,0)",
                "INSERT INTO cms_activity(id,topic_id) VALUES (501,23),(502,24)");
        try (Connection connection = dataSource.getConnection()) {
            FileSystemResource migration =
                    new FileSystemResource("sql/migration_20260815_tandianri_batch3_funds_core.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
            execute("CREATE TABLE topic_edition_finalization (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + "topic_id BIGINT NOT NULL,club_id BIGINT NOT NULL,fixed_compensation DECIMAL(14,2) NOT NULL,"
                            + "quality_amount DECIMAL(14,2) NOT NULL,extra_amount DECIMAL(14,2) NOT NULL,"
                            + "CONSTRAINT ck_topic_edition_final_amounts CHECK"
                            + " (fixed_compensation>=0 AND quality_amount>=0 AND extra_amount>=0)) ENGINE=InnoDB",
                    "CREATE TABLE club_payable (id BIGINT AUTO_INCREMENT PRIMARY KEY,topic_id BIGINT NOT NULL,"
                            + "club_id BIGINT NOT NULL,component VARCHAR(16) NOT NULL,entry_type VARCHAR(16) NOT NULL,"
                            + "amount DECIMAL(14,2) NOT NULL,idempotency_key VARCHAR(96) NOT NULL,"
                            + "CONSTRAINT ck_club_payable_component CHECK"
                            + " (component IN ('FIXED','QUALITY','EXTRA'))) ENGINE=InnoDB");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    "sql/migration_20260817_tandianri_batch3_funds_ticket_source_payable.sql"));
            FileSystemResource closure = new FileSystemResource(
                    "sql/migration_20260817_tandianri_r6_funds_final_closure.sql");
            ScriptUtils.executeSqlScript(connection, closure);
            ScriptUtils.executeSqlScript(connection, closure);
        }
        // 2026-08-25 承接费换轨的净票数列。手工补而不是挂 migration_20260825：那支迁移还要动
        // club_edition_compensation_terms，而本套没有那张表（只建 schema 的一小片）。
        // ★整支迁移的真 MySQL 执行 + 幂等重放由 ClubPayableMybatisIntegrationTest 担保。
        execute("ALTER TABLE topic_edition_funds_attribution"
                + " ADD COLUMN net_ticket_count BIGINT NULL AFTER ticket_count");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void activityShellCashRefundAndPendingAreReadBackWithoutEntitlementMultiplication() throws Exception {
        insertRegistration(1, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2030-01-01 00:00:00");
        insertRegistration(2, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2020-01-01 00:00:00");
        insertRegistration(3, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2030-01-01 00:00:00");
        execute("INSERT INTO cms_registration(id,owner_type,owner_id,purchase_kind,payment_status,"
                        + "wechat_payment_amount,refund_deadline) VALUES"
                        + "(4,1,23,3,2,999.00,'2030-01-01 00:00:00'),"
                        + "(5,2,502,3,2,888.00,'2030-01-01 00:00:00')");
        execute("INSERT INTO registration_entitlement(registration_id,chapter_id,status) VALUES"
                        + "(1,11,0),(1,12,0),(1,13,0),(1,14,0),(2,11,0),(3,11,1)",
                "INSERT INTO refund_application(source_type,source_id,payout_status,refund_amount,del_flag)"
                        + " VALUES ('registration',3,1,20.00,0)");

        TopicEditionFundsRawFacts facts = rawFacts();

        assertEquals(3L, facts.getNCash());
        assertEquals(new BigDecimal("207.00"), facts.getGCash());
        assertEquals(new BigDecimal("69.00"), facts.getUPending());
        assertEquals(new BigDecimal("69.00"), facts.getUOut());
        assertEquals(new BigDecimal("20.00"), facts.getRRefund());
    }

    @Test
    void processingRefundIsNotRecognizedAsMoneyReturned() throws Exception {
        insertRegistration(1, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2030-01-01 00:00:00");
        execute("INSERT INTO registration_entitlement(registration_id,chapter_id,status) VALUES (1,11,0)",
                "INSERT INTO refund_application(source_type,source_id,payout_status,refund_amount,del_flag)"
                        + " VALUES ('registration',1,0,69.00,0)");

        assertEquals(new BigDecimal("0.00"), rawFacts().getRRefund());
    }

    @Test
    void distributionSingletonMigrationReplaysAndDatabaseRejectsASecondActiveRow() throws Exception {
        // ScriptUtils 只 execute 不读结果集 —— 迁移里声明的 scalar-true 回读对它【不可见】，
        // 所以「脚本跑通」不等于「回读为真」。runMigrationAssertingReadbacks 按生产执行器的语义
        // 逐条消费，否则生成表达式的规范化比对在任何地方都没被观测过（回执 ≠ 观测）。
        assertEquals(7, runMigrationAssertingReadbacks(SINGLETON_MIGRATION),
                "迁移声明的 scalar-true 回读条数变了，先核对迁移再改这里");
        assertEquals(7, runMigrationAssertingReadbacks(SINGLETON_MIGRATION), "重放必须仍然逐条为真");
        execute("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                + " VALUES (23,'DISTRIBUTION','CLUB',7,1,71)");

        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                        + " VALUES (23,'DISTRIBUTION','CLUB',8,1,71)"));
        execute("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                + " VALUES (23,'DISTRIBUTION','CLUB',8,0,71)");

        assertEquals(1L, number("SELECT COUNT(*) FROM product_role"
                + " WHERE topic_id=23 AND slot='DISTRIBUTION' AND status=1"));
        assertEquals(1L, number("SELECT COUNT(*) FROM information_schema.statistics"
                + " WHERE table_schema=DATABASE() AND table_name='product_role'"
                + " AND index_name='uk_product_role_distribution_singleton' AND non_unique=0"));
    }

    /**
     * R9-D 负控①：同名生成列但表达式是 status = 2。写前 guard①（第 2 条声明回读）必须在任何
     * ALTER 之前判 0 ⇒ 迁移失败；绝不能靠「列名存在」就跳过 DDL、把一条并发防线静默换掉。
     */
    @Test
    void distributionSingletonGuardRejectsASameNamedWrongExpressionGeneratedColumnBeforeAnyAlter()
            throws Exception {
        execute("ALTER TABLE product_role ADD COLUMN distribution_singleton_key VARCHAR(32)"
                + " GENERATED ALWAYS AS (CASE WHEN status = 2 AND slot = 'DISTRIBUTION'"
                + " THEN CONCAT(topic_id, '-DISTRIBUTION') END) VIRTUAL");

        ReadbackFailure failure = assertThrows(ReadbackFailure.class,
                () -> runMigrationAssertingReadbacks(SINGLETON_MIGRATION));

        assertEquals(2, failure.index, "必须正好是写前 guard①（生成列形状）判红，而不是别的语句偶然抛错");
        assertTrue(failure.sql.contains("distribution_singleton_key"), failure.sql);
        assertTrue(failure.sql.contains("generation_expression"), failure.sql);
        assertEquals(0L, number("SELECT COUNT(*) FROM information_schema.statistics"
                        + " WHERE table_schema=DATABASE() AND table_name='product_role'"
                        + " AND index_name='uk_product_role_distribution_singleton'"),
                "guard① 判红后不得再执行任何 ALTER：唯一键绝不能建在错表达式的列上");
    }

    /**
     * R9-D 负控②：同名 UNIQUE 但是复合 (distribution_singleton_key, entity_id)。
     * 它对「同一主题两个生效 DISTRIBUTION」压根不设防，而存在性预检会跳过建索引 ⇒
     * 写前 guard②（第 3 条声明回读）必须判 0。
     */
    @Test
    void distributionSingletonGuardRejectsASameNamedCompositeUniqueIndexBeforeAnyAlter() throws Exception {
        execute("ALTER TABLE product_role ADD COLUMN distribution_singleton_key VARCHAR(32)"
                        + " GENERATED ALWAYS AS (CASE WHEN status = 1 AND slot = 'DISTRIBUTION'"
                        + " THEN CONCAT(topic_id, '-DISTRIBUTION') END) VIRTUAL",
                "ALTER TABLE product_role ADD UNIQUE KEY uk_product_role_distribution_singleton"
                        + " (distribution_singleton_key, entity_id)");

        ReadbackFailure failure = assertThrows(ReadbackFailure.class,
                () -> runMigrationAssertingReadbacks(SINGLETON_MIGRATION));

        assertEquals(3, failure.index, "必须正好是写前 guard②（唯一键形状）判红");
        assertTrue(failure.sql.contains("uk_product_role_distribution_singleton"), failure.sql);
        // 证明这条同名索引确实没防住单例：两行生效 DISTRIBUTION 照样进得来。
        execute("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                        + " VALUES (23,'DISTRIBUTION','CLUB',7,1,71)",
                "INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                        + " VALUES (23,'DISTRIBUTION','CLUB',8,1,71)");
        assertEquals(2L, number("SELECT COUNT(*) FROM product_role"
                        + " WHERE topic_id=23 AND slot='DISTRIBUTION' AND status=1"),
                "复合 UNIQUE 冒充单例键时并发防线其实不存在 —— 这正是 guard② 必须红的理由");
    }

    /** R9-D 负控③：同名但【非唯一】索引，形状上只差 non_unique；guard② 同样必须判 0。 */
    @Test
    void distributionSingletonGuardRejectsASameNamedNonUniqueIndexBeforeAnyAlter() throws Exception {
        execute("ALTER TABLE product_role ADD COLUMN distribution_singleton_key VARCHAR(32)"
                        + " GENERATED ALWAYS AS (CASE WHEN status = 1 AND slot = 'DISTRIBUTION'"
                        + " THEN CONCAT(topic_id, '-DISTRIBUTION') END) VIRTUAL",
                "ALTER TABLE product_role ADD INDEX uk_product_role_distribution_singleton"
                        + " (distribution_singleton_key)");

        ReadbackFailure failure = assertThrows(ReadbackFailure.class,
                () -> runMigrationAssertingReadbacks(SINGLETON_MIGRATION));

        assertEquals(3, failure.index, "必须正好是写前 guard②（唯一键形状）判红");
        assertEquals(1L, number("SELECT COUNT(*) FROM information_schema.statistics"
                        + " WHERE table_schema=DATABASE() AND table_name='product_role'"
                        + " AND index_name='uk_product_role_distribution_singleton' AND non_unique=1"),
                "guard② 判红后不得静默 drop/recreate：那张非唯一索引必须原样留着等人处置");
    }

    /**
     * R9-C：R6 产品迁移此前【零执行证据】—— 三条回读从没被求值过，那条会改写存量
     * oms_ticket.source_ticket_id 的多表 UPDATE 也从没在任何数据库上跑过。
     * 这里重放两遍、逐条消费全部声明回读，并同时钉住两个方向的歧义都必须保持 NULL。
     */
    @Test
    void productContractsMigrationReplaysAndBackfillsOnlyUnambiguousPairs() throws Exception {
        execute("INSERT INTO cms_activity(id,topic_id,del_flag) VALUES (503,23,0)",
                "CREATE TABLE oms_ticket (id BIGINT PRIMARY KEY, owner_type BIGINT NOT NULL,"
                        + " owner_id BIGINT NOT NULL, name VARCHAR(64) NULL, price DECIMAL(14,2) NULL,"
                        + " start_time DATETIME NULL, end_time DATETIME NULL,"
                        + " del_flag INT NOT NULL DEFAULT 0) ENGINE=InnoDB",
                "INSERT INTO oms_ticket(id,owner_type,owner_id,name,price,start_time,end_time) VALUES"
                        // 主题票：701/702 是同形重复，用来造正向歧义
                        + " (700,1,23,'A',10.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        + " (701,1,23,'B',20.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        + " (702,1,23,'B',20.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        + " (703,1,23,'C',30.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        // 活动壳票：800 唯一配对；801 正向歧义；802/803 跨两个活动壳争同一张 703
                        + " (800,2,501,'A',10.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        + " (801,2,501,'B',20.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        + " (802,2,501,'C',30.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        + " (803,2,503,'C',30.00,'2026-08-01 10:00:00','2026-08-01 12:00:00'),"
                        // 804 挂在另一个主题的活动壳上，主题 24 没有任何票
                        + " (804,2,502,'A',10.00,'2026-08-01 10:00:00','2026-08-01 12:00:00')");

        assertEquals(8, runMigrationAssertingReadbacks(PRODUCT_MIGRATION),
                "迁移声明的 scalar-true 回读条数变了，先核对迁移再改这里");
        assertEquals(8, runMigrationAssertingReadbacks(PRODUCT_MIGRATION), "重放必须仍然逐条为真");

        assertEquals(700L, number("SELECT source_ticket_id FROM oms_ticket WHERE id=800"),
                "唯一命中一张主题票的活动票必须被回填");
        assertEquals(1L, number("SELECT COUNT(*) FROM oms_ticket"
                        + " WHERE id=801 AND source_ticket_id IS NULL"),
                "正向歧义（一张活动票命中两张同形主题票）必须保持 NULL");
        assertEquals(2L, number("SELECT COUNT(*) FROM oms_ticket"
                        + " WHERE id IN (802,803) AND source_ticket_id IS NULL"),
                "反向歧义（两张活动票争同一张主题票）必须【两张都】保持 NULL；"
                        + "旧写法对它恒绿，会给两张票发同一个 source_ticket_id");
        assertEquals(1L, number("SELECT COUNT(*) FROM oms_ticket"
                        + " WHERE id=804 AND source_ticket_id IS NULL"),
                "主题下没有可配对票时不得凭空猜");
        assertEquals(1L, number("SELECT COUNT(*) FROM oms_ticket WHERE source_ticket_id IS NOT NULL"));
    }

    /**
     * R9-B：R6 资金终结闭环迁移的回读此前只由 ScriptUtils execute（结果集不可见）。
     * 这里按生产执行器语义重放两遍并逐条断言标量为 1 —— 写前 guard 与写后观测一条都不能是恒绿。
     */
    @Test
    void finalClosureMigrationReplaysAndEveryDeclaredReadbackHolds() throws Exception {
        assertEquals(11, runMigrationAssertingReadbacks(CLOSURE_MIGRATION),
                "迁移声明的 scalar-true 回读条数变了，先核对迁移再改这里");
        assertEquals(11, runMigrationAssertingReadbacks(CLOSURE_MIGRATION), "重放必须仍然逐条为真");
    }

    /**
     * R9-B 负控：同名 attribution 表但缺去重唯一键（这正是「同一快照同一俱乐部重复归因行 ⇒
     * 票源奖励重复计提」的病灶）。`CREATE TABLE IF NOT EXISTS` 会把它当成已完成，
     * 所以写前 guard①（第 1 条声明回读）必须判 0，且不得往这张错表里回填任何钱。
     */
    @Test
    void finalClosureGuardRejectsASameNamedAttributionTableMissingItsDedupeKeyBeforeAnyWrite()
            throws Exception {
        execute("DROP TABLE topic_edition_funds_attribution",
                "CREATE TABLE topic_edition_funds_attribution (id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "funds_snapshot_id BIGINT NOT NULL, source_club_id BIGINT NULL,"
                        + "ticket_count BIGINT NOT NULL, net_ticket_count BIGINT NULL,"
                        + "earned_amount DECIMAL(14,2) NOT NULL,"
                        + "source_key BIGINT GENERATED ALWAYS AS (COALESCE(source_club_id, 0)) STORED,"
                        + "PRIMARY KEY (id)) ENGINE=InnoDB");

        ReadbackFailure failure = assertThrows(ReadbackFailure.class,
                () -> runMigrationAssertingReadbacks(CLOSURE_MIGRATION));

        assertEquals(1, failure.index, "必须正好是写前 guard①（attribution 表形状）判红");
        assertTrue(failure.sql.contains("uk_edition_funds_attribution_source"), failure.sql);
        assertEquals(0L, number("SELECT COUNT(*) FROM information_schema.statistics"
                        + " WHERE table_schema=DATABASE() AND table_name='topic_edition_funds_attribution'"
                        + " AND index_name='uk_edition_funds_attribution_source'"),
                "guard① 判红后不得静默补建：那张缺键的表必须原样留着等人处置");
    }

    /**
     * R9-B 负控：同名 XP 事实表但唯一键建在错列 (member_id, xp_amount) 上 —— 名字对、
     * key part 数也是 2、也确实 non_unique = 0，旧回读对它完全恒绿，而「每人每期只发一次」
     * 的防线根本不存在。写前 guard③（第 3 条声明回读）必须判 0。
     */
    @Test
    void productContractsGuardRejectsASameNamedXpTableWithTheUniqueKeyOnWrongColumns()
            throws Exception {
        // ⚠️ 这句 DROP 在运行时是空操作(@BeforeEach 的 resetSchema 每条用例前已经丢过一遍),
        // 但它让**文本顺序**也成立:本文件里 oms_ticket 是「建 → 丢 → 再建」,不是「建 → 再建」。
        // ci/dup_create_table_gate.py 只能按文本顺序看,而那道闸防的正是 #741 那种
        // 「两边各加一条、git 不冲突、只在合并结果里炸」—— 让它读得懂,比让它开例外好。
        execute("DROP TABLE IF EXISTS oms_ticket",
                "CREATE TABLE oms_ticket (id BIGINT PRIMARY KEY, owner_type BIGINT NOT NULL,"
                        + " owner_id BIGINT NOT NULL, name VARCHAR(64) NULL, price DECIMAL(14,2) NULL,"
                        + " start_time DATETIME NULL, end_time DATETIME NULL,"
                        + " del_flag INT NOT NULL DEFAULT 0) ENGINE=InnoDB",
                "CREATE TABLE topic_completion_xp_award (id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "member_id BIGINT NOT NULL, topic_id BIGINT NOT NULL, xp_amount INT NOT NULL,"
                        + "granted_at DATETIME NOT NULL, PRIMARY KEY (id),"
                        + "UNIQUE KEY uk_topic_completion_xp_member_topic (member_id, xp_amount),"
                        + "CONSTRAINT ck_topic_completion_xp_positive CHECK (xp_amount > 0)) ENGINE=InnoDB");

        ReadbackFailure failure = assertThrows(ReadbackFailure.class,
                () -> runMigrationAssertingReadbacks(PRODUCT_MIGRATION));

        assertEquals(3, failure.index, "必须正好是写前 guard③（XP 表形状）判红");
        assertTrue(failure.sql.contains("uk_topic_completion_xp_member_topic"), failure.sql);
        // 证明旧判据（2 个 key part + non_unique = 0）对这张错表确实恒绿 —— 这就是它必须被替换的理由。
        assertEquals(2L, number("SELECT COUNT(*) FROM information_schema.statistics"
                + " WHERE table_schema=DATABASE() AND table_name='topic_completion_xp_award'"
                + " AND index_name='uk_topic_completion_xp_member_topic' AND non_unique=0"));
        assertEquals(0L, number("SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema=DATABASE() AND table_name='oms_ticket'"
                        + " AND column_name='source_ticket_id'"),
                "guard③ 判红后不得再执行任何 DDL/回填：source_ticket_id 列绝不能已经建出来");
    }

    /** 声明为 scalar-true 的回读没有返回 1；带上它在文件里的序号，负控才能点名是哪一道 guard。 */
    private static final class ReadbackFailure extends RuntimeException {
        private final int index;
        private final String sql;

        ReadbackFailure(int index, String sql, Object actual) {
            super("第 " + index + " 条 runner assert=scalar-true 失败: readback=" + actual + " sql=" + sql);
            this.index = index;
            this.sql = sql;
        }
    }

    /**
     * 复刻生产执行器（{@code ci/run_migration.py}）的【断言语义】：逐条执行，遇到声明了
     * {@code -- runner: assert=scalar-true} 的语句就真读回标量，不为 1 立即抛 {@link ReadbackFailure}
     * 并停在原地 —— 这样「写前 guard 必须在破坏性 DDL 之前红」才是可观测的事实而不是文本承诺。
     * 返回真正被断言过的回读条数，供调用方与迁移文件自己声明的条数对账。
     *
     * <p>⚠️ 复刻的只是断言语义，<b>分句不是</b>：这里是朴素 {@code split(";")}，
     * 与仓库唯一真源 {@code ci/sql_splitter.py}（识别引号/反引号/块注释/行尾 --）不是同一份。
     * 现状可接受的理由：这三个迁移文件里没有任何字符串字面量含 {@code ;}，也没有行尾注释，
     * 且真正的分句正确性由 CI 的 {@code migration_gates.check_sql_text} 用那份真源单独把关；
     * 一旦文件里出现需要真分句的形状，这里会以「断言条数对不上」的方式<b>响着红</b>
     * （{@code assertEquals(7/8/11, ...)}），不会静默漏过。Java 侧无法直接调用那份 Python 真源，
     * 所以不在这里再写一份「更聪明」的分句器 —— 两份聪明的分句器才是真正危险的。
     */
    private int runMigrationAssertingReadbacks(String path) throws Exception {
        String sql = new String(Files.readAllBytes(new FileSystemResource(path).getFile().toPath()),
                StandardCharsets.UTF_8);
        int asserted = 0;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String chunk : sql.split(";")) {
                boolean scalarTrue = chunk.contains("-- runner: assert=scalar-true");
                String executable = chunk.replaceAll("(?m)^[ \\t]*--[^\\n]*$", "").trim();
                if (executable.isEmpty()) {
                    continue;
                }
                if (!scalarTrue) {
                    statement.execute(executable);
                    continue;
                }
                try (ResultSet result = statement.executeQuery(executable)) {
                    Object value = result.next() ? result.getObject(1) : null;
                    if (!(value instanceof Number) || ((Number) value).longValue() != 1L) {
                        throw new ReadbackFailure(asserted + 1, executable, value);
                    }
                }
                asserted++;
            }
        }
        return asserted;
    }

    @Test
    void distributionBackfillReplaysAndNeverGuessesWhenOrganizerActorIsMissingOrAmbiguous() throws Exception {
        execute("ALTER TABLE cms_topic ADD COLUMN selling_started_at DATETIME NULL",
                "UPDATE cms_topic SET lifecycle=3,selling_started_at='2026-08-01 00:00:00' WHERE id=23",
                "INSERT INTO cms_topic(id,product_type,lifecycle,del_flag,selling_started_at) VALUES"
                        + " (25,2,3,0,'2026-08-01 00:00:00'),(26,2,3,0,'2026-08-01 00:00:00')",
                "CREATE TABLE club_edition_compensation_terms"
                        + " (topic_id BIGINT PRIMARY KEY,executing_club_id BIGINT NOT NULL) ENGINE=InnoDB",
                "INSERT INTO club_edition_compensation_terms(topic_id,executing_club_id)"
                        + " VALUES (23,7),(25,8),(26,9)",
                "INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by) VALUES"
                        + " (23,'ORGANIZER','PLATFORM',NULL,1,71),"
                        + " (26,'ORGANIZER','PLATFORM',NULL,1,81),"
                        + " (26,'ORGANIZER','PLATFORM',NULL,1,82)");
        FileSystemResource backfill = new FileSystemResource(
                "sql/migration_20260817_tandianri_r7_distribution_backfill.sql");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, backfill);
            ScriptUtils.executeSqlScript(connection, backfill);
        }

        assertEquals(1L, number("SELECT COUNT(*) FROM product_role WHERE topic_id=23"
                + " AND slot='DISTRIBUTION' AND entity_type='CLUB' AND entity_id=7"
                + " AND status=1 AND declared_by=71"));
        assertEquals(0L, number("SELECT COUNT(*) FROM product_role WHERE topic_id IN (25,26)"
                + " AND slot='DISTRIBUTION'"));
    }

    @Test
    void expiredNoShowStaysPendingOnlyWhileComplaintIsUnresolved() throws Exception {
        insertRegistration(1, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2020-01-01 00:00:00");
        execute("INSERT INTO registration_entitlement(registration_id,chapter_id,status) VALUES (1,11,0)",
                "INSERT INTO coop_complaint(registration_id,status,del_flag) VALUES (1,2,0)");
        assertEquals(new BigDecimal("69.00"), rawFacts().getUPending());
        assertEquals(new BigDecimal("69.00"), rawFacts().getUOut());

        execute("UPDATE coop_complaint SET status=3 WHERE registration_id=1");
        assertEquals(new BigDecimal("0.00"), rawFacts().getUPending());
        assertEquals(new BigDecimal("0.00"), rawFacts().getUOut());
    }

    @Test
    void manuallyRefundedPendingVerificationIsRecognizedAndOriginalCashRemains() throws Exception {
        insertRegistration(1, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2030-01-01 00:00:00");
        execute("INSERT INTO registration_entitlement(registration_id,chapter_id,status) VALUES (1,11,2)",
                "INSERT INTO refund_application(source_type,source_id,payout_status,refund_amount,del_flag)"
                        + " VALUES ('registration',1,3,69.00,0)");

        TopicEditionFundsRawFacts facts = rawFacts();

        assertEquals(new BigDecimal("69.00"), facts.getGCash());
        assertEquals(new BigDecimal("69.00"), facts.getRRefund());
    }

    @Test
    void unknownPurchaseKindFailsClosedInsteadOfBeingSilentlyIgnored() throws Exception {
        insertRegistration(1, 501, null, "69.00", "2030-01-01 00:00:00");
        try (SqlSession session = factory.openSession(false)) {
            TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(
                    session.getMapper(TopicEditionFundsMapper.class));
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.recomputeProvisional(TOPIC, Date.from(Instant.parse("2028-01-01T00:00:00Z")),
                            "unknown-kind", "finance-a"));
            assertEquals("EDITION_FUNDS_UNKNOWN:paidFacts=1", error.getMessage());
        }
    }

    @Test
    void provisionalRecomputeAppendsVersionAndLeavesOldSnapshotReadable() throws Exception {
        insertRegistration(1, 501, PurchaseKind.EXPLORE_PASS, "69.00", "2030-01-01 00:00:00");
        execute("INSERT INTO registration_entitlement(registration_id,chapter_id,status) VALUES (1,11,0)");
        recompute("version-1");
        execute("UPDATE registration_entitlement SET status=1 WHERE registration_id=1");
        recompute("version-2");

        assertEquals(2L, number("SELECT COUNT(*) FROM topic_edition_funds WHERE topic_id=23"));
        assertEquals(1L, number("SELECT COUNT(*) FROM topic_edition_funds WHERE topic_id=23 AND is_current=1"));
        assertEquals(2L, number("SELECT snapshot_version FROM topic_edition_funds WHERE topic_id=23 AND is_current=1"));
    }

    @Test
    void databaseConservationConstraintRejectsAFalseGreenSnapshot() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO topic_edition_funds(topic_id,snapshot_kind,snapshot_version,is_current,n_cash,"
                        + "g_cash,u_in,g_earned,u_out,u_pending,r_refund,input_hash,idempotency_key,computed_by,computed_at)"
                        + " VALUES(23,'PROVISIONAL',1,1,1,100,0,100,20,20,0,REPEAT('a',64),'broken','x',NOW())"));
    }

    @Test
    void finalRecomputePersistsTheSameTicketSetAttributionAndReplaysIdempotently() throws Exception {
        execute("INSERT INTO cms_registration(id,owner_type,owner_id,purchase_kind,payment_status,"
                        + "wechat_payment_amount,refund_deadline,source_club_id,source_frozen_at) VALUES"
                        + "(1,2,501,3,2,40.00,'2020-01-01 00:00:00',7,NOW()),"
                        + "(2,2,501,3,2,60.00,'2020-01-01 00:00:00',NULL,NOW()),"
                        + "(3,2,501,3,2,20.00,'2020-01-01 00:00:00',7,NOW())",
                "INSERT INTO refund_application(source_type,source_id,payout_status,refund_amount,del_flag)"
                        + " VALUES ('registration',3,1,20.00,0)");
        try (SqlSession session = factory.openSession(false)) {
            TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(
                    session.getMapper(TopicEditionFundsMapper.class));
            ProductRoleServiceImpl roleService = new ProductRoleServiceImpl();
            ReflectionTestUtils.setField(roleService, "productRoleMapper",
                    session.getMapper(ProductRoleMapper.class));
            roleService.declareDistributionSlotForSale(TOPIC, 7L, 71L);

            TopicEditionFunds first = service.recompute(TOPIC,
                    Date.from(Instant.parse("2028-01-01T00:00:00Z")),
                    "FINAL:23:FUNDS", "finance-a", TopicEditionFundsPhase.FINAL);
            TopicEditionFunds replay = service.recompute(TOPIC,
                    Date.from(Instant.parse("2028-01-01T00:00:00Z")),
                    "FINAL:23:FUNDS", "finance-a", TopicEditionFundsPhase.FINAL);
            session.commit();

            assertEquals(first.getId(), replay.getId());
            assertEquals(new BigDecimal("100.00"), first.getGEarned());
            assertEquals(1L, number("SELECT COUNT(*) FROM topic_edition_funds"
                    + " WHERE topic_id=23 AND snapshot_kind='FINAL'"));
            assertEquals(2L, number("SELECT COUNT(*) FROM topic_edition_funds_attribution"
                    + " WHERE funds_snapshot_id=" + first.getId()));
            assertEquals(40L, number("SELECT earned_amount FROM topic_edition_funds_attribution"
                    + " WHERE funds_snapshot_id=" + first.getId() + " AND source_club_id=7"));
            assertEquals(60L, number("SELECT earned_amount FROM topic_edition_funds_attribution"
                    + " WHERE funds_snapshot_id=" + first.getId() + " AND source_club_id IS NULL"));
            // ★净票数的真库断言：俱乐部 7 名下两张（id=1 付 40、id=3 付 20 又全额退 20），
            //   毛计数 2、净票数 1。承接费按净票数计酬，这条盯着那句
            //   sum(case when greatest(paid - refund, 0) > 0 ...) 在真 MySQL 上算对 ——
            //   它只在真库跑，单测替不了。
            assertEquals(2L, number("SELECT ticket_count FROM topic_edition_funds_attribution"
                    + " WHERE funds_snapshot_id=" + first.getId() + " AND source_club_id=7"));
            assertEquals(1L, number("SELECT net_ticket_count FROM topic_edition_funds_attribution"
                    + " WHERE funds_snapshot_id=" + first.getId() + " AND source_club_id=7"));
        }
    }

    @Test
    void purchaseKindRepairOnlyClassifiesActivityShellTicketsAndNeverSelfPlay() throws Exception {
        execute("INSERT INTO cms_registration(id,owner_type,owner_id,ticket_id,purchase_kind,payment_status,"
                        + "wechat_payment_amount,refund_deadline) VALUES"
                        + "(10,1,23,NULL,NULL,2,69,'2030-01-01 00:00:00'),"
                        + "(11,1,23,701,NULL,2,69,'2030-01-01 00:00:00'),"
                        + "(12,2,501,NULL,NULL,2,69,'2030-01-01 00:00:00'),"
                        + "(13,2,501,701,NULL,2,69,'2030-01-01 00:00:00'),"
                        + "(14,2,502,702,NULL,2,69,'2030-01-01 00:00:00')");

        try (SqlSession session = factory.openSession(true)) {
            int rows = session.getMapper(TopicEditionFundsMapper.class)
                    .classifyUnknownExplorePurchases(
                            TOPIC, ProductType.FREE_EXPLORE, PurchaseKind.EXPLORE_PASS);
            assertEquals(1, rows);
        }

        assertEquals(1L, number("SELECT COUNT(*) FROM cms_registration WHERE id=13 AND purchase_kind=3"));
        assertEquals(0L, number("SELECT COUNT(*) FROM cms_registration WHERE id IN (10,11,12,14)"
                + " AND purchase_kind IS NOT NULL"));
    }

    private void recompute(String key) throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(
                    session.getMapper(TopicEditionFundsMapper.class));
            service.recomputeProvisional(TOPIC, Date.from(Instant.parse("2028-01-01T00:00:00Z")), key, "finance-a");
            session.commit();
        }
    }

    private TopicEditionFundsRawFacts rawFacts() {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(TopicEditionFundsMapper.class).selectProvisionalRawFacts(
                    TOPIC, Date.from(Instant.parse("2028-01-01T00:00:00Z")),
                    ProductType.FREE_EXPLORE, PurchaseKind.EXPLORE_PASS);
        }
    }

    private void insertRegistration(long id, long activityId, Integer purchaseKind,
                                    String cash, String refundDeadline) throws Exception {
        String kind = purchaseKind == null ? "NULL" : purchaseKind.toString();
        execute("INSERT INTO cms_registration(id,owner_type,owner_id,purchase_kind,payment_status,"
                + "wechat_payment_amount,refund_deadline) VALUES (" + id + ",2," + activityId + ","
                + kind + ",2," + cash + ",'" + refundDeadline + "')");
    }

    private long number(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private SqlSessionFactory factory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("batch3-funds-it",
                new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(TopicEditionFundsMapper.class);
        configuration.addMapper(ClubEditionCompensationMapper.class);
        configuration.addMapper(ProductRoleMapper.class);
        parseMapper(configuration, "mapper/business/TopicEditionFundsMapper.xml");
        parseMapper(configuration, "mapper/business/ClubEditionCompensationMapper.xml");
        parseMapper(configuration, "mapper/business/ProductRoleMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void parseMapper(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("找不到 " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
