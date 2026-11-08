package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.dto.MarketingAdjustment;
import com.chengyinhub.business.domain.dto.MarketingEligibility;
import com.chengyinhub.business.domain.dto.MarketingExclusion;
import com.chengyinhub.business.domain.dto.MarketingPromotionFacts;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;
import com.chengyinhub.business.domain.dto.MarketingQuoteLine;
import com.chengyinhub.business.exception.MarketingPromotionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 促销定价计算器(纯函数,无 Spring、无 DB、无时钟)。
 *
 * <p><b>顺序是唯一的,且写死在这里</b>:
 * <ol>
 *   <li><b>阶梯价</b>先改单价基数 —— 它改的是"单价",不是折扣。</li>
 *   <li><b>组合包</b>用一个打包价替换折前金额。它与阶梯价互斥:同时存在时组合包生效,
 *       阶梯价逐条给出排除原因,不做二次叠加 —— 两个都改基数,
 *       叠在一起就没有"这个数是怎么来的"这一说了。</li>
 *   <li><b>触发式促销</b>(完局限时/首关)按 priority 降序、id 升序依次在余额上减,
 *       同 stackGroup 只取一个,exclusive 独占后其余全部不参与。</li>
 * </ol>
 *
 * <p><b>金额守恒</b>:{@code list - tier - offer - promotion == payable},
 * 每次减免都被余额封顶,余额永不为负,任一等式不成立即抛异常(不报价 &gt; 报错数)。
 *
 * <p><b>金额符号</b>:{@code MarketingAdjustment.amount} 恒为正(减免额);不接受负调整 ——
 * 加价(如礼品溢价)属于商品定价,由 M3 的礼品 SKU 承接,不进促销引擎。
 */
public class MarketingPromotionCalculator {

    private static final int MONEY_SCALE = 2;
    private static final String PROMO_TYPE_UNSUPPORTED = "PROMO_TYPE_UNSUPPORTED";
    private static final String LINES_INVALID = "LINES_INVALID";

    /**
     * 计算一份促销报价。
     *
     * @param request 调用方(报价/建单)给出的行与意图
     * @param facts   服务端已读好的配置与资格事实
     */
    public MarketingPromotionQuote calculate(MarketingPromotionRequest request, MarketingPromotionFacts facts) {
        if (request == null || facts == null) {
            throw new IllegalArgumentException("促销计算缺少入参");
        }
        MarketingPromotionQuote quote = new MarketingPromotionQuote();
        quote.setMemberId(request.getMemberId());
        quote.setOwnerType(request.getOwnerType());
        quote.setOwnerId(request.getOwnerId());
        List<MarketingQuoteLine> lines = mergeLines(request);
        quote.setLines(lines);
        quote.setListAmount(money(sumListAmount(lines)));

        MarketingOffer offer = facts.getOffer();
        if (facts.getOfferExclusionReason() != null) {
            quote.getExclusions().add(new MarketingExclusion(request.getOfferCode(),
                    facts.getOfferExclusionReason(), "所选优惠不可用", facts.getOfferExclusionDetail()));
        }

        List<String> tierVersions = applyTierStep(quote, facts, offer, lines);

        BigDecimal payable = sumLineAmounts(lines);
        BigDecimal offerAdjustment = applyOfferStep(quote, offer, payable);
        // 只有真的生效了才算 offer:被排除(打包价高于分买)时不得再当成"固定价"去挡促销。
        MarketingOffer effectiveOffer = quote.getOfferId() == null ? null : offer;
        if (effectiveOffer != null) {
            payable = payable.subtract(offerAdjustment);
            if (payable.signum() < 0) {
                throw new IllegalStateException("组合包算出了负应付");
            }
        }

        List<String> promotionVersions = applyPromotionStep(quote, facts, payable);

        quote.setRuleVersion(ruleVersion(effectiveOffer, tierVersions, promotionVersions));
        quote.setMarketingSign(marketingSign(request, quote));
        assertConservation(quote);
        return quote;
    }

    // ---------------------------------------------------------------- 阶梯

