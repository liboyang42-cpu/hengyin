package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingAttribution;
import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.dto.AttributionClaimRequest;
import com.chengyinhub.business.domain.dto.AttributionClaimResult;
import com.chengyinhub.business.domain.dto.CampaignAttributionAggregate;
import com.chengyinhub.business.domain.dto.ReferralTokenIssueRequest;
import com.chengyinhub.business.domain.dto.ReferralTokenView;
import com.chengyinhub.business.exception.MarketingAttributionException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingReferralCredentialMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingAttributionService;
import com.chengyinhub.business.service.support.ReferralTokenCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Date;
import java.util.UUID;

/**
 * 推广归因最小实现(只归因)。
 *
 * <p><b>写边界</b>:本类只写 marketing_referral_credential 与 marketing_attribution 两张表,
 * 对 cms_registration 只做只读校验。没有结算、应付、退款、权益、支付、报价的任何依赖 ——
 * 因此结构上不可能"推广参数自授佣金"或"改锁价"。
 *
 * <p><b>为什么不把凭据塞进 cms_registration</b>:{@code source_club_id} 的费率受益人与
 * 归因列由票源链路独占,{@code CmsRegistrationServiceImpl} 归 M2 独占;归因走独立事实表
 * 既避免改动共享建单逻辑,也不会让归因值反过来影响任何金额。
 *
 * <p><b>已知边界(明确,不掩饰)</b>:
 * <ul>
 *   <li>本期由客户端在下单成功后调用领取接口;若客户端不调用,则该单无归因(只影响报表,
 *       不影响钱与权益)。要同事务落库需改共享建单逻辑,已交 M2 的精确 patch 待批。</li>
 *   <li>没有运营撤销接口:新增权限串属 RBAC 变更,未获批准。凭据靠 {@code expires_at} 收敛。</li>
 * </ul>
 */
