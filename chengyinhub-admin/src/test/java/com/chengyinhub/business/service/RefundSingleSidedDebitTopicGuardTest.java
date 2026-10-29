package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1 结算单边扣款回归(主题 coop 路)。口径同 {@link RefundSingleSidedDebitGuardTest},
 * 只是父级打款凭据换成 {@code coop_settlement.status=1}(payoutDueCoopSettlements 真 addBalance 成功才置)。
 *
 * <p>主题路的单边扣款比活动路更狠:交接档实测商家会少掉 <b>97%</b> 的单——
 * 因为主题报名在**核销**时才由 {@code verifyAndSettle → recordPaidRegistration} 落 settlement_detail,
 * 而真实放款走的是另一套 {@code coop_settlement}(T+7 到期才放),
 * 两套的键、粒度、费率来源全不同 ⇒ 明细 status=1 跟「coop 放没放款」毫无关系。
 */
class RefundSingleSidedDebitTopicGuardTest {

    private static final Long TOPIC_ID = 88L;
    private static final Long REGISTRATION_ID = 20L;
    private static final Long MERCHANT_ID = 5L;
    private static final Long PUBLISHER_ID = 6L;

    private RefundApplicationMapper refundApplicationMapper;
    private CmsRegistrationMapper cmsRegistrationMapper;
    private CmsTopicMapper cmsTopicMapper;
    private CoopOrderMapper coopOrderMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private SettlementDetailMapper settlementDetailMapper;
    private UmsMemberMapper umsMemberMapper;
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;
    private RefundServiceImpl svc;

