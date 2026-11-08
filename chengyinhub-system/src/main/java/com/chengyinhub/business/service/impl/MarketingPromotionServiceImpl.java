package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;
import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingPromotionRedemption;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.dto.MarketingAdjustment;
import com.chengyinhub.business.domain.dto.MarketingEligibility;
import com.chengyinhub.business.domain.dto.MarketingExclusion;
import com.chengyinhub.business.domain.dto.MarketingPromotionFacts;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;
import com.chengyinhub.business.domain.vo.MarketingOfferCandidateView;
import com.chengyinhub.business.domain.vo.MarketingPromotionCandidateView;
import com.chengyinhub.business.exception.MarketingPromotionException;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.MarketingOfferMapper;
import com.chengyinhub.business.mapper.MarketingOrderAdjustmentMapper;
import com.chengyinhub.business.mapper.MarketingPromotionMapper;
import com.chengyinhub.business.mapper.MarketingPromotionRedemptionMapper;
import com.chengyinhub.business.mapper.MarketingTierMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.IMarketingPromotionService;
import com.chengyinhub.business.service.support.MarketingPromotionCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;

/**
 * 促销实现:M4 只做四件可验证的事 —— 算价、判资格、落快照、撤销。
 *
 * <p><b>资格的事实来源</b>:通关事实只从 {@code player_badge}
 * ({@code badge_code = TOPIC_FINISH_<topicId>})读。客户端点"完成"、本地时间、
 * 客户端上报的完成时间都不参与判定 —— 这也让"改手机时间"这类绕过在结构上不可能成立。
 *
 * <p><b>写边界</b>:本类只写 {@code marketing_order_adjustment} 与
 * {@code marketing_promotion_redemption};对 cms_registration 只读 id/member 关系,
 * 不改任何资金列、不发放权益、不发券、不动支付状态。
 *
 * <p><b>默认关闭</b>:所有配置来自 marketing_* 表,没有配置就没有任何优惠 ——
 * 代码合并本身不会改变线上任何一单的价格。
 *
 * <p><b>不接礼品</b>(2026-11-08 root 裁定):礼品独立售价由 M3 的礼品 SKU 唯一提供,
 * 本类不接收"送礼人/收礼人"参数,也不做溢价调整。
 */
@Service
public class MarketingPromotionServiceImpl implements IMarketingPromotionService {

    private static final Logger log = LoggerFactory.getLogger(MarketingPromotionServiceImpl.class);

    /** 通关勋章的编码前缀,与 {@code TopicCompletionAwardServiceImpl} 写入口一致。 */
    static final String COMPLETION_BADGE_PREFIX = "TOPIC_FINISH_";

    private static final String REQUEST_REQUIRED = "REQUEST_REQUIRED";
    private static final String LOGIN_REQUIRED = "LOGIN_REQUIRED";
    private static final String SCOPE_REQUIRED = "SCOPE_REQUIRED";

    private static final int MONEY_SCALE = 2;

    private final MarketingOfferMapper offerMapper;
    private final MarketingTierMapper tierMapper;
    private final MarketingPromotionMapper promotionMapper;
    private final MarketingPromotionRedemptionMapper redemptionMapper;
    private final MarketingOrderAdjustmentMapper adjustmentMapper;
    private final PlayerBadgeMapper playerBadgeMapper;
    private final CmsActivityMapper activityMapper;
    private final MarketingPromotionCalculator calculator = new MarketingPromotionCalculator();
    /** 与 SQL 的 {@code order by priority desc, id asc} 同口径;合并活动/主题候选后必须重排一次。 */
    private static final Comparator<MarketingPromotion> PROMOTION_ORDER = (left, right) -> {
        int leftPriority = left.getPriority() == null ? 0 : left.getPriority();
        int rightPriority = right.getPriority() == null ? 0 : right.getPriority();
        if (leftPriority != rightPriority) {
            return Integer.compare(rightPriority, leftPriority);
        }
        long leftId = left.getId() == null ? 0L : left.getId();
        long rightId = right.getId() == null ? 0L : right.getId();
        return Long.compare(leftId, rightId);
    };

