package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.MerchantFinancePage;
import com.chengyinhub.business.domain.vo.MerchantRedemptionSummary;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper;
import com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.MemberFundsStageService;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第十轮 f1 第 10 项(check-1104 j1 F13 / F12 / p2 F-2):真实 MySQL + 真实 mapper XML,
 * 一笔售出并核销、一笔集合前 24 小时内取消退 50% 之后,商家各屏读到的数字同源:
 * <ul>
 *   <li>核销与退款 / 核销记录(/finance/redemptions):活动验票出现,本月核销 1 笔;</li>
 *   <li>待结算:台账汇总 == 资产页(/api/wallet/stages)== 结算届时实付
 *       (结算同一条 SQL 取「已付未退 + 50% 留存」,同一公式算商户份额);</li>
 *   <li>营销近 30 天核销含今天的这一笔;</li>
 *   <li>结算落账后:已入账 = 结算概览 = 首页累计收入,待结算归零。</li>
 * </ul>
 * 结算落账这一步按 CmsActivityServiceImpl.settleActivity 的写入(eventType=3 收入流水 +
 * markActivityRegistrationDetailsSettled + is_settlement=1)在库里落,金额来自它同一条 SQL 与同一公式。
 */
class MerchantNumbersAgreeMyBatisTest {
    private static final String SUITE = "merchant_numbers_agree";
    private static final long MERCHANT = 990005L;
    private static final long ACTIVITY = 9L;
    private static DriverManagerDataSource source;
    private static JdbcTemplate db;
    private static SqlSessionTemplate sql;

