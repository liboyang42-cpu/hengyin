package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantDashboardIncome;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.MerchantSubscription;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.MerchantAftercareMapper;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.IMerchantAggregateReadService;
import com.chengyinhub.business.service.IMerchantInsightService;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.IProjectPlayerReadService;
import com.chengyinhub.business.service.IPlayPredictService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作台读接口(#17-1)按岗位权限码放行的合同。
 *
 * <p>原实现给 /dashboard、/todo-summary、/events、/funnel、/customers、/subscription 全部套了
 * requireOwnerOnly 的临时旧数据防线,导致店长/财务/运营/核销员一律 403。现在只保留
 * merchantAccessService.require(权限码) 这一道闸,数据块各自按 hasPermission 取数。
 */
class ApiMerchantOperatorReadScopeTest {
    private static final long PRINCIPAL = 9002L;
    private static final long OWNER = 9001L;
    private static final long MERCHANT_ROW = 11L;

    @Test
    void managerDashboardKeepsOwnerScopeWithoutFinanceRead() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_MANAGER);
        IUmsMemberBalanceDetailService balance = mock(IUmsMemberBalanceDetailService.class);
        IOmsOrderService orders = mock(IOmsOrderService.class);
        IMerchantInsightService insight = mock(IMerchantInsightService.class);
        when(orders.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balance);
        ReflectionTestUtils.setField(controller, "omsOrderService", orders);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insight);

        AjaxResult result = controller.getDashboard();

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(balance, never()).selectUmsMemberBalanceDetailList(any(UmsMemberBalanceDetail.class));
        org.mockito.ArgumentCaptor<OmsOrder> orderQuery =
                org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orders).selectOmsOrderList(orderQuery.capture());
        assertEquals(MERCHANT_ROW, orderQuery.getValue().getMmsMerchantId());
        verify(insight).dashboard(OWNER);
    }

    @Test
    void financeDashboardReadsOwnerRevenueWithOwnerMemberScope() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_FINANCE);
        IUmsMemberBalanceDetailService balance = mock(IUmsMemberBalanceDetailService.class);
        IOmsOrderService orders = mock(IOmsOrderService.class);
        IMerchantInsightService insight = mock(IMerchantInsightService.class);
        when(orders.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balance);
        ReflectionTestUtils.setField(controller, "omsOrderService", orders);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insight);

        when(balance.readDashboardIncome(eq(OWNER), any(java.util.Date.class)))
                .thenReturn(new MerchantDashboardIncome(java.math.BigDecimal.ZERO, Collections.emptyMap()));

        AjaxResult result = controller.getDashboard();

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals("0.00", data.get("revenue"));
        assertNotNull(data.get("revenue7d"));
        verify(balance).readDashboardIncome(eq(OWNER), any(java.util.Date.class));
        verify(insight, never()).dashboard(any(Long.class));
        Map<?, ?> windows = (Map<?, ?>) data.get("windows");
        assertEquals(false, windows.containsKey("insight.exposure"));
        assertEquals(false, windows.containsKey("insight.checkin"));
        assertEquals(false, windows.containsKey("insight.supplyCount"));
    }

    @Test
    void marketingOperatorReadsFunnelCustomersAndInsightForOwner() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_MARKETING);
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IProjectPlayerReadService players = mock(IProjectPlayerReadService.class);
        IMerchantInsightService insight = mock(IMerchantInsightService.class);
        when(aggregate.funnel(OWNER, 30)).thenReturn(Collections.singletonMap("ok", true));
        when(players.listMerchantCustomers(eq(OWNER), any(), eq(false)))
                .thenReturn(Collections.singletonMap("rows", Collections.emptyList()));
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "projectPlayerReadService", players);
        ReflectionTestUtils.setField(controller, "merchantInsightService", insight);

        AjaxResult funnel = controller.funnel(null);
        AjaxResult customers = controller.customers(null);

        assertEquals(HttpStatus.SUCCESS, funnel.get(AjaxResult.CODE_TAG));
        assertEquals(HttpStatus.SUCCESS, customers.get(AjaxResult.CODE_TAG));
        verify(aggregate).funnel(OWNER, 30);
        verify(players).listMerchantCustomers(eq(OWNER), any(), eq(false));
        verify(insight, never()).dashboard(any(Long.class));
    }

    @Test
    void checkinOperatorReadsTodoAndEventsScopedToOwner() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_CHECKIN);
        ICmsRegistrationMerchantService offers = mock(ICmsRegistrationMerchantService.class);
        ICmsRegistrationScanService scans = mock(ICmsRegistrationScanService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        IOmsOrderService orders = mock(IOmsOrderService.class);
        MerchantAftercareMapper aftercare = mock(MerchantAftercareMapper.class);
        when(offers.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.emptyList());
        when(scans.selectCmsRegistrationScanList(any(CmsRegistrationScan.class)))
                .thenReturn(Collections.emptyList());
        when(activities.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.emptyList());
        when(orders.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", offers);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scans);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
        ReflectionTestUtils.setField(controller, "omsOrderService", orders);
        ReflectionTestUtils.setField(controller, "merchantAftercareMapper", aftercare);
        ReflectionTestUtils.setField(controller, "playPredictService", mock(IPlayPredictService.class));

        com.chengyinhub.business.service.ICmsTopicService todoTopics = mock(com.chengyinhub.business.service.ICmsTopicService.class);
        com.chengyinhub.business.service.ICmsRegistrationService todoRegistrations = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", todoTopics);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", todoRegistrations);
        AjaxResult todo = controller.todoSummary();
        org.mockito.Mockito.verifyNoInteractions(todoTopics, todoRegistrations);
        AjaxResult events = controller.events();

        assertEquals(HttpStatus.SUCCESS, todo.get(AjaxResult.CODE_TAG));
        assertEquals(HttpStatus.SUCCESS, events.get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<CmsRegistrationMerchant> offerQuery =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(offers).selectCmsRegistrationMerchantList(offerQuery.capture());
        assertEquals(OWNER, offerQuery.getValue().getMemberId());
        org.mockito.ArgumentCaptor<CmsRegistrationScan> scanQuery =
                org.mockito.ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scans, org.mockito.Mockito.atLeastOnce()).selectCmsRegistrationScanList(scanQuery.capture());
        for (CmsRegistrationScan query : scanQuery.getAllValues()) {
            assertEquals(OWNER, query.getMemberId());
        }
        verify(orders, never()).selectOmsOrderList(any(OmsOrder.class));
    }

    @Test
    void everyOperatorEndpointKeepsItsExplicitPermissionGate() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_MANAGER);
        IMerchantAggregateReadService aggregate = mock(IMerchantAggregateReadService.class);
        IProjectPlayerReadService players = mock(IProjectPlayerReadService.class);
        MerchantSubscriptionMapper subscriptions = mock(MerchantSubscriptionMapper.class);
        when(aggregate.funnel(OWNER, 30)).thenReturn(Collections.singletonMap("ok", true));
        when(players.listMerchantCustomers(eq(OWNER), any(), eq(true)))
                .thenReturn(Collections.singletonMap("rows", Collections.emptyList()));
        when(subscriptions.selectMerchantSubscriptionList(any(MerchantSubscription.class)))
                .thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "merchantAggregateReadService", aggregate);
        ReflectionTestUtils.setField(controller, "projectPlayerReadService", players);
        ReflectionTestUtils.setField(controller, "merchantSubscriptionMapper", subscriptions);

        controller.funnel(null);
        controller.customers(null);
        controller.mySubscription();

        verify(merchantAccess(controller)).require(PRINCIPAL, MerchantPermission.MARKETING_READ);
        verify(merchantAccess(controller)).require(PRINCIPAL, MerchantPermission.CRM_READ);
        verify(merchantAccess(controller)).require(PRINCIPAL, MerchantPermission.BASIC_READ);
        org.mockito.ArgumentCaptor<MerchantSubscription> subscriptionQuery =
                org.mockito.ArgumentCaptor.forClass(MerchantSubscription.class);
        verify(subscriptions).selectMerchantSubscriptionList(subscriptionQuery.capture());
        assertEquals(OWNER, subscriptionQuery.getValue().getMerchantId());
    }

    @Test
    void operatorOutsidePermissionStillGetsForbiddenFromAccessService() {
        ApiMerchantController controller = operatorController(MerchantRoleCode.MERCHANT_CHECKIN);
        IMerchantAccessService access = merchantAccess(controller);
        when(access.require(PRINCIPAL, MerchantPermission.CRM_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        ServiceException error = org.junit.jupiter.api.Assertions.assertThrows(
                ServiceException.class, () -> controller.customers(null));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
    }

    private IMerchantAccessService merchantAccess(ApiMerchantController controller) {
        return (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
    }

    private ApiMerchantController operatorController(String roleCode) {
        ApiMerchantController controller = new LoggedInController(PRINCIPAL);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(org.mockito.ArgumentMatchers.eq(PRINCIPAL), any(String.class))).thenReturn(
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