    public MarketingPromotionServiceImpl(MarketingOfferMapper offerMapper,
                                         MarketingTierMapper tierMapper,
                                         MarketingPromotionMapper promotionMapper,
                                         MarketingPromotionRedemptionMapper redemptionMapper,
                                         MarketingOrderAdjustmentMapper adjustmentMapper,
                                         PlayerBadgeMapper playerBadgeMapper,
                                         CmsActivityMapper activityMapper) {
        this.offerMapper = offerMapper;
        this.tierMapper = tierMapper;
        this.promotionMapper = promotionMapper;
        this.redemptionMapper = redemptionMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.playerBadgeMapper = playerBadgeMapper;
        this.activityMapper = activityMapper;
    }

    // ------------------------------------------------------------------ 报价

    @Override
    public MarketingPromotionQuote quote(MarketingPromotionRequest request, Date now) {
        if (request == null) {
            throw MarketingPromotionException.badRequest(REQUEST_REQUIRED, "缺少促销报价参数");
        }
        Long memberId = request.getMemberId();
        if (memberId == null || memberId <= 0) {
            throw MarketingPromotionException.forbidden(LOGIN_REQUIRED, "请先登录");
        }
        Date at = now == null ? new Date() : now;
        Integer ownerType = request.getOwnerType();
        Long ownerId = request.getOwnerId();
        if (ownerType == null || ownerId == null || ownerId <= 0) {
            throw MarketingPromotionException.badRequest(SCOPE_REQUIRED, "缺少报价对象");
        }

        MarketingPromotionFacts facts = new MarketingPromotionFacts();
        if (request.getOfferCode() != null && !request.getOfferCode().trim().isEmpty()) {
            resolveOffer(request, facts, memberId, at);
        }
        List<MarketingTier> tiers = tierMapper.selectActiveByScope(ownerType, ownerId, at);
        facts.setTiers(tiers == null ? new ArrayList<>() : tiers);
        facts.setPromotions(activePromotions(ownerType, ownerId, at));
        Map<Long, MarketingEligibility> eligibility = new LinkedHashMap<>();
        for (MarketingPromotion promotion : facts.getPromotions()) {
            if (promotion == null || promotion.getId() == null) {
                continue;
            }
            eligibility.put(promotion.getId(), resolveEligibility(memberId, promotion, at));
        }
        facts.setEligibility(eligibility);
        return calculator.calculate(request, facts);
    }

    /** 校验所选组合包;任何一步不过都只"不生效 + 说明原因",不抛给用户一堆配置细节。 */
    private void resolveOffer(MarketingPromotionRequest request, MarketingPromotionFacts facts,
                              Long memberId, Date at) {
        String code = request.getOfferCode().trim();
        MarketingOffer offer = offerMapper.selectEnabledByCode(code);
        if (offer == null) {
            excludeOffer(facts, MarketingExclusion.OFFER_NOT_FOUND, "优惠不存在或已停用");
            return;
        }
        if (!Integer.valueOf(MarketingOffer.STATUS_ENABLED).equals(offer.getStatus())) {
            excludeOffer(facts, MarketingExclusion.OFFER_DISABLED, "优惠已停用");
            return;
        }
        if (!sameScope(offer.getOwnerType(), offer.getOwnerId(), request.getOwnerType(), request.getOwnerId())) {
            excludeOffer(facts, MarketingExclusion.OFFER_SCOPE_MISMATCH, "优惠不属于当前购买对象");
            return;
        }
        if (!inWindow(offer.getValidFrom(), offer.getValidTo(), at)) {
            excludeOffer(facts, MarketingExclusion.OFFER_WINDOW_CLOSED, "优惠不在有效期内");
            return;
        }
        Integer maxPerMember = offer.getMaxPerMember();
        if (maxPerMember != null && maxPerMember > 0) {
            int used = adjustmentMapper.countByOfferAndMember(offer.getId(), memberId);
            if (used >= maxPerMember) {
                excludeOffer(facts, MarketingExclusion.OFFER_MEMBER_LIMIT, "已达该优惠的购买次数上限");
                return;
            }
        }
        List<MarketingOfferItem> items = offerMapper.selectItemsByOfferId(offer.getId());
        if (items == null || items.isEmpty()) {
            excludeOffer(facts, MarketingExclusion.OFFER_ITEMS_MISMATCH, "优惠未配置成员");
            return;
        }
        if (!itemsMatch(items, request.getLines())) {
            excludeOffer(facts, MarketingExclusion.OFFER_ITEMS_MISMATCH, "所选优惠与当前购票明细不一致");
            return;
        }
        facts.setOffer(offer);
    }

