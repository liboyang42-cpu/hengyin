package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-09-16 晚用户拍板 1-5(客诉期提现闸)的真库证据:
 * ① 活动结束未满 7 天 → 不结算,钱不进余额,提不出;② 满 7 天 → 入余额,可提;
 * ③ 客诉期内有未结客诉 → 只暂扣涉诉那单,其余照放,结案后补结;
 * ④ 客诉期后退款冲销扣不够 → 记欠款,下一笔结算先抵;逾期未清不能发布新活动。
 * ⑤ 平台人工倒扣余额不足 → 扣到 0 + 差额记欠款(不再扣成负数),同一套抵扣/逾期规则。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComplaintPeriodWithdrawalGateMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("complaint_period_gate_it");
    private static final long HOST = 8801L;
    private static final long PLATFORM = 8809L;

    private DataSource dataSource;
    private CmsActivityServiceImpl activityService;
    private UmsMemberMapper umsMemberMapper;
    private SettlementDebtLedger debtLedger;
    private com.chengyinhub.business.service.impl.MerchantClawbackServiceImpl clawbackService;
    private org.springframework.transaction.support.TransactionTemplate tx;

    @BeforeAll
    void createDatabaseAndService() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        umsMemberMapper = template.getMapper(UmsMemberMapper.class);
        UmsMemberBalanceDetailMapper detailMapper = template.getMapper(UmsMemberBalanceDetailMapper.class);
        debtLedger = new SettlementDebtLedger(template.getMapper(UmsMemberSettlementDebtMapper.class),
                umsMemberMapper, detailMapper);

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberById(anyLong()))
                .thenAnswer(i -> umsMemberMapper.selectUmsMemberById(i.getArgument(0)));
        when(memberService.lockMemberById(anyLong()))
                .thenAnswer(i -> umsMemberMapper.lockMemberById(i.getArgument(0)));
        when(memberService.addBalance(anyLong(), any()))
                .thenAnswer(i -> umsMemberMapper.addBalance(i.getArgument(0), i.getArgument(1)) == 1);
        IUmsMemberBalanceDetailService detailService = mock(IUmsMemberBalanceDetailService.class);
        when(detailService.insertUmsMemberBalanceDetail(any()))
                .thenAnswer(i -> detailMapper.insertUmsMemberBalanceDetail((UmsMemberBalanceDetail) i.getArgument(0)));

        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.10"));
        rule.setMerchantRate(new BigDecimal("0.90"));
        rule.setCreatorRate(BigDecimal.ZERO);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        when(ruleService.getActiveRule(eq(0L), eq("activity"))).thenReturn(rule);
        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(PLATFORM);
        CmsSettingMapper settingMapper = mock(CmsSettingMapper.class);
        when(settingMapper.selectCmsSettingById(1L)).thenReturn(setting);

        activityService = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(activityService, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(activityService, "cmsRegistrationMapper", template.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(activityService, "settlementDetailMapper", template.getMapper(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(activityService, "activitySettlementHoldMapper", template.getMapper(ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(activityService, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(activityService, "cmsSettingMapper", settingMapper);
        ReflectionTestUtils.setField(activityService, "umsMemberService", memberService);
        ReflectionTestUtils.setField(activityService, "umsMemberBalanceDetailService", detailService);
        ReflectionTestUtils.setField(activityService, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(activityService, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(activityService, "transactionManager", new DataSourceTransactionManager(dataSource));

        clawbackService = new com.chengyinhub.business.service.impl.MerchantClawbackServiceImpl();
        ReflectionTestUtils.setField(clawbackService, "applicationMapper",
                template.getMapper(com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper.class));
        ReflectionTestUtils.setField(clawbackService, "batchMapper",
                mock(com.chengyinhub.business.mapper.MerchantPayableBatchMapper.class)); // 无未来批次 ⇒ BALANCE 路径
        ReflectionTestUtils.setField(clawbackService, "umsMemberService", memberService);
        ReflectionTestUtils.setField(clawbackService, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(clawbackService, "balanceDetailMapper", detailMapper);
        ReflectionTestUtils.setField(clawbackService, "settlementDebtLedger", debtLedger);
        tx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS cms_activity", "DROP TABLE IF EXISTS cms_topic", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS ums_member", "DROP TABLE IF EXISTS ums_member_balance_detail",
                "DROP TABLE IF EXISTS settlement_detail", "DROP TABLE IF EXISTS coop_complaint",
                "DROP TABLE IF EXISTS cms_activity_settlement_hold", "DROP TABLE IF EXISTS ums_member_settlement_debt",
                "DROP TABLE IF EXISTS merchant_clawback_application");
        execute(
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(100), description VARCHAR(255), img_url VARCHAR(255), img_arr VARCHAR(255),"
                        + "category_ids VARCHAR(255), address_name VARCHAR(255), longitude VARCHAR(64), latitude VARCHAR(64), address VARCHAR(255), status INT, publish_status INT,"
                        + "cancel_time DATETIME, cancel_reason VARCHAR(255), reason VARCHAR(255), del_flag INT, start_date DATETIME, end_date DATETIME, template_id BIGINT,"
                        + "topic_id BIGINT, club_id BIGINT, check_status INT, is_settlement INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT, team_mode TINYINT NOT NULL DEFAULT 0,"
                        + " team_max_members INT NOT NULL DEFAULT 4, club_id BIGINT,"
                        + " selling_started_at DATETIME(3), sale_end_at DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE cms_registration ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_no VARCHAR(32) NOT NULL, parent_order_no VARCHAR(64), request_id VARCHAR(64), request_fingerprint CHAR(64),"
                        + "owner_type BIGINT, owner_id BIGINT, activity_title VARCHAR(200), ticket_id BIGINT, ticket_name VARCHAR(100), ticket_price DECIMAL(10,2),"
                        + "member_id BIGINT, real_name VARCHAR(768), phone VARCHAR(256), phone_hash CHAR(64), email VARCHAR(768), address VARCHAR(768), address_id BIGINT,"
                        + "participate_date VARCHAR(64), order_num BIGINT, original_amount DECIMAL(10,2), discount_amount DECIMAL(10,2), total_amount DECIMAL(10,2),"
                        + "point_used BIGINT, point_deduct_amount DECIMAL(10,2), point_deduct_rate DECIMAL(5,4), max_point_deduct DECIMAL(10,2), payable_amount DECIMAL(10,2),"
                        + "payment_status INT, payment_type INT, payment_time DATETIME(3), transaction_id VARCHAR(64), point_payment_amount DECIMAL(10,2),"
                        + "wechat_payment_amount DECIMAL(10,2), fee_platform_rate DECIMAL(10,4), registration_status INT, pay_expire_time DATETIME(3), topic_id BIGINT, purchase_kind INT,"
                        + "source_club_id BIGINT, source_frozen_at DATETIME(3), source_channel_code VARCHAR(64),"
                        + "refund_policy_code VARCHAR(64), refund_policy_version INT, refund_deadline DATETIME(3), expires_at DATETIME(3),"
                        + "create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3), verification_code VARCHAR(100),"
                        + "qrcode_url VARCHAR(255), verification_status INT, verification_time DATETIME(3), first_successful_scan_at DATETIME, verifier_id BIGINT, points_returned INT,"
                        + "UNIQUE KEY uk_registration_no(registration_no)) ENGINE=InnoDB",
                "CREATE TABLE ums_member ("
                        + "id BIGINT PRIMARY KEY, user_type INT, role VARCHAR(64), phone VARCHAR(64), password VARCHAR(255), level_id BIGINT, name VARCHAR(100), wechat VARCHAR(100),"
                        + "status INT, invite_user_id VARCHAR(64), login_ip VARCHAR(64), login_time DATETIME, nickname VARCHAR(100), avatar VARCHAR(255), sex INT,"
                        + "wx_open_id VARCHAR(128), app_open_id VARCHAR(64), union_id VARCHAR(128), apple_id VARCHAR(128), area_id BIGINT, birthday DATETIME, mark VARCHAR(255), tag_ids VARCHAR(255),"
                        + "case_pics VARCHAR(255), case_introducton VARCHAR(255), website VARCHAR(255), work_address VARCHAR(255), introduction VARCHAR(255),"
                        + "balance DECIMAL(12,2), point BIGINT NOT NULL DEFAULT 0, del_flag INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE ums_member_balance_detail ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, current_balance DECIMAL(12,2), after_balance DECIMAL(12,2),"
                        + "change_balance DECIMAL(12,2), change_reason VARCHAR(500), event_type BIGINT, event_id BIGINT, change_type BIGINT, del_flag BIGINT,"
                        + "create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE settlement_detail (id BIGINT PRIMARY KEY AUTO_INCREMENT, claim_id BIGINT NULL, entry_kind VARCHAR(24) NULL,"
                        + "event_key VARCHAR(128) NULL, batch_id BIGINT, merchant_id BIGINT, source_type VARCHAR(64),"
                        + "source_id BIGINT, receiver_type VARCHAR(64), receiver_id BIGINT, gross_amount DECIMAL(12,2), commission_amount DECIMAL(12,2),"
                        + "settlement_amount DECIMAL(12,2), status INT, fail_reason VARCHAR(500), idempotency_key VARCHAR(255),"
                        + "create_time DATETIME(3), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE coop_complaint (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT, registration_id BIGINT, reporter_member_id BIGINT,"
                        + "status INT, del_flag INT) ENGINE=InnoDB",
                "INSERT INTO ums_member(id,status,del_flag,balance) VALUES (" + HOST + ",1,0,0.00),(" + PLATFORM + ",1,0,0.00)");
        // 新表直接跑迁移文件本体:IT 与生产 DDL 同源,不在测试里另抄一份。
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_complaint_period_funds_gate_20260917.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_merchant_clawback_20260725.sql"));
        }
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    /** ① 活动结束 6 天:还在客诉期,结算任务不结,主办方余额仍是 0,¥90 提不出来。 */
    @Test
    void moneyIsNotWithdrawableBeforeTheSevenDayComplaintPeriodEnds() throws Exception {
        seedActivity(8101L, 6);
        seedRegistration(8201L, 8101L, "100.00");

        activityService.executeActivitySettlement();

        assertEquals(0, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=8101"), "客诉期内不得结算");
        assertMoney("0.00", balance(HOST));
        assertEquals(0, umsMemberMapper.deductWithdrawableBalance(HOST, new BigDecimal("90.00"), BigDecimal.ZERO),
                "客诉期内这 ¥90 不在余额里,提现原子扣减必须失败");
    }

    /** ② 满 7 天:结算入余额,可提现。 */
    @Test
    void moneyBecomesWithdrawableOnceTheComplaintPeriodEnds() throws Exception {
        seedActivity(8102L, 8);
        seedRegistration(8202L, 8102L, "100.00");

        activityService.executeActivitySettlement();

        assertEquals(1, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=8102"));
        assertMoney("90.00", balance(HOST));
        assertEquals(1, umsMemberMapper.deductWithdrawableBalance(HOST, new BigDecimal("90.00"), BigDecimal.ZERO));
    }

    /** ③ 两单 100 + 60,60 那单有未结客诉:只暂扣 60 那单,100 那单的 90 照放;结案后补结 54。 */
    @Test
    void openComplaintFreezesOnlyTheDisputedOrderAndReleasesItAfterClosing() throws Exception {
        seedActivity(8103L, 8);
        seedRegistration(8203L, 8103L, "100.00");
        seedRegistration(8204L, 8103L, "60.00");
        execute("INSERT INTO coop_complaint(id,topic_id,registration_id,reporter_member_id,status,del_flag) VALUES (9301,1,8204,7001,0,0)");

        activityService.executeActivitySettlement();

        assertMoney("90.00", balance(HOST));
        assertEquals(0, scalarInt("SELECT status FROM cms_activity_settlement_hold WHERE registration_id=8204"),
                "涉诉那单必须登记暂扣");
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM cms_activity_settlement_hold WHERE registration_id=8203"),
                "没被投诉的那单不许被连带冻结");

        activityService.executeActivitySettlement();
        assertMoney("90.00", balance(HOST));

        execute("UPDATE coop_complaint SET status=3 WHERE id=9301");
        activityService.executeActivitySettlement();

        assertMoney("144.00", balance(HOST));
        assertEquals(1, scalarInt("SELECT status FROM cms_activity_settlement_hold WHERE registration_id=8204"));
        assertMoney("16.00", balance(PLATFORM));

        activityService.executeActivitySettlement();
        assertMoney("144.00", balance(HOST));
    }

    /** ③' 涉诉那单结案时已退款:作废暂扣,不补结(商家从没拿到这单的钱)。 */
    @Test
    void refundedDisputedOrderIsVoidedNotPaid() throws Exception {
        seedActivity(8104L, 8);
        seedRegistration(8205L, 8104L, "60.00");
        execute("INSERT INTO coop_complaint(id,topic_id,registration_id,reporter_member_id,status,del_flag) VALUES (9302,1,8205,7001,0,0)");
        activityService.executeActivitySettlement();

        execute("UPDATE cms_registration SET payment_status=4, registration_status=3 WHERE id=8205",
                "UPDATE coop_complaint SET status=3 WHERE id=9302");
        activityService.executeActivitySettlement();

        assertMoney("0.00", balance(HOST));
        assertEquals(2, scalarInt("SELECT status FROM cms_activity_settlement_hold WHERE registration_id=8205"));
    }

    /** ④ 客诉期后退款冲销扣不够的 30 元记欠款:下一笔结算 90 先抵 30,余额只进 60;逾期未清不许发布新活动。 */
    @Test
    void postPeriodRefundShortfallIsDeductedFromTheNextSettlement() throws Exception {
        debtLedger.record(HOST, SettlementDebtLedger.SOURCE_REFUND, 9401L, new BigDecimal("30.00"));
        debtLedger.record(HOST, SettlementDebtLedger.SOURCE_REFUND, 9401L, new BigDecimal("30.00"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt WHERE member_id=" + HOST),
                "同一笔退款重复冲销只记一次欠款");

        execute("UPDATE ums_member_settlement_debt SET due_time=DATE_SUB(NOW(), INTERVAL 1 DAY)");
        ServiceException blocked = assertThrows(ServiceException.class, () -> debtLedger.requireNoOverdueDebt(HOST));
        assertTrue(blocked.getMessage().startsWith("SETTLEMENT_DEBT_OVERDUE"));

        seedActivity(8105L, 8);
        seedRegistration(8206L, 8105L, "100.00");
        activityService.executeActivitySettlement();

        assertMoney("60.00", balance(HOST));
        assertEquals(1, scalarInt("SELECT status FROM ums_member_settlement_debt WHERE member_id=" + HOST));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail WHERE member_id=" + HOST
                + " AND event_type=" + SettlementDebtLedger.EVENT_TYPE_DEBT_OFFSET + " AND change_balance=-30.00"));
        debtLedger.requireNoOverdueDebt(HOST);
    }

    /**
     * ⑤ 2026-09-17 总控裁定:平台人工倒扣 ¥50,商家余额只有 20 ⇒ 扣到 0(不再扣成 -30),差额 30 记欠款;
     * 逾期禁止发布;下一笔结算 ¥90 先抵 30。
     */
    @Test
    void manualClawbackShortfallBecomesDebtInsteadOfNegativeBalance() throws Exception {
        execute("UPDATE ums_member SET balance=20.00 WHERE id=" + HOST);
        Long appId = tx.execute(s -> clawbackService.initiate(HOST, "玩家投诉核实:未履约", new BigDecimal("50.00"), "ops_a").getId());
        Boolean executed = tx.execute(s -> clawbackService.confirmExecute(appId, "ops_b"));
        assertEquals(Boolean.TRUE, executed);

        assertMoney("0.00", balance(HOST));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt WHERE member_id=" + HOST
                + " AND source_type='clawback' AND source_id=" + appId + " AND amount=30.00 AND status=0"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail WHERE member_id=" + HOST
                + " AND event_type=92 AND change_balance=-20.00 AND after_balance=0.00"));

        execute("UPDATE ums_member_settlement_debt SET due_time=DATE_SUB(NOW(), INTERVAL 1 DAY)");
        assertThrows(ServiceException.class, () -> debtLedger.requireNoOverdueDebt(HOST));

        seedActivity(8106L, 8);
        seedRegistration(8207L, 8106L, "100.00");
        activityService.executeActivitySettlement();

        assertMoney("60.00", balance(HOST));
        assertEquals(1, scalarInt("SELECT status FROM ums_member_settlement_debt WHERE source_type='clawback' AND source_id=" + appId));
        debtLedger.requireNoOverdueDebt(HOST);
    }

    /**
     * ⑤' 快照陷阱:复核事务里先读过商家余额 20(RR 快照 + MyBatis 一级缓存),执行前另一连接已提交一笔入账到 120。
     * 扣款必须按加锁读到的 120 算:扣满 50、余额 70、不记欠款。重复复核不得二次扣款或二次记欠款。
     */
    @Test
    void manualClawbackUsesLockedCurrentBalanceNotTransactionSnapshot() throws Exception {
        execute("UPDATE ums_member SET balance=20.00 WHERE id=" + HOST);
        Long appId = tx.execute(s -> clawbackService.initiate(HOST, "玩家投诉核实:未履约", new BigDecimal("50.00"), "ops_a").getId());

        Boolean executed = tx.execute(s -> {
            assertMoney("20.00", umsMemberMapper.selectUmsMemberById(HOST).getBalance()); // 建快照 + 缓存
            try {
                execute("UPDATE ums_member SET balance=120.00 WHERE id=" + HOST); // 另一连接,自动提交
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return clawbackService.confirmExecute(appId, "ops_b");
        });
        assertEquals(Boolean.TRUE, executed);
        assertMoney("70.00", balance(HOST));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt WHERE member_id=" + HOST));

        Boolean again = tx.execute(s -> clawbackService.confirmExecute(appId, "ops_b"));
        assertEquals(Boolean.FALSE, again, "已执行的申请重复复核必须空转");
        assertMoney("70.00", balance(HOST));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail WHERE member_id=" + HOST + " AND event_type=92"));
    }

    private void seedActivity(long id, int endedDaysAgo) throws Exception {
        execute("INSERT INTO cms_activity(id,member_id,name,status,del_flag,is_settlement,start_date,end_date) VALUES ("
                + id + "," + HOST + ",'客诉期活动',1,0,0,DATE_SUB(NOW(),INTERVAL " + (endedDaysAgo + 1) + " DAY),"
                + "DATE_SUB(NOW(),INTERVAL " + endedDaysAgo + " DAY))");
    }

    private void seedRegistration(long id, long activityId, String amount) throws Exception {
        execute("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status) VALUES ("
                + id + ",'R" + id + "',2," + activityId + ",7001," + amount + ",2,2,1)");
    }

    private BigDecimal balance(long memberId) {
        UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
        return member.getBalance();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("complaint-period-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, CmsRegistrationMapper.class, "mapper/business/CmsRegistrationMapper.xml");
        addMapper(configuration, CmsActivityMapper.class, "mapper/business/CmsActivityMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        addMapper(configuration, UmsMemberBalanceDetailMapper.class, "mapper/business/UmsMemberBalanceDetailMapper.xml");
        addMapper(configuration, SettlementDetailMapper.class, "mapper/business/SettlementDetailMapper.xml");
        addMapper(configuration, ActivitySettlementHoldMapper.class, "mapper/business/ActivitySettlementHoldMapper.xml");
        addMapper(configuration, UmsMemberSettlementDebtMapper.class, "mapper/business/UmsMemberSettlementDebtMapper.xml");
        addMapper(configuration, com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper.class,
                "mapper/business/MerchantClawbackApplicationMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            }
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String item : sql) {
                s.execute(item);
            }
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
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
