package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [FIX-WEATHER / C8-05] 天气、场地原因取消:主办方发起取消并全额退款。
 *
 * <p>俱乐部主理人早就有「取消并全退」;商家主办的场次、主题级已售票没有。本类钉两条新入口:
 * <ul>
 *   <li>{@code cancelActivityByMerchantHost}:商家店主/有权限的员工取消自己【主办】的场次;
 *       承办别人主题的场次不能取消(主办方才有权决定取消)。</li>
 *   <li>{@code cancelTopicByHost}:主题主办方取消整个主题 = 停售 + 退主题级票 + 逐场取消场次。
 *       已核销口径与场次取消同一套(9-18 总控拍板 A):整单全核销不退、部分核销转人工、未核销全额退。</li>
 * </ul>
 * 退款一律走现有退款核心(refundResolvingOrder),不另写一套;幂等靠「锁行后复核 payment_status」
 * 与退款单号 RF+报名id 的唯一键。
 */
class HostWeatherCancelTest {

    private static final Long OWNER = 77L;       // 商家店主 / 主题主办
    private static final Long STAFF = 88L;       // 实际点按钮的员工
    private static final Long STRANGER = 99L;
    private static final Long TOPIC = 400L;
    private static final Long ACTIVITY = 200L;
    private static final Long SHELL = 201L;
    private static final Long TICKET = 300L;

    private IRefundService refundService;
    private IRefundPayoutService payout;
    private CmsRegistrationMapper regMapper;
    private CmsActivityMapper activityMapper;
    private CmsTopicMapper topicMapper;
    private com.chengyinhub.business.service.ICmsTopicService topicService;
    private OmsTicketMapper ticketMapper;
    private IActivityCancelManualRefundCaseService manualCases;
    /** 按 (ownerType, ownerId) 存「列表查询」看到的已付款行;锁行查询另按 id 返回。 */
    private final Map<String, List<CmsRegistration>> paidByOwner = new HashMap<>();
    private final Map<Long, CmsRegistration> lockedById = new HashMap<>();
    private final Map<Long, CmsActivity> activities = new HashMap<>();
    private TeamGroupServiceImpl service;
    private long refundSeq;

