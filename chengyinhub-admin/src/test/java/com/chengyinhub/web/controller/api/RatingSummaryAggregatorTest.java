package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回归:2026-08-10 生产 500(326 次)——{@code ROUND(AVG(rating),1)} 走 JDBC 回来是 BigDecimal,
 * 旧码 {@code (Double) row.getOrDefault("avg_rating", 0.0)} 直接强转,ClassCastException。
 *
 * ⚠️ 断言的是「BigDecimal 这种真实驱动类型能被正确取值」这个语义,不是某个字面数字;
 * 把 {@code Convert.toDouble} 换回 {@code (Double)} 强转,第一个用例必须红。
 */
public class RatingSummaryAggregatorTest {

    private static Map<String, Object> row(Object ownerId, Object avgRating, Object ratingTotal) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("owner_id", ownerId);
        row.put("avg_rating", avgRating);
        row.put("rating_total", ratingTotal);
        return row;
    }

    @Test
    @DisplayName("MySQL ROUND(AVG(int)) 回来的 BigDecimal 必须能取到平均分，而不是抛 ClassCastException")
    public void bigDecimalAverageIsAccepted() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        // 驱动真实给的形状:AVG -> BigDecimal、COUNT -> Long、owner_id -> Long
        rows.add(row(Long.valueOf(7L), new BigDecimal("4.7"), Long.valueOf(30L)));

        RatingSummaryAggregator summary = RatingSummaryAggregator.from(rows);

        assertEquals(Double.valueOf(4.7D), summary.average.get(Long.valueOf(7L)),
                "BigDecimal 平均分必须按数值取到");
        assertEquals(Integer.valueOf(30), summary.total.get(Long.valueOf(7L)),
                "COUNT 必须按数值取到");
    }

    @Test
    @DisplayName("owner_id 为 null 的行跳过，不得污染结果也不得抛异常")
    public void nullOwnerRowsAreSkipped() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row(null, new BigDecimal("3.0"), Long.valueOf(1L)));
        rows.add(null);

        RatingSummaryAggregator summary = RatingSummaryAggregator.from(rows);

        assertTrue(summary.average.isEmpty(), "无 owner_id 的行不得进结果");
        assertTrue(summary.total.isEmpty(), "无 owner_id 的行不得进结果");
    }

    @Test
    @DisplayName("没有任何评论时得到空 Map —— 这正是缺陷此前藏了 11 天没被发现的原因")
    public void emptyRowsYieldEmptyMaps() {
        assertTrue(RatingSummaryAggregator.from(null).average.isEmpty());
        assertTrue(RatingSummaryAggregator.from(new ArrayList<Map<String, Object>>()).total.isEmpty());
    }

    @Test
    @DisplayName("avg_rating 缺失时回落 0.0，不得抛 NPE")
    public void missingAverageFallsBackToZero() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> partial = new HashMap<String, Object>();
        partial.put("owner_id", Long.valueOf(9L));
        rows.add(partial);

        RatingSummaryAggregator summary = RatingSummaryAggregator.from(rows);

        assertEquals(Double.valueOf(0.0D), summary.average.get(Long.valueOf(9L)));
        assertEquals(Integer.valueOf(0), summary.total.get(Long.valueOf(9L)));
    }
}
