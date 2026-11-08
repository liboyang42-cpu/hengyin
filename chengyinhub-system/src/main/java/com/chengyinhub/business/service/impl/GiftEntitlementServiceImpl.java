package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.dto.GiftClaimRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IGiftEntitlementService;
import com.chengyinhub.business.service.support.MarketingClaimTokenCodec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 礼品签发/一次领取/退款撤销。
 *
 * <p><b>不建第二权益</b>：买家已付的 {@code cms_registration} 行是唯一可玩真源。
 * 领取只做一次 CAS，把受益人与**同一行**绑定（不改 member_id、金额、支付事实、退款归属），
 * 由 {@code CurrentPlayRegistration} 按本表的礼品事实决定谁可玩：
 * 待领取 ⇒ 无人可玩；已领取 ⇒ 只有领取人可玩（含买家自领，仍只有一份）。
 */
@Service
public class GiftEntitlementServiceImpl implements IGiftEntitlementService {

    private static final char[] ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GiftEntitlementMapper giftMapper;
    private final MarketingProductMapper productMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final PlayerNodeProgressMapper progressMapper;
    private final String secret;

    public GiftEntitlementServiceImpl(GiftEntitlementMapper giftMapper,
                                      MarketingProductMapper productMapper,
                                      CmsRegistrationMapper registrationMapper,
                                      PlayerNodeProgressMapper progressMapper,
                                      @Value("${marketing.product-secret:${marketing.referral-secret:${verify.dyncode-secret:${token.secret}}}}")
                                      String secret) {
        this.giftMapper = giftMapper;
        this.productMapper = productMapper;
        this.registrationMapper = registrationMapper;
        this.progressMapper = progressMapper;
        this.secret = secret;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String issue(MarketingProductOrder order, Date now) {
        MarketingProduct product = productMapper.selectById(order.getProductId());
        String publicId = publicId();
        Date claimExpiresAt = product == null || product.getGiftClaimDays() == null ? null
                : new Date(now.getTime() + TimeUnit.DAYS.toMillis(product.getGiftClaimDays().longValue()));
        long exp = claimExpiresAt == null ? 0L : claimExpiresAt.getTime() / 1000L;
        String token = MarketingClaimTokenCodec.issue(
                MarketingClaimTokenCodec.GIFT_PREFIX, publicId, exp, secret);
        if (order.getPrimaryRegistrationId() == null) {
            // 没有买家已付行就没有可转移的准入；不签发一张永远领不了的码。
            throw MarketingProductException.conflict("GIFT_SOURCE_UNLINKED",
                    "礼品订单尚未绑定已付权益，不能签发领取码");
        }
        GiftEntitlement gift = new GiftEntitlement();
        gift.setPublicId(publicId);
        gift.setOrderId(order.getId());
        gift.setProductId(order.getProductId());
        gift.setSourceRegistrationId(order.getPrimaryRegistrationId());
        gift.setBuyerMemberId(order.getMemberId());
        gift.setRecipientMemberId(order.getGiftRecipientMemberId());
        gift.setClaimTokenHash(MarketingClaimTokenCodec.sha256Hex(token));
        gift.setClaimExpiresAt(claimExpiresAt);
        gift.setStatus(GiftEntitlement.STATUS_PENDING);
        gift.setCreateTime(now);
        gift.setUpdateTime(now);
        giftMapper.insert(gift);
        return token;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claim(long callerMemberId, GiftClaimRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getToken() == null || request.getToken().trim().isEmpty()) {
            throw MarketingProductException.badRequest("TOKEN_REQUIRED", "请提供礼品领取码");
        }
        String token = request.getToken().trim();
        String tokenHash = MarketingClaimTokenCodec.sha256Hex(token);
        GiftEntitlement gift = giftMapper.selectByTokenHashForUpdate(tokenHash);
        if (gift != null
                && Integer.valueOf(GiftEntitlement.STATUS_CLAIMED).equals(gift.getStatus())
                && Long.valueOf(callerMemberId).equals(gift.getClaimedMemberId())) {
            return claimView(gift);
        }
        MarketingClaimTokenCodec.Result verified = MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, token, System.currentTimeMillis() / 1000L, secret);
        if (!verified.valid) {
            expireIfFound(tokenHash);
            throw MarketingProductException.conflict(
                    "TOKEN_EXPIRED".equals(verified.reason) ? "GIFT_CLAIM_EXPIRED" : "GIFT_TOKEN_INVALID",
                    "TOKEN_EXPIRED".equals(verified.reason) ? "礼品领取码已过期" : "礼品领取码无效");
        }
        if (gift == null) throw MarketingProductException.notFound("GIFT_NOT_FOUND", "礼品不存在");
        if (gift.getRecipientMemberId() != null
                && !Long.valueOf(callerMemberId).equals(gift.getRecipientMemberId())) {
            throw MarketingProductException.forbidden("GIFT_NOT_FOR_YOU", "这份礼品不是给你的");
        }
        // 自领不设禁令：礼品 SKU 无「禁止自领」的产品合同；同人领取仍是同一行、同一份准入。
        // 一次 claim / 一份准入是硬边界，由 status 0->1 CAS 保证。
        Date now = new Date();
        if (Integer.valueOf(GiftEntitlement.STATUS_CLAIMED).equals(gift.getStatus())) {
            throw MarketingProductException.conflict("GIFT_ALREADY_CLAIMED", "礼品已被领取");
        }
        if (!Integer.valueOf(GiftEntitlement.STATUS_PENDING).equals(gift.getStatus())) {
            throw MarketingProductException.conflict("GIFT_UNAVAILABLE", "礼品当前不可领取");
        }
        if (gift.getClaimExpiresAt() != null && !gift.getClaimExpiresAt().after(now)) {
            giftMapper.expire(gift.getId(), now);
            throw MarketingProductException.conflict("GIFT_CLAIM_EXPIRED", "礼品领取码已过期");
        }

        // 单行语义：买家已付行就是唯一可玩真源。领取只做一次 CAS 并把受益人与**同一行**绑定，
        // 不新建 registration、不再消费付款事实、不改买家财务/退款归属。
        Long sourceRegistrationId = gift.getSourceRegistrationId();
        if (sourceRegistrationId == null) {
            throw MarketingProductException.conflict("GIFT_SOURCE_UNLINKED",
                    "礼品尚未绑定买家已付权益，不能领取");
        }
        // 锁放领取事务：对源行取写锁，与本域准入读（对保留行取 FOR UPDATE）串行；
        // 领取期间任何人不能把这一行玩起来，领取后买家也不再被放行。
        CmsRegistration source = registrationMapper.selectCmsRegistrationByIdForUpdate(sourceRegistrationId);
        requireClaimableSource(gift, source, now);
        if (giftMapper.claimCas(gift.getId(), callerMemberId,
                request.getRequestId() == null ? null : request.getRequestId().trim(), now) != 1) {
            GiftEntitlement raced = giftMapper.selectById(gift.getId());
            if (raced != null && Long.valueOf(callerMemberId).equals(raced.getClaimedMemberId())) {
                return claimView(raced);
            }
            throw MarketingProductException.conflict("GIFT_ALREADY_CLAIMED", "礼品已被领取");
        }
        giftMapper.bindSameRegistration(gift.getId(), sourceRegistrationId, now);
        return claimView(giftMapper.selectById(gift.getId()));
    }