    @BeforeEach
    void setUp() {
        refundApplicationMapper = mock(RefundApplicationMapper.class);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        settlementDetailMapper = mock(SettlementDetailMapper.class);
        umsMemberMapper = mock(UmsMemberMapper.class);
        umsMemberBalanceDetailMapper = mock(UmsMemberBalanceDetailMapper.class);

        svc = new RefundServiceImpl();
        // Explicit legacy fixture: no new frozen components; preserve the original financial owner/amount assertions.
        var originalOwner = mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class);
        when(originalOwner.hasFrozenOriginalRefundComponents(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        ReflectionTestUtils.setField(svc, "coopSettlementClaimService", originalOwner);
        when(refundApplicationMapper.selectRegistrationHistoryForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> {
                    RefundApplication row = refundApplicationMapper.selectRefundApplicationById(2L);
                    if (row == null || !java.util.Objects.equals(row.getSourceId(), call.getArgument(0)))
                        return java.util.List.of();
                    // Old mock rows omitted the real table's default pending status.
                    if (row.getStatus() == null) row.setStatus(0);
                    return java.util.List.of(row);
                });
        CmsTopic originalTopic = new CmsTopic(); originalTopic.setId(TOPIC_ID); originalTopic.setMemberId(PUBLISHER_ID);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(originalTopic);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> cmsTopicMapper.selectCmsTopicById(call.getArgument(0)));

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(svc, "refundApplicationMapper", refundApplicationMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", cmsRegistrationMapper);
        org.mockito.Mockito.when(cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> cmsRegistrationMapper.selectCmsRegistrationById(call.getArgument(0)));
        when(cmsRegistrationMapper.claimRegistrationRefunded(anyLong(), any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopSettlementMapper", coopSettlementMapper);
        ReflectionTestUtils.setField(svc, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(svc, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(svc, "umsMemberBalanceDetailMapper", umsMemberBalanceDetailMapper);
        ReflectionTestUtils.setField(svc, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(svc, "merchantReferralBountyService",
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        when(umsMemberMapper.lockMemberById(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(umsMemberMapper.deductBalance(anyLong(), any())).thenReturn(1);
        when(umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(any())).thenReturn(1);
    }

    /**
     * 主题报名:owner_type=1 指向 cms_topic;核销时才落 settlement_detail。
     * 默认给「有生效合作单」的 coop 主题,单条已结算明细归 receiverId/receiverType。
     */
    private void givenVerifiedRegistrationOnTopic(Long receiverId, String receiverType) {
        RefundApplication refund = new RefundApplication();
        refund.setId(2L);
        refund.setSourceType("registration");
        refund.setSourceId(REGISTRATION_ID);
        refund.setRefundAmount(new BigDecimal("100"));
        refund.setPaidAmount(new BigDecimal("100"));
        when(refundApplicationMapper.selectRefundApplicationById(2L)).thenReturn(refund);

        CmsRegistration reg = new CmsRegistration();
        reg.setPaymentStatus(2);
        reg.setPayableAmount(new BigDecimal("100"));
        reg.setId(REGISTRATION_ID);
        reg.setOwnerType(1L);
        reg.setOwnerId(TOPIC_ID);
        when(cmsRegistrationMapper.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(reg);

        when(refundApplicationMapper.updateRefundStatusIfPending(anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        SettlementDetail d = new SettlementDetail();
        d.setMerchantId(MERCHANT_ID);
        d.setReceiverType(receiverType);
        d.setReceiverId(receiverId);
        d.setGrossAmount(new BigDecimal("90.00"));
        d.setCommissionAmount(BigDecimal.ZERO);
        d.setSettlementAmount(new BigDecimal("90.00"));
        d.setStatus(1);
        when(settlementDetailMapper.selectSettledDetails("registration", REGISTRATION_ID))
                .thenReturn(Collections.singletonList(d));
        when(settlementDetailMapper.insertAdjustment(any())).thenReturn(1);

        UmsMember m = new UmsMember();
        m.setBalance(new BigDecimal("90.00"));
        when(umsMemberMapper.selectUmsMemberById(receiverId)).thenReturn(m);

        // 默认:有生效合作单 ⇒ coop 路(放款走 coop_settlement)
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID))
                .thenReturn(Collections.singletonList(new CoopOrder()));
    }

    /** 兼容旧用例:coop 主题 + 商家收款方。 */
    private void givenVerifiedRegistrationOnTopic() {
        givenVerifiedRegistrationOnTopic(MERCHANT_ID, "merchant");
    }

    /** 无生效合作单的主题：历史版本可能存在 event_type=1 发布者现金流水。 */
    private void givenLegacyTopic(int isSettlement) {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.emptyList());
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC_ID);
        t.setMemberId(PUBLISHER_ID);
        t.setIsSettlement(isSettlement);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(t);
    }

    private RefundReviewDTO approve() {
        RefundReviewDTO req = new RefundReviewDTO();
        req.setId(2L);
        req.setStatus(1);
        return req;
    }

    /**
     * 🔴 负控方向①:明细 status=1(核销时入的账 + 运营点过确认),但 coop 那笔 T+7 还没放款
     * ⇒ 商家没收到钱 ⇒ 绝不能扣。
     */
    @Test
    void coopPayoutNotYetReleased_mustNotDebitMerchant() {
        givenVerifiedRegistrationOnTopic();
        when(coopSettlementMapper.countPaidByTopicAndPayee(TOPIC_ID, MERCHANT_ID)).thenReturn(0);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(MERCHANT_ID), any());
    }

    /** 🔴 负控方向②:coop 已放款(status=1)⇒ 商家真收到过 ⇒ 必须扣回。 */
    @Test
    void coopPayoutReleased_mustStillDebitMerchant() {
        givenVerifiedRegistrationOnTopic();
        when(coopSettlementMapper.countPaidByTopicAndPayee(TOPIC_ID, MERCHANT_ID)).thenReturn(1);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper).deductBalance(eq(MERCHANT_ID), eq(new BigDecimal("90.00")));
    }

    /**
     * 🔴 存量主题(**无生效合作单**)根本不产生 coop_settlement:
     * 历史版本会直接给发布者 addBalance(event_type=1)，H8 后不再产生该现金。
     *
     * <p>只认 coop_settlement 会把这条路判成「没打过款」⇒ 发布者真收了钱、退款却不扣 ⇒ 白拿。
     * 这是反向漏付,方向与本 P0 相反但同样是资损。
     */
    @Test
    void legacyTopicPublisherPaidDirectly_mustStillDebit() {
        givenVerifiedRegistrationOnTopic(PUBLISHER_ID, "creator");
        givenLegacyTopic(1);
        when(umsMemberBalanceDetailMapper.selectIncomeByEventTypeAndEventId(PUBLISHER_ID, 1L, TOPIC_ID))
                .thenReturn(new BigDecimal("90.00"));

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper).deductBalance(eq(PUBLISHER_ID), eq(new BigDecimal("90.00")));
    }

    /** H8 后 is_settlement=1 只证明结算 CAS 完成，没有 event_type=1 现金流水就不得扣发布者。 */
    @Test
    void h8TopicSettledWithoutPublisherCash_mustNotDebitPublisher() {
        givenVerifiedRegistrationOnTopic(PUBLISHER_ID, "creator");
        givenLegacyTopic(1);
        when(umsMemberBalanceDetailMapper.selectIncomeByEventTypeAndEventId(PUBLISHER_ID, 1L, TOPIC_ID))
                .thenReturn(BigDecimal.ZERO);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(PUBLISHER_ID), any());
    }

    /** 存量主题尚未结算(is_settlement=0)⇒ 发布者没收到过 ⇒ 不能扣。 */
    @Test
    void legacyTopicNotYetSettled_mustNotDebit() {
        givenVerifiedRegistrationOnTopic(PUBLISHER_ID, "creator");
        givenLegacyTopic(0);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(PUBLISHER_ID), any());
    }

    /**
     * 存量主题里**只有发布者**收得到钱:{@code distributeMerchantCommissions} 已于 2026-07-17
     * 作为「假账路径」拆除 ⇒ merchant 收款方从来没被打过款 ⇒ 即便主题已结算也不能扣商家。
     */
    @Test
    void legacyTopicMerchantNeverPaid_mustNotDebitMerchant() {
        givenVerifiedRegistrationOnTopic(MERCHANT_ID, "merchant");
        givenLegacyTopic(1);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(MERCHANT_ID), any());
    }

    /**
     * ⚠️ coop 主题**也会**被置 is_settlement=1(settleTopic:555 两条分支共用),
     * 但它的钱走 coop_settlement T+7 放款 ⇒ 不能拿 is_settlement 当 coop 路的打款凭据,
     * 否则 T+7 未到(status=0)就扣 = 本 P0 原样复发。
     * 判据必须先分流「有没有生效合作单」,与 settleTopic 自己的分支条件同源。
     */
    @Test
    void coopTopicSettledButPayoutPending_mustNotDebit() {
        givenVerifiedRegistrationOnTopic(MERCHANT_ID, "merchant");
        // 有生效合作单 ⇒ coop 路;主题已结算但 T+7 未到,coop_settlement 仍 status=0
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID))
                .thenReturn(Collections.singletonList(new CoopOrder()));
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC_ID);
        t.setMemberId(PUBLISHER_ID);
        t.setIsSettlement(1);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(t);
        when(coopSettlementMapper.countPaidByTopicAndPayee(TOPIC_ID, MERCHANT_ID)).thenReturn(0);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(MERCHANT_ID), any());
    }

    /**
     * 放款是**按收款方**判的,不是按主题判:同一主题下商家A已放款、商家B没放款,
     * 不能因为「这主题放过款」就把 B 也扣了。
     */
    @Test
    void coopPayoutIsPerPayee_notPerTopic() {
        givenVerifiedRegistrationOnTopic();
        // 该主题下另有收款方已放款,但本条明细的收款方(5)没有
        when(coopSettlementMapper.countPaidByTopicAndPayee(TOPIC_ID, MERCHANT_ID)).thenReturn(0);
        when(coopSettlementMapper.countPaidByTopicAndPayee(TOPIC_ID, 999L)).thenReturn(1);

        svc.reviewRefund(approve(), 42L);

        verify(umsMemberMapper, never()).deductBalance(eq(MERCHANT_ID), any());
    }
}
