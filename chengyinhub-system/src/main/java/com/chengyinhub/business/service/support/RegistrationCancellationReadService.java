package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper;
import com.chengyinhub.business.util.RefundPolicy;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 取消执行成功后的只读反馈；不执行退款，也不改变报名或积分状态。 */
@Service
public class RegistrationCancellationReadService {
    private static final Logger logger = LoggerFactory.getLogger(RegistrationCancellationReadService.class);
    @Autowired
    private ActivityCancelManualRefundCaseMapper activityCancelManualRefundCaseMapper;
    private final ICmsRegistrationService registrationService;
    private final IRefundService refundService;

    public RegistrationCancellationReadService(ICmsRegistrationService registrationService,
                                               IRefundService refundService) {
        this.registrationService = registrationService;
        this.refundService = refundService;
    }

    /** 取消事务已返回后只读报名、父单和退款单；不以取消资格推断退款到账。 */
    public Result read(Long registrationId) {
        try {
            return readCancellationFeedback(registrationId);
        } catch (Exception ex) {
            logger.warn("取消已提交但结果读取失败 registrationId={}", registrationId, ex);
            return unconfirmedCancellationFeedback(registrationId, "取消操作已提交，结果暂时无法读取，请刷新订单确认");
        }
    }
    /**
     * 取消动作前只读本人既有人工案件；命中即返回权威进度，调用方不得再次执行退款。
     * mapper 同时校验报名 member_id、payer_member_id、owner 和冻结报名集合，避免越权探测。
     */
    public Result readPlayerManualRefundCase(Long registrationId, Long memberId) {
        if (registrationId == null || registrationId <= 0 || memberId == null || memberId <= 0) {
            return null;
        }
        String status = activityCancelManualRefundCaseMapper.selectPlayerCaseStatus(registrationId, memberId);
        if (StringUtils.isEmpty(status)) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", registrationId);
        data.put("cancellationStatus", "MANUAL_REVIEW");
        data.put("cashRefundStatus", "MANUAL_REVIEW");
        data.put("cashRefundAmount", null);
        data.put("manualRefundCaseStatus", status);
        return new Result(manualRefundCaseMessage(status, null), data);
    }

    private String manualRefundCaseMessage(String status, Boolean activityDetail) {
        switch (status) {
            case ActivityCancelManualRefundCase.STATUS_NO_REFUND:
                return "人工评估结果：不予退款";
            case ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED:
                return "人工评估已通过，等待退款处理";
            case ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED:
                return "人工退款已登记，等待凭证复核";
            case ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED:
                return "人工退款凭证已复核";
            default:
                if (activityDetail == null) {
                    return "本单已转平台人工评估，尚未退款";
                }
                return activityDetail ? "活动已取消，该订单正在人工评估退款" : "该订单正在人工评估退款";
        }
    }


    private Result unconfirmedCancellationFeedback(Long registrationId, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", registrationId);
        data.put("cancellationStatus", "UNCONFIRMED");
        data.put("cashRefundStatus", "UNCONFIRMED");
        data.put("cashRefundAmount", null);
        data.put("pointsRefundStatus", "UNCONFIRMED");
        data.put("pointsUsed", null);
        data.put("pointsReturned", null);
        return new Result(message, data);
    }

