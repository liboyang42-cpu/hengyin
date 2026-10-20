package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.mapper.ClubCorporatePayeeProfileMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.ClubCorporatePayeeServiceImpl;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 对公收款人的真实 MySQL 回读：密文落库、唯一 current、审核 CAS、快照不可变。
 * 类名以 Test 结尾但仍由资金 gate 显式点名执行（仓库没有 surefire/failsafe 配置）。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubCorporatePayeeMybatisIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("club_payee_it");
    private static final long CLUB = 7L;
    private static final long TOPIC = 23L;
    private static final String CREDIT = "91310000MA1FL0AB2C";

    private DataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeAll
    void createDatabase() throws Exception {
        // TypeHandler 由 MyBatis 实例化，靠静态单例拿密钥；不配就 fail-closed 拒写，那样本套证明不了密文。
        new PiiCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "club-payee-it-hmac").register();
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
        execute("DROP TABLE IF EXISTS topic_sale_payee_snapshot",
                "DROP TABLE IF EXISTS club_corporate_payee_profile",
                "DROP TABLE IF EXISTS sys_role_menu", "DROP TABLE IF EXISTS sys_role",
                "DROP TABLE IF EXISTS sys_menu",
                "CREATE TABLE sys_menu (menu_id BIGINT PRIMARY KEY AUTO_INCREMENT, menu_name VARCHAR(64),"
                        + " parent_id BIGINT, order_num INT, path VARCHAR(255), component VARCHAR(255),"
                        + " is_frame INT, is_cache INT, menu_type CHAR(1), visible CHAR(1), status CHAR(1),"
                        + " perms VARCHAR(128), icon VARCHAR(128), create_by VARCHAR(64), create_time DATETIME,"
                        + " remark VARCHAR(500)) ENGINE=InnoDB",
                // 本批迁移要把「财务审核」与「受控解密」分派到两个不同角色上，这两张表必须在。
                "CREATE TABLE sys_role (role_id BIGINT PRIMARY KEY AUTO_INCREMENT, role_name VARCHAR(64),"
                        + " role_key VARCHAR(100), del_flag CHAR(1)) ENGINE=InnoDB",
                "CREATE TABLE sys_role_menu (role_id BIGINT, menu_id BIGINT,"
                        + " PRIMARY KEY (role_id, menu_id)) ENGINE=InnoDB",
                "INSERT INTO sys_role (role_name, role_key, del_flag) VALUES"
                        + " ('日常运营','cy_operator','0'),('财务结算','cy_finance','0')",
                // 一个「本不该同时持两权」的角色状态：迁移的窄范围 DELETE 必须把它清成互斥。
                "INSERT INTO sys_menu (menu_name, parent_id, menu_type, visible, status, perms)"
                        + " VALUES ('俱乐部','0','C','0','0','business:club:list')");
        try (Connection connection = dataSource.getConnection()) {
            FileSystemResource migration =
                    new FileSystemResource("sql/migration_20260816_tandianri_batch3_funds_club_payee.sql");
            // 跑两遍：迁移必须幂等，第二遍不许炸。
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
    void aVersionedProfileIsWrittenAndReadBackThroughTheMapper() {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertEquals(1, mapper.insertVersion(profile(1L, CREDIT)));

            ClubCorporatePayeeProfile readback = mapper.selectCurrentForUpdate(CLUB);
            assertEquals("上海城瘾探索者体育文化有限公司", readback.getLegalName());
            assertEquals(CREDIT, readback.getUnifiedSocialCreditCode());
            assertEquals("13800001234", readback.getFinanceContactPhone());
        }
    }

    /** ★九组敏感值一个都不许以明文落库 —— 直接查表比字符串，不经过 type handler。 */
    @Test
    void noSensitiveValueIsStoredAsPlaintext() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(ClubCorporatePayeeProfileMapper.class).insertVersion(profile(1L, CREDIT));
        }
        String row = rawRow("select concat(legal_name,'|',unified_social_credit_code,'|',"
                + "bank_account_name,'|',corporate_bank_account_no,'|',tax_no,'|',"
                + "finance_contact_name,'|',finance_contact_phone) from club_corporate_payee_profile");

        assertTrue(row.contains("v1:"), "敏感列必须是 v1: 前缀的密文");
        for (String plaintext : new String[]{"上海城瘾探索者体育文化有限公司", CREDIT,
                "6222020200011112222", "周财务", "13800001234"}) {
            assertTrue(row.indexOf(plaintext) < 0, "库里出现了明文:" + plaintext);
        }
    }

    /** 同一信用代码两次不同 nonce 的密文必须不同，但 blind index 必须相同（否则查不了）。 */
    @Test
    void blindIndexIsStableWhileTheCiphertextIsNot() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            mapper.insertVersion(profile(1L, CREDIT));
            ClubCorporatePayeeProfile other = profile(1L, CREDIT);
            other.setClubId(8L);
            mapper.insertVersion(other);
        }
        String indexes = rawRow("select group_concat(credit_code_index) from club_corporate_payee_profile");
        String ciphers = rawRow("select group_concat(unified_social_credit_code) from club_corporate_payee_profile");
        String[] index = indexes.split(",");
        String[] cipher = ciphers.split(",");

        assertEquals(index[0], index[1], "同一信用代码的 blind index 必须相同");
        assertNotEquals(cipher[0], cipher[1], "同一信用代码的密文必须因 nonce 不同而不同");
    }

    /** 每个俱乐部最多一个 current 版本：不先失效就插第二个 current，撞生成列唯一键。 */
    @Test
    void aSecondCurrentVersionForTheSameClubIsRejectedByTheDatabase() {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            mapper.insertVersion(profile(1L, CREDIT));

            assertThrows(RuntimeException.class, () -> mapper.insertVersion(profile(2L, CREDIT)));
        }
    }

    @Test
    void invalidatingTheOldCurrentLetsTheNewVersionIn() {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            mapper.insertVersion(profile(1L, CREDIT));

            assertEquals(1, mapper.invalidateCurrent(CLUB));
            assertEquals(1, mapper.insertVersion(profile(2L, CREDIT)));
            assertEquals(2L, mapper.selectCurrentForUpdate(CLUB).getVersion());
        }
    }

    /** 两个财务并发审核同一 version：只有一个拿到 rows=1。 */
    @Test
    void onlyOneVerificationOfTheSameVersionWinsTheCas() {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            mapper.insertVersion(profile(1L, CREDIT));
            Long id = mapper.selectCurrentForUpdate(CLUB).getId();

            assertEquals(1, mapper.verifyVersionCas(id, CLUB, 72L, new Date(), "VERIFIED", "PASS", "LICENSE_HASH"));
            assertEquals(0, mapper.verifyVersionCas(id, CLUB, 73L, new Date(), "VERIFIED", "PASS", "LICENSE_HASH"));
        }
    }

    /** 执照 hash 在 CAS 的 where 里：登记值被改过就审不动。 */
    @Test
    void verificationIsRefusedWhenTheLicenseHashNoLongerMatches() {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            mapper.insertVersion(profile(1L, CREDIT));
            Long id = mapper.selectCurrentForUpdate(CLUB).getId();

            assertEquals(0, mapper.verifyVersionCas(id, CLUB, 72L, new Date(), "VERIFIED", "PASS", "TAMPERED"));
        }
    }

    @Test
    void verifiedRowsMustCarryTheirVerifierAndTime() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_corporate_payee_profile (club_id, profile_version, is_current, legal_name,"
                        + " unified_social_credit_code, credit_code_index, license_object_key, license_hash,"
                        + " bank_account_name, bank_name, corporate_bank_account_no, invoice_type, tax_no,"
                        + " finance_contact_name, finance_contact_phone, facts_hash, verify_status, created_by)"
                        + " VALUES (9,1,1,'x','x',REPEAT('a',64),'k',REPEAT('b',64),'x','x','x','SPECIAL_VAT','x',"
                        + "'x','x',REPEAT('c',64),'VERIFIED',1)"));
    }

    @Test
    void anUnknownVerifyStatusIsRejectedByTheCheckConstraint() {
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO club_corporate_payee_profile (club_id, profile_version, is_current, legal_name,"
                        + " unified_social_credit_code, credit_code_index, license_object_key, license_hash,"
                        + " bank_account_name, bank_name, corporate_bank_account_no, invoice_type, tax_no,"
                        + " finance_contact_name, finance_contact_phone, facts_hash, verify_status, created_by)"
                        + " VALUES (9,1,1,'x','x',REPEAT('a',64),'k',REPEAT('b',64),'x','x','x','SPECIAL_VAT','x',"
                        + "'x','x',REPEAT('c',64),'MAYBE',1)"));
    }

    /** 开售快照 topic+club 唯一：重放开售不会产生第二份历史事实。 */
    @Test
    void theSaleSnapshotIsUniquePerTopicAndClub() {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            mapper.insertVersion(profile(1L, CREDIT));
            assertEquals(1, mapper.insertSaleSnapshot(snapshot(1L)));

            assertThrows(RuntimeException.class, () -> mapper.insertSaleSnapshot(snapshot(2L)));
            TopicSalePayeeSnapshot frozen = mapper.selectSaleSnapshot(TOPIC, CLUB);
            assertEquals(1L, frozen.getProfileVersion(), "快照必须还是开售当时那一版");
            assertNotNull(frozen.getFrozenAt());
        }
    }

    /**
     * ★迁移 remark 里那句「与审核权限互斥分派」必须是<b>真的</b>。
     *
     * <p>remark 会永久留在生产库的表结构里，比源码注释更难被后来的人证伪；声称一条不存在的
     * 访问控制事实，正是裁决表 §7.1 E3 点名的「让人以为有闸」。上一版这两个权限一条
     * {@code sys_role_menu} 授权都没有、也没有任何互斥约束 —— 而它管的是**完整明文对公账号**的解密权。
     */
    @Test
    void thePayeeVerifyAndPayoutPermissionsLandOnTwoDifferentRoles() throws Exception {
        assertEquals("1", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where r.role_key = 'cy_finance' and m.perms = 'business:clubpayee:verify'"));
        assertEquals("1", scalar("select count(*) from sys_role_menu rm"
                + " join sys_role r on r.role_id = rm.role_id"
                + " join sys_menu m on m.menu_id = rm.menu_id"
                + " where r.role_key = 'cy_operator' and m.perms = 'business:clubpayee:payout'"));
        assertEquals("0", scalar("select count(*) from sys_role_menu a"
                + " join sys_menu ma on ma.menu_id = a.menu_id"
                + " join sys_role_menu b on b.role_id = a.role_id"
                + " join sys_menu mb on mb.menu_id = b.menu_id"
                + " where ma.perms = 'business:clubpayee:verify'"
                + " and mb.perms = 'business:clubpayee:payout'"),
                "批准这份资料的人与能看到完整明文对公账号的人必须分开，否则那句 remark 就是假的");
    }

    @Test
    void rolePageRepairPreservesPayeeGrantsAndAncestorsOnReplay() throws Exception {
        prepareRolePageFixture();
        List<String> before = roleGrants();
        assertEquals(6, runRolePageMigration(0, null));
        List<String> after = roleGrants();
        assertTrue(after.containsAll(before), "补页不能删除任何既有授权");
        assertEquals("1", scalar(rolePermissionCount("cy_operator", "business:clubpayee:payout")));
        assertEquals("1", scalar(rolePermissionCount("cy_finance", "business:clubpayee:verify")));
        assertEquals("0", scalar(rolePermissionCount("cy_operator", "business:clubpayee:verify")));
        assertEquals("0", scalar(rolePermissionCount("cy_finance", "business:clubpayee:payout")));
        for (String role : new String[]{"cy_operator", "cy_finance"}) {
            assertEquals("3", scalar("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id"
                    + " JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE r.role_key='" + role + "'"
                    + " AND (m.menu_id IN (9000,9001) OR m.perms='business:club:list')"),
                    "两个角色都必须实际拿到俱乐部页面及两级祖先");
        }
        assertEquals(6, runRolePageMigration(0, null));
        assertEquals(after, roleGrants(), "重复执行不得增加或删除授权行");
    }

    @Test
    void rolePageRepairRejectsExistingOppositeGrantsBeforePersistentWrites() throws Exception {
        for (String conflict : new String[]{"(100, 'business:clubpayee:verify')", "(103, 'business:clubpayee:payout')"}) {
            prepareRolePageFixture();
            String[] pair = conflict.substring(1, conflict.length() - 1).split(", ", 2);
            execute("INSERT INTO sys_role_menu SELECT " + pair[0] + ",menu_id FROM sys_menu WHERE perms=" + pair[1]);
            List<String> before = roleGrants();
            ReadbackFailure failure = assertThrows(ReadbackFailure.class, () -> runRolePageMigration(0, null));
            assertEquals(1, failure.index, "必须由写入前的第一条真实scalar断言拒绝");
            assertEquals(before, roleGrants(), "异常旧授权只报错，不删除或补写持久授权");
        }
    }

    @Test
    void rolePageRepairStillRejectsNewSensitiveGrantsAndLostOldGrants() throws Exception {
        for (String permission : new String[]{"business:clubpayee:payout", "business:test:payout",
                "business:test:confirm", "business:test:export", "business:test:pii"}) {
            prepareRolePageFixture();
            execute("INSERT INTO sys_menu(menu_id,parent_id,menu_type,visible,status,perms)"
                    + " VALUES(9010,9001,'F','0','0','" + permission + "')");
            ReadbackFailure failure = assertThrows(ReadbackFailure.class,
                    () -> runRolePageMigration(4, "INSERT INTO sys_role_menu VALUES(100,9010)"));
            assertEquals(4, failure.index, "before快照不得豁免新增敏感权限，即使权限串与旧payout相同");
        }
        for (String menuType : new String[]{"NULL", "'C'", "''"}) {
            prepareRolePageFixture();
            ReadbackFailure malformed = assertThrows(ReadbackFailure.class,
                    () -> runRolePageMigration(4, "UPDATE sys_menu SET menu_type=" + menuType
                            + " WHERE perms='business:clubpayee:payout'"));
            assertEquals(4, malformed.index, "旧payout只有真实F类型可保留，NULL/C/空串必须实际判红");
        }
        prepareRolePageFixture();
        ReadbackFailure removed = assertThrows(ReadbackFailure.class,
                () -> runRolePageMigration(5, "DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id"
                        + " WHERE rm.role_id=100 AND m.perms='business:clubpayee:payout'"));
        assertEquals(5, removed.index, "删除旧授权必须由保留断言拒绝");
        for (String conflict : new String[]{"100,'business:clubpayee:verify'", "103,'business:clubpayee:payout'"}) {
            prepareRolePageFixture();
            String[] pair = conflict.split(",", 2);
            ReadbackFailure crossed = assertThrows(ReadbackFailure.class,
                    () -> runRolePageMigration(6, "INSERT INTO sys_role_menu SELECT " + pair[0]
                            + ",menu_id FROM sys_menu WHERE perms=" + pair[1]));
            assertEquals(6, crossed.index, "末尾互斥自检也必须实际拒绝交叉授权");
        }
    }

    private void prepareRolePageFixture() throws Exception {
        resetSchema();
        execute("DELETE FROM sys_role_menu", "DELETE FROM sys_role",
                "INSERT INTO sys_role(role_id,role_name,role_key,del_flag) VALUES"
                        + "(100,'operator','cy_operator','0'),(103,'finance','cy_finance','0')",
                "INSERT INTO sys_menu(menu_id,menu_name,parent_id,menu_type,visible,status,perms) VALUES"
                        + "(9000,'root',0,'M','0','0',''),(9001,'clubs',9000,'M','0','0',''),"
                        + "(9002,'query',9001,'F','0','0','business:club:query')",
                "UPDATE sys_menu SET parent_id=9001 WHERE perms='business:club:list'");
        assertEquals(2, runCheckedMigration("migration_20260816_tandianri_batch3_funds_club_payee.sql", 2, 0, null),
                "旧payee迁移的两条真实scalar合同必须先成立");
        execute("INSERT INTO sys_role_menu VALUES(100,9002)");
    }

    private List<String> roleGrants() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id")) {
            while (result.next()) rows.add(result.getLong(1) + ":" + result.getLong(2));
        }
        return rows;
    }

    private String rolePermissionCount(String role, String permission) {
        return "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id"
                + " JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE r.role_key='" + role + "' AND m.perms='" + permission + "'";
    }

    private static final class ReadbackFailure extends AssertionError {
        private final int index;

        private ReadbackFailure(int index, String sql, Object value) {
            super("scalar-true #" + index + " readback=" + value + " sql=" + sql);
            this.index = index;
        }
    }

    private int runRolePageMigration(int injectBeforeReadback, String mutationSql) throws Exception {
        return runCheckedMigration("migration_20261013_admin_role_page_grants.sql", 6, injectBeforeReadback, mutationSql);
    }

    // Reuse the existing RoamPresenceRevokeJobMySqlIT JDBC-proxy pattern: ScriptUtils owns SQL splitting,
    // while every top-level SELECT must actually return exactly one column/row whose numeric value is one.
    private int runCheckedMigration(String filename, int expected, int injectBeforeReadback, String mutationSql) throws Exception {
        Path path = Paths.get("sql", filename);
        if (!Files.isRegularFile(path)) path = Paths.get("chengyinhub-admin", "sql", filename);
        String migration = Files.readString(path, StandardCharsets.UTF_8);
        assertEquals((long) expected, migration.lines().filter(line -> line.trim().equals("-- runner: assert=scalar-true")).count());
        AtomicInteger observed = new AtomicInteger();
        try (Connection connection = dataSource.getConnection()) {
            Connection checked = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result;
                        try { result = method.invoke(connection, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                        if (!method.getName().equals("createStatement")) return result;
                        Statement statement = (Statement) result;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (p, operation, parameters) -> {
                                    String sql = operation.getName().equals("execute") && parameters != null
                                            && parameters[0] instanceof String ? (String) parameters[0] : null;
                                    boolean readback = sql != null && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ");
                                    int index = observed.get() + 1;
                                    if (readback && index == injectBeforeReadback && mutationSql != null) {
                                        try (Statement injection = connection.createStatement()) { injection.execute(mutationSql); }
                                    }
                                    Object value;
                                    try { value = operation.invoke(statement, parameters); } catch (InvocationTargetException e) { throw e.getCause(); }
                                    if (readback) {
                                        assertEquals(Boolean.TRUE, value);
                                        try (ResultSet rows = statement.getResultSet()) {
                                            if (rows.getMetaData().getColumnCount() != 1 || !rows.next()) throw new ReadbackFailure(index, sql, "shape");
                                            Object scalar = rows.getObject(1);
                                            if (!(scalar instanceof Number) || new BigDecimal(scalar.toString()).compareTo(BigDecimal.ONE) != 0
                                                    || rows.next()) throw new ReadbackFailure(index, sql, scalar);
                                        }
                                        observed.incrementAndGet();
                                    }
                                    return value;
                                });
                    });
            ScriptUtils.executeSqlScript(checked, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(expected, observed.get(), "所有声明的scalar断言都必须实际读回");
        return observed.get();
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static ClubCorporatePayeeProfile profile(long version, String creditCode) {
        ClubCorporatePayeeProfile p = new ClubCorporatePayeeProfile();
        p.setClubId(CLUB);
        p.setVersion(version);
        p.setCurrent(1);
        p.setLegalName("上海城瘾探索者体育文化有限公司");
        p.setUnifiedSocialCreditCode(creditCode);
        p.setCreditCodeIndex(creditCode);
        p.setLicenseObjectKey("private/club/7/license.pdf");
        p.setLicenseHash("LICENSE_HASH");
        p.setBankAccountName("上海城瘾探索者体育文化有限公司");
        p.setBankName("招商银行上海分行营业部");
        p.setCorporateBankAccountNo("6222020200011112222");
        p.setInvoiceType("SPECIAL_VAT");
        p.setTaxNo(creditCode);
        p.setFinanceContactName("周财务");
        p.setFinanceContactPhone("13800001234");
        p.setFactsHash(ClubCorporatePayeeServiceImpl.factsHash(p));
        p.setVerifyStatus(ClubCorporatePayeeProfile.VERIFY_PENDING);
        p.setCreatedBy(71L);
        return p;
    }

    private static TopicSalePayeeSnapshot snapshot(long profileVersion) {
        TopicSalePayeeSnapshot s = new TopicSalePayeeSnapshot();
        s.setTopicId(TOPIC);
        s.setClubId(CLUB);
        s.setProfileId(1L);
        s.setProfileVersion(profileVersion);
        s.setFactsHash("f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0");
        s.setLicenseHash("LICENSE_HASH");
        s.setFrozenAt(new Date());
        return s;
    }

    private String rawRow(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            String value = result.getString(1);
            return value == null ? "" : value;
        }
    }

    private SqlSessionFactory factory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("club-payee-it",
                new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(ClubCorporatePayeeProfileMapper.class);
        String resource = "mapper/business/ClubCorporatePayeeProfileMapper.xml";
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
