package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantChapterOfferWithdrawTest {
    private static final Long OFFER = 9L, CHAPTER = 33L, TOPIC = 10L, MERCHANT = 500L;
    private MerchantChapterOfferMapper offers;
    private MerchantChapterApplicationMapper applications;
    private CmsTopicChapterMapper chapters;
    private CmsTopicMapper topics;
    private ICmsTopicService topicService;
    private ITopicSaleCanonicalMutationService canonical;
    private MerchantOfferServiceImpl service;
    private MerchantChapterOffer offer;
    private CmsTopicChapter chapter;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        offers = mock(MerchantChapterOfferMapper.class);
        applications = mock(MerchantChapterApplicationMapper.class);
        chapters = mock(CmsTopicChapterMapper.class);
        topics = mock(CmsTopicMapper.class);
        topicService = mock(ICmsTopicService.class);
        canonical = mock(ITopicSaleCanonicalMutationService.class);
        service = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(service, "offerMapper", offers);
        ReflectionTestUtils.setField(service, "applicationMapper", applications);
        ReflectionTestUtils.setField(service, "chapterMapper", chapters);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(service, "canonicalMutationService", canonical);
        offer = new MerchantChapterOffer();
        offer.setId(OFFER); offer.setTopicId(TOPIC); offer.setChapterId(CHAPTER);
        offer.setMerchantId(MERCHANT); offer.setStatus(1); offer.setTermsMode(TermsMode.REVSHARE);
        offer.setQuotaUsed(6); offer.setPerHeadFee(new BigDecimal("7.00"));
        chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER); chapter.setTopicId(TOPIC); chapter.setRequired(1);
        topic = new CmsTopic();
        topic.setId(TOPIC); topic.setDelFlag(0); topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(3); topic.setUserStatus(1);
        when(offers.selectById(OFFER)).thenReturn(offer);
        when(offers.selectByIdForUpdate(OFFER)).thenReturn(offer);
        when(chapters.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        when(chapters.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(topicService.casUserStatus(TOPIC, 1, 0)).thenReturn(1);
        when(offers.withdrawOwned(eq(OFFER), eq(MERCHANT), anyString(), anyBoolean())).thenReturn(1);
        when(applications.retireApprovedForSupplyExit(TOPIC, CHAPTER, MERCHANT)).thenReturn(1);
    }

    @Test
    void lockedRequiredChapterPausesBeforeExitAndKeepsFulfilledTerms() {
        assertTrue(service.withdrawOwnChapterOffer(OFFER, MERCHANT, "  暂停营业  "));
        InOrder order = inOrder(chapters, topics, offers, topicService, applications);
        order.verify(offers).selectById(OFFER);
        order.verify(chapters).lockChaptersForCapacity(Collections.singletonList(CHAPTER));
        order.verify(chapters).selectCmsTopicChapterById(CHAPTER);
        order.verify(topics).selectCmsTopicByIdForUpdate(TOPIC);
        order.verify(offers).selectByIdForUpdate(OFFER);
        order.verify(topicService).casUserStatus(TOPIC, 1, 0);
        order.verify(offers).withdrawOwned(OFFER, MERCHANT, "暂停营业", true);
        order.verify(applications).retireApprovedForSupplyExit(TOPIC, CHAPTER, MERCHANT);
        verify(canonical).invalidateApprovedCanonical(TOPIC, "ordinary-chapter-supply-withdraw");
        assertEquals(6, offer.getQuotaUsed());
        assertEquals(new BigDecimal("7.00"), offer.getPerHeadFee());
        assertEquals(TermsMode.REVSHARE, offer.getTermsMode());
    }

    @Test
    void optionalChapterExitDoesNotPauseTheRoute() {
        chapter.setRequired(0);
        assertFalse(service.withdrawOwnChapterOffer(OFFER, MERCHANT, "暂停营业"));
        verify(topicService, never()).casUserStatus(anyLong(), anyInt(), anyInt());
        verify(offers).withdrawOwned(OFFER, MERCHANT, "暂停营业", false);
        verify(applications).retireApprovedForSupplyExit(TOPIC, CHAPTER, MERCHANT);
    }

    @Test
    void anotherMerchantCannotExitTheOffer() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, 999L, "暂停营业"))
                .getMessage().contains("自己的"));
        verify(chapters, never()).lockChaptersForCapacity(anyList());
        verify(offers, never()).withdrawOwned(anyLong(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void changedScopeInTheLockedReadCannotExit() {
        MerchantChapterOffer changed = new MerchantChapterOffer();
        changed.setMerchantId(999L);
        when(offers.selectByIdForUpdate(OFFER)).thenReturn(changed);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT, "暂停营业"))
                .getMessage().contains("归属已变化"));
        verify(topicService, never()).casUserStatus(anyLong(), anyInt(), anyInt());
        verify(offers, never()).withdrawOwned(anyLong(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void repeatingTheSameExitKeepsTheFirstReasonAndDoesNotPauseAgain() {
        offer.setStatus(0); offer.setWithdrawReason("首次退出原因"); topic.setUserStatus(0);
        assertTrue(service.withdrawOwnChapterOffer(OFFER, MERCHANT, "后来重复提交"));
        verify(offers, never()).withdrawOwned(anyLong(), anyLong(), anyString(), anyBoolean());
        verify(applications, never()).retireApprovedForSupplyExit(anyLong(), anyLong(), anyLong());
        verify(topicService, never()).casUserStatus(anyLong(), anyInt(), anyInt());
        verifyNoInteractions(canonical);
        assertEquals("首次退出原因", offer.getWithdrawReason());
    }

    @Test
    void failedPauseCannotProceedToDeactivateOrReleaseTheSlot() {
        when(topicService.casUserStatus(TOPIC, 1, 0)).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT, "暂停营业"));
        verify(offers, never()).withdrawOwned(anyLong(), anyLong(), anyString(), anyBoolean());
        verify(applications, never()).retireApprovedForSupplyExit(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(canonical);
    }

    @Test
    void failedOfferUpdateCannotReleaseTheApprovalOrAdvanceTheCanonical() {
        when(offers.withdrawOwned(eq(OFFER), eq(MERCHANT), anyString(), anyBoolean())).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT, "暂停营业"));
        verify(applications, never()).retireApprovedForSupplyExit(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(canonical);
    }

    @Test
    void blankOrOverlongReasonIsRejectedBeforeReadingAnyOffer() {
        assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT, "  "));
        assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT,
                        String.join("", Collections.nCopies(501, "长"))));
        verifyNoInteractions(offers);
    }

    @Test
    void circleSupplyAndClosedHistoricalOfferStayOutsideThisEntry() {
        topic.setCircleThemeCode("AFTER_WORK");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT, "暂停营业"))
                .getMessage().contains("普通自由探索"));
        topic.setCircleThemeCode(null); topic.setLifecycle(6);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.withdrawOwnChapterOffer(OFFER, MERCHANT, "暂停营业"))
                .getMessage().contains("已结束"));
        verify(offers, never()).withdrawOwned(anyLong(), anyLong(), anyString(), anyBoolean());
    }
}
