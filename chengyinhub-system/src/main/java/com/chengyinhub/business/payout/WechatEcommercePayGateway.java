package com.chengyinhub.business.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.cipher.PrivacyEncryptor;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpMethod;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.core.http.JsonRequestBody;
import com.wechat.pay.java.service.ecommerceprofitsharing.EcommerceProfitSharingService;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.CreateOrderReceiver;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.CreateOrderRequest;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.CreateOrderResponse;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.CreateReturnOrderRequest;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.CreateReturnOrderResponse;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.FinishOrderRequest;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.OrderReceiverDetail;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.QueryOrderRequest;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.QueryOrderResponse;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.QueryReturnOrderRequest;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.QueryReturnOrderResponse;
import com.wechat.pay.java.service.ecommercerefund.EcommerceRefundService;
import com.wechat.pay.java.service.ecommercerefund.model.CreateRefundRequest;
import com.wechat.pay.java.service.ecommercerefund.model.Refund4Create;
import com.wechat.pay.java.service.ecommercerefund.model.RefundReqAmount;
import com.wechat.pay.java.service.partnerpayments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.partnerpayments.jsapi.model.Amount;
import com.wechat.pay.java.service.partnerpayments.jsapi.model.Payer;
import com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.partnerpayments.jsapi.model.SettleInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 电商收付通 SDK 适配。凭据来自 {@code payout.ecommerce.*}（电商平台商户号的 API 证书与 APIv3 密钥，
 * 与现有直连商户号 {@code wx.pay.*} 分开），开关关闭或凭据缺失时任何方法都直接拒绝，不连微信。
 */
@Component
public class WechatEcommercePayGateway implements EcommercePayGateway {

    static final String APPLYMENT_PATH = "https://api.mch.weixin.qq.com/v3/ecommerce/applyments/";
    /** 进件请求体里需要用微信支付平台公钥加密的字段（按官方进件文档的敏感字段）。 */
    static final List<String> SENSITIVE_PATHS = List.of(
            "id_card_info.id_card_name", "id_card_info.id_card_number", "id_card_info.id_card_address",
            "id_doc_info.id_doc_name", "id_doc_info.id_doc_number", "id_doc_info.id_doc_address",
            "account_info.account_name", "account_info.account_number",
            "contact_info.contact_name", "contact_info.contact_id_card_number",
            "contact_info.mobile_phone", "contact_info.contact_email",
            "ubo_info.ubo_id_doc_name", "ubo_info.ubo_id_doc_number", "ubo_info.ubo_id_doc_address");

    private final PayoutSwitches switches;
    private final String privateKeyPath;
    private final String merchantSerialNumber;
    private final String apiV3Key;
    private final String notifyUrl;
    private final String refundNotifyUrl;
    private final ObjectMapper json = new ObjectMapper();
    private volatile Clients clients;

    private record Clients(Config config, HttpClient http, JsapiServiceExtension partnerJsapi,
                           EcommerceProfitSharingService sharing, EcommerceRefundService refunds) { }

    public WechatEcommercePayGateway(PayoutSwitches switches,
                                     @Value("${payout.ecommerce.private-key-path:}") String privateKeyPath,
                                     @Value("${payout.ecommerce.merchant-serial-number:}") String merchantSerialNumber,
                                     @Value("${payout.ecommerce.api-v3-key:}") String apiV3Key,
                                     @Value("${payout.ecommerce.notify-url:}") String notifyUrl,
                                     @Value("${payout.ecommerce.refund-notify-url:}") String refundNotifyUrl) {
        this.switches = switches;
        this.privateKeyPath = privateKeyPath;
        this.merchantSerialNumber = merchantSerialNumber;
        this.apiV3Key = apiV3Key;
        this.notifyUrl = notifyUrl;
        this.refundNotifyUrl = refundNotifyUrl;
    }

    private Clients clients() {
        if (!switches.ecommerceEnabled()) throw new IllegalStateException("ECOMMERCE_SWITCH_OFF");
        Clients current = clients;
        if (current != null) return current;
        synchronized (this) {
            if (clients != null) return clients;
            if (blank(privateKeyPath) || blank(merchantSerialNumber) || blank(apiV3Key))
                throw new IllegalStateException("ECOMMERCE_CREDENTIAL_MISSING");
            Config config = new RSAAutoCertificateConfig.Builder().merchantId(switches.spMchid())
                    .privateKeyFromPath(privateKeyPath).merchantSerialNumber(merchantSerialNumber)
                    .apiV3Key(apiV3Key).build();
            HttpClient http = new DefaultHttpClientBuilder().config(config).build();
            clients = new Clients(config, http,
                    new JsapiServiceExtension.Builder().config(config).build(),
                    new EcommerceProfitSharingService.Builder().httpClient(http).build(),
                    new EcommerceRefundService.Builder().httpClient(http).build());
            return clients;
        }
    }