    private void excludeOffer(MarketingPromotionFacts facts, String reason, String detail) {
        facts.setOffer(null);
        facts.setOfferExclusionReason(reason);
        facts.setOfferExclusionDetail(detail);
    }

    /** 组合包必须与购物车逐项一致:票种集合相同、每种张数相同。 */
    private boolean itemsMatch(List<MarketingOfferItem> items, List<MarketingPromotionRequest.Line> lines) {
        Map<Long, Integer> requested = new LinkedHashMap<>();
        if (lines != null) {
            for (MarketingPromotionRequest.Line line : lines) {
                if (line == null || line.getTicketId() == null) {
                    return false;
                }
                int quantity = line.getQuantity() == null || line.getQuantity() <= 0 ? 1 : line.getQuantity();
                requested.merge(line.getTicketId(), quantity, Integer::sum);
            }
        }
        Map<Long, Integer> configured = new LinkedHashMap<>();
        for (MarketingOfferItem item : items) {
            if (item == null || item.getItemId() == null || item.getItemType() == null
                    || item.getItemType() != MarketingOfferItem.ITEM_TICKET) {
                return false;
            }
            int quantity = item.getQuantity() == null || item.getQuantity() <= 0 ? 1 : item.getQuantity();
            configured.merge(item.getItemId(), quantity, Integer::sum);
        }
        return requested.equals(configured);
    }

    private boolean sameScope(Integer leftType, Long leftId, Integer rightType, Long rightId) {
        return leftType != null && leftType.equals(rightType)
                && leftId != null && leftId.equals(rightId);
    }

    private boolean inWindow(Date from, Date to, Date at) {
        if (from != null && at.before(from)) {
            return false;
        }
        return to == null || !at.after(to);
    }

    // ------------------------------------------------------------------ 资格

    /** 触发式优惠的资格判定。事实与窗口都由服务端算,{@code at} 只来自服务端时钟。 */
    private MarketingEligibility resolveEligibility(Long memberId, MarketingPromotion promotion, Date at) {
        if (!Integer.valueOf(MarketingPromotion.STATUS_ENABLED).equals(promotion.getStatus())) {
            return MarketingEligibility.denied(MarketingExclusion.PROMO_DISABLED);
        }
        if (!inWindow(promotion.getValidFrom(), promotion.getValidTo(), at)) {
            return MarketingEligibility.denied(MarketingExclusion.PROMO_WINDOW_CLOSED);
        }
        if (!supportedDiscount(promotion)) {
            return MarketingEligibility.denied(MarketingExclusion.PROMO_TYPE_UNSUPPORTED);
        }
        Integer windowHours = promotion.getWindowHours();
        if (windowHours == null || windowHours <= 0) {
            return MarketingEligibility.denied(MarketingExclusion.PROMO_WINDOW_UNCONFIGURED);
        }
        PlayerBadge fact = findTriggerFact(memberId, promotion);
        if (fact == null || fact.getId() == null || fact.getObtainTime() == null) {
            return MarketingEligibility.denied(MarketingExclusion.TRIGGER_FACT_MISSING);
        }
        Date windowEndsAt = new Date(fact.getObtainTime().getTime() + windowHours * 3_600_000L);
        if (at.after(windowEndsAt)) {
            return MarketingEligibility.denied(MarketingExclusion.TRIGGER_WINDOW_EXPIRED);
        }
        if (redemptionMapper.countByFact(promotion.getId(), memberId, fact.getId()) > 0) {
            return MarketingEligibility.denied(MarketingExclusion.ALREADY_USED);
        }
        Integer maxUses = promotion.getMaxUsesPerMember();
        if (maxUses != null && maxUses > 0
                && redemptionMapper.countByMember(promotion.getId(), memberId) >= maxUses) {
            return MarketingEligibility.denied(MarketingExclusion.MEMBER_LIMIT_REACHED);
        }
        return MarketingEligibility.allowed(MarketingPromotionRedemption.SOURCE_FACT_BADGE,
                fact.getId(), fact.getObtainTime(), windowEndsAt);
    }

