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
}
