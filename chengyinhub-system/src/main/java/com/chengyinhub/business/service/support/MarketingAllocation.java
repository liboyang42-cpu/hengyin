package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.service.GrantLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 多作品包金额分摊 → M2-F {@link GrantLine}（金额守恒、可对账复算）。
 *
 * <p>权重 = 各主题单买票面；缺失/0 按 1。尾差归首行。
 * 硬约束（违反抛异常，不静默）：Σpayable = 实付；0 ≤ payable ≤ 该行 list；payable ≤ 该主题单买价。
 */
public final class MarketingAllocation {

    private MarketingAllocation() { }

    public static List<GrantLine> lines(MarketingProduct product,
                                        List<MarketingProductItem> items,
                                        List<BigDecimal> listPrices,
                                        BigDecimal total) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("PRODUCT_HAS_NO_CONTENT");
        if (total == null || total.signum() <= 0) throw new IllegalArgumentException("TOTAL_NOT_POSITIVE");
        if (listPrices == null || listPrices.size() != items.size()) {
            throw new IllegalArgumentException("LIST_PRICES_MISMATCH");
        }

        List<BigDecimal> weights = new ArrayList<>(items.size());
        BigDecimal weightSum = BigDecimal.ZERO;
        for (BigDecimal price : listPrices) {
            BigDecimal weight = price == null || price.signum() <= 0 ? BigDecimal.ONE : price;
            weights.add(weight);
            weightSum = weightSum.add(weight);
        }
        if (weightSum.signum() <= 0) throw new IllegalArgumentException("WEIGHTS_INVALID");

        List<BigDecimal> payables = new ArrayList<>(items.size());
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            BigDecimal share = total.multiply(weights.get(i)).divide(weightSum, 2, RoundingMode.DOWN);
            payables.add(share);
            allocated = allocated.add(share);
        }
        payables.set(0, payables.get(0).add(total.subtract(allocated)));

        int validityKind = grantValidityKind(product.getValidityKind());
        int validDays = product.getValidDays() == null ? 0 : product.getValidDays();
        boolean explore = product.occupiesExploreCapacity();

        List<GrantLine> lines = new ArrayList<>(items.size());
        BigDecimal payableSum = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            MarketingProductItem item = items.get(i);
            BigDecimal list = listPrices.get(i) == null ? BigDecimal.ZERO : listPrices.get(i);
            BigDecimal payable = payables.get(i);
            if (payable.signum() < 0) throw new IllegalStateException("ALLOCATION_NEGATIVE");
            if (list.signum() > 0 && payable.compareTo(list) > 0) {
                throw new IllegalStateException("ALLOCATION_EXCEEDS_LIST");
            }
            GrantLine line = new GrantLine();
            line.topicId = item.getTopicId();
            line.listAmount = list;
            line.payableAmount = payable;
            line.validityKind = validityKind;
            line.validDays = validDays;
            line.fixedValidFrom = product.getFixedValidFrom();
            line.fixedValidTo = product.getFixedValidTo();
            line.exploreCapacity = explore;
            lines.add(line);
            payableSum = payableSum.add(payable);
        }
        if (payableSum.compareTo(total) != 0) throw new IllegalStateException("ALLOCATION_NOT_CONSERVED");
        return lines;
    }

    /** M3 商品有效期语义 → M2-F GrantLine 语义（首次使用型用 -1）。 */
    public static int grantValidityKind(Integer productValidityKind) {
        if (productValidityKind == null) return GrantLine.VALIDITY_NONE;
        return switch (productValidityKind) {
            case MarketingProduct.VALIDITY_RELATIVE_DAYS -> GrantLine.VALIDITY_RELATIVE_DAYS;
            case MarketingProduct.VALIDITY_FIXED_WINDOW -> GrantLine.VALIDITY_FIXED_WINDOW;
            case MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE -> GrantLine.VALIDITY_ACTIVATE_ON_FIRST_USE;
            default -> GrantLine.VALIDITY_NONE;
        };
    }
}
