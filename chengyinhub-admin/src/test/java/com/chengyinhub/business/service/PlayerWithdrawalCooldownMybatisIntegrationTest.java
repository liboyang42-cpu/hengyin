package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerWithdrawalCooldown;
import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 的真实 MySQL 回读：起点取数据库时钟、47:59 拒 / 48:00 放、
 * 一明细一冷静期、恢复流水不注册。
 *
 * <p>★边界必须在真库里比才算数：H2 的时间函数与 MySQL 的 CURRENT_TIMESTAMP(3) 不是一回事。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerWithdrawalCooldownMybatisIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("player_cooldown_it");
    private static final long MEMBER = 100L;
    private static final long OTHER_MEMBER = 200L;
    private static final String POLICY = "withdrawal.player-complaint-cooldown-hours";

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
        execute("DROP TABLE IF EXISTS player_withdrawal_cooldown",
                "DROP TABLE IF EXISTS player_withdrawal_cooldown_config_audit",
                "DROP TABLE IF EXISTS ums_member_balance_detail",
                "DROP TABLE IF EXISTS sys_config",
                "DROP TABLE IF EXISTS sys_role_menu", "DROP TABLE IF EXISTS sys_role",
                "DROP TABLE IF EXISTS sys_menu",
                "CREATE TABLE ums_member_balance_detail (id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL,"
                        + " event_type BIGINT NOT NULL, event_id BIGINT NULL, change_type BIGINT NOT NULL,"
                        + " change_balance DECIMAL(14,2) NULL, create_time DATETIME(3) NOT NULL) ENGINE=InnoDB",
                "CREATE TABLE sys_config (config_id BIGINT PRIMARY KEY AUTO_INCREMENT, config_name VARCHAR(100),"
                        + " config_key VARCHAR(100), config_value VARCHAR(500), config_type CHAR(1),"
                        + " create_by VARCHAR(64), create_time DATETIME, remark VARCHAR(500)) ENGINE=InnoDB",
                "CREATE TABLE sys_menu (menu_id BIGINT PRIMARY KEY AUTO_INCREMENT, menu_name VARCHAR(64),"
                        + " parent_id BIGINT, order_num INT, path VARCHAR(255), component VARCHAR(255),"
                        + " is_frame INT, is_cache INT, menu_type CHAR(1), visible CHAR(1), status CHAR(1),"
                        + " perms VARCHAR(128), icon VARCHAR(128), create_by VARCHAR(64), create_time DATETIME,"
                        + " remark VARCHAR(500)) ENGINE=InnoDB",
                // 专用改写权限要真的授到一个角色上，否则它又是一颗谁也拿不到的功能位。
                "CREATE TABLE sys_role (role_id BIGINT PRIMARY KEY AUTO_INCREMENT, role_name VARCHAR(64),"
                        + " role_key VARCHAR(100), del_flag CHAR(1)) ENGINE=InnoDB",
                "CREATE TABLE sys_role_menu (role_id BIGINT, menu_id BIGINT,"
                        + " PRIMARY KEY (role_id, menu_id)) ENGINE=InnoDB",
                "INSERT INTO sys_role (role_name, role_key, del_flag) VALUES ('财务结算','cy_finance','0')",
                // 父菜单必须在，否则功能位不会被种（宁可没有，也不裸挂）。
                "INSERT INTO sys_menu (menu_name, parent_id, menu_type, visible, status, perms)"
                        + " VALUES ('玩家提现','0','C','0','0','business:memberwithdrawal:list')");
        try (Connection connection = dataSource.getConnection()) {
            FileSystemResource migration = new FileSystemResource(
                    "sql/migration_20260816_tandianri_batch3_funds_player_cooldown.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void theSeededPolicyIsFortyEightHours() throws Exception {
        assertEquals("48", scalar("select config_value from sys_config where config_key='" + POLICY + "'"));
    }

    /**
     * ★{@code business:withdrawal:cooldown-config} 现在是一条<b>有真实消费方</b>的权限，
     * 而且真的授到了一个角色上。
     *
     * <p>它曾经是零消费方的假功能位（remark 写成闸、代码里没有任何 @PreAuthorize 引用），
     * 上一版按 E3「宁可明确没有」把它删了。这一版补上了当时如实登记为「没做」的那一半 ——
     * {@code PlayerWithdrawalCooldownController#rewrite} 挂的就是这条权限，且改写会把
     * 前值/后值/理由/操作人写进 {@code player_withdrawal_cooldown_config_audit}。
     *
     * <p>⚠️ 只种菜单不授权，等于又造一颗谁也拿不到的功能位，所以这里两条都断言。
     */
    @Test
    void theDedicatedCooldownRewritePermissionExistsAndIsGrantedToARealRole() throws Exception {
        assertEquals("1", scalar("select count(*) from sys_menu"
                + " where perms = 'business:withdrawal:cooldown-config'"
                + " and menu_type = 'F' and status = '0'"),
                "专用改写权限必须真的种进菜单");
        assertEquals("1", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where r.role_key = 'cy_finance' and m.perms = 'business:withdrawal:cooldown-config'"),
                "只有菜单行没有授权 = 又一颗谁也拿不到的功能位");
    }

    /**
     * ★改写审计表真的存在，且「小时数必须为正」写在 DB CHECK 上。
     *
     * <p>把 48 改成 0 = 冷静期悄悄消失。Java 侧已经拒了，这里让库也拒 —— 两层各自独立。
     */
    @Test
    void theRewriteAuditTableRefusesANonPositiveHourCount() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO player_withdrawal_cooldown_config_audit"
                        + " (config_key, before_hours, after_hours, reason, changed_by, changed_at)"
                        + " VALUES ('" + POLICY + "', 48, 0, '把冷静期关掉', 71, CURRENT_TIMESTAMP(3))"));
    }

    /** 改写的四样事实（前值/后值/理由/操作人）逐行读得回来 —— 这才叫留痕。 */
    @Test
    void aRewriteAuditRowKeepsBeforeAfterReasonAndOperator() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(1, mapper.insertConfigChangeAudit(48, 24, "投诉争议期按法务意见收窄", 71L));
        }
        assertEquals("48", scalar("select before_hours from player_withdrawal_cooldown_config_audit"));
        assertEquals("24", scalar("select after_hours from player_withdrawal_cooldown_config_audit"));
        assertEquals("投诉争议期按法务意见收窄",
                scalar("select reason from player_withdrawal_cooldown_config_audit"));
        assertEquals("71", scalar("select changed_by from player_withdrawal_cooldown_config_audit"));
    }

    /**
     * ★起点与到期都由数据库时钟给，不是任何 Java 时刻、也不是那条明细的 create_time。
     *
     * <p>fixture 把明细的 create_time 造在 100 小时前 —— 如果起点回退去读那一列，
     * created_at 会落在 100 小时前而不是「现在」。那一列承载的是应用机的 JVM 时刻
     * （通用插入面显式写入），拿它当起点、拿库时钟做到期比较就是跨时钟比较。
     */
    @Test
    void theCooldownWindowIsDerivedFromTheDatabaseClockNotTheDetailCreateTime() {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-100 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(1, mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L,
                    new BigDecimal("50"), POLICY, 48));

            PlayerWithdrawalCooldown row = mapper.selectByMember(MEMBER).get(0);
            long window = row.getCooldownUntil().getTime() - row.getCreatedAt().getTime();
            assertEquals(48L * 3600L * 1000L, window, "窗口必须正好是 48 小时");
            long detailAgeMillis = System.currentTimeMillis() - row.getCreatedAt().getTime();
            assertTrue(detailAgeMillis < 10L * 60L * 1000L,
                    "起点必须是本次插入时的库时钟；落到 100 小时前说明它又去读明细的 create_time 了，实际偏移(ms)="
                            + detailAgeMillis);
        }
    }

    /** 一条明细只能产生一个冷静期：并发/重放 advance 撞唯一键。 */
    @Test
    void theSameBalanceDetailCanOnlyProduceOneCooldown() {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48);

            assertThrows(RuntimeException.class, () -> mapper.insertForPositiveComplaintDetail(
                    1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48));
        }
    }

    /** 一张工单只能产生一笔正向补偿冷静期：同一投诉重放成第二笔也被堵死。 */
    @Test
    void theSameComplaintCanOnlyProduceOneCooldownEvenViaAnotherDetail() {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        givenComplaintDetail(2L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48);

            assertThrows(RuntimeException.class, () -> mapper.insertForPositiveComplaintDetail(
                    2L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48));
        }
    }

    /** ★提现驳回退款(event_type=4)不得注册冷静期，否则恢复流水会把冷静期重启。 */
    @Test
    void aWithdrawalRefundRowNeverRegistersACooldown() {
        givenComplaintDetail(1L, MEMBER, 4L, 1L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);

            assertEquals(0, mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L,
                    new BigDecimal("50"), POLICY, 48), "event_type=4 不是投诉正向补偿");
            assertTrue(mapper.selectByMember(MEMBER).isEmpty());
        }
    }

    /** 扣划那一侧(change_type=2)也不注册：冷静期只跟踪加钱的正向补偿。 */
    @Test
    void theDeductionSideOfAComplaintNeverRegistersACooldown() {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 2L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(0, session.getMapper(PlayerWithdrawalCooldownMapper.class)
                    .insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48));
        }
    }

    @Test
    void aDetailBelongingToAnotherMemberIsNeverRegisteredUnderThisOne() {
        givenComplaintDetail(1L, OTHER_MEMBER, 9L, 1L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(0, session.getMapper(PlayerWithdrawalCooldownMapper.class)
                    .insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48));
        }
    }

    /**
     * ★47:59:59 —— 还差 1 秒到期，钱必须仍被冻住。
     *
     * <p>⚠️ 前置必须移【冷静期行】而不是余额明细：起点是入库时刻的库时钟
     * （{@code insertForPositiveComplaintDetail} 刻意不取 {@code d.create_time}，
     * 见该 mapper 注释——用应用机时钟当起点、库机时钟当比较，漂移会把冻结款提前放走）。
     * 原写法把余额明细往前挪 172799 秒，而那一列根本不参与判定 ⇒ 本用例曾是【恒绿】：
     * 无论挪多少，cooldown_until 都是「入库时刻 + 48h」，永远大于 now。
     */
    @Test
    void oneSecondBeforeTheDeadlineTheMoneyIsStillFrozen() throws Exception {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48);
            expireCooldownAt(1L, "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");

            assertEquals(0, new BigDecimal("50").compareTo(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)),
                    "47:59:59 必须拒绝提现");
        }
    }

    /**
     * ★48:00:00 —— 到点即放行。
     *
     * <p>⚠️ 本用例证明的是「已到期 ⇒ 放行」，<b>不</b>证明比较用的是 {@code >} 而不是 {@code >=}：
     * 2026-08-17 实测把 mapper 的 {@code >} 改成 {@code >=}，本套 16 条无一变红 ——
     * 因为断言那一刻 now 已经走过 cooldown_until，两种写法结果相同。
     * 毫秒级的严格/非严格之分在这个接缝上不可观测，别把它当成有闸守着。
     */
    @Test
    void atTheDeadlineTheMoneyIsReleased() throws Exception {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("50"), POLICY, 48);
            // 到期时刻【本身】：此后 now 只会更大，cooldown_until > now 恒假 ⇒ 必须放行。
            expireCooldownAt(1L, "CURRENT_TIMESTAMP(3)");

            assertEquals(0, BigDecimal.ZERO.compareTo(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)),
                    "48:00 整必须允许提现");
        }
    }

    /** 一笔已到期 + 一笔未到期并存时，只冻住未到期那笔。 */
    @Test
    void onlyTheUnexpiredEventIsSummed() throws Exception {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        givenComplaintDetail(2L, MEMBER, 9L, 2L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("30"), POLICY, 48);
            mapper.insertForPositiveComplaintDetail(2L, MEMBER, 2L, new BigDecimal("20"), POLICY, 48);
            expireCooldownAt(1L, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");   // 只让第 1 笔到期

            assertEquals(0, new BigDecimal("20").compareTo(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)));
        }
    }

    /** 跨会员绝不串：A 的提现不许汇总到 B 的冷静额。 */
    @Test
    void oneMembersCooldownNeverLeaksIntoAnothers() {
        givenComplaintDetail(1L, MEMBER, 9L, 1L, 1L, "-1 HOUR");
        givenComplaintDetail(2L, OTHER_MEMBER, 9L, 2L, 1L, "-1 HOUR");
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            mapper.insertForPositiveComplaintDetail(1L, MEMBER, 1L, new BigDecimal("30"), POLICY, 48);
            mapper.insertForPositiveComplaintDetail(2L, OTHER_MEMBER, 2L, new BigDecimal("70"), POLICY, 48);

            assertEquals(0, new BigDecimal("30").compareTo(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)));
            assertEquals(0, new BigDecimal("70").compareTo(
                    mapper.sumUnexpiredFrozenByMemberAtDbNow(OTHER_MEMBER)));
        }
    }

    @Test
    void aNonPositiveAmountIsRejectedByTheCheckConstraint() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO player_withdrawal_cooldown (balance_detail_id, member_id, complaint_id, amount,"
                        + " policy_key, cooldown_hours, created_at, cooldown_until)"
                        + " VALUES (9,100,9,0,'k',48,NOW(3),DATE_ADD(NOW(3), INTERVAL 48 HOUR))"));
    }

    @Test
    void aBackwardsCooldownWindowIsRejectedByTheCheckConstraint() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO player_withdrawal_cooldown (balance_detail_id, member_id, complaint_id, amount,"
                        + " policy_key, cooldown_hours, created_at, cooldown_until)"
                        + " VALUES (9,100,9,50,'k',48,NOW(3),DATE_SUB(NOW(3), INTERVAL 1 HOUR))"));
    }

    /**
     * 把某条已登记冷静期的到期时刻改写成给定的库端表达式。
     *
     * <p>冷静期的起点与到期都由库时钟在 insert 那一刻算出，之后就是一条不可变事实；
     * 因此「已到期 / 还差一点到期」这类边界前置只能改写这一行，改余额明细的 create_time 无效。
     * 传的是 SQL 表达式而不是 Java 时间：Java 侧算时间就又把应用机时钟引回来了。
     */
    private void expireCooldownAt(long balanceDetailId, String dbExpression) throws Exception {
        // created_at 必须跟着往前挪：库上有 ck_player_cooldown_window CHECK (cooldown_until > created_at)，
        // 只改到期时刻会撞该约束 —— 那条约束是对的，前置构造必须落在它允许的形状里。
        execute("UPDATE player_withdrawal_cooldown"
                + " SET created_at = DATE_SUB(" + dbExpression + ", INTERVAL cooldown_hours HOUR),"
                + "     cooldown_until = " + dbExpression
                + " WHERE balance_detail_id = " + balanceDetailId);
    }

    private void givenComplaintDetail(long id, long memberId, long eventType, long eventId, long changeType,
                                      String createTimeOffset) {
        try {
            execute("INSERT INTO ums_member_balance_detail (id, member_id, event_type, event_id, change_type,"
                    + " change_balance, create_time) VALUES (" + id + "," + memberId + "," + eventType + ","
                    + eventId + "," + changeType + ",50.00, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL "
                    + createTimeOffset + "))");
        } catch (Exception e) {
            throw new IllegalStateException("fixture 写入失败", e);
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private SqlSessionFactory factory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("player-cooldown-it",
                new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(PlayerWithdrawalCooldownMapper.class);
        String resource = "mapper/business/PlayerWithdrawalCooldownMapper.xml";
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