    /**
     * 领取前核原 registration：范围 / 支付 / 成功态 / 时效 / 已开始事实。
     * 客户端只给码，全部事实来自服务端；不接受客户端给 registrationId。
     */
    private void requireClaimableSource(GiftEntitlement gift, CmsRegistration source, Date now) {
        if (source == null) {
            throw MarketingProductException.conflict("GIFT_SOURCE_MISSING", "礼品对应的原权益不存在");
        }
        if (!Long.valueOf(gift.getBuyerMemberId()).equals(source.getMemberId())) {
            throw MarketingProductException.conflict("GIFT_SOURCE_OWNER_MISMATCH", "礼品原权益不属于买家");
        }
        if (!Long.valueOf(1L).equals(source.getOwnerType()) || source.getOwnerId() == null) {
            throw MarketingProductException.conflict("GIFT_SOURCE_SCOPE_INVALID", "礼品只能来自自玩通行证");
        }
        if (!Integer.valueOf(2).equals(source.getPaymentStatus())
                || !Integer.valueOf(2).equals(source.getRegistrationStatus())) {
            throw MarketingProductException.conflict("GIFT_SOURCE_INACTIVE", "礼品原权益当前不可用");
        }
        if (source.getExpiresAt() != null && !source.getExpiresAt().after(now)) {
            throw MarketingProductException.conflict("GIFT_SOURCE_EXPIRED", "礼品原权益已过期");
        }
        // 商品必须覆盖该主题：防止有人把 A 商品的码绑到 B 商品的已付行。
        MarketingProduct product = productMapper.selectById(gift.getProductId());
        if (product == null) throw MarketingProductException.conflict("GIFT_PRODUCT_MISSING", "礼品商品不存在");
        if (!covers(product, source.getOwnerId())) {
            throw MarketingProductException.conflict("GIFT_TOPIC_NOT_COVERED", "礼品商品未覆盖该主题");
        }
        // 已开始/已核销：沿用原事实（商家核销、首次扫码、已记节点进度），不允许"买家玩过再送"。
        if (Integer.valueOf(1).equals(source.getVerificationStatus())
                || source.getFirstSuccessfulScanAt() != null || hasStartedProgress(source)) {
            throw MarketingProductException.conflict("GIFT_SOURCE_ALREADY_USED", "该通行证已开始使用，不能转赠");
        }
        requireAllRowsClaimable(gift, source, product, now);
    }

