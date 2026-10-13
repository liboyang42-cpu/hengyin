package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ClubSettlementSummaryRow;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * T7 俱乐部/发布者财务视图单测:按主题聚合「我的分润 / 商家应收 / 打款状态」。mock mapper。
 */
class CoopFinanceTest {

    private CoopOrderMapper coopOrderMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private CmsTopicMapper cmsTopicMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private IPricingService pricingService;
    private CoopInviteServiceImpl svc;

    @BeforeEach
    void setUp() {
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        pricingService = mock(IPricingService.class);
        svc = new CoopInviteServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopSettlementMapper", coopSettlementMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(svc, "pricingService", pricingService);
        // [P0-28 批1] clubFinance 已改读**该主题锁价时冻的费率快照**(platformRateOf),不再是 live 值 ——
        // 页面显示的钱必须与结算实际算的钱同源,而结算读的就是快照。
        // (真实现里 platformRateOf 快照为空时会回落 live,恒非空;此处 mock 直接给值。)
        when(pricingService.platformRateOf(any())).thenReturn(new BigDecimal("0.10"));
    }

    private ClubSettlementSummaryRow settle(BigDecimal amount, int status) {
        ClubSettlementSummaryRow s = new ClubSettlementSummaryRow();
        s.setAmount(amount); s.setStatus(status);
        s.setExecutedAdjustmentAmount(BigDecimal.ZERO);
        if (status == 1) s.setActualPaidAmount(amount);
        return s;
    }

