package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ReferralLandingGiftRow;
import com.chengyinhub.business.domain.vo.GiftLandingView;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.domain.vo.ReferralLandingView;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingReferralCredentialMapper;
import com.chengyinhub.business.mapper.ReferralLandingMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IPublicContentCatalogService;
import com.chengyinhub.business.service.IReferralLandingService;
import com.chengyinhub.business.service.support.MarketingClaimTokenCodec;
import com.chengyinhub.business.service.support.ReferralTokenCodec;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 落地页只读解析实现。
 *
 * <p>可信边界：签名校验 + 库里 {@code token_hash} 双证才算凭据存在；客户端改签名或
 * 直接拿 {@code ?inviter=<memberId>} 都过不了这里，也拿不到任何奖励/免费局。
 *
 * <p>所有状态都靠既有事实判断：凭据 {@code status/expires_at}、礼品
 * {@code status/claim_expires_at}、目标必须通过既有公开目录/公开活动投影。
 * 本类不持有任何 registration/order/payment 服务，因此结构上无法在"看链接"时发权益。
 *
 * <p>目标"公开可见"不等于"现在能报名/购买"：本类只回 {@code UNKNOWN} 或事实性的
 * {@code CLOSED}，把真正的资格/容量/窗口判定留给原注册与商品报价接口。
 */
@Service
public class ReferralLandingServiceImpl implements IReferralLandingService {

    private final MarketingReferralCredentialMapper credentialMapper;
    private final ReferralLandingMapper landingMapper;
    private final UmsMemberMapper memberMapper;
    private final IPublicContentCatalogService publicCatalogService;
    private final ICmsActivityService activityService;
    private final MarketingProductMapper productMapper;
    private final String secret;

    public ReferralLandingServiceImpl(MarketingReferralCredentialMapper credentialMapper,
                                      ReferralLandingMapper landingMapper,
                                      UmsMemberMapper memberMapper,
                                      IPublicContentCatalogService publicCatalogService,
                                      ICmsActivityService activityService,
                                      MarketingProductMapper productMapper,
                                      @Value("${marketing.referral-secret:${verify.dyncode-secret:${token.secret}}}")
                                      String secret) {
        this.credentialMapper = credentialMapper;
        this.landingMapper = landingMapper;
        this.memberMapper = memberMapper;
        this.publicCatalogService = publicCatalogService;
        this.activityService = activityService;
        this.productMapper = productMapper;
        this.secret = secret;
    }

    @Override
    public ReferralLandingView describeReferral(String token) {
        ReferralLandingView view = new ReferralLandingView();
        view.setEndpoints(new ReferralLandingView.Endpoints());

        String value = trimToNull(token);
        if (value == null) {
            return referralState(view, ReferralLandingView.STATE_INVALID_TOKEN, "TOKEN_REQUIRED");
        }
        Date now = new Date();
        ReferralTokenCodec.Result verified =
                ReferralTokenCodec.verify(value, now.getTime() / 1000L, secret);
        if (!verified.valid) {
            String state = "TOKEN_EXPIRED".equals(verified.reason)
                    ? ReferralLandingView.STATE_TOKEN_EXPIRED : ReferralLandingView.STATE_INVALID_TOKEN;
            return referralState(view, state, verified.reason);
        }

        MarketingReferralCredential credential = credentialMapper.selectByPublicId(verified.publicId);
        if (credential == null || credential.getId() == null
                || !ReferralTokenCodec.constEquals(credential.getTokenHash(),
                        ReferralTokenCodec.sha256Hex(value))) {
            return referralState(view, ReferralLandingView.STATE_INVALID_TOKEN, "INVALID_TOKEN");
        }
        if (credential.getStatus() == null
                || credential.getStatus() != MarketingReferralCredential.STATUS_ACTIVE) {
            return referralState(view, ReferralLandingView.STATE_TOKEN_REVOKED, "TOKEN_REVOKED");
        }
        if (credential.getExpiresAt() == null || !credential.getExpiresAt().after(now)) {
            return referralState(view, ReferralLandingView.STATE_TOKEN_EXPIRED, "TOKEN_EXPIRED");
        }

        if (!fillPublicTarget(view, credential)) {
            return referralState(view, ReferralLandingView.STATE_TARGET_UNAVAILABLE, "TARGET_UNAVAILABLE");
        }
        UmsMember sharer = credential.getSharerMemberId() == null ? null
                : memberMapper.selectUmsMemberById(credential.getSharerMemberId());
        if (sharer == null) {
            return referralState(view, ReferralLandingView.STATE_INVITER_UNAVAILABLE, "INVITER_UNAVAILABLE");
        }

        ReferralLandingView.Member inviter = new ReferralLandingView.Member();
        inviter.setNickname(sharer.getNickname());
        inviter.setAvatar(sharer.getAvatar());

        view.setInviter(inviter);
        view.setCampaignKey(credential.getCampaignKey());
        view.setIssuedAt(credential.getIssuedAt());
        view.setExpiresAt(credential.getExpiresAt());
        // 只有既有公开售卖投影明确"停售/未在售"才是 CLOSED；其余一律 UNKNOWN，
        // 由原注册/商品报价接口重新判定资格、窗口与容量。
        // ★ 免费自玩事实优先：catalog 对"在售但售止缺失/未进入 SELLING"的自由探索主题会给出
        // NOT_ON_SALE，而同一投影同时可给 freeSelfPlay=true（自玩价 0 且未过截止）。此时免费入口
        // 明明开着，不能因为付费售卖状态就报"报名关闭"并断掉去目标页的引导。
        boolean freeEntry = Boolean.TRUE.equals(view.getFreeSelfPlay());
        boolean closed = !freeEntry && ("SALE_ENDED".equals(view.getSaleState())
                || "NOT_ON_SALE".equals(view.getSaleState()));
        view.setRegistrationState(closed
                ? ReferralLandingView.REGISTRATION_CLOSED : ReferralLandingView.REGISTRATION_UNKNOWN);
        view.setNextAction(closed ? ReferralLandingView.ACTION_NONE : ReferralLandingView.ACTION_VIEW_TARGET);
        return referralState(view, ReferralLandingView.STATE_VALID, null);
    }

