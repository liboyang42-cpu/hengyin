package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubEditionHours;
import com.chengyinhub.business.domain.ClubExtraHoursApproval;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1/A2 的真实 MySQL 回读：Q 金额 CHECK、一次确认 CAS、EXTRA append-only、冻结计划不可被覆盖。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubEditionCompensationMybatisIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("club_compensation_it");
    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long OPERATOR = 71L;

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
        execute("DROP TABLE IF EXISTS club_payable_batch", "DROP TABLE IF EXISTS club_payable",
                "DROP TABLE IF EXISTS topic_edition_finalization",
                "DROP TABLE IF EXISTS club_quality_decision", "DROP TABLE IF EXISTS club_extra_hours_approval",
                "DROP TABLE IF EXISTS club_edition_compensation_terms",
                "DROP TABLE IF EXISTS club_edition_hours", "DROP TABLE IF EXISTS topic_edition_cost",
                "DROP TABLE IF EXISTS topic_edition_funds", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS sys_role_menu", "DROP TABLE IF EXISTS sys_role",
                "DROP TABLE IF EXISTS sys_menu",
                // funds core 迁移里的 ALTER 需要 cms_registration 先在；只建它用到的那一列。
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, purchase_kind INT NULL) ENGINE=InnoDB",
                "CREATE TABLE sys_menu (menu_id BIGINT PRIMARY KEY AUTO_INCREMENT, menu_name VARCHAR(64),"
                        + " parent_id BIGINT, order_num INT, path VARCHAR(255), component VARCHAR(255),"
                        + " is_frame INT, is_cache INT, menu_type CHAR(1), visible CHAR(1), status CHAR(1),"
                        + " perms VARCHAR(128), icon VARCHAR(128), create_by VARCHAR(64), create_time DATETIME,"
                        + " remark VARCHAR(500)) ENGINE=InnoDB",
                // 四角色基线（migration_web_admin_roles_20260719.sql 建的）在本套里只造它用到的两列，
                // 因为本批迁移要把批次确认/付款登记分派到两个<b>不同</b>角色上。
                "CREATE TABLE sys_role (role_id BIGINT PRIMARY KEY AUTO_INCREMENT, role_name VARCHAR(64),"
                        + " role_key VARCHAR(100), del_flag CHAR(1)) ENGINE=InnoDB",
                "CREATE TABLE sys_role_menu (role_id BIGINT, menu_id BIGINT,"
                        + " PRIMARY KEY (role_id, menu_id)) ENGINE=InnoDB",
                "INSERT INTO sys_role (role_name, role_key, del_flag) VALUES ('财务结算','cy_finance','0')",
                "INSERT INTO sys_role (role_name, role_key, del_flag) VALUES ('日常运营','cy_operator','0')",
                // 父菜单：权限 seed 的 WHERE @cy_comp_parent IS NOT NULL 需要它存在，否则整批权限静默不插。
                "INSERT INTO sys_menu (menu_name, parent_id, menu_type, visible, status, perms, component)"
                        + " VALUES ('主题', 0, 'C', '0', '0', 'business:topic:list', 'business/topic/index')",
                // 主题详情页的准入权限。★这一行不是本批迁移种的 —— 生产库里它早就在
                // （2026-07-17 只读预检实测：menu_id=2008 business:topic:query，父=2007），
                // 本批只是把它<b>授给</b>两个角色。这里照生产形状建出来，否则授权语句会静默插 0 行、
                // 而回读断言正是为了让那种情况变红。
                "INSERT INTO sys_menu (menu_name, parent_id, menu_type, visible, status, perms, component)"
                        + " VALUES ('主题查询', 1, 'F', '0', '0', 'business:topic:query', '')");
        try (Connection connection = dataSource.getConnection()) {
            // club_edition_hours 的四类枚举 / 状态枚举 / 非负 CHECK 都来自 funds core：
            // A1/A2 复用它们，本套跑真迁移就是为了证明复用的是真表，不是测试里另抄一份 DDL。
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_20260815_tandianri_batch3_funds_core.sql"));
            FileSystemResource compensation = new FileSystemResource(
                    "sql/migration_20260816_tandianri_batch3_funds_club_compensation.sql");
            ScriptUtils.executeSqlScript(connection, compensation);
            ScriptUtils.executeSqlScript(connection, compensation);
        }
        // 2026-08-25 承接费换轨的四列。这里【手工补】而不是挂 migration_20260825：
        // 那支迁移还要改 ck_topic_edition_final_amounts（含 ticket_source_amount）与
        // club_payable 的 component 枚举，而本套刻意只建 schema 的一小片、没有那条链。
        // 硬把整支迁移拽进来，等于为了测试去扩迁移的作用面。
        // ★整支迁移的真 MySQL 执行 + 幂等重放由 ClubPayableMybatisIntegrationTest 担保
        //   —— 那一套的前置链是完整的。
        execute("ALTER TABLE topic_edition_cost"
                        + " ADD COLUMN headcount_rate DECIMAL(14,2) NOT NULL DEFAULT 0.00,"
                        + " ADD COLUMN headcount_cap DECIMAL(14,2) NULL",
                "ALTER TABLE club_edition_compensation_terms"
                        + " ADD COLUMN headcount_rate DECIMAL(14,2) NOT NULL DEFAULT 0.00,"
                        + " ADD COLUMN headcount_cap DECIMAL(14,2) NULL");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    /** ★每维只能 0 或 200：中间值在库这一层就写不进来，不只靠 Java。 */
    @Test
    void anIntermediateQualityAmountIsRejectedByTheDatabaseCheck() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_quality_decision (topic_id, club_id, dimension, decision_status, amount,"
                        + " due_at, confirmed_by, confirmed_at) VALUES (23,7,'DELIVERY_SAFETY','CONFIRMED',199,"
                        + "'2030-01-01 00:00:00',71,'2030-01-01 00:00:00')"));
    }

    /**
     * ★已决的质量行必须带理由 —— Java 侧那道 isBlank 是给人话的，这一条是「写不进来」的那道。
     *
     * <p>一次确认不可逆、过错排除能把 0 抬成 200（方向是多付），而 {@code fault_party} 只有两个
     * 枚举值、{@code @Log} 不写请求体：不落 {@code reason} 列，「依据什么这么判」就无处可查。
     */
    @Test
    void aConfirmedDecisionWithoutAReasonIsRejectedByTheDatabaseCheck() throws Exception {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_quality_decision (topic_id, club_id, dimension, decision_status, amount,"
                        + " due_at, confirmed_by, confirmed_at) VALUES (23,7,'DELIVERY_SAFETY','CONFIRMED',200,"
                        + "'2030-01-01 00:00:00',71,'2030-01-01 00:00:00')"));
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_quality_decision (topic_id, club_id, dimension, decision_status, amount,"
                        + " reason, due_at, confirmed_by, confirmed_at) VALUES (23,7,'PLAYER_EXPERIENCE',"
                        + "'CONFIRMED',200,'','2030-01-01 00:00:00',71,'2030-01-01 00:00:00')"));
        // 带了理由就写得进去，并且原样读得回来（只拒空，不是把这条路堵死）。
        execute("INSERT INTO club_quality_decision (topic_id, club_id, dimension, decision_status, amount,"
                + " reason, due_at, confirmed_by, confirmed_at) VALUES (23,7,'CONTENT_REPORT','CONFIRMED',200,"
                + "'商家 8/12 临时闭店，工单 #3312','2030-01-01 00:00:00',71,'2030-01-01 00:00:00')");
        assertEquals("商家 8/12 临时闭店，工单 #3312",
                scalar("select reason from club_quality_decision where dimension = 'CONTENT_REPORT'"));
    }

    @Test
    void anUnknownQualityDimensionIsRejectedByTheDatabaseCheck() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_quality_decision (topic_id, club_id, dimension, decision_status, due_at)"
                        + " VALUES (23,7,'VIBES','PENDING','2030-01-01 00:00:00')"));
    }

    @Test
    void aConfirmedDimensionMustCarryItsAmountVerifierAndTime() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_quality_decision (topic_id, club_id, dimension, decision_status, due_at)"
                        + " VALUES (23,7,'DELIVERY_SAFETY','CONFIRMED','2030-01-01 00:00:00')"));
    }

    /** 一次确认：并发第二次拿到 rows=0。 */
    @Test
    void onlyOneQualityConfirmationWinsTheCas() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            // ¥200 的确认在 CAS 里带一道自己的闸:(#{amount} = 0 or evidence_hash is not null)。
            // 种子行不带证据哈希 ⇒ 第一次 CAS 就是 0 行,这条用例根本走不到「第二次才 0 行」的语义。
            ClubQualityDecision seed = pendingDecision("DELIVERY_SAFETY");
            seed.setEvidenceHash("sha256:交付清单-2026081701");
            mapper.insertQualityDecision(seed);

            assertEquals(1, mapper.confirmQualityCas(TOPIC, CLUB, "DELIVERY_SAFETY",
                    new BigDecimal("200"), "依据现场交付清单逐项核对", OPERATOR, new Date()));
            assertEquals(0, mapper.confirmQualityCas(TOPIC, CLUB, "DELIVERY_SAFETY",
                    new BigDecimal("200"), "依据现场交付清单逐项核对", 72L, new Date()));
        }
    }

    @Test
    void aSecondCurrentDecisionForTheSameDimensionIsRejectedByTheDatabase() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertQualityDecision(pendingDecision("DELIVERY_SAFETY"));
            ClubQualityDecision second = pendingDecision("DELIVERY_SAFETY");
            second.setVersion(2L);

            assertThrows(RuntimeException.class, () -> mapper.insertQualityDecision(second));
        }
    }

    /** ★冻结计划的另一半：残存的 upsert 只改 planned_hours，自报/确认列一个都不许被它覆盖。 */
    @Test
    void thePlannedHoursUpsertNeverOverwritesTheReportedOrConfirmedColumns() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4"));
            assertEquals(1, mapper.reportActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("6"), 88L));

            mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("9"));

            ClubEditionHours row = mapper.selectHoursForClub(TOPIC, CLUB).get(0);
            assertEquals(new BigDecimal("9.00"), row.getPlannedHours());
            assertEquals(new BigDecimal("6.00"), row.getReportedActualHours(), "自报工时不得被计划 upsert 覆盖");
            assertEquals("REPORTED", row.getConfirmStatus(), "确认状态不得被计划 upsert 覆盖");
        }
    }

    /** 确认必须以 REPORTED 为旧状态：没自报过就确认，拿不到行。 */
    @Test
    void confirmationRequiresAPriorReportAsItsCasPrecondition() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4"));

            assertEquals(0, mapper.confirmActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("6"), OPERATOR));
        }
    }

    /** 确认之后不许再自报：CAS 的旧状态集合里没有 CONFIRMED。 */
    @Test
    void reportingIsRefusedOnceTheHoursWereConfirmed() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4"));
            mapper.reportActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("6"), 88L);
            assertEquals(1, mapper.confirmActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("6"), OPERATOR));

            assertEquals(0, mapper.reportActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("99"), 88L));
        }
    }

    @Test
    void theFourHourKindsStayIndependentRowsUnderTheUniqueKey() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            for (String kind : ClubEditionCompensationTerms.HOUR_KINDS) {
                mapper.upsertPlannedHours(TOPIC, CLUB, kind, new BigDecimal("4"));
            }
            List<ClubEditionHours> rows = mapper.selectHoursForClub(TOPIC, CLUB);

            assertEquals(4, rows.size(), "四类各一行，不许被合并成一行");
        }
    }

    /** approved_at 由数据库写：参数里根本没有这一列，「事后补预审批」在 SQL 层就不存在。 */
    @Test
    void theApprovalTimestampIsWrittenByTheDatabaseNotByJava() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubExtraHoursApproval approval = approval("idem-1");
            approval.setApprovedAt(new Date(0L));   // 就算 Java 塞了个 1970，库里也不会用它

            mapper.insertExtraApproval(approval);

            Date stored = mapper.selectExtraApprovals(TOPIC, CLUB).get(0).getApprovedAt();
            assertNotNull(stored);
            org.junit.jupiter.api.Assertions.assertTrue(stored.getTime() > 1_600_000_000_000L,
                    "approved_at 必须是数据库当下的时刻，实际:" + stored);
        }
    }

    @Test
    void aReplayedApprovalIdempotencyKeyIsRejectedByTheDatabase() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertExtraApproval(approval("idem-1"));

            assertThrows(RuntimeException.class, () -> mapper.insertExtraApproval(approval("idem-1")));
        }
    }

    @Test
    void aCorrectionIsANewRowAndTheOriginalStaysUntouched() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertExtraApproval(approval("idem-1"));
            Long originalId = mapper.selectExtraApprovals(TOPIC, CLUB).get(0).getId();
            ClubExtraHoursApproval correction = approval("corr-1");
            correction.setApprovedHours(new BigDecimal("2"));
            correction.setCorrectionOf(originalId);
            mapper.insertExtraApproval(correction);

            List<ClubExtraHoursApproval> all = mapper.selectExtraApprovals(TOPIC, CLUB);
            assertEquals(2, all.size());
            assertEquals(new BigDecimal("3.00"), all.get(0).getApprovedHours(), "原审批行不得被改写");
            assertEquals(originalId, all.get(1).getCorrectionOf());
        }
    }

    /**
     * ★一条审批只能被更正一次：两条兄弟 correction 各自都「没抬高原件」，
     * 合起来却把批准额度按条累加 ⇒ 超额付款。Java 已拒一次，这里证明库这层也拒。
     * 同时证明 correction_of 为 NULL 的普通审批行不受唯一键影响（NULL 在唯一索引里可重复）。
     */
    @Test
    void thesameApprovalCannotBeCorrectedTwiceByTheDatabase() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertExtraApproval(approval("idem-1"));
            mapper.insertExtraApproval(approval("idem-2"));   // correction_of 都是 NULL，不得撞键
            Long originalId = mapper.selectExtraApprovals(TOPIC, CLUB).get(0).getId();
            ClubExtraHoursApproval first = approval("corr-1");
            first.setCorrectionOf(originalId);
            mapper.insertExtraApproval(first);

            ClubExtraHoursApproval sibling = approval("corr-2");
            sibling.setCorrectionOf(originalId);
            assertThrows(RuntimeException.class, () -> mapper.insertExtraApproval(sibling));
            assertEquals(1, mapper.countCorrectionsOf(originalId));
        }
    }

    /**
     * ★归责方必须落库且自洽：排除行没写是谁的过错、或没排除却写了归责方，库这一层就拒。
     *
     * <p>本仓 {@code LogAspect} 已加固到不写请求体，「faultParty 随 @Log 落 sys_oper_log」是假的；
     * 「哪一方的过错让这一维走了排除路径」是这个动作唯一需要留痕的事实。
     */
    @Test
    void anExclusionWithoutAFaultPartyIsRejectedByTheDatabaseCheck() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubQualityDecision noParty = pendingDecision("DELIVERY_SAFETY");
            noParty.setDecisionStatus(ClubQualityDecision.STATUS_CONFIRMED);
            noParty.setAmount(new BigDecimal("200"));
            noParty.setConfirmedBy(OPERATOR);
            noParty.setConfirmedAt(new Date());
            // 已决行必须带理由(ck_club_quality_reason)。不带的话下面那句 assertThrows 会被【理由闸】
            // 接住,而它要点名的是【归责方闸】—— 相邻多闸互相接住,断言看起来绿其实测的是另一道。
            noParty.setReason("现场为商家原因导致交付未达标，按过错方排除");
            noParty.setExcludedByFault(1);

            RuntimeException missingFaultParty = assertThrows(RuntimeException.class,
                    () -> mapper.insertQualityDecision(noParty));
            assertTrue(rootMessage(missingFaultParty).contains("ck_club_quality_fault_party"),
                    "必须是归责方闸拦的,不能被别的 CHECK 顺手接住:" + rootMessage(missingFaultParty));

            noParty.setFaultParty("MERCHANT");
            assertEquals(1, mapper.insertQualityDecision(noParty));
            assertEquals("MERCHANT", mapper.selectCurrentQualityDecisions(TOPIC, CLUB).get(0).getFaultParty());
        }
    }

    /** 没走排除路径却写了归责方同样被拒：这一列只对被排除的行有意义。 */
    @Test
    void aFaultPartyOnANonExcludedDecisionIsRejectedByTheDatabaseCheck() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubQualityDecision stray = pendingDecision("DELIVERY_SAFETY");
            stray.setFaultParty("PLATFORM");

            assertThrows(RuntimeException.class, () -> mapper.insertQualityDecision(stray));
        }
    }

    /**
     * ★批次确认与付款登记必须落在<b>两个不同</b>的仓库权威角色上。
     *
     * <p>只做 Java + SQL CAS + DB CHECK 三层「同一个人两头都签写不进去」是不够的：
     * 新 seed 的这两个权限如果一条角色授权都没有，今天只有超管拿得到 ⇒
     * {@code paid_by <> confirmed_by} 恒不可满足 ⇒ 第一笔真实对公打款登记不进去。
     */
    @Test
    void theBatchConfirmAndPaidPermissionsLandOnTwoDifferentRoles() throws Exception {
        assertEquals("1", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where r.role_key = 'cy_finance' and m.perms = 'business:clubpayable:confirm'"));
        assertEquals("1", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where r.role_key = 'cy_operator' and m.perms = 'business:clubpayable:paid'"));
        assertEquals("0", scalar("select count(*) from sys_role_menu a"
                + " join sys_menu ma on ma.menu_id = a.menu_id"
                + " join sys_role_menu b on b.role_id = a.role_id"
                + " join sys_menu mb on mb.menu_id = b.menu_id"
                + " where ma.perms = 'business:clubpayable:confirm'"
                + " and mb.perms = 'business:clubpayable:paid'"),
                "同一个角色不得同时持有确认与付款登记，否则分离在权限层就已经形同虚设");
    }

    /**
     * ★开售硬闸用的 EXTRA 事前审批权限必须真的落到一个<b>非超管</b>角色上。
     *
     * <p>{@code freezeTermsForSale} 用 {@code ExtraApproverReachability.canApproveExtraHours}
     * 判「冻进 {@code extra_approver_user_id} 的项目负责人按不按得动预批按钮」，而那条判断读的就是
     * {@code selectMenuPermsByUserId}（sys_role_menu → sys_menu.perms）。一条授权行都没有 ⇒
     * 所有非超管都不可达 ⇒ 唯一能通过开售闸的「项目负责人」只剩 userId=1，锁定裁决
     * 「项目负责人事前审批」退化成「超管自己批自己」。
     */
    @Test
    void theExtraApprovalPermissionIsGrantedToARealNonSuperAdminRole() throws Exception {
        assertEquals("1", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where r.role_key = 'cy_operator' and m.perms = 'business:clubextra:approve'"));
        // 只授这一条，别把宽泛的编辑权顺手带出去。
        assertEquals("0", scalar("select count(*) from sys_role_menu rm"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where m.perms in ('business:topic:edit', 'business:topic:add', 'business:topic:remove')"),
                "只补事前审批这一条 F 权限，不得连带授出主题编辑类宽权限");
    }

    /**
     * ★承载四个 A 全部按钮的 {@code /topic/detail} 必须对两个资金角色打得开。
     *
     * <p>路由准入是前端 {@code router/index.js} 里 {@code permissions: ['business:topic:query']}，
     * 由 {@code filterDynamicRoutes → auth.hasPermiOr} 过滤，权限串来自后端
     * {@code selectMenuPermsByUserId}。这一行读不回 1，两个角色登进后台就打不开那张页 ——
     * API 层这一轮修好的「确认人 ≠ 付款登记人」可达性在控制台上仍然点不动。
     */
    @Test
    void bothFundsRolesCanReachTheTopicDetailPageThatCarriesEveryButton() throws Exception {
        assertEquals("2", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where m.perms = 'business:topic:query'"
                + " and r.role_key in ('cy_finance', 'cy_operator')"));
    }

    @Test
    void saleTermsAreUniquePerTopicSoTheyCannotBeRefrozen() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertEquals(1, mapper.insertTerms(terms()));

            assertThrows(RuntimeException.class, () -> mapper.insertTerms(terms()));
            assertEquals(new BigDecimal("40.00"), mapper.selectTerms(TOPIC).getExtraHourRate());
        }
    }

    @Test
    void theFixedCompensationSourceIsTheCurrentCostVersion() throws Exception {
        execute("INSERT INTO topic_edition_cost (topic_id, cost_version, is_current, tax_cost, payment_cost,"
                + " material_cost, kit_cost, insurance_risk_cost, acquisition_cost, platform_labor_cost,"
                + " other_cost, fixed_compensation, bonuses, note) VALUES"
                + " (23,1,0,0,0,0,0,0,0,0,0,999.00,0,'旧版本'),"
                + " (23,2,1,0,0,0,0,0,0,0,0,1500.00,0,'当前版本')");
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);

            assertEquals(new BigDecimal("1500.00"), mapper.selectCurrentFixedCompensation(TOPIC),
                    "只认 is_current=1 那一版，不许读到历史版本");
        }
    }

    private static ClubEditionCompensationTerms terms() {
        ClubEditionCompensationTerms t = new ClubEditionCompensationTerms();
        t.setTopicId(TOPIC);
        t.setExecutingClubId(CLUB);
        t.setFixedCompensation(new BigDecimal("1500.00"));
        t.setExtraHourRate(new BigDecimal("40"));
        t.setQualityDimensionAmount(new BigDecimal("200"));
        t.setQualityDimensions("DELIVERY_SAFETY,PLAYER_EXPERIENCE,CONTENT_REPORT");
        // ck_club_edition_terms_window：证据截止必须晚于履约窗结束（T+7）。
        t.setFulfillmentEndAt(new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000));
        t.setEvidenceDueAt(new Date());
        t.setExtraApproverUserId(OPERATOR);
        // headcount_rate 是 NOT NULL：生产侧唯一写入点 freezeTermsForSale 一定会经
        // freezeHeadcountConfig 落成 0 或真实单价，所以这里【不】在 XML 里 coalesce ——
        // 漏冻就该硬失败，而不是替调用方猜一个 0。
        t.setHeadcountRate(new BigDecimal("0.00"));
        t.setConfigHash("f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0");
        t.setFrozenAt(new Date());
        return t;
    }

    /** 逐层取因,好让断言能点名是哪一条 CHECK 拦的(MyBatis 会把 SQLException 包两层)。 */
    private static String rootMessage(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static ClubQualityDecision pendingDecision(String dimension) {
        ClubQualityDecision d = new ClubQualityDecision();
        d.setTopicId(TOPIC);
        d.setClubId(CLUB);
        d.setDimension(dimension);
        d.setDecisionStatus(ClubQualityDecision.STATUS_PENDING);
        d.setDueAt(new Date());
        d.setVersion(1L);
        d.setCurrent(1);
        d.setExcludedByFault(0);
        return d;
    }

    private static ClubExtraHoursApproval approval(String idempotencyKey) {
        ClubExtraHoursApproval a = new ClubExtraHoursApproval();
        a.setTopicId(TOPIC);
        a.setClubId(CLUB);
        a.setCategory("PREP");
        a.setApprovedHours(new BigDecimal("3"));
        a.setApprovedBy(OPERATOR);
        // insertExtraApproval 的 where 带 work_started_at > CURRENT_TIMESTAMP(3)（根审批的事前性）。
        a.setWorkStartedAt(new Date(System.currentTimeMillis() + 24L * 3600 * 1000));
        a.setIdempotencyKey(idempotencyKey);
        return a;
    }

    private SqlSessionFactory factory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("club-compensation-it",
                new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(ClubEditionCompensationMapper.class);
        String resource = "mapper/business/ClubEditionCompensationMapper.xml";
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

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
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
