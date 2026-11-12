package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomRefund;
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

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * l-payout2：真实 MySQL 8 + 真实 MyBatis 执行 {@code EcomPayoutMapper.xml} 的接线 SQL 与
 * {@code migration_20261110_ecommerce_payout_wiring.sql}（连同其依赖 {@code migration_20261110_ecommerce_payout.sql}）。
 *
 * <p>被读取的既有表只建本 mapper 读到的列，列类型 / 字符集照生产（registration_no 为 utf8mb4_general_ci，
 * 新表单号为 ascii_bin），证明跨排序规则连接在真库成立；外键省略。
 * 只在 {@link MybatisItDatabase} 分配的一次性 scratch 库里跑，结束时 DROP 并回读确认。
 * ⚠️ surefire 默认只收 *Test，本类需 -Dtest 点名（Real Storage IT 工作流按登记执行）。
 */
class EcomPayoutWiringMySqlIT {
    private static final String SUITE = "ecom_wiring";
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
        cfg.setEnvironment(new Environment("ecom-wiring", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/EcomPayoutMapper.xml";
        try (InputStream in = EcomPayoutWiringMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
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
                    + " business_out_trade_no varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL)"
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
            st.execute("CREATE TABLE mms_merchant (id bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " member_id bigint DEFAULT NULL, del_flag int DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.execute("CREATE TABLE club (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, member_id bigint NOT NULL,"
                    + " del_flag int DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
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
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null || adminUrl == null) return;
        assertTrue(database.matches("chengyin_mybatis_ecom_wiring[a-z0-9_]*"), "只许 DROP 本套 scratch 库:" + database);
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
            for (String t : List.of("ecom_refund", "ecom_profit_share_return", "ecom_profit_share_line", "ecom_profit_share_order",
                    "ecom_order_seller", "ecom_sub_merchant", "cms_registration", "cms_topic", "coop_settlement", "coop_complaint",
                    "wx_verified_payment_fact", "coop_settlement_original_component", "coop_settlement_original_adjustment"))
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
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLIntegrityConstraintViolationException) return true;
        return false;
    }

    private void sellerTrade(String no, String sub, String tx) {
        EcomOrderSeller row = new EcomOrderSeller();
        row.setOutTradeNo(no); row.setSubMchid(sub); row.setSellerType("MERCHANT"); row.setSellerId(7L); row.setTotalFen(3000L);
        mapper.insertOrderSeller(row);
        if (tx != null) mapper.bindOrderSellerTransaction(no, tx);
    }

    private EcomProfitShareOrder shareOrder(String no, long topicId, String state) {
        EcomProfitShareOrder o = new EcomProfitShareOrder();
        o.setOutOrderNo("CYPS" + no); o.setOutTradeNo(no); o.setTopicId(topicId); o.setSubMchid("1900000999");
        o.setTransactionId("TX" + no); o.setTotalFen(3000L); o.setSharedFen(300L); o.setPlanHash("a".repeat(64));
        o.setState(state); o.setManualReason("MANUAL".equals(state) ? "RECEIVER_NOT_ONBOARDED" : null);
        mapper.insertShareOrder(o);
        return o;
    }

    private void line(long shareOrderId, long settlementId) {
        EcomProfitShareLine l = new EcomProfitShareLine();
        l.setShareOrderId(shareOrderId); l.setSettlementId(settlementId); l.setLineType("PLATFORM"); l.setReceiverKind("PLATFORM");
        l.setReceiverMchid("1900000001"); l.setAmountFen(300L);
        mapper.insertShareLine(l);
    }

    @Test
    void migrationRegistersPausedJobsAndUngrantedMenuAndIsRepeatable() throws Exception {
        assertEquals(2, scalar("select count(*) from sys_job where invoke_target in ('ecommercePayoutTask.shareAndRetry()',"
                + "'ecommercePayoutTask.syncOnboarding()') and status='1' and concurrent='1' and misfire_policy='3'"));
        assertEquals(1, scalar("select count(*) from sys_menu where perms='business:ecommercepayout:query' and menu_type='C'"
                + " and component='business/finance/ecommerce-payout/index'"
                + " and parent_id=(select menu_id from (select menu_id from sys_menu where path='funds-aftercare' and menu_type='M') p)"));
        assertEquals(1, scalar("select count(*) from sys_menu where perms='business:ecommercepayout:edit' and menu_type='F'"));
        assertEquals(0, scalar("select count(*) from sys_role_menu"));
        // 重复执行：表重建、任务与菜单不重复。
        try (Connection c = dataSource.getConnection()) {
            ItSchema.applyMigrations(c, "sql/migration_20261110_ecommerce_payout_wiring.sql");
        }
        assertEquals(2, scalar("select count(*) from sys_job where invoke_target like 'ecommercePayoutTask.%'"));
        assertEquals(2, scalar("select count(*) from sys_menu where perms like 'business:ecommercepayout:%'"));
    }

    @Test
    void refundRowIsUniqueCheckedAndCompareAndSet() {
        EcomRefund r = new EcomRefund();
        r.setOutRefundNo("RF1"); r.setOutTradeNo("R1"); r.setSubMchid("1900000999"); r.setTransactionId("TX1");
        r.setRefundFen(1000L); r.setTotalFen(3000L);
        assertEquals(1, mapper.insertRefund(r));
        assertTrue(isConstraint(assertThrows(PersistenceException.class, () -> mapper.insertRefund(r))));
        EcomRefund over = new EcomRefund();
        over.setOutRefundNo("RF2"); over.setOutTradeNo("R1"); over.setSubMchid("1900000999"); over.setTransactionId("TX1");
        over.setRefundFen(3001L); over.setTotalFen(3000L);
        assertTrue(assertThrows(PersistenceException.class, () -> mapper.insertRefund(over)).getMessage().contains("ck_ecom_refund_amount"));
        assertEquals("RETURNING", mapper.selectRefund("RF1").getState());
        assertEquals(0, mapper.updateRefundState("RF1", "SUBMITTING", "SUBMITTED", "WX1", null));
        assertEquals(1, mapper.updateRefundState("RF1", "RETURNING", "SUBMITTING", null, null));
        assertEquals(1, mapper.updateRefundState("RF1", "SUBMITTING", "SUBMITTED", "WX1", null));
        assertEquals(1, mapper.updateRefundState("RF1", "SUBMITTED", "ABNORMAL", null, "PROVIDER_ABNORMAL"));
        EcomRefund read = mapper.selectRefund("RF1");
        assertEquals("ABNORMAL", read.getState());
        assertEquals("WX1", read.getRefundId(), "退款单号只写一次，后续空值不覆盖");
        assertEquals(1, mapper.selectRefundsByTrade("R1").size());
        assertEquals(1, mapper.selectRefundsByStates(List.of("CLOSED", "ABNORMAL"), 10).size());
    }

    @Test
    void partnerPortionCountsOnlySellerCollectedComponentsAndTheirAdjustments() throws Exception {
        sellerTrade("R-SELL", "1900000999", "TXS");
        exec("insert into wx_verified_payment_fact(id, mchid, business_out_trade_no) values (1,'1900000999','R-SELL'),"
                + " (2,'1900000109','R-PLAT'), (3,'1900000888','R-SELL')");
        exec("insert into coop_settlement_original_component(id, topic_id, payer_registration_id, settlement_id, payment_fact_id,"
                + " receiver_kind, receiver_member_id, line_type, original_amount) values"
                + " (11, 9, 101, 700, 1, 'MEMBER', 300, 'INITIATOR_MARGIN', 10.00),"
                + " (12, 9, 102, 700, 2, 'MEMBER', 300, 'INITIATOR_MARGIN', 20.00),"
                + " (13, 9, 101, 700, 3, 'MEMBER', 300, 'INITIATOR_MARGIN', 5.00),"
                + " (14, 9, 101, 701, 1, 'ACCOUNTING', null, 'PLATFORM', 1.00)");
        exec("insert into coop_settlement_original_adjustment(component_id, settlement_id, amount) values (11, 700, -3.00), (12, 700, -4.00)");
        assertEquals(0, new BigDecimal("7.00").compareTo(mapper.sumPartnerPortionForSettlement(700L)),
                "只算事实商户号 = 登记 sub_mchid 的分项：10 - 3");
        assertEquals(0, new BigDecimal("1.00").compareTo(mapper.sumPartnerPortionForSettlement(701L)));
        assertEquals(0, BigDecimal.ZERO.compareTo(mapper.sumPartnerPortionForSettlement(999L)));
        assertEquals(1, mapper.countPartnerPaymentFact(1L));
        assertEquals(0, mapper.countPartnerPaymentFact(2L));
        assertEquals(0, mapper.countPartnerPaymentFact(3L), "同单号但商户号不是登记的 sub_mchid：不算");
    }

    @Test
    void plannedSplitIsDueOnlyWhenEveryHeadIsDueAndTopicHasNoOpenComplaint() throws Exception {
        exec("insert into coop_settlement(id, topic_id, del_flag, payable_time) values"
                + " (700, 9, 0, date_sub(now(), interval 1 day)), (701, 9, 0, date_add(now(), interval 3 day))");
        EcomProfitShareOrder o = shareOrder("R1", 9L, "PLANNED");
        line(o.getId(), 700L);
        line(o.getId(), 701L);
        assertTrue(mapper.selectDuePlannedShareOrders(new Date(), 10).isEmpty(), "有一张结算头未到期");
        exec("update coop_settlement set payable_time = date_sub(now(), interval 1 hour) where id = 701");
        assertEquals(List.of(o.getId()), mapper.selectDuePlannedShareOrders(new Date(), 10).stream().map(EcomProfitShareOrder::getId).toList());
        exec("insert into coop_complaint(topic_id, status, del_flag) values (9, 1, 0)");
        assertTrue(mapper.selectDuePlannedShareOrders(new Date(), 10).isEmpty(), "主题有未结客诉");
        exec("update coop_complaint set status = 3");
        exec("update coop_settlement set del_flag = 2 where id = 700");
        assertTrue(mapper.selectDuePlannedShareOrders(new Date(), 10).isEmpty(), "结算头已删除");
    }

    @Test
    void settledTopicsMissingPlansAreFoundAcrossCollationsUntilPlanned() throws Exception {
        exec("insert into cms_registration(id, registration_no, owner_type, owner_id) values (101, 'R-SELL', 1, 9), (102, 'R-OPEN', 1, 10)");
        exec("insert into cms_topic(id, is_settlement) values (9, 1), (10, 0)");
        sellerTrade("R-SELL", "1900000999", "TXS");
        sellerTrade("R-OPEN", "1900000999", "TXO");
        exec("insert into wx_verified_payment_fact(id, mchid, business_out_trade_no) values (1,'1900000999','R-SELL')");
        exec("insert into coop_settlement_original_component(topic_id, payer_registration_id, settlement_id, payment_fact_id,"
                + " receiver_kind, line_type, original_amount) values (9, 101, 700, 1, 'ACCOUNTING', 'PLATFORM', 30.00)");
        assertEquals(List.of(9L), mapper.selectSettledTopicsMissingSharePlans(10));
        shareOrder("R-SELL", 9L, "PLANNED");
        assertTrue(mapper.selectSettledTopicsMissingSharePlans(10).isEmpty());
    }

    @Test
    void replanRewritesOnlyNeverSubmittedManualOrders() {
        EcomProfitShareOrder manual = shareOrder("R1", 9L, "MANUAL");
        line(manual.getId(), 700L);
        assertEquals(1, mapper.deleteShareLines(manual.getId()));
        assertEquals(1, mapper.replanShareOrder(manual.getId(), "PLANNED", 300L, "b".repeat(64), null));
        EcomProfitShareOrder read = mapper.selectShareOrderById(manual.getId());
        assertEquals("PLANNED", read.getState());
        assertEquals("b".repeat(64), read.getPlanHash());
        assertEquals(0, mapper.replanShareOrder(manual.getId(), "PLANNED", 300L, "c".repeat(64), null), "已不是人工单");
        EcomProfitShareOrder submitted = shareOrder("R2", 9L, "MANUAL");
        assertEquals(1, mapper.updateShareOrderState(submitted.getId(), "MANUAL", "MANUAL", "WXORDER", "WECHAT_SHARE_CLOSED"));
        assertEquals(0, mapper.replanShareOrder(submitted.getId(), "PLANNED", 300L, "d".repeat(64), null), "提交过微信的不能重算");
        assertEquals(2, mapper.selectShareOrdersForAdmin(null, 9L, 10).size());
        assertEquals(1, mapper.selectShareOrdersForAdmin("MANUAL", null, 10).size());
        assertTrue(mapper.selectReturnsByState(null, 10).isEmpty());
        assertTrue(mapper.selectSubMerchantsForAdmin("FINISH", 10).isEmpty());
    }
}
