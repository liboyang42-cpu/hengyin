package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantChapterApplicationServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V1 章节招商的服务层负控。
 *
 * <p>这组测试锁住三个不可退化的规则：显式开关而非 maxMerchant 推导、谁发布谁审核、
 * 审批即占名额。去掉其中任一服务闸，对应测试必须变红。</p>
 */
class MerchantChapterApplicationServiceImplTest {

    @Test
    void publisherInboxRequiresAuthenticatedOwnerAndUsesScopedQuery() {
        assertThrows(IllegalStateException.class, () -> service.selectForPublisher(null));
        assertThrows(IllegalStateException.class, () -> service.selectForPublisher(0L));
        verify(applicationMapper, never()).selectByPublisherId(any());
        MerchantChapterApplication row = new MerchantChapterApplication();
        row.setId(91L);
        when(applicationMapper.selectByPublisherId(PUBLISHER)).thenReturn(Collections.singletonList(row));
        assertEquals(Collections.singletonList(row), service.selectForPublisher(PUBLISHER));
        verify(applicationMapper).selectByPublisherId(PUBLISHER);
    }

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
    private ISysCategoryService categoryService;
    private IChapterMerchantNodeService chapterMerchantNodeService;
    private MerchantChapterApplicationServiceImpl service;
    private com.chengyinhub.business.service.support.ChapterApplicationInviteNotice committedNotice;

