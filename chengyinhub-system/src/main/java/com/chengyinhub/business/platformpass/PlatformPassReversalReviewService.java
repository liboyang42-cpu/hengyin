package com.chengyinhub.business.platformpass;

import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.platformpass.mapper.PlatformPassAppleMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T5:Apple 撤回退款(REFUND_REVERSED)后进入 REVIEW 的通行证订单,由运营复核。
 *
 * <ul>
 *   <li>列表:REVIEW(或已处理的 GRANTED / REVOKED)订单,附本笔交易收到的每一条 App Store 通知(类型、子类型、状态)。</li>
 *   <li>重新开通:已退款订单回到已支付,被收回的那一段按冻结天数从现在(或本人最后一段到期)起重开;
 *       退款先于开通到达(订单仍待支付 / 已取消)时按正常开通走一次。不收钱、不调用任何支付渠道。</li>
 *   <li>关闭:维持退款结果,订单置 REVOKED,不再开通。</li>
 * </ul>
 * 两种处理都必须写备注;与业务写同一事务追加一条平台审计(business_audit_event),审计写不进去整笔回滚。
 */
@Service
public class PlatformPassReversalReviewService {
    static final int MAX_LIMIT = 200;
    static final int NOTE_MAX = 500;

    private final PlatformPassService pass;
    private final PlatformPassMapper passMapper;
    private final PlatformPassAppleMapper mapper;
    private final OpsConsoleAudit audit;
    private final TransactionTemplate tx;

    public PlatformPassReversalReviewService(PlatformPassService pass, PlatformPassMapper passMapper,
                                             PlatformPassAppleMapper mapper, OpsConsoleAudit audit,
                                             PlatformTransactionManager transactions) {
        this.pass = pass;
        this.passMapper = passMapper;
        this.mapper = mapper;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    public List<Map<String, Object>> list(String state, Integer limit) {
        String s = state == null || state.isBlank() ? "REVIEW" : state.trim().toUpperCase();
        if (!s.equals("REVIEW") && !s.equals("GRANTED") && !s.equals("REVOKED")) {
            throw PlatformPassException.badRequest("PASS_REVIEW_STATE_INVALID", "状态只能是 REVIEW / GRANTED / REVOKED");
        }
        int n = limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectByState(s, n)) {
            Map<String, Object> view = new LinkedHashMap<>(row);
            Object txn = row.get("transaction_id");
            view.put("notifications", txn == null ? List.of() : mapper.selectNotificationsByTransaction(txn.toString()));
            out.add(view);
        }
        return out;
    }

    /** 同意重新开通。 */
    public Map<String, Object> regrant(Long sysUserId, String orderNo, String note) {
        return resolve(sysUserId, orderNo, note, true);
    }

    /** 关闭复核,维持退款。 */
    public Map<String, Object> close(Long sysUserId, String orderNo, String note) {
        return resolve(sysUserId, orderNo, note, false);
    }

    Map<String, Object> resolve(Long sysUserId, String orderNo, String note, boolean regrant) {
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) throw PlatformPassException.badRequest("PASS_REVIEW_NOTE_REQUIRED", "请填写处理备注");
        if (reason.length() > NOTE_MAX) throw PlatformPassException.badRequest("PASS_REVIEW_NOTE_TOO_LONG", "备注最多 500 字");
        String no = orderNo == null ? "" : orderNo.trim();
        if (no.isEmpty()) throw PlatformPassException.badRequest("PASS_REVIEW_ORDER_REQUIRED", "缺少订单号");
        return tx.execute(s -> {
            Map<String, Object> apple = mapper.selectByOrderNoForUpdate(no);
            if (apple == null || apple.isEmpty()) throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
            if (!"REVIEW".equals(apple.get("state"))) {
                throw PlatformPassException.conflict("PASS_REVIEW_ALREADY_RESOLVED", "这笔订单已处理过");
            }
            Map<String, Object> order = passMapper.selectOrderByNoForUpdate(no);
            if (order == null || order.isEmpty()) throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
            Map<String, Object> before = snapshot(apple, order);
            String target = regrant ? "GRANTED" : "REVOKED";
            if (regrant) regrantOrder(apple, order);
            if (mapper.resolveReview(no, target) != 1) {
                throw PlatformPassException.conflict("PASS_REVIEW_ALREADY_RESOLVED", "这笔订单已处理过");
            }
            Map<String, Object> after = snapshot(mapper.selectByOrderNo(no), passMapper.selectOrderByNo(no));
            audit.record(sysUserId, regrant ? "PASS_REVERSAL_REGRANT" : "PASS_REVERSAL_CLOSE", "PLATFORM_PASS_ORDER",
                    PlatformPassService.num(order.get("id")), before, after, reason);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderNo", no);
            result.put("state", target);
            result.put("orderStatus", after.get("orderStatus"));
            return result;
        });
    }

    private void regrantOrder(Map<String, Object> apple, Map<String, Object> order) {
        int status = PlatformPassService.num(order.get("status")).intValue();
        Date now = new Date();
        if (status == PlatformPassService.REFUNDED) {
            pass.reinstateAfterAppleReversal(order, now);
        } else if (status == PlatformPassService.PENDING || status == PlatformPassService.CANCELLED) {
            Object txn = apple.get("transaction_id");
            if (txn == null) {
                throw PlatformPassException.conflict("PASS_REVIEW_NO_TRANSACTION", "这笔订单没有 App Store 交易,不能开通");
            }
            Date paidAt = apple.get("purchase_date") instanceof Date d ? d : now;
            if (mapper.markOrderPaid(PlatformPassService.num(order.get("id")), txn.toString(), paidAt) != 1) {
                throw new IllegalStateException("通行证订单已付回写失败");
            }
            pass.activate(order, now, null);
        } else if (status != PlatformPassService.PAID) {
            // 退款中:等渠道结果,不在这里抢先开通。
            throw PlatformPassException.conflict("PASS_REVIEW_ORDER_BUSY", "订单正在退款中,稍后再处理");
        }
        // PAID:那一段从未被收回,只需把复核关掉。
    }

    private static Map<String, Object> snapshot(Map<String, Object> apple, Map<String, Object> order) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("appleState", apple == null ? null : apple.get("state"));
        s.put("orderStatus", order == null ? null : order.get("status"));
        return s;
    }
}
