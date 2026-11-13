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
import com.wechat.pay.java.service.ecommerceprofitsharing.model.AddReceiverRequest;
import com.wechat.pay.java.service.ecommerceprofitsharing.model.AddReceiverResponse;
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
    private String combineNotifyUrl = "";
    private final ObjectMapper json = new ObjectMapper();
    private volatile Clients clients;

    private record Clients(Config config, HttpClient http, JsapiServiceExtension partnerJsapi,
                           EcommerceProfitSharingService sharing, EcommerceRefundService refunds,
                           com.wechat.pay.java.service.partnerpayments.jsapi.JsapiService partnerQuery,
                           com.wechat.pay.java.core.notification.NotificationParser notifications) { }

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

    /** l-payout3：合单支付通知地址（payout.ecommerce.combine-notify-url）。 */
    @org.springframework.beans.factory.annotation.Autowired
    void setCombineNotifyUrl(@Value("${payout.ecommerce.combine-notify-url:}") String combineNotifyUrl) {
        this.combineNotifyUrl = combineNotifyUrl == null ? "" : combineNotifyUrl;
    }

    private Clients clients() {
        if (!switches.ecommerceEnabled()) throw new IllegalStateException("ECOMMERCE_SWITCH_OFF");
        Clients current = clients;
        if (current != null) return current;
        synchronized (this) {
            if (clients != null) return clients;
            if (blank(privateKeyPath) || blank(merchantSerialNumber) || blank(apiV3Key))
                throw new IllegalStateException("ECOMMERCE_CREDENTIAL_MISSING");
            RSAAutoCertificateConfig config = new RSAAutoCertificateConfig.Builder().merchantId(switches.spMchid())
                    .privateKeyFromPath(privateKeyPath).merchantSerialNumber(merchantSerialNumber)
                    .apiV3Key(apiV3Key).build();
            HttpClient http = new DefaultHttpClientBuilder().config(config).build();
            clients = new Clients(config, http,
                    new JsapiServiceExtension.Builder().config(config).build(),
                    new EcommerceProfitSharingService.Builder().httpClient(http).encryptor(config.createEncryptor()).build(),
                    new EcommerceRefundService.Builder().httpClient(http).build(),
                    new com.wechat.pay.java.service.partnerpayments.jsapi.JsapiService.Builder().httpClient(http).build(),
                    new com.wechat.pay.java.core.notification.NotificationParser(config));
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
    public void addPersonalReceiver(String openid, String appid) {
        requirePersonalIdentity(openid, appid);
        AddReceiverRequest request = new AddReceiverRequest();
        request.setAppid(switches.spAppid());
        request.setType("PERSONAL_OPENID");
        request.setAccount(openid);
        request.setRelationType("OTHERS");
        AddReceiverResponse response = clients().sharing().addReceiver(request);
        if (response == null || !"PERSONAL_OPENID".equals(response.getType())
                || !openid.equals(response.getAccount()))
            throw new IllegalStateException("PERSONAL_RECEIVER_ACCEPTANCE_MISMATCH");
    }

    private void requirePersonalIdentity(String openid, String appid) {
        if (!switches.personalClubEnabled()) throw new IllegalStateException("PERSONAL_CLUB_SWITCH_OFF");
        if (blank(openid)) throw new IllegalArgumentException("PERSONAL_OPENID_REQUIRED");
        if (blank(appid) || !appid.equals(switches.spAppid()))
            throw new IllegalArgumentException("PERSONAL_APPID_MISMATCH");
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
            if ("PERSONAL_OPENID".equals(r.type())) {
                requirePersonalIdentity(r.account(), r.appid());
                // finish=true only permits the platform merchant; personal shares require a separate finish-order.
                if (finish) throw new IllegalArgumentException("PERSONAL_PROFITSHARING_FINISH_UNSUPPORTED");
            }
            else if (!"MERCHANT_ID".equals(r.type()))
                throw new IllegalArgumentException("PROFITSHARING_RECEIVER_TYPE_UNSUPPORTED");
            item.setType(r.type());
            item.setReceiverAccount(r.account());
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

    private List<ReceiverResult> receivers(List<OrderReceiverDetail> details) {
        List<ReceiverResult> out = new ArrayList<>();
        if (details == null) return out;
        for (OrderReceiverDetail d : details) {
            String type = d.getType();
            if (blank(type) && !blank(d.getReceiverMchid())) type = "MERCHANT_ID";
            String account = d.getReceiverAccount();
            if ("MERCHANT_ID".equals(type) && blank(account)) account = d.getReceiverMchid();
            if ((!"MERCHANT_ID".equals(type) && !"PERSONAL_OPENID".equals(type)) || blank(account))
                throw new IllegalStateException("PROFITSHARING_RECEIVER_IDENTITY_INVALID");
            // PERSONAL_OPENID is scoped to the service-provider appid; APIv3 has no per-receiver appid field.
            if ("PERSONAL_OPENID".equals(type) && blank(switches.spAppid()))
                throw new IllegalStateException("PERSONAL_APPID_MISSING");
            out.add(new ReceiverResult(type, account, "PERSONAL_OPENID".equals(type) ? switches.spAppid() : null,
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

    /** APIv3 ecommerce returnorders supports return_mchid only, never an OpenID substituted for a merchant. */
    @Override
    public ReturnResult createReturn(String subMchid, String outOrderNo, String outReturnNo,
                                     ProfitSharePlanner.Receiver receiver, long amountFen, String description) {
        if (!"MERCHANT_ID".equals(receiver.type()))
            throw new UnsupportedOperationException("PERSONAL_PROFITSHARING_RETURN_UNSUPPORTED");
        return createReturn(subMchid, outOrderNo, outReturnNo, receiver.account(), amountFen, description);
    }

    @Override
    public ReturnResult queryReturn(String subMchid, String outOrderNo, String outReturnNo,
                                    ProfitSharePlanner.Receiver receiver) {
        if (!"MERCHANT_ID".equals(receiver.type()))
            throw new UnsupportedOperationException("PERSONAL_PROFITSHARING_RETURN_UNSUPPORTED");
        QueryReturnOrderRequest request = new QueryReturnOrderRequest();
        request.setSubMchid(subMchid);
        request.setOutOrderNo(outOrderNo);
        request.setOutReturnNo(outReturnNo);
        QueryReturnOrderResponse response = clients().sharing().queryReturnOrder(request);
        if (response == null || !receiver.account().equals(response.getReturnMchid()))
            throw new IllegalStateException("PROFITSHARING_RETURN_IDENTITY_MISMATCH");
        return new ReturnResult(response.getResult(), response.getReturnNo(), response.getFailReason());
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

    // ---- l-payout2：接入现有支付链路 ----

    @Override
    public String paymentNotifyUrl() {
        if (blank(notifyUrl)) throw new IllegalStateException("ECOMMERCE_NOTIFY_URL_MISSING");
        return notifyUrl.trim();
    }

    @Override
    public PartnerOrder queryPartnerOrder(String subMchid, String outTradeNo) {
        Clients c = clients();
        com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByOutTradeNoRequest request =
                new com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByOutTradeNoRequest();
        request.setSpMchid(switches.spMchid());
        request.setSubMchid(subMchid);
        request.setOutTradeNo(outTradeNo);
        try {
            return partnerOrder(c.partnerQuery().queryOrderByOutTradeNo(request));
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if (e.getHttpStatusCode() == 404 && "ORDER_NOT_EXIST".equals(e.getErrorCode()))
                throw new PartnerOrderNotFound("ORDER_NOT_EXIST", e);
            throw e;
        }
    }

    @Override
    public void closePartnerOrder(String subMchid, String outTradeNo) {
        com.wechat.pay.java.service.partnerpayments.jsapi.model.CloseOrderRequest request =
                new com.wechat.pay.java.service.partnerpayments.jsapi.model.CloseOrderRequest();
        request.setSpMchid(switches.spMchid());
        request.setSubMchid(subMchid);
        request.setOutTradeNo(outTradeNo);
        clients().partnerQuery().closeOrder(request);
    }

    @Override
    public PartnerOrder parsePaymentNotification(String body, Map<String, String> headers) {
        return partnerOrder(clients().notifications().parse(notificationParam(body, headers),
                com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public RefundNotice parseRefundNotification(String body, Map<String, String> headers) {
        Map<String, Object> r = clients().notifications().parse(notificationParam(body, headers), Map.class);
        if (r == null) return null;
        return new RefundNotice(str(r.get("sp_mchid")), str(r.get("sub_mchid")), str(r.get("out_refund_no")));
    }

    @Override
    public RefundQuery queryRefund(String subMchid, String outRefundNo) {
        com.wechat.pay.java.service.ecommercerefund.model.QueryRefundByOutRefundNoRequest request =
                new com.wechat.pay.java.service.ecommercerefund.model.QueryRefundByOutRefundNoRequest();
        request.setSubMchid(subMchid);
        request.setOutRefundNo(outRefundNo);
        try {
            com.wechat.pay.java.service.ecommercerefund.model.Refund r = clients().refunds().queryRefundByOutRefundNo(request);
            if (r == null) return null;
            return new RefundQuery(r.getOutRefundNo(), r.getRefundId(), r.getOutTradeNo(), r.getTransactionId(), r.getStatus(),
                    r.getAmount() == null ? null : r.getAmount().getRefund(),
                    r.getAmount() == null ? null : r.getAmount().getCurrency(), r.getSuccessTime());
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if (e.getHttpStatusCode() == 404 && "RESOURCE_NOT_EXISTS".equals(e.getErrorCode()))
                throw new RefundNotFound("RESOURCE_NOT_EXISTS", e);
            throw e;
        }
    }

    // ---- l-payout3：合单支付。SDK 0.2.15 没有合单模型，按官方 APIv3 路径与字段直接请求（字段需在沙箱核对）。

    static final String COMBINE_PATH = "https://api.mch.weixin.qq.com/v3/combine-transactions/";

    @Override
    public String combinedNotifyUrl() {
        if (blank(combineNotifyUrl)) throw new IllegalStateException("ECOMMERCE_COMBINE_NOTIFY_URL_MISSING");
        return combineNotifyUrl.trim();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> prepayCombinedJsapi(String combineOutTradeNo, List<CombinedSubRequest> subOrders, String openid) {
        if (!switches.combinedEnabled()) throw new IllegalStateException("COMBINE_SWITCH_OFF");
        Clients c = clients();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("combine_appid", switches.spAppid());
        body.put("combine_mchid", switches.spMchid());
        body.put("combine_out_trade_no", combineOutTradeNo);
        body.put("sub_orders", combinedSubBody(subOrders, true));
        body.put("combine_payer_info", Map.of("openid", openid));
        body.put("notify_url", combinedNotifyUrl());
        Map<String, Object> resp = c.http().execute(postJson(COMBINE_PATH + "jsapi", body), Map.class).getServiceResponse();
        String prepayId = resp == null ? null : str(resp.get("prepay_id"));
        if (blank(prepayId)) throw new IllegalStateException("COMBINE_PREPAY_ID_MISSING");
        String timeStamp = Long.toString(java.time.Instant.now().getEpochSecond());
        String nonce = com.wechat.pay.java.core.util.NonceUtil.createNonce(32);
        String pkg = "prepay_id=" + prepayId;
        String message = switches.spAppid() + "\n" + timeStamp + "\n" + nonce + "\n" + pkg + "\n";
        Map<String, String> params = new HashMap<>();
        params.put("appId", switches.spAppid());
        params.put("timeStamp", timeStamp);
        params.put("nonceStr", nonce);
        params.put("package", pkg);
        params.put("signType", "RSA");
        params.put("paySign", c.config().createSigner().sign(message).getSign());
        return params;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CombinedOrder queryCombinedOrder(String combineOutTradeNo) {
        Clients c = clients();
        HttpRequest request = new HttpRequest.Builder().httpMethod(HttpMethod.GET)
                .url(COMBINE_PATH + "out-trade-no/" + URLEncoder.encode(combineOutTradeNo, StandardCharsets.UTF_8))
                .addHeader("Accept", "application/json").addHeader("Content-Type", "application/json").build();
        try {
            return combinedOrder(c.http().execute(request, Map.class).getServiceResponse());
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            if (e.getHttpStatusCode() == 404) throw new CombinedOrderNotFound(e.getErrorCode(), e);
            throw e;
        }
    }

    @Override
    public void closeCombinedOrder(String combineOutTradeNo, List<CombinedSubRequest> subOrders) {
        Clients c = clients();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("combine_appid", switches.spAppid());
        body.put("sub_orders", combinedSubBody(subOrders, false));
        c.http().execute(postJson(COMBINE_PATH + "out-trade-no/"
                + URLEncoder.encode(combineOutTradeNo, StandardCharsets.UTF_8) + "/close", body), Object.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String parseCombinedNotification(String body, Map<String, String> headers) {
        Map<String, Object> r = clients().notifications().parse(notificationParam(body, headers), Map.class);
        return r == null ? null : str(r.get("combine_out_trade_no"));
    }

    private List<Map<String, Object>> combinedSubBody(List<CombinedSubRequest> subOrders, boolean prepay) {
        if (subOrders == null || subOrders.size() < 2 || subOrders.size() > CombinedCheckoutPolicy.MAX_SUB_ORDERS)
            throw new IllegalArgumentException("COMBINE_SUB_ORDER_COUNT");
        List<Map<String, Object>> list = new ArrayList<>();
        for (CombinedSubRequest s : subOrders) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("mchid", switches.spMchid());
            m.put("sub_mchid", s.subMchid());
            m.put("out_trade_no", s.outTradeNo());
            if (prepay) {
                m.put("attach", "cy");
                m.put("amount", Map.of("total_amount", s.amountFen(), "currency", "CNY"));
                m.put("description", s.description());
                m.put("settle_info", Map.of("profit_sharing", s.profitSharing()));
            }
            list.add(m);
        }
        return list;
    }

    private HttpRequest postJson(String url, Object body) {
        try {
            return new HttpRequest.Builder().httpMethod(HttpMethod.POST).url(url)
                    .addHeader("Accept", "application/json").addHeader("Content-Type", "application/json")
                    .body(new JsonRequestBody.Builder().body(json.writeValueAsString(body)).build()).build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("COMBINE_BODY_INVALID", e);
        }
    }

    @SuppressWarnings("unchecked")
    static CombinedOrder combinedOrder(Map<String, Object> r) {
        if (r == null) return null;
        List<CombinedSub> subs = new ArrayList<>();
        Object raw = r.get("sub_orders");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> amount = m.get("amount") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
                subs.add(new CombinedSub(str(m.get("mchid")), str(m.get("sub_mchid")), str(m.get("out_trade_no")),
                        str(m.get("transaction_id")), str(m.get("trade_state")), str(m.get("trade_type")),
                        asLong(amount.get("total_amount")), str(amount.get("currency")), str(m.get("success_time"))));
            }
        }
        return new CombinedOrder(str(r.get("combine_appid")), str(r.get("combine_mchid")), str(r.get("combine_out_trade_no")),
                List.copyOf(subs));
    }

    private static com.wechat.pay.java.core.notification.RequestParam notificationParam(String body, Map<String, String> headers) {
        return new com.wechat.pay.java.core.notification.RequestParam.Builder()
                .serialNumber(headers.get("wechatpay-serial")).nonce(headers.get("wechatpay-nonce"))
                .signature(headers.get("wechatpay-signature")).timestamp(headers.get("wechatpay-timestamp"))
                .body(body).build();
    }

    private static PartnerOrder partnerOrder(com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction t) {
        if (t == null) return null;
        return new PartnerOrder(t.getSpMchid(), t.getSubMchid(), t.getSpAppid(), t.getOutTradeNo(), t.getTransactionId(),
                t.getTradeState() == null ? null : t.getTradeState().name(),
                t.getTradeType() == null ? null : t.getTradeType().name(),
                t.getAmount() == null || t.getAmount().getTotal() == null ? null : t.getAmount().getTotal().longValue(),
                t.getAmount() == null ? null : t.getAmount().getCurrency(), t.getSuccessTime());
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
