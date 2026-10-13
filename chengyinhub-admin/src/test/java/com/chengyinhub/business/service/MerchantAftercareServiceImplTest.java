package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantAftercareResponse;
import com.chengyinhub.business.domain.MerchantAftercareListRow;
import com.chengyinhub.business.domain.MerchantRefundPolicySnapshot;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.MerchantAftercareRespondRequest;
import com.chengyinhub.business.domain.vo.MerchantAftercareDetailVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareListItemVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareListVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareResponseVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantAftercareMapper;
import com.chengyinhub.business.service.impl.MerchantAftercareServiceImpl;
import com.chengyinhub.business.service.impl.MerchantAftercareEvidenceUrlSigner;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MerchantAftercareServiceImplTest {
    private static final long OWNER = 900L;
    private static final long PRINCIPAL = OWNER;
    private static final long OPERATOR_PRINCIPAL = 901L;
    private static final long MERCHANT = 77L;
    private static final long OTHER_MERCHANT = 88L;
    private static final long REFUND = 41L;
    private static final String EVIDENCE_KEY =
            "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg";
    private static final String OTHER_EVIDENCE_KEY =
            "upload/merchant-aftercare-evidence/fedcba9876543210fedcba9876543210.png";
    private static final String SIGNED_EVIDENCE_URL =
            "https://private-oss.example/upload/evidence.jpg?Expires=300&Signature=short-lived";

    private IMerchantAccessService access;
    private MerchantAftercareMapper mapper;
    private BusinessAuditEventMapper audits;
    private MerchantAftercareEvidenceUrlSigner evidenceUrlSigner;
    private MerchantAftercareServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IMerchantAccessService.class);
        mapper = mock(MerchantAftercareMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        evidenceUrlSigner = mock(MerchantAftercareEvidenceUrlSigner.class);
        service = new MerchantAftercareServiceImpl();
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        ReflectionTestUtils.setField(service, "merchantAftercareMapper", mapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(service, "evidenceUrlSigner", evidenceUrlSigner);
        when(access.require(PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(ownerAccess());
        when(access.require(PRINCIPAL, MerchantPermission.AFTERCARE_RESPOND)).thenReturn(ownerAccess());
        when(access.require(PRINCIPAL, MerchantPermission.AFTERCARE_DECIDE)).thenReturn(ownerAccess());
        when(access.require(PRINCIPAL, MerchantPermission.AFTERCARE_EVIDENCE)).thenReturn(ownerAccess());
        when(mapper.lockActiveMerchantOwner(MERCHANT, OWNER)).thenReturn(MERCHANT);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(evidenceUrlSigner.sign(EVIDENCE_KEY)).thenReturn(SIGNED_EVIDENCE_URL);
    }

    @Test
    void detailUsesOwnerMemberScopeAndReturnsExplicitProcessingAndOpinion() {
        RefundApplication refund = pendingRefund();
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(refund);
        MerchantRefundPolicySnapshot policy = new MerchantRefundPolicySnapshot();
        policy.setPolicyCode("EXPLORE_END_100_0");
        policy.setPolicyVersion(1);
        policy.setRefundDeadline(new Date(123456789L));
        when(mapper.selectOwnedRefundPolicySnapshot(MERCHANT, OWNER, REFUND)).thenReturn(policy);
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Arrays.asList(
                response(1L, MerchantAftercareResponse.DECISION_EVIDENCE, "补充凭证", EVIDENCE_KEY, "r1"),
                response(2L, MerchantAftercareResponse.DECISION_REJECT, "现场记录不符", null, "r2")));

        MerchantAftercareDetailVO out = service.detail(PRINCIPAL, REFUND);

        verify(access).require(PRINCIPAL, MerchantPermission.AFTERCARE_READ);
        verify(mapper).selectOwnedRefundById(MERCHANT, OWNER, REFUND);
        verify(mapper).selectOwnedRefundPolicySnapshot(MERCHANT, OWNER, REFUND);
        assertEquals("WAITING_PLATFORM_REVIEW", out.getProcessing());
        assertEquals("REJECT", out.getMerchantOpinion());
        assertTrue(out.isCanRespond());
        assertEquals(Arrays.asList("AGREE", "REJECT", "EVIDENCE"), out.getAllowedDecisions());
        assertEquals(2, out.getResponses().size());
        assertEquals(SIGNED_EVIDENCE_URL, out.getResponses().get(0).getEvidenceUrl());
        verify(evidenceUrlSigner).sign(EVIDENCE_KEY);
        assertEquals("EXPLORE_END_100_0", out.getRefundPolicyCode());
        assertEquals(1, out.getRefundPolicyVersion());
        assertEquals(new Date(123456789L), out.getRefundDeadline());
    }

    @Test
    void listUsesOwnerScopeAndSeparatesAwaitingReplyFromPlatformProcessing() {
        MerchantAftercareListRow waiting = listRow(41L, 0,
                RefundApplication.PAYOUT_DISPATCH_PENDING, "PENDING");
        when(mapper.countOwnedRefundList(MERCHANT, OWNER, "PENDING")).thenReturn(1L);
        when(mapper.selectOwnedRefundList(MERCHANT, OWNER, "PENDING", 0, 20))
                .thenReturn(Collections.singletonList(waiting));

        MerchantAftercareListVO out = service.list(PRINCIPAL, " pending ", null, null);

        verify(access).require(PRINCIPAL, MerchantPermission.AFTERCARE_READ);
        verify(mapper).countOwnedRefundList(MERCHANT, OWNER, "PENDING");
        verify(mapper).selectOwnedRefundList(MERCHANT, OWNER, "PENDING", 0, 20);
        assertEquals("PENDING", out.getBucket());
        assertEquals(1L, out.getTotal());
        assertEquals("PENDING", out.getItems().get(0).getBucket());
        assertEquals("WAITING_PLATFORM_REVIEW", out.getItems().get(0).getProcessing());
        assertTrue(out.getItems().get(0).isCanRespond());

        MerchantAftercareListRow responded = listRow(42L, 0,
                RefundApplication.PAYOUT_DISPATCH_PENDING, "AGREE");
        when(mapper.countOwnedRefundList(MERCHANT, OWNER, "PROCESSING")).thenReturn(1L);
        when(mapper.selectOwnedRefundList(MERCHANT, OWNER, "PROCESSING", 0, 10))
                .thenReturn(Collections.singletonList(responded));
        MerchantAftercareListVO processing = service.list(PRINCIPAL, "PROCESSING", 1, 10);
        assertEquals("PROCESSING", processing.getItems().get(0).getBucket(),
                "商家已回应但平台尚未终审，应进入平台处理中而非继续挂待回应");
    }

    @Test
    void listAndDetailExposeDisplayFactsButNicknameOnlyForCrmReaders() {
        Date takeover = new Date(1758074760000L);
        MerchantAftercareListRow row = listRow(41L, 0, RefundApplication.PAYOUT_DISPATCH_PENDING, "PENDING");
        row.setActivityTitle("外滩夜跑");
        row.setCustomerNickname("小王");
        row.setPlatformTakeoverAt(takeover);
        when(mapper.countOwnedRefundList(MERCHANT, OWNER, "PENDING")).thenReturn(1L);
        when(mapper.selectOwnedRefundList(MERCHANT, OWNER, "PENDING", 0, 20)).thenReturn(Collections.singletonList(row));
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Collections.emptyList());
        when(mapper.selectOwnedRefundDisplay(MERCHANT, OWNER, REFUND)).thenReturn(row);

        MerchantAftercareListItemVO ownerItem = service.list(PRINCIPAL, "PENDING", null, null).getItems().get(0);
        assertEquals("外滩夜跑", ownerItem.getActivityTitle());
        assertEquals("小王", ownerItem.getCustomerNickname());
        assertEquals(takeover, ownerItem.getPlatformTakeoverAt());
        MerchantAftercareDetailVO ownerDetail = service.detail(PRINCIPAL, REFUND);
        verify(mapper).selectOwnedRefundDisplay(MERCHANT, OWNER, REFUND);
        assertEquals("外滩夜跑", ownerDetail.getActivityTitle());
        assertEquals("小王", ownerDetail.getCustomerNickname());
        assertEquals(takeover, ownerDetail.getPlatformTakeoverAt());

        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(managerAccess());
        assertEquals("小王", service.detail(OPERATOR_PRINCIPAL, REFUND).getCustomerNickname(),
                "店长有 CRM 读权限,与客户页同口径可见昵称");

        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(financeAccess());
        MerchantAftercareListItemVO financeItem = service.list(OPERATOR_PRINCIPAL, "PENDING", null, null).getItems().get(0);
        MerchantAftercareDetailVO financeDetail = service.detail(OPERATOR_PRINCIPAL, REFUND);
        assertNull(financeItem.getCustomerNickname(), "财务岗无 CRM 读权限,列表不得下发申请人昵称");
        assertNull(financeDetail.getCustomerNickname(), "财务岗无 CRM 读权限,详情不得下发申请人昵称");
        assertEquals("外滩夜跑", financeDetail.getActivityTitle(), "活动名不是个人信息,财务可见");
        assertEquals(takeover, financeDetail.getPlatformTakeoverAt());
    }

    @Test
    void detailToleratesMissingDisplayRowWithoutInventingFacts() {
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Collections.emptyList());
        when(mapper.selectOwnedRefundDisplay(MERCHANT, OWNER, REFUND)).thenReturn(null);

        MerchantAftercareDetailVO out = service.detail(PRINCIPAL, REFUND);

        assertEquals(REFUND, out.getRefundId());
        assertNull(out.getActivityTitle());
        assertNull(out.getCustomerNickname());
        assertNull(out.getPlatformTakeoverAt());
    }

    @Test
    void listRejectsUnknownBucketAndUnboundedPageSizeBeforeMapperRead() {
        ServiceException bucket = assertThrows(ServiceException.class,
                () -> service.list(PRINCIPAL, "REFUNDED", 1, 20));
        assertEquals(400, bucket.getCode());
        ServiceException size = assertThrows(ServiceException.class,
                () -> service.list(PRINCIPAL, "ALL", 1, 500));
        assertEquals(400, size.getCode());
        verify(mapper, never()).selectOwnedRefundList(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void detailFailsClosedForCrossMerchantRefund() {
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class, () -> service.detail(PRINCIPAL, REFUND));

        assertEquals(404, error.getCode());
        verify(mapper, never()).selectResponses(any(), any());
    }

    @Test
    void revokedMerchantAccessCannotReadOrMintEvidenceUrl() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.detail(OPERATOR_PRINCIPAL, REFUND));

        assertEquals(403, error.getCode());
        verifyNoInteractions(evidenceUrlSigner);
        verify(mapper, never()).selectOwnedRefundById(any(), any(), any());
        verify(mapper, never()).selectResponses(any(), any());
    }

    @Test
    void managerCanReadExactStoreAftercare() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(managerAccess());
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Collections.emptyList());

        MerchantAftercareDetailVO out = service.detail(OPERATOR_PRINCIPAL, REFUND);

        assertEquals(REFUND, out.getRefundId());
        verify(mapper).selectOwnedRefundById(MERCHANT, OWNER, REFUND);
        verify(mapper).selectResponses(MERCHANT, REFUND);
    }

    @Test
    void financeDetailExposesOnlyEvidenceDecision() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(financeAccess());
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Collections.emptyList());

        MerchantAftercareDetailVO out = service.detail(OPERATOR_PRINCIPAL, REFUND);

        assertTrue(out.isCanRespond());
        assertEquals(Collections.singletonList("EVIDENCE"), out.getAllowedDecisions());
    }

    @Test
    void managerCanRespondWithinExactStoreAndActorAuditRemainsOperator() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_DECIDE))
                .thenReturn(managerAccess());
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "manager-aftercare-1")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.insertResponse(any(MerchantAftercareResponse.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantAftercareResponse.class).setId(89L);
            return 1;
        });

        assertEquals(89L, service.respond(OPERATOR_PRINCIPAL, REFUND,
                request("AGREE", "门店同意平台继续审核", null, "manager-aftercare-1")).getId());

        ArgumentCaptor<MerchantAftercareResponse> saved = ArgumentCaptor.forClass(MerchantAftercareResponse.class);
        verify(mapper).insertResponse(saved.capture());
        assertEquals(MERCHANT, saved.getValue().getMerchantId());
        assertEquals(OWNER, saved.getValue().getMerchantOwnerMemberId());
        assertEquals(OPERATOR_PRINCIPAL, saved.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, saved.getValue().getActorRoleCode());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals(OPERATOR_PRINCIPAL, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, audit.getValue().getActorRoleCode());
    }

    @Test
    void managerStoreACannotReadOrRespondToStoreBUnderSameOwner() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(managerAccess());
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_DECIDE)).thenReturn(managerAccess());
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(null);
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "manager-cross-store-1")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(null);

        assertEquals(404, assertThrows(ServiceException.class,
                () -> service.detail(OPERATOR_PRINCIPAL, REFUND)).getCode());
        assertEquals(404, assertThrows(ServiceException.class,
                () -> service.respond(OPERATOR_PRINCIPAL, REFUND,
                        request("REJECT", "不属于当前门店", null, "manager-cross-store-1"))).getCode());

        verify(mapper, never()).selectOwnedRefundById(OTHER_MERCHANT, OWNER, REFUND);
        verify(mapper, never()).selectOwnedRefundByIdForUpdate(OTHER_MERCHANT, OWNER, REFUND);
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void financeCanAppendOneUploadedEvidenceKey() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_EVIDENCE))
                .thenReturn(financeAccess());
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "finance-evidence-1")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.insertResponse(any(MerchantAftercareResponse.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantAftercareResponse.class).setId(91L);
            return 1;
        });
        MerchantAftercareRespondRequest request = request(
                "EVIDENCE", "补充现场凭证", null, "finance-evidence-1");
        request.setEvidenceKeys(Collections.singletonList(EVIDENCE_KEY));

        MerchantAftercareResponseVO out = service.respond(OPERATOR_PRINCIPAL, REFUND, request);

        assertEquals(91L, out.getId());
        ArgumentCaptor<MerchantAftercareResponse> saved = ArgumentCaptor.forClass(MerchantAftercareResponse.class);
        verify(mapper).insertResponse(saved.capture());
        assertEquals(EVIDENCE_KEY, saved.getValue().getEvidenceUrl());
        assertEquals(SIGNED_EVIDENCE_URL, out.getEvidenceUrl());
        assertEquals(MerchantRoleCode.MERCHANT_FINANCE, saved.getValue().getActorRoleCode());
    }

    @Test
    void anyAuthorizedStaffCanSubmitEvidenceBoundToThisStoreAndRefundCheckedBeforeInsert() {
        // 9-15 拍板:不限上传者本人,同店有权限店员均可提交;校验门店+退款单绑定且 key 未被别的单使用。
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_EVIDENCE))
                .thenReturn(financeAccess());
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.insertResponse(any(MerchantAftercareResponse.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantAftercareResponse.class).setId(92L);
            return 1;
        });

        service.respond(OPERATOR_PRINCIPAL, REFUND, requestWithEvidenceKey("本店凭证", EVIDENCE_KEY, "bound-1"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(evidenceUrlSigner, mapper);
        order.verify(mapper).countEvidenceKeyUsedByOtherRefund(EVIDENCE_KEY, REFUND);
        order.verify(evidenceUrlSigner).requireBoundTo(EVIDENCE_KEY, MERCHANT, REFUND);
        order.verify(mapper).insertResponse(any(MerchantAftercareResponse.class));
    }

    @Test
    void evidenceBoundToAnotherStoreOrRefundIsRejectedAndNothingIsWritten() {
        // F16 复现 + 同店跨单:key 元数据不是本店本单 —— 必须拒绝。
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        org.mockito.Mockito.doThrow(new IllegalArgumentException("not bound"))
                .when(evidenceUrlSigner).requireBoundTo(EVIDENCE_KEY, MERCHANT, REFUND);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("外单凭证", EVIDENCE_KEY, "foreign-1")));

        assertEquals(400, error.getCode());
        verify(mapper, never()).insertResponse(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(evidenceUrlSigner, never()).sign(any());
    }

    @Test
    void evidenceKeyAlreadyUsedByAnotherRefundIsRejectedAndNothingIsWritten() {
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.countEvidenceKeyUsedByOtherRefund(EVIDENCE_KEY, REFUND)).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("复用凭证", EVIDENCE_KEY, "reused-1")));

        assertEquals(400, error.getCode());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void evidenceStorageUnavailableIsRecoverable503AndNothingIsWritten() {
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        org.mockito.Mockito.doThrow(new IllegalStateException("oss down"))
                .when(evidenceUrlSigner).requireBoundTo(EVIDENCE_KEY, MERCHANT, REFUND);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("凭证", EVIDENCE_KEY, "storage-down-1")));

        assertEquals(503, error.getCode());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void closedCaseIsRejectedBeforeEvidenceBindingLookup() {
        RefundApplication closed = pendingRefund();
        closed.setStatus(1);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(closed);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("结案后补图", EVIDENCE_KEY, "closed-1")));

        assertEquals(409, error.getCode());
        verify(evidenceUrlSigner, never()).requireBoundTo(any(), any(), any());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void detailMarksEvidenceUnavailableInsteadOfSilentlyHidingWhenBindingCheckFails() {
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Arrays.asList(
                response(1L, MerchantAftercareResponse.DECISION_EVIDENCE, "历史凭证", EVIDENCE_KEY, "legacy-1"),
                response(2L, MerchantAftercareResponse.DECISION_REJECT, "无凭证", null, "plain-1")));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("legacy or foreign"))
                .when(evidenceUrlSigner).requireBoundTo(EVIDENCE_KEY, MERCHANT, REFUND);

        MerchantAftercareDetailVO out = service.detail(PRINCIPAL, REFUND);

        assertEquals(null, out.getResponses().get(0).getEvidenceUrl());
        assertEquals("UNAVAILABLE", out.getResponses().get(0).getEvidenceStatus());
        assertEquals("NONE", out.getResponses().get(1).getEvidenceStatus());
        verify(evidenceUrlSigner, never()).sign(any());
    }

    @Test
    void detailSignsEvidenceBoundToTheResponseStoreAndRefund() {
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Collections.singletonList(
                response(1L, MerchantAftercareResponse.DECISION_EVIDENCE, "凭证", EVIDENCE_KEY, "ok-1")));

        MerchantAftercareDetailVO out = service.detail(PRINCIPAL, REFUND);

        verify(evidenceUrlSigner).requireBoundTo(EVIDENCE_KEY, MERCHANT, REFUND);
        assertEquals(SIGNED_EVIDENCE_URL, out.getResponses().get(0).getEvidenceUrl());
        assertEquals("AVAILABLE", out.getResponses().get(0).getEvidenceStatus());
    }

    @Test
    void exactReplayDoesNotRequireObjectToStillPassBindingButMarksUnverifiableUrlUnavailable() {
        MerchantAftercareResponse existing = response(
                88L, MerchantAftercareResponse.DECISION_EVIDENCE, "补图", EVIDENCE_KEY, "req-replay-gone");
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-replay-gone")).thenReturn(existing);
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        org.mockito.Mockito.doThrow(new IllegalStateException("oss down"))
                .when(evidenceUrlSigner).requireBoundTo(EVIDENCE_KEY, MERCHANT, REFUND);

        MerchantAftercareResponseVO replay = service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("补图", EVIDENCE_KEY, "req-replay-gone"));

        assertEquals(88L, replay.getId());
        assertEquals(null, replay.getEvidenceUrl());
        assertEquals("UNAVAILABLE", replay.getEvidenceStatus());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void evidenceUploadTargetIsTheStaffStoreForAnOpenRefundOfThatStore() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(financeAccess());
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());

        assertEquals(MERCHANT, service.requireEvidenceUploadTarget(OPERATOR_PRINCIPAL, REFUND));
    }

    @Test
    void evidenceUploadTargetRejectsForeignClosedOrNoRespondPermissionBeforeAnyUpload() {
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(null);
        assertEquals(404, assertThrows(ServiceException.class,
                () -> service.requireEvidenceUploadTarget(PRINCIPAL, REFUND)).getCode());

        RefundApplication closed = pendingRefund();
        closed.setStatus(2);
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(closed);
        assertEquals(409, assertThrows(ServiceException.class,
                () -> service.requireEvidenceUploadTarget(PRINCIPAL, REFUND)).getCode());

        MerchantAccessContext noAftercareRole = new MerchantAccessContext(OPERATOR_PRINCIPAL, MERCHANT, OWNER,
                MerchantRoleCode.MERCHANT_CHECKIN);
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_READ)).thenReturn(noAftercareRole);
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.requireEvidenceUploadTarget(OPERATOR_PRINCIPAL, REFUND)).getCode());

        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.requireEvidenceUploadTarget(PRINCIPAL, null)).getCode());
    }

    @Test
    void financeAgreeAndRejectAreForbiddenEvenIfBroadRespondPermissionIsPresent() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.AFTERCARE_DECIDE))
                .thenReturn(financeAccess());

        ServiceException agree = assertThrows(ServiceException.class,
                () -> service.respond(OPERATOR_PRINCIPAL, REFUND,
                        request("AGREE", "财务越权同意", null, "finance-agree-1")));
        ServiceException reject = assertThrows(ServiceException.class,
                () -> service.respond(OPERATOR_PRINCIPAL, REFUND,
                        request("REJECT", "财务越权驳回", null, "finance-reject-1")));

        assertEquals(403, agree.getCode());
        assertEquals(403, reject.getCode());
        verify(mapper, never()).lockActiveMerchantOwner(any(), any());
        verify(mapper, never()).insertResponse(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void evidenceKeysFailClosedWhenEmptyOrMoreThanExistingSingleEvidenceContract() {
        MerchantAftercareRespondRequest empty = request(
                "EVIDENCE", "空凭证", null, "evidence-empty-1");
        empty.setEvidenceKeys(Collections.<String>emptyList());
        MerchantAftercareRespondRequest multiple = request(
                "EVIDENCE", "多凭证", null, "evidence-multiple-1");
        multiple.setEvidenceKeys(Arrays.asList(
                EVIDENCE_KEY,
                OTHER_EVIDENCE_KEY));

        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.respond(PRINCIPAL, REFUND, empty)).getCode());
        assertEquals(400, assertThrows(ServiceException.class,
                () -> service.respond(PRINCIPAL, REFUND, multiple)).getCode());
        verify(mapper, never()).lockActiveMerchantOwner(any(), any());
    }

    @Test
    void evidenceKeysRejectOrdinaryUploadPrefixTraversalAndUppercaseVariants() {
        for (String key : Arrays.asList(
                "upload/0123456789abcdef0123456789abcdef.jpg",
                "upload/merchant-aftercare-evidence/../0123456789abcdef0123456789abcdef.jpg",
                "upload/MERCHANT-AFTERCARE-EVIDENCE/0123456789abcdef0123456789abcdef.jpg",
                "upload/merchant-aftercare-evidence/0123456789ABCDEF0123456789ABCDEF.jpg",
                "upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.JPG")) {
            MerchantAftercareRespondRequest request = request(
                    "EVIDENCE", "非法对象键", null, "invalid-key-1");
            request.setEvidenceKeys(Collections.singletonList(key));

            assertEquals(400, assertThrows(ServiceException.class,
                    () -> service.respond(PRINCIPAL, REFUND, request), key).getCode());
        }
        verify(mapper, never()).lockActiveMerchantOwner(any(), any());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void respondRejectsDirtyDecisionBeforeLockOrWrite() {
        MerchantAftercareRespondRequest request = request(" agree ", "同意", null, "req-dirty");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.respond(PRINCIPAL, REFUND, request));

        assertEquals(400, error.getCode());
        verify(mapper, never()).lockActiveMerchantOwner(any(), any());
        verify(mapper, never()).selectOwnedRefundByIdForUpdate(any(), any(), any());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void inactiveMerchantAtTransactionalLockFailsClosedBeforeIdempotencyLookup() {
        when(mapper.lockActiveMerchantOwner(MERCHANT, OWNER)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(
                PRINCIPAL, REFUND, request("AGREE", "同意平台审核", null, "req-inactive")));

        assertEquals(403, error.getCode());
        verify(mapper, never()).selectByRequestIdForUpdate(any(), any());
        verify(mapper, never()).selectOwnedRefundByIdForUpdate(any(), any(), any());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void respondFailsClosedForCrossMerchantRefund() {
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-cross-shop")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(
                PRINCIPAL, REFUND, request("REJECT", "并非本店订单", null, "req-cross-shop")));

        assertEquals(404, error.getCode());
        verify(access).require(PRINCIPAL, MerchantPermission.AFTERCARE_DECIDE);
        verify(mapper, never()).insertResponse(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void terminalRefundRejectsNewOpinionAndNeverChangesMoneyState() {
        RefundApplication refund = pendingRefund();
        refund.setStatus(1);
        refund.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-terminal")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(refund);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(
                PRINCIPAL, REFUND, request("AGREE", "同意退款", null, "req-terminal")));

        assertEquals(409, error.getCode());
        assertEquals(1, refund.getStatus());
        assertEquals(RefundApplication.PAYOUT_SUCCESS, refund.getPayoutStatus());
        verify(mapper, never()).insertResponse(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void agreeOnlyAppendsMerchantOpinionAndDoesNotMeanRefunded() {
        RefundApplication refund = pendingRefund();
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-agree")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(refund);
        when(mapper.insertResponse(any(MerchantAftercareResponse.class))).thenAnswer(call -> {
            call.getArgument(0, MerchantAftercareResponse.class).setId(88L);
            return 1;
        });

        MerchantAftercareResponseVO out = service.respond(
                PRINCIPAL, REFUND, request("AGREE", "同意平台继续审核", null, "req-agree"));

        verify(access).require(PRINCIPAL, MerchantPermission.AFTERCARE_DECIDE);
        verify(mapper).lockActiveMerchantOwner(MERCHANT, OWNER);
        assertEquals(88L, out.getId());
        assertEquals("AGREE", out.getMerchantOpinion());
        assertEquals("WAITING_PLATFORM_REVIEW", out.getProcessing());
        assertFalse(out.isRefunded(), "商家同意只是意见，不是平台审核通过，更不是钱已退");
        assertEquals(0, refund.getStatus());
        assertEquals(RefundApplication.PAYOUT_DISPATCH_PENDING, refund.getPayoutStatus());
        ArgumentCaptor<MerchantAftercareResponse> saved = ArgumentCaptor.forClass(MerchantAftercareResponse.class);
        verify(mapper).insertResponse(saved.capture());
        assertEquals(MERCHANT, saved.getValue().getMerchantId());
        assertEquals(OWNER, saved.getValue().getMerchantOwnerMemberId());
        assertEquals(PRINCIPAL, saved.getValue().getActorMemberId());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("MERCHANT_AFTERCARE_RESPONDED", audit.getValue().getAction());
        assertEquals(PRINCIPAL, audit.getValue().getActorMemberId());
        assertEquals(OWNER, audit.getValue().getOwnerMemberId());
    }

    @Test
    void exactIdempotentReplayReturnsSameServerRecordButChangedPayloadFailsClosed() {
        MerchantAftercareResponse existing = response(
                88L, MerchantAftercareResponse.DECISION_EVIDENCE, "补图", EVIDENCE_KEY, "req-replay");
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-replay")).thenReturn(existing);
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());

        MerchantAftercareResponseVO replay = service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("补图", EVIDENCE_KEY, "req-replay"));

        assertEquals(88L, replay.getId());
        verify(mapper, never()).insertResponse(any());
        verify(audits, never()).insertBusinessAuditEvent(any());

        ServiceException conflict = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                requestWithEvidenceKey("换一张图", OTHER_EVIDENCE_KEY, "req-replay")));
        assertEquals(409, conflict.getCode());
    }

    @Test
    void evidenceRequiresSafeReceiptUrl() {
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-bad-url")).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                request("EVIDENCE", "补图", "javascript:alert(1)", "req-bad-url")));

        assertEquals(400, error.getCode());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void legacyStoredUrlIsHiddenAndNeverPassedToSigner() {
        when(mapper.selectOwnedRefundById(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.selectResponses(MERCHANT, REFUND)).thenReturn(Collections.singletonList(
                response(93L, MerchantAftercareResponse.DECISION_EVIDENCE, "历史凭证",
                        "https://www.chengyinhub.com/upload/legacy.jpg?Signature=long-lived", "legacy-row")));

        MerchantAftercareDetailVO out = service.detail(PRINCIPAL, REFUND);

        assertEquals(null, out.getResponses().get(0).getEvidenceUrl());
        assertEquals("UNAVAILABLE", out.getResponses().get(0).getEvidenceStatus());
        verifyNoInteractions(evidenceUrlSigner);
    }

    @Test
    void legacyLongLivedEvidenceUrlIsRejectedEvenOnAllowedHost() {
        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(PRINCIPAL, REFUND,
                request("EVIDENCE", "补图",
                        "https://www.chengyinhub.com/upload/evidence.jpg?Expires=9999999999&Signature=long-lived",
                        "req-legacy-url")));

        assertEquals(400, error.getCode());
        verify(mapper, never()).lockActiveMerchantOwner(any(), any());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void longLivedUrlCannotBeSmuggledThroughEvidenceKeys() {
        MerchantAftercareRespondRequest request = request(
                "EVIDENCE", "补图", null, "req-url-as-key");
        request.setEvidenceKeys(Collections.singletonList(
                "https://www.chengyinhub.com/upload/evidence.jpg?Signature=long-lived"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.respond(PRINCIPAL, REFUND, request));

        assertEquals(400, error.getCode());
        verify(mapper, never()).lockActiveMerchantOwner(any(), any());
        verify(mapper, never()).insertResponse(any());
    }

    @Test
    void auditFailureFailsTheTransactionClosed() {
        when(mapper.selectByRequestIdForUpdate(MERCHANT, "req-audit")).thenReturn(null);
        when(mapper.selectOwnedRefundByIdForUpdate(MERCHANT, OWNER, REFUND)).thenReturn(pendingRefund());
        when(mapper.insertResponse(any())).thenAnswer(call -> {
            call.getArgument(0, MerchantAftercareResponse.class).setId(99L);
            return 1;
        });
        when(audits.insertBusinessAuditEvent(any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class, () -> service.respond(
                PRINCIPAL, REFUND, request("REJECT", "不同意退款", null, "req-audit")));

        assertEquals(409, error.getCode());
    }

    private MerchantAccessContext managerAccess() {
        return new MerchantAccessContext(OPERATOR_PRINCIPAL, MERCHANT, OWNER,
                MerchantRoleCode.MERCHANT_MANAGER);
    }

    private MerchantAccessContext financeAccess() {
        return new MerchantAccessContext(OPERATOR_PRINCIPAL, MERCHANT, OWNER,
                MerchantRoleCode.MERCHANT_FINANCE);
    }

    private MerchantAccessContext ownerAccess() {
        return new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }

    private RefundApplication pendingRefund() {
        RefundApplication refund = new RefundApplication();
        refund.setId(REFUND);
        refund.setMerchantId(OWNER);
        refund.setRefundNo("RF-41");
        refund.setSourceType("registration");
        refund.setSourceId(501L);
        refund.setRefundAmount(new BigDecimal("25.00"));
        refund.setStatus(0);
        refund.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        refund.setDelFlag(0);
        return refund;
    }

    private MerchantAftercareResponse response(Long id, String decision, String content,
                                                String evidenceUrl, String requestId) {
        MerchantAftercareResponse response = new MerchantAftercareResponse();
        response.setId(id);
        response.setRefundApplicationId(REFUND);
        response.setMerchantId(MERCHANT);
        response.setMerchantOwnerMemberId(OWNER);
        response.setActorMemberId(PRINCIPAL);
        response.setActorRoleCode(MerchantRoleCode.MERCHANT_OWNER);
        response.setDecision(decision);
        response.setContent(content);
        response.setEvidenceUrl(evidenceUrl);
        response.setRequestId(requestId);
        response.setCreateTime(new Date());
        return response;
    }

    private MerchantAftercareListRow listRow(Long id, Integer status, Integer payoutStatus,
                                               String opinion) {
        MerchantAftercareListRow row = new MerchantAftercareListRow();
        row.setId(id);
        row.setRefundNo("RF-" + id);
        row.setRefundAmount(new BigDecimal("25.00"));
        row.setStatus(status);
        row.setPayoutStatus(payoutStatus);
        row.setMerchantOpinion(opinion);
        row.setCreateTime(new Date());
        return row;
    }

    private MerchantAftercareRespondRequest request(String decision, String content,
                                                     String evidenceUrl, String requestId) {
        MerchantAftercareRespondRequest request = new MerchantAftercareRespondRequest();
        request.setDecision(decision);
        request.setContent(content);
        request.setEvidenceUrl(evidenceUrl);
        request.setRequestId(requestId);
        return request;
    }

    private MerchantAftercareRespondRequest requestWithEvidenceKey(String content, String evidenceKey,
                                                                    String requestId) {
        MerchantAftercareRespondRequest request = request("EVIDENCE", content, null, requestId);
        request.setEvidenceKeys(Collections.singletonList(evidenceKey));
        return request;
    }
}
