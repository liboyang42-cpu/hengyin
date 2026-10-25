package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantDashboardIncome;

import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.MerchantLedgerQuery;
import com.chengyinhub.business.domain.vo.MerchantFinancePage;
import com.chengyinhub.business.domain.vo.MerchantRedemptionSummary;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.domain.vo.MerchantSettlementOverview;
import com.chengyinhub.business.domain.vo.SettlementSummaryVO;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.MerchantAftercareMapper;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.IMerchantInsightService;
import com.chengyinhub.business.service.IMerchantAggregateReadService;
import com.chengyinhub.business.service.ISettlementService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiMerchantLedgerControllerTest {

    private static final long PRINCIPAL_MEMBER_ID = 9002L;
    private static final long OWNER_MEMBER_ID = 9001L;
    private static final long MERCHANT_ROW_ID = 11L;

    private static MerchantAccessContext accessContext(String roleCode) {
        return new MerchantAccessContext(PRINCIPAL_MEMBER_ID, MERCHANT_ROW_ID,
                OWNER_MEMBER_ID, roleCode);
    }

    private static IMerchantAccessService attachAccess(ApiMerchantController controller, String roleCode) {
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(eq(PRINCIPAL_MEMBER_ID), anyString())).thenReturn(accessContext(roleCode));
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        return accessService;
    }

    private static IMerchantAccessService attachOwnerAccess(ApiMerchantController controller) {
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(eq(OWNER_MEMBER_ID), anyString())).thenReturn(
                new MerchantAccessContext(OWNER_MEMBER_ID, MERCHANT_ROW_ID,
                        OWNER_MEMBER_ID, MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        return accessService;
    }

    @Test
    void financeOperatorSettlementLedgerUsesResolvedOwnerScope() {
        ISettlementService settlementService = mock(ISettlementService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_FINANCE);
        ReflectionTestUtils.setField(controller, "settlementService", settlementService);
        SettlementBatch batch = new SettlementBatch();
        batch.setId(21L);
        batch.setMerchantId(OWNER_MEMBER_ID);
        SettlementSummaryVO summary = new SettlementSummaryVO();
        summary.setMerchantId(OWNER_MEMBER_ID);
        summary.setPendingAmount(new java.math.BigDecimal("18.00"));
        SettlementSummaryVO reconciliation = new SettlementSummaryVO();
        reconciliation.setMerchantId(OWNER_MEMBER_ID);
        reconciliation.setReversalAmount(new java.math.BigDecimal("3.00"));
        when(settlementService.selectBatchList(any(SettlementBatch.class)))
                .thenReturn(Collections.singletonList(batch));
        when(settlementService.getSummary(OWNER_MEMBER_ID)).thenReturn(summary);
        when(settlementService.getReconciliation(OWNER_MEMBER_ID)).thenReturn(reconciliation);

        AjaxResult result = controller.settlementLedger(new MerchantLedgerQuery());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, ((java.util.List<?>) data.get("rows")).size());
        assertEquals(summary, data.get("summary"));
        assertEquals(reconciliation, data.get("reconciliation"));
        org.mockito.ArgumentCaptor<SettlementBatch> captor = org.mockito.ArgumentCaptor.forClass(SettlementBatch.class);
        verify(settlementService).selectBatchList(captor.capture());
        assertEquals(OWNER_MEMBER_ID, captor.getValue().getMerchantId());
        verify(settlementService).getSummary(OWNER_MEMBER_ID);
        verify(settlementService).getReconciliation(OWNER_MEMBER_ID);
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.FINANCE_READ);
    }

    @Test
    void ledgerAlwaysScopesQueryToLoggedInMerchant() {
        OmsOrderMapper mapper = mock(OmsOrderMapper.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_MANAGER);
        ReflectionTestUtils.setField(controller, "omsOrderMapper", mapper);
        OmsOrder order = new OmsOrder();
        order.setId(11L);
        order.setStatus(1L);
        when(mapper.selectMerchantLedgerOrders(eq(MERCHANT_ROW_ID), eq(1L), eq(false), any(), any()))
                .thenReturn(Collections.singletonList(order));
        Map<String, Object> summary = new HashMap<>();
        summary.put("orderCount", 1L);
        summary.put("orderAmount", "59.00");
        summary.put("aftersaleCount", 0L);
        when(mapper.selectMerchantLedgerSummary(eq(MERCHANT_ROW_ID), eq(1L), eq(false), any(), any())).thenReturn(summary);

        MerchantLedgerQuery query = new MerchantLedgerQuery();
        query.setStatus("pending");
        query.setPeriod("month");
        AjaxResult result = controller.ledgerOrders(query);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1L, data.get("total"));
        verify(mapper).selectMerchantLedgerOrders(eq(MERCHANT_ROW_ID), eq(1L), eq(false), any(), any());
        verify(mapper).selectMerchantLedgerSummary(eq(MERCHANT_ROW_ID), eq(1L), eq(false), any(), any());
        verify(mapper, never()).selectMerchantLedgerOrders(eq(PRINCIPAL_MEMBER_ID), any(), any(), any(), any());
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.ORDER_READ);
    }

    @Test
    void ordersAlwaysUseMerchantRowIdInsteadOfPrincipalMemberId() {
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_MANAGER);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        when(orderService.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());

        OmsOrder clientQuery = new OmsOrder();
        clientQuery.setMmsMerchantId(777L);
        AjaxResult result = controller.orders(clientQuery);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<OmsOrder> captor = org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderService).selectOmsOrderList(captor.capture());
        assertEquals(MERCHANT_ROW_ID, captor.getValue().getMmsMerchantId());
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.ORDER_READ);
    }

    @Test
    void financeOrdersKeepAmountsButRedactCustomerContactInBothViews() {
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        OmsOrderMapper mapper = mock(OmsOrderMapper.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        attachAccess(controller, MerchantRoleCode.MERCHANT_FINANCE);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        ReflectionTestUtils.setField(controller, "omsOrderMapper", mapper);
        OmsOrder listOrder = sensitiveOrder(31L);
        OmsOrder ledgerOrder = sensitiveOrder(32L);
        when(orderService.selectOmsOrderList(any(OmsOrder.class)))
                .thenReturn(Collections.singletonList(listOrder));
        when(mapper.selectMerchantLedgerOrders(eq(MERCHANT_ROW_ID), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(ledgerOrder));
        when(mapper.selectMerchantLedgerSummary(eq(MERCHANT_ROW_ID), any(), any(), any(), any()))
                .thenReturn(Collections.emptyMap());

        OmsOrder attackerFilter = sensitiveOrder(99L);
        AjaxResult listResult = controller.orders(attackerFilter);
        AjaxResult ledgerResult = controller.ledgerOrders(new MerchantLedgerQuery());

        OmsOrder listed = (OmsOrder) ((java.util.List<?>) listResult.get(AjaxResult.DATA_TAG)).get(0);
        OmsOrder ledgered = (OmsOrder) ((java.util.List<?>)
                ((Map<?, ?>) ledgerResult.get(AjaxResult.DATA_TAG)).get("rows")).get(0);
        assertOrderContactRedactedButAmountKept(listed);
        assertOrderContactRedactedButAmountKept(ledgered);
        org.mockito.ArgumentCaptor<OmsOrder> query = org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderService).selectOmsOrderList(query.capture());
        assertEquals(MERCHANT_ROW_ID, query.getValue().getMmsMerchantId());
        assertEquals(null, query.getValue().getMemberId());
        assertEquals(null, query.getValue().getPayId());
        assertEquals(null, query.getValue().getMemberCouponId());
        assertEquals(null, query.getValue().getReceiverPhone());
        assertEquals(null, query.getValue().getReceiverDetailAddress());
        assertEquals(null, query.getValue().getOrderNote());
    }

    @Test
    void financeOperatorOverviewKeepsLegacyOwnerMemberScope() {
        MerchantFinanceQueryService financeService = mock(MerchantFinanceQueryService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_FINANCE);
        ReflectionTestUtils.setField(controller, "merchantFinanceQueryService", financeService);
        when(financeService.overview(OWNER_MEMBER_ID)).thenReturn(new MerchantSettlementOverview());

        AjaxResult result = controller.financeOverview();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(financeService).overview(OWNER_MEMBER_ID);
        verify(financeService, never()).overview(PRINCIPAL_MEMBER_ID);
        verify(financeService, never()).overview(MERCHANT_ROW_ID);
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.FINANCE_READ);
    }

    @Test
    void verificationReaderGetsRedemptionsWithoutFinanceFieldsOrSettlementFilterSideChannel() {
        MerchantFinanceQueryService financeService = mock(MerchantFinanceQueryService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_MANAGER);
        ReflectionTestUtils.setField(controller, "merchantFinanceQueryService", financeService);
        MerchantFinancePage<MerchantRedemptionView> page = redemptionPage();
        when(financeService.redemptions(OWNER_MEMBER_ID, "all", 1, 20)).thenReturn(page);
        Map<String, Object> body = new HashMap<>();
        body.put("filter", "settled");

        AjaxResult result = controller.financeRedemptions(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        MerchantFinancePage<?> returned = (MerchantFinancePage<?>) result.get(AjaxResult.DATA_TAG);
        MerchantRedemptionView row = (MerchantRedemptionView) returned.getRows().get(0);
        assertEquals(null, row.getSettlementAmount());
        assertEquals(null, row.getSettlementState());
        assertEquals(null, row.getSettlementRoute());
        assertEquals(null, row.getDisplayState());
        assertEquals(null, row.getNoCashReason());
        assertEquals(null, row.getPublicSettlement());
        MerchantRedemptionSummary summary = (MerchantRedemptionSummary) returned.getSummary();
        assertEquals(2, summary.getCount());
        assertEquals(null, summary.getPendingAmount());
        assertEquals(null, summary.getArrivedAmount());
        verify(financeService).redemptions(OWNER_MEMBER_ID, "all", 1, 20);
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY_RECORD_READ);
    }

    @Test
    void financeReaderKeepsCompleteRedemptionAmountsAndSettlementFields() {
        MerchantFinanceQueryService financeService = mock(MerchantFinanceQueryService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_FINANCE);
        ReflectionTestUtils.setField(controller, "merchantFinanceQueryService", financeService);
        MerchantFinancePage<MerchantRedemptionView> page = redemptionPage();
        when(financeService.redemptions(OWNER_MEMBER_ID, "settled", 1, 20)).thenReturn(page);
        Map<String, Object> body = new HashMap<>();
        body.put("filter", "settled");

        AjaxResult result = controller.financeRedemptions(body);

        MerchantFinancePage<?> returned = (MerchantFinancePage<?>) result.get(AjaxResult.DATA_TAG);
        MerchantRedemptionView row = (MerchantRedemptionView) returned.getRows().get(0);
        assertEquals("18.00", row.getSettlementAmount());
        assertEquals("SETTLED", row.getSettlementState());
        assertEquals("CHAPTER_OFFER", row.getSettlementRoute());
        assertEquals("SETTLED", row.getDisplayState());
        assertEquals("12.00", ((MerchantRedemptionSummary) returned.getSummary()).getArrivedAmount());
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY_RECORD_READ);
    }

    @Test
    void verificationReaderGetsRedemptionDetailWithServerSideFinanceRedaction() {
        MerchantFinanceQueryService financeService = mock(MerchantFinanceQueryService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        IMerchantAccessService accessService = attachAccess(controller, MerchantRoleCode.MERCHANT_CHECKIN);
        ReflectionTestUtils.setField(controller, "merchantFinanceQueryService", financeService);
        MerchantRedemptionView detail = redemptionPage().getRows().get(0);
        when(financeService.redemptionDetail(OWNER_MEMBER_ID, "redemption", "7")).thenReturn(detail);
        Map<String, Object> body = new HashMap<>();
        body.put("recordType", "redemption");
        body.put("recordId", "7");

        AjaxResult result = controller.financeRedemptionDetail(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        MerchantRedemptionView returned = (MerchantRedemptionView) result.get(AjaxResult.DATA_TAG);
        assertEquals(null, returned.getSettlementAmount());
        assertEquals(null, returned.getSettlementState());
        assertEquals(null, returned.getSettlementRoute());
        assertEquals(null, returned.getDisplayState());
        verify(accessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY_RECORD_READ);
    }

    @Test
    void dashboardUsesMerchantRowIdForOrdersAndOwnerMemberIdForMoney() {
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        IMerchantInsightService insightService = mock(IMerchantInsightService.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        IMerchantAccessService accessService = attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insightService);
        when(balanceService.readDashboardIncome(eq(OWNER_MEMBER_ID), any(java.util.Date.class)))
                .thenReturn(new MerchantDashboardIncome(java.math.BigDecimal.ZERO, Collections.emptyMap()));
        when(orderService.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(insightService.dashboard(OWNER_MEMBER_ID)).thenReturn(new com.alibaba.fastjson2.JSONObject());

        AjaxResult result = controller.getDashboard();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 收入走 SQL 聚合,且按 owner 会员取数;不再拉全量明细
        verify(balanceService).readDashboardIncome(eq(OWNER_MEMBER_ID), any(java.util.Date.class));
        verify(balanceService, never()).selectUmsMemberBalanceDetailList(any(UmsMemberBalanceDetail.class));
        org.mockito.ArgumentCaptor<OmsOrder> orderCaptor = org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderService).selectOmsOrderList(orderCaptor.capture());
        assertEquals(MERCHANT_ROW_ID, orderCaptor.getValue().getMmsMerchantId());
        verify(insightService).dashboard(OWNER_MEMBER_ID);
        verify(accessService).require(OWNER_MEMBER_ID, MerchantPermission.BASIC_READ);
    }

    @Test
    void dashboardRevenueAndSevenDayTrendComeFromSqlAggregatesWithMissingDaysZeroFilled() {
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        IMerchantInsightService insightService = mock(IMerchantInsightService.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insightService);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        when(balanceService.readDashboardIncome(eq(OWNER_MEMBER_ID), any(java.util.Date.class)))
                .thenReturn(new MerchantDashboardIncome(new java.math.BigDecimal("12.345"),
                        Collections.singletonMap(today, new java.math.BigDecimal("5"))));
        when(orderService.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());

        AjaxResult result = controller.getDashboard();

        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals("12.35", data.get("revenue"), "累计收入保持两位小数 HALF_UP");
        java.util.List<?> trend = (java.util.List<?>) data.get("revenue7d");
        assertEquals(7, trend.size());
        assertEquals("5.00", ((java.util.Map<?, ?>) trend.get(6)).get("amount"), "今天落在最后一格");
        java.util.Map<?, ?> windows = (java.util.Map<?, ?>) data.get("windows");
        assertEquals("ALL_TIME", windows.get("revenue"));
        assertEquals("CALENDAR_7D_INCL_TODAY", windows.get("revenue7d"));
        assertEquals("CURRENT_SNAPSHOT", windows.get("pendingOrders"));
        assertEquals("ROLLING_30D", windows.get("insight.exposure"));
        assertEquals("REGISTRATION_COHORT_ROLLING_30D", windows.get("insight.checkin"));
        assertEquals("CURRENT_SNAPSHOT", windows.get("insight.supplyCount"));
        assertEquals(false, windows.containsKey("insight"), "混合口径不能用整个 insight 的统一窗口冒充");
        for (int i = 0; i < 6; i++) {
            assertEquals("0.00", ((java.util.Map<?, ?>) trend.get(i)).get("amount"), "无流水的天补 0");
        }
        // 分桶起点 = 今天往前 6 天的零点(含今天共 7 个自然日)
        org.mockito.ArgumentCaptor<java.util.Date> since = org.mockito.ArgumentCaptor.forClass(java.util.Date.class);
        verify(balanceService).readDashboardIncome(eq(OWNER_MEMBER_ID), since.capture());
        java.util.Calendar start = java.util.Calendar.getInstance();
        start.set(java.util.Calendar.HOUR_OF_DAY, 0);
        start.set(java.util.Calendar.MINUTE, 0);
        start.set(java.util.Calendar.SECOND, 0);
        start.set(java.util.Calendar.MILLISECOND, 0);
        start.add(java.util.Calendar.DATE, -6);
        assertEquals(start.getTime(), since.getValue());
    }

    @Test
    void dashboardInsightFailureDoesNotDeclareAnInsightWindow() {
        IUmsMemberBalanceDetailService balance = mock(IUmsMemberBalanceDetailService.class);
        IOmsOrderService orders = mock(IOmsOrderService.class);
        IMerchantInsightService insight = mock(IMerchantInsightService.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balance);
        ReflectionTestUtils.setField(controller, "omsOrderService", orders);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insight);
        when(orders.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(balance.readDashboardIncome(eq(OWNER_MEMBER_ID), any(java.util.Date.class)))
                .thenReturn(new MerchantDashboardIncome(java.math.BigDecimal.ZERO, Collections.emptyMap()));
        when(insight.dashboard(OWNER_MEMBER_ID)).thenThrow(new IllegalStateException("unavailable"));

        AjaxResult result = controller.getDashboard();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(false, data.containsKey("insight"));
        Map<?, ?> windows = (Map<?, ?>) data.get("windows");
        assertEquals("ALL_TIME", windows.get("revenue"));
        assertEquals(false, windows.containsKey("insight.exposure"));
        assertEquals(false, windows.containsKey("insight.checkin"));
        assertEquals(false, windows.containsKey("insight.supplyCount"));
    }

    @Test
    void managerDashboardReadsOrdersAndInsightWithoutFinanceBlock() {
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        IMerchantInsightService insightService = mock(IMerchantInsightService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        attachAccess(controller, MerchantRoleCode.MERCHANT_MANAGER);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insightService);
        when(orderService.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());

        AjaxResult result = controller.getDashboard();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 店长没有 FINANCE_READ:资金域一块都不查,但订单/参谋两块按岗位权限照常取数。
        verifyNoInteractions(balanceService);
        java.util.Map<?, ?> windows =
                (java.util.Map<?, ?>) ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("windows");
        assertEquals(java.util.Map.of("pendingOrders", "CURRENT_SNAPSHOT",
                        "insight.exposure", "ROLLING_30D",
                        "insight.checkin", "REGISTRATION_COHORT_ROLLING_30D",
                        "insight.supplyCount", "CURRENT_SNAPSHOT"), new java.util.HashMap<>(windows),
                "只声明当前岗位实际下发字段的窗口");
        verify(orderService).selectOmsOrderList(any(OmsOrder.class));
        verify(insightService).dashboard(OWNER_MEMBER_ID);
    }

    @Test
    void todoSummaryUsesOwnerMemberForLegacyTablesAndMerchantRowForOrders() {
        ICmsRegistrationMerchantService offerService = mock(ICmsRegistrationMerchantService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        MerchantAftercareMapper aftercareMapper = mock(MerchantAftercareMapper.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        IMerchantAccessService accessService = attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", offerService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        ReflectionTestUtils.setField(controller, "merchantAftercareMapper", aftercareMapper);
        // release-0917 集成:fix-merchant-0917 #23 给 todo-summary 加了 pendingPredict(依赖 IPlayPredictService),
        // 手工装配须补上;mock 的 pendingInbox 默认空列表 = 0 轮待答,不影响本用例断言的其它口径。
        ReflectionTestUtils.setField(controller, "playPredictService",
                mock(com.chengyinhub.business.service.IPlayPredictService.class));
        when(offerService.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.emptyList());
        when(scanService.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(Collections.emptyList());
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.emptyList());
        when(orderService.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(aftercareMapper.countOwnedRefundList(MERCHANT_ROW_ID, OWNER_MEMBER_ID, "PENDING")).thenReturn(4L);

        com.chengyinhub.business.service.ICmsTopicService todoTopics = mock(com.chengyinhub.business.service.ICmsTopicService.class);
        com.chengyinhub.business.service.ICmsRegistrationService todoRegistrations = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", todoTopics);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", todoRegistrations);
        AjaxResult result = controller.todoSummary();
        org.mockito.Mockito.verifyNoInteractions(todoTopics, todoRegistrations);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<CmsRegistrationMerchant> offerCaptor =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(offerService).selectCmsRegistrationMerchantList(offerCaptor.capture());
        assertEquals(OWNER_MEMBER_ID, offerCaptor.getValue().getMemberId());
        org.mockito.ArgumentCaptor<CmsRegistrationScan> scanCaptor =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanService).selectCmsRegistrationScanList(scanCaptor.capture());
        assertEquals(OWNER_MEMBER_ID, scanCaptor.getValue().getMemberId());
        org.mockito.ArgumentCaptor<CmsActivity> activityCaptor =
                org.mockito.ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).selectCmsActivityList(activityCaptor.capture());
        assertEquals(OWNER_MEMBER_ID, activityCaptor.getValue().getMemberId());
        org.mockito.ArgumentCaptor<OmsOrder> orderCaptor = org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderService).selectOmsOrderList(orderCaptor.capture());
        for (OmsOrder query : orderCaptor.getAllValues()) {
            assertEquals(MERCHANT_ROW_ID, query.getMmsMerchantId());
        }
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(4L, data.get("refundCount"));
        verify(aftercareMapper).countOwnedRefundList(MERCHANT_ROW_ID, OWNER_MEMBER_ID, "PENDING");
        verify(accessService).require(OWNER_MEMBER_ID, MerchantPermission.BASIC_READ);
    }

    @Test
    void financeTodoSummaryReadsVerifyAndOrderRowsScopedToOwner() {
        ICmsRegistrationMerchantService offerService = mock(ICmsRegistrationMerchantService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IOmsOrderService orderService = mock(IOmsOrderService.class);
        MerchantAftercareMapper aftercareMapper = mock(MerchantAftercareMapper.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        attachAccess(controller, MerchantRoleCode.MERCHANT_FINANCE);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", offerService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);
        ReflectionTestUtils.setField(controller, "merchantAftercareMapper", aftercareMapper);
        when(offerService.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.emptyList());
        when(scanService.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(Collections.emptyList());
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.emptyList());
        when(orderService.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());

        com.chengyinhub.business.service.ICmsTopicService todoTopics = mock(com.chengyinhub.business.service.ICmsTopicService.class);
        com.chengyinhub.business.service.ICmsRegistrationService todoRegistrations = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", todoTopics);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", todoRegistrations);
        AjaxResult result = controller.todoSummary();
        org.mockito.Mockito.verifyNoInteractions(todoTopics, todoRegistrations);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<CmsRegistrationMerchant> offerCaptor =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(offerService).selectCmsRegistrationMerchantList(offerCaptor.capture());
        assertEquals(OWNER_MEMBER_ID, offerCaptor.getValue().getMemberId());
        org.mockito.ArgumentCaptor<OmsOrder> orderCaptor = org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderService).selectOmsOrderList(orderCaptor.capture());
        assertEquals(MERCHANT_ROW_ID, orderCaptor.getValue().getMmsMerchantId());
    }

    @Test
    void ownerMarketingFunnelDelegatesToOwnerScopedIndependentEventsWithoutReadingIncome() {
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        Map<String, Object> analytics = new HashMap<>();
        analytics.put("semantics", "INDEPENDENT_EVENT_COUNTS");
        when(aggregate.funnel(OWNER_MEMBER_ID, 30)).thenReturn(analytics);

        AjaxResult result = controller.funnel(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("INDEPENDENT_EVENT_COUNTS",
                ((Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("semantics"));
        verify(aggregate).funnel(OWNER_MEMBER_ID, 30);
        verify(balanceService, never()).selectUmsMemberBalanceDetailList(
                any(UmsMemberBalanceDetail.class));
    }

    @Test
    void checkinEventsReadOnlyVerifyRecordsScopedToOwnerWithoutIncome() {
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        ApiMerchantController controller = new LoggedInMerchantController();
        attachAccess(controller, MerchantRoleCode.MERCHANT_CHECKIN);
        when(scanService.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);

        AjaxResult result = controller.events();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 核销员没有 FINANCE_READ:收入流水一块都不读,只读本店核销记录。
        verifyNoInteractions(balanceService);
        org.mockito.ArgumentCaptor<CmsRegistrationScan> scanCaptor =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanService).selectCmsRegistrationScanList(scanCaptor.capture());
        assertEquals(OWNER_MEMBER_ID, scanCaptor.getValue().getMemberId());
    }

    @Test
    void ownerEventsUseOwnerMemberForIncomeAndVerificationRecords() {
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);
        when(balanceService.selectUmsMemberBalanceDetailList(any(UmsMemberBalanceDetail.class)))
                .thenReturn(Collections.emptyList());
        when(scanService.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(Collections.emptyList());

        AjaxResult result = controller.events();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<UmsMemberBalanceDetail> balance =
                org.mockito.ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(balanceService).selectUmsMemberBalanceDetailList(balance.capture());
        assertEquals(OWNER_MEMBER_ID, balance.getValue().getMemberId());
        org.mockito.ArgumentCaptor<CmsRegistrationScan> scan =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanService).selectCmsRegistrationScanList(scan.capture());
        assertEquals(OWNER_MEMBER_ID, scan.getValue().getMemberId());
    }

    /**
     * 4-10 复看整改:经营动态只认 registrationType=1(真实核销台账)。
     * writeScanLedger 是全仓唯一核销台账写入口,写的是 registrationType=1、status=0;
     * status 从没有过 1 —— 按 status==1 过滤会让核销事件一条都不显示。
     * registrationType=0 是报名主行(member_id=玩家),不是本商家核销。
     */
    @Test
    void ownerEventsReportRealVerificationLedgerRowsAndSkipRegistrationRows() {
        IUmsMemberBalanceDetailService balanceService = mock(IUmsMemberBalanceDetailService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        ApiMerchantController controller = new LoggedInOwnerController();
        attachOwnerAccess(controller);
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService",
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class));
        when(balanceService.selectUmsMemberBalanceDetailList(any(UmsMemberBalanceDetail.class)))
                .thenReturn(Collections.emptyList());

        // 真实核销:writeScanLedger 的落库形状 type=1, status=0
        CmsRegistrationScan verified = new CmsRegistrationScan();
        verified.setId("scan-verified");
        verified.setRegistrationId(71L);
        verified.setMemberId(OWNER_MEMBER_ID);
        verified.setRegistrationType(1);
        verified.setStatus(0L);
        verified.setCreateTime(new java.util.Date());
        // 报名主行:type=0, 不是核销
        CmsRegistrationScan registrationRow = new CmsRegistrationScan();
        registrationRow.setId("scan-registration");
        registrationRow.setMemberId(OWNER_MEMBER_ID);
        registrationRow.setRegistrationType(0);
        registrationRow.setStatus(0L);
        registrationRow.setCreateTime(new java.util.Date());
        when(scanService.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(java.util.Arrays.asList(registrationRow, verified));

        AjaxResult result = controller.events();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        java.util.List<?> events = (java.util.List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, events.size(), "只有 registrationType=1 的真实核销才进经营动态");
        Map<?, ?> event = (Map<?, ?>) events.get(0);
        assertEquals("verify", event.get("type"));
        assertEquals("完成一笔到店核销", event.get("content"));
        org.mockito.ArgumentCaptor<CmsRegistrationScan> scanProbe =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanService).selectCmsRegistrationScanList(scanProbe.capture());
        assertEquals(Integer.valueOf(1), scanProbe.getValue().getRegistrationType(),
                "查询也必须只要真实核销行,不能把报名主行拉回来再在内存里猜");
    }

    private static class LoggedInMerchantController extends ApiMerchantController {
        @Override
        public Long getAppUserId() {
            return PRINCIPAL_MEMBER_ID;
        }
    }

    private static class LoggedInOwnerController extends ApiMerchantController {
        @Override
        public Long getAppUserId() {
            return OWNER_MEMBER_ID;
        }
    }

    private static OmsOrder sensitiveOrder(Long id) {
        OmsOrder order = new OmsOrder();
        order.setId(id);
        order.setMemberId(501L);
        order.setPayId(601L);
        order.setMemberCouponId(701L);
        order.setOrderTotalAmount(new java.math.BigDecimal("88.00"));
        order.setReceiverName("张三");
        order.setReceiverPhone("13800000000");
        order.setReceiverProvince("上海");
        order.setReceiverDetailAddress("静安区测试路 1 号");
        order.setOrderNote("晚间送达");
        order.setDeliverySn("TRACK-PII");
        order.setPaymentTransactionId("wx-secret-id");
        order.setCommerceRequestId("commerce-secret-id");
        return order;
    }

    private static MerchantFinancePage<MerchantRedemptionView> redemptionPage() {
        MerchantRedemptionView row = new MerchantRedemptionView();
        row.setRecordKey("redemption:7");
        row.setRecordType("redemption");
        row.setRecordId("7");
        row.setSettlementAmount("18.00");
        row.setSettlementState("SETTLED");
        row.setSettlementRoute("CHAPTER_OFFER");
        row.setDisplayState("SETTLED");
        row.setNoCashReason("financial-only");
        row.setPublicSettlement(Collections.<String, Object>singletonMap("amount", "18.00"));
        MerchantRedemptionSummary summary = new MerchantRedemptionSummary();
        summary.setCount(2);
        summary.setPendingAmount("3.00");
        summary.setArrivedAmount("12.00");
        MerchantFinancePage<MerchantRedemptionView> page = new MerchantFinancePage<>();
        page.setRows(Collections.singletonList(row));
        page.setSummary(summary);
        page.setTotal(1);
        page.setPageNum(1);
        page.setPageSize(20);
        return page;
    }

    private static void assertOrderContactRedactedButAmountKept(OmsOrder order) {
        assertEquals(new java.math.BigDecimal("88.00"), order.getOrderTotalAmount());
        assertEquals(null, order.getMemberId());
        assertEquals(null, order.getPayId());
        assertEquals(null, order.getMemberCouponId());
        assertEquals(null, order.getReceiverName());
        assertEquals(null, order.getReceiverPhone());
        assertEquals(null, order.getReceiverProvince());
        assertEquals(null, order.getReceiverDetailAddress());
        assertEquals(null, order.getOrderNote());
        assertEquals(null, order.getDeliverySn());
        assertEquals(null, order.getPaymentTransactionId());
        assertEquals(null, order.getCommerceRequestId());
    }
}

