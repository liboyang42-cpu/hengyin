package com.chengyinhub.business.service.f10;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.vo.TopicSaleReviewProgress;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.ITopicSaleCanonicalDraftService;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.impl.TopicAuthorSaleServiceImpl;
import com.chengyinhub.business.service.support.FreeExploreCanonicalDefaults;
import com.chengyinhub.business.service.support.FreeExploreSlotProgress;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** F10 §3.4 作者路径:填价 → 一个事务内锁价、同步场次票价、生成 canonical、开售。 */
class TopicAuthorSaleServiceTest {

    private static final long AUTHOR = 7001L;
    private static final long TOPIC = 81L;

    private CmsTopicMapper topicMapper;
    private OmsTicketMapper ticketMapper;
    private CmsActivityMapper activityMapper;
    private AuditTaskMapper auditTaskMapper;
    private BusinessAuditEventMapper auditEventMapper;
    private IPricingService pricing;
    private IOmsTicketService omsTicketService;
    private ITopicSaleCanonicalDraftService draftService;
    private IAuditTaskService auditTaskService;
    private ITopicSaleFinalizationService finalization;
    private FreeExploreSlotProgress slots;
    private ICoopInviteService invites;
    private TopicAuthorSaleServiceImpl service;
    private CmsTopic topic;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        auditTaskMapper = mock(AuditTaskMapper.class);
        auditEventMapper = mock(BusinessAuditEventMapper.class);
        pricing = mock(IPricingService.class);
        omsTicketService = mock(IOmsTicketService.class);
        draftService = mock(ITopicSaleCanonicalDraftService.class);
        auditTaskService = mock(IAuditTaskService.class);
        finalization = mock(ITopicSaleFinalizationService.class);
        slots = mock(FreeExploreSlotProgress.class);
        invites = mock(ICoopInviteService.class);
        ObjectProvider<ICoopInviteService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(invites);
        service = new TopicAuthorSaleServiceImpl(topicMapper, ticketMapper, activityMapper, auditTaskMapper,
                auditEventMapper, pricing, omsTicketService, draftService, auditTaskService, finalization,
                slots, provider);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(AUTHOR);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        topic.setDelFlag(0);
        topic.setStartDate(new Date(1_800_000_000_000L));
        topic.setEndDate(new Date(1_800_086_400_000L));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);

        OmsTicket t1 = ticket(11L, 100L);
        OmsTicket t2 = ticket(12L, 50L);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Arrays.asList(t1, t2));
        CmsActivity s1 = new CmsActivity();
        s1.setId(31L);
        s1.setMemberId(AUTHOR);
        CmsActivity s2 = new CmsActivity();
        s2.setId(32L);
        s2.setMemberId(9002L);
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class))).thenReturn(Arrays.asList(s1, s2));
        when(auditTaskMapper.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.emptyList());
        TopicSaleCanonicalDraft saved = new TopicSaleCanonicalDraft();
        saved.setSaleReviewProgress(new TopicSaleReviewProgress(501L, "已提交开售审核"));
        when(draftService.save(eq(TOPIC), eq(AUTHOR), any(TopicSaleCanonicalDraftDTO.class))).thenReturn(saved);
        when(finalization.finalizeForSale(TOPIC)).thenReturn(new Date(1_799_000_000_000L));
        when(auditEventMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(slots.slots(TOPIC)).thenReturn(new FreeExploreSlotProgress.Slots(3, 0));
    }

    private static OmsTicket ticket(long id, long inventory) {
        OmsTicket t = new OmsTicket();
        t.setId(id);
        t.setTotalInventory(inventory);
        return t;
    }

    private static Map<Long, BigDecimal> prices() {
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        prices.put(11L, new BigDecimal("39.00"));
        prices.put(12L, new BigDecimal("59.00"));
        return prices;
    }

    @Test
    void publishLocksPriceSyncsEverySessionBuildsCanonicalAndOpensSale() {
        long at = service.publishForSale(TOPIC, AUTHOR, prices());

        assertEquals(1_799_000_000_000L, at);
        InOrder order = inOrder(pricing, omsTicketService, draftService, auditTaskService, finalization, invites);
        order.verify(pricing).confirmPrice(TOPIC, null, null, null, null, null, prices());
        order.verify(omsTicketService).syncTopicTicketsToActivity(TOPIC, 31L, AUTHOR);
        order.verify(omsTicketService).syncTopicTicketsToActivity(TOPIC, 32L, 9002L);
        order.verify(draftService).save(eq(TOPIC), eq(AUTHOR), any(TopicSaleCanonicalDraftDTO.class));
        order.verify(auditTaskService).approveSaleReviewForAuthor(501L, TOPIC, AUTHOR);
        order.verify(finalization).finalizeForSale(TOPIC);
        order.verify(invites).markTopicDepositsDue(TOPIC);

        ArgumentCaptor<TopicSaleCanonicalDraftDTO> draft = ArgumentCaptor.forClass(TopicSaleCanonicalDraftDTO.class);
        verify(draftService).save(eq(TOPIC), eq(AUTHOR), draft.capture());
        assertEquals(0L, draft.getValue().getExpectedParameterVersion());
        assertEquals(FreeExploreCanonicalDefaults.EVIDENCE_STORE_REDEEM,
                draft.getValue().getCanonical().getCompletionPolicy().path("evidenceRule").asText());
        assertEquals(150, draft.getValue().getCanonical().getSessionVenues().path("adultCapacity").asInt());

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditEventMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("AUTHOR_FE_PUBLISH_SALE", audit.getValue().getAction());
        assertEquals(AUTHOR, audit.getValue().getActorMemberId());
        assertTrue(audit.getValue().getAfterJson().contains("\"11\":\"39.00\""));
    }

    @Test
    void openSlotsRejectPublish() {
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        when(slots.tryAdvanceToPricing(TOPIC)).thenReturn(false);
        when(slots.slots(TOPIC)).thenReturn(new FreeExploreSlotProgress.Slots(3, 2));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.publishForSale(TOPIC, AUTHOR, prices()));
        assertTrue(e.getMessage().contains("还有 2 个位置待承接"));
        verifyNoInteractions(pricing, draftService, finalization);
    }

    @Test
    void nonAuthorIsRejected() {
        assertThrows(IllegalStateException.class, () -> service.publishForSale(TOPIC, AUTHOR + 1, prices()));
        verifyNoInteractions(pricing, finalization);
    }

    @Test
    void recruitingTopicCannotFillPriceEvenIfCalledDirectly() {
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        when(slots.slots(TOPIC)).thenReturn(new FreeExploreSlotProgress.Slots(1, 1));
        assertThrows(IllegalStateException.class, () -> service.publishForSale(TOPIC, AUTHOR, prices()));
        verify(pricing, never()).confirmPrice(anyLong(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void ticketSetMustMatchExactly() {
        Map<Long, BigDecimal> partial = new LinkedHashMap<>();
        partial.put(11L, new BigDecimal("39"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.publishForSale(TOPIC, AUTHOR, partial));
        assertTrue(e.getMessage().contains("票种已变化"));
        verifyNoInteractions(pricing);
    }

    @Test
    void legacyLockedOrOpsReviewedTopicsKeepOldPath() {
        topic.setFeePlatformRate(new BigDecimal("0.1"));
        assertThrows(IllegalStateException.class, () -> service.publishForSale(TOPIC, AUTHOR, prices()));
        topic.setFeePlatformRate(null);
        AuditTask pending = new AuditTask();
        pending.setStatus(AuditTask.STATUS_PENDING);
        when(auditTaskMapper.selectAuditTaskList(any(AuditTask.class))).thenReturn(Collections.singletonList(pending));
        assertThrows(IllegalStateException.class, () -> service.publishForSale(TOPIC, AUTHOR, prices()));
        verifyNoInteractions(pricing, finalization);
    }

    @Test
    void negativeOrMissingPriceRejected() {
        Map<Long, BigDecimal> bad = prices();
        bad.put(12L, new BigDecimal("-1"));
        assertThrows(IllegalStateException.class, () -> service.publishForSale(TOPIC, AUTHOR, bad));
        assertThrows(IllegalStateException.class, () -> service.publishForSale(TOPIC, AUTHOR, Collections.emptyMap()));
    }

    @Test
    void freeTicketsAreAllowed() {
        Map<Long, BigDecimal> free = new LinkedHashMap<>();
        free.put(11L, BigDecimal.ZERO);
        free.put(12L, BigDecimal.ZERO);
        service.publishForSale(TOPIC, AUTHOR, free);
        verify(finalization).finalizeForSale(TOPIC);
    }

    @Test
    void readinessReportsSlotsAndWhetherPriceCanBeFilled() {
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        when(slots.slots(TOPIC)).thenReturn(new FreeExploreSlotProgress.Slots(4, 1));
        var r = service.readiness(TOPIC, AUTHOR);
        assertEquals(4, r.getTotalSlots());
        assertEquals(3, r.getFilledSlots());
        assertEquals(1, r.getOpenSlots());
        assertFalse(r.isCanFillPrice());
        when(slots.slots(TOPIC)).thenReturn(new FreeExploreSlotProgress.Slots(4, 0));
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        assertTrue(service.readiness(TOPIC, AUTHOR).isCanPublish());
        assertThrows(IllegalStateException.class, () -> service.readiness(TOPIC, AUTHOR + 1));
    }
}