    private Result readCancellationFeedback(Long registrationId) {
        CmsRegistration row = registrationService.selectCmsRegistrationById(registrationId);
        if (row == null) return unconfirmedCancellationFeedback(registrationId, "取消操作已提交，报名状态尚未确认，请刷新订单");
        boolean parentOrder = StringUtils.isNotEmpty(row.getParentOrderNo());
        List<CmsRegistration> order = Collections.singletonList(row);
        if (parentOrder) {
            CmsRegistration query = new CmsRegistration();
            query.setParentOrderNo(row.getParentOrderNo());
            order = registrationService.selectCmsRegistrationList(query);
        }
        if (order == null || order.isEmpty()) {
            return unconfirmedCancellationFeedback(registrationId, "取消操作已提交，订单状态尚未确认，请刷新订单");
        }
        boolean cancelled = true;
        long pointsUsed = 0;
        long pointsReturned = 0;
        boolean pointsKnown = true;
        CmsRegistration payer = parentOrder ? null : row;
        boolean parentCashKnown = true;
        for (CmsRegistration member : order) {
            cancelled &= Integer.valueOf(3).equals(member.getRegistrationStatus());
            long used = member.getPointUsed() == null ? 0 : member.getPointUsed();
            if (used < 0) {
                pointsKnown = false;
            } else if (pointsKnown) {
                try {
                    pointsUsed = Math.addExact(pointsUsed, used);
                    if (Integer.valueOf(1).equals(member.getPointsReturned())) {
                        pointsReturned = Math.addExact(pointsReturned, used);
                    }
                } catch (ArithmeticException overflow) {
                    pointsKnown = false;
                }
            }
            // 与 refundWholeOrder 一致：首单承载汇总应付额，从单 wechatPaymentAmount 不代表真实支付。
            if (parentOrder && (member.getPayableAmount() == null || member.getPayableAmount().signum() < 0)) {
                parentCashKnown = false;
            }
            if (parentOrder && member.getPayableAmount() != null && member.getPayableAmount().signum() > 0) {
                if (payer != null) return unconfirmedCancellationFeedback(registrationId, "取消操作已提交，订单支付归属尚未确认，请刷新订单");
                payer = member;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", registrationId);
        data.put("scope", parentOrder ? "PARENT_ORDER" : "REGISTRATION");
        data.put("cancellationStatus", cancelled ? "CANCELLED" : "UNCONFIRMED");
        String cashMessage = "无现金退款";
        data.put("cashRefundStatus", "NOT_NEEDED");
        data.put("cashRefundAmount", java.math.BigDecimal.ZERO);
        // 旧单可能没有 wechat_payment_amount；与订单详情相同，只在缺失时回落应付快照。
        // 整单必须使用首单的汇总应付额，不能从从单的历史现金占位字段推断付款。
        java.math.BigDecimal cash;
        if (parentOrder) {
            cash = parentCashKnown ? (payer == null ? java.math.BigDecimal.ZERO : payer.getPayableAmount()) : null;
        } else {
            cash = row.getWechatPaymentAmount() == null ? row.getPayableAmount() : row.getWechatPaymentAmount();
        }
        if (cash == null || cash.signum() < 0) {
            cashMessage = cashRefundFeedback(null, data);
        } else if (cash.signum() > 0) {
            RefundApplication refund = null;
            try {
                refund = latestRegistrationRefund(payer.getId(), payer.getMemberId());
            } catch (Exception ex) {
                logger.warn("取消已提交但现金退款状态读取失败 registrationId={}", payer.getId(), ex);
            }
            cashMessage = cashRefundFeedback(refund, data);
        }
        data.put("pointsUsed", pointsKnown ? pointsUsed : null);
        data.put("pointsReturned", pointsKnown ? pointsReturned : null);
        data.put("pointsRefundStatus", !pointsKnown ? "UNCONFIRMED" : pointsUsed == 0 ? "NOT_NEEDED"
                : pointsUsed == pointsReturned ? "RETURNED" : pointsReturned > 0 ? "PARTIAL" : "UNCONFIRMED");
        String pointsMessage = !pointsKnown ? "本单积分返还结果尚未确认，请查看订单" : pointsUsed == 0 ? "本单未使用积分"
                : pointsUsed == pointsReturned ? "本单已返还" + pointsReturned + "积分"
                : "本单已返还" + pointsReturned + "/" + pointsUsed + "积分，其余尚未确认";
        return new Result((cancelled ? "报名已取消" : "取消操作已提交，报名状态尚未确认")
                + "，" + cashMessage + "，" + pointsMessage, data);
    }

    private String cashRefundFeedback(RefundApplication refund, Map<String, Object> data) {
        data.put("cashRefundStatus", "UNCONFIRMED");
        data.put("cashRefundAmount", null);
        if (refund == null || !Integer.valueOf(1).equals(refund.getStatus())
                || refund.getPayoutStatus() == null) return "现金退款结果尚未确认，请查看订单";
        data.put("cashRefundAmount", refund.getRefundAmount());
        switch (refund.getPayoutStatus()) {
            case RefundApplication.PAYOUT_DISPATCH_PENDING:
                data.put("cashRefundStatus", "DISPATCH_PENDING");
                return "现金退款已受理，尚未派发";
            case RefundApplication.PAYOUT_DISPATCHING:
                data.put("cashRefundStatus", "DISPATCHING");
                return "现金退款正在派发，尚未确认到账";
            case RefundApplication.PAYOUT_PROCESSING:
                data.put("cashRefundStatus", "PROCESSING");
                return "现金退款渠道处理中，尚未确认到账";
            case RefundApplication.PAYOUT_SUCCESS:
                data.put("cashRefundStatus", "SUCCESS");
                return "现金退款已确认到账";
            case RefundApplication.PAYOUT_PENDING_MANUAL:
                data.put("cashRefundStatus", "PENDING_MANUAL");
                return "现金退款失败，待人工处理";
            case RefundApplication.PAYOUT_MANUAL_HANDLED:
                data.put("cashRefundStatus", "MANUAL_HANDLED");
                return "现金退款已人工处理，待复核";
            case RefundApplication.PAYOUT_MANUAL_VERIFIED:
                data.put("cashRefundStatus", "MANUAL_VERIFIED");
                return "现金退款已完成人工复核";
            default:
                return "现金退款结果尚未确认，请查看订单";
        }
    }

    /** 只按报名所属玩家和本报名单查询最新退款单，打款状态不能由 registrationStatus 推断。 */
    private RefundApplication latestRegistrationRefund(Long registrationId, Long memberId) {
        if (registrationId == null || memberId == null || memberId <= 0) {
            return null;
        }
        RefundApplication query = new RefundApplication();
        query.setSourceType("registration");
        query.setSourceId(registrationId);
        query.setMemberId(memberId);
        List<RefundApplication> rows = refundService.selectRefundList(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** 人工案件覆盖普通时间窗；仅读取玩家自身冻结订单的状态，不暴露内部评估与凭证。 */
    public void attachManualRefundInfo(CmsRegistration registration) {
        // FX-05:主题票(owner_type=1)部分核销也会记工单(挂 topic_id),同样要读回状态。
        if (registration == null
                || !(Long.valueOf(2L).equals(registration.getOwnerType()) || Long.valueOf(1L).equals(registration.getOwnerType()))
                || StringUtils.isEmpty(registration.getParentOrderNo())) {
            return;
        }
        String status = activityCancelManualRefundCaseMapper.selectPlayerCaseStatus(
                registration.getId(), registration.getMemberId());
        registration.setManualRefundCaseStatus(status);
        if (status == null) return;
        boolean activity = Long.valueOf(2L).equals(registration.getOwnerType());
        String reason = manualRefundCaseMessage(status, activity);
        registration.setRefundInfo(new RefundPolicy.Verdict(
                false, reason, null, !ActivityCancelManualRefundCase.STATUS_NO_REFUND.equals(status)
                && !ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED.equals(status)));
    }

    public static final class Result {
        private final String message;
        private final Map<String, Object> data;

        private Result(String message, Map<String, Object> data) {
            this.message = message;
            this.data = data;
        }

        public String getMessage() { return message; }
        public Map<String, Object> getData() { return data; }
    }
}
