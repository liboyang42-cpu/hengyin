package com.chengyinhub.business.service.f10;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.business.service.support.FreeExploreSlotProgress;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** F10 §3.2 作者自己承接:建 AUTHOR offer、归属校验、唯一约束、撤回回到招募中。 */
class ChapterSelfSupplyTest {

    private static final long AUTHOR = 7001L;
    private static final long TOPIC = 81L;
    private static final long CHAPTER = 82L;

    private MerchantOfferServiceImpl service;
    private MerchantChapterOfferMapper offerMapper;
    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CoopPerkMapper perkMapper;
    private IChapterMerchantNodeService nodeService;
    private FreeExploreSlotProgress slotProgress;
    private ITopicSaleCanonicalMutationService canonicalMutation;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        service = new MerchantOfferServiceImpl();
        offerMapper = mock(MerchantChapterOfferMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        perkMapper = mock(CoopPerkMapper.class);
        nodeService = mock(IChapterMerchantNodeService.class);
        slotProgress = mock(FreeExploreSlotProgress.class);
        canonicalMutation = mock(ITopicSaleCanonicalMutationService.class);
        ReflectionTestUtils.setField(service, "supplyReviewService", mock(TopicSupplyReviewService.class));
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "coopPerkMapper", perkMapper);
        ReflectionTestUtils.setField(service, "chapterMerchantNodeService", nodeService);
        ReflectionTestUtils.setField(service, "slotProgress", slotProgress);
        ReflectionTestUtils.setField(service, "canonicalMutationService", canonicalMutation);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(AUTHOR);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(1);
        topic.setDelFlag(0);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        chapter.setMaxMerchant(3);
        when(chapterMapper.lockChaptersForCapacity(anyList())).thenReturn(Collections.singletonList(CHAPTER));
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.emptyList());
        doAnswer(call -> { ((CoopPerk) call.getArgument(0)).setId(91L); return 1; })
                .when(perkMapper).insertCoopPerk(any(CoopPerk.class));
        doAnswer(call -> { ((MerchantChapterOffer) call.getArgument(0)).setId(92L); return 1; })
                .when(offerMapper).insertMerchantChapterOffer(any(MerchantChapterOffer.class));
    }

    private static IMerchantOfferService.AuthorSelfSupplyPerk perk() {
        IMerchantOfferService.AuthorSelfSupplyPerk p = new IMerchantOfferService.AuthorSelfSupplyPerk();
        p.setName("示例权益");
        p.setRedeemRule("8 折，到店出示");
        p.setCapacity(30);
        p.setValidEnd(new Date(System.currentTimeMillis() + 30L * 86400_000L));
        return p;
    }

    @Test
    void selfSupplyCreatesAuthorPerkOfferAndTriesToAdvance() {
        Long offerId = service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, perk());

        assertEquals(92L, offerId);
        ArgumentCaptor<MerchantChapterOffer> offer = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).insertMerchantChapterOffer(offer.capture());
        MerchantChapterOffer saved = offer.getValue();
        assertEquals(MerchantChapterOffer.SUPPLIER_AUTHOR, saved.getSupplierKind());
        assertEquals(AUTHOR, saved.getSupplierMemberId());
        assertEquals(AUTHOR, saved.getMerchantId(), "AUTHOR 行 merchant_id = 作者 member_id,唯一约束挡重复承接");
        assertEquals("PERK", saved.getTermsMode());
        assertEquals(91L, saved.getPerkId());
        assertEquals(30, saved.getQuotaTotal());
        assertEquals(1, saved.getStatus());
        verify(nodeService).bindAuthorSelfSupplyNode(AUTHOR, TOPIC, CHAPTER);
        verify(canonicalMutation).invalidateApprovedCanonical(TOPIC, "author-self-supply");
        verify(slotProgress).tryAdvanceToPricing(TOPIC);
    }

    @Test
    void nonAuthorIsRejectedBeforeAnyWrite() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.authorSelfSupply(AUTHOR + 1, TOPIC, CHAPTER, perk()));
        assertTrue(e.getMessage().contains("只有主题作者"));
        verifyNoInteractions(perkMapper, nodeService, slotProgress);
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void sellingTopicCannotBeSelfSupplied() {
        topic.setLifecycle(3);
        assertThrows(IllegalStateException.class, () -> service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, perk()));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void nonFreeExploreTopicIsRejected() {
        topic.setProductType(ProductType.ORIENTEERING);
        assertThrows(IllegalStateException.class, () -> service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, perk()));
    }

    @Test
    void duplicateSupplyOnSameChapterHitsUniqueKeyMessage() {
        doThrow(new DuplicateKeyException("uk_offer_merchant_chapter"))
                .when(offerMapper).insertMerchantChapterOffer(any(MerchantChapterOffer.class));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, perk()));
        assertTrue(e.getMessage().contains("已承接本章节"));
        verify(slotProgress, never()).tryAdvanceToPricing(anyLong());
    }

    @Test
    void perkCardShapeIsValidated() {
        IMerchantOfferService.AuthorSelfSupplyPerk p = perk();
        p.setCapacity(0);
        assertThrows(IllegalStateException.class, () -> service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, p));
        IMerchantOfferService.AuthorSelfSupplyPerk q = perk();
        q.setName(" ");
        assertThrows(IllegalStateException.class, () -> service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, q));
        IMerchantOfferService.AuthorSelfSupplyPerk r = perk();
        r.setValidEnd(null);
        assertThrows(IllegalStateException.class, () -> service.authorSelfSupply(AUTHOR, TOPIC, CHAPTER, r));
    }

    private MerchantChapterOffer authorOffer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(92L);
        o.setTopicId(TOPIC);
        o.setChapterId(CHAPTER);
        o.setMerchantId(AUTHOR);
        o.setSupplierKind(MerchantChapterOffer.SUPPLIER_AUTHOR);
        o.setSupplierMemberId(AUTHOR);
        o.setStatus(1);
        return o;
    }

    @Test
    void withdrawDeactivatesReleasesNodeAndReopensRecruiting() {
        topic.setLifecycle(2);
        when(offerMapper.selectById(92L)).thenReturn(authorOffer());
        when(offerMapper.selectByIdForUpdate(92L)).thenReturn(authorOffer());
        when(offerMapper.deactivate(92L)).thenReturn(1);

        service.withdrawAuthorSelfSupply(AUTHOR, TOPIC, 92L);

        verify(offerMapper).deactivate(92L);
        verify(nodeService).releaseAuthorSelfSupplyNode(AUTHOR, TOPIC, CHAPTER);
        verify(slotProgress).reopenRecruitingIfSlotOpen(TOPIC);
    }

    @Test
    void withdrawRejectsMerchantRowsAndOtherMembers() {
        MerchantChapterOffer merchantRow = authorOffer();
        merchantRow.setSupplierKind(MerchantChapterOffer.SUPPLIER_MERCHANT);
        merchantRow.setSupplierMemberId(null);
        when(offerMapper.selectById(92L)).thenReturn(merchantRow);
        when(offerMapper.selectByIdForUpdate(92L)).thenReturn(merchantRow);
        assertThrows(IllegalStateException.class, () -> service.withdrawAuthorSelfSupply(AUTHOR, TOPIC, 92L));
        assertThrows(IllegalStateException.class, () -> service.withdrawAuthorSelfSupply(AUTHOR + 1, TOPIC, 92L));
        verify(offerMapper, never()).deactivate(anyLong());
    }

    @Test
    void withdrawAfterSaleStartedIsRejected() {
        topic.setLifecycle(3);
        when(offerMapper.selectById(92L)).thenReturn(authorOffer());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.withdrawAuthorSelfSupply(AUTHOR, TOPIC, 92L));
        assertTrue(e.getMessage().contains("已开售"));
        verify(offerMapper, never()).deactivate(anyLong());
    }
}
