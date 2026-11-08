package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.service.support.MarketingAllocation;
import com.chengyinhub.business.service.GrantLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分摊守恒 + kind→PurchaseKind 映射（M3 提供给 M2-F 的唯一输入）。 */
class MarketingAllocationTest {

    private static final long PRODUCT = 10L;

    @Test
    void allocationConservesTotalAndKeepsRealShares() {
        MarketingProduct product = product();
        List<MarketingProductItem> items = Arrays.asList(item(100L), item(101L), item(102L));
        List<BigDecimal> listPrices = Arrays.asList(
                new BigDecimal("19.99"), new BigDecimal("19.99"), new BigDecimal("19.99"));

        List<GrantLine> lines = MarketingAllocation.lines(
                product, items, listPrices, new BigDecimal("39.99"));

        assertEquals(3, lines.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (GrantLine line : lines) {
            sum = sum.add(line.payableAmount);
            assertTrue(line.payableAmount.signum() >= 0);
            assertTrue(line.payableAmount.compareTo(line.listAmount) <= 0);
            assertFalse(line.exploreCapacity);
        }
        assertEquals(0, sum.compareTo(new BigDecimal("39.99")));
    }

    @Test
    void exploreTicketsOnlyRowCarriesCapacityFlag() {
        MarketingProduct product = product();
        product.setPurchaseKind(3);
        product.setExploreCapacity(1);
        List<MarketingProductItem> items = Arrays.asList(item(100L));
        List<BigDecimal> prices = Arrays.asList(new BigDecimal("19.99"));

        List<GrantLine> lines = MarketingAllocation.lines(
                product, items, prices, new BigDecimal("19.99"));

        assertEquals(GrantLine.VALIDITY_RELATIVE_DAYS, lines.get(0).validityKind);
        assertTrue(lines.get(0).exploreCapacity);
    }

    @Test
    void allocationRejectsNonConservingOrOverListAmounts() {
        MarketingProduct product = product();
        List<MarketingProductItem> items = Arrays.asList(item(100L));
        List<BigDecimal> prices = Arrays.asList(new BigDecimal("10.00"));

        assertThrows(IllegalStateException.class, () -> MarketingAllocation.lines(
                product, items, prices, new BigDecimal("39.99")));
        assertThrows(IllegalArgumentException.class, () -> MarketingAllocation.lines(
                product, items, prices, BigDecimal.ZERO));
    }

    @Test
    void negativeOrMissingPricesFallBackToUnitWeight() {
        MarketingProduct product = product();
        List<MarketingProductItem> items = Arrays.asList(item(100L), item(101L));
        List<BigDecimal> prices = Arrays.asList(null, BigDecimal.ZERO);

        List<GrantLine> lines = MarketingAllocation.lines(
                product, items, prices, new BigDecimal("10.00"));

        assertEquals(0, lines.get(0).payableAmount.add(lines.get(1).payableAmount)
                .compareTo(new BigDecimal("10.00")));
    }

    private MarketingProduct product() {
        MarketingProduct product = new MarketingProduct();
        product.setId(PRODUCT);
        product.setProductKind(MarketingProduct.KIND_CITY_BUNDLE);
        product.setPurchaseKind(2);
        product.setExploreCapacity(0);
        product.setValidityKind(MarketingProduct.VALIDITY_RELATIVE_DAYS);
        product.setValidDays(7);
        return product;
    }

    private MarketingProductItem item(long topicId) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(PRODUCT);
        item.setTopicId(topicId);
        item.setContentRevision(5L);
        return item;
    }
}
