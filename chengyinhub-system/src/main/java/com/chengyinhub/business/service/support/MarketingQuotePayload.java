package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** 报价签名载荷；报价与下单共用同一顺序，任一项变化签名即失效。 */
public final class MarketingQuotePayload {

    private MarketingQuotePayload() { }

    public static String of(long memberId, MarketingProduct product, int fulfillmentKind, int quantity,
                            Long originalOrderId, Long recipientMemberId,
                            BigDecimal totalCash, Integer totalPoints, int seatTotal,
                            List<MarketingProductItem> items, long expiresAtMillis) {
        List<String> itemParts = new ArrayList<>();
        if (items != null) {
            List<MarketingProductItem> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparing(MarketingProductItem::getTopicId));
            for (MarketingProductItem item : sorted) {
                itemParts.add(item.getTopicId() + ":" + item.getContentRevision());
            }
        }
        return MarketingQuoteSigner.canonical(
                String.valueOf(memberId),
                String.valueOf(product.getId()),
                String.valueOf(product.getRevision()),
                String.valueOf(product.getProductKind()),
                String.valueOf(fulfillmentKind),
                String.valueOf(quantity),
                originalOrderId == null ? null : String.valueOf(originalOrderId),
                recipientMemberId == null ? null : String.valueOf(recipientMemberId),
                plain(product.getCashPrice()),
                plain(product.getPointsPrice()),
                plain(totalCash),
                plain(totalPoints),
                String.valueOf(product.getValidityKind()),
                plain(product.getValidDays()),
                millis(product.getFixedValidFrom()),
                millis(product.getFixedValidTo()),
                String.valueOf(seatTotal),
                product.getTermsVersion(),
                String.join(",", itemParts),
                String.valueOf(expiresAtMillis));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String plain(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String millis(Date value) {
        return value == null ? null : String.valueOf(value.getTime());
    }

    public static int seatTotal(MarketingProduct product, int quantity) {
        int per = product.getSeatCount() == null ? 1 : product.getSeatCount();
        return Math.multiplyExact(per, quantity);
    }

    public static BigDecimal cashTotal(BigDecimal unit, int quantity) {
        return unit == null ? null : unit.multiply(BigDecimal.valueOf(quantity));
    }

    public static Integer pointsTotal(Integer unit, int quantity) {
        if (unit == null) return null;
        long total = (long) unit * quantity;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("POINTS_AMOUNT_TOO_LARGE");
        return (int) total;
    }
}
