package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.service.IRefundService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 订单退款展示必须读取退款单打款态，不能由已取消报名反推“已退款”。 */
class ApiRegistrationRefundProjectionTest {

    @Test
    void cancelledActivityManualDecisionOverridesTheOrdinaryRefundWindow() {
        com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper cases =
                mock(com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper.class);
        ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        com.chengyinhub.business.domain.CmsRegistration row =
                new com.chengyinhub.business.domain.CmsRegistration();
        row.setId(12L);
        row.setMemberId(99L);
        row.setOwnerType(2L);
        row.setOwnerId(200L);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);
        row.setParentOrderNo("PARENT");
        row.setRefundInfo(new com.chengyinhub.business.util.RefundPolicy.Verdict(true, "可退款", null));
        when(cases.selectPlayerCaseStatus(12L, 99L)).thenReturn("NO_REFUND");
        com.chengyinhub.business.service.ICmsRegistrationService registrations =
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        com.chengyinhub.business.service.ICmsActivityService activities =
                mock(com.chengyinhub.business.service.ICmsActivityService.class);
        com.chengyinhub.business.domain.CmsActivity activity =
                new com.chengyinhub.business.domain.CmsActivity();
        // 5.9:场次票窗口 = 集合时间前 24h ⇒ now+24h 正好压在边界上(now>deadline 会翻成不可退)。
        // 本用例测的是人工案卷覆盖普通退款窗,不需要踩边界,集合时间放到 48h 后。
        activity.setStartDate(new java.util.Date(System.currentTimeMillis() + 2 * 86400000L));
        activity.setCancelTime(new java.util.Date());
        when(registrations.selectCmsRegistrationById(12L)).thenReturn(row);
        when(registrations.selectCmsRegistrationList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.singletonList(row));
        when(activities.selectCmsActivityById(200L)).thenReturn(activity);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activities);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", mock(IRefundService.class));
        com.chengyinhub.business.service.support.RegistrationCancellationReadService projection =
                new com.chengyinhub.business.service.support.RegistrationCancellationReadService(
                        registrations, mock(IRefundService.class));
        ReflectionTestUtils.setField(projection, "activityCancelManualRefundCaseMapper", cases);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "cancellationReadService", projection);

        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("12").get("code"));

        assertEquals(false, row.getRefundInfo().refundable);
        assertEquals("人工评估结果：不予退款", row.getRefundInfo().reason);
        assertEquals("NO_REFUND", row.getManualRefundCaseStatus());
        verify(cases).selectPlayerCaseStatus(12L, 99L);
        when(cases.selectPlayerCaseStatus(12L, 99L)).thenReturn("PENDING_ASSESSMENT");
        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", "0", null).get("code"));
        assertEquals("活动已取消，该订单正在人工评估退款", row.getRefundInfo().reason);
        String[] statuses = {"MANUAL_REFUND_REQUIRED", "MANUAL_REFUND_RECORDED", "MANUAL_REFUND_EVIDENCE_VERIFIED"};
        String[] reasons = {"人工评估已通过，等待退款处理", "人工退款已登记，等待凭证复核", "人工退款凭证已复核"};
        for (int i = 0; i < statuses.length; i++) {
            when(cases.selectPlayerCaseStatus(12L, 99L)).thenReturn(statuses[i]);
            assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("12").get("code"));
            assertEquals(false, row.getRefundInfo().refundable);
            assertEquals(reasons[i], row.getRefundInfo().reason);
        }
        when(cases.selectPlayerCaseStatus(12L, 99L)).thenReturn(null);
        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("12").get("code"));
        assertEquals(true, row.getRefundInfo().refundable);
        org.mockito.Mockito.clearInvocations(cases);
        row.setMemberId(100L);
        org.junit.jupiter.api.Assertions.assertNotEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("12").get("code"));
        org.mockito.Mockito.verifyNoInteractions(cases);
    }

    /** 1-21 F07 残留:零元单的可退文案不能承诺「原路退回(预计1-3个工作日)」。 */
    @Test
    void zeroCashRefundableOrderDoesNotPromiseOriginalPathRefund() {
        ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        com.chengyinhub.business.domain.CmsRegistration row =
                new com.chengyinhub.business.domain.CmsRegistration();
        row.setId(13L);
        row.setMemberId(99L);
        row.setOwnerType(2L);
        row.setOwnerId(201L);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);
        row.setWechatPaymentAmount(java.math.BigDecimal.ZERO);
        row.setPointUsed(0L);
        com.chengyinhub.business.service.ICmsRegistrationService registrations =
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        com.chengyinhub.business.service.ICmsActivityService activities =
                mock(com.chengyinhub.business.service.ICmsActivityService.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setStartDate(new java.util.Date(System.currentTimeMillis() + 5 * 86400000L));
        when(registrations.selectCmsRegistrationById(13L)).thenReturn(row);
        when(activities.selectCmsActivityById(201L)).thenReturn(activity);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activities);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", mock(IRefundService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "cancellationReadService",
                new com.chengyinhub.business.service.support.RegistrationCancellationReadService(
                        registrations, mock(IRefundService.class)));

        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("13").get("code"));
        assertEquals(true, row.getRefundInfo().refundable);
        assertEquals("可免费取消", row.getRefundInfo().reason);

        row.setPointUsed(100L);
        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("13").get("code"));
        assertEquals("可免费取消,已用积分退回账户", row.getRefundInfo().reason);

        row.setWechatPaymentAmount(new java.math.BigDecimal("9.90"));
        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail("13").get("code"));
        assertEquals("可全额退款,原路退回(预计1-3个工作日)", row.getRefundInfo().reason, "有现金的单文案不变");
    }

    @Test
    void keepsLatestRefundForEachRegistrationAndScopesItToCurrentMember() {
        IRefundService refundService = mock(IRefundService.class);
        ApiRegistrationController controller = new ApiRegistrationController();
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);

        RefundApplication newest = refund(11L, RefundApplication.PAYOUT_PROCESSING);
        RefundApplication older = refund(11L, RefundApplication.PAYOUT_SUCCESS);
        RefundApplication another = refund(12L, RefundApplication.PAYOUT_SUCCESS);
        when(refundService.selectRefundList(org.mockito.ArgumentMatchers.any(RefundApplication.class)))
                .thenReturn(Arrays.asList(newest, older, another));

        @SuppressWarnings("unchecked")
        Map<Long, RefundApplication> result = (Map<Long, RefundApplication>) ReflectionTestUtils.invokeMethod(
                RegistrationQueryTestFixtures.application(controller), "latestRegistrationRefunds", 99L);

        assertEquals(RefundApplication.PAYOUT_PROCESSING, result.get(11L).getPayoutStatus());
        assertEquals(RefundApplication.PAYOUT_SUCCESS, result.get(12L).getPayoutStatus());
        ArgumentCaptor<RefundApplication> query = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refundService).selectRefundList(query.capture());
        assertEquals("registration", query.getValue().getSourceType());
        assertEquals(99L, query.getValue().getMemberId());
    }

    /** FX-02:同单部分核销转人工 —— 玩家/主理人都要被明确告知「已转人工」,不能拿取消回执冒充已退。 */
    @Test
    @SuppressWarnings("unchecked")
    void partiallyVerifiedOrderTellsCallerItWasRoutedToManualRefund() {
        com.chengyinhub.business.service.ITeamGroupService teams =
                mock(com.chengyinhub.business.service.ITeamGroupService.class);
        ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        ReflectionTestUtils.setField(controller, "teamGroupService", teams);
        ReflectionTestUtils.setField(controller, "cancellationReadService",
                cancellationRead(mock(com.chengyinhub.business.service.ICmsRegistrationService.class),
                        mock(IRefundService.class)));
        when(teams.cancelPaidRegistration(11L, 99L))
                .thenReturn(com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW);
        when(teams.cancelPaidRegistrationByOwner(11L, 99L))
                .thenReturn(com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW);

        com.chengyinhub.common.core.domain.AjaxResult player = controller.cancelRefund("11");
        com.chengyinhub.common.core.domain.AjaxResult owner = controller.cancelByOwner("11");
        assertEquals("本单已转平台人工评估，尚未退款", player.get("msg"));
        assertEquals("本单已转平台人工评估，尚未退款", owner.get("msg"),
                "尚未退款且只进入评估，不能承诺已打款或联系主理人");
        for (com.chengyinhub.common.core.domain.AjaxResult result : Arrays.asList(player, owner)) {
            assertEquals(200, result.get("code"));
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            // 小程序 cancellation-feedback.js 只在两字段都在时才展示后端 msg
            assertEquals("MANUAL_REVIEW", data.get("cancellationStatus"));
            assertEquals("MANUAL_REVIEW", data.get("cashRefundStatus"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedPlayerCancellationReturnsExistingFinalCaseWithoutRunningRefundAgain() {
        com.chengyinhub.business.service.ITeamGroupService teams =
                mock(com.chengyinhub.business.service.ITeamGroupService.class);
        com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper cases =
                mock(com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper.class);
        com.chengyinhub.business.service.ICmsRegistrationService registrations =
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        IRefundService refunds = mock(IRefundService.class);
        ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        com.chengyinhub.business.service.support.RegistrationCancellationReadService projection =
                new com.chengyinhub.business.service.support.RegistrationCancellationReadService(
                        registrations, refunds);
        ReflectionTestUtils.setField(projection, "activityCancelManualRefundCaseMapper", cases);
        ReflectionTestUtils.setField(controller, "teamGroupService", teams);
        ReflectionTestUtils.setField(controller, "cancellationReadService", projection);
        when(cases.selectPlayerCaseStatus(11L, 99L))
                .thenReturn(com.chengyinhub.business.domain.ActivityCancelManualRefundCase.STATUS_NO_REFUND);

        com.chengyinhub.common.core.domain.AjaxResult result = controller.cancelRefund("11");

        assertEquals(200, result.get("code"));
        assertEquals("人工评估结果：不予退款", result.get("msg"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("NO_REFUND", data.get("manualRefundCaseStatus"));
        assertEquals("MANUAL_REVIEW", data.get("cancellationStatus"));
        assertEquals("MANUAL_REVIEW", data.get("cashRefundStatus"));
        verify(cases).selectPlayerCaseStatus(11L, 99L);
        org.mockito.Mockito.verifyNoInteractions(teams, registrations, refunds);
    }

    @Test
    void zeroCashCancellationDoesNotClaimMoneyOrPointsWereReturned() {
        com.chengyinhub.business.service.ITeamGroupService teams =
                mock(com.chengyinhub.business.service.ITeamGroupService.class);
        com.chengyinhub.business.service.ICmsRegistrationService registrations =
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        ReflectionTestUtils.setField(controller, "teamGroupService", teams);
        ReflectionTestUtils.setField(controller, "registrationService", registrations);
        ReflectionTestUtils.setField(controller, "cancellationReadService",
                cancellationRead(registrations, mock(IRefundService.class)));
        com.chengyinhub.business.domain.CmsRegistration row =
                new com.chengyinhub.business.domain.CmsRegistration();
        row.setId(11L);
        row.setMemberId(99L);
        row.setRegistrationStatus(3);
        row.setWechatPaymentAmount(java.math.BigDecimal.ZERO);
        row.setPointUsed(0L);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(row);

        com.chengyinhub.common.core.domain.AjaxResult result = controller.cancelRefund("11");

        assertEquals("报名已取消，无现金退款，本单未使用积分", result.get("msg"));
        verify(teams).cancelPaidRegistration(11L, 99L);
    }

    @Test
    void cashCancellationReportsChannelStateInsteadOfAssumingArrival() {
        Fixture f = new Fixture();
        f.row.setWechatPaymentAmount(new java.math.BigDecimal("12.50"));
        RefundApplication application = refund(11L, RefundApplication.PAYOUT_DISPATCH_PENDING);
        application.setStatus(1);
        application.setRefundAmount(new java.math.BigDecimal("12.50"));
        when(f.refunds.selectRefundList(org.mockito.ArgumentMatchers.any())).thenReturn(
                java.util.Collections.singletonList(application));
        int[] states = {5, 6, 0, 1, 2, 3, 4};
        String[] messages = {"现金退款已受理，尚未派发", "现金退款正在派发，尚未确认到账",
                "现金退款渠道处理中，尚未确认到账", "现金退款已确认到账",
                "现金退款失败，待人工处理", "现金退款已人工处理，待复核", "现金退款已完成人工复核"};
        for (int i = 0; i < states.length; i++) {
            application.setPayoutStatus(states[i]);
            assertEquals("报名已取消，" + messages[i] + "，本单未使用积分",
                    f.controller.cancelRefund("11").get("msg"));
        }
    }

    @Test
    void pointsCancellationUsesCommittedReturnMarkerForBothCancellationEntrypoints() {
        Fixture f = new Fixture();
        f.row.setPointUsed(100L);
        f.row.setPointsReturned(1);
        assertEquals("报名已取消，无现金退款，本单已返还100积分",
                f.controller.cancelByOwner("11").get("msg"));
        verify(f.teams).cancelPaidRegistrationByOwner(11L, 99L);
        f.row.setPointsReturned(0);
        assertEquals("报名已取消，无现金退款，本单已返还0/100积分，其余尚未确认",
                f.controller.cancelRefund("11").get("msg"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void childCancellationReadsPayerRefundAndWholeOrderPointReturns() {
        Fixture f = new Fixture();
        f.row.setParentOrderNo("ORDER-7");
        f.row.setPayableAmount(java.math.BigDecimal.ZERO);
        f.row.setWechatPaymentAmount(new java.math.BigDecimal("999"));
        f.row.setPointUsed(100L);
        f.row.setPointsReturned(1);
        com.chengyinhub.business.domain.CmsRegistration payer =
                new com.chengyinhub.business.domain.CmsRegistration();
        payer.setId(12L);
        payer.setMemberId(99L);
        payer.setParentOrderNo("ORDER-7");
        payer.setRegistrationStatus(3);
        payer.setPayableAmount(new java.math.BigDecimal("25"));
        payer.setWechatPaymentAmount(new java.math.BigDecimal("25"));
        payer.setPointUsed(50L);
        payer.setPointsReturned(0);
        when(f.registrations.selectCmsRegistrationList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(f.row, payer));
        RefundApplication application = refund(12L, RefundApplication.PAYOUT_PROCESSING);
        application.setStatus(1);
        application.setRefundAmount(new java.math.BigDecimal("25"));
        when(f.refunds.selectRefundList(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            RefundApplication query = call.getArgument(0);
            assertEquals(12L, query.getSourceId(), "从单假现金不是退款真源");
            assertEquals(99L, query.getMemberId());
            return java.util.Collections.singletonList(application);
        });

        Map<String, Object> data = (Map<String, Object>) f.controller.cancelRefund("11").get("data");

        assertEquals("PARENT_ORDER", data.get("scope"));
        assertEquals(new java.math.BigDecimal("25"), data.get("cashRefundAmount"));
        assertEquals(150L, data.get("pointsUsed"));
        assertEquals(100L, data.get("pointsReturned"));
        assertEquals("PARTIAL", data.get("pointsRefundStatus"));
        ArgumentCaptor<com.chengyinhub.business.domain.CmsRegistration> query =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.CmsRegistration.class);
        verify(f.registrations).selectCmsRegistrationList(query.capture());
        assertEquals("ORDER-7", query.getValue().getParentOrderNo());
    }

    @Test
    @SuppressWarnings("unchecked")
    void committedCancellationReadFailureIsNotReportedAsCancellationFailure() {
        Fixture f = new Fixture();
        when(f.registrations.selectCmsRegistrationById(11L)).thenThrow(new RuntimeException("read unavailable"));

        com.chengyinhub.common.core.domain.AjaxResult result = f.controller.cancelRefund("11");

        assertEquals(200, result.get("code"));
        assertEquals("取消操作已提交，结果暂时无法读取，请刷新订单确认", result.get("msg"));
        assertEquals("UNCONFIRMED", ((Map<String, Object>) result.get("data")).get("cashRefundStatus"));
        verify(f.teams).cancelPaidRegistration(11L, 99L);
    }

    @Test
    void repeatedCancellationRetainsExistingRejectionWithoutReadingOrClaimingNewRefund() {
        Fixture f = new Fixture();
        when(f.teams.cancelPaidRegistration(11L, 99L))
                .thenThrow(new RuntimeException("仅已支付报名可取消退款"));
        com.chengyinhub.common.core.domain.AjaxResult result = f.controller.cancelRefund("11");
        assertEquals(500, result.get("code"));
        assertEquals("仅已支付报名可取消退款", result.get("msg"));
        verify(f.registrations, org.mockito.Mockito.never()).selectCmsRegistrationById(11L);
        org.mockito.Mockito.verifyNoInteractions(f.refunds);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purePointsParentOrderIgnoresAllChildCashPlaceholders() {
        Fixture f = new Fixture();
        f.row.setParentOrderNo("ORDER-POINTS");
        f.row.setPayableAmount(java.math.BigDecimal.ZERO);
        f.row.setWechatPaymentAmount(new java.math.BigDecimal("999"));
        f.row.setPointUsed(100L);
        f.row.setPointsReturned(1);
        when(f.registrations.selectCmsRegistrationList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.singletonList(f.row));
        com.chengyinhub.common.core.domain.AjaxResult result = f.controller.cancelRefund("11");
        assertEquals("报名已取消，无现金退款，本单已返还100积分", result.get("msg"));
        assertEquals("NOT_NEEDED", ((Map<String, Object>) result.get("data")).get("cashRefundStatus"));
        org.mockito.Mockito.verifyNoInteractions(f.refunds);
    }

    @Test
    @SuppressWarnings("unchecked")
    void refundReadFailurePreservesConfirmedCancellationAndPointFacts() {
        Fixture f = new Fixture();
        f.row.setWechatPaymentAmount(java.math.BigDecimal.TEN);
        f.row.setPointUsed(20L);
        f.row.setPointsReturned(1);
        when(f.refunds.selectRefundList(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("refund read unavailable"));
        Map<String, Object> data = (Map<String, Object>) f.controller.cancelRefund("11").get("data");
        assertEquals("CANCELLED", data.get("cancellationStatus"));
        assertEquals("UNCONFIRMED", data.get("cashRefundStatus"));
        assertEquals(20L, data.get("pointsReturned"));
        assertEquals("RETURNED", data.get("pointsRefundStatus"));
    }

    private static class Fixture {
        final com.chengyinhub.business.service.ITeamGroupService teams =
                mock(com.chengyinhub.business.service.ITeamGroupService.class);
        final com.chengyinhub.business.service.ICmsRegistrationService registrations =
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        final IRefundService refunds = mock(IRefundService.class);
        final com.chengyinhub.business.domain.CmsRegistration row =
                new com.chengyinhub.business.domain.CmsRegistration();
        final ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        Fixture() {
            row.setId(11L);
            row.setMemberId(99L);
            row.setRegistrationStatus(3);
            row.setWechatPaymentAmount(java.math.BigDecimal.ZERO);
            row.setPointUsed(0L);
            when(registrations.selectCmsRegistrationById(11L)).thenReturn(row);
            ReflectionTestUtils.setField(controller, "teamGroupService", teams);
            ReflectionTestUtils.setField(controller, "registrationService", registrations);
            ReflectionTestUtils.setField(controller, "refundService", refunds);
            ReflectionTestUtils.setField(controller, "cancellationReadService",
                    cancellationRead(registrations, refunds));
        }
    }

    private static com.chengyinhub.business.service.support.RegistrationCancellationReadService cancellationRead(
            com.chengyinhub.business.service.ICmsRegistrationService registrations,
            IRefundService refunds) {
        com.chengyinhub.business.service.support.RegistrationCancellationReadService service =
                new com.chengyinhub.business.service.support.RegistrationCancellationReadService(
                        registrations, refunds);
        ReflectionTestUtils.setField(service, "activityCancelManualRefundCaseMapper",
                mock(com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper.class));
        return service;
    }

    private RefundApplication refund(Long sourceId, Integer payoutStatus) {
        RefundApplication row = new RefundApplication();
        row.setSourceId(sourceId);
        row.setPayoutStatus(payoutStatus);
        return row;
    }
}
