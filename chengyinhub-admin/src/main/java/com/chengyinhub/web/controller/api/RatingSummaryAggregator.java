package com.chengyinhub.web.controller.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.common.core.text.Convert;

/**
 * 评分聚合结果的类型安全装配。
 *
 * ★ 为什么必须存在:{@code UmsCommentMapper.selectRatingSummaryByOwners} 的 SQL 是
 * {@code ROUND(AVG(rating), 1) AS avg_rating} —— MySQL 对整型列求 AVG 返回的是 DECIMAL,
 * JDBC 映射成 {@link java.math.BigDecimal},直接 {@code (Double)} 强转必抛
 * {@code ClassCastException}。而这段循环体在 5 个列表接口里逐字复制过 ——
 * 修一处不等于修好,所以收敛到这里,由 {@link Convert} 按 Number 语义取值。
 *
 * ⚠️ 这是哑弹型缺陷:被查的 owner 一条评论都没有时循环体不执行,接口看起来完全正常;
 * 只要有人留下第一条评价就整片 500。2026-08-10 生产实测 326 次。
 */
final class RatingSummaryAggregator {

    /** 平均分:owner_id -> avg_rating。 */
    final Map<Long, Double> average;

    /** 评价条数:owner_id -> rating_total。 */
    final Map<Long, Integer> total;

    private RatingSummaryAggregator(Map<Long, Double> average, Map<Long, Integer> total) {
        this.average = average;
        this.total = total;
    }

    /**
     * @param rows mapper 返回的原始行;null 或空一律得到空 Map,调用方不必判空。
     */
    static RatingSummaryAggregator from(List<Map<String, Object>> rows) {
        Map<Long, Double> average = new HashMap<Long, Double>();
        Map<Long, Integer> total = new HashMap<Long, Integer>();
        if (rows == null || rows.isEmpty()) {
            return new RatingSummaryAggregator(average, total);
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Long ownerId = Convert.toLong(row.get("owner_id"), null);
            if (ownerId == null) {
                continue;
            }
            average.put(ownerId, Convert.toDouble(row.get("avg_rating"), Double.valueOf(0.0D)));
            total.put(ownerId, Convert.toInt(row.get("rating_total"), Integer.valueOf(0)));
        }
        return new RatingSummaryAggregator(
                Collections.unmodifiableMap(average), Collections.unmodifiableMap(total));
    }
}
