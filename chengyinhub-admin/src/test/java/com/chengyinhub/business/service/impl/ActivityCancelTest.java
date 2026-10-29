package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主办方取消活动 → 对该活动下所有【已付款且未核销】的报名整单全额退款(0 手续费)。
 *
 * 0 手续费全额退是跨平台唯一无分歧的口径:主办方违约却扣用户钱,法理站不住、也是舆情雷。
 * 故本路径【刻意绕开 RefundPolicy 的时间窗】—— 那是买家【主动】退票的规则,主办方取消不适用。
 *
 * 背景:此前 cms_activity 根本没有取消态,/api/activity/delete 只软删 del_flag=2,
 * 不碰任何 registration、不退款、不回补库存 ⇒ 主办方删掉有人付款的活动,用户钱原地悬空。
 */
class ActivityCancelTest {

    private static final Long OWNER_ID = 77L;
    private static final Long OTHER_MEMBER = 78L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TICKET_ID = 300L;
    private static final String PARENT = "P20260715abcd1234";

    // [D-2] 打款已收口到 payoutAfterReview(后台审核与玩家自退两路共用)。本类验的是
    // TeamGroupServiceImpl 的职责:「哪些单该退、按哪一单以什么金额建退款单、打款被触发几次」;
    // 「拿退款单调微信、out_trade_no=registrationNo」由 RefundPayoutServiceTest 覆盖。
    // sourceId→registrationNo 是确定映射(reg(1L,"R001") ⇒ sourceId=1 即 R001),故断言 sourceId 等价于原来断言 out_trade_no。
    private IRefundPayoutService refundPayoutService;
    private IRefundService refundService;
    private CmsRegistrationMapper regMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private IActivityCancelManualRefundCaseService manualRefundCaseService;
    private IImService imService;

    private TeamGroupServiceImpl service(CmsActivity activity, List<CmsRegistration> paid) {
        refundPayoutService = mock(IRefundPayoutService.class);
        regMapper = mock(CmsRegistrationMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        refundService = mock(IRefundService.class);
        manualRefundCaseService = mock(IActivityCancelManualRefundCaseService.class);
        imService = mock(IImService.class);
        when(imService.sendSystem(anyLong(), any(), anyString(), anyString())).thenReturn(910L);

        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY_ID)).thenReturn(activity);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(activityMapper.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);
        when(regMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(invocation -> {
            CmsRegistration query = invocation.getArgument(0, CmsRegistration.class);
            if (query.getPaymentStatus() == null) {
                // The actual current SQL is owner-scoped. Malformed parent rows stay in the parent-lock stub.
                return paid.stream().filter(row -> row == null
                        || (java.util.Objects.equals(query.getOwnerType(), row.getOwnerType())
                        && java.util.Objects.equals(query.getOwnerId(), row.getOwnerId()))).toList();
            }
            return Integer.valueOf(2).equals(query.getPaymentStatus())
                    ? paid : java.util.Collections.<CmsRegistration>emptyList();
        });
        when(regMapper.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(invocation -> {
            Long registrationId = invocation.getArgument(0, Long.class);
            if (paid == null) {
                return null;
            }
            for (CmsRegistration row : paid) {
                if (row != null && registrationId.equals(row.getId())) {
                    return row;
                }
            }
            return null;
        });
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(999L);
            return 1;
        });

        OmsTicket t = new OmsTicket();
        t.setId(TICKET_ID);
        t.setName("标准票");
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(t);
        when(ticketMapper.restoreInventory(anyLong(), anyLong())).thenReturn(1);

