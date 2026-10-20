package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.CouponCommandReceipt;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.mapper.CouponCommandReceiptMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 显式商家命令：券与终态回执同事务提交，默认关闭。 */
@Service
public class CouponCommandService {
    @Value("${coupon.command-receipts.enabled:false}") private boolean enabled;
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private IUmsMemberService umsMemberService;
    @Autowired private IQuotaService quotaService;
    @Autowired private ISmsCouponService smsCouponService;
    @Autowired private SmsCouponMapper smsCouponMapper;
    @Autowired private CouponCommandReceiptMapper receiptMapper;
    @Autowired private RedisCache redisCache;
    @Autowired private PlatformTransactionManager transactionManager;

    public AjaxResult publish(Long actor, String id, Long merchant, CouponPublisDTO request) {
        AjaxResult invalid = preflight(actor, id, merchant, request == null ? null : request.getScope());
        if (invalid != null) return invalid;
        MerchantAccessContext access = authority(actor, merchant);
        return execute(actor, id, access, hash("PUBLISH", access, request, null), "PUBLISH", () -> publishOnce(request, access));
    }
    public AjaxResult stop(Long actor, String id, Long merchant, Long couponId, String scope) {
        AjaxResult invalid = preflight(actor, id, merchant, scope);
        if (invalid != null) return invalid;
        if (couponId == null || couponId <= 0) return error(400, "INVALID_COMMAND", "参数缺失");
        MerchantAccessContext access = authority(actor, merchant);
        return execute(actor, id, access, hash("STOP", access, null, couponId), "STOP", () -> stopOnce(couponId, access));
    }
    /** 主库当前读；仅当前身份自身命令可读，未找到不代表请求未执行。 */
    public AjaxResult read(Long actor, String id, Long merchant, String scope) {
        AjaxResult invalid = preflight(actor, id, merchant, scope);
        if (invalid != null) return invalid;
        return transaction().execute(status -> {
            MerchantAccessContext access = authority(actor, merchant);
            CouponCommandReceipt receipt = receiptMapper.selectCurrent(actor, access.getMerchantId(), id);
            if (receipt == null || !access.getOwnerMemberId().equals(receipt.getOwnerMemberId()))
                return error(404, "COMMAND_NOT_FOUND", "未找到命令结果，请保留原请求");
            return response(receipt);
        });
    }
    private AjaxResult execute(Long actor, String id, MerchantAccessContext expected, byte[] hash,
                               String operation, Supplier<CouponCommandReceipt> action) {
        try {
            return transaction().execute(status -> {
                umsMemberService.lockMemberById(expected.getOwnerMemberId());
                MerchantAccessContext current = authority(actor, expected.getMerchantId());
                if (!current.getOwnerMemberId().equals(expected.getOwnerMemberId()))
                    throw new IllegalStateException("商家归属已变更，请重新确认身份");
                CouponCommandReceipt previous = receiptMapper.selectCurrent(actor, current.getMerchantId(), id);
                if (previous != null) return replay(previous, hash, current);
                CouponCommandReceipt receipt = action.get();
                receipt.setActorMemberId(actor); receipt.setScopeType(1);
                receipt.setMerchantId(current.getMerchantId()); receipt.setOwnerMemberId(current.getOwnerMemberId());
                receipt.setRequestId(id); receipt.setOperation(operation); receipt.setPayloadVersion(1);
                receipt.setPayloadSha256(hash); receipt.setCompletedAt(new Date());
                try {
                    if (receiptMapper.insertReceipt(receipt) != 1) throw new IllegalStateException("命令回执未保存");
                } catch (DuplicateKeyException collision) {
                    // 仅回执唯一键竞争可回读，不能把券写入异常当成重复命令。
                    throw new ReceiptCollision(collision);
                }
                return response(receipt);
            });
        } catch (ReceiptCollision collision) {
            // 失败事务已回滚；禁止在 rollback-only 事务里继续读取。
            return transaction().execute(status -> {
                MerchantAccessContext current = authority(actor, expected.getMerchantId());
                CouponCommandReceipt previous = receiptMapper.selectCurrent(actor, current.getMerchantId(), id);
                if (previous == null) throw collision;
                return replay(previous, hash, current);
            });
        }
    }
    private CouponCommandReceipt publishOnce(CouponPublisDTO request, MerchantAccessContext access) {
        Long owner = access.getOwnerMemberId();
        String quotaError = quotaService.checkCreate(owner, IQuotaService.TYPE_COUPON);
        if (quotaError != null) return result(false, "QUOTA_REJECTED", quotaError, null, null);
        String key = "coupon:publish:" + owner + ":" + request.getCouponType() + ":" + (System.currentTimeMillis() / 60000L);
        if (Boolean.FALSE.equals(redisCache.setIfAbsent(key, "1", 60, TimeUnit.SECONDS)))
            return result(false, "PUBLISH_RATE_LIMITED", "发券太频繁，请稍后再试", null, null);
        SmsCoupon coupon = new SmsCoupon();
        coupon.setMemberId(owner); coupon.setScopeType(1); coupon.setMerchantId(access.getMerchantId());
        coupon.setStatus(1); coupon.setDelFlag(0); coupon.setPlatform(0);
        coupon.setName(request.getName()); coupon.setCouponType(request.getCouponType());
        coupon.setStartTime(request.getStartTime()); coupon.setEndTime(request.getEndTime());
        coupon.setPublishCount(request.getPublishCount()); coupon.setUseCount(0L); coupon.setReceiveCount(0L);
        coupon.setDescription(request.getDescription());
        Integer rows = quotaService.runWithCreateGuard(owner, IQuotaService.TYPE_COUPON, () -> smsCouponService.insertSmsCoupon(coupon));
        if (Integer.valueOf(0).equals(rows)) return result(false, "WRITE_NOT_APPLIED", "发布失败", null, null);
        if (rows == null || rows != 1 || coupon.getId() == null || coupon.getId() <= 0) throw new IllegalStateException("优惠券写入结果不确定");
        return result(true, "PUBLISHED", "发布成功", coupon.getId(), 1);
    }
    private CouponCommandReceipt stopOnce(Long id, MerchantAccessContext access) {
        SmsCoupon coupon = smsCouponMapper.selectMerchantCommandCurrent(id, access.getOwnerMemberId(), access.getMerchantId());
        if (coupon == null) return result(false, "COUPON_NOT_FOUND", "优惠券不存在或无权停发", id, null);
        if (coupon.getDelFlag() != null && coupon.getDelFlag() != 0) return result(false, "COUPON_NOT_FOUND", "优惠券不存在", id, null);
        Integer state = coupon.getStatus();
        if (Integer.valueOf(3).equals(state)) return result(false, "COUPON_MANUALLY_INVALIDATED", "该优惠券已被平台手动失效", id, state);
        if (Integer.valueOf(4).equals(state)) return result(false, "COUPON_ALREADY_STOPPED", "该优惠券已停发,无需重复操作", id, state);
        if (!Integer.valueOf(0).equals(state) && !Integer.valueOf(1).equals(state))
            return result(false, "COUPON_ENDED", "已结束的优惠券无法停发", id, state);
        if (smsCouponMapper.stopIssuingMerchantCommand(id, access.getOwnerMemberId(), access.getMerchantId()) != 1) {
            SmsCoupon current = smsCouponMapper.selectMerchantCommandCurrent(id, access.getOwnerMemberId(), access.getMerchantId());
            return result(false, "STOP_CAS_NOT_APPLIED", current != null && Integer.valueOf(4).equals(current.getStatus())
                    ? "该优惠券已停发,无需重复操作" : "停发失败,请刷新后重试", id, current == null ? null : current.getStatus());
        }
        return result(true, "STOPPED", "已停发", id, 4);
    }
    private MerchantAccessContext authority(Long actor, Long merchant) {
        MerchantAccessContext access = merchantAccessService.require(actor, MerchantPermission.COUPON_MANAGE);
        if (!merchant.equals(access.getMerchantId())) throw new ServiceException("商家身份已变更，请重新确认", 403);
        return access;
    }
    private AjaxResult preflight(Long actor, String id, Long merchant, String scope) {
        if (!enabled) return error(503, "COMMANDS_UNAVAILABLE", "命令结果服务未启用");
        if (actor == null || actor <= 0) return error(401, "LOGIN_REQUIRED", "请先登录");
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || merchant == null || merchant <= 0 || !"MERCHANT".equals(scope))
            return error(400, "INVALID_COMMAND", "命令参数无效");
        return null;
    }
    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // 返回前提交；已有 quota REQUIRED guard 加入此事务，不提前提交。
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
    private AjaxResult replay(CouponCommandReceipt receipt, byte[] hash, MerchantAccessContext current) {
        if (!current.getOwnerMemberId().equals(receipt.getOwnerMemberId()) || !Arrays.equals(hash, receipt.getPayloadSha256()))
            return error(409, "COMMAND_PAYLOAD_CONFLICT", "同一命令不能用于不同内容");
        return response(receipt);
    }
    private static CouponCommandReceipt result(boolean success, String reason, String message, Long id, Integer state) {
        CouponCommandReceipt receipt = new CouponCommandReceipt();
        receipt.setOutcome(success ? "SUCCEEDED" : "REJECTED"); receipt.setReasonCode(reason);
        receipt.setResultCode(success ? 200 : 500); receipt.setResultMessage(message);
        receipt.setCouponId(id); receipt.setCouponStatusAtExecution(state);
        return receipt;
    }
    private static AjaxResult error(int code, String reason, String message) {
        Map<String, Object> data = new LinkedHashMap<>(); data.put("reasonCode", reason);
        return new AjaxResult(code, message, data);
    }
    private static AjaxResult response(CouponCommandReceipt receipt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", receipt.getPayloadVersion()); value.put("requestId", receipt.getRequestId());
        value.put("operation", receipt.getOperation()); value.put("scope", "MERCHANT");
        value.put("merchantId", receipt.getMerchantId()); value.put("ownerMemberId", receipt.getOwnerMemberId());
        value.put("actorMemberId", receipt.getActorMemberId()); value.put("payloadHash", hex(receipt.getPayloadSha256()));
        value.put("outcome", receipt.getOutcome()); value.put("reasonCode", receipt.getReasonCode());
        value.put("couponId", receipt.getCouponId()); value.put("couponStatusAtExecution", receipt.getCouponStatusAtExecution());
        value.put("completedAt", receipt.getCompletedAt());
        Map<String, Object> data = new LinkedHashMap<>(); data.put("commandReceipt", value);
        if ("PUBLISH".equals(receipt.getOperation()) && "SUCCEEDED".equals(receipt.getOutcome())) data.put("id", receipt.getCouponId());
        return new AjaxResult(receipt.getResultCode(), receipt.getResultMessage(), data);
    }
    static byte[] hash(String operation, MerchantAccessContext access, CouponPublisDTO request, Long couponId) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(1); string(out, operation); out.writeInt(1);
            out.writeLong(access.getMerchantId()); out.writeLong(access.getOwnerMemberId());
            if (request != null) {
                string(out, request.getName()); string(out, request.getDescription());
                out.writeInt(request.getCouponType()); out.writeLong(request.getPublishCount());
                out.writeLong(request.getStartTime().getTime()); out.writeLong(request.getEndTime().getTime());
            } else out.writeLong(couponId);
            out.flush(); return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray());
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成命令摘要", exception);
        }
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static final class ReceiptCollision extends RuntimeException {
        private ReceiptCollision(DuplicateKeyException cause) { super(cause); }
    }
}
