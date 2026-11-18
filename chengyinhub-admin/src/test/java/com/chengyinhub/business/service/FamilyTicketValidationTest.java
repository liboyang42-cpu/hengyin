package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.TopicSaleFinalizationServiceImpl;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.FamilyTicketRules;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F12 亲子票:只允许 1+0 / 1+1;亲子票年龄段必填;有承接方没接受亲子票时不能开售。 */
class FamilyTicketValidationTest {
    private static final Long TOPIC_ID = 71L;

    private CmsTopicChapterMapper chapterMapper;
    private TopicSaleFinalizationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
        service = new TopicSaleFinalizationServiceImpl(mock(CmsTopicMapper.class), mock(OmsTicketMapper.class),
                chapterMapper, mock(MerchantChapterOfferMapper.class), mock(CoopOrderLineupSource.class), provider,
                mock(CommercialTopicPolicy.class), mock(CommercialReviewStatusSource.class),
                mock(MerchantPublishedTopicTransferGate.class));
        when(chapterMapper.selectRequiredByTopic(TOPIC_ID)).thenReturn(Arrays.asList(chapter(1L, "示例站点一"), chapter(2L, "示例站点二")));
    }

    @Test
    void onlyOnePlusZeroAndOnePlusOneAreAllowed() {
        assertDoesNotThrow(() -> FamilyTicketRules.validateComposition(1, 0, null, null));
        assertDoesNotThrow(() -> FamilyTicketRules.validateComposition(null, null, null, null));
        assertDoesNotThrow(() -> FamilyTicketRules.validateComposition(1, 1, 3, 12));
        for (int[] bad : new int[][]{{2, 1}, {1, 2}, {0, 1}, {2, 0}}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> FamilyTicketRules.validateComposition(bad[0], bad[1], 3, 12));
            assertEquals("亲子票限 1 大 1 小", error.getMessage());
        }
    }

    @Test
    void familyTicketRequiresAValidAgeBand() {
        assertThrows(IllegalArgumentException.class, () -> FamilyTicketRules.validateComposition(1, 1, null, 12));
        assertThrows(IllegalArgumentException.class, () -> FamilyTicketRules.validateComposition(1, 1, 3, null));
        assertThrows(IllegalArgumentException.class, () -> FamilyTicketRules.validateComposition(1, 1, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> FamilyTicketRules.validateComposition(1, 1, -1, 12));
        assertThrows(IllegalArgumentException.class, () -> FamilyTicketRules.validateComposition(1, 1, 3, 18));
        // 普通票不带年龄段,避免把普通票误显示成亲子票
        assertThrows(IllegalArgumentException.class, () -> FamilyTicketRules.validateComposition(1, 0, 3, 12));
    }

    @Test
    void publishIsRejectedWhileAnyRequiredPositionDoesNotAcceptFamilyTickets() {
        List<MerchantChapterOffer> offers = Arrays.asList(offer(1L, 1, "示例店甲"), offer(2L, 0, "示例店乙"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gate(Collections.singletonList(familyTicket()), offers));
        assertTrue(error.getMessage().startsWith("示例店乙还没接受亲子票"), error.getMessage());
    }

    @Test
    void publishPassesWhenEveryPositionAcceptsOrThereIsNoFamilyTicket() {
        List<MerchantChapterOffer> allAccept = Arrays.asList(offer(1L, 1, "示例店甲"), offer(2L, 1, "示例店乙"));
        assertDoesNotThrow(() -> gate(Arrays.asList(plainTicket(), familyTicket()), allAccept));
        List<MerchantChapterOffer> noneAccept = Arrays.asList(offer(1L, 0, "示例店甲"), offer(2L, 0, "示例店乙"));
        assertDoesNotThrow(() -> gate(Collections.singletonList(plainTicket()), noneAccept));
    }

    @Test
    void missingMerchantNameStillNamesTheChapter() {
        List<MerchantChapterOffer> offers = Arrays.asList(offer(1L, 1, null), offer(2L, null, null));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gate(Collections.singletonList(familyTicket()), offers));
        assertTrue(error.getMessage().startsWith("「示例站点二」的承接店铺还没接受亲子票"), error.getMessage());
    }

    @Test
    void canonicalRejectsChildCountOtherThanZeroOrOne() {
        TopicSaleCanonicalV1 ok = TopicSaleCanonicalTestFixture.canonical();
        ((ObjectNode) ok.getParticipationUnitPolicy()).put("childCount", 1);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(ok, "validateParticipationUnit"));
        TopicSaleCanonicalV1 bad = TopicSaleCanonicalTestFixture.canonical();
        bad.getParticipationUnitPolicy().put("childCount", 2);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(bad, "validateParticipationUnit"));
        assertEquals("SALE_SNAPSHOT_INVALID_FAMILY_COMPOSITION", error.getMessage());
    }

    private void gate(List<OmsTicket> tickets, List<MerchantChapterOffer> offers) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setProductType(ProductType.FREE_EXPLORE);
        ReflectionTestUtils.invokeMethod(service, "assertFamilyTicketAccepted", topic, tickets, offers);
    }

    private static CmsTopicChapter chapter(Long id, String name) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(id);
        chapter.setName(name);
        return chapter;
    }

    private static MerchantChapterOffer offer(Long chapterId, Integer familyAccepted, String merchantName) {
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(chapterId);
        offer.setTopicId(TOPIC_ID);
        offer.setFamilyAccepted(familyAccepted);
        offer.setMerchantName(merchantName);
        return offer;
    }

    private static OmsTicket familyTicket() {
        OmsTicket ticket = plainTicket();
        ticket.setName("亲子票（1 大 1 小）");
        ticket.setChildCount(1);
        ticket.setChildAgeMin(3);
        ticket.setChildAgeMax(12);
        return ticket;
    }

    private static OmsTicket plainTicket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setName("单人票");
        ticket.setAdultCount(1);
        ticket.setChildCount(0);
        return ticket;
    }
}