    private boolean supportedDiscount(MarketingPromotion promotion) {
        Integer type = promotion.getDiscountType();
        BigDecimal value = promotion.getDiscountValue();
        if (type == null || value == null) {
            return false;
        }
        if (type == MarketingPromotion.DISCOUNT_FIXED_AMOUNT) {
            return value.signum() > 0;
        }
        if (type == MarketingPromotion.DISCOUNT_RATE) {
            return value.signum() > 0 && value.compareTo(BigDecimal.ONE) <= 0;
        }
        return false;
    }

    /**
     * 取触发事实。
     *
     * <p>完局限时(trigger=1)必须是"哪个主题通完了":所以要求配置落在具体主题上,
     * 全局配一条"任何通关都算"的规则会让资格来源无法解释,直接判不支持。
     */
    private PlayerBadge findTriggerFact(Long memberId, MarketingPromotion promotion) {
        Integer trigger = promotion.getTriggerKind();
        if (trigger == null) {
            return null;
        }
        if (trigger == MarketingPromotion.TRIGGER_TOPIC_COMPLETION) {
            if (!Integer.valueOf(MarketingPromotion.SCOPE_TOPIC).equals(promotion.getScopeType())
                    || promotion.getScopeId() == null || promotion.getScopeId() <= 0) {
                return null;
            }
            return playerBadgeMapper.selectOne(memberId,
                    COMPLETION_BADGE_PREFIX + promotion.getScopeId(), promotion.getScopeId());
        }
        if (trigger == MarketingPromotion.TRIGGER_FIRST_QUEST) {
            List<PlayerBadge> badges = playerBadgeMapper.selectListByMember(memberId);
            if (badges == null || badges.isEmpty()) {
                return null;
            }
            PlayerBadge earliest = null;
            for (PlayerBadge badge : badges) {
                if (badge == null || badge.getBadgeCode() == null
                        || !badge.getBadgeCode().startsWith(COMPLETION_BADGE_PREFIX)
                        || badge.getObtainTime() == null) {
                    continue;
                }
                if (earliest == null || badge.getObtainTime().before(earliest.getObtainTime())) {
                    earliest = badge;
                }
            }
            return earliest;
        }
        return null;
    }

    /**
     * 本报价的候选促销。
     *
     * <p><b>为什么活动单要并入主题级促销</b>:完局限时(trigger=1)按「哪个主题通完了」判定资格,
     * 配置期强制绑定主题(见 {@code PROMO_SCOPE_REQUIRED});而真实售票路径的报价对象是
     * {@code owner_type=2 的活动}({@code cms_registration.owner_type=2}),通关事实却记在活动所属的主题上。
     * 只按活动 scope 查,主题级配置永远不会进入候选 —— 机制等于没接线。这里只跨越
     * 「活动 → cms_activity.topic_id」这一条既有关系,不猜、不跨主题。
     *
     * <p>并集按 id 去重(全局促销在两次查询里都会出现),再按与 SQL 相同的
     * {@code priority desc, id asc} 重排一次,叠加顺序才与单一路径一致。
     */
    private List<MarketingPromotion> activePromotions(Integer ownerType, Long ownerId, Date at) {
        List<MarketingPromotion> merged = new ArrayList<>();
        List<MarketingPromotion> direct = promotionMapper.selectActiveByScope(ownerType, ownerId, at);
        if (direct != null) {
            merged.addAll(direct);
        }
        Long topicId = activityTopicId(ownerType, ownerId);
        if (topicId != null && topicId > 0) {
            List<MarketingPromotion> topicWide = promotionMapper.selectActiveByScope(
                    MarketingPromotion.SCOPE_TOPIC, topicId, at);
            if (topicWide != null) {
                Set<Long> seen = new LinkedHashSet<>();
                for (MarketingPromotion promotion : merged) {
                    if (promotion != null && promotion.getId() != null) {
                        seen.add(promotion.getId());
                    }
                }
                for (MarketingPromotion promotion : topicWide) {
                    if (promotion != null && promotion.getId() != null && seen.add(promotion.getId())) {
                        merged.add(promotion);
                    }
                }
            }
        }
        merged.sort(PROMOTION_ORDER);
        return merged;
    }

