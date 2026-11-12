package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.dto.MarketingEligibility;
import com.chengyinhub.business.domain.dto.MarketingExclusion;
import com.chengyinhub.business.domain.dto.MarketingPromotionFacts;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;
import com.chengyinhub.business.exception.MarketingPromotionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 促销计算器契约:顺序、叠加、金额守恒、坏配置的拒绝方向。
 *
 * <p>本测试只覆盖纯计算,不证明任何 DB 或事务行为 —— 那些在服务层测试与 MySqlIT 里。
 */
class MarketingPromotionCalculatorTest {

    private static final Long TICKET_A = 11L;

    private final MarketingPromotionCalculator calculator = new MarketingPromotionCalculator();

    // ------------------------------------------------------------------ 阶梯

    @Test
    void tierLowersUnitPriceAndKeepsAmountsBalanced() {
        MarketingPromotionFacts facts = facts();
        facts.setTiers(Arrays.asList(tier(TICKET_A, 2, null, "90.00")));

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);

        assertMoney("200.00", quote.getListAmount());
        assertMoney("20.00", quote.getTierDiscountAmount());
        assertMoney("180.00", quote.getPayableAmount());
        assertEquals(1, quote.getAdjustments().size());
        assertEquals(MarketingPromotionQuote.KIND_TIER, quote.getAdjustments().get(0).getKind());
    }

    @Test
    void tierNeverRaisesThePriceAboveTheTicketPrice() {
        MarketingPromotionFacts facts = facts();
        facts.setTiers(Arrays.asList(tier(TICKET_A, 1, null, "180.00")));

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), facts);

        assertMoney("100.00", quote.getPayableAmount());
        assertMoney("0.00", quote.getTierDiscountAmount());
        assertTrue(quote.getAdjustments().isEmpty(), "阶梯价高于票面时不得产生任何让利分项");
    }

    @Test
    void tierJudgesAggregatedQuantityAcrossSplitLines() {
        // 同一票种被拆成两行(按参与日期分行):阶梯必须按 2+2=4 人判,不能逐行判成 2 人
        MarketingPromotionFacts facts = facts();
        facts.setTiers(Arrays.asList(tier(TICKET_A, 1, 2, "100.00"), tier(TICKET_A, 3, 5, "80.00")));

        MarketingPromotionQuote quote = calculator.calculate(
                request(lines(line(TICKET_A, 2, "100.00"), line(TICKET_A, 2, "100.00"))), facts);

        assertEquals(1, quote.getLines().size(), "同票种应合并成一行");
        assertEquals(Integer.valueOf(4), quote.getLines().get(0).getQuantity());
        // 4 人落在 3~5 区间 → 单价 80 → 320;分买 400,让利 80
        assertMoney("320.00", quote.getPayableAmount());
        assertMoney("80.00", quote.getTierDiscountAmount());
    }

    @Test
    void tierForExactTicketWinsOverWildcardTier() {
        MarketingPromotionFacts facts = facts();
        facts.setTiers(Arrays.asList(tier(MarketingTier.TICKET_ALL, 1, null, "95.00"),
                tier(TICKET_A, 1, null, "85.00")));

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), facts);

        assertMoney("85.00", quote.getPayableAmount());
    }

    // ------------------------------------------------------------------ 组合包

    @Test
    void comboReplacesTheSumAndSupersedesTier() {
        MarketingPromotionFacts facts = facts();
        facts.setTiers(Arrays.asList(tier(TICKET_A, 2, null, "90.00")));
        facts.setOffer(offer("150.00"));

        MarketingPromotionQuote quote = calculator.calculate(
                request(lines(line(TICKET_A, 2, "100.00")), "COMBO-A-2"), facts);

        assertMoney("200.00", quote.getListAmount());
        assertMoney("0.00", quote.getTierDiscountAmount());
        assertMoney("50.00", quote.getOfferDiscountAmount());
        assertMoney("150.00", quote.getPayableAmount());
        assertEquals("COMBO-A-2", quote.getOfferCode());
        assertExcluded(quote, MarketingExclusion.TIER_SUPERSEDED_BY_OFFER);
    }

    @Test
    void comboAboveTheSumIsRejectedAndFallsBackToLinePricing() {
        MarketingPromotionFacts facts = facts();
        facts.setOffer(offer("260.00"));

        MarketingPromotionQuote quote = calculator.calculate(
                request(lines(line(TICKET_A, 2, "100.00")), "COMBO-A-2"), facts);

        assertMoney("200.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.OFFER_PRICE_ABOVE_LIST);
    }

    // ------------------------------------------------------------------ 触发式促销

    @Test
    void fixedAmountPromotionIsCappedByRemainingAmount() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion promotion = promotion(7L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "500.00", 0);
        facts.setPromotions(Arrays.asList(promotion));
        facts.getEligibility().put(7L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);

        assertMoney("0.00", quote.getPayableAmount());
        assertMoney("200.00", quote.getPromotionDiscountAmount());
        assertEquals(Long.valueOf(7L), quote.getPromotionIds().get(0));
    }

    @Test
    void ratePromotionHonoursItsCap() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion promotion = promotion(8L, MarketingPromotion.DISCOUNT_RATE, "0.5000", 0);
        promotion.setMaxDiscountAmount(new BigDecimal("60.00"));
        facts.setPromotions(Arrays.asList(promotion));
        facts.getEligibility().put(8L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);

        assertMoney("60.00", quote.getPromotionDiscountAmount());
        assertMoney("140.00", quote.getPayableAmount());
    }

    @Test
    void onlyTheFirstPromotionOfAStackGroupApplies() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion first = promotion(1L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "20.00", 100);
        MarketingPromotion second = promotion(2L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "50.00", 50);
        second.setStackGroup("FIRST_QUEST");
        first.setStackGroup("FIRST_QUEST");
        facts.setPromotions(Arrays.asList(first, second));
        facts.getEligibility().put(1L, eligible());
        facts.getEligibility().put(2L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);

        assertMoney("20.00", quote.getPromotionDiscountAmount());
        assertExcluded(quote, MarketingExclusion.STACK_GROUP_TAKEN);
    }

    @Test
    void exclusivePromotionBlocksEveryLaterCandidate() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion exclusive = promotion(1L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "20.00", 100);
        exclusive.setExclusiveFlag(1);
        MarketingPromotion other = promotion(2L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "50.00", 90);
        other.setStackGroup("OTHER");
        facts.setPromotions(Arrays.asList(exclusive, other));
        facts.getEligibility().put(1L, eligible());
        facts.getEligibility().put(2L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);

        assertMoney("20.00", quote.getPromotionDiscountAmount());
        assertExcluded(quote, MarketingExclusion.EXCLUSIVE_ALREADY_APPLIED);
    }

    @Test
    void ineligiblePromotionIsReportedWithItsOwnReasonCode() {
        MarketingPromotionFacts facts = facts();
        facts.setPromotions(Arrays.asList(promotion(5L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "30.00", 0)));
        facts.getEligibility().put(5L, MarketingEligibility.denied(MarketingExclusion.ALREADY_USED));

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), facts);

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.ALREADY_USED);
    }

    @Test
    void promotionBelowThresholdIsExcluded() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion promotion = promotion(6L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "30.00", 0);
        promotion.setMinPayableAmount(new BigDecimal("500.00"));
        facts.setPromotions(Arrays.asList(promotion));
        facts.getEligibility().put(6L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), facts);

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, MarketingExclusion.BELOW_MIN_AMOUNT);
    }

    @Test
    void unknownDiscountTypeIsExcludedInsteadOfGuessed() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion promotion = promotion(9L, Integer.valueOf(99), "0.10", 0);
        facts.setPromotions(Arrays.asList(promotion));
        facts.getEligibility().put(9L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), facts);

        assertMoney("100.00", quote.getPayableAmount());
        assertExcluded(quote, "PROMO_TYPE_UNSUPPORTED");
    }

    @Test
    void promotionsApplyInTheGivenOrderOnTheRemainingAmount() {
        MarketingPromotionFacts facts = facts();
        MarketingPromotion first = promotion(1L, MarketingPromotion.DISCOUNT_RATE, "0.1000", 100);
        first.setStackGroup("A");
        MarketingPromotion second = promotion(2L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "10.00", 90);
        second.setStackGroup("B");
        facts.setPromotions(Arrays.asList(first, second));
        facts.getEligibility().put(1L, eligible());
        facts.getEligibility().put(2L, eligible());

        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);

        // 200 × 10% = 20;余额 180 − 10 = 170
        assertMoney("170.00", quote.getPayableAmount());
        assertMoney("30.00", quote.getPromotionDiscountAmount());
    }

    // ------------------------------------------------------------------ 入参与守恒

    @Test
    void emptyLinesAreRejected() {
        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> calculator.calculate(request(new ArrayList<>()), facts()));
        assertEquals("LINES_INVALID", rejection.getReasonCode());
    }

    @Test
    void sameTicketWithTwoDifferentUnitPricesIsRejected() {
        MarketingPromotionException rejection = assertThrows(MarketingPromotionException.class,
                () -> calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"), line(TICKET_A, 1, "50.00"))),
                        facts()));
        assertEquals("LINES_INVALID", rejection.getReasonCode());
    }

    @Test
    void resultCarriesRuleVersionAndStableFingerprint() {
        MarketingPromotionFacts facts = facts();
        facts.setTiers(Arrays.asList(tier(TICKET_A, 2, null, "90.00")));
        MarketingPromotionQuote quote = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);
        assertNotNull(quote.getMarketingSign());
        assertEquals(64, quote.getMarketingSign().length());
        assertTrue(quote.getRuleVersion().startsWith("offer:"), quote.getRuleVersion());

        // 同一输入两次计算必须得到同一个指纹(时间不参与签名)
        MarketingPromotionQuote again = calculator.calculate(request(lines(line(TICKET_A, 2, "100.00"))), facts);
        assertEquals(quote.getMarketingSign(), again.getMarketingSign());
    }

    @Test
    void differentPromotionIdentityChangesTheFingerprint() {
        MarketingPromotionFacts first = facts();
        MarketingPromotion promoA = promotion(1L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "20.00", 0);
        first.setPromotions(Arrays.asList(promoA));
        first.getEligibility().put(1L, eligible());
        MarketingPromotionFacts second = facts();
        MarketingPromotion promoB = promotion(2L, MarketingPromotion.DISCOUNT_FIXED_AMOUNT, "20.00", 0);
        second.setPromotions(Arrays.asList(promoB));
        second.getEligibility().put(2L, eligible());

        MarketingPromotionQuote left = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), first);
        MarketingPromotionQuote right = calculator.calculate(request(lines(line(TICKET_A, 1, "100.00"))), second);

        assertMoney("80.00", left.getPayableAmount());
        assertMoney("80.00", right.getPayableAmount());
        assertTrue(!left.getMarketingSign().equals(right.getMarketingSign()),
                "同金额但不同促销身份,指纹必须不同,否则可被换促销重放");
    }

    // ------------------------------------------------------------------ helpers

    private MarketingPromotionFacts facts() {
        MarketingPromotionFacts facts = new MarketingPromotionFacts();
        facts.setTiers(new ArrayList<>());
        facts.setPromotions(new ArrayList<>());
        return facts;
    }

    private MarketingPromotionRequest request(List<MarketingPromotionRequest.Line> lines) {
        return request(lines, null);
    }

    private MarketingPromotionRequest request(List<MarketingPromotionRequest.Line> lines, String offerCode) {
        MarketingPromotionRequest request = new MarketingPromotionRequest();
        request.setMemberId(88L);
        request.setOwnerType(2);
        request.setOwnerId(200L);
        request.setLines(lines);
        request.setOfferCode(offerCode);
        return request;
    }

    private List<MarketingPromotionRequest.Line> lines(MarketingPromotionRequest.Line... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private MarketingPromotionRequest.Line line(Long ticketId, int quantity, String unitPrice) {
        return new MarketingPromotionRequest.Line(ticketId, quantity, new BigDecimal(unitPrice));
    }

    private MarketingTier tier(Long ticketId, int min, Integer max, String unitPrice) {
        MarketingTier tier = new MarketingTier();
        tier.setTicketId(ticketId);
        tier.setMinQuantity(min);
        tier.setMaxQuantity(max);
        tier.setUnitPrice(new BigDecimal(unitPrice));
        tier.setStatus(1);
        tier.setRuleVersion(1);
        tier.setTierCode("T-" + ticketId + "-" + min);
        return tier;
    }

    private MarketingOffer offer(String price) {
        MarketingOffer offer = new MarketingOffer();
        offer.setId(70L);
        offer.setOfferCode("COMBO-A-2");
        offer.setOfferKind(MarketingOffer.KIND_COMBO);
        offer.setName("打包优惠");
        offer.setPriceAmount(new BigDecimal(price));
        offer.setStatus(MarketingOffer.STATUS_ENABLED);
        offer.setRuleVersion(3);
        return offer;
    }

    private MarketingPromotion promotion(Long id, Integer discountType, String value, int priority) {
        MarketingPromotion promotion = new MarketingPromotion();
        promotion.setId(id);
        promotion.setPromoCode("PROMO-" + id);
        promotion.setName("促销 " + id);
        promotion.setTriggerKind(MarketingPromotion.TRIGGER_TOPIC_COMPLETION);
        promotion.setDiscountType(discountType);
        promotion.setDiscountValue(new BigDecimal(value));
        promotion.setWindowHours(48);
        promotion.setPriority(priority);
        promotion.setStatus(MarketingPromotion.STATUS_ENABLED);
        promotion.setRuleVersion(2);
        promotion.setMaxUsesPerMember(1);
        promotion.setStackGroup("PROMO-" + id);
        return promotion;
    }

    private MarketingEligibility eligible() {
        return MarketingEligibility.allowed(1, 900L, new Date(), new Date(System.currentTimeMillis() + 3_600_000L));
    }

    private void assertExcluded(MarketingPromotionQuote quote, String reasonCode) {
        for (MarketingExclusion exclusion : quote.getExclusions()) {
            if (reasonCode.equals(exclusion.getReasonCode())) {
                return;
            }
        }
        throw new AssertionError("缺少排除原因 " + reasonCode + ",实际=" + quote.getExclusions().size());
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + actual);
    }
}