        TeamGroupServiceImpl s = new TeamGroupServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(s, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(s, "omsTicketMapper", ticketMapper);
        // [P0-15] ③ 退款释放权益容量 —— 本类不测该链路,注入空 mock 即可;
        //        不注入会 NPE(手工装配不走 Spring 容器)。
        ReflectionTestUtils.setField(s, "perkLedgerService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        ReflectionTestUtils.setField(s, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(s, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(s, "refundService", refundService);
        ReflectionTestUtils.setField(s, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(s, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(s, "imService", imService);
        ReflectionTestUtils.setField(s, "refundPayoutService", refundPayoutService);
        ReflectionTestUtils.setField(s, "manualRefundCaseService", manualRefundCaseService);
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireTeamUnit(s, regMapper);
        return s;
    }

    /** 捕获建单参数:退哪一单、退多少,现在体现在退款单上(sourceId ⇒ out_trade_no)。 */
    private RefundApplication capturedRefundApp() {
        ArgumentCaptor<RefundApplication> cap = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refundService).saveRefund(cap.capture());
        return cap.getValue();
    }

    private CmsActivity liveActivity() {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY_ID);
        a.setName("外滩夜行");
        a.setMemberId(OWNER_ID);
        a.setDelFlag(0);
        a.setPublishStatus(1L);
        return a;
    }

    private CmsRegistration reg(Long id, String no, String payable, String parentOrderNo, int verification) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setRegistrationNo(no);
        r.setMemberId(500L + id);
        r.setTicketId(TICKET_ID);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY_ID);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(verification);
        r.setPointUsed(0L);
        r.setOrderNum(1L);
        r.setParentOrderNo(parentOrderNo);
        r.setPayableAmount(new BigDecimal(payable));
        r.setWechatPaymentAmount(new BigDecimal(payable));
        return r;
    }

    private void sameMemberAs(CmsRegistration child, CmsRegistration seed) {
        child.setMemberId(seed.getMemberId());
    }

    private void assertSnapshotConflictWithoutSideEffects(TeamGroupServiceImpl service) {
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地不可用"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(manualRefundCaseService, never()).recordCase(any(ActivityCancelManualRefundCase.class));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    private CmsRegistration pending(Long id) {
        CmsRegistration row = reg(id, "PENDING" + id, "68.00", null, 0);
        row.setPaymentStatus(0);
        row.setRegistrationStatus(1);
        return row;
    }

    private void notificationSnapshot(List<CmsRegistration> recipients) {
        org.mockito.Mockito.doAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            assertEquals(Long.valueOf(2L), probe.getOwnerType());
            assertEquals(ACTIVITY_ID, probe.getOwnerId());
            return probe.getPaymentStatus() == null ? recipients
                    : java.util.Collections.<CmsRegistration>emptyList();
        }).when(regMapper).selectCmsRegistrationList(any(CmsRegistration.class));
        // The old read-only notification projection is now the actual current-row port used by all phases.
        org.mockito.Mockito.doAnswer(call -> recipients.stream()
                .filter(row -> call.getArgument(0).equals(row.getId())).findFirst().orElse(null))
                .when(regMapper).selectCmsRegistrationByIdForUpdate(anyLong());
        org.mockito.Mockito.doAnswer(call -> recipients.stream().anyMatch(row ->
                call.getArgument(0).equals(row.getId()) && Integer.valueOf(2).equals(row.getPaymentStatus())
                        && Integer.valueOf(0).equals(row.getVerificationStatus())) ? 1 : 0)
                .when(regMapper).claimRegistrationRefunded(anyLong(), anyString(), any(Date.class));
        org.mockito.Mockito.doAnswer(call -> recipients.stream().anyMatch(row ->
                call.getArgument(0).equals(row.getId()) && call.getArgument(1).equals(row.getMemberId())
                        && Integer.valueOf(0).equals(row.getPaymentStatus())
                        && Integer.valueOf(1).equals(row.getRegistrationStatus())) ? 1 : 0)
                .when(regMapper).cancelUnpaidIfActive(anyLong(), anyLong(), anyString());
    }

    @Test
    void standaloneCancellationNotifiesPaidPendingAndPartlyFulfilledPlayersOncePerMember() {
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.emptyList());
        CmsRegistration paid = reg(1L, "R1", "0", null, 0);
        CmsRegistration duplicate = reg(2L, "R2", "0", null, 1);
        sameMemberAs(duplicate, paid);
        CmsRegistration waiting = pending(3L);
        CmsRegistration alreadyCancelled = reg(4L, "R4", "0", null, 0);
        alreadyCancelled.setRegistrationStatus(3);
        alreadyCancelled.setPaymentStatus(4);
        notificationSnapshot(Arrays.asList(paid, duplicate, waiting, alreadyCancelled));

        s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        verify(imService, times(2)).sendSystem(anyLong(), any(), content.capture(), extra.capture());
        verify(imService).sendSystem(org.mockito.ArgumentMatchers.eq(paid.getMemberId()), any(), anyString(), anyString());
        verify(imService).sendSystem(org.mockito.ArgumentMatchers.eq(waiting.getMemberId()), any(), anyString(), anyString());
        org.junit.jupiter.api.Assertions.assertTrue(content.getValue().contains("已取消"));
        org.junit.jupiter.api.Assertions.assertFalse(content.getValue().contains("已退回"));
        org.junit.jupiter.api.Assertions.assertFalse(content.getValue().contains("退款任务已提交"));
        org.junit.jupiter.api.Assertions.assertTrue(extra.getValue().contains("\"activityId\":200"));
    }

    @Test
    void standaloneCancellationDoesNotCommitWithoutAuthoritativeNotificationReceipt() {
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.emptyList());
        notificationSnapshot(java.util.Collections.singletonList(pending(3L)));
        when(imService.sendSystem(anyLong(), any(), anyString(), anyString())).thenReturn(null);
        assertThrows(ServiceException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
    }

    @Test
    void legacyZeroClubIdReceivesStandaloneCancellationNotice() {
        CmsActivity standalone = liveActivity();
        standalone.setClubId(0L);
        TeamGroupServiceImpl s = service(standalone, java.util.Collections.emptyList());
        notificationSnapshot(java.util.Collections.singletonList(pending(3L)));
        s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因");
        verify(imService).sendSystem(org.mockito.ArgumentMatchers.eq(503L), any(), anyString(), anyString());
    }

    @Test
    void invalidActiveNoticeRecipientPreventsCancellation() {
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.emptyList());
        CmsRegistration invalid = pending(3L);
        invalid.setMemberId(null);
        notificationSnapshot(java.util.Collections.singletonList(invalid));
        assertThrows(ServiceException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
        verify(activityMapper, never()).updateCmsActivity(any());
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void failedActivityStateWriteDoesNotSendCancellationNotice() {
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.emptyList());
        notificationSnapshot(java.util.Collections.singletonList(pending(3L)));
        when(activityMapper.updateCmsActivity(any())).thenReturn(0);
        assertThrows(ServiceException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void repeatedCancellationDoesNotReadRecipientsOrSendAgain() {
        CmsActivity cancelled = liveActivity();
        cancelled.setCancelTime(new Date());
        TeamGroupServiceImpl s = service(cancelled, java.util.Collections.emptyList());
        assertThrows(ServiceException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
        verify(regMapper, never()).selectCmsRegistrationList(any());
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void clubOccurrenceKeepsItsExistingCampaignNotificationPath() {
        CmsActivity club = liveActivity();
        club.setClubId(7L);
        TeamGroupServiceImpl s = service(club, java.util.Collections.emptyList());
        com.chengyinhub.business.mapper.ClubMapper clubs = mock(com.chengyinhub.business.mapper.ClubMapper.class);
        com.chengyinhub.business.domain.Club owner = new com.chengyinhub.business.domain.Club();
        owner.setMemberId(OWNER_ID);
        owner.setDelFlag(0);
        when(clubs.selectClubById(7L)).thenReturn(owner);
        when(clubs.selectClubByIdForUpdate(7L)).thenReturn(owner);
        ReflectionTestUtils.setField(s, "clubMapper", clubs);
        s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因");
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void standaloneCancellationRejectsForeignRecipientRowsBeforeAnyRefund() {
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.emptyList());
        CmsRegistration foreign = pending(3L);
        foreign.setOwnerId(ACTIVITY_ID + 1);
        notificationSnapshot(java.util.Collections.singletonList(foreign));
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
        assertEquals("原取消报名当前范围不一致", refusal.getMessage());
        verify(activityMapper, never()).updateCmsActivity(any());
        verify(imService, never()).sendSystem(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void cancellationAndFirstNoticeRollBackWhenSecondNoticeFailsThenRetryCommitsOnlyOnce() {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:h2:mem:cancel_notice_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("create table cancellation (id bigint primary key, cancelled integer)");
        jdbc.execute("create table cancellation_notice (member_id bigint primary key)");
        jdbc.update("insert into cancellation values (?,0)", ACTIVITY_ID);
        TeamGroupServiceImpl target = service(liveActivity(), java.util.Collections.emptyList());
        notificationSnapshot(Arrays.asList(pending(1L), pending(2L)));
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY_ID)).thenAnswer(inv -> {
            CmsActivity activity = liveActivity();
            if (jdbc.queryForObject("select cancelled from cancellation where id=?", Integer.class, ACTIVITY_ID) == 1) {
                activity.setCancelTime(new Date());
            }
            return activity;
        });
        when(activityMapper.updateCmsActivity(any())).thenAnswer(inv ->
                jdbc.update("update cancellation set cancelled=1 where id=?", ACTIVITY_ID));
        java.util.concurrent.atomic.AtomicBoolean failSecond = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(imService.sendSystem(anyLong(), any(), anyString(), anyString())).thenAnswer(inv -> {
            Long member = inv.getArgument(0);
            jdbc.update("insert into cancellation_notice values (?)", member);
            if (member.equals(502L) && failSecond.get()) throw new ServiceException("通知保存失败");
            return member;
        });
        org.springframework.jdbc.datasource.DataSourceTransactionManager originalManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireTeamTransactions(target, originalManager);
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                originalManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        com.chengyinhub.business.service.ITeamGroupService transactional =
                (com.chengyinhub.business.service.ITeamGroupService) proxy.getProxy();
        try {
            assertThrows(ServiceException.class,
                    () -> transactional.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
            assertEquals(0, jdbc.queryForObject("select cancelled from cancellation", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from cancellation_notice", Integer.class));
            failSecond.set(false);
            transactional.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因");
            assertEquals(1, jdbc.queryForObject("select cancelled from cancellation", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from cancellation_notice", Integer.class));
            assertThrows(ServiceException.class,
                    () -> transactional.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "天气原因"));
            assertEquals(2, jdbc.queryForObject("select count(*) from cancellation_notice", Integer.class));
            assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRED,
                    ImServiceImpl.class.getMethod("sendSystem", Long.class, Integer.class, String.class, String.class)
                            .getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation());
        } catch (NoSuchMethodException impossible) {
            throw new AssertionError(impossible);
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void onlyActivityOwnerCanCancel() {
        TeamGroupServiceImpl s = service(liveActivity(), new ArrayList<>());
        // 断言收紧到 ServiceException:不收紧就是假绿 —— ServiceException extends RuntimeException,
        // 写 RuntimeException.class 时改前改后恒绿,测不出「入口靠类型区分该不该给用户看文案」这件事。
        assertThrows(ServiceException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OTHER_MEMBER, "临时有事"),
                "非活动创建者不得取消他人活动(IDOR);且必须是 ServiceException —— 入口据此原样回显文案");
        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }

    @Test
    void cancellingRefundsEveryPaidUnverifiedRegistration() {
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(
                reg(1L, "R001", "68.00", null, 0),
                reg(2L, "R002", "68.00", null, 0)));
        TeamGroupServiceImpl s = service(liveActivity(), paid);

        int n = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地临时不可用").getRefundedOrders();

        assertEquals(2, n, "两位已付款用户都该被退款");
        verify(refundPayoutService, times(2)).payoutAfterReview(999L);
    }

    /**
     * 负控:活动取消尚未提交时绝不能触发不可回滚的微信退款；否则后续报名库存回补失败时会出现
     * 本地回滚、钱已退的资金分叉。提交后才派发，退款单本身以 DISPATCH_PENDING 持久化供宕机重试。
     */
    @Test
    void cancellationDispatchesPayoutOnlyAfterTransactionCommit() {
        TeamGroupServiceImpl s = service(liveActivity(),
                new ArrayList<>(Arrays.asList(reg(1L, "R001", "68.00", null, 0))));

        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireTeamTransactions(s,
                com.chengyinhub.business.service.CancellationPhaseTestSupport.memoryTransactions(() ->
                        verify(refundPayoutService, never()).payoutAfterReview(anyLong())));
        s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地临时不可用");
        verify(refundPayoutService).payoutAfterReview(999L);
    }

    /**
     * 负控:第一单已完成本地退款准备、第二单库存回补失败时，事务会回滚；在提交前不得有任何网关调用。
     * 修复前第一单会同步退款，随后第二单抛 409，形成“钱已退、本地未退”的资损状态。
     */
    @Test
    void laterInventoryFailureNeverDispatchesEarlierPayout() {
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(
                reg(1L, "R001", "68.00", null, 0),
                reg(2L, "R002", "68.00", null, 0)));
        TeamGroupServiceImpl s = service(liveActivity(), paid);
        when(ticketMapper.restoreInventory(anyLong(), anyLong())).thenReturn(1, 0);

        assertThrows(ServiceException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地临时不可用"));
        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }

    @Test
    void multiTicketOrderIsRefundedOnceNotPerRow() {
        // 同一父订单的两条(首单承载汇总 200,从单 payable=0)只该整单退一次,
        // 不能对每条各调一次微信 —— 从单单号从来不是 out_trade_no。
        CmsRegistration first = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration sub = reg(2L, "R002", "0.00", PARENT, 0);
        sameMemberAs(sub, first);
        sub.setWechatPaymentAmount(new BigDecimal("100.00"));   // 假现金,必须被无视
        List<CmsRegistration> order = new ArrayList<>(Arrays.asList(first, sub));
        TeamGroupServiceImpl s = service(liveActivity(), order);
        when(regMapper.selectByParentOrderNo(PARENT)).thenReturn(order);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID)).thenReturn(order);
        // 未核销的已付从单,真库 claim 必然领到;mock 默认 0 = 「被并发核销/退款抢走」,RV-p1 1-43 起会拒退
        when(regMapper.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class))).thenReturn(1);

        int n = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "下雨").getRefundedOrders();

        assertEquals(1, n, "一个父订单算一单,不该按行数计");
        verify(refundPayoutService, times(1)).payoutAfterReview(999L);
        RefundApplication app = capturedRefundApp();
        assertEquals(1L, app.getSourceId(), "只能退首单那笔真实交易(sourceId=1 ⇒ out_trade_no=R001)");
        assertEquals(0, new BigDecimal("200.00").compareTo(app.getRefundAmount()), "退汇总 200,不是从单假现金 100");
    }

    /** F-L4-02:同单从单挂着待审退款申请时,主办方取消先取代它,再整单退一次,而不是 409 回滚整场取消。 */
    @Test
    void pendingApplicationInMultiTicketOrderIsSupersededBeforeCancelRefund() {
        CmsRegistration first = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration sub = reg(2L, "R002", "0.00", PARENT, 0);
        sameMemberAs(sub, first);
        List<CmsRegistration> order = new ArrayList<>(Arrays.asList(first, sub));
        TeamGroupServiceImpl s = service(liveActivity(), order);
        when(regMapper.selectByParentOrderNo(PARENT)).thenReturn(order);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID)).thenReturn(order);
        when(regMapper.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class))).thenReturn(1);
        RefundApplication pending = new RefundApplication();
        pending.setId(50L);
        pending.setSourceId(2L);
        pending.setStatus(0);
        pending.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        pending.setRefundAmount(new BigDecimal("100.00"));
        when(refundService.selectRegistrationHistoryForUpdate(2L)).thenReturn(Arrays.asList(pending));
        when(refundService.supersedePendingRegistrationRefunds(org.mockito.ArgumentMatchers.eq(2L), anyString()))
                .thenAnswer(inv -> {
                    pending.setStatus(2);
                    return 1;
                });

        int n = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "下雨").getRefundedOrders();

        assertEquals(1, n);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(refundService);
        inOrder.verify(refundService).supersedePendingRegistrationRefunds(org.mockito.ArgumentMatchers.eq(2L), anyString());
        inOrder.verify(refundService).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, times(1)).payoutAfterReview(999L);
        assertEquals(0, new BigDecimal("200.00").compareTo(capturedRefundApp().getRefundAmount()));
    }

    @Test
    void verifiedRegistrationIsSkippedBecauseServiceWasConsumed() {
        // 已核销 = 用户已参加、服务已交付,主办方事后取消不该把这笔也退了
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(
                reg(1L, "R001", "68.00", null, 1),   // 已核销
                reg(2L, "R002", "68.00", null, 0)));
        TeamGroupServiceImpl s = service(liveActivity(), paid);

        int n = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "取消").getRefundedOrders();

        assertEquals(1, n, "只退未核销那一笔");
        verify(refundPayoutService, times(1)).payoutAfterReview(999L);
        assertEquals(2L, capturedRefundApp().getSourceId(), "退的应是未核销的 R002(sourceId=2)");
    }

    @Test
    void oneVerifiedTicketInAMultiTicketOrderMustNotBlockTheWholeCancellation() {
        // 多票订单里 A 未核销、B 已核销:循环走到 A 时 A 自己没核销 → 会去调整单退 →
        // 而整单退的规则是「同单任一已核销就拒退」→ 抛异常 → 整个活动取消回滚。
        // 一张已核销的票就能让主办方永远取消不了活动。该单应被跳过,其余订单照退。
        CmsRegistration a = reg(1L, "R001", "200.00", PARENT, 0);   // 未核销(首单)
        CmsRegistration b = reg(2L, "R002", "0.00", PARENT, 1);     // 已核销(从单)
        sameMemberAs(b, a);
        CmsRegistration solo = reg(3L, "R003", "68.00", null, 0);   // 另一个单票订单,应正常退
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(a, b, solo));
        TeamGroupServiceImpl s = service(liveActivity(), paid);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(a, b)));

        int n = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地不可用").getRefundedOrders();

        assertEquals(1, n, "含已核销票的那单跳过,单票订单 R003 仍应退");
        verify(refundPayoutService, times(1)).payoutAfterReview(999L);
        assertEquals(3L, capturedRefundApp().getSourceId(), "只退干净的那单(sourceId=3 ⇒ R003)");
    }

    @Test
    void partiallyVerifiedOrderIsReportedForManualHandling() {
        // 部分核销:A 未核销(钱该退却退不了,因为整单退撞「同单有已核销」)、B 已核销。
        // A 的钱【被卡住了】—— 这才是真要人工处置的,必须出现在回执里,不能静默 continue。
        CmsRegistration a = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration b = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(b, a);
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(a, b));
        TeamGroupServiceImpl s = service(liveActivity(), paid);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(a, b)));

        ActivityCancelResult res = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地不可用");

        assertEquals(0, res.getRefundedOrders());
        assertEquals(1, res.getManualOrders(), "未核销成员的钱被卡住,必须报给主办方而不是静默吞掉");
        ArgumentCaptor<ActivityCancelManualRefundCase> captured =
                ArgumentCaptor.forClass(ActivityCancelManualRefundCase.class);
        verify(manualRefundCaseService).recordCase(captured.capture());
        ActivityCancelManualRefundCase snapshot = captured.getValue();
        assertEquals(ACTIVITY_ID, snapshot.getActivityId());
        assertEquals(PARENT, snapshot.getParentOrderNo());
        assertEquals(1L, snapshot.getPayerRegistrationId());
        assertEquals(501L, snapshot.getPayerMemberId());
        assertEquals(20000L, snapshot.getPaidCashAmountFen());
        assertEquals("[2]", snapshot.getVerifiedRegistrationIdsJson());
        assertEquals("[1]", snapshot.getUnverifiedRegistrationIdsJson());
    }

    /**
     * FX-02:玩家自助退款遇到部分核销时已经记过这张单的工单,之后现场又核销了一张、主办方再取消整场。
     * 锁内快照和工单冻结的已不一样 —— 若再 recordCase 就撞「同一父订单已有不同快照」409,
     * 整场取消连带回滚、永远取消不了。同一 (活动, 父订单) 只记一次,已有就沿用。
     */
    @Test
    void orderAlreadyRoutedToManualByPlayerDoesNotBlockActivityCancel() {
        CmsRegistration a = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration b = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(b, a);
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(a, b));
        TeamGroupServiceImpl s = service(liveActivity(), paid);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(a, b)));
        when(manualRefundCaseService.findCase(ACTIVITY_ID, PARENT))
                .thenReturn(new com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO());

        ActivityCancelResult res = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地不可用");

        assertEquals(1, res.getManualOrders(), "仍要出现在给主办方的回执里");
        verify(manualRefundCaseService, never()).recordCase(any(ActivityCancelManualRefundCase.class));
        verify(refundPayoutService, never()).payoutAfterReview(any());
    }

    @Test
    void fullyVerifiedOrderIsNotReportedAsManual() {
        // ★负控的另一半:整单都已核销 = 人真去玩过了、服务已交付,本就不该退 —— 终局,不是人工的活。
        // 把它也算进「待人工处置」会:①每个到场玩家刷一条 warn;②给主办方一个假数字;
        // ③把真正被卡住的那几笔淹进噪音里。跳过 ≠ 需要人管,两者必须分开。
        CmsRegistration a = reg(1L, "R001", "200.00", PARENT, 1);   // 已核销
        CmsRegistration b = reg(2L, "R002", "0.00", PARENT, 1);     // 已核销
        sameMemberAs(b, a);
        CmsRegistration soloVerified = reg(3L, "R003", "68.00", null, 1);  // 单票且已核销
        List<CmsRegistration> paid = new ArrayList<>(Arrays.asList(a, b, soloVerified));
        TeamGroupServiceImpl s = service(liveActivity(), paid);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(a, b)));

        ActivityCancelResult res = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地不可用");

        assertEquals(0, res.getRefundedOrders(), "服务已交付,一笔都不该退");
        assertEquals(0, res.getManualOrders(), "全核销是终局,不是待人工处置 —— 报出去就是噪音");
        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }

    @Test
    void parentOrderMemberMismatchFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void terminalPayerWithStillPaidChildFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, payer);
        payer.setRegistrationStatus(3);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderMixedPaymentStatusFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, payer);
        child.setPaymentStatus(0);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderMixedRegistrationStatusFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, payer);
        child.setRegistrationStatus(1);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderWithoutCashPayableButWithCashReceiptFailsClosed() {
        CmsRegistration first = reg(1L, "R001", "0.00", PARENT, 0);
        first.setWechatPaymentAmount(BigDecimal.ONE);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, first);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(first, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(first, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void zeroCashParentOrdersCancelOnceWithoutPayoutAndReturnPoints() {
        // 单张、多张免费票及积分抵扣整单均走原有整单取消；是否已开始不改变主办取消合同。
        for (int rows : new int[]{1, 2, 3}) {
            CmsRegistration first = reg(1L, "R001", "0.00", PARENT, 0);
            List<CmsRegistration> order = new ArrayList<>();
            order.add(first);
            for (int i = 2; i <= rows; i++) {
                CmsRegistration child = reg((long) i, "R00" + i, "0.00", PARENT, 0);
                sameMemberAs(child, first);
                if (rows == 3) child.setWechatPaymentAmount(new BigDecimal("10.00"));
                order.add(child);
            }
            if (rows == 3) first.setPointUsed(200L);
            TeamGroupServiceImpl s = service(liveActivity(), order);
            when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID)).thenReturn(order);
            when(regMapper.selectByParentOrderNo(PARENT)).thenReturn(order);
            when(regMapper.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class)))
                    .thenReturn(1);
            when(regMapper.claimPointsReturn(anyLong(), anyString(), any(Date.class))).thenReturn(1);
            UmsMemberMapper members = (UmsMemberMapper) ReflectionTestUtils.getField(s, "umsMemberMapper");
            when(members.addPoints(first.getMemberId(), 200)).thenReturn(1);

            ActivityCancelResult result = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "无法履约");

            assertEquals(1, result.getRefundedOrders());
            assertEquals(0, result.getManualOrders());
            verify(ticketMapper, times(rows)).restoreInventory(TICKET_ID, 1L);
            verify(refundService, never()).saveRefund(any(RefundApplication.class));
            verify(refundPayoutService, never()).payoutAfterReview(anyLong());
            if (rows == 3) verify(members).addPoints(first.getMemberId(), 200);

            when(regMapper.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class)))
                    .thenReturn(0);
            s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "重试");
            verify(ticketMapper, times(rows)).restoreInventory(TICKET_ID, 1L);
            if (rows == 3) verify(members).addPoints(first.getMemberId(), 200);
        }
    }

    @Test
    void partiallyVerifiedZeroCashOrderRetainsManualHandlingWithZeroCashSnapshot() {
        CmsRegistration first = reg(1L, "R001", "0.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, first);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(first, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(first, child));

        ActivityCancelResult result = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "无法履约");

        assertEquals(1, result.getManualOrders());
        assertEquals(0, result.getRefundedOrders());
        ArgumentCaptor<ActivityCancelManualRefundCase> captured =
                ArgumentCaptor.forClass(ActivityCancelManualRefundCase.class);
        verify(manualRefundCaseService).recordCase(captured.capture());
        assertEquals(1L, captured.getValue().getPayerRegistrationId());
        assertEquals(0L, captured.getValue().getPaidCashAmountFen());
        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void parentOrderWithMultipleOriginalPayersFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "100.00", PARENT, 0);
        CmsRegistration secondPayer = reg(2L, "R002", "100.00", PARENT, 1);
        sameMemberAs(secondPayer, payer);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, secondPayer));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, secondPayer));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderScopeMismatchFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, payer);
        child.setOwnerId(ACTIVITY_ID + 1);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderOwnerTypeMismatchFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, payer);
        child.setOwnerType(1L);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderNumberMismatchFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", "P_OTHER", 1);
        sameMemberAs(child, payer);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void parentOrderUnknownVerificationFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 2);
        sameMemberAs(child, payer);
        TeamGroupServiceImpl s = service(liveActivity(), Arrays.asList(payer, child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketNonPaidRegistrationStateFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration single = reg(1L, "R001", "68.00", null, 0);
        single.setRegistrationStatus(1);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(single));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketNullVerificationFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration single = reg(1L, "R001", "68.00", null, 0);
        single.setVerificationStatus(null);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(single));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketUnknownVerificationFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration single = reg(1L, "R001", "68.00", null, 2);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(single));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketConcurrentPaymentStateChangeFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration listedPaid = reg(1L, "R001", "68.00", null, 0);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(listedPaid));
        CmsRegistration lockedRefunded = reg(1L, "R001", "68.00", null, 0);
        lockedRefunded.setPaymentStatus(4);
        lockedRefunded.setRegistrationStatus(3);
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(lockedRefunded);

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketLockedMemberMismatchFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration listedPaid = reg(1L, "R001", "68.00", null, 0);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(listedPaid));
        CmsRegistration lockedOtherMember = reg(1L, "R001", "68.00", null, 0);
        lockedOtherMember.setMemberId(listedPaid.getMemberId() + 1);
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(lockedOtherMember);

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketLockedAmountMismatchFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration listedPaid = reg(1L, "R001", "68.00", null, 0);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(listedPaid));
        CmsRegistration lockedDifferentAmount = reg(1L, "R001", "88.00", null, 0);
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(lockedDifferentAmount);

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void singleTicketConcurrentVerificationUsesLockedVerifiedSemantics() {
        CmsRegistration listedUnverified = reg(1L, "R001", "68.00", null, 0);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(listedUnverified));
        CmsRegistration lockedVerified = reg(1L, "R001", "68.00", null, 1);
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(lockedVerified);

        ActivityCancelResult result = s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地不可用");

        assertEquals(0, result.getRefundedOrders(), "锁内已核销的单票不得自动退款");
        assertEquals(0, result.getManualOrders(), "单票已全部核销是服务已交付终局，不制造部分核销人工单");
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void lockedPayerMissingFromPaidSetFailsClosedWithoutCaseOrCancellation() {
        CmsRegistration payer = reg(1L, "R001", "200.00", PARENT, 0);
        CmsRegistration child = reg(2L, "R002", "0.00", PARENT, 1);
        sameMemberAs(child, payer);
        TeamGroupServiceImpl s = service(liveActivity(), java.util.Collections.singletonList(child));
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, ACTIVITY_ID))
                .thenReturn(Arrays.asList(payer, child));

        assertSnapshotConflictWithoutSideEffects(s);
    }

    @Test
    void cancellingMarksActivityCancelledAndUnpublishes() {
        TeamGroupServiceImpl s = service(liveActivity(), new ArrayList<>());

        s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "场地临时不可用");

        ArgumentCaptor<CmsActivity> upd = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(upd.capture());
        CmsActivity saved = upd.getValue();
        assertNotNull(saved.getCancelTime(), "必须落取消时间(cancel_time 非空=已取消)");
        assertEquals("场地临时不可用", saved.getCancelReason(), "取消原因要展示给已报名用户");
        assertEquals(0L, saved.getStatus().longValue(), "取消后真实活动必须关闭");
        assertEquals(0L, saved.getPublishStatus().longValue(), "取消后必须下架,别让新用户继续报名");
    }

    @Test
    void cancellationClosesPendingRegistrationAndReportsHonestCount() {
        CmsRegistration pending = pending(9L);
        TeamGroupServiceImpl s = service(liveActivity(), new ArrayList<>());
        org.mockito.Mockito.doAnswer(invocation -> {
            CmsRegistration query = invocation.getArgument(0, CmsRegistration.class);
            return query.getPaymentStatus() == null || Integer.valueOf(0).equals(query.getPaymentStatus())
                    ? java.util.Collections.singletonList(pending)
                    : java.util.Collections.<CmsRegistration>emptyList();
        }).when(regMapper).selectCmsRegistrationList(any(CmsRegistration.class));
        when(regMapper.cancelUnpaidIfActive(9L, 509L, "activity-cancel:" + OTHER_MEMBER)).thenReturn(1);

        ActivityCancelResult result = s.cancelActivityByClubOperator(
                ACTIVITY_ID, OWNER_ID, OTHER_MEMBER, "场地临时不可用");

        assertEquals(1, result.getClosedPendingOrders());
        assertEquals(0, result.getRefundedOrders());
        verify(ticketMapper).restoreInventory(TICKET_ID, 1L);
        ArgumentCaptor<CmsActivity> update = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(update.capture());
        assertEquals(String.valueOf(OTHER_MEMBER), update.getValue().getUpdateBy());
    }

    @Test
    void paymentCallbackWinningPendingCasAbortsBeforeActivityIsCancelled() {
        CmsRegistration pending = pending(9L);
        TeamGroupServiceImpl s = service(liveActivity(), new ArrayList<>());
        org.mockito.Mockito.doAnswer(invocation -> {
            CmsRegistration query = invocation.getArgument(0, CmsRegistration.class);
            return query.getPaymentStatus() == null || Integer.valueOf(0).equals(query.getPaymentStatus())
                    ? java.util.Collections.singletonList(pending)
                    : java.util.Collections.<CmsRegistration>emptyList();
        }).when(regMapper).selectCmsRegistrationList(any(CmsRegistration.class));
        when(regMapper.cancelUnpaidIfActive(9L, 509L, "activity-cancel:" + OTHER_MEMBER)).thenReturn(0);
        CmsRegistration paidByCallback = pending(9L);
        paidByCallback.setPaymentStatus(2);
        paidByCallback.setRegistrationStatus(2);
        when(regMapper.selectCmsRegistrationByIdForUpdate(9L)).thenReturn(paidByCallback);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> s.cancelActivityByClubOperator(
                        ACTIVITY_ID, OWNER_ID, OTHER_MEMBER, "场地临时不可用"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void cancellingTwiceIsRejected() {
        CmsActivity already = liveActivity();
        already.setCancelTime(new Date());
        TeamGroupServiceImpl s = service(already, new ArrayList<>());

        assertThrows(RuntimeException.class,
                () -> s.cancelActivityByOwner(ACTIVITY_ID, OWNER_ID, "再取消一次"),
                "已取消的活动不得重复取消 —— 否则会对已退款的报名重复走退款链路");
        verify(refundPayoutService, never()).payoutAfterReview(anyLong());
    }
}
