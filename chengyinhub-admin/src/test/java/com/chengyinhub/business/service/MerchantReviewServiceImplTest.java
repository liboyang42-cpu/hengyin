package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantReview;
import com.chengyinhub.business.domain.MerchantReviewEligibility;
import com.chengyinhub.business.domain.MerchantReviewReport;
import com.chengyinhub.business.domain.dto.MerchantReviewCreateRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyDeleteRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReportRequest;
import com.chengyinhub.business.domain.vo.MerchantReviewWriteResultVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantReviewMapper;
import com.chengyinhub.business.service.impl.MerchantReviewServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantReviewServiceImplTest {
    private static final Long AUTHOR = 101L;
    private static final Long MERCHANT_ROW = 7L;
    private static final Long MERCHANT_OWNER = 202L;
    private static final Long PRINCIPAL = MERCHANT_OWNER;
    private static final Long OPERATOR_PRINCIPAL = 102L;
    private static final Long REGISTRATION = 303L;
    private static final Long REDEMPTION = 404L;
    private static final Long REVIEW = 505L;

    private MerchantReviewMapper mapper;
    private IMerchantAccessService accessService;
    private IAuditTaskService auditTaskService;
    private BusinessAuditEventMapper businessAudits;
    private Environment environment;
    private MerchantReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantReviewMapper.class);
        accessService = mock(IMerchantAccessService.class);
        auditTaskService = mock(IAuditTaskService.class);
        businessAudits = mock(BusinessAuditEventMapper.class);
        environment = mock(Environment.class);
        service = new MerchantReviewServiceImpl();
        ReflectionTestUtils.setField(service, "merchantReviewMapper", mapper);
        ReflectionTestUtils.setField(service, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", businessAudits);
        ReflectionTestUtils.setField(service, "environment", environment);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        when(environment.getProperty("CHENGYIN_OSS_ENDPOINT"))
                .thenReturn("https://oss-cn-hangzhou.aliyuncs.com");
        when(environment.getProperty("CHENGYIN_OSS_BUCKET_NAME")).thenReturn("chengyin-review");
        when(mapper.lockActiveMember(AUTHOR)).thenReturn(AUTHOR);
        when(mapper.lockActiveMember(PRINCIPAL)).thenReturn(PRINCIPAL);
        when(mapper.lockActiveMember(OPERATOR_PRINCIPAL)).thenReturn(OPERATOR_PRINCIPAL);
        when(mapper.countActiveMerchantRowsForRow(MERCHANT_ROW)).thenReturn(1);
        when(businessAudits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(auditTaskService.submitSafetyHold(eq(AuditTask.BIZ_MERCHANT_REVIEW), any(), eq(AUTHOR),
                eq("NORMAL"), any())).thenReturn(77L);
        when(accessService.require(PRINCIPAL, MerchantPermission.MARKETING_WRITE)).thenReturn(owner());
        when(accessService.require(PRINCIPAL, MerchantPermission.MARKETING_READ)).thenReturn(owner());
    }

    @Test
    void exactOrderHintDoesNotBorrowLatestEligibilityAndKeepsLegacyCallCompatible() {
        when(mapper.selectLatestEligibility(AUTHOR, MERCHANT_ROW)).thenReturn(eligibility());
        when(mapper.selectEligibilityForRegistration(AUTHOR, MERCHANT_ROW, REGISTRATION)).thenReturn(eligibility());
        assertTrue(service.publicList(AUTHOR, MERCHANT_ROW, 1, 20, REGISTRATION).getEligibility().isCanCreate());
        assertEquals(REGISTRATION, service.publicList(AUTHOR, MERCHANT_ROW, 1, 20, REGISTRATION)
                .getEligibility().getRequestedRegistrationId());
        assertFalse(service.publicList(AUTHOR, MERCHANT_ROW, 1, 20, 999L).getEligibility().isCanCreate());
        verify(mapper, never()).selectLatestEligibility(any(), any());
        verify(mapper, never()).selectEligibility(any(), any(), any());
        assertTrue(service.publicList(AUTHOR, MERCHANT_ROW, 1, 20).getEligibility().isCanCreate());
        when(mapper.countActiveMerchantRowsForRow(MERCHANT_ROW)).thenReturn(2);
        assertEquals("AMBIGUOUS_LEGACY_MERCHANT_SCOPE", service.publicList(AUTHOR, MERCHANT_ROW, 1, 20, 998L)
                .getEligibility().getReasonCode());
        verify(mapper, never()).selectEligibilityForRegistration(AUTHOR, MERCHANT_ROW, 998L);
        assertEquals("LOGIN_REQUIRED", service.publicList(null, MERCHANT_ROW, 1, 20, REGISTRATION)
                .getEligibility().getReasonCode());
    }

    @Test
    void createBindsAuthorMerchantAndRedemptionOnlyFromServerEligibility() {
        when(mapper.selectEligibility(AUTHOR, MERCHANT_ROW, REGISTRATION)).thenReturn(eligibility());
        when(mapper.insertReview(any(MerchantReview.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantReview.class).setId(REVIEW);
            return 1;
        });

        MerchantReviewWriteResultVO out = service.create(AUTHOR, createRequest("review-create-1", 5));

        assertEquals(REVIEW, out.getReviewId());
        assertEquals(MerchantReview.STATUS_PENDING_REVIEW, out.getStatus());
        assertEquals(77L, out.getAuditTaskId());
        assertFalse(out.isReplayed());
        ArgumentCaptor<MerchantReview> saved = ArgumentCaptor.forClass(MerchantReview.class);
        verify(mapper).insertReview(saved.capture());
        assertEquals(AUTHOR, saved.getValue().getAuthorMemberId());
        assertEquals(MERCHANT_ROW, saved.getValue().getMerchantRowId());
        assertEquals(MERCHANT_OWNER, saved.getValue().getMerchantOwnerMemberId());
        assertEquals(REGISTRATION, saved.getValue().getRegistrationId());
        assertEquals(REDEMPTION, saved.getValue().getRedemptionId());
        assertEquals("[\"https://chengyin-review.oss-cn-hangzhou.aliyuncs.com/upload/0123456789abcdef0123456789abcdef.jpg?OSSAccessKeyId=test&Expires=2000000000&Signature=test\"]",
                saved.getValue().getImagesJson());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAudits).insertBusinessAuditEvent(audit.capture());
        assertEquals("MERCHANT_REVIEW_SUBMITTED", audit.getValue().getAction());
        assertEquals(AUTHOR, audit.getValue().getActorMemberId());
        verify(auditTaskService).submitSafetyHold(eq(AuditTask.BIZ_MERCHANT_REVIEW), eq(REVIEW), eq(AUTHOR),
                eq("NORMAL"), any());
    }

    @Test
    void createRejectsExternalOrNonUploadImageUrlsBeforeEligibilityRead() {
        MerchantReviewCreateRequest external = createRequest("review-external-image", 5);
        external.setImageUrls(Arrays.asList(
                "https://attacker.example/upload/0123456789abcdef0123456789abcdef.jpg"
                        + "?OSSAccessKeyId=fake&Expires=2000000000&Signature=fake"));
        MerchantReviewCreateRequest wrongPath = createRequest("review-wrong-image-path", 5);
        wrongPath.setImageUrls(Arrays.asList("https://chengyin-review.oss-cn-hangzhou.aliyuncs.com/avatar/a.jpg"));

        ServiceException externalError = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, external));
        ServiceException pathError = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, wrongPath));

        assertEquals(400, externalError.getCode());
        assertEquals(400, pathError.getCode());
        verify(mapper, never()).selectEligibility(any(), any(), any());
        verify(mapper, never()).insertReview(any());
    }

    @Test
    void imageStorageConfigurationFailsClosedButNoImageReviewStillWorks() {
        when(environment.getProperty("CHENGYIN_OSS_BUCKET_NAME")).thenReturn(null);
        MerchantReviewCreateRequest withImage = createRequest("review-missing-image-config", 5);

        ServiceException imageError = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, withImage));
        assertEquals(400, imageError.getCode());

        MerchantReviewCreateRequest withoutImage = createRequest("review-without-image", 5);
        withoutImage.setImageUrls(Collections.emptyList());
        when(mapper.selectEligibility(AUTHOR, MERCHANT_ROW, REGISTRATION)).thenReturn(eligibility());
        when(mapper.insertReview(any(MerchantReview.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantReview.class).setId(REVIEW);
            return 1;
        });

        assertEquals(MerchantReview.STATUS_PENDING_REVIEW,
                service.create(AUTHOR, withoutImage).getStatus());
    }

    @Test
    void manageListUsesMarketingReadInsteadOfAftercarePermission() {
        when(mapper.countManageReviews(MERCHANT_ROW, "all")).thenReturn(0L);

        assertEquals("manage", service.manageList(PRINCIPAL, 1, 20).getMode());

        verify(accessService).require(PRINCIPAL, MerchantPermission.MARKETING_READ);
        verify(accessService, never()).require(PRINCIPAL, MerchantPermission.AFTERCARE_READ);
    }

    @Test
    void managerCanListExactStoreReviews() {
        when(accessService.require(OPERATOR_PRINCIPAL, MerchantPermission.MARKETING_READ))
                .thenReturn(manager());
        when(mapper.countManageReviews(MERCHANT_ROW, "all")).thenReturn(0L);

        assertEquals("manage", service.manageList(OPERATOR_PRINCIPAL, 1, 20).getMode());

        verify(mapper).countManageReviews(MERCHANT_ROW, "all");
    }

    @Test
    void manageListStatsReturnZeroWhenStoreHasNoReviews() {
        when(mapper.countManageReviews(MERCHANT_ROW, "all")).thenReturn(0L);
        when(mapper.selectReviewStats(MERCHANT_ROW)).thenReturn(reviewStats(0, 0, 0));

        com.chengyinhub.business.domain.vo.MerchantReviewPageVO out =
                service.manageList(PRINCIPAL, 1, 20);

        assertEquals(0, out.getPendingReplyCount());
        assertEquals(0, out.getMonthNewCount());
        assertEquals(0, out.getReplyRatePct());
        verify(mapper).selectReviewStats(MERCHANT_ROW);
    }

    @Test
    void manageListStatsReturnFullCountsWhenAllReviewsAreReplied() {
        MerchantReview first = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_VISIBLE);
        first.setReplyContent("谢谢反馈");
        MerchantReview second = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_VISIBLE);
        second.setId(REVIEW + 1);
        second.setReplyContent("欢迎再来");
        when(mapper.countManageReviews(MERCHANT_ROW, "all")).thenReturn(2L);
        when(mapper.selectManageReviews(MERCHANT_ROW, "all", 0, 20))
                .thenReturn(Arrays.asList(first, second));
        when(mapper.selectReviewStats(MERCHANT_ROW)).thenReturn(reviewStats(0, 2, 100));

        com.chengyinhub.business.domain.vo.MerchantReviewPageVO out =
                service.manageList(PRINCIPAL, 1, 20);

        assertEquals(2, out.getItems().size());
        assertEquals(0, out.getPendingReplyCount());
        assertEquals(2, out.getMonthNewCount());
        assertEquals(100, out.getReplyRatePct());
    }

    @Test
    void manageListStatsAreFullStoreCountsNotCurrentPageCountsWhenAllReviewsAreUnreplied() {
        MerchantReview currentPageOnly = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_VISIBLE);
        when(mapper.countManageReviews(MERCHANT_ROW, "all")).thenReturn(2L);
        when(mapper.selectManageReviews(MERCHANT_ROW, "all", 0, 1))
                .thenReturn(Collections.singletonList(currentPageOnly));
        when(mapper.selectReviewStats(MERCHANT_ROW)).thenReturn(reviewStats(2, 2, 0));

        com.chengyinhub.business.domain.vo.MerchantReviewPageVO out =
                service.manageList(PRINCIPAL, 1, 1);

        assertEquals(1, out.getItems().size());
        assertEquals(2, out.getPendingReplyCount());
        assertEquals(2, out.getMonthNewCount());
        assertEquals(0, out.getReplyRatePct());
        assertTrue(out.isHasMore());
    }

    @Test
    void manageListPassesServerFilterAndReturnsFullStoreFilterCounts() {
        MerchantReview reported = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_VISIBLE);
        reported.setUnderPlatformReview(1);
        when(mapper.countManageReviews(MERCHANT_ROW, "platform")).thenReturn(3L);
        when(mapper.selectManageReviews(MERCHANT_ROW, "platform", 0, 1))
                .thenReturn(Collections.singletonList(reported));
        Map<String, Object> counts = new HashMap<>();
        counts.put("all_count", 12L);
        counts.put("pending_count", 4L);
        counts.put("low_count", 2L);
        counts.put("photo_count", 5L);
        counts.put("platform_count", 3L);
        when(mapper.selectManageFilterCounts(MERCHANT_ROW)).thenReturn(counts);

        com.chengyinhub.business.domain.vo.MerchantReviewPageVO out =
                service.manageList(PRINCIPAL, "platform", 1, 1);

        assertEquals("platform", out.getFilter());
        assertEquals(3L, out.getTotal(), "total 必须是筛选后的全量,不是当前页条数");
        assertTrue(out.isHasMore());
        assertEquals(Integer.valueOf(12), out.getFilterCounts().get("all"));
        assertEquals(Integer.valueOf(4), out.getFilterCounts().get("pending"));
        assertEquals(Integer.valueOf(2), out.getFilterCounts().get("low"));
        assertEquals(Integer.valueOf(5), out.getFilterCounts().get("photo"));
        assertEquals(Integer.valueOf(3), out.getFilterCounts().get("platform"));
        assertTrue(out.getItems().get(0).isUnderPlatformReview());
        verify(mapper).countManageReviews(MERCHANT_ROW, "platform");
        verify(mapper).selectManageFilterCounts(MERCHANT_ROW);
    }

    @Test
    void manageListBlankFilterMeansAllAndUnknownFilterIsRejectedBeforeAnyRead() {
        when(mapper.countManageReviews(MERCHANT_ROW, "all")).thenReturn(0L);
        assertEquals("all", service.manageList(PRINCIPAL, " ", 1, 20).getFilter());
        assertEquals("all", service.manageList(PRINCIPAL, null, 1, 20).getFilter());

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.manageList(PRINCIPAL, "hidden' or 1=1", 1, 20));
        assertEquals(400, error.getCode());
        verify(mapper, never()).countManageReviews(MERCHANT_ROW, "hidden' or 1=1");
    }

    @Test
    void manageListFilterStaysInsideAccessScopeAndNeedsMarketingRead() {
        when(accessService.require(AUTHOR, MerchantPermission.MARKETING_READ))
                .thenThrow(new ServiceException("无权访问", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.manageList(AUTHOR, "pending", 1, 20));

        assertEquals(403, error.getCode());
        verify(mapper, never()).selectManageFilterCounts(any());
    }

    @Test
    void publicListNeverMarksPlatformReviewOrCarriesManageCounts() {
        MerchantReview row = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_VISIBLE);
        row.setUnderPlatformReview(1);
        when(mapper.countPublicReviews(MERCHANT_ROW)).thenReturn(1L);
        when(mapper.selectPublicReviews(MERCHANT_ROW, 0, 20)).thenReturn(Collections.singletonList(row));

        com.chengyinhub.business.domain.vo.MerchantReviewPageVO out =
                service.publicList(null, MERCHANT_ROW, 1, 20);

        assertFalse(out.getItems().get(0).isUnderPlatformReview());
        assertNull(out.getFilterCounts());
        assertNull(out.getFilter());
    }

    @Test
    void managerCanReplyToExactStoreReviewWithOperatorAudit() {
        when(accessService.require(OPERATOR_PRINCIPAL, MerchantPermission.MARKETING_WRITE))
                .thenReturn(manager());
        MerchantReview row = review(MERCHANT_OWNER, 2, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.replyCas(eq(REVIEW), eq(MERCHANT_ROW), eq(MERCHANT_OWNER),
                eq("感谢反馈，我们会持续改进"), eq(OPERATOR_PRINCIPAL),
                eq(MerchantRoleCode.MERCHANT_MANAGER), eq("manager-reply-1"), any(), eq(2)))
                .thenReturn(1);

        assertEquals(3, service.reply(OPERATOR_PRINCIPAL,
                replyRequest("manager-reply-1", 2)).getVersion());

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAudits).insertBusinessAuditEvent(audit.capture());
        assertEquals(OPERATOR_PRINCIPAL, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, audit.getValue().getActorRoleCode());
    }

    @Test
    void managerCanReportExactStoreReviewWithOperatorReceipt() {
        when(accessService.require(OPERATOR_PRINCIPAL, MerchantPermission.MARKETING_WRITE))
                .thenReturn(manager());
        MerchantReview row = review(MERCHANT_OWNER, 0, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(auditTaskService.reportContent(AuditTask.BIZ_MERCHANT_REVIEW, REVIEW,
                OPERATOR_PRINCIPAL, "涉嫌刷评")).thenReturn(78L);
        when(mapper.insertReport(any(MerchantReviewReport.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantReviewReport.class).setId(89L);
            return 1;
        });

        MerchantReviewWriteResultVO out = service.reportAsMerchant(OPERATOR_PRINCIPAL,
                reportRequest("manager-report-1", "涉嫌刷评", 0));

        assertEquals(78L, out.getAuditTaskId());
        ArgumentCaptor<MerchantReviewReport> receipt = ArgumentCaptor.forClass(MerchantReviewReport.class);
        verify(mapper).insertReport(receipt.capture());
        assertEquals(MERCHANT_ROW, receipt.getValue().getMerchantRowId());
        assertEquals(MERCHANT_OWNER, receipt.getValue().getMerchantOwnerMemberId());
        assertEquals(OPERATOR_PRINCIPAL, receipt.getValue().getReporterMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, receipt.getValue().getReporterRoleCode());
    }

    @Test
    void publicStoreAListUsesMerchantRowInsteadOfSharedOwner() {
        when(mapper.countPublicReviews(MERCHANT_ROW)).thenReturn(1L);
        when(mapper.selectPublicReviews(MERCHANT_ROW, 0, 20))
                .thenReturn(Collections.singletonList(review(MERCHANT_OWNER, 0, MerchantReview.STATUS_VISIBLE)));
        when(mapper.selectPublicRatingSummary(MERCHANT_ROW)).thenReturn(Collections.<String, Object>emptyMap());

        assertEquals(1, service.publicList(null, MERCHANT_ROW, 1, 20).getItems().size());

        verify(mapper).countPublicReviews(MERCHANT_ROW);
        verify(mapper).selectPublicReviews(MERCHANT_ROW, 0, 20);
        verify(mapper).selectPublicRatingSummary(MERCHANT_ROW);
    }

    @Test
    void publicEligibilityExplainsWhyLegacyOwnerRedemptionCannotBeAssignedAcrossStores() {
        when(mapper.countActiveMerchantRowsForRow(MERCHANT_ROW)).thenReturn(2);
        when(mapper.selectPublicRatingSummary(MERCHANT_ROW)).thenReturn(Collections.<String, Object>emptyMap());

        com.chengyinhub.business.domain.vo.MerchantReviewPageVO out =
                service.publicList(AUTHOR, MERCHANT_ROW, 1, 20);

        assertFalse(out.getEligibility().isCanCreate());
        assertEquals("AMBIGUOUS_LEGACY_MERCHANT_SCOPE", out.getEligibility().getReasonCode());
        verify(mapper, never()).selectLatestEligibility(any(), any());
    }

    @Test
    void reversedOrCrossUserRedemptionCannotCreateReview() {
        when(mapper.selectEligibility(AUTHOR, MERCHANT_ROW, REGISTRATION)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, createRequest("review-no-active", 4)));

        assertEquals(403, error.getCode());
        verify(mapper, never()).insertReview(any());
        verify(businessAudits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void legacyOwnerScopedRedemptionCannotCreateWhenOwnerHasMultipleActiveMerchantRows() {
        when(mapper.countActiveMerchantRowsForRow(MERCHANT_ROW)).thenReturn(2);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, createRequest("review-ambiguous-store", 4)));

        assertEquals(403, error.getCode());
        assertTrue(error.getMessage().contains("多个活跃门店"));
        verify(mapper, never()).selectEligibility(any(), any(), any());
        verify(mapper, never()).insertReview(any());
    }

    @Test
    void exactCreateRequestReplayReturnsWinnerButChangedFingerprintConflicts() {
        when(mapper.selectEligibility(AUTHOR, MERCHANT_ROW, REGISTRATION)).thenReturn(eligibility());
        ArgumentCaptor<MerchantReview> saved = ArgumentCaptor.forClass(MerchantReview.class);
        when(mapper.insertReview(saved.capture())).thenAnswer(call -> {
            call.getArgument(0, MerchantReview.class).setId(REVIEW);
            return 1;
        });
        MerchantReviewWriteResultVO first = service.create(AUTHOR, createRequest("review-replay", 5));
        MerchantReview persisted = saved.getValue();
        when(mapper.selectByAuthorRequestForUpdate(AUTHOR, "review-replay")).thenReturn(persisted);

        MerchantReviewWriteResultVO replay = service.create(AUTHOR, createRequest("review-replay", 5));
        ServiceException changed = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, createRequest("review-replay", 4)));

        assertEquals(first.getReviewId(), replay.getReviewId());
        assertTrue(replay.isReplayed());
        assertEquals(409, changed.getCode());
    }

    @Test
    void differentRequestCannotCreateSecondReviewForSameRegistrationAndMerchant() {
        when(mapper.selectEligibility(AUTHOR, MERCHANT_ROW, REGISTRATION)).thenReturn(eligibility());
        MerchantReview existing = review(MERCHANT_OWNER, 0, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectByRegistrationMerchant(REGISTRATION, MERCHANT_OWNER)).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.create(AUTHOR, createRequest("review-duplicate", 5)));

        assertEquals(409, error.getCode());
        verify(mapper, never()).insertReview(any());
    }

    @Test
    void replyRequiresCurrentMerchantPermissionAndRejectsCrossMerchantIdor() {
        MerchantReview foreign = review(999L, 2, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(foreign);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.reply(PRINCIPAL, replyRequest("reply-cross", 2)));

        assertEquals(404, error.getCode());
        verify(accessService).require(PRINCIPAL, MerchantPermission.MARKETING_WRITE);
        verify(mapper, never()).replyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void storeACannotReplyToStoreBReviewEvenWhenBothRowsShareOneOwner() {
        when(accessService.require(OPERATOR_PRINCIPAL, MerchantPermission.MARKETING_WRITE))
                .thenReturn(manager());
        MerchantReview storeB = review(MERCHANT_OWNER, 2, MerchantReview.STATUS_VISIBLE);
        storeB.setMerchantRowId(8L);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(storeB);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.reply(OPERATOR_PRINCIPAL, replyRequest("reply-store-b", 2)));

        assertEquals(404, error.getCode());
        verify(mapper, never()).replyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publicReplyAndUpdateRejectUncheckedTextBeforeAnyWrite() {
        com.chengyinhub.business.service.IWxContentSecurityService safety =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(safety.checkText(anyString(), any())).thenReturn("安全检测不可用");
        ReflectionTestUtils.setField(service, "wxContentSecurityService", safety);
        MerchantReview row = review(MERCHANT_OWNER, 2, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        assertThrows(ServiceException.class, () -> service.reply(PRINCIPAL, replyRequest("unsafe-reply", 2)));
        verify(mapper, never()).replyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());
        row.setReplyContent("旧回复");
        assertThrows(ServiceException.class, () -> service.updateReply(PRINCIPAL, editReplyRequest("unsafe-update", "不应发布的更新", 2)));
        verify(mapper, never()).updateReplyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replyUsesVersionCasAndOnlyCreatesOnePublicReply() {
        MerchantReview row = review(MERCHANT_OWNER, 2, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.replyCas(eq(REVIEW), eq(MERCHANT_ROW), eq(MERCHANT_OWNER),
                eq("感谢反馈，我们会持续改进"), eq(PRINCIPAL),
                eq(MerchantRoleCode.MERCHANT_OWNER), eq("reply-1"), any(), eq(2))).thenReturn(1);

        MerchantReviewWriteResultVO out = service.reply(PRINCIPAL, replyRequest("reply-1", 2));

        assertEquals(3, out.getVersion());
        assertFalse(out.isReplayed());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAudits).insertBusinessAuditEvent(audit.capture());
        assertEquals("MERCHANT_REVIEW_REPLIED", audit.getValue().getAction());

        row.setReplyContent("已回复");
        row.setReplyRequestId("another-request");
        ServiceException second = assertThrows(ServiceException.class,
                () -> service.reply(PRINCIPAL, replyRequest("reply-2", 3)));
        assertEquals(409, second.getCode());
    }

    @Test
    void publicReportCreatesUnifiedAuditTaskAndExactReplayDoesNotDuplicate() {
        MerchantReview row = review(MERCHANT_OWNER, 0, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(auditTaskService.reportContent(AuditTask.BIZ_MERCHANT_REVIEW, REVIEW, AUTHOR, "内容不实"))
                .thenReturn(77L);
        ArgumentCaptor<MerchantReviewReport> saved = ArgumentCaptor.forClass(MerchantReviewReport.class);
        when(mapper.insertReport(saved.capture())).thenAnswer(call -> {
            call.getArgument(0, MerchantReviewReport.class).setId(88L);
            return 1;
        });

        MerchantReviewReportRequest firstRequest = reportRequest("report-1", "内容不实", 0);
        firstRequest.setMerchantMemberId(null);
        MerchantReviewWriteResultVO first = service.report(AUTHOR, firstRequest);

        assertEquals("PENDING_PLATFORM_REVIEW", first.getStatus());
        assertEquals(77L, first.getAuditTaskId());
        assertFalse(first.isReplayed());
        verify(auditTaskService).reportContent(AuditTask.BIZ_MERCHANT_REVIEW, REVIEW, AUTHOR, "内容不实");
        MerchantReviewReport receipt = saved.getValue();
        when(mapper.selectReportByReporterRequestForUpdate(AUTHOR, "report-1")).thenReturn(receipt);

        MerchantReviewReportRequest replayRequest = reportRequest("report-1", "内容不实", 0);
        replayRequest.setMerchantMemberId(null);
        MerchantReviewWriteResultVO replay = service.report(AUTHOR, replayRequest);
        MerchantReviewReportRequest changedRequest = reportRequest("report-1", "换一个原因", 0);
        changedRequest.setMerchantMemberId(null);
        ServiceException changed = assertThrows(ServiceException.class,
                () -> service.report(AUTHOR, changedRequest));
        assertTrue(replay.isReplayed());
        assertEquals(409, changed.getCode());
    }

    @Test
    void merchantReportFailsClosedForAnotherStoreBeforeAuditTask() {
        MerchantReview foreign = review(999L, 0, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(foreign);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.reportAsMerchant(PRINCIPAL, reportRequest("merchant-report", "涉嫌刷评", 0)));

        assertEquals(404, error.getCode());
        verify(accessService).require(PRINCIPAL, MerchantPermission.MARKETING_WRITE);
        verify(auditTaskService, never()).reportContent(any(), any(), any(), any());
    }

    @Test
    void merchantStoreACannotReportStoreBReviewEvenWhenOwnerIsShared() {
        when(accessService.require(OPERATOR_PRINCIPAL, MerchantPermission.MARKETING_WRITE))
                .thenReturn(manager());
        MerchantReview storeB = review(MERCHANT_OWNER, 0, MerchantReview.STATUS_VISIBLE);
        storeB.setMerchantRowId(8L);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(storeB);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.reportAsMerchant(OPERATOR_PRINCIPAL,
                        reportRequest("merchant-report-store-b", "涉嫌刷评", 0)));

        assertEquals(404, error.getCode());
        verify(auditTaskService, never()).reportContent(any(), any(), any(), any());
    }

    @Test
    void platformModerationUsesReviewVersionCas() {
        MerchantReview row = review(MERCHANT_OWNER, 4, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.moderateCas(REVIEW, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_HIDDEN, "确认违规", 4)).thenReturn(1);

        assertEquals(1, service.moderateFromAudit(REVIEW, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_HIDDEN, "确认违规"));
        verify(mapper).moderateCas(REVIEW, MerchantReview.STATUS_VISIBLE,
                MerchantReview.STATUS_HIDDEN, "确认违规", 4);
    }

    @Test
    void platformApprovalPublishesPendingReviewByVersionCas() {
        MerchantReview row = review(MERCHANT_OWNER, 0, MerchantReview.STATUS_PENDING_REVIEW);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.moderateCas(REVIEW, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_VISIBLE, null, 0)).thenReturn(1);

        assertEquals(1, service.moderateFromAudit(REVIEW, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_VISIBLE, "通过复核"));
        verify(mapper).moderateCas(REVIEW, MerchantReview.STATUS_PENDING_REVIEW,
                MerchantReview.STATUS_VISIBLE, null, 0);
    }

    @Test
    void platformRevertReturnsRejectedSubmissionToPendingReviewByVersionCas() {
        MerchantReview row = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_HIDDEN);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.moderateCas(REVIEW, MerchantReview.STATUS_HIDDEN,
                MerchantReview.STATUS_PENDING_REVIEW, null, 1)).thenReturn(1);

        assertEquals(1, service.moderateFromAudit(REVIEW, MerchantReview.STATUS_HIDDEN,
                MerchantReview.STATUS_PENDING_REVIEW, null));
        verify(mapper).moderateCas(REVIEW, MerchantReview.STATUS_HIDDEN,
                MerchantReview.STATUS_PENDING_REVIEW, null, 1);
    }

    @Test
    void platformCannotMoveVisibleReviewBackToPendingOutsideRejectionRevert() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.moderateFromAudit(REVIEW, MerchantReview.STATUS_VISIBLE,
                        MerchantReview.STATUS_PENDING_REVIEW, null));

        assertEquals(400, error.getCode());
        verify(mapper, never()).selectReviewByIdForUpdate(any());
        verify(mapper, never()).moderateCas(any(), any(), any(), any(), any());
    }

    @Test
    void platformRevertCasMissFailsInsteadOfReportingRecovery() {
        MerchantReview row = review(MERCHANT_OWNER, 1, MerchantReview.STATUS_HIDDEN);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.moderateCas(REVIEW, MerchantReview.STATUS_HIDDEN,
                MerchantReview.STATUS_PENDING_REVIEW, null, 1)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.moderateFromAudit(REVIEW, MerchantReview.STATUS_HIDDEN,
                        MerchantReview.STATUS_PENDING_REVIEW, null));

        assertEquals(409, error.getCode());
    }

    @Test
    void platformModerationRejectsUnexpectedSourceStatusBeforeMapperCas() {
        MerchantReview row = review(MERCHANT_OWNER, 4, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.moderateFromAudit(REVIEW, MerchantReview.STATUS_HIDDEN,
                        MerchantReview.STATUS_VISIBLE, null));

        assertEquals(409, error.getCode());
        verify(mapper, never()).moderateCas(any(), any(), any(), any(), any());
    }

    @Test
    void updateReplyRewritesExistingReplyWithVersionCasAndDistinctAuditAction() {
        MerchantReview row = review(MERCHANT_OWNER, 4, MerchantReview.STATUS_VISIBLE);
        row.setReplyContent("先前的回复");
        row.setReplyRequestId("first-reply");
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.updateReplyCas(eq(REVIEW), eq(MERCHANT_ROW), eq(MERCHANT_OWNER),
                eq("改过的回复"), eq(PRINCIPAL), eq(MerchantRoleCode.MERCHANT_OWNER),
                eq("reply-edit-1"), any(), eq(4))).thenReturn(1);

        MerchantReviewWriteResultVO out = service.updateReply(PRINCIPAL, editReplyRequest("reply-edit-1", "改过的回复", 4));

        assertEquals(5, out.getVersion());
        assertFalse(out.isReplayed());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAudits).insertBusinessAuditEvent(audit.capture());
        assertEquals("MERCHANT_REVIEW_REPLY_UPDATED", audit.getValue().getAction(),
                "改回复必须与第一次回复在审计里可区分");
        assertEquals("改过的回复", row.getReplyContent());
    }

    @Test
    void updateReplyRequiresAnExistingReplyAndCurrentVersion() {
        MerchantReview untouched = review(MERCHANT_OWNER, 4, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(untouched);

        ServiceException noReply = assertThrows(ServiceException.class,
                () -> service.updateReply(PRINCIPAL, editReplyRequest("reply-edit-none", "改过的回复", 4)));
        assertEquals(409, noReply.getCode());
        verify(mapper, never()).updateReplyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());

        MerchantReview stale = review(MERCHANT_OWNER, 4, MerchantReview.STATUS_VISIBLE);
        stale.setReplyContent("先前的回复");
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(stale);
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.updateReply(PRINCIPAL, editReplyRequest("reply-edit-stale", "改过的回复", 3)));
        assertEquals(409, conflict.getCode());
        verify(mapper, never()).updateReplyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateReplyKeepsOwnershipIdorGuard() {
        MerchantReview foreign = review(999L, 4, MerchantReview.STATUS_VISIBLE);
        foreign.setReplyContent("别人的回复");
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(foreign);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateReply(PRINCIPAL, editReplyRequest("reply-edit-cross", "改过的回复", 4)));

        assertEquals(404, error.getCode());
        verify(mapper, never()).updateReplyCas(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteReplyClearsReplyFieldsAndAuditsDeletion() {
        MerchantReview row = review(MERCHANT_OWNER, 4, MerchantReview.STATUS_VISIBLE);
        row.setReplyContent("要删的回复");
        row.setReplyRequestId("first-reply");
        row.setRepliedAt(new java.util.Date());
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);
        when(mapper.deleteReplyCas(eq(REVIEW), eq(MERCHANT_ROW), eq(MERCHANT_OWNER), eq(4))).thenReturn(1);

        MerchantReviewWriteResultVO out = service.deleteReply(PRINCIPAL, deleteReplyRequest("reply-del-1", 4));

        assertEquals(5, out.getVersion());
        assertFalse(out.isReplayed());
        assertNull(row.getReplyContent());
        assertNull(row.getReplyRequestId());
        assertNull(row.getRepliedAt());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(businessAudits).insertBusinessAuditEvent(audit.capture());
        assertEquals("MERCHANT_REVIEW_REPLY_DELETED", audit.getValue().getAction());
    }

    @Test
    void deleteReplyIsIdempotentWhenReplyIsAlreadyGone() {
        MerchantReview row = review(MERCHANT_OWNER, 5, MerchantReview.STATUS_VISIBLE);
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);

        MerchantReviewWriteResultVO out = service.deleteReply(PRINCIPAL, deleteReplyRequest("reply-del-retry", 5));

        assertTrue(out.isReplayed(), "重试删除必须按回执成功返回,不能报冲突");
        verify(mapper, never()).deleteReplyCas(any(), any(), any(), any());
        verify(businessAudits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void deleteReplyRejectsStaleVersionWithoutTouchingRow() {
        MerchantReview row = review(MERCHANT_OWNER, 5, MerchantReview.STATUS_VISIBLE);
        row.setReplyContent("要删的回复");
        when(mapper.selectReviewByIdForUpdate(REVIEW)).thenReturn(row);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.deleteReply(PRINCIPAL, deleteReplyRequest("reply-del-stale", 4)));

        assertEquals(409, error.getCode());
        verify(mapper, never()).deleteReplyCas(any(), any(), any(), any());
    }

    private MerchantAccessContext manager() {
        return new MerchantAccessContext(OPERATOR_PRINCIPAL, MERCHANT_ROW, MERCHANT_OWNER,
                MerchantRoleCode.MERCHANT_MANAGER);
    }

    private MerchantAccessContext owner() {
        return new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, MERCHANT_OWNER,
                MerchantRoleCode.MERCHANT_OWNER);
    }

    private MerchantReviewEligibility eligibility() {
        MerchantReviewEligibility row = new MerchantReviewEligibility();
        row.setMerchantRowId(MERCHANT_ROW);
        row.setMerchantOwnerMemberId(MERCHANT_OWNER);
        row.setRegistrationId(REGISTRATION);
        row.setRedemptionId(REDEMPTION);
        return row;
    }

    private MerchantReview review(Long merchantOwner, int version, String status) {
        MerchantReview row = new MerchantReview();
        row.setId(REVIEW);
        row.setMerchantRowId(MERCHANT_ROW);
        row.setMerchantOwnerMemberId(merchantOwner);
        row.setRegistrationId(REGISTRATION);
        row.setRedemptionId(REDEMPTION);
        row.setAuthorMemberId(AUTHOR);
        row.setRating(5);
        row.setContent("体验很好");
        row.setImagesJson("[]");
        row.setStatus(status);
        row.setVersion(version);
        return row;
    }

    private Map<String, Object> reviewStats(int pendingReplyCount, int monthNewCount, int replyRatePct) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingReplyCount", pendingReplyCount);
        stats.put("monthNewCount", monthNewCount);
        stats.put("replyRatePct", replyRatePct);
        return stats;
    }

    private MerchantReviewCreateRequest createRequest(String requestId, int rating) {
        MerchantReviewCreateRequest request = new MerchantReviewCreateRequest();
        request.setMerchantRowId(MERCHANT_ROW);
        request.setRegistrationId(REGISTRATION);
        request.setRating(rating);
        request.setContent("体验很好，核销顺畅");
        request.setImageUrls(Arrays.asList(
                "https://chengyin-review.oss-cn-hangzhou.aliyuncs.com/upload/0123456789abcdef0123456789abcdef.jpg"
                        + "?OSSAccessKeyId=test&Expires=2000000000&Signature=test"));
        request.setRequestId(requestId);
        return request;
    }

    private MerchantReviewReplyRequest replyRequest(String requestId, int version) {
        MerchantReviewReplyRequest request = new MerchantReviewReplyRequest();
        request.setReviewId(REVIEW);
        request.setContent("感谢反馈，我们会持续改进");
        request.setExpectedVersion(version);
        request.setRequestId(requestId);
        return request;
    }

    private MerchantReviewReplyRequest editReplyRequest(String requestId, String content, int version) {
        MerchantReviewReplyRequest request = new MerchantReviewReplyRequest();
        request.setReviewId(REVIEW);
        request.setContent(content);
        request.setExpectedVersion(version);
        request.setRequestId(requestId);
        return request;
    }

    private MerchantReviewReplyDeleteRequest deleteReplyRequest(String requestId, int version) {
        MerchantReviewReplyDeleteRequest request = new MerchantReviewReplyDeleteRequest();
        request.setReviewId(REVIEW);
        request.setExpectedVersion(version);
        request.setRequestId(requestId);
        return request;
    }

    private MerchantReviewReportRequest reportRequest(String requestId, String reason, int version) {
        MerchantReviewReportRequest request = new MerchantReviewReportRequest();
        request.setReviewId(REVIEW);
        request.setMerchantMemberId(MERCHANT_OWNER);
        request.setReason(reason);
        request.setExpectedVersion(version);
        request.setRequestId(requestId);
        return request;
    }
}
