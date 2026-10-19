package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.OmsOrderPaymentCheck;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.OmsOrderPaymentCheckMapper;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.WechatOrderNotExistException;

/**
 * F0535 平台支付事实核对(只读 + 只追加回执)。
 *
 * <p>参照 Stripe 的 charge / transfer 分离:订单上「本地保存的状态」只是我们自己写下的结论,
 * 不是资金事实;资金事实只能来自渠道查询回执。因此:
 * <ul>
 *   <li>积分来源与现金来源分开展示 —— 购物车结算(含积分商城)以积分扣减,没有渠道交易;
 *       商业化订单(biz_type 非空)走微信 JSAPI,out_trade_no = order_sn。</li>
 *   <li>现金来源的结论只有三种:UNCONFIRMED(从未查询 / 查询失败 / 渠道处理中)、
 *       CONFIRMED(渠道回执与本地交易号、金额、状态一致)、CONFLICT(任一不一致)。</li>
 *   <li>查询只读渠道、只追加回执,不改订单、不补单、不退款 —— 冲突交给人工处置。</li>
 * </ul>
 */
@Service
public class OrderPaymentFactService
{
    private static final Logger logger = LoggerFactory.getLogger(OrderPaymentFactService.class);

    public static final String SOURCE_POINTS = "POINTS";
    public static final String SOURCE_CASH = "CASH";
    public static final String CHANNEL_WECHAT = "WECHAT";

    @Autowired
    private OmsOrderMapper omsOrderMapper;

    @Autowired
    private OmsOrderPaymentCheckMapper paymentCheckMapper;

    @Autowired
    private IWechatPayService wechatPayService;

    /** 订单支付事实视图;订单不存在返回 null */
    public Map<String, Object> facts(Long orderId)
    {
        OmsOrder order = orderId == null ? null : omsOrderMapper.selectOmsOrderById(orderId);
        if (order == null)
        {
            return null;
        }
        return view(order, isCash(order) ? paymentCheckMapper.selectLatestByOrderId(orderId) : null);
    }

    /**
     * 向渠道查询一次并落回执。积分订单没有渠道交易,拒绝查询(IllegalArgumentException)。
     * 渠道异常不抛给调用方:记为 FAILED 回执,结论 UNCONFIRMED —— 查询失败不是「已确认」也不是「冲突」。
     */
    public Map<String, Object> queryChannel(Long orderId, Long operatorId)
    {
        OmsOrder order = orderId == null ? null : omsOrderMapper.selectOmsOrderById(orderId);
        if (order == null)
        {
            return null;
        }
        if (!isCash(order))
        {
            throw new IllegalArgumentException("积分订单没有渠道交易，无需向支付渠道查询");
        }
        OmsOrderPaymentCheck check = new OmsOrderPaymentCheck();
        check.setOrderId(order.getId());
        check.setChannel(CHANNEL_WECHAT);
        check.setOutTradeNo(order.getOrderSn());
        check.setSavedStatus(order.getStatus() == null ? null : order.getStatus().intValue());
        check.setSavedTransactionId(order.getPaymentTransactionId());
        check.setSavedPayAmountFen(toFen(order.getPayAmount()));
        check.setOperatorId(operatorId);
        check.setQueryTime(new Date());
        try
        {
            Map<String, String> receipt = wechatPayService.queryOrderStatus(order.getOrderSn());
            check.setQueryStatus(OmsOrderPaymentCheck.QUERY_OK);
            check.setChannelTradeState(receipt == null ? null : receipt.get("trade_state"));
            check.setChannelTransactionId(receipt == null ? null : receipt.get("transaction_id"));
            check.setChannelTotalFen(parseFen(receipt == null ? null : receipt.get("total")));
        }
        catch (WechatOrderNotExistException e)
        {
            check.setQueryStatus(OmsOrderPaymentCheck.QUERY_NOT_EXIST);
        }
        catch (RuntimeException e)
        {
            logger.warn("订单支付事实渠道查询失败 orderId={} outTradeNo={}", order.getId(), order.getOrderSn(), e);
            check.setQueryStatus(OmsOrderPaymentCheck.QUERY_FAILED);
            check.setErrorMessage(truncate(e.getMessage(), 255));
        }
        String[] verdict = verdict(order, check);
        check.setVerdict(verdict[0]);
        check.setDetail(truncate(verdict[1], 255));
        if (paymentCheckMapper.insertPaymentCheck(check) != 1)
        {
            throw new IllegalStateException("支付事实查询回执写入失败");
        }
        return view(order, check);
    }

    // ------------------------------------------------------------------ 纯函数

    /** 现金来源判定:商业化订单或已有渠道交易号。购物车结算(含积分商城)一律积分来源。 */
    public static boolean isCash(OmsOrder order)
    {
        return order.getBizType() != null || notBlank(order.getPaymentTransactionId());
    }

    /** 本地状态是否声称已付款(1 待发货 / 2 已发货 / 3 待评价 / 4 已完成) */
    static boolean savedClaimsPaid(OmsOrder order)
    {
        long status = order.getStatus() == null ? -1L : order.getStatus();
        return status >= 1L && status <= 4L;
    }