    /** 活动所属主题;非活动报价、活动不存在或未挂主题时返回 null。 */
    private Long activityTopicId(Integer ownerType, Long ownerId) {
        if (!Integer.valueOf(MarketingPromotion.SCOPE_ACTIVITY).equals(ownerType)
                || ownerId == null || ownerId <= 0) {
            return null;
        }
        CmsActivity activity = activityMapper.selectCmsActivityById(ownerId);
        return activity == null ? null : activity.getTopicId();
    }

    // ------------------------------------------------------------------ 投影

    @Override
    public List<MarketingOfferCandidateView> listOffers(Long memberId, Integer ownerType, Long ownerId, Date now) {
        Date at = now == null ? new Date() : now;
        List<MarketingOfferCandidateView> views = new ArrayList<>();
        if (ownerType == null || ownerId == null || ownerId <= 0) {
            return views;
        }
        List<MarketingOffer> offers = offerMapper.selectActiveByScope(ownerType, ownerId, at);
        if (offers == null) {
            return views;
        }
        for (MarketingOffer offer : offers) {
            if (offer == null) {
                continue;
            }
            MarketingOfferCandidateView view = new MarketingOfferCandidateView();
            view.setOfferCode(offer.getOfferCode());
            view.setOfferKind(offer.getOfferKind());
            view.setName(offer.getName());
            view.setDescription(offer.getDescription());
            view.setPriceAmount(money(offer.getPriceAmount()));
            view.setListAmount(money(offer.getListAmount()));
            view.setDifferenceAmount(money(money(offer.getListAmount()).subtract(money(offer.getPriceAmount()))));
            view.setValidFrom(offer.getValidFrom());
            view.setValidTo(offer.getValidTo());
            view.setMaxPerMember(offer.getMaxPerMember());
            view.setRuleVersion(offer.getRuleVersion());
            Integer maxPerMember = offer.getMaxPerMember();
            if (memberId != null && memberId > 0 && maxPerMember != null && maxPerMember > 0
                    && adjustmentMapper.countByOfferAndMember(offer.getId(), memberId) >= maxPerMember) {
                view.setAvailable(false);
                view.setReasonCode(MarketingExclusion.OFFER_MEMBER_LIMIT);
            } else {
                view.setAvailable(true);
            }
            List<MarketingOfferItem> items = offerMapper.selectItemsByOfferId(offer.getId());
            view.setItems(toItemViews(items));
            views.add(view);
        }
        return views;
    }

    private List<MarketingOfferCandidateView.Item> toItemViews(List<MarketingOfferItem> items) {
        List<MarketingOfferCandidateView.Item> views = new ArrayList<>();
        if (items == null) {
            return views;
        }
        for (MarketingOfferItem item : items) {
            if (item == null) {
                continue;
            }
            MarketingOfferCandidateView.Item view = new MarketingOfferCandidateView.Item();
            view.setTicketId(item.getItemId());
            view.setQuantity(item.getQuantity());
            view.setUnitListAmount(money(item.getUnitListAmount()));
            views.add(view);
        }
        return views;
    }

    @Override
    public List<MarketingPromotionCandidateView> listPromotions(Long memberId, Integer ownerType, Long ownerId,
                                                                Date now) {
        Date at = now == null ? new Date() : now;
        List<MarketingPromotionCandidateView> views = new ArrayList<>();
        if (ownerType == null || ownerId == null || ownerId <= 0) {
            return views;
        }
        List<MarketingPromotion> promotions = activePromotions(ownerType, ownerId, at);
        for (MarketingPromotion promotion : promotions) {
            if (promotion == null || promotion.getId() == null) {
                continue;
            }
            MarketingEligibility eligibility = memberId == null || memberId <= 0
                    ? MarketingEligibility.denied(LOGIN_REQUIRED)
                    : resolveEligibility(memberId, promotion, at);
            MarketingPromotionCandidateView view = new MarketingPromotionCandidateView();
            view.setPromoCode(promotion.getPromoCode());
            view.setName(promotion.getName());
            view.setDescription(promotion.getDescription());
            view.setTriggerKind(promotion.getTriggerKind());
            view.setDiscountType(promotion.getDiscountType());
            view.setDiscountValue(promotion.getDiscountValue());
            view.setMaxDiscountAmount(promotion.getMaxDiscountAmount());
            view.setMinPayableAmount(promotion.getMinPayableAmount());
            view.setWindowHours(promotion.getWindowHours());
            view.setStackGroup(promotion.getStackGroup());
            view.setExclusive(Integer.valueOf(1).equals(promotion.getExclusiveFlag()));
            view.setPriority(promotion.getPriority());
            view.setEligible(eligibility.isEligible());
            view.setReasonCode(eligibility.getReasonCode());
            view.setSourceFactTime(eligibility.getSourceFactTime());
            view.setWindowEndsAt(eligibility.getWindowEndsAt());
            views.add(view);
        }
        return views;
    }

