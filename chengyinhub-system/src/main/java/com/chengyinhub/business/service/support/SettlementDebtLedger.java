package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberSettlementDebt;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 收款方欠款台账(2026-09-16 晚拍板第 2 条):客诉期后仍发生退款/补偿,从该收款方后续结算扣;
 * 扣不够记欠款、限期补足,逾期未补不能发布新活动。平台不垫付。
 *
 * <p>在 D-3「余额扣到 0、差额转人工」之上改:差额不再只是一条告警,而是一行可追的欠款。
 * 三个方法都不开事务,必须在调用方的资金事务里执行(与余额变动同生共死)。
 */
@Component
public class SettlementDebtLedger {

    /** 限期补足天数(总控默认口径,见 拍板实施_0917/资金_客诉期提现闸.md)。 */
    public static final int REPAY_DAYS = 30;
    /** 余额明细事件:欠款从结算入账中抵扣。 */
    public static final long EVENT_TYPE_DEBT_OFFSET = 93L;
    public static final String SOURCE_REFUND = "refund";
    public static final String SOURCE_COMPLAINT = "complaint";
    /** 平台人工倒扣(merchant_clawback_application.id)。 */
    public static final String SOURCE_CLAWBACK = "clawback";

    private final UmsMemberSettlementDebtMapper debtMapper;
    private final UmsMemberMapper umsMemberMapper;
    private final UmsMemberBalanceDetailMapper balanceDetailMapper;

    public SettlementDebtLedger(UmsMemberSettlementDebtMapper debtMapper, UmsMemberMapper umsMemberMapper,
                                UmsMemberBalanceDetailMapper balanceDetailMapper) {
        this.debtMapper = debtMapper;
        this.umsMemberMapper = umsMemberMapper;
        this.balanceDetailMapper = balanceDetailMapper;
    }

    /**
     * 余额扣不动的差额记为欠款;同一来源重复调用只记一次(幂等键 source_type+source_id+member_id)。
     * 同一笔退款对同一会员可能有多条收款方行(merchant/creator),调用方须把收款方类型拼进 sourceType。
     */
    public void record(Long memberId, String sourceType, Long sourceId, BigDecimal shortfall) {
        if (shortfall == null || shortfall.signum() <= 0) {
            return;
        }
        if (memberId == null || sourceId == null || sourceType == null) {
            // 有差额却记不下来 = 平台静默垫付,必须让资金事务整体回滚
            throw new IllegalStateException("欠款无法登记 memberId=" + memberId + " source=" + sourceType + ":" + sourceId);
        }
        UmsMemberSettlementDebt debt = new UmsMemberSettlementDebt();
        debt.setMemberId(memberId);
        debt.setSourceType(sourceType);
        debt.setSourceId(sourceId);
        debt.setAmount(shortfall);
        debt.setDueTime(new Date(System.currentTimeMillis() + REPAY_DAYS * 24L * 3600 * 1000));
        debtMapper.insertIgnore(debt);
    }