    /** {verdict, detail} */
    public static String[] verdict(OmsOrder order, OmsOrderPaymentCheck check)
    {
        if (check == null || check.getQueryStatus() == null)
        {
            return new String[] {OmsOrderPaymentCheck.VERDICT_UNCONFIRMED, "尚未向渠道查询，本地状态未经核验"};
        }
        if (OmsOrderPaymentCheck.QUERY_FAILED.equals(check.getQueryStatus()))
        {
            return new String[] {OmsOrderPaymentCheck.VERDICT_UNCONFIRMED, "渠道查询失败，本地状态未经核验"};
        }
        boolean savedPaid = savedClaimsPaid(order);
        if (OmsOrderPaymentCheck.QUERY_NOT_EXIST.equals(check.getQueryStatus()))
        {
            return savedPaid
                    ? new String[] {OmsOrderPaymentCheck.VERDICT_CONFLICT, "渠道无此交易，但本地记为已付款"}
                    : new String[] {OmsOrderPaymentCheck.VERDICT_CONFIRMED, "渠道无此交易，本地也未记为已付款"};
        }
        String state = check.getChannelTradeState() == null ? "" : check.getChannelTradeState();
        Integer savedFen = toFen(order.getPayAmount());
        boolean sameTxn = notBlank(check.getChannelTransactionId())
                && check.getChannelTransactionId().equals(order.getPaymentTransactionId());
        boolean sameAmount = savedFen != null && savedFen.equals(check.getChannelTotalFen());
        switch (state)
        {
            case "SUCCESS":
                if (!savedPaid) return new String[] {OmsOrderPaymentCheck.VERDICT_CONFLICT, "渠道已收款，但本地未记为已付款"};
                if (!sameTxn) return new String[] {OmsOrderPaymentCheck.VERDICT_CONFLICT, "渠道交易号与本地保存的交易号不一致"};
                if (!sameAmount) return new String[] {OmsOrderPaymentCheck.VERDICT_CONFLICT, "渠道金额与本地实付金额不一致"};
                return new String[] {OmsOrderPaymentCheck.VERDICT_CONFIRMED, "渠道已收款，交易号与金额一致"};
            case "REFUND":
                boolean savedRefunded = order.getAftersaleStatus() != null && order.getAftersaleStatus() == 4L;
                return savedRefunded && sameTxn
                        ? new String[] {OmsOrderPaymentCheck.VERDICT_CONFIRMED, "渠道已转入退款，本地售后为退款成功"}
                        : new String[] {OmsOrderPaymentCheck.VERDICT_CONFLICT, "渠道已转入退款，本地未记录退款成功"};
            case "NOTPAY":
            case "CLOSED":
            case "REVOKED":
            case "PAYERROR":
                return savedPaid
                        ? new String[] {OmsOrderPaymentCheck.VERDICT_CONFLICT, "渠道未收款（" + state + "），但本地记为已付款"}
                        : new String[] {OmsOrderPaymentCheck.VERDICT_CONFIRMED, "渠道未收款（" + state + "），本地也未记为已付款"};
            default:
                return new String[] {OmsOrderPaymentCheck.VERDICT_UNCONFIRMED, "渠道状态 " + (state.isEmpty() ? "未知" : state) + "，暂不能确认"};
        }
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> view(OmsOrder order, OmsOrderPaymentCheck latest)
    {
        Map<String, Object> facts = new LinkedHashMap<>();
        boolean cash = isCash(order);
        facts.put("orderId", order.getId());
        facts.put("orderSn", order.getOrderSn());
        facts.put("source", cash ? SOURCE_CASH : SOURCE_POINTS);
        facts.put("savedStatus", order.getStatus());
        facts.put("savedStatusVerified", false);
        if (!cash)
        {
            Map<String, Object> points = new LinkedHashMap<>();
            points.put("pointsPrice", order.getPayAmount());
            points.put("pointsAmountColumn", order.getPointsAmount());
            points.put("note", "购物车结算按整单商品价格向上取整扣减积分，没有支付渠道交易");
            facts.put("points", points);
            return facts;
        }
        Map<String, Object> cashPart = new LinkedHashMap<>();
        cashPart.put("channel", CHANNEL_WECHAT);
        cashPart.put("outTradeNo", order.getOrderSn());
        cashPart.put("savedTransactionId", order.getPaymentTransactionId());
        cashPart.put("savedPayAmount", order.getPayAmount());
        cashPart.put("savedPaymentTime", order.getPaymentTime());
        String[] verdict = latest == null ? verdict(order, null) : new String[] {latest.getVerdict(), latest.getDetail()};
        cashPart.put("verdict", verdict[0]);
        cashPart.put("verdictDetail", verdict[1]);
        cashPart.put("lastQueryTime", latest == null ? null : latest.getQueryTime());
        cashPart.put("receipt", latest);
        facts.put("cash", cashPart);
        return facts;
    }

    static Integer toFen(BigDecimal amount)
    {
        if (amount == null) return null;
        try
        {
            return amount.movePointRight(2).intValueExact();
        }
        catch (ArithmeticException e)
        {
            return null;
        }
    }

    private static Integer parseFen(String total)
    {
        if (total == null || total.trim().isEmpty()) return null;
        try
        {
            return Integer.valueOf(total.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static boolean notBlank(String value)
    {
        return value != null && !value.trim().isEmpty();
    }

    private static String truncate(String value, int max)
    {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
