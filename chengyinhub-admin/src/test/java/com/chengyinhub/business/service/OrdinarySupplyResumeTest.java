package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrdinarySupplyResumeTest {
    private final CmsTopicServiceImpl service = new CmsTopicServiceImpl();
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final MerchantChapterOfferMapper offers = mock(MerchantChapterOfferMapper.class);
    private final IUmsCreativeSquareService square = mock(IUmsCreativeSquareService.class);
    private CmsTopic topic;
    private CmsTopicChapter chapter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(service, "merchantChapterOfferMapper", offers);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", square);
        topic = new CmsTopic(); topic.setId(41L); topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(1); topic.setUserStatus(0);
        chapter = new CmsTopicChapter(); chapter.setId(73L); chapter.setRequired(1);
        when(topics.selectCmsTopicByIdForUpdate(41L)).thenReturn(topic);
        when(topics.lockTopicContentBudget(41L)).thenReturn(41L);
        when(chapters.countContentByTopicForUpdate(41L)).thenReturn(1L);
        when(nodes.countContentByTopicForUpdate(41L)).thenReturn(0L);
        when(topics.selectCmsTopicLifecycleById(41L)).thenReturn(topic);
        when(topics.casUserStatus(eq(41L), anyInt(), anyInt(), any())).thenReturn(1);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
    }

    private MerchantChapterOffer offer(long id, int status, Long source) {
        MerchantChapterOffer row = new MerchantChapterOffer();
        row.setId(id); row.setTopicId(41L); row.setChapterId(73L); row.setMerchantId(2000L + id);
        row.setTermsMode("TRAFFIC"); row.setStatus(status); row.setReplacementOfOfferId(source);
        if (status == 0) row.setWithdrawReason("停止营业");
        return row;
    }

    private void history(MerchantChapterOffer... rows) {
        when(offers.selectByTopicIncludingInactiveForUpdate(41L)).thenReturn(Arrays.asList(rows));
    }

    @Test
    void busyRedemptionNeverSkipsSupplyOrWaitsWithTheOrganizerAccountLock() {
        when(offers.selectByTopicIncludingInactiveForUpdate(41L)).thenThrow(new RuntimeException(
                new java.sql.SQLException("NOWAIT conflict", "HY000", 3572)));
        assertTrue(com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service).topicSupplyAutoOnlineBlockReason(41L).contains("稍后恢复"));
        assertTrue(assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1))
                .getMessage().contains("稍后恢复"));
        verify(topics, never()).casUserStatus(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void mandatoryExitSnapshotCannotBeErasedByMakingTheChapterOptional() {
        MerchantChapterOffer original = offer(801, 0, null); original.setWithdrawRequired(true);
        chapter.setRequired(0); history(original);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        history(original, offer(802, 1, 801L));
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
    }

    @Test
    void auditCannotAutomaticallyResumeEvenAfterReplacementWasConfirmed() {
        history(offer(801, 0, null), offer(802, 1, 801L));
        assertNotNull(com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service).topicSupplyAutoOnlineBlockReason(41L));
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        history();
        assertNull(com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service).topicSupplyAutoOnlineBlockReason(41L));
    }

    @Test
    void anInvitationOrAnotherUnlinkedLiveMerchantDoesNotPermitResume() {
        history(offer(801, 0, null), offer(802, 1, null));
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        verify(topics, never()).casUserStatus(anyLong(), anyInt(), anyInt(), any());
        verify(square, never()).syncTopicPostVisibility(anyLong(), anyBoolean());
    }

    @Test
    void actualConfirmedLiveReplacementAllowsExplicitHostResumeWithCurrentReads() {
        history(offer(801, 0, null), offer(802, 1, 801L));
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        InOrder order = inOrder(topics, offers, chapters);
        order.verify(topics).selectCmsTopicByIdForUpdate(41L);
        order.verify(offers).selectByTopicIncludingInactiveForUpdate(41L);
        order.verify(chapters).selectCmsTopicChapterList(any());
        order.verify(topics).casUserStatus(eq(41L), eq(0), eq(1), any());
        verify(chapters, never()).lockChaptersForCapacity(anyList());
        verify(square).syncTopicPostVisibility(41L, true);
    }

    @Test
    void expiredInactiveAndAlteredTermsReplacementCannotRestoreSales() {
        MerchantChapterOffer next = offer(802, 1, 801L); next.setValidEnd(new Date(0));
        history(offer(801, 0, null), next);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        next.setValidEnd(null); next.setStatus(0);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        next.setStatus(1); next.setMaxNodeXp(99);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        verify(topics, never()).casUserStatus(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void repeatExitPreservesAtoBtoCAndNeedsTheLastLiveReplacement() {
        history(offer(801, 0, null), offer(802, 0, 801L), offer(803, 1, 802L));
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        history(offer(801, 0, null), offer(802, 0, 801L));
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
    }

    @Test
    void genericUpdateCannotBypassTheResumeGate() {
        history(offer(801, 0, null));
        CmsTopic patch = new CmsTopic(); patch.setId(41L); patch.setUserStatus(1);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(service).updateCmsTopic(patch));
        verify(topics, never()).updateCmsTopic(any());
    }

    @Test
    void optionalExitAndEmptyRecruitmentStillAllowPublishing() {
        chapter.setRequired(0); history(offer(801, 0, null));
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        chapter.setRequired(1); history();
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
    }

    @Test
    void pauseDoesNotIntroduceTopicOfferOrChapterLocks() {
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 1, 0));
        verify(topics, never()).selectCmsTopicByIdForUpdate(anyLong());
        verifyNoInteractions(offers, chapters, nodes);
    }

    @Test
    void perkReplacementMustCarryOriginalUnusedCapacityAndValidity() {
        MerchantChapterOffer original = offer(801, 0, null); original.setTermsMode("PERK");
        original.setQuotaTotal(10); original.setQuotaUsed(3); original.setValidEnd(new Date(System.currentTimeMillis()+600000));
        MerchantChapterOffer next = offer(802, 1, 801L); next.setTermsMode("PERK"); next.setQuotaTotal(6);
        next.setValidEnd(original.getValidEnd()); history(original, next);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1));
        next.setQuotaTotal(7); next.setQuotaUsed(2);
        assertEquals(1, com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service).casUserStatus(41L, 0, 1), "补位后正常履约不应再次要求补满已履约份数");
    }
}
