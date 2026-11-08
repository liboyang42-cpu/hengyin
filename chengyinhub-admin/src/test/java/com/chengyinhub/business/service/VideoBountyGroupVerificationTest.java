package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.domain.VideoBountySubmission;
import com.chengyinhub.business.domain.VideoCampaign;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.VideoBountyAccrualMapper;
import com.chengyinhub.business.mapper.VideoBountyEvidenceMapper;
import com.chengyinhub.business.mapper.VideoBountySubmissionMapper;
import com.chengyinhub.business.mapper.VideoCampaignMapper;
import com.chengyinhub.business.service.impl.VideoBountyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 原视频组核验契约：封顶只认运营核验结论，作者自报组键不能决定应计。
 *
 * <p>注：只证明服务层判定顺序与写面，不证明数据库并发/回滚，也不证明 {@code @PreAuthorize} 生效。
 */
class VideoBountyGroupVerificationTest {

    private static final long CAMPAIGN_ID = 5L;
    private static final String CAMPAIGN_KEY = "camp-1";
    private static final long SUBMISSION_ID = 11L;
    private static final long AUTHOR = 900L;
    private static final String REVIEWER = "sys:7";

    private VideoCampaignMapper campaigns;
    private VideoBountySubmissionMapper submissions;
    private VideoBountyEvidenceMapper evidence;
    private VideoBountyAccrualMapper accruals;
    private ICmsTopicService topics;
    private IMarketingAttributionService attribution;
    private UmsMemberMapper members;
    private BusinessAuditEventMapper audits;
    private VideoBountyServiceImpl service;

    @BeforeEach
    void setUp() {
        campaigns = mock(VideoCampaignMapper.class);
        submissions = mock(VideoBountySubmissionMapper.class);
        evidence = mock(VideoBountyEvidenceMapper.class);
        accruals = mock(VideoBountyAccrualMapper.class);
        topics = mock(ICmsTopicService.class);
        attribution = mock(IMarketingAttributionService.class);
        members = mock(UmsMemberMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new VideoBountyServiceImpl(campaigns, submissions, evidence, accruals, topics,
                attribution, members, audits);

        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
        when(members.selectUmsMemberById(anyLong())).thenReturn(member());
        CmsTopic topic = new CmsTopic();
        topic.setId(3L);
        topic.setStatus(1);
        when(topics.selectCmsTopicById(3L)).thenReturn(topic);
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(campaign());
    }

    // ------------------------------------------------------------------ 应计不许猜

    @Test
    void unverifiedGroupProducesNoAccrualAtAll() {
        VideoBountySubmission submission = approvedSubmission();
        submission.setOriginGroupKey("author-says-group-a");
        submission.setVerifiedGroupKey(null);
        when(submissions.selectById(SUBMISSION_ID)).thenReturn(submission);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.recomputeAccrual(SUBMISSION_ID));

        assertEquals("ACCRUAL_GROUP_UNVERIFIED", rejection.getReasonCode());
        verify(accruals, never()).insertAccrual(any());
    }

    @Test
    void accrualGroupsByVerifiedKeyAndIgnoresTheAuthorKey() {
        VideoBountySubmission submission = approvedSubmission();
        submission.setOriginGroupKey("author-split-1");
        submission.setVerifiedGroupKey("same-original-video");
        when(submissions.selectById(SUBMISSION_ID)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "same-original-video"))
                .thenReturn(List.of(submission));
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(0L);
        when(accruals.selectByIdempotencyKey(anyString())).thenReturn(null);
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

        service.recomputeAccrual(SUBMISSION_ID);