    @Test
    void clubFinance_settledTopic_computesMyIncomeAndMerchantStatus() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(700L));
        CmsTopic t = new CmsTopic();
        t.setId(700L); t.setMemberId(900001L); t.setName("周末城市定向"); t.setLifecycle(5); // 已结算
        when(cmsTopicMapper.selectCmsTopicById(700L)).thenReturn(t);
        when(coopSettlementMapper.selectSummaryByTopicId(700L))
                .thenReturn(Collections.singletonList(settle(new BigDecimal("30"), 1))); // 商家应收30,已打款
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(700L)).thenReturn(new BigDecimal("100"));

        List<Map<String, Object>> rows = svc.clubFinance(900001L);

        assertEquals(1, rows.size());
        Map<String, Object> r = rows.get(0);
        assertEquals(true, r.get("settled"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) r.get("platformAmount")), "平台10%");
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) r.get("merchantTotal")), "商家应收30");
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) r.get("merchantPaid")), "已打款30");
        // 我的分润 = 100 - 10 - 30 = 60
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) r.get("myIncome")), "发布者分润60");
    }

    @Test
    void clubFinance_unsettledTopic_myIncomeNull() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(701L));
        CmsTopic t = new CmsTopic();
        t.setId(701L); t.setMemberId(900001L); t.setName("售票中的团"); t.setLifecycle(3); // 售票中,未结算
        when(cmsTopicMapper.selectCmsTopicById(701L)).thenReturn(t);
        when(coopSettlementMapper.selectSummaryByTopicId(701L)).thenReturn(Collections.emptyList());
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(701L)).thenReturn(new BigDecimal("50"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(701L)).thenReturn(BigDecimal.ZERO);

        List<Map<String, Object>> rows = svc.clubFinance(900001L);

        assertEquals(false, rows.get(0).get("settled"));
        assertNull(rows.get(0).get("myIncome"), "未结算不显示分润(待结算)");
    }

    @Test
    void clubFinance_skipsTopicsNotOwnedByMember() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(702L));
        CmsTopic t = new CmsTopic();
        t.setId(702L); t.setMemberId(999999L); t.setLifecycle(5); // 归属他人
        when(cmsTopicMapper.selectCmsTopicById(702L)).thenReturn(t);

        List<Map<String, Object>> rows = svc.clubFinance(900001L);

        assertTrue(rows.isEmpty(), "非本人发布的主题不计入");
    }

    @Test
    void exploreDayFinanceReadsActivityShellSalesWithoutCrossingToOwnerOneOnlyQueries() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(703L));
        CmsTopic topic = new CmsTopic();
        topic.setId(703L);
        topic.setMemberId(900001L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(3);
        when(cmsTopicMapper.selectCmsTopicById(703L)).thenReturn(topic);
        when(coopSettlementMapper.selectSummaryByTopicId(703L)).thenReturn(Collections.emptyList());
        when(cmsRegistrationMapper.selectTotalSalesEvidenceByTopicIdIncludingActivityShell(703L))
                .thenReturn(new BigDecimal("69.00"));
        when(cmsRegistrationMapper.selectVerifiedSalesEvidenceByTopicIdIncludingActivityShell(703L))
                .thenReturn(new BigDecimal("69.00"));

        Map<String, Object> result = svc.clubFinance(900001L).get(0);

        assertEquals(new BigDecimal("69.00"), result.get("totalSales"));
        assertEquals(new BigDecimal("69.00"), result.get("verifiedSales"));
        verify(cmsRegistrationMapper, never()).selectTotalSalesByTopicId(703L);
        verify(cmsRegistrationMapper, never()).selectVerifiedSalesByTopicId(703L);
    }

    /** 1-03 F17:发起人分润页必须和俱乐部汇总同口径 —— 减已执行调整、排除作废行、已入账以实付流水为准。 */
    @Test
    void clubFinance_netOfAdjustments_excludesVoid_paidFromLedger() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(704L));
        CmsTopic t = new CmsTopic();
        t.setId(704L); t.setMemberId(900001L); t.setLifecycle(5);
        when(cmsTopicMapper.selectCmsTopicById(704L)).thenReturn(t);
        ClubSettlementSummaryRow mine = settle(new BigDecimal("12.34"), 1);
        mine.setPayeeType("initiator");
        mine.setExecutedAdjustmentAmount(new BigDecimal("-2.34"));
        mine.setActualPaidAmount(new BigDecimal("10.00"));
        ClubSettlementSummaryRow merchant = settle(new BigDecimal("20"), 1);
        merchant.setPayeeType("merchant");
        merchant.setActualPaidAmount(new BigDecimal("20"));
        ClubSettlementSummaryRow voided = settle(new BigDecimal("50"), 2);
        voided.setPayeeType("merchant");
        when(coopSettlementMapper.selectSummaryByTopicId(704L)).thenReturn(Arrays.asList(mine, merchant, voided));
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(704L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(704L)).thenReturn(new BigDecimal("100"));

        Map<String, Object> r = svc.clubFinance(900001L).get(0);

        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) r.get("myIncome")), "12.34 - 2.34 调整");
        assertEquals(true, r.get("myIncomeArrived"), "实付流水 10 已覆盖净额 10");
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) r.get("merchantTotal")), "作废行不计入应收");
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) r.get("merchantPaid")));
    }

    /** 存量已打款行(status=1)没有匹配的实付流水时,仍以 status=1 为到账凭据,不能翻成「未到账」。 */
    @Test
    void clubFinance_paidStatusWithoutLedgerRow_stillArrived() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(705L));
        CmsTopic t = new CmsTopic();
        t.setId(705L); t.setMemberId(900001L); t.setLifecycle(5);
        when(cmsTopicMapper.selectCmsTopicById(705L)).thenReturn(t);
        ClubSettlementSummaryRow mine = settle(new BigDecimal("12.34"), 1);
        mine.setPayeeType("initiator");
        mine.setActualPaidAmount(null);                         // 旧格式流水,虚拟列匹配不到
        mine.setExecutedAdjustmentAmount(new BigDecimal("-2.34")); // 打款后才执行的调整
        when(coopSettlementMapper.selectSummaryByTopicId(705L)).thenReturn(Collections.singletonList(mine));
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(705L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(705L)).thenReturn(new BigDecimal("100"));

        assertEquals(true, svc.clubFinance(900001L).get(0).get("myIncomeArrived"));
    }

    /** 发起人行全部作废:我的分润是 0,不能回落到旧模型「销售额−平台−商家」。 */
    @Test
    void clubFinance_allInitiatorLinesVoid_incomeZero() {
        when(coopOrderMapper.selectTopicIdsByFromId(900001L)).thenReturn(Collections.singletonList(706L));
        CmsTopic t = new CmsTopic();
        t.setId(706L); t.setMemberId(900001L); t.setLifecycle(5);
        when(cmsTopicMapper.selectCmsTopicById(706L)).thenReturn(t);
        ClubSettlementSummaryRow voided = settle(new BigDecimal("40"), 2);
        voided.setPayeeType("initiator");
        when(coopSettlementMapper.selectSummaryByTopicId(706L)).thenReturn(Collections.singletonList(voided));
        when(cmsRegistrationMapper.selectTotalSalesByTopicId(706L)).thenReturn(new BigDecimal("100"));
        when(cmsRegistrationMapper.selectVerifiedSalesByTopicId(706L)).thenReturn(new BigDecimal("100"));

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) svc.clubFinance(900001L).get(0).get("myIncome")));
    }

    /** clubFinance 读的这条 SQL 必须和俱乐部汇总共用同一段 join(调整 + 实付流水),真解析 XML 验证。 */
    @Test
    void summaryByTopicSql_sharesClubSummaryJoins() throws Exception {
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopSettlement", com.chengyinhub.business.domain.CoopSettlement.class);
        String res = "mapper/business/CoopSettlementMapper.xml";
        try (java.io.InputStream in = org.apache.ibatis.io.Resources.getResourceAsStream(res)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, cfg, res, cfg.getSqlFragments()).parse();
        }
        String sql = cfg.getMappedStatement("com.chengyinhub.business.mapper.CoopSettlementMapper.selectSummaryByTopicId")
                .getBoundSql(Collections.singletonMap("topicId", 1L)).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("coop_settlement_adjustment") && sql.contains("ums_member_balance_detail")
                && sql.contains("s.topic_id = ?"), sql);
    }
}
