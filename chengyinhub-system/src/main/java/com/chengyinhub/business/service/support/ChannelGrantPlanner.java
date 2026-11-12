package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.dto.ChannelGrantTopic;
import com.chengyinhub.business.exception.ChannelVoucherException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一次渠道实付按各主题分买价权重分摊到作品行。
 *
 * <p>约束（与 M2-F BRIDGE-CONTRACT §3 同口径）：
 * <ul>
 *   <li>Σ分摊 == 渠道实付（分不差），尾差归首行；</li>
 *   <li>每行分摊 ≥ 0，且不大于该主题分买价（无价主题不作为上限）；</li>
 *   <li>币种只接受与商品计价一致的 CNY；外币不做隐式汇率。</li>
 * </ul>
 * 尾差归首行是唯一允许的取整差，其余一律拒绝而不是静默。
 */
public final class ChannelGrantPlanner {

    private static final String BASE_CURRENCY = "CNY";

    private ChannelGrantPlanner() { }

    public static String requireBaseCurrency(String currency) {
        if (currency == null || !BASE_CURRENCY.equalsIgnoreCase(currency.trim())) {
            throw ChannelVoucherException.conflict("CURRENCY_UNSUPPORTED",
                    "渠道商品当前只接受人民币计价，外币订单需先完成汇率与责任合同");
        }
        return BASE_CURRENCY;
    }

    /**
     * @param items      商品绑定的作品（顺序即落单顺序）
     * @param weights    与 items 等长的分买价权重；null/≤0 视为 1，且不参与上限校验
     * @param totalAmount 渠道已验证实付（元，两位小数）
     */
    public static List<ChannelGrantTopic> plan(List<MarketingProductItem> items,
                                               List<BigDecimal> weights,
                                               BigDecimal totalAmount) {
        if (items == null || items.isEmpty()) {
            throw ChannelVoucherException.conflict("PRODUCT_UNAVAILABLE", "商品未绑定任何作品");
        }
        if (weights == null || weights.size() != items.size()) {
            throw new IllegalArgumentException("CHANNEL_ALLOCATION_WEIGHTS_MISMATCH");
        }
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw ChannelVoucherException.conflict("AMOUNT_INVALID", "渠道实付金额无效");
        }
        BigDecimal total = totalAmount.setScale(2, RoundingMode.HALF_UP);

        BigDecimal weightSum = BigDecimal.ZERO;
        List<BigDecimal> usableWeights = new ArrayList<>(items.size());
        for (BigDecimal weight : weights) {
            BigDecimal usable = weight == null || weight.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ONE : weight;
            usableWeights.add(usable);
            weightSum = weightSum.add(usable);
        }

        List<ChannelGrantTopic> lines = new ArrayList<>(items.size());
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            MarketingProductItem item = items.get(i);
            BigDecimal share = total.multiply(usableWeights.get(i))
                    .divide(weightSum, 2, RoundingMode.HALF_UP);
            BigDecimal weightPrice = weights.get(i);
            if (weightPrice != null && weightPrice.compareTo(BigDecimal.ZERO) > 0
                    && share.compareTo(weightPrice) > 0) {
                throw ChannelVoucherException.conflict("ALLOCATION_EXCEEDS_LIST_PRICE",
                        "渠道实付分摊超过作品单价，不能按打包价落原订单");
            }
            ChannelGrantTopic line = new ChannelGrantTopic();
            line.setTopicId(item.getTopicId());
            line.setTopicTitle(item.getTopicTitle());
            line.setContentRevision(item.getContentRevision());
            line.setListAmount(weightPrice == null ? null : weightPrice.setScale(2, RoundingMode.HALF_UP));
            line.setAllocatedAmount(share);
            lines.add(line);
            allocated = allocated.add(share);
        }

        BigDecimal tail = total.subtract(allocated);
        if (tail.signum() != 0) {
            ChannelGrantTopic first = lines.get(0);
            BigDecimal adjusted = first.getAllocatedAmount().add(tail);
            if (adjusted.signum() < 0) {
                throw new IllegalStateException("CHANNEL_ALLOCATION_NEGATIVE");
            }
            BigDecimal firstListPrice = weights.get(0);
            if (firstListPrice != null && firstListPrice.compareTo(BigDecimal.ZERO) > 0
                    && adjusted.compareTo(firstListPrice) > 0) {
                throw ChannelVoucherException.conflict("ALLOCATION_EXCEEDS_LIST_PRICE",
                        "渠道实付分摊尾差超过作品单价");
            }
            first.setAllocatedAmount(adjusted);
        }
        return lines;
    }

    /**
     * 渠道商品不支持“首次使用型”：兑换时必须立即产出可玩准入行。
     * 这不是假永久授权，而是明确拒绝未获支持的档位。
     */
    public static void requireSupportedValidity(MarketingProduct product) {
        Integer kind = product.getValidityKind();
        if (kind != null && kind == MarketingProduct.VALIDITY_ACTIVATE_ON_FIRST_USE) {
            throw ChannelVoucherException.conflict("PRODUCT_VALIDITY_UNSUPPORTED",
                    "首次使用型商品暂不支持渠道兑换，需先接通激活回写");
        }
        if (kind != null && kind == MarketingProduct.VALIDITY_FIXED_WINDOW
                && (product.getFixedValidFrom() == null || product.getFixedValidTo() == null)) {
            throw ChannelVoucherException.conflict("PRODUCT_VALIDITY_UNSUPPORTED", "商品固定有效期配置不完整");
        }
        if (kind != null && kind == MarketingProduct.VALIDITY_RELATIVE_DAYS
                && (product.getValidDays() == null || product.getValidDays() <= 0)) {
            throw ChannelVoucherException.conflict("PRODUCT_VALIDITY_UNSUPPORTED", "商品有效期天数未配置");
        }
    }
}
