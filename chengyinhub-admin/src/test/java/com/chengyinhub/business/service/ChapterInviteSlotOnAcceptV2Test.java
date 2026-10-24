package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantChapterApplicationServiceImpl;
import com.chengyinhub.business.service.support.ChapterApplicationInviteNotice;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-34 / F-L3-02：主办方发出的邀请在商家接受前不占章节名额；
 * 接受时才在章节锁内做名额校验并预占，拒绝不受名额影响。
 */
class ChapterInviteSlotOnAcceptV2Test {

    private static final Long TOPIC = 41L;
    private static final Long CHAPTER = 73L;
    private static final Long APPLICATION = 91L;
    private static final Long PUBLISHER = 1001L;
    private static final Long MERCHANT = 2001L;
    private static final Long OTHER_MERCHANT = 2002L;
    private static final Integer CATEGORY = 18;

    private MerchantChapterApplicationMapper applicationMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private MmsMerchantMapper merchantMapper;
    private MerchantChapterOfferMapper offerMapper;
    private ChapterApplicationInviteNotice committedNotice;
    private MerchantChapterApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        committedNotice = mock(ChapterApplicationInviteNotice.class);
        ISysCategoryService categoryService = mock(ISysCategoryService.class);
        service = new MerchantChapterApplicationServiceImpl();
        ReflectionTestUtils.setField(service, "supplyReviewService", mock(TopicSupplyReviewService.class));
        ReflectionTestUtils.setField(service, "applicationMapper", applicationMapper);
        ReflectionTestUtils.setField(service, "inviteNotice", committedNotice);
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(com.chengyinhub.business.mapper.AuditTaskMapper.class));
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(service, "chapterMerchantNodeService", mock(IChapterMerchantNodeService.class));

        SysCategory category = new SysCategory();
        category.setId(CATEGORY.longValue());
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        when(categoryService.selectSysCategoryById(CATEGORY.longValue())).thenReturn(category);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        chapter.setRecruitEnabled(1);
        chapter.setCategoryId(CATEGORY);
        chapter.setMaxMerchant(1); // 只有一个名额
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(1);
        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86400000L));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);

        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(MERCHANT);
        merchant.setCategoryId(CATEGORY);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(merchant);
        when(applicationMapper.updateAudit(any(), any(), any(), any(), any())).thenReturn(1);
    }

    private MerchantChapterApplication approvedBy(Long merchantId) {
        MerchantChapterApplication row = new MerchantChapterApplication();
        row.setId(500L);
        row.setChapterId(CHAPTER);
        row.setMerchantId(merchantId);
        row.setStatus(MerchantChapterApplication.STATUS_APPROVED);
        return row;
    }

    private MerchantChapterApplication pendingInvite() {
        MerchantChapterApplication row = new MerchantChapterApplication();
        row.setId(APPLICATION);
        row.setTopicId(TOPIC);
        row.setChapterId(CHAPTER);
        row.setMerchantId(MERCHANT);
        row.setStatus(MerchantChapterApplication.STATUS_PENDING);
        row.setSource(MerchantChapterApplication.SOURCE_INVITE);
        row.setDelFlag(0);
        return row;
    }

    @Test
    void invitationIsPendingAndDoesNotNeedOrTakeASlot() {
        // 唯一名额已被别家占住：邀请仍可发出（待接受），不做名额校验
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.singletonList(approvedBy(OTHER_MERCHANT)));

        MerchantChapterApplication invite = service.invite(CHAPTER, PUBLISHER, MERCHANT, "来承接吧");

        ArgumentCaptor<MerchantChapterApplication> saved = ArgumentCaptor.forClass(MerchantChapterApplication.class);
        verify(applicationMapper).insertMerchantChapterApplication(saved.capture());
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_PENDING), saved.getValue().getStatus());
        assertEquals(Integer.valueOf(MerchantChapterApplication.SOURCE_INVITE), saved.getValue().getSource());
        assertEquals(invite, saved.getValue());
        verify(applicationMapper, never()).selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED);
        verify(offerMapper, never()).selectByChapter(any());
        verify(applicationMapper, never()).updateAudit(any(), any(), any(), any(), any());
    }

    @Test
    void pendingInvitationIsNotCountedAsReservedInStats() {
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_PENDING))
                .thenReturn(Collections.singletonList(pendingInvite()));
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.<MerchantChapterApplication>emptyList());

        MerchantChapterRecruitmentStats stats = service.getRecruitmentStats(CHAPTER, 1);

        assertEquals(1, stats.getPendingApplicationCount());
        assertEquals(0, stats.getReservedMerchantCount());
        assertEquals(1, stats.getRemainingMerchantCount());
    }

    @Test
    void acceptingWhenSlotTakenIsRefusedAndStateUnchanged() {
        MerchantChapterApplication invite = pendingInvite();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.singletonList(approvedBy(OTHER_MERCHANT)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.respondInvite(APPLICATION, MERCHANT, true));

        assertTrue(error.getMessage().contains("已满"), error.getMessage());
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_PENDING), invite.getStatus());
        verify(applicationMapper, never()).updateAudit(any(), any(), any(), any(), any());
        verify(committedNotice, never()).submitOutcome(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptingWithFreeSlotApprovesAndOnlyThenReserves() {
        MerchantChapterApplication invite = pendingInvite();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.<MerchantChapterApplication>emptyList());

        MerchantChapterApplication accepted = service.respondInvite(APPLICATION, MERCHANT, true);

        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED), accepted.getStatus());
        verify(applicationMapper).selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED);
        verify(applicationMapper).updateAudit(eq(APPLICATION), eq(MerchantChapterApplication.STATUS_APPROVED),
                eq("商家已接受邀请"), eq(MERCHANT), any());
    }

    @Test
    void decliningIsAllowedEvenWhenFullAndNeverChecksCapacity() {
        MerchantChapterApplication invite = pendingInvite();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.singletonList(approvedBy(OTHER_MERCHANT)));

        MerchantChapterApplication declined = service.respondInvite(APPLICATION, MERCHANT, false);

        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_REJECTED), declined.getStatus());
        verify(applicationMapper, never()).selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED);
        // 只有受邀商家本人能接受；主办方不能代为批准邀请
        MerchantChapterApplication other = pendingInvite();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(other);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(other);
        assertThrows(IllegalStateException.class, () -> service.audit(APPLICATION, PUBLISHER, true, "代批"));
        assertThrows(IllegalStateException.class, () -> service.respondInvite(APPLICATION, PUBLISHER, true));
    }
}
