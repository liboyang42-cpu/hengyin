package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.service.ChannelRefundCommand;
import com.chengyinhub.business.service.ChannelRevocationOutcome;
import com.chengyinhub.business.service.FulfillmentCommand;
import com.chengyinhub.business.service.FulfillmentOutcome;
import com.chengyinhub.business.service.GrantLine;
import com.chengyinhub.business.service.IMarketingChannelRevenueReversal;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.MarketingChannelFactReader;
import com.chengyinhub.business.service.MarketingChannelRefundFactReader;
import com.chengyinhub.business.service.PaymentFacts;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.util.RefundPolicy;
import com.chengyinhub.common.constant.RefundPolicyCode;

/**
 * 营销商品成交 → {@code cms_registration}（root 裁决 A）。
 *
 * <p>守的是"钱与授权都不许假":
 * <ol>
 *   <li>每行 payable 是真实分摊、Σ = 商品实付;渠道总额/交易号只在首行;</li>
 *   <li>费率按**各覆盖主题自己的**锁价快照,不首行独占、不 0 兜底;</li>
 *   <li>不假造票面(分买价缺失即拒),分摊不守恒即拒,未付即拒,非平台自营即拒;</li>
 *   <li>退款只认 RefundPolicy 真源:表达不了的 SKU 不可售并报精确缺口;</li>
 *   <li>渠道成交金额只认渠道域回读的事实;端口缺失即关闭;同一渠道单只成交一次;</li>
 *   <li>首次使用型不落行,不用 {@code expires_at=NULL} 冒充永久通行。</li>
 * </ol>
 */
class MarketingProductFulfillmentServiceImplTest {

    private static final long MEMBER_ID = 88L;
    private static final String REQUEST_ID = "mreq-1";
    private static final String ORDER_NO = "MPO-1";
    private static final String CHANNEL_ORDER_NO = "OTA-1";
    private static final long TOPIC_A = 501L;
    private static final long TOPIC_B = 502L;
    private static final long TOPIC_C = 503L;
    private static final long PRODUCT_ID = 77L;

    @Test
    void apportionedPayableIsKeptPerRowAndChannelTotalSitsOnFirstRowOnly() {
        Fixture fixture = fixture();
        List<CmsRegistration> inserted = fixture.captureInserts();

        FulfillmentOutcome outcome = fixture.service.grant(command(
                line(TOPIC_A, "60.00", "40.00"), line(TOPIC_B, "40.00", "20.00")));

        assertEquals(2, inserted.size());
        assertEquals(0, new BigDecimal("40.00").compareTo(inserted.get(0).getPayableAmount()));
        assertEquals(0, new BigDecimal("20.00").compareTo(inserted.get(1).getPayableAmount()),
                "非首行必须保留真实分摊,不能清零");
        assertEquals(0, BigDecimal.ZERO.compareTo(inserted.get(0).getWechatPaymentAmount()),
                "积分订单不伪造渠道付款金额");
        assertEquals(0, BigDecimal.ZERO.compareTo(inserted.get(1).getWechatPaymentAmount()));
        assertNull(inserted.get(0).getTransactionId());
        assertNull(inserted.get(1).getTransactionId());
        assertEquals(REQUEST_ID, inserted.get(0).getRequestId());
        assertNull(inserted.get(1).getRequestId(), "request_id 唯一键只允许首行");
        assertEquals(2, outcome.registrationIds.size());
        assertFalse(outcome.pendingActivation);
        // 每行都应入队应计(分摊后各是真实收入),不是只首行。
        verify(fixture.posting, org.mockito.Mockito.times(2)).enqueueAccrual(anyLong());
    }

    @Test
    void eachRowCarriesItsOwnTopicFeeRate() {
        Fixture fixture = fixture();
        List<CmsRegistration> inserted = fixture.captureInserts();
        fixture.service.grant(command(line(TOPIC_A, "60.00", "40.00"), line(TOPIC_B, "40.00", "20.00")));

        assertEquals(0, new BigDecimal("0.1000").compareTo(inserted.get(0).getFeePlatformRate()));
        assertEquals(0, new BigDecimal("0.2000").compareTo(inserted.get(1).getFeePlatformRate()),
                "第二主题不能用首主题费率(结算会错账)");
    }

