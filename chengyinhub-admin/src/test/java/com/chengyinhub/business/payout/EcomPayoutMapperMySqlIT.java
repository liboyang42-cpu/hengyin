package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.CreatorPayoutRequest;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
import com.chengyinhub.business.domain.EcomSubMerchant;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * l-payout：真实 MySQL 8 + 真实 MyBatis 执行 {@code EcomPayoutMapper.xml} 与 {@code migration_20261110_ecommerce_payout.sql}。
 *
 * <p>新表直接执行仓库迁移；被读取的既有表（cms_registration / mms_merchant / club / coop_settlement_original_component）
 * 只建本 mapper 读到的列，列类型与字符集/排序规则照生产（registration_no 为 utf8mb4_general_ci，
 * 新表单号为 ascii_bin），以证明跨排序规则的连接在真库上成立。component 表去掉了指向其它账本表的外键。
 *
 * <p>只在 {@link MybatisItDatabase} 分配的一次性 scratch 库里跑，结束时 DROP 并回读确认。
 * ⚠️ surefire 默认只收 *Test，本类需 -Dtest 点名（Real Storage IT 工作流按登记执行）。
 */
class EcomPayoutMapperMySqlIT {
    private static final String SUITE = "ecom_payout";
    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;
    private static String database;
    private static String adminUrl;

    private SqlSession session;
    private EcomPayoutMapper mapper;

