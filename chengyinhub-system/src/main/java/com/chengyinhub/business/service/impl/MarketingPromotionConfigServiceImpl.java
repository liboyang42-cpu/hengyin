package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.exception.MarketingPromotionException;
import com.chengyinhub.business.mapper.MarketingOfferMapper;
import com.chengyinhub.business.mapper.MarketingPromotionMapper;
import com.chengyinhub.business.mapper.MarketingTierMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IMarketingPromotionConfigService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 促销配置实现。
 *
 * <p><b>为什么每次写都重算 listAmount / ruleVersion</b>:
 * <ul>
 *   <li>{@code list_amount} 由成员现价算出,不接受客户端自报 —— 它是"打包价不高于分买"的唯一判据。</li>
 *   <li>{@code rule_version} 只由服务端递增。报价签名与订单快照都带它,
 *       若信任入参,任何人改一次配置就能让旧报价继续有效。</li>
 * </ul>
 *
 * <p><b>跨主体套利闸</b>:组合包成员必须属于本供给的同一 owner —— 否则可以把 A 商家的
 * 便宜票与 B 商家的贵票打包成一个"超值包",由框架替两家结算差额。
 */
@Service
public class MarketingPromotionConfigServiceImpl implements IMarketingPromotionConfigService {

    private static final int MONEY_SCALE = 2;

    private final MarketingOfferMapper offerMapper;
    private final MarketingTierMapper tierMapper;
    private final MarketingPromotionMapper promotionMapper;
    private final OmsTicketMapper omsTicketMapper;
    private final ICmsActivityService activityService;
    private final ITopicOperationsScopeAuthorizer scopeAuthorizer;

    public MarketingPromotionConfigServiceImpl(MarketingOfferMapper offerMapper,
                                               MarketingTierMapper tierMapper,
                                               MarketingPromotionMapper promotionMapper,
                                               OmsTicketMapper omsTicketMapper,
                                               ICmsActivityService activityService,
                                               ITopicOperationsScopeAuthorizer scopeAuthorizer) {
        this.offerMapper = offerMapper;
        this.tierMapper = tierMapper;
        this.promotionMapper = promotionMapper;
        this.omsTicketMapper = omsTicketMapper;
        this.activityService = activityService;
        this.scopeAuthorizer = scopeAuthorizer;
    }

    // ------------------------------------------------------------------ 对象级范围

    /**
     * 菜单权限之外的对象归属校验:主题看主题写权限,活动看其所属主题,两者都无则仅全局账号可写。
     */
    private void requireScope(Integer ownerType, Long ownerId) {
        if (ownerType == null || ownerId == null || ownerId <= 0) {
            throw MarketingPromotionException.badRequest("SCOPE_REQUIRED", "缺少适用范围");
        }
        if (ownerType == 1) {
            if (!scopeAuthorizer.canEditTopic(ownerId)) {
                throw MarketingPromotionException.forbidden("SCOPE_FORBIDDEN", "无权管理该主题的促销配置");
            }
            return;
        }
        if (ownerType == 2) {
            CmsActivity activity = activityService.selectCmsActivityById(ownerId);
            if (activity == null) {
                throw MarketingPromotionException.notFound("ACTIVITY_NOT_FOUND", "活动不存在");
            }
            Long topicId = activity.getTopicId();
            if (topicId != null && topicId > 0 && scopeAuthorizer.canEditTopic(topicId)) {
                return;
            }
            if (scopeAuthorizer.hasGlobalTopicScope()) {
                return;
            }
            throw MarketingPromotionException.forbidden("SCOPE_FORBIDDEN", "无权管理该活动的促销配置");
        }
        throw MarketingPromotionException.badRequest("SCOPE_UNSUPPORTED", "不支持的适用范围");
    }

