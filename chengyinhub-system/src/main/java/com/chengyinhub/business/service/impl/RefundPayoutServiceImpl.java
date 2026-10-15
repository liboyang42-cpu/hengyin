package com.chengyinhub.business.service.impl;

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

    @Override
    public void payoutAfterReview(Long refundId) {
        if (refundId == null) {
            return;
        }
        try {
            doPayout(refundId);
        } catch (Exception e) {
            // claim 前的 DB 故障仍保持 PENDING；claim 后故障则保持 DISPATCHING，都会由超时对账重试。
            // 此处不能无条件改成人工态，否则 CAS loser/瞬时 DB 抖动会覆盖另一个 worker 的真实结果。
            logger.error("退款派发编排失败 refundId={}", refundId, e);
        }
    }

    private void doPayout(Long refundId) {
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
        // 只有待派发/遗留空态，或超过 30 分钟的派发中记录能领取。PROCESSING 只能查微信真实态，
        // PENDING_MANUAL 只能人工处理；它们都绝不能再次调用退款。
        // 只传「多少分钟算陈旧」,基准时间 NOW() 由 MySQL 自己取 —— 绝不从这里传 Date 过去比。
        // 那会让互斥判定依赖「JVM 时区 == MySQL 服务器时区」这个没有任何东西保证的前提;
        // 一旦不一致,偏差以小时计,30 分钟租约恒被判为陈旧,并发 worker 会重复调微信退款。
        String leaseToken = UUID.randomUUID().toString();
        if (refundApplicationMapper.claimPayoutDispatch(refundId, DISPATCH_STALE_MINUTES, leaseToken) != 1) {
            return;
        }
        refund = refundApplicationMapper.selectRefundApplicationById(refundId);
        if (refund == null) {
            throw new IllegalStateException("领取退款派发权后记录不存在 refundId=" + refundId);
        }
        CmsRegistration registration = cmsRegistrationMapper.selectCmsRegistrationById(refund.getSourceId());
        if (registration == null) {
            // ⚠️ 不能静默 return —— 那正是 D-2 本身:钱不退、payout_status 停在 0(处理中)、
            // 而人工队列只捞 payout_status=2 ⇒ 这笔单没有任何人看得到。推进人工队列。
            refundService.markPayoutResult(refundId, leaseToken, false,
                    "报名单不存在(sourceId=" + refund.getSourceId() + "),无法原路退款,待人工");
            return;
        }
        BigDecimal paidCash = registration.getWechatPaymentAmount();
        if (paidCash == null || paidCash.compareTo(BigDecimal.ZERO) <= 0) {
            // 退款单要退现金,报名却没有现金实付 —— 数据自相矛盾(纯积分单本不该建现金退款单,
            // refundOne 只在 hasCash 时建)。同样不静默,转人工判断。
            refundService.markPayoutResult(refundId, leaseToken, false,
                    "报名无现金实付(wechat_payment_amount=" + paidCash + ")却存在现金退款单,待人工核对");
            return;
        }

        String expectedRefundNo = refund.getRefundNo();
        if (StringUtils.isBlank(expectedRefundNo)) expectedRefundNo = "RF" + registration.getId();
        if (!expectedRefundNo.matches("RF" + registration.getId() + "(?:-[1-9][0-9]*)?")) {
            refundService.markPayoutResult(refundId, leaseToken, false, "退款编号与原报名不一致，请人工核对");
            return;
        }
        if (!ensureRefundNoOrEscalate(refundId, leaseToken, expectedRefundNo)) {
            return;
        }
        RefundRequestResult requestResult;
        String payoutRemark = null;
        try {
            // ⚠️ total_amount 传原订单实付额,不是本次退款额 —— 部分退时两者不等,传错会被微信按
            // 「退款额 > 订单额」拒掉。全额退时二者恰好相等,所以这个错只有部分退才暴露。
            requestResult = wechatPayService.submitRefund(registration.getRegistrationNo(), expectedRefundNo,
                    refundAmount, paidCash, refund.getReason());
        } catch (Exception e) {
            // 网络异常无法证明微信没受理。回到 PENDING，让确定性 out_refund_no 由对账安全重试；
            // 若回调已经先到终态，release 的 status=6 CAS 会失败，不覆盖真实结果。
            payoutRemark = "原路退款调用异常:" + e.getMessage();
            releasePayoutDispatchOrVerify(refundId, leaseToken, payoutRemark);
            logger.warn("微信退款调用结果不确定，已释放待重试 refundId={} remark={}", refundId, payoutRemark);
            return;
        }
        // 受理成功→PROCESSING(等退款结果通知回调确认到账);受理失败→PENDING_MANUAL 进人工队列 + 告警。
        // 不自动转平台余额——那会造成二清(平台留住微信款却只给用户内部余额记账),违《退款规则》。
        if (requestResult == RefundRequestResult.UNKNOWN) {
            payoutRemark = "原路退款结果未知,等待按确定性退款号查单";
            releasePayoutDispatchOrVerify(refundId, leaseToken, payoutRemark);
            return;
        }
        boolean wxAccepted = requestResult == RefundRequestResult.ACCEPTED;
        if (!wxAccepted) {
            payoutRemark = "原路退款受理失败(微信拒绝/CLOSED/ABNORMAL),待人工处理";
        }
        refundService.markPayoutResult(refundId, leaseToken, wxAccepted, payoutRemark);
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