    @Override
    public List<MarketingTier> listTiers(Integer ownerType, Long ownerId, Date now) {
        if (ownerType == null || ownerId == null || ownerId <= 0) {
            return new ArrayList<>();
        }
        List<MarketingTier> tiers = tierMapper.selectActiveByScope(ownerType, ownerId, now == null ? new Date() : now);
        return tiers == null ? new ArrayList<>() : tiers;
    }

    // ------------------------------------------------------------------ 成交快照 / 撤销

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void commitApplied(MarketingPromotionQuote quote, Integer orderType, Long orderId, Date now) {
        if (quote == null || (quote.getOfferId() == null && quote.getAdjustments().isEmpty())) {
            return;
        }
        if (orderType == null || orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("促销快照缺少订单标识");
        }
        // 重放:同一 (orderType, orderId) 已有成交快照 ⇒ 这单已经消耗过这份优惠,直接返回。
        // 早于任何锁/计数/insert:重放既不能再占一次 max_per_member,也不该因此报错。
        if (adjustmentMapper.selectByOrder(orderType, orderId) != null) {
            log.info("marketing promotion snapshot already committed, replay ignored orderType={} orderId={}",
                    orderType, orderId);
            return;
        }
        // 组合包限次闸:报价侧只展示、不持锁;真正的 max_per_member 在这里(建单事务内)
        // 锁住同一 offer 行后重新读使用数再写使用事实,把 count→insert 串行化。
        requireOfferQuotaUnderLock(quote);
        Date at = now == null ? new Date() : now;
        MarketingOrderAdjustment row = new MarketingOrderAdjustment();
        row.setOrderType(orderType);
        row.setOrderId(orderId);
        row.setMemberId(quote.getMemberId());
        row.setOwnerType(quote.getOwnerType());
        row.setOwnerId(quote.getOwnerId());
        row.setListAmount(money(quote.getListAmount()));
        row.setTierDiscountAmount(money(quote.getTierDiscountAmount()));
        row.setOfferDiscountAmount(money(quote.getOfferDiscountAmount()));
        row.setPromotionDiscountAmount(money(quote.getPromotionDiscountAmount()));
        row.setPayableAmount(money(quote.getPayableAmount()));
        row.setOfferId(quote.getOfferId());
        row.setOfferCode(quote.getOfferCode());
        row.setAppliedJson(appliedJson(quote));
        row.setRuleVersion(quote.getRuleVersion());
        row.setQuoteFingerprint(quote.getMarketingSign());
        row.setCreateTime(at);
        adjustmentMapper.insertAdjustment(row);

        for (MarketingAdjustment adjustment : quote.getAdjustments()) {
            if (adjustment == null || adjustment.getPromotionId() == null) {
                continue;
            }
            // 没有触发事实就没有一次性语义:宁可在建单事务里失败,也不写一条无法去重的使用事实。
            if (adjustment.getSourceFactId() == null) {
                throw new IllegalStateException("促销使用事实缺少触发事实 promotionId="
                        + adjustment.getPromotionId());
            }
            MarketingPromotionRedemption redemption = new MarketingPromotionRedemption();
            redemption.setPromotionId(adjustment.getPromotionId());
            redemption.setPromoCode(adjustment.getCode());
            redemption.setMemberId(quote.getMemberId());
            redemption.setSourceFactType(adjustment.getSourceFactType());
            redemption.setSourceFactId(adjustment.getSourceFactId());
            redemption.setOrderType(orderType);
            redemption.setOrderId(orderId);
            redemption.setDiscountAmount(money(adjustment.getAmount()));
            redemption.setRuleVersion(adjustment.getRuleVersion());
            redemption.setStatus(MarketingPromotionRedemption.STATUS_USED);
            redemption.setIdempotencyKey("promo:" + adjustment.getPromotionId() + ":" + orderType + ":" + orderId);
            redemption.setCreateTime(at);
            redemption.setUpdateTime(at);
            redemptionMapper.insertRedemption(redemption);
        }
        log.info("marketing promotion snapshot committed orderType={} orderId={} list={} payable={} sign={}",
                orderType, orderId, row.getListAmount(), row.getPayableAmount(), row.getQuoteFingerprint());
    }

