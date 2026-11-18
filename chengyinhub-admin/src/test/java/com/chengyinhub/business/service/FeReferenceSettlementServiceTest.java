package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.FeReferenceSettlement;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.vo.FeReferenceSettlementView;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.FeReferenceSettlementMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.FeReferenceSettlementServiceImpl;
import com.chengyinhub.business.service.support.ClubPrincipalPayee;
import com.chengyinhub.business.service.support.SessionPayeeResolver;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F11 自由探索店铺参考结算(FE-G16=B 计酬只作参考、FE-G22 店铺线下结款、MC-011=B 空金额 ¥0.00)。
 * 场次结算侧(不划款、主理人份额不扣)见 {@code CmsActivityServiceImplFeSettlementTest}。
 */
class FeReferenceSettlementServiceTest {

    private static final long TOPIC = 70L, ACTIVITY = 71L, CHAPTER = 72L, OFFER = 73L, PERK = 74L;
    private static final long REG = 75L, REDEMPTION = 76L, SHOP = 801L, ORGANIZER = 802L, CLUB = 803L, PRINCIPAL = 804L;

    private FeReferenceSettlementMapper mapper;
    private CmsTopicMapper topics;
    private CmsActivityMapper activities;
    private CmsRegistrationMapper registrations;
    private CoopPerkMapper perks;
    private ClubMapper clubs;
    private MmsMerchantMapper merchants;
    private FeReferenceSettlementServiceImpl service;
    private CmsTopic topic;
    private CmsActivity activity;

