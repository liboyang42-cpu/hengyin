package com.chengyinhub.business.commerceconsole;

import com.chengyinhub.business.commerceconsole.mapper.DuesRefundDecisionMapper;
import com.chengyinhub.business.domain.ClubMembershipRefundReview;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneyGate;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W7 MERCHANT-12: the operator action for club dues refund reviews (商品与会费 → 会费退款). Until now a refund review was a
 * queue with no way to finish it.
 * <ul>
 *   <li><b>Reject</b> moves no money: it records the decision and note. Profit sharing for that invoice is no longer
 *       held by the review.</li>
 *   <li><b>Approve</b> refunds the full invoice amount through the existing WeChat refund path
 *       ({@link IWechatPayService#submitRefund}), which routes a split (sub-merchant) order through the partner adapter
 *       (returns the platform's share first) and a platform order through the direct refund. It runs only while the
 *       Q5 money switch area {@code refunds} is open. The refund number is deterministic ({@code CRF<reviewId>}) and
 *       WeChat is idempotent on it, so an UNKNOWN answer can be sent again safely.</li>
 * </ul>
 * The decision is written and audited in one transaction before the request leaves, and the answer in a second one; the
 * provider call itself runs outside any transaction (the gateway requires it). Permission
 * {@code business:commerceConsole:refund} is granted to no role.
 */
@Service
public class DuesRefundDecisionService {
    private static final Logger log = LoggerFactory.getLogger(DuesRefundDecisionService.class);
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    static final String NOT_SENT = "NOT_SENT";
    static final String SUBMITTING = "SUBMITTING";
    static final String UNKNOWN = "UNKNOWN";
    static final String REFUND_REASON = "会费退款";

    private final DuesRefundDecisionMapper mapper;
    private final OpsConsoleAudit audit;
    private final MoneyGate gate;
    private final IWechatPayService payments;
    private final TransactionTemplate tx;

    public DuesRefundDecisionService(DuesRefundDecisionMapper mapper, OpsConsoleAudit audit, MoneyGate gate,
                                     IWechatPayService payments, PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.audit = audit;
        this.gate = gate;
        this.payments = payments;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Newest first; {@code undecided} keeps only reviews without a decision. */
    public List<Map<String, Object>> reviews(Boolean undecided, Integer limit) {
        return mapper.selectReviews(Boolean.TRUE.equals(undecided), CommerceConsoleService.limit(limit));
    }

    public Map<String, Object> reject(Long sysUserId, String operator, long reviewId, String note) {
        String why = CommerceConsoleService.reason(note);
        requireOperator(operator);
        return tx.execute(status -> {
            Map<String, Object> row = reviewFor(reviewId);
            if (existingDecision(reviewId) != null) throw new ServiceException("这笔退款申请已经处理过");
            insert(reviewId, REJECTED, why, operator, null, null, NOT_SENT);
            Map<String, Object> after = decision(reviewId, REJECTED, NOT_SENT);
            audit.record(sysUserId, "CLUB_DUES_REFUND_REJECT", "CLUB_MEMBERSHIP_REFUND_REVIEW", reviewId, row, after, why);
            return after;
        });
    }

    /** Approves and sends the refund; a decision whose answer was UNKNOWN (or never came back) is sent again. */
    public Map<String, Object> approve(Long sysUserId, String operator, long reviewId, String note) {
        String why = CommerceConsoleService.reason(note);
        requireOperator(operator);
        if (!gate.isOpen(MoneyArea.REFUNDS)) throw new ServiceException("资金开关「退款」未打开，会费退款暂不能执行");
        Send send = tx.execute(status -> {
            Map<String, Object> row = reviewFor(reviewId);
            String outTradeNo = text(row.get("outTradeNo"));
            long amount = row.get("amountFen") instanceof Number n ? n.longValue() : 0L;
            if (!"PAID".equals(row.get("invoiceStatus")) || outTradeNo == null || amount <= 0)
                throw new ServiceException("这张账单不是已支付状态，不能退款");
            Map<String, Object> existing = existingDecision(reviewId);
            if (existing != null) {
                String state = text(existing.get("refundState"));
                if (!APPROVED.equals(existing.get("decision")) || !(SUBMITTING.equals(state) || UNKNOWN.equals(state)))
                    throw new ServiceException("这笔退款申请已经处理过");
                String outRefundNo = text(existing.get("outRefundNo"));
                long sent = existing.get("refundAmountFen") instanceof Number n ? n.longValue() : 0L;
                if (outRefundNo == null || sent != amount) throw new ServiceException("退款记录与账单不一致，请人工核对");
                audit.record(sysUserId, "CLUB_DUES_REFUND_RESEND", "CLUB_MEMBERSHIP_REFUND_REVIEW", reviewId, existing,
                        existing, why);
                return new Send(outTradeNo, outRefundNo, amount);
            }
            String outRefundNo = outRefundNo(reviewId);
            insert(reviewId, APPROVED, why, operator, outRefundNo, amount, SUBMITTING);
            Map<String, Object> after = decision(reviewId, APPROVED, SUBMITTING);
            after.put("outRefundNo", outRefundNo);
            after.put("refundAmountFen", amount);
            audit.record(sysUserId, "CLUB_DUES_REFUND_APPROVE", "CLUB_MEMBERSHIP_REFUND_REVIEW", reviewId, row, after, why);
            return new Send(outTradeNo, outRefundNo, amount);
        });
        String state = submit(send);
        return tx.execute(status -> {
            mapper.updateRefundState(reviewId, state);
            Map<String, Object> after = decision(reviewId, APPROVED, state);
            after.put("outRefundNo", send.outRefundNo());
            after.put("refundAmountFen", send.amountFen());
            audit.record(sysUserId, "CLUB_DUES_REFUND_SUBMITTED", "CLUB_MEMBERSHIP_REFUND_REVIEW", reviewId, null, after, why);
            return after;
        });
    }

    /**
     * ACCEPTED, FAILED (definitive) or UNKNOWN (can be sent again). A restore pause is passed on: nothing left, the
     * decision stays SUBMITTING and the operator can send it again later.
     */
    String submit(Send send) {
        BigDecimal yuan = BigDecimal.valueOf(send.amountFen(), 2);
        try {
            RefundRequestResult result = payments.submitRefund(send.outTradeNo(), send.outRefundNo(), yuan, yuan, REFUND_REASON);
            if (result == RefundRequestResult.ACCEPTED) return "ACCEPTED";
            if (result == RefundRequestResult.DEFINITIVE_FAILED) return "FAILED";
            return UNKNOWN;
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException unavailable) {
            log.warn("dues refund {} not confirmed: {}", send.outRefundNo(), unavailable.getClass().getSimpleName());
            return UNKNOWN;
        }
    }

    static String outRefundNo(long reviewId) {
        return "CRF" + reviewId;
    }

    private Map<String, Object> reviewFor(long reviewId) {
        if (reviewId <= 0) throw new ServiceException("退款申请不存在");
        Map<String, Object> row = mapper.selectForDecision(reviewId);
        if (row == null || row.isEmpty()) throw new ServiceException("退款申请不存在");
        if (!ClubMembershipRefundReview.STATUS_PENDING_REVIEW.equals(row.get("reviewStatus")))
            throw new ServiceException("退款申请状态不对，请刷新");
        return row;
    }

    private Map<String, Object> existingDecision(long reviewId) {
        Map<String, Object> row = mapper.selectDecision(reviewId);
        return row == null || row.isEmpty() ? null : row;
    }

    private void insert(long reviewId, String decision, String note, String operator, String outRefundNo, Long amount,
                        String state) {
        try {
            if (mapper.insertDecision(reviewId, decision, note, operator, outRefundNo, amount, state) != 1)
                throw new ServiceException("这笔退款申请已经处理过");
        } catch (DuplicateKeyException race) {
            throw new ServiceException("这笔退款申请已经处理过");
        }
    }

    private static Map<String, Object> decision(long reviewId, String decision, String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewId", reviewId);
        out.put("decision", decision);
        out.put("refundState", state);
        return out;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) throw new ServiceException("缺少操作人");
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    record Send(String outTradeNo, String outRefundNo, long amountFen) {}
}
