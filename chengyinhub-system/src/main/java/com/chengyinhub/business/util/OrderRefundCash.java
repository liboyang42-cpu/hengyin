package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多票订单(parent_order_no)首单还能退多少现金 —— 小程序整单退(FX-01)与后台建单(FX-04)共用的唯一口径。
 */
public final class OrderRefundCash {

    private static final Logger logger = LoggerFactory.getLogger(OrderRefundCash.class);

    private OrderRefundCash() {
    }

    public static void assertOrderScope(List<CmsRegistration> order, CmsRegistration seed) {
        if (seed == null || seed.getOwnerType() == null || seed.getOwnerId() == null
                || order == null || order.isEmpty()) throw new ServiceException("原订单范围缺失，请人工核对", HttpStatus.CONFLICT);
        boolean found = false;
        for (CmsRegistration row : order) {
            if (row == null || !java.util.Objects.equals(row.getParentOrderNo(), seed.getParentOrderNo())
                    || !java.util.Objects.equals(row.getOwnerType(), seed.getOwnerType())
                    || !java.util.Objects.equals(row.getOwnerId(), seed.getOwnerId())) {
                throw new ServiceException("原订单包含不一致报名，请人工核对", HttpStatus.CONFLICT);
            }
            if (java.util.Objects.equals(row.getId(), seed.getId())) {
                found = true;
                if (!java.util.Objects.equals(row.getMemberId(), seed.getMemberId())) {
                    throw new ServiceException("报名归属已变化，请刷新后重试", HttpStatus.CONFLICT);
                }
            }
        }
        if (!found) throw new ServiceException("报名不在原订单中，请人工核对", HttpStatus.CONFLICT);
    }

    /**
     * FX-01:整单现金里还没退出去的部分 = 首单汇总额 − 同单其余票已批准(status=1)的退款额。
     * 拆不出单张票实付(积分/会员价按整单算),已退的那张到底退了多少,只有它自己的退款单说了算。
     * fail-closed:其余票挂着待审退款单(之后再批准就是第二次退),或已退却查不到已批准的退款单
     * (已退金额无从核对)⇒ 409,不按汇总额照退。
     */
    public static BigDecimal remaining(List<CmsRegistration> order, CmsRegistration payer, IRefundService refundService) {
        return remaining(order, payer, refundService, null);
    }

    public static BigDecimal remaining(List<CmsRegistration> order, CmsRegistration payer,
                                       IRefundService refundService, Long excludeRefundId) {
        return remaining(order, payer, refundService, excludeRefundId, false);
    }

    /** 系统整单取代须先裁定再驳回待审单：金额只排除未派发待审，不忽略任何已批准事实。 */
    public static BigDecimal remainingForSystemSupersession(List<CmsRegistration> order, CmsRegistration payer,
                                                            IRefundService refundService) {
        return remaining(order, payer, refundService, null, true);
    }

    private static BigDecimal remaining(List<CmsRegistration> order, CmsRegistration payer,
                                        IRefundService refundService, Long excludeRefundId, boolean superseding) {
        BigDecimal refunded = BigDecimal.ZERO;
        for (CmsRegistration m : order) {
            List<RefundApplication> apps = refundService.selectRegistrationHistoryForUpdate(m.getId());
            boolean approved = false;
            if (apps != null) {
                for (RefundApplication app : apps) {
                    if (excludeRefundId != null && excludeRefundId.equals(app.getId())) continue;
                    if (excludeRefundId == null && Integer.valueOf(0).equals(app.getStatus())
                            && (!superseding || !Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(app.getPayoutStatus()))) {
                        throw new ServiceException("同单有退款申请正在审核或派发事实不明,请稍后核对", HttpStatus.CONFLICT);
                    }
                    if (Integer.valueOf(1).equals(app.getStatus())) {
                        if (app.getRefundAmount() == null || app.getRefundAmount().signum() <= 0) {
                            throw new ServiceException("历史退款金额无法核对,请人工处理", HttpStatus.CONFLICT);
                        }
                        refunded = refunded.add(app.getRefundAmount());
                        approved = true;
                    }
                }
            }
            if (Integer.valueOf(4).equals(m.getPaymentStatus()) && !approved) {
                logger.warn("[退款转人工] 同单已退票查不到已批准退款单,已退金额无法核对 parentOrderNo={} registrationId={}",
                        m.getParentOrderNo(), m.getId());
                throw new ServiceException("同单已退金额无法核对,请联系客服人工处理", HttpStatus.CONFLICT);
            }
        }
        if (payer.getWechatPaymentAmount() == null) {
            throw new ServiceException("原订单现金实付快照缺失,请人工处理", HttpStatus.CONFLICT);
        }
        BigDecimal cash = payer.getWechatPaymentAmount();
        return cash.subtract(refunded);
    }
}
