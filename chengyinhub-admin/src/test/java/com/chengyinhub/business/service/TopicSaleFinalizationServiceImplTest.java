package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.service.impl.TopicSaleFinalizationServiceImpl;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.ApprovedSaleCanonical;
import com.chengyinhub.business.service.support.ApprovedSaleCanonicalSource;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.business.service.support.TopicSaleCanonicalBuilder;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 合同二闸 2：工作流回执不能绕过 AcceptedLineup，所有开售都走 CAS+实际时刻。 */
class TopicSaleFinalizationServiceImplTest {

    private static final Long TOPIC_ID = 61L;
    private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");

    private CmsTopicMapper topicMapper;
    private OmsTicketMapper ticketMapper;
    private CmsTopicChapterMapper chapterMapper;
    private MerchantChapterOfferMapper offerMapper;
    private CoopOrderLineupSource lineupSource;
    private CommercialTopicPolicy commercialTopicPolicy;
    private CommercialReviewStatusSource reviewStatusSource;
    private MerchantPublishedTopicTransferGate transferGate;
    private TopicSaleSnapshotMapper saleSnapshotMapper;
    private CmsActivityMapper activityMapper;
    private TopicSaleCanonicalBuilder canonicalBuilder;
    private ApprovedSaleCanonicalSource approvedCanonicalSource;
    private IProductRoleService productRoleService;
    private IClubCorporatePayeeService clubPayeeService;
    private IClubEditionCompensationService compensationService;
    private TopicSaleFinalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        lineupSource = mock(CoopOrderLineupSource.class);
        commercialTopicPolicy = mock(CommercialTopicPolicy.class);
        reviewStatusSource = mock(CommercialReviewStatusSource.class);
        transferGate = mock(MerchantPublishedTopicTransferGate.class);
        saleSnapshotMapper = mock(TopicSaleSnapshotMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        canonicalBuilder = mock(TopicSaleCanonicalBuilder.class);
        approvedCanonicalSource = mock(ApprovedSaleCanonicalSource.class);
        productRoleService = mock(IProductRoleService.class);
        ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(PendingRecruitWorkflowTerminator.class));
        service = new TopicSaleFinalizationServiceImpl(topicMapper, ticketMapper, chapterMapper, offerMapper,
                lineupSource, provider, commercialTopicPolicy, reviewStatusSource, transferGate);
        ReflectionTestUtils.setField(service, "saleSnapshotMapper", saleSnapshotMapper);
        ReflectionTestUtils.setField(service, "canonicalBuilder", canonicalBuilder);
        ReflectionTestUtils.setField(service, "approvedCanonicalSource", approvedCanonicalSource);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        clubPayeeService = mock(IClubCorporatePayeeService.class);
        ReflectionTestUtils.setField(service, "clubPayeeService", clubPayeeService);
        compensationService = mock(IClubEditionCompensationService.class);
        ReflectionTestUtils.setField(service, "compensationService", compensationService);
        service.setClock(Clock.fixed(NOW, ZoneId.of("UTC")));
        when(chapterMapper.selectRequiredByTopic(TOPIC_ID)).thenReturn(Collections.emptyList());
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.emptyList());
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(validGuidedLineup());
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(validTicket()));
        CmsActivity activity = new CmsActivity();
        activity.setId(801L);
        activity.setTopicId(TOPIC_ID);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(activity));
        when(topicMapper.finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class))).thenReturn(1);
        when(productRoleService.activeOrganizerSlot(TOPIC_ID)).thenReturn(platformOrganizer());
        when(commercialTopicPolicy.evaluate(TOPIC_ID)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        stubSuccessfulCanonicalFreeze();
    }

    @Test
    void shopDayWithoutPlatformOrganizerActorCannotFinalize() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(productRoleService.activeOrganizerSlot(TOPIC_ID)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_FREE_EXPLORE_ORGANIZER_MISSING", error.getMessage());
        verify(saleSnapshotMapper, never()).insertSnapshot(any(TopicSaleSnapshot.class));
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void shopDayOrganizerMustBePlatformAndCarryDeclarationAudit() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        ProductRole club = platformOrganizer();
        club.setEntityType(RoleEntityType.CLUB);
        club.setEntityId(701L);
        when(productRoleService.activeOrganizerSlot(TOPIC_ID)).thenReturn(club);

        IllegalStateException wrongActor = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));
        assertEquals("SALE_FREE_EXPLORE_ORGANIZER_MUST_BE_PLATFORM", wrongActor.getMessage());

        ProductRole noOperator = platformOrganizer();
        noOperator.setDeclaredBy(null);
        when(productRoleService.activeOrganizerSlot(TOPIC_ID)).thenReturn(noOperator);
        IllegalStateException missingAudit = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));
        assertEquals("SALE_FREE_EXPLORE_ORGANIZER_AUDIT_MISSING", missingAudit.getMessage());
    }

    @Test
    void directFinalizeForSaleWithoutApprovedReviewFailsBeforeAnySaleWrite() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(pricingTopic(ProductType.FREE_EXPLORE));
        when(commercialTopicPolicy.evaluate(TOPIC_ID))
                .thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));
        org.mockito.Mockito.doThrow(new IllegalStateException("开售审核未通过"))
                .when(reviewStatusSource).assertSaleApproved(TOPIC_ID);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("开售审核未通过", error.getMessage());
        verify(reviewStatusSource).assertSaleApproved(TOPIC_ID);
        verify(saleSnapshotMapper, never()).insertSnapshot(any(TopicSaleSnapshot.class));
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void approvedReviewStillRereadsAndRevalidatesTransferBeforeFinalizing() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setMemberId(501L);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(commercialTopicPolicy.evaluate(TOPIC_ID))
                .thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));
        CoopOrderLineupSource.ReadResult current = new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(organizer(7L)), Collections.emptyList());
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(current);

        service.finalizeForSale(TOPIC_ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(reviewStatusSource, lineupSource, transferGate,
                saleSnapshotMapper, topicMapper);
        order.verify(reviewStatusSource).assertSaleApproved(TOPIC_ID);
        order.verify(lineupSource).readEffectiveLineup(TOPIC_ID);
        order.verify(transferGate).assertTransferred(topic, current);
        order.verify(saleSnapshotMapper).insertSnapshot(any(TopicSaleSnapshot.class));
        order.verify(topicMapper).finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class));
    }

    @Test
    void duplicateCanonicalSnapshotFailsBeforeSellingCas() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(commercialTopicPolicy.evaluate(TOPIC_ID))
                .thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));
        when(saleSnapshotMapper.selectSnapshotByTopicId(TOPIC_ID)).thenReturn(new TopicSaleSnapshot());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_SNAPSHOT_DUPLICATE", error.getMessage());
        verify(saleSnapshotMapper, never()).insertSnapshot(any(TopicSaleSnapshot.class));
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void changedDraftVersionFailsClosed() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(commercialTopicPolicy.evaluate(TOPIC_ID))
                .thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));
        TopicSaleCanonicalDraft changed = draft(2L, completeCanonical());
        when(saleSnapshotMapper.selectDraftByTopicIdForUpdate(TOPIC_ID)).thenReturn(changed);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_SNAPSHOT_PARAMETER_VERSION_CHANGED", error.getMessage());
        verify(saleSnapshotMapper, never()).insertSnapshot(any(TopicSaleSnapshot.class));
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void transferValidAtReviewButVoidedBeforeFinalizeFailsRealGateAndSkipsCas() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setMemberId(501L);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(commercialTopicPolicy.evaluate(TOPIC_ID))
                .thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 11L));

        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(501L);
        merchant.setStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberId(501L)).thenReturn(merchant);
        CoopOrderLineupSource.ReadResult accepted = new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(merchantOrganizer(7L, 501L)), Collections.emptyList());
        CoopOrderLineupSource.ReadResult voided = new CoopOrderLineupSource.ReadResult(
                Collections.emptyList(), Collections.emptyList());
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(accepted, voided);
        MerchantPublishedTopicTransferGate realGate =
                new MerchantPublishedTopicTransferGate(merchantMapper, clubMapper, lineupSource);

        realGate.assertTransferred(topic); // 开售提审时有效
        ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(PendingRecruitWorkflowTerminator.class));
        TopicSaleFinalizationServiceImpl staleService = new TopicSaleFinalizationServiceImpl(
                topicMapper, ticketMapper, chapterMapper, offerMapper, lineupSource, provider,
                commercialTopicPolicy, reviewStatusSource, realGate);
        staleService.setClock(Clock.fixed(NOW, ZoneId.of("UTC")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> staleService.finalizeForSale(TOPIC_ID));

        assertTrue(error.getMessage().contains("当前=0"));
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void acceptedInviteWithoutFrozenOrderSnapshotFailsClosed() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(pricingTopic(ProductType.ORIENTEERING));
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Collections.emptyList(), Collections.singletonList("coop_order:9 缺快照")));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID));

        assertTrue(error.getMessage().contains("合作单缺冻结快照"), "应明确是 AcceptedLineup 快照闸拦截");
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void missingRequiredChapterNamesTheExactChapter() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(pricingTopic(ProductType.ORIENTEERING));
        CmsTopicChapter required = new CmsTopicChapter();
        required.setId(991L);
        when(chapterMapper.selectRequiredByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(required));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID));

        assertTrue(error.getMessage().contains("chapterId=991"), "必选章空缺必须点名被拦字段");
    }

    @Test
    void guidedTopicRejectsZeroAndMultipleOrganizers() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(pricingTopic(ProductType.ORIENTEERING));
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Collections.emptyList(), Collections.emptyList()));
        IllegalStateException noOrganizer = assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID));
        assertTrue(noOrganizer.getMessage().contains("当前=0"));

        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Arrays.asList(organizer(3L), organizer(4L)), Collections.emptyList()));
        IllegalStateException multipleOrganizers = assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID));
        assertTrue(multipleOrganizers.getMessage().contains("当前=2"));
    }

    @Test
    void freeExploreAlsoRequiresCanonicalAndWritesFinalizationCas() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(BigDecimal.ZERO);
        topic.setFeePlatformRate(null);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.emptyList());
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(validGuidedLineup());

        Date finalized = service.finalizeForSale(TOPIC_ID);

        assertEquals(Date.from(NOW), finalized);
        verify(topicMapper).finalizeForSale(eq(TOPIC_ID), eq(Date.from(NOW)), eq(Date.from(NOW)));
    }

    @Test
    void casFailureNamesAtomicSellingStartedAtGate() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(pricingTopic(ProductType.ORIENTEERING));
        when(topicMapper.finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class))).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID));

        assertTrue(error.getMessage().contains("sellingStartedAt"), "CAS 与开售时刻必须作为同一道原子闸失败");
    }

    @Test
    void sellingRetryReturnsPersistedTimeWithoutOverwritingIt() {
        CmsTopic selling = pricingTopic(ProductType.ORIENTEERING);
        selling.setLifecycle(TopicLifecycleState.SELLING.getCode());
        Date persisted = Date.from(Instant.parse("2026-08-10T01:00:00Z"));
        selling.setSellingStartedAt(persisted);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(selling);

        assertEquals(persisted, service.finalizeForSale(TOPIC_ID));
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void sellingFreeExploreWithoutPersistedCanonicalFailsClosed() {
        CmsTopic selling = pricingTopic(ProductType.FREE_EXPLORE);
        selling.setLifecycle(TopicLifecycleState.SELLING.getCode());
        selling.setSellingStartedAt(Date.from(NOW.minusSeconds(60)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(selling);
        when(saleSnapshotMapper.selectSnapshotByTopicId(TOPIC_ID)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_SNAPSHOT_MISSING", error.getMessage());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void requiredRevshareChapterAndOfferAreRejectedBeforeSelling() {
        givenNonPerkRequiredSupply(TermsMode.REVSHARE, new BigDecimal("1.00"), 0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_FREE_EXPLORE_TERMS_NOT_PERK", error.getMessage());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void requiredTrafficChapterCannotBypassWithZeroQuota() {
        givenNonPerkRequiredSupply(TermsMode.TRAFFIC, null, 0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_FREE_EXPLORE_TERMS_NOT_PERK", error.getMessage());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void classicProductKeepsOriginalFinalizationAndSkipsShopDayCanonicalGates() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID))
                .thenReturn(pricingTopic(ProductType.ORIENTEERING));

        assertEquals(Date.from(NOW), service.finalizeForSale(TOPIC_ID));

        verify(reviewStatusSource, never()).assertSaleApproved(any(Long.class));
        verify(saleSnapshotMapper, never()).selectDraftByTopicIdForUpdate(any(Long.class));
        verify(saleSnapshotMapper, never()).insertSnapshot(any(TopicSaleSnapshot.class));
    }

    @Test
    void activityProjectionIncludesCanonicalTicketNameAndMode() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        OmsTicket source = validTicket();
        OmsTicket projected = validTicket();
        projected.setName("被售后改名");
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            return Long.valueOf(2L).equals(query.getOwnerType())
                    ? Collections.singletonList(projected) : Collections.singletonList(source);
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_NAME", error.getMessage());
    }

    /** 俱乐部开团的活动票按复制口径带本场时间(OmsTicketServiceImpl.projectedStart/EndTime)，开售核对同一口径。 */
    @Test
    void clubSessionProjectionUsesTheSessionWindowButStillRejectsTamperedTimesAndFields() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        CmsActivity session = new CmsActivity();
        session.setId(802L); session.setTopicId(TOPIC_ID); session.setDelFlag(0); session.setClubId(7002L);
        session.setStartDate(Date.from(Instant.parse("2026-08-20T02:00:00Z")));
        session.setEndDate(Date.from(Instant.parse("2026-08-20T15:59:59Z")));
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class))).thenReturn(Collections.singletonList(session));
        OmsTicket source = validTicket();
        OmsTicket projected = validTicket();
        projected.setStartTime(session.getStartDate());
        projected.setEndTime(session.getEndDate());
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            return Long.valueOf(2L).equals(query.getOwnerType())
                    ? Collections.singletonList(projected) : Collections.singletonList(source);
        });
        ReflectionTestUtils.invokeMethod(service, "assertActivityTicketProjection", TOPIC_ID,
                Collections.singletonList(source));

        // 负控:场次票被改回主题旧日期 / 改了别的字段，仍拒绝。
        projected.setEndTime(source.getEndTime());
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "assertActivityTicketProjection", TOPIC_ID,
                        Collections.singletonList(source)));
        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_END_TIME", stale.getMessage());
        projected.setEndTime(session.getEndDate());
        projected.setPrice(new BigDecimal("1"));
        IllegalStateException price = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "assertActivityTicketProjection", TOPIC_ID,
                        Collections.singletonList(source)));
        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_PRICE", price.getMessage());
    }

    /** 非俱乐部活动(无 clubId)仍要求与主题票时间一致。 */
    @Test
    void nonClubActivityProjectionStillRequiresTopicTicketTimes() {
        OmsTicket source = validTicket();
        OmsTicket projected = validTicket();
        projected.setEndTime(Date.from(Instant.parse("2026-08-20T15:59:59Z")));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            return Long.valueOf(2L).equals(query.getOwnerType())
                    ? Collections.singletonList(projected) : Collections.singletonList(source);
        });
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "assertActivityTicketProjection", TOPIC_ID,
                        Collections.singletonList(source)));
        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_END_TIME", error.getMessage());
    }

    @Test
    void projectionAcceptsUnsetAndDefaultNoTeamSizeInBothDirections() {
        OmsTicket source = validTicket();
        OmsTicket projected = validTicket();
        source.setTeamSize(null);
        projected.setTeamSize(0);
        ReflectionTestUtils.invokeMethod(service, "assertTicketProjection", source, projected, new CmsActivity());
        ReflectionTestUtils.invokeMethod(service, "assertTicketProjection", projected, source, new CmsActivity());
    }

    @Test
    void projectionStillRejectsDifferentPositiveTeamSize() {
        OmsTicket source = validTicket();
        OmsTicket projected = validTicket();
        source.setTeamSize(3);
        projected.setTeamSize(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "assertTicketProjection", source, projected, new CmsActivity()));
        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_TEAM_SIZE", error.getMessage());
    }

    @Test
    void clubSessionsOnDifferentDatesUseTheirOwnActivityWindowsWhenFinalizing() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        OmsTicket source = validTicket();
        CmsActivity first = new CmsActivity();
        first.setId(801L);
        first.setTopicId(TOPIC_ID);
        first.setClubId(7002L);
        first.setStartDate(Date.from(Instant.parse("2026-08-12T02:00:00Z")));
        first.setEndDate(Date.from(Instant.parse("2026-08-12T15:59:59Z")));
        CmsActivity second = new CmsActivity();
        second.setId(802L);
        second.setTopicId(TOPIC_ID);
        second.setClubId(7002L);
        second.setStartDate(Date.from(Instant.parse("2026-08-13T02:00:00Z")));
        second.setEndDate(Date.from(Instant.parse("2026-08-13T15:59:59Z")));
        OmsTicket firstProjected = validTicket();
        firstProjected.setStartTime(first.getStartDate());
        firstProjected.setEndTime(first.getEndDate());
        OmsTicket secondProjected = validTicket();
        secondProjected.setStartTime(second.getStartDate());
        secondProjected.setEndTime(second.getEndDate());
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Arrays.asList(first, second));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            if (!Long.valueOf(2L).equals(query.getOwnerType())) return Collections.singletonList(source);
            return Collections.singletonList(first.getId().equals(query.getOwnerId())
                    ? firstProjected : secondProjected);
        });

        assertEquals(Date.from(NOW), service.finalizeForSale(TOPIC_ID));

        verify(topicMapper).finalizeForSale(TOPIC_ID, Date.from(NOW), Date.from(NOW));
    }

    @Test
    void clubSessionMatchesTicketsByTheirProjectedWindowsBeforeComparingOtherTerms() {
        CmsActivity activity = new CmsActivity();
        activity.setId(801L);
        activity.setClubId(7002L);
        activity.setStartDate(Date.from(Instant.parse("2026-08-13T02:00:00Z")));
        activity.setEndDate(Date.from(Instant.parse("2026-08-13T15:59:59Z")));
        OmsTicket earlySource = validTicket();
        earlySource.setSignupDeadline(Date.from(Instant.parse("2026-08-11T00:00:00Z")));
        OmsTicket lateSource = validTicket();
        lateSource.setStartTime(Date.from(Instant.parse("2026-08-19T02:00:00Z")));
        lateSource.setEndTime(Date.from(Instant.parse("2026-08-20T02:00:00Z")));
        lateSource.setSignupDeadline(Date.from(Instant.parse("2026-08-04T00:00:00Z")));
        OmsTicket earlyProjected = validTicket();
        earlyProjected.setStartTime(activity.getStartDate());
        earlyProjected.setEndTime(activity.getEndDate());
        earlyProjected.setSignupDeadline(earlySource.getSignupDeadline());
        OmsTicket lateProjected = validTicket();
        lateProjected.setStartTime(activity.getStartDate());
        lateProjected.setEndTime(activity.getEndDate());
        lateProjected.setSignupDeadline(lateSource.getSignupDeadline());
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(activity));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Arrays.asList(lateProjected, earlyProjected));

        ReflectionTestUtils.invokeMethod(service, "assertActivityTicketProjection",
                TOPIC_ID, Arrays.asList(earlySource, lateSource));
    }

    @Test
    void clubSessionStillRejectsStartAndEndThatDifferFromItsOwnActivity() {
        assertProjectionTimeMismatch(true, "START_TIME");
        assertProjectionTimeMismatch(true, "END_TIME");
    }

    @Test
    void ordinaryActivityStillRejectsStartAndEndThatDifferFromItsSourceTicket() {
        assertProjectionTimeMismatch(false, "START_TIME");
        assertProjectionTimeMismatch(false, "END_TIME");
    }

    private void assertProjectionTimeMismatch(boolean clubSession, String field) {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        OmsTicket source = validTicket();
        CmsActivity activity = new CmsActivity();
        activity.setId(801L);
        activity.setTopicId(TOPIC_ID);
        activity.setStartDate(Date.from(Instant.parse("2026-08-13T02:00:00Z")));
        activity.setEndDate(Date.from(Instant.parse("2026-08-13T15:59:59Z")));
        if (clubSession) activity.setClubId(7002L);
        OmsTicket projected = validTicket();
        if (clubSession) {
            projected.setStartTime(activity.getStartDate());
            projected.setEndTime(activity.getEndDate());
        }
        if ("START_TIME".equals(field)) {
            projected.setStartTime(new Date(projected.getStartTime().getTime() + 60000));
        } else {
            projected.setEndTime(new Date(projected.getEndTime().getTime() + 60000));
        }
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(activity));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            return Collections.singletonList(Long.valueOf(2L).equals(query.getOwnerType())
                    ? projected : source);
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_TICKET_PROJECTION_MISMATCH_" + field, error.getMessage());
        verify(topicMapper, never()).finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class));
        verify(saleSnapshotMapper, never()).insertSnapshot(any(TopicSaleSnapshot.class));
    }

    private void givenNonPerkRequiredSupply(String termsMode, BigDecimal fee, int quota) {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(991L);
        chapter.setTermsMode(termsMode);
        when(chapterMapper.selectRequiredByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(chapter));
        com.chengyinhub.business.domain.MerchantChapterOffer offer =
                new com.chengyinhub.business.domain.MerchantChapterOffer();
        offer.setTopicId(TOPIC_ID);
        offer.setChapterId(991L);
        offer.setMerchantId(200L);
        offer.setTermsMode(termsMode);
        offer.setPerHeadFee(fee);
        offer.setQuotaTotal(quota);
        offer.setStatus(1);
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(offer));
    }

    private static ProductRole platformOrganizer() {
        ProductRole role = new ProductRole();
        role.setId(91L);
        role.setTopicId(TOPIC_ID);
        role.setSlot(ProductSlot.ORGANIZER);
        role.setEntityType(RoleEntityType.PLATFORM);
        role.setDeclaredBy(9001L);
        role.setCreatedAt(Date.from(NOW.minusSeconds(3600)));
        role.setStatus(1);
        return role;
    }

    private static CmsTopic pricingTopic(int productType) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        topic.setProductType(productType);
        topic.setSaleEndAt(Date.from(Instant.parse("2026-08-12T00:00:00Z")));
        topic.setStartDate(Date.from(Instant.parse("2026-08-12T00:00:00Z")));
        topic.setEndDate(Date.from(Instant.parse("2026-08-13T00:00:00Z")));
        topic.setFeePlatformRate(new BigDecimal("0.10"));
        return topic;
    }

    private void stubSuccessfulCanonicalFreeze() {
        stubSuccessfulCanonicalFreeze(completeCanonical());
    }

    private void stubSuccessfulCanonicalFreeze(TopicSaleCanonicalV1 canonical) {
        when(approvedCanonicalSource.read(eq(TOPIC_ID), any())).thenReturn(new ApprovedSaleCanonical(
                701L, TopicSaleCanonicalV1.SCHEMA_VERSION, 1L, canonical));
        when(saleSnapshotMapper.selectDraftByTopicIdForUpdate(TOPIC_ID)).thenReturn(draft(1L, canonical));
        when(canonicalBuilder.rebuild(any(CmsTopic.class), any(TopicSaleCanonicalV1.class)))
                .thenReturn(canonical);
        AtomicReference<TopicSaleSnapshot> persisted = new AtomicReference<TopicSaleSnapshot>();
        when(saleSnapshotMapper.selectSnapshotByTopicId(TOPIC_ID)).thenAnswer(invocation -> persisted.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            TopicSaleSnapshot write = invocation.getArgument(0);
            persisted.set(write);
            return 1;
        }).when(saleSnapshotMapper).insertSnapshot(any(TopicSaleSnapshot.class));
    }

    @Test
    void ordinaryFreeSaleAllowsOneMerchantTwoTrafficChaptersWithoutExecutingClub() throws Exception {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(
                new CoopOrderLineupSource.ReadResult(Collections.emptyList(), Collections.emptyList()));
        when(productRoleService.activeOrganizerSlot(TOPIC_ID)).thenReturn(null);
        java.util.List<CmsTopicChapter> chapters = new java.util.ArrayList<CmsTopicChapter>();
        java.util.List<com.chengyinhub.business.domain.MerchantChapterOffer> offers = new java.util.ArrayList<>();
        for (long chapterId : new long[]{100L, 101L}) {
            CmsTopicChapter chapter = new CmsTopicChapter();
            chapter.setId(chapterId);
            chapter.setRequired(1);
            chapter.setTermsMode(TermsMode.TRAFFIC);
            chapters.add(chapter);
            com.chengyinhub.business.domain.MerchantChapterOffer offer =
                    new com.chengyinhub.business.domain.MerchantChapterOffer();
            offer.setId(chapterId + 300L);
            offer.setTopicId(TOPIC_ID);
            offer.setChapterId(chapterId);
            offer.setMerchantId(200L);
            offer.setTermsMode(TermsMode.TRAFFIC);
            offer.setStatus(1);
            offers.add(offer);
        }
        when(chapterMapper.selectRequiredByTopic(TOPIC_ID)).thenReturn(chapters);
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(offers);
        stubSuccessfulCanonicalFreeze(ordinaryCanonical());

        service.finalizeForSale(TOPIC_ID);

        verify(saleSnapshotMapper).insertSnapshot(any(TopicSaleSnapshot.class));
        verify(topicMapper).finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class));
        verify(clubPayeeService, never()).snapshotForSale(any(), any(), any());
        verify(compensationService, never()).freezeTermsForSale(any(), any(), any(), any(), any());
        verify(productRoleService, never()).declareDistributionSlotForSale(any(), any(), any());
    }

    @Test
    void ordinaryFreeSaleStillRejectsRequiredMerchantChapterWithoutSupply() throws Exception {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(100L);
        chapter.setRequired(1);
        chapter.setTermsMode(TermsMode.TRAFFIC);
        when(chapterMapper.selectRequiredByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(chapter));
        stubSuccessfulCanonicalFreeze(ordinaryCanonical());

        assertTrue(assertThrows(IllegalStateException.class, () -> service.finalizeForSale(TOPIC_ID))
                .getMessage().contains("缺有效 SUPPLY"));
        verify(topicMapper, never()).finalizeForSale(any(), any(), any());
    }

    private static TopicSaleCanonicalV1 ordinaryCanonical() throws Exception {
        ObjectNode value = TopicSaleCanonicalTestFixture.json();
        value.put("storeCount", 1);
        ((ObjectNode) value.path("completionPolicy")).put("evidenceRule", "ALL_REQUIRED_CHAPTERS");
        for (String section : new String[]{"merchantCashSettlementMode", "microExperienceCatalog", "merchantWorkPackage"}) {
            com.fasterxml.jackson.databind.node.ArrayNode chapters =
                    (com.fasterxml.jackson.databind.node.ArrayNode) value.path(section).path("chapters");
            while (chapters.size() > 2) chapters.remove(chapters.size() - 1);
            for (com.fasterxml.jackson.databind.JsonNode chapter : chapters)
                ((ObjectNode) chapter).put("termsMode", "TRAFFIC");
        }
        for (String section : new String[]{"merchantCashSettlementMode", "microExperienceCatalog"}) {
            com.fasterxml.jackson.databind.node.ArrayNode offers =
                    (com.fasterxml.jackson.databind.node.ArrayNode) value.path(section).path("offers");
            while (offers.size() > 2) offers.remove(offers.size() - 1);
            for (com.fasterxml.jackson.databind.JsonNode offer : offers) {
                ((ObjectNode) offer).put("termsMode", "TRAFFIC").put("merchantId", 200L);
                ((ObjectNode) offer).remove("perkId");
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode stores = (com.fasterxml.jackson.databind.node.ArrayNode) value.path("storeCluster");
        while (stores.size() > 2) stores.remove(stores.size() - 1);
        for (com.fasterxml.jackson.databind.JsonNode store : stores) ((ObjectNode) store).put("merchantMemberId", 200L);
        ((ObjectNode) value.path("microExperienceCatalog")).putArray("perks");
        ((ObjectNode) value.path("merchantWorkPackage")).putArray("workItems");
        ((ObjectNode) value.path("clubWorkPackage")).putArray("organizers");
        ((ObjectNode) value.path("completionPolicy")).putArray("requiredChapterIds").add(100L).add(101L);
        return new ObjectMapper().treeToValue(value, TopicSaleCanonicalV1.class);
    }

    private static TopicSaleCanonicalDraft draft(Long version, TopicSaleCanonicalV1 canonical) {
        TopicSaleCanonicalDraft draft = new TopicSaleCanonicalDraft();
        draft.setTopicId(TOPIC_ID);
        draft.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
        draft.setParameterVersion(version);
        draft.setCanonicalJson(TopicSaleCanonicalCodec.writeDraft(canonical));
        return draft;
    }

    private static TopicSaleCanonicalV1 completeCanonical() {
        return TopicSaleCanonicalTestFixture.canonical();
    }

    private static OmsTicket validTicket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setName("一期场次票");
        ticket.setMode(ProductType.FREE_EXPLORE);
        ticket.setPrice(new BigDecimal("88"));
        ticket.setTotalInventory(10L);
        ticket.setRemainingInventory(10L);
        ticket.setStartTime(Date.from(Instant.parse("2026-08-12T02:00:00Z")));
        ticket.setEndTime(Date.from(Instant.parse("2026-08-13T02:00:00Z")));
        ticket.setDelFlag(0);
        ticket.setSubType("guided");
        return ticket;
    }

    // ── A4 开售闸：对公收款人快照必须在 PRICING→SELLING CAS 之前冻结 ──────────────
    @Test
    void shopDayFreezesCorporatePayeeSnapshotBeforeSellingCas() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);

        service.finalizeForSale(TOPIC_ID);

        verify(clubPayeeService).snapshotForSale(TOPIC_ID, 7L, Date.from(NOW));
        verify(topicMapper).finalizeForSale(eq(TOPIC_ID), eq(Date.from(NOW)), eq(Date.from(NOW)));
    }

    @Test
    void shopDayCannotEnterSellingWhenPayeeGateBlocks() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(clubPayeeService.snapshotForSale(eq(TOPIC_ID), eq(7L), any(Date.class)))
                .thenThrow(new IllegalStateException("SALE_PAYEE_NOT_VERIFIED"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_PAYEE_NOT_VERIFIED", error.getMessage());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void shopDayCannotEnterSellingWithoutExactlyOneExecutingClub() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(
                new CoopOrderLineupSource.ReadResult(Collections.emptyList(), Collections.emptyList()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("SALE_EXECUTING_CLUB_UNRESOLVED", error.getMessage());
        verify(clubPayeeService, never()).snapshotForSale(any(), any(), any());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    @Test
    void twoDifferentOrganizerClubsBlockSaleInsteadOfBeingGuessed() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        topic.setClubId(7L);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Arrays.asList(organizer(7L), organizer(8L)), Collections.emptyList()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        // 实测：多执行俱乐部先被既有的 assertOrganizer 拦住，所以红在它那句话上。
        // resolveExecutingClub 里的 AMBIGUOUS 分支是同一条资金路径上的第二道闸（纵深防御），
        // 若将来 assertOrganizer 放宽，冻结快照也不会去随便挑一个 club。
        assertEquals("FREE_EXPLORE ORGANIZER 至多一个，当前=2", error.getMessage(),
                "两个不同的执行俱乐部必须阻断，绝不退回去猜 CmsTopic.club_id");
        verify(clubPayeeService, never()).snapshotForSale(any(), any(), any());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    // ── F2 开售闸：补偿条款必须与收款人快照在同一次开售事务里冻结 ──────────────
    //
    // 这一组钉的是 P0-1：条款冻结方法写好了却没有任何生产调用方 ⇒ A1(Q)/A2(EXTRA) 的六个写入口
    // 与 F3 的 FINAL 在生产上全部抛 EDITION_TERMS_MISSING，而本地/CI 全绿。
    // 判据必须是「开售链路真的调了它」，不是「那个方法自己能跑」。

    /** 履约窗结束的权威瞬间（FulfillmentWindowResolver 的 Asia/Shanghai 日粒度窗）。 */
    private static final Date EXPECTED_FULFILLMENT_END_AT = Date.from(Instant.parse("2026-08-13T16:00:00Z"));
    /** T+7 的权威起点 = 上面那个瞬间。 */
    private static final Date EXPECTED_EVIDENCE_DUE_AT =
            Date.from(Instant.parse("2026-08-13T16:00:00Z").plus(7, java.time.temporal.ChronoUnit.DAYS));

    @Test
    void shopDayFreezesCompensationTermsWithTheAuthoritativeEditionEndPlusSevenDays() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);

        service.finalizeForSale(TOPIC_ID);

        // endDate=2026-08-13T00:00Z ⇒ Asia/Shanghai 的 2026-08-13 ⇒ 窗口结束 2026-08-14T00:00+08:00
        // = 2026-08-13T16:00Z；T+7 = 2026-08-20T16:00Z。不是 JVM 默认时区、不是 new Date()。
        verify(compensationService).freezeTermsForSale(TOPIC_ID, 7L, Date.from(NOW), EXPECTED_EVIDENCE_DUE_AT,
                EXPECTED_FULFILLMENT_END_AT);
    }

    @Test
    void shopDaySaleWritesTheExecutingClubDistributionSlotBeforeTermsFreeze() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);

        service.finalizeForSale(TOPIC_ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(productRoleService, clubPayeeService,
                compensationService, topicMapper);
        order.verify(productRoleService).declareDistributionSlotForSale(TOPIC_ID, 7L, 9001L);
        order.verify(clubPayeeService).snapshotForSale(TOPIC_ID, 7L, Date.from(NOW));
        order.verify(compensationService).freezeTermsForSale(eq(TOPIC_ID), eq(7L), any(Date.class),
                any(Date.class), any(Date.class));
        order.verify(topicMapper).finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class));
    }

    /**
     * ★EXTRA 审批人<b>不再</b>从这一层传下去。
     *
     * <p>它是「本期项目负责人」，由 {@code freezeTermsForSale} 自己从当期成本版本上显式登记的那一列读，
     * 并在那里做可达校验。{@code ProductRole.declaredBy} 是「谁点了 ORGANIZER 槽那个按钮」的<b>审计</b>
     * 字段（COMMENT 明写「不参与主体归属判断」），换一个声明人不得改变本期的审批人。
     */
    @Test
    void theOrganizerSlotDeclarerIsNoLongerPromotedIntoTheExtraApprover() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        ProductRole organizer = platformOrganizer();
        organizer.setDeclaredBy(4242L);
        when(productRoleService.activeOrganizerSlot(TOPIC_ID)).thenReturn(organizer);

        service.finalizeForSale(TOPIC_ID);

        // 主办方角色槽仍是开售闸（读了它、断言了它），但它的 declaredBy 不再流进补偿条款。
        verify(productRoleService).activeOrganizerSlot(TOPIC_ID);
        verify(compensationService).freezeTermsForSale(eq(TOPIC_ID), eq(7L), any(Date.class), any(Date.class),
                any(Date.class));
    }

    /** 履约窗结束也必须冻进条款：EXTRA 的开工时刻上界与 FINAL 的「期已结束」事实门都要它。 */
    @Test
    void theEditionFulfillmentEndIsFrozenIntoTheTermsNotRecomputedLater() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);

        service.finalizeForSale(TOPIC_ID);

        verify(compensationService).freezeTermsForSale(eq(TOPIC_ID), eq(7L), any(Date.class), any(Date.class),
                eq(EXPECTED_FULFILLMENT_END_AT));
    }

    @Test
    void compensationTermsAndPayeeSnapshotAreFrozenInTheSameTransactionBeforeTheSellingCas() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);

        service.finalizeForSale(TOPIC_ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(clubPayeeService, compensationService, topicMapper);
        order.verify(clubPayeeService).snapshotForSale(TOPIC_ID, 7L, Date.from(NOW));
        order.verify(compensationService).freezeTermsForSale(eq(TOPIC_ID), eq(7L), any(Date.class), any(Date.class), any(Date.class));
        order.verify(topicMapper).finalizeForSale(eq(TOPIC_ID), any(Date.class), any(Date.class));
    }

    @Test
    void shopDayCannotEnterSellingWhenTheCompensationTermsFreezeFails() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(compensationService.freezeTermsForSale(eq(TOPIC_ID), eq(7L), any(Date.class), any(Date.class), any(Date.class)))
                .thenThrow(new IllegalStateException("EDITION_TERMS_WRITE_FAILED"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertEquals("EDITION_TERMS_WRITE_FAILED", error.getMessage());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    /** 缺活动结束事实 ⇒ 无法解析 T+7 ⇒ fail closed，绝不用 JVM now 凑一个截止时刻。 */
    @Test
    void aTopicWithoutAnEditionEndCannotSellBecauseTPlusSevenIsUnresolvable() {
        CmsTopic topic = pricingTopic(ProductType.FREE_EXPLORE);
        topic.setRecruitDeadline(Date.from(NOW.minusSeconds(1)));
        topic.setEndDate(null);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeForSale(TOPIC_ID));

        assertTrue(error.getMessage().contains("topic.endDate"),
                "必须点名缺的是履约窗结束字段，实际:" + error.getMessage());
        verify(compensationService, never()).freezeTermsForSale(any(), any(), any(), any(), any());
        verify(topicMapper, never()).finalizeForSale(any(Long.class), any(Date.class), any(Date.class));
    }

    /** 非探店日主题的开售路径零变化（D3 显式偏离）：不冻结条款、不写收款人快照。 */
    @Test
    void classicProductNeverFreezesShopDayCompensationTerms() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID))
                .thenReturn(pricingTopic(ProductType.ORIENTEERING));

        service.finalizeForSale(TOPIC_ID);

        verify(compensationService, never()).freezeTermsForSale(any(), any(), any(), any(), any());
        verify(clubPayeeService, never()).snapshotForSale(any(), any(), any());
    }

    private static CoopOrderLineupSource.ReadResult validGuidedLineup() {
        return new CoopOrderLineupSource.ReadResult(Collections.singletonList(organizer(7L)), Collections.emptyList());
    }

    private static CoopOrderLineupSource.LineupEntry organizer(Long subjectId) {
        return new CoopOrderLineupSource.LineupEntry("CLUB", subjectId, "ORGANIZER", "TOPIC", TOPIC_ID,
                "COOP_ORDER", subjectId + 100, false);
    }

    private static CoopOrderLineupSource.LineupEntry merchantOrganizer(Long subjectId, Long publisherId) {
        return new CoopOrderLineupSource.LineupEntry("CLUB", subjectId, "ORGANIZER", "TOPIC", TOPIC_ID,
                "COOP_ORDER", subjectId + 100, false, publisherId);
    }
}
