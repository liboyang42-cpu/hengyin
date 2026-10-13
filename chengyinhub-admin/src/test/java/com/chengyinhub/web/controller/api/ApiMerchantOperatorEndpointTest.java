package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.IMerchantFulfillmentReadService;
import com.chengyinhub.business.service.ISettlementService;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import com.chengyinhub.common.constant.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantOperatorEndpointTest {
    private static final long PRINCIPAL = 9002L;
    private static final long OWNER = 9001L;
    private static final long MERCHANT_ROW = 11L;

    @Test
    void verificationOperatorCanReadLegacyScopedRunsAndRecords() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_CHECKIN);
        IMerchantFulfillmentReadService fulfillment = mock(IMerchantFulfillmentReadService.class);
        ICmsRegistrationScanService scans = mock(ICmsRegistrationScanService.class);
        when(fulfillment.listUpcomingRuns(OWNER, null)).thenReturn(Collections.emptyList());
        when(scans.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "merchantFulfillmentReadService", fulfillment);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scans);

        assertAll(
                () -> assertEquals(HttpStatus.SUCCESS,
                        controller.upcomingRuns(Collections.emptyMap()).get("code")),
                () -> assertEquals(HttpStatus.SUCCESS, controller.verificationRecords().get("code")));
        verify(fulfillment).listUpcomingRuns(OWNER, null);
        org.mockito.ArgumentCaptor<CmsRegistrationScan> query =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scans).selectCmsRegistrationScanList(query.capture());
        assertEquals(OWNER, query.getValue().getMemberId());
    }

    @Test
    void financeOperatorCanReadLegacyScopedFundsWithResolvedOwner() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_FINANCE);
        ISettlementService settlements = mock(ISettlementService.class);
        MerchantFinanceQueryService finance = mock(MerchantFinanceQueryService.class);
        when(settlements.selectBatchList(any())).thenReturn(Collections.emptyList());
        when(finance.overview(OWNER)).thenReturn(
                new com.chengyinhub.business.domain.vo.MerchantSettlementOverview());
        when(finance.redemptions(OWNER, "all", 1, 20)).thenReturn(
                new com.chengyinhub.business.domain.vo.MerchantFinancePage<>());
        when(finance.publicTransferBatches(OWNER, 1, 20)).thenReturn(
                new com.chengyinhub.business.domain.vo.MerchantFinancePage<>());
        when(finance.settlementEntries(OWNER, "all", 1, 20)).thenReturn(
                new com.chengyinhub.business.domain.vo.MerchantFinancePage<>());
        ReflectionTestUtils.setField(controller, "settlementService", settlements);
        ReflectionTestUtils.setField(controller, "merchantFinanceQueryService", finance);

        assertAll(
                () -> assertEquals(HttpStatus.SUCCESS, controller.settlementLedger(null).get("code")),
                () -> assertEquals(HttpStatus.SUCCESS, controller.financeOverview().get("code")),
                () -> assertEquals(HttpStatus.SUCCESS,
                        controller.financeRedemptions(Collections.emptyMap()).get("code")),
                () -> assertEquals(HttpStatus.ERROR,
                        controller.financeRedemptionDetail(Collections.emptyMap()).get("code")),
                () -> assertEquals(HttpStatus.SUCCESS,
                        controller.financePublicTransferBatches(Collections.emptyMap()).get("code")),
                () -> assertEquals(HttpStatus.ERROR,
                        controller.financePublicTransferBatchDetail(Collections.emptyMap()).get("code")),
                () -> assertEquals(HttpStatus.SUCCESS,
                        controller.financeSettlementEntries(Collections.emptyMap()).get("code")));
        verify(finance).overview(OWNER);
        verify(finance).redemptions(OWNER, "all", 1, 20);
        verify(finance).publicTransferBatches(OWNER, 1, 20);
        verify(finance).settlementEntries(OWNER, "all", 1, 20);
    }

    @Test
    void exactMerchantRowOrderAndCouponEndpointsRemainAvailableToOperators() {
        ApiMerchantController controller = managerController();
        com.chengyinhub.business.service.IOmsOrderService orders =
                mock(com.chengyinhub.business.service.IOmsOrderService.class);
        com.chengyinhub.business.service.ISmsCouponService coupons =
                mock(com.chengyinhub.business.service.ISmsCouponService.class);
        when(orders.selectOmsOrderList(any())).thenReturn(Collections.emptyList());
        when(coupons.selectSmsCouponList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsOrderService", orders);
        ReflectionTestUtils.setField(controller, "smsCouponService", coupons);

        assertAll(
                () -> assertEquals(HttpStatus.SUCCESS, controller.orders(null).get("code")),
                () -> assertEquals(HttpStatus.SUCCESS, controller.couponSummary().get("code")));
    }

    private ApiMerchantController managerController() {
        return operatorController(MerchantRoleCode.MERCHANT_MANAGER);
    }

    private ApiMerchantController operatorController(String roleCode) {
        ApiMerchantController controller = new LoggedInController(PRINCIPAL);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(org.mockito.ArgumentMatchers.eq(PRINCIPAL), anyString())).thenReturn(
                new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, OWNER, roleCode));
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        return controller;
    }

    private static class LoggedInController extends ApiMerchantController {
        private final Long principal;

        private LoggedInController(Long principal) {
            this.principal = principal;
        }

        @Override
        public Long getAppUserId() {
            return principal;
        }
    }
}
