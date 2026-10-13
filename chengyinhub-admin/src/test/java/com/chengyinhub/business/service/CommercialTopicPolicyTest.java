package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommercialTopicPolicyTest {

    private static final Long TOPIC_ID = 41L;
    private OmsTicketMapper ticketMapper;
    private CmsTopicChapterMapper chapterMapper;
    private MerchantChapterOfferMapper offerMapper;
    private CmsTopicMapper topicMapper;
    private CommercialTopicPolicy policy;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        policy = new CommercialTopicPolicy(ticketMapper, chapterMapper, offerMapper, topicMapper);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.emptyList());
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.emptyList());
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.emptyList());
    }

    @Test
    void pureUgcWithoutAnyCommercialFactBypassesReview() {
        assertFalse(policy.evaluate(TOPIC_ID).isCommercial());
    }

    @Test
    void paidTicketAloneMakesTopicCommercial() {
        OmsTicket paid = new OmsTicket();
        paid.setId(11L);
        paid.setPrice(new BigDecimal("0.01"));
        paid.setDelFlag(0);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(paid));

        CommercialTopicPolicy.Evaluation result = policy.evaluate(TOPIC_ID);

        assertTrue(result.isCommercial());
        assertTrue(result.isPaidTicket());
    }

    @Test
    void paidSelfPlayPassAloneMakesTopicCommercial() {
        CmsTopic topic = new CmsTopic();
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("99"));
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        assertTrue(policy.evaluate(TOPIC_ID).isCommercial());
    }

    @Test
    void freeExploreProductTypeRequiresProjectReviewBeforeTicketsOffersOrRecruitmentExist() {
        CmsTopic topic = new CmsTopic();
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CommercialTopicPolicy.Evaluation result = policy.evaluate(TOPIC_ID);

        assertTrue(result.isCommercial());
        assertTrue((Boolean) result.toSnapshotFacts().get("freeExplore"));
    }

    @Test
    void recruitmentAloneMakesTopicCommercial() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(12L);
        chapter.setRecruitEnabled(1);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));

        CommercialTopicPolicy.Evaluation result = policy.evaluate(TOPIC_ID);

        assertTrue(result.isCommercial());
        assertTrue(result.isRecruitmentOpen());
    }

    @Test
    void liveMerchantSupplyAloneMakesTopicCommercial() {
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setId(13L);
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(offer));

        CommercialTopicPolicy.Evaluation result = policy.evaluate(TOPIC_ID);

        assertTrue(result.isCommercial());
        assertTrue(result.isMerchantSupply());
    }
}
