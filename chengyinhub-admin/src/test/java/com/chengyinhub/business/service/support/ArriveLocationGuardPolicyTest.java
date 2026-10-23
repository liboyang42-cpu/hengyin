package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.support.ArriveLocationGuardPolicy.Decision;
import com.chengyinhub.business.service.support.ArriveLocationGuardPolicy.Fix;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O-01 第 4–7 层纯判定:节流 5s、速度 8m/s、60 分钟 40km、不确定性扣减。 */
class ArriveLocationGuardPolicyTest
{
    private static final long T0 = 1_800_000_000_000L;
    private static final double LAT = 31.2304;
    private static final double LNG = 121.4737;
    /** 纬度 1 米对应的度数 */
    private static final double M = 1d / 111_195d;

    private static Fix at(double northMeters, long atMillis, Double acc)
    {
        return new Fix(LAT + northMeters * M, LNG, acc, atMillis);
    }

    @Test
    void firstArrivalWithoutHistoryPasses()
    {
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(0, T0, 10d), null, null, null).pass());
    }

    @Test
    void secondEvaluationWithinFiveSecondsIsThrottled()
    {
        Fix prev = at(0, T0, 10d);
        Decision d = ArriveLocationGuardPolicy.evaluate(at(0, T0 + 4_999L, 10d), prev, prev, List.of(prev));
        assertEquals(ArriveLocationGuardPolicy.THROTTLE, d.reasonCode());
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(0, T0 + 5_000L, 10d), prev, prev, List.of(prev)).pass());
    }

    @Test
    void walkingPaceBetweenStationsPasses()
    {
        // 600m 用 3 分钟 ≈ 3.3 m/s
        Fix prev = at(0, T0, 10d);
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(600, T0 + 180_000L, 10d), prev, prev, List.of(prev)).pass());
    }

    @Test
    void teleportAcrossTownIsRejectedAsSpeed()
    {
        // 3km 用 60 秒:扣掉两端 (50+10)m 后仍 ≈ 48 m/s
        Fix prev = at(0, T0, 10d);
        Decision d = ArriveLocationGuardPolicy.evaluate(at(3_000, T0 + 60_000L, 10d), prev, prev, List.of(prev));
        assertEquals(ArriveLocationGuardPolicy.SPEED, d.reasonCode());
        assertTrue(d.detail().contains("speedMps="), d.detail());
    }

    @Test
    void speedBoundaryUsesConservativeDisplacement()
    {
        // 站点相距 920m,两端精度 10m ⇒ 保守位移 = 920 − 2×(50+10) = 800m;100s ⇒ 恰 8 m/s 放行
        Fix prev = at(0, T0, 10d);
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(920, T0 + 100_000L, 10d), prev, prev, List.of(prev)).pass());
        // 同距离 99s ⇒ 8.08 m/s 拒绝
        assertEquals(ArriveLocationGuardPolicy.SPEED,
                ArriveLocationGuardPolicy.evaluate(at(920, T0 + 99_000L, 10d), prev, prev, List.of(prev)).reasonCode());
    }

    @Test
    void missingOrCoarseAccuracyOnlyWidensTheJudgement()
    {
        // 站点相距 300m、30 秒:精度好时保守位移 300−120=180m ⇒ 6 m/s 放行;
        // 精度缺失按 80m 计 ⇒ 300−260=40m,更宽,不会因为「没报精度/定位粗」被判作弊
        Fix prevGood = at(0, T0, 10d);
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(300, T0 + 30_000L, 10d), prevGood, prevGood, null).pass());
        Fix prevMissing = at(0, T0, null);
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(300, T0 + 30_000L, null), prevMissing, prevMissing, null).pass());
        // 同一跳若站点相距 520m:精度好 ⇒ (520−120)/30=13.3 拒;精度缺失 ⇒ (520−260)/30=8.67 仍拒但更接近阈值
        assertEquals(ArriveLocationGuardPolicy.SPEED, ArriveLocationGuardPolicy.evaluate(
                at(520, T0 + 30_000L, 10d), prevGood, prevGood, null).reasonCode());
        double good = ArriveLocationGuardPolicy.conservativeDisplacement(prevGood, at(520, T0, 10d));
        double missing = ArriveLocationGuardPolicy.conservativeDisplacement(prevMissing, at(520, T0, null));
        assertTrue(missing < good);
    }

    @Test
    void sameStationRepeatedIsNeverSpeeding()
    {
        Fix prev = at(0, T0, 5d);
        assertTrue(ArriveLocationGuardPolicy.evaluate(at(0, T0 + 6_000L, 5d), prev, prev, List.of(prev)).pass());
        assertEquals(0d, ArriveLocationGuardPolicy.conservativeDisplacement(prev, at(80, T0, 5d)), 0d);
    }

    @Test
    void windowRejectsMoreThanFortyKilometresInSixtyMinutes()
    {
        // 第 5 层只看上一跳;这里把速度锚点设成很久以前(速度层放行),单独钉窗口层:7 跳 × (6km−100m) = 41.3km
        List<Fix> chain = new ArrayList<>();
        for (int i = 0; i <= 6; i++) chain.add(at(i * 6_000d, T0 + i * 8 * 60_000L, 0d));
        Fix current = at(7 * 6_000d, T0 + 58 * 60_000L, 0d);
        Fix ancient = at(7 * 6_000d, T0 - 10 * 3_600_000L, 0d);
        Decision d = ArriveLocationGuardPolicy.evaluate(current, ancient, ancient, chain);
        assertEquals(ArriveLocationGuardPolicy.WINDOW_DISTANCE, d.reasonCode());
    }

    @Test
    void windowIgnoresFixesOlderThanSixtyMinutes()
    {
        List<Fix> chain = new ArrayList<>();
        chain.add(at(0, T0, 0d));                       // 61 分钟前,不计
        chain.add(at(45_000, T0 + 30 * 60_000L, 0d));   // 窗口内第一跳的起点
        Fix current = at(45_100, T0 + 61 * 60_000L, 0d);
        Fix ancient = at(45_100, T0 - 3_600_000L, 0d);
        assertTrue(ArriveLocationGuardPolicy.evaluate(current, ancient, ancient, chain).pass());
    }

    @Test
    void uncertaintyIsGeofencePlusAccuracyWithMissingAsEightyMeters()
    {
        assertEquals(60d, ArriveLocationGuardPolicy.uncertaintyMeters(at(0, T0, 10d)), 0d);
        assertEquals(130d, ArriveLocationGuardPolicy.uncertaintyMeters(at(0, T0, null)), 0d);
        assertEquals(130d, ArriveLocationGuardPolicy.uncertaintyMeters(at(0, T0, Double.NaN)), 0d);
    }

    @Test
    void displacementUsesSharedUtility()
    {
        Fix a = at(0, T0, 0d);
        Fix b = at(1_000, T0, 0d);
        double expected = GeoDistanceUtils.haversineMeters(a.nodeLat(), a.nodeLng(), b.nodeLat(), b.nodeLng()) - 100d;
        assertEquals(expected, ArriveLocationGuardPolicy.conservativeDisplacement(a, b), 1e-9);
        assertNull(ArriveLocationGuardPolicy.evaluate(b, null, null, Collections.emptyList()).reasonCode());
    }
}
