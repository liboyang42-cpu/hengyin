package com.chengyinhub.business.payout;

import java.util.List;
import java.util.Map;

/**
 * 微信支付电商收付通的最小调用面。生产实现 {@link WechatEcommercePayGateway} 只在开关打开且凭据齐全时才构建 SDK 客户端；
 * 单测用桩实现。所有金额单位为分。
 */
public interface EcommercePayGateway {

    /** 二级商户进件提交结果。 */
    record ApplymentSubmitted(Long applymentId) { }

    /** 进件查询结果（GET /v3/ecommerce/applyments/out-request-no/{out_request_no}）。 */
    record ApplymentStatus(Long applymentId, String state, String stateDesc, String signUrl, String subMchid) { }

    /** 分账请求/查询结果。state：PROCESSING/FINISHED/CLOSED 或 SDK 的 PENDING/SUCCESS/CLOSED。 */
    record ShareResult(String state, String wxOrderId, List<ReceiverResult> receivers) { }

    record ReceiverResult(String receiverMchid, long amountFen, String result, String failReason) { }

    /** 分账回退结果：result PROCESSING/SUCCESS/FAILED。 */
    record ReturnResult(String result, String wxReturnNo, String failReason) { }

    /** 电商收付通退款受理结果（POST /v3/ecommerce/refunds/apply）。 */
    record RefundAccepted(String refundId, String outRefundNo) { }

    /** 普通支付（服务商 JSAPI，带 sub_mchid、settle_info.profit_sharing=true）的小程序调起参数。 */
    Map<String, String> prepayJsapi(String subMchid, String outTradeNo, long totalFen, String description, String spOpenid);

    ApplymentSubmitted submitApplyment(String outRequestNo, Map<String, Object> applymentBody);

    ApplymentStatus queryApplyment(String outRequestNo);

    ShareResult createProfitSharing(String subMchid, String transactionId, String outOrderNo,
                                    List<ProfitSharePlanner.Receiver> receivers, boolean finish);

    ShareResult queryProfitSharing(String subMchid, String transactionId, String outOrderNo);

    ReturnResult createReturn(String subMchid, String outOrderNo, String outReturnNo, String returnMchid,
                              long amountFen, String description);

    ReturnResult queryReturn(String subMchid, String outOrderNo, String outReturnNo);

    void finishProfitSharing(String subMchid, String transactionId, String outOrderNo, String description);

    RefundAccepted createRefund(String subMchid, String transactionId, String outRefundNo, long refundFen,
                                long totalFen, String reason);

    // ---- l-payout2：接入现有支付链路 ----

    /** 服务商 JSAPI 原单（查单或已验签通知）的事实。tradeState/tradeType 用微信枚举名。 */
    record PartnerOrder(String spMchid, String subMchid, String spAppid, String outTradeNo, String transactionId,
                        String tradeState, String tradeType, Long totalFen, String currency, String successTime) { }

    /** 电商收付通退款查询结果（GET /v3/ecommerce/refunds/out-refund-no/{out_refund_no}）。 */
    record RefundQuery(String outRefundNo, String refundId, String outTradeNo, String transactionId, String status,
                       Long refundFen, String currency, String successTime) { }

    /** 已验签的电商退款通知里可信的定位字段；业务事实一律另行查单核对。 */
    record RefundNotice(String spMchid, String subMchid, String outRefundNo) { }

    /** 服务商 JSAPI 原单不存在（404 ORDER_NOT_EXIST）。 */
    final class PartnerOrderNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PartnerOrderNotFound(String message, Throwable cause) { super(message, cause); }
    }

    /** 电商退款单不存在（404 RESOURCE_NOT_EXISTS）。 */
    final class RefundNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public RefundNotFound(String message, Throwable cause) { super(message, cause); }
    }

    /** 服务商 JSAPI 支付通知地址（冻结支付意图时作为原请求指纹的一部分）。 */
    String paymentNotifyUrl();

    PartnerOrder queryPartnerOrder(String subMchid, String outTradeNo);

    void closePartnerOrder(String subMchid, String outTradeNo);

    /** 用电商平台商户号的 APIv3 密钥与平台证书验签并解密支付通知；验签失败抛 SDK 的 ValidationException。 */
    PartnerOrder parsePaymentNotification(String body, Map<String, String> headers);

    RefundNotice parseRefundNotification(String body, Map<String, String> headers);

    RefundQuery queryRefund(String subMchid, String outRefundNo);

    // ---- l-payout3：合单支付（电商收付通，combine_mchid 与子单 mchid 都是电商平台商户号，sub_mchid 为二级商户号） ----

    /** 合单下单的一笔子单。 */
    record CombinedSubRequest(String subMchid, String outTradeNo, long amountFen, String description, boolean profitSharing) { }

    /** 合单查单 / 已验签合单通知里的一笔子单。tradeState/tradeType 用微信枚举名。 */
    record CombinedSub(String mchid, String subMchid, String outTradeNo, String transactionId, String tradeState,
                       String tradeType, Long totalFen, String currency, String successTime) { }

    record CombinedOrder(String combineAppid, String combineMchid, String combineOutTradeNo, List<CombinedSub> subOrders) { }

    /** 合单单号不存在（404 ORDER_NOT_EXIST / RESOURCE_NOT_EXISTS）。 */
    final class CombinedOrderNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CombinedOrderNotFound(String message, Throwable cause) { super(message, cause); }
    }

    /** POST /v3/combine-transactions/jsapi，返回小程序调起参数。 */
    default Map<String, String> prepayCombinedJsapi(String combineOutTradeNo, List<CombinedSubRequest> subOrders, String openid) {
        throw new UnsupportedOperationException("COMBINE_NOT_SUPPORTED");
    }

    /** GET /v3/combine-transactions/out-trade-no/{combine_out_trade_no}。 */
    default CombinedOrder queryCombinedOrder(String combineOutTradeNo) {
        throw new UnsupportedOperationException("COMBINE_NOT_SUPPORTED");
    }

    /** POST /v3/combine-transactions/out-trade-no/{combine_out_trade_no}/close（必须带全部子单）。 */
    default void closeCombinedOrder(String combineOutTradeNo, List<CombinedSubRequest> subOrders) {
        throw new UnsupportedOperationException("COMBINE_NOT_SUPPORTED");
    }

    /** 合单支付通知验签解密；只用于定位合单号，事实一律另行查单核对。 */
    default String parseCombinedNotification(String body, Map<String, String> headers) {
        throw new UnsupportedOperationException("COMBINE_NOT_SUPPORTED");
    }

    /** 合单支付通知地址（冻结支付意图时作为原请求指纹的一部分）。 */
    default String combinedNotifyUrl() {
        throw new UnsupportedOperationException("COMBINE_NOT_SUPPORTED");
    }
}