    /**
     * 多主题 pass：领取转移的是**整单**产出的每一行，不只 primary。
     * 逐行复核同一口径（属于买家、自玩域、已付成功、未过期、商品覆盖、未开始），
     * 任一行不合格即整单拒绝；查询用 parent_order_no 一次取回，不逐主题 N+1。
     */
    private void requireAllRowsClaimable(GiftEntitlement gift, CmsRegistration source,
                                         MarketingProduct product, Date now) {
        if (source.getParentOrderNo() == null || source.getParentOrderNo().trim().isEmpty()) {
            return; // 无父子单的老数据：单行语义已由 primary 校验覆盖。
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setParentOrderNo(source.getParentOrderNo());
        List<CmsRegistration> rows = registrationMapper.selectCmsRegistrationList(probe);
        if (rows == null || rows.isEmpty()) return;
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null) continue;
            if (!Long.valueOf(gift.getBuyerMemberId()).equals(row.getMemberId())
                    || !Long.valueOf(1L).equals(row.getOwnerType())
                    || !Integer.valueOf(2).equals(row.getPaymentStatus())
                    || !Integer.valueOf(2).equals(row.getRegistrationStatus())) {
                throw MarketingProductException.conflict("GIFT_SOURCE_INACTIVE", "礼品原权益当前不可用");
            }
            if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) {
                throw MarketingProductException.conflict("GIFT_SOURCE_EXPIRED", "礼品原权益已过期");
            }
            if (!covers(product, row.getOwnerId())) {
                throw MarketingProductException.conflict("GIFT_TOPIC_NOT_COVERED", "礼品商品未覆盖该主题");
            }
            if (Integer.valueOf(1).equals(row.getVerificationStatus())
                    || row.getFirstSuccessfulScanAt() != null || hasStartedProgress(row)) {
                throw MarketingProductException.conflict("GIFT_SOURCE_ALREADY_USED", "该通行证已开始使用，不能转赠");
            }
        }
    }

    private boolean covers(MarketingProduct product, Long topicId) {
        if (product == null || topicId == null) return false;
        List<MarketingProductItem> items = productMapper.selectItems(product.getId());
        if (items == null) return false;
        for (MarketingProductItem item : items) {
            if (item != null && topicId.equals(item.getTopicId())) return true;
        }
        return false;
    }

    private boolean hasStartedProgress(CmsRegistration source) {
        if (progressMapper == null || source.getOwnerId() == null) return false;
        try {
            PlayerNodeProgress probe = new PlayerNodeProgress();
            probe.setMemberId(source.getMemberId());
            probe.setTopicId(source.getOwnerId());
            probe.setActivityId(0L);
            List<PlayerNodeProgress> rows = progressMapper.selectPlayerNodeProgressList(probe);
            return rows != null && !rows.isEmpty();
        } catch (RuntimeException unavailable) {
            // 读不到进度事实时按"已开始"处理：宁可拒绝转赠，也不能把可能已用的通行证再给第二个人。
            return true;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> giftsBoughtBy(long callerMemberId, int limit) {
        requireMember(callerMemberId);
        return project(giftMapper.selectByBuyer(callerMemberId, size(limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> giftsClaimedBy(long callerMemberId, int limit) {
        requireMember(callerMemberId);
        return project(giftMapper.selectByClaimedMember(callerMemberId, size(limit)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeForOrder(MarketingProductOrder order, String reason, Date now) {
        GiftEntitlement gift = giftMapper.selectByOrderId(order.getId());
        if (gift == null) return;
        if (Integer.valueOf(GiftEntitlement.STATUS_REVOKED).equals(gift.getStatus())) return;
        // 只撤销礼品来源本身：把 status 置 3 后，该行回到买家（原退款归属不变）。
        // 原 registration 的退款/撤销由 C07 原链路按原单执行，本域不改行、不改金额。
        giftMapper.revoke(gift.getId(), reason == null ? "REFUND" : reason, now);
    }

    // ------------------------------------------------------------------ helpers

    private void expireIfFound(String tokenHash) {
        try {
            GiftEntitlement gift = giftMapper.selectByTokenHashForUpdate(tokenHash);
            if (gift != null && Integer.valueOf(GiftEntitlement.STATUS_PENDING).equals(gift.getStatus())) {
                giftMapper.expire(gift.getId(), new Date());
            }
        } catch (RuntimeException ignored) {
            // Rejection path; never turn a token problem into a 500.
        }
    }

    private List<Map<String, Object>> project(List<GiftEntitlement> gifts) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (gifts == null) return out;
        for (GiftEntitlement gift : gifts) out.add(giftView(gift));
        return out;
    }

    private Map<String, Object> claimView(GiftEntitlement gift) {
        Map<String, Object> out = giftView(gift);
        boolean linked = gift.getClaimedRegistrationId() != null;
        out.put("admission", "cms_registration");
        out.put("admissionState", linked ? "LINKED_SAME_REGISTRATION" : "PENDING_CLAIM");
        out.put("registrationId", gift.getClaimedRegistrationId());
        // 单行语义：受益人取得该行；买家保留财务/退款归属但不再可玩（准入读口据此排除）。
        out.put("buyerRetainsFinancials", Boolean.TRUE);
        out.put("buyerCanPlay", Boolean.FALSE);
        return out;
    }

    private Map<String, Object> giftView(GiftEntitlement gift) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("giftId", gift.getId());
        out.put("publicId", gift.getPublicId());
        out.put("orderId", gift.getOrderId());
        out.put("productId", gift.getProductId());
        out.put("sourceRegistrationId", gift.getSourceRegistrationId());
        out.put("buyerMemberId", gift.getBuyerMemberId());
        out.put("recipientMemberId", gift.getRecipientMemberId());
        out.put("status", gift.getStatus());
        out.put("claimedMemberId", gift.getClaimedMemberId());
        out.put("claimedRegistrationId", gift.getClaimedRegistrationId());
        out.put("claimedAt", gift.getClaimedAt());
        out.put("claimExpiresAt", gift.getClaimExpiresAt());
        out.put("revokedReason", gift.getRevokedReason());
        out.put("createdAt", gift.getCreateTime());
        return out;
    }

    private int size(int limit) { return limit <= 0 ? 20 : Math.min(limit, 100); }

    private void requireMember(long memberId) {
        if (memberId <= 0) throw MarketingProductException.unauthorized();
    }

    private static String publicId() {
        StringBuilder out = new StringBuilder(24);
        for (int i = 0; i < 24; i++) out.append(ID_ALPHABET[RANDOM.nextInt(ID_ALPHABET.length)]);
        return out.toString();
    }
}
