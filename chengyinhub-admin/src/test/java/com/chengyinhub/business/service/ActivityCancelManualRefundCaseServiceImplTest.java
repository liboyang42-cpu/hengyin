package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundAssessmentRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundHandleRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReopenRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReworkRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundVerifyRequest;
import com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO;
import com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.impl.ActivityCancelManualRefundCaseServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityCancelManualRefundCaseServiceImplTest {
    private ActivityCancelManualRefundCaseMapper mapper;
    private BusinessAuditEventMapper auditMapper;
    private IActivityCancelManualRefundEvidenceStore evidenceStore;
    private ActivityCancelManualRefundCaseServiceImpl service;

    private static final String EVIDENCE_KEY =
            "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg";
    private static final String EVIDENCE_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String EVIDENCE_VERSION_ID = "CAEQARiBgMC-example-version-1";
    private static final String EVIDENCE_UPLOAD_REQUEST_ID = "finance-handle-1";

    @BeforeEach
    void setUp() {
        mapper = mock(ActivityCancelManualRefundCaseMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        evidenceStore = mock(IActivityCancelManualRefundEvidenceStore.class);
        service = new ActivityCancelManualRefundCaseServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "auditMapper", auditMapper);
        ReflectionTestUtils.setField(service, "evidenceStore", evidenceStore);
        when(evidenceStore.sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID)).thenReturn(EVIDENCE_SHA256);
        when(auditMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_EVIDENCE_UPLOAD", EVIDENCE_UPLOAD_REQUEST_ID))
                .thenReturn(evidenceUploadAudit(99L, 8L, 1L, EVIDENCE_UPLOAD_REQUEST_ID,
                        EVIDENCE_KEY, EVIDENCE_VERSION_ID, EVIDENCE_SHA256));
    }

    @Test
    void uploadReadbackIsBoundToUploaderCaseVersionAndRequestId() {
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_EVIDENCE_UPLOAD", "evidence-upload-2"))
                .thenReturn(null, null,
                        evidenceUploadAudit(99L, 8L, 1L, "evidence-upload-2",
                                EVIDENCE_KEY, EVIDENCE_VERSION_ID, EVIDENCE_SHA256));
        when(mapper.selectById(99L)).thenReturn(assessedRequired());
        when(mapper.selectByIdForUpdate(99L)).thenReturn(assessedRequired());

        assertNull(service.prepareEvidenceUpload(8L, 99L, 1L, "evidence-upload-2"));
        String key = service.recordEvidenceUpload(8L, 99L, 1L, "evidence-upload-2",
                EVIDENCE_KEY, EVIDENCE_VERSION_ID);

        assertEquals(EVIDENCE_KEY, key);
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("ACTIVITY_REFUND_EVIDENCE_UPLOAD", audit.getValue().getAction());
        assertEquals(99L, audit.getValue().getTargetId());
        assertEquals(8L, audit.getValue().getActorMemberId());
        assertTrue(audit.getValue().getBeforeJson().contains("\"expectedVersion\":1"));
        assertTrue(audit.getValue().getAfterJson().contains(EVIDENCE_KEY));
        assertTrue(audit.getValue().getAfterJson().contains(EVIDENCE_VERSION_ID));
        assertTrue(audit.getValue().getAfterJson().contains(EVIDENCE_SHA256));
        verify(evidenceStore).sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID);
    }

    @Test
    void uploadReadbackFailureCannotCreateSuccessfulAudit() {
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_EVIDENCE_UPLOAD", "evidence-upload-3"))
                .thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(assessedRequired());
        when(evidenceStore.sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID))
                .thenThrow(new ServiceException("凭证对象暂不可读取", 503));

        ServiceException unavailable = assertThrows(ServiceException.class,
                () -> service.recordEvidenceUpload(8L, 99L, 1L, "evidence-upload-3",
                        EVIDENCE_KEY, EVIDENCE_VERSION_ID));

        assertEquals(Integer.valueOf(503), unavailable.getCode());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void uploadCannotSucceedWhenCaseChangesAfterOssReadback() {
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_EVIDENCE_UPLOAD", "evidence-upload-stale"))
                .thenReturn(null, evidenceUploadAudit(99L, 8L, 1L, "evidence-upload-stale",
                        EVIDENCE_KEY, EVIDENCE_VERSION_ID, EVIDENCE_SHA256));
        when(mapper.selectById(99L)).thenReturn(assessedRequired());
        when(mapper.selectByIdForUpdate(99L)).thenReturn(recordedCase());

        ServiceException stale = assertThrows(ServiceException.class,
                () -> service.recordEvidenceUpload(8L, 99L, 1L, "evidence-upload-stale",
                        EVIDENCE_KEY, EVIDENCE_VERSION_ID));

        assertEquals(Integer.valueOf(409), stale.getCode());
        verify(evidenceStore).sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID);
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void duplicateCancellationReadsBackTheSameDurableSnapshot() {
        ActivityCancelManualRefundCase draft = pendingCase();
        ActivityCancelManualRefundCase stored = pendingCase();
        stored.setId(99L);
        stored.setVersion(0L);
        when(mapper.insertCase(draft)).thenThrow(new DuplicateKeyException("same activity/order"));
        when(mapper.selectByActivityAndParent(200L, "PARENT-1")).thenReturn(stored);

        ActivityCancelManualRefundCaseVO result = service.recordCase(draft);

        assertEquals(99L, result.getId());
        assertEquals(Collections.singletonList(1L), result.getUnverifiedRegistrationIds());
        assertEquals(Collections.singletonList(2L), result.getVerifiedRegistrationIds());
    }

    @Test
    void caseCreationIsMandatoryInsideTheCancellationTransaction() throws Exception {
        Transactional boundary = ActivityCancelManualRefundCaseServiceImpl.class
                .getMethod("recordCase", ActivityCancelManualRefundCase.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.MANDATORY, boundary.propagation());
    }

    @Test
    void duplicateIdentityWithDifferentMoneySnapshotFailsClosed() {
        ActivityCancelManualRefundCase draft = pendingCase();
        ActivityCancelManualRefundCase stored = pendingCase();
        stored.setId(99L);
        stored.setVersion(0L);
        stored.setPaidCashAmountFen(19999L);
        when(mapper.insertCase(draft)).thenThrow(new DuplicateKeyException("same activity/order"));
        when(mapper.selectByActivityAndParent(200L, "PARENT-1")).thenReturn(stored);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.recordCase(draft));

        assertEquals(Integer.valueOf(409), conflict.getCode());
    }

    /** FX-05:主题工单(topic_id)重复冻结按 (主题, 父订单) 回读,快照相同即幂等返回。 */
    @Test
    void duplicateTopicCaseReadsBackByTopicAndParent() {
        ActivityCancelManualRefundCase draft = pendingCase();
        draft.setActivityId(null);
        draft.setTopicId(9L);
        ActivityCancelManualRefundCase stored = pendingCase();
        stored.setActivityId(null);
        stored.setTopicId(9L);
        stored.setId(98L);
        stored.setVersion(0L);
        when(mapper.insertCase(draft)).thenThrow(new DuplicateKeyException("same topic/order"));
        when(mapper.selectByTopicAndParent(9L, "PARENT-1")).thenReturn(stored);

        ActivityCancelManualRefundCaseVO result = service.recordCase(draft);

        assertEquals(98L, result.getId());
        assertEquals(Long.valueOf(9L), result.getTopicId());
        verify(mapper, never()).selectByActivityAndParent(any(), any());
    }

    /** FX-05:工单必须恰好挂一个对象 —— 两个都挂、两个都空都不能写库。 */
    @Test
    void caseMustBelongToExactlyOneActivityOrTopic() {
        ActivityCancelManualRefundCase both = pendingCase();
        both.setTopicId(9L);
        ActivityCancelManualRefundCase neither = pendingCase();
        neither.setActivityId(null);

        assertEquals("工单必须恰好挂一个场次或主题",
                assertThrows(ServiceException.class, () -> service.recordCase(both)).getMessage());
        assertEquals("工单必须恰好挂一个场次或主题",
                assertThrows(ServiceException.class, () -> service.recordCase(neither)).getMessage());
        verify(mapper, never()).insertCase(any(ActivityCancelManualRefundCase.class));
    }

    @Test
    void malformedNullSnapshotNeverReachesInsert() {
        ActivityCancelManualRefundCase draft = pendingCase();
        draft.setPaidCashAmountFen(null);

        assertThrows(ServiceException.class, () -> service.recordCase(draft));

        verify(mapper, never()).insertCase(any(ActivityCancelManualRefundCase.class));
    }

    @Test
    void assessmentRequestIdIsIdempotentlyReadBackAfterRestart() {
        ActivityCancelManualRefundAssessmentRequest request = assessment(
                "MANUAL_REFUND_REQUIRED", 12000L, "finance-assess-1");
        ActivityCancelManualRefundCase assessed = pendingCase();
        assessed.setId(99L);
        assessed.setStatus(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED);
        assessed.setAssessedRefundAmountFen(12000L);
        assessed.setAssessmentNote("未核销票按现金比例人工处理");
        assessed.setAssessedByUserId(7L);
        assessed.setAssessmentRequestId("finance-assess-1");
        assessed.setAssessedAt(new java.util.Date());
        assessed.setVersion(1L);
        when(mapper.selectByAssessmentRequestId("finance-assess-1")).thenReturn(assessed);

        ActivityCancelManualRefundCaseVO result = service.assess(7L, request);

        assertEquals(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED, result.getStatus());
        assertEquals(12000L, result.getAssessedRefundAmountFen());
        verify(mapper, never()).assessCase(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void assessmentUsesVersionCasAndAuthoritativeReadback() {
        ActivityCancelManualRefundAssessmentRequest request = assessment(
                "NO_REFUND", 0L, "finance-assess-2");
        ActivityCancelManualRefundCase pending = pendingCase();
        pending.setId(99L);
        pending.setVersion(0L);
        ActivityCancelManualRefundCase assessed = pendingCase();
        assessed.setId(99L);
        assessed.setStatus(ActivityCancelManualRefundCase.STATUS_NO_REFUND);
        assessed.setAssessedRefundAmountFen(0L);
        assessed.setAssessmentNote("已核销服务价值覆盖整单现金");
        assessed.setAssessedByUserId(7L);
        assessed.setAssessmentRequestId("finance-assess-2");
        assessed.setAssessedAt(new java.util.Date());
        assessed.setVersion(1L);
        when(mapper.selectByAssessmentRequestId("finance-assess-2")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(pending, assessed);
        when(mapper.assessCase(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        ActivityCancelManualRefundCaseVO result = service.assess(7L, request);

        assertEquals(ActivityCancelManualRefundCase.STATUS_NO_REFUND, result.getStatus());
        assertEquals(1L, result.getVersion());
        verify(mapper).assessCase(eq(99L), eq(0L),
                eq(ActivityCancelManualRefundCase.STATUS_NO_REFUND),
                eq(0L), eq("已核销服务价值覆盖整单现金"), eq(7L),
                eq("finance-assess-2"), any(java.util.Date.class));
    }

    @Test
    void manualAssessmentCannotExceedFrozenPaidCash() {
        ActivityCancelManualRefundAssessmentRequest request = assessment(
                "MANUAL_REFUND_REQUIRED", 20001L, "finance-assess-3");
        ActivityCancelManualRefundCase pending = pendingCase();
        pending.setId(99L);
        pending.setVersion(0L);
        when(mapper.selectByAssessmentRequestId("finance-assess-3")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(pending);

        ServiceException invalid = assertThrows(ServiceException.class,
                () -> service.assess(7L, request));

        assertEquals(Integer.valueOf(400), invalid.getCode());
        verify(mapper, never()).assessCase(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void malformedDatabaseRowMakesTheWholeListFailClosed() {
        ActivityCancelManualRefundCase malformed = pendingCase();
        malformed.setId(99L);
        malformed.setVersion(0L);
        malformed.setVerifiedRegistrationIdsJson("[2,2]");
        when(mapper.selectList(null, ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT))
                .thenReturn(Collections.singletonList(malformed));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.list(null, ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT));

        assertEquals(Integer.valueOf(500), failure.getCode());
    }

    @Test
    void handleAcceptsOnlyExistingManualChannelAndExactAssessedAmount() {
        ActivityCancelManualRefundHandleRequest request = handleRequest("offline_transfer");
        ActivityCancelManualRefundCase required = assessedRequired();
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectByHandleRequestId("finance-handle-1")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(required, recorded);
        when(mapper.handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        ActivityCancelManualRefundCaseVO result = service.handle(8L, request);

        assertEquals(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED, result.getStatus());
        assertEquals("offline_transfer", result.getManualChannel());
        assertEquals(EVIDENCE_SHA256, result.getEvidenceSha256());
        verify(evidenceStore, times(2)).sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID);
        assertEquals("凭证已复核不等于银行或微信到账", result.getEvidenceVerificationNotice());
    }

    @Test
    void inventedManualChannelFailsBeforeAnyWrite() {
        ActivityCancelManualRefundHandleRequest request = handleRequest("cash_in_envelope");

        ServiceException invalid = assertThrows(ServiceException.class,
                () -> service.handle(8L, request));

        assertEquals(Integer.valueOf(400), invalid.getCode());
        verify(mapper, never()).handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void handlerCannotVerifyOwnEvidence() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectByVerifyRequestId("finance-verify-1")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(recorded);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.verify(8L, verifyRequest()));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(mapper, never()).verifyCase(any(), any(), any(), any(), any(), any(), any());
        verify(evidenceStore, never()).sha256(any(String.class), any(String.class));
    }

    @Test
    void secondOperatorVerificationEndsOnlyAtEvidenceVerified() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        ActivityCancelManualRefundCase verified = recordedCase();
        verified.setStatus(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED);
        verified.setVerificationNote("已从受控凭证源复核哈希与金额");
        verified.setVerifiedByUserId(9L);
        verified.setVerifyRequestId("finance-verify-1");
        verified.setVerifiedAt(new java.util.Date());
        verified.setVersion(3L);
        when(mapper.selectByVerifyRequestId("finance-verify-1")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(recorded, verified);
        when(mapper.verifyCase(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        ActivityCancelManualRefundCaseVO result = service.verify(9L, verifyRequest());

        assertEquals(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED,
                result.getStatus());
        verify(evidenceStore, times(2)).sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID);
        assertEquals("凭证已复核不等于银行或微信到账", result.getEvidenceVerificationNotice());
    }

    @Test
    void assessmentReplayStillReadsCurrentCaseAfterLaterHandling() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectByAssessmentRequestId("finance-assess-1")).thenReturn(recorded);

        ActivityCancelManualRefundCaseVO result = service.assess(7L,
                assessment("MANUAL_REFUND_REQUIRED", 12000L, "finance-assess-1"));

        assertEquals(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED,
                result.getStatus());
        verify(mapper, never()).assessCase(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void handleReplayStillReadsCurrentCaseAfterSecondPersonVerification() {
        ActivityCancelManualRefundCase verified = recordedCase();
        verified.setStatus(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED);
        verified.setVerificationNote("已从受控凭证源复核哈希与金额");
        verified.setVerifiedByUserId(9L);
        verified.setVerifyRequestId("finance-verify-1");
        verified.setVerifiedAt(new java.util.Date());
        verified.setVersion(3L);
        when(mapper.selectByHandleRequestId("finance-handle-1")).thenReturn(verified);

        ActivityCancelManualRefundCaseVO result = service.handle(8L,
                handleRequest("offline_transfer"));

        assertEquals(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED,
                result.getStatus());
        verify(mapper, never()).handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ordinaryUploadKeyCannotBeUsedAsRefundEvidence() {
        ActivityCancelManualRefundHandleRequest request = handleRequest("offline_transfer");
        request.setEvidenceObjectKey("upload/0123456789abcdef0123456789abcdef.jpg");

        ServiceException invalid = assertThrows(ServiceException.class,
                () -> service.handle(8L, request));

        assertEquals(Integer.valueOf(400), invalid.getCode());
        verify(evidenceStore, never()).sha256(any(String.class), any(String.class));
        verify(mapper, never()).handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void evidenceReadFailureNeverAdvancesRecordedState() {
        ActivityCancelManualRefundHandleRequest request = handleRequest("offline_transfer");
        ActivityCancelManualRefundCase required = assessedRequired();
        when(mapper.selectByHandleRequestId("finance-handle-1")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(required);
        when(evidenceStore.sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID))
                .thenThrow(new ServiceException("凭证对象暂不可读取", 503));

        ServiceException unavailable = assertThrows(ServiceException.class,
                () -> service.handle(8L, request));

        assertEquals(Integer.valueOf(503), unavailable.getCode());
        verify(mapper, never()).handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void digestChangeBetweenPrecheckAndAuthoritativeReadbackRollsBackHandle() {
        ActivityCancelManualRefundCase required = assessedRequired();
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectByHandleRequestId(EVIDENCE_UPLOAD_REQUEST_ID)).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(required, recorded);
        when(mapper.handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(evidenceStore.sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID))
                .thenReturn(EVIDENCE_SHA256, repeat('b', 64));

        ServiceException changed = assertThrows(ServiceException.class,
                () -> service.handle(8L, handleRequest("offline_transfer")));

        assertEquals(Integer.valueOf(409), changed.getCode());
        verify(mapper).handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void verifierMustRereadTheSameServerObjectAndRejectChangedBytes() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectByVerifyRequestId("finance-verify-1")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(recorded);
        when(evidenceStore.sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID)).thenReturn(repeat('b', 64));

        ServiceException changed = assertThrows(ServiceException.class,
                () -> service.verify(9L, verifyRequest()));

        assertEquals(Integer.valueOf(409), changed.getCode());
        verify(mapper, never()).verifyCase(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void detailUsesShortLivedPreviewWithoutExposingStoredObjectKey() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectById(99L)).thenReturn(recorded);
        when(evidenceStore.shortLivedPreviewUrl(EVIDENCE_KEY, EVIDENCE_VERSION_ID))
                .thenReturn("https://private.example/evidence?Signature=short");

        ActivityCancelManualRefundCaseVO detail = service.detail(99L);

        assertEquals("https://private.example/evidence?Signature=short", detail.getEvidencePreviewUrl());
        verify(evidenceStore).sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID);
        verify(evidenceStore).shortLivedPreviewUrl(EVIDENCE_KEY, EVIDENCE_VERSION_ID);
    }

    @Test
    void previewFailsClosedWhenImmutableVersionDigestDoesNotMatch() {
        when(mapper.selectById(99L)).thenReturn(recordedCase());
        when(evidenceStore.sha256(EVIDENCE_KEY, EVIDENCE_VERSION_ID)).thenReturn(repeat('b', 64));

        ServiceException changed = assertThrows(ServiceException.class, () -> service.detail(99L));

        assertEquals(Integer.valueOf(409), changed.getCode());
        verify(evidenceStore, never()).shortLivedPreviewUrl(any(String.class), any(String.class));
    }

    @Test
    void uploadBoundToAnotherCaseOrUploaderCannotBeHandled() {
        ActivityCancelManualRefundCase required = assessedRequired();
        when(mapper.selectByHandleRequestId(EVIDENCE_UPLOAD_REQUEST_ID)).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(required);
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_EVIDENCE_UPLOAD", EVIDENCE_UPLOAD_REQUEST_ID))
                .thenReturn(evidenceUploadAudit(100L, 8L, 1L, EVIDENCE_UPLOAD_REQUEST_ID,
                        EVIDENCE_KEY, EVIDENCE_VERSION_ID, EVIDENCE_SHA256));

        ServiceException wrongCase = assertThrows(ServiceException.class,
                () -> service.handle(8L, handleRequest("offline_transfer")));
        assertEquals(Integer.valueOf(409), wrongCase.getCode());

        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_EVIDENCE_UPLOAD", EVIDENCE_UPLOAD_REQUEST_ID))
                .thenReturn(evidenceUploadAudit(99L, 7L, 1L, EVIDENCE_UPLOAD_REQUEST_ID,
                        EVIDENCE_KEY, EVIDENCE_VERSION_ID, EVIDENCE_SHA256));
        ServiceException wrongUploader = assertThrows(ServiceException.class,
                () -> service.handle(8L, handleRequest("offline_transfer")));
        assertEquals(Integer.valueOf(409), wrongUploader.getCode());
        verify(mapper, never()).handleCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void evidenceCannotBeReusedWithANewHandleRequestOrLaterCycle() {
        ActivityCancelManualRefundHandleRequest differentRequest = handleRequest("offline_transfer");
        differentRequest.setRequestId("finance-handle-2");
        when(mapper.selectByHandleRequestId("finance-handle-2")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(assessedRequired());

        ServiceException missingBinding = assertThrows(ServiceException.class,
                () -> service.handle(8L, differentRequest));
        assertEquals(Integer.valueOf(409), missingBinding.getCode());

        ActivityCancelManualRefundHandleRequest laterCycle = handleRequest("offline_transfer");
        laterCycle.setExpectedVersion(3L);
        ActivityCancelManualRefundCase requiredAgain = assessedRequired();
        requiredAgain.setVersion(3L);
        when(mapper.selectById(99L)).thenReturn(requiredAgain);
        ServiceException staleBinding = assertThrows(ServiceException.class,
                () -> service.handle(8L, laterCycle));
        assertEquals(Integer.valueOf(409), staleBinding.getCode());
    }

    @Test
    void differentReviewerCanReworkRecordedEvidenceAfterAppendingAudit() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        ActivityCancelManualRefundCase requiredAgain = assessedRequired();
        requiredAgain.setVersion(3L);
        when(mapper.selectByIdForUpdate(99L)).thenReturn(recorded);
        when(mapper.selectById(99L)).thenReturn(requiredAgain);
        when(mapper.reworkCase(eq(99L), eq(2L), any(java.util.Date.class))).thenReturn(1);

        ActivityCancelManualRefundCaseVO result = service.rework(9L, reworkRequest());

        assertEquals(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED, result.getStatus());
        ArgumentCaptor<BusinessAuditEvent> audits = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper, times(2)).insertBusinessAuditEvent(audits.capture());
        BusinessAuditEvent archive = audits.getAllValues().get(0);
        BusinessAuditEvent transition = audits.getAllValues().get(1);
        assertEquals("ACTIVITY_REFUND_HANDLE_ARCHIVE", archive.getAction());
        assertTrue(archive.getBeforeJson().contains(EVIDENCE_KEY));
        assertTrue(archive.getBeforeJson().contains(EVIDENCE_SHA256));
        assertTrue(archive.getBeforeJson().contains(EVIDENCE_VERSION_ID));
        assertTrue(archive.getBeforeJson().contains("\"actualAmountFen\":12000"));
        assertTrue(archive.getBeforeJson().contains("\"channel\":\"offline_transfer\""));
        assertEquals("ACTIVITY_REFUND_REWORK", transition.getAction());
        assertEquals(9L, transition.getActorMemberId());
        assertEquals("finance-rework-1", transition.getRequestId());
        assertEquals("凭证金额无法辨认，请重新上传", transition.getReason());
        verify(mapper).reworkCase(eq(99L), eq(2L), any(java.util.Date.class));
    }

    @Test
    void handlerCannotApproveReworkOfOwnEvidence() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        when(mapper.selectByIdForUpdate(99L)).thenReturn(recorded);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.rework(8L, reworkRequest()));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper, never()).reworkCase(any(), any(), any());
    }

    @Test
    void staleReworkVersionCannotAppendAuditOrChangeState() {
        ActivityCancelManualRefundCase recorded = recordedCase();
        ActivityCancelManualRefundReworkRequest request = reworkRequest();
        request.setExpectedVersion(1L);
        when(mapper.selectByIdForUpdate(99L)).thenReturn(recorded);

        ServiceException stale = assertThrows(ServiceException.class,
                () -> service.rework(9L, request));

        assertEquals(Integer.valueOf(409), stale.getCode());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper, never()).reworkCase(any(), any(), any());
    }

    @Test
    void oldEvidenceCannotBeVerifiedAfterReworkClearsCurrentAttempt() {
        ActivityCancelManualRefundCase requiredAgain = assessedRequired();
        requiredAgain.setVersion(3L);
        when(mapper.selectByVerifyRequestId("finance-verify-1")).thenReturn(null);
        when(mapper.selectById(99L)).thenReturn(requiredAgain);

        ServiceException stale = assertThrows(ServiceException.class,
                () -> service.verify(9L, verifyRequest()));

        assertEquals(Integer.valueOf(409), stale.getCode());
        verify(evidenceStore, never()).sha256(any(String.class), any(String.class));
        verify(mapper, never()).verifyCase(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void differentReviewerCanReopenNoRefundAfterAppendingAudit() {
        ActivityCancelManualRefundCase noRefund = noRefundCase();
        ActivityCancelManualRefundCase pendingAgain = pendingCase();
        pendingAgain.setId(99L);
        pendingAgain.setVersion(2L);
        when(mapper.selectByIdForUpdate(99L)).thenReturn(noRefund);
        when(mapper.selectById(99L)).thenReturn(pendingAgain);
        when(mapper.reopenAssessment(eq(99L), eq(1L), any(java.util.Date.class))).thenReturn(1);

        ActivityCancelManualRefundCaseVO result = service.reopenAssessment(9L, reopenRequest());

        assertEquals(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT, result.getStatus());
        verify(auditMapper, times(2)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper).reopenAssessment(eq(99L), eq(1L), any(java.util.Date.class));
    }

    @Test
    void originalAssessorCannotReopenOwnNoRefundDecision() {
        when(mapper.selectByIdForUpdate(99L)).thenReturn(noRefundCase());

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.reopenAssessment(7L, reopenRequest()));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper, never()).reopenAssessment(any(), any(), any());
    }

    @Test
    void staleReopenVersionCannotAppendAuditOrChangeState() {
        ActivityCancelManualRefundReopenRequest request = reopenRequest();
        request.setExpectedVersion(0L);
        when(mapper.selectByIdForUpdate(99L)).thenReturn(noRefundCase());

        ServiceException stale = assertThrows(ServiceException.class,
                () -> service.reopenAssessment(9L, request));

        assertEquals(Integer.valueOf(409), stale.getCode());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper, never()).reopenAssessment(any(), any(), any());
    }

    @Test
    void repeatedReworkRequestReadsCurrentStateWithoutAppendingAgain() {
        BusinessAuditEvent replay = transitionAudit("ACTIVITY_REFUND_REWORK", 9L,
                "finance-rework-1", 2L, "凭证金额无法辨认，请重新上传");
        ActivityCancelManualRefundCase requiredAgain = assessedRequired();
        requiredAgain.setVersion(3L);
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_REWORK", "finance-rework-1"))
                .thenReturn(replay);
        when(mapper.selectById(99L)).thenReturn(requiredAgain);

        ActivityCancelManualRefundCaseVO result = service.rework(9L, reworkRequest());

        assertEquals(3L, result.getVersion());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper, never()).reworkCase(any(), any(), any());
    }

    @Test
    void repeatedReopenRequestReadsCurrentStateWithoutAppendingAgain() {
        BusinessAuditEvent replay = transitionAudit("ACTIVITY_REFUND_REOPEN", 9L,
                "finance-reopen-1", 1L, "判额依据补充，需要重新评估");
        ActivityCancelManualRefundCase pendingAgain = pendingCase();
        pendingAgain.setId(99L);
        pendingAgain.setVersion(2L);
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "PLATFORM", 1L, "ACTIVITY_REFUND_REOPEN", "finance-reopen-1"))
                .thenReturn(replay);
        when(mapper.selectById(99L)).thenReturn(pendingAgain);

        ActivityCancelManualRefundCaseVO result = service.reopenAssessment(9L, reopenRequest());

        assertEquals(2L, result.getVersion());
        verify(auditMapper, never()).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(mapper, never()).reopenAssessment(any(), any(), any());
    }

    private ActivityCancelManualRefundAssessmentRequest assessment(String decision, Long amount,
                                                                    String requestId) {
        ActivityCancelManualRefundAssessmentRequest request = new ActivityCancelManualRefundAssessmentRequest();
        request.setCaseId(99L);
        request.setExpectedVersion(0L);
        request.setDecision(decision);
        request.setAssessedRefundAmountFen(amount);
        request.setNote(decision.equals("NO_REFUND")
                ? "已核销服务价值覆盖整单现金" : "未核销票按现金比例人工处理");
        request.setRequestId(requestId);
        return request;
    }

    private ActivityCancelManualRefundCase pendingCase() {
        ActivityCancelManualRefundCase row = new ActivityCancelManualRefundCase();
        row.setClubId(8L);
        row.setActivityId(200L);
        row.setOwnerMemberId(77L);
        row.setActorMemberId(78L);
        row.setParentOrderNo("PARENT-1");
        row.setPayerRegistrationId(1L);
        row.setPayerMemberId(501L);
        row.setPaidCashAmountFen(20000L);
        row.setVerifiedRegistrationIdsJson("[2]");
        row.setUnverifiedRegistrationIdsJson("[1]");
        row.setStatus(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT);
        return row;
    }

    private ActivityCancelManualRefundCase assessedRequired() {
        ActivityCancelManualRefundCase row = pendingCase();
        row.setId(99L);
        row.setStatus(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED);
        row.setAssessedRefundAmountFen(12000L);
        row.setAssessmentNote("未核销票按现金比例人工处理");
        row.setAssessedByUserId(7L);
        row.setAssessmentRequestId("finance-assess-1");
        row.setAssessedAt(new java.util.Date());
        row.setVersion(1L);
        return row;
    }

    private ActivityCancelManualRefundCase recordedCase() {
        ActivityCancelManualRefundCase row = assessedRequired();
        row.setStatus(ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED);
        row.setActualRefundAmountFen(12000L);
        row.setManualChannel("offline_transfer");
        row.setEvidenceObjectKey(EVIDENCE_KEY);
        row.setEvidenceSha256(EVIDENCE_SHA256);
        row.setHandleNote("线下转账完成，凭证已归档");
        row.setHandledByUserId(8L);
        row.setHandleRequestId("finance-handle-1");
        row.setHandledAt(new java.util.Date());
        row.setVersion(2L);
        return row;
    }

    private ActivityCancelManualRefundHandleRequest handleRequest(String channel) {
        ActivityCancelManualRefundHandleRequest request = new ActivityCancelManualRefundHandleRequest();
        request.setCaseId(99L);
        request.setExpectedVersion(1L);
        request.setActualRefundAmountFen(12000L);
        request.setChannel(channel);
        request.setEvidenceObjectKey(EVIDENCE_KEY);
        request.setNote("线下转账完成，凭证已归档");
        request.setRequestId("finance-handle-1");
        return request;
    }

    private ActivityCancelManualRefundVerifyRequest verifyRequest() {
        ActivityCancelManualRefundVerifyRequest request = new ActivityCancelManualRefundVerifyRequest();
        request.setCaseId(99L);
        request.setExpectedVersion(2L);
        request.setNote("已从受控凭证源复核哈希与金额");
        request.setRequestId("finance-verify-1");
        return request;
    }

    private ActivityCancelManualRefundCase noRefundCase() {
        ActivityCancelManualRefundCase row = pendingCase();
        row.setId(99L);
        row.setStatus(ActivityCancelManualRefundCase.STATUS_NO_REFUND);
        row.setAssessedRefundAmountFen(0L);
        row.setAssessmentNote("已核销服务价值覆盖整单现金");
        row.setAssessedByUserId(7L);
        row.setAssessmentRequestId("finance-assess-no-refund-1");
        row.setAssessedAt(new java.util.Date());
        row.setVersion(1L);
        return row;
    }

    private ActivityCancelManualRefundReworkRequest reworkRequest() {
        ActivityCancelManualRefundReworkRequest request = new ActivityCancelManualRefundReworkRequest();
        request.setCaseId(99L);
        request.setExpectedVersion(2L);
        request.setReason("凭证金额无法辨认，请重新上传");
        request.setRequestId("finance-rework-1");
        return request;
    }

    private ActivityCancelManualRefundReopenRequest reopenRequest() {
        ActivityCancelManualRefundReopenRequest request = new ActivityCancelManualRefundReopenRequest();
        request.setCaseId(99L);
        request.setExpectedVersion(1L);
        request.setReason("判额依据补充，需要重新评估");
        request.setRequestId("finance-reopen-1");
        return request;
    }

    private BusinessAuditEvent transitionAudit(String action, Long actorUserId,
                                                String requestId, Long expectedVersion,
                                                String reason) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("PLATFORM");
        event.setDomainId(1L);
        event.setOwnerMemberId(77L);
        event.setActorMemberId(actorUserId);
        event.setActorRoleCode("REFUND_REVIEWER");
        event.setAction(action);
        event.setTargetType("MANUAL_REFUND_CASE");
        event.setTargetId(99L);
        event.setBeforeJson("{\"expectedVersion\":" + expectedVersion + "}");
        event.setAfterJson("{}");
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new java.util.Date());
        return event;
    }

    private BusinessAuditEvent evidenceUploadAudit(Long caseId, Long uploaderUserId,
                                                   Long expectedVersion, String requestId,
                                                   String objectKey, String versionId,
                                                   String sha256) {
        BusinessAuditEvent event = transitionAudit("ACTIVITY_REFUND_EVIDENCE_UPLOAD",
                uploaderUserId, requestId, expectedVersion, "人工退款凭证上传回读通过");
        event.setTargetId(caseId);
        event.setRelationId(200L);
        event.setAfterJson("{\"objectKey\":\"" + objectKey + "\","
                + "\"versionId\":\"" + versionId + "\",\"sha256\":\"" + sha256 + "\"}");
        return event;
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