    private OmsTicket requireTicket(Long ticketId, Integer ownerType, Long ownerId) {
        if (ticketId == null || ownerType == null || ownerId == null) {
            throw MarketingPromotionException.badRequest("TICKET_REQUIRED", "缺少票种或适用范围");
        }
        OmsTicket ticket = omsTicketMapper.selectOmsTicketById(ticketId);
        if (ticket == null || (ticket.getDelFlag() != null && ticket.getDelFlag() != 0)) {
            throw MarketingPromotionException.badRequest("TICKET_NOT_FOUND", "票种不存在");
        }
        boolean sameOwner = ticket.getOwnerType() != null
                && ticket.getOwnerType().longValue() == ownerType.longValue()
                && ticket.getOwnerId() != null && ticket.getOwnerId().equals(ownerId);
        if (!sameOwner) {
            throw MarketingPromotionException.badRequest("TICKET_SCOPE_MISMATCH", "票种不属于该适用范围");
        }
        return ticket;
    }

    private void requireWindow(Date from, Date to) {
        if (from != null && to != null && !to.after(from)) {
            throw MarketingPromotionException.badRequest("WINDOW_INVALID", "结束时间必须晚于开始时间");
        }
    }

    // ------------------------------------------------------------------ 组合包 / 礼品

    @Override
    public List<MarketingOffer> listOffers(MarketingOffer query) {
        return offerMapper.selectOfferList(query == null ? new MarketingOffer() : query);
    }

