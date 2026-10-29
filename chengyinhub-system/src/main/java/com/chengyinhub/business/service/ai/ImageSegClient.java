package com.chengyinhub.business.service.ai;

import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SimpleTimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.utils.StringUtils;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * 阿里云视觉智能开放平台「商品分割」(SegmentCommodity)客户端 —— 只负责「调一次接口,拿到抠图结果地址」。
 *
 * <p>结果地址是阿里云的临时对象,<b>只保留 30 分钟</b>(施工文档 §2.3),所以调用方拿到就必须立刻
 * 下载转存我们自己的 OSS;这个地址既不能进业务表,也不能下发给小程序。
 *
 * <p><b>为什么不引 SDK</b>:本仓只需要这一个接口,pom 里也只有 aliyun-sdk-oss。RPC 签名 V1 就是
 * 「参数排序 → 转义 → HMAC-SHA1 → Base64」几十行,签名单元测试直接拿阿里云官方示例做基准
 * (见 {@link #stringToSign})。
 *
 * <p><b>fail-open</b>:未开通、没配 key、地址不合法、HTTP 错、超时、返回里没有结果 —— 一律返回 null,
 * 不抛异常。调用方是铸卡那一步,抠不出来就出照片卡,不能因为第三方抖动让玩家铸卡失败。
 * (与 {@link DashScopeVoiceClient} 相反的取舍:那边失败要能分清原因,因为语音没有可用的降级形态。)
 *
 * @author chengyinhub
 */
@Component
public class ImageSegClient {

    private static final Logger log = LoggerFactory.getLogger(ImageSegClient.class);

    /** 商品分割的接入地址,上海地域(我们的 OSS 也在 oss-cn-shanghai,官方推荐同区)。 */
    static final String ENDPOINT = "https://imageseg.cn-shanghai.aliyuncs.com/";
    static final String ACTION = "SegmentCommodity";
    static final String API_VERSION = "2019-12-30";

    private static final String SIGNATURE_METHOD = "HMAC-SHA1";
    /** 上面那个是线上的参数取值,JCA 只认这个名字 —— 传 HMAC-SHA1 会 NoSuchAlgorithmException。 */
    private static final String MAC_NAME = "HmacSHA1";
    private static final String SIGNATURE_VERSION = "1.0";

    /** 总开关。关着 = 不出抠图,卡照样铸。 */
    @Value("${ai.imageseg.enabled:false}")
    private boolean enabled;

    /** RAM 子账号 AccessKey,只授视觉智能权限(施工文档 §6)。只从环境变量注入,不进代码不进日志。 */
    @Value("${ai.imageseg.accessKeyId:}")
    private String accessKeyId;

    @Value("${ai.imageseg.accessKeySecret:}")
    private String accessKeySecret;

    /** 单次调用硬超时(毫秒)。当场出卡的整体预算是 4 秒,这里不能更宽。 */
    @Value("${ai.imageseg.timeoutMs:4000}")
    private int timeoutMs;

    /**
     * 抠一张图。
     *
     * @param imageUrl 本站上传链产出的照片地址(来源闸在调用方,这里只管接口能不能吃)
     * @return 抠好的透明 PNG 临时地址;没有任何结果时返回 null,不抛异常
     */
    public String segment(String imageUrl) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!usable()) {
            return null;
        }
        String image = acceptedImageUrl(imageUrl);
        if (image == null) {
            return null;
        }

        String body;
        try {
            body = get(signedUrl(image));
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            // 只打异常类名:hutool 的异常原文会带上请求地址,而地址里有签名 —— 那是可重放的凭据
            log.error("[ImageSeg] 调用失败:{}", e.getClass().getSimpleName());
            return null;
        }
        return body == null ? null : cutoutUrlOf(body);
    }

    /** 开关开着但 key 没注入 = 配了一半,这种请求必被拒,不如不发(与语音客户端同一判据)。 */
    boolean usable() {
        return enabled && StringUtils.isNotBlank(accessKeyId) && StringUtils.isNotBlank(accessKeySecret);
    }

    /** 接口不接受含中文的地址,空地址也没必要跑一趟 —— 这两种都直接算「没有结果」。 */
    private static String acceptedImageUrl(String imageUrl) {
        String url = imageUrl == null ? "" : imageUrl.trim();
        return url.isEmpty() || containsChinese(url) ? null : url;
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            int cp = value.codePointAt(i);
            // 中日韩统一表意文字:中文文件名基本都落在这段,接口会按非法地址拒掉
            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private String signedUrl(String imageUrl) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", ACTION);
        params.put("Format", "JSON");
        params.put("SignatureMethod", SIGNATURE_METHOD);
        // 每次请求都要新的 nonce:重复 nonce 会被判重放,而铸卡是会被同一玩家反复触发的
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", SIGNATURE_VERSION);
        params.put("Timestamp", utcTimestamp());
        params.put("Version", API_VERSION);
        params.put("ImageURL", imageUrl);
        // 不带 ReturnForm:按原图大小回透明 PNG。crop 形式会把「物品在原图哪一块」丢掉,
        // 当场揭晓要靠这个位置让背景散掉、物品留在原位(裁边在转存那一步做)。

        StringBuilder url = new StringBuilder(ENDPOINT).append('?');
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(percentEncode(entry.getKey())).append('=').append(percentEncode(entry.getValue())).append('&');
        }
        return url.append("Signature=").append(percentEncode(sign(accessKeySecret, stringToSign("GET", params))))
                .toString();
    }

    /**
     * 待签字符串:参数按名字排序、逐个转义后拼成 query,再整体转义一次,
     * 前面接上「请求方法 + & + 转义过的斜杠 + &」。
     * 算法与各参数取值见阿里云《签名机制的方法及代码示例》
     * https://help.aliyun.com/document_detail/203278.html —— 单测拿这篇的示例逐字节钉住它。
     *
     * <p>{@code Signature} 本身不参与签名,不能出现在 params 里。
     */
    static String stringToSign(String httpMethod, Map<String, String> params) {
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder();
        for (String key : keys) {
            if (canonical.length() > 0) {
                canonical.append('&');
            }
            canonical.append(percentEncode(key)).append('=').append(percentEncode(params.get(key)));
        }
        return httpMethod + "&" + percentEncode("/") + "&" + percentEncode(canonical.toString());
    }

    /** HMAC-SHA1,密钥是「AccessKeySecret&amp;」—— 结尾那个 & 是算法的一部分,少它就是 SignatureDoesNotMatch。 */
    static String sign(String accessKeySecret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance(MAC_NAME);
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes("UTF-8"), MAC_NAME));
            return new String(Base64.getEncoder().encode(mac.doFinal(stringToSign.getBytes("UTF-8"))), "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 算不出来", e);
        }
    }

    /**
     * 阿里云要的是 RFC 3986 转义,与 {@link URLEncoder} 的表单编码差三处:
     * 空格是 {@code %20} 而不是 {@code +}、{@code *} 要转、{@code ~} 不转。
     */
    static String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(new SimpleTimeZone(0, "GMT"));
        return format.format(new Date());
    }

    /** 成功是 {@code {"Data":{"ImageURL":"…"}}};业务失败也是 200,带 Code/Message 而没有 Data。 */
    private static String cutoutUrlOf(String body) {
        JSONObject json;
        try {
            json = JSON.parseObject(body);
        } catch (Exception e) {
            log.error("[ImageSeg] 返回不是 JSON:{}", brief(body));
            return null;
        }
        JSONObject data = json == null ? null : json.getJSONObject("Data");
        String result = data == null ? null : data.getString("ImageURL");
        if (result == null || result.trim().isEmpty()) {
            log.warn("[ImageSeg] 没有抠图结果:{}", brief(body));
            return null;
        }
        return result.trim();
    }

    /**
     * HTTP 缝:生产走 hutool,单测覆写这一缝(不许真发网络请求)。非 2xx 与任何异常都返回 null。
     *
     * <p>⚠️ 这里只打状态码:地址里带着签名,而签名一旦进日志,日志泄露就等于请求可被重放。
     */
    String get(String url) {
        try (HttpResponse resp = HttpRequest.get(url).timeout(timeoutMs).execute()) {
            if (!resp.isOk()) {
                log.error("[ImageSeg] HTTP {}", resp.getStatus());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            // 超时是预期内的降级(预算本来就卡得紧),其余才是该有人看的异常
            if (ExceptionUtil.isCausedBy(e, SocketTimeoutException.class)) {
                log.warn("[ImageSeg] 请求超时");
            } else {
                log.error("[ImageSeg] 请求失败:{}", e.getClass().getSimpleName());
            }
            return null;
        }
    }

    /** 回包截断再打:整包进日志会把带 token 性质的结果地址原样留在日志里。 */
    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
