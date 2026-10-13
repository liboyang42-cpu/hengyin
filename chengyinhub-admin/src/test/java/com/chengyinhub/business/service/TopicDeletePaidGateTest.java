package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.utils.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * C3 · 主题删除付费闸 —— 主题侧一直没有活动侧那道闸。
 *
 * <p><b>洞</b>:{@code ApiTopicController.topicDelete} 只做 IDOR 归属校验就直接 delFlag=2 软删,
 * <b>不看有没有付费报名</b>。删完主题,报名单还在、钱还在平台,但主题查不到了 ——
 * 钱原地悬空,且「取消并全额退」这条路也没了(主题侧压根没有取消接口)。
 *
 * <p><b>口径参照活动侧</b>({@code ApiActivityController:441},C2 已在 master):
 * 「删除只软删,不碰 registration、不退款 ⇒ 钱原地悬空。『删除』不该隐含大额资金操作,
 * 故拒绝并引导去走『取消活动』」。主题侧照搬这个理由。
 *
 * <p><b>★ W9 修正:原闸瞄准了一个不存在的状态。</b>原判据拦 {@code payment_status ∈ {1,2}},理由是
 * 「1 = 支付中 = 钱在途,最不能删的时刻」。但 <b>全仓没有任何地方写入 1</b>(2026-07-17 grep
 * {@code setPaymentStatus} 实证:只出现 0/2/3/4)⇒ {@code paying} 恒 0 ⇒ 那一半是**永不触发的假闸**,
 * 而下单主通路({@code CmsRegistrationServiceImpl:406})写的是 <b>0</b> ⇒ <b>真正的支付窗口原样敞着</b>:
 * 玩家拉起支付 → 创建者此刻删主题 → 放行 → 微信回调到达把单置 2 ⇒ 钱收了、单成了、主题没了。
 *
 * <p><b>正确判据</b>:
 * <ul>
 *   <li>2 已支付 —— 拦。钱在平台手里,必须先退</li>
 *   <li>0 待支付 —— <b>无条件拦</b>。单还活着,钱随时可能进来</li>
 *   <li>3 支付失败/已关单 —— 放行。单已死,无副作用</li>
 *   <li>4 已退款 —— 放行。钱已结清,报名单还在当对账凭证,软删主题不毁它
 *       (与 F7 不同:那是 <b>物理</b> 删报名单,4 必须拦否则毁证)</li>
 *   <li>1 支付中 —— 仍拦,但**别指望它**:没有任何生产路径会写出这个状态</li>
 * </ul>
 *
 * <p><b>★ 为什么不拿 {@code pay_expire_time} 放过「过期弃单」</b>(2026-07-17 拍板):该字段后端
 * <b>没有强制力</b> —— 支付口从不校验它({@code ApiRegistrationController:385-405} 只拦
 * {@code payment_status ∈ {2,4}})⇒ 过期弃单照样能换到支付参数并支付成功 ⇒「窗口已过 = 安全」
 * 不成立,放行它就是把同一个洞从「新单热窗口」挪到「陈年单」。
 * 弃单的解堵靠 {@code orderCloseTask}(每 5 分钟)把 {@code 0 → 3},<b>不靠这道闸放行</b>
 * ⇒ 弃单最多堵约 35 分钟,自愈。⚠️ 该任务一旦停,主题就永久删不掉。
 *
 * <p>⚠️ <b>也别改用 {@code expires_at}</b>:那是自玩通行证的 90 天权益有效期(全仓只在
 * {@code CmsRegistrationServiceImpl:638} 写),普通报名恒为 NULL —— 拿它当判据要么恒拦要么恒不拦,
 * 又造一道假闸,正是本卡要拆的病。
 *
 * <p>⚠️ 负控(已实测):删付费闸 ⇒ {@code paidTopic_cannotDelete} 红;删待支付闸 ⇒
 * {@code pendingOrder_cannotDelete} + {@code expiredPendingOrder_stillCannotDelete} 红;
 * 把 3 也拦上 ⇒ {@code closedOrdersOnly_canDelete} 红。多道闸抛同型异常,故各自断言错误文案
 * 指明是哪道闸,防「拆了一道另一道接住」的假绿。
 */
