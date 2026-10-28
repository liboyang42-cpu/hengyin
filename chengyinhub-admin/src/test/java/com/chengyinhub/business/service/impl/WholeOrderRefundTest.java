package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整单退(方案 §3.2「一次网关调用」):退父订单内任意一张 → 只对【首单】那笔真实微信交易退一次、
 * 金额=汇总;从单绝不单独调微信 —— 其 registrationNo 从来不是 out_trade_no,调了必被微信拒。
 *
 * 首单判据 = payableAmount > 0(CmsRegistrationServiceImpl:370 首单=汇总 / :272-273 从单=0)。
 * 注意从单的 wechatPaymentAmount 是【假现金】(:277 写了整张票价),整单退必须无视它 ——
 * 这正是 P0-A 的病灶,整单退让它变成没人读的死数据,不需要单独修那一行
 * (bfa5f066「从单清零」的外科修法会让 refundOne 走 markRefunded 静默退 0、丢人工兜底信号,
 *  已因引入回归被 revert 为 a8d4618c)。
 */
class WholeOrderRefundTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long TICKET_ID = 300L;
    private static final String PARENT = "P20260715abcd1234";

    private IRefundPayoutService refundPayoutService;
    private CmsRegistrationMapper regMapper;
    private CmsTopicMapper topicMapper;
    private OmsTicketMapper ticketMapper;
    private IRefundService refundService;

    private TeamGroupServiceImpl newService() {
        refundPayoutService = mock(IRefundPayoutService.class);
        regMapper = mock(CmsRegistrationMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        RegistrationScopeFixture.currentReads(regMapper, null, topicMapper);
        refundService = mock(IRefundService.class);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setName("标准票");
        ticket.setMode(1);
        // 集合时间在未来 → RefundPolicy 判定可退,把测试聚焦在退款粒度而非时间窗
        ticket.setStartTime(new Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000));
        ticket.setEndTime(new Date(System.currentTimeMillis() + 8L * 24 * 3600 * 1000));
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);
        when(ticketMapper.restoreInventory(eq(TICKET_ID), any(Long.class))).thenReturn(1);
        when(regMapper.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class))).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);

        TeamGroupServiceImpl service = new TeamGroupServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        // [P0-15] ③ 退款释放权益容量 —— 本类不测该链路,注入空 mock 即可;
        //        不注入会 NPE(手工装配不走 Spring 容器)。
        ReflectionTestUtils.setField(service, "perkLedgerService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "refundService", refundService);
        ReflectionTestUtils.setField(service, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(service, "notifyService", mock(INotifyService.class));
        // [D-2] 打款已收口到 payoutAfterReview(两路共用)。本类只验 TeamGroupServiceImpl 的职责:
        // 「按哪一单、以什么金额建退款单,以及打款被触发几次」;
        // 「拿退款单去调微信、out_trade_no=registrationNo」由 RefundPayoutServiceTest 覆盖。
        ReflectionTestUtils.setField(service, "refundPayoutService", refundPayoutService);
        return service;
    }

    /** 捕获建单参数:整单退的不变式(退首单、退汇总额)现在体现在退款单上。 */
    private RefundApplication capturedRefundApp() {
        ArgumentCaptor<RefundApplication> cap = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refundService).saveRefund(cap.capture());
        return cap.getValue();
    }

    private CmsRegistration reg(Long id, String no, String payable, String wxAmount) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setRegistrationNo(no);
        r.setMemberId(MEMBER_ID);
        r.setTicketId(TICKET_ID);
        r.setOwnerType(1L);
        r.setOwnerId(9L);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(0);
        r.setPointUsed(0L);
        r.setOrderNum(1L);
        r.setPayableAmount(new BigDecimal(payable));
        r.setWechatPaymentAmount(new BigDecimal(wxAmount));
        return r;
    }

    /** 首单=汇总 200;从单 payable=0 但 wechatPaymentAmount=100(假现金,P0-A 病灶) */
    private List<CmsRegistration> multiTicketOrder() {
        List<CmsRegistration> order = new ArrayList<>();
        CmsRegistration first = reg(1L, "R001", "200.00", "200.00");
        CmsRegistration sub = reg(2L, "R002", "0.00", "100.00");
        first.setParentOrderNo(PARENT);
        sub.setParentOrderNo(PARENT);
        order.add(first);
        order.add(sub);
        return order;
    }

    @Test
    void refundingSubOrderRefundsFirstOrderTransactionExactlyOnce() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        CmsRegistration sub = order.get(1);
        when(regMapper.selectCmsRegistrationById(2L)).thenReturn(sub);
        when(regMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(sub);
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        // saveRefund 回填 app.id(真实实现如此,reviewRefund 依赖它)
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(999L);
            return 1;
        });

        service.cancelPaidRegistration(2L, MEMBER_ID);

        // §3.2:整单退只准触发一次打款(微信单笔订单退款 1 次/分钟频控 + 单笔最多 50 次部分退)
        verify(refundPayoutService, times(1)).payoutAfterReview(999L);

        // 不变式没变,只是落在退款单上:退款单必须挂【首单】且金额=首单承载的汇总。
        // payoutAfterReview 按 sourceId 查报名再取 registrationNo 作 out_trade_no,
        // 故 sourceId=首单 ⇒ out_trade_no=R001(那一环由 RefundPayoutServiceTest 断言)。
        RefundApplication app = capturedRefundApp();
        assertEquals(1L, app.getSourceId(),
                "退款单必须挂【首单】—— 从单 R002 从来不是 out_trade_no,拿它去退必被微信拒");
        assertEquals(0, new BigDecimal("200.00").compareTo(app.getRefundAmount()),
                "退款额必须=首单承载的汇总 200,而非从单的假现金 100");
    }

    @Test
    void wholeOrderRefundMarksEveryMemberOfTheOrder() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        when(regMapper.selectCmsRegistrationById(2L)).thenReturn(order.get(1));
        when(regMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(order.get(1));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(999L);
            return 1;
        });

        service.cancelPaidRegistration(2L, MEMBER_ID);

        // 首单由现金退款链置取消，从单由 claimRegistrationRefunded 同一条 CAS 同时置取消。
        ArgumentCaptor<CmsRegistration> upd = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(regMapper, atLeast(1)).updateCmsRegistration(upd.capture());
        Set<Long> cancelled = new HashSet<>();
        for (CmsRegistration u : upd.getAllValues()) {
            if (u.getRegistrationStatus() != null && u.getRegistrationStatus() == 3) cancelled.add(u.getId());
        }
        assertEquals(new HashSet<>(Arrays.asList(1L)), cancelled,
                "现金首单应由退款链置为已取消(3),实际=" + cancelled);
        verify(regMapper).claimRegistrationRefunded(eq(2L), eq("auto-refund"), any(Date.class));
        // 库存两张都要回补
        verify(ticketMapper, times(2)).restoreInventory(eq(TICKET_ID), any(Long.class));
        // FX-10:从单不经 markRegistrationRefunded(首单那条在 RefundServiceImpl 里收),在这里收回首评积分
        com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService clawback =
                (com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService)
                        ReflectionTestUtils.getField(service, "firstReviewPointsClawback");
        verify(clawback).onRegistrationRefunded(order.get(1));
        verify(clawback, never()).onRegistrationRefunded(order.get(0));
    }

    /** FX-10:纯积分单不走现金退款链,领到退款权后自己收回首评积分;输给并发核销则不收。 */
    @Test
    void pointsOnlyRefundClawsBackFirstReviewPoints() {
        TeamGroupServiceImpl service = newService();
        CmsRegistration solo = reg(6L, "R006", "0.00", "0.00");
        solo.setPointUsed(100L);
        when(regMapper.selectCmsRegistrationById(6L)).thenReturn(solo);
        when(regMapper.selectCmsRegistrationByIdForUpdate(6L)).thenReturn(solo);

        service.cancelPaidRegistration(6L, MEMBER_ID);

        com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService clawback =
                (com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService)
                        ReflectionTestUtils.getField(service, "firstReviewPointsClawback");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(regMapper, clawback);
        order.verify(regMapper).claimRegistrationRefunded(eq(6L), anyString(), any(Date.class));
        order.verify(clawback).onRegistrationRefunded(solo);
    }

    /**
     * FX-05(9-18 拍板「加列」):主题票(owner_type=1)整单部分核销,原来工单表只能挂场次 ⇒ 整单拒退。
     * 现在冻结一张挂 topic_id 的人工退款工单,玩家看到「已转人工」;不退钱、不回库存、不领退款权。
     */
    @Test
    void partiallyVerifiedTopicOrderIsRoutedToManualRefundCase() {
        TeamGroupServiceImpl service = newService();
        com.chengyinhub.business.service.IActivityCancelManualRefundCaseService cases =
                mock(com.chengyinhub.business.service.IActivityCancelManualRefundCaseService.class);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", cases);
        List<CmsRegistration> order = multiTicketOrder();
        for (CmsRegistration m : order) {
            m.setRegistrationStatus(2);
        }
        order.get(0).setVerificationStatus(1);   // 首单已核销 = 别人已经用掉了
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setMemberId(500L);
        topic.setClubId(31L);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);
        when(regMapper.selectCmsRegistrationById(2L)).thenReturn(order.get(1));
        when(regMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(order.get(1));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, 9L)).thenReturn(order);

        int result = service.cancelPaidRegistration(2L, MEMBER_ID);

        assertEquals(com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW, result, "主题票部分核销应转人工");
        ArgumentCaptor<com.chengyinhub.business.domain.ActivityCancelManualRefundCase> cap =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.ActivityCancelManualRefundCase.class);
        verify(cases).recordCase(cap.capture());
        com.chengyinhub.business.domain.ActivityCancelManualRefundCase c = cap.getValue();
        assertEquals(Long.valueOf(9L), c.getTopicId());
        assertEquals(null, c.getActivityId(), "主题工单不挂场次");
        assertEquals(Long.valueOf(500L), c.getOwnerMemberId());
        assertEquals(Long.valueOf(31L), c.getClubId());
        assertEquals(PARENT, c.getParentOrderNo());
        assertEquals(1L, c.getPayerRegistrationId());
        assertEquals(Long.valueOf(20000L), c.getPaidCashAmountFen());
        assertEquals("[1]", c.getVerifiedRegistrationIdsJson());
        assertEquals("[2]", c.getUnverifiedRegistrationIdsJson());
        verify(regMapper, never()).claimRegistrationRefunded(anyLong(), anyString(), any(Date.class));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, never()).payoutAfterReview(any());
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
    }

    /** FX-05:同一主题父订单已有工单(先前自助转过人工)⇒ 沿用,不重复冻结。 */
    @Test
    void existingTopicCaseIsReusedNotRecordedAgain() {
        TeamGroupServiceImpl service = newService();
        com.chengyinhub.business.service.IActivityCancelManualRefundCaseService cases =
                mock(com.chengyinhub.business.service.IActivityCancelManualRefundCaseService.class);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", cases);
        List<CmsRegistration> order = multiTicketOrder();
        for (CmsRegistration m : order) {
            m.setRegistrationStatus(2);
        }
        order.get(0).setVerificationStatus(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setMemberId(500L);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);
        when(regMapper.selectCmsRegistrationById(2L)).thenReturn(order.get(1));
        when(regMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(order.get(1));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, 9L)).thenReturn(order);
        when(cases.findTopicCase(9L, PARENT)).thenReturn(
                new com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO());

        assertEquals(com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW,
                service.cancelPaidRegistration(2L, MEMBER_ID));
        verify(cases, never()).recordCase(any());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
    }

    /** 同单某张从单被别的路径退过:stub 它那张已批准的退款单(金额 = 当时真退出去的钱)。 */
    private void subAlreadyRefundedBy(Long subId, String amount) {
        RefundApplication done = new RefundApplication();
        done.setSourceType("registration");
        done.setSourceId(subId);
        done.setStatus(1);
        done.setRefundAmount(new BigDecimal(amount));
        when(refundService.selectRegistrationHistoryForUpdate(subId)).thenReturn(Arrays.asList(done));
    }

    /**
     * FX-01:同单里已有从单被别的路径退过(如 §6.1 人工兜底放行 → payment_status=4),整单退:
     *  ① 跳过它 —— 否则 restoreInventory 会把库存多回补一张(它没有幂等守卫);
     *  ② 首单只退「汇总 − 已退」。旧断言只验了 ①,首单实际照退汇总 200 = 那张从单的 100 退两遍。
     */
    @Test
    void alreadyRefundedMemberIsSkippedSoInventoryIsNotRestoredTwice() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        order.get(1).setPaymentStatus(4);            // 从单已退款
        subAlreadyRefundedBy(2L, "100.00");
        CmsRegistration first = order.get(0);
        when(regMapper.selectCmsRegistrationById(1L)).thenReturn(first);
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(first);
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(997L);
            return 1;
        });

        service.cancelPaidRegistration(1L, MEMBER_ID);

        // 只有首单该回补库存,已退的从单不该被再碰一次
        verify(ticketMapper, times(1)).restoreInventory(eq(TICKET_ID), any(Long.class));
        RefundApplication app = capturedRefundApp();
        assertEquals(1L, app.getSourceId());
        assertEquals(0, new BigDecimal("100.00").compareTo(app.getRefundAmount()),
                "从单已退 100,首单只能退剩下的 100,实际=" + app.getRefundAmount());
        verify(refundPayoutService, times(1)).payoutAfterReview(997L);
    }

    /** FX-01:同单其余票已把汇总额退完 ⇒ 首单不再调微信,只领退款权/回库存/返积分。 */
    @Test
    void orderCashAlreadyFullyRefundedByOtherPathsPaysNothingMore() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        order.get(1).setPaymentStatus(4);
        subAlreadyRefundedBy(2L, "200.00");
        when(regMapper.selectCmsRegistrationById(1L)).thenReturn(order.get(0));
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(order.get(0));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);

        service.cancelPaidRegistration(1L, MEMBER_ID);

        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, never()).payoutAfterReview(any());
        verify(regMapper).claimRegistrationRefunded(eq(1L), eq("auto-refund"), any(Date.class));
        verify(ticketMapper, times(1)).restoreInventory(eq(TICKET_ID), any(Long.class));
    }

    /** FX-01 fail-closed:从单已退却查不到已批准的退款单 = 已退金额无从核对,不能按汇总额照退。 */
    @Test
    void refundedSubWithoutApprovedRefundRecordFailsClosed() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        order.get(1).setPaymentStatus(4);            // 没有 stub 退款单
        when(regMapper.selectCmsRegistrationById(1L)).thenReturn(order.get(0));
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(order.get(0));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.cancelPaidRegistration(1L, MEMBER_ID));
        assertEquals(409, e.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
    }

    /** FX-01:同单从单还挂着一张待审退款单 —— 此时整单退,之后那张再被批准就是第二次退。 */
    @Test
    void pendingRefundOnSubBlocksWholeOrderRefund() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        RefundApplication pending = new RefundApplication();
        pending.setSourceId(2L);
        pending.setStatus(0);
        pending.setRefundAmount(new BigDecimal("100.00"));
        when(refundService.selectRegistrationHistoryForUpdate(2L)).thenReturn(Arrays.asList(pending));
        when(regMapper.selectCmsRegistrationById(1L)).thenReturn(order.get(0));
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(order.get(0));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.cancelPaidRegistration(1L, MEMBER_ID));
        assertEquals(409, e.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
    }

    /**
     * 有状态替身:claim = CAS(同一行只赢一次,赢了置 payment_status=4);
     * saveRefund = 退款单幂等键(同一 refundNo 第二次撞唯一键),与真实库的两道闸同构。
     */
    private List<CmsRegistration> statefulOrder() {
        final List<CmsRegistration> order = multiTicketOrder();
        final java.util.Map<Long, CmsRegistration> byId = new java.util.concurrent.ConcurrentHashMap<>();
        for (CmsRegistration m : order) byId.put(m.getId(), m);
        final Set<Long> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<String> refundNos = java.util.concurrent.ConcurrentHashMap.newKeySet();
        when(regMapper.selectCmsRegistrationById(anyLong())).thenAnswer(inv -> byId.get(inv.getArgument(0, Long.class)));
        org.mockito.Mockito.doAnswer(inv -> byId.get(inv.getArgument(0, Long.class))).when(regMapper).selectCmsRegistrationByIdForUpdate(anyLong());
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(regMapper.claimRegistrationRefunded(anyLong(), anyString(), any(Date.class))).thenAnswer(inv -> {
            Long id = inv.getArgument(0, Long.class);
            if (!claimed.add(id)) return 0;
            byId.get(id).setPaymentStatus(4);
            return 1;
        });
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            RefundApplication app = inv.getArgument(0, RefundApplication.class);
            if (!refundNos.add(app.getRefundNo())) {
                throw new org.springframework.dao.DuplicateKeyException("uk_refund_idempotency");
            }
            byId.get(app.getSourceId()).setPaymentStatus(4);
            app.setId(990L);
            return 1;
        });
        return order;
    }

    /** FX-01 幂等:退成功后再点一次,不能再建退款单、再打款。 */
    @Test
    void repeatedWholeOrderRefundPaysOnce() {
        TeamGroupServiceImpl service = newService();
        statefulOrder();

        service.cancelPaidRegistration(2L, MEMBER_ID);
        assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(2L, MEMBER_ID));
        assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(1L, MEMBER_ID));

        verify(refundService, times(1)).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, times(1)).payoutAfterReview(990L);
        verify(ticketMapper, times(2)).restoreInventory(eq(TICKET_ID), any(Long.class));
    }

    /** FX-01 并发:首单、从单两个入口同时点整单退 ⇒ 只建出一张退款单,金额=汇总 200,打款一次。 */
    @Test
    void concurrentWholeOrderRefundPaysOnce() throws Exception {
        TeamGroupServiceImpl service = newService();
        statefulOrder();
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        for (final Long id : Arrays.asList(1L, 2L)) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    service.cancelPaidRegistration(id, MEMBER_ID);
                    return true;
                } catch (RuntimeException lost) {
                    return false;
                }
            }));
        }
        go.countDown();
        int winners = 0;
        for (java.util.concurrent.Future<Boolean> f : results) {
            if (f.get(10, java.util.concurrent.TimeUnit.SECONDS)) winners++;
        }
        pool.shutdown();

        // 后到的一边若在对方提交后才预读,会看到整单已退、什么都不做即返回 —— 所以不数「成功次数」,只数钱。
        assertEquals(true, winners >= 1, "至少一边完成整单退");
        RefundApplication app = capturedRefundApp();   // verify(...) 默认 times(1)
        assertEquals(1L, app.getSourceId());
        assertEquals(0, new BigDecimal("200.00").compareTo(app.getRefundAmount()));
        verify(refundPayoutService, times(1)).payoutAfterReview(990L);
    }

    /** 场次(owner_type=2)双票订单:首单 1 已核销、从单 2 未核销;活动 77 归属 500。 */
    private List<CmsRegistration> activityOrderFirstVerified(TeamGroupServiceImpl service,
            com.chengyinhub.business.service.IActivityCancelManualRefundCaseService cases) {
        com.chengyinhub.business.mapper.CmsActivityMapper activityMapper =
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity act = new com.chengyinhub.business.domain.CmsActivity();
        act.setId(77L);
        act.setMemberId(500L);
        act.setStartDate(new Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000));
        when(activityMapper.selectCmsActivityById(77L)).thenReturn(act);
        RegistrationScopeFixture.currentReads(null, activityMapper, null);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", cases);
        List<CmsRegistration> order = multiTicketOrder();
        for (CmsRegistration m : order) {
            m.setOwnerType(2L);
            m.setOwnerId(77L);
            m.setRegistrationStatus(2);
        }
        order.get(0).setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationById(2L)).thenReturn(order.get(1));
        when(regMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(order.get(1));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(regMapper.selectByParentOrderNoForUpdate(PARENT, 77L)).thenReturn(order);
        return order;
    }

    /**
     * FX-02(9-18 拍板 A,与场次取消同口径):部分核销 ⇒ 不自动退、不报错,
     * 冻结一张人工退款工单(复用 ActivityCancelManualRefundCase),告诉玩家已转人工。
     */
    @Test
    void partiallyVerifiedActivityOrderIsRoutedToManualRefundCase() {
        TeamGroupServiceImpl service = newService();
        com.chengyinhub.business.service.IActivityCancelManualRefundCaseService cases =
                mock(com.chengyinhub.business.service.IActivityCancelManualRefundCaseService.class);
        activityOrderFirstVerified(service, cases);

        int result = service.cancelPaidRegistration(2L, MEMBER_ID);

        assertEquals(com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW, result, "部分核销应返回「已转人工」");
        ArgumentCaptor<com.chengyinhub.business.domain.ActivityCancelManualRefundCase> cap =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.ActivityCancelManualRefundCase.class);
        verify(cases).recordCase(cap.capture());
        com.chengyinhub.business.domain.ActivityCancelManualRefundCase c = cap.getValue();
        assertEquals(77L, c.getActivityId());
        assertEquals(PARENT, c.getParentOrderNo());
        assertEquals(1L, c.getPayerRegistrationId());
        assertEquals(MEMBER_ID, c.getActorMemberId());
        assertEquals(Long.valueOf(20000L), c.getPaidCashAmountFen());
        assertEquals("[1]", c.getVerifiedRegistrationIdsJson());
        assertEquals("[2]", c.getUnverifiedRegistrationIdsJson());
        verify(regMapper, never()).claimRegistrationRefunded(anyLong(), anyString(), any(Date.class));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, never()).payoutAfterReview(any());
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
    }

    /** FX-02:主理人清退同口径。 */
    @Test
    void partiallyVerifiedOrderEvictedByOwnerIsAlsoRoutedToManual() {
        TeamGroupServiceImpl service = newService();
        com.chengyinhub.business.service.IActivityCancelManualRefundCaseService cases =
                mock(com.chengyinhub.business.service.IActivityCancelManualRefundCaseService.class);
        activityOrderFirstVerified(service, cases);
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setMemberId(500L);
        com.chengyinhub.business.domain.CmsActivity act =
                ((com.chengyinhub.business.mapper.CmsActivityMapper) ReflectionTestUtils.getField(service, "cmsActivityMapper"))
                        .selectCmsActivityById(77L);
        act.setTopicId(9L);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);

        assertEquals(com.chengyinhub.business.service.ITeamGroupService.CANCEL_MANUAL_REVIEW, service.cancelPaidRegistrationByOwner(2L, 500L));
        verify(cases).recordCase(any(com.chengyinhub.business.domain.ActivityCancelManualRefundCase.class));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
    }

    /** FX-02:同单仍在已支付状态的票全都已核销 ⇒ 不退、也不建工单。 */
    @Test
    void fullyVerifiedOrderIsNotRefundedNorRoutedToManual() {
        TeamGroupServiceImpl service = newService();
        com.chengyinhub.business.service.IActivityCancelManualRefundCaseService cases =
                mock(com.chengyinhub.business.service.IActivityCancelManualRefundCaseService.class);
        List<CmsRegistration> order = activityOrderFirstVerified(service, cases);
        CmsRegistration staleClicked = reg(2L, "R002", "0.00", "100.00");   // 玩家手里的旧快照:未核销
        staleClicked.setParentOrderNo(PARENT);
        staleClicked.setOwnerType(2L);
        staleClicked.setOwnerId(77L);
        when(regMapper.selectCmsRegistrationById(2L)).thenReturn(staleClicked);
        when(regMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(staleClicked);
        order.get(1).setVerificationStatus(1);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(2L, MEMBER_ID));
        assertEquals("已核销报名不可退款", e.getMessage(), "当前锁读已证明本票核销，须沿原核销闸拒绝；全核销仍是终局不退");
        verify(cases, never()).recordCase(any());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
    }

    /** 1-43:纯积分单领退款权时输给并发核销,不能静默「退款成功」却什么都没退。 */
    @Test
    void pointsOnlyRefundLosingRaceToVerificationFailsLoudly() {
        TeamGroupServiceImpl service = newService();
        CmsRegistration solo = reg(6L, "R006", "0.00", "0.00");
        solo.setPointUsed(100L);
        when(regMapper.selectCmsRegistrationById(6L)).thenReturn(solo);
        when(regMapper.claimRegistrationRefunded(eq(6L), anyString(), any(Date.class))).thenReturn(0);
        CmsRegistration verifiedNow = reg(6L, "R006", "0.00", "0.00");
        verifiedNow.setVerificationStatus(1);
        // 入口锁内读仍未核销；claim 输给并发核销后的复读才看到已核销。
        org.mockito.Mockito.doReturn(solo, solo, verifiedNow).when(regMapper).selectCmsRegistrationByIdForUpdate(6L);

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(6L, MEMBER_ID));
        assertEquals("已核销报名不可退款", e.getMessage());
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
        verify((com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService)
                ReflectionTestUtils.getField(service, "firstReviewPointsClawback"), never()).onRegistrationRefunded(any());
    }

    /**
     * RV-p1 1-43:整单退的「同单无已核销」只在无锁预读时判一次。预读之后从单被并发核销
     * ⇒ 从单 claim 返回 0。首单退的是整单汇总额,拆不出单张票价 ⇒ 不能照退(那样已核销那张的钱
     * 也跟着退出去),整单拒绝、事务回滚,退款金额=0(与预读即发现已核销同口径)。
     */
    @Test
    void subTicketVerifiedConcurrentlyAfterPrereadRefundsNothing() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();   // 预读快照:两张都未核销
        when(regMapper.selectCmsRegistrationById(1L)).thenReturn(order.get(0));
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(order.get(0));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        // 并发核销在预读之后、claim 之前提交:claim 的 verification_status=0 条件落空
        when(regMapper.claimRegistrationRefunded(eq(2L), anyString(), any(Date.class))).thenReturn(0);
        CmsRegistration subNow = reg(2L, "R002", "0.00", "100.00");
        subNow.setParentOrderNo(PARENT);
        subNow.setVerificationStatus(1);
        org.mockito.Mockito.doReturn(subNow).when(regMapper).selectCmsRegistrationByIdForUpdate(2L);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(996L);
            return 1;
        });

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(1L, MEMBER_ID));
        assertEquals("同单存在已核销的票,不能整单退", e.getMessage());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundService, never()).reviewRefund(any(), any());
        verify(refundPayoutService, never()).payoutAfterReview(any());
        verify(ticketMapper, never()).restoreInventory(anyLong(), any(Long.class));
    }

    /** 同上,但 claim 落空不是因为核销(被别的路径抢先退了):现金整单本次回滚 409。⚠ 重试时预读会剔掉已退从单、首单仍按汇总额退 —— 修复前既有问题,另立项。 */
    @Test
    void subTicketClaimLostToConcurrentRefundRollsBackThisAttempt() {
        TeamGroupServiceImpl service = newService();
        List<CmsRegistration> order = multiTicketOrder();
        when(regMapper.selectCmsRegistrationById(1L)).thenReturn(order.get(0));
        when(regMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(order.get(0));
        when(regMapper.selectByParentOrderNoForUpdate(eq(PARENT), org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(order);
        when(regMapper.claimRegistrationRefunded(eq(2L), anyString(), any(Date.class))).thenReturn(0);
        CmsRegistration subNow = reg(2L, "R002", "0.00", "100.00");
        subNow.setParentOrderNo(PARENT);
        subNow.setPaymentStatus(4);
        org.mockito.Mockito.doReturn(subNow).when(regMapper).selectCmsRegistrationByIdForUpdate(2L);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.cancelPaidRegistration(1L, MEMBER_ID));
        assertEquals(409, e.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, never()).payoutAfterReview(any());
    }

    @Test
    void singleTicketOrderIsUnaffected() {
        // 负控:单票(parent_order_no IS NULL)是当前生产【唯一活着】的路径,行为必须一字不变 —— 零回归。
        TeamGroupServiceImpl service = newService();
        CmsRegistration solo = reg(5L, "R005", "68.00", "68.00");   // parentOrderNo 不设 = null
        when(regMapper.selectCmsRegistrationById(5L)).thenReturn(solo);
        when(regMapper.selectCmsRegistrationByIdForUpdate(5L)).thenReturn(solo);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(998L);
            return 1;
        });

        service.cancelPaidRegistration(5L, MEMBER_ID);

        verify(refundPayoutService, times(1)).payoutAfterReview(998L);
        RefundApplication app = capturedRefundApp();
        assertEquals(5L, app.getSourceId(), "单票应退自己那笔(sourceId=5 ⇒ out_trade_no=R005)");
        assertEquals(0, new BigDecimal("68.00").compareTo(app.getRefundAmount()), "单票退自己的 68");
        // 单票不该去查父订单
        verify(regMapper, never()).selectByParentOrderNo(anyString());
    }

    @Test
    void inventoryRestoreConflictStopsGatewayRefundBeforeExternalPayout() {
        TeamGroupServiceImpl service = newService();
        CmsRegistration solo = reg(6L, "R006", "68.00", "68.00");
        when(regMapper.selectCmsRegistrationById(6L)).thenReturn(solo);
        when(regMapper.selectCmsRegistrationByIdForUpdate(6L)).thenReturn(solo);
        when(ticketMapper.restoreInventory(eq(TICKET_ID), any(Long.class))).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.cancelPaidRegistration(6L, MEMBER_ID));

        assertEquals(409, ex.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(refundPayoutService, never()).payoutAfterReview(any());
    }
}