    @Test
    void missingTopicFeeRateIsRejectedInsteadOfZeroDefault() {
        Fixture fixture = fixture();
        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(TOPIC_B)).thenAnswer(invocation -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC_B);
            topic.setName("无费率主题");
            topic.setFeePlatformRate(null);
            return topic;
        });
        assertThrows(IllegalStateException.class, () -> fixture.service.grant(command(
                line(TOPIC_A, "60.00", "40.00"), line(TOPIC_B, "40.00", "20.00"))));
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void apportionmentMustSumToThePaidAmount() {
        Fixture fixture = fixture();
        assertThrows(RegistrationRequestConflictException.class, () -> fixture.service.grant(command(
                line(TOPIC_A, "60.00", "40.00"), line(TOPIC_B, "40.00", "19.99"))));
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void zeroListPriceIsRejectedInsteadOfFabricated() {
        Fixture fixture = fixture();
        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.grant(command(line(TOPIC_A, "0.00", "0.00"))),
                "缺分买价不得按 1/0 元假造票面");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void unpaidOrderIsRejected() {
        Fixture fixture = fixture();
        fixture.order.setStatus(MarketingProductOrder.STATUS_PENDING_CASH);
        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00"))));
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void nonPlatformOwnedTopicIsRejectedNotCollected() {
        Fixture fixture = fixture();
        when(fixture.productRoleService.isPlatformOwned(TOPIC_B)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> fixture.service.grant(command(
                line(TOPIC_A, "60.00", "40.00"), line(TOPIC_B, "40.00", "20.00"))),
                "二清闸:非平台自营不得代收");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void plainPassFreezesTheExistingFulfillmentWindowDeadlineWithoutFakeCode() {
        // ② 的原政策事实:evaluateTopicFulfillmentWindow 用「履约窗开始」作截止点。
        // 这里必须冻结同一口钟;RefundPolicyCode 至今只有 ③ 的码,所以 code/version 留空(不伪造)。
        Fixture fixture = fixture();
        List<CmsRegistration> rows = fixture.captureInserts();
        fixture.service.grant(command(plainLine(TOPIC_A, "100.00", "60.00")));

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getRefundPolicyCode(), "不得伪造 ② 的退款码");
        assertNull(rows.get(0).getRefundPolicyVersion());
        assertEquals(fixture.windowStartDate, rows.get(0).getRefundDeadline(),
                "截止点必须是履约窗开始(既有政策),不能是自造的 N 天");
    }

    @Test
    void missingFulfillmentWindowMakesTheSkuUnsellableWithPreciseGap() {
        Fixture fixture = fixture();
        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(TOPIC_A)).thenAnswer(invocation -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC_A);
            topic.setName("无履约窗主题");
            topic.setFeePlatformRate(new BigDecimal("0.1000"));
            return topic;   // startDate/endDate 缺失
        });
        IllegalStateException gap = assertThrows(IllegalStateException.class,
                () -> fixture.service.grant(command(plainLine(TOPIC_A, "100.00", "60.00"))));
        assertTrue(gap.getMessage().contains(MarketingProductFulfillmentServiceImpl.REFUND_DECISION_POINT_MISSING),
                "必须报具体缺口(实际:" + gap.getMessage() + ")");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void explorePassUsesTheExplorePolicySnapshot() {
        Fixture fixture = fixture();
        List<CmsRegistration> rows = fixture.captureInserts();
        fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00")));
        assertEquals("EXPLORE_END_100_0", rows.get(0).getRefundPolicyCode(),
                "③ 走既有 RefundPolicy 档位,不自造政策");
        assertEquals(rows.get(0).getExpiresAt(), rows.get(0).getRefundDeadline());
        verify(fixture.perkLedger).reserveEntitlements(anyLong(), eq(TOPIC_A));
    }

    @Test
    void activateOnFirstUseLineCreatesNoAccessRowAndIsReportedPending() {
        Fixture fixture = fixture();
        List<CmsRegistration> inserted = fixture.captureInserts();
        GrantLine pending = line(TOPIC_A, "100.00", "60.00");
        pending.validityKind = GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE;
        // 首次使用型仍是 ③ 探索票档位才能被退款政策表达;这里只验"不落行"。
        FulfillmentOutcome outcome = fixture.service.grant(command(pending));

        assertTrue(inserted.isEmpty(), "未激活不得落行(expires_at=NULL 会被当永久通行)");
        assertTrue(outcome.pendingActivation);
        assertEquals(Collections.singletonList(TOPIC_A), outcome.pendingActivationTopicIds);
    }

    @Test
    void replayReturnsOriginalRowsAndWritesNothingNew() {
        Fixture fixture = fixture();
        fixture.order.setPrimaryRegistrationId(9001L);
        CmsRegistration primary = new CmsRegistration();
        primary.setId(9001L);
        primary.setParentOrderNo(ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationById(9001L)).thenReturn(primary);
        CmsRegistration second = new CmsRegistration();
        second.setId(9002L);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(primary, second)));

        FulfillmentOutcome outcome = fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00")));

        assertTrue(outcome.replayed);
        assertEquals(Arrays.asList(9001L, 9002L), outcome.registrationIds);
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
        verify(fixture.posting, never()).enqueueAccrual(anyLong());
        verify(fixture.orderMapper, never()).bindRegistrations(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void unknownOrderIsRejected() {
        Fixture fixture = fixture();
        when(fixture.orderMapper.selectByMemberRequest(MEMBER_ID, REQUEST_ID)).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00"))));
    }

    @Test
    void seatsComeFromTheFrozenSnapshotNotFromCoveredTopicCount() {
        // 覆盖 2 个主题、买 1 份、成交快照每份 2 席 ⇒ 每行 order_num=2(不是 2 个主题,也不是 lines 数量)。
        Fixture fixture = fixture(/*snapshotSeatPerUnit*/ 2, /*snapshotQuantity*/ 1);
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.grant(command(
                line(TOPIC_A, "50.00", "30.00"),
                line(TOPIC_B, "50.00", "30.00")));

        assertEquals(2, rows.size(), "覆盖 2 个主题就有 2 行");
        for (CmsRegistration row : rows) {
            assertEquals(2L, row.getOrderNum().longValue(),
                    "席位 = 快照每份席位(2) × 已付份数(1);不能用覆盖主题数凑");
        }
    }

    @Test
    void seatsMultiplyFrozenPerUnitByPaidQuantity() {
        // 3 份 × 每份 2 席(都来自成交快照)= 6。
        Fixture fixture = fixture(2, 3);
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00")));

        assertEquals(6L, rows.get(0).getOrderNum().longValue(),
                "席位 = 快照 2 × 已付 3 份 = 6");
    }

    @Test
    void liveSkuSeatDropDoesNotShrinkAnAlreadyPaidOrder() {
        // 成交时每份 5 席;作者事后把 live SKU 改成 2。已付单仍必须是 5 —— 不能少给。
        Fixture fixture = fixture(5, 1);
        fixture.product.setSeatCount(2);           // live 配置被改小
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00")));

        assertEquals(5L, rows.get(0).getOrderNum().longValue(),
                "已付 5 席不能被事后改小的 live SKU 缩水");
    }

    @Test
    void liveSkuSeatGrowthCannotGrantExtraSeats() {
        // 成交时每份 5 席;作者事后把 live SKU 改成 10。已付单仍是 5,不能多领。
        Fixture fixture = fixture(5, 1);
        fixture.product.setSeatCount(10);          // live 配置被改大
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00")));

        assertEquals(5L, rows.get(0).getOrderNum().longValue(),
                "已付 5 席不能因为 live SKU 调大而多领");
    }

    @Test
    void callerSeatCountMustMatchTheFrozenSnapshotEvenIfLiveSkuAgreesWithCaller() {
        // 调用方拿 live SKU(10) 来凑席位:即使与 live 一致,也与已付快照(5)不符 ⇒ 拒绝且不写不入队。
        Fixture fixture = fixture(5, 1);
        fixture.product.setSeatCount(10);
        List<CmsRegistration> rows = fixture.captureInserts();
        FulfillmentCommand command = command(line(TOPIC_A, "100.00", "60.00"));
        command.seatCount = 10;

        assertThrows(RegistrationRequestConflictException.class, () -> fixture.service.grant(command));
        assertTrue(rows.isEmpty(), "与已付快照不符的席位不能落任何行");
        verify(fixture.posting, never()).enqueueAccrual(anyLong());
        verify(fixture.orderMapper, never()).bindRegistrations(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void activationAlsoUsesTheFrozenSnapshotNotTheLiveSku() {
        // 首次使用型激活:live SKU 改成 10,激活行仍是快照的 5。
        Fixture fixture = fixture(5, 1);
        fixture.product.setSeatCount(10);
        GrantLine frozen = line(TOPIC_A, "100.00", "60.00");
        frozen.validityKind = GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE;
        frozen.validDays = 30;
        when(fixture.orderMapper.selectGrantLine(ORDER_NO, TOPIC_A)).thenReturn(frozen);
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_A, new java.util.Date());

        assertEquals(1, rows.size());
        assertEquals(5L, rows.get(0).getOrderNum().longValue(),
                "激活必须沿用已付快照的席位,不能读 live SKU");
    }

    @Test
    void missingSeatFactInTheSnapshotIsRejectedInsteadOfFallingBackToLiveSku() {
        // 快照没有 seatCount ⇒ 报精确缺口,绝不回落到 live SKU 猜一个值。
        Fixture fixture = fixture(null, 1);
        fixture.product.setSeatCount(8);
        List<CmsRegistration> rows = fixture.captureInserts();

        IllegalStateException gap = assertThrows(IllegalStateException.class,
                () -> fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00"))));
        assertTrue(gap.getMessage().contains(MarketingProductFulfillmentServiceImpl.SHAPE_SNAPSHOT_MISSING),
                "必须报具体缺口(实际:" + gap.getMessage() + ")");
        assertTrue(rows.isEmpty());
    }

    @Test
    void callerCannotAskForMoreQuantityThanThePaidOrder() {
        // 已付 1 份的单被调用方要 3 份 = 外部覆盖已付数量多领 ⇒ 409 且不落任何行。
        Fixture fixture = fixture(1, 1);   // 快照:1 份 × 1 席
        List<CmsRegistration> rows = fixture.captureInserts();
        // 分摊先守住(60 == 实付 60),这样断言失败只可能来自"抬量"守卫,不会与守恒冲突混淆。
        FulfillmentCommand command = command(line(TOPIC_A, "100.00", "60.00"));
        command.quantity = 3;

        assertThrows(RegistrationRequestConflictException.class, () -> fixture.service.grant(command));
        assertTrue(rows.isEmpty(), "抬量的请求不能落任何准入行");
        verify(fixture.posting, never()).enqueueAccrual(anyLong());
    }

    @Test
    void callerCannotInflateSeatsBeyondTheSkuConfiguration() {
        // 每份 1 席 × 1 份，调用方硬塞 5 席 ⇒ 拒绝(席位只能由 SKU/已验证渠道事实决定)。
        Fixture fixture = fixture(1, 1);   // 快照:每份 1 席 × 1 份
        List<CmsRegistration> rows = fixture.captureInserts();
        FulfillmentCommand command = command(line(TOPIC_A, "100.00", "60.00"));
        command.seatCount = 5;

        assertThrows(RegistrationRequestConflictException.class, () -> fixture.service.grant(command));
        assertTrue(rows.isEmpty());
    }

    @Test
    void seatMultiplicationOverflowIsRejectedInsteadOfWrapping() {
        // 50000 席 × 50000 份 = 2.5e9 超出 int:过去会静默溢出成负数/小数写进 order_num。
        Fixture fixture = fixture(50000, 50000);   // 快照自身就是越界组合
        List<CmsRegistration> rows = fixture.captureInserts();

        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00"))));
        assertTrue(rows.isEmpty(), "溢出的席位不能落成负 order_num");
    }

    @Test
    void replayWithALargerShapeIsRejectedButIdenticalReplayStillWorks() {
        Fixture fixture = fixture(1, 1);
        fixture.order.setPrimaryRegistrationId(9001L);
        CmsRegistration primary = new CmsRegistration();
        primary.setId(9001L);
        primary.setParentOrderNo(ORDER_NO);
        primary.setOrderNum(1L);
        when(fixture.registrationMapper.selectCmsRegistrationById(9001L)).thenReturn(primary);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(primary)));

        // 同形重放:幂等返回原行。
        FulfillmentOutcome same = fixture.service.grant(command(line(TOPIC_A, "100.00", "60.00")));
        assertTrue(same.replayed);

        // 同父单换更大 shape:不是重放,是抬量 ⇒ 拒绝(live SKU 调大也不能成为理由)。
        fixture.product.setSeatCount(4);
        FulfillmentCommand inflated = command(line(TOPIC_A, "100.00", "60.00"));
        inflated.seatCount = 4;
        assertThrows(RegistrationRequestConflictException.class, () -> fixture.service.grant(inflated));
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void coveredTopicCountIsNotPeopleCount() {
        // 3 个主题 × 1 份 × 1 席 ⇒ 3 行,但每行 order_num=1;行数不等于人数。
        Fixture fixture = fixture(1, 1);
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.grant(command(
                line(TOPIC_A, "40.00", "20.00"),
                line(TOPIC_B, "40.00", "20.00"),
                line(TOPIC_C, "40.00", "20.00")));

        assertEquals(3, rows.size());
        for (CmsRegistration row : rows) {
            assertEquals(1L, row.getOrderNum().longValue(), "覆盖 3 个主题不代表 3 个人");
        }
    }

    // ---------- 渠道退款完成后的撤销 ----------

    @Test
    void channelRefundRevocationIsClosedWithoutAFactReader() {
        Fixture fixture = fixture();
        assertThrows(IllegalStateException.class,
                () -> fixture.service.consumeVerifiedChannelRefund(refundCommand()),
                "没有退款事实端口时不得凭 reason 撤权");
        verify(fixture.registrationMapper, never()).claimRegistrationRefunded(anyLong(), any(), any());
    }

    @Test
    void channelRefundRevocationFailsLoudWhenTheReversalPortIsMissing() {
        // 端口缺失 ≠ 静默跳过:撤了准入却不冲销收入是查不出来的漏账,必须整笔回滚。
        Fixture fixture = fixture();
        fixture.service = fixture.withRefundReader(new BigDecimal("60.00"), true, true, false);
        ReflectionTestUtils.setField(fixture.service, "channelRevenueReversal", null);
        CmsRegistration row = new CmsRegistration();
        row.setId(9400L);
        row.setParentOrderNo(CHANNEL_ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        when(fixture.registrationMapper.claimRegistrationRefunded(eq(9400L), any(), any())).thenReturn(1);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> fixture.service.consumeVerifiedChannelRefund(refundCommand()));
        assertTrue(missing.getMessage().contains("CHANNEL_REVERSAL_NOT_WIRED"),
                "必须报具体缺口(实际:" + missing.getMessage() + ")");
    }

    @Test
    void unconfirmedChannelRefundDoesNotRevoke() {
        Fixture fixture = fixture();
        fixture.service = fixture.withRefundReader(new BigDecimal("60.00"), false, true, false);
        CmsRegistration row = new CmsRegistration();
        row.setId(9401L);
        row.setParentOrderNo(CHANNEL_ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.consumeVerifiedChannelRefund(refundCommand()),
                "只是申请/未确认到账,不得撤准入");
        verify(fixture.registrationMapper, never()).claimRegistrationRefunded(anyLong(), any(), any());
    }

    @Test
    void partialChannelRefundIsRefusedWithPreciseGap() {
        Fixture fixture = fixture();
        fixture.service = fixture.withRefundReader(new BigDecimal("30.00"), true, false, false);
        CmsRegistration row = new CmsRegistration();
        row.setId(9401L);
        row.setParentOrderNo(CHANNEL_ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        ChannelRefundCommand command = refundCommand();
        command.claimedAmount = new BigDecimal("30.00");
        IllegalStateException gap = assertThrows(IllegalStateException.class,
                () -> fixture.service.consumeVerifiedChannelRefund(command));
        assertTrue(gap.getMessage().contains("PARTIAL_CHANNEL_REFUND_UNSUPPORTED"),
                "部分退款没有既定政策时必须报具体缺口(实际:" + gap.getMessage() + ")");
        verify(fixture.registrationMapper, never()).claimRegistrationRefunded(anyLong(), any(), any());
    }

    @Test
    void confirmedFullChannelRefundRevokesAccessThroughTheExistingCas() {
        Fixture fixture = fixture();
        fixture.service = fixture.withRefundReader(new BigDecimal("60.00"), true, true, false);
        CmsRegistration row = new CmsRegistration();
        row.setId(9400L);
        row.setParentOrderNo(CHANNEL_ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        when(fixture.registrationMapper.claimRegistrationRefunded(eq(9400L), any(), any())).thenReturn(1);

        ChannelRevocationOutcome outcome = fixture.service.consumeVerifiedChannelRefund(refundCommand());

        assertEquals("REVOKED", outcome.reasonCode);
        verify(fixture.registrationMapper).claimRegistrationRefunded(eq(9400L), any(), any());
        verify(fixture.perkLedger).releaseOnRefund(eq(9400L), any());
        // 净额冲销必须真的入队(不是只撤准入):同一渠道退款事实作幂等键。
        verify(fixture.reversal).enqueueChannelReversal(
                eq(CHANNEL_ORDER_NO), eq("OTA_X"), eq("OTA-RF-1"), eq(777L), eq("refund-hash-777"));
    }

    @Test
    void alreadyRevokedChannelRefundIsIdempotent() {
        Fixture fixture = fixture();
        fixture.service = fixture.withRefundReader(new BigDecimal("60.00"), true, true, false);
        CmsRegistration row = new CmsRegistration();
        row.setId(9400L);
        row.setParentOrderNo(CHANNEL_ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        when(fixture.registrationMapper.claimRegistrationRefunded(eq(9400L), any(), any())).thenReturn(0);

        ChannelRevocationOutcome outcome = fixture.service.consumeVerifiedChannelRefund(refundCommand());

        assertTrue(outcome.replayed);
        assertEquals("ALREADY_REVOKED", outcome.reasonCode);
        verify(fixture.reversal, never()).enqueueChannelReversal(any(), any(), any(), anyLong(), any());
    }

    @Test
    void verifiedChannelRefundCannotRevokeAnAlreadyUsedEntitlement() {
        Fixture fixture = fixture();
        fixture.service = fixture.withRefundReader(new BigDecimal("60.00"), true, true, false);
        CmsRegistration row = new CmsRegistration();
        row.setId(9400L);
        row.setParentOrderNo(CHANNEL_ORDER_NO);
        row.setVerificationStatus(1);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));

        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.consumeVerifiedChannelRefund(refundCommand()));
        verify(fixture.registrationMapper, never()).claimRegistrationRefunded(anyLong(), any(), any());
    }

    // ---------- 首次使用型激活 ----------

    @Test
    void activateUsesFrozenLineAndStartsTheWindowAtActivation() {
        Fixture fixture = fixture();
        GrantLine frozen = line(TOPIC_A, "100.00", "60.00");
        frozen.validityKind = GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE;
        frozen.validDays = 30;
        when(fixture.orderMapper.selectGrantLine(ORDER_NO, TOPIC_A)).thenReturn(frozen);
        List<CmsRegistration> rows = fixture.captureInserts();
        java.util.Date activationTime = new java.util.Date();

        FulfillmentOutcome outcome = fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_A, activationTime);

        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("60.00").compareTo(rows.get(0).getPayableAmount()),
                "金额取成交时冻结值,不重新定价");
        assertEquals(com.chengyinhub.common.utils.DateUtils.addDays(activationTime, 30), rows.get(0).getExpiresAt(),
                "首次使用型:有效期从激活时刻起算");
        assertEquals(2, rows.get(0).getRegistrationStatus().intValue());
        assertEquals(2, rows.get(0).getPaymentStatus().intValue());
        assertFalse(outcome.replayed);
        assertEquals(1, outcome.registrationIds.size());
    }

    @Test
    void activateIsIdempotentAndNeverExtendsExpiry() {
        Fixture fixture = fixture();
        CmsRegistration existing = new CmsRegistration();
        existing.setId(9200L);
        existing.setParentOrderNo(ORDER_NO);
        existing.setTopicId(TOPIC_A);          // 同一主题已激活过
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(existing)));

        FulfillmentOutcome outcome = fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_A, new java.util.Date());

        assertTrue(outcome.replayed);
        assertEquals(Collections.singletonList(9200L), outcome.registrationIds);
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
        verify(fixture.orderMapper, never()).selectGrantLine(any(), anyLong());
    }

    @Test
    void activateRejectsTopicNotCoveredByTheOrder() {
        Fixture fixture = fixture();
        when(fixture.orderMapper.selectGrantLine(ORDER_NO, TOPIC_A)).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_A, new java.util.Date()));
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void activateRejectsNonActivationSkus() {
        Fixture fixture = fixture();
        GrantLine fixed = line(TOPIC_A, "100.00", "60.00");   // RELATIVE_DAYS
        when(fixture.orderMapper.selectGrantLine(ORDER_NO, TOPIC_A)).thenReturn(fixed);
        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_A, new java.util.Date()),
                "已在成交时生效的 SKU 不能再用激活入口");
    }

    // ---------- 数量/席位 ----------

    @Test
    void quantityComesFromTheCommandNotFromLineCount() {
        // 「1 个主题 × 2 份」:lines=1、quantity=2,必须把 2 传给渠道事实核对。
        Fixture fixture = fixture();
        fixture.service = fixture.withChannelReader(new BigDecimal("60.00"), false);
        FulfillmentCommand channel = channelCommand();
        channel.quantity = 2;
        channel.seatCount = 4;
        List<CmsRegistration> rows = fixture.captureInserts();

        fixture.service.grant(channel);

        org.mockito.ArgumentCaptor<MarketingChannelFactReader.ChannelFactQuery> query =
                org.mockito.ArgumentCaptor.forClass(MarketingChannelFactReader.ChannelFactQuery.class);
        verify(fixture.channelReader).requireVerified(query.capture());
        assertEquals(2, query.getValue().quantity, "份数不能用 lines.size() 冒充");
        assertEquals(4L, rows.get(0).getOrderNum().longValue(), "席位总数落 order_num(既有席位口径)");
    }

    @Test
    void activatingSecondTopicUnderSamePassGrantsItWithoutReopeningTheWindow() {
        // 城市 pass 覆盖 A/B:A 先激活定死期限;B 之后激活必须**拿到自己的准入行**,
        // 且期限与 A 同一起点(不能把通行证续一轮)。
        Fixture fixture = fixture();
        java.util.Date activationA = new java.util.Date(System.currentTimeMillis() - 3L * 24 * 3600 * 1000);
        CmsRegistration rowA = new CmsRegistration();
        rowA.setId(9300L);
        rowA.setParentOrderNo(ORDER_NO);
        rowA.setTopicId(TOPIC_A);
        rowA.setCreateTime(activationA);
        rowA.setRequestId(REQUEST_ID);                  // 首行持有幂等键
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(rowA)));
        GrantLine frozen = line(TOPIC_B, "100.00", "40.00");
        frozen.validityKind = GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE;
        frozen.validDays = 30;
        when(fixture.orderMapper.selectGrantLine(ORDER_NO, TOPIC_B)).thenReturn(frozen);
        List<CmsRegistration> inserted = fixture.captureInserts();

        FulfillmentOutcome outcome =
                fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_B, new java.util.Date());

        assertEquals(1, inserted.size(), "B 必须真的拿到自己的准入行");
        assertEquals(Long.valueOf(TOPIC_B), inserted.get(0).getTopicId());
        assertEquals(com.chengyinhub.common.utils.DateUtils.addDays(activationA, 30),
                inserted.get(0).getExpiresAt(),
                "期限必须沿用同一 SKU 首次激活时刻,不能按本次 now 重开");
        assertNull(inserted.get(0).getRequestId(), "幂等键已被 A 占用,不能撞 uk_reg_member_request");
        assertEquals(Collections.singletonList(inserted.get(0).getId()), outcome.registrationIds);
    }

    @Test
    void secondActivatedTopicIsAppendedToTheOrderRegistrationIds() {
        // 首次绑定之后(bindRegistrations 只在 primary is null 时生效)必须走原子追加,
        // 否则激活行不进 order.registration_ids_json,退款消费者按 registration 反查会漏单。
        Fixture fixture = fixture();
        fixture.order.setPrimaryRegistrationId(9300L);
        CmsRegistration rowA = new CmsRegistration();
        rowA.setId(9300L);
        rowA.setParentOrderNo(ORDER_NO);
        rowA.setTopicId(TOPIC_A);
        rowA.setCreateTime(new java.util.Date(System.currentTimeMillis() - 3_600_000L));
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(rowA)));
        GrantLine frozen = line(TOPIC_B, "100.00", "40.00");
        frozen.validityKind = GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE;
        frozen.validDays = 30;
        when(fixture.orderMapper.selectGrantLine(ORDER_NO, TOPIC_B)).thenReturn(frozen);
        List<CmsRegistration> inserted = fixture.captureInserts();

        fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_B, new java.util.Date());

        assertEquals(1, inserted.size());
        verify(fixture.orderMapper).appendRegistrationId(
                eq(1L), eq(inserted.get(0).getId()), any(java.util.Date.class));
        verify(fixture.orderMapper, never()).bindRegistrations(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void repeatedActivationOfSameTopicReturnsThatRowOnly() {
        Fixture fixture = fixture();
        CmsRegistration rowA = new CmsRegistration();
        rowA.setId(9300L);
        rowA.setParentOrderNo(ORDER_NO);
        rowA.setTopicId(TOPIC_A);
        CmsRegistration rowB = new CmsRegistration();
        rowB.setId(9301L);
        rowB.setParentOrderNo(ORDER_NO);
        rowB.setTopicId(TOPIC_B);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(rowA, rowB)));

        FulfillmentOutcome outcome =
                fixture.service.activateOnFirstUse(REQUEST_ID, MEMBER_ID, TOPIC_A, new java.util.Date());

        assertTrue(outcome.replayed);
        assertEquals(Collections.singletonList(9300L), outcome.registrationIds,
                "不能因为 parent 下有任何一行就返回别人的行");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    // ---------- 渠道 ----------

    @Test
    void channelGrantIsClosedWhenFactReaderMissing() {
        Fixture fixture = fixture();
        FulfillmentCommand channel = channelCommand();
        IllegalStateException closed = assertThrows(IllegalStateException.class,
                () -> fixture.service.grant(channel));
        assertTrue(closed.getMessage().contains("未启用"), "真实 provider 未配置时渠道成交必须关闭");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void callerReportedChannelAmountMismatchIsRejectedBeforeGrant() {
        Fixture fixture = fixture();
        fixture.service = fixture.withChannelReader(new BigDecimal("70.00"), false);
        List<CmsRegistration> rows = fixture.captureInserts();

        // 调用方报 60,渠道事实为 70:拒绝不一致请求,不能按任一金额静默发权益。
        RegistrationRequestConflictException mismatch = assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.grant(channelCommand()));
        assertTrue(mismatch.getMessage().contains("渠道事实金额与调用方自报不一致"));
        assertTrue(rows.isEmpty());
    }

    @Test
    void channelCallerClaimMismatchIsRejected() {
        Fixture fixture = fixture();
        fixture.service = fixture.withChannelReader(new BigDecimal("70.00"), false);
        FulfillmentCommand channel = channelCommand();
        channel.lines.get(0).payableAmount = new BigDecimal("60.00");
        assertThrows(RegistrationRequestConflictException.class, () -> fixture.service.grant(channel),
                "调用方自报金额与渠道事实不一致必须拒绝(不能靠自报凑分摊)");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void alreadyBoundChannelOrderIsRejected() {
        Fixture fixture = fixture();
        fixture.service = fixture.withChannelReader(new BigDecimal("60.00"), true);
        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.grant(channelCommand()),
                "同一渠道订单只允许一次成交(防跨 voucher 双授)");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    @Test
    void channelReplayReadsBackByExternalOrderNo() {
        Fixture fixture = fixture();
        fixture.service = fixture.withChannelReader(new BigDecimal("60.00"), false);
        CmsRegistration existing = new CmsRegistration();
        existing.setId(9100L);
        existing.setParentOrderNo(CHANNEL_ORDER_NO);
        when(fixture.registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(existing)));

        FulfillmentOutcome outcome = fixture.service.grant(channelCommand());
        assertTrue(outcome.replayed);
        assertEquals(Collections.singletonList(9100L), outcome.registrationIds);
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any());
    }

    // ------------------------------------------------------------------ fixture

    /** 默认 ③ 探索票:只有它当前能被 RefundPolicy 表达,用于验主路径。 */
    private GrantLine line(long topicId, String listAmount, String payableAmount) {
        GrantLine line = new GrantLine();
        line.topicId = topicId;
        line.listAmount = new BigDecimal(listAmount);
        line.payableAmount = new BigDecimal(payableAmount);
        line.validityKind = GrantLine.VALIDITY_RELATIVE_DAYS;
        line.validDays = 30;
        line.exploreCapacity = true;
        return line;
    }

    /** 普通 pass(非 ③):用于验"退款政策表达不了 ⇒ 不可售"。 */
    private GrantLine plainLine(long topicId, String listAmount, String payableAmount) {
        GrantLine line = line(topicId, listAmount, payableAmount);
        line.exploreCapacity = false;
        return line;
    }

    private FulfillmentCommand command(GrantLine... lines) {
        FulfillmentCommand command = new FulfillmentCommand();
        command.requestId = REQUEST_ID;
        command.parentOrderNo = ORDER_NO;
        command.memberId = MEMBER_ID;
        command.productId = PRODUCT_ID;
        command.requestFingerprint = "fp-1";
        command.payment = PaymentFacts.points(6000L);
        command.lines = new ArrayList<>(Arrays.asList(lines));
        return command;
    }

    private ChannelRefundCommand refundCommand() {
        ChannelRefundCommand command = new ChannelRefundCommand();
        command.parentOrderNo = CHANNEL_ORDER_NO;
        command.providerKey = "OTA_X";
        command.channelRefundNo = "OTA-RF-1";
        command.refundFactId = 777L;
        command.refundFactHash = "refund-hash-777";
        command.claimedAmount = new BigDecimal("60.00");
        command.claimedCurrency = "CNY";
        command.requestId = "rf-req-1";
        return command;
    }

    private FulfillmentCommand channelCommand() {
        FulfillmentCommand command = new FulfillmentCommand();
        command.requestId = REQUEST_ID;
        command.parentOrderNo = CHANNEL_ORDER_NO;
        command.memberId = MEMBER_ID;
        command.productId = PRODUCT_ID;
        command.requestFingerprint = "fp-channel";
        PaymentFacts channel = new PaymentFacts();
        channel.mode = PaymentFacts.MODE_CHANNEL;
        channel.sourceProviderKey = "OTA_X";
        channel.externalOrderNo = CHANNEL_ORDER_NO;
        channel.externalFactId = 555L;
        channel.externalFactHash = "hash-555";
        channel.verifiedAmount = new BigDecimal("60.00");
        command.payment = channel;
        command.lines = new ArrayList<>(Collections.singletonList(line(TOPIC_A, "100.00", "60.00")));
        return command;
    }

    /** 成交时的冻结快照:seatCount 是"每份席位数",与 MS 的 Purchase.snapshot 写入口径一致。 */
    private static String frozenSnapshot(Integer seatCountPerUnit, Integer quantity) {
        com.alibaba.fastjson2.JSONObject snapshot = new com.alibaba.fastjson2.JSONObject();
        snapshot.put("productId", PRODUCT_ID);
        snapshot.put("quantity", quantity);
        if (seatCountPerUnit != null) snapshot.put("seatCount", seatCountPerUnit);
        return snapshot.toJSONString();
    }

    private Fixture fixture() {
        return fixture(1, 1);
    }

    /**
     * @param seatCountPerUnit 成交快照里冻结的每份席位(null = 快照缺该字段,用于验 fail-closed)
     * @param quantity         成交快照里冻结的已付份数
     */
    private Fixture fixture(Integer seatCountPerUnit, Integer quantity) {
        Fixture fixture = new Fixture();
        fixture.order = new MarketingProductOrder();
        fixture.order.setId(1L);
        fixture.order.setOrderNo(ORDER_NO);
        fixture.order.setRequestId(REQUEST_ID);
        fixture.order.setMemberId(MEMBER_ID);
        fixture.order.setProductId(PRODUCT_ID);
        fixture.order.setStatus(MarketingProductOrder.STATUS_PAID);
        fixture.order.setTotalCashAmount(new BigDecimal("60.00"));
        fixture.order.setTotalPointsAmount(6000);
        fixture.order.setQuantity(quantity);
        fixture.order.setProductSnapshotJson(frozenSnapshot(seatCountPerUnit, quantity));
        when(fixture.orderMapper.selectByMemberRequest(MEMBER_ID, REQUEST_ID)).thenReturn(fixture.order);
        when(fixture.orderMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(fixture.order);

        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            CmsTopic topic = new CmsTopic();
            topic.setId(id);
            topic.setName("主题" + id);
            // 每个主题不同的费率:用来证明不会把首主题费率套到第二主题。
            topic.setFeePlatformRate(TOPIC_A == id.longValue()
                    ? new BigDecimal("0.1000") : new BigDecimal("0.2000"));
            // 履约窗:② 自玩/pass 的退款截止点就是它的开始时刻(既有 RefundPolicy 口径)。
            // 用 Fixture 里**固定一次**算出的日期,避免每次 Answer 重新 now() 导致期望值抖动。
            topic.setStartDate(fixture.windowStartDate);
            topic.setEndDate(fixture.windowEndDate);
            return topic;
        });
        when(fixture.productRoleService.isPlatformOwned(anyLong())).thenReturn(true);
        when(fixture.registrationMapper.selectCmsRegistrationById(anyLong())).thenAnswer(invocation -> {
            CmsRegistration row = new CmsRegistration();
            row.setId(invocation.getArgument(0));
            row.setRegistrationNo("M-TEST-" + invocation.getArgument(0));
            row.setParentOrderNo(ORDER_NO);
            return row;
        });
        when(fixture.orderMapper.bindRegistrations(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

        fixture.service = new MarketingProductFulfillmentServiceImpl();
        ReflectionTestUtils.setField(fixture.service, "cmsRegistrationMapper", fixture.registrationMapper);
        ReflectionTestUtils.setField(fixture.service, "cmsTopicMapper", fixture.topicMapper);
        ReflectionTestUtils.setField(fixture.service, "productOrderMapper", fixture.orderMapper);
        ReflectionTestUtils.setField(fixture.service, "productRoleService", fixture.productRoleService);
        ReflectionTestUtils.setField(fixture.service, "perkLedgerService", fixture.perkLedger);
        ReflectionTestUtils.setField(fixture.service, "pointsValuePolicy",
                com.chengyinhub.business.service.support.PointsValuePolicy.codeDefaults());
        // M5 入队是强依赖:测试里给桩,断言"每行都入队、赠礼/重放不重复入队"。
        ReflectionTestUtils.setField(fixture.service, "marketingRevenuePostingService", fixture.posting);
        return fixture;
    }

    private static final class Fixture {
        final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        final MarketingProductOrderMapper orderMapper = mock(MarketingProductOrderMapper.class);
        final IProductRoleService productRoleService = mock(IProductRoleService.class);
        final IPerkLedgerService perkLedger = mock(IPerkLedgerService.class);
        /** live SKU 配置;只有负例才设置,用来证明冻结快照优先于它。 */
        final com.chengyinhub.business.domain.MarketingProduct product =
                new com.chengyinhub.business.domain.MarketingProduct();
        final com.chengyinhub.business.service.IMarketingRevenuePostingService posting =
                mock(com.chengyinhub.business.service.IMarketingRevenuePostingService.class);
        /** 固定一次的履约窗(② 的退款截止点 = 窗开始)。 */
        final java.util.Date windowStartDate = java.util.Date.from(
                java.time.LocalDate.now().plusDays(7).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        final java.util.Date windowEndDate = java.util.Date.from(
                java.time.LocalDate.now().plusDays(13).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        MarketingProductOrder order;
        MarketingProductFulfillmentServiceImpl service;

        List<CmsRegistration> captureInserts() {
            List<CmsRegistration> captured = new ArrayList<>();
            when(registrationMapper.insertCmsRegistration(any())).thenAnswer(invocation -> {
                CmsRegistration row = invocation.getArgument(0);
                row.setId(9000L + captured.size());
                captured.add(row);
                return 1;
            });
            return captured;
        }

        MarketingChannelFactReader channelReader;
        MarketingChannelRefundFactReader refundReader;
        final IMarketingChannelRevenueReversal reversal =
                mock(IMarketingChannelRevenueReversal.class);

        MarketingProductFulfillmentServiceImpl withRefundReader(BigDecimal amount, boolean confirmed,
                                                               boolean fullRefund, boolean consumed) {
            refundReader = mock(MarketingChannelRefundFactReader.class);
            when(refundReader.requireVerifiedRefund(any())).thenReturn(
                    new MarketingChannelRefundFactReader.ChannelRefundFact(
                            777L, "refund-hash-777", CHANNEL_ORDER_NO, "OTA_X", "OTA-RF-1",
                            amount, "CNY", confirmed, fullRefund, consumed));
            ReflectionTestUtils.setField(service, "channelRefundFactReader", refundReader);
            // 冲销端口默认给桩:happy path 必须能真的入队;单测另有"端口缺失 ⇒ fail-loud"用例。
            ReflectionTestUtils.setField(service, "channelRevenueReversal", reversal);
            return service;
        }

        MarketingProductFulfillmentServiceImpl withChannelReader(BigDecimal factAmount, boolean bound) {
            channelReader = mock(MarketingChannelFactReader.class);
            when(channelReader.requireVerified(any())).thenReturn(new MarketingChannelFactReader.ChannelFact(
                    CHANNEL_ORDER_NO, factAmount, "CNY", "ota-tx-1", bound));
            ReflectionTestUtils.setField(service, "channelFactReader", channelReader);
            return service;
        }
    }
}
