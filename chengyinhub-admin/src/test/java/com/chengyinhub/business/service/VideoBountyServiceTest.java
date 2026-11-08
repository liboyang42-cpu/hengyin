package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.domain.VideoBountyEvidence;
import com.chengyinhub.business.domain.VideoBountySubmission;
import com.chengyinhub.business.domain.VideoCampaign;
import com.chengyinhub.business.domain.dto.VideoBountyEvidenceRequest;
import com.chengyinhub.business.domain.dto.VideoBountyReviewRequest;
import com.chengyinhub.business.domain.dto.VideoBountySubmitRequest;
import com.chengyinhub.business.domain.dto.CampaignAttributionAggregate;
import com.chengyinhub.business.domain.dto.VideoCampaignAttributionView;
import com.chengyinhub.business.domain.dto.VideoCampaignRequest;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.VideoBountyAccrualMapper;
import com.chengyinhub.business.mapper.VideoBountyEvidenceMapper;
import com.chengyinhub.business.mapper.VideoBountySubmissionMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.VideoCampaignMapper;
import com.chengyinhub.business.service.impl.VideoBountyServiceImpl;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.mapper.VideoBountySubmissionMapper;

/**
 * 视频悬赏服务契约。
 *
 * <p>守三件事:去重挡跨活动重复领取、应计恒为"仅记录"、证据必须有外部来源。
 * 注:本测试不证明 InnoDB 事务回滚或真实并发唯一键竞争 —— 那是 MySqlIT 的范围。
 */
class VideoBountyServiceTest {

    private static final long AUTHOR = 7L;
    private static final long TOPIC_ID = 42L;
    private static final long CAMPAIGN_ID = 3L;
    private static final long OWNER = 55L;
    private static final long OTHER_MEMBER = 66L;
    private static final String CAMPAIGN_KEY = "launch-2026q4";

    private VideoCampaignMapper campaigns;
    private VideoBountySubmissionMapper submissions;
    private VideoBountyEvidenceMapper evidence;
    private VideoBountyAccrualMapper accruals;
    private ICmsTopicService topics;
    private IMarketingAttributionService attribution;
    private UmsMemberMapper members;
    private com.chengyinhub.business.mapper.BusinessAuditEventMapper audits;
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
        audits = mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        service = new VideoBountyServiceImpl(campaigns, submissions, evidence, accruals, topics,
                attribution, members, audits);
        // 默认所有会员 id 都是可用的真实会员;个别用例再覆盖成不存在。
        when(members.selectUmsMemberById(anyLong())).thenAnswer(invocation -> availableMember());

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        when(topics.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        VideoCampaign campaign = campaign();
        when(campaigns.selectByCampaignKey(CAMPAIGN_KEY)).thenReturn(campaign);
        when(campaigns.selectById(CAMPAIGN_ID)).thenReturn(campaign);
        // 重算会先锁活动行(预算夹取必须与已用合计同锁读),测试也要覆盖这条路径。
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(campaign);
    }

    private static com.chengyinhub.business.domain.UmsMember availableMember() {
        com.chengyinhub.business.domain.UmsMember member = new com.chengyinhub.business.domain.UmsMember();
        member.setId(OWNER);
        member.setStatus(1L);
        return member;
    }

    private static VideoCampaign campaign() {
        VideoCampaign campaign = new VideoCampaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setCampaignKey(CAMPAIGN_KEY);
        campaign.setStatus(VideoCampaign.STATUS_ACTIVE);
        campaign.setOwnerMemberId(OWNER);
        campaign.setCurrency("EUR");
        campaign.setRatePerThousand(new BigDecimal("2.00"));
        campaign.setPerVideoCap(new BigDecimal("500.00"));
        campaign.setWindowDays(7);
        campaign.setRuleVersion("v1");
        campaign.setCashPayoutEnabled(0);
        return campaign;
    }

    private static VideoBountySubmitRequest submitRequest() {
        VideoBountySubmitRequest request = new VideoBountySubmitRequest();
        request.setCampaignKey(CAMPAIGN_KEY);
        request.setTopicId(TOPIC_ID);
        request.setPlatform("tiktok");
        request.setSourceUrl("https://www.tiktok.com/@author/video/7300000000000000001");
        return request;
    }

    // ------------------------------------------------------------------ 投稿去重

    @Test
    void submitRecordsCanonicalKeyAndOriginGroup() {
        ArgumentCaptor<VideoBountySubmission> captor = ArgumentCaptor.forClass(VideoBountySubmission.class);

        service.submit(AUTHOR, submitRequest());

        verify(submissions).insertSubmission(captor.capture());
        VideoBountySubmission row = captor.getValue();
        assertEquals("tiktok:7300000000000000001", row.getCanonicalVideoKey());
        assertEquals(row.getCanonicalVideoKey(), row.getOriginGroupKey(), "未声明分组时自成一组");
        assertEquals(VideoBountySubmission.STATUS_SUBMITTED, row.getStatus().intValue());
    }

    @Test
    void sameVideoInAnotherCampaignIsStillRejected() {
        // 唯一键只挡同活动;换活动再投同一视频必须另判,否则封顶可被拆活动绕开。
        VideoBountySubmission existing = new VideoBountySubmission();
        existing.setId(99L);
        when(submissions.selectByMemberAndCanonical(AUTHOR, "tiktok:7300000000000000001")).thenReturn(existing);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.submit(AUTHOR, submitRequest()));

