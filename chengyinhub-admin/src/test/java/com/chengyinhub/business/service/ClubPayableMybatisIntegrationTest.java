package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubPayable;
import com.chengyinhub.business.domain.ClubPayableBatch;
import com.chengyinhub.business.domain.TopicEditionFinalization;
import com.chengyinhub.business.mapper.ClubPayableMapper;
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

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** F3 的真实 MySQL 回读：FINAL 一期一行、应付按 component 幂等、只追加、批次确认与付款登记分离。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubPayableMybatisIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("club_payable_it");
    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long CONFIRMER = 71L;
    private static final long PAYER = 72L;

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
                "DROP TABLE IF EXISTS club_payable_batch", "DROP TABLE IF EXISTS club_payable",
                "DROP TABLE IF EXISTS topic_edition_finalization",
                "DROP TABLE IF EXISTS club_quality_decision", "DROP TABLE IF EXISTS club_extra_hours_approval",
                "DROP TABLE IF EXISTS club_edition_compensation_terms",
                "DROP TABLE IF EXISTS club_edition_hours", "DROP TABLE IF EXISTS topic_edition_cost",
                "DROP TABLE IF EXISTS topic_edition_funds", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS sys_role_menu", "DROP TABLE IF EXISTS sys_role",
                "DROP TABLE IF EXISTS sys_menu",
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, purchase_kind INT NULL) ENGINE=InnoDB",
                "CREATE TABLE sys_menu (menu_id BIGINT PRIMARY KEY AUTO_INCREMENT, menu_name VARCHAR(64),"
                        + " parent_id BIGINT, order_num INT, path VARCHAR(255), component VARCHAR(255),"
                        + " is_frame INT, is_cache INT, menu_type CHAR(1), visible CHAR(1), status CHAR(1),"
                        + " perms VARCHAR(128), icon VARCHAR(128), create_by VARCHAR(64), create_time DATETIME,"
                        + " remark VARCHAR(500)) ENGINE=InnoDB",
                // 本批迁移要把批次确认/付款登记分派到两个不同角色上，这两张表必须在。
                "CREATE TABLE sys_role (role_id BIGINT PRIMARY KEY AUTO_INCREMENT, role_name VARCHAR(64),"
                        + " role_key VARCHAR(100), del_flag CHAR(1)) ENGINE=InnoDB",
                "CREATE TABLE sys_role_menu (role_id BIGINT, menu_id BIGINT,"
                        + " PRIMARY KEY (role_id, menu_id)) ENGINE=InnoDB");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_20260815_tandianri_batch3_funds_core.sql"));
            FileSystemResource compensation = new FileSystemResource(
                    "sql/migration_20260816_tandianri_batch3_funds_club_compensation.sql");
            ScriptUtils.executeSqlScript(connection, compensation);
            ScriptUtils.executeSqlScript(connection, compensation);
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    "sql/migration_20260817_tandianri_batch3_funds_ticket_source_payable.sql"));
            FileSystemResource closure = new FileSystemResource(
                    "sql/migration_20260817_tandianri_r6_funds_final_closure.sql");
            ScriptUtils.executeSqlScript(connection, closure);
            ScriptUtils.executeSqlScript(connection, closure);
            // 2026-08-25 承接费换轨（HEADCOUNT）：insertFinalization 现在带 headcount_amount，
            // club_payable 的 component 枚举也多一个成员。跑两遍 = 幂等负控。
            FileSystemResource headcount = new FileSystemResource(
                    "sql/migration_20260825_club_headcount_component.sql");
            ScriptUtils.executeSqlScript(connection, headcount);
            ScriptUtils.executeSqlScript(connection, headcount);
            // 2026-08-25 TICKET_SOURCE 退役：摘掉分量枚举与 ticket_source_amount 列。
            // 它的 guard② 断言 club_payable / topic_edition_finalization 为空 —— 本套每个用例
            // 都是全新 schema，正好满足；跑两遍 = 幂等负控。
            FileSystemResource retire = new FileSystemResource(
                    "sql/migration_20260825_ticket_source_retire.sql");
            ScriptUtils.executeSqlScript(connection, retire);
            ScriptUtils.executeSqlScript(connection, retire);
        }
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    /** FINAL 一期一行：并发/重放第二次撞唯一键，绝不产生第二套应付。 */
    @Test
    void aTopicCanOnlyBeFinalizedOnce() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            assertEquals(1, mapper.insertFinalization(finalization()));

            assertThrows(RuntimeException.class, () -> mapper.insertFinalization(finalization()));
            assertEquals(new BigDecimal("400.00"), mapper.selectFinalization(TOPIC).getQualityAmount());
        }
    }

    @Test
    void anUnknownFinalStatusIsRejectedByTheCheckConstraint() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO topic_edition_finalization (topic_id, club_id, final_status, fixed_compensation,"
                        + " quality_amount, extra_amount, payee_profile_id, payee_profile_version,"
                        + " payee_facts_hash, idempotency_key, finalized_by)"
                        + " VALUES (23,7,'DRAFT',0,0,0,9,1,REPEAT('a',64),'k',71)"));
    }

    /** 同一 (topic, club, component, key) 只能有一笔 accrual：FINAL 重放算同一笔。 */
    @Test
    void aReplayedAccrualHitsTheIdempotencyKey() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            assertEquals(1, mapper.insertPayable(accrual("QUALITY", "400.00")));

            assertThrows(RuntimeException.class, () -> mapper.insertPayable(accrual("QUALITY", "400.00")));
        }
    }

    @Test
    void differentComponentsAreSeparateRowsUnderTheSameFinalKey() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("FIXED", "1500.00"));
            mapper.insertPayable(accrual("QUALITY", "400.00"));
            mapper.insertPayable(accrual("EXTRA", "80.00"));
            mapper.insertPayable(accrual("HEADCOUNT", "300.00"));

            assertEquals(4, mapper.selectPayables(TOPIC, CLUB).size(),
                    "F/Q/EXTRA/HEADCOUNT 是四笔不同的经济事实，不许被幂等键合并掉");
        }
    }

    @Test
    void anUnknownComponentIsRejectedByTheCheckConstraint() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable (topic_id, club_id, component, entry_type, amount, idempotency_key,"
                        + " created_by) VALUES (23,7,'TIPS','ACCRUAL',10,'k',71)"));
    }

    /**
     * ★TICKET_SOURCE 退役的负控：它必须像任何未知分量一样被库拒绝。
     *
     * <p>光看代码里删掉了常量证明不了什么 —— 只要 CHECK 还留着这个成员，
     * 任何绕过 service 的直写（DBA 手工、旧版本 jar 回滚、别的分支）就还能把它写进台账。
     * 这条在<b>真 MySQL</b> 上证明枚举本身收窄了。
     */
    @Test
    void theRetiredTicketSourceComponentIsRejectedByTheCheckConstraint() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable (topic_id, club_id, component, entry_type, amount, idempotency_key,"
                        + " created_by) VALUES (23,7,'TICKET_SOURCE','ACCRUAL',10,'ts-retired',71)"));
    }

    /** 同一条 CHECK 的正面：退役后仍在用的四个分量必须照常写得进去（防止收窄收过头）。 */
    @Test
    void theFourSurvivingComponentsAreStillAccepted() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            for (String component : new String[] {"FIXED", "QUALITY", "EXTRA", "HEADCOUNT"}) {
                mapper.insertPayable(accrual(component, "1.00"));
            }
            assertEquals(4, mapper.selectPayables(TOPIC, CLUB).size());
        }
    }

    /** 只追加的形状写进 CHECK：正向必须 ≥0、红冲必须 ≤0。 */
    @Test
    void aNegativeAccrualAndAPositiveReversalAreBothRejected() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable (topic_id, club_id, component, entry_type, amount, idempotency_key,"
                        + " created_by) VALUES (23,7,'QUALITY','ACCRUAL',-1,'k1',71)"));
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable (topic_id, club_id, component, entry_type, amount, idempotency_key,"
                        + " created_by) VALUES (23,7,'QUALITY','REVERSAL',1,'k2',71)"));
    }

    @Test
    void aReversalNetsTheAccrualOutWithoutTouchingIt() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("QUALITY", "400.00"));
            ClubPayable original = mapper.selectPayables(TOPIC, CLUB).get(0);
            ClubPayable reversal = accrual("QUALITY", "-400.00");
            reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
            reversal.setIdempotencyKey("REVERSAL:" + original.getId());
            reversal.setReversalOf(original.getId());
            reversal.setReason("复核后撤销");
            mapper.insertPayable(reversal);

            List<ClubPayable> rows = mapper.selectPayables(TOPIC, CLUB);
            BigDecimal net = rows.get(0).getAmount().add(rows.get(1).getAmount());
            assertEquals(0, BigDecimal.ZERO.compareTo(net));
            assertEquals(new BigDecimal("400.00"), rows.get(0).getAmount(), "原 accrual 一分不许被改");
            assertEquals("复核后撤销", rows.get(1).getReason(), "红冲理由必须落在台账行上");
        }
    }

    /** ★无因由的负向资金行在库这一层就写不进来（ck_club_payable_reason）。 */
    @Test
    void aReversalWithoutAReasonIsRejectedByTheDatabaseCheck() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable (topic_id, club_id, component, entry_type, amount, idempotency_key,"
                        + " created_by) VALUES (23,7,'QUALITY','REVERSAL',-400.00,'REVERSAL:X',71)"));
    }

    /**
     * ★E4「一期一批次一张发票」：同一俱乐部同一期只能有一张批次。
     *
     * <p>没有这把唯一键，两期两份合同的钱可以被分别/合并开成任意张付款单，
     * 「批次 ↔ 发票 ↔ 合同」的一一对应在库里就没有承载物。
     */
    @Test
    void oneClubGetsExactlyOneBatchPerEdition() throws Exception {
        execute("INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                + " invoice_status) VALUES ('CPB-A',7,23,100,'PENDING','NONE')");

        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                        + " invoice_status) VALUES ('CPB-B',7,23,200,'PENDING','NONE')"));

        // 同一俱乐部的另一期照常可以自己开一张
        execute("INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                + " invoice_status) VALUES ('CPB-C',7,24,200,'PENDING','NONE')");
    }

    /** 发票轴自洽：ISSUED 必须有票号，RED 必须两个号都有。 */
    @Test
    void theInvoiceAxisIsSelfConsistentAtTheDatabaseLevel() throws Exception {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                        + " invoice_status) VALUES ('CPB-D',7,25,100,'PENDING','ISSUED')"));
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                        + " invoice_status, invoice_no) VALUES ('CPB-E',7,26,100,'PENDING','RED','INV-1')"));

        // 已打款但未开票是合法状态：两条轴互不引用
        execute("INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                + " confirmed_by, confirmed_at, paid_by, paid_at, payment_reference, invoice_status)"
                + " VALUES ('CPB-F',7,27,100,'PAID',71,NOW(3),72,NOW(3),'TX','NONE')");
    }

    /**
     * ★红冲的锁定读<b>真的</b>拦住并发的批次挂靠 —— 这一条只有真 InnoDB 证得了。
     *
     * <p>缺这把行锁时的交错：T1 用快照读看到 {@code batch_id is null} → T2 建批次并
     * {@code attachUnbatchedPayables} 命中 1 行 == 它自己算的 pending 数、<b>提交</b> →
     * T1 再插入反向行（纯 INSERT，不与 T2 冲突）、提交 ⇒ 正额进了付款单、抵消它的反向行落在
     * 批次之外 ⇒ 多付一笔，零告警。
     *
     * <p>本用例把 T1 的锁定读真的拿住不放，再让 T2 去 attach：InnoDB 必须让 T2 等到锁超时。
     * 等不到超时（= 没拿到锁）就说明那条 {@code for update} 没生效。
     */
    @Test
    void aLockedPayableBlocksAConcurrentBatchAttach() throws Exception {
        try (SqlSession seed = factory.openSession(true)) {
            seed.getMapper(ClubPayableMapper.class).insertPayable(accrual("FIXED", "1500.00"));
        }
        try (Connection t1 = dataSource.getConnection(); Connection t2 = dataSource.getConnection()) {
            t1.setAutoCommit(false);
            Long payableId;
            try (Statement s = t1.createStatement()) {
                s.execute("SELECT id FROM club_payable WHERE topic_id = " + TOPIC + " FOR UPDATE");
                try (java.sql.ResultSet rs = s.getResultSet()) {
                    rs.next();
                    payableId = rs.getLong(1);
                }
            }
            assertEquals(true, payableId > 0);

            t2.setAutoCommit(false);
            try (Statement s = t2.createStatement()) {
                s.execute("SET SESSION innodb_lock_wait_timeout = 1");
                SQLException blocked = assertThrows(SQLException.class, () -> s.executeUpdate(
                        "UPDATE club_payable SET batch_id = 1 WHERE topic_id = " + TOPIC
                                + " AND club_id = " + CLUB + " AND batch_id IS NULL"));
                assertEquals(true, String.valueOf(blocked.getMessage()).toLowerCase().contains("lock"),
                        "并发挂靠必须被红冲持有的行锁拦下，实际报的是：" + blocked.getMessage());
            } finally {
                t2.rollback();
            }
            t1.rollback();
        }
    }

    /**
     * ★重计提持有的 component 行锁必须挡住并发建批次的挂靠 —— 与红冲那条同一把尺子。
     *
     * <p>没有这条行锁时的真实交错：台账现有 FIXED 一条 P = +2000 未进批次，当下重算应为 1500。
     * T1 {@code reaccrueAfterReversal} 快照读得 [P]、{@code batch_id} 为空 ⇒ 过守卫，算出 −500；
     * T2 {@code createBatch} 同样读到 [P]（pending.size()=1）→ 插批次 →
     * {@code attachUnbatchedPayables} 命中 1 行 == 1 ⇒ {@code CLUB_PAYABLE_BATCH_CONTENT_CHANGED}
     * <b>不触发</b> ⇒ 提交，批次 total = 2000；T1 再插入 −500（纯 INSERT，不与行锁冲突）⇒ 提交。
     * 批次付 2000，台账真实净额 1500，抵消它的 −500 落在批次<b>之外</b> ⇒ <b>多付 500</b>，零告警。
     * FINAL 头的行锁 serialize 不了它：{@code createBatch} 全程不碰 {@code topic_edition_finalization}。
     *
     * <p>本用例把 T1 的锁定读真的拿住不放，再让 T2 去 attach：InnoDB 必须让 T2 等到锁超时。
     * 等不到超时（= 没拿到锁）就说明 {@code selectPayablesByComponentForUpdate} 那条
     * {@code for update} 没生效，多付的窗口还开着。
     */
    @Test
    void aReaccrualHoldsItsComponentRowsAgainstAConcurrentBatchAttach() throws Exception {
        try (SqlSession seed = factory.openSession(true)) {
            seed.getMapper(ClubPayableMapper.class).insertPayable(accrual("FIXED", "2000.00"));
        }
        try (Connection t1 = dataSource.getConnection(); Connection t2 = dataSource.getConnection()) {
            t1.setAutoCommit(false);
            // T1 = reaccrueAfterReversal 的 appendDelta 那一步：按 component 锁定读整批行。
            // ⚠️ 这个 SqlSession 【不能】用 try-with-resources：MyBatis 的 openSession(Connection)
            // 在 close() 时会 resetAutoCommit 并把这条外部连接一起关掉 —— 行锁当场释放，
            // 下面 T2 的 UPDATE 就畅通无阻，assertThrows 变成「什么都没抛」。
            // 锁必须一直持到 T2 断言完成，所以放到 finally 里关。
            SqlSession locked = factory.openSession(t1);
            try {
                List<ClubPayable> rows = locked.getMapper(ClubPayableMapper.class)
                        .selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED");
                assertEquals(1, rows.size());
                assertNull(rows.get(0).getBatchId(), "守卫读的就是这一列");

                t2.setAutoCommit(false);
                try (Statement s = t2.createStatement()) {
                    s.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    SQLException blocked = assertThrows(SQLException.class, () -> s.executeUpdate(
                            "UPDATE club_payable SET batch_id = 1 WHERE topic_id = " + TOPIC
                                    + " AND club_id = " + CLUB + " AND batch_id IS NULL"));
                    assertEquals(true, String.valueOf(blocked.getMessage()).toLowerCase().contains("lock"),
                            "并发挂靠必须被重计提持有的行锁拦下，实际报的是：" + blocked.getMessage());
                } finally {
                    t2.rollback();
                }
            } finally {
                // 经 session 回滚再关：close() 会把 t1 一并关掉，所以这之后不能再 t1.rollback()
                // （会报 "No operations allowed after connection closed"）。
                locked.rollback();
                locked.close();
            }
        }
    }

    /** 批次只收还没进批次的行；已进批次的不会被第二个批次抢走。 */
    @Test
    void aBatchOnlyCapturesRowsThatAreNotYetBatched() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("FIXED", "1500.00"));
            mapper.insertPayable(accrual("QUALITY", "400.00"));
            mapper.insertBatch(batch("CPB-1", "1900.00"));
            Long batchId = firstBatchId();

            assertEquals(2, mapper.attachUnbatchedPayables(batchId, TOPIC, CLUB));
            assertEquals(0, mapper.selectUnbatchedPayables(TOPIC, CLUB).size());
            assertEquals(0, mapper.attachUnbatchedPayables(batchId, TOPIC, CLUB), "第二次没有可收的行");
        }
    }

    @Test
    void confirmationOnlyWinsFromDraft() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertBatch(batch("CPB-1", "1900.00"));
            Long batchId = firstBatchId();

            assertEquals(1, mapper.confirmBatchCas(batchId, CONFIRMER, new Date()));
            assertEquals(0, mapper.confirmBatchCas(batchId, PAYER, new Date()), "已确认的批次不许再确认一次");
        }
    }

    /** ★确认人自己来登记付款：CAS 命中 0 行。 */
    @Test
    void theConfirmerCannotRecordThePaymentThemselves() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertBatch(batch("CPB-1", "1900.00"));
            Long batchId = firstBatchId();
            mapper.confirmBatchCas(batchId, CONFIRMER, new Date());

            assertEquals(0, mapper.markBatchPaidCas(batchId, CONFIRMER, new Date(), "TX-1"));
            assertEquals(ClubPayableBatch.STATUS_CONFIRMED, mapper.selectBatch(batchId).getBatchStatus());
            assertNull(mapper.selectBatch(batchId).getPaidBy());
        }
    }

    @Test
    void aDifferentPersonCanRecordThePaymentOnAConfirmedBatch() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertBatch(batch("CPB-1", "1900.00"));
            Long batchId = firstBatchId();
            mapper.confirmBatchCas(batchId, CONFIRMER, new Date());

            assertEquals(1, mapper.markBatchPaidCas(batchId, PAYER, new Date(), "TX-1"));
            ClubPayableBatch paid = mapper.selectBatch(batchId);
            assertEquals(ClubPayableBatch.STATUS_PAID, paid.getBatchStatus());
            assertEquals(PAYER, paid.getPaidBy());
        }
    }

    /** 就算有人绕过 CAS 直接写库，DB CHECK 仍然不让同一个人两头都签。 */
    @Test
    void theDatabaseItselfRefusesASelfConfirmedSelfPaidBatch() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                        + " confirmed_by, confirmed_at, paid_by, paid_at, payment_reference)"
                        + " VALUES ('CPB-X',7,90,100,'PAID',71,NOW(3),71,NOW(3),'TX')"));
    }

    @Test
    void aPaidBatchWithoutItsPaymentReferenceIsRejected() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_payable_batch (batch_no, club_id, topic_id, total_amount, batch_status,"
                        + " confirmed_by, confirmed_at, paid_by, paid_at)"
                        + " VALUES ('CPB-Y',7,91,100,'PAID',71,NOW(3),72,NOW(3))"));
    }

    private Long firstBatchId() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery("select min(id) from club_payable_batch")) {
            result.next();
            return result.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("读不到批次 id", e);
        }
    }

    private static TopicEditionFinalization finalization() {
        TopicEditionFinalization f = new TopicEditionFinalization();
        f.setTopicId(TOPIC);
        f.setClubId(CLUB);
        f.setFinalStatus(TopicEditionFinalization.STATUS_FINAL);
        f.setFixedCompensation(new BigDecimal("1500.00"));
        f.setQualityAmount(new BigDecimal("400.00"));
        f.setExtraAmount(new BigDecimal("80.00"));
        // headcount_amount 是 NOT NULL 且【不给默认】：漏设就在库这一层硬失败，
        // 而不是静默落 0。生产侧由 facts.getHeadcount() 保证非空。
        f.setHeadcountAmount(new BigDecimal("300.00"));
        f.setPayeeProfileId(9L);
        f.setPayeeProfileVersion(1L);
        f.setPayeeFactsHash("f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0");
        f.setIdempotencyKey("FINAL:" + TOPIC);
        f.setFinalizedBy(CONFIRMER);
        return f;
    }

    private static ClubPayable accrual(String component, String amount) {
        ClubPayable p = new ClubPayable();
        p.setTopicId(TOPIC);
        p.setClubId(CLUB);
        p.setComponent(component);
        p.setEntryType(ClubPayable.ENTRY_ACCRUAL);
        p.setAmount(new BigDecimal(amount));
        p.setIdempotencyKey("FINAL:" + TOPIC);
        p.setCreatedBy(CONFIRMER);
        return p;
    }

    private static ClubPayableBatch batch(String batchNo, String total) {
        ClubPayableBatch b = new ClubPayableBatch();
        b.setBatchNo(batchNo);
        b.setClubId(CLUB);
        b.setTopicId(TOPIC);
        b.setTotalAmount(new BigDecimal(total));
        b.setBatchStatus(ClubPayableBatch.STATUS_PENDING);
        b.setInvoiceStatus(ClubPayableBatch.INVOICE_NONE);
        return b;
    }

    private SqlSessionFactory factory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("club-payable-it",
                new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(ClubPayableMapper.class);
        String resource = "mapper/business/ClubPayableMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("找不到 " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
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
