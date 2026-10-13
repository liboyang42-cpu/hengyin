package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.ContributionRewardLedger;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.MerchantSafetyAggregate;
import com.chengyinhub.business.domain.MerchantSafetyRating;
import com.chengyinhub.business.domain.MerchantSafetyPolicy;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.CommunityReportAppealRequest;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionReviewRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSupplementRequest;
import com.chengyinhub.business.domain.dto.MerchantSafetyRatingRequest;
import com.chengyinhub.business.domain.vo.MerchantSafetySummaryVO;
import com.chengyinhub.business.domain.vo.CommunityReportPlatformVO;
import com.chengyinhub.business.mapper.CommunityTrustMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.CommunityTrustServiceImpl;
import com.chengyinhub.business.service.support.CommunityReportPolicy;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityTrustServiceImplTest {
    private static final long MEMBER = 101L;
    private static final long SUBJECT = 102L;
    private static final long MERCHANT = 201L;
    private static final long REPORT = 301L;
    private static final long CONTRIBUTION = 401L;
    private static final String REPORT_REQUEST = "report-member-102-harassment-v1";
    private static final String CONTRIBUTION_REQUEST = "merchant-201-address-v1";
    private static final String SAFETY_REQUEST = "merchant-201-safety-202608";
    private static final String ADDRESS = "address";
    private static final String PROPOSED_ADDRESS = "上海市黄浦区测试路 1 号";
    private static final String EXPECTED_REWARD_BUSINESS_KEY = "CONTRIBUTION_REWARD:401";
    private static final String SAMPLE_INSUFFICIENT = "INSUFFICIENT";
    private static final String SAMPLE_SUFFICIENT = "SUFFICIENT";
    private static final String SAFETY_DISCLAIMER = "PERCEPTION_NOT_GUARANTEE";
    private static final String SAFETY_POLICY_PENDING = "POLICY_PENDING";

    private CommunityTrustMapper mapper;
    private CommunityPostMapper communityPosts;
    private MmsMerchantMapper merchants;
    private CommunityTrustServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityTrustMapper.class);
        communityPosts = mock(CommunityPostMapper.class);
        merchants = mock(MmsMerchantMapper.class);
        service = new CommunityTrustServiceImpl();
        ReflectionTestUtils.setField(service, "communityTrustMapper", mapper);
        ReflectionTestUtils.setField(service, "communityPostMapper", communityPosts);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchants);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setStatus(1L);
        merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantById(MERCHANT)).thenReturn(merchant);
        when(mapper.selectVerifiedVisitForSafety(MEMBER, MERCHANT)).thenReturn(801L);
        MerchantSafetyPolicy policy = new MerchantSafetyPolicy();
        policy.setMinimumSampleCount(5);
        policy.setWindowDays(90);
        policy.setNeutralMinimumScore(1.75D);
        policy.setSafeMinimumScore(2.5D);
        when(mapper.selectActiveSafetyPolicy()).thenReturn(policy);
        when(communityPosts.insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), any(),
                eq("NOTIFICATION_REQUESTED"), anyString(), any())).thenReturn(1);
    }

    @Test
    void reportReplayReturnsTheOriginalReceiptOnlyForTheSameStablePayload() {
        CommunityReportSubmitRequest request = reportRequest();
        when(mapper.insertReport(any(CommunityReportCase.class))).thenAnswer(call -> {
            call.getArgument(0, CommunityReportCase.class).setId(REPORT);
            return 1;
        });
        when(mapper.insertReportTimeline(any())).thenReturn(1);

        CommunityReportCase first = service.submitReport(MEMBER, request);
        when(mapper.selectReportByRequestForUpdate(MEMBER, REPORT_REQUEST)).thenReturn(first);
        CommunityReportCase replay = service.submitReport(MEMBER, request);

        assertEquals(REPORT, replay.getId());
        verify(mapper).insertReport(any(CommunityReportCase.class));
        verify(communityPosts).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(MEMBER),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());

        request.setDescription("另一份不相同的事实说明");
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.submitReport(MEMBER, request));
        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
    }

    @Test
    void legacyReportReplaySurvivesTheNullablePreDeployCompatibilityWindow() {
        CommunityReportSubmitRequest request = reportRequest();
        CommunityReportCase legacy = new CommunityReportCase();
        legacy.setId(REPORT);
        legacy.setPayloadHash(ReflectionTestUtils.invokeMethod(service, "hash",
                "MEMBER|102|HARASSMENT|线下活动中持续骚扰|[]"));
        when(mapper.selectReportByRequestForUpdate(MEMBER, REPORT_REQUEST)).thenReturn(legacy);

        CommunityReportCase replay = service.submitReport(MEMBER, request);

        assertEquals(REPORT, replay.getId());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void legacyReportReplaySurvivesPostDeployLegacyVersionBackfill() {
        CommunityReportSubmitRequest request = reportRequest();
        CommunityReportCase legacy = new CommunityReportCase();
        legacy.setId(REPORT);
        legacy.setPolicyVersion(CommunityReportPolicy.LEGACY_VERSION);
        legacy.setPayloadHash(ReflectionTestUtils.invokeMethod(service, "hash",
                "MEMBER|102|HARASSMENT|线下活动中持续骚扰|[]"));
        when(mapper.selectReportByRequestForUpdate(MEMBER, REPORT_REQUEST)).thenReturn(legacy);

        CommunityReportCase replay = service.submitReport(MEMBER, request);

        assertEquals(REPORT, replay.getId());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportTaxonomyPublishesStableLabelsPriorityAndFirstResponseSla() {
        Map<String, Object> taxonomy = service.getReportTaxonomy();

        assertEquals("COMMUNITY_REPORT_POLICY_V1", taxonomy.get("policyVersion"));
        assertTrue(((List<String>) taxonomy.get("targetTypes")).contains("COMMENT"),
                "评论可举报时，公开 taxonomy 不能漏掉 COMMENT");
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) taxonomy.get("reasons");
        assertEquals(((List<String>) taxonomy.get("reasonCodes")).size(), reasons.size());

        Map<String, Object> minorSafety = reasons.stream()
                .filter(row -> "MINOR_SAFETY".equals(row.get("code")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("未成年人安全", minorSafety.get("label"));
        assertEquals("URGENT", minorSafety.get("priority"));
        assertEquals(15, minorSafety.get("firstResponseMinutes"));

        Map<String, Object> intellectualProperty = reasons.stream()
                .filter(row -> "INTELLECTUAL_PROPERTY".equals(row.get("code")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("知识产权或内容盗用", intellectualProperty.get("label"));
        assertEquals("STANDARD", intellectualProperty.get("priority"));
        assertEquals(1440, intellectualProperty.get("firstResponseMinutes"));
    }

    @Test
    void specializedCommunityReasonIsAcceptedAndPersistedWithoutFallingBackToOther() {
        CommunityReportSubmitRequest request = reportRequest();
        request.setReasonCode("SEXUAL_CONTENT");
        request.setRequestId("report-member-102-sexual-content-v1");
        when(mapper.insertReport(any(CommunityReportCase.class))).thenAnswer(call -> {
            call.getArgument(0, CommunityReportCase.class).setId(REPORT);
            return 1;
        });
        when(mapper.insertReportTimeline(any())).thenReturn(1);

        service.submitReport(MEMBER, request);

        ArgumentCaptor<CommunityReportCase> stored = ArgumentCaptor.forClass(CommunityReportCase.class);
        verify(mapper).insertReport(stored.capture());
        assertEquals("SEXUAL_CONTENT", stored.getValue().getReasonCode());
        assertEquals("COMMUNITY_REPORT_POLICY_V1", stored.getValue().getPolicyVersion());
        assertEquals("色情低俗内容", stored.getValue().getReasonLabelSnapshot());
        assertEquals("STANDARD", stored.getValue().getPrioritySnapshot());
        assertEquals(1440, stored.getValue().getFirstResponseMinutesSnapshot());
    }

    @Test
    void unsupportedReportPolicyVersionIsRejectedBeforePersistence() {
        CommunityReportSubmitRequest request = reportRequest();
        request.setPolicyVersion("COMMUNITY_REPORT_POLICY_V0");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.submitReport(MEMBER, request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getCode());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void anAlreadyAcceptedOldPolicyRequestStillReplaysAfterPolicyUpgrade() {
        CommunityReportSubmitRequest request = reportRequest();
        request.setPolicyVersion("COMMUNITY_REPORT_POLICY_V0");
        CommunityReportCase existing = new CommunityReportCase();
        existing.setId(REPORT);
        existing.setPolicyVersion("COMMUNITY_REPORT_POLICY_V0");
        existing.setPayloadHash(ReflectionTestUtils.invokeMethod(service, "hash",
                "MEMBER|102|HARASSMENT|COMMUNITY_REPORT_POLICY_V0|线下活动中持续骚扰|[]"));
        when(mapper.selectReportByRequestForUpdate(MEMBER, REPORT_REQUEST)).thenReturn(existing);

        CommunityReportCase replay = service.submitReport(MEMBER, request);

        assertEquals(REPORT, replay.getId());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void platformQueueDerivesAnUrgentDeadlineAndBreachFromTheSamePolicy() {
        CommunityReportCase report = new CommunityReportCase();
        report.setId(REPORT);
        report.setTargetType(CommunityReportCase.TARGET_CONTENT);
        report.setTargetId(701L);
        report.setReasonCode("MINOR_SAFETY");
        report.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        applyCurrentPolicy(report);
        report.setStage(CommunityReportCase.STAGE_SUBMITTED);
        report.setCreateTime(new Date(System.currentTimeMillis() - 20L * 60 * 1000));
        when(mapper.selectPlatformReports(null, null))
                .thenReturn(Collections.singletonList(CommunityReportPlatformVO.from(report)));

        CommunityReportPlatformVO queued = service.listPlatformReports(null, null).get(0);

        assertEquals("COMMUNITY_REPORT_POLICY_V1", queued.getPolicyVersion());
        assertEquals("未成年人安全", queued.getReasonLabel());
        assertEquals("URGENT", queued.getPriority());
        assertEquals(15, queued.getFirstResponseMinutes());
        assertEquals(report.getCreateTime().getTime() + 15L * 60 * 1000,
                queued.getFirstResponseDueAt().getTime());
        assertTrue(queued.getFirstResponseOverdue());
    }

    @Test
    void platformQueueKeepsALateFirstResponseVisibleAfterTriage() {
        CommunityReportCase report = new CommunityReportCase();
        report.setId(REPORT);
        report.setTargetType(CommunityReportCase.TARGET_CONTENT);
        report.setTargetId(701L);
        report.setReasonCode("MINOR_SAFETY");
        report.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        applyCurrentPolicy(report);
        report.setStage("TRIAGED");
        report.setCreateTime(new Date(System.currentTimeMillis() - 60L * 60 * 1000));
        CommunityReportPlatformVO row = CommunityReportPlatformVO.from(report);
        row.setFirstResponseAt(new Date(report.getCreateTime().getTime() + 20L * 60 * 1000));
        when(mapper.selectPlatformReports(null, null)).thenReturn(Collections.singletonList(row));

        CommunityReportPlatformVO queued = service.listPlatformReports(null, null).get(0);

        assertEquals(row.getFirstResponseAt(), queued.getFirstResponseAt());
        assertTrue(queued.getFirstResponseOverdue(),
                "首响发生在15分钟截止线以后，后续进入TRIAGED也必须保留超时事实");
    }

    @Test
    void platformQueuePlacesNewUrgentCasesBeforeOlderStandardCases() {
        CommunityReportCase standard = new CommunityReportCase();
        standard.setId(302L);
        standard.setTargetType(CommunityReportCase.TARGET_CONTENT);
        standard.setTargetId(702L);
        standard.setReasonCode("SPAM");
        standard.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        applyCurrentPolicy(standard);
        standard.setStage(CommunityReportCase.STAGE_SUBMITTED);
        standard.setCreateTime(new Date(System.currentTimeMillis() - 2L * 60 * 60 * 1000));
        CommunityReportCase urgent = new CommunityReportCase();
        urgent.setId(303L);
        urgent.setTargetType(CommunityReportCase.TARGET_CONTENT);
        urgent.setTargetId(703L);
        urgent.setReasonCode("SELF_HARM");
        urgent.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        applyCurrentPolicy(urgent);
        urgent.setStage(CommunityReportCase.STAGE_SUBMITTED);
        urgent.setCreateTime(new Date());
        when(mapper.selectPlatformReports(null, null)).thenReturn(Arrays.asList(
                CommunityReportPlatformVO.from(standard), CommunityReportPlatformVO.from(urgent)));

        List<CommunityReportPlatformVO> queued = service.listPlatformReports(null, null);

        assertEquals(303L, queued.get(0).getId());
        assertEquals("URGENT", queued.get(0).getPriority());
    }

    @Test
    void unknownReportReasonFailsClosedIntoTheUrgentQueue() {
        CommunityReportCase report = new CommunityReportCase();
        report.setId(REPORT);
        report.setTargetType(CommunityReportCase.TARGET_CONTENT);
        report.setTargetId(701L);
        report.setReasonCode("UNRECOGNIZED_POLICY_CODE");
        report.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        report.setStage(CommunityReportCase.STAGE_SUBMITTED);
        report.setCreateTime(new Date());
        when(mapper.selectPlatformReports(null, null))
                .thenReturn(Collections.singletonList(CommunityReportPlatformVO.from(report)));

        CommunityReportPlatformVO queued = service.listPlatformReports(null, null).get(0);

        assertFalse(queued.getPolicyMatched());
        assertEquals("未知或损坏的规则快照", queued.getReasonLabel());
        assertEquals("URGENT", queued.getPriority());
        assertEquals(15, queued.getFirstResponseMinutes());
    }

    @Test
    void historicalPolicyKeepsItsStoredLabelPriorityAndSlaWithoutCurrentPolicyLookup() {
        CommunityReportCase report = new CommunityReportCase();
        report.setId(REPORT);
        report.setTargetType(CommunityReportCase.TARGET_CONTENT);
        report.setTargetId(701L);
        report.setReasonCode("SPAM");
        report.setPolicyVersion("COMMUNITY_REPORT_POLICY_V0");
        report.setReasonLabelSnapshot("旧版垃圾内容");
        report.setPrioritySnapshot("STANDARD");
        report.setFirstResponseMinutesSnapshot(60);
        report.setStage(CommunityReportCase.STAGE_SUBMITTED);
        report.setCreateTime(new Date());
        when(mapper.selectPlatformReports(null, null))
                .thenReturn(Collections.singletonList(CommunityReportPlatformVO.from(report)));

        CommunityReportPlatformVO queued = service.listPlatformReports(null, null).get(0);

        assertEquals("COMMUNITY_REPORT_POLICY_V0", queued.getPolicyVersion());
        assertTrue(queued.getPolicyMatched());
        assertEquals("旧版垃圾内容", queued.getReasonLabel());
        assertEquals("STANDARD", queued.getPriority());
        assertEquals(60, queued.getFirstResponseMinutes());
    }

    @Test
    void unownedEvidenceIdsFailClosedUntilOwnershipAndRedactionAreConnected() {
        CommunityReportSubmitRequest request = reportRequest();
        request.setEvidenceAssetIds(Collections.singletonList(701L));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.submitReport(MEMBER, request));

        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void appealWinsOnlyThroughResolvedStageCasBeforeAnyReceiptIsWritten() {
        CommunityReportCase report = resolvedReport();
        when(mapper.selectReportForOwnerForUpdate(REPORT, MEMBER)).thenReturn(report);
        when(mapper.appealReportCas(REPORT, CommunityReportCase.STAGE_RESOLVED,
                CommunityReportCase.STAGE_APPEALED, report.getVersion())).thenReturn(0);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.appealReport(MEMBER, appealRequest()));

        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        verify(mapper, never()).insertReportAppeal(any());
        verify(mapper, never()).insertReportTimeline(any());
    }

    @Test
    void sameContributorCannotBypassTheMerchantScopeGuardWithANewRequestKey() {
        MerchantContribution existing = contribution();
        when(mapper.selectContributionByScopeForUpdate(eq(MEMBER), eq(MERCHANT), any(String.class)))
                .thenReturn(existing);

        MerchantContributionSubmitRequest request = contributionRequest();
        request.setIdempotencyKey("merchant-201-address-retry-with-new-key");
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.submitContribution(MEMBER, request));

        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        verify(mapper, never()).insertContribution(any());
    }

    @Test
    void approvedContributionCreatesOnlyAProvisionalRewardWithAStableBusinessKey() {
        MerchantContribution row = contribution();
        when(mapper.selectContributionForUpdate(CONTRIBUTION)).thenReturn(row);
        when(mapper.reviewContributionCas(CONTRIBUTION, MerchantContribution.STATUS_PENDING,
                MerchantContribution.STATUS_APPROVED, row.getVersion(), 9001L,
                "证据可采纳", false)).thenReturn(1);
        when(mapper.insertContributionReview(any())).thenReturn(1);
        when(mapper.insertRewardLedger(any())).thenReturn(1);

        MerchantContributionReviewRequest request = reviewRequest();
        service.reviewContribution(9001L, request);

        ArgumentCaptor<ContributionRewardLedger> reward =
                ArgumentCaptor.forClass(ContributionRewardLedger.class);
        verify(mapper).insertRewardLedger(reward.capture());
        assertEquals(EXPECTED_REWARD_BUSINESS_KEY, reward.getValue().getBusinessKey());
        assertEquals(ContributionRewardLedger.STATUS_PROVISIONAL, reward.getValue().getStatus());
        assertNull(reward.getValue().getPoints(), "产品未冻结奖励口径时，未知积分不得伪装成0");
    }

    @Test
    void reviewCannotChooseRewardPointsBeforeTheMoneyEdgePolicyIsFrozen() {
        MerchantContributionReviewRequest request = reviewRequest();
        request.setRewardPoints(10);
        MerchantContribution row = contribution();
        when(mapper.selectContributionForUpdate(CONTRIBUTION)).thenReturn(row);
        when(mapper.reviewContributionCas(CONTRIBUTION, MerchantContribution.STATUS_PENDING,
                MerchantContribution.STATUS_APPROVED, row.getVersion(), 9001L,
                "证据可采纳", false)).thenReturn(1);
        when(mapper.insertContributionReview(any())).thenReturn(1);
        when(mapper.insertRewardLedger(any())).thenReturn(1);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.reviewContribution(9001L, request));

        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        verify(mapper, never()).reviewContributionCas(any(), any(), any(), any(), any(), any(), any(Boolean.class));
        verify(mapper, never()).insertRewardLedger(any());
    }

    @Test
    void ownerSupplementReopensTheSameRecordOnlyThroughExpectedStatusAndVersionCas() {
        MerchantContribution row = contribution();
        row.setReviewStatus(MerchantContribution.STATUS_NEEDS_EVIDENCE);
        row.setCanSupplement(true);
        row.setVersion(1);
        when(mapper.selectContributionForOwnerForUpdate(CONTRIBUTION, MEMBER)).thenReturn(row);
        when(mapper.supplementContributionCas(eq(CONTRIBUTION),
                eq(MerchantContribution.STATUS_NEEDS_EVIDENCE), eq(1), any(), any(), any()))
                .thenReturn(1);
        when(mapper.insertContributionSupplement(any())).thenReturn(1);

        MerchantContribution result = service.supplementContribution(MEMBER, supplementRequest());

        assertEquals(MerchantContribution.STATUS_PENDING, result.getReviewStatus());
        assertFalse(result.getCanSupplement(), "补证受理后必须关闭重复补证入口，等待下一轮审核");
        verify(mapper).supplementContributionCas(eq(CONTRIBUTION),
                eq(MerchantContribution.STATUS_NEEDS_EVIDENCE), eq(1), any(), any(), any());
    }

    @Test
    void safetyPeriodGuardRejectsASecondSubmissionInsteadOfAveragingBoth() {
        MerchantSafetyRating existing = safetyRating();
        when(mapper.selectSafetyRatingByPeriodForUpdate(MEMBER, MERCHANT, currentPeriod()))
                .thenReturn(existing);
        MerchantSafetyRatingRequest request = safetyRequest();
        request.setIdempotencyKey("merchant-201-safety-second-202608");

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.submitSafetyRating(MEMBER, request));

        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        verify(mapper, never()).insertSafetyRating(any());
    }

    @Test
    void safetySubmissionRequiresAServerVerifiedVisit() {
        when(mapper.selectVerifiedVisitForSafety(MEMBER, MERCHANT)).thenReturn(null);

        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.submitSafetyRating(MEMBER, safetyRequest()));

        assertEquals(HttpStatus.FORBIDDEN, forbidden.getCode());
        verify(mapper, never()).insertSafetyRating(any());
    }

    @Test
    void safetySummaryReturnsPolicyPendingWithoutInventingThresholdsOrScore() {
        when(mapper.selectActiveSafetyPolicy()).thenReturn(null);

        MerchantSafetySummaryVO result = service.getSafetySummary(MERCHANT);

        assertEquals(SAFETY_POLICY_PENDING, result.getSampleStatus());
        assertNull(result.getMinimumSampleCount(), "政策未冻结时不得伪造样本门槛");
        assertNull(result.getLevel(), "政策未冻结时不得生成安全档位");
        verify(mapper, never()).selectSafetyAggregate(any(), any());
    }

    @Test
    void safetySubmissionStoresOnlyTheReferenceOverallDimension() {
        when(mapper.insertSafetyRating(any())).thenAnswer(call -> {
            call.getArgument(0, MerchantSafetyRating.class).setId(501L);
            return 1;
        });
        when(mapper.insertSafetyRatingDimension(any())).thenReturn(1);

        service.submitSafetyRating(MEMBER, safetyRequest());

        ArgumentCaptor<com.chengyinhub.business.domain.MerchantSafetyRatingDimension> dimension =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.MerchantSafetyRatingDimension.class);
        verify(mapper).insertSafetyRatingDimension(dimension.capture());
        assertEquals(MerchantSafetyRating.DIMENSION_OVERALL, dimension.getValue().getDimensionCode(),
                "原图只有一个整体安全感问题，当前版本不得写入自创维度");
        assertEquals(MerchantSafetyRating.LEVEL_NEUTRAL, dimension.getValue().getLevel());
    }

    @Test
    void aggregateReturnsNoLevelOrDimensionsBelowTheSampleThreshold() {
        MerchantSafetyAggregate raw = new MerchantSafetyAggregate();
        raw.setSampleCount(4);
        raw.setAverageScore(3.0D);
        when(mapper.selectSafetyAggregate(any(), any())).thenReturn(raw);

        MerchantSafetySummaryVO result = service.getSafetySummary(MERCHANT);

        assertEquals(SAMPLE_INSUFFICIENT, result.getSampleStatus());
        assertNull(result.getLevel(), "样本不足时绝不能返回看似真实的安全档位");
        assertTrue(result.getDimensions().isEmpty());
    }

    @Test
    void sufficientAggregateUsesTheConservativeThreeLevelPolicy() {
        MerchantSafetyAggregate raw = new MerchantSafetyAggregate();
        raw.setSampleCount(5);
        raw.setAverageScore(1.7D);
        when(mapper.selectSafetyAggregate(any(), any())).thenReturn(raw);
        when(mapper.selectSafetyDimensionAggregates(any(), any()))
                .thenReturn(Collections.emptyList());

        MerchantSafetySummaryVO result = service.getSafetySummary(MERCHANT);

        assertEquals(SAMPLE_SUFFICIENT, result.getSampleStatus());
        assertEquals(MerchantSafetyRating.LEVEL_UNSAFE, result.getLevel());
        assertEquals(SAFETY_DISCLAIMER, result.getDisclaimerCode(),
                "安全感聚合必须附带非安全保证的免责声明代码");
    }

    private CommunityReportSubmitRequest reportRequest() {
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetType(CommunityReportCase.TARGET_MEMBER);
        request.setTargetId(SUBJECT);
        request.setReasonCode("HARASSMENT");
        request.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        request.setDescription("线下活动中持续骚扰");
        request.setEvidenceAssetIds(Collections.emptyList());
        request.setRequestId(REPORT_REQUEST);
        return request;
    }

    private void applyCurrentPolicy(CommunityReportCase report) {
        CommunityReportPolicy.Reason reason = CommunityReportPolicy.find(report.getReasonCode());
        report.setReasonLabelSnapshot(reason.getLabel());
        report.setPrioritySnapshot(reason.getPriority());
        report.setFirstResponseMinutesSnapshot(reason.getFirstResponseMinutes());
    }

    private CommunityReportAppealRequest appealRequest() {
        CommunityReportAppealRequest request = new CommunityReportAppealRequest();
        request.setReportId(REPORT);
        request.setReason("处理结论遗漏了补充证据");
        request.setRequestId("report-301-appeal-v1");
        return request;
    }

    private CommunityReportCase resolvedReport() {
        CommunityReportCase report = new CommunityReportCase();
        report.setId(REPORT);
        report.setReporterMemberId(MEMBER);
        report.setTargetType(CommunityReportCase.TARGET_MEMBER);
        report.setTargetId(SUBJECT);
        report.setStage(CommunityReportCase.STAGE_RESOLVED);
        report.setVersion(2);
        return report;
    }

    private MerchantContributionSubmitRequest contributionRequest() {
        MerchantContributionSubmitRequest request = new MerchantContributionSubmitRequest();
        request.setMerchantId(MERCHANT);
        request.setContributionType(MerchantContribution.TYPE_INFO_UPDATE);
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(ADDRESS, PROPOSED_ADDRESS);
        request.setFieldChanges(changes);
        request.setDescription("门牌号已更新");
        request.setEvidenceAssetIds(Collections.emptyList());
        request.setIdempotencyKey(CONTRIBUTION_REQUEST);
        return request;
    }

    private MerchantContribution contribution() {
        MerchantContribution row = new MerchantContribution();
        row.setId(CONTRIBUTION);
        row.setMerchantId(MERCHANT);
        row.setContributorMemberId(MEMBER);
        row.setContributionType(MerchantContribution.TYPE_INFO_UPDATE);
        row.setFieldChangesJson("{\"address\":\"" + PROPOSED_ADDRESS + "\"}");
        row.setReviewStatus(MerchantContribution.STATUS_PENDING);
        row.setVersion(0);
        return row;
    }

    private MerchantContributionReviewRequest reviewRequest() {
        MerchantContributionReviewRequest request = new MerchantContributionReviewRequest();
        request.setContributionId(CONTRIBUTION);
        request.setExpectedVersion(0);
        request.setAction(MerchantContributionReviewRequest.ACTION_APPROVE);
        request.setReason("证据可采纳");
        request.setRequestId("contribution-401-review-v1");
        return request;
    }

    private MerchantContributionSupplementRequest supplementRequest() {
        MerchantContributionSupplementRequest request = new MerchantContributionSupplementRequest();
        request.setContributionId(CONTRIBUTION);
        request.setExpectedVersion(1);
        request.setDescription("补充夜间拍摄的门牌照片");
        request.setEvidenceAssetIds(Collections.emptyList());
        request.setRequestId("contribution-401-supplement-v1");
        return request;
    }

    private MerchantSafetyRatingRequest safetyRequest() {
        MerchantSafetyRatingRequest request = new MerchantSafetyRatingRequest();
        request.setMerchantId(MERCHANT);
        request.setOverallLevel(MerchantSafetyRating.LEVEL_NEUTRAL);
        request.setReason("夜间入口照明不足");
        request.setIdempotencyKey(SAFETY_REQUEST);
        return request;
    }

    private MerchantSafetyRating safetyRating() {
        MerchantSafetyRating row = new MerchantSafetyRating();
        row.setId(501L);
        row.setMemberId(MEMBER);
        row.setMerchantId(MERCHANT);
        row.setRatingPeriod(currentPeriod());
        row.setPayloadHash("existing");
        return row;
    }

    private String currentPeriod() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMM");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