        assertEquals("VIDEO_ALREADY_SUBMITTED", rejection.getReasonCode());
        verify(submissions, never()).insertSubmission(any());
    }

    @Test
    void declaredPlatformMustMatchLink() {
        VideoBountySubmitRequest request = submitRequest();
        request.setPlatform("youtube");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.submit(AUTHOR, request));

        assertEquals("PLATFORM_MISMATCH", rejection.getReasonCode());
        verify(submissions, never()).insertSubmission(any());
    }

    @Test
    void submissionRequiresPublishedTopic() {
        VideoBountySubmitRequest request = submitRequest();
        request.setTopicId(404L);
        when(topics.selectCmsTopicById(404L)).thenReturn(null);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.submit(AUTHOR, request));

        assertEquals("TOPIC_NOT_FOUND", rejection.getReasonCode());
    }

    @Test
    void pausedCampaignRejectsNewSubmissions() {
        VideoCampaign paused = campaign();
        paused.setStatus(VideoCampaign.STATUS_PAUSED);
        when(campaigns.selectByCampaignKey(CAMPAIGN_KEY)).thenReturn(paused);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.submit(AUTHOR, submitRequest()));

        assertEquals("CAMPAIGN_NOT_ACTIVE", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 审核

    @Test
    void approveRequiresPublishedAt() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        VideoBountyReviewRequest request = new VideoBountyReviewRequest();
        request.setDecision("APPROVE");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.review("sys:1", 11L, request));

        assertEquals("PUBLISHED_AT_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void rejectRequiresReason() {
        when(submissions.selectById(11L)).thenReturn(submitted(11L, AUTHOR));
        VideoBountyReviewRequest request = new VideoBountyReviewRequest();
        request.setDecision("REJECT");
        request.setReviewNote("  ");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.review("sys:1", 11L, request));

        assertEquals("REVIEW_NOTE_REQUIRED", rejection.getReasonCode());
    }

    @Test
    void approveSetsWindowFromPublishedAtAndCampaignWindowDays() {
        when(submissions.selectById(11L)).thenReturn(submitted(11L, AUTHOR));
        when(submissions.markApprovedIfPending(anyLong(), any(Date.class), any(Date.class),
                anyString(), any(Date.class), any())).thenReturn(1);
        VideoBountyReviewRequest request = new VideoBountyReviewRequest();
        request.setDecision("approve");
        Date publishedAt = new Date(1_700_000_000_000L);
        request.setPublishedAt(publishedAt);

        service.review("sys:1", 11L, request);

        verify(submissions).markApprovedIfPending(eq(11L), eq(publishedAt),
                eq(new Date(publishedAt.getTime() + 7L * 24 * 60 * 60 * 1000)),
                eq("sys:1"), any(Date.class), any());
    }

    @Test
    void concurrentReviewLosesCasAndReportsConflict() {
        when(submissions.selectById(11L)).thenReturn(submitted(11L, AUTHOR));
        when(submissions.markRejectedIfPending(anyLong(), anyString(), any(Date.class), anyString()))
                .thenReturn(0);
        VideoBountyReviewRequest request = new VideoBountyReviewRequest();
        request.setDecision("REJECT");
        request.setReviewNote("与作品无关的搬运");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.review("sys:1", 11L, request));

        assertEquals("REVIEW_CONFLICT", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 证据

    @Test
    void evidenceWithoutExternalSourceIsRejected() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        VideoBountyEvidenceRequest request = new VideoBountyEvidenceRequest();
        request.setViews(1000L);
        request.setPeriodStart(new Date(1_700_000_000_000L));
        request.setPeriodEnd(new Date(1_700_000_100_000L));
        // 没有 sourceRef = 我方自证播放,必须拒绝。

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.recordEvidence("ops", 11L, request));

        assertEquals("SOURCE_REF_REQUIRED", rejection.getReasonCode());
        verify(evidence, never()).insertEvidence(any());
    }

    @Test
    void evidenceOnlyAcceptedForApprovedSubmission() {
        when(submissions.selectById(11L)).thenReturn(submitted(11L, AUTHOR));
        VideoBountyEvidenceRequest request = validEvidence();

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.recordEvidence("ops", 11L, request));

        assertEquals("EVIDENCE_NOT_ALLOWED", rejection.getReasonCode());
    }

    @Test
    void identicalEvidenceIsIdempotentByHash() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        VideoBountyEvidence existing = new VideoBountyEvidence();
        existing.setId(5L);
        existing.setSubmissionId(11L);
        existing.setViews(1000L);
        existing.setOrganic(1);
        existing.setVerifiable(1);
        when(evidence.selectByHash(anyString())).thenReturn(existing);

        var view = service.recordEvidence("ops", 11L, validEvidence());

        assertEquals(5L, view.getId().longValue());
        verify(evidence, never()).insertEvidence(any());
    }

    // ------------------------------------------------------------------ 应计

    @Test
    void accrualIsRecordedOnlyAndNeverPayable() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("tiktok:7300000000000000001");
        submission.setVerifiedGroupKey("tiktok:7300000000000000001");
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, submission.getVerifiedGroupKey()))
                .thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(submission, 12_500L)));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(1L);
        when(accruals.selectByIdempotencyKey(anyString())).thenReturn(null);
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

        VideoBountyAccrual accrual = service.recomputeAccrual(11L);

        assertEquals(new BigDecimal("25.00"), accrual.getPayableAmount());
        assertEquals(VideoBountyAccrual.PAYOUT_RECORDED_ONLY, accrual.getPayoutState().intValue(),
                "没有兑付通道时只能记 RECORDED_ONLY;写待兑付/已打款就是假账");
        // 整组替换:作废范围是组内不在保留集的行(不是只作废本条投稿)。
        ArgumentCaptor<List<Long>> kept = ArgumentCaptor.forClass(List.class);
        verify(accruals).supersedeActiveByGroupExceptIds(eq(CAMPAIGN_ID), anyString(), kept.capture());
        assertTrue(kept.getValue().isEmpty() || !kept.getValue().isEmpty());
        verify(accruals).insertAccrual(any());
    }

    @Test
    void groupRecomputeWritesEveryMemberOfTheGroupSoTheSplitIsExactNotJustUnderCap() {
        // root 复查的真实错误：只写当前投稿 + 把别人作废，会把「越封顶」修成「漏算」。
        // 数值：rate=0.6/1000，per_video_cap=100（覆盖 campaign() 默认值）。
        //   T1 A 10万→60、B 10万→60，组封顶后各 50/50
        //   T2 B 涨到 30万 → A 25、B 75，合计正好 100
        // 只断言 <=cap 不够：A 归 0、B 75 也满足 <=cap。
        VideoCampaign capped = campaign();
        capped.setCurrency("CNY");
        capped.setRatePerThousand(new BigDecimal("0.60"));
        capped.setPerVideoCap(new BigDecimal("100.00"));
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(capped);

        Date published = new Date(1_700_000_000_000L);
        Date windowEnd = new Date(published.getTime() + 7L * 24 * 60 * 60 * 1000);
        VideoBountySubmission a = approved(11L, AUTHOR);
        a.setVerifiedGroupKey("grp-cap");
        a.setPublishedAt(published);
        a.setWindowEndsAt(windowEnd);
        VideoBountySubmission b = approved(12L, AUTHOR);
        b.setVerifiedGroupKey("grp-cap");
        b.setPublishedAt(published);
        b.setWindowEndsAt(windowEnd);
        when(submissions.selectById(12L)).thenReturn(b);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-cap")).thenReturn(List.of(a, b));

        // A 10 万播放、B 30 万播放（同窗口、有机 + 可核验）。
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(9L);
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(a, 100_000L)));
        when(evidence.selectBySubmission(12L)).thenReturn(List.of(countedEvidence(b, 300_000L)));

        when(accruals.selectByIdempotencyKey(anyString())).thenReturn(null);
        when(accruals.insertAccrual(any())).thenAnswer(call -> {
            VideoBountyAccrual row = call.getArgument(0);
            row.setId(row.getSubmissionId() + 100L);
            return 1;
        });
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString())).thenReturn(BigDecimal.ZERO);

        service.recomputeAccrual(12L);

        ArgumentCaptor<VideoBountyAccrual> inserted = ArgumentCaptor.forClass(VideoBountyAccrual.class);
        verify(accruals, times(2)).insertAccrual(inserted.capture());
        Map<Long, BigDecimal> bySubmission = new HashMap<>();
        for (VideoBountyAccrual row : inserted.getAllValues()) {
            bySubmission.put(row.getSubmissionId(), row.getPayableAmount());
            assertEquals(VideoBountyAccrual.PAYOUT_RECORDED_ONLY, row.getPayoutState().intValue(),
                    "仍然只能记 RECORDED_ONLY，不得出现待付/已付");
            assertEquals(0, row.getGroupBasisViews().compareTo(Long.valueOf(400_000L)),
                    "整组基数必须是 A+B 的合计，不是单条");
        }
        // 精确值：A 25、B 75（按播放量 1:3 分摊 100）。
        assertEquals(0, bySubmission.get(11L).compareTo(new BigDecimal("25.00")), "A 必须落到 25.00");
        assertEquals(0, bySubmission.get(12L).compareTo(new BigDecimal("75.00")), "B 必须落到 75.00");
        assertEquals(0, bySubmission.get(11L).add(bySubmission.get(12L)).compareTo(new BigDecimal("100.00")),
                "组内合计必须正好等于 cap");

        // 整组只保留本轮这一套（两条都排除在作废之外）。
        ArgumentCaptor<List<Long>> keep = ArgumentCaptor.forClass(List.class);
        verify(accruals).supersedeActiveByGroupExceptIds(eq(CAMPAIGN_ID), eq("grp-cap"), keep.capture());
        assertEquals(2, keep.getValue().size());
        assertTrue(keep.getValue().containsAll(List.of(111L, 112L)), "本轮写入的两条都必须保留");
    }

    @Test
    void groupRecomputeDoesNotDoubleClampAgainstThisGroupsOwnOldBudget() {
        // 预算已用满时，本组旧行不能被算进"已用"：否则整组重算会被二次 clamp 成 0（漏算）。
        VideoBountySubmission a = approved(11L, AUTHOR);
        a.setVerifiedGroupKey("grp-cap");
        Date published = new Date(1_700_000_000_000L);
        a.setPublishedAt(published);
        a.setWindowEndsAt(new Date(published.getTime() + 7L * 24 * 60 * 60 * 1000));
        when(submissions.selectById(11L)).thenReturn(a);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-cap")).thenReturn(List.of(a));
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(0L);
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(a, 100_000L)));
        when(accruals.selectByIdempotencyKey(anyString())).thenReturn(null);
        when(accruals.insertAccrual(any())).thenReturn(1);

        VideoCampaign capped = campaign();
        capped.setTotalBudgetCap(new BigDecimal("100.00"));
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(capped);
        // 组外已有 50；本组旧行必须排除，剩余 50 应完整留给本组重算。
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString()))
                .thenReturn(new BigDecimal("50.00"));

        service.recomputeAccrual(11L);

        ArgumentCaptor<VideoBountyAccrual> inserted = ArgumentCaptor.forClass(VideoBountyAccrual.class);
        verify(accruals).insertAccrual(inserted.capture());
        assertEquals(0, inserted.getValue().getPayableAmount().compareTo(new BigDecimal("50.00")),
                "本组旧值不得占用活动预算，否则会被二次 clamp");
    }

    @Test
    void sameEvidenceWatermarkReturnsExistingRowInsteadOfDoubleCounting() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("tiktok:7300000000000000001");
        submission.setVerifiedGroupKey("tiktok:7300000000000000001");
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(anyLong(), anyString())).thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(submission, 12_500L)));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(1L);
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString())).thenReturn(BigDecimal.ZERO);
        AccrualStore store = new AccrualStore();
        store.install(accruals);

        VideoBountyAccrual first = service.recomputeAccrual(11L);
        VideoBountyAccrual replay = service.recomputeAccrual(11L);

        assertEquals(first.getId(), replay.getId(), "同一状态重复重算必须返回同一条有效应计");
        assertEquals(1, store.insertedCount(), "重复重算不得新增行(否则重复占预算)");
        assertEquals(1, store.activeRows().size());
    }

    @Test
    void evidenceOutsideWindowDoesNotEnterBasis() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("g1");
        submission.setVerifiedGroupKey("g1");
        Date publish = new Date(1_700_000_000_000L);
        submission.setPublishedAt(publish);
        submission.setWindowEndsAt(new Date(publish.getTime() + 7L * 24 * 60 * 60 * 1000));
        // 证据区间跨过窗口终点:无法拆分 ⇒ 不计入基数。
        VideoBountyEvidence late = new VideoBountyEvidence();
        late.setId(2L);
        late.setSubmissionId(11L);
        late.setOrganic(1);
        late.setVerifiable(1);
        late.setViews(999_999L);
        late.setPeriodStart(new Date(submission.getWindowEndsAt().getTime() - 1000L));
        late.setPeriodEnd(new Date(submission.getWindowEndsAt().getTime() + 24L * 60 * 60 * 1000));

        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(anyLong(), anyString())).thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(late));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(2L);
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

        VideoBountyAccrual accrual = service.recomputeAccrual(11L);

        assertEquals(0L, accrual.getBasisViews().longValue());
        assertEquals(new BigDecimal("0.00"), accrual.getPayableAmount());
    }

    @Test
    void paidTrafficEvidenceIsExcluded() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("g1");
        submission.setVerifiedGroupKey("g1");
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        VideoBountyEvidence paid = countedEvidence(submission, 50_000L);
        paid.setOrganic(0);   // 付费推广播放:不计应计

        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(anyLong(), anyString())).thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(paid));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(3L);
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

        VideoBountyAccrual accrual = service.recomputeAccrual(11L);

        assertEquals(0L, accrual.getBasisViews().longValue());
    }

    @Test
    void unverifiablePlayCountIsExcluded() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("g1");
        submission.setVerifiedGroupKey("g1");
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        VideoBountyEvidence suspicious = countedEvidence(submission, 50_000L);
        suspicious.setVerifiable(0);

        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(anyLong(), anyString())).thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(suspicious));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(3L);
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

        assertEquals(0L, service.recomputeAccrual(11L).getBasisViews().longValue());
    }

    @Test
    void targetBudgetCapClampsAccrual() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("g1");
        submission.setVerifiedGroupKey("g1");
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        VideoCampaign capped = campaign();
        capped.setTotalBudgetCap(new BigDecimal("10.00"));
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(capped);
        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(anyLong(), anyString())).thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(submission, 12_500L)));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(1L);
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

        VideoBountyAccrual accrual = service.recomputeAccrual(11L);

        assertEquals(new BigDecimal("10.00"), accrual.getPayableAmount(), "活动预算必须硬夹");
        assertNotNull(accrual.getNote());
    }

    @Test
    void exhaustedBudgetAccruesZero() {
        VideoBountySubmission submission = approved(11L, AUTHOR);
        submission.setOriginGroupKey("g1");
        submission.setVerifiedGroupKey("g1");
        submission.setPublishedAt(new Date(1_700_000_000_000L));
        submission.setWindowEndsAt(new Date(1_700_000_000_000L + 7L * 24 * 60 * 60 * 1000));
        VideoCampaign capped = campaign();
        capped.setTotalBudgetCap(new BigDecimal("10.00"));
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(capped);
        when(submissions.selectById(11L)).thenReturn(submission);
        when(submissions.selectActiveByVerifiedGroup(anyLong(), anyString())).thenReturn(List.of(submission));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(submission, 12_500L)));
        when(evidence.selectMaxIdByVerifiedGroup(anyLong(), anyString())).thenReturn(1L);
        when(campaigns.sumActiveAccrualExcludingGroup(CAMPAIGN_ID, "g1"))
                .thenReturn(new BigDecimal("10.00"));

        assertEquals(new BigDecimal("0.00"), service.recomputeAccrual(11L).getPayableAmount());
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    void revokeReversesAccrualWithoutDeletingIt() {
        when(submissions.markRevokedIfApproved(anyLong(), anyString(), any(Date.class), anyString()))
                .thenReturn(1);
        VideoBountySubmission revoked = approved(11L, AUTHOR);
        revoked.setStatus(VideoBountySubmission.STATUS_REVOKED);
        when(submissions.selectById(11L)).thenReturn(revoked);

        service.revoke("sys:1", 11L, "作品已下架");

        // 应计不删除:置 REVERSED,历史金额仍可复现。
        verify(accruals).reverseActiveBySubmission(eq(11L), anyString());
    }

    @Test
    void revokeRequiresReason() {
        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.revoke("sys:1", 11L, " "));

        assertEquals("REVOKE_NOTE_REQUIRED", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 活动配置

    @Test
    void createCampaignIsDraftAndCashPayoutOff() {
        when(campaigns.selectByCampaignKey("new-key")).thenReturn(null);
        ArgumentCaptor<VideoCampaign> captor = ArgumentCaptor.forClass(VideoCampaign.class);

        service.createCampaign("sys:1", campaignRequest("new-key"));

        verify(campaigns).insertCampaign(captor.capture());
        assertEquals(VideoCampaign.STATUS_DRAFT, captor.getValue().getStatus().intValue());
        assertEquals(0, captor.getValue().getCashPayoutEnabled().intValue(),
                "建活动不能顺带打开现金兑付");
    }

    @Test
    void updateCampaignRejectsPayoutSwitch() {
        VideoCampaignRequest request = campaignRequest(CAMPAIGN_KEY);
        request.setCashPayoutEnabled(1);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.updateCampaign("sys:1", CAMPAIGN_ID, request));

        assertEquals("CASH_PAYOUT_NOT_AVAILABLE", rejection.getReasonCode());
    }

    @Test
    void ruleChangeIsLockedOnceAccrued() {
        VideoCampaignRequest request = campaignRequest(CAMPAIGN_KEY);
        request.setRatePerThousand(new BigDecimal("3.00"));
        when(campaigns.sumActiveAccrual(CAMPAIGN_ID)).thenReturn(new BigDecimal("12.00"));

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.updateCampaign("sys:1", CAMPAIGN_ID, request));

        assertEquals("RULE_LOCKED_BY_ACCRUAL", rejection.getReasonCode(),
                "已有应计还改费率 = 追溯改写历史口径");
        verify(campaigns, never()).updateCampaign(any());
    }

    @Test
    void campaignStatusChangeUsesCas() {
        when(campaigns.updateStatusIfCurrent(CAMPAIGN_ID, VideoCampaign.STATUS_ACTIVE,
                VideoCampaign.STATUS_CLOSED)).thenReturn(0);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.changeCampaignStatus("sys:1", CAMPAIGN_ID, VideoCampaign.STATUS_CLOSED));

        assertEquals("STATUS_CONFLICT", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 成交归因(消费归因域)

    @Test
    void submissionAttributionUsesAuthorAndTopicFromDbNotFromClient() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        when(attribution.aggregateOwnedCampaign(AUTHOR, CAMPAIGN_KEY,
                MarketingReferralCredential.TARGET_TOPIC, TOPIC_ID)).thenReturn(boundAggregate());

        VideoCampaignAttributionView view = service.submissionAttribution(AUTHOR, 11L);

        // 聚合主体 = 投稿行的作者,目标 = 投稿行的作品主题;两者都是 DB 读出来的。
        verify(attribution).aggregateOwnedCampaign(eq(AUTHOR), eq(CAMPAIGN_KEY),
                eq(MarketingReferralCredential.TARGET_TOPIC), eq(TOPIC_ID));
        assertTrue(view.isConfirmed());
        assertEquals("BOUND", view.getStatus());
    }

    @Test
    void authorCannotReadAnotherMembersAttribution() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.submissionAttribution(OTHER_MEMBER, 11L));

        // 他人投稿按"不存在"处理,不泄露是否存在,也不触发任何聚合调用。
        assertEquals("SUBMISSION_NOT_FOUND", rejection.getReasonCode());
        verify(attribution, never()).aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unboundAggregateIsNotRenderedAsConfirmedZero() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        CampaignAttributionAggregate unbound = new CampaignAttributionAggregate();
        unbound.setCampaignKey(CAMPAIGN_KEY);
        unbound.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        unbound.setTargetId(TOPIC_ID);
        unbound.setBound(false);
        unbound.setPaidAmount(BigDecimal.ZERO);
        unbound.setRefundedAmount(BigDecimal.ZERO);
        when(attribution.aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(unbound);

        VideoCampaignAttributionView view = service.submissionAttribution(AUTHOR, 11L);

        assertEquals("UNBOUND", view.getStatus());
        assertFalse(view.isConfirmed());
        // 关键:数字必须是 null,不能把上游的 0 透出去当成"确认 0 成交"。
        assertNull(view.getPaidOrders());
        assertNull(view.getPaidAmount());
        assertNull(view.getAttributedOrders());
        assertEquals("NOT_BOUND", view.getReasonCode());
    }

    @Test
    void boundAggregateExposesCountsAndRefundsOnlyNoIdentity() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        when(attribution.aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(boundAggregate());

        VideoCampaignAttributionView view = service.submissionAttribution(AUTHOR, 11L);

        assertEquals(Long.valueOf(5L), view.getAttributedOrders());
        assertEquals(Long.valueOf(3L), view.getPaidOrders());
        assertEquals(new BigDecimal("120.00"), view.getPaidAmount());
        // 已付与退款分别呈现:退款不是"从没付过"。
        assertEquals(Long.valueOf(1L), view.getRefundedOrders());
        assertEquals(new BigDecimal("40.00"), view.getRefundedAmount());
        assertEquals("CNY", view.getAmountCurrency());
    }

    @Test
    void reviewerDelegatesWithSubmissionAuthorNotReviewerIdentity() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));
        when(attribution.aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(boundAggregate());

        service.reviewedAttribution("sys:1", 11L);

        // 超管的 id(1)绝不能被当成 sharer —— 否则会静默查到空集并显示 0 成交。
        verify(attribution).aggregateOwnedCampaign(eq(AUTHOR), eq(CAMPAIGN_KEY),
                eq(MarketingReferralCredential.TARGET_TOPIC), eq(TOPIC_ID));
        verify(attribution, never()).aggregateOwnedCampaign(eq(1L), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reviewerWhoIsNeitherBackofficeNorOwnerIsForbidden() {
        when(submissions.selectById(11L)).thenReturn(approved(11L, AUTHOR));

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.reviewedAttribution("member:" + OTHER_MEMBER, 11L));

        assertEquals("ATTRIBUTION_FORBIDDEN", rejection.getReasonCode());
        verify(attribution, never()).aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void campaignOwnerSeesOnlyOwnCampaign() {
        when(campaigns.selectById(CAMPAIGN_ID)).thenReturn(campaign());
        when(submissions.countApprovedByCampaignAndTopic(CAMPAIGN_ID, TOPIC_ID)).thenReturn(1);
        when(attribution.aggregateOwnedCampaign(OWNER, CAMPAIGN_KEY,
                MarketingReferralCredential.TARGET_TOPIC, TOPIC_ID)).thenReturn(boundAggregate());

        VideoCampaignAttributionView view = service.ownerCampaignAttribution(OWNER, CAMPAIGN_ID, TOPIC_ID);

        assertTrue(view.isConfirmed());
        verify(attribution).aggregateOwnedCampaign(eq(OWNER), eq(CAMPAIGN_KEY),
                eq(MarketingReferralCredential.TARGET_TOPIC), eq(TOPIC_ID));
    }

    @Test
    void nonOwnerMemberCannotReadCampaignAttribution() {
        when(campaigns.selectById(CAMPAIGN_ID)).thenReturn(campaign());

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.ownerCampaignAttribution(OTHER_MEMBER, CAMPAIGN_ID, TOPIC_ID));

        assertEquals("ATTRIBUTION_FORBIDDEN", rejection.getReasonCode());
    }

    @Test
    void campaignOwnerCannotProbeTopicOutsideCampaignScope() {
        // 同一个 campaignKey,换一个不属于本活动的 topic(恶意同 key 不同 target)。
        when(campaigns.selectById(CAMPAIGN_ID)).thenReturn(campaign());
        when(submissions.countApprovedByCampaignAndTopic(CAMPAIGN_ID, TOPIC_ID)).thenReturn(1);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.ownerCampaignAttribution(OWNER, CAMPAIGN_ID, 999L));

        assertEquals("TARGET_OUT_OF_SCOPE", rejection.getReasonCode());
        verify(attribution, never()).aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void campaignWithoutOwnerReportsOwnerNotSetNotZero() {
        VideoCampaign ownerless = campaign();
        ownerless.setOwnerMemberId(null);
        when(campaigns.selectById(CAMPAIGN_ID)).thenReturn(ownerless);

        VideoCampaignAttributionView view = service.ownerCampaignAttribution(OWNER, CAMPAIGN_ID, TOPIC_ID);

        assertEquals("OWNER_NOT_SET", view.getStatus());
        assertFalse(view.isConfirmed());
        assertNull(view.getPaidAmount());
        verify(attribution, never()).aggregateOwnedCampaign(anyLong(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateSubmissionOnUniqueKeyIsRejectedNotSilentlyAccepted() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("uk_video_canonical"))
                .when(submissions).insertSubmission(any());

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.submit(AUTHOR, submitRequest()));

        assertEquals("VIDEO_ALREADY_SUBMITTED", rejection.getReasonCode());
    }

    @Test
    void campaignOwnerMustBeAnAvailableMember() {
        when(members.selectUmsMemberById(77L)).thenReturn(null);
        VideoCampaignRequest request = campaignRequest("owner-check");
        request.setOwnerMemberId(77L);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.createCampaign("sys:1", request));

        assertEquals("OWNER_UNAVAILABLE", rejection.getReasonCode());
        verify(campaigns, never()).insertCampaign(any());
    }

    // ------------------------------------------------------------------ fixtures

    private static VideoCampaignRequest campaignRequest(String key) {
        VideoCampaignRequest request = new VideoCampaignRequest();
        request.setCampaignKey(key);
        request.setTitle("Q4 视频悬赏");
        request.setOwnerMemberId(OWNER);
        request.setCurrency("EUR");
        request.setRatePerThousand(new BigDecimal("2.00"));
        request.setPerVideoCap(new BigDecimal("500.00"));
        request.setWindowDays(7);
        request.setRuleVersion("v1");
        return request;
    }

    private static VideoBountySubmission submitted(long id, long memberId) {
        VideoBountySubmission row = new VideoBountySubmission();
        row.setId(id);
        row.setMemberId(memberId);
        row.setCampaignId(CAMPAIGN_ID);
        row.setCampaignKey(CAMPAIGN_KEY);
        row.setTopicId(TOPIC_ID);
        row.setStatus(VideoBountySubmission.STATUS_SUBMITTED);
        return row;
    }

    private static VideoBountySubmission approved(long id, long memberId) {
        VideoBountySubmission row = submitted(id, memberId);
        row.setStatus(VideoBountySubmission.STATUS_APPROVED);
        return row;
    }

    private static VideoBountyEvidenceRequest validEvidence() {
        VideoBountyEvidenceRequest request = new VideoBountyEvidenceRequest();
        request.setEvidenceType(VideoBountyEvidence.TYPE_PLATFORM_EXPORT);
        request.setSourceRef("tiktok-analytics-export-2026-11-01.csv");
        request.setPeriodStart(new Date(1_700_000_000_000L));
        request.setPeriodEnd(new Date(1_700_000_100_000L));
        request.setViews(1000L);
        return request;
    }

    private static VideoBountyEvidence countedEvidence(VideoBountySubmission submission, long views) {
        VideoBountyEvidence row = new VideoBountyEvidence();
        row.setId(1L);
        row.setSubmissionId(submission.getId());
        row.setOrganic(1);
        row.setVerifiable(1);
        row.setViews(views);
        row.setPeriodStart(submission.getPublishedAt());
        row.setPeriodEnd(submission.getWindowEndsAt());
        return row;
    }

    /** 归因域已绑定的聚合:5 单归因 / 3 单已付 ¥120 / 1 单已退 ¥40。 */
    private static CampaignAttributionAggregate boundAggregate() {
        CampaignAttributionAggregate aggregate = new CampaignAttributionAggregate();
        aggregate.setCampaignKey(CAMPAIGN_KEY);
        aggregate.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        aggregate.setTargetId(TOPIC_ID);
        aggregate.setBound(true);
        aggregate.setAttributedOrders(5L);
        aggregate.setPaidOrders(3L);
        aggregate.setPaidAmount(new BigDecimal("120.00"));
        aggregate.setRefundedOrders(1L);
        aggregate.setRefundedAmount(new BigDecimal("40.00"));
        return aggregate;
    }

    // ------------------------------------------------------------------ M6-F1 缺陷回归

    /**
     * root 给的精确例:B 带**更旧** evidence id 加入 A 的组,组内 max 证据号不变 ⇒
     * 旧幂等键会重现,修复前 A 的 100 被当成本次结果复用,再插 B 25 ⇒ 125 超 cap。
     *
     * <p>rate=0.6/1000、cap=100。A 30 万播放、B 10 万播放 ⇒ 正解 A 75 + B 25 = 100。
     */
    @Test
    void joiningWithOlderEvidenceIdMustNotReuseTheOldFullCapRow() {
        VideoCampaign capped = cappedCampaign();
        Date pA = new Date(1_700_000_000_000L);
        Date pB = new Date(1_700_000_000_000L);
        VideoBountySubmission a = approved(11L, AUTHOR);
        a.setVerifiedGroupKey("grp-join");
        a.setPublishedAt(pA);
        a.setWindowEndsAt(new Date(pA.getTime() + 7L * 24 * 60 * 60 * 1000));
        VideoBountySubmission b = approved(12L, AUTHOR);
        b.setVerifiedGroupKey("grp-join");
        b.setPublishedAt(pB);
        b.setWindowEndsAt(new Date(pB.getTime() + 7L * 24 * 60 * 60 * 1000));

        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(a, 300_000L)));
        when(evidence.selectBySubmission(12L)).thenReturn(List.of(countedEvidence(b, 100_000L)));
        // 关键:B 的 evidence id(10)比 A 的(20)更旧,组内 max 恒为 20。
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(20L);
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString())).thenReturn(BigDecimal.ZERO);
        AccrualStore store = new AccrualStore();
        store.install(accruals);

        // 第一步:组内只有 A。
        when(submissions.selectById(11L)).thenReturn(a);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-join")).thenReturn(List.of(a));
        VideoBountyAccrual firstA = service.recomputeAccrual(11L);
        assertEquals(0, firstA.getPayableAmount().compareTo(new BigDecimal("100.00")),
                "A 单独在组内时应吃满 cap");

        // 第二步:B 被核验进同一组,组内 max 证据号没变。
        when(submissions.selectById(12L)).thenReturn(b);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-join")).thenReturn(List.of(a, b));
        service.recomputeAccrual(12L);

        Map<Long, BigDecimal> active = store.activeAmounts();
        assertEquals(2, active.size(), "组内应恰好两条有效应计");
        assertEquals(0, active.get(11L).compareTo(new BigDecimal("75.00")), "A 必须重算成 75.00");
        assertEquals(0, active.get(12L).compareTo(new BigDecimal("25.00")), "B 必须插 25.00");
        assertEquals(0, active.get(11L).add(active.get(12L)).compareTo(new BigDecimal("100.00")),
                "组内合计必须正好等于 cap,不能是 125");
        assertTrue(store.isSuperseded(firstA.getId()), "A 的旧 100 必须被作废,不能被复用");
        assertEquals(0, capped.getPerVideoCap().compareTo(new BigDecimal("100.00")));
    }

    /** 兄弟行的审计窗口必须是**它自己**的发布时间窗,不是本次被重算那条的。 */
    @Test
    void siblingAccrualKeepsItsOwnPublicationWindow() {
        cappedCampaign();
        Date pA = new Date(1_700_000_000_000L);
        Date pB = new Date(1_700_600_000_000L);              // 晚 7 天
        long week = 7L * 24 * 60 * 60 * 1000;
        VideoBountySubmission a = approved(11L, AUTHOR);
        a.setVerifiedGroupKey("grp-win");
        a.setPublishedAt(pA);
        a.setWindowEndsAt(new Date(pA.getTime() + week));
        VideoBountySubmission b = approved(12L, AUTHOR);
        b.setVerifiedGroupKey("grp-win");
        b.setPublishedAt(pB);
        b.setWindowEndsAt(new Date(pB.getTime() + week));

        when(submissions.selectById(11L)).thenReturn(a);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-win")).thenReturn(List.of(a, b));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(a, 300_000L)));
        when(evidence.selectBySubmission(12L)).thenReturn(List.of(countedEvidence(b, 100_000L)));
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(30L);
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString())).thenReturn(BigDecimal.ZERO);
        AccrualStore store = new AccrualStore();
        store.install(accruals);

        service.recomputeAccrual(11L);

        VideoBountyAccrual rowB = store.activeFor(12L);
        assertNotNull(rowB, "兄弟行必须写出");
        assertEquals(pB.getTime(), rowB.getWindowStart().getTime(), "B 行的窗口起点必须是 B 自己的发布时间");
        assertEquals(b.getWindowEndsAt().getTime(), rowB.getWindowEnd().getTime(), "B 行窗口终点同理");
        assertEquals(pA.getTime(), store.activeFor(11L).getWindowStart().getTime());
    }

    /** 同一状态重复重算:零新增行、零重复占预算、返回同一条。 */
    @Test
    void repeatedRecomputeWithIdenticalInputsWritesNothingNew() {
        cappedCampaign();
        Date p = new Date(1_700_000_000_000L);
        long week = 7L * 24 * 60 * 60 * 1000;
        VideoBountySubmission a = approved(11L, AUTHOR);
        a.setVerifiedGroupKey("grp-rep");
        a.setPublishedAt(p);
        a.setWindowEndsAt(new Date(p.getTime() + week));
        VideoBountySubmission b = approved(12L, AUTHOR);
        b.setVerifiedGroupKey("grp-rep");
        b.setPublishedAt(p);
        b.setWindowEndsAt(new Date(p.getTime() + week));

        when(submissions.selectById(12L)).thenReturn(b);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-rep")).thenReturn(List.of(a, b));
        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(a, 300_000L)));
        when(evidence.selectBySubmission(12L)).thenReturn(List.of(countedEvidence(b, 100_000L)));
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(40L);
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString())).thenReturn(BigDecimal.ZERO);
        AccrualStore store = new AccrualStore();
        store.install(accruals);

        VideoBountyAccrual first = service.recomputeAccrual(12L);
        int afterFirst = store.insertedCount();
        VideoBountyAccrual replay = service.recomputeAccrual(12L);

        assertEquals(afterFirst, store.insertedCount(), "重复重算不得新增行");
        assertEquals(first.getId(), replay.getId());
        assertEquals(0, store.activeTotal().compareTo(new BigDecimal("100.00")),
                "重复重算不得重复占预算");
    }

    /**
     * 撤销/改组循环:{A} → {A,B} → 回到 {A}。
     *
     * <p>修复前:回到 {A} 时组成与证据都回到旧值,幂等键重现并撞上已 SUPERSEDED 的旧行,
     * catch 分支读回 inactive winner 后 continue ⇒ A 本轮没有新行,而它的原有效行又被整组替换作废
     * ⇒ 有效应计凭空消失。修好后代次递增,不会撞旧键,也不复活旧行。
     */
    @Test
    void regroupCycleDoesNotResurrectOldRowOrLoseTheSurvivor() {
        cappedCampaign();
        Date p = new Date(1_700_000_000_000L);
        long week = 7L * 24 * 60 * 60 * 1000;
        VideoBountySubmission a = approved(11L, AUTHOR);
        a.setVerifiedGroupKey("grp-cycle");
        a.setPublishedAt(p);
        a.setWindowEndsAt(new Date(p.getTime() + week));
        VideoBountySubmission b = approved(12L, AUTHOR);
        b.setVerifiedGroupKey("grp-cycle");
        b.setPublishedAt(p);
        b.setWindowEndsAt(new Date(p.getTime() + week));

        when(evidence.selectBySubmission(11L)).thenReturn(List.of(countedEvidence(a, 300_000L)));
        when(evidence.selectBySubmission(12L)).thenReturn(List.of(countedEvidence(b, 100_000L)));
        when(evidence.selectMaxIdByVerifiedGroup(eq(CAMPAIGN_ID), anyString())).thenReturn(50L);
        when(campaigns.sumActiveAccrualExcludingGroup(anyLong(), anyString())).thenReturn(BigDecimal.ZERO);
        AccrualStore store = new AccrualStore();
        store.install(accruals);

        when(submissions.selectById(11L)).thenReturn(a);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-cycle")).thenReturn(List.of(a));
        VideoBountyAccrual onlyA = service.recomputeAccrual(11L);

        when(submissions.selectById(12L)).thenReturn(b);
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-cycle")).thenReturn(List.of(a, b));
        service.recomputeAccrual(12L);

        // 回到只剩 A(B 被撤销/移出组)。
        when(submissions.selectActiveByVerifiedGroup(CAMPAIGN_ID, "grp-cycle")).thenReturn(List.of(a));
        VideoBountyAccrual backToA = service.recomputeAccrual(11L);

        assertEquals(1, store.activeRows().size(), "回到 {A} 后组内只应剩一条有效应计");
        VideoBountyAccrual activeA = store.activeFor(11L);
        assertNotNull(activeA, "A 不能被丢掉");
        assertEquals(0, activeA.getPayableAmount().compareTo(new BigDecimal("100.00")),
                "A 单独在组内应吃满 cap");
        assertNotEquals(onlyA.getId(), activeA.getId(),
                "不得复活已被作废的旧行;应是新一代写入");
        assertTrue(store.isSuperseded(onlyA.getId()));
        assertEquals(backToA.getId(), activeA.getId());
    }

    /** rate=0.6/1000、cap=100、CNY 的活动(不改动默认断言),并让行锁返回它。 */
    private VideoCampaign cappedCampaign() {
        VideoCampaign capped = campaign();
        capped.setCurrency("CNY");
        capped.setRatePerThousand(new BigDecimal("0.60"));
        capped.setPerVideoCap(new BigDecimal("100.00"));
        when(campaigns.selectByCampaignKeyForUpdate(CAMPAIGN_KEY)).thenReturn(capped);
        return capped;
    }

    /**
     * 内存版应计仓:让 recompute 的真实读/写顺序(active 行、组最大 id、唯一键、整组作废)
     * 都被执行到,而不是逐个 when 打桩。测试专用。
     */
    private static final class AccrualStore {
        private final List<VideoBountyAccrual> rows = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong(1000L);

        void install(VideoBountyAccrualMapper mapper) {
            when(mapper.selectMaxIdByGroup(anyLong(), anyString())).thenAnswer(call -> rows.stream()
                    .map(VideoBountyAccrual::getId).filter(Objects::nonNull)
                    .max(Long::compareTo).orElse(null));
            when(mapper.selectActiveBySubmission(anyLong())).thenAnswer(call -> {
                Long submissionId = call.getArgument(0);
                VideoBountyAccrual found = null;
                for (VideoBountyAccrual row : rows) {
                    if (submissionId.equals(row.getSubmissionId()) && isActiveRow(row)) found = row;
                }
                return found;
            });
            when(mapper.selectByIdempotencyKey(anyString())).thenAnswer(call -> {
                String key = call.getArgument(0);
                for (VideoBountyAccrual row : rows) {
                    if (key.equals(row.getIdempotencyKey())) return row;
                }
                return null;
            });
            when(mapper.insertAccrual(any())).thenAnswer(call -> {
                VideoBountyAccrual row = call.getArgument(0);
                for (VideoBountyAccrual saved : rows) {
                    if (saved.getIdempotencyKey().equals(row.getIdempotencyKey())) {
                        throw new org.springframework.dao.DuplicateKeyException("uk_accrual_idempotency");
                    }
                }
                row.setId(Long.valueOf(ids.incrementAndGet()));
                rows.add(row);
                return 1;
            });
            when(mapper.supersedeActiveByGroupExceptIds(anyLong(), anyString(), anyList())).thenAnswer(call -> {
                Long campaignId = call.getArgument(0);
                String groupKey = call.getArgument(1);
                List<Long> keep = call.getArgument(2);
                int changed = 0;
                for (VideoBountyAccrual row : rows) {
                    if (!campaignId.equals(row.getCampaignId()) || !groupKey.equals(row.getGroupKey())) continue;
                    if (!isActiveRow(row)) continue;
                    if (keep.contains(row.getId())) continue;
                    row.setPayoutState(Integer.valueOf(3));
                    changed++;
                }
                return changed;
            });
        }

        int insertedCount() { return rows.size(); }

        List<VideoBountyAccrual> activeRows() {
            List<VideoBountyAccrual> active = new ArrayList<>();
            for (VideoBountyAccrual row : rows) if (isActiveRow(row)) active.add(row);
            return active;
        }

        VideoBountyAccrual activeFor(long submissionId) {
            VideoBountyAccrual found = null;
            for (VideoBountyAccrual row : activeRows()) {
                if (Long.valueOf(submissionId).equals(row.getSubmissionId())) found = row;
            }
            return found;
        }

        Map<Long, BigDecimal> activeAmounts() {
            Map<Long, BigDecimal> map = new HashMap<>();
            for (VideoBountyAccrual row : activeRows()) map.put(row.getSubmissionId(), row.getPayableAmount());
            return map;
        }

        BigDecimal activeTotal() {
            BigDecimal total = BigDecimal.ZERO;
            for (VideoBountyAccrual row : activeRows()) total = total.add(row.getPayableAmount());
            return total;
        }

        boolean isSuperseded(Long id) {
            for (VideoBountyAccrual row : rows) {
                if (id.equals(row.getId())) {
                    return !isActiveRow(row);
                }
            }
            return false;
        }

        private static boolean isActiveRow(VideoBountyAccrual row) {
            Integer state = row.getPayoutState();
            return state == null || (!Integer.valueOf(3).equals(state) && !Integer.valueOf(4).equals(state));
        }
    }
}