    @Override
    public GiftLandingView describeGift(String token) {
        GiftLandingView view = new GiftLandingView();
        view.setEndpoints(new GiftLandingView.Endpoints());

        String value = trimToNull(token);
        if (value == null) {
            return giftState(view, GiftLandingView.STATE_INVALID_TOKEN, "TOKEN_REQUIRED");
        }
        Date now = new Date();
        MarketingClaimTokenCodec.Result verified = MarketingClaimTokenCodec.verify(
                MarketingClaimTokenCodec.GIFT_PREFIX, value, now.getTime() / 1000L, secret);
        if (!verified.valid) {
            String state = "TOKEN_EXPIRED".equals(verified.reason)
                    ? GiftLandingView.STATE_TOKEN_EXPIRED : GiftLandingView.STATE_INVALID_TOKEN;
            return giftState(view, state, verified.reason);
        }
        ReferralLandingGiftRow gift =
                landingMapper.selectGiftByTokenHash(MarketingClaimTokenCodec.sha256Hex(value));
        if (gift == null || gift.getProductId() == null) {
            // 签名有效但库里没有对应行：视为无效码，不区分"曾存在被删"。
            return giftState(view, GiftLandingView.STATE_INVALID_TOKEN, "INVALID_TOKEN");
        }

        view.setClaimExpiresAt(gift.getClaimExpiresAt());
        view.setRecipientBound(gift.isRecipientBound());
        int status = gift.getStatus() == null ? ReferralLandingGiftRow.STATUS_PENDING : gift.getStatus();

        if (status == ReferralLandingGiftRow.STATUS_CLAIMED) {
            view.setClaimedAt(gift.getClaimedAt());
            fillGiftProduct(view, gift.getProductId());
            return giftState(view, GiftLandingView.STATE_CLAIMED, "GIFT_ALREADY_CLAIMED");
        }
        if (status == ReferralLandingGiftRow.STATUS_REVOKED) {
            fillGiftProduct(view, gift.getProductId());
            return giftState(view, GiftLandingView.STATE_GIFT_REVOKED, "GIFT_REVOKED");
        }
        if (status == ReferralLandingGiftRow.STATUS_EXPIRED
                || (gift.getClaimExpiresAt() != null && !gift.getClaimExpiresAt().after(now))) {
            fillGiftProduct(view, gift.getProductId());
            return giftState(view, GiftLandingView.STATE_GIFT_EXPIRED, "GIFT_CLAIM_EXPIRED");
        }
        if (status != ReferralLandingGiftRow.STATUS_PENDING) {
            return giftState(view, GiftLandingView.STATE_INVALID_TOKEN, "GIFT_STATE_UNKNOWN");
        }
        if (!fillGiftProduct(view, gift.getProductId())) {
            return giftState(view, GiftLandingView.STATE_PRODUCT_UNAVAILABLE, "GIFT_PRODUCT_MISSING");
        }
        view.setNextAction(GiftLandingView.ACTION_CLAIM_GIFT);
        return giftState(view, GiftLandingView.STATE_VALID, null);
    }