    private List<String> applyTierStep(MarketingPromotionQuote quote, MarketingPromotionFacts facts,
                                       MarketingOffer offer, List<MarketingQuoteLine> lines) {
        List<String> tierVersions = new ArrayList<>();
        if (offer != null) {
            for (MarketingQuoteLine line : lines) {
                line.setUnitPrice(money(line.getListUnitPrice()));
                line.setAmount(money(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()))));
            }
            if (!facts.getTiers().isEmpty()) {
                quote.getExclusions().add(new MarketingExclusion("TIER",
                        MarketingExclusion.TIER_SUPERSEDED_BY_OFFER, "阶梯价",
                        "已选组合包,阶梯价不再叠加"));
            }
            return tierVersions;
        }
        BigDecimal tierDiscount = BigDecimal.ZERO;
        for (MarketingQuoteLine line : lines) {
            BigDecimal unit = money(line.getListUnitPrice());
            MarketingTier tier = pickTier(facts.getTiers(), line.getTicketId(), line.getQuantity());
            if (tier != null) {
                tierVersions.add(tier.getTierCode() + "@" + nz(tier.getRuleVersion()));
                BigDecimal tierUnit = money(tier.getUnitPrice());
                if (tierUnit.signum() < 0) {
                    throw new IllegalStateException("阶梯单价为负 tierCode=" + tier.getTierCode());
                }
                if (tierUnit.compareTo(unit) < 0) {
                    BigDecimal saved = money(unit.subtract(tierUnit)
                            .multiply(BigDecimal.valueOf(line.getQuantity())));
                    tierDiscount = tierDiscount.add(saved);
                    MarketingAdjustment adjustment = new MarketingAdjustment(
                            MarketingPromotionQuote.KIND_TIER, tier.getTierCode(), "人数阶梯价", saved,
                            tier.getRuleVersion());
                    quote.getAdjustments().add(adjustment);
                    unit = tierUnit;
                }
            }
            line.setUnitPrice(unit);
            line.setAmount(money(unit.multiply(BigDecimal.valueOf(line.getQuantity()))));
        }
        quote.setTierDiscountAmount(money(tierDiscount));
        return tierVersions;
    }

    /** 同票种取"最贴近"的区间;具体票种优先于通配票种,同类型取 min 最大者。 */
    private MarketingTier pickTier(List<MarketingTier> tiers, Long ticketId, int quantity) {
        MarketingTier bestExact = null;
        MarketingTier bestAll = null;
        long tid = ticketId == null ? -1L : ticketId;
        for (MarketingTier tier : tiers) {
            if (tier == null || tier.getMinQuantity() == null) {
                continue;
            }
            if (tier.getStatus() != null && tier.getStatus() != 1) {
                continue;
            }
            if (quantity < tier.getMinQuantity()) {
                continue;
            }
            if (tier.getMaxQuantity() != null && quantity > tier.getMaxQuantity()) {
                continue;
            }
            long tierTicket = tier.getTicketId() == null ? MarketingTier.TICKET_ALL : tier.getTicketId();
            if (tierTicket == tid) {
                if (bestExact == null || tier.getMinQuantity() > bestExact.getMinQuantity()) {
                    bestExact = tier;
                }
            } else if (tierTicket == MarketingTier.TICKET_ALL) {
                if (bestAll == null || tier.getMinQuantity() > bestAll.getMinQuantity()) {
                    bestAll = tier;
                }
            }
        }
        return bestExact != null ? bestExact : bestAll;
    }

    // ---------------------------------------------------------------- 组合包

    /**
     * 组合包:返回"相对折前金额的减免额"(≥ 0)。
     * 打包价高于分买合计时不许多收 —— 退回分买计价并给出排除原因。
     */
    private BigDecimal applyOfferStep(MarketingPromotionQuote quote, MarketingOffer offer, BigDecimal payable) {
        if (offer == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal offerPrice = money(offer.getPriceAmount());
        if (offerPrice.signum() < 0) {
            throw new IllegalStateException("打包价不能为负 offerCode=" + offer.getOfferCode());
        }
        if (offerPrice.compareTo(payable) > 0) {
            quote.getExclusions().add(new MarketingExclusion(offer.getOfferCode(),
                    MarketingExclusion.OFFER_PRICE_ABOVE_LIST, "组合包",
                    "打包价高于分买合计,按分买计价"));
            return BigDecimal.ZERO;
        }
        BigDecimal adjustment = money(payable.subtract(offerPrice));
        quote.setOfferId(offer.getId());
        quote.setOfferCode(offer.getOfferCode());
        quote.setOfferKind(offer.getOfferKind());
        quote.setOfferDiscountAmount(adjustment);
        MarketingAdjustment line = new MarketingAdjustment(MarketingPromotionQuote.KIND_OFFER,
                offer.getOfferCode(), offer.getName(), adjustment, offer.getRuleVersion());
        line.setOfferId(offer.getId());
        quote.getAdjustments().add(line);
        return adjustment;
    }

    // ---------------------------------------------------------------- 触发式促销

    private List<String> applyPromotionStep(MarketingPromotionQuote quote, MarketingPromotionFacts facts,
                                            BigDecimal payable) {
        List<String> versions = new ArrayList<>();
        BigDecimal remaining = payable;
        BigDecimal total = BigDecimal.ZERO;
        Set<String> takenGroups = new LinkedHashSet<>();
        boolean exclusiveApplied = false;

        for (MarketingPromotion promotion : facts.getPromotions()) {
            if (promotion == null) {
                continue;
            }
            String code = promotion.getPromoCode();
            String label = promotion.getName();
            if (exclusiveApplied) {
                quote.getExclusions().add(new MarketingExclusion(code,
                        MarketingExclusion.EXCLUSIVE_ALREADY_APPLIED, label, "已有独占优惠生效"));
                continue;
            }
            MarketingEligibility eligibility = facts.getEligibility().get(promotion.getId());
            if (eligibility == null) {
                quote.getExclusions().add(new MarketingExclusion(code,
                        MarketingExclusion.PROMO_DISABLED, label, "未参与本次报价"));
                continue;
            }
            if (!eligibility.isEligible()) {
                String reason = eligibility.getReasonCode() == null
                        ? MarketingExclusion.TRIGGER_FACT_MISSING : eligibility.getReasonCode();
                quote.getExclusions().add(new MarketingExclusion(code, reason, label, null));
                continue;
            }
            String group = stackGroup(promotion);
            if (takenGroups.contains(group)) {
                quote.getExclusions().add(new MarketingExclusion(code,
                        MarketingExclusion.STACK_GROUP_TAKEN, label, "同组已有更高优先级的优惠生效"));
                continue;
            }
            BigDecimal minPayable = promotion.getMinPayableAmount();
            if (minPayable != null && remaining.compareTo(minPayable) < 0) {
                quote.getExclusions().add(new MarketingExclusion(code,
                        MarketingExclusion.BELOW_MIN_AMOUNT, label, "折前金额未达门槛"));
                continue;
            }
            BigDecimal amount = discountOf(promotion, remaining);
            if (amount == null) {
                quote.getExclusions().add(new MarketingExclusion(code,
                        PROMO_TYPE_UNSUPPORTED, label, "优惠类型不支持"));
                continue;
            }
            amount = money(amount);
            if (amount.signum() <= 0) {
                quote.getExclusions().add(new MarketingExclusion(code,
                        MarketingExclusion.ZERO_AMOUNT, label, "本次可减金额为 0"));
                continue;
            }
            if (amount.compareTo(remaining) > 0) {
                amount = remaining;
            }
            remaining = remaining.subtract(amount);
            total = total.add(amount);
            MarketingAdjustment adjustment = new MarketingAdjustment(
                    MarketingPromotionQuote.KIND_PROMOTION, code, label, amount, promotion.getRuleVersion());
            adjustment.setPromotionId(promotion.getId());
            adjustment.setSourceFactType(eligibility.getSourceFactType());
            adjustment.setSourceFactId(eligibility.getSourceFactId());
            quote.getAdjustments().add(adjustment);
            quote.getPromotionIds().add(promotion.getId());
            quote.getPromotionCodes().add(code);
            versions.add(promotion.getId() + "@" + nz(promotion.getRuleVersion()));
            takenGroups.add(group);
            if (Integer.valueOf(1).equals(promotion.getExclusiveFlag())) {
                exclusiveApplied = true;
            }
        }
        quote.setPromotionDiscountAmount(money(total));
        quote.setPayableAmount(money(remaining));
        return versions;
    }

    /** 返回 null 表示配置的折扣类型不认识(调用方给排除原因,不猜)。 */
    private BigDecimal discountOf(MarketingPromotion promotion, BigDecimal remaining) {
        BigDecimal value = promotion.getDiscountValue();
        if (value == null) {
            return null;
        }
        Integer type = promotion.getDiscountType();
        if (type == null) {
            return null;
        }
        if (type == MarketingPromotion.DISCOUNT_FIXED_AMOUNT) {
            return value.min(remaining);
        }
        if (type == MarketingPromotion.DISCOUNT_RATE) {
            BigDecimal raw = remaining.multiply(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal cap = promotion.getMaxDiscountAmount();
            if (cap != null && raw.compareTo(cap) > 0) {
                raw = cap;
            }
            return raw.min(remaining);
        }
        return null;
    }

    private String stackGroup(MarketingPromotion promotion) {
        String group = promotion.getStackGroup();
        return group == null || group.trim().isEmpty() ? "DEFAULT" : group.trim();
    }

    // ---------------------------------------------------------------- 守恒与签名

    private void assertConservation(MarketingPromotionQuote quote) {
        BigDecimal recomputed = money(quote.getListAmount())
                .subtract(money(quote.getTierDiscountAmount()))
                .subtract(money(quote.getOfferDiscountAmount()))
                .subtract(money(quote.getPromotionDiscountAmount()));
        if (recomputed.compareTo(money(quote.getPayableAmount())) != 0) {
            throw new IllegalStateException("促销金额不守恒 list=" + quote.getListAmount()
                    + " tier=" + quote.getTierDiscountAmount()
                    + " offer=" + quote.getOfferDiscountAmount()
                    + " promo=" + quote.getPromotionDiscountAmount()
                    + " payable=" + quote.getPayableAmount());
        }
        if (quote.getPayableAmount().signum() < 0) {
            throw new IllegalStateException("促销算出负应付 " + quote.getPayableAmount());
        }
        if (quote.getPromotionDiscountAmount().signum() < 0) {
            throw new IllegalStateException("促销减免合计为负");
        }
    }

    private String ruleVersion(MarketingOffer offer, List<String> tierVersions, List<String> promotionVersions) {
        StringBuilder builder = new StringBuilder();
        builder.append("offer:").append(offer == null ? "0" : String.valueOf(nz(offer.getRuleVersion())));
        builder.append("|tier:").append(String.join(",", tierVersions));
        builder.append("|promo:").append(String.join(",", promotionVersions));
        return builder.toString();
    }

    /**
     * 促销报价指纹:只取身份 + 版本 + 金额,不含时间。
     * 时间进签名会让同一份报价每刷新一次就变一次,已发出的报价永远对不上。
     */
    private String marketingSign(MarketingPromotionRequest request, MarketingPromotionQuote quote) {
        StringBuilder canonical = new StringBuilder("mkt1");
        canonical.append('|').append(nz(request.getMemberId()));
        canonical.append('|').append(nz(request.getOwnerType()));
        canonical.append('|').append(nz(request.getOwnerId()));
        canonical.append('|').append(plain(quote.getListAmount()));
        canonical.append('|').append(quote.getOfferId() == null ? "0" : String.valueOf(quote.getOfferId()));
        canonical.append('|').append(quote.getOfferCode() == null ? "" : quote.getOfferCode());
        canonical.append('|').append(plain(quote.getOfferDiscountAmount()));
        for (MarketingAdjustment adjustment : quote.getAdjustments()) {
            canonical.append('|').append(adjustment.getKind())
                    .append(':').append(adjustment.getCode())
                    .append(':').append(adjustment.getRuleVersion() == null ? "0" : String.valueOf(adjustment.getRuleVersion()))
                    .append(':').append(plain(adjustment.getAmount()));
        }
        canonical.append("|p=").append(plain(quote.getPayableAmount()));
        canonical.append("|rv=").append(quote.getRuleVersion());
        return sha256(canonical.toString());
    }

    // ---------------------------------------------------------------- 工具

    private List<MarketingQuoteLine> mergeLines(MarketingPromotionRequest request) {
        Map<Long, MarketingQuoteLine> merged = new LinkedHashMap<>();
        List<MarketingPromotionRequest.Line> raw = request.getLines();
        if (raw == null || raw.isEmpty()) {
            throw MarketingPromotionException.badRequest(LINES_INVALID, "缺少购票明细");
        }
        for (MarketingPromotionRequest.Line line : raw) {
            if (line == null || line.getTicketId() == null || line.getTicketId() <= 0) {
                throw MarketingPromotionException.badRequest(LINES_INVALID, "购票明细缺少票种");
            }
            int quantity = line.getQuantity() == null || line.getQuantity() <= 0 ? 1 : line.getQuantity();
            BigDecimal unit = line.getUnitListPrice() == null ? BigDecimal.ZERO : line.getUnitListPrice();
            if (unit.signum() < 0) {
                throw MarketingPromotionException.badRequest(LINES_INVALID, "票面价不能为负");
            }
            MarketingQuoteLine existing = merged.get(line.getTicketId());
            if (existing == null) {
                MarketingQuoteLine created = new MarketingQuoteLine(line.getTicketId(), quantity, money(unit));
                created.setUnitPrice(money(unit));
                created.setListAmount(money(unit.multiply(BigDecimal.valueOf(quantity))));
                created.setAmount(created.getListAmount());
                merged.put(line.getTicketId(), created);
            } else {
                // 同票种不同单价 = 上游把两张不同票当成一张,或票价在两次读之间变了;不猜,报错重报。
                if (existing.getListUnitPrice().compareTo(money(unit)) != 0) {
                    throw MarketingPromotionException.badRequest(LINES_INVALID,
                            "同一票种出现两个单价,请重新报价");
                }
                int total = existing.getQuantity() + quantity;
                existing.setQuantity(total);
                existing.setListAmount(money(existing.getListUnitPrice().multiply(BigDecimal.valueOf(total))));
                existing.setAmount(existing.getListAmount());
            }
        }
        return new ArrayList<>(merged.values());
    }

    private BigDecimal sumListAmount(List<MarketingQuoteLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (MarketingQuoteLine line : lines) {
            total = total.add(line.getListAmount());
        }
        return total;
    }

    private BigDecimal sumLineAmounts(List<MarketingQuoteLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (MarketingQuoteLine line : lines) {
            total = total.add(line.getAmount());
        }
        return money(total);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return money(value).toPlainString();
    }

    private static String nz(Number value) {
        return value == null ? "0" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
