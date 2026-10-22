package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.ExploreBundleOrder;
import com.chengyinhub.business.domain.ExploreBundleReservation;
import com.chengyinhub.business.domain.ExploreBundleUnit;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ExploreBundleMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.service.support.FreeExploreBundlePolicy;
import com.chengyinhub.business.service.support.FreeExploreBundleSwitches;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.web.controller.api.ApiFreeExploreBundleController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** DOC2 F3 Phase 1 套票:开关默认关零写入、产品规则、读接口无锁、归属校验。真库部分见 FreeExploreBundleLedgerMySqlIT。 */
class FreeExploreBundleServiceTest {
    private static final long HOUR = 3600_000L;
    private final Date now = new Date(1792000000000L);

    private ExploreBundleMapper bundleMapper;
    private CmsRegistrationMapper registrationMapper;
    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private MerchantChapterOfferMapper offerMapper;
    private SettlementRuleMapper rules;

    @BeforeEach
    void setUp() {
        bundleMapper = mock(ExploreBundleMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        rules = mock(SettlementRuleMapper.class);
    }

    private FreeExploreBundleServiceImpl service(boolean ledger, boolean sale, boolean reservation) {
        FreeExploreBundleServiceImpl impl = new FreeExploreBundleServiceImpl();
        ReflectionTestUtils.setField(impl, "bundleMapper", bundleMapper);
        ReflectionTestUtils.setField(impl, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(impl, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(impl, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(impl, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(impl, "settlementRuleMapper", rules);
        ReflectionTestUtils.setField(impl, "switches", new FreeExploreBundleSwitches(ledger, sale, reservation));
        impl.clock = () -> now;
        return impl;
    }

    // ---------------------------------------------------------------- 开关默认关:既有路径零变化

    @Test
    void switchesDefaultOffAndEveryHookIsANoOp() {
        FreeExploreBundleSwitches defaults = new FreeExploreBundleSwitches(false, false, false);
        assertFalse(defaults.ledgerEnabled());
        assertFalse(defaults.saleActive());
        assertFalse(defaults.reservationActive());
        assertFalse(new FreeExploreBundleSwitches(false, true, true).saleActive(), "sale 必须依赖 ledger");
        assertFalse(new FreeExploreBundleSwitches(false, true, true).reservationActive());

        FreeExploreBundleServiceImpl off = service(false, false, false);
        assertNull(off.freezeOnOrderCreated(registration(1L, 11L), topic(), activity()));
        off.onChapterRedeemed(1L, 701L, 9001L, 5001L);
        off.onRedemptionReversed(5001L);
        off.onRegistrationRefunded(1L);
        verifyNoInteractions(bundleMapper, chapterMapper, offerMapper, rules, registrationMapper);
        assertEquals("BUNDLE_LEDGER_OFF", assertThrows(FreeExploreBundleException.class,
                () -> off.orderView(11L, 1L)).getBusinessCode());
        assertEquals("BUNDLE_RESERVATION_OFF", assertThrows(FreeExploreBundleException.class,
                () -> off.reserve(11L, 1L, 2L, 3L, new Date(now.getTime() + 48 * HOUR), "k")).getBusinessCode());
    }

    @Test
    void ledgerOnButSaleOffFreezesNothing() {
        assertNull(service(true, false, true).freezeOnOrderCreated(registration(1L, 11L), topic(), activity()));
        verifyNoInteractions(bundleMapper, chapterMapper, offerMapper);
    }

    @Test
    void nonFreeExploreTopicIsNeverBundled() {
        CmsTopic orienteering = topic();
        orienteering.setProductType(ProductType.ORIENTEERING);
        assertNull(service(true, true, true).freezeOnOrderCreated(registration(1L, 11L), orienteering, activity()));
        verifyNoInteractions(bundleMapper);
    }

    // ---------------------------------------------------------------- 建单冻结

    @Test
    void freezeRecordsFiveVersionsAndReplaysExistingOrderOnce() {
        when(chapterMapper.selectRequiredByTopic(70L)).thenReturn(Arrays.asList(chapter(701L), chapter(702L)));
        when(offerMapper.selectByTopic(70L)).thenReturn(Collections.singletonList(offer(801L, 701L)));
        FreeExploreBundleServiceImpl impl = service(true, true, false);
        ExploreBundleOrder order = impl.freezeOnOrderCreated(registration(1L, 11L), topic(), activity());
        assertEquals(64, order.getProductVersion().length());
        assertEquals(64, order.getQuotaVersion().length());
        assertEquals(64, order.getContractVersion().length());
        assertEquals(FreeExploreBundlePolicy.POLICY_VERSION, order.getPolicyVersion());
        assertEquals("rule:none", order.getSettlementRuleVersion());
        assertEquals("bundle-order:1", order.getIdempotencyKey());
        assertTrue(order.getSnapshotJson().contains("\"perHeadFee\":\"15\""), order.getSnapshotJson());
        verify(bundleMapper, times(1)).insertOrder(any());
        verify(bundleMapper, times(2)).insertGrant(any());
        verify(bundleMapper, times(2)).insertUnit(any());

        when(bundleMapper.selectOrderByRegistration(1L)).thenReturn(order);
        assertEquals(order, impl.freezeOnOrderCreated(registration(1L, 11L), topic(), activity()));
        verify(bundleMapper, times(1)).insertOrder(any());
    }

    @Test
    void freezeRefusesBundleWithoutValidity() {
        when(chapterMapper.selectRequiredByTopic(70L)).thenReturn(Collections.singletonList(chapter(701L)));
        CmsActivity noEnd = activity();
        noEnd.setEndDate(null);
        assertEquals("BUNDLE_VALIDITY_MISSING", assertThrows(FreeExploreBundleException.class,
                () -> service(true, true, false).freezeOnOrderCreated(registration(1L, 11L), topic(), noEnd))
                .getBusinessCode());
        verify(bundleMapper, never()).insertOrder(any());
    }

    @Test
    void orderSnapshotIsInsertOnlyAndBalanceNeverReadsSharedQuota() throws Exception {
        String xml = new String(Files.readAllBytes(Path.of(
                "../chengyinhub-system/src/main/resources/mapper/business/ExploreBundleMapper.xml")), StandardCharsets.UTF_8);
        assertFalse(xml.matches("(?s).*update\\s+explore_bundle_order.*"), "快照不得有 update");
        assertFalse(xml.matches("(?s).*delete\\s+from\\s+explore_bundle.*"), "账本不得有 delete");
        assertFalse(xml.contains("quota_total") || xml.contains("quota_used") || xml.contains("merchant_chapter_offer"),
                "持有人余额不得读共享名额");
    }

    // ---------------------------------------------------------------- 核销

    @Test
    void redemptionPrefersReservationAtThisStoreAndRejectsWhenReservedElsewhere() {
        when(bundleMapper.selectOrderByRegistration(1L)).thenReturn(order(1L, 11L));
        ExploreBundleUnit reserved = unit(31L, "RESERVED", 41L);
        when(bundleMapper.selectUnitsForUpdate(1L, 11L, 701L)).thenReturn(Collections.singletonList(reserved));
        when(bundleMapper.selectReservationByIdForUpdate(41L)).thenReturn(reservation(41L, 31L, 9002L, "BOOKED"));
        FreeExploreBundleServiceImpl impl = service(true, false, false);
        assertEquals("BUNDLE_RESERVED_ELSEWHERE", assertThrows(FreeExploreBundleException.class,
                () -> impl.onChapterRedeemed(1L, 701L, 9001L, 5001L)).getBusinessCode());
        verify(bundleMapper, never()).casUnitStatus(anyLong(), any(), anyString(), anyString(), any(), any(), any());

        when(bundleMapper.casUnitStatus(31L, 0, "RESERVED", "REDEEMED", 5001L, null, "REDEEMED")).thenReturn(1);
        when(bundleMapper.casReservationStatus(41L, 0, "BOOKED", "FULFILLED")).thenReturn(1);
        impl.onChapterRedeemed(1L, 701L, 9002L, 5001L);
        verify(bundleMapper).casReservationStatus(41L, 0, "BOOKED", "FULFILLED");
    }

    @Test
    void expiredUnitCannotBeRedeemed() {
        when(bundleMapper.selectOrderByRegistration(1L)).thenReturn(order(1L, 11L));
        ExploreBundleUnit unit = unit(31L, "AVAILABLE", null);
        unit.setValidTo(new Date(now.getTime() - HOUR));
        when(bundleMapper.selectUnitsForUpdate(1L, 11L, 701L)).thenReturn(Collections.singletonList(unit));
        assertEquals("BUNDLE_EXPIRED", assertThrows(FreeExploreBundleException.class,
                () -> service(true, false, false).onChapterRedeemed(1L, 701L, 9001L, 5001L)).getBusinessCode());
    }

    @Test
    void reversalOnRefundedTicketDoesNotResurrectUnit() {
        ExploreBundleUnit redeemed = unit(31L, "REDEEMED", null);
        redeemed.setRedemptionId(5001L);
        when(bundleMapper.selectUnitByRedemptionForUpdate(5001L)).thenReturn(redeemed);
        CmsRegistration refunded = registration(1L, 11L);
        refunded.setPaymentStatus(4);
        when(registrationMapper.selectCmsRegistrationById(1L)).thenReturn(refunded);
        when(bundleMapper.casUnitStatus(31L, 0, "REDEEMED", "REFUNDED", null, null, "REDEMPTION_REVERSED")).thenReturn(1);
        service(true, false, false).onRedemptionReversed(5001L);
        verify(bundleMapper).casUnitStatus(31L, 0, "REDEEMED", "REFUNDED", null, null, "REDEMPTION_REVERSED");
    }

    // ---------------------------------------------------------------- 预约规则

    @Test
    void cancelAtExactlyTwelveHoursIsFreeAndJustUnderIsLate() {
        assertTrue(FreeExploreBundlePolicy.freeCancel(new Date(now.getTime() + 12 * HOUR), now));
        assertFalse(FreeExploreBundlePolicy.freeCancel(new Date(now.getTime() + 12 * HOUR - 1), now));

        ExploreBundleReservation late = reservation(41L, 31L, 9001L, "BOOKED");
        late.setStartAt(new Date(now.getTime() + 2 * HOUR));
        when(bundleMapper.selectReservationByIdForUpdate(41L)).thenReturn(late);
        when(bundleMapper.selectUnitByIdForUpdate(31L)).thenReturn(unit(31L, "RESERVED", 41L));
        when(bundleMapper.casReservationStatus(41L, 0, "BOOKED", "LATE_CANCELLED")).thenReturn(1);
        when(bundleMapper.casUnitStatus(31L, 0, "RESERVED", "FORFEITED", null, null, "LATE_CANCELLED")).thenReturn(1);
        service(true, false, true).cancelReservation(11L, 41L, false);
        verify(bundleMapper).casUnitStatus(31L, 0, "RESERVED", "FORFEITED", null, null, "LATE_CANCELLED");
        verify(bundleMapper, never()).insertWaiverIgnore(anyLong(), anyString(), anyLong());
    }

    @Test
    void secondWaiverInSameMonthIsRefusedWithoutAnyStateChange() {
        ExploreBundleReservation late = reservation(41L, 31L, 9001L, "BOOKED");
        late.setStartAt(new Date(now.getTime() + 2 * HOUR));
        when(bundleMapper.selectReservationByIdForUpdate(41L)).thenReturn(late);
        when(bundleMapper.selectUnitByIdForUpdate(31L)).thenReturn(unit(31L, "RESERVED", 41L));
        when(bundleMapper.insertWaiverIgnore(eq(11L), eq(FreeExploreBundlePolicy.waiverMonth(now)), eq(41L))).thenReturn(0);
        assertEquals("BUNDLE_WAIVER_USED", assertThrows(FreeExploreBundleException.class,
                () -> service(true, false, true).cancelReservation(11L, 41L, true)).getBusinessCode());
        verify(bundleMapper, never()).casReservationStatus(anyLong(), any(), anyString(), anyString());
        verify(bundleMapper, never()).casUnitStatus(anyLong(), any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void waiverMonthFollowsShanghaiCalendar() {
        // 2026-10-31 16:30 UTC = 2026-11-01 00:30 Asia/Shanghai
        assertEquals("2026-11", FreeExploreBundlePolicy.waiverMonth(new Date(1793464200000L)));
    }

    @Test
    void cancelOfOthersReservationLooksLikeNotFound() {
        when(bundleMapper.selectReservationByIdForUpdate(41L)).thenReturn(reservation(41L, 31L, 9001L, "BOOKED"));
        assertEquals("BUNDLE_NOT_FOUND", assertThrows(FreeExploreBundleException.class,
                () -> service(true, false, true).cancelReservation(99L, 41L, false)).getBusinessCode());
    }

    @Test
    void noShowOnlyByOwningMerchantAfterStartAndForfeitsWithoutRedemption() {
        ExploreBundleReservation started = reservation(41L, 31L, 9001L, "BOOKED");
        started.setStartAt(new Date(now.getTime() - HOUR));
        when(bundleMapper.selectReservationByIdForUpdate(41L)).thenReturn(started);
        when(bundleMapper.selectUnitByIdForUpdate(31L)).thenReturn(unit(31L, "RESERVED", 41L));
        when(bundleMapper.casReservationStatus(41L, 0, "BOOKED", "NO_SHOW")).thenReturn(1);
        when(bundleMapper.casUnitStatus(31L, 0, "RESERVED", "FORFEITED", null, null, "NO_SHOW")).thenReturn(1);
        FreeExploreBundleServiceImpl impl = service(true, false, true);
        assertEquals("BUNDLE_NOT_FOUND", assertThrows(FreeExploreBundleException.class,
                () -> impl.markNoShow(9002L, 41L)).getBusinessCode(), "别家商家不能记爽约");
        impl.markNoShow(9001L, 41L);
        verify(bundleMapper).casUnitStatus(eq(31L), eq(0), eq("RESERVED"), eq("FORFEITED"), isNull(), isNull(), eq("NO_SHOW"));
    }

    // ---------------------------------------------------------------- 商家退出

    @Test
    void merchantExitNeverSwitchesSilentlyAndSwapKeepsValidity() {
        ExploreBundleUnit reserved = unit(31L, "RESERVED", 41L);
        when(bundleMapper.selectUnitByIdForUpdate(31L)).thenReturn(reserved);
        when(bundleMapper.selectOrderByRegistration(1L)).thenReturn(order(1L, 11L));
        when(bundleMapper.selectReservationByIdForUpdate(41L)).thenReturn(reservation(41L, 31L, 9001L, "BOOKED"));
        MerchantChapterOffer stillOpen = offer(801L, 701L);
        when(offerMapper.selectById(801L)).thenReturn(stillOpen);
        FreeExploreBundleServiceImpl impl = service(true, false, true);
        assertEquals("BUNDLE_MERCHANT_NOT_EXITED", assertThrows(FreeExploreBundleException.class,
                () -> impl.decideMerchantExit(11L, 31L, "REFUND_UNFULFILLED", null, "exit-1")).getBusinessCode());

        stillOpen.setStatus(0);   // 门店退出
        MerchantChapterOffer shortTarget = offer(802L, 701L);
        shortTarget.setValidEnd(new Date(reserved.getValidTo().getTime() - HOUR));
        when(offerMapper.selectById(802L)).thenReturn(shortTarget);
        assertEquals("BUNDLE_SWAP_SHORTENS_VALIDITY", assertThrows(FreeExploreBundleException.class,
                () -> impl.decideMerchantExit(11L, 31L, "SWAP_CONSENTED", 802L, "exit-2")).getBusinessCode());
        verify(bundleMapper, never()).insertExitCase(any());

        when(bundleMapper.casReservationStatus(41L, 0, "BOOKED", "MERCHANT_EXITED")).thenReturn(1);
        when(bundleMapper.casUnitStatus(31L, 0, "RESERVED", "REFUND_PENDING", null, null, "MERCHANT_EXIT")).thenReturn(1);
        assertEquals("REFUND_UNFULFILLED", impl.decideMerchantExit(11L, 31L, "REFUND_UNFULFILLED", null, "exit-3").getDecision());
        verify(bundleMapper).casUnitStatus(31L, 0, "RESERVED", "REFUND_PENDING", null, null, "MERCHANT_EXIT");
    }

    // ---------------------------------------------------------------- 退款报价

    @Test
    void refundQuoteFollowsTheThreeRules() {
        List<ExploreBundleUnit> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) units.add(valued("AVAILABLE", "20.00"));
        FreeExploreBundlePolicy.RefundQuote unused = FreeExploreBundlePolicy.refundQuote(new BigDecimal("100.00"), units);
        assertEquals(FreeExploreBundlePolicy.ROUTE_ORIGINAL_METHOD, unused.getRoute());
        assertEquals(new BigDecimal("100.00"), unused.getAmount());

        units.get(0).setStatus("REDEEMED");
        units.get(1).setStatus("FORFEITED");
        units.get(2).setStatus("REDEEMED");
        FreeExploreBundlePolicy.RefundQuote partial = FreeExploreBundlePolicy.refundQuote(new BigDecimal("100.00"), units);
        assertEquals(FreeExploreBundlePolicy.ROUTE_PLATFORM_BALANCE, partial.getRoute(), "剩余 40% > 20%");
        assertEquals(new BigDecimal("40.00"), partial.getAmount());

        units.get(3).setStatus("REDEEMED");
        FreeExploreBundlePolicy.RefundQuote exactlyTwenty = FreeExploreBundlePolicy.refundQuote(new BigDecimal("100.00"), units);
        assertEquals(FreeExploreBundlePolicy.ROUTE_NOT_REFUNDABLE, exactlyTwenty.getRoute(), "恰好 20% 不走常规退款");
        assertTrue(exactlyTwenty.isAfterSalesForMerchantBreach());
        assertEquals(BigDecimal.ZERO, exactlyTwenty.getAmount());
    }

    @Test
    void unitValuesSumToPaidAmount() {
        List<BigDecimal> values = FreeExploreBundlePolicy.splitValue(new BigDecimal("100.00"), 3);
        assertEquals(Arrays.asList(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34")), values);
    }

    // ---------------------------------------------------------------- 读接口

    @Test
    void browsingCatalogAcrossCategoriesTakesNoLockAndWritesNothing() {
        CmsTopic topic = topic();
        topic.setDelFlag(0);
        topic.setLifecycle(directSaleLifecycle());
        when(topicMapper.selectCmsTopicById(70L)).thenReturn(topic);
        CmsTopic other = topic();
        other.setId(71L);
        other.setDelFlag(0);
        other.setLifecycle(directSaleLifecycle());
        when(topicMapper.selectCmsTopicById(71L)).thenReturn(other);
        when(chapterMapper.selectRequiredByTopic(anyLong())).thenReturn(Collections.singletonList(chapter(701L)));
        when(offerMapper.selectByTopic(anyLong())).thenReturn(Collections.singletonList(offer(801L, 701L)));
        FreeExploreBundleServiceImpl impl = service(true, true, true);
        Map<String, Object> first = impl.catalog(70L);
        impl.catalog(71L);
        impl.catalog(70L);
        assertEquals(first.toString(), impl.catalog(70L).toString());
        verifyNoInteractions(bundleMapper, registrationMapper);
        verify(offerMapper, times(4)).selectByTopic(anyLong());
        verifyNoMoreInteractions(offerMapper);
        assertFalse(first.toString().contains("perHeadFee"), "目录不外露商家采购价");
    }

    @Test
    void orderViewIsOwnerScoped() {
        when(registrationMapper.selectCmsRegistrationById(1L)).thenReturn(registration(1L, 11L));
        assertEquals("BUNDLE_NOT_FOUND", assertThrows(FreeExploreBundleException.class,
                () -> service(true, false, false).orderView(12L, 1L)).getBusinessCode());
    }

    @Test
    void miniProgramAndAppReadTheSameOrderPayload() {
        when(registrationMapper.selectCmsRegistrationById(1L)).thenReturn(registration(1L, 11L));
        when(bundleMapper.selectOrderByRegistration(1L)).thenReturn(order(1L, 11L));
        when(bundleMapper.selectUnitsByRegistration(1L)).thenReturn(Collections.singletonList(unit(31L, "AVAILABLE", null)));
        FreeExploreBundleServiceImpl impl = service(true, false, false);
        assertEquals(impl.orderView(11L, 1L).toString(), impl.orderView(11L, 1L).toString());
        // 回读接口不接收任何客户端/渠道参数,结构不可能按端分叉
        for (Method method : ApiFreeExploreBundleController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                assertFalse(HttpServletRequest.class.isAssignableFrom(parameter.getType()), method.getName());
            }
        }
    }

    @Test
    void unknownCreateReadsBackByRequestId() {
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> receipt = service(true, false, false).orderReceiptByRequestId(11L, "req-1");
        assertEquals("NOT_FOUND", receipt.get("receipt"));
    }

    // ---------------------------------------------------------------- fixtures

    private int directSaleLifecycle() {
        for (TopicLifecycleState state : TopicLifecycleState.values()) {
            if (state.isDirectSaleOpen()) return state.getCode();
        }
        throw new IllegalStateException("no direct-sale lifecycle");
    }

    private CmsRegistration registration(Long id, Long memberId) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(memberId);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setPayableAmount(new BigDecimal("99.00"));
        return r;
    }

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(70L);
        t.setName("套票");
        t.setProductType(ProductType.FREE_EXPLORE);
        return t;
    }

    private CmsActivity activity() {
        CmsActivity a = new CmsActivity();
        a.setId(60L);
        a.setEndDate(new Date(now.getTime() + 720 * HOUR));
        return a;
    }

    private static CmsTopicChapter chapter(Long id) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setName("章" + id);
        c.setRequired(1);
        return c;
    }

    private static MerchantChapterOffer offer(Long id, Long chapterId) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(id);
        o.setChapterId(chapterId);
        o.setMerchantId(9001L);
        o.setStatus(1);
        o.setPerHeadFee(new BigDecimal("15.00"));
        return o;
    }

    private ExploreBundleOrder order(Long registrationId, Long holder) {
        ExploreBundleOrder o = new ExploreBundleOrder();
        o.setId(1L);
        o.setRegistrationId(registrationId);
        o.setHolderMemberId(holder);
        o.setTopicId(70L);
        o.setPaidAmount(new BigDecimal("99.00"));
        return o;
    }

    private ExploreBundleUnit unit(Long id, String status, Long reservationId) {
        ExploreBundleUnit u = new ExploreBundleUnit();
        u.setId(id);
        u.setRegistrationId(1L);
        u.setHolderMemberId(11L);
        u.setChapterId(701L);
        u.setStatus(status);
        u.setReservationId(reservationId);
        u.setVersion(0);
        u.setValidFrom(new Date(now.getTime() - HOUR));
        u.setValidTo(new Date(now.getTime() + 720 * HOUR));
        return u;
    }

    private static ExploreBundleUnit valued(String status, String value) {
        ExploreBundleUnit u = new ExploreBundleUnit();
        u.setStatus(status);
        u.setUnitValue(new BigDecimal(value));
        return u;
    }

    private ExploreBundleReservation reservation(Long id, Long unitId, Long merchantId, String status) {
        ExploreBundleReservation r = new ExploreBundleReservation();
        r.setId(id);
        r.setUnitId(unitId);
        r.setRegistrationId(1L);
        r.setHolderMemberId(11L);
        r.setChapterId(701L);
        r.setOfferId(801L);
        r.setMerchantId(merchantId);
        r.setStatus(status);
        r.setVersion(0);
        r.setStartAt(new Date(now.getTime() + 48 * HOUR));
        return r;
    }
}
