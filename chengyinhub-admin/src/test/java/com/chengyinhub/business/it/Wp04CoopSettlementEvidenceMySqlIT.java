package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实仓库 DDL + MyBatis + MySQL；仅供已批准 CI/VPS 执行，无 H2/skip/生产连接。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Wp04CoopSettlementEvidenceMySqlIT {
    private JdbcTemplate db;
    private String createdDatabase;
    private CoopSettlementEvidenceMapper mapper;
    private TransactionTemplate tx;
    private static final List<String> TABLES = List.of("cms_registration", "oms_ticket", "cms_activity",
            "cms_topic_node", "cms_registration_scan", "coop_group_member_verify", "coop_group_verify", "refund_application");
    @BeforeAll void schema() throws Exception {
        for (String name : new String[]{"HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX"}) {
            String configured = System.getenv("CHENGYIN_MYBATIS_IT_" + name);
            assertNotNull(configured, "explicit isolated IT variable required: " + name);
            if (!"PASSWORD".equals(name)) assertFalse(configured.isEmpty(), "empty IT variable: " + name);
        }
        int port = Integer.parseInt(MybatisItDatabase.port());
        assertTrue(port > 0 && port <= 65535);
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("_[a-z0-9_]{1,20}"));
        String host = System.getenv("CHENGYIN_MYBATIS_IT_HOST");
        assertTrue("127.0.0.1".equals(host) || "localhost".equals(host), "必须显式指定已批准隔离 MySQL 回环实例");
        String suite = "g_wp04_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String url = MybatisItDatabase.url(suite);
        createdDatabase = MybatisItDatabase.databaseName(suite);
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        Path baseline = Path.of("../sql/base_schema_20260818.sql");
        if (!Files.isRegularFile(baseline)) baseline = Path.of("sql/base_schema_20260818.sql");
        String ddl = Files.readString(baseline);
        for (String table : TABLES) {
            Matcher match = Pattern.compile("(?s)CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=.*?;").matcher(ddl);
            assertTrue(match.find(), "缺少真实 DDL: " + table); db.execute(match.group());
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementEvidenceMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CoopSettlementEvidenceMapper.class);
    }

    @AfterAll void cleanup() throws Exception {
        if (createdDatabase == null) return;
        assertTrue(createdDatabase.matches("chengyin_mybatis_g_wp04_[a-f0-9]{12}_[a-z0-9_]{1,20}"));
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                        + "/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
                java.sql.Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + createdDatabase + "`");
        }
    }
    @BeforeEach void seed() {
        for (String table : TABLES) db.execute("TRUNCATE TABLE " + table);
        db.update("INSERT INTO cms_activity(id,topic_id,del_flag) VALUES(201,11,0),(11,99,0),(202,11,2)");
        db.update("INSERT INTO cms_topic_node(id,topic_id) VALUES(201,99),(11,11)");
        db.update("INSERT INTO oms_ticket(id,owner_type,owner_id,mode,sub_type,del_flag,start_time,end_time) VALUES"
                + "(21,1,11,1,'guided',0,'2026-10-01 09:00:00','2026-10-01 10:00:00'),"
                + "(22,2,201,1,'guided',0,'2026-10-01 09:00:00','2026-10-01 10:00:00'),"
                + "(23,2,11,1,'guided',0,'2026-10-01 09:00:00','2026-10-01 10:00:00'),"
                + "(24,2,202,1,'guided',0,'2026-10-01 09:00:00','2026-10-01 10:00:00')");
        registration(1,1,11,21); registration(2,2,201,22); registration(3,2,11,23); registration(4,2,202,24);
        registration(5,1,11,23); // 数字 id 一致不能替代票归属。
        for (int id = 6; id <= 10; id++) registration(id,1,11,21);
        scan(1,88,1,0,"2026-10-01 09:00:00"); scan(2,88,1,0,"2026-10-01 09:30:00");
        scan(6,88,1,0,"2026-10-01 08:59:59"); scan(7,88,1,0,"2026-10-01 10:00:00");
        scan(8,88,1,2,"2026-10-01 09:30:00"); scan(9,88,0,0,"2026-10-01 09:30:00");
        scan(10,99,1,0,"2026-10-01 09:30:00");
        db.update("INSERT INTO coop_group_member_verify(registration_id,topic_id,activity_id,verifier_member_id,verify_time)"
                + " VALUES(1,11,201,77,'2026-10-01 09:20:00'),(2,11,201,77,'2026-10-01 09:20:00')");
        db.update("INSERT INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time)"
                + " VALUES(11,201,701,88,77,'2026-10-01 09:30:00')");
    }
    @Test void actualActivityParentExcludesCollidingIdAndDeletedShell() {
        List<Long> ids = rows(snapshot("TOPIC",11)).stream().map(CoopSettlementCandidate::getRegistrationId).toList();
        assertTrue(ids.containsAll(List.of(1L,2L))); assertFalse(ids.contains(3L)); assertFalse(ids.contains(4L));
        assertEquals(2, mapper.countPaidActivityRegistrations(11L), "已删除壳仍有已支付资金，也必须阻断旧现金基数");
        assertEquals(1L, row(1,snapshot("TOPIC",11)).getOwnerType());
        assertEquals(2L, row(2,snapshot("TOPIC",11)).getOwnerType());
        assertEquals(201L, row(2,snapshot("TOPIC",11)).getOwnerId());
    }
    @Test void nullOrEmptyActivitySetNeverGuessesActivityIdFromTopic() {
        tx.execute(status -> {
            for (List<Long> ids : Arrays.asList(null, Collections.<Long>emptyList())) {
                List<CoopSettlementCandidate> rows = mapper.selectTopicEvidenceCandidatesForUpdate(snapshot("TOPIC",11), ids, cutoff());
                assertTrue(rows.stream().allMatch(row -> row.getOwnerType() == 1L));
                assertTrue(rows.stream().anyMatch(row -> row.getRegistrationId() == 1L));
            }
            return null;
        });
    }
    @Test void nodeMustHaveActualParentAndScansCannotProveNodeIdentity() {
        assertFalse(row(1,snapshot("NODE",201)).isScopeCovered());
        CoopSettlementCandidate node = row(1,snapshot("NODE",11));
        assertTrue(node.isScopeCovered()); assertFalse(node.isMerchantScanPresent());
        assertFalse(node.isGroupEvidenceMatchesOrder());
    }
    @Test void activityScopeUsesActualOwnerInsteadOfTopicNumber() {
        assertFalse(row(1,snapshot("ACTIVITY",201)).isScopeCovered());
        assertTrue(row(2,snapshot("ACTIVITY",201)).isScopeCovered());
        assertFalse(row(2,snapshot("ACTIVITY",11)).isScopeCovered());
    }
    @Test void ticketOwnerTimeScanTypeAndReversalAreEnforced() {
        assertTrue(row(1,snapshot("TOPIC",11)).isMerchantScanPresent(), "真实写侧 type=1/status=0 合法，start 包含");
        assertFalse(row(5,snapshot("TOPIC",11)).isTicketOwnerMatches());
        for (int id = 6; id <= 10; id++) assertFalse(row(id,snapshot("TOPIC",11)).isMerchantScanPresent(), "无效 scan id=" + id);
        db.update("UPDATE cms_registration_scan SET create_time='2026-10-01 09:30:00' WHERE registration_id=6");
        assertTrue(row(6,snapshot("TOPIC",11)).isMerchantScanPresent());
    }
    @Test void groupEvidenceRequiresSameTopicActivityOrderMerchantAndBothWindows() {
        CoopSettlementSnapshot s = snapshot("TOPIC",11);
        assertTrue(row(1,s).isGroupEvidenceMatchesOrder()); assertTrue(row(2,s).isGroupEvidenceMatchesOrder());
        db.update("UPDATE coop_group_verify SET coop_order_id=702"); assertFalse(row(2,s).isGroupEvidenceMatchesOrder());
        db.update("UPDATE coop_group_verify SET coop_order_id=701,topic_id=99"); assertFalse(row(2,s).isGroupEvidenceMatchesOrder());
        db.update("UPDATE coop_group_verify SET topic_id=11,merchant_member_id=99"); assertFalse(row(2,s).isGroupEvidenceMatchesOrder());
        db.update("UPDATE coop_group_verify SET merchant_member_id=88,verify_time='2026-10-01 10:00:00'");
        assertFalse(row(2,s).isGroupEvidenceMatchesOrder());
        db.update("UPDATE coop_group_verify SET verify_time='2026-10-01 09:30:00'");
        db.update("UPDATE coop_group_member_verify SET activity_id=11 WHERE registration_id=2");
        assertFalse(row(2,s).isOrganizerScopeCovered()); assertFalse(row(2,s).isGroupEvidenceMatchesOrder());
    }
    @Test void pendingAndExecutedApprovedRefundsRemainBlockingUntilNetBasisExists() {
        assertTrue(tx.execute(status -> mapper.selectCashBasisBlockingRefundIdsForUpdate(11L)).isEmpty());
        db.update("INSERT INTO refund_application(id,source_type,source_id,owner_type,owner_id,status,payout_status,del_flag) VALUES"
                + "(1,'registration',1,1,11,0,0,0),(2,'registration',1,1,11,1,1,0),(3,'registration',1,1,11,2,0,0)");
        assertEquals(List.of(1L,2L), tx.execute(status -> mapper.selectCashBasisBlockingRefundIdsForUpdate(11L)).stream().sorted().toList());
    }
    private void registration(int id,int type,int owner,int ticket) {
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,"
                + "original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time)"
                + " VALUES(?,?,?,?,?,10,77,1,10,10,10,2,2,1,1,'2026-10-01 08:00:00')", id,"G-WP04-"+id,type,owner,ticket);
    }
    private void scan(int reg,int member,int type,int state,String time) {
        db.update("INSERT INTO cms_registration_scan(registration_id,member_id,registration_type,status,create_time) VALUES(?,?,?,?,?)",reg,member,type,state,time);
    }
    private List<CoopSettlementCandidate> rows(CoopSettlementSnapshot s) {
        List<Long> activityIds = db.queryForList("SELECT id FROM cms_activity WHERE topic_id=11 AND del_flag=0",Long.class);
        return tx.execute(status -> mapper.selectTopicEvidenceCandidatesForUpdate(s,activityIds,cutoff()));
    }
    private CoopSettlementCandidate row(long id,CoopSettlementSnapshot s) {
        return rows(s).stream().filter(row -> row.getRegistrationId()==id).findFirst().orElseThrow();
    }
    private Date cutoff() { return java.sql.Timestamp.valueOf("2026-10-01 10:00:00"); }
    private CoopSettlementSnapshot snapshot(String scope,long scopeId) {
        CoopSettlementSnapshot s = new CoopSettlementSnapshot(); s.setTopicId(11L); s.setOrderId(701L);
        s.setScopeType(scope); s.setScopeId(scopeId); s.setBeneficiaryId(88L); return s;
    }
}
