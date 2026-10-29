package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.ClubMembershipPlan;
import com.chengyinhub.business.domain.ClubMembershipSetting;
import com.chengyinhub.business.domain.ClubMembershipTerm;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ClubMembershipActionRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipPlanRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipRenewRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipSettingRequest;
import com.chengyinhub.business.domain.vo.ClubMembershipRenewalVO;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;
import com.chengyinhub.business.domain.vo.ClubMembershipOverviewVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubMembershipMapper;
import com.chengyinhub.business.service.impl.ClubMembershipFeatureGate;
import com.chengyinhub.business.service.impl.ClubMembershipServiceImpl;
import com.chengyinhub.business.service.impl.ClubMembershipSnapshotCodec;
import com.chengyinhub.business.service.impl.WechatPayServiceImpl;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubMembershipServiceImplTest {
    private static final long CLUB = 7L;
    private static final long OWNER = 70L;
    private static final long MEMBER = 71L;
    private ClubMembershipFeatureGate feature;
    private IClubAccessService access;
    private ClubMembershipMapper mapper;
    private ClubMapper clubs;
    private ClubMemberMapper clubMembers;
    private IUmsMemberService members;
    private IWechatPayService pay;
    private BusinessAuditEventMapper audits;
    private PlatformTransactionManager transactions;
    private TransactionStatus transactionStatus;
    private ClubMembershipServiceImpl service;
    private VerifiedPaymentProofFixtures proof;

    @BeforeEach
    void setUp() {
        feature = mock(ClubMembershipFeatureGate.class);
        access = mock(IClubAccessService.class);
        mapper = mock(ClubMembershipMapper.class);
        clubs = mock(ClubMapper.class);
        clubMembers = mock(ClubMemberMapper.class);
        members = mock(IUmsMemberService.class);
        pay = mock(IWechatPayService.class);
        when(pay.lockClosablePaymentAttemptForUpdate(anyString(), anyString())).thenReturn(true);
        when(pay.lockPaymentAttemptForUpdate(anyString(), anyString())).thenReturn(true);
        audits = mock(BusinessAuditEventMapper.class);
        transactions = mock(PlatformTransactionManager.class);
        transactionStatus = mock(TransactionStatus.class);
        service = new ClubMembershipServiceImpl();
        proof = new VerifiedPaymentProofFixtures();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"verifiedPaymentFacts",proof.reader);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "featureGate", feature);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "clubAccessService", access);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "clubMembershipMapper", mapper);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "clubMapper", clubs);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "clubMemberMapper", clubMembers);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "memberService", members);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "wechatPayService", pay);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "businessAuditEventMapper", audits);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "transactionManager", transactions);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "clock", Clock.fixed(
                Instant.parse("2026-08-23T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
        when(access.resolve(MEMBER, CLUB, null)).thenReturn(memberAccess());
        when(access.require(OWNER, CLUB, null, ClubPermission.CLUB_WRITE)).thenReturn(ownerAccess());
        when(transactions.getTransaction(any(org.springframework.transaction.TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub());
        ClubMember activeMember = new ClubMember();
        activeMember.setId(701L);
        activeMember.setClubId(CLUB);
        activeMember.setMemberId(MEMBER);
        activeMember.setStatus(1);
        activeMember.setDelFlag(0);
        when(clubMembers.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenReturn(activeMember);
        when(mapper.lockMemberForRenewal(MEMBER)).thenReturn(MEMBER);
        when(mapper.lockMemberForRenewal(OWNER)).thenReturn(OWNER);
        when(mapper.selectInvoiceByIdForUpdate(any())).thenAnswer(call ->
                mapper.selectInvoiceById(call.getArgument(0, Long.class)));
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(mapper.bindInvoicePaymentProof(any(),anyString(),any(),anyString(),any(),anyString())).thenAnswer(call -> {
            ClubMembershipInvoice invoice=mapper.selectInvoiceByOutTradeNoForUpdate("CLM01234567890123456789012345678");
            if(invoice==null)throw new AssertionError("missing original invoice fixture");
            invoice.setStatus(ClubMembershipInvoice.STATUS_PAID);invoice.setTransactionId(call.getArgument(1));
            invoice.setVerifiedPaymentFactId(call.getArgument(4));invoice.setVerifiedPaymentFactHash(call.getArgument(5));return 1;
        });
    }

    @Test
    void everyPublicReadFailsClosedWhenGlobalFlagIsOff() {
        org.mockito.Mockito.doThrow(new ServiceException("未开放", HttpStatus.NOT_FOUND))
                .when(feature).requireEnabled();

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.overview(MEMBER, CLUB));

        assertEquals(HttpStatus.NOT_FOUND, error.getCode());
        verify(access, never()).resolve(any(), any(), any());
        verify(mapper, never()).selectSetting(any());
    }

    @Test
    void flagOffResumeStillReconcilesWechatButNeverCreatesNewPrepay() {
        flagOff();
        ClubMembershipInvoice invoice = resumableInvoiceWithoutParams();
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "NOTPAY"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, action(invoice.getId(), "resume-flag-off")));

        assertEquals(HttpStatus.NOT_FOUND, error.getCode());
        InOrder order = inOrder(pay, feature);
        order.verify(pay).queryOrderStatus(invoice.getOutTradeNo());
        order.verify(feature).requireEnabled();
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
        verify(mapper, never()).attachPayParamsCas(any(), anyInt(), any(), any());
    }

    @Test
    void flagOffResumeStillAppliesWechatClosedReconcile() {
        flagOff();
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "CLOSED"));
        when(mapper.markInvoiceClosedCas(eq(invoice.getId()), eq(1), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-flag-off-closed"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        verify(mapper).markInvoiceClosedCas(eq(invoice.getId()), eq(1), any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void oldClosedObservationCannotCloseNewCurrentInvoiceAttempt() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        Map<String, String> observed = wxOrder(invoice, "CLOSED");
        observed.put("wx_out_trade_no", "old-wx-attempt");
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(observed);
        when(pay.lockClosablePaymentAttemptForUpdate(invoice.getOutTradeNo(), "old-wx-attempt")).thenReturn(false);
        assertThrows(ServiceException.class, () -> service.resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-stale-attempt")));
        verify(mapper, never()).markInvoiceClosedCas(any(), anyInt(), any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void payErrorOrRevokedCannotReleaseOriginalInvoiceWithoutClosedProof() {
        for (String state : new String[]{"PAYERROR", "REVOKED"}) {
            ClubMembershipInvoice invoice = validPayingInvoice();
            invoice.setVersion(1);
            when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
            when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, state));
            org.mockito.Mockito.doThrow(new RuntimeException("close unknown")).when(pay).closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo());
            assertThrows(ServiceException.class,
                    () -> service.closeInvoice(MEMBER, action(invoice.getId(), "close-" + state)));
            verify(mapper, never()).markInvoiceClosedCas(any(), anyInt(), any());
            verify(pay).queryOrderStatus(invoice.getOutTradeNo());
            verify(pay).closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo());
            org.mockito.Mockito.clearInvocations(pay, mapper);
        }
    }

    @Test
    void flagOnResumeChecksGateAfterReconcileThenCreatesPrepay() {
        ClubMembershipInvoice invoice = resumableInvoiceWithoutParams();
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "NOTPAY"));
        when(pay.createGenericOrder(eq(invoice.getOutTradeNo()), any(), any(), eq("openid-71")))
                .thenReturn(payParams("prepay_id=flag-on"));
        when(mapper.attachPayParamsCas(eq(invoice.getId()), eq(1), any(), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-flag-on"));

        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        InOrder order = inOrder(pay, feature);
        order.verify(pay).queryOrderStatus(invoice.getOutTradeNo());
        order.verify(feature).requireEnabled();
        order.verify(pay).createGenericOrder(eq(invoice.getOutTradeNo()), any(), any(), eq("openid-71"));
    }

    @Test
    void flagOffCloseInvoiceStillClosesExistingPayingInvoice() {
        flagOff();
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(2);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo()))
                .thenReturn(wxOrder(invoice, "NOTPAY"), wxOrder(invoice, "CLOSED"));
        when(pay.closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo())).thenReturn(true);
        when(mapper.markInvoiceClosedCas(eq(invoice.getId()), eq(2), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.closeInvoice(
                MEMBER, action(invoice.getId(), "close-flag-off"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        verify(pay).closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void flagOffCloseInvoiceStillRejectsAnotherMembersInvoice() {
        flagOff();
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setMemberId(72L);
        invoice.setSnapshotHash(ClubMembershipSnapshotCodec.hash(invoice));
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.closeInvoice(MEMBER, action(invoice.getId(), "close-flag-off-idor")));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(pay, never()).queryOrderStatus(any());
        verify(pay, never()).closePaymentAttempt(any(), any());
    }

    @Test
    void invoiceQueryWorksWithFlagOffAndOn() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);

        assertEquals(invoice.getId(), service.invoice(MEMBER, CLUB, invoice.getId()).getId());
        flagOff();
        ClubMembershipInvoice offRead = service.invoice(MEMBER, CLUB, invoice.getId());

        assertEquals(invoice.getId(), offRead.getId());
        assertEquals(null, offRead.getIdempotencyKey());
        verify(access, times(2)).resolve(MEMBER, CLUB, null);
    }

    @Test
    void flagOffInvoiceQueryStillRejectsCrossMemberRows() {
        flagOff();
        ClubMembershipInvoice crossMember = validPayingInvoice();
        crossMember.setMemberId(72L);
        when(mapper.selectInvoiceById(100L)).thenReturn(crossMember);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.invoice(MEMBER, CLUB, 100L));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
    }

    @Test
    void financeListWorksWithFlagOffAndOnButStillRequiresFinanceRead() {
        when(access.require(OWNER, CLUB, null, ClubPermission.FINANCE_READ)).thenReturn(ownerAccess());
        when(mapper.selectInvoicesForFinance(CLUB))
                .thenAnswer(call -> Collections.singletonList(validPayingInvoice()));

        assertEquals(1, service.financeInvoices(OWNER, CLUB).size());
        flagOff();
        java.util.List<ClubMembershipInvoice> offRows = service.financeInvoices(OWNER, CLUB);

        assertEquals(1, offRows.size());
        assertEquals(null, offRows.get(0).getPayParamsJson());
        verify(access, times(2)).require(OWNER, CLUB, null, ClubPermission.FINANCE_READ);

        when(access.require(MEMBER, CLUB, null, ClubPermission.FINANCE_READ))
                .thenThrow(new ServiceException("无权限", HttpStatus.FORBIDDEN));
        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.financeInvoices(MEMBER, CLUB));
        assertEquals(HttpStatus.FORBIDDEN, denied.getCode());
    }

    @Test
    void flagOffStillBlocksRenewAndOtherWriteEntries() {
        flagOff();

        ServiceException renewError = assertThrows(ServiceException.class,
                () -> service.renew(MEMBER, renew(9L, "idem-flag-off")));
        ServiceException cancelError = assertThrows(ServiceException.class,
                () -> service.cancelTerm(MEMBER, action(500L, "cancel-term-flag-off")));
        ServiceException planError = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, planRequest(CLUB)));

        assertEquals(HttpStatus.NOT_FOUND, renewError.getCode());
        assertEquals(HttpStatus.NOT_FOUND, cancelError.getCode());
        assertEquals(HttpStatus.NOT_FOUND, planError.getCode());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
        verify(mapper, never()).insertInvoice(any());
    }

    @Test
    void missingClubSettingKeepsNormalMemberFreeAndNeverExposesPaidPlans() {
        when(mapper.selectSetting(CLUB)).thenReturn(null);

        ClubMembershipOverviewVO result = service.overview(MEMBER, CLUB);

        assertEquals("FREE", result.getMembershipType());
        assertEquals("FREE", result.getEffectiveStatus());
        assertFalse(result.isClubMembershipEnabled());
        assertFalse(result.isHasEntitlement());
        assertTrue(result.getPlans().isEmpty());
        verify(mapper, never()).selectPlans(eq(CLUB), eq(true));
    }

    @Test
    void renewalRejectsSameIdempotencyKeyReusedForDifferentPlan() {
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan(9L, CLUB, 1, 9900L));
        ClubMembershipInvoice existing = new ClubMembershipInvoice();
        existing.setId(100L);
        existing.setClubId(CLUB);
        existing.setMemberId(MEMBER);
        existing.setPlanId(10L);
        existing.setRequestFingerprint("different-request");
        when(mapper.selectInvoiceByIdempotency(CLUB, MEMBER, "idem-1")).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.renew(MEMBER, renew(9L, "idem-1")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void renewalRevalidatesActiveMembershipInsideIntentTransaction() {
        when(clubMembers.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.renew(MEMBER, renew(9L, "idem-after-exit")));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).insertInvoice(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
        verify(transactions).rollback(transactionStatus);
    }

    @Test
    void sameIdempotencyKeyReplaysFrozenInvoiceAfterPlanVersionChanges() {
        ClubMembershipInvoice existing = validPayingInvoice();
        existing.setIdempotencyKey("idem-plan-updated");
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan(9L, CLUB, 2, 19900L));
        when(mapper.selectInvoiceByIdempotency(CLUB, MEMBER, "idem-plan-updated")).thenReturn(existing);

        ClubMembershipRenewalVO result = service.renew(MEMBER, renew(9L, "idem-plan-updated"));

        assertEquals(existing.getId(), result.getInvoice().getId());
        assertEquals(9900L, result.getInvoice().getAmountFen());
        verify(mapper, never()).selectActivePayingInvoiceForUpdate(any(), any());
        verify(mapper, never()).insertInvoice(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void crossClubOwnerCannotSavePlanOrReachMapper() {
        ClubMembershipPlanRequest request = planRequest(8L);
        when(access.require(OWNER, 8L, null, ClubPermission.CLUB_WRITE))
                .thenThrow(new ServiceException("权限不足", HttpStatus.FORBIDDEN));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, request));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).insertPlan(any());
        verify(mapper, never()).updatePlanCas(any());
    }

    @Test
    void coOwnerCannotChangeMembershipPricePolicyOrEnablement() {
        long coOwner = 72L;
        ClubAccessContext coOwnerAccess = new ClubAccessContext(coOwner, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_CO_OWNER));
        when(access.require(coOwner, CLUB, null, ClubPermission.CLUB_WRITE)).thenReturn(coOwnerAccess);
        ClubMembershipSettingRequest setting = new ClubMembershipSettingRequest();
        setting.setClubId(CLUB);

        ServiceException settingError = assertThrows(ServiceException.class,
                () -> service.updateSetting(coOwner, setting));
        ServiceException planError = assertThrows(ServiceException.class,
                () -> service.savePlan(coOwner, planRequest(CLUB)));

        assertEquals(HttpStatus.FORBIDDEN, settingError.getCode());
        assertEquals(HttpStatus.FORBIDDEN, planError.getCode());
        verify(mapper, never()).insertSetting(any());
        verify(mapper, never()).updateSettingCas(any(), anyBoolean(), anyInt(), any(), any());
        verify(mapper, never()).insertPlan(any());
        verify(mapper, never()).updatePlanCas(any());
    }

    @Test
    void transferredOwnerCannotEnableMembershipAfterPreLockAccessWasResolved() {
        Club transferred = activeClub();
        transferred.setMemberId(99L);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(transferred);
        when(mapper.insertSetting(any())).thenReturn(1);
        ClubMembershipSettingRequest request = new ClubMembershipSettingRequest();
        request.setClubId(CLUB);
        request.setEnabled(true);
        request.setVersion(0);
        request.setRequestId("setting-after-owner-transfer");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateSetting(OWNER, request));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).selectSetting(any());
        verify(mapper, never()).insertSetting(any());
        verify(mapper, never()).updateSettingCas(any(), anyBoolean(), anyInt(), any(), any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void transferredOwnerCannotCreateMembershipPlanAfterPreLockAccessWasResolved() {
        Club transferred = activeClub();
        transferred.setMemberId(99L);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(transferred);
        when(mapper.insertPlan(any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, planRequest(CLUB)));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).insertPlan(any());
        verify(mapper, never()).updatePlanCas(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void currentOwnerLocksClubBeforeCreatingMembershipPlan() {
        when(mapper.insertPlan(any())).thenAnswer(call -> {
            call.getArgument(0, ClubMembershipPlan.class).setId(9L);
            return 1;
        });

        ClubMembershipPlan result = service.savePlan(OWNER, planRequest(CLUB));

        assertEquals(Long.valueOf(9L), result.getId());
        InOrder order = inOrder(clubs, mapper);
        order.verify(clubs).selectClubByIdForUpdate(CLUB);
        order.verify(mapper).insertPlan(any());
    }

    @Test
    void createPlanLostResponseReplaysOriginalPlanFromExactAuditReceipt() {
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        AtomicReference<ClubMembershipPlan> stored = new AtomicReference<>();
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PLAN_SAVED", "plan-cross-club"))
                .thenAnswer(call -> receipt.get());
        when(mapper.insertPlan(any())).thenAnswer(call -> {
            ClubMembershipPlan inserted = call.getArgument(0, ClubMembershipPlan.class);
            inserted.setId(9L);
            stored.set(inserted);
            return 1;
        });
        when(mapper.selectPlanByIdForUpdate(9L)).thenAnswer(call -> stored.get());
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        ClubMembershipPlanRequest request = planRequest(CLUB);

        ClubMembershipPlan first = service.savePlan(OWNER, request);
        ClubMembershipPlanRequest normalizedEquivalent = planRequest(CLUB);
        normalizedEquivalent.setName(" 年度会员 ");
        normalizedEquivalent.setBenefits(Arrays.asList(" 优先报名 ", "会员活动"));
        ClubMembershipPlan replay = service.savePlan(OWNER, normalizedEquivalent);

        assertEquals(Long.valueOf(9L), first.getId());
        assertEquals(first.getId(), replay.getId());
        assertEquals(first.getPriceFen(), replay.getPriceFen());
        assertEquals(Long.valueOf(9L), receipt.get().getRelationId(),
                "不可变回执必须保存首次创建的 planId");
        assertEquals(Long.valueOf(OWNER), receipt.get().getActorMemberId());
        assertTrue(receipt.get().getAfterJson().matches(
                "\\{\\\"fingerprint\\\":\\\"[0-9a-f]{64}\\\"}"));
        verify(mapper, times(1)).insertPlan(any());
        verify(audits, times(1)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void createPlanRejectsSameRequestIdWithDifferentNormalizedPayload() {
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        AtomicReference<ClubMembershipPlan> stored = new AtomicReference<>();
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PLAN_SAVED", "plan-cross-club"))
                .thenAnswer(call -> receipt.get());
        when(mapper.insertPlan(any())).thenAnswer(call -> {
            ClubMembershipPlan inserted = call.getArgument(0, ClubMembershipPlan.class);
            inserted.setId(9L);
            stored.set(inserted);
            return 1;
        });
        when(mapper.selectPlanByIdForUpdate(9L)).thenAnswer(call -> stored.get());
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        ClubMembershipPlanRequest first = planRequest(CLUB);
        service.savePlan(OWNER, first);
        ClubMembershipPlanRequest changed = planRequest(CLUB);
        changed.setBenefits(Arrays.asList("优先报名", "另一项权益"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, changed));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, times(1)).insertPlan(any());
        verify(audits, times(1)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void updatePlanLostResponseReplaysCurrentPlanFromExactAuditReceipt() {
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        AtomicReference<ClubMembershipPlan> stored = new AtomicReference<>(plan(9L, CLUB, 0, 39900L));
        stored.get().setCreatedByMemberId(OWNER);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PLAN_SAVED", "plan-update-retry"))
                .thenAnswer(call -> receipt.get());
        when(mapper.selectPlanByIdForUpdate(9L)).thenAnswer(call -> stored.get());
        when(mapper.updatePlanCas(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0, ClubMembershipPlan.class));
            return 1;
        });
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        ClubMembershipPlanRequest request = planRequest(CLUB);
        request.setId(9L);
        request.setVersion(0);
        request.setRequestId("plan-update-retry");

        ClubMembershipPlan first = service.savePlan(OWNER, request);
        ClubMembershipPlan replay = service.savePlan(OWNER, request);

        assertEquals(Integer.valueOf(1), first.getVersion());
        assertEquals(first.getId(), replay.getId());
        assertEquals(Integer.valueOf(1), replay.getVersion());
        assertEquals(Long.valueOf(9L), receipt.get().getRelationId());
        assertTrue(receipt.get().getAfterJson().matches(
                "\\{\\\"fingerprint\\\":\\\"[0-9a-f]{64}\\\",\\\"expectedVersion\\\":0}"));
        verify(mapper, times(1)).updatePlanCas(any());
        verify(audits, times(1)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void updatePlanRejectsSameRequestIdWithDifferentFullPayload() {
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        AtomicReference<ClubMembershipPlan> stored = new AtomicReference<>(plan(9L, CLUB, 0, 39900L));
        stored.get().setCreatedByMemberId(OWNER);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PLAN_SAVED", "plan-update-conflict"))
                .thenAnswer(call -> receipt.get());
        when(mapper.selectPlanByIdForUpdate(9L)).thenAnswer(call -> stored.get());
        when(mapper.updatePlanCas(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0, ClubMembershipPlan.class));
            return 1;
        });
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        ClubMembershipPlanRequest first = planRequest(CLUB);
        first.setId(9L);
        first.setVersion(0);
        first.setRequestId("plan-update-conflict");
        service.savePlan(OWNER, first);
        ClubMembershipPlanRequest changed = planRequest(CLUB);
        changed.setId(9L);
        changed.setVersion(0);
        changed.setRequestId("plan-update-conflict");
        changed.setPastDueDays(45);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, changed));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, times(1)).updatePlanCas(any());
        verify(audits, times(1)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
    }

    @Test
    void updatePlanReplayRejectsAPlanChangedAgainAfterOriginalOutcome() {
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        AtomicReference<ClubMembershipPlan> stored = new AtomicReference<>(plan(9L, CLUB, 0, 39900L));
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PLAN_SAVED", "plan-update-later-version"))
                .thenAnswer(call -> receipt.get());
        when(mapper.selectPlanByIdForUpdate(9L)).thenAnswer(call -> stored.get());
        when(mapper.updatePlanCas(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0, ClubMembershipPlan.class));
            return 1;
        });
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        ClubMembershipPlanRequest request = planRequest(CLUB);
        request.setId(9L);
        request.setVersion(0);
        request.setRequestId("plan-update-later-version");
        service.savePlan(OWNER, request);
        stored.get().setVersion(2);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, request));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, times(1)).updatePlanCas(any());
    }

    @Test
    void updatePlanReplayRejectsPayloadDriftEvenAtExpectedOutcomeVersion() {
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        AtomicReference<ClubMembershipPlan> stored = new AtomicReference<>(plan(9L, CLUB, 0, 39900L));
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PLAN_SAVED", "plan-update-payload-drift"))
                .thenAnswer(call -> receipt.get());
        when(mapper.selectPlanByIdForUpdate(9L)).thenAnswer(call -> stored.get());
        when(mapper.updatePlanCas(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0, ClubMembershipPlan.class));
            return 1;
        });
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        ClubMembershipPlanRequest request = planRequest(CLUB);
        request.setId(9L);
        request.setVersion(0);
        request.setRequestId("plan-update-payload-drift");
        service.savePlan(OWNER, request);
        stored.get().setPriceFen(49900L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.savePlan(OWNER, request));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, times(1)).updatePlanCas(any());
    }

    @Test
    void membershipPlanWriteUsesMemberThenClubThenFreshAccessLockOrder() {
        when(mapper.insertPlan(any())).thenAnswer(call -> {
            call.getArgument(0, ClubMembershipPlan.class).setId(9L);
            return 1;
        });

        service.savePlan(OWNER, planRequest(CLUB));

        InOrder lockOrder = inOrder(mapper, clubs, access);
        lockOrder.verify(mapper).lockMemberForRenewal(OWNER);
        lockOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.CLUB_WRITE);
    }

    @Test
    void membershipSettingWriteUsesMemberThenClubThenFreshAccessLockOrder() {
        ClubMembershipSetting current = enabledSetting();
        current.setVersion(1);
        when(mapper.selectSetting(CLUB)).thenReturn(current);
        when(mapper.updateSettingCas(eq(CLUB), eq(false), eq(1), eq(String.valueOf(OWNER)), any()))
                .thenReturn(1);
        ClubMembershipSettingRequest request = new ClubMembershipSettingRequest();
        request.setClubId(CLUB);
        request.setEnabled(false);
        request.setVersion(1);
        request.setRequestId("setting-lock-order");

        service.updateSetting(OWNER, request);

        InOrder lockOrder = inOrder(mapper, clubs, access);
        lockOrder.verify(mapper).lockMemberForRenewal(OWNER);
        lockOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        lockOrder.verify(access).require(OWNER, CLUB, null, ClubPermission.CLUB_WRITE);
    }

    @Test
    void ownInvoiceReadRejectsCrossClubAndCrossMemberRows() {
        ClubMembershipInvoice crossClub = validPayingInvoice();
        crossClub.setClubId(8L);
        ClubMembershipInvoice crossMember = validPayingInvoice();
        crossMember.setMemberId(72L);
        when(mapper.selectInvoiceById(100L)).thenReturn(crossClub, crossMember);

        ServiceException clubError = assertThrows(ServiceException.class,
                () -> service.invoice(MEMBER, CLUB, 100L));
        ServiceException memberError = assertThrows(ServiceException.class,
                () -> service.invoice(MEMBER, CLUB, 100L));

        assertEquals(HttpStatus.FORBIDDEN, clubError.getCode());
        assertEquals(HttpStatus.FORBIDDEN, memberError.getCode());
    }

    @Test
    void renewalCreatesDedicatedRecoverableInvoiceAndCompletePaymentParams() {
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan(9L, CLUB, 1, 9900L));
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        AtomicReference<ClubMembershipInvoice> stored = new AtomicReference<>();
        when(mapper.selectInvoiceByIdempotency(CLUB, MEMBER, "idem-2"))
                .thenAnswer(call -> stored.get());
        when(mapper.selectInvoiceByOutTradeNoForUpdate(any())).thenAnswer(call -> stored.get());
        when(mapper.insertInvoice(any())).thenAnswer(call -> {
            ClubMembershipInvoice inserted = call.getArgument(0, ClubMembershipInvoice.class);
            inserted.setId(100L);
            stored.set(inserted);
            return 1;
        });
        when(mapper.markInvoicePayingCas(100L, 0, java.util.Date.from(Instant.parse("2026-08-23T16:00:00Z"))))
                .thenReturn(1);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("timeStamp", "1");
        payParams.put("nonceStr", "n");
        payParams.put("package", "prepay_id=x");
        payParams.put("signType", "RSA");
        payParams.put("paySign", "s");
        when(pay.createGenericOrder(any(), eq(new java.math.BigDecimal("99.00")), any(), eq("openid-71")))
                .thenReturn(payParams);
        when(mapper.attachPayParamsCas(eq(100L), eq(1), any(), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.renew(MEMBER, renew(9L, "idem-2"));

        assertTrue(result.getInvoice().getOutTradeNo().startsWith("CLM"));
        assertTrue(result.getInvoice().getOutTradeNo().length() <= 32);
        assertEquals(payParams, result.getPayParams());
        assertEquals("PAYING", result.getPaymentState());
        assertTrue(result.isRecoverable());
        ArgumentCaptor<ClubMembershipInvoice> invoice = ArgumentCaptor.forClass(ClubMembershipInvoice.class);
        verify(mapper).insertInvoice(invoice.capture());
        assertEquals(OWNER, invoice.getValue().getOwnerMemberIdSnapshot());
        assertEquals(9900L, invoice.getValue().getAmountFen());
        assertNotNull(invoice.getValue().getSnapshotHash());
        InOrder commitBeforeWechat = inOrder(transactions, pay);
        commitBeforeWechat.verify(transactions).commit(transactionStatus);
        commitBeforeWechat.verify(pay).createGenericOrder(any(), any(), any(), any());
        InOrder frozenPlanOrder = inOrder(clubs, mapper);
        frozenPlanOrder.verify(clubs).selectClubByIdForUpdate(CLUB);
        frozenPlanOrder.verify(mapper).selectPlanByIdForUpdate(9L);
        frozenPlanOrder.verify(mapper).insertInvoice(any());

        ClubMembershipRenewalVO replay = service.renew(MEMBER, renew(9L, "idem-2"));
        assertEquals(100L, replay.getInvoice().getId());
        assertEquals(payParams, replay.getPayParams());
        verify(pay, times(1)).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void differentIdempotencyKeyReusesSamePlanPayingInvoiceUnderMemberLock() {
        ClubMembershipPlan plan = plan(9L, CLUB, 1, 9900L);
        ClubMembershipInvoice paying = validPayingInvoice();
        paying.setIdempotencyKey("idem-first");
        paying.setRequestFingerprint(CanonicalDigest.sha256Hex(CanonicalDigest.join(
                String.valueOf(CLUB), String.valueOf(MEMBER), String.valueOf(plan.getId()),
                String.valueOf(plan.getVersion()), String.valueOf(plan.getPriceFen()),
                String.valueOf(plan.getDurationDays()), plan.getBenefitsJson(),
                String.valueOf(plan.getGraceDays()), String.valueOf(plan.getPastDueDays()),
                plan.getExitPolicy(), plan.getRefundPolicy())));
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan);
        when(mapper.selectInvoiceByIdempotency(CLUB, MEMBER, "idem-second")).thenReturn(null);
        when(mapper.selectActivePayingInvoiceForUpdate(CLUB, MEMBER)).thenReturn(paying);

        ClubMembershipRenewalVO result = service.renew(MEMBER, renew(9L, "idem-second"));

        assertEquals(paying.getId(), result.getInvoice().getId());
        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        verify(mapper).lockMemberForRenewal(MEMBER);
        verify(mapper, never()).insertInvoice(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void renewalUsesCommittedShortIntentTransactionWithoutSettingRowLock() {
        ClubMembershipInvoice existing = validPayingInvoice();
        existing.setIdempotencyKey("idem-member-lock");
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectInvoiceByIdempotency(CLUB, MEMBER, "idem-member-lock")).thenReturn(existing);

        service.renew(MEMBER, renew(9L, "idem-member-lock"));

        verify(mapper).lockMemberForRenewal(MEMBER);
        verify(clubs).selectClubByIdForUpdate(CLUB);
        verify(transactions).commit(transactionStatus);
    }

    @Test
    void renewalRejectsAmbientTransactionWithoutSuspendingCallerLocks() {
        TrackingTransactionManager tracker = useTrackingTransactions();
        new TransactionTemplate(tracker).execute(status -> {
            assertTrue(tracker.isTransactionActive());
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    () -> transactionalService(tracker).renew(MEMBER, renew(9L, "ambient-renew")));
            assertTrue(tracker.isTransactionActive(), "rejected call must not suspend or end the caller transaction");
            return null;
        });
        assertFalse(tracker.isTransactionActive());
        org.mockito.Mockito.verifyNoInteractions(feature, access, mapper, clubs, clubMembers, members, pay, audits);
    }

    @Test
    void differentPlanCannotCreateSecondInvoiceWhileAnotherPlanIsPaying() {
        ClubMembershipInvoice paying = validPayingInvoice();
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(10L)).thenReturn(plan(10L, CLUB, 1, 19900L));
        when(mapper.selectActivePayingInvoiceForUpdate(CLUB, MEMBER)).thenReturn(paying);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.renew(MEMBER, renew(10L, "idem-other-plan")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, never()).insertInvoice(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void activePayingInvoiceWithTamperedSnapshotFailsClosedInsteadOfBeingReused() {
        ClubMembershipPlan plan = plan(9L, CLUB, 1, 9900L);
        ClubMembershipInvoice paying = validPayingInvoice();
        paying.setRequestFingerprint(CanonicalDigest.sha256Hex(CanonicalDigest.join(
                String.valueOf(CLUB), String.valueOf(MEMBER), String.valueOf(plan.getId()),
                String.valueOf(plan.getVersion()), String.valueOf(plan.getPriceFen()),
                String.valueOf(plan.getDurationDays()), plan.getBenefitsJson(),
                String.valueOf(plan.getGraceDays()), String.valueOf(plan.getPastDueDays()),
                plan.getExitPolicy(), plan.getRefundPolicy())));
        paying.setAmountFen(1L);
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan);
        when(mapper.selectActivePayingInvoiceForUpdate(CLUB, MEMBER)).thenReturn(paying);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.renew(MEMBER, renew(9L, "idem-after-tamper")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, never()).insertInvoice(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void committedIntentSurvivesButStateConflictStillFailsClosedAfterWechat() {
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan(9L, CLUB, 1, 9900L));
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        AtomicReference<ClubMembershipInvoice> stored = new AtomicReference<>();
        when(mapper.insertInvoice(any())).thenAnswer(call -> {
            ClubMembershipInvoice inserted = call.getArgument(0, ClubMembershipInvoice.class);
            inserted.setId(100L);
            stored.set(inserted);
            return 1;
        });
        when(mapper.selectInvoiceByOutTradeNoForUpdate(any())).thenAnswer(call -> stored.get());
        when(mapper.markInvoicePayingCas(eq(100L), eq(0), any())).thenReturn(1);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("timeStamp", "1");
        payParams.put("nonceStr", "n");
        payParams.put("package", "prepay_id=x");
        payParams.put("signType", "RSA");
        payParams.put("paySign", "s");
        when(pay.createGenericOrder(any(), eq(new java.math.BigDecimal("99.00")), any(), eq("openid-71")))
                .thenReturn(payParams);
        when(mapper.attachPayParamsCas(eq(100L), eq(1), any(), any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.renew(MEMBER, renew(9L, "idem-cas-conflict")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(pay, times(1)).createGenericOrder(any(), any(), any(), any());
        verify(transactions).commit(transactionStatus);
        verify(transactions).rollback(transactionStatus);
    }

    @Test
    void committedIntentCanReturnCurrentWechatParamsWhenAttachDatabaseIsUnavailable() {
        when(mapper.selectSetting(CLUB)).thenReturn(enabledSetting());
        when(mapper.selectPlanByIdForUpdate(9L)).thenReturn(plan(9L, CLUB, 1, 9900L));
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(mapper.insertInvoice(any())).thenAnswer(call -> {
            call.getArgument(0, ClubMembershipInvoice.class).setId(100L);
            return 1;
        });
        when(mapper.markInvoicePayingCas(eq(100L), eq(0), any())).thenReturn(1);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("timeStamp", "1");
        payParams.put("nonceStr", "n");
        payParams.put("package", "prepay_id=x");
        payParams.put("signType", "RSA");
        payParams.put("paySign", "s");
        when(pay.createGenericOrder(any(), any(), any(), eq("openid-71"))).thenReturn(payParams);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        ClubMembershipRenewalVO result = service.renew(MEMBER, renew(9L, "idem-attach-db-down"));

        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        assertEquals(payParams, result.getPayParams());
        verify(transactions).commit(transactionStatus);
        verify(transactions).rollback(transactionStatus);
    }

    @Test
    void recoverableInvoiceReusesSameOutTradeNoAndNeverCreatesAnotherInvoice() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        Map<String, String> payParams = new HashMap<>();
        payParams.put("timeStamp", "1");
        payParams.put("nonceStr", "n");
        payParams.put("package", "prepay_id=recovered");
        payParams.put("signType", "RSA");
        payParams.put("paySign", "s");
        when(pay.createGenericOrder(eq(invoice.getOutTradeNo()),
                eq(new java.math.BigDecimal("99.00")), any(), eq("openid-71"))).thenReturn(payParams);
        when(mapper.attachPayParamsCas(eq(invoice.getId()), eq(1), any(), any())).thenReturn(1);
        ClubMembershipActionRequest request = new ClubMembershipActionRequest();
        request.setClubId(CLUB);
        request.setId(invoice.getId());
        request.setRequestId("resume-invoice-100");

        ClubMembershipRenewalVO result = service.resumeInvoice(MEMBER, request);

        assertEquals(invoice.getId(), result.getInvoice().getId());
        assertEquals(payParams, result.getPayParams());
        assertEquals("PAYING", result.getPaymentState());
        verify(mapper, never()).insertInvoice(any());
        verify(pay, times(1)).createGenericOrder(eq(invoice.getOutTradeNo()), any(), any(), any());
    }

    @Test
    void resumeRevalidatesActiveMembershipUnderClubAndMemberLocks() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(clubMembers.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, action(invoice.getId(), "resume-after-removal")));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(clubs).selectClubByIdForUpdate(CLUB);
        verify(mapper, never()).selectInvoiceByIdForUpdate(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(pay, never()).queryOrderStatus(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void sameResumeRequestReplaysCommittedAuditAndContinuesWechatRecoveryAfterLostResponse() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);

        String requestId = "resume-lost-response-100";
        AtomicReference<BusinessAuditEvent> committedReceipt = new AtomicReference<>();
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PREPAY_RESUMED", requestId))
                .thenAnswer(call -> committedReceipt.get());
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            if (committedReceipt.get() != null) {
                throw new DuplicateKeyException("duplicate resume request");
            }
            BusinessAuditEvent inserted = call.getArgument(0, BusinessAuditEvent.class);
            inserted.setId(900L);
            committedReceipt.set(inserted);
            return 1;
        });

        Map<String, String> recoveredParams = new HashMap<>();
        recoveredParams.put("timeStamp", "1");
        recoveredParams.put("nonceStr", "n");
        recoveredParams.put("package", "prepay_id=recovered-after-loss");
        recoveredParams.put("signType", "RSA");
        recoveredParams.put("paySign", "s");
        when(pay.queryOrderStatus(invoice.getOutTradeNo()))
                .thenThrow(new IllegalStateException("first query unavailable"))
                .thenReturn(wxOrder(invoice, "NOTPAY"));
        when(pay.createGenericOrder(eq(invoice.getOutTradeNo()),
                eq(new java.math.BigDecimal("99.00")), any(), eq("openid-71")))
                .thenThrow(new IllegalStateException("first prepay response lost"))
                .thenReturn(recoveredParams);
        when(mapper.attachPayParamsCas(eq(invoice.getId()), eq(1), any(), any())).thenReturn(1);
        ClubMembershipActionRequest request = action(invoice.getId(), requestId);

        ClubMembershipRenewalVO first = service.resumeInvoice(MEMBER, request);
        ClubMembershipRenewalVO replay = service.resumeInvoice(MEMBER, request);

        assertEquals("PREPAY_UNKNOWN", first.getPaymentState());
        assertEquals(ClubMembershipInvoice.STATUS_PAYING, replay.getPaymentState());
        assertEquals(recoveredParams, replay.getPayParams());
        verify(audits, times(1)).insertBusinessAuditEvent(any(BusinessAuditEvent.class));
        verify(pay, times(2)).queryOrderStatus(invoice.getOutTradeNo());
        verify(pay, times(2)).createGenericOrder(eq(invoice.getOutTradeNo()), any(), any(), any());
    }

    @Test
    void resumeReplayFailsClosedWhenAuditActorDoesNotMatch() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        String requestId = "resume-wrong-actor-100";
        BusinessAuditEvent receipt = resumeAuditReceipt(invoice, requestId);
        receipt.setActorMemberId(999L);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PREPAY_RESUMED", requestId)).thenReturn(receipt);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, action(invoice.getId(), requestId)));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(pay, never()).queryOrderStatus(any());
    }

    @Test
    void resumeReplayFailsClosedWhenAuditInvoiceDoesNotMatch() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        String requestId = "resume-wrong-invoice-100";
        BusinessAuditEvent receipt = resumeAuditReceipt(invoice, requestId);
        receipt.setTargetId(999L);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PREPAY_RESUMED", requestId)).thenReturn(receipt);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, action(invoice.getId(), requestId)));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(pay, never()).queryOrderStatus(any());
    }

    @Test
    void resumeReplayFailsClosedWhenAuditRequestIdDoesNotMatch() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        String requestId = "resume-request-100";
        BusinessAuditEvent receipt = resumeAuditReceipt(invoice, "different-resume-request-100");
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PREPAY_RESUMED", requestId)).thenReturn(receipt);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, action(invoice.getId(), requestId)));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(pay, never()).queryOrderStatus(any());
    }

    @Test
    void resumeReplayAcceptsDatabaseNormalizedAuditJson() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson("{\"timeStamp\":\"1\"}");
        String requestId = "resume-normalized-json-100";
        BusinessAuditEvent receipt = resumeAuditReceipt(invoice, requestId);
        receipt.setAfterJson("{\"recoverable\": true, \"status\": \"PAYING\"}");
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(audits.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB, "CLUB_MEMBERSHIP_PREPAY_RESUMED", requestId)).thenReturn(receipt);
        when(pay.queryOrderStatus(invoice.getOutTradeNo()))
                .thenThrow(new IllegalStateException("query unavailable"));

        ClubMembershipRenewalVO result = service.resumeInvoice(
                MEMBER, action(invoice.getId(), requestId));

        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        verify(audits, never()).insertBusinessAuditEvent(any());
        verify(pay).queryOrderStatus(invoice.getOutTradeNo());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void resumeReconcilesWechatPaidOrderBeforeCreatingAnotherPrepay() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(1);
        ClubMembershipInvoice paidInvoice = validPayingInvoice();
        paidInvoice.setStatus(ClubMembershipInvoice.STATUS_PAID);
        paidInvoice.setTransactionId("wx-tx-recovered");
        var consumed=proof.issue(paidInvoice.getOutTradeNo(),9900,"wx-tx-recovered");
        paidInvoice.setVerifiedPaymentFactId(consumed.id());paidInvoice.setVerifiedPaymentFactHash(consumed.hash());
        paidInvoice.setVersion(2);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice, paidInvoice);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        stubActiveCallback(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(1), eq("wx-tx-recovered"), any()))
                .thenReturn(1);
        when(mapper.insertTerm(any(ClubMembershipTerm.class))).thenReturn(1);
        Map<String, String> wxOrder = new HashMap<>();
        wxOrder.put("trade_state", "SUCCESS");
        wxOrder.put("transaction_id", "wx-tx-recovered");
        var originalProof=proof.issue(invoice.getOutTradeNo(),9900,"wx-tx-recovered");
        wxOrder.put("verified_fact_id",Long.toString(originalProof.id()));wxOrder.put("verified_fact_hash",originalProof.hash());
        wxOrder.put("out_trade_no", invoice.getOutTradeNo());
        wxOrder.put("total", "9900");
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder);
        ClubMembershipActionRequest request = new ClubMembershipActionRequest();
        request.setClubId(CLUB);
        request.setId(invoice.getId());
        request.setRequestId("resume-paid-invoice-100");

        ClubMembershipRenewalVO result = service.resumeInvoice(MEMBER, request);

        assertEquals(ClubMembershipInvoice.STATUS_PAID, result.getPaymentState());
        assertEquals("wx-tx-recovered", result.getInvoice().getTransactionId());
        verify(pay).queryOrderStatus(invoice.getOutTradeNo());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
        verify(mapper).insertTerm(any(ClubMembershipTerm.class));
    }

    @Test
    void resumeRejectsWechatPaidOrderWithMismatchedAmountBeforeGrantingEntitlement() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        Map<String, String> wxOrder = new HashMap<>();
        wxOrder.put("trade_state", "SUCCESS");
        wxOrder.put("transaction_id", "wx-tx-wrong-amount");
        var originalProof=proof.issue(invoice.getOutTradeNo(),9900,"wx-tx-wrong-amount");
        wxOrder.put("verified_fact_id",Long.toString(originalProof.id()));wxOrder.put("verified_fact_hash",originalProof.hash());
        wxOrder.put("out_trade_no", invoice.getOutTradeNo());
        wxOrder.put("total", "9899");
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder);
        ClubMembershipActionRequest request = new ClubMembershipActionRequest();
        request.setClubId(CLUB);
        request.setId(invoice.getId());
        request.setRequestId("resume-wrong-amount-100");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, request));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, never()).markInvoicePaidCas(any(), anyInt(), any(), any());
        verify(mapper, never()).insertTerm(any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void resumedWechatPrepayNeverHidesPayParamsCasConflictAsExternalUncertainty() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "NOTPAY"));
        Map<String, String> payParams = new HashMap<>();
        payParams.put("timeStamp", "1");
        payParams.put("nonceStr", "n");
        payParams.put("package", "prepay_id=recovered");
        payParams.put("signType", "RSA");
        payParams.put("paySign", "s");
        when(pay.createGenericOrder(eq(invoice.getOutTradeNo()),
                eq(new java.math.BigDecimal("99.00")), any(), eq("openid-71"))).thenReturn(payParams);
        when(mapper.attachPayParamsCas(eq(invoice.getId()), eq(1), any(), any())).thenReturn(0);
        ClubMembershipActionRequest request = new ClubMembershipActionRequest();
        request.setClubId(CLUB);
        request.setId(invoice.getId());
        request.setRequestId("resume-cas-conflict-100");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER, request));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(pay, times(1)).createGenericOrder(eq(invoice.getOutTradeNo()), any(), any(), any());
    }

    @Test
    void resumedWechatPrepayNeverReturnsParamsWhenAccessEndBlocksBeforeAttach() {
        ClubMembershipInvoice prepared = validPayingInvoice();
        prepared.setPayParamsJson(null);
        prepared.setVersion(1);
        ClubMembershipInvoice blocked = validPayingInvoice();
        blocked.setPayParamsJson(null);
        blocked.setVersion(2);
        blocked.setResumeBlockedAt(Date.from(Instant.parse("2026-08-23T15:59:59Z")));
        stubResumedPrepay(prepared, payParams("prepay_id=must-not-leak"));
        when(mapper.selectInvoiceByIdForUpdate(prepared.getId())).thenReturn(prepared);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(prepared.getOutTradeNo())).thenReturn(blocked);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.resumeInvoice(MEMBER,
                        action(prepared.getId(), "resume-blocked-before-attach")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, never()).attachPayParamsCas(any(), anyInt(), any(), any());
    }

    @Test
    void resumedPrepayAcceptsConcurrentPaidInvoiceReadbackInsteadOfUsingStaleVersion() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        ClubMembershipInvoice concurrentlyPaid = validPayingInvoice();
        concurrentlyPaid.setStatus(ClubMembershipInvoice.STATUS_PAID);
        concurrentlyPaid.setTransactionId("wx-paid-during-resume");
        concurrentlyPaid.setPayParamsJson(null);
        concurrentlyPaid.setVersion(2);
        stubResumedPrepay(invoice, payParams("prepay_id=new-response"));
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo()))
                .thenReturn(concurrentlyPaid);

        ClubMembershipRenewalVO result = service.resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-concurrent-paid-100"));

        assertEquals(ClubMembershipInvoice.STATUS_PAID, result.getPaymentState());
        assertEquals("wx-paid-during-resume", result.getInvoice().getTransactionId());
        verify(mapper, never()).attachPayParamsCas(any(), anyInt(), any(), any());
    }

    @Test
    void resumedPrepayAcceptsConcurrentClosedInvoiceReadbackInsteadOfUsingStaleVersion() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        ClubMembershipInvoice concurrentlyClosed = validPayingInvoice();
        concurrentlyClosed.setStatus(ClubMembershipInvoice.STATUS_CLOSED);
        concurrentlyClosed.setPayParamsJson(null);
        concurrentlyClosed.setVersion(2);
        stubResumedPrepay(invoice, payParams("prepay_id=new-response"));
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo()))
                .thenReturn(concurrentlyClosed);

        ClubMembershipRenewalVO result = service.resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-concurrent-closed-100"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        assertFalse(result.isRecoverable());
        verify(mapper, never()).attachPayParamsCas(any(), anyInt(), any(), any());
    }

    @Test
    void resumedPrepayReturnsAlreadyAttachedConcurrentParamsInsteadOfUsingStaleVersion() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        ClubMembershipInvoice concurrentlyAttached = validPayingInvoice();
        concurrentlyAttached.setVersion(2);
        stubResumedPrepay(invoice, payParams("prepay_id=new-response"));
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo()))
                .thenReturn(concurrentlyAttached);

        ClubMembershipRenewalVO result = service.resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-concurrent-attached-100"));

        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        assertEquals("prepay_id=stored", result.getPayParams().get("package"));
        verify(mapper, never()).attachPayParamsCas(any(), anyInt(), any(), any());
    }

    @Test
    void resumedPrepayKeepsCommittedPayingFactWhenWechatAcceptedButAttachDatabaseIsUnavailable() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        Map<String, String> acceptedParams = payParams("prepay_id=accepted-before-db-loss");
        stubResumedPrepay(invoice, acceptedParams);
        AtomicInteger localTransactionDepth = trackLocalTransaction();
        AtomicBoolean externalCallInsideLocalTransaction = new AtomicBoolean(false);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenAnswer(call ->
                recordExternalState(externalCallInsideLocalTransaction,
                        localTransactionDepth, wxOrder(invoice, "NOTPAY")));
        when(pay.createGenericOrder(eq(invoice.getOutTradeNo()), any(), any(), eq("openid-71")))
                .thenAnswer(call -> recordExternalState(externalCallInsideLocalTransaction,
                        localTransactionDepth, acceptedParams));
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo()))
                .thenThrow(new IllegalStateException("database unavailable after WeChat accepted"));

        ClubMembershipRenewalVO result = transactionalService().resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-attach-db-down-100"));

        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        assertEquals(acceptedParams, result.getPayParams());
        assertTrue(result.isRecoverable());
        assertFalse(externalCallInsideLocalTransaction.get());
        assertEquals(0, localTransactionDepth.get());
    }

    @Test
    void resumeClosesWechatTerminatedPayingInvoiceSoAnotherPlanCanBePurchased() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "CLOSED"));
        when(mapper.markInvoiceClosedCas(eq(invoice.getId()), eq(1), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.resumeInvoice(MEMBER, action(invoice.getId(), "resume-closed-100"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        assertFalse(result.isRecoverable());
        verify(mapper).markInvoiceClosedCas(eq(invoice.getId()), eq(1), any());
        verify(pay, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void resumeCommitsLocalPreparationBeforeWechatQuery() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        AtomicInteger localTransactionActive = trackLocalTransaction();
        AtomicBoolean queriedInsideLocalTransaction = new AtomicBoolean(false);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenAnswer(call -> {
            queriedInsideLocalTransaction.set(localTransactionActive.get() > 0);
            return wxOrder(invoice, "NOTPAY");
        });

        ClubMembershipRenewalVO result = transactionalService().resumeInvoice(
                MEMBER, action(invoice.getId(), "resume-tx-boundary-100"));

        assertEquals(ClubMembershipInvoice.STATUS_PAYING, result.getPaymentState());
        assertFalse(queriedInsideLocalTransaction.get(),
                "微信查单不能发生在本地数据库事务内");
        assertEquals(0, localTransactionActive.get());
        verify(transactions, times(2)).commit(transactionStatus);
    }

    @Test
    void resumeRejectsAmbientTransactionWithoutSuspendingCallerLocks() {
        TrackingTransactionManager tracker = useTrackingTransactions();
        new TransactionTemplate(tracker).execute(status -> {
            assertTrue(tracker.isTransactionActive());
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    () -> transactionalService(tracker).resumeInvoice(MEMBER, action(100L, "resume-ambient-tx-100")));
            assertTrue(tracker.isTransactionActive(), "rejected call must not suspend or end the caller transaction");
            return null;
        });
        assertFalse(tracker.isTransactionActive());
        org.mockito.Mockito.verifyNoInteractions(feature, access, mapper, clubs, clubMembers, members, pay, audits);
    }

    @Test
    void explicitCancelClosesWechatThenRequiresReadbackBeforeLocalInvoiceIsClosed() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(2);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo()))
                .thenReturn(wxOrder(invoice, "NOTPAY"), wxOrder(invoice, "CLOSED"));
        when(pay.closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo())).thenReturn(true);
        when(mapper.markInvoiceClosedCas(eq(invoice.getId()), eq(2), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.closeInvoice(
                MEMBER, action(invoice.getId(), "cancel-pending-100"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        assertFalse(result.isRecoverable());
        InOrder order = inOrder(pay, mapper);
        order.verify(pay).queryOrderStatus(invoice.getOutTradeNo());
        order.verify(pay).closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo());
        order.verify(pay).queryOrderStatus(invoice.getOutTradeNo());
        order.verify(mapper).markInvoiceClosedCas(eq(invoice.getId()), eq(2), any());
    }

    @Test
    void explicitCancelOfW1CannotCloseOrGrantForNewNotpayW2() {
        CloseAttemptReadbackFixture f = new CloseAttemptReadbackFixture(Transaction.TradeStateEnum.NOTPAY);
        String originalParams = f.invoice.getPayParamsJson();
        String originalSnapshot = f.invoice.getSnapshotHash();

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.closeInvoice(MEMBER, action(f.invoice.getId(), "close-w1-read-w2-notpay")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        assertEquals(ClubMembershipInvoice.STATUS_PAYING, f.invoice.getStatus());
        assertEquals(2, f.invoice.getVersion());
        assertEquals(originalParams, f.invoice.getPayParamsJson());
        assertEquals(originalSnapshot, f.invoice.getSnapshotHash());
        verify(mapper, never()).markInvoiceClosedCas(any(), anyInt(), any());
        verify(mapper, never()).markInvoicePaidCas(any(), anyInt(), any(), any());
        verify(mapper, never()).insertTerm(any());
        verify(mapper, never()).insertRefundReview(any());
        verify(f.gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        f.verifyOriginalCloseAndSuccessorQuery();
    }

    @Test
    void explicitCancelOfW1RecordsOnlyVerifiedSuccessfulW2WithOriginalInvoiceOwnerAndAmount() {
        CloseAttemptReadbackFixture f = new CloseAttemptReadbackFixture(Transaction.TradeStateEnum.SUCCESS);
        ClubMembershipInvoice paid = validPayingInvoice();
        paid.setStatus(ClubMembershipInvoice.STATUS_PAID);
        paid.setTransactionId("wx-w2-success");
        paid.setVersion(3);
        stubActiveCallback(f.invoice);
        AtomicReference<ClubMembershipInvoice> stored = new AtomicReference<>(f.invoice);
        // Every read observes storage state; only the exact successful CAS advances it to PAID.
        // Avoid ordered returns: preparation and locking can legitimately read the same row again.
        org.mockito.Mockito.doAnswer(call -> stored.get()).when(mapper).selectInvoiceById(f.invoice.getId());
        org.mockito.Mockito.doAnswer(call -> stored.get()).when(mapper).selectInvoiceByIdForUpdate(f.invoice.getId());
        org.mockito.Mockito.doAnswer(call -> stored.get()).when(mapper).selectInvoiceByOutTradeNo(f.invoice.getOutTradeNo());
        org.mockito.Mockito.doAnswer(call -> stored.get()).when(mapper).selectInvoiceByOutTradeNoForUpdate(f.invoice.getOutTradeNo());
        when(mapper.markInvoicePaidCas(eq(f.invoice.getId()), eq(2), eq("wx-w2-success"), any())).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertSame(f.invoice, stored.get());
            assertEquals(ClubMembershipInvoice.STATUS_PAYING, stored.get().getStatus());
            stored.set(paid);
            return 1;
        });
        when(mapper.insertTerm(any(ClubMembershipTerm.class))).thenReturn(1);

        ClubMembershipRenewalVO result = service.closeInvoice(
                MEMBER, action(f.invoice.getId(), "close-w1-read-w2-success"));

        assertEquals(ClubMembershipInvoice.STATUS_PAID, result.getPaymentState());
        assertEquals("wx-w2-success", result.getInvoice().getTransactionId());
        assertEquals(f.invoice.getSnapshotHash(), result.getInvoice().getSnapshotHash());
        assertEquals(3, result.getInvoice().getVersion());
        org.junit.jupiter.api.Assertions.assertSame(paid, stored.get());
        org.junit.jupiter.api.Assertions.assertSame(paid, result.getInvoice());
        InOrder persistedReadback = inOrder(mapper);
        persistedReadback.verify(mapper).markInvoicePaidCas(eq(f.invoice.getId()), eq(2), eq("wx-w2-success"), any());
        persistedReadback.verify(mapper).selectInvoiceById(f.invoice.getId());
        verify(mapper).markInvoicePaidCas(eq(f.invoice.getId()), eq(2), eq("wx-w2-success"), any());
        ArgumentCaptor<ClubMembershipTerm> term = ArgumentCaptor.forClass(ClubMembershipTerm.class);
        verify(mapper).insertTerm(term.capture());
        assertEquals(f.invoice.getId(), term.getValue().getSourceInvoiceId());
        assertEquals(Long.valueOf(MEMBER), term.getValue().getMemberId());
        assertEquals(Long.valueOf(CLUB), term.getValue().getClubId());
        assertEquals(ClubMembershipTerm.STATUS_ACTIVE, term.getValue().getStatus());
        verify(mapper, never()).markInvoiceClosedCas(any(), anyInt(), any());
        verify(mapper, never()).insertRefundReview(any());
        verify(f.gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        f.verifyOriginalCloseAndSuccessorQuery();
    }

    @Test
    void explicitCancelOfW1ClosesLocalInvoiceOnlyAfterCurrentClosedW2Fence() {
        CloseAttemptReadbackFixture f = new CloseAttemptReadbackFixture(Transaction.TradeStateEnum.CLOSED);
        when(mapper.markInvoiceClosedCas(eq(f.invoice.getId()), eq(2), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.closeInvoice(
                MEMBER, action(f.invoice.getId(), "close-w1-read-w2-closed"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        assertEquals(3, result.getInvoice().getVersion());
        assertFalse(result.isRecoverable());
        InOrder order = inOrder(f.gateway, mapper);
        order.verify(f.gateway).lockClosablePaymentAttemptForUpdate(f.invoice.getOutTradeNo(), f.w2);
        order.verify(mapper).markInvoiceClosedCas(eq(f.invoice.getId()), eq(2), any());
        verify(f.gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), eq(f.w1));
        verify(mapper, never()).markInvoicePaidCas(any(), anyInt(), any(), any());
        verify(mapper, never()).insertTerm(any());
        verify(mapper, never()).insertRefundReview(any());
        assertTrue(f.closedAttempts.contains(f.w2));
        f.verifyOriginalCloseAndSuccessorQuery();
    }

    @Test
    void formerMemberCanCloseOnlyTheirExactPendingInvoice() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(2);
        when(access.resolve(MEMBER, CLUB, null))
                .thenThrow(new ServiceException("已不是成员", HttpStatus.FORBIDDEN));
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.selectInvoiceByIdForUpdate(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo()))
                .thenReturn(wxOrder(invoice, "NOTPAY"), wxOrder(invoice, "CLOSED"));
        when(pay.closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo())).thenReturn(true);
        when(mapper.markInvoiceClosedCas(eq(invoice.getId()), eq(2), any())).thenReturn(1);

        ClubMembershipRenewalVO result = service.closeInvoice(
                MEMBER, action(invoice.getId(), "former-member-close"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        verify(access, never()).require(any(), any(), any(), any());
    }

    @Test
    void explicitCancelKeepsEveryWechatCallOutsideLocalTransactionAndAppliesReadbackInsideOne() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setVersion(2);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        AtomicInteger localTransactionActive = trackLocalTransaction();
        AtomicBoolean externalCallInsideLocalTransaction = new AtomicBoolean(false);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenAnswer(
                call -> recordExternalState(externalCallInsideLocalTransaction,
                        localTransactionActive, wxOrder(invoice, "NOTPAY")))
                .thenAnswer(call -> recordExternalState(externalCallInsideLocalTransaction,
                        localTransactionActive, wxOrder(invoice, "CLOSED")));
        when(pay.closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo())).thenAnswer(call ->
                recordExternalState(externalCallInsideLocalTransaction, localTransactionActive, true));
        AtomicBoolean closeAppliedInsideLocalTransaction = new AtomicBoolean(false);
        when(mapper.markInvoiceClosedCas(eq(invoice.getId()), eq(2), any())).thenAnswer(call -> {
            closeAppliedInsideLocalTransaction.set(localTransactionActive.get() > 0);
            return 1;
        });

        ClubMembershipRenewalVO result = transactionalService().closeInvoice(
                MEMBER, action(invoice.getId(), "cancel-tx-boundary-100"));

        assertEquals(ClubMembershipInvoice.STATUS_CLOSED, result.getPaymentState());
        assertFalse(externalCallInsideLocalTransaction.get(),
                "微信查单与关单不能发生在本地数据库事务内");
        assertTrue(closeAppliedInsideLocalTransaction.get(),
                "微信终态必须在独立短事务内通过 CAS 落地");
        assertEquals(0, localTransactionActive.get());
    }

    @Test
    void explicitCancelRejectsAmbientTransactionWithoutSuspendingCallerLocks() {
        TrackingTransactionManager tracker = useTrackingTransactions();
        new TransactionTemplate(tracker).execute(status -> {
            assertTrue(tracker.isTransactionActive());
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    () -> transactionalService(tracker).closeInvoice(MEMBER, action(100L, "cancel-ambient-tx-100")));
            assertTrue(tracker.isTransactionActive(), "rejected call must not suspend or end the caller transaction");
            return null;
        });
        assertFalse(tracker.isTransactionActive());
        org.mockito.Mockito.verifyNoInteractions(feature, access, mapper, clubs, clubMembers, members, pay, audits);
    }

    @Test
    void explicitCancelKeepsPayingWhenWechatCloseReadbackIsUnknown() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo()))
                .thenReturn(wxOrder(invoice, "NOTPAY"), wxOrder(invoice, "USERPAYING"));
        when(pay.closePaymentAttempt(invoice.getOutTradeNo(), invoice.getOutTradeNo())).thenReturn(true);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.closeInvoice(MEMBER, action(invoice.getId(), "cancel-unknown-100")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(mapper, never()).markInvoiceClosedCas(any(), anyInt(), any());
    }

    @Test
    void refundedWechatOrderIsNeverCollapsedIntoAnUnpaidClosedInvoice() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "REFUND"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.closeInvoice(MEMBER, action(invoice.getId(), "cancel-refund-100")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(pay, never()).closePaymentAttempt(any(), any());
        verify(mapper, never()).markInvoiceClosedCas(any(), anyInt(), any());
    }

    @Test
    void callbackRejectsWrongAmountBeforeCas() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        stubActiveCallback(invoice);

        assertFalse(paidInvoice(invoice.getOutTradeNo(), true, 9899, "wx-tx-1"));

        verify(mapper, never()).markInvoicePaidCas(any(), any(Integer.class), any(), any());
        verify(mapper, never()).insertTerm(any());
    }

    @Test
    void callbackCasCreatesOneTermAndExactDuplicateIsSuccess() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        stubActiveCallback(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()), eq("wx-tx-1"), any()))
                .thenReturn(1);
        when(mapper.insertTerm(any(ClubMembershipTerm.class))).thenReturn(1);

        assertTrue(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-1"));
        ArgumentCaptor<ClubMembershipTerm> term = ArgumentCaptor.forClass(ClubMembershipTerm.class);
        verify(mapper).insertTerm(term.capture());
        assertEquals(Instant.parse("2026-08-23T16:00:00Z"), term.getValue().getStartsAt().toInstant());
        assertEquals(Instant.parse("2026-09-22T16:00:00Z"), term.getValue().getEndsAt().toInstant());
        assertEquals(ClubMembershipTerm.STATUS_ACTIVE, term.getValue().getStatus());

        invoice.setStatus(ClubMembershipInvoice.STATUS_PAID);
        invoice.setTransactionId("wx-tx-1");
        assertTrue(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-1"));
        verify(mapper, times(1)).insertTerm(any());
    }

    @Test
    void paidCallbackSerializesWithMembershipExitUsingClubThenMemberThenInvoiceLockOrder() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        stubActiveCallback(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()), eq("wx-tx-order"), any()))
                .thenReturn(1);
        when(mapper.insertTerm(any(ClubMembershipTerm.class))).thenReturn(1);

        assertTrue(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-order"));

        InOrder order = inOrder(mapper, clubs, clubMembers);
        order.verify(mapper).selectInvoiceByOutTradeNo(invoice.getOutTradeNo());
        order.verify(clubs).selectClubByIdForUpdate(CLUB);
        order.verify(clubMembers).selectByClubAndMemberForUpdate(CLUB, MEMBER);
        order.verify(mapper).selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo());
        order.verify(mapper).markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()),
                eq("wx-tx-order"), any());
    }

    @Test
    void callbackRejectsTransactionAlreadyOwnedByAnotherInvoice() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        ClubMembershipInvoice other = validPayingInvoice();
        other.setId(101L);
        stubActiveCallback(invoice);
        when(mapper.selectInvoiceByTransactionId("wx-tx-used")).thenReturn(other);

        assertFalse(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-used"));

        verify(mapper, never()).markInvoicePaidCas(any(), any(Integer.class), any(), any());
        verify(mapper, never()).insertTerm(any());
    }

    @Test
    void paidInvoiceOnlyAcceptsExactSameTransactionOnReplay() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setStatus(ClubMembershipInvoice.STATUS_PAID);
        invoice.setTransactionId("wx-tx-original");
        var original=proof.issue(invoice.getOutTradeNo(),9900,"wx-tx-original");
        invoice.setVerifiedPaymentFactId(original.id());invoice.setVerifiedPaymentFactHash(original.hash());
        stubActiveCallback(invoice);

        assertFalse(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-other"));

        verify(mapper, never()).insertTerm(any());
        verify(mapper, never()).markInvoicePaidCas(any(), any(Integer.class), any(), any());
    }

    @Test
    void paidRenewalStartsAfterCurrentEntitlementEnds() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        ClubMembershipTerm current = new ClubMembershipTerm();
        current.setId(50L);
        current.setClubId(CLUB);
        current.setMemberId(MEMBER);
        current.setStatus(ClubMembershipTerm.STATUS_ACTIVE);
        current.setEndsAt(Date.from(Instant.parse("2026-09-01T16:00:00Z")));
        stubActiveCallback(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()), eq("wx-tx-next"), any()))
                .thenReturn(1);
        when(mapper.selectLatestTermForUpdate(CLUB, MEMBER)).thenReturn(current);
        when(mapper.insertTerm(any())).thenReturn(1);

        assertTrue(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-next"));

        ArgumentCaptor<ClubMembershipTerm> term = ArgumentCaptor.forClass(ClubMembershipTerm.class);
        verify(mapper).insertTerm(term.capture());
        assertEquals(Instant.parse("2026-09-01T16:00:00Z"), term.getValue().getStartsAt().toInstant());
        assertEquals(Instant.parse("2026-10-01T16:00:00Z"), term.getValue().getEndsAt().toInstant());
        assertEquals(ClubMembershipTerm.STATUS_PENDING, term.getValue().getStatus());
    }

    @Test
    void refundRequestOnlyCreatesPendingReviewAndNeverCallsWechatRefund() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setStatus(ClubMembershipInvoice.STATUS_PAID);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.insertRefundReview(any())).thenAnswer(call -> {
            call.getArgument(0, com.chengyinhub.business.domain.ClubMembershipRefundReview.class).setId(300L);
            return 1;
        });
        ClubMembershipActionRequest request = new ClubMembershipActionRequest();
        request.setClubId(CLUB);
        request.setId(invoice.getId());
        request.setReason("搬离城市，申请人工审核");
        request.setRequestId("refund-review-1");

        com.chengyinhub.business.domain.ClubMembershipRefundReview review =
                service.requestRefundReview(MEMBER, request);

        assertEquals("PENDING_REVIEW", review.getStatus());
        verify(mapper, never()).updateTermStatusCas(any(), any(), any(), any(Integer.class), any(), any());
        verify(pay, never()).submitRefund(any(), any(), any(), any(), any());
    }

    @Test
    void accessEndUsesFrozenPoliciesAndOnlyCreatesPendingReviewFact() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setStatus(ClubMembershipInvoice.STATUS_PAID);
        ClubMembershipTerm term = termFor(invoice);
        when(mapper.selectEntitledTermsByMemberForUpdate(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(Collections.singletonList(term));
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.updateTermStatusCas(eq(term.getId()), eq(CLUB), eq(MEMBER), eq(0),
                eq(ClubMembershipTerm.STATUS_CANCELLED), any(Date.class))).thenReturn(1);
        when(mapper.insertRefundReview(any())).thenAnswer(call -> {
            call.getArgument(0, com.chengyinhub.business.domain.ClubMembershipRefundReview.class).setId(300L);
            return 1;
        });

        ClubMembershipAccessEndResultVO result = service.handleMemberAccessEnded(
                CLUB, MEMBER, "MEMBER_QUIT", "quit-71-1");

        assertEquals(1, result.getCancelledTermCount());
        assertEquals(1, result.getPendingReviewCount());
        assertEquals(0, result.getNonRefundableCount());
        assertFalse(result.isPayoutCompleted());
        verify(pay, never()).submitRefund(any(), any(), any(), any(), any());
    }

    @Test
    void accessEndBlocksEveryPayingInvoiceEvenWhenThereIsNoPaidTerm() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        when(mapper.selectPayingInvoicesByMemberForUpdate(CLUB, MEMBER))
                .thenReturn(Collections.singletonList(invoice));
        when(mapper.blockInvoiceResumeCas(eq(invoice.getId()), eq(invoice.getVersion()), any(Date.class)))
                .thenReturn(1);
        when(mapper.selectEntitledTermsByMemberForUpdate(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(Collections.emptyList());

        ClubMembershipAccessEndResultVO result = service.handleMemberAccessEnded(
                CLUB, MEMBER, "MEMBER_REMOVED", "remove-member-71");

        assertEquals(1, result.getBlockedInvoiceCount());
        verify(mapper).blockInvoiceResumeCas(eq(invoice.getId()), eq(2), any(Date.class));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_MEMBERSHIP_PREPAY_BLOCKED", audit.getValue().getAction());
    }

    @Test
    void latePaidCallbackAfterAccessEndedCancelsTermAndQueuesReview() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        stubEndedCallback(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()), eq("wx-tx-late"), any()))
                .thenReturn(1);
        when(mapper.insertTerm(any())).thenReturn(1);
        when(mapper.insertRefundReview(any())).thenAnswer(call -> {
            call.getArgument(0, com.chengyinhub.business.domain.ClubMembershipRefundReview.class).setId(301L);
            return 1;
        });

        assertTrue(paidInvoice(invoice.getOutTradeNo(), true, 9900, "wx-tx-late"));

        ArgumentCaptor<ClubMembershipTerm> term = ArgumentCaptor.forClass(ClubMembershipTerm.class);
        verify(mapper).insertTerm(term.capture());
        assertEquals(ClubMembershipTerm.STATUS_CANCELLED, term.getValue().getStatus());
        ArgumentCaptor<com.chengyinhub.business.domain.ClubMembershipRefundReview> review =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.ClubMembershipRefundReview.class);
        verify(mapper).insertRefundReview(review.capture());
        assertEquals("PENDING_REVIEW", review.getValue().getStatus());
        verify(pay, never()).submitRefund(any(), any(), any(), any(), any());
    }

    @Test
    void latePaidCallbackAfterAccessEndedQueuesReviewEvenForNonRefundableSnapshot() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setRefundPolicySnapshot(ClubMembershipPlan.POLICY_NON_REFUNDABLE);
        invoice.setSnapshotHash(ClubMembershipSnapshotCodec.hash(invoice));
        stubEndedCallback(invoice);
        when(mapper.markInvoicePaidCas(eq(invoice.getId()), eq(invoice.getVersion()), eq("wx-tx-late-nonref"), any()))
                .thenReturn(1);
        when(mapper.insertTerm(any())).thenReturn(1);
        when(mapper.insertRefundReview(any())).thenAnswer(call -> {
            call.getArgument(0, com.chengyinhub.business.domain.ClubMembershipRefundReview.class).setId(302L);
            return 1;
        });

        assertTrue(paidInvoice(
                invoice.getOutTradeNo(), true, 9900, "wx-tx-late-nonref"));

        ArgumentCaptor<com.chengyinhub.business.domain.ClubMembershipRefundReview> review =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.ClubMembershipRefundReview.class);
        verify(mapper).insertRefundReview(review.capture());
        assertEquals("ACCESS_ENDED_BEFORE_PAYMENT_CALLBACK", review.getValue().getReason());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertTrue(audit.getValue().getAfterJson().contains("PAID_NO_ENTITLEMENT"));
    }

    /** No database claim: real CLM/gateway flow with official SDK and intention-read doubles. */
    private final class CloseAttemptReadbackFixture {
        final ClubMembershipInvoice invoice = validPayingInvoice();
        final String w1 = invoice.getOutTradeNo();
        final String w2 = "WXP0123456789abcdef0123456789abc";
        final AtomicReference<String> current = new AtomicReference<>(w1);
        final Set<String> closedAttempts = new HashSet<>();
        final AtomicBoolean w1Closed = new AtomicBoolean();
        final JsapiService sdk = mock(JsapiService.class);
        final WechatPayServiceImpl gateway = org.mockito.Mockito.spy(com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.gateway());

        CloseAttemptReadbackFixture(Transaction.TradeStateEnum w2State) {
            // Spring's resource-free transaction boundary exercises the gateway's actual-transaction guard;
            // it is not an InnoDB isolation or durability proof (the separate four-case MySQL suite is).
            PlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "transactionManager", manager);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "wechatPayService", gateway);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"verifiedPaymentFacts",
                    com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts"));
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(gateway, "paymentIntentTransactions", manager);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(gateway, "jsapiService", sdk);
            WxPayConfig config = mock(WxPayConfig.class);
            when(config.getMerchantId()).thenReturn("merchant-clm");
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(gateway, "wxPayConfig", config);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(gateway, "paymentIntentJdbc", new JdbcTemplate() {
                @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                    if (sql.contains("FROM club_membership_invoice")) {
                        assertEquals(w1, args[0]);
                        return List.of(Map.of("id", invoice.getId(), "amount", invoice.getAmountFen(),
                                "payable", ClubMembershipInvoice.STATUS_PAYING.equals(invoice.getStatus()) ? 1 : 0));
                    }
                    if (sql.contains("WHERE biz_type=? AND biz_id=?")) {
                        assertEquals("CLM", args[0]); assertEquals(invoice.getId(), args[1]);
                        assertTrue(sql.endsWith("FOR UPDATE"));
                        return List.of(intent(current.get()));
                    }
                    if (sql.contains("WHERE mchid=? AND business_out_trade_no=?")) {
                        assertEquals("merchant-clm", args[0]); assertEquals(w1, args[1]);
                        return List.of(intent(current.get()));
                    }
                    if (sql.contains("WHERE mchid=? AND out_trade_no=?")) {
                        assertEquals("merchant-clm", args[0]);
                        assertTrue(w1.equals(args[1]) || w2.equals(args[1]));
                        return List.of(intent((String)args[1]));
                    }
                    throw new AssertionError("Unexpected payment intent query: " + sql);
                }
                @Override public int update(String sql, Object... args) {
                    assertTrue(sql.startsWith("UPDATE wx_pay_order_intent SET closed_confirmed_at="));
                    assertEquals("merchant-clm", args[0]);
                    assertTrue(w1.equals(args[1]) || w2.equals(args[1]));
                    closedAttempts.add((String)args[1]);
                    return 1;
                }
            });
            var actualFacts=(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService)
                    com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts");
            var originalJdbc=(JdbcTemplate)com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.getField(gateway,"paymentIntentJdbc");
            var originalOwner=new com.chengyinhub.business.service.impl.JdbcWechatPaymentOrderOwner();
            org.springframework.test.util.ReflectionTestUtils.setField(originalOwner,"paymentIntentJdbc",originalJdbc);
            org.springframework.test.util.ReflectionTestUtils.setField(actualFacts,"factBusinessOwner",originalOwner);
            org.springframework.test.util.ReflectionTestUtils.setField(actualFacts,"factIntentJdbc",originalJdbc);
            when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
            when(mapper.selectInvoiceByIdForUpdate(invoice.getId())).thenReturn(invoice);
            when(sdk.queryOrderByOutTradeNo(any())).thenAnswer(call -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                QueryOrderByOutTradeNoRequest request = call.getArgument(0);
                assertEquals("merchant-clm", request.getMchid());
                String no = request.getOutTradeNo();
                assertTrue(w1.equals(no) || w2.equals(no));
                return facts(no, w1.equals(no) ? (w1Closed.get()
                        ? Transaction.TradeStateEnum.CLOSED : Transaction.TradeStateEnum.NOTPAY) : w2State);
            });
            org.mockito.Mockito.doAnswer(call -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest request = call.getArgument(0);
                assertEquals("merchant-clm", request.getMchid()); assertEquals(w1, request.getOutTradeNo());
                w1Closed.set(true);
                return null;
            }).when(sdk).closeOrder(any());
            org.mockito.Mockito.doAnswer(call -> {
                boolean accepted = (Boolean)call.callRealMethod();
                assertTrue(accepted); assertTrue(closedAttempts.contains(w1));
                assertEquals(w1, current.get());
                current.set(w2); // Another checkout wins after W1 CLOSED is committed, before CLM readback.
                return accepted;
            }).when(gateway).closePaymentAttempt(w1, w1);
        }

        Map<String, Object> intent(String no) {
            Map<String,Object> original=new HashMap<>(Map.of("out_trade_no", no, "business_out_trade_no", w1, "mchid", "merchant-clm",
                    "appid", w1.equals(no) ? "wx-clm-mini" : "wx-clm-app",
                    "trade_type", w1.equals(no) ? "JSAPI" : "APP", "currency", "CNY",
                    "total_fen", invoice.getAmountFen(), "request_hash", (w1.equals(no) ? "a" : "b").repeat(64),
                    "biz_type","CLM","biz_id",invoice.getId()));
            if(closedAttempts.contains(no))original.put("closed_confirmed_at",new Date(1));
            return original;
        }

        Transaction facts(String no, Transaction.TradeStateEnum state) {
            Transaction tx = new Transaction();
            tx.setOutTradeNo(no); tx.setMchid("merchant-clm");
            tx.setAppid(w1.equals(no) ? "wx-clm-mini" : "wx-clm-app");
            tx.setTradeType(w1.equals(no) ? Transaction.TradeTypeEnum.JSAPI : Transaction.TradeTypeEnum.APP);
            tx.setTradeState(state);
            if (state == Transaction.TradeStateEnum.SUCCESS) tx.setTransactionId("wx-w2-success"); tx.setSuccessTime("2026-10-24T00:00:00Z");
            TransactionAmount amount = new TransactionAmount();
            amount.setTotal(invoice.getAmountFen().intValue()); amount.setCurrency("CNY"); tx.setAmount(amount);
            return tx;
        }

        void verifyOriginalCloseAndSuccessorQuery() {
            assertEquals(w2, current.get());
            assertEquals(Long.valueOf(OWNER), invoice.getOwnerMemberIdSnapshot());
            assertEquals(Long.valueOf(MEMBER), invoice.getMemberId());
            assertEquals(Long.valueOf(CLUB), invoice.getClubId());
            assertEquals(Long.valueOf(9900), invoice.getAmountFen());
            verify(gateway).closePaymentAttempt(w1, w1);
            verify(gateway, never()).closePaymentAttempt(anyString(), eq(w2));
            verify(gateway, never()).closeOrder(anyString());
            ArgumentCaptor<QueryOrderByOutTradeNoRequest> requests = ArgumentCaptor.forClass(QueryOrderByOutTradeNoRequest.class);
            verify(sdk, times(3)).queryOrderByOutTradeNo(requests.capture());
            assertEquals(List.of(w1, w1, w2), requests.getAllValues().stream()
                    .map(QueryOrderByOutTradeNoRequest::getOutTradeNo).toList());
            verify(sdk).closeOrder(org.mockito.ArgumentMatchers.argThat(request -> w1.equals(request.getOutTradeNo())
                    && "merchant-clm".equals(request.getMchid())));
            org.mockito.Mockito.verifyNoMoreInteractions(sdk);
        }
    }

    private void stubActiveCallback(ClubMembershipInvoice invoice) {
        when(mapper.selectInvoiceByOutTradeNo(invoice.getOutTradeNo())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        Club club = activeClub();
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        ClubMember member = new ClubMember();
        member.setId(701L);
        member.setClubId(CLUB);
        member.setMemberId(MEMBER);
        member.setStatus(1);
        member.setDelFlag(0);
        when(clubMembers.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenReturn(member);
    }

    private void stubEndedCallback(ClubMembershipInvoice invoice) {
        when(mapper.selectInvoiceByOutTradeNo(invoice.getOutTradeNo())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub());
        when(clubMembers.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenReturn(null);
    }

    private Club activeClub() {
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private ClubMembershipActionRequest action(Long invoiceId, String requestId) {
        ClubMembershipActionRequest request = new ClubMembershipActionRequest();
        request.setClubId(CLUB);
        request.setId(invoiceId);
        request.setRequestId(requestId);
        return request;
    }

    private AtomicInteger trackLocalTransaction() {
        AtomicInteger depth = new AtomicInteger(0);
        Deque<TrackedTransactionFrame> frames = new ArrayDeque<>();
        when(transactions.getTransaction(any(org.springframework.transaction.TransactionDefinition.class)))
                .thenAnswer(call -> {
                    TransactionDefinition definition = call.getArgument(0, TransactionDefinition.class);
                    if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
                        if (depth.get() > 0) throw new org.springframework.transaction.IllegalTransactionStateException("outer transaction rejected");
                        frames.push(new TrackedTransactionFrame(false, 0));
                    } else if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                        int suspendedDepth = depth.getAndSet(1);
                        frames.push(new TrackedTransactionFrame(true, suspendedDepth));
                    } else if (definition.getPropagationBehavior()
                            == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
                        int suspendedDepth = depth.getAndSet(0);
                        frames.push(new TrackedTransactionFrame(false, suspendedDepth));
                    } else if (depth.get() > 0) {
                        frames.push(new TrackedTransactionFrame(false, 0));
                    } else {
                        depth.incrementAndGet();
                        frames.push(new TrackedTransactionFrame(true, 0));
                    }
                    return transactionStatus;
                });
        org.mockito.Mockito.doAnswer(call -> {
            completeTrackedFrame(depth, frames.pop());
            return null;
        }).when(transactions).commit(transactionStatus);
        org.mockito.Mockito.doAnswer(call -> {
            completeTrackedFrame(depth, frames.pop());
            return null;
        }).when(transactions).rollback(transactionStatus);
        return depth;
    }

    private void completeTrackedFrame(AtomicInteger depth, TrackedTransactionFrame frame) {
        if (frame.newTransaction) depth.decrementAndGet();
        if (frame.suspendedDepth > 0) depth.set(frame.suspendedDepth);
    }

    private IClubMembershipService transactionalService() {
        return transactionalService(transactions);
    }

    private IClubMembershipService transactionalService(PlatformTransactionManager manager) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        return (IClubMembershipService) proxyFactory.getProxy();
    }

    private TrackingTransactionManager useTrackingTransactions() {
        TrackingTransactionManager tracker = new TrackingTransactionManager();
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(service, "transactionManager", tracker);
        return tracker;
    }

    private <T> T recordExternalState(AtomicBoolean observedInside,
                                      AtomicInteger transactionDepth,
                                      T result) {
        if (transactionDepth.get() > 0) observedInside.set(true);
        return result;
    }

    private void stubResumedPrepay(ClubMembershipInvoice invoice,
                                   Map<String, String> params) {
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(pay.queryOrderStatus(invoice.getOutTradeNo())).thenReturn(wxOrder(invoice, "NOTPAY"));
        when(pay.createGenericOrder(eq(invoice.getOutTradeNo()),
                eq(new java.math.BigDecimal("99.00")), any(), eq("openid-71"))).thenReturn(params);
    }

    private Map<String, String> payParams(String packageValue) {
        Map<String, String> params = new HashMap<>();
        params.put("timeStamp", "1");
        params.put("nonceStr", "n");
        params.put("package", packageValue);
        params.put("signType", "RSA");
        params.put("paySign", "s");
        return params;
    }

    private static final class TrackingTransactionManager implements PlatformTransactionManager {
        private int activeDepth;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
                if (activeDepth > 0) throw new org.springframework.transaction.IllegalTransactionStateException("outer transaction rejected");
                return new TrackingTransactionStatus(false, 0);
            }
            if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                int suspendedDepth = activeDepth;
                activeDepth = 1;
                return new TrackingTransactionStatus(true, suspendedDepth);
            }
            if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
                int suspendedDepth = activeDepth;
                activeDepth = 0;
                return new TrackingTransactionStatus(false, suspendedDepth);
            }
            if (activeDepth > 0) return new TrackingTransactionStatus(false, 0);
            activeDepth = 1;
            return new TrackingTransactionStatus(true, 0);
        }

        @Override
        public void commit(TransactionStatus status) {
            complete((TrackingTransactionStatus) status);
        }

        @Override
        public void rollback(TransactionStatus status) {
            complete((TrackingTransactionStatus) status);
        }

        private void complete(TrackingTransactionStatus status) {
            if (status.isNewTransaction()) activeDepth = 0;
            if (status.suspendedDepth > 0) activeDepth = status.suspendedDepth;
        }

        private boolean isTransactionActive() {
            return activeDepth > 0;
        }
    }

    private static final class TrackedTransactionFrame {
        private final boolean newTransaction;
        private final int suspendedDepth;

        private TrackedTransactionFrame(boolean newTransaction, int suspendedDepth) {
            this.newTransaction = newTransaction;
            this.suspendedDepth = suspendedDepth;
        }
    }

    private static final class TrackingTransactionStatus extends SimpleTransactionStatus {
        private final int suspendedDepth;

        private TrackingTransactionStatus(boolean newTransaction, int suspendedDepth) {
            super(newTransaction);
            this.suspendedDepth = suspendedDepth;
        }
    }

    private BusinessAuditEvent resumeAuditReceipt(ClubMembershipInvoice invoice, String requestId) {
        BusinessAuditEvent receipt = new BusinessAuditEvent();
        receipt.setId(900L);
        receipt.setDomainType("CLUB");
        receipt.setDomainId(CLUB);
        receipt.setOwnerMemberId(OWNER);
        receipt.setActorMemberId(MEMBER);
        receipt.setActorRoleCode(ClubRoleCode.CLUB_MEMBER);
        receipt.setAction("CLUB_MEMBERSHIP_PREPAY_RESUMED");
        receipt.setTargetType("INVOICE");
        receipt.setTargetId(invoice.getId());
        receipt.setAfterJson("{\"status\":\"PAYING\",\"recoverable\":true}");
        receipt.setRequestId(requestId);
        return receipt;
    }

    private void flagOff() {
        org.mockito.Mockito.doThrow(new ServiceException("未开放", HttpStatus.NOT_FOUND))
                .when(feature).requireEnabled();
    }

    private ClubMembershipInvoice resumableInvoiceWithoutParams() {
        ClubMembershipInvoice invoice = validPayingInvoice();
        invoice.setPayParamsJson(null);
        invoice.setVersion(1);
        when(mapper.selectInvoiceById(invoice.getId())).thenReturn(invoice);
        when(mapper.selectInvoiceByOutTradeNoForUpdate(invoice.getOutTradeNo())).thenReturn(invoice);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setWxOpenId("openid-71");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        return invoice;
    }

    private Map<String, String> wxOrder(ClubMembershipInvoice invoice, String state) {
        Map<String, String> order = new HashMap<>();
        order.put("trade_state", state);
        order.put("out_trade_no", invoice.getOutTradeNo());
        order.put("wx_out_trade_no", invoice.getOutTradeNo());
        order.put("total", String.valueOf(invoice.getAmountFen()));
        return order;
    }

    private ClubMembershipInvoice validPayingInvoice() {
        ClubMembershipInvoice invoice = new ClubMembershipInvoice();
        invoice.setId(100L);
        invoice.setInvoiceNo("CLM01234567890123456789012345678");
        invoice.setOutTradeNo("CLM01234567890123456789012345678");
        invoice.setClubId(CLUB);
        invoice.setOwnerMemberIdSnapshot(OWNER);
        invoice.setMemberId(MEMBER);
        invoice.setPlanId(9L);
        invoice.setPlanVersionSnapshot(1);
        invoice.setPlanNameSnapshot("月度会员");
        invoice.setDurationDaysSnapshot(30);
        invoice.setAmountFen(9900L);
        invoice.setBenefitsSnapshot("[\"优先报名\"]");
        invoice.setGraceDaysSnapshot(3);
        invoice.setPastDueDaysSnapshot(30);
        invoice.setExitPolicySnapshot(ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY);
        invoice.setRefundPolicySnapshot(ClubMembershipPlan.POLICY_MANUAL_REVIEW);
        invoice.setStatus(ClubMembershipInvoice.STATUS_PAYING);
        invoice.setPayParamsJson("{\"timeStamp\":\"1\",\"nonceStr\":\"n\","
                + "\"package\":\"prepay_id=stored\",\"signType\":\"RSA\",\"paySign\":\"s\"}");
        invoice.setIdempotencyKey("idem-2");
        invoice.setRequestFingerprint("fp");
        invoice.setVersion(2);
        invoice.setSnapshotHash(ClubMembershipSnapshotCodec.hash(invoice));
        return invoice;
    }

    private ClubMembershipTerm termFor(ClubMembershipInvoice invoice) {
        ClubMembershipTerm term = new ClubMembershipTerm();
        term.setId(200L);
        term.setClubId(invoice.getClubId());
        term.setMemberId(invoice.getMemberId());
        term.setPlanId(invoice.getPlanId());
        term.setSourceInvoiceId(invoice.getId());
        term.setStatus(ClubMembershipTerm.STATUS_ACTIVE);
        term.setStartsAt(Date.from(Instant.parse("2026-08-01T16:00:00Z")));
        term.setEndsAt(Date.from(Instant.parse("2026-09-01T16:00:00Z")));
        term.setGraceEndsAt(Date.from(Instant.parse("2026-09-04T16:00:00Z")));
        term.setExpiresAt(Date.from(Instant.parse("2026-10-04T16:00:00Z")));
        term.setExitPolicySnapshot(invoice.getExitPolicySnapshot());
        term.setRefundPolicySnapshot(invoice.getRefundPolicySnapshot());
        term.setVersion(0);
        return term;
    }

    private ClubMembershipRenewRequest renew(long planId, String key) {
        ClubMembershipRenewRequest request = new ClubMembershipRenewRequest();
        request.setClubId(CLUB);
        request.setPlanId(planId);
        request.setIdempotencyKey(key);
        request.setRequestId("req-" + key);
        return request;
    }

    private ClubMembershipPlanRequest planRequest(long clubId) {
        ClubMembershipPlanRequest request = new ClubMembershipPlanRequest();
        request.setClubId(clubId);
        request.setName("年度会员");
        request.setDurationDays(365);
        request.setPriceFen(39900L);
        request.setBenefits(Arrays.asList("优先报名", "会员活动"));
        request.setGraceDays(7);
        request.setPastDueDays(30);
        request.setExitPolicy(ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY);
        request.setRefundPolicy(ClubMembershipPlan.POLICY_MANUAL_REVIEW);
        request.setStatus(ClubMembershipPlan.STATUS_ACTIVE);
        request.setRequestId("plan-cross-club");
        return request;
    }

    private ClubMembershipSetting enabledSetting() {
        ClubMembershipSetting setting = new ClubMembershipSetting();
        setting.setClubId(CLUB);
        setting.setEnabled(true);
        setting.setVersion(1);
        return setting;
    }

    private ClubMembershipPlan plan(long id, long clubId, int version, long priceFen) {
        ClubMembershipPlan plan = new ClubMembershipPlan();
        plan.setId(id);
        plan.setClubId(clubId);
        plan.setName("月度会员");
        plan.setDurationDays(30);
        plan.setPriceFen(priceFen);
        plan.setBenefitsJson("[\"优先报名\"]");
        plan.setGraceDays(3);
        plan.setPastDueDays(30);
        plan.setExitPolicy(ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY);
        plan.setRefundPolicy(ClubMembershipPlan.POLICY_MANUAL_REVIEW);
        plan.setStatus(ClubMembershipPlan.STATUS_ACTIVE);
        plan.setVersion(version);
        plan.setDelFlag(0);
        return plan;
    }

    private ClubAccessContext memberAccess() {
        return new ClubAccessContext(MEMBER, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_MEMBER));
    }

    private ClubAccessContext ownerAccess() {
        return new ClubAccessContext(OWNER, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER));
    }
    @Test
    void paymentNetworkEntriesRejectActualOuterTransactionBeforeIntentOrSdk() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.renew(MEMBER, null));
            assertThrows(IllegalStateException.class, () -> service.resumeInvoice(MEMBER, null));
            assertThrows(IllegalStateException.class, () -> service.closeInvoice(MEMBER, null));
            org.mockito.Mockito.verifyNoInteractions(feature, access, mapper, clubs, clubMembers, members, pay, audits, transactions);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void paymentNetworkEntriesNeverSuspendLocksAndLocalPhasesStayRequiresNew() throws Exception {
        for (java.lang.reflect.Method method : ClubMembershipServiceImpl.class.getMethods()) {
            if (java.util.Set.of("renew", "resumeInvoice", "closeInvoice").contains(method.getName())) {
                assertEquals(org.springframework.transaction.annotation.Propagation.NEVER,
                        method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation());
            }
        }
        org.springframework.transaction.support.TransactionTemplate transaction =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "transaction");
        assertNotNull(transaction);
        assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW, transaction.getPropagationBehavior());
    }

    private boolean paidInvoice(String no,boolean paid,Integer fen,String tx) {
        return service.handlePaidInvoice(no,paid,fen,tx,proof.issue(no,fen,tx));
    }

}