class TopicDeletePaidGateTest {

    private static final Long ME = 100L;
    private static final Long TOPIC = 700L;

    private CmsTopicMapper topicMapper;
    private CmsRegistrationMapper registrationMapper;
    private CmsActivityMapper activityMapper;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);

        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(ME);
        t.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(topicMapper.updateCmsTopic(ArgumentMatchers.any())).thenReturn(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void childActivityOpenOrderBlocksDeletionEvenWithoutTicketOrCash(int status) {
        givenChildOrder(status);
        RuntimeException e = assertThrows(RuntimeException.class, () -> delete(ME));
        assertTrue(e.getMessage().contains("场次"), e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void childActivityClosedOrRefundedOrdersKeepDeletionAvailable(int status) {
        givenChildOrder(status);
        delete(ME);
        verify(activityMapper).selectCmsActivityList(ArgumentMatchers.argThat(q ->
                TOPIC.equals(q.getTopicId()) && q.getMemberId() == null && q.getDelFlag() == null
                        && q.getStatus() == null && q.getPublishStatus() == null));
        verify(topicMapper).updateCmsTopic(ArgumentMatchers.any());
    }

    private void givenChildOrder(int status) {
        CmsActivity child = new CmsActivity();
        child.setId(901L);
        child.setTopicId(TOPIC);
        child.setMemberId(200L);
        child.setDelFlag(2);
        child.setStatus(0L);
        when(activityMapper.selectCmsActivityList(ArgumentMatchers.argThat(q ->
                q != null && TOPIC.equals(q.getTopicId())))).thenReturn(Collections.singletonList(child));
        CmsRegistration order = new CmsRegistration();
        order.setPaymentStatus(status);
        order.setPayableAmount(java.math.BigDecimal.ZERO);
        order.setPayExpireTime(DateUtils.addMinutes(new Date(), -1));
        when(registrationMapper.selectCmsRegistrationList(ArgumentMatchers.argThat(q ->
                q != null && Long.valueOf(2L).equals(q.getOwnerType())
                        && Long.valueOf(901L).equals(q.getOwnerId())
                        && Integer.valueOf(status).equals(q.getPaymentStatus()))))
                .thenReturn(Collections.singletonList(order));
    }

    /** 让「查该主题下 payment_status=X 的报名」返回 n 条。 */
    private void givenRegistrationsWithStatus(Integer status, int n) {
        givenRegistrationRows(status, n == 0 ? Collections.emptyList()
                : Collections.nCopies(n, new CmsRegistration()));
    }

    private void givenRegistrationRows(Integer status, List<CmsRegistration> rows) {
        when(registrationMapper.selectCmsRegistrationList(ArgumentMatchers.argThat(
                q -> q != null && status.equals(q.getPaymentStatus())
                        && Long.valueOf(1L).equals(q.getOwnerType())
                        && TOPIC.equals(q.getOwnerId()))))
                .thenReturn(rows);
    }

    /**
     * 一条**下单主通路真实写出来**的待支付单:{@code payment_status=0} +
     * {@code pay_expire_time = 下单时刻 + 30 分钟}(照 {@code CmsRegistrationServiceImpl:406/411} 的写法)。
     *
     * <p>⚠️ 负控必须从这个真实状态进入 —— 原测试用的 {@code payment_status=1} 是**没有任何生产路径
     * 会写出的状态**,拿它当「支付中」正是本卡要拆的假保护。
     */
    private CmsRegistration pendingOrderLike(int payExpireMinutesFromNow) {
        CmsRegistration r = new CmsRegistration();
        r.setPaymentStatus(0);
        r.setPayExpireTime(DateUtils.addMinutes(new Date(), payExpireMinutesFromNow));
        return r;
    }

    private void delete(Long operator) {
        ReflectionTestUtils.invokeMethod(service, "deleteTopicByOwner", TOPIC, operator);
    }

    /** ★已支付报名(payment_status=2)⇒ 拒删,并引导去取消。洞本身。 */
    @Test
    void paidTopic_cannotDelete() {
        givenRegistrationsWithStatus(2, 3);
        RuntimeException e = assertThrows(RuntimeException.class, () -> delete(ME));
        assertTrue(e.getMessage().contains("付费报名"), "实际:" + e.getMessage());
    }

    /**
     * ★★ W9 洞本身:玩家拉起支付(status=0、支付窗口未过),创建者此刻删主题 ⇒ 必须拒。
     * 放行的话回调到达把单置 2 = 钱收了、单成了、主题没了。
     */
    @Test
    void pendingOrder_cannotDelete() {
        givenRegistrationRows(0, Collections.singletonList(pendingOrderLike(30)));
        RuntimeException e = assertThrows(RuntimeException.class, () -> delete(ME));
        assertTrue(e.getMessage().contains("待支付"),
                "得指明是待支付这道闸拦的,否则另一道闸接住也算绿。实际:" + e.getMessage());
    }

    /**
     * ★★ 支付窗口<b>已过</b>的弃单 ⇒ <b>仍然拒</b>。这条最反直觉,也最关键:
     * pay_expire_time 后端没有强制力,过期单照样能换到支付参数并支付成功
     * ⇒ 放行它 = 把同一个洞从「新单热窗口」挪到「陈年单」。解堵靠 orderCloseTask 关单成 3。
     */
    @Test
    void expiredPendingOrder_stillCannotDelete() {
        givenRegistrationRows(0, Collections.singletonList(pendingOrderLike(-1)));
        RuntimeException e = assertThrows(RuntimeException.class, () -> delete(ME));
        assertTrue(e.getMessage().contains("待支付"), "实际:" + e.getMessage());
    }

    /** pay_expire_time 为 NULL(存量/后台手工单)⇒ 拒。⚠️ orderCloseTask 永远扫不到这种单,需人工处置。 */
    @Test
    void pendingOrderWithoutExpiry_cannotDelete() {
        CmsRegistration noExpire = new CmsRegistration();
        noExpire.setPaymentStatus(0);
        givenRegistrationRows(0, Collections.singletonList(noExpire));
        RuntimeException e = assertThrows(RuntimeException.class, () -> delete(ME));
        assertTrue(e.getMessage().contains("待支付"), "实际:" + e.getMessage());
    }

    /** 支付中(payment_status=1)⇒ 拒删。⚠️ 全仓无人写入该状态,这条只是防御,别当它是保护。 */
    @Test
    void payingTopic_cannotDelete() {
        givenRegistrationsWithStatus(1, 1);
        assertThrows(RuntimeException.class, () -> delete(ME));
    }

    /** 已退款(4)⇒ 放行。钱已结清,报名单还在当凭证,软删主题不毁它。 */
    @Test
    void refundedOnly_canDelete() {
        givenRegistrationsWithStatus(4, 5);
        delete(ME);
    }

    /**
     * ★ 只剩已关单的(3)⇒ 放行。这是弃单的<b>唯一出口</b>:orderCloseTask 把过期未支付单
     * 关成 3(payment_status 0→3,同时回补库存/退积分),主题届时才删得掉。
     * 拦上 3 的话,弃单就再也没有出口 = 永久堵死。
     */
    @Test
    void closedOrdersOnly_canDelete() {
        givenRegistrationsWithStatus(3, 2);
        delete(ME);
    }

    /** 无任何报名 ⇒ 放行(正常删草稿路径,拦了就是打挂功能)。 */
    @Test
    void noRegistration_canDelete() {
        delete(ME);
    }

    /** 他人主题 ⇒ 拒。原有的 IDOR 校验不能被我改丢。 */
    @Test
    void othersTopic_cannotDelete() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> delete(999L));
        assertTrue(e.getMessage().contains("无权"), "实际:" + e.getMessage());
    }
}
