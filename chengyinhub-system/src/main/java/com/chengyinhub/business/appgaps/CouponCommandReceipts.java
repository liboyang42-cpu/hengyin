package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 商家优惠券命令回执 v1（App Core/CouponCommandReceipts.swift）：/api/coupon/publish|stop 带
 * X-Coupon-Command-Id + X-Coupon-Merchant-Id 时，同一 (actor, requestId) 只执行一次，执行结果（成功或预检拒绝）
 * 连同执行时券状态写成不可变回执；/api/coupon/command-receipt 按原 requestId 读回同一回执。
 * payloadHash 由服务端按 App 同一二进制规范（大端整数 + int32 长度前缀的普通 UTF-8）从请求字段重算，不信任客户端。
 */
@Service
public class CouponCommandReceipts {
    public static final String PROTOCOL = "coupon-command-v1";
    public static final String PUBLISH = "PUBLISH", STOP = "STOP";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);
    private final AppOriginalRequestLedger ledger;

    public CouponCommandReceipts(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    /** 一次命令执行的结论。couponId/status 为执行时事实；ok=true 只用于 PUBLISHED/STOPPED。 */
    public record Outcome(boolean ok, String reasonCode, Long couponId, Integer statusAtExecution, String message) {
        public static Outcome rejected(String reason, Long couponId, Integer status, String message) {
            return new Outcome(false, reason, couponId, status, message);
        }
    }

    public static String publishHash(long merchantId, long ownerMemberId, String name, String description, int couponType,
                                     long publishCount, long startEpochMs, long endEpochMs) {
        Canonical c = header(PUBLISH, merchantId, ownerMemberId);
        c.string(name); c.string(description); c.int32(couponType); c.int64(publishCount); c.int64(startEpochMs); c.int64(endEpochMs);
        return c.sha();
    }

    public static String stopHash(long merchantId, long ownerMemberId, long couponId) {
        Canonical c = header(STOP, merchantId, ownerMemberId);
        c.int64(couponId);
        return c.sha();
    }

    private static Canonical header(String operation, long merchantId, long ownerMemberId) {
        if (merchantId <= 0 || ownerMemberId <= 0) throw new ServiceException("商家身份无效", 403);
        Canonical c = new Canonical();
        c.int32(1); c.string(operation); c.int32(1); c.int64(merchantId); c.int64(ownerMemberId);
        return c;
    }

    /**
     * 执行一条命令。writer 在台账事务内运行；返回的 Outcome（含拒绝）连同执行时券状态写进回执。
     * 同 requestId 同摘要重放已存回执、不再执行；同 requestId 不同摘要 409。
     */
    public AjaxResult execute(long actor, String requestId, String operation, long merchantId, long ownerMemberId,
                              String payloadHash, Supplier<Outcome> writer) {
        if (!AppOriginalRequestLedger.requestId(requestId)) return AjaxResult.error(400, "请求编号无效");
        AppOriginalRequestLedger.Fact fact;
        try {
            fact = ledger.run(PROTOCOL, actor, requestId, scope(merchantId), payloadHash, () -> {
                Outcome outcome = writer.get();
                return stored(receipt(actor, requestId, operation, merchantId, ownerMemberId, payloadHash, outcome), outcome);
            });
        } catch (ServiceException conflict) {
            return AjaxResult.error(conflict.getCode() == null ? 409 : conflict.getCode(), conflict.getMessage());
        }
        return present(fact);
    }

    /** 只读回执；没有该 requestId 时 data 不含 commandReceipt（App 视为未知，不得据此重发）。 */
    public AjaxResult read(long actor, long merchantId, String requestId) {
        if (!AppOriginalRequestLedger.requestId(requestId)) return AjaxResult.error(400, "请求编号无效");
        AppOriginalRequestLedger.Fact fact = ledger.readById(PROTOCOL, actor, requestId);
        if (!fact.committed() || !scope(merchantId).equals(fact.scope())) return AjaxResult.success(new LinkedHashMap<>());
        return present(fact);
    }

    /**
     * 所有业务结论（成功与预检拒绝）都在台账事务里提交为回执，writer 不抛业务异常；
     * 未知异常整体回滚、不留行，读回为空（App 视为未知），同一 requestId 可安全重试。
     */
    private static AjaxResult present(AppOriginalRequestLedger.Fact fact) {
        if (!fact.committed()) return AjaxResult.error(fact.errorCode() == null ? 500 : fact.errorCode(), fact.errorMessage());
        Map<String, Object> stored = fact.result();
        Map<String, Object> data = new LinkedHashMap<>();
        if (stored.get("id") != null) data.put("id", ((Number) stored.get("id")).longValue());
        data.put("commandReceipt", stored.get("commandReceipt"));
        AjaxResult result = new AjaxResult(((Number) stored.get("code")).intValue(), (String) stored.get("msg"));
        result.put(AjaxResult.DATA_TAG, data);
        return result;
    }

    static Map<String, Object> receipt(long actor, String requestId, String operation, long merchantId, long ownerMemberId,
                                       String payloadHash, Outcome outcome) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("version", 1); r.put("requestId", requestId); r.put("operation", operation); r.put("scope", "MERCHANT");
        r.put("merchantId", merchantId); r.put("ownerMemberId", ownerMemberId); r.put("actorMemberId", actor);
        r.put("payloadHash", payloadHash);
        r.put("outcome", outcome.ok() ? "SUCCEEDED" : "REJECTED"); r.put("reasonCode", outcome.reasonCode());
        r.put("couponId", outcome.couponId()); r.put("couponStatusAtExecution", outcome.statusAtExecution());
        r.put("completedAt", ISO.format(Instant.now()));
        return r;
    }

    private static Map<String, Object> stored(Map<String, Object> receipt, Outcome outcome) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", outcome.ok() ? 200 : 500);
        s.put("msg", outcome.message());
        // 发布成功回传新券 id（与旧写线格式一致）；停发与拒绝不带 id。
        s.put("id", outcome.ok() && PUBLISH.equals(receipt.get("operation")) ? outcome.couponId() : null);
        s.put("commandReceipt", receipt);
        return s;
    }

    static String scope(long merchantId) { return "merchant:" + merchantId; }

    /** 大端整数 + int32 长度前缀的普通 UTF-8；null 字符串写 -1。 */
    static final class Canonical {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void int32(int v) { out.writeBytes(ByteBuffer.allocate(4).putInt(v).array()); }
        void int64(long v) { out.writeBytes(ByteBuffer.allocate(8).putLong(v).array()); }
        void string(String v) {
            if (v == null) { int32(-1); return; }
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            int32(b.length); out.writeBytes(b);
        }
        String sha() { return AppOriginalRequestLedger.sha256Hex(out.toByteArray()); }
    }
}
