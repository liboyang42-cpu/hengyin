package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.utils.StringUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RegistrationPaymentFactReceipt;
import com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RefundRequestResult;

/**
 * D-2:后台运营审核通过后不打款的修复 —— 把「原路退款 + 回写打款态」收成两路共用的唯一入口。
 *
 * 病:reviewRefund 只改审核态/冲销分账/标记报名,既不调 wechatPayService.refund 也不调 markPayoutResult。
 * 全仓 markPayoutResult 只有 TeamGroupServiceImpl 一个调用点(玩家自退),后台审核这条路径从未接线 ⇒
 * 运营在 /business/refund 点「通过」,用户 payment_status=4、分账冲销、库存回补都发生了,钱一分没退,
 * 且退款单的 payout_status 永远停在 0(处理中) —— 待人工队列也捞不到它,零告警。
 */
@Service
public class RefundPayoutServiceImpl implements IRefundPayoutService {

    private static final Logger logger = LoggerFactory.getLogger(RefundPayoutServiceImpl.class);
    private static final long DISPATCH_STALE_MINUTES = 30L;

    @Autowired
    private RefundApplicationMapper refundApplicationMapper;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private IRefundService refundService;

    @Autowired
    private IWechatPayService wechatPayService;

    @Autowired
    private RegistrationPaymentConsumptionReadService paymentConsumption;

    @Autowired
    private PlatformTransactionManager payoutTransactions;

    private record DispatchClaim(RefundApplication refund, boolean queryOnly) { }

