package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.common.constant.PurchaseKind;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Arrays;
import java.math.BigDecimal;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-05 的**真正修法点**:TeamGroupServiceImpl 的退款闸。
 *
 * <p>为什么单开一类:{@code RefundPolicyTest} 守的是纯函数 RefundPolicy.evaluate,但 P0-05 的资损
 * 闸在调用方那一行 —— 评审用 mutation 实测过:把两处 `if (!v.refundable)` 改回原来的
 * `if (!v.refundable && v.deadline != null)`,**692 个测试全绿**。也就是 P0-05 最关键的那半个字
 * 完全没有测试守着。
 *
 * <p>那半个字为什么关键:`&& v.deadline != null` 正是让「从严」失效的地方 —— 截止时间缺失时
 * evaluate 返 refundable=false,但 deadline 也是 null,于是这道闸直接放行 ⇒ 无条件全额退。
 * 只改 RefundPolicy 不改调用方 = 白改。
 *
 * ⚠️ 负控已实测:两处任一改回 `&& v.deadline != null` ⇒ 对应用例红。
 */
class RefundDeadlineGateTest {

    private static final Long REG = 5001L;
    private static final Long TOPIC = 300L;
    private static final Long ACTIVITY = 400L;
    private static final Long TICKET = 7L;
    private static final Long MEMBER = 9L;