        verify(submissions).selectActiveByVerifiedGroup(CAMPAIGN_ID, "same-original-video");
        ArgumentCaptor<VideoBountyAccrual> saved = ArgumentCaptor.forClass(VideoBountyAccrual.class);
        verify(accruals).insertAccrual(saved.capture());
        assertEquals("same-original-video", saved.getValue().getGroupKey(),
                "应计落库的组键必须是核验组，不是作者自报");
    }

    // ------------------------------------------------------------------ 核验写面

    @Test
    void reviewerVerificationWritesCasAndAudit() {
        VideoBountySubmission submission = approvedSubmission();
        submission.setVerifiedGroupVersion(0);
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(submission);
        when(accruals.selectActiveBySubmission(SUBMISSION_ID)).thenReturn(null);
        when(submissions.markGroupVerified(eq(SUBMISSION_ID), eq("orig-1"), eq(7L), any(), eq("TICKET-9"), eq(0)))
                .thenReturn(1);
        when(submissions.selectById(SUBMISSION_ID)).thenReturn(submission);

        service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", "TICKET-9", 0, "两平台同一条原视频");

        verify(submissions).markGroupVerified(eq(SUBMISSION_ID), eq("orig-1"), eq(7L), any(),
                eq("TICKET-9"), eq(0));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_SYS_USER, audit.getValue().getActorType());
        assertEquals(7L, audit.getValue().getActorMemberId().longValue());
        assertEquals("两平台同一条原视频", audit.getValue().getReason());
        assertEquals(SUBMISSION_ID, audit.getValue().getDomainId().longValue());
    }

    @Test
    void onlyApprovedSubmissionsCanBeVerified() {
        VideoBountySubmission submission = approvedSubmission();
        submission.setStatus(VideoBountySubmission.STATUS_SUBMITTED);
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(submission);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", "TICKET-9", 0, "理由"));

        assertEquals("GROUP_VERIFY_NOT_ALLOWED", rejection.getReasonCode());
        verify(submissions, never()).markGroupVerified(anyLong(), anyString(), anyLong(), any(), anyString(), anyInt());
    }

    @Test
    void verificationRequiresEvidenceReferenceAndReason() {
        VideoBountySubmission submission = approvedSubmission();
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(submission);

        assertEquals("REASON_REQUIRED", assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", "TICKET-9", 0, " ")).getReasonCode());
        assertEquals("SOURCE_REF_REQUIRED", assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", " ", 0, "理由")).getReasonCode());
    }

    @Test
    void aGroupKeyCannotBeSharedAcrossAuthors() {
        VideoBountySubmission target = approvedSubmission();
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(target);
        VideoBountySubmission otherAuthor = approvedSubmission();
        otherAuthor.setId(99L);
        otherAuthor.setMemberId(AUTHOR + 1);
        otherAuthor.setVerifiedGroupKey("orig-1");
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "orig-1"))
                .thenReturn(List.of(otherAuthor));

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", "TICKET-9", 0, "并组"));

        assertEquals("GROUP_AUTHOR_MISMATCH", rejection.getReasonCode());
        verify(submissions, never()).markGroupVerified(anyLong(), anyString(), anyLong(), any(), anyString(), anyInt());
    }

    @Test
    void malformedGroupKeyIsRejected() {
        VideoBountySubmission submission = approvedSubmission();
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(submission);

        assertEquals("GROUP_KEY_MALFORMED", assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "Bad Group!", "TICKET-9", 0, "理由"))
                .getReasonCode());
    }

    @Test
    void reviewerReferenceMustCarryARealmPrefix() {
        assertThrows(RuntimeException.class,
                () -> service.verifyOriginalGroup("7", SUBMISSION_ID, "orig-1", "TICKET-9", 0, "理由"));
    }

    // ------------------------------------------------------------------ 已计后不改组

    @Test
    void aSubmissionWithActiveAccrualCannotBeMovedToAnotherGroup() {
        VideoBountySubmission submission = approvedSubmission();
        submission.setVerifiedGroupKey("small-group");
        submission.setVerifiedGroupVersion(2);
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(submission);
        VideoBountyAccrual active = new VideoBountyAccrual();
        active.setGroupKey("small-group");
        when(accruals.selectActiveBySubmission(SUBMISSION_ID)).thenReturn(active);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "merged-group",
                        "TICKET-9", 2, "并组"));

        assertEquals("GROUP_LOCKED_BY_ACCRUAL", rejection.getReasonCode());
        verify(submissions, never()).markGroupVerified(anyLong(), anyString(), anyLong(), any(), anyString(), anyInt());
    }

    @Test
    void reVerifyingTheSameGroupIsAllowedEvenAfterAccrual() {
        VideoBountySubmission submission = approvedSubmission();
        submission.setVerifiedGroupKey("same-group");
        submission.setVerifiedGroupVersion(2);
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(submission);
        VideoBountyAccrual active = new VideoBountyAccrual();
        active.setGroupKey("same-group");
        when(accruals.selectActiveBySubmission(SUBMISSION_ID)).thenReturn(active);
        when(submissions.markGroupVerified(anyLong(), anyString(), anyLong(), any(), anyString(), anyInt()))
                .thenReturn(1);
        when(submissions.selectById(SUBMISSION_ID)).thenReturn(submission);

        service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "same-group", "TICKET-9", 2, "复核确认同组");

        verify(submissions).markGroupVerified(eq(SUBMISSION_ID), eq("same-group"), eq(7L), any(),
                eq("TICKET-9"), eq(2));
    }

    @Test
    void concurrentVerificationLosesCleanlyAndReplayIsIdempotent() {
        VideoBountySubmission stale = approvedSubmission();
        stale.setVerifiedGroupKey(null);
        stale.setVerifiedGroupVersion(3);
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(stale);

        // 调用方版本落后 → 409，且不写审计。
        BountyTipException conflict = assertThrows(BountyTipException.class,
                () -> service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", "TICKET-9", 1, "理由"));
        assertEquals("GROUP_VERIFY_VERSION_CONFLICT", conflict.getReasonCode());
        verify(audits, never()).insertBusinessAuditEvent(any());

        // 同一结论重投（CAS 0 行但结论一致）→ 幂等返回，不重复记审计。
        stale.setVerifiedGroupKey("orig-1");
        stale.setVerifiedSourceRef("TICKET-9");
        when(accruals.selectActiveBySubmission(SUBMISSION_ID)).thenReturn(null);
        when(submissions.markGroupVerified(anyLong(), anyString(), anyLong(), any(), anyString(), anyInt()))
                .thenReturn(0);
        when(submissions.selectById(SUBMISSION_ID)).thenReturn(stale);

        service.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1", "TICKET-9", 3, "理由");

        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void missingAuditStoreRefusesVerification() {
        VideoBountyServiceImpl withoutAudit = new VideoBountyServiceImpl(campaigns, submissions, evidence,
                accruals, topics, attribution, members, null);
        when(submissions.selectByIdForUpdate(SUBMISSION_ID)).thenReturn(approvedSubmission());
        when(accruals.selectActiveBySubmission(SUBMISSION_ID)).thenReturn(null);
        when(submissions.markGroupVerified(anyLong(), anyString(), anyLong(), any(), anyString(), anyInt()))
                .thenReturn(1);

        assertEquals("AUDIT_UNAVAILABLE", assertThrows(BountyTipException.class,
                () -> withoutAudit.verifyOriginalGroup(REVIEWER, SUBMISSION_ID, "orig-1",
                        "TICKET-9", 0, "理由")).getReasonCode());
    }

    // ------------------------------------------------------------------ helpers

    private VideoBountySubmission approvedSubmission() {
        VideoBountySubmission submission = new VideoBountySubmission();
        submission.setId(SUBMISSION_ID);
        submission.setCampaignId(CAMPAIGN_ID);
        submission.setCampaignKey(CAMPAIGN_KEY);
        submission.setMemberId(AUTHOR);
        submission.setTopicId(3L);
        submission.setStatus(VideoBountySubmission.STATUS_APPROVED);
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        submission.setVerifiedGroupVersion(0);
        return submission;
    }

    private VideoCampaign campaign() {
        VideoCampaign campaign = new VideoCampaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setCampaignKey(CAMPAIGN_KEY);
        campaign.setStatus(VideoCampaign.STATUS_ACTIVE);
        campaign.setCurrency("CNY");
        campaign.setRatePerThousand(new BigDecimal("2.0000"));
        campaign.setPerVideoCap(new BigDecimal("500.00"));
        campaign.setWindowDays(7);
        campaign.setRuleVersion("v1");
        return campaign;
    }

    private UmsMember member() {
        UmsMember member = new UmsMember();
        member.setId(AUTHOR);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }
}
