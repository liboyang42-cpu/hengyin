package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantChapterApplicationServiceImpl;
import com.chengyinhub.business.service.support.ChapterApplicationInviteNotice;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-35 / F-L3-02 · F-R2F-05：章节承接的拒绝与撤回都要发通知给对方——
 * 主办驳回申请(带原因)→商家；商家拒绝邀请→主办；主办撤回待接受邀请→商家；商家撤回自己的申请→主办。
 */
class ChapterRejectRevokeNoticeV2Test {

    private static final Long TOPIC = 41L;
    private static final Long CHAPTER = 73L;
    private static final Long APPLICATION = 91L;
    private static final Long PUBLISHER = 1001L;
    private static final Long MERCHANT = 2001L;

    private MerchantChapterApplicationMapper applicationMapper;
    private ChapterApplicationInviteNotice committedNotice;
    private MerchantChapterApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        committedNotice = mock(ChapterApplicationInviteNotice.class);
        service = new MerchantChapterApplicationServiceImpl();
        ReflectionTestUtils.setField(service, "supplyReviewService", mock(TopicSupplyReviewService.class));
        ReflectionTestUtils.setField(service, "applicationMapper", applicationMapper);
        ReflectionTestUtils.setField(service, "inviteNotice", committedNotice);
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(com.chengyinhub.business.mapper.AuditTaskMapper.class));
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service, "offerMapper", mock(MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(service, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(service, "chapterMerchantNodeService", mock(IChapterMerchantNodeService.class));

        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        chapter.setRecruitEnabled(1);
        chapter.setMaxMerchant(2);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(1);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(applicationMapper.updateAudit(any(), any(), any(), any(), any())).thenReturn(1);
    }

    private MerchantChapterApplication pending(int source) {
        MerchantChapterApplication row = new MerchantChapterApplication();
        row.setId(APPLICATION);
        row.setTopicId(TOPIC);
        row.setChapterId(CHAPTER);
        row.setMerchantId(MERCHANT);
        row.setStatus(MerchantChapterApplication.STATUS_PENDING);
        row.setSource(source);
        row.setDelFlag(0);
        return row;
    }

    @Test
    void publisherRejectionNotifiesMerchantWithReason() {
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION))
                .thenReturn(pending(MerchantChapterApplication.SOURCE_APPLY));
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(pending(MerchantChapterApplication.SOURCE_APPLY));

        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_REJECTED),
                service.audit(APPLICATION, PUBLISHER, false, "  营业时间不符  ").getStatus());

        verify(committedNotice, times(1)).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.REVIEW), eq(MERCHANT), eq("章节申请审核结果"), eq("章节申请未通过：营业时间不符"));
    }

    @Test
    void merchantDecliningInvitationNotifiesPublisher() {
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION))
                .thenReturn(pending(MerchantChapterApplication.SOURCE_INVITE));
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(pending(MerchantChapterApplication.SOURCE_INVITE));

        MerchantChapterApplication declined = service.respondInvite(APPLICATION, MERCHANT, false);

        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_REJECTED), declined.getStatus());
        verify(applicationMapper).updateAudit(eq(APPLICATION), eq(MerchantChapterApplication.STATUS_REJECTED),
                eq("商家已拒绝邀请"), eq(MERCHANT), any());
        verify(committedNotice, times(1)).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.INVITE_DECISION), eq(PUBLISHER), eq("章节邀请处理结果"), eq("商家已拒绝章节邀请"));
        verify(committedNotice, never()).submitOutcome(any(), any(), any(), any(), eq(MERCHANT), any(), any());
    }

    @Test
    void publisherRevokingPendingInvitationNotifiesMerchantOnce() {
        MerchantChapterApplication invite = pending(MerchantChapterApplication.SOURCE_INVITE);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invite);
        when(applicationMapper.withdrawPending(APPLICATION, MERCHANT)).thenReturn(1);

        assertEquals(Integer.valueOf(2), service.revokeInvite(APPLICATION, PUBLISHER).getDelFlag());
        // 幂等重放不重复通知
        service.revokeInvite(APPLICATION, PUBLISHER);

        verify(committedNotice, times(1)).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.INVITE_REVOKE), eq(MERCHANT), eq("章节邀请已撤回"), eq("主办方已撤回待接受的章节邀请"));
    }

    @Test
    void failedRevokeSendsNoNotice() {
        MerchantChapterApplication invite = pending(MerchantChapterApplication.SOURCE_INVITE);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(invite);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invite);
        when(applicationMapper.withdrawPending(APPLICATION, MERCHANT)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.revokeInvite(APPLICATION, PUBLISHER));
        assertThrows(IllegalStateException.class, () -> service.revokeInvite(APPLICATION, MERCHANT));
        verify(committedNotice, never()).submitOutcome(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void merchantWithdrawingOwnApplicationNotifiesPublisherOnce() {
        MerchantChapterApplication application = pending(MerchantChapterApplication.SOURCE_APPLY);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(application);
        when(applicationMapper.withdrawPending(APPLICATION, MERCHANT)).thenReturn(1);

        assertEquals(Integer.valueOf(2), service.withdraw(APPLICATION, MERCHANT).getDelFlag());
        // 幂等重放(行已 del_flag=2)不重复通知
        service.withdraw(APPLICATION, MERCHANT);

        verify(applicationMapper, times(1)).withdrawPending(APPLICATION, MERCHANT);
        verify(committedNotice, times(1)).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.WITHDRAW), eq(PUBLISHER), eq("章节申请已撤回"), eq("商家已撤回章节承接申请"));
        verify(committedNotice, never()).submitOutcome(any(), any(), any(), any(), eq(MERCHANT), any(), any());
    }

    @Test
    void lostWithdrawRaceSendsNoNotice() {
        MerchantChapterApplication application = pending(MerchantChapterApplication.SOURCE_APPLY);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.withdrawPending(APPLICATION, MERCHANT)).thenReturn(0);
        MerchantChapterApplication alreadyWithdrawn = pending(MerchantChapterApplication.SOURCE_APPLY);
        alreadyWithdrawn.setDelFlag(2);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(alreadyWithdrawn);

        assertEquals(Integer.valueOf(2), service.withdraw(APPLICATION, MERCHANT).getDelFlag());
        verify(committedNotice, never()).submitOutcome(any(), any(), any(), any(), any(), any(), any());
    }
}
