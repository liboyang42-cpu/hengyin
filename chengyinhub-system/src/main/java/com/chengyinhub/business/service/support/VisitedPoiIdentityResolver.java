package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MemberVisitedPoi;
import com.chengyinhub.business.mapper.MemberVisitedPoiMapper;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.chengyinhub.system.service.ISysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * O-02 点亮城市「点到店」非参与地点的稳定身份,替代「3 位小数坐标 = 身份」(geo_key)。
 *
 * <p>微信 bindpoitap 只给 name + 坐标,没有可信的 POI id,所以身份由服务端决定:
 * <ol>
 *   <li>被点坐标 50m 内有上架地标(roam_poi.type=1)→ 身份 = poi:{roam_poi.id};</li>
 *   <li>否则与本人已有到点记录比对:5m 内,或 80m 内且同名(非「未知地点」)= 同一地点。
 *       80m 覆盖 3 位小数取整的最大偏差(约 0.0005° × √2),同一点不同精度只算一次;</li>
 *   <li>都没有 → 新地点,poi_key = pt:{5 位小数}(仅作唯一标签,身份由第 2 步的距离比对承担)。</li>
 * </ol>
 *
 * <p>开关 {@value #CONFIG_STABLE_IDENTITY} 默认 false = 旧 geo_key 路径一字不变;
 * 打开前必须先落 poi_key 列迁移与回填迁移。并发:调用方 /api/roam/checkin 已在同一事务里由
 * RoamLocationConsentService#requireCurrentAgreement 锁住玩家行,同一玩家的到点串行。
 */
@Component
public class VisitedPoiIdentityResolver
{
    public static final String CONFIG_STABLE_IDENTITY = "roam.visited_poi.stable_identity";

    static final double LANDMARK_MATCH_RADIUS_M = 50d;
    static final double SAME_POINT_RADIUS_M = 5d;
    static final double SAME_NAME_RADIUS_M = 80d;
    static final String UNKNOWN_NAME = "未知地点";

    private static final Logger logger = LoggerFactory.getLogger(VisitedPoiIdentityResolver.class);

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private MemberVisitedPoiMapper visitedPoiMapper;

    public boolean enabled()
    {
        try {
            String value = configService.selectConfigByKey(CONFIG_STABLE_IDENTITY);
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (RuntimeException e) {
            logger.error("点到店稳定身份开关读取失败,按旧 geo_key 路径处理", e);
            return false;
        }
    }

    /** 记录一次到点。返回 true = 首次点亮(发探索值),false = 已点亮过。 */
    public boolean recordVisit(Long memberId, double lat, double lng, String poiName, Date visitTime)
    {
        String poiKey = null;
        Long landmarkId = nearestLandmark(lat, lng);
        if (landmarkId != null) {
            poiKey = "poi:" + landmarkId;
            if (visitedPoiMapper.countByPoiKey(memberId, poiKey) > 0) return false;
        }
        double[] box = box(lat, lng, SAME_NAME_RADIUS_M);
        List<MemberVisitedPoi> nearby = visitedPoiMapper.selectMemberVisitsInBox(memberId,
                bd(box[0]), bd(box[1]), bd(box[2]), bd(box[3]));
        if (matchesExistingVisit(nearby, lat, lng, poiName)) return false;
        if (poiKey == null) poiKey = pointKey(lat, lng);
        return visitedPoiMapper.insertVisitedPoiWithKeyIgnore(memberId, poiKey, poiName,
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), visitTime) > 0;
    }

    private Long nearestLandmark(double lat, double lng)
    {
        double[] box = box(lat, lng, LANDMARK_MATCH_RADIUS_M);
        List<MemberVisitedPoi> pois = visitedPoiMapper.selectLandmarkPoisInBox(
                bd(box[0]), bd(box[1]), bd(box[2]), bd(box[3]));
        return nearestWithin(pois, lat, lng, LANDMARK_MATCH_RADIUS_M);
    }

    /** rows 中距 (lat,lng) 不超过 maxM 的最近一行 id;同距取小 id,无则 null。 */
    static Long nearestWithin(List<MemberVisitedPoi> rows, double lat, double lng, double maxM)
    {
        if (rows == null) return null;
        Long best = null;
        double bestD = Double.MAX_VALUE;
        for (MemberVisitedPoi row : rows) {
            if (row == null || row.getId() == null || row.getLat() == null || row.getLng() == null) continue;
            double d = GeoDistanceUtils.haversineMeters(lat, lng, row.getLat().doubleValue(), row.getLng().doubleValue());
            if (!(d <= maxM)) continue;
            if (d < bestD || (d == bestD && row.getId() < best)) {
                bestD = d;
                best = row.getId();
            }
        }
        return best;
    }

    /** 已有记录中是否有「同一地点」:5m 内任意名,或 80m 内同名(未知地点不按名合并)。 */
    static boolean matchesExistingVisit(List<MemberVisitedPoi> visits, double lat, double lng, String poiName)
    {
        if (visits == null) return false;
        boolean named = poiName != null && !poiName.isEmpty() && !UNKNOWN_NAME.equals(poiName);
        for (MemberVisitedPoi v : visits) {
            if (v == null || v.getLat() == null || v.getLng() == null) continue;
            double d = GeoDistanceUtils.haversineMeters(lat, lng, v.getLat().doubleValue(), v.getLng().doubleValue());
            if (d <= SAME_POINT_RADIUS_M) return true;
            if (named && d <= SAME_NAME_RADIUS_M && Objects.equals(poiName, v.getPoiName())) return true;
        }
        return false;
    }

    static String pointKey(double lat, double lng)
    {
        return String.format(Locale.US, "pt:%.5f,%.5f", lat, lng);
    }

    /** [minLat, maxLat, minLng, maxLng],与 /checkin 参与据点粗筛同一换算。 */
    static double[] box(double lat, double lng, double radiusM)
    {
        double dLat = radiusM / 111320.0;
        double dLng = radiusM / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        return new double[] { lat - dLat, lat + dLat, lng - dLng, lng + dLng };
    }

    private static BigDecimal bd(double v)
    {
        return BigDecimal.valueOf(v);
    }
}
