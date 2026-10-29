package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.ClubMembershipPlan;
import com.chengyinhub.business.domain.ClubMembershipRefundReview;
import com.chengyinhub.business.domain.ClubMembershipSetting;
import com.chengyinhub.business.domain.ClubMembershipTerm;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ClubMembershipActionRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipPlanRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipRenewRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipSettingRequest;
import com.chengyinhub.business.domain.vo.ClubMembershipOverviewVO;
import com.chengyinhub.business.domain.vo.ClubMembershipRenewalVO;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubMembershipMapper;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ClubMembershipServiceImpl implements IClubMembershipService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ClubMembershipStatusPolicy STATUS_POLICY = new ClubMembershipStatusPolicy();
    private static final String PLAN_SAVE_ACTION = "CLUB_MEMBERSHIP_PLAN_SAVED";
    private static final String PREPAY_RESUME_ACTION = "CLUB_MEMBERSHIP_PREPAY_RESUMED";
    private static final String PREPAY_RESUME_AFTER_JSON =
            "{\"status\":\"PAYING\",\"recoverable\":true}";

    @Autowired private ClubMembershipFeatureGate featureGate;
    @Autowired private IClubAccessService clubAccessService;
    @Autowired private ClubMembershipMapper clubMembershipMapper;
    @Autowired private ClubMapper clubMapper;
    @Autowired private ClubMemberMapper clubMemberMapper;
    @Autowired private IUmsMemberService memberService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private WechatPaymentOperations wechatPayService;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WechatVerifiedPaymentFactService verifiedPaymentFacts;

    private Clock clock = Clock.system(SHANGHAI);

    @Override
    public ClubMembershipOverviewVO overview(Long principalMemberId, Long clubId) {
        ClubAccessContext access = requireMember(principalMemberId, clubId);
        ClubMembershipSetting setting = clubMembershipMapper.selectSetting(clubId);
        boolean enabled = setting != null && Boolean.TRUE.equals(setting.getEnabled());
        ClubMembershipOverviewVO result = new ClubMembershipOverviewVO();
        result.setClubMembershipEnabled(enabled);
        result.setAutoRenewAvailable(false);
        result.setMembershipType("FREE");
        result.setEffectiveStatus("FREE");
        result.setHasEntitlement(false);
        result.setPlans(enabled ? safe(clubMembershipMapper.selectPlans(clubId, true))
                : Collections.<ClubMembershipPlan>emptyList());
        List<ClubMembershipTerm> terms = safe(clubMembershipMapper.selectTermsByMember(clubId, principalMemberId));
        Instant now = clock.instant();
        ClubMembershipTerm selected = null;
        for (ClubMembershipTerm term : terms) {
            validateOwnedTerm(term, access.getClubId(), principalMemberId);
            String effective = STATUS_POLICY.evaluate(term, now);
            if (selected == null || STATUS_POLICY.hasEntitlement(term, now)) {
                term.setStatus(effective);
                selected = term;
            }
            if (STATUS_POLICY.hasEntitlement(term, now)) break;
        }
        if (selected != null) {
            result.setMembershipType("PAID");
            result.setEffectiveStatus(selected.getStatus());
            result.setHasEntitlement(STATUS_POLICY.hasEntitlement(selected, now));
            result.setTerm(selected);
        }
        result.setRefundReviews(safe(clubMembershipMapper.selectRefundReviewsByMember(clubId, principalMemberId)));
        List<ClubMembershipInvoice> invoices = safe(clubMembershipMapper.selectInvoicesByMember(clubId, principalMemberId));
        for (ClubMembershipInvoice invoice : invoices) {
            validateOwnedInvoice(invoice, clubId, principalMemberId);
            invoice.setPayParamsJson(null);
            invoice.setIdempotencyKey(null);
            invoice.setRequestFingerprint(null);
            invoice.setSnapshotHash(null);
        }
        result.setInvoices(invoices);
        return result;
    }

    @Override
    public ClubMembershipSetting setting(Long principalMemberId, Long clubId) {
        requireWrite(principalMemberId, clubId);
        ClubMembershipSetting row = clubMembershipMapper.selectSetting(clubId);
        if (row != null) return row;
        ClubMembershipSetting disabled = new ClubMembershipSetting();
        disabled.setClubId(clubId);
        disabled.setEnabled(false);
        disabled.setVersion(0);
        return disabled;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubMembershipSetting updateSetting(Long principalMemberId, ClubMembershipSettingRequest request) {
        requireRequest(request, request == null ? null : request.getClubId());
        ClubAccessContext access = requireLockedOwnerWrite(principalMemberId, request.getClubId());
        if (request.getEnabled() == null || invalidRequestId(request.getRequestId())) {
            bad("开关或 requestId 不合法");
        }
        ClubMembershipSetting current = clubMembershipMapper.selectSetting(request.getClubId());
        Date now = now();
        if (current == null) {
            if (request.getVersion() != null && request.getVersion() != 0) conflict();
            current = new ClubMembershipSetting();
            current.setClubId(request.getClubId());
            current.setEnabled(request.getEnabled());
            current.setVersion(0);
            current.setCreateBy(String.valueOf(principalMemberId));
            current.setUpdateBy(String.valueOf(principalMemberId));
            current.setCreateTime(now);
            current.setUpdateTime(now);
            try {
                if (clubMembershipMapper.insertSetting(current) != 1) conflict();
            } catch (DuplicateKeyException duplicate) {
                conflict();
            }
        } else {
            int expected = request.getVersion() == null ? -1 : request.getVersion();
            if (current.getVersion() == null || current.getVersion() != expected
                    || clubMembershipMapper.updateSettingCas(request.getClubId(), request.getEnabled(),
                    expected, String.valueOf(principalMemberId), now) != 1) conflict();
            current.setEnabled(request.getEnabled());
            current.setVersion(expected + 1);
            current.setUpdateTime(now);
        }
        audit(access, "CLUB_MEMBERSHIP_SETTING_UPDATED", "SETTING", request.getClubId(), null,
                "{\"enabled\":" + request.getEnabled() + "}", request.getRequestId(), null);
        return current;
    }

    @Override
    public List<ClubMembershipPlan> plans(Long principalMemberId, Long clubId, boolean manage) {
        if (manage) requireWrite(principalMemberId, clubId); else requireMember(principalMemberId, clubId);
        return safe(clubMembershipMapper.selectPlans(clubId, !manage));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubMembershipPlan savePlan(Long principalMemberId, ClubMembershipPlanRequest request) {
        requireRequest(request, request == null ? null : request.getClubId());
        ClubAccessContext access = requireLockedOwnerWrite(principalMemberId, request.getClubId());
        validatePlanRequest(request);
        ClubMembershipPlan plan = toPlan(request, principalMemberId);
        Date now = now();
        plan.setUpdateBy(String.valueOf(principalMemberId));
        plan.setUpdateTime(now);
        boolean create = request.getId() == null;
        if (!create && request.getVersion() == null) conflict();
        String fingerprint = create
                ? planCreateFingerprint(plan, principalMemberId)
                : planUpdateFingerprint(plan, principalMemberId, request.getVersion());
        BusinessAuditEvent receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", request.getClubId(), PLAN_SAVE_ACTION, request.getRequestId().trim());
        if (receipt != null) {
            return create
                    ? replayCreatedPlan(receipt, access, request.getRequestId().trim(), fingerprint)
                    : replayUpdatedPlan(receipt, access, request.getRequestId().trim(),
                    request.getId(), request.getVersion(), fingerprint);
        }
        if (request.getId() == null) {
            plan.setCreatedByMemberId(principalMemberId);
            plan.setCreateBy(String.valueOf(principalMemberId));
            plan.setCreateTime(now);
            plan.setVersion(0);
            if (clubMembershipMapper.insertPlan(plan) != 1) conflict();
        } else {
            ClubMembershipPlan current = clubMembershipMapper.selectPlanByIdForUpdate(request.getId());
            if (current == null || !request.getClubId().equals(current.getClubId())) forbidden();
            if (request.getVersion() == null || clubMembershipMapper.updatePlanCas(plan) != 1) conflict();
            plan.setVersion(request.getVersion() + 1);
        }
        audit(access, PLAN_SAVE_ACTION, "PLAN", plan.getId(), plan.getId(),
                create ? planCreateReceiptJson(fingerprint)
                        : planUpdateReceiptJson(fingerprint, request.getVersion()),
                request.getRequestId(), null);
        return plan;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public ClubMembershipRenewalVO renew(Long principalMemberId, ClubMembershipRenewRequest request) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requirePaymentOutsideTransaction();
        requireRequest(request, request == null ? null : request.getClubId());
        requireMember(principalMemberId, request.getClubId());
        if (request.getPlanId() == null || request.getPlanId() <= 0
                || invalidIdempotencyKey(request.getIdempotencyKey())
                || invalidRequestId(request.getRequestId())) bad("续费参数不完整");
        // 跨微信网络调用不能和本地 intent 共用一个未提交事务：先短事务提交 PAYING intent，
        // 再调微信，最后用第二个短事务附着参数。JVM 在任一点退出，overview 仍能读到原 invoice。
        RenewalIntent intent = transaction().execute(status -> prepareRenewalIntent(
                principalMemberId, request));
        if (intent == null || intent.invoice == null) conflict();
        ClubMembershipInvoice invoice = intent.invoice;
        ClubMembershipRenewalVO result = renewal(invoice);
        if (!intent.needsPrepay) return result;
        Map<String, String> payParams;
        String payJson;
        try {
            payParams = wechatPayService.createGenericOrder(invoice.getOutTradeNo(),
                    BigDecimal.valueOf(invoice.getAmountFen(), 2),
                    "俱乐部会费-" + invoice.getPlanNameSnapshot(), intent.wxOpenId);
            requirePaymentParams(payParams);
            payJson = JSON.writeValueAsString(payParams);
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception uncertain) {
            result.setPayParams(Collections.<String, String>emptyMap());
            result.setPaymentState("PREPAY_UNKNOWN");
            return result;
        }
        try {
            ClubMembershipInvoice attached = transaction().execute(status -> attachPrepayParams(invoice, payJson));
            if (attached != null && (ClubMembershipInvoice.STATUS_PAID.equals(attached.getStatus())
                    || ClubMembershipInvoice.STATUS_CLOSED.equals(attached.getStatus())
                    || !blank(attached.getPayParamsJson()))) return renewal(attached);
        } catch (ServiceException stateConflict) {
            throw stateConflict;
        } catch (RuntimeException persistenceUnknown) {
            // 微信已受理且 PAYING intent 已独立提交；不能回滚/隐藏外部事实。
            // 当前响应仍可使用刚取得的参数，之后 overview/回调/显式关单都能定位同一 invoice。
        }
        result.setPayParams(new LinkedHashMap<>(payParams));
        result.setPaymentState(ClubMembershipInvoice.STATUS_PAYING);
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public ClubMembershipRenewalVO resumeInvoice(Long principalMemberId, ClubMembershipActionRequest request) {
        requirePaymentOutsideTransaction();
        requireAction(request);
        InvoiceActionIntent intent = transaction().execute(status ->
                prepareResumeIntent(principalMemberId, request));
        if (intent == null || intent.invoice == null) conflict();
        ClubMembershipInvoice invoice = intent.invoice;
        ClubMembershipRenewalVO result = renewal(invoice);
        if (ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())
                || ClubMembershipInvoice.STATUS_CLOSED.equals(invoice.getStatus())) return result;
        ClubMembershipRenewalVO reconciled = reconcileWechatInvoice(
                invoice, intent.access, intent.requestId, false);
        if (reconciled != null) return reconciled;
        // 已取得的预支付参数只能复用，不能为同一 outTradeNo 再生成另一组本地状态；
        // 每次恢复仍先查微信真相，微信明确 CLOSED 时会释放本地 one-PAYING 约束。
        if (!blank(invoice.getPayParamsJson())) return result;
        // D-12：会费开关关闭时只拦新的微信预下单；上面的查单对账仍照常执行。
        featureGate.requireEnabled();
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        Map<String, String> payParams;
        String payJson;
        try {
            payParams = wechatPayService.createGenericOrder(invoice.getOutTradeNo(),
                    BigDecimal.valueOf(invoice.getAmountFen(), 2),
                    "俱乐部会费-" + invoice.getPlanNameSnapshot(), intent.wxOpenId);
            requirePaymentParams(payParams);
            payJson = JSON.writeValueAsString(payParams);
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception uncertain) {
            result.setPayParams(Collections.<String, String>emptyMap());
            result.setPaymentState("PREPAY_UNKNOWN");
            return result;
        }
        try {
            ClubMembershipInvoice attached = transaction().execute(status ->
                    attachPrepayParams(invoice, payJson));
            if (attached != null && (ClubMembershipInvoice.STATUS_PAID.equals(attached.getStatus())
                    || ClubMembershipInvoice.STATUS_CLOSED.equals(attached.getStatus())
                    || !blank(attached.getPayParamsJson()))) return renewal(attached);
        } catch (ServiceException stateConflict) {
            throw stateConflict;
        } catch (RuntimeException persistenceUnknown) {
            // 微信已受理；prepare 短事务中的 PAYING 账单与审计回执已经提交。
            // 即使附着参数的本地提交结果未知，仍返回本次微信参数并保留同一账单供恢复。
        }
        result.setPayParams(new LinkedHashMap<>(payParams));
        result.setPaymentState(ClubMembershipInvoice.STATUS_PAYING);
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public ClubMembershipRenewalVO closeInvoice(Long principalMemberId, ClubMembershipActionRequest request) {
        requirePaymentOutsideTransaction();
        requireAction(request);
        InvoiceActionIntent intent = transaction().execute(status ->
                prepareCloseIntent(principalMemberId, request));
        if (intent == null || intent.invoice == null) conflict();
        ClubMembershipInvoice invoice = intent.invoice;
        if (ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())
                || ClubMembershipInvoice.STATUS_CLOSED.equals(invoice.getStatus())) return renewal(invoice);

        Map<String, String> closingObservation;
        try { closingObservation = wechatPayService.queryOrderStatus(invoice.getOutTradeNo()); }
        catch (Exception unavailable) {
            throw new ServiceException("微信支付状态暂不可确认，原账单继续保留", HttpStatus.CONFLICT);
        }
        ClubMembershipRenewalVO observed = applyWechatInvoiceObservation(
                invoice, intent.access, intent.requestId, true, closingObservation);
        if (observed != null) return observed;
        boolean closeAccepted;
        try {
            closeAccepted = wechatPayService.closePaymentAttempt(invoice.getOutTradeNo(), closingObservation.get("wx_out_trade_no"));
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception closeUnknown) {
            throw new ServiceException("微信关单结果未确认，原账单继续保留", HttpStatus.CONFLICT);
        }
        if (!closeAccepted) {
            throw new ServiceException("微信未确认关单，原账单继续保留", HttpStatus.CONFLICT);
        }
        ClubMembershipRenewalVO readback = reconcileWechatInvoice(
                invoice, intent.access, intent.requestId, true);
        if (readback != null) return readback;
        throw new ServiceException("微信关单终态尚未读回，原账单继续保留", HttpStatus.CONFLICT);
    }

    @Override
    public ClubMembershipInvoice invoice(Long principalMemberId, Long clubId, Long invoiceId) {
        // D-12：开关关闭时既有账单仍可查询，只做成员与归属校验。
        resolveMember(principalMemberId, clubId);
        ClubMembershipInvoice row = clubMembershipMapper.selectInvoiceById(invoiceId);
        validateOwnedInvoice(row, clubId, principalMemberId);
        row.setIdempotencyKey(null);
        row.setRequestFingerprint(null);
        row.setSnapshotHash(null);
        return row;
    }

    @Override
    public List<ClubMembershipInvoice> financeInvoices(Long principalMemberId, Long clubId) {
        requireFinance(principalMemberId, clubId);
        List<ClubMembershipInvoice> rows = safe(clubMembershipMapper.selectInvoicesForFinance(clubId));
        for (ClubMembershipInvoice row : rows) {
            row.setPayParamsJson(null);
            row.setIdempotencyKey(null);
            row.setRequestFingerprint(null);
            row.setSnapshotHash(null);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTerm(Long principalMemberId, ClubMembershipActionRequest request) {
        requireAction(request);
        ClubAccessContext access = requireMember(principalMemberId, request.getClubId());
        ClubMembershipTerm term = clubMembershipMapper.selectTermById(request.getId());
        validateOwnedTerm(term, request.getClubId(), principalMemberId);
        if (request.getVersion() == null || term.getVersion() == null
                || !request.getVersion().equals(term.getVersion())) conflict();
        if (ClubMembershipTerm.STATUS_CANCELLED.equals(term.getStatus())) return;
        if (ClubMembershipTerm.STATUS_REFUNDED.equals(term.getStatus())) conflict();
        if (clubMembershipMapper.updateTermStatusCas(term.getId(), term.getClubId(), term.getMemberId(),
                term.getVersion(), ClubMembershipTerm.STATUS_CANCELLED, now()) != 1) conflict();
        audit(access, "CLUB_MEMBERSHIP_TERM_CANCELLED", "TERM", term.getId(), null,
                "{\"status\":\"CANCELLED\",\"refund\":false}", request.getRequestId(), request.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubMembershipRefundReview requestRefundReview(Long principalMemberId,
                                                           ClubMembershipActionRequest request) {
        requireAction(request);
        ClubAccessContext access = requireMember(principalMemberId, request.getClubId());
        ClubMembershipInvoice invoice = clubMembershipMapper.selectInvoiceById(request.getId());
        validateOwnedInvoice(invoice, request.getClubId(), principalMemberId);
        if (!ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())) {
            throw new ServiceException("只有已支付账单可申请退款审核", HttpStatus.CONFLICT);
        }
        if (ClubMembershipPlan.POLICY_NON_REFUNDABLE.equals(invoice.getRefundPolicySnapshot())) {
            throw new ServiceException("该账单冻结政策为不可退款", HttpStatus.CONFLICT);
        }
        if (!ClubMembershipPlan.POLICY_MANUAL_REVIEW.equals(invoice.getRefundPolicySnapshot())) {
            throw new ServiceException("账单退款政策无效", HttpStatus.CONFLICT);
        }
        if (blank(request.getReason()) || request.getReason().trim().length() > 500) bad("请填写退款原因（500字内）");
        ClubMembershipRefundReview existing = clubMembershipMapper.selectRefundReviewByInvoice(invoice.getId());
        if (existing != null) return existing;
        ClubMembershipRefundReview review = new ClubMembershipRefundReview();
        review.setClubId(invoice.getClubId());
        review.setMemberId(invoice.getMemberId());
        review.setInvoiceId(invoice.getId());
        review.setStatus(ClubMembershipRefundReview.STATUS_PENDING_REVIEW);
        review.setReason(request.getReason().trim());
        review.setRequestId(request.getRequestId().trim());
        review.setVersion(0);
        review.setCreateBy(String.valueOf(principalMemberId));
        review.setUpdateBy(String.valueOf(principalMemberId));
        review.setCreateTime(now());
        review.setUpdateTime(review.getCreateTime());
        try {
            if (clubMembershipMapper.insertRefundReview(review) != 1) conflict();
        } catch (DuplicateKeyException duplicate) {
            existing = clubMembershipMapper.selectRefundReviewByInvoice(invoice.getId());
            if (existing == null) conflict();
            return existing;
        }
        audit(access, "CLUB_MEMBERSHIP_REFUND_REVIEW_REQUESTED", "INVOICE", invoice.getId(),
                review.getId(), "{\"status\":\"PENDING_REVIEW\",\"payout\":false}",
                request.getRequestId(), request.getReason());
        return review;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubMembershipAccessEndResultVO handleMemberAccessEnded(Long clubId, Long memberId,
                                                                    String eventType, String requestId) {
        requireAccessEndRequest(clubId, memberId, eventType, requestId);
        if ("CLUB_DISSOLVED".equals(eventType)) bad("成员级会费收尾事件不合法");
        Date endedAt = now();
        ClubMembershipAccessEndResultVO result = blockPayingInvoices(
                clubMembershipMapper.selectPayingInvoicesByMemberForUpdate(clubId, memberId),
                clubId, memberId, eventType, requestId, endedAt);
        return endAccessTerms(clubMembershipMapper.selectEntitledTermsByMemberForUpdate(
                clubId, memberId, endedAt), eventType, requestId, endedAt, result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubMembershipAccessEndResultVO handleClubAccessEnded(Long clubId, String eventType,
                                                                  String requestId) {
        requireAccessEndRequest(clubId, null, eventType, requestId);
        if (!"CLUB_DISSOLVED".equals(eventType)) bad("俱乐部级会费收尾事件不合法");
        Date endedAt = now();
        ClubMembershipAccessEndResultVO result = blockPayingInvoices(
                clubMembershipMapper.selectPayingInvoicesByClubForUpdate(clubId),
                clubId, null, eventType, requestId, endedAt);
        return endAccessTerms(clubMembershipMapper.selectEntitledTermsByClubForUpdate(
                clubId, endedAt), eventType, requestId, endedAt, result);
    }

    @Override
    public boolean supportsOutTradeNo(String outTradeNo) {
        return outTradeNo != null && outTradeNo.matches("^CLM[0-9a-f]{29}$");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaidInvoice(String outTradeNo, boolean paid, Integer amountFen, String transactionId,
            Reference reference) {
        if (reference == null) throw new IllegalArgumentException("原成功支付事实引用必填");
        if (!supportsOutTradeNo(outTradeNo) || !paid || amountFen == null || amountFen <= 0
                || blank(transactionId) || transactionId.trim().length() > 64) return false;
        WechatVerifiedPaymentFactService.Stored verified = checkedPayment(reference, outTradeNo, amountFen, transactionId);
        // REQUIRED joins original callback/DONE; programmatic boundary also covers self/direct calls.
        var application = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return Boolean.TRUE.equals(application.execute(status -> applyVerifiedPayment(
                outTradeNo, paid, amountFen, transactionId, verified)));
    }

    private boolean applyVerifiedPayment(String outTradeNo, boolean paid, Integer amountFen, String transactionId,
            WechatVerifiedPaymentFactService.Stored verified) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!supportsOutTradeNo(outTradeNo) || !paid || amountFen == null || amountFen <= 0
                || blank(transactionId) || transactionId.trim().length() > 64) return false;
        // 先做不加锁的不可变快照定位，只用来发现锁键；真正状态决策在统一锁序下重读。
        ClubMembershipInvoice preview = clubMembershipMapper.selectInvoiceByOutTradeNo(outTradeNo);
        if (!validCallbackSnapshot(preview, outTradeNo)
                || !Long.valueOf(amountFen.longValue()).equals(preview.getAmountFen())) {
            return false;
        }
        String tx = transactionId.trim();
        if (!ClubMembershipInvoice.STATUS_CREATED.equals(preview.getStatus())
                && !ClubMembershipInvoice.STATUS_PAYING.equals(preview.getStatus())
                && !ClubMembershipInvoice.STATUS_PAID.equals(preview.getStatus())) return false;

        // 与退出/移除保持 club -> club_member -> invoice 的锁序。任一方先拿到 club 锁，
        // 另一方都会在其完整事务提交后看到终态，不能出现“离会已完成却又补发 ACTIVE 权益”。
        Club lockedClub = clubMapper.selectClubByIdForUpdate(preview.getClubId());
        boolean clubActive = lockedClub != null && Long.valueOf(1L).equals(lockedClub.getStatus())
                && Integer.valueOf(0).equals(lockedClub.getDelFlag());
        boolean ownerStillActive = clubActive && preview.getMemberId().equals(lockedClub.getMemberId());
        ClubMember lockedMember = clubActive && !ownerStillActive
                ? clubMemberMapper.selectByClubAndMemberForUpdate(preview.getClubId(), preview.getMemberId()) : null;
        boolean accessStillActive = ownerStillActive || (lockedMember != null
                && Integer.valueOf(1).equals(lockedMember.getStatus())
                && Integer.valueOf(0).equals(lockedMember.getDelFlag()));

        ClubMembershipInvoice invoice = clubMembershipMapper.selectInvoiceByOutTradeNoForUpdate(outTradeNo);
        if (!sameCallbackInvoice(preview, invoice) || !validCallbackSnapshot(invoice, outTradeNo)
                || !Long.valueOf(amountFen.longValue()).equals(invoice.getAmountFen())) return false;
        if (ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())) {
            return completeInvoiceProof(invoice, verified);
        }
        if (!ClubMembershipInvoice.STATUS_CREATED.equals(invoice.getStatus())
                && !ClubMembershipInvoice.STATUS_PAYING.equals(invoice.getStatus())) return false;
        ClubMembershipInvoice transactionOwner = clubMembershipMapper.selectInvoiceByTransactionId(tx);
        if (transactionOwner != null && !invoice.getId().equals(transactionOwner.getId())) return false;
        if (invoice.getVerifiedPaymentFactId() != null || invoice.getVerifiedPaymentFactHash() != null) return false;
        if (!currentInvoicePayment(verified)) return false;
        Date paidAt = now();
        if (invoice.getVersion() == null || clubMembershipMapper.markInvoicePaidCas(
                invoice.getId(), invoice.getVersion(), tx, paidAt) != 1) return false;
        ClubMembershipTerm latest = accessStillActive
                ? clubMembershipMapper.selectLatestTermForUpdate(invoice.getClubId(), invoice.getMemberId()) : null;
        Instant start = paidAt.toInstant();
        if (latest != null && latest.getEndsAt() != null
                && !ClubMembershipTerm.STATUS_CANCELLED.equals(latest.getStatus())
                && !ClubMembershipTerm.STATUS_REFUNDED.equals(latest.getStatus())
                && latest.getEndsAt().toInstant().isAfter(start)) {
            start = latest.getEndsAt().toInstant();
        }
        ClubMembershipTerm term = createTerm(invoice, start, paidAt.toInstant());
        if (!accessStillActive) term.setStatus(ClubMembershipTerm.STATUS_CANCELLED);
        if (clubMembershipMapper.insertTerm(term) != 1) {
            throw new ServiceException("会员权益期创建失败", HttpStatus.ERROR);
        }
        ClubMembershipRefundReview review = null;
        if (!accessStillActive) {
            if (ClubMembershipPlan.POLICY_MANUAL_REVIEW.equals(invoice.getRefundPolicySnapshot())
                    || ClubMembershipPlan.POLICY_NON_REFUNDABLE.equals(invoice.getRefundPolicySnapshot())) {
                review = ensurePendingReview(invoice, "ACCESS_ENDED_BEFORE_PAYMENT_CALLBACK",
                        accessEndRequestId(outTradeNo, invoice.getId(), "late-payment-review"), paidAt);
            } else {
                throw new ServiceException("会员退款政策快照无效，未静默处理资金", HttpStatus.CONFLICT);
            }
        }
        ClubAccessContext callbackAccess = new ClubAccessContext(invoice.getMemberId(), invoice.getClubId(),
                invoice.getOwnerMemberIdSnapshot(), null, Collections.<String>emptySet());
        audit(callbackAccess, "CLUB_MEMBERSHIP_INVOICE_PAID", "INVOICE", invoice.getId(), term.getId(),
                "{\"amountFen\":" + invoice.getAmountFen() + ",\"termStatus\":\""
                        + term.getStatus() + "\",\"refundState\":\""
                        + (review == null ? "NONE" : "PENDING_REVIEW")
                        + "\",\"fundState\":\""
                        + (accessStillActive ? "PAID_ENTITLEMENT_CREATED" : "PAID_NO_ENTITLEMENT")
                        + "\",\"payout\":false}", "pay-" + invoice.getOutTradeNo(), null);
        if (clubMembershipMapper.bindInvoicePaymentProof(invoice.getId(), tx, invoice.getAmountFen(), invoice.getSnapshotHash(),
                verified.reference().id(), verified.reference().hash()) != 1)
            throw new IllegalStateException("会费原支付消费凭据绑定失败");
        ClubMembershipInvoice readback = clubMembershipMapper.selectInvoiceByOutTradeNoForUpdate(outTradeNo);
        if (!sameCallbackInvoice(invoice, readback) || !completeInvoiceProof(readback, verified))
            throw new IllegalStateException("会费原支付消费凭据回读不一致");
        return true;
    }

    private ClubAccessContext requireMember(Long principalMemberId, Long clubId) {
        featureGate.requireEnabled();
        return resolveMember(principalMemberId, clubId);
    }

    private ClubAccessContext resolveMember(Long principalMemberId, Long clubId) {
        if (!positive(principalMemberId) || !positive(clubId)) forbidden();
        ClubAccessContext access = clubAccessService.resolve(principalMemberId, clubId, null);
        if (access == null || !principalMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId())) forbidden();
        return access;
    }

    private ClubAccessContext requireWrite(Long principalMemberId, Long clubId) {
        featureGate.requireEnabled();
        return clubAccessService.require(principalMemberId, clubId, null, ClubPermission.CLUB_WRITE);
    }

    /**
     * 会费政策写与建会/配额统一使用 principal ums_member -> club 锁序；拿到 club 锁后
     * 再走一次 fresh access。ClubAccessService 只会重锁同一 principal，不引入新锁序。
     */
    private ClubAccessContext requireLockedOwnerWrite(Long principalMemberId, Long clubId) {
        featureGate.requireEnabled();
        if (!positive(principalMemberId) || !positive(clubId)) forbidden();
        if (!principalMemberId.equals(clubMembershipMapper.lockMemberForRenewal(principalMemberId))) {
            forbidden();
        }
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        if (locked == null || !clubId.equals(locked.getId())
                || !Long.valueOf(1L).equals(locked.getStatus())
                || !Integer.valueOf(0).equals(locked.getDelFlag())) forbidden();
        ClubAccessContext access = clubAccessService.require(
                principalMemberId, clubId, null, ClubPermission.CLUB_WRITE);
        if (!principalMemberId.equals(locked.getMemberId())
                || access == null || !principalMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId())
                || !principalMemberId.equals(access.getOwnerMemberId())
                || !access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER)) forbidden();
        return access;
    }

    /** D-12：财务对账读不受会费开关影响，开关关闭后既有账单仍需可对账。 */
    private ClubAccessContext requireFinance(Long principalMemberId, Long clubId) {
        return clubAccessService.require(principalMemberId, clubId, null, ClubPermission.FINANCE_READ);
    }

    private ClubMembershipPlan toPlan(ClubMembershipPlanRequest request, Long actor) {
        ClubMembershipPlan plan = new ClubMembershipPlan();
        plan.setId(request.getId());
        plan.setClubId(request.getClubId());
        plan.setName(request.getName().trim());
        plan.setDurationDays(request.getDurationDays());
        plan.setPriceFen(request.getPriceFen());
        try { plan.setBenefitsJson(JSON.writeValueAsString(normalizeBenefits(request.getBenefits()))); }
        catch (Exception impossible) { throw new ServiceException("权益内容无法保存", HttpStatus.BAD_REQUEST); }
        plan.setGraceDays(request.getGraceDays());
        plan.setPastDueDays(request.getPastDueDays());
        plan.setExitPolicy(request.getExitPolicy());
        plan.setRefundPolicy(request.getRefundPolicy());
        plan.setStatus(request.getStatus());
        plan.setVersion(request.getVersion());
        plan.setCreatedByMemberId(actor);
        plan.setDelFlag(0);
        return plan;
    }

    private void validatePlanRequest(ClubMembershipPlanRequest request) {
        if (invalidRequestId(request.getRequestId()) || blank(request.getName()) || request.getName().trim().length() > 40
                || request.getDurationDays() == null || request.getDurationDays() < 1 || request.getDurationDays() > 3650
                || request.getPriceFen() == null || request.getPriceFen() < 1 || request.getPriceFen() > 100000000L
                || request.getGraceDays() == null || request.getGraceDays() < 0 || request.getGraceDays() > 30
                || request.getPastDueDays() == null || request.getPastDueDays() < 0 || request.getPastDueDays() > 90
                || !ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY.equals(request.getExitPolicy())
                || (!ClubMembershipPlan.POLICY_NON_REFUNDABLE.equals(request.getRefundPolicy())
                && !ClubMembershipPlan.POLICY_MANUAL_REVIEW.equals(request.getRefundPolicy()))
                || (!ClubMembershipPlan.STATUS_ACTIVE.equals(request.getStatus())
                && !ClubMembershipPlan.STATUS_INACTIVE.equals(request.getStatus()))) {
            bad("会费计划参数不合法");
        }
        normalizeBenefits(request.getBenefits());
    }

    private List<String> normalizeBenefits(List<String> benefits) {
        if (benefits == null || benefits.isEmpty() || benefits.size() > 12) bad("权益需填写 1-12 项");
        List<String> normalized = new ArrayList<>();
        for (String benefit : benefits) {
            if (blank(benefit) || benefit.trim().length() > 80) bad("单项权益需为 1-80 字");
            normalized.add(benefit.trim());
        }
        return normalized;
    }

    private void validatePurchasablePlan(ClubMembershipPlan plan, Long clubId) {
        if (plan == null || !clubId.equals(plan.getClubId()) || !ClubMembershipPlan.STATUS_ACTIVE.equals(plan.getStatus())
                || plan.getVersion() == null || plan.getDurationDays() == null || plan.getDurationDays() < 1
                || plan.getPriceFen() == null || plan.getPriceFen() < 1 || blank(plan.getBenefitsJson())
                || plan.getGraceDays() == null || plan.getPastDueDays() == null
                || !ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY.equals(plan.getExitPolicy())
                || (!ClubMembershipPlan.POLICY_NON_REFUNDABLE.equals(plan.getRefundPolicy())
                && !ClubMembershipPlan.POLICY_MANUAL_REVIEW.equals(plan.getRefundPolicy()))) {
            throw new ServiceException("会费计划不可购买", HttpStatus.CONFLICT);
        }
    }

    private String planCreateFingerprint(ClubMembershipPlan plan, Long actorMemberId) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(
                "CLUB_MEMBERSHIP_PLAN_CREATE_V1", String.valueOf(plan.getClubId()),
                String.valueOf(actorMemberId), plan.getName(), String.valueOf(plan.getDurationDays()),
                String.valueOf(plan.getPriceFen()), plan.getBenefitsJson(), String.valueOf(plan.getGraceDays()),
                String.valueOf(plan.getPastDueDays()), plan.getExitPolicy(), plan.getRefundPolicy(),
                plan.getStatus()));
    }

    private String planUpdateFingerprint(ClubMembershipPlan plan, Long actorMemberId,
                                         Integer expectedVersion) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(
                "CLUB_MEMBERSHIP_PLAN_UPDATE_V1", String.valueOf(plan.getClubId()),
                String.valueOf(actorMemberId), String.valueOf(plan.getId()), String.valueOf(expectedVersion),
                plan.getName(), String.valueOf(plan.getDurationDays()), String.valueOf(plan.getPriceFen()),
                plan.getBenefitsJson(), String.valueOf(plan.getGraceDays()),
                String.valueOf(plan.getPastDueDays()), plan.getExitPolicy(), plan.getRefundPolicy(),
                plan.getStatus()));
    }

    private String planCreateReceiptJson(String fingerprint) {
        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("fingerprint", fingerprint);
        try {
            return JSON.writeValueAsString(receipt);
        } catch (Exception impossible) {
            throw new ServiceException("会费计划回执无法生成", HttpStatus.ERROR);
        }
    }

    private String planUpdateReceiptJson(String fingerprint, Integer expectedVersion) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("fingerprint", fingerprint);
        receipt.put("expectedVersion", expectedVersion);
        try {
            return JSON.writeValueAsString(receipt);
        } catch (Exception impossible) {
            throw new ServiceException("会费计划回执无法生成", HttpStatus.ERROR);
        }
    }

    private ClubMembershipPlan replayCreatedPlan(BusinessAuditEvent receipt,
                                                   ClubAccessContext access,
                                                   String requestId,
                                                   String fingerprint) {
        String actorRoleCode = access.getPrimaryRoleCode();
        boolean exactEnvelope = "CLUB".equals(receipt.getDomainType())
                && Objects.equals(access.getClubId(), receipt.getDomainId())
                && PLAN_SAVE_ACTION.equals(receipt.getAction())
                && Objects.equals(requestId, receipt.getRequestId())
                && Objects.equals(access.getOwnerMemberId(), receipt.getOwnerMemberId())
                && Objects.equals(access.getPrincipalMemberId(), receipt.getActorMemberId())
                && Objects.equals(actorRoleCode, receipt.getActorRoleCode())
                && "PLAN".equals(receipt.getTargetType())
                && positive(receipt.getTargetId())
                && Objects.equals(receipt.getTargetId(), receipt.getRelationId())
                && receipt.getReason() == null
                && exactPlanCreateFingerprint(receipt.getAfterJson(), fingerprint);
        if (!exactEnvelope) {
            throw new ServiceException("requestId 已用于其他会费计划请求", HttpStatus.CONFLICT);
        }
        ClubMembershipPlan replay = clubMembershipMapper.selectPlanByIdForUpdate(receipt.getRelationId());
        if (replay == null || !Objects.equals(receipt.getRelationId(), replay.getId())
                || !Objects.equals(access.getClubId(), replay.getClubId())
                || !Objects.equals(access.getPrincipalMemberId(), replay.getCreatedByMemberId())
                || replay.getVersion() == null || !Integer.valueOf(0).equals(replay.getDelFlag())) {
            throw new ServiceException("会费计划回执与领域记录不一致", HttpStatus.CONFLICT);
        }
        if (!fingerprint.equals(planCreateFingerprint(
                replay, access.getPrincipalMemberId()))) {
            throw new ServiceException("会费计划已在原请求后发生变化，请刷新", HttpStatus.CONFLICT);
        }
        return replay;
    }

    private boolean exactPlanCreateFingerprint(String afterJson, String fingerprint) {
        if (blank(afterJson) || blank(fingerprint)) return false;
        try {
            Map<String, Object> receipt = JSON.readValue(afterJson,
                    new TypeReference<Map<String, Object>>() { });
            return receipt.size() == 1 && fingerprint.equals(receipt.get("fingerprint"));
        } catch (Exception invalidJson) {
            return false;
        }
    }

    private ClubMembershipPlan replayUpdatedPlan(BusinessAuditEvent receipt,
                                                   ClubAccessContext access,
                                                   String requestId,
                                                   Long planId,
                                                   Integer expectedVersion,
                                                   String fingerprint) {
        String actorRoleCode = access.getPrimaryRoleCode();
        boolean exactEnvelope = "CLUB".equals(receipt.getDomainType())
                && Objects.equals(access.getClubId(), receipt.getDomainId())
                && PLAN_SAVE_ACTION.equals(receipt.getAction())
                && Objects.equals(requestId, receipt.getRequestId())
                && Objects.equals(access.getOwnerMemberId(), receipt.getOwnerMemberId())
                && Objects.equals(access.getPrincipalMemberId(), receipt.getActorMemberId())
                && Objects.equals(actorRoleCode, receipt.getActorRoleCode())
                && "PLAN".equals(receipt.getTargetType())
                && Objects.equals(planId, receipt.getTargetId())
                && Objects.equals(planId, receipt.getRelationId())
                && receipt.getReason() == null
                && exactPlanUpdatePayload(receipt.getAfterJson(), fingerprint, expectedVersion);
        if (!exactEnvelope) {
            throw new ServiceException("requestId 已用于其他会费计划请求", HttpStatus.CONFLICT);
        }
        ClubMembershipPlan replay = clubMembershipMapper.selectPlanByIdForUpdate(planId);
        if (replay == null || !Objects.equals(planId, replay.getId())
                || !Objects.equals(access.getClubId(), replay.getClubId())
                || replay.getVersion() == null || !Integer.valueOf(0).equals(replay.getDelFlag())) {
            throw new ServiceException("会费计划回执与领域记录不一致", HttpStatus.CONFLICT);
        }
        if ((long) replay.getVersion() != (long) expectedVersion + 1L
                || !fingerprint.equals(planUpdateFingerprint(
                replay, access.getPrincipalMemberId(), expectedVersion))) {
            throw new ServiceException("会费计划已在原请求后发生变化，请刷新", HttpStatus.CONFLICT);
        }
        return replay;
    }

    private boolean exactPlanUpdatePayload(String afterJson, String fingerprint,
                                           Integer expectedVersion) {
        if (blank(afterJson) || blank(fingerprint) || expectedVersion == null) return false;
        try {
            Map<String, Object> receipt = JSON.readValue(afterJson,
                    new TypeReference<Map<String, Object>>() { });
            Object version = receipt.get("expectedVersion");
            return receipt.size() == 2 && fingerprint.equals(receipt.get("fingerprint"))
                    && version instanceof Number
                    && ((Number) version).intValue() == expectedVersion;
        } catch (Exception invalidJson) {
            return false;
        }
    }

    private RenewalIntent prepareRenewalIntent(Long principalMemberId,
                                                ClubMembershipRenewRequest request) {
        Club lockedClub = clubMapper.selectClubByIdForUpdate(request.getClubId());
        boolean clubActive = lockedClub != null && Long.valueOf(1L).equals(lockedClub.getStatus())
                && Integer.valueOf(0).equals(lockedClub.getDelFlag());
        boolean owner = clubActive && principalMemberId.equals(lockedClub.getMemberId());
        ClubMember memberRow = clubActive && !owner
                ? clubMemberMapper.selectByClubAndMemberForUpdate(request.getClubId(), principalMemberId) : null;
        if (!owner && (memberRow == null || !Integer.valueOf(1).equals(memberRow.getStatus())
                || !Integer.valueOf(0).equals(memberRow.getDelFlag()))) forbidden();
        if (clubMembershipMapper.lockMemberForRenewal(principalMemberId) == null) {
            throw new ServiceException("当前会员不可用", HttpStatus.FORBIDDEN);
        }
        ClubAccessContext lockedAccess = new ClubAccessContext(principalMemberId, request.getClubId(),
                lockedClub.getMemberId(), null, Collections.singleton(
                owner ? ClubRoleCode.CLUB_OWNER : ClubRoleCode.CLUB_MEMBER));
        ClubMembershipSetting setting = clubMembershipMapper.selectSetting(request.getClubId());
        if (setting == null || !Boolean.TRUE.equals(setting.getEnabled())) {
            throw new ServiceException("该俱乐部未开启付费会员", HttpStatus.CONFLICT);
        }
        String key = request.getIdempotencyKey().trim();
        ClubMembershipInvoice existing = clubMembershipMapper.selectInvoiceByIdempotency(
                request.getClubId(), principalMemberId, key);
        if (existing != null) {
            validateOwnedInvoice(existing, request.getClubId(), principalMemberId);
            if (!request.getPlanId().equals(existing.getPlanId())
                    || !validCallbackSnapshot(existing, existing.getOutTradeNo())) conflict();
            return renewalIntent(existing, principalMemberId);
        }
        // 与 Owner 保存套餐共用 club -> plan 锁序；冻结价格后到 intent 提交前不允许并发改价/下架。
        ClubMembershipPlan plan = clubMembershipMapper.selectPlanByIdForUpdate(request.getPlanId());
        validatePurchasablePlan(plan, request.getClubId());
        String fingerprint = requestFingerprint(request.getClubId(), principalMemberId, plan);
        ClubMembershipInvoice activePaying = clubMembershipMapper.selectActivePayingInvoiceForUpdate(
                request.getClubId(), principalMemberId);
        if (activePaying != null) {
            validateOwnedInvoice(activePaying, request.getClubId(), principalMemberId);
            if (!ClubMembershipInvoice.STATUS_PAYING.equals(activePaying.getStatus())
                    || !fingerprint.equals(activePaying.getRequestFingerprint())
                    || !validCallbackSnapshot(activePaying, activePaying.getOutTradeNo())) {
                throw new ServiceException("已有其他会费账单支付中，请先恢复原账单", HttpStatus.CONFLICT);
            }
            return renewalIntent(activePaying, principalMemberId);
        }
        ClubMembershipInvoice invoice = freezeInvoice(lockedAccess, plan, key, fingerprint);
        try {
            if (clubMembershipMapper.insertInvoice(invoice) != 1) conflict();
        } catch (DuplicateKeyException duplicate) {
            ClubMembershipInvoice raced = clubMembershipMapper.selectInvoiceByIdempotency(
                    request.getClubId(), principalMemberId, key);
            if (raced == null) conflict();
            validateOwnedInvoice(raced, request.getClubId(), principalMemberId);
            if (!request.getPlanId().equals(raced.getPlanId())
                    || !fingerprint.equals(raced.getRequestFingerprint())
                    || !validCallbackSnapshot(raced, raced.getOutTradeNo())) conflict();
            return renewalIntent(raced, principalMemberId);
        }
        Date createdAt = now();
        if (clubMembershipMapper.markInvoicePayingCas(invoice.getId(), 0, createdAt) != 1) conflict();
        invoice.setStatus(ClubMembershipInvoice.STATUS_PAYING);
        invoice.setVersion(1);
        audit(lockedAccess, "CLUB_MEMBERSHIP_INVOICE_CREATED", "INVOICE", invoice.getId(), null,
                "{\"amountFen\":" + invoice.getAmountFen()
                        + ",\"status\":\"PAYING\",\"recoverable\":true}",
                request.getRequestId(), null);
        return renewalIntent(invoice, principalMemberId);
    }

    private RenewalIntent renewalIntent(ClubMembershipInvoice invoice, Long principalMemberId) {
        if (invoice.getResumeBlockedAt() != null) {
            return new RenewalIntent(invoice, null, false);
        }
        boolean needsPrepay = ClubMembershipInvoice.STATUS_PAYING.equals(invoice.getStatus())
                && blank(invoice.getPayParamsJson());
        if (!needsPrepay) return new RenewalIntent(invoice, null, false);
        UmsMember member = memberService.selectUmsMemberById(principalMemberId);
        if (member == null || blank(member.getWxOpenId())) {
            throw new ServiceException("当前账号缺少小程序支付身份", HttpStatus.CONFLICT);
        }
        return new RenewalIntent(invoice, member.getWxOpenId(), true);
    }

    private InvoiceActionIntent prepareResumeIntent(Long principalMemberId,
                                                     ClubMembershipActionRequest request) {
        Club lockedClub = clubMapper.selectClubByIdForUpdate(request.getClubId());
        boolean clubActive = lockedClub != null && Long.valueOf(1L).equals(lockedClub.getStatus())
                && Integer.valueOf(0).equals(lockedClub.getDelFlag());
        boolean owner = clubActive && principalMemberId.equals(lockedClub.getMemberId());
        ClubMember lockedMember = clubActive && !owner
                ? clubMemberMapper.selectByClubAndMemberForUpdate(request.getClubId(), principalMemberId) : null;
        if (!owner && (lockedMember == null || !Integer.valueOf(1).equals(lockedMember.getStatus())
                || !Integer.valueOf(0).equals(lockedMember.getDelFlag()))) forbidden();
        if (clubMembershipMapper.lockMemberForRenewal(principalMemberId) == null) {
            throw new ServiceException("当前会员不可用", HttpStatus.FORBIDDEN);
        }
        ClubMembershipInvoice invoice = clubMembershipMapper.selectInvoiceByIdForUpdate(request.getId());
        validateOwnedInvoice(invoice, request.getClubId(), principalMemberId);
        if (!validCallbackSnapshot(invoice, invoice.getOutTradeNo())) {
            throw new ServiceException("账单快照校验失败，未恢复支付", HttpStatus.CONFLICT);
        }
        ClubAccessContext access = new ClubAccessContext(principalMemberId, request.getClubId(),
                lockedClub.getMemberId(), null, Collections.singleton(
                owner ? ClubRoleCode.CLUB_OWNER : ClubRoleCode.CLUB_MEMBER));
        String requestId = trim(request.getRequestId(), 64);
        if (ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())
                || ClubMembershipInvoice.STATUS_CLOSED.equals(invoice.getStatus())) {
            return new InvoiceActionIntent(access, invoice, requestId, null);
        }
        if (invoice.getResumeBlockedAt() != null) {
            throw new ServiceException("成员关系已变化，该账单只能关单不能恢复支付", HttpStatus.CONFLICT);
        }
        if (!ClubMembershipInvoice.STATUS_PAYING.equals(invoice.getStatus()) || invoice.getVersion() == null) {
            throw new ServiceException("该账单当前不可恢复支付", HttpStatus.CONFLICT);
        }
        ensureResumeAudit(access, invoice, requestId);
        String wxOpenId = null;
        if (blank(invoice.getPayParamsJson())) {
            UmsMember member = memberService.selectUmsMemberById(principalMemberId);
            if (member == null || blank(member.getWxOpenId())) {
                throw new ServiceException("当前账号缺少小程序支付身份", HttpStatus.CONFLICT);
            }
            wxOpenId = member.getWxOpenId();
        }
        return new InvoiceActionIntent(access, invoice, requestId, wxOpenId);
    }

    private InvoiceActionIntent prepareCloseIntent(Long principalMemberId,
                                                    ClubMembershipActionRequest request) {
        // D-12：关单不受会费开关影响，开关关闭后仍须能关闭既有待支付账单。
        ClubMembershipInvoice preview = clubMembershipMapper.selectInvoiceById(request.getId());
        validateOwnedInvoice(preview, request.getClubId(), principalMemberId);
        Club lockedClub = clubMapper.selectClubByIdForUpdate(request.getClubId());
        if (lockedClub == null || !request.getClubId().equals(lockedClub.getId())) forbidden();
        if (!principalMemberId.equals(lockedClub.getMemberId())) {
            clubMemberMapper.selectByClubAndMemberForUpdate(request.getClubId(), principalMemberId);
        }
        ClubMembershipInvoice invoice = clubMembershipMapper.selectInvoiceByIdForUpdate(request.getId());
        validateOwnedInvoice(invoice, request.getClubId(), principalMemberId);
        if (!sameCallbackInvoice(preview, invoice)
                || !validCallbackSnapshot(invoice, invoice.getOutTradeNo())) {
            throw new ServiceException("账单快照校验失败，未关闭支付", HttpStatus.CONFLICT);
        }
        if (!ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())
                && !ClubMembershipInvoice.STATUS_CLOSED.equals(invoice.getStatus())
                && (!ClubMembershipInvoice.STATUS_PAYING.equals(invoice.getStatus())
                || invoice.getVersion() == null)) {
            throw new ServiceException("该账单当前不可关闭", HttpStatus.CONFLICT);
        }
        ClubAccessContext access = new ClubAccessContext(principalMemberId, request.getClubId(),
                invoice.getOwnerMemberIdSnapshot(), null, Collections.singleton(
                principalMemberId.equals(invoice.getOwnerMemberIdSnapshot())
                        ? ClubRoleCode.CLUB_OWNER : ClubRoleCode.CLUB_MEMBER));
        return new InvoiceActionIntent(access, invoice, trim(request.getRequestId(), 64), null);
    }

    private ClubMembershipInvoice attachPrepayParams(ClubMembershipInvoice expected, String payJson) {
        ClubMembershipInvoice current = clubMembershipMapper.selectInvoiceByOutTradeNoForUpdate(
                expected.getOutTradeNo());
        if (!sameCallbackInvoice(expected, current)
                || !validCallbackSnapshot(current, expected.getOutTradeNo())) conflict();
        if (ClubMembershipInvoice.STATUS_PAID.equals(current.getStatus())
                || ClubMembershipInvoice.STATUS_CLOSED.equals(current.getStatus())) return current;
        if (current.getResumeBlockedAt() != null) {
            throw new ServiceException("成员关系已变化，未返回新的支付参数", HttpStatus.CONFLICT);
        }
        if (!blank(current.getPayParamsJson())) return current;
        if (!ClubMembershipInvoice.STATUS_PAYING.equals(current.getStatus()) || current.getVersion() == null
                || clubMembershipMapper.attachPayParamsCas(
                current.getId(), current.getVersion(), payJson, now()) != 1) conflict();
        current.setPayParamsJson(payJson);
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private static final class RenewalIntent {
        private final ClubMembershipInvoice invoice;
        private final String wxOpenId;
        private final boolean needsPrepay;

        private RenewalIntent(ClubMembershipInvoice invoice, String wxOpenId, boolean needsPrepay) {
            this.invoice = invoice;
            this.wxOpenId = wxOpenId;
            this.needsPrepay = needsPrepay;
        }
    }

    private static final class InvoiceActionIntent {
        private final ClubAccessContext access;
        private final ClubMembershipInvoice invoice;
        private final String requestId;
        private final String wxOpenId;

        private InvoiceActionIntent(ClubAccessContext access,
                                    ClubMembershipInvoice invoice,
                                    String requestId,
                                    String wxOpenId) {
            this.access = access;
            this.invoice = invoice;
            this.requestId = requestId;
            this.wxOpenId = wxOpenId;
        }
    }

    private ClubMembershipInvoice freezeInvoice(ClubAccessContext access, ClubMembershipPlan plan,
                                                  String key, String fingerprint) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        ClubMembershipInvoice invoice = new ClubMembershipInvoice();
        invoice.setClubId(access.getClubId());
        invoice.setOwnerMemberIdSnapshot(access.getOwnerMemberId());
        invoice.setMemberId(access.getPrincipalMemberId());
        invoice.setPlanId(plan.getId());
        invoice.setPlanVersionSnapshot(plan.getVersion());
        invoice.setPlanNameSnapshot(plan.getName());
        invoice.setDurationDaysSnapshot(plan.getDurationDays());
        invoice.setAmountFen(plan.getPriceFen());
        invoice.setBenefitsSnapshot(plan.getBenefitsJson());
        invoice.setGraceDaysSnapshot(plan.getGraceDays());
        invoice.setPastDueDaysSnapshot(plan.getPastDueDays());
        invoice.setExitPolicySnapshot(plan.getExitPolicy());
        invoice.setRefundPolicySnapshot(plan.getRefundPolicy());
        invoice.setStatus(ClubMembershipInvoice.STATUS_CREATED);
        invoice.setIdempotencyKey(key);
        invoice.setRequestFingerprint(fingerprint);
        invoice.setVersion(0);
        String orderNo = "CLM" + CanonicalDigest.sha256Hex(CanonicalDigest.join(
                String.valueOf(access.getClubId()), String.valueOf(access.getPrincipalMemberId()), key)).substring(0, 29);
        invoice.setInvoiceNo(orderNo);
        invoice.setOutTradeNo(orderNo);
        invoice.setSnapshotHash(ClubMembershipSnapshotCodec.hash(invoice));
        Date now = now();
        invoice.setCreateBy(String.valueOf(access.getPrincipalMemberId()));
        invoice.setUpdateBy(String.valueOf(access.getPrincipalMemberId()));
        invoice.setCreateTime(now);
        invoice.setUpdateTime(now);
        return invoice;
    }

    private String requestFingerprint(Long clubId, Long memberId, ClubMembershipPlan plan) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(String.valueOf(clubId), String.valueOf(memberId),
                String.valueOf(plan.getId()), String.valueOf(plan.getVersion()), String.valueOf(plan.getPriceFen()),
                String.valueOf(plan.getDurationDays()), plan.getBenefitsJson(), String.valueOf(plan.getGraceDays()),
                String.valueOf(plan.getPastDueDays()), plan.getExitPolicy(), plan.getRefundPolicy()));
    }

    private ClubMembershipRenewalVO renewal(ClubMembershipInvoice invoice) {
        ClubMembershipRenewalVO result = new ClubMembershipRenewalVO();
        result.setInvoice(invoice);
        result.setRecoverable(!ClubMembershipInvoice.STATUS_CLOSED.equals(invoice.getStatus())
                && invoice.getResumeBlockedAt() == null);
        result.setPaymentState(invoice.getStatus());
        if (invoice.getResumeBlockedAt() == null && !blank(invoice.getPayParamsJson())) {
            try {
                result.setPayParams(JSON.readValue(invoice.getPayParamsJson(),
                        new TypeReference<Map<String, String>>() { }));
            } catch (Exception ignored) {
                result.setPayParams(Collections.<String, String>emptyMap());
                result.setPaymentState("PREPAY_PARAMS_UNAVAILABLE");
            }
        }
        return result;
    }

    /**
     * 以微信查单为真源：SUCCESS 复用回调状态机补齐权益；仅 CLOSED
     * 只有在订单号与金额读回一致后才把本地 PAYING 前向关闭。查询未知时不改本地资金状态。
     */
    private ClubMembershipRenewalVO reconcileWechatInvoice(ClubMembershipInvoice invoice,
                                                             ClubAccessContext access,
                                                             String requestId,
                                                             boolean strictQuery) {
        Map<String, String> wxOrder;
        try {
            wxOrder = wechatPayService.queryOrderStatus(invoice.getOutTradeNo());
        } catch (Exception queryUnavailable) {
            if (strictQuery) {
                throw new ServiceException("微信支付状态暂不可确认，原账单继续保留", HttpStatus.CONFLICT);
            }
            return null;
        }
        return applyWechatInvoiceObservation(invoice, access, requestId, strictQuery, wxOrder);
    }

    private ClubMembershipRenewalVO applyWechatInvoiceObservation(ClubMembershipInvoice invoice,
            ClubAccessContext access, String requestId, boolean strictQuery, Map<String, String> wxOrder) {
        String tradeState = wxOrder == null ? null : wxOrder.get("trade_state");
        if (blank(tradeState)) {
            if (strictQuery) {
                throw new ServiceException("微信支付状态缺失，原账单继续保留", HttpStatus.CONFLICT);
            }
            return null;
        }
        String wxOutTradeNo = wxOrder.get("out_trade_no");
        Integer amountFen = positiveInteger(wxOrder.get("total"));
        if (!invoice.getOutTradeNo().equals(wxOutTradeNo) || amountFen == null
                || !Long.valueOf(amountFen.longValue()).equals(invoice.getAmountFen())) {
            throw new ServiceException("微信支付账单对账不一致，未变更本地账单", HttpStatus.CONFLICT);
        }
        if ("CLOSED".equalsIgnoreCase(tradeState)) {
            return transaction().execute(status -> closeObservedInvoice(
                    invoice, access, requestId, tradeState.toUpperCase(), wxOrder.get("wx_out_trade_no")));
        }
        if ("REFUND".equalsIgnoreCase(tradeState)) {
            throw new ServiceException("微信订单存在已支付退款事实，不能按未支付账单关闭", HttpStatus.CONFLICT);
        }
        if (!"SUCCESS".equalsIgnoreCase(tradeState)) {
            return null;
        }
        String transactionId = wxOrder.get("transaction_id");
        if (blank(transactionId)) {
            throw new ServiceException("微信支付成功但交易号缺失，未恢复会员权益", HttpStatus.CONFLICT);
        }
        Boolean paidApplied = transaction().execute(status ->
                handlePaidInvoice(invoice.getOutTradeNo(), true, amountFen, transactionId, paymentReference(wxOrder)));
        if (!Boolean.TRUE.equals(paidApplied)) {
            throw new ServiceException("微信支付已成功，但本地账单恢复冲突", HttpStatus.CONFLICT);
        }
        return transaction().execute(status -> readbackPaidInvoice(invoice, transactionId));
    }

    private ClubMembershipRenewalVO readbackPaidInvoice(ClubMembershipInvoice expected,
                                                          String transactionId) {
        ClubMembershipInvoice paidInvoice = clubMembershipMapper.selectInvoiceById(expected.getId());
        validateOwnedInvoice(paidInvoice, expected.getClubId(), expected.getMemberId());
        if (!sameCallbackInvoice(expected, paidInvoice)
                || !validCallbackSnapshot(paidInvoice, expected.getOutTradeNo())
                || !ClubMembershipInvoice.STATUS_PAID.equals(paidInvoice.getStatus())
                || !transactionId.trim().equals(paidInvoice.getTransactionId())) {
            throw new ServiceException("微信支付成功后的本地账单回读不一致", HttpStatus.CONFLICT);
        }
        return renewal(paidInvoice);
    }

    private ClubMembershipRenewalVO closeObservedInvoice(ClubMembershipInvoice invoice,
                                                           ClubAccessContext access,
                                                           String requestId,
                                                           String wechatState, String observedWxNo) {
        if (!wechatPayService.lockClosablePaymentAttemptForUpdate(invoice.getOutTradeNo(), observedWxNo)) {
            throw new ServiceException("支付尝试已变化，未关闭当前账单", HttpStatus.CONFLICT);
        }
        if (invoice.getVersion() == null || clubMembershipMapper.markInvoiceClosedCas(
                invoice.getId(), invoice.getVersion(), now()) != 1) {
            ClubMembershipInvoice current = clubMembershipMapper.selectInvoiceById(invoice.getId());
            validateOwnedInvoice(current, invoice.getClubId(), invoice.getMemberId());
            if (ClubMembershipInvoice.STATUS_CLOSED.equals(current.getStatus())
                    || ClubMembershipInvoice.STATUS_PAID.equals(current.getStatus())) return renewal(current);
            throw new ServiceException("账单状态并发变化，未关闭原账单", HttpStatus.CONFLICT);
        }
        invoice.setStatus(ClubMembershipInvoice.STATUS_CLOSED);
        invoice.setPayParamsJson(null);
        invoice.setVersion(invoice.getVersion() + 1);
        audit(access, "CLUB_MEMBERSHIP_INVOICE_CLOSED", "INVOICE", invoice.getId(), null,
                "{\"status\":\"CLOSED\",\"wechatState\":\"" + wechatState
                        + "\",\"recoverable\":false}", requestId, null);
        return renewal(invoice);
    }

    private Integer positiveInteger(String value) {
        if (blank(value)) return null;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private ClubMembershipTerm createTerm(ClubMembershipInvoice invoice, Instant startsAt, Instant paidAt) {
        ZonedDateTime start = ZonedDateTime.ofInstant(startsAt, SHANGHAI);
        ZonedDateTime end = start.plusDays(invoice.getDurationDaysSnapshot());
        ZonedDateTime graceEnd = end.plusDays(invoice.getGraceDaysSnapshot());
        ZonedDateTime expires = graceEnd.plusDays(invoice.getPastDueDaysSnapshot());
        ClubMembershipTerm term = new ClubMembershipTerm();
        term.setClubId(invoice.getClubId());
        term.setMemberId(invoice.getMemberId());
        term.setPlanId(invoice.getPlanId());
        term.setSourceInvoiceId(invoice.getId());
        term.setStartsAt(Date.from(start.toInstant()));
        term.setEndsAt(Date.from(end.toInstant()));
        term.setGraceEndsAt(Date.from(graceEnd.toInstant()));
        term.setExpiresAt(Date.from(expires.toInstant()));
        term.setStatus(startsAt.isAfter(paidAt) ? ClubMembershipTerm.STATUS_PENDING : ClubMembershipTerm.STATUS_ACTIVE);
        term.setBenefitsSnapshot(invoice.getBenefitsSnapshot());
        term.setExitPolicySnapshot(invoice.getExitPolicySnapshot());
        term.setRefundPolicySnapshot(invoice.getRefundPolicySnapshot());
        term.setVersion(0);
        term.setCreateBy(String.valueOf(invoice.getMemberId()));
        term.setUpdateBy(String.valueOf(invoice.getMemberId()));
        term.setCreateTime(Date.from(paidAt));
        term.setUpdateTime(Date.from(paidAt));
        return term;
    }

    private ClubMembershipAccessEndResultVO blockPayingInvoices(List<ClubMembershipInvoice> rows,
                                                                 Long clubId, Long memberId,
                                                                 String eventType, String requestId,
                                                                 Date blockedAt) {
        ClubMembershipAccessEndResultVO result = new ClubMembershipAccessEndResultVO();
        result.setPayoutCompleted(false);
        for (ClubMembershipInvoice invoice : safe(rows)) {
            if (invoice == null || !positive(invoice.getId()) || !clubId.equals(invoice.getClubId())
                    || (memberId != null && !memberId.equals(invoice.getMemberId()))
                    || !positive(invoice.getMemberId()) || invoice.getVersion() == null
                    || !ClubMembershipInvoice.STATUS_PAYING.equals(invoice.getStatus())
                    || !validCallbackSnapshot(invoice, invoice.getOutTradeNo())) {
                throw new ServiceException("待支付会费账单快照无效，未完成成员关系收尾", HttpStatus.CONFLICT);
            }
            if (invoice.getResumeBlockedAt() != null) continue;
            if (clubMembershipMapper.blockInvoiceResumeCas(
                    invoice.getId(), invoice.getVersion(), blockedAt) != 1) conflict();
            invoice.setResumeBlockedAt(blockedAt);
            invoice.setPayParamsJson(null);
            invoice.setVersion(invoice.getVersion() + 1);
            result.setBlockedInvoiceCount(result.getBlockedInvoiceCount() + 1);
            ClubAccessContext systemAccess = new ClubAccessContext(
                    invoice.getMemberId(), invoice.getClubId(), invoice.getOwnerMemberIdSnapshot(),
                    null, Collections.<String>emptySet());
            audit(systemAccess, "CLUB_MEMBERSHIP_PREPAY_BLOCKED", "INVOICE", invoice.getId(), null,
                    "{\"status\":\"PAYING\",\"resumeBlocked\":true,\"eventType\":\""
                            + eventType + "\"}",
                    accessEndRequestId(requestId, invoice.getId(), "prepay-block"), eventType);
        }
        return result;
    }

    private ClubMembershipAccessEndResultVO endAccessTerms(List<ClubMembershipTerm> rows, String eventType,
                                                            String requestId, Date endedAt,
                                                            ClubMembershipAccessEndResultVO result) {
        if (result == null) result = new ClubMembershipAccessEndResultVO();
        result.setPayoutCompleted(false);
        for (ClubMembershipTerm term : safe(rows)) {
            if (term == null || !positive(term.getId()) || !positive(term.getClubId())
                    || !positive(term.getMemberId()) || !positive(term.getPlanId())
                    || !positive(term.getSourceInvoiceId())
                    || term.getVersion() == null
                    || !ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY.equals(term.getExitPolicySnapshot())) {
                throw new ServiceException("会员退出政策快照无效，未静默终止权益", HttpStatus.CONFLICT);
            }
            ClubMembershipInvoice invoice = clubMembershipMapper.selectInvoiceById(term.getSourceInvoiceId());
            if (!validCallbackSnapshot(invoice, invoice == null ? null : invoice.getOutTradeNo())
                    || !term.getClubId().equals(invoice.getClubId())
                    || !term.getMemberId().equals(invoice.getMemberId())
                    || !term.getPlanId().equals(invoice.getPlanId())
                    || !ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())) {
                throw new ServiceException("会员账单与权益期不一致，未静默处理资金", HttpStatus.CONFLICT);
            }
            if (clubMembershipMapper.updateTermStatusCas(term.getId(), term.getClubId(), term.getMemberId(),
                    term.getVersion(), ClubMembershipTerm.STATUS_CANCELLED, endedAt) != 1) conflict();
            result.setCancelledTermCount(result.getCancelledTermCount() + 1);

            ClubMembershipRefundReview review = null;
            if (ClubMembershipPlan.POLICY_MANUAL_REVIEW.equals(invoice.getRefundPolicySnapshot())) {
                review = ensurePendingReview(invoice, eventType,
                        accessEndRequestId(requestId, invoice.getId(), "review"), endedAt);
                result.setPendingReviewCount(result.getPendingReviewCount() + 1);
            } else if (ClubMembershipPlan.POLICY_NON_REFUNDABLE.equals(invoice.getRefundPolicySnapshot())) {
                result.setNonRefundableCount(result.getNonRefundableCount() + 1);
            } else {
                throw new ServiceException("会员退款政策快照无效，未静默处理资金", HttpStatus.CONFLICT);
            }
            ClubAccessContext systemAccess = new ClubAccessContext(invoice.getMemberId(), invoice.getClubId(),
                    invoice.getOwnerMemberIdSnapshot(), null, Collections.<String>emptySet());
            audit(systemAccess, "CLUB_MEMBERSHIP_ACCESS_ENDED", "TERM", term.getId(),
                    review == null ? null : review.getId(), "{\"status\":\"CANCELLED\",\"eventType\":\""
                            + eventType + "\",\"refundState\":\""
                            + (review == null ? "NON_REFUNDABLE" : "PENDING_REVIEW")
                            + "\",\"payout\":false}",
                    accessEndRequestId(requestId, term.getId(), "audit"), eventType);
        }
        return result;
    }

    private ClubMembershipRefundReview ensurePendingReview(ClubMembershipInvoice invoice, String reason,
                                                             String requestId, Date createdAt) {
        ClubMembershipRefundReview existing = clubMembershipMapper.selectRefundReviewByInvoice(invoice.getId());
        if (existing != null) {
            if (!ClubMembershipRefundReview.STATUS_PENDING_REVIEW.equals(existing.getStatus())
                    || !invoice.getClubId().equals(existing.getClubId())
                    || !invoice.getMemberId().equals(existing.getMemberId())) {
                throw new ServiceException("退款审核事实不一致", HttpStatus.CONFLICT);
            }
            return existing;
        }
        ClubMembershipRefundReview review = new ClubMembershipRefundReview();
        review.setClubId(invoice.getClubId());
        review.setMemberId(invoice.getMemberId());
        review.setInvoiceId(invoice.getId());
        review.setStatus(ClubMembershipRefundReview.STATUS_PENDING_REVIEW);
        review.setReason(reason);
        review.setRequestId(requestId);
        review.setVersion(0);
        review.setCreateBy("SYSTEM_ACCESS_END");
        review.setUpdateBy("SYSTEM_ACCESS_END");
        review.setCreateTime(createdAt);
        review.setUpdateTime(createdAt);
        try {
            if (clubMembershipMapper.insertRefundReview(review) != 1) conflict();
        } catch (DuplicateKeyException duplicate) {
            existing = clubMembershipMapper.selectRefundReviewByInvoice(invoice.getId());
            if (existing == null || !ClubMembershipRefundReview.STATUS_PENDING_REVIEW.equals(existing.getStatus())) {
                conflict();
            }
            return existing;
        }
        return review;
    }

    private void requireAccessEndRequest(Long clubId, Long memberId, String eventType, String requestId) {
        if (!positive(clubId) || (memberId != null && !positive(memberId)) || blank(requestId)
                || requestId.trim().length() > 256
                || !requestId.trim().matches("[A-Za-z0-9._:-]+")
                || (!"MEMBER_QUIT".equals(eventType) && !"MEMBER_REMOVED".equals(eventType)
                && !"MEMBER_BANNED".equals(eventType) && !"CLUB_DISSOLVED".equals(eventType))) {
            bad("会员访问终止参数不完整");
        }
    }

    private String accessEndRequestId(String requestId, Long targetId, String suffix) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join("CLM_ACCESS_END", requestId,
                String.valueOf(targetId), suffix));
    }

    private boolean sameCallbackInvoice(ClubMembershipInvoice preview, ClubMembershipInvoice locked) {
        return preview != null && locked != null && preview.getId().equals(locked.getId())
                && preview.getClubId().equals(locked.getClubId())
                && preview.getMemberId().equals(locked.getMemberId())
                && preview.getOutTradeNo().equals(locked.getOutTradeNo())
                && preview.getSnapshotHash().equals(locked.getSnapshotHash());
    }

    private boolean validCallbackSnapshot(ClubMembershipInvoice invoice, String outTradeNo) {
        return invoice != null && outTradeNo != null && outTradeNo.equals(invoice.getOutTradeNo())
                && invoice.getId() != null && positive(invoice.getClubId())
                && positive(invoice.getOwnerMemberIdSnapshot()) && positive(invoice.getMemberId())
                && positive(invoice.getPlanId()) && invoice.getPlanVersionSnapshot() != null
                && !blank(invoice.getPlanNameSnapshot()) && invoice.getDurationDaysSnapshot() != null
                && invoice.getDurationDaysSnapshot() > 0 && invoice.getAmountFen() != null
                && invoice.getAmountFen() > 0 && !blank(invoice.getBenefitsSnapshot())
                && invoice.getGraceDaysSnapshot() != null && invoice.getPastDueDaysSnapshot() != null
                && ClubMembershipPlan.EXIT_POLICY_END_IMMEDIATELY.equals(invoice.getExitPolicySnapshot())
                && !blank(invoice.getRefundPolicySnapshot()) && !blank(invoice.getSnapshotHash())
                && invoice.getSnapshotHash().equals(ClubMembershipSnapshotCodec.hash(invoice));
    }

    private void requirePaymentParams(Map<String, String> params) {
        String[] keys = {"timeStamp", "nonceStr", "package", "signType", "paySign"};
        if (params == null) throw new ServiceException("支付参数缺失", HttpStatus.ERROR);
        for (String key : keys) if (blank(params.get(key))) {
            throw new ServiceException("支付参数不完整", HttpStatus.ERROR);
        }
    }

    private void requireAction(ClubMembershipActionRequest request) {
        requireRequest(request, request == null ? null : request.getClubId());
        if (request.getId() == null || request.getId() <= 0 || invalidRequestId(request.getRequestId())) {
            bad("操作参数不完整");
        }
    }

    private void validateOwnedInvoice(ClubMembershipInvoice row, Long clubId, Long memberId) {
        if (row == null || !clubId.equals(row.getClubId()) || !memberId.equals(row.getMemberId())) forbidden();
    }

    private void validateOwnedTerm(ClubMembershipTerm row, Long clubId, Long memberId) {
        if (row == null || !clubId.equals(row.getClubId()) || !memberId.equals(row.getMemberId())) forbidden();
    }

    private void audit(ClubAccessContext access, String action, String targetType, Long targetId,
                       Long relationId, String afterJson, String requestId, String reason) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(access.getClubId());
        event.setOwnerMemberId(access.getOwnerMemberId());
        event.setActorMemberId(access.getPrincipalMemberId());
        String actorRoleCode = access.getPrimaryRoleCode();
        event.setActorRoleCode(actorRoleCode == null ? "SYSTEM_CALLBACK" : actorRoleCode);
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setRelationId(relationId);
        event.setAfterJson(afterJson);
        event.setReason(reason == null ? null : trim(reason, 500));
        event.setRequestId(trim(requestId, 64));
        event.setCreateTime(now());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw new ServiceException("业务审计写入失败", HttpStatus.ERROR);
        }
    }

    private void ensureResumeAudit(ClubAccessContext access, ClubMembershipInvoice invoice,
                                   String requestId) {
        BusinessAuditEvent receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", access.getClubId(), PREPAY_RESUME_ACTION, requestId);
        if (receipt != null) {
            requireExactResumeReplay(receipt, access, invoice, requestId);
            return;
        }
        try {
            audit(access, PREPAY_RESUME_ACTION, "INVOICE", invoice.getId(), null,
                    PREPAY_RESUME_AFTER_JSON, requestId, null);
        } catch (DuplicateKeyException duplicate) {
            receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                    "CLUB", access.getClubId(), PREPAY_RESUME_ACTION, requestId);
            if (receipt == null) {
                throw new ServiceException("会费恢复请求回执冲突", HttpStatus.CONFLICT);
            }
            requireExactResumeReplay(receipt, access, invoice, requestId);
        }
    }

    private void requireExactResumeReplay(BusinessAuditEvent receipt, ClubAccessContext access,
                                          ClubMembershipInvoice invoice, String requestId) {
        boolean exact = "CLUB".equals(receipt.getDomainType())
                && Objects.equals(access.getClubId(), receipt.getDomainId())
                && PREPAY_RESUME_ACTION.equals(receipt.getAction())
                && Objects.equals(requestId, receipt.getRequestId())
                && Objects.equals(access.getPrincipalMemberId(), receipt.getActorMemberId())
                && "INVOICE".equals(receipt.getTargetType())
                && Objects.equals(invoice.getId(), receipt.getTargetId())
                && exactResumeReceiptPayload(receipt.getAfterJson());
        if (!exact) {
            throw new ServiceException("requestId 已用于其他会费恢复请求", HttpStatus.CONFLICT);
        }
    }

    private boolean exactResumeReceiptPayload(String afterJson) {
        if (blank(afterJson)) return false;
        try {
            return JSON.readTree(PREPAY_RESUME_AFTER_JSON).equals(JSON.readTree(afterJson));
        } catch (Exception invalidJson) {
            return false;
        }
    }

    private Date now() { return Date.from(clock.instant()); }
    private boolean positive(Long value) { return value != null && value > 0; }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private boolean invalidRequestId(String value) {
        return blank(value) || value.trim().length() > 64
                || !value.trim().matches("[A-Za-z0-9._:-]+");
    }
    private boolean invalidIdempotencyKey(String value) {
        return blank(value) || value.trim().length() > 64
                || !value.trim().matches("[A-Za-z0-9._:-]+");
    }
    private String trim(String value, int max) {
        if (value == null) return null;
        String result = value.trim();
        return result.length() <= max ? result : result.substring(0, max);
    }
    private <T> List<T> safe(List<T> rows) { return rows == null ? Collections.<T>emptyList() : rows; }
    private void requireRequest(Object request, Long clubId) {
        if (request == null || !positive(clubId)) bad("俱乐部参数不完整");
    }
    private void bad(String message) { throw new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private void forbidden() { throw new ServiceException("俱乐部身份无效或权限不足", HttpStatus.FORBIDDEN); }
    private void conflict() { throw new ServiceException("数据已变化或幂等键冲突", HttpStatus.CONFLICT); }
    /** NEVER rejects proxied outer transactions; this also protects self/direct invocation before any local intent. */
    private static void requirePaymentOutsideTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("支付外呼必须在原业务事务外，不能挂起持锁事务继续执行");
        }
    }

    /** Known current-attempt denial remains false; DB/read failures must propagate. No local intent policy. */
    private boolean currentInvoicePayment(WechatVerifiedPaymentFactService.Stored verified) {
        try {
            var consumable = verifiedPaymentFacts.requireConsumablePayment(verified.reference());
            if (!verified.equals(consumable)) throw new IllegalStateException("原成功支付事实回读不一致");
            return true;
        } catch (WechatVerifiedPaymentFactService.ConflictException rejected) {
            return false;
        }
    }

    private boolean completeInvoiceProof(ClubMembershipInvoice invoice, WechatVerifiedPaymentFactService.Stored verified) {
        if (invoice == null || !ClubMembershipInvoice.STATUS_PAID.equals(invoice.getStatus())
                || !validCallbackSnapshot(invoice, verified.facts().businessOutTradeNo())
                || !verified.facts().transactionId().equals(invoice.getTransactionId())
                || !Long.valueOf(verified.facts().totalFen()).equals(invoice.getAmountFen())
                || !Long.valueOf(verified.reference().id()).equals(invoice.getVerifiedPaymentFactId())
                || !verified.reference().hash().equals(invoice.getVerifiedPaymentFactHash())) return false;
        if (!currentInvoicePayment(verified)) return false;
        verifiedPaymentFacts.completePaymentRecovery(verified.reference(), invoice.getId());
        return true;
    }

    private WechatVerifiedPaymentFactService.Stored checkedPayment(Reference reference, String outTradeNo,
            Integer amountFen, String transactionId) {
        WechatVerifiedPaymentFactService.Stored verified = verifiedPaymentFacts.requireVerified(reference);
        WechatVerifiedPaymentFact fact = verified.facts();
        fact.requireValid();
        if (!reference.equals(verified.reference()) || !reference.hash().equals(fact.factHash())
                || fact.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT || !"CNY".equals(fact.currency())
                || !outTradeNo.equals(fact.businessOutTradeNo()) || fact.totalFen() != amountFen.longValue()
                || fact.amountFen() != fact.totalFen() || !transactionId.trim().equals(fact.transactionId()))
            throw new IllegalStateException("原支付事实与业务单号、金额或交易身份不符");
        // Original merchant/app/channel/intent hash are immutable B facts, never current-config defaults.
        return verified;
    }

    private Reference paymentReference(Map<String, String> observed) {
        if (observed == null || observed.get("verified_fact_id") == null || observed.get("verified_fact_hash") == null)
            throw new IllegalStateException("微信成功观察缺少原支付事实引用");
        try { return new Reference(Long.parseLong(observed.get("verified_fact_id")), observed.get("verified_fact_hash")); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("微信成功观察的原支付事实引用无效", invalid); }
    }

}