    private TransactionTemplate payoutTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(payoutTransactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    @Override
    @CriticalPath(value = CriticalPathCode.CP_REFUND_FAILED, op = "payout")
    public void payoutAfterReview(Long refundId) {
        if (refundId == null) {
            return;
        }
        try {
            doPayout(refundId);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            // claim 前的 DB 故障仍保持 PENDING；claim 后故障保持 DISPATCHING，只能按原身份查证。
            // 此处不能无条件改成人工态，否则 CAS loser/瞬时 DB 抖动会覆盖另一个 worker 的真实结果。
            logger.error("退款派发编排失败 refundId={}", refundId, e);
        }
    }

    private void doPayout(Long refundId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("退款派发必须在原业务事务提交并退出后执行");
        RefundApplication refund = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (refund == null) {
            return;
        }
        // 只给审核通过的打款:驳回/待审核态打款 = 白送钱
        if (!Integer.valueOf(1).equals(refund.getStatus())) {
            return;
        }
        if (!"registration".equals(refund.getSourceType())) {
            return;
        }
        BigDecimal refundAmount = refund.getRefundAmount();
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (RestoreDispatchGuard.isRestoreMode()) {
            observeOriginalDuringRestore(refund);
            return;
        }
        RestoreDispatchGuard.requireDispatchAllowed();
        // Decide first submission versus recovery under the same original refund row lock as claim.
        // Otherwise a delayed worker can read PENDING before another worker's UNKNOWN and resubmit it.
        final RefundApplication observed = refund;
        String leaseToken = UUID.randomUUID().toString();
        DispatchClaim claim = payoutTransaction().execute(tx -> {
            RefundApplication current = refundApplicationMapper.selectRegistrationHistoryForUpdate(observed.getSourceId()).stream()
                    .filter(row -> refundId.equals(row.getId())).findFirst().orElse(null);
            if (current == null || !Integer.valueOf(1).equals(current.getStatus())
                    || !"registration".equals(current.getSourceType())
                    || !observed.getSourceId().equals(current.getSourceId())
                    || current.getRefundAmount() == null || current.getRefundAmount().signum() <= 0) return null;
            Integer previous = current.getPayoutStatus();
            if (previous != null && previous != RefundApplication.PAYOUT_DISPATCH_PENDING
                    && previous != RefundApplication.PAYOUT_DISPATCHING) return null;
            boolean queryOnly = Integer.valueOf(RefundApplication.PAYOUT_DISPATCHING).equals(previous)
                    || StringUtils.isNotBlank(current.getPayoutRemark());
            if (refundApplicationMapper.claimPayoutDispatch(refundId, DISPATCH_STALE_MINUTES, leaseToken) != 1)
                return null;
            return new DispatchClaim(current, queryOnly);
        });
        if (claim == null) return;
        refund = claim.refund();
        refundAmount = refund.getRefundAmount();
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(refund.getSourceId());
        if (registration == null) {
            // ⚠️ 不能静默 return —— 那正是 D-2 本身:钱不退、payout_status 停在 0(处理中)、
            // 而人工队列只捞 payout_status=2 ⇒ 这笔单没有任何人看得到。推进人工队列。
            if (claim.queryOnly()) retainUnknownDispatch(refundId, leaseToken, "原报名缺失，保留原退款查证");
            else refundService.markPayoutResult(refundId, leaseToken, false,
                    "报名单不存在(sourceId=" + refund.getSourceId() + "),无法原路退款,待人工");
            return;
        }
        BigDecimal paidCash = registration.getWechatPaymentAmount();
        if (paidCash == null || paidCash.compareTo(BigDecimal.ZERO) <= 0) {
            // 退款单要退现金,报名却没有现金实付 —— 数据自相矛盾(纯积分单本不该建现金退款单,
            // refundOne 只在 hasCash 时建)。同样不静默,转人工判断。
            if (claim.queryOnly()) retainUnknownDispatch(refundId, leaseToken, "原现金实付缺失，保留原退款查证");
            else refundService.markPayoutResult(refundId, leaseToken, false,
                    "报名无现金实付(wechat_payment_amount=" + paidCash + ")却存在现金退款单,待人工核对");
            return;
        }

        String expectedRefundNo = refund.getRefundNo();
        if (claim.queryOnly() && StringUtils.isBlank(expectedRefundNo)) {
            retainUnknownDispatch(refundId, leaseToken, "原退款号缺失，禁止更换身份重发");
            return;
        }
        if (StringUtils.isBlank(expectedRefundNo)) expectedRefundNo = "RF" + registration.getId();
        if (!expectedRefundNo.matches("RF" + registration.getId() + "(?:-[1-9][0-9]*)?")) {
            if (claim.queryOnly()) retainUnknownDispatch(refundId, leaseToken, "原退款编号冲突，禁止更换身份重发");
            else refundService.markPayoutResult(refundId, leaseToken, false, "退款编号与原报名不一致，请人工核对");
            return;
        }
        if (!ensureRefundNoOrEscalate(refundId, leaseToken, expectedRefundNo)) {
            return;
        }
        // The consumption receipt is immutable. Read it in its own short domain transaction,
        // after the claim transaction has released refund locks; never suspend held locks for SDK/fact work.
        final Long registrationId = refund.getSourceId();
        RegistrationPaymentFactReceipt receipt = payoutTransaction().execute(tx ->
                paymentConsumption.findConsumedPayment(registrationId).orElse(null));
        if (receipt == null || !registrationId.equals(receipt.registrationId())
                || !registration.getId().equals(receipt.registrationId())
                || !registration.getRegistrationNo().equals(receipt.registrationNo())
                || !receipt.transactionId().equals(registration.getTransactionId())) {
            retainUnknownDispatch(refundId, leaseToken, "原付款消费事实缺失或不符，禁止猜测退款来源");
            return;
        }
        if (claim.queryOnly()) {
            recoverOriginalRefund(refundId, leaseToken, receipt, expectedRefundNo, refundAmount, paidCash);
            return;
        }
        RefundRequestResult requestResult;
        String payoutRemark = null;
        try {
            // ⚠️ total_amount 传原订单实付额,不是本次退款额 —— 部分退时两者不等,传错会被微信按
            // 「退款额 > 订单额」拒掉。全额退时二者恰好相等,所以这个错只有部分退才暴露。
            requestResult = wechatPayService.submitRefund(receipt.reference(), registration.getRegistrationNo(), expectedRefundNo,
                    refundAmount, paidCash, refund.getReason());
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            // UNKNOWN keeps DISPATCHING: lease expiry authorizes a same-identity query, never a second create.
            payoutRemark = "原路退款调用异常:" + e.getMessage();
            retainUnknownDispatch(refundId, leaseToken, payoutRemark);
            logger.warn("微信退款调用结果不确定，保留原号待查 refundId={} remark={}", refundId, payoutRemark);
            return;
        }
        // 受理成功→PROCESSING(等退款结果通知回调确认到账);受理失败→PENDING_MANUAL 进人工队列 + 告警。
        // 不自动转平台余额——那会造成二清(平台留住微信款却只给用户内部余额记账),违《退款规则》。
        if (requestResult == null || requestResult == RefundRequestResult.UNKNOWN) {
            payoutRemark = "原路退款结果未知,等待按确定性退款号查单";
            retainUnknownDispatch(refundId, leaseToken, payoutRemark);
            return;
        }
        boolean wxAccepted = requestResult == RefundRequestResult.ACCEPTED;
        if (!wxAccepted) {
            payoutRemark = "原路退款受理失败(微信拒绝/CLOSED/ABNORMAL),待人工处理";
        }
        refundService.markPayoutResult(refundId, leaseToken, wxAccepted, payoutRemark);
    }

    /** No claim, number allocation, submission or synthetic failure during restore. */
    private void observeOriginalDuringRestore(RefundApplication refund) {
        if (StringUtils.isBlank(refund.getRefundNo()) || refund.getSourceId() == null) return;
        RegistrationPaymentFactReceipt receipt = payoutTransaction().execute(tx ->
                paymentConsumption.findConsumedPayment(refund.getSourceId()).orElse(null));
        if (receipt == null || !refund.getSourceId().equals(receipt.registrationId())) return;
        java.util.Map<String, String> facts = wechatPayService.queryRefundFacts(receipt.reference(), refund.getRefundNo());
        // The original verifier and inbox registration still run. RefundService pauses the economic
        // mutation after registration, so a valid SUCCESS is retained without a false DONE.
        if (facts != null) refundService.handleRefundNotify(facts);
    }

    private void recoverOriginalRefund(Long refundId, String leaseToken, RegistrationPaymentFactReceipt receipt,
            String refundNo, BigDecimal refundAmount, BigDecimal paidCash) {
        try {
            java.util.Map<String,String> facts = wechatPayService.queryRefundFacts(receipt.reference(), refundNo);
            boolean same = facts != null && refundNo.equals(facts.get("outRefundNo"))
                    && receipt.registrationNo().equals(facts.get("outTradeNo"))
                    && receipt.transactionId().equals(facts.get("transactionId"))
                    && "CNY".equals(facts.get("currency"))
                    && refundAmount.movePointRight(2).longValueExact() == Long.parseLong(facts.get("refund"))
                    && paidCash.movePointRight(2).longValueExact() == Long.parseLong(facts.get("total"));
            if (same && "PROCESSING".equals(facts.get("status"))) {
                refundService.markPayoutResult(refundId, leaseToken, true, "原退款查单确认处理中");
                return;
            }
            if (same && ("CLOSED".equals(facts.get("status")) || "ABNORMAL".equals(facts.get("status"))
                    || ("SUCCESS".equals(facts.get("status")) && facts.get("verified_fact_id") != null
                        && facts.get("verified_fact_hash") != null))
                    && refundService.handleRefundNotify(facts)) return;
        } catch (RuntimeException unavailable) {
            logger.warn("原退款恢复查证未知 refundId={} type={}", refundId, unavailable.getClass().getSimpleName());
        }
        retainUnknownDispatch(refundId, leaseToken, "原退款查证未知或本地未完成；保留原身份，禁止重发");
    }

    private void retainUnknownDispatch(Long refundId, String leaseToken, String remark) {
        if (refundApplicationMapper.updatePayoutResultWithLease(refundId, leaseToken,
                RefundApplication.PAYOUT_DISPATCHING, remark) == 1) return;
        RefundApplication current = refundApplicationMapper.selectRefundApplicationById(refundId);
        Integer state = current == null ? null : current.getPayoutStatus();
        if (Integer.valueOf(RefundApplication.PAYOUT_DISPATCHING).equals(state)
                || Integer.valueOf(RefundApplication.PAYOUT_PROCESSING).equals(state)
                || Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(state)
                || Integer.valueOf(RefundApplication.PAYOUT_PENDING_MANUAL).equals(state)
                || Integer.valueOf(RefundApplication.PAYOUT_MANUAL_HANDLED).equals(state)
                || Integer.valueOf(RefundApplication.PAYOUT_MANUAL_VERIFIED).equals(state)) return;
        throw new IllegalStateException("原退款未知状态未持久化 refundId=" + refundId);
    }

    /**
     * 固定退款业务号写入。真库上 uk_refund_no 是唯一键，别的退款单占着同一个号时 UPDATE 直接抛 1062，
     * 异常一路穿到 payoutAfterReview 的 catch 里只被 log 掉 —— 记录停在 DISPATCHING(6)，
     * 对账每 30 分钟原样重试一次，而待人工积压计数只数 (2,3)，这笔钱没人看得见。
     *
     * @return true = 业务号已是预期值，可以继续调微信；false = 已按冲突推进人工队列并告警，调用方直接返回。
     */
    private boolean ensureRefundNoOrEscalate(Long refundId, String leaseToken, String expectedRefundNo) {
        try {
            if (refundApplicationMapper.ensureRefundNo(refundId, expectedRefundNo) == 1) {
                return true;
            }
        } catch (DataIntegrityViolationException conflict) {   // DuplicateKeyException 是它的子类
            logger.warn("退款业务号唯一键冲突 refundId={} refundNo={}", refundId, expectedRefundNo, conflict);
        }
        // 冲突后回读:自己已经持有该号(受理成功但响应丢失/重试)则照常继续,否则转人工。
        RefundApplication current = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (current != null && expectedRefundNo.equals(current.getRefundNo())) {
            return true;
        }
        RefundApplication holder = refundApplicationMapper.selectByRefundNo(expectedRefundNo);
        String owner = holder == null || holder.getId() == null ? "未知退款单"
                : ("退款单 " + holder.getId());
        refundService.markPayoutResult(refundId, leaseToken, false,
                "退款业务号冲突,预期=" + expectedRefundNo + ",已被" + owner + "占用,待人工核对");
        return false;
    }

    private void releasePayoutDispatchOrVerify(Long refundId, String leaseToken, String remark) {
        if (refundApplicationMapper.releasePayoutDispatch(refundId, leaseToken, remark) == 1) {
            return;
        }
        RefundApplication current = refundApplicationMapper.selectRefundApplicationById(refundId);
        Integer status = current == null ? null : current.getPayoutStatus();
        if (Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(status)
                || Integer.valueOf(RefundApplication.PAYOUT_DISPATCHING).equals(status)
                || Integer.valueOf(RefundApplication.PAYOUT_PROCESSING).equals(status)
                || Integer.valueOf(RefundApplication.PAYOUT_SUCCESS).equals(status)
                || Integer.valueOf(RefundApplication.PAYOUT_PENDING_MANUAL).equals(status)) {
            // DISPATCHING(6):release CAS 因 lease token 不匹配失败,说明另一个 worker 在 stale
            // 窗口后已重新领取派发权。这是正常竞争,当前记录由新 owner 继续推进,静默放行。
            return;
        }
        throw new IllegalStateException("退款派发 lease 释放失败 refundId=" + refundId);
    }
}
