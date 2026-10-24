package com.chengyinhub.common.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 核销动态码签发/校验(BE-11 · Layer B 游戏外核销)。
 *
 * <p>玩家侧签发带 nonce+TTL 的动态核销码,收数据方(商家/俱乐部)扫码后由后端 {@link #verify(String, long, String)} 校验。
 * 设计为<b>纯静态无状态</b>:签名保真、TTL 防重放拍照转赠、"一次性"由业务侧 verificationStatus 幂等兜底。
 *
 * <p>码格式:{@code v1.{regId}.{type}.{expMs}.{nonce}.{sig}},
 * 其中 {@code sig = base64url(HMAC-SHA256(payload, secret))},payload 为前 5 段。
 *
 * <p>与旧静态 verificationCode 并存:调用方先试 {@link #verify},失败再回落旧静态码查询(向后兼容)。
 */
public class VerifyDynCodeUtil {

    private static final String VERSION = "v1";
    private static final String HMAC_ALG = "HmacSHA256";

    /** 校验结果。valid=true 时 regId/type 有效;valid=false 时 reason 说明原因。 */
    public static class Result {
        public final boolean valid;
        public final Long regId;
        public final String type;
        public final String reason;

        private Result(boolean valid, Long regId, String type, String reason) {
            this.valid = valid;
            this.regId = regId;
            this.type = type;
            this.reason = reason;
        }

        static Result ok(Long regId, String type) {
            return new Result(true, regId, type, null);
        }

        static Result fail(String reason) {
            return new Result(false, null, null, reason);
        }
    }

    /**
     * 签发:给指定报名单在 [nowMs, nowMs+ttlMs] 内有效的动态核销码。
     *
     * @param regId  报名单 id
     * @param type   核销类型(activity/topic/coupon…),不含 '.'
     * @param nowMs  当前毫秒
     * @param ttlMs  有效期毫秒(建议 60_000)
     * @param nonce  随机数(每次刷新变化,拉平缓存)
     * @param secret 服务端密钥
     */
    public static String issue(long regId, String type, long nowMs, long ttlMs, long nonce, String secret) {
        long exp = nowMs + ttlMs;
        String payload = VERSION + "." + regId + "." + safe(type) + "." + exp + "." + nonce;
        return payload + "." + hmac(payload, secret);
    }

    /**
     * 校验:签名对 + 未过期。通过则返回解析出的 regId/type。
     *
     * @param code   扫到的动态码
     * @param nowMs  当前毫秒
     * @param secret 服务端密钥
     */
    public static Result verify(String code, long nowMs, String secret) {
        return verify(code, nowMs, secret, true);
    }

    /**
     * Signature/identity check for an authorized terminal receipt read only. An expired transport
     * may identify a fact already committed; this result must never authorize a new redemption.
     * New use must still call {@link #verify(String, long, String)} with the actual current time.
     */
    public static Result verifyForReceipt(String code, String secret) {
        return verify(code, 0L, secret, false);
    }

    private static Result verify(String code, long nowMs, String secret, boolean enforceExpiry) {
        if (code == null || code.isEmpty()) {
            return Result.fail("空码");
        }
        String[] p = code.split("\\.");
        // v1 . regId . type . exp . nonce . sig  => 6 段
        if (p.length != 6 || !VERSION.equals(p[0])) {
            return Result.fail("格式错误");
        }
        String payload = p[0] + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4];
        if (!constEquals(hmac(payload, secret), p[5])) {
            return Result.fail("签名不匹配");
        }
        long regId;
        long exp;
        try {
            regId = Long.parseLong(p[1]);
            exp = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            return Result.fail("参数错误");
        }
        if (enforceExpiry && nowMs > exp) {
            return Result.fail("已过期");
        }
        return Result.ok(regId, p[2]);
    }

    // ===== 据点张贴码(静态) =====
    //
    // 与上面的动态码是**两个相反的用途**,别混:
    //   动态码 = 玩家掏出来给商家扫,靠 TTL 防转赠;
    //   张贴码 = 商家打印贴在店里给玩家扫,**必须长期有效**,不能有 TTL。
    //
    // 它不存库:码 = 签名过的 poiId,不是秘密。想作废就换 secret,不用回表改数据。
    // 防「拍下码回家扫」不靠码本身 —— `complete` 在分支之前先做服务端 GPS 半径校验,
    // 人不到店照样过不了。张贴码加的是「确实站在这家店门口」这层证据。

    private static final String POI_VERSION = "poi1";

    /** 签发某据点的张贴码。格式 {@code poi1.{poiId}.{sig}},无有效期。 */
    public static String issuePoiPoster(long poiId, String secret) {
        String payload = POI_VERSION + "." + poiId;
        return payload + "." + hmac(payload, secret);
    }

    /** 校验张贴码,通过返回 poiId,否则返回 null(格式错/签名不匹配一律 null,不区分,免探测)。 */
    public static Long verifyPoiPoster(String code, String secret) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        String[] p = code.split("\\.");
        if (p.length != 3 || !POI_VERSION.equals(p[0])) {
            return null;
        }
        String payload = p[0] + "." + p[1];
        if (!constEquals(hmac(payload, secret), p[2])) {
            return null;
        }
        try {
            return Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===== 优惠券出示码(F5) =====
    //
    // 格式 {@code cq1.{historyId}.{epochSec}.{sig}},sig = HMAC-SHA256("cq1.{historyId}.{epochSec}")。
    // 独立版本前缀:不以 v1 开头,报名单 verify() 永远不会把它当票码,反之亦然。

    private static final String COUPON_VERSION = "cq1";

    /** 签发某条持券记录在 epochSec 时刻的出示码。 */
    public static String issueCoupon(long historyId, long epochSec, String secret) {
        String payload = COUPON_VERSION + "." + historyId + "." + epochSec;
        return payload + "." + hmac(payload, secret);
    }

    /** 校验出示码:签名对、签发于 ttlSec 内且不在未来 60s 以上。通过返回 historyId,否则 null。 */
    public static Long verifyCoupon(String code, long nowSec, long ttlSec, String secret) {
        if (code == null || secret == null || secret.trim().isEmpty()) {
            return null;
        }
        String[] p = code.split("\\.");
        if (p.length != 4 || !COUPON_VERSION.equals(p[0])) {
            return null;
        }
        if (!constEquals(hmac(p[0] + "." + p[1] + "." + p[2], secret), p[3])) {
            return null;
        }
        try {
            long historyId = Long.parseLong(p[1]);
            long epochSec = Long.parseLong(p[2]);
            if (nowSec - epochSec > ttlSec || epochSec - nowSec > 60) {
                return null;
            }
            return historyId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("核销码签名失败", e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace(".", "_");
    }

    /** 常量时间比较,防时序侧信道。 */
    private static boolean constEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