    @BeforeEach
    void setUp() {
        refundService = mock(IRefundService.class);
        payout = mock(IRefundPayoutService.class);
        regMapper = mock(CmsRegistrationMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        topicService = mock(com.chengyinhub.business.service.ICmsTopicService.class);
        ticketMapper = mock(OmsTicketMapper.class);
        manualCases = mock(IActivityCancelManualRefundCaseService.class);

        when(regMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(inv -> {
            CmsRegistration q = inv.getArgument(0, CmsRegistration.class);
            if (q.getPaymentStatus() != null && !Integer.valueOf(2).equals(q.getPaymentStatus())) return Collections.emptyList();
            List<CmsRegistration> rows = paidByOwner.get(q.getOwnerType() + ":" + q.getOwnerId());
            return rows == null ? Collections.emptyList() : rows;
        });
        when(regMapper.selectCmsRegistrationByIdForUpdate(anyLong()))
                .thenAnswer(inv -> lockedById.get(inv.getArgument(0, Long.class)));
        when(activityMapper.selectCmsActivityByIdForUpdate(anyLong()))
                .thenAnswer(inv -> activities.get(inv.getArgument(0, Long.class)));
        when(activityMapper.selectCmsActivityById(anyLong()))
                .thenAnswer(inv -> activities.get(inv.getArgument(0, Long.class)));
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class))).thenAnswer(inv -> {
            CmsActivity q = inv.getArgument(0, CmsActivity.class);
            List<CmsActivity> out = new ArrayList<>();
            for (CmsActivity a : activities.values()) {
                boolean hideCancelled = Integer.valueOf(1).equals(q.getExcludeCancelled());
                if (q.getTopicId() != null && q.getTopicId().equals(a.getTopicId())
                        && !(hideCancelled && a.getCancelTime() != null)) {
                    out.add(a);
                }
            }
            return out;
        });
        when(activityMapper.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);
        when(topicService.casUserStatus(anyLong(), anyInt(), anyInt())).thenReturn(1);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundApplication.class).setId(++refundSeq);
            return 1;
        });
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
        t.setName("标准票");
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(t);
        when(ticketMapper.restoreInventory(anyLong(), anyLong())).thenReturn(1);

        service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService",
                mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(service, "refundService", refundService);
        ReflectionTestUtils.setField(service, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(service, "notifyService", mock(INotifyService.class));
        var notices = mock(com.chengyinhub.business.service.IImService.class);
        when(notices.sendSystem(any(), any(), any(), any())).thenReturn(910L);
        ReflectionTestUtils.setField(service, "imService", notices);
        ReflectionTestUtils.setField(service, "refundPayoutService", payout);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", manualCases);
        settlementDetails = mock(com.chengyinhub.business.mapper.SettlementDetailMapper.class);
        ReflectionTestUtils.setField(service, "settlementDetailMapper", settlementDetails);
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireTeamUnit(service, regMapper);
    }

    private com.chengyinhub.business.mapper.SettlementDetailMapper settlementDetails;

    /** 这张报名的钱已随结算批次结给收款方(settlement_detail.status=1)。 */
    private void settled(Long registrationId) {
        com.chengyinhub.business.domain.SettlementDetail d = new com.chengyinhub.business.domain.SettlementDetail();
        d.setStatus(1);
        when(settlementDetails.selectSettledDetails("registration", registrationId))
                .thenReturn(Collections.singletonList(d));
    }

    // ---------------------------------------------------------------- fixtures

    private CmsTopic topic(Long hostMemberId) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("外滩夜行");
        t.setMemberId(hostMemberId);
        t.setUserStatus(1);
        t.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(t);
        return t;
    }

    private CmsActivity activity(Long id, Long topicId, Long memberId) {
        CmsActivity a = new CmsActivity();
        a.setId(id);
        a.setName("场次" + id);
        a.setTopicId(topicId);
        a.setMemberId(memberId);
        a.setDelFlag(0);
        a.setPublishStatus(1L);
        activities.put(id, a);
        return a;
    }

    private CmsRegistration paid(Long id, long ownerType, Long ownerId, int verification) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setRegistrationNo("R" + id);
        r.setMemberId(500L + id);
        r.setTicketId(TICKET);
        r.setOwnerType(ownerType);
        r.setOwnerId(ownerId);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(verification);
        r.setPointUsed(0L);
        r.setOrderNum(1L);
        r.setPayableAmount(new BigDecimal("68.00"));
        r.setWechatPaymentAmount(new BigDecimal("68.00"));
        paidByOwner.computeIfAbsent(ownerType + ":" + ownerId, k -> new ArrayList<>()).add(r);
        lockedById.put(id, r);
        return r;
    }

    private List<Long> refundedRegistrationIds() {
        ArgumentCaptor<RefundApplication> cap = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refundService, org.mockito.Mockito.atLeast(0)).saveRefund(cap.capture());
        List<Long> ids = new ArrayList<>();
        for (RefundApplication app : cap.getAllValues()) ids.add(app.getSourceId());
        Collections.sort(ids);
        return ids;
    }

    // ---------------------------------------------------------------- 商家主办场次

    @Test
    void merchantHostCancelsOwnSessionAndEveryPaidPlayerIsRefundedFully() {
        topic(OWNER);
        activity(ACTIVITY, TOPIC, OWNER);
        paid(1L, 2L, ACTIVITY, 0);
        paid(2L, 2L, ACTIVITY, 0);

        ActivityCancelResult res = service.cancelActivityByMerchantHost(ACTIVITY, OWNER, STAFF, "暴雨预警");

        assertEquals(2, res.getRefundedOrders());
        assertEquals(Arrays.asList(1L, 2L), refundedRegistrationIds());
        ArgumentCaptor<RefundApplication> cap = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refundService, times(2)).saveRefund(cap.capture());
        for (RefundApplication app : cap.getAllValues()) {
            assertEquals(0, new BigDecimal("68.00").compareTo(app.getRefundAmount()), "全额,0 手续费");
        }
        ArgumentCaptor<CmsActivity> upd = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(upd.capture());
        assertEquals(String.valueOf(STAFF), upd.getValue().getUpdateBy(), "留痕记实际操作的员工");
        assertEquals("暴雨预警", upd.getValue().getCancelReason());
    }

    @Test
    void contractorOfSomeoneElsesTopicCannotCancelTheSession() {
        topic(STRANGER);                       // 主题主办是别人,商家只是承办
        activity(ACTIVITY, TOPIC, OWNER);
        paid(1L, 2L, ACTIVITY, 0);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelActivityByMerchantHost(ACTIVITY, OWNER, STAFF, "暴雨预警"));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    @Test
    void merchantCannotCancelAnotherOwnersSession() {
        activity(ACTIVITY, null, STRANGER);
        paid(1L, 2L, ACTIVITY, 0);

        assertThrows(ServiceException.class,
                () -> service.cancelActivityByMerchantHost(ACTIVITY, OWNER, STAFF, "暴雨预警"));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
    }

    /**
     * [RV-p6line F2] 店主同时是俱乐部主理人时,俱乐部场次的 member_id 也是店主。商家入口(含持
     * PROJECT_MANAGE 的员工)不能取消它:俱乐部场次只能由俱乐部主理人取消并走俱乐部收尾。
     * 预览与取消同一道闸,零退款、场次不被写。
     */
    @Test
    void merchantScopeCannotCancelClubSessionEvenIfOwnerIsTheClubHost() {
        topic(OWNER);
        activity(ACTIVITY, TOPIC, OWNER).setClubId(5L);
        activity(SHELL, null, OWNER).setClubId(5L);          // 不挂主题的俱乐部系列场次
        paid(1L, 2L, ACTIVITY, 0);
        paid(2L, 2L, SHELL, 0);
        var clubs = mock(com.chengyinhub.business.mapper.ClubMapper.class);
        var club = new com.chengyinhub.business.domain.Club();
        club.setId(5L);
        club.setMemberId(OWNER);
        club.setDelFlag(0);
        when(clubs.selectClubByIdForUpdate(5L)).thenReturn(club);
        ReflectionTestUtils.setField(service, "clubMapper", clubs);

        // Preview remains read-only; cancellation now rechecks the current club
        // owner under the original scope locks before rejecting merchant scope.
        for (Long id : Arrays.asList(ACTIVITY, SHELL)) {
            ServiceException previewDenied = assertThrows(ServiceException.class,
                    () -> service.countPaidPlayersForMerchantActivityCancel(id, OWNER));
            assertEquals(Integer.valueOf(403), previewDenied.getCode());
        }
        verify(activityMapper, never()).selectCmsActivityByIdForUpdate(anyLong());
        for (Long id : Arrays.asList(ACTIVITY, SHELL)) {
            ServiceException denied = assertThrows(ServiceException.class,
                    () -> service.cancelActivityByMerchantHost(id, OWNER, STAFF, "暴雨预警"));
            assertEquals(Integer.valueOf(403), denied.getCode());
        }
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
        verify(activityMapper, times(2)).selectCmsActivityByIdForUpdate(anyLong());
        verify(regMapper, never()).selectCancellationRowsForUpdate(anyLong(), anyLong());
    }

    /** 重复点:第一次已把活动置取消,第二次必须被拒,不能对已退的单再走一遍退款。 */
    @Test
    void merchantRepeatCancelIsRejectedWithoutSecondRefund() {
        activity(ACTIVITY, null, OWNER).setCancelTime(new Date());
        paid(1L, 2L, ACTIVITY, 0);

        assertThrows(ServiceException.class,
                () -> service.cancelActivityByMerchantHost(ACTIVITY, OWNER, STAFF, "再点一次"));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(payout, never()).payoutAfterReview(anyLong());
    }

    /**
     * 并发点:第二个请求读列表时这单还是已付款,等拿到行锁时第一个请求已经退完(payment_status=4)。
     * 必须整次 409 回滚,零退款单、零库存回补、活动不被再写一次。
     */
    @Test
    void concurrentSecondMerchantCancelSeesRefundedRowUnderLockAndFailsClosed() {
        activity(ACTIVITY, null, OWNER);
        paid(1L, 2L, ACTIVITY, 0);
        lockedById.put(1L, refundedByFirstRequest(1L, 2L, ACTIVITY));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelActivityByMerchantHost(ACTIVITY, OWNER, STAFF, "暴雨预警"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    /** 第一个请求提交后,同一行在库里的样子:已退款、报名取消。 */
    private CmsRegistration refundedByFirstRequest(Long id, long ownerType, Long ownerId) {
        CmsRegistration r = new CmsRegistration();
        org.springframework.beans.BeanUtils.copyProperties(lockedById.get(id), r);
        r.setPaymentStatus(4);
        r.setRegistrationStatus(3);
        return r;
    }

    // ---------------------------------------------------------------- 主题主办取消整个主题

    @Test
    void topicHostCancelStopsSellingRefundsTopicTicketsAndEverySession() {
        topic(OWNER);
        paid(1L, 1L, TOPIC, 0);                  // 主题级票,未核销 → 退
        paid(2L, 1L, TOPIC, 1);                  // 主题级票,已核销(=已结算)→ 不退,也不算人工
        activity(SHELL, TOPIC, OWNER);           // 自办场次
        paid(3L, 2L, SHELL, 0);
        activity(202L, TOPIC, STRANGER);         // 承办方的场次:主题取消了,它也要退
        paid(4L, 2L, 202L, 0);

        ActivityCancelResult res = service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运");

        verify(topicService).casUserStatus(TOPIC, 1, 0);
        assertEquals(Arrays.asList(1L, 3L, 4L), refundedRegistrationIds());
        assertEquals(3, res.getRefundedOrders());
        assertEquals(0, res.getManualOrders(), "整单全核销是终局,不是人工的活");
        verify(activityMapper, times(2)).updateCmsActivity(any(CmsActivity.class));
        verify(manualCases, never()).recordCase(any(ActivityCancelManualRefundCase.class));
    }

    /**
     * [RV-p6line F1] 主题取消只取消还没结束的场次。已结束的场次(自办或承办、含俱乐部)一律不动:
     * 不取消、不退未核销的票(没扫码就玩了的也算玩过)、不经退款核心去冲回承办方已结算的钱。
     * 确认框人数同一口径。
     */
    @Test
    void topicCancelLeavesEndedSessionsUntouchedAndOnlyCancelsUnfinishedOnes() {
        topic(OWNER);
        Date past = new Date(System.currentTimeMillis() - 3600_000L);
        Date future = new Date(System.currentTimeMillis() + 3600_000L);
        activity(SHELL, TOPIC, OWNER).setEndDate(future);              // 还没结束 → 取消并退
        paid(3L, 2L, SHELL, 0);
        activity(202L, TOPIC, STRANGER).setEndDate(past);              // 承办方已办完的场次 → 不动
        paid(4L, 2L, 202L, 0);
        CmsActivity endedClub = activity(203L, TOPIC, STRANGER);        // 已结束的俱乐部场次 → 也不动
        endedClub.setEndDate(past);
        endedClub.setClubId(5L);
        paid(5L, 2L, 203L, 0);
        com.chengyinhub.business.service.IClubEventOpsService clubOps =
                mock(com.chengyinhub.business.service.IClubEventOpsService.class);
        ReflectionTestUtils.setField(service, "clubEventOpsService", clubOps);

        assertEquals(1, service.countPaidPlayersForTopicCancel(TOPIC, OWNER));
        ActivityCancelResult res = service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运");

        assertEquals(Arrays.asList(3L), refundedRegistrationIds(), "已结束场次的未核销票不退");
        assertEquals(1, res.getRefundedOrders());
        ArgumentCaptor<CmsActivity> upd = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper, times(1)).updateCmsActivity(upd.capture());
        assertEquals(SHELL, upd.getValue().getId(), "只有未结束的场次被置取消");
        verify(activityMapper, never()).selectCmsActivityByIdForUpdate(202L);
        verify(clubOps, never()).cancelActivityForAuthorizedTopicCancellation(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(clubOps, never()).captureAuthorizedTopicCancellationPayments(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(clubOps, never()).cancelAuthorizedTopicWithObservedPayments(
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    /**
     * [RV-p6line F1] 已结算的钱不冲回:主题票或未结束场次里有订单的钱已结给主办/承办方,
     * 自动退会经退款核心冲销、从对方余额扣回。兜底 fail closed(总控 9-18 选 A):整次 409,一分不动。
     */
    @Test
    void topicCancelRefusesWhenATopicTicketIsAlreadySettled() {
        topic(OWNER);
        paid(1L, 1L, TOPIC, 0);
        settled(1L);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
    }

    @Test
    void topicCancelRefusesWhenTheTopicIsAlreadySettled() {
        topic(OWNER).setIsSettlement(1);
        paid(1L, 1L, TOPIC, 0);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
    }

    @Test
    void topicCancelRefusesWhenAContractorSessionIsAlreadySettled() {
        topic(OWNER);
        activity(202L, TOPIC, STRANGER).setEndDate(new Date(System.currentTimeMillis() + 3600_000L));
        paid(4L, 2L, 202L, 0);
        settled(4L);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    /** 单场取消(商家/俱乐部自己取消自己的场次)不受这道闸影响,口径与改动前一致。 */
    @Test
    void singleSessionMerchantCancelKeepsExistingBehaviourForSettledRows() {
        activity(ACTIVITY, null, OWNER);
        paid(1L, 2L, ACTIVITY, 0);
        settled(1L);

        assertEquals(1, service.cancelActivityByMerchantHost(ACTIVITY, OWNER, STAFF, "暴雨预警").getRefundedOrders());
    }

    @Test
    void onlyTheTopicHostCanCancelTheTopic() {
        topic(STRANGER);
        paid(1L, 1L, TOPIC, 0);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(topicService, never()).casUserStatus(anyLong(), anyInt(), anyInt());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
    }

    @Test
    void clubTopicIsSentToClubEndTopicNotCancelledHere() {
        topic(OWNER).setClubId(5L);
        paid(1L, 1L, TOPIC, 0);

        assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
    }

    /** 重复点:第一次退完后库里这些单已是 4、场次已取消 ⇒ 第二次零退款单,不会退两遍。 */
    @Test
    void repeatTopicCancelRefundsNothingTheSecondTime() {
        CmsTopic t = topic(OWNER);
        paid(1L, 1L, TOPIC, 0);
        CmsActivity shell = activity(SHELL, TOPIC, OWNER);
        paid(3L, 2L, SHELL, 0);
        service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运");
        verify(refundService, times(2)).saveRefund(any(RefundApplication.class));

        // 第一次提交后的库:停售、单已退、场次已取消
        t.setUserStatus(0);
        paidByOwner.clear();
        shell.setCancelTime(new Date());

        ActivityCancelResult again = service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运");

        assertEquals(0, again.getRefundedOrders());
        verify(refundService, times(2)).saveRefund(any(RefundApplication.class));
        verify(topicService, times(1)).casUserStatus(anyLong(), anyInt(), anyInt());
    }

    /** 并发点:列表读到已付款,锁行时已被另一请求退掉 ⇒ 整次 409,零退款单、场次不被写。 */
    @Test
    void concurrentSecondTopicCancelSeesRefundedRowUnderLockAndFailsClosed() {
        topic(OWNER);
        paid(1L, 1L, TOPIC, 0);
        lockedById.put(1L, refundedByFirstRequest(1L, 1L, TOPIC));
        activity(SHELL, TOPIC, OWNER);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(ticketMapper, never()).restoreInventory(anyLong(), anyLong());
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    /**
     * 部分核销的主题多票单:拍板 A 要转人工工单,但工单表只认场次(activity_id NOT NULL),
     * 主题票没处落 ⇒ 整次拒绝,钱一分不动;不能静默跳过让未核销那几人的钱卡死没人知道。
     */
    @Test
    void partiallyVerifiedTopicOrderFailsClosedBecauseManualCaseOnlyKnowsSessions() {
        topic(OWNER);
        CmsRegistration payer = paid(1L, 1L, TOPIC, 0);
        CmsRegistration child = paid(2L, 1L, TOPIC, 1);
        payer.setParentOrderNo("P1");
        child.setParentOrderNo("P1");
        child.setMemberId(payer.getMemberId());
        child.setPayableAmount(BigDecimal.ZERO);
        when(regMapper.selectByParentOrderNoForUpdate("P1", TOPIC)).thenReturn(Arrays.asList(payer, child));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运"));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verify(manualCases, never()).recordCase(any(ActivityCancelManualRefundCase.class));
    }

    @Test
    void confirmCountIsDistinctPaidUnverifiedPlayersAcrossTopicTicketsAndSessions() {
        topic(OWNER);
        paid(1L, 1L, TOPIC, 0);
        paid(2L, 1L, TOPIC, 1);                  // 已核销不在「将被退款」之列
        activity(SHELL, TOPIC, OWNER);
        paid(3L, 2L, SHELL, 0).setMemberId(501L); // 与 1 号同一个人,按人去重

        assertEquals(1, service.countPaidPlayersForTopicCancel(TOPIC, OWNER));
        assertThrows(ServiceException.class, () -> service.countPaidPlayersForTopicCancel(TOPIC, STRANGER));
    }

    // ---------------------------------------------------------------- 复审(code-review)补的三条

    /** 预览与取消同一套授权:承办别人主题的场次,预览也不给人数(否则弹「N 位」点下去 403)。 */
    @Test
    void merchantPreviewUsesTheSameHostRuleAsCancel() {
        topic(STRANGER);
        activity(ACTIVITY, TOPIC, OWNER);
        paid(1L, 2L, ACTIVITY, 0);

        assertThrows(ServiceException.class,
                () -> service.countPaidPlayersForMerchantActivityCancel(ACTIVITY, OWNER));
    }

    /** 部分核销的多票单不会被自动退(转人工),确认框里的「N 位全额退款」不能把他们算进去。 */
    @Test
    void confirmCountExcludesPlayersOfPartiallyVerifiedOrders() {
        activity(ACTIVITY, null, OWNER);
        paid(1L, 2L, ACTIVITY, 0);                         // 单票未核销 → 会退
        CmsRegistration a = paid(2L, 2L, ACTIVITY, 0);      // 同单一人未核销、一人已核销 → 转人工
        CmsRegistration b = paid(3L, 2L, ACTIVITY, 1);
        a.setParentOrderNo("P9");
        b.setParentOrderNo("P9");

        assertEquals(1, service.countPaidPlayersForActivityCancel(ACTIVITY, OWNER));
    }

    /**
     * 主题取消逐场取消时,俱乐部名下的场次要走 6-17 那套俱乐部收尾(候补终止/领队回收/站内取消通知),
     * 不能只走裸退款核心 —— 否则 6-17 在控制器补上的缺口又在这一层开了。
     */
    @Test
    void topicCancelRoutesClubSessionsThroughClubWrapUp() {
        topic(OWNER);
        CmsActivity clubShell = activity(202L, TOPIC, STRANGER);
        clubShell.setClubId(5L);
        paid(4L, 2L, 202L, 0);
        com.chengyinhub.business.service.IClubEventOpsService clubOps =
                mock(com.chengyinhub.business.service.IClubEventOpsService.class);
        var captured = new com.chengyinhub.business.domain.dto.RegistrationCancellationPayments(Collections.singletonList(
                new com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.Scope(
                        2L,202L,TOPIC,STRANGER,5L,Collections.emptyList())));
        when(clubOps.captureAuthorizedTopicCancellationPayments(STAFF, TOPIC, 5L, 202L, "台风停运"))
                .thenAnswer(call -> {
                    org.junit.jupiter.api.Assertions.assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    return captured;
                });
        when(clubOps.cancelAuthorizedTopicWithObservedPayments(org.mockito.ArgumentMatchers.eq(STAFF),
                org.mockito.ArgumentMatchers.eq(TOPIC), org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(202L), org.mockito.ArgumentMatchers.eq("台风停运"),
                org.mockito.ArgumentMatchers.any(com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.class)))
                .thenAnswer(call -> {
                    org.junit.jupiter.api.Assertions.assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    com.chengyinhub.business.domain.dto.RegistrationCancellationPayments original = call.getArgument(5);
                    assertEquals(captured.scopes().getFirst(), original.requireScope(2L,202L));
                    return new ActivityCancelResult(1, 0, 0);
                });
        ReflectionTestUtils.setField(service, "clubEventOpsService", clubOps);

        ActivityCancelResult res = service.cancelTopicByHost(TOPIC, OWNER, STAFF, "台风停运");

        verify(clubOps).captureAuthorizedTopicCancellationPayments(STAFF, TOPIC, 5L, 202L, "台风停运");
        ArgumentCaptor<com.chengyinhub.business.domain.dto.RegistrationCancellationPayments> observed =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.class);
        verify(clubOps).cancelAuthorizedTopicWithObservedPayments(org.mockito.ArgumentMatchers.eq(STAFF),
                org.mockito.ArgumentMatchers.eq(TOPIC), org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(202L), org.mockito.ArgumentMatchers.eq("台风停运"), observed.capture());
        assertEquals(captured.scopes().getFirst(), observed.getValue().requireScope(2L,202L));
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        assertEquals(1, res.getRefundedOrders());
    }
}
