package com.chengyinhub.business.moneypayout;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.moneypayout.mapper.PayoutBindingMapper;
import com.chengyinhub.business.moneypayout.mapper.PayoutRequestMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Withdrawal in TEST (R6b, FLOW-11 / MON-08): a member asks, an admin reviews, an approved request is paid out
 * through the existing WeChat transfer ({@link ICashWithdrawService#withdraw}).
 *
 * <p>Money rules:
 * <ul>
 *   <li>Asking, approving and sending are refused unless the money switch area {@code withdrawal} is open. Sending
 *       also needs the transfer integration's own property. Rejecting and cancelling move no money and stay allowed.</li>
 *   <li>A request moves no money. The balance is debited only when the payout is sent, by the transfer service's own
 *       atomic debit, which also keeps frozen amounts back. If the balance is short by then, the request fails and
 *       nothing is debited.</li>
 *   <li>This drains the member balance only. It never credits a balance, so no third-party sale can reach a
 *       balance through it.</li>
 * </ul>
 */
@Service
public class PayoutRequestService {
    private static final Logger log = LoggerFactory.getLogger(PayoutRequestService.class);

    public static final String PENDING = "PENDING";
    public static final String PAYING = "PAYING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    static final Set<String> STATUSES = Set.of(PENDING, PAYING, SENT, FAILED, REJECTED, CANCELLED);
    static final BigDecimal MAX_AMOUNT = new BigDecimal("20000.00");
    static final int NOTE_MAX = 255;

    private final PayoutRequestMapper requests;
    private final PayoutBindingMapper bindings;
    private final UmsMemberMapper members;
    private final IRoleService roles;
    private final WithdrawalFrozenAmountResolver frozen;
    private final ICashWithdrawService transfers;
    private final PayoutMoneyGate gate;
    private final OpsConsoleAudit audit;
    private final TransactionTemplate tx;

    public PayoutRequestService(PayoutRequestMapper requests, PayoutBindingMapper bindings, UmsMemberMapper members,
                                IRoleService roles, WithdrawalFrozenAmountResolver frozen, ICashWithdrawService transfers,
                                PayoutMoneyGate gate, OpsConsoleAudit audit, PlatformTransactionManager transactions) {
        this.requests = requests;
        this.bindings = bindings;
        this.members = members;
        this.roles = roles;
        this.frozen = frozen;
        this.transfers = transfers;
        this.gate = gate;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    /** What the withdrawal page needs. Readable with the switch closed, so the page can say so honestly. */
    public Map<String, Object> overview(Long memberId) {
        requireMember(memberId);
        UmsMember member = members.selectUmsMemberById(memberId);
        if (member == null) throw new BusinessRefusal(ErrorCode.USER_NOT_FOUND);
        BigDecimal balance = member.getBalance() == null ? BigDecimal.ZERO : member.getBalance();
        BigDecimal held = frozen.resolve(memberId).total();
        BigDecimal available = balance.subtract(held).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("withdrawalOpen", gate.isOpen(MoneyArea.WITHDRAWAL));
        out.put("balance", balance.setScale(2, RoundingMode.DOWN));
        out.put("held", held.setScale(2, RoundingMode.DOWN));
        out.put("available", available);
        out.put("minAmount", ICashWithdrawService.MIN_AMOUNT);
        out.put("maxAmount", MAX_AMOUNT);
        out.put("wechatReady", payoutWechatReady(member));
        List<Map<String, Object>> recent = requests.selectByMember(memberId, 20);
        out.put("requests", recent);
        out.put("open", recent.stream().filter(r -> PENDING.equals(r.get("status")) || PAYING.equals(r.get("status")))
                .findFirst().orElse(null));
        return out;
    }

    public Map<String, Object> submit(Long memberId, Object rawAmount) {
        requireMember(memberId);
        if (!gate.isOpen(MoneyArea.WITHDRAWAL)) throw new BusinessRefusal(ErrorCode.FEATURE_OFF, "提现暂未开放");
        BigDecimal amount = parseAmount(rawAmount);
        Long id = tx.execute(status -> {
            if (!memberId.equals(members.lockActiveMemberById(memberId)))
                throw new BusinessRefusal(ErrorCode.ACCOUNT_DISABLED);
            UmsMember member = members.selectUmsMemberById(memberId);
            if (member == null) throw new BusinessRefusal(ErrorCode.USER_NOT_FOUND);
            if (Boolean.FALSE.equals(roles.getPermission(member).getWithdrawable()))
                throw new BusinessRefusal(ErrorCode.REFUSED, "当前身份不支持提现");
            if (!payoutWechatReady(member))
                throw new BusinessRefusal(ErrorCode.REFUSED, "请先绑定收款微信");
            BigDecimal balance = members.selectBalanceForUpdate(memberId);
            WithdrawalFrozenAmountResolver.Frozen held = frozen.resolve(memberId);
            BigDecimal available = (balance == null ? BigDecimal.ZERO : balance).subtract(held.total());
            if (available.compareTo(amount) < 0)
                throw new BusinessRefusal(ErrorCode.REFUSED, held.isEmpty() ? "可提现余额不足" : held.rejectionMessage());
            if (requests.countOpenByMember(memberId) > 0)
                throw new BusinessRefusal(ErrorCode.CONFLICT, "你已有一笔提现在审核或处理中");
            PayoutRequestMapper.PayoutRequestRow row = new PayoutRequestMapper.PayoutRequestRow();
            row.setMemberId(memberId);
            row.setAmount(amount);
            if (requests.insert(row) != 1 || row.getId() == null) throw new IllegalStateException("提现申请未保存");
            return row.getId();
        });
        return requests.selectById(id);
    }

    public Map<String, Object> cancel(Long memberId, Long id) {
        requireMember(memberId);
        if (id == null || id <= 0) throw new BusinessRefusal(ErrorCode.BAD_REQUEST);
        if (requests.cancelPending(id, memberId) != 1)
            throw new BusinessRefusal(ErrorCode.CONFLICT, "只有本人审核中的提现申请可以撤回");
        return requests.selectById(id);
    }

    public List<Map<String, Object>> adminList(String status, Integer limit) {
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (wanted != null && !STATUSES.contains(wanted)) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "未知状态");
        int capped = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return requests.selectAdminList(wanted, capped);
    }

    /** Admin console flags, so the page can say why approving is unavailable. */
    public Map<String, Object> adminGate() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("withdrawalOpen", gate.isOpen(MoneyArea.WITHDRAWAL));
        out.put("transferEnabled", gate.transferEnabled());
        return out;
    }

    public Map<String, Object> reject(Long id, Long sysUserId, String operator, String note) {
        String reason = requireNote(note);
        Map<String, Object> before = requireRequest(id);
        tx.executeWithoutResult(status -> {
            if (requests.reviewPending(id, REJECTED, operator, reason) != 1)
                throw new BusinessRefusal(ErrorCode.CONFLICT, "这笔申请已被处理，请刷新");
            audit.record(sysUserId, "PAYOUT_REQUEST_REJECT", "PAYOUT_REQUEST", id, before, Map.of("status", REJECTED), reason);
        });
        return requests.selectById(id);
    }

    /**
     * Approve and send. The review commits first (PENDING to PAYING, audited); the transfer then runs outside any
     * transaction, as {@link ICashWithdrawService#withdraw} requires. Its outcome is recorded on the request; the
     * transfer row stays the money record and the existing query task settles it.
     */
    public Map<String, Object> approve(Long id, Long sysUserId, String operator, String note) {
        String reason = requireNote(note);
        if (!gate.isOpen(MoneyArea.WITHDRAWAL)) throw new BusinessRefusal(ErrorCode.FEATURE_OFF, "资金开关「提现」未打开，不能打款");
        if (!gate.transferEnabled()) throw new BusinessRefusal(ErrorCode.FEATURE_OFF, "微信转账通道未开通，不能打款");
        Map<String, Object> before = requireRequest(id);
        tx.executeWithoutResult(status -> {
            if (requests.reviewPending(id, PAYING, operator, reason) != 1)
                throw new BusinessRefusal(ErrorCode.CONFLICT, "这笔申请已被处理，请刷新");
            audit.record(sysUserId, "PAYOUT_REQUEST_APPROVE", "PAYOUT_REQUEST", id, before, Map.of("status", PAYING), reason);
        });
        Long memberId = toLong(before.get("memberId"));
        BigDecimal amount = new BigDecimal(String.valueOf(before.get("amount")));
        try {
            MemberCashWithdraw sent = transfers.withdraw(memberId, amount, boundWalletOpenid(memberId));
            requests.markSent(id, sent == null ? null : sent.getId());
        } catch (IllegalStateException refused) {
            requests.markFailed(id, clip(refused.getMessage()));
        } catch (RuntimeException unknown) {
            log.warn("payout request {} transfer outcome unknown type={}", id, unknown.getClass().getSimpleName());
            requests.markFailed(id, "转账结果未知，请到「微信零钱提现」记录核对");
        }
        return requests.selectById(id);
    }

    /** A WeChat identity the transfer can pay: the member's own, or one bound from the app with a binding code. */
    boolean payoutWechatReady(UmsMember member) {
        return notBlank(member.getWxOpenId()) || notBlank(member.getAppOpenId()) || boundWalletOpenid(member.getId()) != null;
    }

    String boundWalletOpenid(Long memberId) {
        Map<String, Object> row = memberId == null ? null : bindings.selectWallet(memberId);
        Object openid = row == null ? null : row.get("openid");
        return openid == null || String.valueOf(openid).isBlank() ? null : String.valueOf(openid);
    }

    private Map<String, Object> requireRequest(Long id) {
        if (id == null || id <= 0) throw new BusinessRefusal(ErrorCode.BAD_REQUEST);
        Map<String, Object> row = requests.selectById(id);
        if (row == null) throw new BusinessRefusal(ErrorCode.NOT_FOUND, "提现申请不存在");
        return row;
    }

    static BigDecimal parseAmount(Object raw) {
        if (raw == null) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "请输入提现金额");
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "提现金额不合法");
        }
        if (amount.scale() > 2) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "提现金额最多两位小数");
        if (amount.compareTo(ICashWithdrawService.MIN_AMOUNT) < 0)
            throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "最低提现 ¥" + ICashWithdrawService.MIN_AMOUNT);
        if (amount.compareTo(MAX_AMOUNT) > 0)
            throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "单笔最多提现 ¥" + MAX_AMOUNT);
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "请填写审核说明");
        String trimmed = note.trim();
        if (trimmed.length() > NOTE_MAX) throw new BusinessRefusal(ErrorCode.BAD_REQUEST, "审核说明太长");
        return trimmed;
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new BusinessRefusal(ErrorCode.SIGN_IN_REQUIRED);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value));
    }

    private static String clip(String value) {
        if (value == null) return "转账未受理";
        return value.length() > NOTE_MAX ? value.substring(0, NOTE_MAX) : value;
    }
}