    // ------------------------------------------------------------------ internals

    /**
     * 目标必须通过与公开目录/公开活动同一个投影闸才可出现。
     *
     * <p>不重写可见性判据：主题走 {@link IPublicContentCatalogService#byIds}（同一 SQL 公开闸：
     * 删除/审核/模板/会员专属/俱乐部/主圈闸），活动走
     * {@link ICmsActivityService#selectPublicCmsActivityById}（删除、审核、发布、俱乐部与父主题闸）。
     * 只回标题、稳定路由与目录里的 {@code saleState}/{@code freeSelfPlay} 事实，不推断可报名。
     */
    private boolean fillPublicTarget(ReferralLandingView view, MarketingReferralCredential credential) {
        Integer targetType = credential.getTargetType();
        Long targetId = credential.getTargetId();
        if (targetType == null || targetId == null) {
            return false;
        }
        if (targetType == MarketingReferralCredential.TARGET_TOPIC) {
            List<PublicContentCatalogItemVO> rows;
            try {
                rows = publicCatalogService.byIds(Collections.singletonList(targetId), null);
            } catch (ServiceException invalidTarget) {
                // 公开投影判定 targetId 不合规：一律按"不可见"处理，不在落地页抛 500。
                return false;
            }
            if (rows == null || rows.isEmpty()) {
                return false;
            }
            PublicContentCatalogItemVO row = rows.get(0);
            ReferralLandingView.Target target = new ReferralLandingView.Target();
            target.setType(targetType);
            target.setId(row.getId());
            target.setTitle(row.getName());
            target.setCanonicalPath(row.getCanonicalPath());
            view.setTarget(target);
            view.setSaleState(row.getSaleState());
            view.setFreeSelfPlay(row.getFreeSelfPlay());
            return true;
        }
        if (targetType == MarketingReferralCredential.TARGET_ACTIVITY) {
            CmsActivity activity = activityService.selectPublicCmsActivityById(targetId);
            if (activity == null || !targetId.equals(activity.getId())) {
                return false;
            }
            ReferralLandingView.Target target = new ReferralLandingView.Target();
            target.setType(targetType);
            target.setId(activity.getId());
            target.setTitle(activity.getName());
            view.setTarget(target);
            return true;
        }
        return false;
    }

    /** 只填公开商品字段；商品或条目缺失返回 false，不编造任何标题或有效期。 */
    private boolean fillGiftProduct(GiftLandingView view, Long productId) {
        MarketingProduct product = productMapper.selectById(productId);
        if (product == null) {
            return false;
        }
        GiftLandingView.Product out = new GiftLandingView.Product();
        out.setProductId(product.getId());
        out.setProductCode(product.getProductCode());
        out.setProductName(product.getProductName());
        out.setProductKind(product.getProductKind());
        out.setValidityKind(product.getValidityKind());
        out.setValidDays(product.getValidDays());
        out.setFixedValidFrom(product.getFixedValidFrom());
        out.setFixedValidTo(product.getFixedValidTo());
        out.setRevision(product.getRevision());
        List<MarketingProductItem> items = productMapper.selectItems(productId);
        if (items != null) {
            for (MarketingProductItem item : items) {
                GiftLandingView.Item viewItem = new GiftLandingView.Item();
                viewItem.setTopicId(item.getTopicId());
                viewItem.setTopicTitle(item.getTopicTitle());
                viewItem.setContentRevision(item.getContentRevision());
                out.getItems().add(viewItem);
            }
        }
        view.setProduct(out);
        return true;
    }

    private static ReferralLandingView referralState(ReferralLandingView view, String state, String reasonCode) {
        view.setState(state);
        view.setReasonCode(reasonCode);
        return view;
    }

    private static GiftLandingView giftState(GiftLandingView view, String state, String reasonCode) {
        view.setState(state);
        view.setReasonCode(reasonCode);
        return view;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
