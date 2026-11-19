package com.chengyinhub.business.service.f10;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.support.FreeExploreSlotProgress;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** F10 §3.3:最后一个位置被承接时自动进入待定价;并发只进一次;开售前出现空位回到招募中。 */
class TopicLifecycleAutoAdvanceTest {

    private CmsTopicMapper topicMapper;
    private MerchantChapterOfferMapper offerMapper;
    private ITopicLifecycleService lifecycle;
    private FreeExploreSlotProgress progress;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        lifecycle = mock(ITopicLifecycleService.class);
        progress = new FreeExploreSlotProgress(topicMapper, offerMapper, lifecycle);
        topic = new CmsTopic();
        topic.setId(5L);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(TopicLifecycleState.RECRUITING.getCode());
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(5L)).thenReturn(topic);
        when(offerMapper.countRequiredChapters(5L)).thenReturn(3);
    }

    @Test
    void lastSlotFilledAdvancesToPricing() {
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(0);
        when(lifecycle.transition(5L, TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING)).thenReturn(true);
        assertTrue(progress.tryAdvanceToPricing(5L));
    }

    @Test
    void openSlotKeepsRecruiting() {
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(1);
        assertFalse(progress.tryAdvanceToPricing(5L));
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        FreeExploreSlotProgress.Slots slots = progress.slots(5L);
        assertEquals(3, slots.getTotal());
        assertEquals(2, slots.getFilled());
        assertEquals(1, slots.getOpen());
    }

    @Test
    void concurrentLastSlotAdvancesOnlyOnce() {
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(0);
        // CAS:第一个请求迁移成功,第二个拿到 0 行 ⇒ false,不报错、不重复迁移。
        when(lifecycle.transition(5L, TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING))
                .thenReturn(true).thenReturn(false);
        assertTrue(progress.tryAdvanceToPricing(5L));
        assertFalse(progress.tryAdvanceToPricing(5L));
        verify(lifecycle, times(2)).transition(5L, TopicLifecycleState.RECRUITING, TopicLifecycleState.PRICING);
    }

    @Test
    void nonFreeExploreOrOtherStatesAreIgnored() {
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(0);
        topic.setProductType(ProductType.ORIENTEERING);
        assertFalse(progress.tryAdvanceToPricing(5L));
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        assertFalse(progress.tryAdvanceToPricing(5L));
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void slotReopenedBeforeSaleGoesBackToRecruiting() {
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(1);
        when(lifecycle.transition(5L, TopicLifecycleState.PRICING, TopicLifecycleState.RECRUITING)).thenReturn(true);
        assertTrue(progress.reopenRecruitingIfSlotOpen(5L));
    }

    @Test
    void priceLockedLegacyTopicDoesNotReopen() {
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        topic.setFeePlatformRate(new BigDecimal("0.10"));
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(1);
        assertFalse(progress.reopenRecruitingIfSlotOpen(5L));
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void allFilledPricingTopicStaysPricing() {
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(5L)).thenReturn(0);
        assertFalse(progress.reopenRecruitingIfSlotOpen(5L));
    }
}