    @BeforeAll
    static void setup() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host(), "只允许隔离的 loopback 测试库");
        source = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(source);
        assertTrue(db.queryForObject("select version()", String.class).startsWith("8.0."));
        P0AcceptanceSchema.baseTables(db, "ums_member", "ums_member_balance_detail", "cms_activity", "cms_registration",
                "cms_topic", "cms_topic_node", "cms_registration_merchant", "tms_browse_record", "settlement_detail",
                "refund_application", "coop_settlement", "coop_complaint", "redemption", "merchant_payable",
                "merchant_payable_batch");
        // DEC-C 个人分账快照列（migration_20261112_dec_c_payout.sql），Mapper 已读写这些列。
        db.execute("ALTER TABLE coop_settlement ADD COLUMN club_id BIGINT NULL, ADD COLUMN club_name VARCHAR(255) NULL,"
                + " ADD COLUMN payout_hold_reason VARCHAR(128) NULL");
        db.execute("ALTER TABLE settlement_detail ADD COLUMN club_id BIGINT NULL, ADD COLUMN club_name VARCHAR(255) NULL");
        db.execute("CREATE TABLE IF NOT EXISTS topic_sale_snapshot (topic_id BIGINT PRIMARY KEY, schema_version VARCHAR(64), canonical_json LONGTEXT)");
        P0AcceptanceSchema.migrations(source, "migration_complaint_period_funds_gate_20260917.sql");
        sql = P0AcceptanceSchema.sql(source, "RedemptionMapper", "ActivitySettlementHoldMapper", "CmsRegistrationMapper",
                "SettlementDetailMapper", "UmsMemberBalanceDetailMapper", "CoopSettlementMapper", "MerchantPayableMapper",
                "MerchantPayableBatchMapper", "MerchantMarketingAnalyticsMapper");
    }

    @AfterAll
    static void cleanup() {
        if (db != null) P0AcceptanceSchema.drop(db, SUITE);
    }

    @Test
    void oneSaleOneRedemptionOneHalfRefundAgreeAcrossMerchantScreens() {
        PiiCryptoService pii = new PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]), "hmac-secret");
        // 活动:商家自有,三天后开始(未结束 ⇒ 待结算段),3% 平台费。
        db.update("insert into cms_activity(id,member_id,name,status,del_flag,is_settlement,start_date,end_date)"
                + " values(?,?,?,1,0,0,date_add(now(),interval 3 day),date_add(now(),interval 4 day))",
                ACTIVITY, MERCHANT, "梧桐里读书夜");
        // 一笔售出 ¥39 并核销(支付时商家/平台待结算明细 37.83 / 1.17)。
        db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,activity_title,ticket_id,ticket_name,"
                + "ticket_price,member_id,real_name,original_amount,total_amount,payable_amount,pay_expire_time,payment_status,"
                + "registration_status,verification_status,verification_time,create_time,payment_time)"
                + " values(74,'R74',2,?,?,75,?,39.00,9004,?,39.00,39.00,39.00,now(),2,2,1,now(),now(),now())", ACTIVITY, "梧桐里读书夜", "单人票", pii.encrypt("林野"));
        db.update("insert into settlement_detail(batch_id,merchant_id,source_type,source_id,receiver_type,receiver_id,"
                + "gross_amount,commission_amount,settlement_amount,status,idempotency_key,create_time) values"
                + "(0,?,'registration',74,'merchant',?,37.83,0,37.83,0,'registration:74:merchant:990005',now()),"
                + "(0,?,'registration',74,'platform',0,1.17,0,1.17,0,'registration:74:platform:0',now())",
                MERCHANT, MERCHANT, MERCHANT);
        // 一笔售出 ¥39,集合前 24 小时内取消:批准退 19.50,整单待结算明细作废(RefundServiceImpl.voidPendingBySource)。
        db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,activity_title,ticket_id,ticket_name,"
                + "ticket_price,member_id,original_amount,total_amount,payable_amount,pay_expire_time,payment_status,"
                + "registration_status,verification_status,create_time,payment_time)"
                + " values(76,'R76',2,?,?,75,?,39.00,9005,39.00,39.00,39.00,now(),4,3,0,now(),now())", ACTIVITY, "梧桐里读书夜", "单人票");
        db.update("insert into refund_application(refund_no,source_type,source_id,owner_type,owner_id,member_id,merchant_id,"
                + "paid_amount,refund_amount,status,del_flag) values('RF76','registration',76,2,?,9005,?,39.00,19.50,1,0)",
                ACTIVITY, MERCHANT);
        db.update("insert into settlement_detail(batch_id,merchant_id,source_type,source_id,receiver_type,receiver_id,"
                + "gross_amount,commission_amount,settlement_amount,status,fail_reason,idempotency_key,create_time) values"
                + "(0,?,'registration',76,'merchant',?,37.83,0,37.83,2,'退款作废','registration:76:merchant:990005',now()),"
                + "(0,?,'registration',76,'platform',0,1.17,0,1.17,2,'退款作废','registration:76:platform:0',now())",
                MERCHANT, MERCHANT, MERCHANT);

        CmsActivityServiceImpl activityService = activityServiceWithRule();
        MemberFundsStageService stages = stageService(activityService);
        MerchantFinanceQueryService finance = financeService(stages, pii);

        // 结算届时实付:与 settleActivity 同一条 SQL(已付未退 + 50% 留存)与同一公式。
        CmsRegistrationMapper registrations = sql.getMapper(CmsRegistrationMapper.class);
        BigDecimal settlementSales = registrations.selectTotalSalesByActivityId(ACTIVITY)
                .add(registrations.selectRetainedSalesAfterPartialRefundByActivityId(ACTIVITY));
        assertEquals(new BigDecimal("58.50"), settlementSales);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        BigDecimal settlementPays = activityService.estimateMerchantShare(activity, settlementSales);
        assertEquals(new BigDecimal("56.74"), settlementPays, "58.50 - 平台 1.76(1.755 四舍五入)");

        // 资产页「待结算」= 未结束 + 客诉期
        Map<String, Object> funds = stages.stages(MERCHANT, new Date());
        assertEquals(Boolean.TRUE, funds.get("amountsKnown"));
        assertEquals(0, settlementPays.compareTo((BigDecimal) funds.get("pendingSettlement")),
                "资产页待结算必须含 50% 退款后的留存部分,等于结算实付(原先只有 37.83)");

        // 核销与退款 / 核销记录
        MerchantFinancePage<MerchantRedemptionView> page = finance.redemptions(MERCHANT, "all", 1, 20);
        assertEquals(1, page.getTotal(), "活动验票必须出现在核销列表(原先 total 0)");
        MerchantRedemptionView row = page.getRows().get(0);
        assertEquals("activity_ticket", row.getRecordType());
        assertEquals("74", row.getRecordId());
        assertEquals("单人票", row.getChapterName());
        assertEquals("梧桐里读书夜", row.getTopicName());
        assertEquals("林野", row.getCustomerDisplayName());
        assertEquals("37.83", row.getSettlementAmount());
        assertEquals("PENDING_SETTLEMENT", row.getDisplayState());
        MerchantRedemptionSummary summary = (MerchantRedemptionSummary) page.getSummary();
        assertEquals(1, summary.getCount());
        assertEquals(settlementPays.setScale(2).toPlainString(), summary.getPendingAmount(), "台账待结算与资产页同源");
        assertEquals("0.00", summary.getArrivedAmount());
        assertEquals(0, finance.redemptions(MERCHANT + 1, "all", 1, 20).getTotal(), "别家商家看不到这张票");

        // 营销近 30 天:核销含今天这一笔(D-93:营销首页与店铺参谋共用 selectVerifyEventTimes)
        List<Date> verifyEvents = sql.getMapper(MerchantMarketingAnalyticsMapper.class).selectVerifyEventTimes(
                MERCHANT, daysFromToday(-29), daysFromToday(1));
        assertEquals(1, verifyEvents.size(), "营销核销数必须含今天的活动验票");

        // 结算落账(settleActivity 的写入):商户 eventType=3 收入、明细置已结算、活动已结算。
        db.update("insert into ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,"
                + "event_type,event_id,change_type,del_flag,create_time) values(?,0,?,?,?,3,?,1,0,now())",
                MERCHANT, settlementPays, settlementPays, "活动分润 - 梧桐里读书夜", ACTIVITY);
        sql.getMapper(SettlementDetailMapper.class).markActivityRegistrationDetailsSettled(ACTIVITY);
        db.update("update cms_activity set is_settlement=1 where id=?", ACTIVITY);

        Map<String, Object> after = stages.stages(MERCHANT, new Date());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) after.get("pendingSettlement")), "结算后不再待结算");
        MerchantFinancePage<MerchantRedemptionView> settled = finance.redemptions(MERCHANT, "all", 1, 20);
        MerchantRedemptionSummary settledSummary = (MerchantRedemptionSummary) settled.getSummary();
        assertEquals("SETTLED", settled.getRows().get(0).getDisplayState());
        assertEquals("0.00", settledSummary.getPendingAmount());
        assertEquals("56.74", settledSummary.getArrivedAmount(), "台账已入账");
        assertEquals("56.74", finance.overview(MERCHANT).getPersonalArrivedThisMonthGross(), "结算概览本月个人账户入账");
        assertEquals(0, new BigDecimal("56.74").compareTo(
                sql.getMapper(UmsMemberBalanceDetailMapper.class).selectDashboardIncomeTotal(MERCHANT)), "首页累计收入");
    }

    /** 改过/新增的三条 SQL 都要过应用同配置的 Druid wall(IT 走裸数据源,wall 拦不到)。 */
    @Test
    void changedStatementsPassTheApplicationWall() {
        com.alibaba.druid.wall.WallConfig config = new com.alibaba.druid.wall.WallConfig(
                com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        com.alibaba.druid.wall.spi.MySqlWallProvider wall = new com.alibaba.druid.wall.spi.MySqlWallProvider(config);
        org.apache.ibatis.session.Configuration mybatis = sql.getConfiguration();
        Map<String, Object> scanParams = new java.util.HashMap<>();
        scanParams.put("merchantId", MERCHANT);
        scanParams.put("registrationId", 74L);
        Map<String, Object> windowParams = new java.util.HashMap<>();
        windowParams.put("ownerMemberId", MERCHANT);
        windowParams.put("previousStart", new Date());
        windowParams.put("currentStart", new Date());
        windowParams.put("endExclusive", new Date());
        Map<String, Object> statements = new java.util.LinkedHashMap<>();
        statements.put("com.chengyinhub.business.mapper.RedemptionMapper.selectActivityTicketScansByMerchant", scanParams);
        statements.put("com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.selectUnsettledActivityFundsByMember", MERCHANT);
        statements.put("com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper.selectAnalytics", windowParams);
        Map<String, Object> verifyParams = new java.util.HashMap<>();
        verifyParams.put("ownerMemberId", MERCHANT);
        verifyParams.put("since", new Date());
        verifyParams.put("endExclusive", new Date());
        statements.put("com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper.selectVerifyEventTimes", verifyParams);
        statements.forEach((id, params) -> {
            String bound = mybatis.getMappedStatement(id).getBoundSql(params).getSql();
            assertTrue(wall.check(bound).getViolations().isEmpty(), id + " 被 wall 拒:" + wall.check(bound).getViolations());
        });
    }

    private static Date daysFromToday(int days) {
        return Date.from(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(days)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private static CmsActivityServiceImpl activityServiceWithRule() {
        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        SettlementRule rule = new SettlementRule();
        rule.setBizType("activity");
        rule.setPlatformCommissionRate(new BigDecimal("0.0300"));
        rule.setMerchantRate(new BigDecimal("0.9700"));
        rule.setCreatorRate(BigDecimal.ZERO);
        when(rules.getActiveRule(0L, "activity")).thenReturn(rule);
        ReflectionTestUtils.setField(service, "settlementRuleService", rules);
        return service;
    }

    private static MemberFundsStageService stageService(CmsActivityServiceImpl activityService) {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember merchant = new UmsMember();
        merchant.setId(MERCHANT);
        merchant.setBalance(BigDecimal.ZERO);
        when(members.selectUmsMemberById(MERCHANT)).thenReturn(merchant);
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        return new MemberFundsStageService(sql.getMapper(ActivitySettlementHoldMapper.class), activityService,
                sql.getMapper(CoopSettlementMapper.class), mock(CoopSettlementAdjustmentMapper.class),
                mock(CoopComplaintMapper.class), members,
                new WithdrawalFrozenAmountResolver(bounty, mock(IPlayerWithdrawalCooldownService.class)));
    }

    private static MerchantFinanceQueryService financeService(MemberFundsStageService stages, PiiCryptoService pii) {
        MerchantFinanceQueryService service = new MerchantFinanceQueryService();
        ReflectionTestUtils.setField(service, "payableMapper", sql.getMapper(MerchantPayableMapper.class));
        ReflectionTestUtils.setField(service, "batchMapper", sql.getMapper(MerchantPayableBatchMapper.class));
        ReflectionTestUtils.setField(service, "coopSettlementMapper", sql.getMapper(CoopSettlementMapper.class));
        ReflectionTestUtils.setField(service, "balanceDetailMapper", sql.getMapper(UmsMemberBalanceDetailMapper.class));
        ReflectionTestUtils.setField(service, "redemptionMapper", sql.getMapper(RedemptionMapper.class));
        MerchantClawbackApplicationMapper clawbacks = mock(MerchantClawbackApplicationMapper.class);
        when(clawbacks.selectByMerchantMemberId(anyLong())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "clawbackMapper", clawbacks);
        ReflectionTestUtils.setField(service, "piiCryptoService", pii);
        ReflectionTestUtils.setField(service, "fundsStageService", stages);
        return service;
    }
}
