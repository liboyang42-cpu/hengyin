package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineRefund;
import com.chengyinhub.business.domain.EcomCombineReturn;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomCombineSubOrder;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.EcomCombineMapper;
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

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * l-payout3：真实 MySQL 8 + 真实 MyBatis 执行 {@code EcomCombineMapper.xml} 与 {@code migration_20261111_ecommerce_combine.sql}。
 *
 * <p>被读取的既有表只建本 mapper 读到的列，列类型 / 字符集照生产（registration_no 为 utf8mb4_general_ci，
 * 新表单号为 ascii_bin），证明跨排序规则连接在真库成立。只在 {@link MybatisItDatabase} 分配的一次性 scratch 库里跑，
 * 结束时 DROP 并回读确认。⚠️ surefire 默认只收 *Test，本类需 -Dtest 点名（Real Storage IT 工作流按登记执行）。
 */
class EcomCombineMapperMySqlIT {
    private static final String SUITE = "ecom_combine";
    private static final String MIGRATION = "sql/migration_20261111_ecommerce_combine.sql";
    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;
    private static String database;
    private static String adminUrl;

    private SqlSession session;
    private EcomCombineMapper mapper;

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
        cfg.setEnvironment(new Environment("ecom-combine", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/EcomCombineMapper.xml";
        try (InputStream in = EcomCombineMapperMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("mapper XML 不在 classpath:" + resource);
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cms_registration (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " registration_no varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,"
                    + " owner_type int DEFAULT '0', owner_id bigint NOT NULL, UNIQUE KEY uk_reg_no(registration_no))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.execute("CREATE TABLE cms_topic (id bigint NOT NULL PRIMARY KEY, is_settlement tinyint DEFAULT '0')"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE coop_settlement (id bigint NOT NULL PRIMARY KEY, topic_id bigint NOT NULL,"
                    + " del_flag int NOT NULL DEFAULT 0, status int NOT NULL DEFAULT 0, payable_time datetime NULL)"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE coop_complaint (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, topic_id bigint NOT NULL,"
                    + " status int NOT NULL, del_flag int NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE wx_verified_payment_fact (id bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " mchid varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + " business_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + " wx_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL)"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE coop_settlement_original_component (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " topic_id BIGINT NOT NULL, payer_registration_id BIGINT NOT NULL, settlement_id BIGINT NOT NULL,"
                    + " payment_fact_id BIGINT UNSIGNED NOT NULL,"
                    + " receiver_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + " receiver_member_id BIGINT UNSIGNED NULL, line_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + " original_amount DECIMAL(15,2) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE coop_settlement_original_adjustment (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " component_id BIGINT NOT NULL, settlement_id BIGINT NOT NULL, amount DECIMAL(15,2) NOT NULL)"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            ItSchema.applyMigrations(c, MIGRATION);
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null || adminUrl == null) return;
        assertTrue(database.matches("chengyin_mybatis_ecom_combine[a-z0-9_]*"), "只许 DROP 本套 scratch 库:" + database);
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
        mapper = session.getMapper(EcomCombineMapper.class);
        try (Statement st = session.getConnection().createStatement()) {
            for (String t : List.of("ecom_supplier_payable", "ecom_combine_return", "ecom_combine_settlement", "ecom_combine_refund",
                    "ecom_combine_sub_order", "ecom_combine_order", "cms_registration", "cms_topic", "coop_settlement",
                    "coop_complaint", "wx_verified_payment_fact", "coop_settlement_original_component",
                    "coop_settlement_original_adjustment"))
                st.execute("delete from " + t);
        }
    }

    @AfterEach
    void close() { if (session != null) session.close(); }

    private void exec(String sql) throws Exception {
        try (Statement st = session.getConnection().createStatement()) { st.execute(sql); }
    }

    private long scalar(String sql) throws Exception {
        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static boolean isConstraint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) return true;
            // MySQL 8 的 CHECK 违反是 SQLState HY000 / 3819。
            if (t instanceof SQLException sql && sql.getErrorCode() == 3819) return true;
        }
        return false;
    }

    private EcomCombineOrder order(String no, long seller, long held) {
        EcomCombineOrder o = new EcomCombineOrder();
        o.setOutTradeNo(no); o.setSellerSubMchid("1900000101"); o.setSellerType("MERCHANT"); o.setSellerId(5L);
        o.setPlatformSubMchid("1900000888"); o.setTotalFen(seller + held); o.setSellerFen(seller); o.setHeldFen(held);
        o.setCommissionFen(1500L); o.setReserveFen(held - 1500L);
        return o;
    }

    private EcomCombineSubOrder sub(String combineNo, String role, String no, long amount) {
        EcomCombineSubOrder s = new EcomCombineSubOrder();
        s.setCombineOutTradeNo(combineNo); s.setRole(role); s.setOutTradeNo(no); s.setBusinessOutTradeNo("R1");
        s.setSubMchid("SELLER".equals(role) ? "1900000101" : "1900000888"); s.setAmountFen(amount);
        s.setProfitSharing("SELLER".equals(role) ? 1 : 0);
        return s;
    }

    private EcomCombineSettlement settlement(String no, long topicId, long shortfall, long surplus) {
        EcomCombineSettlement s = new EcomCombineSettlement();
        s.setOutOrderNo("CYCS" + no + topicId); s.setBusinessOutTradeNo(no); s.setTopicId(topicId);
        s.setSellerSubMchid("1900000101"); s.setSellerTransactionId("TX-S" + no); s.setSellerFen(7500L); s.setHeldFen(2500L);
        s.setPlatformFen(1500L); s.setShortfallFen(shortfall); s.setSurplusFen(surplus); s.setPlanHash("a".repeat(64));
        s.setState("PLANNED");
        return s;
    }

    @Test
    void migrationIsRepeatableAndChecksRejectInconsistentSplits() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ItSchema.applyMigrations(c, MIGRATION);
        }
        assertEquals(6, scalar("select count(*) from information_schema.tables where table_schema=database()"
                + " and table_name like 'ecom\\_%' and table_name in ('ecom_combine_order','ecom_combine_sub_order',"
                + "'ecom_combine_refund','ecom_combine_settlement','ecom_combine_return','ecom_supplier_payable')"));
        EcomCombineOrder broken = order("R-BAD", 7500L, 2500L);
        broken.setTotalFen(9999L);
        PersistenceException total = assertThrows(PersistenceException.class, () -> mapper.insertCombineOrder(broken));
        assertTrue(isConstraint(total), "子单之和必须等于实付");
        EcomCombineOrder same = order("R-SAME", 7500L, 2500L);
        same.setPlatformSubMchid("1900000101");
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertCombineOrder(same))), "卖家与平台自营不能同号");
        EcomCombineOrder zero = order("R-ZERO", 0L, 2500L);
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertCombineOrder(zero))));
    }

    @Test
    void combineOrderIsUniqueAndPaidIdentityBindsOnce() {
        mapper.insertCombineOrder(order("R1", 7500L, 2500L));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertCombineOrder(order("R1", 7500L, 2500L)))));
        assertEquals(1, mapper.bindCombinePaid("R1", "R1", "TX-S"));
        assertEquals(0, mapper.bindCombinePaid("R1", "R1-2", "TX-OTHER"));
        EcomCombineOrder read = mapper.selectCombineOrder("R1");
        assertEquals("R1", read.getCombineOutTradeNo());
        assertEquals("TX-S", read.getSellerTransactionId());
        assertEquals(10000L, read.getTotalFen());
        assertNull(mapper.selectCombineOrder("r1"), "ascii_bin：单号大小写敏感");
    }

    @Test
    void subOrdersAreUniquePerRoleAndNumberAndTransactionBindsOnce() {
        mapper.insertSubOrder(sub("R1", "PLATFORM_HELD", "CYC-P", 2500L));
        mapper.insertSubOrder(sub("R1", "SELLER", "CYC-S", 7500L));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertSubOrder(sub("R1", "SELLER", "CYC-X", 7500L)))));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertSubOrder(sub("R2", "SELLER", "CYC-S", 7500L)))));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertSubOrder(sub("R3", "OTHER", "CYC-O", 1L)))));
        List<EcomCombineSubOrder> subs = mapper.selectSubOrders("R1");
        assertEquals(List.of("SELLER", "PLATFORM_HELD"), subs.stream().map(EcomCombineSubOrder::getRole).toList());
        assertEquals(1, mapper.bindSubOrderTransaction("R1", "SELLER", "TX-S"));
        assertEquals(0, mapper.bindSubOrderTransaction("R1", "SELLER", "TX-OTHER"));
        assertEquals("TX-S", mapper.selectSubOrders("R1").get(0).getTransactionId());
    }

    private EcomCombineRefund refund(String parent, String role, long fen) {
        EcomCombineRefund r = new EcomCombineRefund();
        r.setOutRefundNo("CYCR" + parent + role); r.setParentOutRefundNo(parent); r.setBusinessOutTradeNo("R1"); r.setRole(role);
        r.setSubMchid("1900000101"); r.setSubOutTradeNo("CYC-S"); r.setTransactionId("TX-S"); r.setRefundFen(fen);
        r.setTotalFen(7500L); r.setState("SELLER".equals(role) ? "RETURNING" : "SUBMITTING");
        return r;
    }

    @Test
    void subRefundsAreUniquePerParentAndRoleWithCasStateAndAmountCheck() {
        mapper.insertRefund(refund("RF1", "PLATFORM_HELD", 1250L));
        mapper.insertRefund(refund("RF1", "SELLER", 3750L));
        EcomCombineRefund dup = refund("RF1", "SELLER", 3750L);
        dup.setOutRefundNo("OTHER-NO");
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertRefund(dup))));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertRefund(refund("RF2", "SELLER", 7501L)))),
                "子单退款不能超过子单实付");
        assertEquals(List.of("SELLER", "PLATFORM_HELD"), mapper.selectRefundsByParent("RF1").stream().map(EcomCombineRefund::getRole).toList());
        assertEquals(1, mapper.updateRefundState("CYCRRF1SELLER", "RETURNING", "SUBMITTING", null, null));
        assertEquals(0, mapper.updateRefundState("CYCRRF1SELLER", "RETURNING", "SUBMITTED", "WX1", null));
        assertEquals(1, mapper.updateRefundState("CYCRRF1SELLER", "SUBMITTING", "SUBMITTED", "WX1", null));
        assertEquals(1, mapper.updateRefundState("CYCRRF1SELLER", "SUBMITTED", "SUCCESS", null, null));
        EcomCombineRefund read = mapper.selectRefund("CYCRRF1SELLER");
        assertEquals("SUCCESS", read.getState());
        assertEquals("WX1", read.getRefundId(), "refund_id 一经写入不被空值覆盖");
        assertEquals(2, mapper.selectRefundsByTrade("R1").size());
    }

    @Test
    void paidOrdersForTopicJoinAcrossCollationsAndMissingSettlementBackfillFindsOnlyUnplannedSettledTopics() throws Exception {
        exec("insert into cms_registration(id, registration_no, owner_type, owner_id) values (11, 'R1', 1, 9), (12, 'R2', 1, 9), (13, 'R3', 2, 9)");
        exec("insert into cms_topic(id, is_settlement) values (9, 1)");
        mapper.insertCombineOrder(order("R1", 7500L, 2500L));
        mapper.insertCombineOrder(order("R2", 7500L, 2500L));
        mapper.insertCombineOrder(order("R3", 7500L, 2500L));
        mapper.bindCombinePaid("R1", "R1", "TX-S1");
        mapper.bindCombinePaid("R3", "R3", "TX-S3");
        List<EcomCombineOrder> paid = mapper.selectPaidCombineOrdersForTopic(9L);
        assertEquals(List.of("R1"), paid.stream().map(EcomCombineOrder::getOutTradeNo).toList(), "未付 / 活动报名不算");
        assertEquals(11L, paid.get(0).getRegistrationId());

        assertTrue(mapper.selectSettledTopicsMissingCombineSettlements(10).isEmpty(), "还没有冻结账本分项");
        exec("insert into coop_settlement_original_component(topic_id, payer_registration_id, settlement_id, payment_fact_id,"
                + " receiver_kind, receiver_member_id, line_type, original_amount) values (9, 11, 700, 1, 'ACCOUNTING', null, 'PLATFORM', 15.00)");
        assertEquals(List.of(9L), mapper.selectSettledTopicsMissingCombineSettlements(10));
        mapper.insertSettlement(settlement("R1", 9L, 0L, 0L));
        assertTrue(mapper.selectSettledTopicsMissingCombineSettlements(10).isEmpty());
    }

    @Test
    void settlementIsUniquePerTradeAndTopicAndDueOnlyWhenEveryHeadIsPayableWithoutOpenComplaint() throws Exception {
        exec("insert into cms_registration(id, registration_no, owner_type, owner_id) values (11, 'R1', 1, 9)");
        exec("insert into coop_settlement(id, topic_id, payable_time) values (700, 9, '2026-11-01 00:00:00'), (701, 9, '2026-12-30 00:00:00')");
        exec("insert into coop_settlement_original_component(topic_id, payer_registration_id, settlement_id, payment_fact_id,"
                + " receiver_kind, receiver_member_id, line_type, original_amount) values"
                + " (9, 11, 700, 1, 'ACCOUNTING', null, 'PLATFORM', 15.00), (9, 11, 701, 1, 'MEMBER', 55, 'INITIATOR_MARGIN', 85.00)");
        EcomCombineSettlement s = settlement("R1", 9L, 0L, 0L);
        mapper.insertSettlement(s);
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertSettlement(settlement("R1", 9L, 0L, 0L)))));
        EcomCombineSettlement both = settlement("R2", 9L, 100L, 100L);
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertSettlement(both))), "补差与退回不能同时存在");
        Date now = java.sql.Timestamp.valueOf("2026-11-20 00:00:00");
        assertTrue(mapper.selectDuePlannedSettlements(now, 10).isEmpty(), "一张头未到期");
        exec("update coop_settlement set payable_time = '2026-11-02 00:00:00' where id = 701");
        assertEquals(1, mapper.selectDuePlannedSettlements(now, 10).size());
        exec("insert into coop_complaint(topic_id, status) values (9, 1)");
        assertTrue(mapper.selectDuePlannedSettlements(now, 10).isEmpty(), "主题有未结客诉");
        exec("update coop_complaint set status = 3");
        exec("update coop_settlement set del_flag = 2 where id = 700");
        assertTrue(mapper.selectDuePlannedSettlements(now, 10).isEmpty(), "结算头已删除");
        exec("update coop_settlement set del_flag = 0 where id = 700");
        assertEquals(1, mapper.updateSettlementState(s.getId(), "PLANNED", "PROCESSING", null, null));
        assertEquals(0, mapper.updateSettlementState(s.getId(), "PLANNED", "PROCESSING", null, null));
        assertEquals(1, mapper.updateSettlementState(s.getId(), "PROCESSING", "SUCCESS", "W1", null));
        assertTrue(mapper.selectDuePlannedSettlements(now, 10).isEmpty());
        assertEquals("W1", mapper.selectSettlementById(s.getId()).getWxOrderId());
        assertEquals(1, mapper.selectSettlementsByState("SUCCESS", 10).size());
        assertEquals(1, mapper.selectSettlementsByTrade("R1").size());
    }

    @Test
    void returnsAreUniquePerSettlementAndRefund() {
        EcomCombineReturn r = new EcomCombineReturn();
        r.setOutReturnNo("CYCT1"); r.setCombineSettlementId(1L); r.setParentOutRefundNo("RF1"); r.setReturnMchid("1900000001");
        r.setAmountFen(400L); r.setState("PROCESSING");
        mapper.insertReturn(r);
        EcomCombineReturn again = new EcomCombineReturn();
        again.setOutReturnNo("CYCT2"); again.setCombineSettlementId(1L); again.setParentOutRefundNo("RF1"); again.setReturnMchid("1900000001");
        again.setAmountFen(400L); again.setState("PROCESSING");
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertReturn(again))));
        assertEquals(1, mapper.updateReturnState("CYCT1", "PROCESSING", "SUCCESS", "WR1", null));
        assertEquals(0, mapper.updateReturnState("CYCT1", "PROCESSING", "FAILED", null, "X"));
        assertEquals("SUCCESS", mapper.selectReturn("CYCT1").getState());
        assertEquals(1, mapper.selectReturnsBySettlement(1L).size());
    }

    private EcomSupplierPayable payable(String source, String no, long member, String lineType, long fen) {
        EcomSupplierPayable p = new EcomSupplierPayable();
        p.setSource(source); p.setBusinessOutTradeNo(no); p.setTopicId(9L); p.setSettlementId(700L); p.setLineType(lineType);
        p.setPayeeMemberId(member); p.setAmountFen(fen);
        return p;
    }

    @Test
    void payablesAreUniqueMarkPaidOnceAndRefundHoldsOnlyPending() {
        mapper.insertPayable(payable("COMBINED", "R1", 66L, "MERCHANT_VERIFY", 1000L));
        mapper.insertPayable(payable("COMBINED", "R1", 55L, "SURPLUS", 1600L));
        mapper.insertPayable(payable("RELAY", "R1", 66L, "MERCHANT_VERIFY", 1000L));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertPayable(payable("COMBINED", "R1", 66L, "MERCHANT_VERIFY", 1000L)))));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertPayable(payable("OTHER", "R2", 66L, "X", 1L)))));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertPayable(payable("COMBINED", "R2", 66L, "X", 0L)))));
        List<EcomSupplierPayable> rows = mapper.selectPayablesByTrade("R1");
        assertEquals(3, rows.size());
        long first = rows.get(0).getId();
        assertEquals(1, mapper.markPayablePaid(first, "BANK-1", "ops"));
        assertEquals(0, mapper.markPayablePaid(first, "BANK-2", "ops"));
        assertEquals(2, mapper.holdPayablesForRefund("R1"), "已付的不挂起");
        assertEquals(1, mapper.selectPayablesForAdmin("PAID", 10).size());
        assertEquals(2, mapper.selectPayablesForAdmin("HOLD_REFUND", 10).size());
        assertEquals(3, mapper.selectPayablesForAdmin(null, 10).size());
        assertEquals("BANK-1", mapper.selectPayablesForAdmin("PAID", 10).get(0).getPaidReference());
    }

    @Test
    void combinedPortionCountsOnlyFactsOfTheBoundCombinedOrder() throws Exception {
        mapper.insertCombineOrder(order("R1", 7500L, 2500L));
        mapper.bindCombinePaid("R1", "R1", "TX-S");
        // 1 = 合单事实；2 = 同业务单号但别的微信单号（例如此前平台商户号尝试）；3 = 别的业务单。
        exec("insert into wx_verified_payment_fact(id, mchid, business_out_trade_no, wx_out_trade_no) values"
                + " (1, '1900000001', 'R1', 'R1'), (2, '1900000109', 'R1', 'WXP-OTHER'), (3, '1900000001', 'R9', 'R9')");
        exec("insert into coop_settlement_original_component(id, topic_id, payer_registration_id, settlement_id, payment_fact_id,"
                + " receiver_kind, receiver_member_id, line_type, original_amount) values"
                + " (1, 9, 11, 701, 1, 'MEMBER', 55, 'INITIATOR_MARGIN', 60.00),"
                + " (2, 9, 12, 701, 2, 'MEMBER', 55, 'INITIATOR_MARGIN', 30.00),"
                + " (3, 9, 13, 701, 3, 'MEMBER', 55, 'INITIATOR_MARGIN', 10.00)");
        exec("insert into coop_settlement_original_adjustment(component_id, settlement_id, amount) values (1, 701, -6.00), (2, 701, -3.00)");
        assertEquals(0, new BigDecimal("54.00").compareTo(mapper.sumCombinedPortionForSettlement(701L)));
        assertEquals(0, BigDecimal.ZERO.compareTo(mapper.sumCombinedPortionForSettlement(702L)));
        assertEquals(1, mapper.countCombinedPaymentFact(1L));
        assertEquals(0, mapper.countCombinedPaymentFact(2L));
        assertEquals(0, mapper.countCombinedPaymentFact(3L));
        assertEquals(1, scalar("select count(*) from ecom_combine_order where out_trade_no = 'R1'"));
    }
}