    private CmsRegistrationMapper regMapper;
    private OmsTicketMapper ticketMapper;
    private CmsActivityMapper activityMapper;
    private TeamGroupServiceImpl service;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(TeamGroupServiceImpl.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void attachLogCapture() {
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);

        service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(MEMBER);
        when(topicMapper.selectCmsTopicById(anyLong())).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong())).thenReturn(topic);
        when(regMapper.selectCmsRegistrationByIdForUpdate(anyLong()))
                .thenAnswer(call -> regMapper.selectCmsRegistrationById(call.getArgument(0)));
        when(activityMapper.selectCmsActivityByIdForUpdate(anyLong()))
                .thenAnswer(call -> activityMapper.selectCmsActivityById(call.getArgument(0)));
    }

    private CmsRegistration paidTopicRegistration() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(1L);
        r.setOwnerId(TOPIC);
        r.setMemberId(MEMBER);
        r.setTicketId(TICKET);
        r.setPaymentStatus(2);
        r.setVerificationStatus(0);
        return r;
    }

    private void givenTicket(Integer mode, Date startTime, Date endTime) {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
        t.setMode(mode);
        t.setStartTime(startTime);
        t.setEndTime(endTime);
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(t);
    }

    private Date future() {
        return new Date(System.currentTimeMillis() + 86400_000L);
    }

    /**
     * **核心**:mode=1 票的 start_time 缺失(G2 那个洞的产物)⇒ 必须拒绝自助退款,不能放行。
     *
     * <p>改之前:evaluate 返 refundable=true(「保守判可退」)⇒ 直接退。
     * 只改 evaluate 不改这道闸:evaluate 返 false 但 deadline 也是 null ⇒ `&& v.deadline != null`
     * 让它照样放行 ⇒ 一样无条件全额退。两处都改了才真的堵住。
     */
    @Test
    void topicTicketMissingStartTime_refundRejected() {
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(paidTopicRegistration());
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(paidTopicRegistration());
        givenTicket(1, null, null);            // G2:mode=1 票 start_time 为 null

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("人工"),
                "截止缺失必须拒自助退并转人工,实际:" + e.getMessage());
    }

    /** mode=2 票的 end_time 缺失(G3)⇒ 同样拒。 */
    @Test
    void freeExploreTicketMissingEndTime_refundRejected() {
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(paidTopicRegistration());
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(paidTopicRegistration());
        givenTicket(2, null, null);            // G3:mode=2 票 end_time 为 null

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("人工"), "实际:" + e.getMessage());
    }

    /** 已错过集合时间 ⇒ 拒(这条改前就该拒,确认没被改坏)。 */
    @Test
    void missedDeadline_stillRejected() {
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(paidTopicRegistration());
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(paidTopicRegistration());
        givenTicket(1, new Date(System.currentTimeMillis() - 86400_000L), future());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("履约窗"), "实际:" + e.getMessage());
    }

    /**
     * <b>[P0-06] 「③ 首核销转人工」必须在**执行路径**上真的生效。</b>
     *
     * <p>这条补的是一个我自己挖的坑:P0-06 把转人工写进了 {@code RefundPolicy.evaluate},
     * 但 cancelPaidRegistration 在调 evaluate **之前**就硬编码了一句
     * {@code if (已核销) throw "已核销报名不可退款"} ⇒ 新分支永远走不到。
     * 当时 24 个单测全绿,因为它们全在单独测 evaluate 这个纯函数,**没有一条从入口走进去**。
     * 后果:退款详情页(走 evaluate)显示「需人工处理」,玩家点下去弹「已核销报名不可退款」——
     * 展示与执行两个口径,而这正是同批 wiring 测试自己写在断言消息里的那个失败。
     *
     * <p>当前入口还会锁定报名和章节快照并创建待评估工单；只显示人工文案不能证明工单已记录。
     */
    @Test
    void explorePassVerified_goesToManualOnExecutionPath() {
        CmsRegistration r = paidTopicRegistration();
        r.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        r.setVerificationStatus(1);
        r.setRegistrationStatus(2);
        r.setRegistrationNo("R5001");
        r.setPayableAmount(new BigDecimal("69.00"));
        r.setWechatPaymentAmount(new BigDecimal("69.00"));
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(r);
        RegistrationEntitlementMapper entitlements = mock(RegistrationEntitlementMapper.class);
        RegistrationEntitlement used = new RegistrationEntitlement();
        used.setRegistrationId(REG);
        used.setTopicId(TOPIC);
        used.setChapterId(1L);
        used.setStatus(1);
        RegistrationEntitlement pending = new RegistrationEntitlement();
        pending.setRegistrationId(REG);
        pending.setTopicId(TOPIC);
        pending.setChapterId(2L);
        pending.setStatus(0);
        when(entitlements.selectByRegistrationForUpdate(REG)).thenReturn(Arrays.asList(used, pending));
        IActivityCancelManualRefundCaseService cases = mock(IActivityCancelManualRefundCaseService.class);
        IRefundService refunds = mock(IRefundService.class);
        ReflectionTestUtils.setField(service, "entitlementMapper", entitlements);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", cases);
        ReflectionTestUtils.setField(service, "refundService", refunds);

        assertEquals(ITeamGroupService.CANCEL_MANUAL_REVIEW, service.cancelPaidRegistration(REG, MEMBER));
        org.mockito.ArgumentCaptor<ActivityCancelManualRefundCase> draft =
                org.mockito.ArgumentCaptor.forClass(ActivityCancelManualRefundCase.class);
        verify(cases).recordCase(draft.capture());
        assertEquals(ActivityCancelManualRefundCase.TYPE_PARTIAL_CHAPTERS, draft.getValue().getCaseType());
        assertEquals(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT, draft.getValue().getStatus());
        assertEquals(Long.valueOf(6900L), draft.getValue().getPaidCashAmountFen());
        assertTrue(draft.getValue().getChapterSnapshotJson().contains("\"status\":0"));
        assertTrue(draft.getValue().getChapterSnapshotJson().contains("\"status\":1"));
        verifyNoInteractions(refunds);
        verify(entitlements, never()).invalidateByRegistration(anyLong(), any());
        verify(regMapper, never()).updateCmsRegistration(any());
    }

    @Test
    void explorePassMissingLockedSnapshotCannotReturnManualAcceptance() {
        CmsRegistration r = paidTopicRegistration();
        r.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        r.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);
        // The topic-first fence must first see the original row; the later chapter
        // snapshot read is deliberately missing, as this negative control requires.
        org.mockito.Mockito.doReturn(r, (CmsRegistration) null).when(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        IActivityCancelManualRefundCaseService cases = mock(IActivityCancelManualRefundCaseService.class);
        IRefundService refunds = mock(IRefundService.class);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", cases);
        ReflectionTestUtils.setField(service, "refundService", refunds);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(error.getMessage().contains("报名快照"));
        verifyNoInteractions(cases, refunds);
        verify(regMapper, never()).updateCmsRegistration(any());
    }

    /** 同一条闸对 ①:必须**原样硬拒**,别顺手把没授权的产品也改了。 */
    @Test
    void guidedTicketVerified_stillHardRejected() {
        CmsRegistration r = paidTopicRegistration();
        r.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        r.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);
        givenTicket(1, future(), null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("已核销报名不可退款"),
                "① 的已核销是终局(领队真把人带出去了),不该转人工。实际:" + e.getMessage());
    }

    /** 存量单(purchase_kind 为 null,迁移刻意不回填)⇒ 走原口径硬拒,别拿它赌。 */
    @Test
    void legacyRegistrationVerified_stillHardRejected() {
        CmsRegistration r = paidTopicRegistration();
        r.setPurchaseKind(null);
        r.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);
        givenTicket(2, null, future());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("已核销报名不可退款"), "实际:" + e.getMessage());
    }

    /**
     * <b>② 自玩通行证的已核销闸不能被 P0-06 弄丢</b>。
     *
     * <p>② 的 ticketId 为 null ⇒ 下面那段 evaluate 整个跳过(它裹在 {@code if (ticketId != null)} 里)
     * ⇒ 入口那道闸是 ② 唯一的已核销保护。收口时若图省事把闸删掉改成「靠 evaluate」,
     * ② 就会直接放行退款。
     */
    @Test
    void selfPlayPassVerified_stillRejectedDespiteNoTicket() {
        CmsRegistration r = paidTopicRegistration();
        r.setPurchaseKind(PurchaseKind.SELF_PLAY_PASS);
        r.setTicketId(null);                              // ② 不落 ticketId
        r.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("已核销"),
                "② 无票 ⇒ 走不到 evaluate,入口闸是它唯一的保护,删不得。实际:" + e.getMessage());
    }

    /** 活动缺 start_date ⇒ 拒(活动分支的同一道闸)。 */
    @Test
    void activityMissingStartDate_refundRejected() {
        CmsRegistration r = paidTopicRegistration();
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setTicketId(null);
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(r);

        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setStartDate(null);                // 缺锚点
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(act);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistration(REG, MEMBER));
        assertTrue(e.getMessage().contains("人工"), "实际:" + e.getMessage());
    }

    /**
     * [退款转人工-不再静默] 自助闸拒掉的「算不出来」单必须留可查日志 —— 否则这一档
     * 既不退也不拒也没人知道(玩家侧还会被客户端拦在请求之前,后端日志是唯一观测点)。
     *
     * <p>负控:摘掉 {@code rejectRefund} 里的 logger.warn ⇒ 本用例红。
     */
    @Test
    void missingDeadline_refusalLeavesSearchableWarnLog() {
        when(regMapper.selectCmsRegistrationById(REG)).thenReturn(paidTopicRegistration());
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(paidTopicRegistration());
        givenTicket(1, null, null);            // G2:mode=1 票 start_time 为 null

        assertThrows(RuntimeException.class, () -> service.cancelPaidRegistration(REG, MEMBER));

        assertTrue(logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("[退款转人工]")
                        && e.getFormattedMessage().contains(String.valueOf(REG))),
                "转人工被拒必须留可查日志,实际日志:"
                        + logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList()));
    }
}