    @BeforeEach
    void setUp() {
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        when(applicationMapper.selectApplicationRoundForUpdate(anyLong())).thenReturn(1L);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        categoryService = mock(ISysCategoryService.class);
        chapterMerchantNodeService = mock(IChapterMerchantNodeService.class);

        service = new MerchantChapterApplicationServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class));
        ReflectionTestUtils.setField(service, "applicationMapper", applicationMapper);
        committedNotice = mock(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.class);
        ReflectionTestUtils.setField(service, "inviteNotice", committedNotice);
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(com.chengyinhub.business.mapper.AuditTaskMapper.class));
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(service, "chapterMerchantNodeService", chapterMerchantNodeService);
        when(categoryService.selectSysCategoryById(CATEGORY.longValue())).thenReturn(activeCategory());
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
    }

    @Test
    void invitationStoresMessageAndStillChecksPublisher() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 0));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
        MerchantChapterApplication result = service.invite(CHAPTER, PUBLISHER, MERCHANT, "  一起来学习  ");
        assertEquals("一起来学习", result.getMessage());
        assertEquals(CHAPTER, result.getChapterId());
        verify(applicationMapper).insertMerchantChapterApplication(result);
        assertThrows(IllegalStateException.class, () -> service.invite(CHAPTER, PUBLISHER + 1, MERCHANT, "越权"));
        assertThrows(IllegalStateException.class, () -> service.invite(CHAPTER, PUBLISHER, MERCHANT,
                String.join("", Collections.nCopies(501, "字"))));
    }

    @Test
    void invitationReusesWithdrawnPendingRowAndRejectsChangedHistory() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
        MerchantChapterApplication withdrawn = application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 2);
        when(applicationMapper.selectAnyByChapterAndMerchant(CHAPTER, MERCHANT)).thenReturn(withdrawn);
        when(applicationMapper.reinviteWithdrawn(any())).thenReturn(1);

        MerchantChapterApplication invited = service.invite(CHAPTER, PUBLISHER, MERCHANT, "重新邀请");
        assertEquals(APPLICATION, invited.getId());
        assertEquals(Integer.valueOf(0), invited.getDelFlag());
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_PENDING), invited.getStatus());
        assertEquals(Integer.valueOf(MerchantChapterApplication.SOURCE_INVITE), invited.getSource());
        assertNull(invited.getAuditMemberId());
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());

        when(applicationMapper.reinviteWithdrawn(any())).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "并发已变化"));
        withdrawn.setStatus(MerchantChapterApplication.STATUS_APPROVED);
        assertThrows(IllegalStateException.class,
                () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "不能重邀已退出的供给"));
    }

    @Test
    void expiredRecruitmentRejectsNewCommitmentsAndCandidates() {
        CmsTopic topic = availableTopic();
        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() - 1000L));
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 0));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "过期邀请"));

        assertEquals("招商写入已关闭：now >= recruitDeadline", error.getMessage());
        assertEquals(error.getMessage(), assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "过期申请")).getMessage());

        MerchantChapterApplication pending = new MerchantChapterApplication();
        pending.setId(APPLICATION);
        pending.setTopicId(TOPIC);
        pending.setChapterId(CHAPTER);
        pending.setMerchantId(MERCHANT);
        pending.setStatus(MerchantChapterApplication.STATUS_PENDING);
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(pending);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(pending);
        assertEquals(error.getMessage(), assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, PUBLISHER, true, "过期批准")).getMessage());
        verify(applicationMapper, never()).updateAudit(any(), any(), any(), any(), any());

        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter(1, 0)));
        when(merchantMapper.selectMmsMerchantList(any())).thenReturn(Collections.singletonList(activeMerchant(MERCHANT)));
        assertTrue(service.invitableMerchants(TOPIC, PUBLISHER).isEmpty());

        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86400000L));
        assertEquals(1, service.invitableMerchants(TOPIC, PUBLISHER).size(), "仍在招商期的候选不能被一起隐藏");

        topic.setRecruitDeadline(null);
        assertEquals("招商写入已关闭：recruitDeadline 未配置", assertThrows(IllegalStateException.class,
                () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "缺截止时间")).getMessage());
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
        verify(applicationMapper, never()).selectByChapterAndStatus(any(), any());
    }

    private CmsTopicChapter chapter(int recruitEnabled, int maxMerchant) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        chapter.setRecruitEnabled(recruitEnabled);
        chapter.setCategoryId(CATEGORY);
        chapter.setMaxMerchant(maxMerchant);
        return chapter;
    }

    private CmsTopic availableTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(1);
        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86400000L));
        return topic;
    }

    private MmsMerchant activeMerchant(Long memberId) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(memberId);
        merchant.setCategoryId(CATEGORY);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }

    private SysCategory activeCategory() {
        SysCategory category = new SysCategory();
        category.setId(CATEGORY.longValue());
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        return category;
    }

    @Test
    void apply_enabledMatchingMerchant_createsPendingApplication() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        CmsTopic topic = availableTopic();
        topic.setRecruitDeadline(new java.util.Date(Long.MAX_VALUE));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
        when(applicationMapper.selectByChapterAndMerchant(CHAPTER, MERCHANT)).thenReturn(null);

        service.apply(CHAPTER, MERCHANT, "愿意提供夜间接待");

        ArgumentCaptor<MerchantChapterApplication> captor = ArgumentCaptor.forClass(MerchantChapterApplication.class);
        verify(applicationMapper).insertMerchantChapterApplication(captor.capture());
        MerchantChapterApplication saved = captor.getValue();
        assertEquals(TOPIC, saved.getTopicId(), "topicId 必须由章节反查，不能信前端");
        assertEquals(CHAPTER, saved.getChapterId());
        assertEquals(MERCHANT, saved.getMerchantId());
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_PENDING), saved.getStatus());
        verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        verify(topicMapper, never()).selectCmsTopicById(TOPIC);
    }

    @Test
    void apply_pendingApplicationReturnsStableExistsCode() {
        arrangeEligibleApplication();
        MerchantChapterApplication existing = new MerchantChapterApplication();
        existing.setStatus(MerchantChapterApplication.STATUS_PENDING);
        when(applicationMapper.selectAnyByChapterAndMerchant(CHAPTER, MERCHANT)).thenReturn(existing);

        MerchantChapterApplicationExistsException error = assertThrows(
                MerchantChapterApplicationExistsException.class,
                () -> service.apply(CHAPTER, MERCHANT, "再次提交"));

        assertEquals(MerchantChapterApplicationExistsException.ERROR_CODE, error.getErrorCode());
        assertTrue(error.getMessage().contains("等待发布者审核"));
    }

    @Test
    void apply_approvedApplicationReturnsStableExistsCode() {
        arrangeEligibleApplication();
        MerchantChapterApplication existing = new MerchantChapterApplication();
        existing.setStatus(MerchantChapterApplication.STATUS_APPROVED);
        when(applicationMapper.selectAnyByChapterAndMerchant(CHAPTER, MERCHANT)).thenReturn(existing);

        MerchantChapterApplicationExistsException error = assertThrows(
                MerchantChapterApplicationExistsException.class,
                () -> service.apply(CHAPTER, MERCHANT, "再次提交"));

        assertEquals(MerchantChapterApplicationExistsException.ERROR_CODE, error.getErrorCode());
        assertTrue(error.getMessage().contains("申请已通过"));
    }

    @Test
    void apply_concurrentDuplicateReturnsStableExistsCode() {
        arrangeEligibleApplication();
        when(applicationMapper.selectByChapterAndMerchant(CHAPTER, MERCHANT)).thenReturn(null);
        when(applicationMapper.insertMerchantChapterApplication(any()))
                .thenThrow(new DuplicateKeyException("uk_chapter_merchant"));

        MerchantChapterApplicationExistsException error = assertThrows(
                MerchantChapterApplicationExistsException.class,
                () -> service.apply(CHAPTER, MERCHANT, "并发提交"));

        assertEquals(MerchantChapterApplicationExistsException.ERROR_CODE, error.getErrorCode());
        assertTrue(error.getMessage().contains("刷新后查看状态"));
    }

    @Test
    void withdraw_otherMerchantsApplicationReturnsOwnershipErrorCode() {
        MerchantChapterApplication application = application(
                MerchantChapterApplication.STATUS_PENDING, OTHER_MERCHANT, 0);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(application);

        MerchantChapterApplicationWithdrawException error = assertThrows(
                MerchantChapterApplicationWithdrawException.class,
                () -> service.withdraw(APPLICATION, MERCHANT));

        assertEquals(MerchantChapterApplicationWithdrawException.ERROR_CODE_NOT_OWNER, error.getErrorCode());
        assertTrue(error.getMessage().contains("只能撤回自己的申请"));
        verify(applicationMapper, never()).withdrawPending(anyLong(), anyLong());
    }

    @Test
    void withdraw_approvedApplicationReturnsFinalizedErrorCode() {
        MerchantChapterApplication application = application(
                MerchantChapterApplication.STATUS_APPROVED, MERCHANT, 0);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(application);

        MerchantChapterApplicationWithdrawException error = assertThrows(
                MerchantChapterApplicationWithdrawException.class,
                () -> service.withdraw(APPLICATION, MERCHANT));

        assertEquals(MerchantChapterApplicationWithdrawException.ERROR_CODE_FINALIZED, error.getErrorCode());
        assertTrue(error.getMessage().contains("已通过或已拒绝"));
        verify(applicationMapper, never()).withdrawPending(anyLong(), anyLong());
    }

    @Test
    void withdraw_rejectedApplicationReturnsFinalizedErrorCode() {
        MerchantChapterApplication application = application(
                MerchantChapterApplication.STATUS_REJECTED, MERCHANT, 0);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(application);

        MerchantChapterApplicationWithdrawException error = assertThrows(
                MerchantChapterApplicationWithdrawException.class,
                () -> service.withdraw(APPLICATION, MERCHANT));

        assertEquals(MerchantChapterApplicationWithdrawException.ERROR_CODE_FINALIZED, error.getErrorCode());
        verify(applicationMapper, never()).withdrawPending(anyLong(), anyLong());
    }

    @Test
    void apply_withdrawnApplicationRestoresOriginalRow() {
        arrangeEligibleApplication();
        MerchantChapterApplication withdrawn = application(
                MerchantChapterApplication.STATUS_PENDING, MERCHANT, 2);
        when(applicationMapper.selectAnyByChapterAndMerchant(CHAPTER, MERCHANT)).thenReturn(withdrawn);
        when(applicationMapper.restoreWithdrawn(APPLICATION, "重新提交", String.valueOf(MERCHANT))).thenReturn(1);

        MerchantChapterApplication reapplied = service.apply(CHAPTER, MERCHANT, "重新提交");

        assertEquals(APPLICATION, reapplied.getId());
        assertEquals(Integer.valueOf(0), reapplied.getDelFlag());
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_PENDING), reapplied.getStatus());
        verify(applicationMapper).restoreWithdrawn(APPLICATION, "重新提交", String.valueOf(MERCHANT));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void withdraw_pendingApplicationSoftDeletesIt() {
        arrangeExpiredRecruitment();
        MerchantChapterApplication application = application(
                MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(application);
        when(applicationMapper.withdrawPending(APPLICATION, MERCHANT)).thenReturn(1);

        MerchantChapterApplication withdrawn = service.withdraw(APPLICATION, MERCHANT);

        assertEquals(Integer.valueOf(2), withdrawn.getDelFlag());
        verify(applicationMapper).withdrawPending(APPLICATION, MERCHANT);
        verify(chapterMerchantNodeService).auditAlongApplication(
                CHAPTER, MERCHANT, MERCHANT, false, "商家已撤回承接申请");
    }

    @Test
    void withdraw_alreadyWithdrawnApplicationIsIdempotent() {
        MerchantChapterApplication application = application(
                MerchantChapterApplication.STATUS_PENDING, MERCHANT, 2);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(application);

        MerchantChapterApplication withdrawn = service.withdraw(APPLICATION, MERCHANT);

        assertEquals(APPLICATION, withdrawn.getId());
        assertEquals(Integer.valueOf(2), withdrawn.getDelFlag());
        verify(applicationMapper, never()).withdrawPending(anyLong(), anyLong());
        verify(chapterMerchantNodeService, never()).auditAlongApplication(
                anyLong(), anyLong(), anyLong(), eq(false), any());
    }

    @Test
    void invitedMerchantMustAcceptBeforeCapacityIsReserved() {
        arrangeEligibleApplication();
        MerchantChapterApplication invited = application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0);
        invited.setSource(MerchantChapterApplication.SOURCE_INVITE);
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(invited);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invited);
        when(applicationMapper.updateAudit(any(), any(), any(), any(), any())).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.respondInvite(APPLICATION, OTHER_MERCHANT, true));
        assertThrows(IllegalStateException.class, () -> service.audit(APPLICATION, PUBLISHER, true, ""));
        MerchantChapterApplication accepted = service.respondInvite(APPLICATION, MERCHANT, true);
        verify(committedNotice).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.INVITE_DECISION), eq(PUBLISHER), eq("章节邀请处理结果"), eq("商家已接受章节邀请"));
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED), accepted.getStatus());
        assertEquals(MERCHANT, accepted.getAuditMemberId());
        assertEquals(accepted, service.respondInvite(APPLICATION, MERCHANT, true));
        verify(applicationMapper).updateAudit(APPLICATION, MerchantChapterApplication.STATUS_APPROVED,
                "商家已接受邀请", MERCHANT, accepted.getHandleTime());
    }

    @Test
    void publisherCanRevokeOnlyItsOwnPendingInvitation() {
        arrangeEligibleApplication();
        MerchantChapterApplication invited = application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0);
        invited.setSource(MerchantChapterApplication.SOURCE_INVITE);
        when(applicationMapper.selectByIdIncludingDeleted(APPLICATION)).thenReturn(invited);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invited);
        when(applicationMapper.withdrawPending(APPLICATION, MERCHANT)).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.revokeInvite(APPLICATION, OTHER_MERCHANT));
        assertEquals(Integer.valueOf(2), service.revokeInvite(APPLICATION, PUBLISHER).getDelFlag());
        verify(committedNotice).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.INVITE_REVOKE), eq(MERCHANT), eq("章节邀请已撤回"), eq("主办方已撤回待接受的章节邀请"));
        assertEquals(invited, service.revokeInvite(APPLICATION, PUBLISHER));
        verify(applicationMapper).withdrawPending(APPLICATION, MERCHANT);
    }

    private MerchantChapterApplication application(int status, Long merchantId, int delFlag) {
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(APPLICATION);
        application.setTopicId(TOPIC);
        application.setChapterId(CHAPTER);
        application.setMerchantId(merchantId);
        application.setStatus(status);
        application.setDelFlag(delFlag);
        return application;
    }

    private void arrangeEligibleApplication() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
    }

    private void arrangeExpiredRecruitment() {
        arrangeEligibleApplication();
        CmsTopic topic = availableTopic();
        topic.setRecruitDeadline(new java.util.Date(0L));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    @Test
    void configuredDeadlineClosesNewApplicationsAndReapplications() {
        arrangeExpiredRecruitment();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "截止后申请")).getMessage().contains("招商"));
        when(applicationMapper.selectAnyByChapterAndMerchant(CHAPTER, MERCHANT))
                .thenReturn(application(MerchantChapterApplication.STATUS_REJECTED, MERCHANT, 0));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "截止后重申")).getMessage().contains("招商"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
        verify(applicationMapper, never()).reapply(anyLong(), any(), any());
    }

    @Test
    void configuredDeadlineClosesApprovalOfPendingApplications() {
        arrangeExpiredRecruitment();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION))
                .thenReturn(application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0));
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0));
        when(applicationMapper.updateAudit(anyLong(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, PUBLISHER, true, "截止后批准")).getMessage().contains("招商"));
        verify(applicationMapper, never()).updateAudit(anyLong(), any(), any(), any(), any());
    }

    @Test
    void configuredDeadlineClosesPublisherInvitations() {
        arrangeExpiredRecruitment();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "截止后邀请")).getMessage().contains("招商"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void configuredDeadlineStillAllowsRejectingPendingApplications() {
        arrangeExpiredRecruitment();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION))
                .thenReturn(application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0));
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0));
        when(applicationMapper.updateAudit(anyLong(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_REJECTED),
                service.audit(APPLICATION, PUBLISHER, false, "招募截止收尾").getStatus());
    }

    /** maxMerchant=4 是存量默认值，不能再被误当成开放招商的推导开关。 */
    @Test
    void apply_disabledChapterWithDefaultCapacity_rejected() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(0, 4));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("未开放"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    /** 写入收口前留下的负数名额同样 fail-closed，不能在申请阶段被当作不限家数。 */
    @Test
    void apply_rejectsInvalidLegacyMaxMerchant() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, -1));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("0到127"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void apply_rejectsNonPositiveLegacyPerkThreshold() {
        CmsTopicChapter chapter = chapter(1, 2);
        chapter.setPerkMinValue(BigDecimal.ZERO);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("权益最低价值"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void apply_rejectsLegacyTrafficChapterWithPerkThreshold() {
        CmsTopicChapter chapter = chapter(1, 2);
        chapter.setTermsMode("TRAFFIC");
        chapter.setPerkMinValue(BigDecimal.ONE);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("只有权益档"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void apply_rejectsDeletedTopic() {
        CmsTopic deleted = availableTopic();
        deleted.setDelFlag(2);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(deleted);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("已删除"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void apply_rejectsChapterWhoseConfiguredCategoryHasBeenDisabled() {
        SysCategory disabled = activeCategory();
        disabled.setStatus(0);
        when(categoryService.selectSysCategoryById(CATEGORY.longValue())).thenReturn(disabled);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("不存在或已停用"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void apply_rejectsRevshareForV1Recruitment() {
        ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));
        CmsTopicChapter chapter = chapter(1, 2);
        chapter.setTermsMode("REVSHARE");
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.apply(CHAPTER, MERCHANT, "绕过前端直调"));

        assertTrue(error.getMessage().contains("待P2开放"));
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    @Test
    void currentMerchantEligibility_rejectsMerchantDisabledAfterApproval() {
        CmsTopicChapter chapter = chapter(1, 2);
        MmsMerchant merchant = activeMerchant(MERCHANT);
        merchant.setStatus(0L);
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(merchant);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.assertCurrentMerchantEligibility(chapter, MERCHANT));

        assertTrue(error.getMessage().contains("审核通过且启用"));
    }

    /** 审核者只能是 chapterId 反查到的主题原发布者。 */
    @Test
    void audit_nonPublisherRejected() {
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(APPLICATION);
        application.setTopicId(TOPIC);
        application.setChapterId(CHAPTER);
        application.setMerchantId(MERCHANT);
        application.setStatus(MerchantChapterApplication.STATUS_PENDING);
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, OTHER_MERCHANT, true, "同意"));

        assertTrue(error.getMessage().contains("发布者"));
        verify(applicationMapper, never()).updateAudit(eq(APPLICATION), any(Integer.class), any(), any(), any());
    }

    @Test
    void audit_rejectsDeletedTopic() {
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(APPLICATION);
        application.setTopicId(TOPIC);
        application.setChapterId(CHAPTER);
        application.setMerchantId(MERCHANT);
        application.setStatus(MerchantChapterApplication.STATUS_PENDING);
        CmsTopic deleted = availableTopic();
        deleted.setDelFlag(2);
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(deleted);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, PUBLISHER, true, "同意"));

        assertTrue(error.getMessage().contains("主题归属不明"));
        verify(applicationMapper, never()).updateAudit(eq(APPLICATION), any(Integer.class), any(), any(), any());
    }

    /** 已通过申请先占坑，审批时不能把超过名额的意向一并放行。 */
    @Test
    void audit_approvalRejectedWhenApprovedApplicationsAlreadyFillCapacity() {
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(APPLICATION);
        application.setTopicId(TOPIC);
        application.setChapterId(CHAPTER);
        application.setMerchantId(MERCHANT);
        application.setStatus(MerchantChapterApplication.STATUS_PENDING);
        MerchantChapterApplication approved = new MerchantChapterApplication();
        approved.setMerchantId(OTHER_MERCHANT);
        approved.setStatus(MerchantChapterApplication.STATUS_APPROVED);

        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(application);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 1));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(availableTopic());
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.singletonList(approved));
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, PUBLISHER, true, "同意"));

        assertTrue(error.getMessage().contains("已满"));
        verify(applicationMapper, never()).updateAudit(eq(APPLICATION), any(Integer.class), any(), any(), any());
    }

    /** 审批容量与运营统计必须用同一去重口径，已通过申请和生效供给不能把同一商家算两次。 */
    @Test
    void recruitmentStats_deduplicatesMerchantAcrossApprovedApplicationAndLiveOffer() {
        MerchantChapterApplication approved = new MerchantChapterApplication();
        approved.setMerchantId(OTHER_MERCHANT);
        MerchantChapterOffer liveOffer = new MerchantChapterOffer();
        liveOffer.setMerchantId(OTHER_MERCHANT);
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_PENDING))
                .thenReturn(Collections.<MerchantChapterApplication>emptyList());
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.singletonList(approved));
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.singletonList(liveOffer));

        MerchantChapterRecruitmentStats stats = service.getRecruitmentStats(CHAPTER, 2);

        assertEquals(1, stats.getApprovedApplicationCount());
        assertEquals(1, stats.getActiveOfferCount());
        assertEquals(1, stats.getReservedMerchantCount());
        assertEquals(1, stats.getRemainingMerchantCount());
    }

    @Test
    void lockedReplacementInvitationRetainsMessageAndPausedAdmissions() {
        CmsTopic topic = availableTopic(); topic.setLifecycle(3); topic.setUserStatus(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 1));
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
        MerchantChapterOffer original = new MerchantChapterOffer();
        original.setId(800L); original.setTopicId(TOPIC); original.setChapterId(CHAPTER);
        original.setMerchantId(OTHER_MERCHANT); original.setStatus(0); original.setWithdrawReason("停止营业");
        when(offerMapper.selectByIdForUpdate(800L)).thenReturn(original);
        MerchantChapterApplication invited = service.invite(CHAPTER, PUBLISHER, MERCHANT, "同价补位", 800L);
        assertEquals(800L, invited.getReplacementOfOfferId());
        assertEquals("同价补位", invited.getMessage());
        assertEquals(0, topic.getUserStatus());
        original.setStatus(1);
        assertThrows(IllegalStateException.class, () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "同价补位", 800L));
        original.setStatus(0); topic.setLifecycle(5);
        assertThrows(IllegalStateException.class, () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "同价补位", 800L));
    }

    /** F-L3-02:邀请改为待接受后，待接受的补位邀请同样占住该原供给的补位位。 */
    @Test
    void pendingReplacementInvitationBlocksSecondReplacementInvitationForSameOffer() {
        CmsTopic topic = availableTopic(); topic.setLifecycle(3); topic.setUserStatus(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter(1, 2));
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(activeMerchant(MERCHANT));
        MerchantChapterOffer original = new MerchantChapterOffer();
        original.setId(800L); original.setTopicId(TOPIC); original.setChapterId(CHAPTER);
        original.setMerchantId(OTHER_MERCHANT); original.setStatus(0); original.setWithdrawReason("停止营业");
        when(offerMapper.selectByIdForUpdate(800L)).thenReturn(original);
        MerchantChapterApplication pending = application(MerchantChapterApplication.STATUS_PENDING, 3003L, 0);
        pending.setId(92L); pending.setSource(MerchantChapterApplication.SOURCE_INVITE); pending.setReplacementOfOfferId(800L);
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_PENDING))
                .thenReturn(Collections.singletonList(pending));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.invite(CHAPTER, PUBLISHER, MERCHANT, "同价补位", 800L));
        assertEquals("该供给已有补位邀请，请等待确认或撤回后另邀", error.getMessage());
        verify(applicationMapper, never()).insertMerchantChapterApplication(any());
    }

    /** F-L3-02:同一原供给只能有一家接受补位，第二家接受时拒绝且不改状态。 */
    @Test
    void acceptingReplacementInvitationRejectsWhenAnotherReplacementAlreadyAccepted() {
        arrangeEligibleApplication();
        CmsTopic paused = availableTopic(); paused.setLifecycle(3); paused.setUserStatus(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(paused);
        MerchantChapterApplication invited = application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0);
        invited.setSource(MerchantChapterApplication.SOURCE_INVITE); invited.setReplacementOfOfferId(800L);
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION)).thenReturn(invited);
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(invited);
        MerchantChapterApplication accepted = application(MerchantChapterApplication.STATUS_APPROVED, 3003L, 0);
        accepted.setId(92L); accepted.setSource(MerchantChapterApplication.SOURCE_INVITE); accepted.setReplacementOfOfferId(800L);
        when(applicationMapper.selectByChapterAndStatus(CHAPTER, MerchantChapterApplication.STATUS_APPROVED))
                .thenReturn(Collections.singletonList(accepted));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.respondInvite(APPLICATION, MERCHANT, true));
        assertEquals("原供给已有确认替补，请刷新后查看", error.getMessage());
        verify(applicationMapper, never()).updateAudit(any(), any(), any(), any(), any());
    }

    /** F-R2F-05:主办驳回章节申请必须带原因；商家拒绝邀请不受影响。 */
    @Test
    void publisherRejectionRequiresReason() {
        arrangeEligibleApplication();
        when(applicationMapper.selectMerchantChapterApplicationById(APPLICATION))
                .thenReturn(application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0));
        when(applicationMapper.selectByIdIncludingDeletedForUpdate(APPLICATION)).thenReturn(application(MerchantChapterApplication.STATUS_PENDING, MERCHANT, 0));
        when(applicationMapper.updateAudit(any(), any(), any(), any(), any())).thenReturn(1);
        assertEquals("请填写拒绝原因", assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, PUBLISHER, false, "  ")).getMessage());
        assertEquals("请填写拒绝原因", assertThrows(IllegalStateException.class,
                () -> service.audit(APPLICATION, PUBLISHER, false, null)).getMessage());
        verify(applicationMapper, never()).updateAudit(any(), any(), any(), any(), any());
        assertEquals(Integer.valueOf(MerchantChapterApplication.STATUS_REJECTED),
                service.audit(APPLICATION, PUBLISHER, false, "品类不符").getStatus());
        verify(committedNotice).submitOutcome(eq(TOPIC), eq(APPLICATION), any(), eq(com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome.REVIEW), eq(MERCHANT), eq("章节申请审核结果"), eq("章节申请未通过：品类不符"));
    }
}