    @Override
    public MarketingOffer getOffer(Long id) {
        if (id == null) {
            return null;
        }
        MarketingOffer offer = offerMapper.selectOfferById(id);
        if (offer != null) {
            offer.setItems(offerMapper.selectItemsByOfferId(id));
        }
        return offer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createOffer(MarketingOffer offer, List<MarketingOfferItem> items) {
        if (offer == null) {
            throw MarketingPromotionException.badRequest("OFFER_REQUIRED", "缺少优惠配置");
        }
        requireScope(offer.getOwnerType(), offer.getOwnerId());
        normalizeOffer(offer);
        BigDecimal listAmount = validateItems(offer, items);
        // 唯一性按"含已软删"的全部供给判:库上有唯一键,复用旧码会在 insert 时直接 1062。
        if (offerMapper.countByCode(offer.getOfferCode()) > 0) {
            throw MarketingPromotionException.conflict("OFFER_CODE_TAKEN", "优惠码已存在");
        }
        offer.setListAmount(listAmount);
        offer.setStatus(offer.getStatus() == null ? MarketingOffer.STATUS_DISABLED : offer.getStatus());
        offer.setDelFlag(0);
        offer.setRuleVersion(1);
        Date now = new Date();
        offer.setCreateTime(now);
        offer.setUpdateTime(now);
        int rows = offerMapper.insertOffer(offer);
        if (rows == 1) {
            for (MarketingOfferItem item : items) {
                item.setOfferId(offer.getId());
                item.setCreateTime(now);
                offerMapper.insertItem(item);
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateOffer(MarketingOffer offer, List<MarketingOfferItem> items) {
        if (offer == null || offer.getId() == null) {
            throw MarketingPromotionException.badRequest("OFFER_REQUIRED", "缺少优惠配置");
        }
        MarketingOffer existing = offerMapper.selectOfferById(offer.getId());
        if (existing == null) {
            throw MarketingPromotionException.notFound("OFFER_NOT_FOUND", "优惠不存在");
        }
        requireScope(existing.getOwnerType(), existing.getOwnerId());
        // 身份与归属不可改:换归属等于把 A 的供给挂到 B 名下。
        offer.setOfferCode(existing.getOfferCode());
        offer.setOwnerType(existing.getOwnerType());
        offer.setOwnerId(existing.getOwnerId());
        normalizeOffer(offer);
        BigDecimal listAmount = validateItems(offer, items);
        offer.setListAmount(listAmount);
        offer.setRuleVersion(nextVersion(existing.getRuleVersion()));
        offer.setUpdateTime(new Date());
        int rows = offerMapper.updateOffer(offer);
        if (rows == 1) {
            offerMapper.deleteItemsByOfferId(offer.getId());
            Date now = new Date();
            for (MarketingOfferItem item : items) {
                item.setOfferId(offer.getId());
                item.setCreateTime(now);
                offerMapper.insertItem(item);
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteOffers(Long[] ids) {
        if (ids == null || ids.length == 0) {
            throw MarketingPromotionException.badRequest("IDS_REQUIRED", "缺少要删除的优惠");
        }
        for (Long id : ids) {
            MarketingOffer existing = id == null ? null : offerMapper.selectOfferById(id);
            if (existing == null) {
                continue;
            }
            requireScope(existing.getOwnerType(), existing.getOwnerId());
        }
        return offerMapper.deleteOfferByIds(ids);
    }

    private void normalizeOffer(MarketingOffer offer) {
        if (offer.getOfferCode() == null || offer.getOfferCode().trim().isEmpty()) {
            throw MarketingPromotionException.badRequest("OFFER_CODE_REQUIRED", "优惠码不能为空");
        }
        offer.setOfferCode(offer.getOfferCode().trim());
        if (offer.getOfferCode().length() > 40) {
            throw MarketingPromotionException.badRequest("OFFER_CODE_TOO_LONG", "优惠码过长");
        }
        // 只有组合包一种:礼品独立售价由 M3 的礼品 SKU 承接(root 2026-11-08 裁定)。
        if (offer.getOfferKind() == null || offer.getOfferKind() != MarketingOffer.KIND_COMBO) {
            throw MarketingPromotionException.badRequest("OFFER_KIND_INVALID", "优惠类型不合法");
        }
        if (offer.getName() == null || offer.getName().trim().isEmpty()) {
            throw MarketingPromotionException.badRequest("OFFER_NAME_REQUIRED", "优惠名称不能为空");
        }
        offer.setName(offer.getName().trim());
        if (offer.getPriceAmount() == null || offer.getPriceAmount().signum() < 0) {
            throw MarketingPromotionException.badRequest("OFFER_PRICE_INVALID", "优惠价不合法");
        }
        offer.setPriceAmount(money(offer.getPriceAmount()));
        if (offer.getMaxPerMember() == null || offer.getMaxPerMember() <= 0) {
            offer.setMaxPerMember(1);
        }
        if (offer.getStatus() == null) {
            offer.setStatus(MarketingOffer.STATUS_DISABLED);
        } else if (offer.getStatus() != MarketingOffer.STATUS_DISABLED
                && offer.getStatus() != MarketingOffer.STATUS_ENABLED
                && offer.getStatus() != MarketingOffer.STATUS_RETIRED) {
            throw MarketingPromotionException.badRequest("OFFER_STATUS_INVALID", "优惠状态不合法");
        }
        requireWindow(offer.getValidFrom(), offer.getValidTo());
    }

    /** 校验成员并算出分买合计;返回 server 计算值,不采信入参里的 listAmount。 */
    private BigDecimal validateItems(MarketingOffer offer, List<MarketingOfferItem> items) {
        if (items == null || items.isEmpty()) {
            throw MarketingPromotionException.badRequest("OFFER_ITEMS_REQUIRED", "优惠必须包含至少一张票");
        }

        Set<Long> seen = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (MarketingOfferItem item : items) {
            if (item == null) {
                throw MarketingPromotionException.badRequest("OFFER_ITEMS_REQUIRED", "优惠成员不能为空");
            }
            if (item.getItemType() == null) {
                item.setItemType(MarketingOfferItem.ITEM_TICKET);
            }
            if (item.getItemType() != MarketingOfferItem.ITEM_TICKET) {
                throw MarketingPromotionException.badRequest("OFFER_ITEM_TYPE_INVALID", "优惠成员类型不合法");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                item.setQuantity(1);
            }

            requireTicket(item.getItemId(), offer.getOwnerType(), offer.getOwnerId());
            if (!seen.add(item.getItemId())) {
                throw MarketingPromotionException.badRequest("OFFER_ITEM_DUPLICATED", "同一票种只能出现一次");
            }
            if (item.getUnitListAmount() == null || item.getUnitListAmount().signum() < 0) {
                throw MarketingPromotionException.badRequest("OFFER_ITEM_PRICE_INVALID", "成员标价不合法");
            }
            item.setUnitListAmount(money(item.getUnitListAmount()));
            total = total.add(item.getUnitListAmount().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        BigDecimal listAmount = money(total);
        if (offer.getPriceAmount().compareTo(listAmount) > 0) {
            throw MarketingPromotionException.badRequest("OFFER_PRICE_ABOVE_LIST",
                    "组合包价不能高于成员分买合计");
        }
        if (listAmount.signum() == 0) {
            throw MarketingPromotionException.badRequest("OFFER_LIST_ZERO", "成员分买合计为 0,无法构成组合包");
        }
        return listAmount;
    }

    // ------------------------------------------------------------------ 阶梯价

    @Override
    public List<MarketingTier> listTiers(MarketingTier query) {
        return tierMapper.selectTierList(query == null ? new MarketingTier() : query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createTier(MarketingTier tier) {
        if (tier == null) {
            throw MarketingPromotionException.badRequest("TIER_REQUIRED", "缺少阶梯配置");
        }
        requireScope(tier.getOwnerType(), tier.getOwnerId());
        normalizeTier(tier);
        assertNoTierOverlap(tier, null);
        Date now = new Date();
        // 软删后按同配置重建:uk_tier_range 不含 del_flag,唯一键上仍压着那条已删行,
        // 直接 insert 会 1062。沿既有 softdelete 惯例复活那一行,不新建第二行、不改唯一键。
        MarketingTier deleted = tierMapper.selectTierByNaturalKeyIncludingDeleted(
                tier.getOwnerType(), tier.getOwnerId(), tier.getTicketId(), tier.getMinQuantity());
        if (deleted != null) {
            return reviveSoftDeletedTier(tier, deleted, now);
        }
        tier.setDelFlag(0);
        tier.setRuleVersion(1);
        tier.setCreateTime(now);
        tier.setUpdateTime(now);
        return tierMapper.insertTier(tier);
    }

    /**
     * 复活一条已软删的阶梯行(业务键完全相同的那条)。
     *
     * <p><b>为什么复活而不是 insert</b>{@code uk_tier_range(owner_type, owner_id, ticket_id, min_quantity)}
     * 不含 {@code del_flag}:软删只把 {@code del_flag} 置 2,行仍在唯一键上。改唯一键等于改删除策略,
     * 所以按既有惯例(见 {@code ClubPostMapper.reactivateRepostById}"一个业务键一行")复活原行。
     *
     * <p><b>归属按旧行再校验一次</b>:复活的必须是调用者有权管理的那个 owner;这里用旧行的
     * owner 重跑 {@link #requireScope},与 {@link #updateTier} 同口径 —— 不因为"业务键里带了 owner"
     * 就跳过对象级权限。
     *
     * <p><b>规则版本继续递增</b>,不重置为 1:已发出的报价/快照靠 rule_version 区分"改版前成交"
     * 与"改版后报价",重置会让新配置和旧快照撞上同一个版本号。
     *
     * <p><b>并发</b>:{@code reactivateTier} 的 {@code where id = ? and del_flag = 2} 只允许一个请求命中;
     * 另一个拿到 0 行时按区间冲突拒绝,不会出现两行同区间。
     */
    private int reviveSoftDeletedTier(MarketingTier tier, MarketingTier deleted, Date now) {
        if (deleted.getId() == null) {
            throw MarketingPromotionException.conflict("TIER_RANGE_OVERLAP", "阶梯区间与已有配置重叠");
        }
        requireScope(deleted.getOwnerType(), deleted.getOwnerId());
        if (!Integer.valueOf(2).equals(deleted.getDelFlag())) {
            // 未删除的同业务键行会先被 assertNoTierOverlap 拦住;留这一道是为了不冒 1062。
            throw MarketingPromotionException.conflict("TIER_RANGE_OVERLAP", "阶梯区间与已有配置重叠");
        }
        tier.setId(deleted.getId());
        tier.setRuleVersion(nextVersion(deleted.getRuleVersion()));
        tier.setUpdateTime(now);
        int revived = tierMapper.reactivateTier(tier);
        if (revived != 1) {
            throw MarketingPromotionException.conflict("TIER_RANGE_OVERLAP", "阶梯区间与已有配置重叠");
        }
        return revived;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTier(MarketingTier tier) {
        if (tier == null || tier.getId() == null) {
            throw MarketingPromotionException.badRequest("TIER_REQUIRED", "缺少阶梯配置");
        }
        MarketingTier existing = tierMapper.selectTierById(tier.getId());
        if (existing == null) {
            throw MarketingPromotionException.notFound("TIER_NOT_FOUND", "阶梯配置不存在");
        }
        requireScope(existing.getOwnerType(), existing.getOwnerId());
        tier.setOwnerType(existing.getOwnerType());
        tier.setOwnerId(existing.getOwnerId());
        normalizeTier(tier);
        assertNoTierOverlap(tier, tier.getId());
        tier.setRuleVersion(nextVersion(existing.getRuleVersion()));
        tier.setUpdateTime(new Date());
        return tierMapper.updateTier(tier);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTiers(Long[] ids) {
        if (ids == null || ids.length == 0) {
            throw MarketingPromotionException.badRequest("IDS_REQUIRED", "缺少要删除的阶梯配置");
        }
        for (Long id : ids) {
            MarketingTier existing = id == null ? null : tierMapper.selectTierById(id);
            if (existing == null) {
                continue;
            }
            requireScope(existing.getOwnerType(), existing.getOwnerId());
        }
        return tierMapper.deleteTierByIds(ids);
    }

    private void normalizeTier(MarketingTier tier) {
        if (tier.getTicketId() == null) {
            tier.setTicketId(MarketingTier.TICKET_ALL);
        }
        if (tier.getTicketId().longValue() != MarketingTier.TICKET_ALL) {
            requireTicket(tier.getTicketId(), tier.getOwnerType(), tier.getOwnerId());
        }
        if (tier.getMinQuantity() == null || tier.getMinQuantity() < 1) {
            throw MarketingPromotionException.badRequest("TIER_MIN_INVALID", "起始人数至少为 1");
        }
        if (tier.getMaxQuantity() != null && tier.getMaxQuantity() < tier.getMinQuantity()) {
            throw MarketingPromotionException.badRequest("TIER_RANGE_INVALID", "结束人数不能小于起始人数");
        }
        if (tier.getUnitPrice() == null || tier.getUnitPrice().signum() < 0) {
            throw MarketingPromotionException.badRequest("TIER_PRICE_INVALID", "阶梯单价不合法");
        }
        tier.setUnitPrice(money(tier.getUnitPrice()));
        if (tier.getTierCode() == null || tier.getTierCode().trim().isEmpty()) {
            tier.setTierCode("TIER-" + tier.getOwnerType() + "-" + tier.getOwnerId()
                    + "-" + tier.getTicketId() + "-" + tier.getMinQuantity());
        }
        tier.setTierCode(tier.getTierCode().trim());
        if (tier.getStatus() == null) {
            tier.setStatus(0);
        }
        if (tier.getStatus() != 0 && tier.getStatus() != 1) {
            throw MarketingPromotionException.badRequest("TIER_STATUS_INVALID", "阶梯状态不合法");
        }
        requireWindow(tier.getValidFrom(), tier.getValidTo());
    }

    /**
     * 区间不得重叠,且"全部票种"行不得与具体票种行重叠 —— 否则同一单会有两个合法单价,
     * 报价就成了"看查询顺序"。
     */
    private void assertNoTierOverlap(MarketingTier candidate, Long excludeId) {
        MarketingTier probe = new MarketingTier();
        probe.setOwnerType(candidate.getOwnerType());
        probe.setOwnerId(candidate.getOwnerId());
        List<MarketingTier> existing = tierMapper.selectTierList(probe);
        if (existing == null) {
            return;
        }
        for (MarketingTier other : existing) {
            if (other == null || other.getId() == null) {
                continue;
            }
            if (excludeId != null && excludeId.equals(other.getId())) {
                continue;
            }
            long candidateTicket = candidate.getTicketId() == null
                    ? MarketingTier.TICKET_ALL : candidate.getTicketId();
            long otherTicket = other.getTicketId() == null ? MarketingTier.TICKET_ALL : other.getTicketId();
            boolean sameTicket = candidateTicket == otherTicket
                    || candidateTicket == MarketingTier.TICKET_ALL
                    || otherTicket == MarketingTier.TICKET_ALL;
            if (!sameTicket) {
                continue;
            }
            if (rangesOverlap(candidate.getMinQuantity(), candidate.getMaxQuantity(),
                    other.getMinQuantity(), other.getMaxQuantity())) {
                throw MarketingPromotionException.conflict("TIER_RANGE_OVERLAP",
                        "阶梯区间与已有配置重叠");
            }
        }
    }

    private boolean rangesOverlap(Integer leftMin, Integer leftMax, Integer rightMin, Integer rightMax) {
        if (leftMin == null || rightMin == null) {
            return false;
        }
        int leftEnd = leftMax == null ? Integer.MAX_VALUE : leftMax;
        int rightEnd = rightMax == null ? Integer.MAX_VALUE : rightMax;
        return leftMin <= rightEnd && rightMin <= leftEnd;
    }

    // ------------------------------------------------------------------ 触发式促销

    @Override
    public List<MarketingPromotion> listPromotions(MarketingPromotion query) {
        return promotionMapper.selectPromotionList(query == null ? new MarketingPromotion() : query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createPromotion(MarketingPromotion promotion) {
        if (promotion == null) {
            throw MarketingPromotionException.badRequest("PROMOTION_REQUIRED", "缺少促销配置");
        }
        normalizePromotion(promotion);
        if (promotionMapper.countByCode(promotion.getPromoCode()) > 0) {
            throw MarketingPromotionException.conflict("PROMO_CODE_TAKEN", "促销码已存在");
        }
        promotion.setDelFlag(0);
        promotion.setRuleVersion(1);
        Date now = new Date();
        promotion.setCreateTime(now);
        promotion.setUpdateTime(now);
        return promotionMapper.insertPromotion(promotion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updatePromotion(MarketingPromotion promotion) {
        if (promotion == null || promotion.getId() == null) {
            throw MarketingPromotionException.badRequest("PROMOTION_REQUIRED", "缺少促销配置");
        }
        MarketingPromotion existing = promotionMapper.selectPromotionById(promotion.getId());
        if (existing == null) {
            throw MarketingPromotionException.notFound("PROMOTION_NOT_FOUND", "促销不存在");
        }
        // 必须先按「原配置」的 scope 过对象级校验:否则只改入参 scope 就能把别人主题/平台级的
        // 促销改挂到自己名下(updateOffer/updateTier 都用既有归属做同一道闸)。
        requirePromotionScope(existing);
        promotion.setPromoCode(existing.getPromoCode());
        normalizePromotion(promotion);
        promotion.setRuleVersion(nextVersion(existing.getRuleVersion()));
        promotion.setUpdateTime(new Date());
        return promotionMapper.updatePromotion(promotion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deletePromotions(Long[] ids) {
        if (ids == null || ids.length == 0) {
            throw MarketingPromotionException.badRequest("IDS_REQUIRED", "缺少要删除的促销");
        }
        for (Long id : ids) {
            MarketingPromotion existing = id == null ? null : promotionMapper.selectPromotionById(id);
            if (existing == null) {
                continue;
            }
            requirePromotionScope(existing);
        }
        return promotionMapper.deletePromotionByIds(ids);
    }

    private void requirePromotionScope(MarketingPromotion promotion) {
        Integer scopeType = promotion.getScopeType();
        if (scopeType == null || scopeType == MarketingPromotion.SCOPE_ALL) {
            if (!scopeAuthorizer.hasGlobalTopicScope()) {
                throw MarketingPromotionException.forbidden("SCOPE_FORBIDDEN", "全局促销仅限平台账号维护");
            }
            return;
        }
        requireScope(scopeType, promotion.getScopeId());
    }

    private void normalizePromotion(MarketingPromotion promotion) {
        if (promotion.getPromoCode() == null || promotion.getPromoCode().trim().isEmpty()) {
            throw MarketingPromotionException.badRequest("PROMO_CODE_REQUIRED", "促销码不能为空");
        }
        promotion.setPromoCode(promotion.getPromoCode().trim());
        if (promotion.getPromoCode().length() > 40) {
            throw MarketingPromotionException.badRequest("PROMO_CODE_TOO_LONG", "促销码过长");
        }
        if (promotion.getName() == null || promotion.getName().trim().isEmpty()) {
            throw MarketingPromotionException.badRequest("PROMO_NAME_REQUIRED", "促销名称不能为空");
        }
        promotion.setName(promotion.getName().trim());
        Integer trigger = promotion.getTriggerKind();
        if (trigger == null || (trigger != MarketingPromotion.TRIGGER_TOPIC_COMPLETION
                && trigger != MarketingPromotion.TRIGGER_FIRST_QUEST)) {
            throw MarketingPromotionException.badRequest("PROMO_TRIGGER_INVALID", "触发类型不合法");
        }
        requirePromotionScope(promotion);
        if (trigger == MarketingPromotion.TRIGGER_TOPIC_COMPLETION
                && !Integer.valueOf(MarketingPromotion.SCOPE_TOPIC).equals(promotion.getScopeType())) {
            // 完局限时优惠必须绑定具体主题:否则"哪个通关事实"无法解释,资格会被无限放大。
            throw MarketingPromotionException.badRequest("PROMO_SCOPE_REQUIRED",
                    "完局限时优惠必须绑定具体主题");
        }
        Integer discountType = promotion.getDiscountType();
        BigDecimal value = promotion.getDiscountValue();
        if (discountType == null || value == null) {
            throw MarketingPromotionException.badRequest("PROMO_DISCOUNT_REQUIRED", "缺少优惠方式");
        }
        if (discountType == MarketingPromotion.DISCOUNT_FIXED_AMOUNT) {
            if (value.signum() <= 0) {
                throw MarketingPromotionException.badRequest("PROMO_DISCOUNT_INVALID", "减免金额必须大于 0");
            }
            promotion.setDiscountValue(money(value));
        } else if (discountType == MarketingPromotion.DISCOUNT_RATE) {
            if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw MarketingPromotionException.badRequest("PROMO_DISCOUNT_INVALID",
                        "折扣率必须在 (0,1] 之间");
            }
            promotion.setDiscountValue(value.setScale(4, RoundingMode.HALF_UP));
            if (promotion.getMaxDiscountAmount() != null) {
                promotion.setMaxDiscountAmount(money(promotion.getMaxDiscountAmount()));
            }
        } else {
            throw MarketingPromotionException.badRequest("PROMO_DISCOUNT_INVALID", "不支持的优惠方式");
        }
        // 窗口必须显式配置:竞品样本的 48h / 30d 不是城瘾默认值,这里不给兜底。
        if (promotion.getWindowHours() == null || promotion.getWindowHours() <= 0) {
            throw MarketingPromotionException.badRequest("PROMO_WINDOW_REQUIRED",
                    "必须显式配置有效小时数");
        }
        if (promotion.getMinPayableAmount() != null) {
            if (promotion.getMinPayableAmount().signum() < 0) {
                throw MarketingPromotionException.badRequest("PROMO_MIN_INVALID", "门槛金额不能为负");
            }
            promotion.setMinPayableAmount(money(promotion.getMinPayableAmount()));
        }
        if (promotion.getExclusiveFlag() == null) {
            promotion.setExclusiveFlag(0);
        }
        if (promotion.getExclusiveFlag() != 0 && promotion.getExclusiveFlag() != 1) {
            throw MarketingPromotionException.badRequest("PROMO_EXCLUSIVE_INVALID", "独占标记不合法");
        }
        if (promotion.getStackGroup() == null || promotion.getStackGroup().trim().isEmpty()) {
            promotion.setStackGroup("DEFAULT");
        } else {
            promotion.setStackGroup(promotion.getStackGroup().trim());
        }
        if (promotion.getStackGroup().length() > 32) {
            throw MarketingPromotionException.badRequest("PROMO_STACK_GROUP_TOO_LONG", "叠加组名过长");
        }
        if (promotion.getPriority() == null) {
            promotion.setPriority(0);
        }
        if (promotion.getMaxUsesPerMember() == null || promotion.getMaxUsesPerMember() < 0) {
            promotion.setMaxUsesPerMember(1);
        }
        if (promotion.getStatus() == null) {
            promotion.setStatus(MarketingPromotion.STATUS_DISABLED);
        }
        if (promotion.getStatus() != MarketingPromotion.STATUS_DISABLED
                && promotion.getStatus() != MarketingPromotion.STATUS_ENABLED) {
            throw MarketingPromotionException.badRequest("PROMO_STATUS_INVALID", "促销状态不合法");
        }
        requireWindow(promotion.getValidFrom(), promotion.getValidTo());
    }

    private int nextVersion(Integer current) {
        return (current == null ? 0 : current) + 1;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

}