    /**
     * 组合包 {@code max_per_member} 的建单期闸(在调用方事务内执行)。
     *
     * <p><b>为什么不能只靠报价侧</b>:{@link #resolveOffer} 的 {@code countByOfferAndMember}
     * 是普通读,读到 0 到真正 insert 使用事实之间有窗口。同一会员并发两笔首购会双双读到 0,
     * 各自用一个次数 —— 这是配置项本身的硬限制被绕过,不是"可选优化"。
     *
     * <p><b>做法</b>:锁住同一 {@code marketing_offer} 行(现有行级锁),锁后重新读计数,
     * 超限即拒绝,再由调用方继续 insert 调整事实与使用事实。锁随调用方事务提交释放,
     * 只影响"同一组合包的并发建单",不新增计数表/Redis/锁框架,报价路径不加锁。
     *
     * <p>没有 offer 的报价(纯阶梯/触发式优惠)不进这里 —— 那些没有 {@code max_per_member} 概念。
     */
    private void requireOfferQuotaUnderLock(MarketingPromotionQuote quote) {
        Long offerId = quote.getOfferId();
        if (offerId == null) {
            return;
        }
        MarketingOffer locked = offerMapper.selectOfferByIdForUpdate(offerId);
        if (locked == null || (locked.getDelFlag() != null && locked.getDelFlag() != 0)) {
            throw MarketingPromotionException.conflict(MarketingExclusion.OFFER_NOT_FOUND,
                    "该优惠已失效,请重新报价后再提交");
        }
        Integer maxPerMember = locked.getMaxPerMember();
        if (maxPerMember == null || maxPerMember <= 0) {
            return;
        }
        // 当前读:普通 count 在 REPEATABLE READ 下会沿用事务早先的快照,看不到并发那笔刚提交的
        // 使用事实 —— 那就等于没锁。命中 idx_adjustment_offer(offer_id, member_id)。
        int used = adjustmentMapper.countByOfferAndMemberForUpdate(offerId, quote.getMemberId());
        if (used >= maxPerMember) {
            throw MarketingPromotionException.conflict(MarketingExclusion.OFFER_MEMBER_LIMIT,
                    "该优惠的购买次数已达上限,请重新报价后选择其它优惠");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeForOrder(Integer orderType, Long orderId, Date now) {
        if (orderType == null || orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("促销撤销缺少订单标识");
        }
        redemptionMapper.revokeByOrder(orderType, orderId,
                MarketingPromotionRedemption.STATUS_REVOKED_BY_REFUND, now == null ? new Date() : now);
    }

    @Override
    public MarketingOrderAdjustment readOrderAdjustment(Integer orderType, Long orderId) {
        if (orderType == null || orderId == null) {
            return null;
        }
        return adjustmentMapper.selectByOrder(orderType, orderId);
    }

    private String appliedJson(MarketingPromotionQuote quote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", quote.getLines());
        body.put("adjustments", quote.getAdjustments());
        body.put("exclusions", quote.getExclusions());
        body.put("offerCode", quote.getOfferCode());
        body.put("offerKind", quote.getOfferKind());
        body.put("listAmount", money(quote.getListAmount()));
        body.put("payableAmount", money(quote.getPayableAmount()));
        body.put("ruleVersion", quote.getRuleVersion());
        body.put("marketingSign", quote.getMarketingSign());
        return JSON.toJSONString(body);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
