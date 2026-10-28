package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-15 退款/弃单释放接线 —— 弃单两条路径(cancelRegistration / closeExpiredOne)。
 *
 * <p><b>为什么弃单也必须释放</b>:预占接在**建单**(不是支付成功)⇒ 未支付单已经占了容量
 * ⇒ 取消/关单不释放 = 永久占坑。这两处与预占落点**绑死**:若哪天把 reserveEntitlements
 * 挪到「支付成功」再做,这两处就该删掉。
 *
 * <p><b>只接购票不接释放的后果</b>:容量只增不减,卖到 0 就永久锁死 —— 且全程零告警。
 * 反过来只接释放不接购票:恒返 0 行(表里根本没有 status=0 的行)= 接了个 no-op,单测还会全绿。
 * 两者必须成对。
 *
 * <p>{@code closeExpiredOne} 是**定时任务**路径,静默失效在这里最危险:没人会发现容量在慢慢漏光。
 *
 * <p>⚠️ 另外三处落点(TeamGroupServiceImpl 的 refundOne / refundWholeOrder 从单循环、
 * RefundServiceImpl 的后台审核路)因 harness 依赖过重(微信网关 / 分账冲销)未在本类覆盖,
 * 由源码断言 + code-review 兜;**这是本次接线里测试覆盖最弱的一块,已知并记录。**
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>删掉 cancelRegistration 的 releaseOnRefund ⇒ cancelUnpaid_releasesEntitlements 红</li>
 *   <li>删掉 closeExpiredOne 的 releaseOnRefund ⇒ closeExpired_releasesEntitlements 红</li>
 * </ul>
 */
class ExploreReleaseWiringTest {

    private static final Long REG = 4242L;
    private static final Long MEMBER = 1L;
    private static final Long TICKET = 9L;

    private CmsRegistrationMapper registrationMapper;
    private OmsTicketMapper ticketMapper;
    private UmsMemberMapper memberMapper;
    private IPerkLedgerService perkLedgerService;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        registrationMapper = mock(CmsRegistrationMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        perkLedgerService = mock(IPerkLedgerService.class);

        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService", perkLedgerService);
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(6001L);
        when(topics.selectCmsTopicById(6001L)).thenReturn(topic);
        com.chengyinhub.business.service.impl.RegistrationScopeFixture
                .currentReads(registrationMapper, null, topics);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        // These unpaid/cancelled fixtures have no stored external SUCCESS.
        ReflectionTestUtils.setField(service, "verifiedPaymentFacts",
                mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class));
        when(ticketMapper.restoreInventory(anyLong(), anyLong())).thenReturn(1);
    }

    /** 未支付单:payment_status=0(待支付),registration_status=1 */
    private CmsRegistration unpaid() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(1L);
        r.setOwnerId(6001L);
        r.setRegistrationNo("REG4242");
        r.setMemberId(MEMBER);
        r.setTicketId(TICKET);
        r.setOrderNum(1L);
        r.setPaymentStatus(0);
        r.setVerificationStatus(0);
        r.setRegistrationStatus(1);
        r.setPointUsed(0L);
        r.setPointsReturned(0);
        r.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        return r;
    }

    // ------------------------------------------------- cancelRegistration(玩家主动取消未支付单)

    @Test
    void cancelUnpaid_releasesEntitlements() {
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(unpaid());
        when(registrationMapper.cancelUnpaidIfActive(REG, MEMBER, "张三")).thenReturn(1);

        assertTrue(service.cancelRegistration(REG, MEMBER, "张三"));

        verify(perkLedgerService).releaseOnRefund(REG, EntitlementInvalidReason.ORGANIZER_CANCEL);
    }

    /** 更新失败(0 行)⇒ 单没真取消 ⇒ 不该释放容量,否则票还在、容量却放了 = 超卖。 */
    @Test
    void cancelUnpaid_updateFails_doesNotRelease() {
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(unpaid());
        when(registrationMapper.cancelUnpaidIfActive(REG, MEMBER, "张三")).thenReturn(0);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(unpaid());

        assertThrows(RuntimeException.class, () -> service.cancelRegistration(REG, MEMBER, "张三"));

        verify(perkLedgerService, never()).releaseOnRefund(anyLong(), anyString());
    }

    @Test
    void redeemedRejectionTakesPrecedenceOverPaidAndNeverReleasesInventory() {
        CmsRegistration row = unpaid(); row.setPaymentStatus(2); row.setVerificationStatus(1);
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(row);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(row);
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.cancelRegistration(REG, MEMBER, "张三"));
        org.junit.jupiter.api.Assertions.assertEquals("已核销的报名不能取消", error.getMessage());
        verify(registrationMapper, never()).cancelUnpaidIfActive(anyLong(), anyLong(), anyString());
        org.mockito.Mockito.verifyNoInteractions(ticketMapper, memberMapper, perkLedgerService);
    }

    @Test
    void paidFreeAndPaidMoneyOrdersUseTheActualOrderCancellationEntryWithoutPromisingEligibility() {
        for (java.math.BigDecimal amount : new java.math.BigDecimal[] {java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN}) {
            CmsRegistration row = unpaid(); row.setPaymentStatus(2); row.setPayableAmount(amount);
            when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(row);
            when(registrationMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(row);
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.cancelRegistration(REG, MEMBER, "张三"));
            org.junit.jupiter.api.Assertions.assertEquals("已完成支付，请从订单详情申请取消报名，是否可取消以订单规则为准", error.getMessage());
        }
        verify(registrationMapper, never()).cancelUnpaidIfActive(anyLong(), anyLong(), anyString());
        org.mockito.Mockito.verifyNoInteractions(ticketMapper, memberMapper, perkLedgerService);
    }

    // ------------------------------------------------- closeExpiredOne(弃单关单定时任务)

    @Test
    void closeExpired_releasesEntitlements() {
        when(registrationMapper.closeExpired(org.mockito.ArgumentMatchers.eq(REG), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(4))).thenReturn(1);
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(unpaid());

        assertTrue(service.closeExpiredOne(REG));

        verify(perkLedgerService).releaseOnRefund(REG, EntitlementInvalidReason.ORGANIZER_CANCEL);
    }

    /**
     * 条件关单 0 行 = 竞态下这单刚被支付/已取消 ⇒ **提前 return,不得释放**。
     * 放了就是:玩家刚付完钱,容量却被定时任务放掉 ⇒ 超卖。
     */
    @Test
    void closeExpired_alreadyPaidOrCancelled_doesNotRelease() {
        when(registrationMapper.closeExpired(org.mockito.ArgumentMatchers.eq(REG), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(4))).thenReturn(0);

        service.closeExpiredOne(REG);

        verify(perkLedgerService, never()).releaseOnRefund(anyLong(), anyString());
    }

    /** reason 用常量而非散落的字面量:5 处调用拼错了不报错,只是账上多一种没人认识的 reason。 */
    @Test
    void releaseReason_usesConstant_notLiteral() {
        when(registrationMapper.closeExpired(org.mockito.ArgumentMatchers.eq(REG), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(4))).thenReturn(1);
        when(registrationMapper.selectCmsRegistrationById(REG)).thenReturn(unpaid());

        service.closeExpiredOne(REG);

        verify(perkLedgerService).releaseOnRefund(anyLong(), eq("ORGANIZER_CANCEL"));
    }
}
