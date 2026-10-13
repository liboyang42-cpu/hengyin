package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import static com.chengyinhub.web.controller.api.ApiCityNodeController.answerMatches;
import static com.chengyinhub.web.controller.api.ApiCityNodeController.distMeters;
import static com.chengyinhub.web.controller.api.ApiCityNodeController.withinReach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-B3 领券链纯逻辑:到店半径二次校验 + 玩法答案判定。
 * (uk_poi_member 幂等在真 MySQL 数据层验,见 P1-B5)
 */
class CityNodeCompleteTest {

    @Test
    void distMeters_knownDistances() {
        assertEquals(0.0, distMeters(31.23, 121.47, 31.23, 121.47), 0.001, "同点=0");
        // 0.001° 纬度 ≈ 111.32m
        double d = distMeters(31.230, 121.470, 31.231, 121.470);
        assertTrue(d > 105 && d < 118, "0.001°纬度应≈111m,实得 " + d);
    }

    @Test
    void withinReach_boundary() {
        assertTrue(withinReach(80, 80), "正好在半径内");
        assertTrue(withinReach(0, 80), "站在点上");
        assertTrue(withinReach(110, 80), "半径80+容差30=110 内可达");
        assertFalse(withinReach(111, 80), "超出半径+容差 → 太远");
        assertFalse(withinReach(3000, 80), "远在天边");
    }

    @Test
    void answerMatches_trimAndCase() {
        assertTrue(answerMatches("暗号A", " 暗号A "), "去首尾空白");
        assertTrue(answerMatches("Coffee", "coffee"), "忽略大小写");
        assertTrue(answerMatches("B", "b"), "选项忽略大小写");
    }

    @Test
    void answerMatches_rejects() {
        assertFalse(answerMatches("A", "B"), "答案不同");
        assertFalse(answerMatches(null, "A"), "期望为空不匹配");
        assertFalse(answerMatches("A", null), "提交为空不匹配");
        assertFalse(answerMatches("暗号A", "暗号"), "部分匹配不算");
    }
}
