package com.chengyinhub.web.service.registration;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.support.RegistrationCancellationReadService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
import com.chengyinhub.web.controller.api.RegistrationQueryTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/registration/info 的退款投影接线：父单聚合必须按「登录账号 + 父单号」查询，
 * 不能先全量读取再在内存裁剪；单票订单不得触发父单查询。
 */
class RegistrationSettlementDetailContractTest {

    private static final Long MEMBER = 99L;
    private static final Long REG = 700L;
    private static final Long ACTIVITY = 800L;

    private ApiRegistrationController controller;
    private ICmsRegistrationService registrations;
    private IRefundService refunds;

    @BeforeEach
    void setUp() {
        registrations = mock(ICmsRegistrationService.class);
        refunds = mock(IRefundService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller),
                "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller),
                "refundService", refunds);

        ICmsActivityService activities = mock(ICmsActivityService.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setStartDate(new Date(System.currentTimeMillis() + 5 * 86_400_000L));
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller),
                "activityService", activities);
        RegistrationCancellationReadService cancellationRead = new RegistrationCancellationReadService(
                registrations, refunds);
        ReflectionTestUtils.setField(cancellationRead, "activityCancelManualRefundCaseMapper",
                mock(com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller),
                "cancellationReadService", cancellationRead);
    }

    private CmsRegistration order(String parentOrderNo) {
        CmsRegistration row = new CmsRegistration();
        row.setId(REG);
        row.setMemberId(MEMBER);
        row.setOwnerType(2L);
        row.setOwnerId(ACTIVITY);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);
        row.setOrderNum(1L);
        row.setPayableAmount(new BigDecimal("60.00"));
        row.setParentOrderNo(parentOrderNo);
        return row;
    }

    @Test
    void parentOrderAggregationIsScopedInTheQueryNotAfterIt() {
        CmsRegistration row = order("PARENT-9");
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(row);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of(row));
        when(refunds.selectRefundList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));
        assertEquals(200, result.get("code"));

        ArgumentCaptor<CmsRegistration> orderProbe = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations).selectCmsRegistrationList(orderProbe.capture());
        assertEquals(MEMBER, orderProbe.getValue().getMemberId(), "父单聚合必须带登录账号条件");
        assertEquals("PARENT-9", orderProbe.getValue().getParentOrderNo());

        ArgumentCaptor<RefundApplication> refundProbe = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refunds, org.mockito.Mockito.atLeastOnce()).selectRefundList(refundProbe.capture());
        assertEquals(MEMBER, refundProbe.getValue().getMemberId(), "退款历史必须带登录账号条件");
        assertEquals("registration", refundProbe.getValue().getSourceType());

        CmsRegistration detail = (CmsRegistration) result.get(AjaxResult.DATA_TAG);
        assertNotNull(detail.getSettlementSummary());
        assertEquals(0, new BigDecimal("60.00").compareTo(detail.getSettlementSummary().getOrderPaidAmount()));
        assertEquals(1, detail.getSettlementSummary().getTicketCount().intValue());
        assertEquals("NONE", detail.getSettlementSummary().getRefundStage());
    }

    @Test
    void singleTicketOrderDoesNotWidenIntoAParentQuery() {
        CmsRegistration row = order(null);
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(row);
        when(refunds.selectRefundList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));
        assertEquals(200, result.get("code"));
        verify(registrations, never()).selectCmsRegistrationList(any());

        CmsRegistration detail = (CmsRegistration) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, detail.getSettlementSummary().getTicketCount().intValue());
        assertEquals(Boolean.TRUE, detail.getSettlementSummary().getAmountsComplete());
    }

    /** 退款查询异常时详情读回失败，而不是展示成「零退款」。 */
    @Test
    void refundReadFailureFailsTheReadInsteadOfFakingZero() {
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(order(null));
        when(refunds.selectRefundList(any())).thenThrow(new IllegalStateException("db down"));

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));
        assertTrue(result.get("code") != null && !Integer.valueOf(200).equals(result.get("code")),
                "读不到退款事实时必须失败");
    }
}
