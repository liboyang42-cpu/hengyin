package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.GeoDistanceUtils;

import java.util.List;
import java.util.Locale;

/**
 * O-01 /api/play/arrive 位置守卫的纯判定(无 IO,单测友好)。调用方 {@link ArriveLocationGuard}。
 *
 * <p>「七层」与现有代码的对应(前三层是既有逻辑,一字未改,仍在 ApiPlayProgressController#arrive):
 * <ol>
 *   <li>坐标完整且合法(缺坐标 = 请开启定位;NaN/越界 = 定位无效)—— 既有</li>
 *   <li>精度闸:上报精度 &gt; 80m 拒(请到开阔处或扫码);空精度按开关 play.arrive.require_accuracy —— 既有 + 新开关</li>
 *   <li>50m 围栏 —— 既有</li>
 *   <li>节流:与上一次被评估的到达间隔 &lt; 5s 拒({@link #THROTTLE},可重试,不算作弊)</li>
 *   <li>速度:与上一次放行的到达之间,保守位移 / 时间差 &gt; 8 m/s 拒({@link #SPEED},可申诉)</li>
 *   <li>窗口:近 60 分钟放行链 + 本次的保守位移累计 &gt; 40 km 拒({@link #WINDOW_DISTANCE},可申诉)</li>
 *   <li>位移不确定性扣减:第 5、6 层用的都是「保守位移」= 两站站点距离 − 两端不确定半径
 *       (围栏 50m + 上报精度;精度缺失按 80m 计)。粗定位 / 旧版不报精度只会让判定更宽,
 *       不会因为「定位粗」本身被判作弊。</li>
 * </ol>
 *
 * <p>只用服务端站点坐标做位移,不用玩家上报坐标:玩家位置已被围栏约束在站点 50m 内,
 * 用站点坐标既能判定速度,又不必落库玩家原始轨迹。
 */
public final class ArriveLocationGuardPolicy
{
    public static final String THROTTLE = "ARRIVE_GUARD_THROTTLE";
    public static final String SPEED = "ARRIVE_GUARD_SPEED";
    public static final String WINDOW_DISTANCE = "ARRIVE_GUARD_WINDOW_DISTANCE";

    /** 第 4 层:两次到达评估的最小间隔(毫秒) */
    public static final long THROTTLE_MILLIS = 5_000L;
    /** 第 5 层:速度上限(米/秒) */
    public static final double MAX_SPEED_MPS = 8d;
    /** 第 6 层:滚动窗口长度(毫秒) */
    public static final long WINDOW_MILLIS = 60L * 60L * 1000L;
    /** 第 6 层:窗口内保守位移累计上限(米) */
    public static final double WINDOW_MAX_METERS = 40_000d;
    /** 第 7 层:围栏半径(米),与 ApiPlayProgressController.GEOFENCE_METERS 同值 */
    public static final double GEOFENCE_METERS = 50d;
    /** 第 7 层:精度缺失时按精度闸上限计(米),宁宽勿误判 */
    public static final double MISSING_ACCURACY_METERS = 80d;

    /** 一次到达:站点坐标、上报精度(可空)、服务端时间。 */
    public record Fix(double nodeLat, double nodeLng, Double accuracyM, long atMillis) {}

    /** reasonCode 为 null = 放行。 */
    public record Decision(String reasonCode, String detail)
    {
        public boolean pass()
        {
            return reasonCode == null;
        }
    }

    private static final Decision PASS = new Decision(null, null);

    private ArriveLocationGuardPolicy()
    {
    }

    /**
     * @param current       本次到达
     * @param lastEvaluated 上一次被评估的到达(节流参照,不含节流拒绝本身);可空
     * @param lastAccepted  上一次放行的到达(速度锚点);可空
     * @param acceptedAsc   近 60 分钟内放行的到达,按时间升序;可空
     */
    public static Decision evaluate(Fix current, Fix lastEvaluated, Fix lastAccepted, List<Fix> acceptedAsc)
    {
        if (lastEvaluated != null) {
            long gap = current.atMillis() - lastEvaluated.atMillis();
            if (gap < THROTTLE_MILLIS) {
                return new Decision(THROTTLE, String.format(Locale.ROOT, "gapMs=%d", gap));
            }
        }
        if (lastAccepted != null) {
            double seconds = Math.max(1d, (current.atMillis() - lastAccepted.atMillis()) / 1000d);
            double meters = conservativeDisplacement(lastAccepted, current);
            double speed = meters / seconds;
            if (speed > MAX_SPEED_MPS) {
                return new Decision(SPEED, String.format(Locale.ROOT,
                        "minDisplacementM=%.0f elapsedS=%.0f speedMps=%.2f", meters, seconds, speed));
            }
        }
        if (acceptedAsc != null && !acceptedAsc.isEmpty()) {
            long since = current.atMillis() - WINDOW_MILLIS;
            double total = 0d;
            Fix prev = null;
            for (Fix f : acceptedAsc) {
                if (f == null || f.atMillis() < since) continue;
                if (prev != null) total += conservativeDisplacement(prev, f);
                prev = f;
            }
            if (prev != null) total += conservativeDisplacement(prev, current);
            if (total > WINDOW_MAX_METERS) {
                return new Decision(WINDOW_DISTANCE, String.format(Locale.ROOT,
                        "windowMinutes=%d minDisplacementM=%.0f", WINDOW_MILLIS / 60_000L, total));
            }
        }
        return PASS;
    }

    /** 第 7 层:两次到达之间玩家至少移动了多少米(站点距离扣掉两端不确定半径,不小于 0)。 */
    static double conservativeDisplacement(Fix a, Fix b)
    {
        double d = GeoDistanceUtils.haversineMeters(a.nodeLat(), a.nodeLng(), b.nodeLat(), b.nodeLng());
        if (!Double.isFinite(d)) return 0d;
        return Math.max(0d, d - uncertaintyMeters(a) - uncertaintyMeters(b));
    }

    static double uncertaintyMeters(Fix f)
    {
        Double acc = f.accuracyM();
        boolean known = acc != null && Double.isFinite(acc) && acc >= 0d;
        return GEOFENCE_METERS + (known ? acc : MISSING_ACCURACY_METERS);
    }
}