    @BeforeAll
    static void start() throws Exception {
        adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select version()")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).startsWith("8.0."), "必须是 MySQL 8.0，实际=" + rs.getString(1));
        }
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url(SUITE),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(SUITE);
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("ecom-payout", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/EcomPayoutMapper.xml";
        try (InputStream in = EcomPayoutMapperMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("mapper XML 不在 classpath:" + resource);
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        cfg.getTypeAliasRegistry().registerAlias("UmsMemberBalanceDetail", com.chengyinhub.business.domain.UmsMemberBalanceDetail.class);
        String statementResource = "mapper/business/UmsMemberBalanceDetailMapper.xml";
        try (InputStream in = EcomPayoutMapperMySqlIT.class.getClassLoader().getResourceAsStream(statementResource)) {
            new XMLMapperBuilder(in, cfg, statementResource, cfg.getSqlFragments()).parse();
        }
        String sessionResource = "mapper/business/EcomSessionPayoutMapper.xml";
        try (InputStream in = EcomPayoutMapperMySqlIT.class.getClassLoader().getResourceAsStream(sessionResource)) {
            new XMLMapperBuilder(in, cfg, sessionResource, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cms_registration (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " registration_no varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,"
                    + " owner_type int DEFAULT '0', owner_id bigint NOT NULL, UNIQUE KEY uk_reg_no(registration_no))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.execute("CREATE TABLE mms_merchant (id bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " member_id bigint DEFAULT NULL, del_flag int DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE club (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, member_id bigint NOT NULL,"
                    + " del_flag int DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE coop_settlement_original_component (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " topic_id BIGINT NOT NULL, payer_registration_id BIGINT NOT NULL, settlement_id BIGINT NOT NULL,"
                    + " receiver_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + " receiver_member_id BIGINT UNSIGNED NULL, line_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + " original_amount DECIMAL(15,2) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // l-payout2：分账提交改为「结算头到期 + 无未结客诉」，计划会读 ecom_refund；这里补齐这些表并执行接线迁移。
            st.execute("CREATE TABLE coop_settlement (id bigint NOT NULL PRIMARY KEY, topic_id bigint NOT NULL,"
                    + " del_flag int NOT NULL DEFAULT 0, payable_time datetime NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE coop_complaint (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, topic_id bigint NOT NULL,"
                    + " status int NOT NULL, del_flag int NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE sys_job (job_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, job_name varchar(64) NOT NULL,"
                    + " job_group varchar(64) NOT NULL, invoke_target varchar(500) NOT NULL, cron_expression varchar(255),"
                    + " misfire_policy varchar(20), concurrent char(1), status char(1), create_by varchar(64),"
                    + " create_time datetime, remark varchar(500)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE sys_menu (menu_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, menu_name varchar(50) NOT NULL,"
                    + " parent_id bigint DEFAULT 0, order_num int DEFAULT 0, path varchar(200) DEFAULT '', component varchar(255),"
                    + " `query` varchar(255), route_name varchar(50) DEFAULT '', is_frame int DEFAULT 1, is_cache int DEFAULT 0,"
                    + " menu_type char(1) DEFAULT '', visible char(1) DEFAULT '0', status char(1) DEFAULT '0', perms varchar(100),"
                    + " icon varchar(100) DEFAULT '#', create_by varchar(64), create_time datetime, remark varchar(500))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE sys_role_menu (role_id bigint NOT NULL, menu_id bigint NOT NULL, PRIMARY KEY(role_id, menu_id))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("INSERT INTO sys_menu(menu_name, parent_id, path, menu_type, perms) VALUES ('资金售后', 0, 'funds-aftercare', 'M', '')");
            ItSchema.applyMigrations(c, "sql/migration_20261110_ecommerce_payout.sql",
                    "sql/migration_20261110_ecommerce_payout_wiring.sql");
            st.execute("CREATE TABLE settlement_detail(id BIGINT PRIMARY KEY,source_type VARCHAR(32),source_id BIGINT,"
                    + "receiver_type VARCHAR(32),receiver_id BIGINT,settlement_amount DECIMAL(18,2),status INT,fail_reason VARCHAR(255),update_time DATETIME)");
            ItSchema.applyMigrations(c, "sql/migration_20261112_dec_c_payout.sql",
                    "sql/migration_20261112_dec_c_payout.sql");
            st.execute("CREATE TABLE ums_member_balance_detail(id BIGINT PRIMARY KEY,member_id BIGINT,"
                    + "current_balance DECIMAL(18,2),after_balance DECIMAL(18,2),change_balance DECIMAL(18,2),"
                    + "change_reason VARCHAR(255),event_type BIGINT,event_id BIGINT,change_type BIGINT,del_flag BIGINT,"
                    + "create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME)");
            st.execute("CREATE TABLE cms_activity(id BIGINT PRIMARY KEY,del_flag INT,is_settlement INT)");
            st.execute("CREATE TABLE cms_activity_settlement_hold(registration_id BIGINT PRIMARY KEY,status INT)");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null || adminUrl == null) return;
        assertTrue(database.matches("chengyin_mybatis_ecom_payout[a-z0-9_]*"), "只许 DROP 本套 scratch 库:" + database);
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery("select count(*) from information_schema.schemata where schema_name = '" + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @BeforeEach
    void open() throws Exception {
        session = factory.openSession(true);
        mapper = session.getMapper(EcomPayoutMapper.class);
        try (Statement st = session.getConnection().createStatement()) {
            for (String t : List.of("cms_activity_settlement_hold", "settlement_detail", "cms_activity", "club_personal_payout_beneficiary"))
                st.execute("delete from " + t);
            for (String t : List.of("ums_member_balance_detail", "ecom_refund", "coop_settlement", "coop_complaint", "ecom_profit_share_return", "ecom_profit_share_line", "ecom_profit_share_order",
                    "ecom_order_seller", "ecom_sub_merchant", "creator_payout_request", "cms_registration",
                    "mms_merchant", "club", "coop_settlement_original_component")) st.execute("delete from " + t);
        }
    }

    @AfterEach
    void close() { if (session != null) session.close(); }

    private void exec(String sql) throws Exception {
        try (Statement st = session.getConnection().createStatement()) { st.execute(sql); }
    }

    private static boolean isDuplicate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLIntegrityConstraintViolationException) return true;
        return false;
    }

    private EcomSubMerchant sub(String type, long ownerId) {
        EcomSubMerchant row = new EcomSubMerchant();
        row.setOwnerType(type); row.setOwnerId(ownerId);
        row.setOutRequestNo(EcommerceSubMerchantService.outRequestNo(type, ownerId)); row.setCreatedBy("it");
        mapper.insertSubMerchant(row);
        return row;
    }

    @Test
    void subMerchantOwnerAndRequestAreUniqueAndOwnerTypeIsChecked() {
        sub("MERCHANT", 5);
        PersistenceException dup = assertThrows(PersistenceException.class, () -> sub("MERCHANT", 5));
        assertTrue(isDuplicate(dup));
        PersistenceException bad = assertThrows(PersistenceException.class, () -> sub("PERSON", 6));
        assertTrue(bad.getMessage().contains("ck_ecom_sub_merchant_owner"));
        EcomSubMerchant read = mapper.selectSubMerchant("MERCHANT", 5L);
        assertEquals("DRAFT", read.getApplymentState());
        assertEquals("CYAPM5", read.getOutRequestNo());
    }

    @Test
    void applymentStatusUpdatesAreCompareAndSetAndSubMchidIsWriteOnce() {
        EcomSubMerchant row = sub("CLUB", 9);
        assertEquals(0, mapper.markApplymentSubmitted(row.getId() + 999, 1L));
        assertEquals(1, mapper.markApplymentSubmitted(row.getId(), 2000001L));
        assertEquals(0, mapper.markApplymentSubmitted(row.getId(), 2000002L));
        assertEquals(0, mapper.updateApplymentStatus(row.getId(), "AUDITING", "FINISH", "x", null, "1900000101", null));
        assertEquals(1, mapper.updateApplymentStatus(row.getId(), "CHECKING", "FINISH", "完成", null, "1900000101", null));
        assertEquals(0, mapper.updateApplymentStatus(row.getId(), "FINISH", "FROZEN", "冻结", null, "1900000999", null));
        assertEquals(1, mapper.updateApplymentStatus(row.getId(), "FINISH", "FROZEN", "冻结", null, "1900000101", null));
        EcomSubMerchant read = mapper.selectSubMerchantById(row.getId());
        assertEquals("FROZEN", read.getApplymentState());
        assertEquals("1900000101", read.getSubMchid());
        assertEquals(2000001L, read.getApplymentId());
        assertTrue(mapper.selectSubMerchantsInProgress(10).isEmpty());
    }

    @Test
    void finishedSubMchidForMemberCoversMerchantAndClubButNotUnfinishedOrDeleted() throws Exception {
        exec("insert into mms_merchant(id, member_id, del_flag) values (5, 55, 0), (6, 66, 1)");
        exec("insert into club(id, member_id, del_flag) values (9, 55, 0)");
        EcomSubMerchant m = sub("MERCHANT", 5);
        EcomSubMerchant deleted = sub("MERCHANT", 6);
        EcomSubMerchant c = sub("CLUB", 9);
        mapper.markApplymentSubmitted(m.getId(), 1L);
        mapper.markApplymentSubmitted(deleted.getId(), 2L);
        mapper.updateApplymentStatus(m.getId(), "CHECKING", "FINISH", null, null, "1900000101", null);
        mapper.updateApplymentStatus(deleted.getId(), "CHECKING", "FINISH", null, null, "1900000102", null);
        assertEquals(List.of("1900000101"), mapper.selectFinishedSubMchidsForMember(55L));
        assertTrue(mapper.selectFinishedSubMchidsForMember(66L).isEmpty());
        mapper.markApplymentSubmitted(c.getId(), 3L);
        mapper.updateApplymentStatus(c.getId(), "CHECKING", "FINISH", null, null, "1900000103", null);
        assertEquals(2, mapper.selectFinishedSubMchidsForMember(55L).size());
        assertEquals(55L, mapper.selectSellerMemberId("MERCHANT", 5L));
        assertEquals(55L, mapper.selectSellerMemberId("CLUB", 9L));
        assertNull(mapper.selectSellerMemberId("MERCHANT", 6L));
        PersistenceException dupMchid = assertThrows(PersistenceException.class, () -> {
            EcomSubMerchant other = sub("MERCHANT", 7);
            mapper.markApplymentSubmitted(other.getId(), 4L);
            mapper.updateApplymentStatus(other.getId(), "CHECKING", "FINISH", null, null, "1900000101", null);
        });
        assertTrue(isDuplicate(dupMchid));
    }

    private void seller(String no, long total, String tx) {
        EcomOrderSeller s = new EcomOrderSeller();
        s.setOutTradeNo(no); s.setSubMchid("1900000101"); s.setSellerType("MERCHANT"); s.setSellerId(5L); s.setTotalFen(total);
        mapper.insertOrderSeller(s);
        if (tx != null) assertEquals(1, mapper.bindOrderSellerTransaction(no, tx));
    }

    @Test
    void paidSubMerchantTradesJoinAcrossCollationsAndFilterByTopicAndPayment() throws Exception {
        exec("insert into cms_registration(id, registration_no, owner_type, owner_id) values"
                + " (77, 'REG77', 1, 1), (78, 'REG78', 1, 1), (79, 'REG79', 1, 2), (80, 'REG80', 2, 1), (81, 'reg81', 1, 1)");
        seller("REG77", 10000, "4200000077");
        seller("REG78", 10000, null);
        seller("REG79", 10000, "4200000079");
        seller("REG80", 10000, "4200000080");
        seller("REG81", 10000, "4200000081"); // 大小写不同：二进制比较不得误配
        assertEquals(0, mapper.bindOrderSellerTransaction("REG77", "4200009999"));
        List<EcomOrderSeller> paid = mapper.selectPaidSubMerchantTradesForTopic(1L);
        assertEquals(1, paid.size());
        assertEquals("REG77", paid.get(0).getOutTradeNo());
        assertEquals(77L, paid.get(0).getRegistrationId());
        assertEquals("4200000077", paid.get(0).getTransactionId());
        assertEquals(10000L, paid.get(0).getTotalFen());
        PersistenceException zero = assertThrows(PersistenceException.class, () -> seller("REG90", 0, null));
        assertTrue(zero.getMessage().contains("ck_ecom_order_seller_total"));
    }

    @Test
    void ledgerComponentsMapFromProductionColumns() throws Exception {
        exec("insert into coop_settlement_original_component(topic_id, payer_registration_id, settlement_id, receiver_kind,"
                + " receiver_member_id, line_type, original_amount) values (1, 77, 11, 'ACCOUNTING', null, 'PLATFORM', 15.00),"
                + " (1, 77, 12, 'MEMBER', 66, 'MERCHANT_VERIFY', 10.00), (1, 78, 11, 'ACCOUNTING', null, 'PLATFORM', 1.00)");
        List<EcomLedgerComponent> cs = mapper.selectLedgerComponents(1L, 77L);
        assertEquals(2, cs.size());
        assertEquals(11L, cs.get(0).getSettlementId());
        assertEquals("ACCOUNTING", cs.get(0).getReceiverKind());
        assertEquals(new BigDecimal("15.00"), cs.get(0).getOriginalAmount());
        assertEquals(66L, cs.get(1).getReceiverMemberId());
        assertEquals("MERCHANT_VERIFY", cs.get(1).getLineType());
    }

    private EcomProfitShareOrder shareOrder(String no, long topic) {
        EcomProfitShareOrder o = new EcomProfitShareOrder();
        o.setOutOrderNo(EcommerceProfitShareService.outOrderNo(no, topic)); o.setOutTradeNo(no); o.setTopicId(topic);
        o.setSubMchid("1900000101"); o.setTransactionId("42"); o.setTotalFen(10000L); o.setSharedFen(2500L);
        o.setPlanHash("a".repeat(64)); o.setState("PLANNED");
        mapper.insertShareOrder(o);
        return o;
    }

    @Test
    void shareOrderIsUniquePerTradeAndTopicAndStateIsCas() {
        EcomProfitShareOrder o = shareOrder("REG77", 1);
        assertTrue(isDuplicate(assertThrows(PersistenceException.class, () -> shareOrder("REG77", 1))));
        assertEquals(1, mapper.updateShareOrderState(o.getId(), "PLANNED", "PROCESSING", null, null));
        assertEquals(0, mapper.updateShareOrderState(o.getId(), "PLANNED", "PROCESSING", null, null));
        assertEquals(1, mapper.updateShareOrderState(o.getId(), "PROCESSING", "SUCCESS", "W1", null));
        assertEquals(1, mapper.updateShareOrderState(o.getId(), "SUCCESS", "SUCCESS", null, null));
        EcomProfitShareOrder read = mapper.selectShareOrder("REG77", 1L);
        assertEquals("W1", read.getWxOrderId()); // coalesce 保留已知微信单号
        assertEquals(3, read.getAttempts());
        assertEquals(1, mapper.selectShareOrdersByState("SUCCESS", 10).size());
        EcomProfitShareOrder bad = new EcomProfitShareOrder();
        bad.setOutOrderNo("X"); bad.setOutTradeNo("REG1"); bad.setTopicId(9L); bad.setSubMchid("1"); bad.setTransactionId("1");
        bad.setTotalFen(100L); bad.setSharedFen(101L); bad.setPlanHash("b".repeat(64)); bad.setState("PLANNED");
        assertTrue(assertThrows(PersistenceException.class, () -> mapper.insertShareOrder(bad)).getMessage().contains("ck_ecom_share_amount"));
    }

    @Test
    void shareLinesAndReturnsKeepTheirUniqueKeys() {
        EcomProfitShareOrder o = shareOrder("REG77", 1);
        EcomProfitShareLine l = new EcomProfitShareLine();
        l.setShareOrderId(o.getId()); l.setSettlementId(11L); l.setLineType("PLATFORM"); l.setReceiverKind("PLATFORM");
        l.setReceiverMchid("1900000001"); l.setAmountFen(1500L);
        mapper.insertShareLine(l);
        assertTrue(isDuplicate(assertThrows(PersistenceException.class, () -> mapper.insertShareLine(l))));
        assertEquals(1, mapper.selectShareLines(o.getId()).size());

        EcomProfitShareReturn r = new EcomProfitShareReturn();
        r.setOutReturnNo(EcommerceProfitShareService.outReturnNo(o.getId(), "RF1", "1900000001")); r.setShareOrderId(o.getId());
        r.setOutRefundNo("RF1"); r.setReturnMchid("1900000001"); r.setAmountFen(1500L);
        mapper.insertReturn(r);
        EcomProfitShareReturn again = new EcomProfitShareReturn();
        again.setOutReturnNo("OTHER"); again.setShareOrderId(o.getId()); again.setOutRefundNo("RF1");
        again.setReturnMchid("1900000001"); again.setAmountFen(1L);
        assertTrue(isDuplicate(assertThrows(PersistenceException.class, () -> mapper.insertReturn(again))));
        assertEquals("PROCESSING", mapper.selectReturn(r.getOutReturnNo()).getState());
        assertEquals(1, mapper.updateReturnState(r.getId(), "PROCESSING", "SUCCESS", "WR1", null));
        assertEquals(0, mapper.updateReturnState(r.getId(), "PROCESSING", "FAILED", null, "X"));
        assertEquals("SUCCESS", mapper.selectReturnsByShareOrder(o.getId()).get(0).getState());
    }

    @Test
    void creatorPayoutIsUniquePerSource() {
        CreatorPayoutRequest p = new CreatorPayoutRequest();
        p.setOutPayoutNo("CYCPC2M1"); p.setCreatorMemberId(1L); p.setSourceType("CREATOR_REVENUE"); p.setSourceId(2L);
        p.setAmountFen(100L); p.setProvider("acme");
        mapper.insertCreatorPayout(p);
        p.setOutPayoutNo("CYCPC2M1-B");
        assertTrue(isDuplicate(assertThrows(PersistenceException.class, () -> mapper.insertCreatorPayout(p))));
        CreatorPayoutRequest read = mapper.selectCreatorPayout("CREATOR_REVENUE", 2L, 1L);
        assertEquals("REQUESTED", read.getState());
        assertEquals(1, mapper.updateCreatorPayoutState(read.getId(), "REQUESTED", "SUBMITTED", "P1", null));
        assertEquals(0, mapper.updateCreatorPayoutState(read.getId(), "REQUESTED", "FAILED", null, "X"));
    }

    @Test
    void serviceEndToEndOnRealSqlPlansSplitsAndReturnsFromTheLedger() throws Exception {
        exec("insert into mms_merchant(id, member_id, del_flag) values (5, 55, 0), (6, 66, 0)");
        exec("insert into cms_registration(id, registration_no, owner_type, owner_id) values (77, 'REG77', 1, 1), (78, 'REG78', 1, 1)");
        // REG77：平台佣金 + 卖家本人份额（唯一第三方收款方 = 卖家）；REG78：另有一个已进件的二级商户份额。
        exec("insert into coop_settlement_original_component(topic_id, payer_registration_id, settlement_id, receiver_kind,"
                + " receiver_member_id, line_type, original_amount) values (1, 77, 11, 'ACCOUNTING', null, 'PLATFORM', 15.00),"
                + " (1, 77, 12, 'MEMBER', 55, 'MERCHANT_VERIFY', 10.00), (1, 77, 13, 'MEMBER', 55, 'INITIATOR_MARGIN', 75.00),"
                + " (1, 78, 11, 'ACCOUNTING', null, 'PLATFORM', 15.00),"
                + " (1, 78, 12, 'MEMBER', 66, 'MERCHANT_VERIFY', 10.00), (1, 78, 13, 'MEMBER', 55, 'INITIATOR_MARGIN', 75.00)");
        EcomSubMerchant seller = sub("MERCHANT", 5);
        EcomSubMerchant other = sub("MERCHANT", 6);
        mapper.markApplymentSubmitted(seller.getId(), 1L);
        mapper.markApplymentSubmitted(other.getId(), 2L);
        mapper.updateApplymentStatus(seller.getId(), "CHECKING", "FINISH", null, null, "1900000101", null);
        mapper.updateApplymentStatus(other.getId(), "CHECKING", "FINISH", null, null, "1900000202", null);
        seller("REG77", 10000, "4200000077");
        seller("REG78", 10000, "4200000078");
        exec("insert into coop_settlement(id, topic_id, payable_time) values (11, 1, date_sub(now(), interval 1 day)),"
                + " (12, 1, date_sub(now(), interval 1 day)), (13, 1, date_add(now(), interval 1 day))");

        PayoutSwitches on = new PayoutSwitches(true, true, true, "1900000001", "wxsp", "", 30, false, "none");
        FakeEcommerceGateway gateway = new FakeEcommerceGateway();
        EcommerceProfitShareService service = new EcommerceProfitShareService(mapper, gateway, on, TransactionOperations.withoutTransaction());
        assertEquals(2, service.planTopic(1));
        assertEquals(0, service.planTopic(1));
        EcomProfitShareOrder order = mapper.selectShareOrder("REG77", 1L);
        assertEquals("PLANNED", order.getState());
        assertEquals(1500L, order.getSharedFen());
        assertEquals(3, mapper.selectShareLines(order.getId()).size());
        // f-money 待决定 2：合单关时需要分给另一个二级商户的单整单转人工，不提交任何分账。
        EcomProfitShareOrder frozen = mapper.selectShareOrder("REG78", 1L);
        assertEquals("MANUAL", frozen.getState());
        assertEquals(ProfitSharePlanner.SUB_TO_SUB_UNSUPPORTED, frozen.getManualReason());
        assertEquals(0, service.submitPlanned(10), "l-payout2：结算头 13 未到期，不提交");
        exec("update coop_settlement set payable_time = date_sub(now(), interval 1 hour) where id = 13");
        assertEquals(1, service.submitPlanned(10));
        assertEquals(0, service.submitPlanned(10));
        assertEquals("SUCCESS", mapper.selectShareOrder("REG77", 1L).getState());
        assertEquals("MANUAL", mapper.selectShareOrder("REG78", 1L).getState());
        assertEquals(List.of(new ProfitSharePlanner.Receiver("1900000001", 1500)), gateway.lastReceivers);
        assertTrue(gateway.calls.stream().noneMatch(c -> c.contains(frozen.getOutOrderNo())), gateway.calls.toString());
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund("REG77", "RF1", 4000, 0));
        long returned = mapper.selectReturnsByShareOrder(order.getId()).stream().mapToLong(EcomProfitShareReturn::getAmountFen).sum();
        assertEquals(600, returned); // round(1500 × 4000 / 10000)
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, service.returnForRefund("REG77", "RF2", 6000, 4000));
        returned = mapper.selectReturnsByShareOrder(order.getId()).stream().mapToLong(EcomProfitShareReturn::getAmountFen).sum();
        assertEquals(1500, returned);
        Map<String, Long> byMchid = new java.util.TreeMap<>();
        mapper.selectReturnsByShareOrder(order.getId()).forEach(r -> byMchid.merge(r.getReturnMchid(), r.getAmountFen(), Long::sum));
        assertEquals(Map.of("1900000001", 1500L), byMchid);
    }
    @Test
    void personalClubStatementUsesReceivedSnapshotAndNeverCreditsTheWallet() throws Exception {
        EcomProfitShareOrder order = shareOrder("PERSONAL-STATEMENT", -19);
        EcomProfitShareLine line = new EcomProfitShareLine();
        line.setShareOrderId(order.getId()); line.setSettlementId(-11L); line.setLineType("merchant");
        line.setReceiverKind("PERSONAL"); line.setReceiverType("PERSONAL_OPENID");
        line.setReceiverAppid("wx-platform"); line.setReceiverMchid("original-openid");
        line.setReceiverMemberId(55L); line.setClubId(7L); line.setClubName("原名称"); line.setAmountFen(2500L);
        mapper.insertShareLine(line);
        var statement = session.getMapper(com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper.class);
        var query = new com.chengyinhub.business.domain.UmsMemberBalanceDetail();
        query.setMemberId(55L); query.getParams().put("includeExternalClubIncome", true);
        assertTrue(statement.selectUmsMemberBalanceDetailList(query).isEmpty(), "planned is not received income");
        exec("update ecom_profit_share_order set state='SUCCESS' where id=" + order.getId());
        session.clearCache();
        var received = statement.selectUmsMemberBalanceDetailList(query);
        assertEquals(1, received.size());
        assertEquals("来自俱乐部 原名称", received.getFirst().getChangeReason());
        assertEquals(0, new BigDecimal("25.00").compareTo(received.getFirst().getChangeBalance()));
        assertEquals(2L, received.getFirst().getEventType());
        assertTrue(received.getFirst().getId() < 0);
        assertNull(received.getFirst().getAfterBalance());
        query.setMemberId(99L); assertTrue(statement.selectUmsMemberBalanceDetailList(query).isEmpty());
        query.setMemberId(55L); query.setEventType(1L);
        assertTrue(statement.selectUmsMemberBalanceDetailList(query).isEmpty());
        query.setEventType(null); query.getParams().remove("includeExternalClubIncome");
        assertTrue(statement.selectUmsMemberBalanceDetailList(query).isEmpty(), "wallet query stays wallet-only");
        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery("select count(*) from ums_member_balance_detail")) {
            assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
        }
    }
    @Test
    void sessionTerminalFlagWaitsForAllReceiptsAndUnresolvedHolds() throws Exception {
        EcomProfitShareOrder order = shareOrder("SESSION-COMPLETE", -19);
        EcomProfitShareLine line = new EcomProfitShareLine();
        line.setShareOrderId(order.getId()); line.setSettlementId(-11L); line.setLineType("merchant");
        line.setReceiverKind("PERSONAL"); line.setReceiverType("PERSONAL_OPENID");
        line.setReceiverAppid("wx-platform"); line.setReceiverMchid("original-openid");
        line.setReceiverMemberId(55L); line.setClubId(7L); line.setClubName("原名称"); line.setAmountFen(2500L);
        mapper.insertShareLine(line);
        exec("insert into cms_activity(id,del_flag,is_settlement) values(19,0,0)");
        exec("insert into cms_registration(id,registration_no,owner_type,owner_id) values(201,'SESSION-REG',2,19)");
        exec("insert into club_personal_payout_beneficiary(scope_type,scope_id,club_id,club_name,member_id,frozen_at,payout_ready_at)"
                + " values('ACTIVITY',19,7,'原名称',55,now(),now())");
        exec("insert into settlement_detail(id,source_type,source_id,receiver_type,receiver_id,settlement_amount,status,club_id,club_name)"
                + " values(11,'registration',201,'merchant',55,25,0,7,'原名称'),(12,'registration',201,'platform',0,1,0,null,null)");
        var sessions = session.getMapper(com.chengyinhub.business.mapper.EcomSessionPayoutMapper.class);
        assertEquals(0, sessions.completeActivityIfReceived(order.getId()), "planned is not received");
        exec("update ecom_profit_share_order set state='SUCCESS' where id=" + order.getId());
        assertEquals(1, sessions.markShareDetailsReceived(order.getId()));
        assertEquals(0, sessions.completeActivityIfReceived(order.getId()), "another obligation remains pending");
        exec("update settlement_detail set status=1 where id=12");
        exec("insert into cms_activity_settlement_hold(registration_id,status) values(201,0)");
        assertEquals(0, sessions.completeActivityIfReceived(order.getId()), "open complaint still holds settlement");
        exec("update cms_activity_settlement_hold set status=1 where registration_id=201");
        assertEquals(1, sessions.completeActivityIfReceived(order.getId()));
        assertEquals(0, sessions.completeActivityIfReceived(order.getId()));
    }
}
