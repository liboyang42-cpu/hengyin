package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.vo.ClubSessionSettlementRow;
import com.chengyinhub.business.domain.vo.MerchantSessionFeeLine;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.CoopSettlementOriginalMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.SettlementClaimServiceImpl;
import com.chengyinhub.business.service.support.ClubPrincipalPayee;
import com.chengyinhub.business.service.support.ClubSessionMerchantFee;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第十二轮 k5(2026-11-05 业主)真库证据:俱乐部场次「按核销付费」商家费在场次结算时付,从主理人份额里出,
 * 平台仍是留存销售额的 3%。照 h4 走查的数字:¥39 × 4 人,一人集合前 24 小时内退一半,三人参加,商家扫团码,
 * 单价 ¥8/人。真 SQL:结算选集(14 天)、计费人头、主理人分账行减额、商家行、claim、余额流水、俱乐部结算行、
 * 商家「核销与退款」行、主题现金基数与退款闸的取数。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubSessionMerchantFeeMySqlIT {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("club_session_merchant_fee_it");
    private static final long PRINCIPAL = 990005L, MERCHANT = 9002L, PLATFORM = 9001L;
    private static final long CLUB = 990004L, TOPIC = 990015L, ACTIVITY = 9L, ORDER = 1L;

    private DataSource dataSource;
    private CmsActivityServiceImpl activityService;
    private SettlementDetailMapper feeMapper;
    private SettlementDetailMapper detailMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private CoopSettlementEvidenceMapper evidenceMapper;
    private CoopSettlementOriginalMapper originalMapper;
    private UmsMemberMapper umsMemberMapper;
    private TransactionTemplate tx;
    private com.chengyinhub.business.domain.CmsTopic topic;
    private com.chengyinhub.business.service.impl.RefundServiceImpl refundService;
    private AlarmService refundAlarms;
    private CmsRegistrationMapper registrationMapper;

    @BeforeAll
    void createDatabaseAndService() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(manager);
        umsMemberMapper = template.getMapper(UmsMemberMapper.class);
        UmsMemberBalanceDetailMapper balanceMapper = template.getMapper(UmsMemberBalanceDetailMapper.class);
        SettlementDebtLedger debtLedger = new SettlementDebtLedger(template.getMapper(UmsMemberSettlementDebtMapper.class),
                umsMemberMapper, balanceMapper);
        feeMapper = template.getMapper(SettlementDetailMapper.class);
        detailMapper = template.getMapper(SettlementDetailMapper.class);
        coopSettlementMapper = template.getMapper(CoopSettlementMapper.class);
        evidenceMapper = template.getMapper(CoopSettlementEvidenceMapper.class);
        originalMapper = template.getMapper(CoopSettlementOriginalMapper.class);

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberById(anyLong()))
                .thenAnswer(i -> umsMemberMapper.selectUmsMemberById(i.getArgument(0)));
        when(memberService.lockMemberById(anyLong()))
                .thenAnswer(i -> umsMemberMapper.lockMemberById(i.getArgument(0)));
        when(memberService.addBalance(anyLong(), any()))
                .thenAnswer(i -> umsMemberMapper.addBalance(i.getArgument(0), i.getArgument(1)) == 1);
        IUmsMemberBalanceDetailService detailService = mock(IUmsMemberBalanceDetailService.class);
        when(detailService.insertUmsMemberBalanceDetail(any()))
                .thenAnswer(i -> balanceMapper.insertUmsMemberBalanceDetail((UmsMemberBalanceDetail) i.getArgument(0)));

        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.03"));
        rule.setMerchantRate(new BigDecimal("0.97"));
        rule.setCreatorRate(BigDecimal.ZERO);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
        when(ruleService.getActiveRule(eq(0L), eq("activity"))).thenReturn(rule);
        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(PLATFORM);
        CmsSettingMapper settingMapper = mock(CmsSettingMapper.class);
        when(settingMapper.selectCmsSettingById(1L)).thenReturn(setting);
        ClubMapper clubMapper = mock(ClubMapper.class);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(PRINCIPAL);
        when(clubMapper.selectClubById(CLUB)).thenReturn(club);
        ClubPrincipalPayee payee = new ClubPrincipalPayee();
        ReflectionTestUtils.setField(payee, "clubMapper", clubMapper);

        ClubSessionMerchantFee fee = new ClubSessionMerchantFee();
        ReflectionTestUtils.setField(fee, "coopOrderMapper", template.getMapper(CoopOrderMapper.class));
        ReflectionTestUtils.setField(fee, "settlementDetailMapper", detailMapper);
        ReflectionTestUtils.setField(fee, "topicSaleSnapshotMapper", template.getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class));
        ReflectionTestUtils.setField(fee, "settlementClaimService",
                new SettlementClaimServiceImpl(template.getMapper(SettlementClaimMapper.class)));
        ReflectionTestUtils.setField(fee, "alarmService", mock(AlarmService.class));

        activityService = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(activityService, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(activityService, "cmsRegistrationMapper", template.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(activityService, "settlementDetailMapper", detailMapper);
        ReflectionTestUtils.setField(activityService, "activitySettlementHoldMapper", template.getMapper(ActivitySettlementHoldMapper.class));
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PRINCIPAL);
        topic.setProductType(1);
        topic.setClubId(CLUB);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        ReflectionTestUtils.setField(activityService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(activityService, "topicSaleSnapshotMapper",
                template.getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class));
        ReflectionTestUtils.setField(activityService, "cmsSettingMapper", settingMapper);
        ReflectionTestUtils.setField(activityService, "umsMemberService", memberService);
        ReflectionTestUtils.setField(activityService, "umsMemberBalanceDetailService", detailService);
        ReflectionTestUtils.setField(activityService, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(activityService, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(activityService, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(activityService, "clubPrincipalPayee", payee);
        ReflectionTestUtils.setField(activityService, "clubSessionMerchantFee", fee);
        ReflectionTestUtils.setField(activityService, "transactionManager", manager);
        // 第十三轮 m6:俱乐部自有自由探索场次结算时补记逐张分账行,走真实 SettlementEntryServiceImpl。
        com.chengyinhub.business.service.impl.SettlementEntryServiceImpl entries =
                new com.chengyinhub.business.service.impl.SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(entries, "settlementDetailMapper", detailMapper);
        ReflectionTestUtils.setField(entries, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(entries, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(entries, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(entries, "coopOrderMapper", template.getMapper(CoopOrderMapper.class));
        ReflectionTestUtils.setField(entries, "settlementClaimService",
                new SettlementClaimServiceImpl(template.getMapper(SettlementClaimMapper.class)));
        ReflectionTestUtils.setField(activityService, "settlementEntryService", entries);

        // 第十二轮 k11:结算后退款走真实的 RefundServiceImpl 冲销(真表的分账行、余额、余额流水、欠款)。
        registrationMapper = template.getMapper(CmsRegistrationMapper.class);
        refundAlarms = mock(AlarmService.class);
        refundService = new com.chengyinhub.business.service.impl.RefundServiceImpl();
        ReflectionTestUtils.setField(refundService, "settlementDetailMapper", detailMapper);
        ReflectionTestUtils.setField(refundService, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(refundService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(refundService, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(refundService, "coopOrderMapper", template.getMapper(CoopOrderMapper.class));
        ReflectionTestUtils.setField(refundService, "coopSettlementMapper", coopSettlementMapper);
        ReflectionTestUtils.setField(refundService, "activitySettlementHoldMapper", template.getMapper(ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(refundService, "settlementDebtLedger", debtLedger);
        ReflectionTestUtils.setField(refundService, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(refundService, "umsMemberBalanceDetailMapper", balanceMapper);
        ReflectionTestUtils.setField(refundService, "alarmService", refundAlarms);
        ReflectionTestUtils.setField(refundService, "topicSaleSnapshotMapper", template.getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class));
        ReflectionTestUtils.setField(refundService, "refundApplicationMapper", template.getMapper(com.chengyinhub.business.mapper.RefundApplicationMapper.class));
        ReflectionTestUtils.setField(refundService, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(refundService, "merchantReferralBountyService", mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(refundService, "firstReviewPointsClawback", mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
    }

    @BeforeEach
    void resetSchema() throws Exception {
        topic.setProductType(1);
        execute("DROP TABLE IF EXISTS cms_activity", "DROP TABLE IF EXISTS cms_topic", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS topic_sale_snapshot", "DROP TABLE IF EXISTS topic_edition_finalization",
                "DROP TABLE IF EXISTS ums_member", "DROP TABLE IF EXISTS ums_member_balance_detail",
                "DROP TABLE IF EXISTS settlement_detail", "DROP TABLE IF EXISTS coop_complaint",
                "DROP TABLE IF EXISTS cms_activity_settlement_hold", "DROP TABLE IF EXISTS ums_member_settlement_debt",
                "DROP TABLE IF EXISTS refund_application", "DROP TABLE IF EXISTS coop_order", "DROP TABLE IF EXISTS coop_invite",
                "DROP TABLE IF EXISTS coop_group_verify", "DROP TABLE IF EXISTS settlement_claim",
                "DROP TABLE IF EXISTS cms_registration_seat", "DROP TABLE IF EXISTS club_lead_checkin",
                "DROP TABLE IF EXISTS club_event_attendance_correction", "DROP TABLE IF EXISTS player_node_progress");
        execute("DROP TABLE IF EXISTS redemption", "DROP TABLE IF EXISTS merchant_payable", "DROP TABLE IF EXISTS club_direct_merchant_receivable");
        execute(
                "CREATE TABLE redemption (id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_id BIGINT, topic_id BIGINT, chapter_id BIGINT,"
                        + " merchant_id BIGINT, offer_id BIGINT, status VARCHAR(16)) ENGINE=InnoDB",
                "CREATE TABLE merchant_payable (id BIGINT PRIMARY KEY AUTO_INCREMENT, redemption_id BIGINT, entry_type VARCHAR(16)) ENGINE=InnoDB",
                "CREATE TABLE club_direct_merchant_receivable (id BIGINT PRIMARY KEY AUTO_INCREMENT, redemption_id BIGINT, status VARCHAR(16)) ENGINE=InnoDB",
                "CREATE TABLE topic_edition_finalization (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT NOT NULL,"
                        + " UNIQUE KEY uk_topic_edition_finalization(topic_id)) ENGINE=InnoDB",
                "CREATE TABLE topic_sale_snapshot (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT UNIQUE, schema_version VARCHAR(64),"
                        + "parameter_version BIGINT, source_review_task_id BIGINT, canonical_json LONGTEXT, frozen_at DATETIME) ENGINE=InnoDB",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(100), description VARCHAR(255), img_url VARCHAR(255), img_arr VARCHAR(255),"
                        + "category_ids VARCHAR(255), address_name VARCHAR(255), longitude VARCHAR(64), latitude VARCHAR(64), address VARCHAR(255), status INT, publish_status INT,"
                        + "cancel_time DATETIME, cancel_reason VARCHAR(255), reason VARCHAR(255), del_flag INT, start_date DATETIME, end_date DATETIME, template_id BIGINT,"
                        + "topic_id BIGINT, club_id BIGINT, check_status INT, is_settlement INT, create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME) ENGINE=InnoDB",
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, name VARCHAR(100), member_id BIGINT, product_type INT, team_mode TINYINT NOT NULL DEFAULT 0,"
                        + " team_max_members INT NOT NULL DEFAULT 4, club_id BIGINT, del_flag INT DEFAULT 0,"
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
                        + "source_id BIGINT, receiver_type VARCHAR(32) NOT NULL, receiver_id BIGINT, gross_amount DECIMAL(12,2), commission_amount DECIMAL(12,2),"
                        + "settlement_amount DECIMAL(12,2), status INT, fail_reason VARCHAR(500), idempotency_key VARCHAR(255),"
                        + "create_time DATETIME(3), update_time DATETIME(3), UNIQUE KEY uk_detail_idempotency(idempotency_key),"
                        + "UNIQUE KEY uk_settlement_detail_claim_event (claim_id, entry_kind, event_key)) ENGINE=InnoDB",
                "CREATE TABLE coop_complaint (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT, registration_id BIGINT, reporter_member_id BIGINT,"
                        + "status INT, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE refund_application (id BIGINT PRIMARY KEY AUTO_INCREMENT, source_type VARCHAR(32), source_id BIGINT,"
                        + "paid_amount DECIMAL(12,2), refund_amount DECIMAL(12,2), status INT, del_flag INT DEFAULT 0,"
                        + "refund_no VARCHAR(64), owner_type BIGINT, owner_id BIGINT, member_id BIGINT, merchant_id BIGINT, reason VARCHAR(500),"
                        + "reject_reason VARCHAR(500), reviewer_id BIGINT, reviewed_at DATETIME, created_by_user_id BIGINT, adjustment_created INT,"
                        + "idempotency_key VARCHAR(128) UNIQUE, payout_status INT, payout_method VARCHAR(64), payout_receipt_url VARCHAR(500),"
                        + "payout_handler_id BIGINT, payout_handled_at DATETIME, payout_reviewer2_id BIGINT, payout_reviewed2_at DATETIME,"
                        + "payout_remark VARCHAR(500), payout_lease_token VARCHAR(64), payout_lease_owner BIGINT, payout_lease_expires_at DATETIME,"
                        + "create_time DATETIME, update_time DATETIME, platform_takeover_at DATETIME) ENGINE=InnoDB",
                "CREATE TABLE coop_invite (id BIGINT PRIMARY KEY, invite_type INT) ENGINE=InnoDB",
                "CREATE TABLE coop_order (id BIGINT PRIMARY KEY, invite_id BIGINT, reg_id BIGINT, topic_id BIGINT, game_id BIGINT, from_id BIGINT,"
                        + " to_id BIGINT, to_type VARCHAR(16), share_mode INT, share_rate DECIMAL(6,2), fixed_fee DECIMAL(10,2), perks_snapshot TEXT,"
                        + " terms_version VARCHAR(16), role_snapshot VARCHAR(32), scope_type_snapshot VARCHAR(32), scope_id_snapshot BIGINT,"
                        + " eligible_purchase_kinds_snapshot VARCHAR(64), fulfillment_evidence_rule_snapshot VARCHAR(64), terms_snapshot TEXT,"
                        + " deposit_amount_snapshot DECIMAL(10,2), status INT, create_time DATETIME, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE coop_group_verify (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT NOT NULL, activity_id BIGINT,"
                        + " coop_order_id BIGINT NOT NULL, merchant_member_id BIGINT NOT NULL, leader_member_id BIGINT NOT NULL,"
                        + " verify_time DATETIME NOT NULL, UNIQUE KEY uk_group_verify_activity_merchant(activity_id, merchant_member_id)) ENGINE=InnoDB",
                "CREATE TABLE settlement_claim (id BIGINT NOT NULL AUTO_INCREMENT, commercial_context_type VARCHAR(32) NOT NULL,"
                        + " commercial_context_id BIGINT NOT NULL, fulfillment_type VARCHAR(32) NOT NULL, fulfillment_id BIGINT NOT NULL,"
                        + " beneficiary_role VARCHAR(32) NOT NULL, beneficiary_id BIGINT NOT NULL, service_unit VARCHAR(48) NOT NULL,"
                        + " settlement_route VARCHAR(32) NOT NULL, source_contract_type VARCHAR(48) NOT NULL, source_contract_id BIGINT NOT NULL,"
                        + " status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', create_time DATETIME NOT NULL, PRIMARY KEY (id),"
                        + " UNIQUE KEY uk_settlement_claim_key (commercial_context_type, commercial_context_id, fulfillment_type, fulfillment_id,"
                        + " beneficiary_role, beneficiary_id, service_unit)) ENGINE=InnoDB",
                "CREATE TABLE cms_registration_seat (id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_id BIGINT, seat_no INT, status INT,"
                        + " topic_id BIGINT, activity_id BIGINT, verified_at DATETIME) ENGINE=InnoDB",
                // C-10 到场事实的三张表(生产 DDL 的相关列)
                "CREATE TABLE club_lead_checkin (id BIGINT NOT NULL AUTO_INCREMENT, activity_id BIGINT NOT NULL, member_id BIGINT NOT NULL,"
                        + " arrived_at DATETIME NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_arrive (activity_id, member_id)) ENGINE=InnoDB",
                "CREATE TABLE club_event_attendance_correction (id BIGINT NOT NULL AUTO_INCREMENT, club_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,"
                        + " member_id BIGINT NOT NULL, original_arrived TINYINT(1) NOT NULL, new_arrived TINYINT(1) NOT NULL, reason VARCHAR(500) NOT NULL,"
                        + " actor_member_id BIGINT NOT NULL, request_id VARCHAR(64) NOT NULL, version INT NOT NULL, corrected_at DATETIME NOT NULL,"
                        + " PRIMARY KEY (id)) ENGINE=InnoDB",
                "CREATE TABLE player_node_progress (id BIGINT NOT NULL AUTO_INCREMENT, member_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,"
                        + " topic_id BIGINT, node_id BIGINT NOT NULL, status TINYINT DEFAULT 1, checkin_time DATETIME, del_flag INT DEFAULT 0,"
                        + " PRIMARY KEY (id)) ENGINE=InnoDB",
                "INSERT INTO ums_member(id,status,del_flag,balance) VALUES (" + PRINCIPAL + ",1,0,0.00),(" + MERCHANT + ",1,0,0.00),("
                        + PLATFORM + ",1,0,0.00)",
                "INSERT INTO cms_topic(id,name,member_id,product_type,club_id) VALUES (" + TOPIC + ",'外滩钟声'," + PRINCIPAL + ",1," + CLUB + ")",
                "INSERT INTO coop_order(id,topic_id,from_id,to_id,to_type,share_mode,fixed_fee,terms_version,terms_snapshot,status,del_flag,create_time)"
                        + " VALUES (" + ORDER + "," + TOPIC + "," + PRINCIPAL + "," + MERCHANT + ",'merchant',2,8.00,'v1',"
                        + "'{\"shareMode\":2,\"fixedFee\":8.00,\"compensationMode\":\"FIXED_PER_FULFILLMENT\"}',0,0,NOW())");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_complaint_period_funds_gate_20260917.sql"));
        }
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    /**
     * 四人 ¥39,一人退一半,商家扫团码,¥8/人。13 天时不结;满 14 天结:平台 4.10、商家 24.00、主理人 108.40,
     * 合计 = 留存销售额 136.50;每张计费报名 主理人 29.83 + 商家 8.00 + 平台 1.17 = 39.00。重跑不再付。
     * 俱乐部结算行与商家「核销与退款」行读到同样的钱;结算后退款按比例冲回商家那份。
     */
    @Test
    void halfRefundGroupScanFourteenDayWaitSettleRefundAndRerunToTheFen() throws Exception {
        seedSession(ACTIVITY, 13);
        for (long reg = 74; reg <= 77; reg++) seedPaid(reg, ACTIVITY);
        leadCheckin(74L);      // 领队签到
        ticketVerified(75L);   // 票被核销
        nodeProgress(76L);     // 节点进度
        leadCheckin(77L);      // 到了,但集合前退了一半 ⇒ 不计
        execute("UPDATE cms_registration SET payment_status=4, registration_status=3 WHERE id=77",
                "INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                        + " VALUES (601,'registration',77,39.00,19.50,1,0)",
                "UPDATE settlement_detail SET status=2 WHERE source_id=77",
                "INSERT INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time)"
                        + " VALUES (" + TOPIC + "," + ACTIVITY + "," + ORDER + "," + MERCHANT + "," + PRINCIPAL + ",NOW())");

        activityService.executeActivitySettlement();
        assertEquals(0, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=" + ACTIVITY), "13 天:还在客诉期,不结");
        assertMoney("0.00", balance(MERCHANT));
        assertMoney("0.00", balance(PRINCIPAL));

        execute("UPDATE cms_activity SET start_date=DATE_SUB(NOW(),INTERVAL 16 DAY), end_date=DATE_SUB(NOW(),INTERVAL 15 DAY) WHERE id=" + ACTIVITY);
        assertEquals(1, activityService.executeActivitySettlement());

        assertMoney("4.10", balance(PLATFORM));
        assertMoney("24.00", balance(MERCHANT));
        assertMoney("108.40", balance(PRINCIPAL));
        assertMoney("136.50", balance(PLATFORM).add(balance(MERCHANT)).add(balance(PRINCIPAL)));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail WHERE member_id=" + MERCHANT
                + " AND event_type=95 AND event_id=" + ACTIVITY + " AND change_type=1 AND change_balance=24.00"));
        for (long reg = 74; reg <= 76; reg++) {
            assertMoney("29.83", money("SELECT settlement_amount FROM settlement_detail WHERE source_id=" + reg + " AND receiver_type='merchant' AND status=1"));
            assertMoney("8.00", money("SELECT settlement_amount FROM settlement_detail WHERE source_id=" + reg + " AND receiver_type='coop_merchant' AND receiver_id=" + MERCHANT + " AND status=1"));
            assertMoney("39.00", money("SELECT SUM(settlement_amount) FROM settlement_detail WHERE source_type='registration' AND source_id=" + reg + " AND status=1"));
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM settlement_claim c JOIN settlement_detail d ON d.claim_id=c.id"
                    + " WHERE d.source_id=" + reg + " AND d.receiver_type='coop_merchant' AND c.source_contract_type='COOP_ORDER'"
                    + " AND c.source_contract_id=" + ORDER + " AND c.beneficiary_id=" + MERCHANT));
        }
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE source_id=77 AND receiver_type='coop_merchant'"),
                "退了一半的那单不计人头");

        // 俱乐部结算行:销售额 = 到账 + 平台 + 商家 = 136.50
        ClubSessionSettlementRow row = coopSettlementMapper.selectClubSessionSettlements(CLUB).get(0);
        assertMoney("108.40", row.getNetAmount());
        assertMoney("4.10", row.getPlatformAmount());
        assertMoney("24.00", row.getMerchantFeeAmount());
        // 商家「核销与退款」:三条 ¥8 核销费
        List<MerchantSessionFeeLine> lines = feeMapper.selectSessionFeeLinesByMerchant(MERCHANT, null);
        assertEquals(3, lines.size());
        for (MerchantSessionFeeLine line : lines) {
            assertMoney("8.00", line.getFeeAmount());
            assertMoney("8.00", line.getUnitFee());
            assertMoney("0.00", line.getClawbackAmount());
        }

        // 重跑结算任务:不再付
        assertEquals(0, activityService.executeActivitySettlement());
        assertMoney("24.00", balance(MERCHANT));
        assertMoney("108.40", balance(PRINCIPAL));
        assertMoney("4.10", balance(PLATFORM));

        // 结算后退款 reg 76 全额(¥39):既有冲销按真表读出的已结算行分,商家那份 8.00 分毫不差
        List<SettlementDetail> settled = detailMapper.selectSettledDetails("registration", 76L);
        RefundApplication refund = new RefundApplication();
        refund.setPaidAmount(new BigDecimal("39.00"));
        refund.setRefundAmount(new BigDecimal("39.00"));
        BigDecimal[][] amounts = allocateClawback(settled, refund);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < settled.size(); i++) {
            total = total.add(amounts[i][2]);
            if ("coop_merchant".equals(settled.get(i).getReceiverType())) assertMoney("8.00", amounts[i][2]);
            if ("merchant".equals(settled.get(i).getReceiverType())) assertMoney("29.83", amounts[i][2]);
            execute("INSERT INTO settlement_detail(batch_id,merchant_id,source_type,source_id,receiver_type,receiver_id,gross_amount,"
                    + "commission_amount,settlement_amount,status,idempotency_key,create_time) VALUES (0," + PRINCIPAL + ",'refund',602,'"
                    + settled.get(i).getReceiverType() + "'," + settled.get(i).getReceiverId() + ",-" + amounts[i][0] + ",0,-"
                    + amounts[i][2] + ",1,'refund:602:" + settled.get(i).getReceiverType() + ":" + settled.get(i).getReceiverId() + "',NOW())");
        }
        assertMoney("39.00", total);
        execute("INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                + " VALUES (602,'registration',76,39.00,39.00,1,0)");
        ClubSessionSettlementRow after = coopSettlementMapper.selectClubSessionSettlements(CLUB).get(0);
        assertMoney("78.57", after.getNetAmount());      // 108.40 − 29.83
        assertMoney("2.93", after.getPlatformAmount());  // 4.10 − 1.17
        assertMoney("16.00", after.getMerchantFeeAmount()); // 24.00 − 8.00
        BigDecimal clawed = BigDecimal.ZERO;
        for (MerchantSessionFeeLine line : feeMapper.selectSessionFeeLinesByMerchant(MERCHANT, ACTIVITY)) {
            clawed = clawed.add(line.getClawbackAmount());
        }
        assertMoney("-8.00", clawed);
    }

    /** 到场更正优先、没到场不计;客诉暂扣的那单结算时不计人头,结案补结时再付它那 ¥8;补结后重跑不再付。 */
    @Test
    void heldRegistrationPaysItsFeeAtRelease() throws Exception {
        seedSession(ACTIVITY, 15);
        for (long reg = 74; reg <= 77; reg++) seedPaid(reg, ACTIVITY);
        leadCheckin(74L);
        leadCheckin(75L);
        correctedNotArrived(75L); // 主理人更正为没到:更正优先,不计
        nodeProgress(76L);
        // 77 没有任何到场事实:不计
        execute("INSERT INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time)"
                        + " VALUES (" + TOPIC + "," + ACTIVITY + "," + ORDER + "," + MERCHANT + "," + PRINCIPAL + ",NOW())",
                "INSERT INTO coop_complaint(id,topic_id,registration_id,reporter_member_id,status,del_flag) VALUES (9301," + TOPIC + ",76,7001,0,0)");

        activityService.executeActivitySettlement();
        assertMoney("8.00", balance(MERCHANT));      // 只有 74
        assertMoney("105.49", balance(PRINCIPAL));   // 117.00 − 3.51 平台 − 8.00
        assertMoney("3.51", balance(PLATFORM));

        execute("UPDATE coop_complaint SET status=3 WHERE id=9301");
        activityService.executeActivitySettlement();
        assertMoney("16.00", balance(MERCHANT));     // 补结 76:+8
        assertMoney("135.32", balance(PRINCIPAL));   // + 37.83 − 8.00
        assertMoney("4.68", balance(PLATFORM));
        assertMoney("156.00", balance(MERCHANT).add(balance(PRINCIPAL)).add(balance(PLATFORM)));

        activityService.executeActivitySettlement();
        assertMoney("16.00", balance(MERCHANT));
    }

    /** 商家费超过主理人份额:拒结本场,一分不动,下轮仍拒(告警由组件发)。 */
    @Test
    void feeAbovePrincipalShareRefusesTheWholeSessionAndRollsBack() throws Exception {
        seedSession(ACTIVITY, 15);
        for (long reg = 74; reg <= 75; reg++) { seedPaid(reg, ACTIVITY); leadCheckin(reg); }
        execute("UPDATE coop_order SET fixed_fee=45.00, terms_snapshot='{\"shareMode\":2,\"fixedFee\":45.00,\"compensationMode\":\"FIXED_PER_FULFILLMENT\"}'",
                "INSERT INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time)"
                        + " VALUES (" + TOPIC + "," + ACTIVITY + "," + ORDER + "," + MERCHANT + "," + PRINCIPAL + ",NOW())");

        IllegalStateException refused = assertThrows(IllegalStateException.class, activityService::executeActivitySettlement);
        assertTrue(refused.getMessage().contains("MERCHANT_FEE_EXCEEDS_PRINCIPAL_SHARE"), refused.getMessage());
        assertEquals(0, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=" + ACTIVITY), "拒结要连领取一起回滚");
        assertMoney("0.00", balance(MERCHANT).add(balance(PRINCIPAL)).add(balance(PLATFORM)));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE receiver_type='coop_merchant'"));
        assertMoney("37.83", money("SELECT settlement_amount FROM settlement_detail WHERE source_id=74 AND receiver_type='merchant'"));
    }

    /**
     * 主题级:在世俱乐部场次的票与退款不进主题现金基数、不挡原单关账;已删壳与非俱乐部活动照旧进(仍拒结)。
     */
    @Test
    void liveClubSessionCashStaysOutOfTheThemeBasisButOtherShellsStayIn() throws Exception {
        seedSession(ACTIVITY, 15);
        seedPaid(74L, ACTIVITY);
        execute("INSERT INTO cms_activity(id,member_id,name,status,del_flag,is_settlement,topic_id,club_id) VALUES"
                        + " (10," + PRINCIPAL + ",'已删场次',1,2,0," + TOPIC + "," + CLUB + "),(11," + PRINCIPAL + ",'个人场',1,0,0," + TOPIC + ",NULL)",
                "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status)"
                        + " VALUES (80,'R80',2,10,7001,39.00,2,2,1),(81,'R81',2,11,7002,39.00,2,2,1)",
                "INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag) VALUES"
                        + " (701,'registration',74,39.00,19.50,1,0),(702,'registration',80,39.00,39.00,1,0),(703,'registration',81,39.00,39.00,1,0)");

        List<Long> ids = tx.execute(s -> {
            List<Long> out = new java.util.ArrayList<>();
            evidenceMapper.selectTopicNonClubSessionCashRowsForUpdate(TOPIC).forEach(r -> out.add(r.getId()));
            return out;
        });
        assertEquals(List.of(80L, 81L), ids);
        assertEquals(List.of(ACTIVITY), evidenceMapper.selectLiveClubSessionIdsByTopicId(TOPIC));
        List<Long> blocking = tx.execute(s -> originalMapper.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(TOPIC));
        assertEquals(List.of(702L, 703L), blocking, "在世俱乐部场次的退款归场次结算,不挡主题原单关账");
    }

    /**
     * 第十二轮 k11(2026-11-05 业主:结算后退款,平台也按比例退回它那份)。k5 表里的 ¥39 退款,
     * 走真实 RefundServiceImpl 冲销:余额 平台 −1.17、主理人 −29.83、商家 −8.00,合计 −39.00;
     * 重试不重复扣;俱乐部结算行读到的平台服务费与平台余额一致。
     */
    @Test
    void refundAfterSettlementDebitsPlatformPrincipalAndMerchantBalances() throws Exception {
        // k5 表 S1:四人 ¥39,程一苇集合前退一半(¥19.50),三人计核销费 ¥8
        seedSession(ACTIVITY, 15);
        for (long reg = 74; reg <= 77; reg++) seedPaid(reg, ACTIVITY);
        leadCheckin(74L);
        ticketVerified(75L);
        nodeProgress(76L);
        leadCheckin(77L);
        execute("UPDATE cms_registration SET payment_status=4, registration_status=3 WHERE id=77",
                "INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                        + " VALUES (601,'registration',77,39.00,19.50,1,0)",
                "UPDATE settlement_detail SET status=2 WHERE source_id=77",
                "INSERT INTO coop_group_verify(topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id,verify_time)"
                        + " VALUES (" + TOPIC + "," + ACTIVITY + "," + ORDER + "," + MERCHANT + "," + PRINCIPAL + ",NOW())");
        assertEquals(1, activityService.executeActivitySettlement());
        assertMoney("4.10", balance(PLATFORM));
        assertMoney("108.40", balance(PRINCIPAL));
        assertMoney("24.00", balance(MERCHANT));

        execute("INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                + " VALUES (602,'registration',76,39.00,39.00,1,0)");
        clawBack(602L, 76L, "39.00", "39.00");

        assertMoney("2.93", balance(PLATFORM));    // 4.10 − 1.17
        assertMoney("78.57", balance(PRINCIPAL));  // 108.40 − 29.83
        assertMoney("16.00", balance(MERCHANT));   // 24.00 − 8.00
        assertMoney("-1.17", refundDebit(PLATFORM, 602L));
        assertMoney("-29.83", refundDebit(PRINCIPAL, 602L));
        assertMoney("-8.00", refundDebit(MERCHANT, 602L));
        assertMoney("-39.00", money("SELECT SUM(change_balance) FROM ums_member_balance_detail WHERE event_type=6 AND event_id=602"));
        assertMoney("-39.00", money("SELECT SUM(settlement_amount) FROM settlement_detail WHERE source_type='refund' AND source_id=602"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt"), "余额够,不记欠款");

        // 重试:同一退款再冲一次,不重复扣
        clawBack(602L, 76L, "39.00", "39.00");
        assertMoney("2.93", balance(PLATFORM));
        assertMoney("78.57", balance(PRINCIPAL));
        assertMoney("16.00", balance(MERCHANT));
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=6 AND event_id=602"));

        // 俱乐部结算行 = 余额:平台服务费 2.93,到账 78.57,商家核销费 16.00(销售额 97.50)
        ClubSessionSettlementRow row = coopSettlementMapper.selectClubSessionSettlements(CLUB).get(0);
        assertMoney("2.93", row.getPlatformAmount());
        assertMoney("78.57", row.getNetAmount());
        assertMoney("16.00", row.getMerchantFeeAmount());
    }

    /**
     * 第十二轮 k11:没有商家费的俱乐部场次,平台余额已被提走一部分时,扣能扣的、差额记平台欠款(同一欠款规则),
     * 告警;重试不重复记;平台下一笔分润先抵这笔欠款。
     */
    @Test
    void platformShortBalanceRecordsDebtAndNextPlatformShareOffsetsIt() throws Exception {
        seedSession(ACTIVITY, 15);
        seedPaid(74L, ACTIVITY);
        seedPaid(75L, ACTIVITY);
        assertEquals(1, activityService.executeActivitySettlement());
        assertMoney("2.34", balance(PLATFORM));
        assertMoney("75.66", balance(PRINCIPAL));
        assertMoney("0.00", balance(MERCHANT));

        execute("UPDATE ums_member SET balance=0.50 WHERE id=" + PLATFORM); // 平台账户已提走 1.84
        execute("INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                + " VALUES (610,'registration',74,39.00,39.00,1,0)");
        clawBack(610L, 74L, "39.00", "39.00");
        assertMoney("0.00", balance(PLATFORM));
        assertMoney("37.83", balance(PRINCIPAL));
        assertMoney("-0.50", refundDebit(PLATFORM, 610L));
        assertMoney("0.67", money("SELECT amount FROM ums_member_settlement_debt WHERE member_id=" + PLATFORM
                + " AND source_type='refund:platform' AND source_id=610"));
        verify(refundAlarms, atLeastOnce()).alarm(eq("退款冲销扣款不足"), anyString());

        clawBack(610L, 74L, "39.00", "39.00");
        assertMoney("0.00", balance(PLATFORM));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt WHERE member_id=" + PLATFORM));

        // 下一场结算:平台分润 1.17 先抵 0.67 欠款
        seedSession(10L, 15);
        seedPaid(80L, 10L);
        assertEquals(1, activityService.executeActivitySettlement());
        assertMoney("0.50", balance(PLATFORM));
        assertMoney("0.67", money("SELECT repaid_amount FROM ums_member_settlement_debt WHERE member_id=" + PLATFORM
                + " AND source_id=610"));
    }

    private void clawBack(long refundId, long registrationId, String paid, String refunded) throws Exception {
        RefundApplication refund = new RefundApplication();
        refund.setId(refundId);
        refund.setSourceType("registration");
        refund.setSourceId(registrationId);
        refund.setPaidAmount(new BigDecimal(paid));
        refund.setRefundAmount(new BigDecimal(refunded));
        Method m = com.chengyinhub.business.service.impl.RefundServiceImpl.class.getDeclaredMethod("clawBackSettledShares",
                RefundApplication.class, com.chengyinhub.business.domain.CmsRegistration.class);
        m.setAccessible(true);
        tx.executeWithoutResult(status -> {
            try {
                m.invoke(refundService, refund, registrationMapper.selectCmsRegistrationByIdForUpdate(registrationId));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
            }
        });
    }

    private BigDecimal refundDebit(long memberId, long refundId) throws Exception {
        return money("SELECT COALESCE(SUM(change_balance),0) FROM ums_member_balance_detail WHERE member_id=" + memberId
                + " AND event_type=6 AND event_id=" + refundId);
    }

    private static BigDecimal[][] allocateClawback(List<SettlementDetail> details, RefundApplication refund) throws Exception {
        Method m = com.chengyinhub.business.service.impl.RefundServiceImpl.class.getDeclaredMethod("allocateClawback",
                List.class, RefundApplication.class, BigDecimal.class);
        m.setAccessible(true);
        return (BigDecimal[][]) m.invoke(null, details, refund, new BigDecimal("39.00"));
    }

    private void seedSession(long id, int endedDaysAgo) throws Exception {
        execute("INSERT INTO cms_activity(id,member_id,name,status,del_flag,is_settlement,start_date,end_date,topic_id,club_id) VALUES ("
                + id + "," + PRINCIPAL + ",'外滩钟声 · 周六场',1,0,0,DATE_SUB(NOW(),INTERVAL " + (endedDaysAgo + 1) + " DAY),"
                + "DATE_SUB(NOW(),INTERVAL " + endedDaysAgo + " DAY)," + TOPIC + "," + CLUB + ")");
    }

    /** 已付 ¥39、已参加;并照 SettlementEntryServiceImpl 支付时落的两条分账行(主理人 37.83 / 平台 1.17)。 */
    private void seedPaid(long id, long activityId) throws Exception {
        execute("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,payable_amount,payment_status,registration_status,"
                        + "verification_status,order_num,ticket_name,activity_title) VALUES ("
                        + id + ",'R" + id + "',2," + activityId + "," + (7000 + id) + ",39.00,2,2,0,1,'领队场','外滩钟声 · 周六场')",
                "INSERT INTO settlement_detail(batch_id,merchant_id,source_type,source_id,receiver_type,receiver_id,gross_amount,commission_amount,"
                        + "settlement_amount,status,idempotency_key,create_time) VALUES"
                        + " (0," + PRINCIPAL + ",'registration'," + id + ",'merchant'," + PRINCIPAL + ",37.83,0,37.83,0,'registration:" + id + ":merchant:" + PRINCIPAL + "',NOW()),"
                        + " (0," + PRINCIPAL + ",'registration'," + id + ",'platform',0,1.17,0,1.17,0,'registration:" + id + ":platform:0',NOW())");
    }

    /** C-10 到场的三种来源:领队签到 / 票被核销 / 节点进度;以及到场更正(最新一条优先)。 */
    private void leadCheckin(long registrationId) throws Exception {
        execute("INSERT INTO club_lead_checkin(activity_id,member_id,arrived_at) VALUES (" + ACTIVITY + "," + (7000 + registrationId) + ",NOW())");
    }

    private void ticketVerified(long registrationId) throws Exception {
        execute("UPDATE cms_registration SET verification_status=1, verification_time=NOW() WHERE id=" + registrationId);
    }

    private void nodeProgress(long registrationId) throws Exception {
        execute("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag) VALUES ("
                + (7000 + registrationId) + "," + ACTIVITY + "," + TOPIC + ",71,1,NOW(),0)");
    }

    private void correctedNotArrived(long registrationId) throws Exception {
        execute("INSERT INTO club_event_attendance_correction(club_id,activity_id,member_id,original_arrived,new_arrived,reason,"
                + "actor_member_id,request_id,version,corrected_at) VALUES (" + CLUB + "," + ACTIVITY + "," + (7000 + registrationId)
                + ",1,0,'误签到'," + PRINCIPAL + ",'k5-corr-" + registrationId + "',1,NOW())");
    }

    private BigDecimal balance(long memberId) {
        return umsMemberMapper.selectUmsMemberById(memberId).getBalance();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void clubOwnedFreeExploreSettlesTwoTwentyNineYuanTicketsAndRefundsToTheFen() throws Exception {
        seedSession(ACTIVITY, 13);
        seedPaid(74L, ACTIVITY);
        seedPaid(75L, ACTIVITY);
        topic.setProductType(2);
        // ③ 探索票支付时不落分账行(SettlementEntryServiceImpl.recordOnPayment 跳过 EXPLORE_PASS);
        // 第十三轮 m6 起由场次结算补记,结算后退款才有行可冲。
        execute("UPDATE cms_topic SET product_type=2 WHERE id=" + TOPIC,
                "UPDATE cms_registration SET payable_amount=29.00, purchase_kind=3, fee_platform_rate=0.0300 WHERE id IN (74,75)",
                "DELETE FROM settlement_detail WHERE source_id IN (74,75)");
        var canonical = TopicSaleCanonicalTestFixture.canonical();
        canonical.getCompletionPolicy().put("evidenceRule", "ALL_REQUIRED_STAMPS");
        var snapshot = new com.chengyinhub.business.domain.TopicSaleSnapshot();
        snapshot.setTopicId(TOPIC);
        snapshot.setSchemaVersion(com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION);
        snapshot.setParameterVersion(1L);
        snapshot.setSourceReviewTaskId(1L);
        snapshot.setFrozenAt(new java.util.Date());
        snapshot.setCanonicalJson(com.chengyinhub.business.service.support.TopicSaleCanonicalCodec.write(canonical, 2));
        SqlSessionTemplate sql = new SqlSessionTemplate(buildFactory(dataSource));
        sql.getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class).insertSnapshot(snapshot);

        var pending = sql.getMapper(ActivitySettlementHoldMapper.class).selectUnsettledActivityFundsByMember(PRINCIPAL);
        assertEquals(1, pending.size());
        assertMoney("58.00", pending.get(0).getNormalSales());
        assertEquals(0, activityService.executeActivitySettlement(), "结束后未满14天不能入余额");
        assertMoney("0.00", balance(PLATFORM).add(balance(PRINCIPAL)));

        execute("UPDATE cms_activity SET start_date=DATE_SUB(NOW(),INTERVAL 16 DAY),end_date=DATE_SUB(NOW(),INTERVAL 15 DAY) WHERE id=" + ACTIVITY);
        assertEquals(1, activityService.executeActivitySettlement());
        assertMoney("1.74", balance(PLATFORM));
        assertMoney("56.26", balance(PRINCIPAL));
        assertMoney("0.00", balance(MERCHANT));
        assertEquals(0, activityService.executeActivitySettlement());
        assertMoney("58.00", balance(PLATFORM).add(balance(PRINCIPAL)));
        assertEquals(4, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE source_id IN (74,75) AND status=1"
                + " AND ((receiver_type='merchant' AND receiver_id=" + PRINCIPAL + " AND settlement_amount=28.13)"
                + " OR (receiver_type='platform' AND receiver_id=0 AND settlement_amount=0.87))"), "结算时补记、重跑不重复");

        RefundApplication refund = new RefundApplication();
        refund.setId(603L);
        refund.setSourceType("registration");
        refund.setSourceId(75L);
        refund.setPaidAmount(new BigDecimal("29.00"));
        refund.setRefundAmount(new BigDecimal("29.00"));
        Method clawback = refundService.getClass().getDeclaredMethod("clawBackSettledShares", RefundApplication.class,
                com.chengyinhub.business.domain.CmsRegistration.class);
        clawback.setAccessible(true);
        tx.executeWithoutResult(s -> {
            try {
                clawback.invoke(refundService, refund, registrationMapper.selectCmsRegistrationById(75L));
                clawback.invoke(refundService, refund, registrationMapper.selectCmsRegistrationById(75L));
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertMoney("0.87", balance(PLATFORM));
        assertMoney("28.13", balance(PRINCIPAL));
        assertMoney("-29.00", money("SELECT SUM(change_balance) FROM ums_member_balance_detail WHERE event_type=6 AND event_id=603"));

        // 四店合同及缺快照不进入普通场次选单，不能抢它的专用 FINAL 结算。
        execute("UPDATE cms_activity SET is_settlement=0 WHERE id=" + ACTIVITY,
                "UPDATE topic_sale_snapshot SET canonical_json=JSON_SET(canonical_json,'$.completionPolicy.evidenceRule','FOUR_STORE_REDEEM')");
        assertTrue(sql.getMapper(CmsActivityMapper.class).selectActivitiesForSettlement().isEmpty());
        execute("DELETE FROM topic_sale_snapshot");
        assertTrue(sql.getMapper(CmsActivityMapper.class).selectActivitiesForSettlement().isEmpty());
    }

    /**
     * 第十三轮 m6:到了结算时点却没有场次结算路径的自由探索实收(四店探店日 ¥69 未做期级 FINAL / 别家俱乐部开的场次),
     * 不再是「没有需要结算的活动」静默收工:任务抛错点名场次、实收与原因(Quartz 记 FAIL 并告警),一分不动、不置已结算,
     * 下轮仍告警。没满 14 天、全额退款后没有实收、四店已做 FINAL 的,都不告警;同一轮里可结的俱乐部自有场次照常结。
     */
    @Test
    void freeExploreSalesWithNoSettlementPathAlarmInsteadOfSittingSilent() throws Exception {
        long other = ACTIVITY + 1;
        seedSession(ACTIVITY, 13);
        seedPaid(74L, ACTIVITY);
        topic.setProductType(2);
        execute("UPDATE cms_topic SET product_type=2 WHERE id=" + TOPIC,
                "UPDATE cms_registration SET payable_amount=69.00, purchase_kind=3, fee_platform_rate=0.0300 WHERE id=74",
                "DELETE FROM settlement_detail WHERE source_id=74");
        var canonical = TopicSaleCanonicalTestFixture.canonical();
        canonical.getCompletionPolicy().put("evidenceRule", "FOUR_STORE_REDEEM");
        var snapshot = new com.chengyinhub.business.domain.TopicSaleSnapshot();
        snapshot.setTopicId(TOPIC);
        snapshot.setSchemaVersion(com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION);
        snapshot.setParameterVersion(1L);
        snapshot.setSourceReviewTaskId(1L);
        snapshot.setFrozenAt(new java.util.Date());
        snapshot.setCanonicalJson(com.chengyinhub.business.service.support.TopicSaleCanonicalCodec.write(canonical, 2));
        SqlSessionTemplate sql = new SqlSessionTemplate(buildFactory(dataSource));
        sql.getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class).insertSnapshot(snapshot);
        com.chengyinhub.business.domain.TopicSaleSnapshot frozen =
                sql.getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class).selectSnapshotByTopicId(TOPIC);
        assertNotNull(frozen);

        assertEquals(0, activityService.executeActivitySettlement(), "13 天:还没到结算时点,不告警");

        execute("UPDATE cms_activity SET start_date=DATE_SUB(NOW(),INTERVAL 16 DAY),end_date=DATE_SUB(NOW(),INTERVAL 15 DAY) WHERE id=" + ACTIVITY);
        IllegalStateException fourStore = assertThrows(IllegalStateException.class, () -> activityService.executeActivitySettlement());
        assertTrue(fourStore.getMessage().contains("activityId=" + ACTIVITY + ": FREE_EXPLORE_SALE_HAS_NO_SETTLEMENT_PATH"), fourStore.getMessage());
        assertTrue(fourStore.getMessage().contains("实收=69.00"), fourStore.getMessage());
        assertTrue(fourStore.getMessage().contains("FOUR_STORE_REDEEM"), fourStore.getMessage());
        assertEquals(0, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=" + ACTIVITY));
        assertMoney("0.00", balance(PLATFORM).add(balance(PRINCIPAL)).add(balance(MERCHANT)));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail"));
        assertThrows(IllegalStateException.class, () -> activityService.executeActivitySettlement(), "下轮仍告警");

        // 四店已做期级 FINAL:钱归 /business/edition-final 那条线,结算任务不再告警。
        execute("INSERT INTO topic_edition_finalization(topic_id) VALUES (" + TOPIC + ")");
        assertEquals(0, activityService.executeActivitySettlement());
        execute("DELETE FROM topic_edition_finalization");

        // 普通自由探索、别家俱乐部开的场次:代码里没有分钱规则 ⇒ 告警点名两边俱乐部;同一轮自有场次照常结。
        execute("UPDATE topic_sale_snapshot SET canonical_json=JSON_SET(canonical_json,'$.completionPolicy.evidenceRule','ALL_REQUIRED_STAMPS')",
                "UPDATE cms_activity SET club_id=" + (CLUB + 1) + " WHERE id=" + ACTIVITY);
        seedSession(other, 15);
        seedPaid(76L, other);
        execute("UPDATE cms_registration SET payable_amount=29.00, purchase_kind=3, fee_platform_rate=0.0300 WHERE id=76",
                "DELETE FROM settlement_detail WHERE source_id=76");
        IllegalStateException crossClub = assertThrows(IllegalStateException.class, () -> activityService.executeActivitySettlement());
        assertTrue(crossClub.getMessage().contains("成功 1 个"), crossClub.getMessage());
        assertTrue(crossClub.getMessage().contains("场次俱乐部 " + (CLUB + 1) + " 不是主题俱乐部 " + CLUB), crossClub.getMessage());
        assertTrue(crossClub.getMessage().contains("实收=69.00"), crossClub.getMessage());
        assertEquals(0, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=" + ACTIVITY));
        assertMoney("0.87", balance(PLATFORM));
        assertMoney("28.13", balance(PRINCIPAL));

        // 全额退款后这场没有实收:不再告警。
        execute("UPDATE cms_registration SET payment_status=4, registration_status=3 WHERE id=74",
                "INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                        + " VALUES (604,'registration',74,69.00,69.00,1,0)");
        assertEquals(0, activityService.executeActivitySettlement());
        assertMoney("29.00", balance(PLATFORM).add(balance(PRINCIPAL)));
    }

    @Test
    void explorationFeesCountEachShopScannedTicketOnceWithoutGroupCodes() throws Exception {
        seedSession(ACTIVITY, 15);
        for (long id = 74; id <= 76; id++) seedPaid(id, ACTIVITY);
        freezeOrdinaryExplore(ordinaryExplore());
        execute("INSERT INTO ums_member(id,balance,status,del_flag) VALUES (9005,0,1,0)",
                "INSERT INTO coop_order(id,topic_id,from_id,to_id,to_type,share_mode,fixed_fee,terms_version,terms_snapshot,status,del_flag,create_time)"
                        + " SELECT 2,topic_id,from_id,9005,to_type,2,5.00,terms_version,'{\"shareMode\":2,\"fixedFee\":5.00}',0,0,NOW() FROM coop_order WHERE id=1");
        exploreScan(74, MERCHANT, 100); exploreScan(74, MERCHANT, 101);
        exploreScan(75, MERCHANT, 100); exploreScan(75, 9005, 102);
        // Attendance alone is not a shop visit. A group code is not required.
        leadCheckin(76);
        assertMoney("0.00", balance(MERCHANT));
        var estimates = new SqlSessionTemplate(buildFactory(dataSource)).getMapper(SettlementDetailMapper.class)
                .selectUnsettledFeeEstimates(MERCHANT, null);
        assertMoney("16.00", estimates.get(0).getAmount());
        assertEquals(1, activityService.executeActivitySettlement());
        assertMoney("16.00", balance(MERCHANT)); assertMoney("5.00", balance(9005L));
        assertMoney("3.51", balance(PLATFORM)); assertMoney("92.49", balance(PRINCIPAL));
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE receiver_type='coop_merchant'"));
        assertEquals(0, activityService.executeActivitySettlement());
        assertMoney("117.00", balance(PRINCIPAL).add(balance(PLATFORM)).add(balance(MERCHANT)).add(balance(9005L)));
    }

    @Test
    void frozenExploreOfferFeeCountsUniqueShopHeadsAndRefusesExcessOrOfflineLiability() throws Exception {
        seedSession(ACTIVITY, 15); seedPaid(74L, ACTIVITY);
        execute("UPDATE coop_order SET status=2");
        var canonical = ordinaryExplore();
        var offers = canonical.getMerchantCashSettlementMode().withArray("offers");
        offers.removeAll();
        for (int i = 0; i < 2; i++) offers.addObject().put("id", 400 + i).put("chapterId", 100 + i)
                .put("merchantId", MERCHANT).put("payerClubId", CLUB).put("termsMode", "REVSHARE").put("paymentRoute", "SESSION_SETTLEMENT").put("perHeadFee", "50.00");
        canonical.getMicroExperienceCatalog().set("offers", offers.deepCopy());
        canonical.setStoreCount(1);
        for (var store : canonical.getStoreCluster()) ((com.fasterxml.jackson.databind.node.ObjectNode) store).put("merchantMemberId", MERCHANT);
        freezeOrdinaryExplore(canonical);
        exploreScan(74, MERCHANT, 100); exploreScan(74, MERCHANT, 101);
        assertThrows(RuntimeException.class, () -> activityService.executeActivitySettlement());
        assertMoney("0.00", balance(PRINCIPAL).add(balance(PLATFORM)).add(balance(MERCHANT)));
        assertEquals(0, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=" + ACTIVITY));
        execute("UPDATE topic_sale_snapshot SET canonical_json=JSON_SET(canonical_json,"
                + "'$.merchantCashSettlementMode.offers[0].perHeadFee','8.00','$.merchantCashSettlementMode.offers[1].perHeadFee','8.00',"
                + "'$.microExperienceCatalog.offers[0].perHeadFee','8.00','$.microExperienceCatalog.offers[1].perHeadFee','8.00')",
                "INSERT INTO club_direct_merchant_receivable(redemption_id,status) SELECT MIN(id),'OPEN' FROM redemption");
        assertThrows(RuntimeException.class, () -> activityService.executeActivitySettlement());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE receiver_type='coop_merchant'"));
        // Only the isolated fixture's hypothetical debt is removed; no production migration or cleanup exists.
        execute("DELETE FROM club_direct_merchant_receivable");
        assertEquals(1, activityService.executeActivitySettlement());
        assertMoney("8.00", balance(MERCHANT)); assertMoney("29.83", balance(PRINCIPAL));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE receiver_type='coop_merchant'"));
    }

    @Test
    void verifiedManualPartialExploreRefundDebitsOriginalSharesAndRecordsShortfallOnce() throws Exception {
        seedSession(ACTIVITY, 15); seedPaid(74L, ACTIVITY);
        freezeOrdinaryExplore(ordinaryExplore()); exploreScan(74, MERCHANT, 100);
        assertEquals(1, activityService.executeActivitySettlement());
        execute("UPDATE ums_member SET balance=0.50 WHERE id=" + PRINCIPAL,
                "UPDATE ums_member SET balance=0.10 WHERE id=" + PLATFORM,
                "UPDATE ums_member SET balance=0.20 WHERE id=" + MERCHANT);
        var manual = verifiedManualCase(1950L);
        tx.executeWithoutResult(s -> refundService.recordVerifiedExploreManualRefund(manual));
        assertMoney("0.00", balance(PRINCIPAL).add(balance(PLATFORM)).add(balance(MERCHANT)));
        assertMoney("18.70", money("SELECT SUM(amount) FROM ums_member_settlement_debt"));
        assertMoney("-19.50", money("SELECT SUM(settlement_amount) FROM settlement_detail WHERE source_type='refund'"));
        assertMoney("14.41", money("SELECT amount FROM ums_member_settlement_debt WHERE member_id=" + PRINCIPAL));
        assertMoney("0.49", money("SELECT amount FROM ums_member_settlement_debt WHERE member_id=" + PLATFORM));
        assertMoney("3.80", money("SELECT amount FROM ums_member_settlement_debt WHERE member_id=" + MERCHANT));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM refund_application WHERE status=1 AND payout_status=4"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM refund_application WHERE payout_status IN (0,5,6)"));
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> refundService.recordVerifiedExploreManualRefund(manual)));
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM refund_application"));
        assertEquals(0, activityService.executeActivitySettlement());
    }

    @Test
    void manualRemainderAfterEarlierHalfRefundClawsOnlyTheOriginalRemainingFen() throws Exception {
        seedSession(ACTIVITY, 15); seedPaid(74L, ACTIVITY);
        freezeOrdinaryExplore(ordinaryExplore()); exploreScan(74, MERCHANT, 100);
        assertEquals(1, activityService.executeActivitySettlement());
        execute("INSERT INTO refund_application(id,source_type,source_id,paid_amount,refund_amount,status,del_flag)"
                + " VALUES (610,'registration',74,39.00,19.50,1,0)");
        clawBack(610L, 74L, "39.00", "19.50");
        assertMoney("0.58", balance(PLATFORM)); assertMoney("14.92", balance(PRINCIPAL)); assertMoney("4.00", balance(MERCHANT));
        tx.executeWithoutResult(s -> refundService.recordVerifiedExploreManualRefund(verifiedManualCase(1950L)));
        assertMoney("0.00", balance(PRINCIPAL).add(balance(PLATFORM)).add(balance(MERCHANT)));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt"), "两次退款不能因舍入多扣一分");
        assertMoney("-39.00", money("SELECT SUM(settlement_amount) FROM settlement_detail WHERE source_type='refund'"));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM refund_application"));
    }

    @Test
    void manualRefundOfUnpaidComplaintHeldExploreTicketNeverDebitsAnotherTicketsIncome() throws Exception {
        for (boolean anotherTicketWasPaid : new boolean[]{false, true}) {
            resetSchema();
            seedSession(ACTIVITY, 15); seedPaid(74L, ACTIVITY);
            if (anotherTicketWasPaid) seedPaid(75L, ACTIVITY);
            freezeOrdinaryExplore(ordinaryExplore()); exploreScan(74, MERCHANT, 100);
            execute("INSERT INTO coop_complaint(id,topic_id,registration_id,reporter_member_id,status,del_flag) VALUES (9301,"
                    + TOPIC + ",74,7001,0,0)");
            assertEquals(1, feeMapper.countInAppExploreRedemption((long) scalarInt("SELECT MIN(id) FROM redemption")),
                    "真实扫码路由必须阻止另生线下应收");
            assertEquals(1, activityService.executeActivitySettlement());
            assertEquals(1, scalarInt("SELECT is_settlement FROM cms_activity WHERE id=" + ACTIVITY));
            String received = anotherTicketWasPaid ? "39.00" : "0.00";
            assertMoney(received, balance(PRINCIPAL).add(balance(PLATFORM)).add(balance(MERCHANT)));
            tx.executeWithoutResult(s1 -> refundService.recordVerifiedExploreManualRefund(verifiedManualCase(3900L)));
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM refund_application WHERE status=1 AND payout_status=4"));
            assertEquals(2, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE source_type='registration' AND source_id=74 AND status=2"));
            assertEquals(0, scalarInt("SELECT COUNT(*) FROM settlement_detail WHERE source_type='refund'"));
            assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=6"));
            assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt"));
            execute("UPDATE coop_complaint SET status=3 WHERE id=9301");
            activityService.executeActivitySettlement();
            assertMoney(received, balance(PRINCIPAL).add(balance(PLATFORM)).add(balance(MERCHANT)));
        }
    }

    @Test
    void manualRefundRefusesGuessingPrincipalAfterHeldTicketReleasedToANewOwner() throws Exception {
        seedSession(ACTIVITY, 15); seedPaid(74L, ACTIVITY); seedPaid(75L, ACTIVITY);
        freezeOrdinaryExplore(ordinaryExplore()); exploreScan(74, MERCHANT, 100); exploreScan(75, MERCHANT, 100);
        execute("INSERT INTO ums_member(id,status,del_flag,balance) VALUES (991005,1,0,0)",
                "INSERT INTO coop_complaint(id,topic_id,registration_id,reporter_member_id,status,del_flag) VALUES (9301,"
                        + TOPIC + ",75,7001,0,0)");
        assertEquals(1, activityService.executeActivitySettlement());
        var payee = ReflectionTestUtils.getField(activityService, "clubPrincipalPayee");
        var clubs = (ClubMapper) ReflectionTestUtils.getField(payee, "clubMapper");
        var club = clubs.selectClubById(CLUB);
        try {
            club.setMemberId(991005L);
            execute("UPDATE coop_complaint SET status=3 WHERE id=9301");
            activityService.executeActivitySettlement();
            assertMoney("29.83", balance(PRINCIPAL)); assertMoney("29.83", balance(991005L));
            assertEquals(PRINCIPAL, feeMapper.selectPayeeLinesForUpdate(75L).get(0).getReceiverId(),
                    "原生产补结只改status，没有重写按旧主理人冻结的receiver_id");
            var manual = verifiedManualCase(3900L);
            manual.setParentOrderNo("REG:75"); manual.setPayerRegistrationId(75L); manual.setPayerMemberId(7075L);
            manual.setVerifiedRegistrationIdsJson("[75]");
            assertTrue(assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(
                    s1 -> refundService.recordVerifiedExploreManualRefund(manual))).getMessage().contains("主理人原实收人"));
            assertEquals(0, scalarInt("SELECT COUNT(*) FROM refund_application"));
            assertEquals(0, scalarInt("SELECT COUNT(*) FROM ums_member_settlement_debt"));
            assertMoney("29.83", balance(PRINCIPAL)); assertMoney("29.83", balance(991005L));
            assertMoney("16.00", balance(MERCHANT)); assertMoney("2.34", balance(PLATFORM));
        } finally {
            club.setMemberId(PRINCIPAL);
        }
    }

    @Test
    void manualExploreRefundMissingProofOriginalLedgerOrRemainingCashCannotWriteAnything() throws Exception {
        seedSession(ACTIVITY, 15); seedPaid(74L, ACTIVITY);
        freezeOrdinaryExplore(ordinaryExplore());
        assertEquals(1, activityService.executeActivitySettlement());
        var manual = verifiedManualCase(3900L);
        manual.setEvidenceSha256(null);
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> refundService.recordVerifiedExploreManualRefund(manual)));
        manual.setEvidenceSha256("a".repeat(64)); manual.setActualRefundAmountFen(3901L);
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> refundService.recordVerifiedExploreManualRefund(manual)));
        manual.setActualRefundAmountFen(3900L);
        execute("DELETE FROM ums_member_balance_detail WHERE event_type=7");
        assertTrue(assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(
                s1 -> refundService.recordVerifiedExploreManualRefund(manual))).getMessage().contains("平台原实收人"),
                "真正已放款的报名仍必须有原平台实收凭证");
        execute("DELETE FROM settlement_detail WHERE receiver_type='platform'");
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(s -> refundService.recordVerifiedExploreManualRefund(manual)));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM refund_application"));
        assertMoney("39.00", balance(PRINCIPAL).add(balance(PLATFORM)));
    }

    private com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1 ordinaryExplore() {
        var canonical = TopicSaleCanonicalTestFixture.canonical();
        canonical.getCompletionPolicy().put("evidenceRule", "ALL_REQUIRED_STAMPS");
        return canonical;
    }

    private void freezeOrdinaryExplore(com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1 canonical) throws Exception {
        topic.setProductType(2);
        execute("UPDATE cms_topic SET product_type=2 WHERE id=" + TOPIC,
                "UPDATE cms_registration SET purchase_kind=3, fee_platform_rate=0.0300, wechat_payment_amount=payable_amount WHERE owner_id=" + ACTIVITY,
                "DELETE FROM settlement_detail");
        var snapshot = new com.chengyinhub.business.domain.TopicSaleSnapshot();
        snapshot.setTopicId(TOPIC); snapshot.setSchemaVersion(com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION);
        snapshot.setParameterVersion(1L); snapshot.setSourceReviewTaskId(1L); snapshot.setFrozenAt(new java.util.Date());
        snapshot.setCanonicalJson(com.chengyinhub.business.service.support.TopicSaleCanonicalCodec.write(canonical, 2));
        new SqlSessionTemplate(buildFactory(dataSource)).getMapper(com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class).insertSnapshot(snapshot);
    }

    private void exploreScan(long registrationId, long merchant, long chapter) throws Exception {
        execute("INSERT INTO redemption(registration_id,topic_id,chapter_id,merchant_id,offer_id,status) VALUES ("
                + registrationId + "," + TOPIC + "," + chapter + "," + merchant + "," + (chapter + 300) + ",'ACTIVE')");
    }

    private com.chengyinhub.business.domain.ActivityCancelManualRefundCase verifiedManualCase(long amount) {
        var row = new com.chengyinhub.business.domain.ActivityCancelManualRefundCase();
        row.setId(99L); row.setActivityId(ACTIVITY); row.setParentOrderNo("REG:74"); row.setPayerRegistrationId(74L);
        row.setPayerMemberId(7074L); row.setOwnerMemberId(PRINCIPAL); row.setPaidCashAmountFen(3900L);
        row.setVerifiedRegistrationIdsJson("[74]"); row.setUnverifiedRegistrationIdsJson("[]");
        row.setStatus(com.chengyinhub.business.domain.ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED);
        row.setHandledByUserId(8L); row.setVerifiedByUserId(9L); row.setActualRefundAmountFen(amount);
        row.setEvidenceSha256("a".repeat(64)); row.setManualChannel("offline_transfer");
        row.setEvidenceObjectKey("upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg");
        row.setHandledAt(new java.util.Date()); row.setVerifiedAt(new java.util.Date());
        return row;
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("club-session-fee-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, com.chengyinhub.business.mapper.RefundApplicationMapper.class, "mapper/business/RefundApplicationMapper.xml");
        addMapper(configuration, CmsRegistrationMapper.class, "mapper/business/CmsRegistrationMapper.xml");
        addMapper(configuration, com.chengyinhub.business.mapper.TopicSaleSnapshotMapper.class, "mapper/business/TopicSaleSnapshotMapper.xml");
        addMapper(configuration, CmsActivityMapper.class, "mapper/business/CmsActivityMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        addMapper(configuration, UmsMemberBalanceDetailMapper.class, "mapper/business/UmsMemberBalanceDetailMapper.xml");
        addMapper(configuration, SettlementDetailMapper.class, "mapper/business/SettlementDetailMapper.xml");
        addMapper(configuration, ActivitySettlementHoldMapper.class, "mapper/business/ActivitySettlementHoldMapper.xml");
        addMapper(configuration, UmsMemberSettlementDebtMapper.class, "mapper/business/UmsMemberSettlementDebtMapper.xml");
        addMapper(configuration, CoopOrderMapper.class, "mapper/business/CoopOrderMapper.xml");
        addMapper(configuration, SettlementClaimMapper.class, "mapper/business/SettlementClaimMapper.xml");
        addMapper(configuration, CoopSettlementMapper.class, "mapper/business/CoopSettlementMapper.xml");
        addMapper(configuration, CoopSettlementEvidenceMapper.class, "mapper/business/CoopSettlementEvidenceMapper.xml");
        addMapper(configuration, CoopSettlementOriginalMapper.class, "mapper/business/CoopSettlementOriginalMapper.xml");
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

    private BigDecimal money(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getBigDecimal(1);
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
