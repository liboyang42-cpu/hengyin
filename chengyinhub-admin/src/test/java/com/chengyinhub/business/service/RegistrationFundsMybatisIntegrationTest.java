package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.CmsPointsSettingMapper;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CmsPointsSettingServiceImpl;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.service.impl.RegistrationOrderServiceImpl;
import com.chengyinhub.business.service.IRegistrationOrderCreateGate;
import com.chengyinhub.business.service.impl.RegistrationCheckoutServiceImpl;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.business.task.OrderCloseTask;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资金原子性真实数据库门禁。仅在显式打开 CHENGYIN_FUNDS_MYSQL_IT=1 时运行，避免普通单测误连数据库。
 * 版本合同固定为 Oracle MySQL 8.0.45；版本不符时必须 fail-closed。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationFundsMybatisIntegrationTest {

    private static final String TEST_PII_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("funds_core_it");

    private DataSource dataSource;
    private ICmsRegistrationService registrationService;
    private RegistrationOrderServiceImpl registrationOrderService;
    private CmsRegistrationMapper registrationMapper;
    private RefundServiceImpl refundService;
    private IPerkLedgerService perkLedgerService;
    private PlatformTransactionManager transactionManager;
    private SqlSessionTemplate template;
    private CmsRegistrationServiceImpl registrationTarget;

    @BeforeAll
    void createDatabaseAndService() throws Exception {
        new PiiCryptoService(TEST_PII_KEY, "funds-it-hmac").register();
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            // ★ 2026-08-19:先 DROP 再 CREATE,不接受"环境里恰好剩下一个同名库"。
            // 原来是 CREATE DATABASE IF NOT EXISTS ⇒ 本类默默继承上一次(可能是崩在半路的那次)留下的表,
            // 于是 @BeforeEach 建表撞 "Table 'xxx' already exists" —— #741 的资金闸就是这么连红的:
            // 本地怎么跑都绿,只有 runner 上那个库有残留。库名本来就一类一个(见 MysqlIntegrationTestConfig),
            // 这里补的是另一半:**每次开跑都从零建**,隔离才是完整的。
            // 同一条规矩在 business/it/ExploreItDatabase 已经这么写了,这里对齐。
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        template = new SqlSessionTemplate(buildFactory(dataSource));
        registrationMapper = template.getMapper(CmsRegistrationMapper.class);

        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(target, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        registrationTarget = target;
        ReflectionTestUtils.setField(target, "platformMemberId", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "growthService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", template.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "omsTicketMapper", template.getMapper(OmsTicketMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberPointsDetailMapper", template.getMapper(UmsMemberPointsDetailMapper.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", template.getMapper(CmsRegistrationScanMapper.class));
        ISettlementRuleService feeRuleService = mock(ISettlementRuleService.class);
        SettlementRule activityRule = new SettlementRule();
        activityRule.setPlatformCommissionRate(new BigDecimal("0.1234"));
        when(feeRuleService.getActiveRule(0L, "activity")).thenReturn(activityRule);
        ReflectionTestUtils.setField(target, "settlementRuleService", feeRuleService);
        ReflectionTestUtils.setField(target, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        perkLedgerService = mock(IPerkLedgerService.class);
        ReflectionTestUtils.setField(target, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));

        CmsPointsSettingServiceImpl pointsSettingService = new CmsPointsSettingServiceImpl();
        ReflectionTestUtils.setField(pointsSettingService, "cmsPointsSettingMapper", template.getMapper(CmsPointsSettingMapper.class));
        ReflectionTestUtils.setField(target, "cmsPointsSettingService", pointsSettingService);
        SettlementEntryServiceImpl settlementEntryService = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(settlementEntryService, "cmsActivityMapper",
                template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "settlementEntryService", settlementEntryService);

        transactionManager = new DataSourceTransactionManager(dataSource);
        // 2026-09-16: 回调 inbox 去重(cc36b4552)是支付/退款回调的必经一步,本套手工装配漏注入即 NPE。
        // 2026-10-22 W3:报名支付回调改走 inbox process fence,业务在 fence 事务内提交;
        // inbox 自身语义由 WxPaymentCallbackInboxServiceImplTest / WxPaymentCallbackFencingMySqlIT 覆盖,
        // 这里用直通桩:每次都在独立事务里跑业务 mutation,失败整笔回滚。
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService",
                PaymentCallbackInboxStubs.passThrough(transactionManager));
        java.util.concurrent.ScheduledExecutorService noticeExecutor =
                mock(java.util.concurrent.ScheduledExecutorService.class);
        when(noticeExecutor.schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.MILLISECONDS)))
                .thenAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return mock(java.util.concurrent.ScheduledFuture.class);
                });
        ReflectionTestUtils.setField(target, "committedBusinessNotice",
                new com.chengyinhub.business.service.support.CommittedBusinessNotice(
                        mock(INotifyService.class), mock(AlarmService.class), transactionManager, noticeExecutor));
        TransactionInterceptor interceptor = new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ICmsRegistrationService.class, IRegistrationCreationTransaction.class);
        proxyFactory.addAdvice(interceptor);
        Object registrationProxy = proxyFactory.getProxy();
        registrationService = (ICmsRegistrationService) registrationProxy;

        RegistrationOrderServiceImpl orderTarget = new RegistrationOrderServiceImpl();
        ReflectionTestUtils.setField(orderTarget, "registrationService", registrationService);
        ReflectionTestUtils.setField(orderTarget, "registrationCreationTransaction",
                (IRegistrationCreationTransaction) registrationProxy);
        ReflectionTestUtils.setField(orderTarget, "registrationOrderCreateGate",
                mock(IRegistrationOrderCreateGate.class));
        registrationOrderService = orderTarget;

        refundService = new RefundServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(refundService, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(refundService, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(refundService, "umsMemberBalanceDetailMapper",
                template.getMapper(UmsMemberBalanceDetailMapper.class));
        ReflectionTestUtils.setField(refundService, "alarmService", mock(com.chengyinhub.business.service.impl.AlarmService.class));
        ReflectionTestUtils.setField(refundService, "wxPaymentCallbackInboxService",
                mock(IWxPaymentCallbackInboxService.class));
        // 客诉期提现闸:暂扣/欠款两张表的真库行为由 ComplaintPeriodWithdrawalGateMybatisIntegrationTest 覆盖
        ReflectionTestUtils.setField(refundService, "activitySettlementHoldMapper", mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(refundService, "settlementDebtLedger", mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS cms_registration_scan", "DROP TABLE IF EXISTS cms_member_template",
                "DROP TABLE IF EXISTS cms_topic_node",
                "DROP TABLE IF EXISTS club_edition_compensation_terms",
                "DROP TABLE IF EXISTS activity_cancel_manual_refund_case",
                "DROP TABLE IF EXISTS refund_application",
                "DROP TABLE IF EXISTS oms_ticket", "DROP TABLE IF EXISTS cms_activity",
                "DROP TABLE IF EXISTS cms_topic",
                "DROP TABLE IF EXISTS cms_points_setting", "DROP TABLE IF EXISTS ums_member_points_detail",
                "DROP TABLE IF EXISTS ums_member_balance_detail",
                "DROP TABLE IF EXISTS ums_member", "DROP TABLE IF EXISTS cms_registration");
        execute(                "CREATE TABLE cms_registration ("
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
                        + "UNIQUE KEY uk_registration_no(registration_no), UNIQUE KEY uk_reg_member_request(member_id,request_id)) ENGINE=InnoDB",
                "CREATE TABLE ums_member ("
                        + "id BIGINT PRIMARY KEY, user_type INT, role VARCHAR(64), phone VARCHAR(64), password VARCHAR(255), level_id BIGINT, name VARCHAR(100), wechat VARCHAR(100),"
                        + "status INT, invite_user_id VARCHAR(64), login_ip VARCHAR(64), login_time DATETIME, nickname VARCHAR(100), avatar VARCHAR(255), sex INT,"
                        + "wx_open_id VARCHAR(128), app_open_id VARCHAR(64), union_id VARCHAR(128), apple_id VARCHAR(128), area_id BIGINT, birthday DATETIME, mark VARCHAR(255), tag_ids VARCHAR(255),"
                        + "case_pics VARCHAR(255), case_introducton VARCHAR(255), website VARCHAR(255), work_address VARCHAR(255), introduction VARCHAR(255),"
                        + "balance DECIMAL(12,2), point BIGINT NOT NULL DEFAULT 0, del_flag INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE ums_member_points_detail ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, current_points DECIMAL(12,2), after_points DECIMAL(12,2), change_points DECIMAL(12,2),"
                        + "change_reason VARCHAR(500), event_type BIGINT, event_id BIGINT, change_type BIGINT, del_flag INT, create_by VARCHAR(64), create_time DATETIME,"
                        + "update_by VARCHAR(64), update_time DATETIME, reward_business_key VARCHAR(128), points_rule_id BIGINT,"
                        + "points_rule_version BIGINT, points_rule_value DECIMAL(12,2),"
                        + "publisher_settlement_topic_id BIGINT NULL,"
                        + "UNIQUE KEY uk_member_event_once(member_id,event_type,event_id),"
                        + "UNIQUE KEY uk_member_reward_business(member_id,reward_business_key),"
                        + "UNIQUE KEY uk_publisher_settlement_topic(publisher_settlement_topic_id)) ENGINE=InnoDB",
                "CREATE TABLE ums_member_balance_detail ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, current_balance DECIMAL(12,2), after_balance DECIMAL(12,2),"
                        + "change_balance DECIMAL(12,2), change_reason VARCHAR(500), event_type BIGINT, event_id BIGINT, change_type BIGINT, del_flag BIGINT,"
                        + "create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE cms_points_setting (id BIGINT PRIMARY KEY, title VARCHAR(100), value DECIMAL(12,2), description VARCHAR(255), icon VARCHAR(255),"
                        + "type BIGINT, event_type BIGINT, rule_version BIGINT NOT NULL DEFAULT 1, status INT, sort_order INT, del_flag INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT, chapter_id BIGINT, name VARCHAR(100), description VARCHAR(255),"
                        + "address VARCHAR(255), longitude VARCHAR(64), latitude VARCHAR(64), img_url VARCHAR(255), node_time BIGINT, template_id BIGINT, checkin_code VARCHAR(64),"
                        + "business_time VARCHAR(64), status INT, sort_id INT, xp_value INT, hook_text VARCHAR(255), card_hook_long VARCHAR(255), fragment_text VARCHAR(255),"
                        + "create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE cms_member_template (id BIGINT PRIMARY KEY, member_id BIGINT, validation_method INT, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE cms_registration_scan (id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_type INT, registration_id BIGINT, member_id BIGINT,"
                        + "validation_method VARCHAR(500), question_answer VARCHAR(500), feedback_text DECIMAL(12,2), status INT, name VARCHAR(500), address TEXT,"
                        + "latitude VARCHAR(500), longitude VARCHAR(500), template_id BIGINT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                // ⚠️ 这张表的列必须是**两侧的并集**,而且全仓只能有这一条 CREATE:
                //    · product_type 来自 master #739(CmsActivityMapper 的 selectCmsActivityVo 里有
                //      `(select t.product_type from cms_topic t where t.id = cms_activity.topic_id)` 子查询,
                //      缺这一列 ⇒ 走该 mapper 的查询恒 BadSqlGrammar);
                //    · club_id/selling_started_at/sale_end_at 来自本分支的票源冻结。
                //    2026-08-19 实证:两侧各自加了一条 cms_topic 建表,git **没有冲突**地把两条都并了进来
                //    (它们在数组的不同位置),于是同一批 DDL 里建两次同名表 ⇒ 第二条报
                //    "Table 'cms_topic' already exists",60 条全 ERROR。
                //    ★ 这个红只出现在 PR 的 merge ref(refs/pull/*/merge)上,分支单独跑永远绿 ——
                //    也就是说它恰恰是这条闸在**替我们看合并结果**。别按"环境问题"处理。
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT, del_flag TINYINT(1) DEFAULT 0, product_type INT, team_mode TINYINT NOT NULL DEFAULT 0,"
                        + " team_max_members INT NOT NULL DEFAULT 4, club_id BIGINT,"
                        + " selling_started_at DATETIME(3), sale_end_at DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE club_edition_compensation_terms (topic_id BIGINT PRIMARY KEY, executing_club_id BIGINT NOT NULL) ENGINE=InnoDB",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(100), description VARCHAR(255), img_url VARCHAR(255), img_arr VARCHAR(255),"
                        + "category_ids VARCHAR(255), address_name VARCHAR(255), longitude VARCHAR(64), latitude VARCHAR(64), address VARCHAR(255), status INT, publish_status INT,"
                        + "cancel_time DATETIME, cancel_reason VARCHAR(255), reason VARCHAR(255), del_flag INT, start_date DATETIME, end_date DATETIME, template_id BIGINT,"
                        + "topic_id BIGINT, club_id BIGINT, check_status INT, is_settlement INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                // source_ticket_id 是 R6 产品迁移加的列，OmsTicketMapper 的全列 select 从 79792d942 起就读它。
                // 这条 fixture DDL 漏了它 ⇒ 该套件里所有走 selectOmsTicketById 的用例都 ERROR 1054，
                // 而这份 fixture 就是「jar 必须晚于迁移落库」那条发布顺序在测试侧的等价物。
                // 2-26 同一课重犯一次：migration_oms_ticket_sale_fields_20260918.sql 给 oms_ticket 加了
                // sale_start_time / sale_end_time / meeting_point_address / refund_supported / sync_with_theme，
                // selectOmsTicketVo 无条件读这 5 列，而这份 fixture 没同步 ⇒ 建单/重放一进来就
                // BadSqlGrammarException: Unknown column 'sale_start_time' in 'field list'（本套件 15 ERROR +
                // 2 FAILURE「期望 PaymentPreorderException 实为 BadSqlGrammar」）。列定义逐字抄迁移，
                // 顺序按迁移的 AFTER 落点；5 列全可空，存量 fixture 行不回填。
                "CREATE TABLE oms_ticket (id BIGINT PRIMARY KEY, member_id BIGINT, owner_type BIGINT, owner_id BIGINT,"
                        + " source_ticket_id BIGINT NULL, name VARCHAR(100), price DECIMAL(10,2),"
                        + "start_time DATETIME, end_time DATETIME, sale_start_time DATETIME NULL, sale_end_time DATETIME NULL,"
                        + "total_inventory BIGINT, remaining_inventory BIGINT, mode INT, sub_type VARCHAR(64), meeting_point VARCHAR(255),"
                        + "meeting_point_address VARCHAR(255) NULL, team_size INT, min_people INT, max_people INT, signup_deadline DATETIME, gather_lng VARCHAR(64), gather_lat VARCHAR(64), team_status INT,"
                        + "refund_rule VARCHAR(255), refund_supported TINYINT(1) NULL, sync_with_theme TINYINT(1) NULL, need_audit INT, del_flag INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                // RUN-01 自动核销闸只读这四列(在途退款排除),给最小列即可;全列语义由退款侧自己的用例负责。
                // 同 cms_topic 的教训:本类里 refund_application 只能有这一条 CREATE。
                "CREATE TABLE refund_application (id BIGINT PRIMARY KEY AUTO_INCREMENT, source_type VARCHAR(32),"
                        + " source_id BIGINT, status INT, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE activity_cancel_manual_refund_case (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " activity_id BIGINT, topic_id BIGINT, parent_order_no VARCHAR(64) NOT NULL, payer_member_id BIGINT NOT NULL,"
                        + " payer_registration_id BIGINT, case_type VARCHAR(32) NOT NULL DEFAULT 'MIXED_TICKETS',"
                        + " chapter_snapshot_json JSON,"
                        + " status VARCHAR(48) NOT NULL, verified_registration_ids_json JSON NOT NULL,"
                        + " unverified_registration_ids_json JSON NOT NULL,"
                        + " UNIQUE KEY uk_manual_activity(activity_id,parent_order_no),"
                        + " UNIQUE KEY uk_manual_topic(topic_id,parent_order_no),"
                        + " CHECK ((activity_id IS NOT NULL AND topic_id IS NULL) OR (activity_id IS NULL AND topic_id IS NOT NULL)),"
                        + " CHECK (status IN ('PENDING_ASSESSMENT','MANUAL_REFUND_REQUIRED','MANUAL_REFUND_RECORDED','MANUAL_REFUND_EVIDENCE_VERIFIED','NO_REFUND')),"
                        + " CHECK (JSON_TYPE(verified_registration_ids_json)='ARRAY' AND JSON_LENGTH(verified_registration_ids_json)>0),"
                        + " CHECK (JSON_TYPE(unverified_registration_ids_json)='ARRAY' AND JSON_LENGTH(unverified_registration_ids_json)>0)) ENGINE=InnoDB"
        );
        execute("INSERT INTO ums_member(id,status,point,del_flag) VALUES (7,1,100,0)",
                "INSERT INTO cms_points_setting(id,value,type,status,del_flag) VALUES (2,10,1,1,0),(3,5,1,1,0)",
                "INSERT INTO cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,end_date) VALUES (55,1,'本地活动',1,1,0,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY))",
                "INSERT INTO oms_ticket(id,owner_type,owner_id,name,price,start_time,end_time,total_inventory,remaining_inventory,del_flag) VALUES (9,2,55,'本地票',100,DATE_ADD(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 DAY),10,0,0)",
                "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,point_used,payable_amount,"
                        + "wechat_payment_amount,fee_platform_rate,payment_status,registration_status,pay_expire_time,qrcode_url,verification_status,points_returned) "
                        + "VALUES (11,'T20260724PAY',1,99,'资金并发测试',7,0,100,100,0.1000,0,1,DATE_ADD(NOW(),INTERVAL 30 MINUTE),'already-generated',0,0)");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        try {
            DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            }
        } finally {
            ReflectionTestUtils.setField(PiiCryptoService.class, "instance", null);
        }
    }

    @Test
    void mysqlServerVersionMustBeOracle8045() throws Exception {
        String version = text("SELECT VERSION()");
        assertTrue(version.startsWith("8.0.45"),
                "Funds MySQL IT 必须运行在 Oracle MySQL 8.0.45，实际为 " + version);
    }

    /**
     * RUN-01 到期自动核销:扫描口径(只活动单、end_date+7天缓冲、排除在途退款与缺锚点)
     * 与条件置位的当前读闸,都在真库上跑出来,不接受口头等价。
     */
    @Test
    void autoVerifyExpiredSessions_scansOnlyEligibleAndCasGuardsRace() throws Exception {
        execute(
                "INSERT INTO cms_activity(id,member_id,name,status,publish_status,del_flag,end_date) VALUES"
                        + " (8801,1,'已结束10天',1,1,0,DATE_SUB(NOW(),INTERVAL 10 DAY)),"
                        + " (8802,1,'已结束3天',1,1,0,DATE_SUB(NOW(),INTERVAL 3 DAY)),"
                        + " (8803,1,'缺锚点',1,1,0,NULL)",
                "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,payable_amount,"
                        + "payment_status,registration_status,verification_status,qrcode_url) VALUES"
                        + " (8811,'AUTOV8811',2,8801,7,100,2,2,NULL,'q'),"
                        + " (8812,'AUTOV8812',2,8801,7,100,2,2,0,'q'),"
                        + " (8813,'AUTOV8813',2,8802,7,100,2,2,0,'q'),"
                        + " (8814,'AUTOV8814',2,8801,7,100,2,2,1,'q'),"
                        + " (8815,'AUTOV8815',2,8801,7,100,2,2,0,'q'),"
                        + " (8816,'AUTOV8816',2,8803,7,100,2,2,0,'q'),"
                        + " (8817,'AUTOV8817',2,8801,7,100,4,2,0,'q'),"
                        + " (8818,'AUTOV8818',1,8801,7,100,2,2,0,'q'),"
                        + " (8819,'AUTOV8819',2,8801,7,100,2,2,0,'q')",
                // 8815 有在途退款(0=待审核)必须被挡;8819 只有已删除单据(del_flag=2)不受影响;
                // source_type='topic' 那行证明闸按来源过滤、不拿别的业务的退款乱挡。
                "INSERT INTO refund_application(source_type,source_id,status,del_flag) VALUES"
                        + " ('registration',8815,0,0),('registration',8819,1,2),('topic',8815,0,0)");

        assertEquals(java.util.Arrays.asList(8811L, 8812L, 8819L),
                registrationService.listExpiredUnverifiedIds(),
                "缓冲内/已核销/在途退款/缺锚点/已回单/主题单都不得入扫");
        assertEquals(java.util.Arrays.asList(8811L, 8812L, 8819L),
                registrationService.listExpiredUnverifiedIdsAfter(0L),
                "游标从零扫描必须与旧接口的到期判据一致");
        assertEquals(java.util.Arrays.asList(8812L, 8819L),
                registrationService.listExpiredUnverifiedIdsAfter(8811L),
                "游标必须排除已扫描的 ID 并保持升序");
        assertEquals(java.util.Collections.emptyList(),
                registrationService.listExpiredUnverifiedIdsAfter(8819L),
                "超过最后一笔候选必须返回空页");

        execute(
                "INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,member_id,"
                        + "payment_status,registration_status,verification_status,qrcode_url) VALUES"
                        + " (8820,'AUTOV8820','AUTO-MANUAL',2,8801,7,2,2,0,'q')",
                "INSERT INTO activity_cancel_manual_refund_case(activity_id,topic_id,parent_order_no,"
                        + "payer_member_id,status,verified_registration_ids_json,unverified_registration_ids_json)"
                        + " VALUES (8801,NULL,'AUTO-MANUAL',7,'PENDING_ASSESSMENT',JSON_ARRAY(8811),JSON_ARRAY(8820))");
        assertFalse(registrationService.autoVerifyExpiredOne(8820L),
                "到期自动核销也必须尊重人工退款冻结");
        assertEquals(0, number("SELECT verification_status FROM cms_registration WHERE id=8820"));

        assertTrue(registrationService.autoVerifyExpiredOne(8811L));
        assertEquals(1, number("SELECT verification_status FROM cms_registration WHERE id=8811"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_registration WHERE id=8811 AND verifier_id IS NOT NULL"),
                "自动收口不许伪造核销人");
        assertEquals("registration-auto-verify-task",
                text("SELECT update_by FROM cms_registration WHERE id=8811"));
        assertFalse(registrationService.autoVerifyExpiredOne(8811L), "重复收口必须 0 行");

        // 竞态闸:扫描与置位之间用户提交退款申请 → 条件 UPDATE 当前读必须拦住
        execute("INSERT INTO refund_application(source_type,source_id,status,del_flag) VALUES ('registration',8812,0,0)");
        assertFalse(registrationService.autoVerifyExpiredOne(8812L));
        assertEquals(0, number("SELECT verification_status FROM cms_registration WHERE id=8812"));
        assertFalse(registrationService.autoVerifyExpiredOne(8813L), "未过安全缓冲不得收口");
        assertFalse(registrationService.autoVerifyExpiredOne(8816L), "缺锚点 fail-closed");
    }

    @Test
    void pendingManualRefundCaseFreezesVerificationUntilNoRefundDecision() throws Exception {
        execute(
                "INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,member_id,"
                        + "payment_status,registration_status,verification_status) VALUES"
                        + " (8891,'MANUAL8891','MANUAL-PARENT',2,55,7,2,2,0),"
                        + " (8892,'MANUAL8892','MANUAL-PARENT',2,55,7,2,2,0)",
                "INSERT INTO activity_cancel_manual_refund_case(activity_id,topic_id,parent_order_no,"
                        + "payer_member_id,status,verified_registration_ids_json,unverified_registration_ids_json)"
                        + " VALUES (55,NULL,'MANUAL-PARENT',7,'PENDING_ASSESSMENT',JSON_ARRAY(8890),JSON_ARRAY(8891))");

        CmsRegistrationMapper mapper = template.getMapper(CmsRegistrationMapper.class);
        assertTrue(mapper.hasActiveManualRefundCaseForUpdate(8891L));
        assertFalse(mapper.hasActiveManualRefundCaseForUpdate(8892L),
                "同父单但不在工单票集中的报名不得被扩大冻结");
        assertEquals(0, mapper.verifyIfNotVerified(8891L, 9002L),
                "人工退款待评估期间，剩余票不得继续核销");

        execute("UPDATE activity_cancel_manual_refund_case SET status='NO_REFUND'"
                + " WHERE parent_order_no='MANUAL-PARENT'");
        assertFalse(mapper.hasActiveManualRefundCaseForUpdate(8891L));
        assertEquals(1, mapper.verifyIfNotVerified(8891L, 9002L),
                "明确不退款后，合法未核销票应恢复核销");

        execute("UPDATE cms_registration SET registration_status=3 WHERE id=8892");
        assertEquals(0, mapper.verifyIfNotVerified(8892L, 9002L),
                "NO_REFUND 只解除工单冻结，已取消报名仍不得核销");
    }

    @Test
    void manualRefundCreationAndRedemptionGateSerializeOnRegistrationRow() throws Exception {
        execute("INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,member_id,"
                + "payment_status,registration_status,verification_status) VALUES"
                + " (8893,'MANUAL8893','MANUAL-RACE',2,55,7,2,2,0)");

        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch caseInserted = new CountDownLatch(1);
        CountDownLatch gateStarted = new CountDownLatch(1);
        CountDownLatch verificationStarted = new CountDownLatch(1);
        CountDownLatch allowCaseCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Future<Boolean> gate = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM activity_cancel_manual_refund_case", Integer.class);
                snapshotReady.countDown();
                await(caseInserted);
                gateStarted.countDown();
                return registrationMapper.hasActiveManualRefundCaseForUpdate(8893L);
            }));
            Future<Integer> blockedVerification = pool.submit(() -> {
                await(caseInserted);
                verificationStarted.countDown();
                return registrationMapper.verifyIfNotVerified(8893L, 9002L);
            });
            Future<Boolean> caseCreation = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                await(snapshotReady);
                registrationMapper.selectCmsRegistrationByIdForUpdate(8893L);
                jdbc.update("INSERT INTO activity_cancel_manual_refund_case(activity_id,topic_id,parent_order_no,"
                                + "payer_member_id,status,verified_registration_ids_json,unverified_registration_ids_json)"
                                + " VALUES (55,NULL,'MANUAL-RACE',7,'PENDING_ASSESSMENT',JSON_ARRAY(8890),JSON_ARRAY(8893))");
                caseInserted.countDown();
                await(gateStarted);
                await(verificationStarted);
                await(allowCaseCommit);
                return true;
            }));

            await(gateStarted);
            await(verificationStarted);
            Thread.sleep(100);
            assertFalse(gate.isDone(), "工单事务持有 registration 行锁时，核销闸必须实际阻塞");
            assertFalse(blockedVerification.isDone(), "工单事务持有 registration 行锁时，整单核销 CAS 必须实际阻塞");
            allowCaseCommit.countDown();
            assertTrue(caseCreation.get());
            assertTrue(gate.get(), "锁等待后必须看见新提交的退款工单，不得读 RR 旧快照");
            assertEquals(0, blockedVerification.get(), "最终原子 UPDATE 也必须在锁等待后被新工单拦下");
            assertEquals(0, number("SELECT verification_status FROM cms_registration WHERE id=8893"));

            execute("DELETE FROM activity_cancel_manual_refund_case WHERE parent_order_no='MANUAL-RACE'");
            CountDownLatch verificationUpdated = new CountDownLatch(1);
            CountDownLatch caseLockStarted = new CountDownLatch(1);
            CountDownLatch allowVerificationCommit = new CountDownLatch(1);
            Future<Integer> verification = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                int updated = registrationMapper.verifyIfNotVerified(8893L, 9002L);
                verificationUpdated.countDown();
                await(caseLockStarted);
                await(allowVerificationCommit);
                return updated;
            }));
            Future<Integer> caseSnapshot = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                await(verificationUpdated);
                caseLockStarted.countDown();
                return registrationMapper.selectByParentOrderNoForUpdate("MANUAL-RACE", 55L)
                        .get(0).getVerificationStatus();
            }));

            await(caseLockStarted);
            Thread.sleep(100);
            assertFalse(caseSnapshot.isDone(), "核销事务持有 registration 行锁时，建工单快照必须实际阻塞");
            allowVerificationCommit.countDown();
            assertEquals(1, verification.get());
            assertEquals(1, caseSnapshot.get(),
                    "核销先获锁时，建工单方等待后必须把该票归入已核销快照");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSuccessCallbacksAwardAndTransitionOnlyOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> callbackAfter(ready, start));
            Future<String> b = pool.submit(() -> callbackAfter(ready, start));
            ready.await();
            start.countDown();

            assertEquals("SUCCESS", a.get());
            assertEquals("SUCCESS", b.get());
            assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
            assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
            assertEquals(115, number("SELECT point FROM ums_member WHERE id=7"));
            assertEquals(2, number("SELECT COUNT(*) FROM ums_member_points_detail"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRefundClawbacksSerializeBalanceAndFlowsWithoutGoingNegative() throws Exception {
        execute("UPDATE ums_member SET balance=50.00 WHERE id=7");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> debitAfter(ready, start, 901L));
            Future<Boolean> second = pool.submit(() -> debitAfter(ready, start, 902L));
            ready.await();
            start.countDown();

            assertTrue(first.get());
            assertTrue(second.get());
            assertEquals(0, number("SELECT balance FROM ums_member WHERE id=7"));
            assertEquals(2, number("SELECT COUNT(*) FROM ums_member_balance_detail WHERE member_id=7 AND event_type=6"));
            assertEquals(-50, number("SELECT SUM(change_balance) FROM ums_member_balance_detail WHERE member_id=7 AND event_type=6"));
            assertEquals(0, number("SELECT COUNT(*) FROM ums_member_balance_detail WHERE after_balance < 0"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cashSuccessRejectsAnyCentMismatchAgainstFrozenWechatAmount() throws Exception {
        Map<String, String> underpaid = callback();
        underpaid.put("total", "9999");

        assertEquals("FAIL", registrationService.handleWechatPayCallback(underpaid));

        assertEquals(0, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
    }

    @Test
    void shopDayPaymentFreezesValidClubSourceAtSuccess() throws Exception {
        prepareShopDayTicketSource(7L, 7L, 7L, "CLUB-7", "2030-01-01 00:00:00");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(7, number("SELECT source_club_id FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11 AND source_frozen_at IS NOT NULL"));
        assertEquals("CLUB-7", text("SELECT source_channel_code FROM cms_registration WHERE id=11"));
    }

    @Test
    void platformShopDayPaymentKeepsSourceFromFrozenExecutingClub() throws Exception {
        prepareShopDayTicketSource(7L, null, null, "CLUB-7", "2030-01-01 00:00:00");
        execute("INSERT INTO club_edition_compensation_terms(topic_id,executing_club_id) VALUES(23,7)");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(7, number("SELECT source_club_id FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11 AND source_frozen_at IS NOT NULL"));
    }

    @Test
    void duplicateShopDayPaymentKeepsOriginalFrozenSource() throws Exception {
        prepareShopDayTicketSource(7L, 7L, 7L, "CLUB-7", "2030-01-01 00:00:00");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        String firstFrozenAt = text("SELECT CAST(source_frozen_at AS CHAR) FROM cms_registration WHERE id=11");
        assertNotNull(firstFrozenAt);
        execute("UPDATE cms_activity SET club_id=8 WHERE id=55",
                "UPDATE cms_topic SET club_id=8 WHERE id=23");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(7, number("SELECT source_club_id FROM cms_registration WHERE id=11"));
        assertEquals(firstFrozenAt,
                text("SELECT CAST(source_frozen_at AS CHAR) FROM cms_registration WHERE id=11"));
    }

    @Test
    void shopDayPaymentWithoutClubMarkerFreezesPlatformAttribution() throws Exception {
        prepareShopDayTicketSource(null, 7L, 7L, "PLATFORM", "2030-01-01 00:00:00");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11"
                + " AND source_club_id IS NULL AND source_frozen_at IS NOT NULL"));
    }

    @Test
    void expiredShopDayClubMarkerFreezesAsPlatform() throws Exception {
        prepareShopDayTicketSource(7L, 7L, 7L, "CLUB-7", "2020-01-01 00:00:00");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11"
                + " AND source_club_id IS NULL AND source_frozen_at IS NOT NULL"));
    }

    @Test
    void ambiguousShopDayClubMarkerNeverFabricatesClubAttribution() throws Exception {
        prepareShopDayTicketSource(7L, 7L, 8L, "CLUB-7", "2030-01-01 00:00:00");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11"
                + " AND source_club_id IS NULL AND source_frozen_at IS NOT NULL"));
    }

    @Test
    void missingTicketFactNeverFabricatesClubAttribution() throws Exception {
        prepareShopDayTicketSource(7L, 7L, 7L, "CLUB-7", "2030-01-01 00:00:00");
        execute("UPDATE cms_registration SET ticket_id=999999 WHERE id=11");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11"
                + " AND source_club_id IS NULL AND source_frozen_at IS NOT NULL"));
    }

    @Test
    void zeroCashCompletionUsesDedicatedEntryAndExternalSuccessCannotForgeIt() throws Exception {
        execute("UPDATE cms_registration SET payable_amount=0,wechat_payment_amount=0,payment_type=0 WHERE id=11");
        Map<String, String> forgedCallback = callback();
        forgedCallback.put("total", "0");

        assertEquals("FAIL", registrationService.handleWechatPayCallback(forgedCallback));
        assertEquals("SUCCESS", registrationService.completeZeroAmountPayment("T20260724PAY"));

        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void duplicateSuccessRepairsHistoricalPaidOrderMissingRewards() throws Exception {
        execute("UPDATE cms_registration SET payment_status=2,registration_status=2,"
                        + "transaction_id='WX-TX-11',payment_time=NOW() WHERE id=11");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(115, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=2 AND event_id=0"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=3 AND event_id=11"));
    }

    @Test
    void independentActivityOrderFreezesPlatformFeeAtCreation() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");

        RegistrationOrderResult created = createOrReplay(request("fee-snapshot"));

        assertEquals("0.1234", text("SELECT CAST(fee_platform_rate AS CHAR) FROM cms_registration WHERE id="
                + created.getPrimary().getId()));
    }

    /**
     * R9-P1：公开建单接口收到的 sourceClubId 是客户端自报，绝不能落进资金归因列；审计码仍要留下。
     * 这是攻击链的第一跳（外部请求 → create），第二跳（支付冻结）由
     * {@link #forgedClubMarkerFromPublicCreateCannotSurviveIntoPaymentFreeze()} 接着钉。
     */
    @Test
    void publicOrderCreationRefusesClientClaimedTicketSourceClubAndKeepsOnlyTheAuditCode() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        RegistrationRequest request = request("source-marker-create");
        request.setSourceClubId(7L);
        request.setSourceChannelCode("CLUB-7");

        RegistrationOrderResult created = createOrReplay(request);

        long registrationId = created.getPrimary().getId();
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=" + registrationId
                + " AND source_club_id IS NULL"));
        assertEquals("CLUB-7", text("SELECT source_channel_code FROM cms_registration WHERE id=" + registrationId));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_registration WHERE id=" + registrationId
                + " AND source_frozen_at IS NOT NULL"));
    }

    /**
     * R9-P1 跨两跳负控：买家把本期公开可得的执行俱乐部 id 填进 body，经真实 create 落库，
     * 再让真实冻结 SQL 跑在这一行上 —— 三侧俱乐部全部等于 7、售票窗全开，冻结仍必须归平台。
     * 旧码在这里会冻出 source_club_id=7：冻结闸比的那个相等条件，正是被伪造的那个值。
     */
    @Test
    void forgedClubMarkerFromPublicCreateCannotSurviveIntoPaymentFreeze() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100,signup_deadline='2030-01-01 00:00:00' WHERE id=9");
        RegistrationRequest request = request("forged-source-marker");
        request.setSourceClubId(7L);
        request.setSourceChannelCode("CLUB-7");
        long registrationId = createOrReplay(request).getPrimary().getId();
        // 期事实在【建单之后】才补齐：归因判定发生在冻结那一刻，先建单再补事实不削弱本用例，
        // 反而把两跳拆得更干净 —— 第一跳证明 create 没写归因列，第二跳证明冻结也造不出来。
        // (本套件的 service fixture 没有接 cmsTopicMapper，建单期间 activity.topic_id 必须保持为空。)
        execute("INSERT INTO cms_topic(id,club_id,selling_started_at,sale_end_at) VALUES"
                        + " (23,7,'2020-01-01 00:00:00','2030-01-01 00:00:00')",
                "UPDATE cms_activity SET topic_id=23,club_id=7 WHERE id=55",
                "INSERT INTO club_edition_compensation_terms(topic_id,executing_club_id) VALUES(23,7)",
                "UPDATE cms_registration SET purchase_kind=3,payment_status=2,registration_status=2"
                        + " WHERE id=" + registrationId);

        registrationMapper.freezeTicketSourceOnPayment(registrationId, new Date());

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=" + registrationId
                        + " AND source_club_id IS NULL AND source_frozen_at IS NOT NULL"),
                "自报俱乐部即使与执行俱乐部完全相等，也必须冻结成平台归因");
        assertEquals("CLUB-7", text("SELECT source_channel_code FROM cms_registration WHERE id="
                + registrationId), "审计码要留下：伪造尝试必须仍可追溯");
    }

    @Test
    void paidOrderReplayCannotChangeOriginalTicketSourceMarker() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        RegistrationRequest original = request("source-marker-replay");
        original.setSourceClubId(7L);
        original.setSourceChannelCode("CLUB-7");
        RegistrationOrderResult created = createOrReplay(original);
        execute("UPDATE cms_registration SET payment_status=2,registration_status=2 WHERE id="
                + created.getPrimary().getId());
        RegistrationRequest changed = request("source-marker-replay");
        changed.setSourceClubId(8L);
        changed.setSourceChannelCode("CLUB-8");

        assertThrows(RegistrationRequestConflictException.class, () -> createOrReplay(changed));
        // 自报值本来就不落库（R9-P1 信任边界），重放冲突由 requestFingerprint 判定，不靠这一列。
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE source_club_id IS NULL AND id="
                + created.getPrimary().getId()));
        assertEquals("CLUB-7", text("SELECT source_channel_code FROM cms_registration WHERE id="
                + created.getPrimary().getId()));
    }

    @Test
    void cashCallbackRepairsOnlyMatchingHistoricalPaidHalfState() throws Exception {
        execute("UPDATE cms_registration SET payment_status=2,registration_status=1,transaction_id='WX-TX-11' WHERE id=11");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));

        execute("UPDATE cms_registration SET registration_status=1,transaction_id='OTHER-TX' WHERE id=11");
        assertEquals("FAIL", registrationService.handleWechatPayCallback(callback()));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void paymentCreatesScansOnlyFromActivityAuthoritativeTopic() throws Exception {
        execute("UPDATE cms_activity SET topic_id=3,member_id=NULL WHERE id=55",
                "UPDATE cms_registration SET owner_type=2,owner_id=55,purchase_kind=NULL WHERE id=11",
                "INSERT INTO cms_member_template(id,member_id,validation_method,del_flag) VALUES (30,300,1,0),(550,5500,1,0)",
                "INSERT INTO cms_topic_node(topic_id,name,template_id) VALUES (3,'真实主题节点',30),(55,'错误 ownerId 主题节点',550)");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration_scan WHERE registration_id=11"));
        assertEquals(30, number("SELECT template_id FROM cms_registration_scan WHERE registration_id=11"));
    }

    @Test
    void duplicateSuccessRepairsHistoricalUnpaidSibling() throws Exception {
        execute("UPDATE cms_registration SET parent_order_no='P-HALF',payment_status=2,registration_status=2,"
                        + "transaction_id='WX-TX-11',payment_time=NOW() WHERE id=11",
                "INSERT INTO cms_registration(registration_no,parent_order_no,owner_type,owner_id,activity_title,"
                        + "member_id,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                        + "pay_expire_time,qrcode_url,verification_status,points_returned) VALUES "
                        + "('T20260724SIB','P-HALF',1,99,'资金并发测试',7,0,0,0,1,"
                        + "DATE_ADD(NOW(),INTERVAL 30 MINUTE),'already-generated',0,0)");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(2, number("SELECT payment_status FROM cms_registration "
                + "WHERE registration_no='T20260724SIB'"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration "
                + "WHERE registration_no='T20260724SIB'"));
    }

    @Test
    void downstreamDatabaseFailureRollsBackClaimAndRewards() throws Exception {
        execute("ALTER TABLE ums_member_points_detail ADD CONSTRAINT ck_reject_participation CHECK (change_reason <> '参与活动')");
        Map<String, String> callback = callback();

        // fence 事务内的下游失败整笔回滚、inbox 释放,回调回 FAIL 等微信重投(不再把异常抛给验签入口)。
        assertEquals("FAIL", registrationService.handleWechatPayCallback(callback));
        assertEquals(0, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_points_detail"));
    }

    @Test
    void closedCallbackCannotOverwriteCompletedPaymentOrReturnItsPoints() throws Exception {
        execute("UPDATE cms_registration SET point_used=20,points_returned=0 WHERE id=11",
                "UPDATE ums_member SET point=80 WHERE id=7");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals("FAIL", registrationService.handleWechatPayCallback(callback("CLOSED")));

        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(95, number("SELECT point FROM ums_member WHERE id=7"));
    }

    @Test
    void concurrentRefundCallbacksReturnDeductedPointsExactlyOnce() throws Exception {
        execute("UPDATE cms_registration SET payment_status=2,registration_status=2,point_used=20,points_returned=0 WHERE id=11",
                "UPDATE ums_member SET point=80 WHERE id=7");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> callbackAfter(ready, start, "REFUND"));
            Future<String> b = pool.submit(() -> callbackAfter(ready, start, "REFUND"));
            ready.await();
            start.countDown();
            assertEquals("SUCCESS", a.get());
            assertEquals("SUCCESS", b.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(1, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(4, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT registration_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void parentRefundRejectsAmbiguousOrMissingCashPayerWithoutChangingRows() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,parent_order_no='P-CASH-UNKNOWN',"
                        + "ticket_id=9,payment_status=2,registration_status=2 WHERE id=11",
                "UPDATE cms_activity SET start_date=DATE_ADD(NOW(),INTERVAL 3 DAY) WHERE id=55",
                "INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,member_id,"
                        + "payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status,point_used,points_returned)"
                        + " VALUES(12,'SECOND-CASH','P-CASH-UNKNOWN',2,55,7,100,100,2,2,0,0,0)");
        IRefundService refunds = mock(IRefundService.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        var group = activityCancellation(wechat, refunds);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt == 1) {
                execute("UPDATE cms_registration SET payable_amount=NULL WHERE id=11",
                        "UPDATE cms_registration SET payable_amount=0 WHERE id=12");
            }
            assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> new TransactionTemplate(transactionManager).execute(status -> group.cancelPaidRegistration(11L, 7L)));
            assertEquals(2, number("SELECT COUNT(*) FROM cms_registration WHERE payment_status=2 AND registration_status=2"));
            assertEquals(0, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
            assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
            org.mockito.Mockito.verifyNoInteractions(wechat, refunds);
        }
    }

    @Test
    void unpaidPlayerCancelRejectsOverflowingPointSnapshotBeforeClaimOrInventoryReturn() throws Exception {
        execute("UPDATE cms_registration SET ticket_id=9,point_used=2147483648,points_returned=0 WHERE id=11");
        assertThrows(IllegalStateException.class, () -> registrationService.cancelRegistration(11L, 7L, "test actor"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    @Test
    void expiredCloseRejectsOverflowingPointReturnAndRollsBackOrderAndMarker() throws Exception {
        execute("UPDATE cms_registration SET ticket_id=NULL,pay_expire_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE),"
                        + "point_used=2147483648,points_returned=0 WHERE id=11");
        assertThrows(IllegalStateException.class, () -> registrationService.closeExpiredOne(11L));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void playerRefundWithOverflowingPointsRollsBackClaimInventoryAndBalance() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,payment_status=2,"
                        + "registration_status=2,wechat_payment_amount=0,payable_amount=0,"
                        + "point_used=2147483648,points_returned=0 WHERE id=11",
                "UPDATE cms_activity SET start_date=DATE_ADD(NOW(),INTERVAL 3 DAY) WHERE id=55");
        var group = activityCancellation(mock(IWechatPayService.class), mock(IRefundService.class));
        ReflectionTestUtils.setField(group, "firstReviewPointsClawback",
                mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactionManager).execute(status -> group.cancelPaidRegistration(11L, 7L)));
        assertTrue(failure.getMessage().contains("积分快照"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    @Test
    void playerRefundWithUnknownCashDoesNotCancelOrRestoreInventory() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,payment_status=2,"
                        + "registration_status=2,wechat_payment_amount=NULL,point_used=20,points_returned=0 WHERE id=11",
                "UPDATE cms_activity SET start_date=DATE_ADD(NOW(),INTERVAL 3 DAY) WHERE id=55");
        IRefundService refunds = mock(IRefundService.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        var group = activityCancellation(wechat, refunds);
        com.chengyinhub.common.exception.ServiceException failure = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> new TransactionTemplate(transactionManager).execute(status -> group.cancelPaidRegistration(11L, 7L)));
        assertEquals(409, failure.getCode());
        assertTrue(failure.getMessage().contains("现金快照"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        org.mockito.Mockito.verifyNoInteractions(wechat, refunds);
    }

    @Test
    void expiredCloseRollsBackWhenPointReturnUpdatesNoMember() throws Exception {
        execute("UPDATE cms_registration SET ticket_id=NULL,pay_expire_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE),"
                        + "point_used=20,points_returned=0 WHERE id=11",
                "DELETE FROM ums_member WHERE id=7");

        assertThrows(IllegalStateException.class, () -> registrationService.closeExpiredOne(11L));

        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
    }

    @Test
    void successAndFailureCallbacksNeverOverwriteOneAnotherWithStaleRows() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> success = pool.submit(() -> callbackAfter(ready, start, "SUCCESS"));
            Future<String> closed = pool.submit(() -> callbackAfter(ready, start, "CLOSED"));
            ready.await();
            start.countDown();
            success.get();
            closed.get();

            int paymentStatus = number("SELECT payment_status FROM cms_registration WHERE id=11");
            int registrationStatus = number("SELECT registration_status FROM cms_registration WHERE id=11");
            assertTrue((paymentStatus == 2 && registrationStatus == 2)
                    || (paymentStatus == 3 && registrationStatus == 1));
            assertFalse(paymentStatus == 3 && registrationStatus == 2,
                    "失败回调不得覆盖成功报名的支付状态");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retryPaymentKeepsBusinessNumberAndRejectsExpiredOrder() throws Exception {
        // §5-9:1 秒余量在「UTC 库 + PDT JVM」的时区错位下会变成未来时刻 ⇒ 假红。
        // ⚠️ 分钟级余量不够:实测 5 MINUTE 在该组合下仍假红(库 wall clock 比 JVM 快 7 小时)——
        // 过期判据只关心「已经过去」,用日级余量才盖得住时区漂移(与相邻 expiredOrderRejection... 的 3 DAY 同口径)。
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,pay_expire_time=DATE_SUB(NOW(),INTERVAL 3 DAY) WHERE id=11");
        assertThrows(RuntimeException.class, () -> registrationService.preparePaymentRetry(11L, 7L));
        assertEquals("T20260724PAY", text("SELECT registration_no FROM cms_registration WHERE id=11"));
        // 这个测试故意把 id=11 的 pay_expire_time 改成过去时间来触发拒绝逻辑;必须改回未来时间,
        // 否则本类是 @TestInstance(PER_CLASS) 且未固定方法执行顺序,后面复用 id=11 的测试
        // (如 validRetryReturnsSameBusinessNumberEvenWhenReservedInventoryIsZero 等)会视执行
        // 顺序偶发撞上"订单已过期",与它们本身要测的逻辑无关。
        execute("UPDATE cms_registration SET pay_expire_time=DATE_ADD(NOW(),INTERVAL 30 MINUTE) WHERE id=11");
    }

    @Test
    void expiredOrderRejectionClosesTheOrderAndReleasesHeldResources() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,point_used=20,points_returned=0,"
                        + "pay_expire_time=DATE_SUB(NOW(),INTERVAL 3 DAY) WHERE id=11",
                "UPDATE ums_member SET point=80 WHERE id=7",
                "UPDATE oms_ticket SET remaining_inventory=5 WHERE id=9");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);

        RuntimeException rejected = assertThrows(RuntimeException.class,
                () -> checkoutService(wechat).retryPayment(7L, 11L));

        assertEquals("订单已过期,请重新报名", rejected.getMessage());
        // 过期即拒必须连关单一起做:只拒不管,库存/积分/权益要等定时任务(或永远)才释放。
        assertEquals(4, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(6, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        verify(wechat, never()).createOrder(org.mockito.ArgumentMatchers.any(CmsRegistration.class));
    }

    @Test
    void unexpiredOrderKeepsPayingWindowAndIsNotClosed() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,"
                        + "pay_expire_time=DATE_ADD(NOW(),INTERVAL 3 DAY) WHERE id=11",
                "UPDATE oms_ticket SET remaining_inventory=5 WHERE id=9");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("package", "prepay_id=cr527-live");
        when(wechat.createOrder(org.mockito.ArgumentMatchers.any(CmsRegistration.class))).thenReturn(payParams);

        RegistrationCheckoutResult result = checkoutService(wechat).retryPayment(7L, 11L);

        assertEquals(payParams, result.getData().get("payParams"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    /**
     * §5-3 查单结果①:微信说已支付 ⇒ 按支付回调同一条链路补记,绝不能关单回补。
     */
    @Test
    void expiredPayingOrderQuerySuccessIsAppliedAsPaymentCallback() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("SUCCESS"));
        AlarmService alarm = mock(AlarmService.class);

        orderCloseTask(wechat, alarm).reconcileExpiredPaying();

        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals("WX-TX-11", text("SELECT transaction_id FROM cms_registration WHERE id=11"));
        // 钱到了就不能回补:库存继续被占用、预扣积分不退。
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(alarm, never()).alarm(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * §5-3 查单结果②:微信说未支付 ⇒ 先关微信订单把状态钉成终态,再本地关单 + 回补库存/积分/权益。
     */
    @Test
    void expiredPayingOrderQueryNotPayClosesOrderAndReleasesHeldResources() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);
        AlarmService alarm = mock(AlarmService.class);
        clearInvocations(perkLedgerService);

        orderCloseTask(wechat, alarm).reconcileExpiredPaying();

        assertEquals(4, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(6, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        verify(wechat).closePaymentAttempt("T20260724PAY", "T20260724PAY");
        verify(perkLedgerService).releaseOnRefund(11L, "ORGANIZER_CANCEL");
        verify(alarm, never()).alarm(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 清单 6 不确定清单 #1:reconcileCursor 只活在进程内,应用重启会归零。
     * 幂等证明:重启后从头扫(新 OrderCloseTask 实例、游标重新是 0),已关的单不会被二次关单/二次回补——
     * 靠的不是游标记忆,而是 selectExpiredPayingIds/closeExpiredPaying 的 WHERE 子句本身就把
     * payment_status/registration_status 已变化的单排除在外,游标只管扫描顺序,不参与「关没关过」的判定。
     */
    @Test
    void restartResetsCursorButNeverDoubleClosesOrDoubleReleases() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);
        AlarmService alarm = mock(AlarmService.class);
        clearInvocations(perkLedgerService);

        // 第一个 OrderCloseTask 实例(游标从 0 开始)把单关掉、回补一次。
        orderCloseTask(wechat, alarm).reconcileExpiredPaying();
        assertEquals(4, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(6, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));

        // 模拟应用重启:全新实例,reconcileCursor 重新是 0,从头扫一遍。
        orderCloseTask(wechat, alarm).reconcileExpiredPaying();

        // 已关的单不再落入 payment_status=1 的扫描范围,重启重扫不会二次关单/二次回补/二次告警。
        assertEquals(6, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        verify(wechat, org.mockito.Mockito.times(1)).closePaymentAttempt("T20260724PAY", "T20260724PAY");
        verify(perkLedgerService, org.mockito.Mockito.times(1)).releaseOnRefund(11L, "ORGANIZER_CANCEL");
        verify(alarm, never()).alarm(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * §5-3 查单结果③:查单失败 ⇒ 保持不动并告警,不猜结果、不关单、不回补。
     */
    @Test
    void expiredPayingOrderQueryFailureLeavesOrderUntouchedAndAlarms() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenThrow(new RuntimeException("微信查单超时"));
        AlarmService alarm = mock(AlarmService.class);
        clearInvocations(perkLedgerService);

        orderCloseTask(wechat, alarm).reconcileExpiredPaying();

        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(perkLedgerService, never()).releaseOnRefund(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        verify(alarm).alarm(org.mockito.ArgumentMatchers.eq("支付中过期订单对账失败"),
                org.mockito.ArgumentMatchers.contains("registrationId=11"));
    }

    /**
     * P1-PT-7:多票父单主单 PAYING、从单仍 0 态且都过期。老关单任务先跑也不得把从单关掉,
     * 否则主单查到 SUCCESS 时从单 claim 失败 ⇒ 整单补记回滚,钱收了既不补记也不退。
     */
    @Test
    void expiredMultiTicketPayingOrderIsNotSplitByUnpaidCloseTask() throws Exception {
        prepareExpiredMultiTicketPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("SUCCESS"));
        AlarmService alarm = mock(AlarmService.class);
        OrderCloseTask task = orderCloseTask(wechat, alarm);

        task.closeExpired();
        task.reconcileExpiredPaying();

        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE registration_no='T20260724SIB'"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE registration_no='T20260724SIB'"));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        verify(alarm, never()).alarm(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /** P1-PT-7:多票父单查到未支付 ⇒ 整单关(主单 + 从单一起关、一起回补),不留半开从单。 */
    @Test
    void expiredMultiTicketPayingOrderNotPayClosesWholeParentOrder() throws Exception {
        prepareExpiredMultiTicketPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);

        orderCloseTask(wechat, mock(AlarmService.class)).reconcileExpiredPaying();

        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE registration_no='T20260724SIB'"));
        assertEquals(4, number("SELECT registration_status FROM cms_registration WHERE registration_no='T20260724SIB'"));
        assertEquals(7, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    /** 裁决2:预下单结果未知、微信侧从未建单(ORDER_NOT_EXIST)且过期超宽限 ⇒ 视为未支付,本地关单回补。 */
    @Test
    void staleClosedAttemptCannotExpireCurrentRegistrationOrReleaseBalances() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.isReady()).thenReturn(true);
        Map<String, String> observed = wxQuery("CLOSED");
        observed.put("wx_out_trade_no", "old-wx-attempt");
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(observed);
        when(wechat.lockPaymentAttemptForUpdate("T20260724PAY", "old-wx-attempt")).thenReturn(false);
        orderCloseTask(wechat, mock(AlarmService.class)).reconcileExpiredPaying();
        verify(wechat).lockPaymentAttemptForUpdate("T20260724PAY", "old-wx-attempt");
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
    }

    @Test
    void expiredPayingOrderNotExistBeyondGraceRetainsOriginalAndBalances() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY"))
                .thenThrow(new WechatOrderNotExistException("ORDER_NOT_EXIST", null));
        AlarmService alarm = mock(AlarmService.class);
        orderCloseTask(wechat, alarm).reconcileExpiredPaying();
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(alarm).alarm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /** 裁决2:ORDER_NOT_EXIST 但刚过期、未过宽限(预下单可能仍在途)⇒ 不动。 */
    @Test
    void expiredPayingOrderNotExistWithinGraceIsLeftUntouched() throws Exception {
        prepareExpiredPayingOrder();
        setPayExpireTime(11L, System.currentTimeMillis() - 10 * 60 * 1000L);
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY"))
                .thenThrow(new WechatOrderNotExistException("ORDER_NOT_EXIST", null));

        orderCloseTask(wechat, mock(AlarmService.class)).reconcileExpiredPaying();

        verify(wechat).queryOrderStatus("T20260724PAY");
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
    }

    /**
     * 清单 5:宽限必须不短于 prepay_id 有效期(2 小时)。过期 110 分钟时用户手里的 prepay_id 仍有效,
     * 随时可能拉起支付把单建出来 —— 此刻「查不到 = 没付」并关单回补,会关掉一笔随后收到钱的单。
     * 取 110 分钟(不是 45)是为了让「宽限被改短到 1 小时」这种回退也照样红。
     */
    @Test
    void expiredPayingOrderNotExistIsLeftUntouchedWhilePrepayIdCanStillBeUsed() throws Exception {
        prepareExpiredPayingOrder();
        setPayExpireTime(11L, System.currentTimeMillis() - 110 * 60 * 1000L);
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY"))
                .thenThrow(new WechatOrderNotExistException("ORDER_NOT_EXIST", null));

        orderCloseTask(wechat, mock(AlarmService.class)).reconcileExpiredPaying();

        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
    }

    /** 裁决2:积满 200 个查不动的卡单也不得饿死排在后面的过期 PAYING 单(游标分页推进)。 */
    @Test
    void stuckPayingOrdersDoNotStarveLaterExpiredOrders() throws Exception {
        execute("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,point_used,"
                + "payable_amount,wechat_payment_amount,payment_status,registration_status,pay_expire_time,verification_status,points_returned) "
                + "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 200) "
                + "SELECT 1000+i,CONCAT('STUCK',i),1,99,'卡单',7,0,100,100,1,1,DATE_SUB(NOW(),INTERVAL 3 DAY),0,0 FROM n",
                "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,point_used,"
                        + "payable_amount,wechat_payment_amount,payment_status,registration_status,pay_expire_time,verification_status,points_returned) "
                        + "VALUES (2000,'TLATE',1,99,'后到单',7,0,100,100,1,1,DATE_SUB(NOW(),INTERVAL 3 DAY),0,0)");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus(org.mockito.ArgumentMatchers.startsWith("STUCK")))
                .thenThrow(new RuntimeException("微信查单超时"));
        Map<String, String> late = wxQuery("NOTPAY");
        late.put("out_trade_no", "TLATE");
        late.put("wx_out_trade_no", "TLATE");
        when(wechat.queryOrderStatus("TLATE")).thenReturn(late);
        when(wechat.closePaymentAttempt("TLATE", "TLATE")).thenReturn(true);

        orderCloseTask(wechat, mock(AlarmService.class)).reconcileExpiredPaying();

        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=2000"));
        assertEquals(200, number("SELECT COUNT(*) FROM cms_registration WHERE payment_status=1"));
    }

    /**
     * 清单 6:单轮最多 500 单(每单一次微信查单,无上限时一轮可能拖过 cron 周期,
     * concurrent=1 只会让下一轮排队顺延、越积越长),停在哪儿下一轮从哪儿接着扫。
     *
     * <p>用 500 个卡单(查单一直失败、会一直留在 PAYING)压满第一轮,后面挂 100 个可关的单:
     * 没有上限 ⇒ 第一轮就把这 100 单关了;有上限但游标不带到下一轮 ⇒ 第二轮又从这 500 个卡单
     * 重新啃一遍,后面那 100 单永远轮不到。
     */
    @Test
    void reconcileStopsAtScanLimitAndResumesFromCursorNextRound() throws Exception {
        execute("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,"
                + "point_used,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                + "pay_expire_time,verification_status,points_returned) "
                + "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 500) "
                + "SELECT 3000+i,CONCAT('CAPSTUCK',i),1,99,'卡单',7,0,100,100,1,1,"
                + "DATE_SUB(NOW(),INTERVAL 3 DAY),0,0 FROM n",
                "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,"
                        + "point_used,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                        + "pay_expire_time,verification_status,points_returned) "
                        + "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 100) "
                        + "SELECT 3500+i,CONCAT('CAPLATE',i),1,99,'后到单',7,0,100,100,1,1,"
                        + "DATE_SUB(NOW(),INTERVAL 3 DAY),0,0 FROM n");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus(org.mockito.ArgumentMatchers.startsWith("CAPSTUCK")))
                .thenThrow(new RuntimeException("微信查单超时"));
        when(wechat.queryOrderStatus(org.mockito.ArgumentMatchers.startsWith("CAPLATE")))
                .thenAnswer(call -> {
                    Map<String, String> q = wxQuery("NOTPAY");
                    q.put("out_trade_no", call.getArgument(0));
                    q.put("wx_out_trade_no", call.getArgument(0));
                    return q;
                });
        when(wechat.closePaymentAttempt(org.mockito.ArgumentMatchers.startsWith("CAPLATE"), org.mockito.ArgumentMatchers.startsWith("CAPLATE"))).thenReturn(true);
        OrderCloseTask task = orderCloseTask(wechat, mock(AlarmService.class));

        task.reconcileExpiredPaying();

        // 第一轮被 500 个卡单占满,后面 100 个可关的单这一轮不该被碰。
        assertEquals(100, number("SELECT COUNT(*) FROM cms_registration"
                + " WHERE registration_no LIKE 'CAPLATE%' AND payment_status=1"));

        task.reconcileExpiredPaying();

        // 第二轮从游标接着扫,而不是又从那 500 个卡单重来。
        assertEquals(0, number("SELECT COUNT(*) FROM cms_registration"
                + " WHERE registration_no LIKE 'CAPLATE%' AND payment_status=1"));
        assertEquals(500, number("SELECT COUNT(*) FROM cms_registration"
                + " WHERE registration_no LIKE 'CAPSTUCK%' AND payment_status=1"));
    }

    /** 裁决3(P1-PT-1):微信关单失败(含查单后用户刚付成功的 ORDERPAID)⇒ 不得本地关单回补,告警。 */
    @Test
    void expiredPayingOrderWechatCloseFailureMustNotCloseLocally() throws Exception {
        assertCloseFailureLeavesOrderUntouched("NOTPAY");
    }

    /** 裁决3:PAYERROR 保守处理,同样先关微信单;关单失败不本地关单。 */
    @Test
    void expiredPayingOrderPayErrorWithCloseFailureMustNotCloseLocally() throws Exception {
        assertCloseFailureLeavesOrderUntouched("PAYERROR");
    }

    /** 裁决3:PAYERROR 先调微信 closeOrder,成功后才本地关单回补。 */
    @Test
    void expiredPayingOrderPayErrorClosesWechatOrderBeforeLocalClose() throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("PAYERROR"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);
        clearInvocations(perkLedgerService);

        orderCloseTask(wechat, mock(AlarmService.class)).reconcileExpiredPaying();

        verify(wechat).closePaymentAttempt("T20260724PAY", "T20260724PAY");
        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(6, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals("payment-reconcile-task", text("SELECT update_by FROM cms_registration WHERE id=11"));
        verify(perkLedgerService).releaseOnRefund(11L, EntitlementInvalidReason.ORGANIZER_CANCEL);
    }

    private void assertCloseFailureLeavesOrderUntouched(String tradeState) throws Exception {
        prepareExpiredPayingOrder();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery(tradeState));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenThrow(new RuntimeException("关闭订单异常：ORDERPAID"));
        AlarmService alarm = mock(AlarmService.class);
        clearInvocations(perkLedgerService);

        orderCloseTask(wechat, alarm).reconcileExpiredPaying();

        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
        verify(perkLedgerService, never()).releaseOnRefund(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        verify(alarm).alarm(org.mockito.ArgumentMatchers.eq("支付中过期订单对账失败"),
                org.mockito.ArgumentMatchers.contains("registrationId=11"));
    }

    /** P1-PT-8:主办取消活动遇 PAYING 单,查到已付 ⇒ 先按回调补记,再按已付走整单退款。 */
    @Test
    void activityCancelWithPayingOrderPaidOnWechatAppliesPaymentThenRefunds() throws Exception {
        preparePayingOrderOnActivity();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("SUCCESS"));
        IRefundService refunds = mock(IRefundService.class);
        when(refunds.saveRefund(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            ((com.chengyinhub.business.domain.RefundApplication) call.getArgument(0)).setId(700L);
            return 1;
        });

        // 活动单支付入账要 settlement_detail 等表,本 IT 未建;入账不是本用例断言对象,临时替身后复原。
        Object realSettlement = ReflectionTestUtils.getField(registrationTarget, "settlementEntryService");
        ReflectionTestUtils.setField(registrationTarget, "settlementEntryService", mock(ISettlementEntryService.class));
        try {
            cancelActivity(activityCancellation(wechat, refunds));
        } finally {
            ReflectionTestUtils.setField(registrationTarget, "settlementEntryService", realSettlement);
        }

        assertEquals("WX-TX-11", text("SELECT transaction_id FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        verify(refunds).saveRefund(org.mockito.ArgumentMatchers.argThat(app -> Long.valueOf(11L).equals(app.getSourceId())));
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertEquals(1, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /** P1-PT-8:PAYING 单查到未付 ⇒ 先关微信单,再本地关单回补库存/积分,活动照常取消。 */
    @Test
    void activityCancelWithPayingOrderUnpaidOnWechatClosesItFirst() throws Exception {
        preparePayingOrderOnActivity();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);
        IRefundService refunds = mock(IRefundService.class);
        clearInvocations(perkLedgerService);

        cancelActivity(activityCancellation(wechat, refunds));

        verify(wechat).closePaymentAttempt("T20260724PAY", "T20260724PAY");
        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        // 主办取消导致的关单,真实原因是「取消」不是「过期」:registration_status 落 3(已取消),
        // 不是 4(已过期)——4 留给对账任务真过期那条路径(见下一条用例)。
        assertEquals(3, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(6, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        // 关单回补走 settlePayingOne 唯一实现,来源由参数带进来:积分回补 CAS 必须记「谁关的」,
        // 不能再是取消路径自己那份写死的 auto-refund。
        assertEquals(1, number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertEquals("activity-cancel:1", text("SELECT update_by FROM cms_registration WHERE id=11"));
        // 权益失效原因按枚举 javadoc:「主办方取消活动」本就是 ORGANIZER_CANCEL 的定义之一,
        // 取消路径与对账任务(下一条用例)现在传的是同一个值。
        verify(perkLedgerService).releaseOnRefund(11L, EntitlementInvalidReason.ORGANIZER_CANCEL);
        verify(refunds, never()).saveRefund(org.mockito.ArgumentMatchers.any());
        assertEquals(1, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /**
     * 清单 3:多票父单在主办取消活动路径下,主单 + 从单一并关,终态都应是「已取消」(3),
     * 不是「已过期」(4)——4 那档留给对账任务真过期(见
     * {@code expiredMultiTicketPayingOrderNotPayClosesWholeParentOrder})。
     */
    @Test
    void activityCancelWithMultiTicketPayingOrderUnpaidEndsCancelledNotExpired() throws Exception {
        preparePayingMultiTicketOrderOnActivity();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);
        IRefundService refunds = mock(IRefundService.class);

        cancelActivity(activityCancellation(wechat, refunds));

        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE registration_no='T20260724SIB'"));
        assertEquals(3, number("SELECT registration_status FROM cms_registration WHERE registration_no='T20260724SIB'"));
        assertEquals(7, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    /** P1-PT-8:PAYING 单查单失败 ⇒ 拒绝取消并提示稍后重试,活动与订单都不动。 */
    @Test
    void activityCancelWithPayingOrderQueryFailureRejectsCancellation() throws Exception {
        preparePayingOrderOnActivity();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenThrow(new RuntimeException("微信查单超时"));
        IRefundService refunds = mock(IRefundService.class);

        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, refunds)));

        assertTrue(rejected.getMessage().contains("稍后重试"), rejected.getMessage());
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 清单 2:两段式。第一单可关、第二单查单失败 ⇒ 必须在<b>任何 closeOrder 之前</b>整次拒掉。
     * 一段式会先把第一单在微信关掉再回滚,留下「本地 PAYING、微信 CLOSED」的夹层。
     */
    @Test
    void activityCancelRejectsBeforeClosingAnyWechatOrderWhenAnotherQueryFails() throws Exception {
        preparePayingOrderOnActivity();
        execute("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,"
                + "ticket_id,point_used,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                + "pay_expire_time,verification_status,points_returned) VALUES "
                + "(12,'T20260724SECOND',2,55,'资金并发测试',7,9,0,100,100,1,1,"
                + "DATE_ADD(NOW(),INTERVAL 3 DAY),0,0)");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        // selectCmsRegistrationList 是 order by id desc ⇒ 先处理 id=12(可关),后处理 id=11(查单失败)。
        Map<String, String> second = wxQuery("NOTPAY");
        second.put("out_trade_no", "T20260724SECOND");
        second.put("wx_out_trade_no", "T20260724SECOND");
        when(wechat.queryOrderStatus("T20260724SECOND")).thenReturn(second);
        when(wechat.closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenThrow(new RuntimeException("微信查单超时"));

        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class))));

        assertTrue(rejected.getMessage().contains("稍后重试"), rejected.getMessage());
        // 关键断言:先被定性通过的那单(id=12)的微信订单绝不能已经被关掉。
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=12"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /**
     * 自审补(两段式的真边界):第二段里「先做可回滚的、最后做不可回滚的」。
     * id=12 是 NOTPAY(要调 closeOrder),id=11 是已付但补记会失败(金额对不上)——
     * 按 plan 顺序执行的话 closeOrder 会先落地、再因补记失败整体回滚,
     * 留下「本地 PAYING、微信 CLOSED」的夹层。补记必须排在所有 closeOrder 之前。
     */
    @Test
    void activityCancelAppliesPaymentsBeforeAnyIrreversibleWechatClose() throws Exception {
        preparePayingOrderOnActivity();
        execute("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,activity_title,member_id,"
                + "ticket_id,point_used,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                + "pay_expire_time,verification_status,points_returned) VALUES "
                + "(12,'T20260724SECOND',2,55,'资金并发测试',7,9,0,100,100,1,1,"
                + "DATE_ADD(NOW(),INTERVAL 3 DAY),0,0)");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        // selectCmsRegistrationList 是 order by id desc ⇒ plan 顺序为 id=12(NOTPAY)、id=11(SUCCESS)。
        Map<String, String> second = wxQuery("NOTPAY");
        second.put("out_trade_no", "T20260724SECOND");
        second.put("wx_out_trade_no", "T20260724SECOND");
        when(wechat.queryOrderStatus("T20260724SECOND")).thenReturn(second);
        when(wechat.closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        Map<String, String> paid = wxQuery("SUCCESS");
        paid.put("total", "1"); // 金额对不上 ⇒ handleWechatPayCallback 返回 FAIL
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(paid);

        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class))));

        assertTrue(rejected.getMessage().contains("稍后重试"), rejected.getMessage());
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=12"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /**
     * 自审补(清单 1 的 null 边界):pay_expire_time 为 null 时没有任何依据判「微信侧不可能再建出这笔单」,
     * 必须与对账任务一致地拒绝,不能当作「宽限已过」零宽限关单回补。
     */
    @Test
    void activityCancelWithOrderNotExistAndNoExpireTimeIsRejected() throws Exception {
        preparePayingOrderOnActivity();
        execute("UPDATE cms_registration SET pay_expire_time=NULL WHERE id=11");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY"))
                .thenThrow(new WechatOrderNotExistException("ORDER_NOT_EXIST", null));

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class))));

        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /** 清单 1:取消路径遇 ORDER_NOT_EXIST 且仍在宽限内 ⇒ 拒绝,并告诉主办方大约几分钟后可重试。 */
    @Test
    void activityCancelWithOrderNotExistWithinGraceTellsOrganizerWhenToRetry() throws Exception {
        preparePayingOrderOnActivity();
        setPayExpireTime(11L, System.currentTimeMillis() - 10 * 60 * 1000L);
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY"))
                .thenThrow(new WechatOrderNotExistException("ORDER_NOT_EXIST", null));

        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class))));

        assertTrue(rejected.getMessage().contains("重试"), rejected.getMessage());
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /** 清单 1:取消路径遇 ORDER_NOT_EXIST 且已过宽限 ⇒ 复用对账口径本地关单回补,取消照常完成。 */
    @Test
    void activityCancelWithOrderNotExistBeyondGraceRetainsOriginalAndRejectsCancel() throws Exception {
        preparePayingOrderOnActivity();
        setPayExpireTime(11L, System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L);
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY"))
                .thenThrow(new WechatOrderNotExistException("ORDER_NOT_EXIST", null));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class))));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
        verify(wechat, never()).closePaymentAttempt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    /**
     * 清单 8:关单语句自己就要带来源。point_used=0 时不会走积分 CAS,
     * 关单来源只能靠 closeExpiredPaying 的 update_by ——
     * 写死 'payment-reconcile-task' 的话,主办方取消在库里跟对账任务完全分不开。
     */
    @Test
    void activityCancelStampsCancelSourceEvenWhenNoPointsWereUsed() throws Exception {
        preparePayingOrderOnActivity();
        execute("UPDATE cms_registration SET point_used=0 WHERE id=11");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenReturn(true);

        cancelActivity(activityCancellation(wechat, mock(IRefundService.class)));

        assertEquals(3, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals("activity-cancel:1", text("SELECT update_by FROM cms_registration WHERE id=11"));
    }

    /** 清单 7:取消路径查单失败,主办方看到 409 会重试,运营必须同时收到告警,否则微信侧出问题没人知道。 */
    @Test
    void activityCancelQueryFailureAlarmsOperations() throws Exception {
        preparePayingOrderOnActivity();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenThrow(new RuntimeException("微信查单超时"));
        AlarmService alarm = mock(AlarmService.class);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class), alarm)));

        verify(alarm).alarm(org.mockito.ArgumentMatchers.contains("查单失败"),
                org.mockito.ArgumentMatchers.contains("registrationId=11"));
    }

    /** 清单 7:取消路径微信关单失败同样要告警 —— 这条路只有运营能查商户平台。 */
    @Test
    void activityCancelWechatCloseFailureAlarmsOperations() throws Exception {
        preparePayingOrderOnActivity();
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.queryOrderStatus("T20260724PAY")).thenReturn(wxQuery("NOTPAY"));
        when(wechat.closePaymentAttempt("T20260724PAY", "T20260724PAY")).thenThrow(new RuntimeException("关闭订单异常：ORDERPAID"));
        AlarmService alarm = mock(AlarmService.class);

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> cancelActivity(activityCancellation(wechat, mock(IRefundService.class), alarm)));

        verify(alarm).alarm(org.mockito.ArgumentMatchers.contains("关微信单失败"),
                org.mockito.ArgumentMatchers.contains("registrationId=11"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_activity WHERE id=55 AND cancel_time IS NOT NULL"));
    }

    @Test
    void validRetryReturnsSameBusinessNumberEvenWhenReservedInventoryIsZero() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9 WHERE id=11");
        assertEquals("T20260724PAY", registrationService.preparePaymentRetry(11L, 7L).getRegistrationNo());
        assertEquals("T20260724PAY", text("SELECT registration_no FROM cms_registration WHERE id=11"));
    }

    @Test
    void durablePayingClaimBlocksCancellationAndLateSuccessRecoversAutomatically() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9 WHERE id=11");

        CmsRegistration claimed = registrationService.beginPaymentAttempt(11L, 7L);

        assertEquals("T20260724PAY", claimed.getRegistrationNo());
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertThrows(RuntimeException.class,
                () -> registrationService.cancelRegistration(11L, 7L, "funds-it"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        execute("UPDATE cms_registration SET owner_type=1,owner_id=99,ticket_id=NULL WHERE id=11");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void payParamsAreReturnedOnlyAfterPayingIsDurableSoConcurrentCancelCannotWin() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9 WHERE id=11");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("package", "prepay_id=durable-11");
        when(wechat.createOrder(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenReturn(payParams);

        RegistrationCheckoutResult result = checkoutService(wechat).retryPayment(7L, 11L);

        assertEquals(payParams, result.getData().get("payParams"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertThrows(RuntimeException.class,
                () -> registrationService.cancelRegistration(11L, 7L, "funds-it"));
        execute("UPDATE cms_registration SET owner_type=1,owner_id=99,ticket_id=NULL WHERE id=11");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void uncertainWechatPreorderFailureKeepsPayingForLateSuccessRecovery() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9 WHERE id=11");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.createOrder(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenThrow(PaymentPreorderException.resultUncertain("微信响应未知", null));

        assertThrows(PaymentPreorderException.class,
                () -> checkoutService(wechat).retryPayment(7L, 11L));

        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        execute("UPDATE cms_registration SET owner_type=1,owner_id=99,ticket_id=NULL WHERE id=11");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void laterLocalPreorderRejectionKeepsOriginalPayingForLateSuccess() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9 WHERE id=11");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        when(wechat.createOrder(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenThrow(PaymentPreorderException.definiteFailure("本地参数错误", null));

        assertThrows(PaymentPreorderException.class,
                () -> checkoutService(wechat).retryPayment(7L, 11L));

        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        execute("UPDATE cms_registration SET owner_type=1,owner_id=99,ticket_id=NULL WHERE id=11");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void applicationServiceOwnsQuoteCreateReplayPreorderAndResponseMapping() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        IWechatPayService wechat = mock(IWechatPayService.class);
        when(wechat.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(wechat.isReady()).thenReturn(true);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("package", "prepay_id=application-flow");
        when(wechat.createOrder(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenReturn(payParams);
        IRegistrationCheckoutService checkout = checkoutService(wechat);
        RegistrationRequest request = request("application-flow-request");
        request.setQuoteSign(checkout.quote(7L, request).getQuoteSign());

        RegistrationCheckoutResult created = checkout.createOrReplay(7L, request);
        RegistrationCheckoutResult replayed = checkout.createOrReplay(7L, request);

        assertEquals("报名创建成功", created.getMessage());
        assertEquals(created.getData().get("registrationId"), replayed.getData().get("registrationId"));
        assertEquals(payParams, created.getData().get("payParams"));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration "
                + "WHERE member_id=7 AND request_id='application-flow-request'"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration "
                + "WHERE member_id=7 AND request_id='application-flow-request'"));
        assertEquals(99, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    @Test
    void zeroCashCompletionFailureIsRecoveredBySameRequestIdReplay() throws Exception {
        execute("UPDATE oms_ticket SET price=0,remaining_inventory=100 WHERE id=9",
                "UPDATE cms_activity SET member_id=NULL WHERE id=55",
                "ALTER TABLE ums_member ADD CONSTRAINT ck_points_unchanged CHECK (point=100)");
        IRegistrationCheckoutService checkout = checkoutService(mock(IWechatPayService.class));
        RegistrationRequest request = request("zero-recovery-request");
        request.setQuoteSign(checkout.quote(7L, request).getQuoteSign());

        assertThrows(RuntimeException.class, () -> checkout.createOrReplay(7L, request));

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration "
                + "WHERE member_id=7 AND request_id='zero-recovery-request' AND payment_status=0"));
        assertEquals(99, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));

        execute("ALTER TABLE ums_member DROP CHECK ck_points_unchanged");
        RegistrationCheckoutResult recovered = checkout.createOrReplay(7L, request);

        assertEquals(2, number("SELECT payment_status FROM cms_registration "
                + "WHERE id=" + recovered.getData().get("registrationId")));
        assertEquals(99, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND reward_business_key='registration:2:0'"));
    }

    @Test
    void concurrentCancellationReturnsPointsAndInventoryExactlyOnce() throws Exception {
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,point_used=20,points_returned=0 WHERE id=11",
                "UPDATE ums_member SET point=80 WHERE id=7");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> cancelAfter(ready, start));
            Future<Boolean> b = pool.submit(() -> cancelAfter(ready, start));
            ready.await();
            start.countDown();
            assertTrue(a.get());
            assertTrue(b.get());
            assertEquals(3, number("SELECT registration_status FROM cms_registration WHERE id=11"));
            assertEquals(1, number("SELECT points_returned FROM cms_registration WHERE id=11"));
            assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
            assertEquals(1, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requestIdUniqueKeyAllowsOnlyOneConcurrentMainOrder() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> insertRequestedRegistration(ready, start, "REQ-A"));
            Future<Boolean> b = pool.submit(() -> insertRequestedRegistration(ready, start, "REQ-B"));
            ready.await();
            start.countDown();
            int successes = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE member_id=7 AND request_id='request-7'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requestReplayFailsClosedWhenLegacyDataContainsMultipleMatches() throws Exception {
        execute("ALTER TABLE cms_registration DROP INDEX uk_reg_member_request",
                "INSERT INTO cms_registration(registration_no,member_id,request_id) VALUES "
                        + "('DUP-REQ-A',7,'same-request'),('DUP-REQ-B',7,'same-request')");

        assertThrows(IllegalStateException.class,
                () -> createOrReplay(request("same-request")));
    }

    @Test
    void requestReplayReturnsTheOnlyMatchingWinner() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        RegistrationOrderResult created = createOrReplay(request("winner-request"));

        RegistrationOrderResult replay = createOrReplay(request("winner-request"));

        assertTrue(replay.isReplayed());
        assertEquals(created.getPrimary().getRegistrationNo(), replay.getPrimary().getRegistrationNo());
    }

    @Test
    void checkoutReplayBypassesLiveQuoteAndWechatReadinessWithoutRepeatingDeductions() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100,price=1000 WHERE id=9",
                "UPDATE ums_member SET point=1 WHERE id=7");
        RegistrationRequest original = request("durable-replay");
        original.setIsUsePoint(1);
        original.setQuoteSign("original-signed-quote");
        RegistrationOrderResult created = createOrReplay(original);
        assertTrue(number("SELECT payable_amount FROM cms_registration WHERE id="
                + created.getPrimary().getId()) > 0);
        int pointsAfterCreate = number("SELECT point FROM ums_member WHERE id=7");
        int inventoryAfterCreate = number("SELECT remaining_inventory FROM oms_ticket WHERE id=9");
        IWechatPayService unavailableWechat = mock(IWechatPayService.class);
        when(unavailableWechat.isReady()).thenReturn(false);

        RegistrationCheckoutResult replay = checkoutService(unavailableWechat).createOrReplay(7L, original);

        assertEquals(created.getPrimary().getRegistrationNo(), replay.getData().get("registrationNo"));
        assertEquals(pointsAfterCreate, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(inventoryAfterCreate, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE member_id=7 AND request_id='durable-replay'"));
    }

    @Test
    void legacyNullFingerprintBindsOnlyAfterPersistentOrderProof() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        RegistrationRequest compatible = request("legacy-null-fingerprint");
        RegistrationOrderResult created = createOrReplay(compatible);
        execute("UPDATE cms_registration SET request_fingerprint=NULL WHERE id=" + created.getPrimary().getId());

        RegistrationOrderResult replay = createOrReplay(compatible);

        assertTrue(replay.isReplayed());
        assertEquals(64, text("SELECT request_fingerprint FROM cms_registration WHERE id="
                + created.getPrimary().getId()).length());

        RegistrationRequest conflictBase = request("legacy-null-conflict");
        RegistrationOrderResult conflictOrder = createOrReplay(conflictBase);
        execute("UPDATE cms_registration SET request_fingerprint=NULL WHERE id=" + conflictOrder.getPrimary().getId());
        RegistrationRequest differentTicket = request("legacy-null-conflict");
        differentTicket.setTicketId(10L);
        int registrationsBefore = number("SELECT COUNT(*) FROM cms_registration");

        assertThrows(RegistrationRequestConflictException.class,
                () -> createOrReplay(differentTicket));
        assertEquals(registrationsBefore, number("SELECT COUNT(*) FROM cms_registration"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_registration WHERE member_id=7 "
                + "AND request_id='legacy-null-conflict' AND ticket_id=10"));

        RegistrationRequest differentAttendee = request("legacy-null-conflict");
        differentAttendee.setRealName("另一个报名人");
        assertThrows(RegistrationRequestConflictException.class,
                () -> createOrReplay(differentAttendee));
    }

    @Test
    void sameRequestIdWithDifferentOwnerTicketOrAmountFingerprintIsRejected() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        RegistrationRequest original = request("semantic-request");
        original.setQuoteSign("quote-amount-v1");
        createOrReplay(original);

        RegistrationRequest differentOwner = request("semantic-request");
        differentOwner.setOwnerId(56L);
        differentOwner.setQuoteSign("quote-amount-v1");
        assertThrows(RegistrationRequestConflictException.class,
                () -> createOrReplay(differentOwner));

        RegistrationRequest differentTicket = request("semantic-request");
        differentTicket.setTicketId(10L);
        differentTicket.setQuoteSign("quote-amount-v1");
        assertThrows(RegistrationRequestConflictException.class,
                () -> createOrReplay(differentTicket));

        RegistrationRequest differentAmount = request("semantic-request");
        differentAmount.setQuoteSign("quote-amount-v2");
        assertThrows(RegistrationRequestConflictException.class,
                () -> createOrReplay(differentAmount));

        assertEquals(64, text("SELECT request_fingerprint FROM cms_registration "
                + "WHERE member_id=7 AND request_id='semantic-request'").length());
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration "
                + "WHERE member_id=7 AND request_id='semantic-request'"));
        assertEquals(99, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }

    @Test
    void concurrentCreateOrReplayReturnsOneWinnerForTheSameRequestId() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        int callers = 50;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<RegistrationOrderResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return createOrReplay(request("shared-request"));
                }));
            }
            ready.await();
            start.countDown();

            Long winnerId = null;
            for (Future<RegistrationOrderResult> future : futures) {
                Long currentId = future.get().getPrimary().getId();
                winnerId = winnerId == null ? currentId : winnerId;
                assertEquals(winnerId, currentId);
            }
            assertEquals(1, number("SELECT COUNT(*) FROM cms_registration "
                    + "WHERE member_id=7 AND request_id='shared-request'"));
            assertEquals(99, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 2026-08-30 拆掉 8-11 留的向后兼容坡道：空/null 幂等键改为**直接拒绝**。
     *
     * 原契约（blankAndNullRequestIdsRemainLegacyNonIdempotentInputs）断言的是相反行为——
     * 空键要被接受、建单、`request_id IS NULL`、不去重。那是 #714（2026-08-11）刻意留的：
     * 同一个 commit 里客户端才刚开始发 requestId，坡道是给当时已发布、还在用户手机里的
     * 那版小程序用的。
     *
     * ⚠️ 这次改动等于**宣告 8-11 前那版小程序的报名会报错**，是产品决定不是技术清理。
     * 服务端不生成兜底键：自己造一个键，等于把"重试算不算同一单"的判断权从客户端手里
     * 拿走，网络重试就会变成两单。
     */
    @Test
    void blankAndNullRequestIdsAreRejectedBeforeAnyOrderIsInserted() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        int before = number("SELECT COUNT(*) FROM cms_registration");

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> createOrReplay(request("   ")));
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> createOrReplay(request(null)));

        // 断言拦在哪道闸上，而不只是"抛了"——否则任何一个别的异常都能让它假绿。
        assertTrue(blank.getMessage().contains("幂等键"), "空白键要报幂等键这道闸，实际：" + blank.getMessage());
        assertTrue(absent.getMessage().contains("幂等键"), "缺键要报幂等键这道闸，实际：" + absent.getMessage());
        // 拒绝必须发生在插库之前：拒了却留下半单，比接受更糟。
        assertEquals(before, number("SELECT COUNT(*) FROM cms_registration"));
    }

    @Test
    void overlongRequestIdFailsBeforeAnyOrderIsInserted() throws Exception {
        execute("UPDATE oms_ticket SET remaining_inventory=100 WHERE id=9");
        int before = number("SELECT COUNT(*) FROM cms_registration");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> createOrReplay(request(repeat('x', 65))));

        assertTrue(error.getMessage().contains("64"));
        assertEquals(before, number("SELECT COUNT(*) FROM cms_registration"));
    }

    @Test
    void firstRegistrationRewardUsesStableMemberBusinessKey() throws Exception {
        execute("UPDATE ums_member SET point=110 WHERE id=7",
                "INSERT INTO ums_member_points_detail(member_id,current_points,after_points,change_points,change_reason,event_type,event_id,reward_business_key,change_type,del_flag)"
                        + " VALUES (7,100,110,10,'首次活动报名',2,0,'registration:2:0',1,0)");
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(115, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=7 AND reward_business_key='registration:2:0'"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=7 AND event_type=3 AND event_id=11"));
    }

    @Test
    void crossDomainEventTupleDoesNotSuppressRegistrationReward() throws Exception {
        execute("INSERT INTO ums_member_points_detail(member_id,current_points,after_points,change_points,change_reason,event_type,event_id,change_type,del_flag)"
                + " VALUES (7,100,101,1,'非报名域同号事件',3,11,1,0)",
                "ALTER TABLE ums_member_points_detail DROP INDEX uk_member_event_once");

        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));

        assertEquals(2, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=7 AND event_type=3 AND event_id=11"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=7 "
                + "AND reward_business_key='registration:3:11'"));
    }

    @Test
    void conflictingDuplicateRewardKeyFailsClosedAndRollsBackAllRewards() throws Exception {
        execute("INSERT INTO ums_member_points_detail(member_id,current_points,after_points,change_points,change_reason,event_type,event_id,reward_business_key,change_type,del_flag)"
                + " VALUES (7,100,1099,999,'冲突奖励',3,11,'registration:3:11',1,0)");

        // fence 事务内的冲突奖励键整笔回滚、inbox 释放,回调回 FAIL 等微信重投。
        assertEquals("FAIL", registrationService.handleWechatPayCallback(callback()));

        assertEquals(100, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=7 "
                + "AND reward_business_key='registration:2:0'"));
        assertEquals(0, number("SELECT payment_status FROM cms_registration WHERE id=11"));
    }

    @Test
    void migrationCreatesBothBusinessKeysWithoutAssumingLegacyIndexExists() throws Exception {
        execute("ALTER TABLE cms_registration DROP INDEX uk_reg_member_request",
                "ALTER TABLE cms_registration DROP COLUMN request_fingerprint",
                "ALTER TABLE ums_member_points_detail DROP INDEX uk_member_event_once",
                "ALTER TABLE ums_member_points_detail DROP COLUMN reward_business_key");

        applyFundsMigration();

        assertEquals("0:member_id,request_id", indexDefinition("cms_registration", "uk_reg_member_request"));
        assertEquals("0:member_id,reward_business_key",
                indexDefinition("ums_member_points_detail", "uk_member_reward_business"));
        assertEquals("0:member_id,event_type,event_id",
                indexDefinition("ums_member_points_detail", "uk_member_event_once"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='cms_registration' AND column_name='request_fingerprint'"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='ums_member_points_detail' AND column_name='reward_business_key'"));
    }

    @Test
    void migrationFailsClosedOnBlankRequestId() throws Exception {
        execute("INSERT INTO cms_registration(registration_no,member_id,request_id) VALUES ('BLANK-REQ',7,'')");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void migrationFailsClosedOnFingerprintWithoutRequestId() throws Exception {
        execute("INSERT INTO cms_registration(registration_no,member_id,request_id,request_fingerprint) "
                + "VALUES ('ORPHAN-FP',7,NULL,REPEAT('a',64))");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void migrationFailsClosedOnRequestIdLongerThanSchemaContract() throws Exception {
        execute("ALTER TABLE cms_registration MODIFY request_id VARCHAR(128)",
                "INSERT INTO cms_registration(registration_no,member_id,request_id) "
                        + "VALUES ('LONG-REQ',7,REPEAT('x',65))");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void migrationFailsClosedOnRequestIdWithOuterWhitespace() throws Exception {
        execute("INSERT INTO cms_registration(registration_no,member_id,request_id) "
                + "VALUES ('PADDED-REQ',7,' padded-request ')");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void migrationAllowsMultipleNullRequestIds() throws Exception {
        execute("INSERT INTO cms_registration(registration_no,member_id,request_id) "
                + "VALUES ('NULL-REQ',7,NULL)");

        applyFundsMigration();

        assertEquals(2, number("SELECT COUNT(*) FROM cms_registration WHERE request_id IS NULL"));
    }

    @Test
    void migrationRepairsWrongIndexesThatAlreadyUseTheRequiredNames() throws Exception {
        // fixture 建的是【迁移后】的库形状(两把唯一键都在)。这条用例要模拟的是
        // 「名字已被占用、但索引形状是错的」——所以必须先把同名键拆掉再按错形状建回去，
        // 否则 ADD 会先撞 Duplicate key name，压根走不到迁移。cms_registration 那行本来就这么写。
        execute("ALTER TABLE cms_registration DROP INDEX uk_reg_member_request",
                "ALTER TABLE cms_registration ADD INDEX uk_reg_member_request(request_id,member_id)",
                "ALTER TABLE ums_member_points_detail DROP INDEX uk_member_reward_business",
                "ALTER TABLE ums_member_points_detail ADD INDEX uk_member_reward_business(event_id,member_id)");

        applyFundsMigration();

        assertEquals("0:member_id,request_id", indexDefinition("cms_registration", "uk_reg_member_request"));
        assertEquals("0:member_id,reward_business_key",
                indexDefinition("ums_member_points_detail", "uk_member_reward_business"));
        assertEquals("0:member_id,event_type,event_id",
                indexDefinition("ums_member_points_detail", "uk_member_event_once"));
    }

    @Test
    void migrationCanRunTwiceWithoutChangingTheBusinessKeyContract() throws Exception {
        applyFundsMigration();
        applyFundsMigration();

        assertEquals("0:member_id,request_id", indexDefinition("cms_registration", "uk_reg_member_request"));
        assertEquals("0:member_id,reward_business_key",
                indexDefinition("ums_member_points_detail", "uk_member_reward_business"));
        assertEquals("0:member_id,event_type,event_id",
                indexDefinition("ums_member_points_detail", "uk_member_event_once"));
    }

    @Test
    void migrationResumesAfterUniqueKeyWasBuiltButOldIndexStillExists() throws Exception {
        // 「唯一键已建好」这一半 fixture 本来就满足；这里显式 DROP 再 ADD，
        // 让用例自述前置状态而不是依赖 fixture 恰好长这样(直接 ADD 会 Duplicate key name)。
        execute("ALTER TABLE cms_registration ADD INDEX idx_reg_member_request(member_id,request_id)",
                "ALTER TABLE ums_member_points_detail DROP INDEX uk_member_reward_business",
                "ALTER TABLE ums_member_points_detail ADD UNIQUE KEY uk_member_reward_business(member_id,reward_business_key)");

        applyFundsMigration();

        assertEquals("0:member_id,request_id", indexDefinition("cms_registration", "uk_reg_member_request"));
        assertEquals(0, number("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='cms_registration' "
                + "AND index_name='idx_reg_member_request'"));
        assertEquals("0:member_id,reward_business_key",
                indexDefinition("ums_member_points_detail", "uk_member_reward_business"));
        assertEquals("0:member_id,event_type,event_id",
                indexDefinition("ums_member_points_detail", "uk_member_event_once"));
    }

    @Test
    void migrationBackfillsHistoricalRewardsWithStableMemberAndInviteeSemantics() throws Exception {
        execute("INSERT INTO ums_member(id,status,point,del_flag,invite_user_id) VALUES (8,1,0,0,'99'),(99,1,0,0,NULL)",
                "INSERT INTO ums_member_points_detail(member_id,event_type,event_id,change_type,change_reason) "
                        + "VALUES (7,2,11,1,'首次活动报名'),(99,5,8,1,'邀请1位完成首次购买的用户')");

        applyFundsMigration();

        assertEquals("registration:2:0", text("SELECT reward_business_key FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=2"));
        assertEquals(0, number("SELECT event_id FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=2"));
        assertEquals("registration:5:8", text("SELECT reward_business_key FROM ums_member_points_detail "
                + "WHERE member_id=99 AND event_type=5"));
    }

    @Test
    void migrationFailsBeforeBackfillingProjectedDuplicateHistoricalRewards() throws Exception {
        execute("INSERT INTO ums_member_points_detail(member_id,event_type,event_id,change_type,change_reason) "
                + "VALUES (7,2,11,1,'首次活动报名'),(7,2,12,1,'首次活动报名')");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
        assertEquals(2, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=2 AND reward_business_key IS NULL"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=2 AND event_id=11"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=2 AND event_id=12"));
    }

    @Test
    void migrationFailsClosedWhenHistoricalInviteRewardCannotResolveInvitee() throws Exception {
        execute("INSERT INTO ums_member_points_detail(member_id,event_type,event_id,change_type,change_reason) "
                + "VALUES (99,5,999999,1,'邀请1位完成首次购买的用户')");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void migrationFailsClosedWhenHistoricalInviteeBelongsToAnotherInviter() throws Exception {
        execute("INSERT INTO ums_member(id,status,point,del_flag,invite_user_id) VALUES (8,1,0,0,'7'),(99,1,0,0,NULL)",
                "INSERT INTO ums_member_points_detail(member_id,event_type,event_id,change_type,change_reason) "
                        + "VALUES (99,5,8,1,'邀请1位完成首次购买的用户')");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void migrationKeepsLegacyEventUniquenessForWritersWithoutBusinessKeys() throws Exception {
        applyFundsMigration();

        execute("INSERT INTO ums_member_points_detail(member_id,event_type,event_id,change_type) "
                + "VALUES (7,14,77,2)");
        assertThrows(Exception.class, () -> execute(
                "INSERT INTO ums_member_points_detail(member_id,event_type,event_id,change_type) "
                        + "VALUES (7,14,77,2)"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail "
                + "WHERE member_id=7 AND event_type=14 AND event_id=77"));
    }

    @Test
    void migrationFailsClosedOnDuplicateNamespacedRewardKey() throws Exception {
        // 要证明的是「迁移遇到脏数据必须 fail-closed」，所以前置必须是【迁移前】的库：
        // 两把唯一键都不在，脏数据才进得来。只拆 uk_member_event_once 的话，
        // 第二行会先撞 fixture 里的 uk_member_reward_business，红在 INSERT 上而不是红在迁移上。
        execute("ALTER TABLE ums_member_points_detail DROP INDEX uk_member_event_once",
                "ALTER TABLE ums_member_points_detail DROP INDEX uk_member_reward_business",
                "INSERT INTO ums_member_points_detail(member_id,event_type,event_id,reward_business_key) "
                        + "VALUES (7,3,11,'registration:3:11'),(7,99,999,'registration:3:11')");

        Exception error = assertThrows(Exception.class, this::applyFundsMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    private String callbackAfter(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return registrationService.handleWechatPayCallback(callback());
    }

    private Boolean debitAfter(CountDownLatch ready, CountDownLatch start, Long refundId) throws Exception {
        ready.countDown();
        start.await();
        return new TransactionTemplate(transactionManager).execute(status -> {
            ReflectionTestUtils.invokeMethod(refundService, "debitReceiverBalance",
                    "merchant", 7L, new BigDecimal("40.00"), refundId);
            return Boolean.TRUE;
        });
    }

    private String callbackAfter(CountDownLatch ready, CountDownLatch start, String tradeState) throws Exception {
        ready.countDown();
        start.await();
        return registrationService.handleWechatPayCallback(callback(tradeState));
    }

    private Boolean cancelAfter(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return registrationService.cancelRegistration(11L, 7L, "funds-it");
    }

    private Boolean insertRequestedRegistration(CountDownLatch ready, CountDownLatch start,
                                                String registrationNo) throws Exception {
        ready.countDown();
        start.await();
        CmsRegistration registration = new CmsRegistration();
        registration.setRegistrationNo(registrationNo);
        registration.setMemberId(7L);
        registration.setRequestId("request-7");
        try {
            return registrationMapper.insertCmsRegistration(registration) == 1;
        } catch (RuntimeException duplicate) {
            return false;
        }
    }

    private Map<String, String> callback() {
        return callback("SUCCESS");
    }

    private Map<String, String> callback(String tradeState) {
        Map<String, String> callback = new HashMap<>();
        callback.put("out_trade_no", "T20260724PAY");
        callback.put("transaction_id", "WX-TX-11");
        callback.put("trade_state", tradeState);
        callback.put("total", "10000");
        return callback;
    }

    /**
     * §5-3:把 id=11 摆成「预下单已发起(payment_status=1)且过了支付窗口」,库存 5、预扣积分 20。
     * ⚠️ 过期余量用 3 DAY 而不是分钟级:任务扫描的 #{now} 是 JVM 时间,在「UTC 库 + PDT JVM」
     * 组合下与库 side 的 NOW() 错位 7 小时,分钟级余量会扫不到(§5-9 实测)。
     */
    private void prepareExpiredPayingOrder() throws Exception {
        execute("UPDATE cms_registration SET payment_status=1,registration_status=1,ticket_id=9,"
                        + "point_used=20,points_returned=0,"
                        + "pay_expire_time=DATE_SUB(NOW(),INTERVAL 3 DAY) WHERE id=11",
                "UPDATE ums_member SET point=80 WHERE id=7",
                "UPDATE oms_ticket SET remaining_inventory=5 WHERE id=9");
    }

    /** P1-PT-7:id=11 为多票父单 P-MULTI 的主单(PAYING),另插一张同票从单(0 态,payable=0),两行都过期。 */
    private void prepareExpiredMultiTicketPayingOrder() throws Exception {
        prepareExpiredPayingOrder();
        execute("UPDATE cms_registration SET parent_order_no='P-MULTI' WHERE id=11",
                "INSERT INTO cms_registration(registration_no,parent_order_no,owner_type,owner_id,activity_title,"
                        + "member_id,ticket_id,point_used,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                        + "pay_expire_time,qrcode_url,verification_status,points_returned) VALUES "
                        + "('T20260724SIB','P-MULTI',1,99,'资金并发测试',7,9,0,0,0,0,1,"
                        + "DATE_SUB(NOW(),INTERVAL 3 DAY),'already-generated',0,0)");
    }

    /** P1-PT-8:id=11 挂在活动 55(主办 member 1)下,PAYING、未过期,库存 5、预扣积分 20。 */
    private void preparePayingOrderOnActivity() throws Exception {
        prepareExpiredPayingOrder();
        execute("UPDATE cms_registration SET owner_type=2,owner_id=55,pay_expire_time=DATE_ADD(NOW(),INTERVAL 3 DAY) WHERE id=11");
    }

    /** 清单 3:id=11 为活动 55 下多票父单 P-MULTI 的主单(PAYING、未过期),另插一张同票从单(0 态)。 */
    private void preparePayingMultiTicketOrderOnActivity() throws Exception {
        preparePayingOrderOnActivity();
        execute("UPDATE cms_registration SET parent_order_no='P-MULTI' WHERE id=11",
                "INSERT INTO cms_registration(registration_no,parent_order_no,owner_type,owner_id,activity_title,"
                        + "member_id,ticket_id,point_used,payable_amount,wechat_payment_amount,payment_status,registration_status,"
                        + "pay_expire_time,qrcode_url,verification_status,points_returned) VALUES "
                        + "('T20260724SIB','P-MULTI',2,55,'资金并发测试',7,9,0,0,0,0,1,"
                        + "DATE_ADD(NOW(),INTERVAL 3 DAY),'already-generated',0,0)");
    }

    private com.chengyinhub.business.service.impl.TeamGroupServiceImpl activityCancellation(
            IWechatPayService wechat, IRefundService refunds) {
        return activityCancellation(wechat, refunds, mock(AlarmService.class));
    }

    private com.chengyinhub.business.service.impl.TeamGroupServiceImpl activityCancellation(
            IWechatPayService wechat, IRefundService refunds, AlarmService alarm) {
        com.chengyinhub.business.service.impl.TeamGroupServiceImpl target =
                new com.chengyinhub.business.service.impl.TeamGroupServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", template.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "omsTicketMapper", template.getMapper(OmsTicketMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(target, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(target, "refundService", refunds);
        ReflectionTestUtils.setField(target, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(target, "manualRefundCaseService", mock(IActivityCancelManualRefundCaseService.class));
        ReflectionTestUtils.setField(target, "wechatPayService", wechat);
        ReflectionTestUtils.setField(target, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(target, "alarmService", alarm);
        // 本套检查支付/退票落库；站内通知持久化另由取消通知事务测试覆盖。
        IImService notices = mock(IImService.class);
        when(notices.sendSystem(any(), any(), any(), any())).thenReturn(1L);
        ReflectionTestUtils.setField(target, "imService", notices);
        return target;
    }

    /** 与生产 @Transactional 同一提交边界:取消在一个事务里,抛异常整体回滚。 */
    private void cancelActivity(com.chengyinhub.business.service.impl.TeamGroupServiceImpl target) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                tx -> target.cancelActivityByOwner(55L, 1L, "场地临时不可用"));
    }

    /** 用 JVM 时间经 JDBC 写 pay_expire_time,与任务读回比较走同一时区换算(避开库 NOW() 与 JVM 错位,§5-9)。 */
    private void setPayExpireTime(Long id, long epochMillis) throws Exception {
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("UPDATE cms_registration SET pay_expire_time=? WHERE id=?")) {
            ps.setTimestamp(1, new java.sql.Timestamp(epochMillis));
            ps.setLong(2, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** §5-3:微信查单返回的原生 map(queryOrderStatus 与回调 resultMap 同键)。 */
    private Map<String, String> wxQuery(String tradeState) {
        Map<String, String> query = new HashMap<>();
        query.put("out_trade_no", "T20260724PAY");
        query.put("wx_out_trade_no", "T20260724PAY");
        query.put("transaction_id", "WX-TX-11");
        query.put("trade_state", tradeState);
        query.put("total", "10000");
        return query;
    }

    private OrderCloseTask orderCloseTask(IWechatPayService wechat, AlarmService alarm) {
        OrderCloseTask task = new OrderCloseTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(task, "wechatPayService", wechat);
        ReflectionTestUtils.setField(task, "alarmService", alarm);
        ReflectionTestUtils.setField(task, "paymentTransactions", transactionManager);
        return task;
    }

    /**
     * source_club_id 在这里是【直接写库】的 —— R9-P1 之后建单入口恒不写该列，
     * 所以这个 helper 模拟的是「服务端事实写入」（未来的引流凭据 / 受审计的后台更正），
     * 用它承重的那几条正控证明：只要归因值不是客户端自报的，逐票 10% 链路仍照常成立。
     */
    private void prepareShopDayTicketSource(Long sourceClubId, Long activityClubId, Long topicClubId,
                                            String channelCode, String saleEndAt) throws Exception {
        String source = sourceClubId == null ? "NULL" : sourceClubId.toString();
        execute("INSERT INTO cms_topic(id,club_id,selling_started_at,sale_end_at) VALUES"
                        + " (23," + topicClubId + ",'2020-01-01 00:00:00','" + saleEndAt + "')",
                "UPDATE cms_activity SET topic_id=23,club_id=" + activityClubId + " WHERE id=55",
                "UPDATE oms_ticket SET signup_deadline='" + saleEndAt + "' WHERE id=9",
                "UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,purchase_kind=3,"
                        + "source_club_id=" + source + ",source_channel_code='" + channelCode + "' WHERE id=11");
    }

    private RegistrationRequest request(String requestId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(55L);
        request.setTicketId(9L);
        request.setIsUsePoint(0);
        request.setRealName("资金测试用户");
        request.setPhone("13800000000");
        request.setRequestId(requestId);
        return request;
    }

    private RegistrationOrderResult createOrReplay(RegistrationRequest request) {
        return registrationOrderService.createOrReplay(7L, request);
    }

    private IRegistrationCheckoutService checkoutService(IWechatPayService wechatPayService) {
        RegistrationCheckoutServiceImpl target = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(target, "registrationService", registrationService);
        ReflectionTestUtils.setField(target, "registrationOrderService", registrationOrderService);
        ReflectionTestUtils.setField(target, "wechatPayService", wechatPayService);
        return target;
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发测试等待事件超时，请核对工作线程失败与锁定快照");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String text(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String indexDefinition(String tableName, String indexName) throws Exception {
        String sql = "SELECT CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)) "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='" + tableName + "' AND index_name='" + indexName + "'";
        String definition = text(sql);
        return definition == null ? "" : definition;
    }

    private void applyFundsMigration() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(
                    "chengyinhub-admin/sql/migration_p1_funds_core_uniqueness_20260724.sql")));
        }
    }

    private String allMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("funds-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, CmsRegistrationMapper.class, "mapper/business/CmsRegistrationMapper.xml");
        addMapper(configuration, CmsActivityMapper.class, "mapper/business/CmsActivityMapper.xml");
        addMapper(configuration, OmsTicketMapper.class, "mapper/business/OmsTicketMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        addMapper(configuration, UmsMemberBalanceDetailMapper.class,
                "mapper/business/UmsMemberBalanceDetailMapper.xml");
        addMapper(configuration, UmsMemberPointsDetailMapper.class, "mapper/business/UmsMemberPointsDetailMapper.xml");
        addMapper(configuration, CmsPointsSettingMapper.class, "mapper/business/CmsPointsSettingMapper.xml");
        addMapper(configuration, CmsTopicNodeMapper.class, "mapper/business/CmsTopicNodeMapper.xml");
        addMapper(configuration, CmsRegistrationScanMapper.class, "mapper/business/CmsRegistrationScanMapper.xml");
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

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
