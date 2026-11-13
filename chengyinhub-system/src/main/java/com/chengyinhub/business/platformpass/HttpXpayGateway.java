package com.chengyinhub.business.platformpass;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 复用既有小程序 access_token（与内容安全/小程序码同一个 token 源）。 */
@Component
public class HttpXpayGateway implements XpayGateway {
    private final PlatformPassProperties properties;
    private final IWxContentSecurityService tokens;

    public HttpXpayGateway(PlatformPassProperties properties, IWxContentSecurityService tokens) {
        this.properties = properties;
        this.tokens = tokens;
    }

    @Override
    public JSONObject queryOrder(String openid, String orderNo, int env) {
        JSONObject body = new JSONObject();
        body.put("openid", openid);
        body.put("env", env);
        body.put("order_id", orderNo);
        return post("/xpay/query_order", body);
    }

    @Override
    public JSONObject refundOrder(String openid, String orderNo, String refundOrderNo, long leftFee, long refundFee,
                                  String reason, int env) {
        JSONObject body = new JSONObject();
        body.put("openid", openid);
        body.put("order_id", orderNo);
        body.put("refund_order_id", refundOrderNo);
        body.put("left_fee", leftFee);
        body.put("refund_fee", refundFee);
        body.put("biz_meta", "");
        body.put("refund_reason", "0");
        body.put("req_from", "2");
        body.put("env", env);
        return post("/xpay/refund_order", body);
    }

    @Override
    public JSONObject notifyProvideGoods(String orderNo, int env) {
        JSONObject body = new JSONObject();
        body.put("order_id", orderNo);
        body.put("env", env);
        return post("/xpay/notify_provide_goods", body);
    }

    private JSONObject post(String uri, JSONObject payload) {
        RestoreDispatchGuard.requireDispatchAllowed();
        PlatformPassProperties.Xpay xpay = properties.getXpay();
        if (!xpay.hasCredentials()) throw new IllegalStateException("虚拟支付未配置");
        String token = tokens.getAccessToken();
        if (token == null || token.isEmpty()) throw new IllegalStateException("access_token 不可用");
        String body = JSON.toJSONString(payload);
        String sig = XpaySigner.paySig(xpay.getAppKey(), uri, body);
        HttpURLConnection connection = null;
        try {
            String url = xpay.getApiBase() + uri + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&pay_sig=" + sig;
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                if (in == null) throw new IllegalStateException("虚拟支付接口无响应体");
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > 65536) throw new IllegalStateException("虚拟支付接口响应过大");
                }
                JSONObject parsed = JSON.parseObject(buffer.toString(StandardCharsets.UTF_8));
                if (parsed == null) throw new IllegalStateException("虚拟支付接口响应不可解析");
                return parsed;
            }
        } catch (RestoreDispatchGuard.Blocked blocked) {
            throw blocked;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("虚拟支付接口请求失败", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