@Service
public class MarketingAttributionServiceImpl implements IMarketingAttributionService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAttributionServiceImpl.class);

    static final int DEFAULT_TTL_SECONDS = 30 * 24 * 60 * 60;
    static final int MIN_TTL_SECONDS = 60 * 60;
    static final int MAX_TTL_SECONDS = 90 * 24 * 60 * 60;

    private final MarketingReferralCredentialMapper credentialMapper;
    private final MarketingAttributionMapper attributionMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final ICmsTopicService topicService;
    private final ICmsActivityService activityService;
    private final String secret;

    public MarketingAttributionServiceImpl(MarketingReferralCredentialMapper credentialMapper,
                                           MarketingAttributionMapper attributionMapper,
                                           CmsRegistrationMapper registrationMapper,
                                           ICmsTopicService topicService,
                                           ICmsActivityService activityService,
                                           @Value("${marketing.referral-secret:${verify.dyncode-secret:${token.secret}}}")
                                           String secret) {
        this.credentialMapper = credentialMapper;
        this.attributionMapper = attributionMapper;
        this.registrationMapper = registrationMapper;
        this.topicService = topicService;
        this.activityService = activityService;
        this.secret = secret;
    }

    @Override
    public ReferralTokenView issueToken(long sharerMemberId, ReferralTokenIssueRequest request) {
        if (sharerMemberId <= 0) {
            throw MarketingAttributionException.forbidden("LOGIN_REQUIRED", "请先登录");
        }
        if (request == null) {
            throw MarketingAttributionException.badRequest("TARGET_REQUIRED", "缺少推广目标");
        }
        Integer targetType = request.getTargetType();
        Long targetId = request.getTargetId();
        if (targetType == null || (targetType != MarketingReferralCredential.TARGET_TOPIC
                && targetType != MarketingReferralCredential.TARGET_ACTIVITY)
                || targetId == null || targetId <= 0) {
            throw MarketingAttributionException.badRequest("TARGET_INVALID", "推广目标不合法");
        }
        requirePublishedTarget(targetType, targetId);
        String campaignKey = normalizeCampaign(request.getCampaignKey());
        int ttlSeconds = normalizeTtl(request.getTtlSeconds());

        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + ttlSeconds * 1000L);
        long expiresAtEpochSec = expiresAt.getTime() / 1000L;

        MarketingReferralCredential credential = null;
        String issuedToken = null;
        for (int attempt = 0; attempt < 2 && credential == null; attempt++) {
            String publicId = newPublicId();
            String token = ReferralTokenCodec.issue(publicId, expiresAtEpochSec, secret);
            MarketingReferralCredential row = new MarketingReferralCredential();
            row.setPublicId(publicId);
            row.setSharerMemberId(sharerMemberId);
            row.setCampaignKey(campaignKey);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setIssuedAt(now);
            row.setExpiresAt(expiresAt);
            row.setStatus(MarketingReferralCredential.STATUS_ACTIVE);
            row.setTokenHash(ReferralTokenCodec.sha256Hex(token));
            row.setCreateTime(now);
            try {
                if (credentialMapper.insertCredential(row) == 1) {
                    credential = row;
                    issuedToken = token;
                }
            } catch (DuplicateKeyException collision) {
                // 公开标识撞车(128 位随机,实际不会发生):换一个再试,不把偶发撞车变成失败。
                log.warn("[marketing] referral publicId collision, retrying once");
            }
        }
        if (credential == null || issuedToken == null) {
            throw new IllegalStateException("推广凭据签发失败");
        }

        ReferralTokenView view = new ReferralTokenView();
        view.setToken(issuedToken);
        view.setTargetType(targetType);
        view.setTargetId(targetId);
        view.setCampaignKey(campaignKey);
        view.setExpiresAt(expiresAt);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttributionClaimResult claim(long callerMemberId, AttributionClaimRequest request) {
        if (callerMemberId <= 0) {
            throw MarketingAttributionException.forbidden("LOGIN_REQUIRED", "请先登录");
        }
        if (request == null) {
            throw MarketingAttributionException.badRequest("ORDER_REQUIRED", "缺少订单");
        }
        if (request.getOrderType() == null
                || request.getOrderType() != MarketingAttribution.ORDER_REGISTRATION) {
            throw MarketingAttributionException.badRequest("UNSUPPORTED_ORDER_TYPE", "订单类型不支持归因");
        }
        Long orderId = request.getOrderId();
        if (orderId == null || orderId <= 0) {
            throw MarketingAttributionException.badRequest("ORDER_REQUIRED", "缺少订单");
        }
        String token = request.getToken();
        if (token == null || token.trim().isEmpty()) {
            throw MarketingAttributionException.badRequest("TOKEN_REQUIRED", "缺少推广凭据");
        }

        Date now = new Date();
        ReferralTokenCodec.Result verified =
                ReferralTokenCodec.verify(token, now.getTime() / 1000L, secret);
        if (!verified.valid) {
            // INVALID_TOKEN / TOKEN_EXPIRED 都可区分,但都不泄露凭据是否存在。
            throw MarketingAttributionException.badRequest(verified.reason, "推广凭据无效或已过期");
        }
        MarketingReferralCredential credential = credentialMapper.selectByPublicId(verified.publicId);
        if (credential == null || credential.getId() == null
                || !ReferralTokenCodec.constEquals(credential.getTokenHash(),
                        ReferralTokenCodec.sha256Hex(token.trim()))) {
            throw MarketingAttributionException.badRequest("INVALID_TOKEN", "推广凭据无效或已过期");
        }
        if (credential.getStatus() == null
                || credential.getStatus() != MarketingReferralCredential.STATUS_ACTIVE) {
            throw MarketingAttributionException.conflict("TOKEN_REVOKED", "推广凭据已失效");
        }
        if (credential.getExpiresAt() == null || !credential.getExpiresAt().after(now)) {
            throw MarketingAttributionException.badRequest("TOKEN_EXPIRED", "推广凭据无效或已过期");
        }

        CmsRegistration registration = registrationMapper.selectCmsRegistrationById(orderId);
        if (registration == null) {
            throw MarketingAttributionException.notFound("ORDER_NOT_FOUND", "订单不存在");
        }
        if (registration.getMemberId() == null || !registration.getMemberId().equals(callerMemberId)) {
            throw MarketingAttributionException.forbidden("ORDER_NOT_OWNED", "该订单不属于当前账号");
        }
        requireNotSelfReferral(credential, callerMemberId);
        requireAttributableOrder(registration);
        requireTargetMatches(credential, registration);
        requireInsideWindow(credential, registration);

        MarketingAttribution existing =
                attributionMapper.selectByOrder(MarketingAttribution.ORDER_REGISTRATION, orderId);
        if (existing != null) {
            return replayOrConflict(existing, credential);
        }

        MarketingAttribution row = new AttributionRowBuilder(registration, credential, callerMemberId, now).build();
        try {
            if (attributionMapper.insertAttribution(row) != 1) {
                throw new IllegalStateException("归因写入失败");
            }
        } catch (DuplicateKeyException raced) {
            MarketingAttribution winner =
                    attributionMapper.selectByOrder(MarketingAttribution.ORDER_REGISTRATION, orderId);
            if (winner == null) {
                throw raced;
            }
            return replayOrConflict(winner, credential);
        }
        return toResult(row, false);
    }

    @Override
    public CampaignAttributionAggregate aggregateOwnedCampaign(long callerMemberId, String campaignKey,
                                                               Integer targetType, Long targetId) {
        CampaignAttributionAggregate empty = new CampaignAttributionAggregate();
        empty.setCampaignKey(campaignKey);
        empty.setTargetType(targetType);
        empty.setTargetId(targetId);
        empty.setBound(false);
        empty.setPaidAmount(java.math.BigDecimal.ZERO);
        empty.setRefundedAmount(java.math.BigDecimal.ZERO);
        if (callerMemberId <= 0 || campaignKey == null || campaignKey.trim().isEmpty()
                || targetType == null || targetId == null || targetId <= 0) {
            return empty;
        }
        String key = campaignKey.trim();
        // 绑定门槛:没有服务端签发的凭据就没有"这条 campaign"这回事。
        // 客户端可以随便传同名字符串 —— 那不是来源证明,也不能据此跨单聚合。
        if (attributionMapper.countOwnedCredentials(callerMemberId, key, targetType, targetId) <= 0) {
            return empty;
        }
        CampaignAttributionAggregate aggregate =
                attributionMapper.aggregateOwnedCampaign(callerMemberId, key, targetType, targetId);
        if (aggregate == null) {
            return empty;
        }
        aggregate.setCampaignKey(key);
        aggregate.setTargetType(targetType);
        aggregate.setTargetId(targetId);
        aggregate.setBound(true);
        if (aggregate.getPaidAmount() == null) aggregate.setPaidAmount(java.math.BigDecimal.ZERO);
        if (aggregate.getRefundedAmount() == null) aggregate.setRefundedAmount(java.math.BigDecimal.ZERO);
        return aggregate;
    }

    // ------------------------------------------------------------------ internals

    private AttributionClaimResult replayOrConflict(MarketingAttribution existing,
                                                    MarketingReferralCredential credential) {
        if (existing.getCredentialId() != null && existing.getCredentialId().equals(credential.getId())) {
            return toResult(existing, true);
        }
        throw MarketingAttributionException.conflict("ATTRIBUTION_CONFLICT",
                "该订单已归因到其他推广来源,不允许改写");
    }

    private AttributionClaimResult toResult(MarketingAttribution row, boolean replayed) {
        AttributionClaimResult result = new AttributionClaimResult();
        result.setOrderType(row.getOrderType());
        result.setOrderId(row.getOrderId());
        result.setCampaignKey(row.getCampaignKey());
        result.setSharerMemberId(row.getSharerMemberId());
        result.setAttributedAt(row.getCreateTime());
        result.setReplayed(replayed);
        return result;
    }

    private void requirePublishedTarget(Integer targetType, Long targetId) {
        if (targetType == MarketingReferralCredential.TARGET_TOPIC) {
            CmsTopic topic = topicService.selectCmsTopicById(targetId);
            if (topic == null || !Integer.valueOf(1).equals(topic.getStatus())) {
                throw MarketingAttributionException.notFound("TARGET_NOT_FOUND", "推广目标不存在或未上架");
            }
            return;
        }
        CmsActivity activity = activityService.selectCmsActivityById(targetId);
        if (activity == null || !Long.valueOf(1L).equals(activity.getStatus())) {
            throw MarketingAttributionException.notFound("TARGET_NOT_FOUND", "推广目标不存在或未上架");
        }
    }

    /**
     * 自我归因拦截:分享者不能给自己买的单挂自己的推广来源。
     *
     * <p>当前归因不发钱,所以这不是资损闸;但它挡的是"自推自买刷高推广数据"这件事本身,
     * 以及将来若有人把归因接上分佣时的第一号作弊路径 —— 与票源归因那道恒 null 的边界同一个思路:
     * 宁可明确没有,不要留一个以后会被当成收入依据的假事实。
     */
    private void requireNotSelfReferral(MarketingReferralCredential credential, long callerMemberId) {
        if (credential.getSharerMemberId() == null
                || credential.getSharerMemberId().longValue() == callerMemberId) {
            throw MarketingAttributionException.badRequest("SELF_REFERRAL",
                    "不能把订单归因到自己的推广来源");
        }
    }

    /**
     * 只给"还活着"的单记归因:已取消/已过期(registration_status 3/4)的单没有履约事实,
     * 给它记来源只会污染报表,并且在未来接分佣时制造"退过的单也算业绩"的入口。
     * 已退款(payment_status=4)同样不记。
     */
    private void requireAttributableOrder(CmsRegistration registration) {
        Integer registrationStatus = registration.getRegistrationStatus();
        if (Integer.valueOf(3).equals(registrationStatus) || Integer.valueOf(4).equals(registrationStatus)) {
            throw MarketingAttributionException.conflict("ORDER_NOT_ATTRIBUTABLE",
                    "订单已取消或已过期,不予归因");
        }
        if (Integer.valueOf(4).equals(registration.getPaymentStatus())) {
            throw MarketingAttributionException.conflict("ORDER_NOT_ATTRIBUTABLE",
                    "订单已退款,不予归因");
        }
    }

    private void requireTargetMatches(MarketingReferralCredential credential, CmsRegistration registration) {
        Long ownerType = registration.getOwnerType();
        Long ownerId = registration.getOwnerId();
        boolean matches = ownerType != null && ownerId != null
                && credential.getTargetType() != null
                && ownerType.longValue() == credential.getTargetType().longValue()
                && ownerId.equals(credential.getTargetId());
        if (!matches) {
            throw MarketingAttributionException.badRequest("TARGET_MISMATCH", "推广凭据与订单商品不匹配");
        }
    }

    private void requireInsideWindow(MarketingReferralCredential credential, CmsRegistration registration) {
        Date orderCreatedAt = registration.getCreateTime();
        if (orderCreatedAt == null
                || credential.getIssuedAt() == null
                || credential.getExpiresAt() == null
                || orderCreatedAt.before(credential.getIssuedAt())
                || orderCreatedAt.after(credential.getExpiresAt())) {
            throw MarketingAttributionException.badRequest("ORDER_OUTSIDE_TOKEN_WINDOW",
                    "订单不在推广凭据有效期内,不予归因");
        }
    }

    private String normalizeCampaign(String campaignKey) {
        if (campaignKey == null || campaignKey.trim().isEmpty()) {
            return "share";
        }
        String value = campaignKey.trim();
        if (!value.matches("[a-z0-9_-]{1,40}")) {
            throw MarketingAttributionException.badRequest("CAMPAIGN_INVALID", "推广标识不合法");
        }
        return value;
    }

    private int normalizeTtl(Integer ttlSeconds) {
        if (ttlSeconds == null) {
            return DEFAULT_TTL_SECONDS;
        }
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw MarketingAttributionException.badRequest("TTL_INVALID", "有效期超出允许范围");
        }
        return ttlSeconds;
    }

    private String newPublicId() {
        return new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString(36);
    }

    /** 归因行构造 + 规范化摘要:字段顺序固定,任何字段变化都会改变摘要。 */
    private static final class AttributionRowBuilder {
        private final CmsRegistration registration;
        private final MarketingReferralCredential credential;
        private final long claimedMemberId;
        private final Date now;

        AttributionRowBuilder(CmsRegistration registration, MarketingReferralCredential credential,
                              long claimedMemberId, Date now) {
            this.registration = registration;
            this.credential = credential;
            this.claimedMemberId = claimedMemberId;
            this.now = now;
        }

        MarketingAttribution build() {
            MarketingAttribution row = new MarketingAttribution();
            row.setOrderType(MarketingAttribution.ORDER_REGISTRATION);
            row.setOrderId(registration.getId());
            row.setCredentialId(credential.getId());
            row.setSharerMemberId(credential.getSharerMemberId());
            row.setCampaignKey(credential.getCampaignKey());
            row.setClaimedMemberId(claimedMemberId);
            row.setOrderCreatedAt(registration.getCreateTime());
            row.setCreateTime(now);
            row.setSnapshotHash(hash(row));
            return row;
        }

        private static String hash(MarketingAttribution row) {
            StringBuilder canonical = new StringBuilder("marketing-attribution-v1");
            append(canonical, row.getOrderType());
            append(canonical, row.getOrderId());
            append(canonical, row.getCredentialId());
            append(canonical, row.getSharerMemberId());
            append(canonical, row.getCampaignKey());
            append(canonical, row.getClaimedMemberId());
            append(canonical, row.getOrderCreatedAt() == null ? null : row.getOrderCreatedAt().getTime());
            return ReferralTokenCodec.sha256Hex(canonical.toString());
        }

        private static void append(StringBuilder canonical, Object value) {
            String text = value == null ? null : String.valueOf(value);
            if (text == null) {
                canonical.append("-1:");
            } else {
                canonical.append(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .append(':').append(text);
            }
        }
    }
}