    @Override
    public Map<String, String> prepayJsapi(String subMchid, String outTradeNo, long totalFen, String description, String spOpenid) {
        if (!switches.sellerRoutingEnabled()) throw new IllegalStateException("SELLER_ROUTING_SWITCH_OFF");
        if (blank(notifyUrl)) throw new IllegalStateException("ECOMMERCE_NOTIFY_URL_MISSING");
        PrepayRequest request = new PrepayRequest();
        request.setSpAppid(switches.spAppid());
        request.setSpMchid(switches.spMchid());
        request.setSubMchid(subMchid);
        request.setOutTradeNo(outTradeNo);
        request.setDescription(description);
        request.setNotifyUrl(notifyUrl.trim());
        Amount amount = new Amount();
        amount.setTotal(Math.toIntExact(totalFen));
        amount.setCurrency("CNY");
        request.setAmount(amount);
        Payer payer = new Payer();
        payer.setSpOpenid(spOpenid);
        request.setPayer(payer);
        SettleInfo settle = new SettleInfo();
        settle.setProfitSharing(true);
        request.setSettleInfo(settle);
        PrepayWithRequestPaymentResponse r = clients().partnerJsapi().prepayWithRequestPayment(request, switches.spAppid());
        Map<String, String> params = new HashMap<>();
        params.put("appId", r.getAppId());
        params.put("timeStamp", r.getTimeStamp());
        params.put("nonceStr", r.getNonceStr());
        params.put("package", r.getPackageVal());
        params.put("signType", r.getSignType());
        params.put("paySign", r.getPaySign());
        return params;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ApplymentSubmitted submitApplyment(String outRequestNo, Map<String, Object> applymentBody) {
        Clients c = clients();
        Map<String, Object> body = deepCopy(applymentBody);
        body.put("out_request_no", outRequestNo);
        PrivacyEncryptor encryptor = c.config().createEncryptor();
        encryptSensitive(body, encryptor);
        try {
            HttpRequest request = new HttpRequest.Builder().httpMethod(HttpMethod.POST).url(APPLYMENT_PATH)
                    .addHeader("Accept", "application/json").addHeader("Content-Type", "application/json")
                    .addHeader("Wechatpay-Serial", encryptor.getWechatpaySerial())
                    .body(new JsonRequestBody.Builder().body(json.writeValueAsString(body)).build()).build();
            Map<String, Object> resp = c.http().execute(request, Map.class).getServiceResponse();
            return new ApplymentSubmitted(asLong(resp == null ? null : resp.get("applyment_id")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("APPLYMENT_BODY_INVALID", e);
        }
    }

    /** 递归把敏感字段替换为密文；只处理字符串值，不改其它字段。 */
    @SuppressWarnings("unchecked")
    static void encryptSensitive(Map<String, Object> body, PrivacyEncryptor encryptor) {
        for (String path : SENSITIVE_PATHS) {
            String[] parts = path.split("\\.");
            Object node = body;
            for (int i = 0; i < parts.length - 1 && node instanceof Map; i++) node = ((Map<String, Object>) node).get(parts[i]);
            if (!(node instanceof Map)) continue;
            Map<String, Object> parent = (Map<String, Object>) node;
            Object value = parent.get(parts[parts.length - 1]);
            if (value instanceof String s && !s.isEmpty()) parent.put(parts[parts.length - 1], encryptor.encrypt(s));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ApplymentStatus queryApplyment(String outRequestNo) {
        Clients c = clients();
        HttpRequest request = new HttpRequest.Builder().httpMethod(HttpMethod.GET)
                .url(APPLYMENT_PATH + "out-request-no/" + URLEncoder.encode(outRequestNo, StandardCharsets.UTF_8))
                .addHeader("Accept", "application/json").addHeader("Content-Type", "application/json").build();
        Map<String, Object> r = c.http().execute(request, Map.class).getServiceResponse();
        if (r == null) return null;
        return new ApplymentStatus(asLong(r.get("applyment_id")), str(r.get("applyment_state")),
                str(r.get("applyment_state_desc")), str(r.get("sign_url")), str(r.get("sub_mchid")));
    }

    @Override
    public ShareResult createProfitSharing(String subMchid, String transactionId, String outOrderNo,
                                           List<ProfitSharePlanner.Receiver> receivers, boolean finish) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setAppid(switches.spAppid());
        request.setSubMchid(subMchid);
        request.setTransactionId(transactionId);
        request.setOutOrderNo(outOrderNo);
        request.setFinish(finish);
        List<CreateOrderReceiver> list = new ArrayList<>();
        for (ProfitSharePlanner.Receiver r : receivers) {
            CreateOrderReceiver item = new CreateOrderReceiver();
            item.setType("MERCHANT_ID");
            item.setReceiverAccount(r.mchid());
            item.setAmount(r.amountFen());
            item.setDescription("城瘾结算分账");
            list.add(item);
        }
        request.setReceivers(list);
        CreateOrderResponse r = clients().sharing().createOrder(request);
        return new ShareResult(r.getStatus() == null ? null : r.getStatus().name(), r.getOrderId(), receivers(r.getReceivers()));
    }

    @Override
    public ShareResult queryProfitSharing(String subMchid, String transactionId, String outOrderNo) {
        QueryOrderRequest request = new QueryOrderRequest();
        request.setSubMchid(subMchid);
        request.setTransactionId(transactionId);
        request.setOutOrderNo(outOrderNo);
        QueryOrderResponse r = clients().sharing().queryOrder(request);
        return new ShareResult(r.getStatus(), r.getOrderId(), receivers(r.getReceivers()));
    }

    private static List<ReceiverResult> receivers(List<OrderReceiverDetail> details) {
        List<ReceiverResult> out = new ArrayList<>();
        if (details == null) return out;
        for (OrderReceiverDetail d : details) {
            out.add(new ReceiverResult(d.getReceiverMchid() != null ? d.getReceiverMchid() : d.getReceiverAccount(),
                    d.getAmount() == null ? 0 : d.getAmount(), d.getResult(), d.getFailReason()));
        }
        return out;
    }

    @Override
    public ReturnResult createReturn(String subMchid, String outOrderNo, String outReturnNo, String returnMchid,
                                     long amountFen, String description) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setSubMchid(subMchid);
        request.setOutOrderNo(outOrderNo);
        request.setOutReturnNo(outReturnNo);
        request.setReturnMchid(returnMchid);
        request.setAmount(amountFen);
        request.setDescription(description);
        CreateReturnOrderResponse r = clients().sharing().createReturnOrder(request);
        return new ReturnResult(r.getResult(), r.getReturnNo(), r.getFailReason());
    }

    @Override
    public ReturnResult queryReturn(String subMchid, String outOrderNo, String outReturnNo) {
        QueryReturnOrderRequest request = new QueryReturnOrderRequest();
        request.setSubMchid(subMchid);
        request.setOutOrderNo(outOrderNo);
        request.setOutReturnNo(outReturnNo);
        QueryReturnOrderResponse r = clients().sharing().queryReturnOrder(request);
        return new ReturnResult(r.getResult(), r.getReturnNo(), r.getFailReason());
    }

    @Override
    public void finishProfitSharing(String subMchid, String transactionId, String outOrderNo, String description) {
        FinishOrderRequest request = new FinishOrderRequest();
        request.setSubMchid(subMchid);
        request.setTransactionId(transactionId);
        request.setOutOrderNo(outOrderNo);
        request.setDescription(description);
        clients().sharing().finishOrder(request);
    }

    @Override
    public RefundAccepted createRefund(String subMchid, String transactionId, String outRefundNo, long refundFen,
                                       long totalFen, String reason) {
        CreateRefundRequest request = new CreateRefundRequest();
        request.setSubMchid(subMchid);
        request.setSpAppid(switches.spAppid());
        request.setTransactionId(transactionId);
        request.setOutRefundNo(outRefundNo);
        if (reason != null && !reason.isEmpty()) request.setReason(reason.length() > 80 ? reason.substring(0, 80) : reason);
        if (!blank(refundNotifyUrl)) request.setNotifyUrl(refundNotifyUrl.trim());
        RefundReqAmount amount = new RefundReqAmount();
        amount.setRefund(refundFen);
        amount.setTotal(totalFen);
        amount.setCurrency("CNY");
        request.setAmount(amount);
        Refund4Create r = clients().refunds().createRefund(request);
        return new RefundAccepted(r.getRefundId(), r.getOutRefundNo());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null) throw new IllegalArgumentException("APPLYMENT_BODY_REQUIRED");
        return json.convertValue(source, Map.class);
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && s.matches("[0-9]{1,19}")) return Long.parseLong(s);
        return null;
    }
}
