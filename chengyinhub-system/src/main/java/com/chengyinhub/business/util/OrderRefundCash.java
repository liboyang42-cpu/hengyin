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

    /**
     * FX-01:整单现金里还没退出去的部分 = 首单汇总额 − 同单其余票已批准(status=1)的退款额。
     * 拆不出单张票实付(积分/会员价按整单算),已退的那张到底退了多少,只有它自己的退款单说了算。
     * fail-closed:其余票挂着待审退款单(之后再批准就是第二次退),或已退却查不到已批准的退款单
     * (已退金额无从核对)⇒ 409,不按汇总额照退。
     */
    public static BigDecimal remaining(List<CmsRegistration> order, CmsRegistration payer, IRefundService refundService) {
        BigDecimal refunded = BigDecimal.ZERO;
        for (CmsRegistration m : order) {
            if (m.getId().equals(payer.getId())) {
                continue;
            }
            RefundApplication q = new RefundApplication();
            q.setSourceType("registration");
            q.setSourceId(m.getId());
            List<RefundApplication> apps = refundService.selectRefundList(q);
            boolean approved = false;
            if (apps != null) {
                for (RefundApplication app : apps) {
                    if (Integer.valueOf(0).equals(app.getStatus())) {
                        throw new ServiceException("同单有退款申请正在审核,请稍后再试", HttpStatus.CONFLICT);
                    }
                    if (Integer.valueOf(1).equals(app.getStatus()) && app.getRefundAmount() != null) {
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
        BigDecimal cash = payer.getWechatPaymentAmount() == null ? BigDecimal.ZERO : payer.getWechatPaymentAmount();
        return cash.subtract(refunded);
    }
}