    @BeforeEach
    void setUp() {
        mapper = mock(FeReferenceSettlementMapper.class);
        topics = mock(CmsTopicMapper.class);
        activities = mock(CmsActivityMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        perks = mock(CoopPerkMapper.class);
        clubs = mock(ClubMapper.class);
        merchants = mock(MmsMerchantMapper.class);
        when(mapper.insertReference(any())).thenReturn(1);

        ClubPrincipalPayee principal = new ClubPrincipalPayee();
        ReflectionTestUtils.setField(principal, "clubMapper", clubs);
        SessionPayeeResolver resolver = new SessionPayeeResolver();
        ReflectionTestUtils.setField(resolver, "clubPrincipalPayee", principal);
        ReflectionTestUtils.setField(resolver, "topicSaleSnapshotMapper", mock(TopicSaleSnapshotMapper.class));
        ReflectionTestUtils.setField(resolver, "clubMapper", clubs);
        ReflectionTestUtils.setField(resolver, "mmsMerchantMapper", merchants);
        ReflectionTestUtils.setField(resolver, "umsMemberMapper", mock(UmsMemberMapper.class));

        service = new FeReferenceSettlementServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "payeeResolver", resolver);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "chapterMapper", mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(service, "coopPerkMapper", perks);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        topic.setMemberId(ORGANIZER);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setMemberId(ORGANIZER);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REG);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(registration);
    }

    private static Redemption redemption() {
        Redemption r = new Redemption();
        r.setId(REDEMPTION);
        r.setRegistrationId(REG);
        r.setTopicId(TOPIC);
        r.setChapterId(CHAPTER);
        r.setMerchantId(SHOP);
        r.setOfferId(OFFER);
        r.setStatus("ACTIVE");
        return r;
    }

    private static MerchantChapterOffer perkOffer() {
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setId(OFFER);
        offer.setTopicId(TOPIC);
        offer.setChapterId(CHAPTER);
        offer.setMerchantId(SHOP);
        offer.setPerkId(PERK);
        offer.setTermsMode("PERK");
        offer.setSupplierKind(MerchantChapterOffer.SUPPLIER_MERCHANT);
        return offer;
    }

    private FeReferenceSettlement inserted() {
        ArgumentCaptor<FeReferenceSettlement> captor = ArgumentCaptor.forClass(FeReferenceSettlement.class);
        verify(mapper).insertReference(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ PERK

    @Test
    void perkRedemptionWritesOpenReferenceRowPaidBySessionPayee() {
        CoopPerk perk = new CoopPerk();
        perk.setUnitCost(new BigDecimal("12.345"));
        when(perks.selectById(PERK)).thenReturn(perk);

        service.recordPerkRedemption(redemption(), perkOffer());

        FeReferenceSettlement row = inserted();
        assertEquals(FeReferenceSettlement.BASIS_PERK_REDEEMED, row.getBasis());
        assertEquals(FeReferenceSettlement.STATUS_OPEN, row.getStatus());
        assertEquals(MerchantChapterOffer.SUPPLIER_MERCHANT, row.getSupplierKind());
        assertEquals(1, row.getQuantity());
        assertEquals(new BigDecimal("12.35"), row.getUnitAmount(), "单价按分四舍五入");
        assertEquals(new BigDecimal("12.35"), row.getAmount());
        assertEquals(Long.valueOf(ACTIVITY), row.getActivityId());
        assertEquals(Long.valueOf(SHOP), row.getMerchantId());
        assertEquals(FeReferenceSettlement.PAYER_MEMBER, row.getPayerKind());
        assertEquals(Long.valueOf(ORGANIZER), row.getPayerId(), "付款方 = 场次收款人");
        assertEquals(Long.valueOf(REDEMPTION), row.getRedemptionId());
        assertEquals(Long.valueOf(REG), row.getRegistrationId());
        assertEquals("fe:perk:" + REDEMPTION, row.getIdempotencyKey());
    }

    @Test
    void perkWithoutReferenceValueRecordsZeroAsAgreed() {
        service.recordPerkRedemption(redemption(), perkOffer());

        FeReferenceSettlement row = inserted();
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getAmount()));
        assertEquals(FeReferenceSettlement.STATUS_OPEN, row.getStatus());
    }

    @Test
    void clubSessionPayerIsClubAndCurrentPrincipal() {
        activity.setClubId(CLUB);
        topic.setClubId(CLUB);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(PRINCIPAL);
        when(clubs.selectClubById(CLUB)).thenReturn(club);

        service.recordPerkRedemption(redemption(), perkOffer());

        FeReferenceSettlement row = inserted();
        assertEquals(FeReferenceSettlement.PAYER_CLUB, row.getPayerKind());
        assertEquals(Long.valueOf(CLUB), row.getPayerId());
    }

    @Test
    void authorSelfSuppliedRowIsZeroAndAlreadySettledOffline() {
        MerchantChapterOffer offer = perkOffer();
        offer.setSupplierKind(MerchantChapterOffer.SUPPLIER_AUTHOR);
        offer.setSupplierMemberId(ORGANIZER);
        offer.setMerchantId(ORGANIZER);
        Redemption r = redemption();
        r.setMerchantId(ORGANIZER);
        CoopPerk perk = new CoopPerk();
        perk.setUnitCost(new BigDecimal("30.00"));
        when(perks.selectById(PERK)).thenReturn(perk);

        service.recordPerkRedemption(r, offer);

        FeReferenceSettlement row = inserted();
        assertEquals(MerchantChapterOffer.SUPPLIER_AUTHOR, row.getSupplierKind());
        assertEquals(FeReferenceSettlement.STATUS_SETTLED_OFFLINE, row.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getAmount()), "作者自己给自己供货,没有要结的钱");
        assertEquals("作者自营", row.getNote());
        assertNotNull(row.getSettledAt());
    }

    @Test
    void idempotencyKeyPreventsDuplicateRows() {
        when(mapper.selectByIdempotencyKey("fe:perk:" + REDEMPTION)).thenReturn(new FeReferenceSettlement());

        service.recordPerkRedemption(redemption(), perkOffer());

        verify(mapper, never()).insertReference(any());
    }

    @Test
    void nonPerkOfferAndNonFreeExploreTopicWriteNothing() {
        MerchantChapterOffer revshare = perkOffer();
        revshare.setTermsMode("REVSHARE");
        service.recordPerkRedemption(redemption(), revshare);
        MerchantChapterOffer traffic = perkOffer();
        traffic.setTermsMode("TRAFFIC");
        service.recordPerkRedemption(redemption(), traffic);
        topic.setProductType(1);
        service.recordPerkRedemption(redemption(), perkOffer());

        verify(mapper, never()).insertReference(any());
    }

    @Test
    void reversedRedemptionVoidsItsRow() {
        when(mapper.voidByRedemption(REDEMPTION)).thenReturn(1);

        assertEquals(1, service.voidForReversedRedemption(REDEMPTION));
    }

    @Test
    void fullRefundVoidsOnlyUnredeemedRowsOfThatOrder() {
        when(mapper.voidUnredeemedByRegistration(REG)).thenReturn(1);

        assertEquals(1, service.voidUnredeemedOnRefund(REG));
        verify(mapper).voidUnredeemedByRegistration(REG);
    }

    // ------------------------------------------------------------------ REVSHARE

    @Test
    void revshareHeadsBecomeReferenceRowsHeadsTimesUnit() {
        activity.setClubId(CLUB);
        SessionPayeeResolver.Payer payer = new SessionPayeeResolver.Payer(FeReferenceSettlement.PAYER_CLUB, CLUB, PRINCIPAL);
        IFeReferenceSettlementService.RevshareLine line = new IFeReferenceSettlementService.RevshareLine(
                SHOP, "MERCHANT_CHAPTER_OFFER", OFFER, CHAPTER, 3, new BigDecimal("8.00"), new BigDecimal("24.00"));

        service.recordRevshareHeads(activity, topic, payer, List.of(line), null);

        FeReferenceSettlement row = inserted();
        assertEquals(FeReferenceSettlement.BASIS_REVSHARE_HEAD, row.getBasis());
        assertEquals(3, row.getQuantity());
        assertEquals(new BigDecimal("8.00"), row.getUnitAmount());
        assertEquals(new BigDecimal("24.00"), row.getAmount());
        assertEquals(Long.valueOf(OFFER), row.getOfferId());
        assertEquals(Long.valueOf(CHAPTER), row.getChapterId());
        assertEquals(FeReferenceSettlement.PAYER_CLUB, row.getPayerKind());
        assertEquals(Long.valueOf(CLUB), row.getPayerId());
        assertEquals(FeReferenceSettlement.STATUS_OPEN, row.getStatus());
        assertEquals("fe:rv:" + ACTIVITY + ":O:" + OFFER + ":" + SHOP, row.getIdempotencyKey());
        assertTrue(row.getIdempotencyKey().length() <= 96);
    }

    @Test
    void revshareReplayAndZeroHeadLinesWriteNothing() {
        SessionPayeeResolver.Payer payer = new SessionPayeeResolver.Payer(FeReferenceSettlement.PAYER_MEMBER, ORGANIZER, ORGANIZER);
        when(mapper.selectByIdempotencyKey("fe:rv:" + ACTIVITY + ":C:9:" + SHOP + ":r:" + REG)).thenReturn(new FeReferenceSettlement());

        service.recordRevshareHeads(activity, topic, payer, Arrays.asList(
                new IFeReferenceSettlementService.RevshareLine(SHOP, "COOP_ORDER", 9L, null, 1,
                        new BigDecimal("5.00"), new BigDecimal("5.00")),
                new IFeReferenceSettlementService.RevshareLine(SHOP + 1, "COOP_ORDER", 10L, null, 0,
                        new BigDecimal("5.00"), BigDecimal.ZERO)), REG);

        verify(mapper, never()).insertReference(any());
    }

    // ------------------------------------------------------------------ 线下结清 / 异议

    private static FeReferenceSettlement owned(long id, String status) {
        FeReferenceSettlement row = new FeReferenceSettlement();
        row.setId(id);
        row.setStatus(status);
        row.setSupplierKind(MerchantChapterOffer.SUPPLIER_MERCHANT);
        return row;
    }

    @Test
    void payerMarksOwnOpenOrDisputedRowsSettledOffline() {
        when(mapper.selectOwnedByPayerForUpdate(ORGANIZER, List.of(1L, 2L)))
                .thenReturn(List.of(owned(1L, "OPEN"), owned(2L, "DISPUTED")));
        when(mapper.markSettledOffline(anyLong(), eq(ORGANIZER), eq("V-1"), eq("已转账"))).thenReturn(1);

        assertEquals(2, service.settle(ORGANIZER, List.of(2L, 1L), " V-1 ", "已转账"));

        verify(mapper).markSettledOffline(1L, ORGANIZER, "V-1", "已转账");
        verify(mapper).markSettledOffline(2L, ORGANIZER, "V-1", "已转账");
    }

    @Test
    void settleRejectsRowsNotOwnedByCaller() {
        when(mapper.selectOwnedByPayerForUpdate(SHOP, List.of(1L, 2L))).thenReturn(List.of(owned(1L, "OPEN")));

        assertThrows(ServiceException.class, () -> service.settle(SHOP, List.of(1L, 2L), null, null));

        verify(mapper, never()).markSettledOffline(anyLong(), anyLong(), any(), any());
    }

    @Test
    void settleRejectsAlreadySettledOrVoidAndBadInput() {
        when(mapper.selectOwnedByPayerForUpdate(ORGANIZER, List.of(1L))).thenReturn(List.of(owned(1L, "VOID")));

        assertThrows(ServiceException.class, () -> service.settle(ORGANIZER, List.of(1L), null, null));
        assertThrows(ServiceException.class, () -> service.settle(ORGANIZER, Collections.emptyList(), null, null));
        assertThrows(ServiceException.class, () -> service.settle(ORGANIZER, List.of(1L, 1L), null, null));
        assertThrows(ServiceException.class, () -> service.settle(ORGANIZER, List.of(1L), "x".repeat(65), null));
        verify(mapper, never()).markSettledOffline(anyLong(), anyLong(), any(), any());
    }

    @Test
    void merchantDisputesOwnRowOnly() {
        when(mapper.selectOwnedByMerchantForUpdate(5L, SHOP)).thenReturn(owned(5L, "SETTLED_OFFLINE"));
        when(mapper.markDisputed(5L, SHOP, "没收到")).thenReturn(1);

        service.dispute(SHOP, 5L, " 没收到 ");
        verify(mapper).markDisputed(5L, SHOP, "没收到");

        assertThrows(ServiceException.class, () -> service.dispute(SHOP + 9, 5L, "不是我的"));
        assertThrows(ServiceException.class, () -> service.dispute(SHOP, 5L, " "));
        FeReferenceSettlement author = owned(6L, "SETTLED_OFFLINE");
        author.setSupplierKind(MerchantChapterOffer.SUPPLIER_AUTHOR);
        when(mapper.selectOwnedByMerchantForUpdate(6L, SHOP)).thenReturn(author);
        assertThrows(ServiceException.class, () -> service.dispute(SHOP, 6L, "作者行"));
        verify(mapper, times(1)).markDisputed(anyLong(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------ 读

    private static FeReferenceSettlement listed(long id, Long activityId, String status, String amount) {
        FeReferenceSettlement row = owned(id, status);
        row.setActivityId(activityId);
        row.setActivityTitle("示例站点 · 场次" + activityId);
        row.setChapterName("示例站点");
        row.setMerchantName("示例店铺");
        row.setPayerKind(FeReferenceSettlement.PAYER_MEMBER);
        row.setPayerId(ORGANIZER);
        row.setBasis(FeReferenceSettlement.BASIS_PERK_REDEEMED);
        row.setQuantity(1);
        row.setUnitAmount(new BigDecimal(amount));
        row.setAmount(new BigDecimal(amount));
        return row;
    }

    @Test
    void merchantViewGroupsBySessionWithOrganizerAndTotals() {
        MmsMerchant organizer = new MmsMerchant();
        organizer.setName("示例主办方");
        when(merchants.selectMmsMerchantByMemberId(ORGANIZER)).thenReturn(organizer);
        when(mapper.selectForMerchant(SHOP, null)).thenReturn(Arrays.asList(
                listed(1L, 10L, "OPEN", "12.00"), listed(2L, 10L, "SETTLED_OFFLINE", "8.00"),
                listed(3L, 11L, "DISPUTED", "5.50")));

        FeReferenceSettlementView view = service.listForMerchant(SHOP, null);

        assertEquals(2, view.getGroups().size());
        FeReferenceSettlementView.Group first = view.getGroups().get(0);
        assertEquals(Long.valueOf(10L), first.getActivityId());
        assertEquals("示例主办方", first.getPayerName());
        assertEquals(new BigDecimal("12.00"), first.getOpenAmount());
        assertEquals(new BigDecimal("8.00"), first.getSettledAmount());
        assertEquals("待线下结款", first.getRows().get(0).getStatusText());
        assertEquals("已线下结清", first.getRows().get(1).getStatusText());
        assertEquals("有异议", view.getGroups().get(1).getRows().get(0).getStatusText());
        assertEquals(new BigDecimal("17.50"), view.getTotalOpenAmount());
        assertEquals(new BigDecimal("8.00"), view.getTotalSettledAmount());
    }

    @Test
    void emptyViewShowsZeroMoney() {
        FeReferenceSettlementView view = service.listForMerchant(SHOP, "OPEN");

        assertTrue(view.getGroups().isEmpty());
        assertEquals("0.00", view.getTotalOpenAmount().toPlainString(), "MC-011=B:没有数据显示 ¥0.00");
        assertEquals("0.00", view.getTotalSettledAmount().toPlainString());
        assertThrows(ServiceException.class, () -> service.listForMerchant(SHOP, "PAID"));
    }

    @Test
    void payerViewOfOneSessionRequiresBeingItsPayee() {
        assertThrows(ServiceException.class, () -> service.listForPayer(SHOP, ACTIVITY));
        verify(mapper, never()).selectForPayer(anyLong(), any());

        service.listForPayer(ORGANIZER, ACTIVITY);
        verify(mapper).selectForPayer(ORGANIZER, ACTIVITY);
    }

    @Test
    void shopNoteNamesTheSessionPayeeNotTheTopicPublisher() {
        // 主题由俱乐部 A 发布,场次由别的主办方开 ⇒ 店铺该找的是场次收款人
        topic.setClubId(CLUB);
        activity.setClubId(null);
        activity.setMemberId(ORGANIZER);
        MmsMerchant organizer = new MmsMerchant();
        organizer.setName("示例主办方");
        when(merchants.selectMmsMerchantByMemberId(ORGANIZER)).thenReturn(organizer);
        Club club = new Club();
        club.setId(CLUB);
        club.setName("示例俱乐部");
        club.setMemberId(PRINCIPAL);
        when(clubs.selectClubById(CLUB)).thenReturn(club);

        assertEquals("票款由主办方收取；店铺如需结算，请联系主办方线下处理 · 主办方：示例主办方",
                service.offlineSettlementNoteForRedemption(redemption()));
    }
}