    /** 原分项ID唯一且不可释放；同身份不同本金必须冲突，不能把INSERT IGNORE当成功回执。 */
    public void recordOriginalRefund(Long memberId, Long adjustmentId, BigDecimal shortfall) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("ORIGINAL_REFUND_DEBT_TRANSACTION_REQUIRED");
        }
        if (memberId == null || memberId <= 0 || adjustmentId == null || adjustmentId <= 0 || shortfall == null || shortfall.signum() <= 0) {
            throw new IllegalArgumentException("原分项退款欠款身份/金额缺失");
        }
        String sourceType = "coop_refund_component";
        UmsMemberSettlementDebt requested = new UmsMemberSettlementDebt();
        requested.setMemberId(memberId); requested.setSourceType(sourceType); requested.setSourceId(adjustmentId);
        requested.setAmount(shortfall); requested.setDueTime(new Date(System.currentTimeMillis() + REPAY_DAYS * 24L * 3600 * 1000));
        if (debtMapper.insertIgnore(requested) == 1) return;
        UmsMemberSettlementDebt stored = debtMapper.selectBySourceForUpdate(memberId, sourceType, adjustmentId);
        if (stored == null || stored.getAmount() == null || stored.getAmount().compareTo(shortfall) != 0
                || !memberId.equals(stored.getMemberId()) || !adjustmentId.equals(stored.getSourceId())
                || !sourceType.equals(stored.getSourceType())) {
            throw new IllegalStateException("ORIGINAL_REFUND_DEBT_IDENTITY_CONFLICT");
        }
    }

    /**
     * 收款方刚入账 credited 元结算款后调用:先抵未清欠款,抵扣额不超过本次入账。
     *
     * @return 本次抵掉的金额
     */
    public BigDecimal offsetFromSettlement(Long memberId, BigDecimal credited, Long eventId) {
        if (memberId == null || credited == null || credited.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        // 加锁读当前已提交余额(普通 select 在 RR 下读快照,可能比真实余额高 ⇒ deductBalance 失败回滚)
        BigDecimal before = umsMemberMapper.selectBalanceForUpdate(memberId);
        if (before == null) {
            throw new IllegalStateException("欠款抵扣收款方不存在 memberId=" + memberId);
        }
        // 抵扣额不超过本次入账,也不超过当前余额(存量负余额 / 超过余额会让 deductBalance 恒失败、
        // 整笔结算每轮回滚卡死)。抵不动的部分留在欠款里。
        BigDecimal left = credited.min(before.max(BigDecimal.ZERO));
        BigDecimal offset = BigDecimal.ZERO;
        for (UmsMemberSettlementDebt debt : debtMapper.selectOpenByMemberForUpdate(memberId)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal remaining = debt.getAmount().subtract(debt.getRepaidAmount());
            BigDecimal take = remaining.min(left);
            if (take.signum() <= 0) {
                continue;
            }
            if (debtMapper.addRepaid(debt.getId(), take) != 1) {
                throw new IllegalStateException("欠款抵扣更新失败 debtId=" + debt.getId());
            }
            left = left.subtract(take);
            offset = offset.add(take);
        }
        if (offset.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (umsMemberMapper.deductBalance(memberId, offset) != 1) {
            throw new IllegalStateException("欠款抵扣余额扣减失败 memberId=" + memberId + " offset=" + offset);
        }
        UmsMemberBalanceDetail flow = new UmsMemberBalanceDetail();
        flow.setMemberId(memberId);
        flow.setCurrentBalance(before);
        flow.setAfterBalance(before.subtract(offset));
        flow.setChangeBalance(offset.negate());
        flow.setChangeReason("欠款抵扣");
        flow.setEventType(EVENT_TYPE_DEBT_OFFSET);
        flow.setEventId(eventId);
        flow.setChangeType(2L);
        flow.setDelFlag(0L);
        flow.setCreateTime(new Date());
        if (balanceDetailMapper.insertUmsMemberBalanceDetail(flow) != 1) {
            throw new IllegalStateException("欠款抵扣流水写入失败 memberId=" + memberId);
        }
        return offset;
    }

    /**
     * 是否有到期未补足的欠款。不抛异常 —— 给不能被本闸回滚外层事务的回调路径用
     * (如审核通过回调里的自办活动壳补建:抛出会把 review() 的审核结论一起回滚)。
     */
    public boolean hasOverdueDebt(Long memberId) {
        return memberId != null && debtMapper.countOverdueByMember(memberId) > 0;
    }

    /** 到期未补足的欠款 ⇒ 不能发布新活动 / 开新场次。 */
    public void requireNoOverdueDebt(Long memberId) {
        if (hasOverdueDebt(memberId)) {
            throw new ServiceException("SETTLEMENT_DEBT_OVERDUE:你有逾期未补足的欠款,暂不能发布新活动,请联系平台客服处理");
        }
    }
}
