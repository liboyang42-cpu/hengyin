package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.domain.vo.NearbyRunnerVO;
import com.chengyinhub.business.domain.vo.RunnerShopVO;
import com.chengyinhub.business.domain.vo.ShopVisitorAvatarVO;
import com.chengyinhub.business.domain.vo.ShopVisitorCountVO;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.MemberVisitedPoiMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.IRoamExploreDayCandidateService;
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.service.RoamAiContentService;
import com.chengyinhub.business.service.impl.RoamSessionWriteService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 漫游 · roam(真 GPS 迷雾探索)。
 * 三接口:GET /pois(附近兴趣点)· POST /reveal(批量点亮格子,幂等)· POST /finish(结算发 XP)。
 *
 * 奖励契约(v3 §3):1 格=1 探索值(日限 200)/ POI 发现=20 探索值 + 里程碑勋章。
 * 双轨红线:XP 只走 IGrowthService.addExp → member_growth.exp_value,绝不入积分明细。
 */
@RestController
@Tag(name = "漫游")
@RequestMapping("/api/roam")
public class ApiRoamController extends BaseController {

    static final int DAILY_TILE_XP_LIMIT = 200;   // 1格=1XP,日限
    static final int[] TILE_MILESTONES = { 50, 200, 1000 };  // 版图里程碑勋章阈值

    /** 三店连亮的勋章 code。★ 阈值不进 code:改阈值=改 code 会让已发的章变孤儿
     *  (现有 ROAM_TILES_50/200/1000 就踩了这个坑)。阈值只活在 growth_badge.unlock_rule_json。 */
    static final String SHOP_STREAK_CODE = "ROAM_SHOP_STREAK";

    @Autowired
    private RoamMapper roamMapper;
    @Autowired private com.chengyinhub.business.service.impl.RoamLocationConsentService locationConsent;
    @Autowired
    private com.chengyinhub.business.service.IConsentRecordService consentRecordService;
    @Autowired
    private com.chengyinhub.business.service.impl.RoamPresenceRevokeAuditService presenceRevokeAuditService;
    @Autowired
    private com.chengyinhub.business.service.impl.AlarmService alarmService;
    @Autowired
    private com.chengyinhub.business.service.support.RoamRecoveryService roamRecoveryService;
    @Autowired
    private RoamSessionWriteService roamSessionWriteService;
    @Autowired
    private IGrowthService growthService;
    @Autowired
    private PlayerBadgeMapper playerBadgeMapper;
    @Autowired
    private MemberVisitedPoiMapper memberVisitedPoiMapper;
    /** O-02 点到店稳定身份;开关默认关,未注入时同样走旧 geo_key 路径 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.VisitedPoiIdentityResolver visitedPoiIdentity;
    @Autowired
    private GrowthBadgeMapper growthBadgeMapper;
    @Autowired
    private RuleEvaluator ruleEvaluator;
    @Autowired
    private CmsRegistrationMerchantMapper cmsRegistrationMerchantMapper;
    @Autowired
    private RoamAiContentService roamAiContentService;
    @Autowired
    private NpcFeatureFlags npcFeatureFlags;
    @Autowired
    private IRewardDropService rewardDropService;
    @Autowired
    private com.chengyinhub.business.service.impl.RoamRewardDeliveryService roamRewardDeliveryService;
    @Autowired
    private com.chengyinhub.business.service.impl.RoamBadgeDeliveryService roamBadgeDeliveryService;
    @Autowired
    private ISmsCouponHistoryService smsCouponHistoryService;
    @Autowired
    private IRoamExploreDayCandidateService roamExploreDayCandidateService;
    @Autowired
    private IRoamHangoutService roamHangoutService;

    /** 包级可见 + 非 final:测试用子类覆写它注入身份,好直调真入口(见 RoamPresenceControllerTest)。 */
    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    /** /pois 单次最多返回的点数(由近到远截断);地图一屏画不下更多,也不做整城导出口。 */
    static final int POIS_NEAR_LIMIT = 500;

    @Operation(summary = "附近 POI(经纬度包围盒粗筛)")
    @GetMapping("/pois")
    public AjaxResult pois(Double lat, Double lng, Integer radius) {
        if (lat == null || lng == null) return error("缺少定位");
        if (!validLat(lat) || !validLng(lng)) return error("定位无效");
        int r = (radius == null || radius <= 0) ? 3000 : Math.min(radius, 20000);
        double dLat = r / 111320.0;
        double dLng = r / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        // C-13:found 是「这个账号已发现过」的服务端事实(按会员投影),换设备/清缓存不再回到迷雾。
        List<RoamPoi> list = roamMapper.selectPoisNear(lat - dLat, lat + dLat, lng - dLng, lng + dLng, uid(), POIS_NEAR_LIMIT);
        return success(list);
    }

    @Operation(summary = "首次发现商家后的合规自由探索活动壳")
    @GetMapping("/nearby-exploreday")
    public AjaxResult nearbyExploreDay(@RequestParam("lat") Double lat, @RequestParam("lng") Double lng) {
        if (lat == null || lng == null) return error("缺少定位");
        if (!validLat(lat) || !validLng(lng)) return error("定位无效");
        return success(roamExploreDayCandidateService.findNearestEligible(lat, lng));
    }

    @Operation(summary = "漫游入口附近活动")
    @GetMapping("/entry/nearby")
    public AjaxResult entryNearby(Double lat, Double lng, Integer radius) {
        if (uid() <= 0) return error("请先登录");
        if (lat == null || lng == null) return error("缺少定位");
        if (!validLat(lat) || !validLng(lng)) return error("定位无效");
        return success(roamHangoutService.nearby(uid(), lat, lng, radius == null ? 0 : radius));
    }

    /** POI 发现校验半径(米);poi 自带 radius_m 优先,否则用此默认 */
    private static final int POI_DISCOVER_RADIUS_M = 120;
    /** GPS 抖动容差(米) */
    private static final int ROAM_GPS_TOLERANCE_M = 30;
    /** R6 反作弊:瞬移速度上限(m/s,~126km/h,城市探索足够宽松) */
    private static final double MAX_MOVE_SPEED_MPS = 35.0;
    /** R6 反作弊:小于此位移不做速度校验(防真实小范围移动误伤) */
    private static final int MIN_MOVE_DIST_M = 300;
    /** 点到店宽松 geo:玩家距被点 POI 上限(米) */
    private static final int CHECKIN_LOOSE_RADIUS_M = 200;
    /** 被点坐标就近匹配参与据点的半径(米) */
    private static final int NODE_MATCH_RADIUS_M = 50;
    /** 首次到点探索值 */
    private static final int VISIT_XP = 20;

    @Operation(summary = "发现 POI(R6:服务端校验到点,记录供结算发 XP)")
    @PostMapping("/poi/discover")
    public AjaxResult discoverPoi(String sessionId, Long poiId, Double lat, Double lng) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (poiId == null || poiId <= 0) return error("参数有误");
        if (lat == null || lng == null) return error("缺少定位");
        if (!validLat(lat) || !validLng(lng)) return error("定位无效");
        Long sid = Convert.toLong(sessionId, 0L);
        RoamSession session = sid > 0 ? roamMapper.selectSessionById(sid) : null;
        if (session == null || !memberId.equals(session.getMemberId())) return error("会话不存在");
        if (!Integer.valueOf(0).equals(session.getStatus())) return error("本次漫游已结算");
        // C-17:与 /pois 同一审核口径 —— 未过审的商家投放不可发现、不可入账。
        List<RoamPoi> pois = roamMapper.selectPlayerPoisByIds(java.util.Collections.singletonList(poiId));
        if (pois.isEmpty()) return error("地点不存在或已下线");
        RoamPoi poi = pois.get(0);
        if (poi.getLat() == null || poi.getLng() == null) return error("地点未设坐标");
        // R6:服务端 haversine 校验到点。伪造 poiId 但人不在附近 → 拒,不记录 → finish 不发该 POI XP
        double dist = GeoDistanceUtils.haversineMeters(lat, lng, poi.getLat().doubleValue(), poi.getLng().doubleValue());
        int radius = (poi.getRadiusM() != null && poi.getRadiusM() > 0) ? poi.getRadiusM() : POI_DISCOVER_RADIUS_M;
        if (dist > radius + ROAM_GPS_TOLERANCE_M) return error("还没到该地点附近");
        // R6 反作弊:与上一次到点做瞬移速度校验,挡"读 /pois 拿坐标秒发全城 POI"的伪造定位刷取
        java.util.Map<String, Object> last = roamMapper.selectLastPoiFoundLoc(memberId);
        if (last != null && last.get("foundAt") instanceof java.util.Date
                && last.get("lat") instanceof Number && last.get("lng") instanceof Number) {
            long dtSec = Math.max(1L, (System.currentTimeMillis() - ((java.util.Date) last.get("foundAt")).getTime()) / 1000L);
            double prevDist = GeoDistanceUtils.haversineMeters(((Number) last.get("lat")).doubleValue(), ((Number) last.get("lng")).doubleValue(),
                    poi.getLat().doubleValue(), poi.getLng().doubleValue());
            if (prevDist > MIN_MOVE_DIST_M && prevDist / dtSec > MAX_MOVE_SPEED_MPS) {
                return error("移动过快,疑似伪造定位");
            }
        }
        int inserted;
        try {
            inserted = roamSessionWriteService.recordPoiFound(memberId, sid, poiId);
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("discovered", inserted > 0);
        data.put("poiId", poiId);
        int poiXp = poi.getXp() != null ? poi.getXp() : 20;
        data.put("xp", poiXp);
        // L1:商户据点首次发现复用 M6 掉落引擎，但用独立 sceneKey 分流规则与流水幂等。
        if (Integer.valueOf(2).equals(poi.getType())) {
            try {
                Map<String, Object> drop = inserted > 0
                        ? roamRewardDeliveryService.rollOriginalDrop(memberId, poiId, sid, poiXp)
                        : rewardDropService.findOriginalDrop(memberId, 0L, poiId, sid, IRewardDropService.SCENE_ROAM_MERCHANT_POI);
                if (drop != null && (inserted > 0 || Integer.valueOf(3).equals(drop.get("dropType")))) {
                    applyRoamDrop(memberId, drop);
                    data.put("drop", drop);
                }
            } catch (Exception e) {
                logger.error("漫游据点掉落发放异常 member={} poi={},需人工核查", memberId, poiId, e);
            }
        }
        // 地点意义必须同时满足：总开关开启、服务器校验到点、非商户 POI、独立内容已审核。
        // 没有任何一个条件都不返回 meaning；绝不回退到 roam_poi.description。
        if (inserted > 0 && npcFeatureFlags.eventOn()) {
            try {
                String meaning = roamAiContentService.approvedPoiMeaning(poi);
                if (meaning != null) data.put("meaning", meaning);
            } catch (Exception e) {
                // 内容层故障只损失一句文字；服务器已确认的发现记录不能被它改写成失败。
                logger.warn("漫游地点意义不可用,本次仅保留发现结果 poiId={}, reason={}",
                        poiId, e.getClass().getSimpleName());
            }
        }
        return AjaxResult.success(inserted > 0 ? "发现新地点" : "已发现过", data);
    }

    /** 掉落引擎只决定规则与占位；场景调用方负责把奖励真实入账。 */
    private void applyRoamDrop(Long memberId, Map<String, Object> drop) {
        Object rawType = drop.get("dropType");
        int type = rawType instanceof Number ? ((Number) rawType).intValue() : 0;
        if (type == 1 && drop.get("extraXp") instanceof Number) {
            Object rawSource = drop.get("dropLogId");
            if (!(rawSource instanceof Number) || ((Number) rawSource).longValue() <= 0)
                throw new ServiceException("原掉落交付身份缺失");
            if (!roamRewardDeliveryService.deliverOriginal(memberId, ((Number) rawSource).longValue()))
                logger.warn("原漫游探索值交付待补 member={} dropLogId={}", memberId, rawSource);
        } else if (type == 3 && drop.get("couponTemplateId") instanceof Number) {
            long couponId = ((Number) drop.get("couponTemplateId")).longValue();
            Object rawSource = drop.get("dropLogId");
            if (!(rawSource instanceof Number) || ((Number) rawSource).longValue() <= 0)
                throw new ServiceException("原掉落交付身份缺失");
            if (couponId > 0 && !roamRewardDeliveryService.deliverOriginal(memberId, ((Number) rawSource).longValue()))
                logger.warn("原漫游掉落券交付待补 member={} dropLogId={}", memberId, rawSource);
        }
    }

    /** 探店入账的到点校验半径(米);比 POI_DISCOVER_RADIUS_M 略紧,探店是"进店"不是"路过" */
    private static final int SHOP_VISIT_RADIUS_M = 100;
    /** owner 2026-10-23 D7(U-112):探店隐含速度超过它只留痕(warn 日志),照常入账,不拒绝。 */
    static final double SHOP_VISIT_TELEPORT_WARN_MPS = 50.0;
    private static final org.slf4j.Logger TELEPORT_AUDIT_LOG =
            org.slf4j.LoggerFactory.getLogger(ApiRoamController.class.getName() + ".teleportAudit");

    @Operation(summary = "探店入账(任意真商家:roam 据点 / 报名商家),服务端校验到点")
    @PostMapping("/shop/visit")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult shopVisit(String sessionId, Integer sourceType, Long sourceId, Double lat, Double lng) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (sourceType == null || sourceId == null || sourceId <= 0) return error("参数有误");
        if (lat == null || lng == null) return error("缺少定位");
        if (!validLat(lat) || !validLng(lng)) return error("定位无效");
        locationConsent.requireCurrentAgreement(memberId);
        Long sid = Convert.toLong(sessionId, 0L);
        RoamSession session = sid > 0 ? roamMapper.selectOwnedSessionForUpdate(sid, memberId) : null;
        if (session == null || !memberId.equals(session.getMemberId())) return error("会话不存在");
        if (!Integer.valueOf(0).equals(session.getStatus())) return error("本次漫游已结算");

        // ★ 店名与坐标一律从来源表读,不采信客户端:
        //   坐标必须用库里的真坐标,否则伪造坐标可绕过到点校验;
        //   店名虽不参与计数(结算按 source 身份 distinct),但要落库供展示/审计,也从来源表读。
        String name;
        double shopLat, shopLng;
        if (sourceType == 1) {                       // roam_poi 据点
            // C-17:签到/入账同走玩家侧查询 —— 未过审的投放不能成为打卡或结算事实。
            List<RoamPoi> pois = roamMapper.selectPlayerPoisByIds(java.util.Collections.singletonList(sourceId));
            if (pois.isEmpty()) return error("地点不存在或已下线");
            RoamPoi poi = pois.get(0);
            if (poi.getLat() == null || poi.getLng() == null) return error("地点未设坐标");
            name = poi.getName();
            shopLat = poi.getLat().doubleValue();
            shopLng = poi.getLng().doubleValue();
        } else if (sourceType == 2) {                // cms_registration_merchant 报名商家
            CmsRegistrationMerchant m = cmsRegistrationMerchantMapper.selectCmsRegistrationMerchantById(sourceId);
            if (m == null || (m.getDelFlag() != null && m.getDelFlag() != 0)) return error("商家不存在");
            // ★ 只认中标(audit_status=1)的报名:待调配(0)/落选(2)的报名在玩法里根本不存在。
            //   不挡的话:userType=2 商家号可在自家坐标零审核自造 3 条报名(落库即 audit_status=0)凑满三店。
            if (m.getAuditStatus() == null || m.getAuditStatus() != 1L) {
                return error("该商家未参与漫游");
            }
            Double mLat = parseCoord(m.getLatitude(), -90d, 90d);
            Double mLng = parseCoord(m.getLongitude(), -180d, 180d);
            if (mLat == null || mLng == null) return error("商家未设坐标");
            name = m.getAddressName();
            shopLat = mLat;
            shopLng = mLng;
        } else {
            return error("来源有误");
        }
        // address_name 可空(建表 DEFAULT ''、DTO 无 @NotBlank)。无名店没法按店名收敛,直接拒:
        // 落库一个空名会把所有无名店在 count(distinct) 里塌成一家。
        if (name == null || name.trim().isEmpty()) return error("该商家未设名称,无法计入");

        if (GeoDistanceUtils.haversineMeters(lat, lng, shopLat, shopLng) > SHOP_VISIT_RADIUS_M + ROAM_GPS_TOLERANCE_M) {
            return error("还没到该店附近");
        }

        auditShopVisitSpeed(memberId, sid, sourceType, sourceId, shopLat, shopLng);
        roamMapper.recordAnonymousShopVisitor(memberId,sourceType,sourceId);
        int inserted = roamMapper.insertShopVisitIgnore(memberId, sid, sourceType, sourceId, name.trim(), new Date());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recorded", inserted > 0);
        data.put("shops", roamMapper.countDistinctShopsBySession(sid));
        return AjaxResult.success(inserted > 0 ? "已记入本次漫游" : "本次已记过", data);
    }

    /**
     * D7:只记不拦。以该 member 上一个已入账点(最近一次 POI 发现 / 探店,取较新者;坐标均为服务端来源坐标)
     * 到本店的直线距离 ÷ 时间差算隐含速度,超过 {@link #SHOP_VISIT_TELEPORT_WARN_MPS} 写一条结构化 warn。
     * 任何读取/解析异常都只跳过留痕,绝不影响入账。
     */
    private void auditShopVisitSpeed(Long memberId, Long sid, Integer sourceType, Long sourceId,
                                     double shopLat, double shopLng) {
        try {
            Object[] prev = latestAcceptedPoint(roamMapper.selectLastPoiFoundLoc(memberId), "foundAt", "POI_DISCOVER", null);
            prev = latestAcceptedPoint(roamMapper.selectLastShopVisitLoc(memberId), "visitedAt", "SHOP_VISIT", prev);
            if (prev == null) return;
            long elapsedSec = Math.max(1L, (System.currentTimeMillis() - (Long) prev[0]) / 1000L);
            double distanceM = GeoDistanceUtils.haversineMeters((Double) prev[1], (Double) prev[2], shopLat, shopLng);
            double speedMps = distanceM / elapsedSec;
            if (speedMps > SHOP_VISIT_TELEPORT_WARN_MPS) {
                TELEPORT_AUDIT_LOG.warn("event=ROAM_SHOP_VISIT_TELEPORT_SUSPECT action=ACCEPTED member={} session={}"
                                + " sourceType={} sourceId={} prevKind={} distanceM={} elapsedSec={} speedMps={} thresholdMps={}",
                        memberId, sid, sourceType, sourceId, prev[3], Math.round(distanceM), elapsedSec,
                        String.format(java.util.Locale.ROOT, "%.1f", speedMps), SHOP_VISIT_TELEPORT_WARN_MPS);
            }
        } catch (RuntimeException e) {
            TELEPORT_AUDIT_LOG.warn("event=ROAM_SHOP_VISIT_TELEPORT_AUDIT_SKIPPED member={} reason={}",
                    memberId, e.getClass().getSimpleName());
        }
    }

    /** 从候选行取 {时间毫秒, lat, lng, 来源};行无效则返回 current;两者都有效时取时间较新的那个。 */
    private static Object[] latestAcceptedPoint(Map<String, Object> row, String timeKey, String kind, Object[] current) {
        if (row == null) return current;
        Object rawTime = row.get(timeKey);
        Long at = rawTime instanceof java.util.Date ? Long.valueOf(((java.util.Date) rawTime).getTime())
                : rawTime instanceof java.time.LocalDateTime   // 新版 MySQL 驱动按 Map 取 DATETIME 回 LocalDateTime
                ? Long.valueOf(((java.time.LocalDateTime) rawTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                : null;
        if (at == null) return current;
        Double lat = row.get("lat") == null ? null : parseCoord(String.valueOf(row.get("lat")), -90d, 90d);
        Double lng = row.get("lng") == null ? null : parseCoord(String.valueOf(row.get("lng")), -180d, 180d);
        if (lat == null || lng == null) return current;
        if (current != null && at <= (Long) current[0]) return current;
        return new Object[] {at, lat, lng, kind};
    }

    /** 商家卡头像堆:一次最多问多少个来源,和横滑卡一次渲染的张数同量级。 */
    private static final int VISITORS_SOURCE_MAX = 20;
    /** 每张卡最多露几张脸。再多也放不下,右边那枚 +N 负责说清楚还有多少人。 */
    private static final int VISITORS_AVATAR_MAX = 5;

    @Operation(summary = "谁在这儿打过卡(商家卡头像堆 + 人数)")
    @PostMapping("/shop/visitors")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public AjaxResult shopVisitors(Integer sourceType, String sourceIds) {
        if (sourceType == null || (sourceType != 1 && sourceType != 2)) return error("来源有误");
        List<Long> ids = new ArrayList<>();
        for (String raw : StringUtils.split(sourceIds == null ? "" : sourceIds, ",")) {
            Long v = Convert.toLong(raw, 0L);
            // 去重后再截断:同一个 id 传两遍不该占掉别人的名额
            if (v != null && v > 0 && !ids.contains(v)) ids.add(v);
            if (ids.size() >= VISITORS_SOURCE_MAX) break;
        }
        // 空列表要在进 SQL 前挡住:`in ()` 是语法错,会 500
        if (ids.isEmpty()) return AjaxResult.success(new ArrayList<Map<String, Object>>());

        List<ShopVisitorAvatarVO> avatars = roamMapper.selectShopVisitorAvatars(sourceType, ids, VISITORS_AVATAR_MAX);
        List<ShopVisitorCountVO> counts = roamMapper.selectShopVisitorCounts(sourceType, ids);

        Map<Long, List<String>> bySource = new LinkedHashMap<>();
        for (ShopVisitorAvatarVO a : avatars) {
            if (a == null || a.getSourceId() == null) continue;
            List<String> list = bySource.get(a.getSourceId());
            if (list == null) { list = new ArrayList<>(); bySource.put(a.getSourceId(), list); }
            list.add(a.getAvatar());
        }
        Map<Long, Integer> totalBySource = new LinkedHashMap<>();
        for (ShopVisitorCountVO c : counts) {
            if (c != null && c.getSourceId() != null) totalBySource.put(c.getSourceId(), c.getTotal());
        }

        // 按请求顺序原样返回,一个来源一行 —— 没人打过卡的也返回(total=0),
        // 否则前端分不清「这家没人来过」和「这家没查到」。
        List<Map<String, Object>> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", id);
            List<String> list = bySource.get(id);
            row.put("avatars", list == null ? new ArrayList<String>() : list);
            Integer total = totalBySource.get(id);
            row.put("total", total == null ? 0 : total);
            rows.add(row);
        }
        return AjaxResult.success(rows);
    }

    /** cms_registration_merchant 的经纬度是 VARCHAR,可能是空串/脏值 */
    private static Double parseCoord(String s, double min, double max) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            double v = Double.parseDouble(s.trim());
            return (!Double.isFinite(v) || v < min || v > max) ? null : v;
        } catch (Exception e) { return null; }
    }

    /** 玩家上报纬度:非 null、有限、[-90,90]。NaN/Infinity 在这里失败关闭。 */
    private static boolean validLat(Double v) {
        return v != null && Double.isFinite(v) && v >= -90 && v <= 90;
    }

    /** 玩家上报经度:非 null、有限、[-180,180]。NaN/Infinity 在这里失败关闭。 */
    private static boolean validLng(Double v) {
        return v != null && Double.isFinite(v) && v >= -180 && v <= 180;
    }

    /**
     * 三店连亮:def 驱动发章。阈值/文案/图全在 growth_badge.unlock_rule_json —— 改配置 = UPDATE 一行 SQL,
     * 不发版、不改代码。取不到 def 就不发(与 PlayerProgressionServiceImpl.addIfNew 同范式)。
     * source_id 恒 0 = 终身一次:用阈值做 source_id 会导致调阈值(3→4)后老用户重复拿章。
     * @return 新发放的章(供前端结算屏显示)或 null
     */
    private Map<String, Object> awardShopStreak(Long memberId, Long sessionId, int sessionShops) {
        GrowthBadge def = growthBadgeMapper.selectByCode(SHOP_STREAK_CODE);
        if (def == null) {
            logger.warn("三店连亮 def 未落库(badge_code={}),本次不发章 —— 检查 migration_roam_shop_visit_*.sql 是否已跑", SHOP_STREAK_CODE);
            return null;
        }
        // kill switch:发现被刷时 UPDATE growth_badge SET status='1' 即停发(不必删行 —— 删行会让已发的章变孤儿)。
        // selectByCode 不带 status 过滤(与既有范式一致),故在这里判。'0'=启用(沿用现表约定)。
        if (!"0".equals(def.getStatus())) return null;
        if (!ruleEvaluator.isThresholdMet(ruleEvaluator.parse(def.getUnlockRuleJson()), sessionShops)) return null;
        if (playerBadgeMapper.selectOne(memberId, SHOP_STREAK_CODE, 0L) != null) return null;   // 终身一次
        PlayerBadge b = new PlayerBadge();
        b.setMemberId(memberId);
        b.setBadgeCode(def.getBadgeCode());
        b.setBadgeName(def.getBadgeName());
        b.setIconUrl(def.getIconUrl());     // ★ ROAM_TILES_* 漏了这行导致 icon 恒 null,别再漏
        b.setSourceType(4);                 // 4=漫游
        b.setSourceId(0L);
        b.setObtainTime(new Date());
        b.setDelFlag(0);
        b.setCreateTime(new Date());
        roamBadgeDeliveryService.prepare(sessionId, b);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", def.getBadgeCode());
        m.put("name", def.getBadgeName());
        m.put("statement", def.getStatement());
        m.put("iconUrl", def.getIconUrl());
        return m;
    }

    @Operation(summary = "三店连亮勋章配置(前端实时弹卡要阈值/文案;拿不到就用本地兜底)")
    @GetMapping("/badge/shop-streak")
    public AjaxResult shopStreakConfig() {
        GrowthBadge def = growthBadgeMapper.selectByCode(SHOP_STREAK_CODE);
        if (def == null || !"0".equals(def.getStatus())) return success(null);   // 停用则前端也不弹卡
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", def.getBadgeCode());
        data.put("name", def.getBadgeName());
        data.put("statement", def.getStatement());
        data.put("iconUrl", def.getIconUrl());
        data.put("threshold", ruleEvaluator.parse(def.getUnlockRuleJson()).getThreshold());
        return success(data);
    }

    @Operation(summary = "历史点亮迷雾格(恢复版图)")
    @GetMapping("/tiles")
    public AjaxResult tiles(Integer limit) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        int max = (limit == null || limit <= 0) ? 5000 : Math.min(limit, 20000);
        return success(roamMapper.selectTilesByMember(memberId, max));
    }

    @Operation(summary = "玩家迷雾记忆增量页(换设备/清缓存恢复版图)")
    @GetMapping("/tiles/page")
    public AjaxResult tilePage(Long afterId, Integer limit) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        long cursor = afterId == null || afterId < 0 ? 0L : afterId;
        int pageSize = (limit == null || limit <= 0) ? 1000 : Math.min(limit, 2000);
        List<Map<String, Object>> rows = roamMapper.selectTilePageByMember(memberId, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        int size = Math.min(rows.size(), pageSize);
        List<String> tileList = new ArrayList<>(size);
        Map<String, Object> tileRetentionAt = new LinkedHashMap<>();
        long nextAfterId = cursor;
        for (int i = 0; i < size; i++) {
            Map<String, Object> row = rows.get(i);
            String tile = String.valueOf(row.get("geohash7"));
            tileList.add(tile);
            tileRetentionAt.put(tile, row.get("locationExpiresFrom"));
            nextAfterId = Convert.toLong(row.get("id"), nextAfterId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tiles", tileList);
        data.put("tileRetentionAt", tileRetentionAt);
        data.put("nextAfterId", nextAfterId);
        // Keep the legacy numeric field; new clients must not round BIGINT cursors in JSON.
        data.put("nextAfterIdExact", Long.toString(nextAfterId));
        data.put("hasMore", hasMore);
        return success(data);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult reveal(String sessionId, String tiles) {
        return reveal(sessionId, tiles, null);
    }

    @GetMapping("/session")
    public AjaxResult session(String sessionId, String clientSessionKey) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        return success(roamRecoveryService.read(memberId, sessionId, clientSessionKey));
    }

    @PostMapping("/session/records")
    public AjaxResult archiveRecords(String sessionId, String clientSessionKey, String records) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long sid = Convert.toLong(sessionId);
        if (sid == null || sid <= 0 || sessionId == null || !sessionId.matches("[1-9][0-9]{0,18}"))
            return error("会话编号无效");
        if (clientSessionKey != null && !clientSessionKey.matches("[a-f0-9]{32}")) return error("会话关联标识无效");
        int accepted = roamSessionWriteService.archiveFinishedRecords(memberId, sid, clientSessionKey, records);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", String.valueOf(sid));
        data.put("acceptedRecords", accepted);
        data.put("evidenceType", "CLIENT_REPORTED");
        return success(data);
    }

    @Operation(summary = "点亮迷雾格(批量,幂等)")
    @PostMapping("/reveal")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult reveal(String sessionId, String tiles, String clientSessionKey) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (clientSessionKey != null && !clientSessionKey.matches("[a-f0-9]{32}")) return error("会话关联标识无效");
        List<String> tileList = splitTiles(tiles, 500);
        if (tileList.isEmpty()) return error("没有要点亮的格子");
        Long sid = Convert.toLong(sessionId, 0L);
        if (clientSessionKey != null && sessionId != null
                && (!sessionId.matches("0|[1-9][0-9]{0,18}") || Convert.toLong(sessionId) == null))
            return error("会话编号无效");
        locationConsent.requireCurrentAgreement(memberId);
        if (sid <= 0 && clientSessionKey != null) {
            roamMapper.insertKeyedSession(memberId, clientSessionKey);
            sid = roamMapper.selectOwnedSessionByKeyForUpdate(memberId, clientSessionKey).getId();
        } else if (sid <= 0) {
            RoamSession s = new RoamSession();
            s.setMemberId(memberId);
            s.setStartedAt(new Date());
            s.setStatus(0);
            s.setCreateTime(new Date());
            roamMapper.insertSession(s);
            sid = s.getId();
        }
        RoamSession session = roamMapper.selectOwnedSessionForUpdate(sid, memberId);
        if (session == null || !memberId.equals(session.getMemberId())) return error("会话不存在");
        if (clientSessionKey != null && !clientSessionKey.equals(session.getClientSessionKey()))
            return error("会话关联不一致");
        if (!Integer.valueOf(0).equals(session.getStatus())) return error("本次漫游已结算");
        // P0-23:格子是客户端上报的,服务端至少按会话时长封顶 —— 不能一次请求就把全城点亮刷里程碑勋章。
        //   超出预算的部分直接丢弃(不报错):报错会让客户端待传队列卡死、连带结算排不空。
        Date now = new Date();
        int remaining = revealTileBudget(session.getStartedAt(), now.getTime()) - roamMapper.countTilesBySession(sid);
        if (remaining <= 0) {
            logger.warn("漫游点亮超出会话时长预算,本批丢弃 member={} session={} tiles={}", memberId, sid, tileList.size());
            tileList = java.util.Collections.emptyList();
        } else if (tileList.size() > remaining) {
            logger.warn("漫游点亮超出会话时长预算,截断 member={} session={} tiles={} kept={}", memberId, sid, tileList.size(), remaining);
            tileList = tileList.subList(0, remaining);
        }
        int newly = tileList.isEmpty() ? 0 : roamMapper.batchInsertTile(memberId, sid, tileList, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", clientSessionKey == null ? sid : String.valueOf(sid));
        data.put("newlyRevealed", Math.max(0, newly));
        return success(data);
    }

    // ── 附近正在漫游的人(原型 otherRunners / otherSheet)────────────────────────
    //
    // 隐私口径(2026-09-10 用户拍板「做,按原型来」)。三条是硬的,改这段代码前先读完:
    //  ① **坐标必须截位**。客户端传来的是真实定位点,落库前一律截到 3 位小数
    //     (≈ 100 m 一格,见 PRESENCE_SCALE)。够地图上看出「附近有人在走」,
    //     不够跟着一个人走。截位在服务端做,不指望客户端自觉。
    //  ② **只露拍板过的那几样**:昵称、头像、地盘 %、探店数、在走时长、这一趟点亮的店。
    //     NearbyRunnerVO 里没有手机号 / 真名 / 精确坐标,别往里加。
    //  ③ **停止上报 = 立刻消失**。关定位、退出漫游、结算,都会让 last_report_at 变旧,
    //     PRESENCE_TTL_MIN 一过就不再出现在任何人的地图上。这就是那个「关掉」的开关,
    //     不另做一个能勾选却不影响上报的假开关。

    /** 上报位置截到几位小数。3 位 ≈ 100 m,是「看得见有人」和「跟得住一个人」之间那条线。 */
    private static final int PRESENCE_SCALE = 3;
    /** 多久没上报就算下线。定位心跳是 30 s 一次,5 分钟 = 连丢十次才判离线,不会闪。 */
    private static final int PRESENCE_TTL_MIN = 5;
    /** 一次最多返回几个人。地图上再多也画不下,而且是发给陌生人的数据,不做大批量导出口。 */
    private static final int RUNNERS_MAX = 20;
    /** 每人最多几张「TA 点亮的店」。原型那排是 3 张。 */
    private static final int RUNNER_SHOPS_MAX = 3;
    /** 附近的查询半径上限(米)。超过就按上限截 —— 不接受客户端要一个全城范围。 */
    private static final int RUNNERS_RADIUS_MAX = 3000;
    /** 瞬移校验回看多久内的本人心跳(分钟)。超过这个间隔的旧坐标不再约束新位置。 */
    private static final int PRESENCE_TELEPORT_WINDOW_MIN = 120;

    @Operation(summary = "上报我此刻的位置(漫游心跳,让附近的人看得见我)")
    @PostMapping("/presence")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult presence(String sessionId, Double lat, Double lng, Integer explorePct) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        Long sid = Convert.toLong(sessionId, 0L);
        // 没有会话就没有「在走」这回事 —— 这里不懒建会话(/reveal 才建),
        // 否则一个只打开过地图、从没走过的人也会出现在别人的地图上。
        if (sid <= 0) return error("会话不存在");
        if (lat == null || lng == null) return error("位置为空");
        // NaN/Infinity 必须在这里拒:旧的区间比较对 NaN 全 false,会漏到 BigDecimal.valueOf(NaN) 抛 500。
        if (!validLat(lat) || !validLng(lng)) return error("位置越界");

        locationConsent.requireCurrentAgreement(memberId);
        // 附近的人只以服务端记录的本人位置为查询中心;再拒掉会话内瞬移式的坐标,
        // 换着坐标上报去扫别人位置就不成立(合法的乘车移动按真实时间间隔放行)。
        // PR10 复审:基线只取本场会话已被接受的上一跳。跨会话比对会让「漫游结束→坐高铁→到站重新开始」
        // 在 120 分钟窗口内被当成瞬移,且被拒的上报从不刷新基线 ⇒ 整段被拦 ~1.5 小时。
        Date reportAt = new Date();
        RoamSession lastFix = roamMapper.selectLatestPresenceFix(memberId, sid,
                new Date(reportAt.getTime() - PRESENCE_TELEPORT_WINDOW_MIN * 60L * 1000L));
        if (lastFix != null && lastFix.getLastReportAt() != null
                && lastFix.getLastLat() != null && lastFix.getLastLng() != null) {
            long dtSec = Math.max(1L, (reportAt.getTime() - lastFix.getLastReportAt().getTime()) / 1000L);
            double moved = GeoDistanceUtils.haversineMeters(lat, lng, lastFix.getLastLat().doubleValue(), lastFix.getLastLng().doubleValue());
            if (moved > MIN_MOVE_DIST_M && moved / dtSec > MAX_MOVE_SPEED_MPS) return error("移动过快,疑似伪造定位");
        }
        BigDecimal blat = BigDecimal.valueOf(lat).setScale(PRESENCE_SCALE, RoundingMode.HALF_UP);
        BigDecimal blng = BigDecimal.valueOf(lng).setScale(PRESENCE_SCALE, RoundingMode.HALF_UP);
        // 探索度是本机 20×20 格的口径,后端没有等价物 ⇒ 只存不算,但要 clamp:
        // 它会显示给陌生人,不能让客户端写个 9999 进去。
        int pct = explorePct == null ? 0 : Math.max(0, Math.min(99, explorePct));

        int hit = roamMapper.updatePresence(sid, memberId, blat, blng, reportAt, pct);
        // 命中 0 = 这场会话不是你的,或已经结算了。不细分原因回话:
        // 分得越细,越能拿它探别人的会话 id。
        if (hit <= 0) return error("会话不存在");
        return success();
    }

    @GetMapping("/presence-visibility")
    public AjaxResult presenceVisibility(String sessionId) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        RoamSession own = roamMapper.selectSessionById(Convert.toLong(sessionId, 0L));
        if (own == null || !memberId.equals(own.getMemberId()) || !Integer.valueOf(0).equals(own.getStatus())) {
            return success(false);
        }
        return success(Boolean.TRUE.equals(own.getPresenceVisible()));
    }

    @PostMapping("/presence-visibility")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult changePresenceVisibility(String sessionId, Boolean visible, String consentVersion) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (visible == null) return error("请选择是否公开");
        Long sid = Convert.toLong(sessionId, 0L);
        if (visible) {
            if (!"roam-presence-v1".equals(consentVersion)) return error("请先单独同意附近的人位置分享");
            // 位置分享建立在漫游定位同意之上:撤回漫游定位后不能再单独打开对他人的展示。
            // 会员互斥锁先于偏好事实锁,与 RoamLocationConsentService 的加锁顺序一致。
            locationConsent.requireCurrentAgreement(memberId);
        }
        presenceRevokeAuditService.lockPreference(memberId);
        String requestId = "presence-" + java.util.UUID.randomUUID();
        Date occurredAt = new Date();
        if (visible) {
            RoamSession own = roamMapper.selectOwnedSessionForUpdate(sid, memberId);
            if (own == null || !Integer.valueOf(0).equals(own.getStatus())) return error("请先开始漫游");
        }
        // 关闭覆盖本人所有未结束会话，旧会话或在途心跳都不能重新打开。
        roamMapper.updatePresenceVisibility(null, memberId, false);
        if (!visible) {
            presenceRevokeAuditService.remember(memberId, requestId, occurredAt);
            AjaxResult closed = success(false);
            closed.put("auditStatus", "PENDING");
            Runnable audit = () -> {
                try { enqueuePresenceAudit(memberId, () -> presenceRevokeAuditService.deliver(requestId)); }
                catch (RuntimeException | LinkageError rejected) { alarmPresenceAuditFailure(memberId, rejected); }
            };
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { audit.run(); }
                    });
            } else { audit.run(); }
            return closed;
        }
        int consentRows = consentRecordService.record(memberId, "privacy_policy", "roam-presence-v1", "roam_presence",
                "AGREE", requestId, "");
        if (consentRows != 1) throw new IllegalStateException("位置分享同意未保存，请重试");
        presenceRevokeAuditService.rememberAgreement(memberId, requestId, occurredAt);
        if (visible && roamMapper.updatePresenceVisibility(sid, memberId, true) <= 0) {
            throw new IllegalStateException("漫游会话状态已变化");
        }
        return success(visible);
    }

    protected void enqueuePresenceAudit(Long memberId, Runnable audit) {
        com.chengyinhub.framework.manager.AsyncManager.me().execute(new java.util.TimerTask() {
            @Override public void run() {
                try { audit.run(); }
                catch (RuntimeException | LinkageError failure) { alarmPresenceAuditFailure(memberId, failure); }
            }
        });
    }

    private void alarmPresenceAuditFailure(Long memberId, Throwable failure) {
        logger.warn("位置分享已关闭但撤回审计未完成 memberId={} reason={}", memberId,
                failure == null ? "UNKNOWN" : failure.getClass().getSimpleName());
        try { alarmService.alarm("位置分享撤回记录失败", "分享已关闭，撤回记录需补存 memberId=" + memberId); }
        catch (RuntimeException alarmFailure) { logger.warn("位置分享撤回记录告警未送达 reason={}", alarmFailure.getClass().getSimpleName()); }
    }

    @Operation(summary = "附近还在走的人(地图上的他人图层 + 点头像的半屏)")
    @PostMapping("/nearby-runners")
    public AjaxResult nearbyRunners(Double lat, Double lng, Integer radius) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (lat == null || lng == null) return error("位置为空");
        if (!validLat(lat) || !validLng(lng)) return error("位置越界");
        int radiusM = radius == null || radius <= 0 ? RUNNERS_RADIUS_MAX
                : Math.min(radius, RUNNERS_RADIUS_MAX);

        Date now = new Date();
        Date freshAfter = new Date(now.getTime() - PRESENCE_TTL_MIN * 60L * 1000L);
        RoamSession own = roamMapper.selectActivePresence(memberId, freshAfter);
        if (own == null || own.getLastLat() == null || own.getLastLng() == null) {
            return AjaxResult.success(new ArrayList<Map<String, Object>>());
        }
        List<NearbyRunnerVO> runners =
                roamMapper.selectNearbyRunners(memberId, own.getLastLat().doubleValue(),
                        own.getLastLng().doubleValue(), radiusM, freshAfter, now, RUNNERS_MAX);
        if (runners == null || runners.isEmpty()) return AjaxResult.success(new ArrayList<Map<String, Object>>());

        List<Long> ids = new ArrayList<>(runners.size());
        for (NearbyRunnerVO r : runners) if (r != null && r.getMemberId() != null) ids.add(r.getMemberId());
        // 空列表要在进 SQL 前挡住:`in ()` 是语法错,会 500
        Map<Long, List<Map<String, Object>>> shopsBy = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            List<RunnerShopVO> shops = roamMapper.selectRunnerShops(ids, RUNNER_SHOPS_MAX);
            for (RunnerShopVO sv : shops == null ? new ArrayList<RunnerShopVO>() : shops) {
                if (sv == null || sv.getMemberId() == null) continue;
                List<Map<String, Object>> list = shopsBy.get(sv.getMemberId());
                if (list == null) { list = new ArrayList<>(); shopsBy.put(sv.getMemberId(), list); }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", sv.getShopName());
                one.put("image", sv.getShopImage());
                list.add(one);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(runners.size());
        for (NearbyRunnerVO r : runners) {
            if (r == null || r.getMemberId() == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", r.getMemberId());
            row.put("nickname", StringUtils.isEmpty(r.getNickname()) ? "漫游者" : r.getNickname());
            row.put("avatar", r.getAvatar());
            row.put("lat", r.getLat() == null ? null : r.getLat().setScale(2, RoundingMode.HALF_UP));
            row.put("lng", r.getLng() == null ? null : r.getLng().setScale(2, RoundingMode.HALF_UP));
            row.put("explorePct", r.getExplorePct() == null ? 0 : r.getExplorePct());
            row.put("shops", r.getShops() == null ? 0 : r.getShops());
            row.put("elapsedSec", r.getElapsedSec() == null ? 0L : r.getElapsedSec());
            List<Map<String, Object>> list = shopsBy.get(r.getMemberId());
            row.put("shopPhotos", list == null ? new ArrayList<Map<String, Object>>() : list);
            rows.add(row);
        }
        return AjaxResult.success(rows);
    }

    @Operation(summary = "漫游结算(发探索值 + 里程碑勋章)")
    @PostMapping("/finish")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)  // C3b:中途失败整体回滚,重试不重复发/不丢
    public AjaxResult finish(String sessionId, String poiIds, Integer distanceM) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        roamMapper.lockClaimOwner(memberId);
        Long sid = Convert.toLong(sessionId, 0L);
        RoamSession session = sid > 0 ? roamMapper.selectOwnedSessionForUpdate(sid, memberId) : null;
        if (session == null || !memberId.equals(session.getMemberId())) return error("会话不存在");
        if (!Integer.valueOf(0).equals(session.getStatus())) return error("本次漫游已结算");

        // ① POI:R6 只认服务端 /poi/discover 已校验到点的记录,不再采信客户端 poiIds(防伪造 poiId 刷 XP)
        List<RoamPoi> foundPois = roamMapper.selectFoundPoisBySession(sid);
        int poiXp = 0, newPois = foundPois.size();
        for (RoamPoi p : foundPois) {
            poiXp += (p.getXp() != null ? p.getXp() : 20);
        }

        // ② 格子:本会话新点亮数,套今日 200 上限
        int sessionTiles = roamMapper.countTilesBySession(sid);
        int tilesToday = roamMapper.countTilesTodayByMember(memberId);
        int tileXp = tileXpWithDailyCap(tilesToday, sessionTiles, DAILY_TILE_XP_LIMIT);

        int totalXp = tileXp + poiXp;
        // ③ 先用 status=0→1 的 CAS 抢结算权。先读后判挡不住两个并发请求同时读到 status=0;
        //    CAS 失败的请求必须在任何 XP/勋章写入前退出。
        session.setFinishedAt(new Date());
        // P0-23:里程是客户端自报的展示值(会出现在社区帖的路线引用里),按会话时长与格子数封顶后再落库。
        session.setDistanceM(clampClientDistance(distanceM, session.getStartedAt(), session.getFinishedAt(), sessionTiles));
        session.setTilesRevealed(sessionTiles);
        session.setPoisFound(newPois);
        session.setXpAwarded(totalXp);
        if (roamMapper.updateSessionFinish(session) == 0) return error("本次漫游已结算");
        session.setStatus(1);

        // ④ 双轨发放:只入 member_growth。后续异常会由方法事务把上面的 CAS 一并回滚。
        if (totalXp > 0) growthService.addExp(memberId, totalXp, "城市漫游");

        // ⑤ 里程碑勋章(按累计版图跨阈)
        int tilesEver = roamMapper.countTilesEverByMember(memberId);
        int crossed = crossedMilestone(tilesEver - sessionTiles, tilesEver, TILE_MILESTONES);
        String medalName = null;
        if (crossed > 0) {
            String code = "ROAM_TILES_" + crossed;
            if (playerBadgeMapper.selectOne(memberId, code, (long) crossed) == null) {
                PlayerBadge b = new PlayerBadge();
                b.setMemberId(memberId);
                b.setBadgeCode(code);
                b.setBadgeName("城市点亮 " + crossed + " 格");
                b.setSourceType(4);          // 4=漫游里程碑
                b.setSourceId((long) crossed);
                b.setObtainTime(new Date());
                b.setDelFlag(0);
                b.setCreateTime(new Date());
                roamBadgeDeliveryService.prepare(sid, b);
                medalName = b.getBadgeName();
            }
        }

        // ⑥ 三店连亮(def 驱动 + 真落 player_badge)。此前这枚章是纯前端假徽章:
        //    _popMedal() 只 setData、零请求,勋章墙永远查不到,而 shareMedal() 还能把它画成图分享出去。
        //    口径 = 本会话 count(distinct source_type, source_id),含 roam 据点与报名商家(用户拍板「任意真商家」)。
        int sessionShops = roamMapper.countDistinctShopsBySession(sid);
        Map<String, Object> shopMedal = awardShopStreak(memberId, sid, sessionShops);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("finishedAt", session.getFinishedAt().getTime());
        data.put("tileXp", tileXp);
        data.put("poiXp", poiXp);
        data.put("totalXp", totalXp);
        data.put("newTiles", sessionTiles);
        data.put("newPois", newPois);
        data.put("tilesEver", tilesEver);
        data.put("medal", medalName);
        data.put("sessionShops", sessionShops);
        data.put("badgeDelivery", roamBadgeDeliveryService.readStatus(memberId, sid));
        data.put("shopMedal", shopMedal);   // null = 未达阈值/已拿过/def 未落库
        // 终点故事只消费本次已经由服务端结算的统计值；开关关闭或内容未审时静默。
        if (npcFeatureFlags.eventOn()) {
            try {
                String routeStory = roamAiContentService.approvedRouteStory(session);
                if (routeStory != null) data.put("routeStory", routeStory);
            } catch (Exception e) {
                // 结算事务已完成核心账本写入；可选内容查询失败不得让结算回滚。
                logger.warn("漫游足迹回顾不可用,本次仅保留结算结果 sessionId={}, reason={}",
                        sid, e.getClass().getSimpleName());
            }
        }
        if (roamMapper.saveFinishResult(sid, memberId, com.alibaba.fastjson2.JSON.toJSONString(data)) != 1)
            throw new com.chengyinhub.common.exception.ServiceException("结算结果保存失败，请重新核对");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            final Long originalSid = sid;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            try { data.put("badgeDelivery", roamBadgeDeliveryService.readCommittedStatus(memberId, originalSid)); }
                            catch (RuntimeException ex) { logger.warn("原漫游徽章回读待重试 sessionId={}", originalSid); }
                        }
                    });
        }
        return success(data);
    }

    @Operation(summary = "点到店(bindpoitap):宽松 geo → 参与据点走扫码 / 非参与点亮发探索值")
    @PostMapping("/checkin")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult checkin(String name, Double poiLat, Double poiLng, Double curLat, Double curLng) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (poiLat == null || poiLng == null || curLat == null || curLng == null) return error("缺少定位");
        // 点到店四个坐标全是客户端上报:NaN/Infinity/越界会让 ①的距离比较恒 false 而直接放行发探索值。
        if (!validLat(poiLat) || !validLng(poiLng) || !validLat(curLat) || !validLng(curLng)) return error("定位无效");

        locationConsent.requireCurrentAgreement(memberId);
        Map<String, Object> data = new LinkedHashMap<>();
        // ① 宽松 geo:玩家离被点 POI 太远则拒
        if (GeoDistanceUtils.haversineMeters(curLat, curLng, poiLat, poiLng) > CHECKIN_LOOSE_RADIUS_M) {
            data.put("tooFar", true);
            return AjaxResult.success("离得有点远,走近点", data);
        }
        // ② 就近匹配参与据点(type=2 status=1):60m 包围盒粗筛 → 精确挑最近 ≤50m
        double dLat = 60 / 111320.0;
        double dLng = 60 / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(poiLat))));
        List<CityNodeVO> near = roamMapper.selectMerchantNodesNear(
                poiLat - dLat, poiLat + dLat, poiLng - dLng, poiLng + dLng,
                poiLat, poiLng, null, null, null, null);
        Long poiId = nearestWithin(near, poiLat, poiLng, NODE_MATCH_RADIUS_M);
        if (poiId != null) {
            data.put("participating", true);
            data.put("poiId", poiId);
            return AjaxResult.success(data);
        }
        // ③ 非参与:通用点亮,首次到点发探索值(每点终身一次)
        String poiName = (name != null && !name.trim().isEmpty())
                ? (name.length() > 120 ? name.substring(0, 120) : name) : "未知地点";
        boolean firstVisit;
        if (visitedPoiIdentity != null && visitedPoiIdentity.enabled()) {
            // O-02:身份 = 地标 id 或首访锚点,同一点不同精度只算一次(见 VisitedPoiIdentityResolver)
            firstVisit = visitedPoiIdentity.recordVisit(memberId, poiLat, poiLng, poiName, new Date());
        } else {
            int inserted = memberVisitedPoiMapper.insertVisitedPoiIgnore(memberId, geoKey(poiLat, poiLng), poiName,
                    java.math.BigDecimal.valueOf(poiLat), java.math.BigDecimal.valueOf(poiLng), new Date());
            firstVisit = inserted > 0;
        }
        int xp = 0;
        if (firstVisit) { xp = VISIT_XP; growthService.addExp(memberId, xp, "点亮地点"); }
        data.put("participating", false);
        data.put("firstVisit", firstVisit);
        data.put("xp", xp);
        data.put("name", poiName);
        return AjaxResult.success(firstVisit ? "点亮 " + poiName : "已点亮过", data);
    }

    // ---------- 纯逻辑(单测友好) ----------

    /**
     * 格子探索值(套今日 200 上限):tilesToday=今日累计新格(含本次),sessionTiles=本会话新格。
     * 本会话应得 = min(tilesToday,limit) 减去 本会话之前今日已达上限部分,保证跨会话每日不超 limit。
     */
    static int tileXpWithDailyCap(int tilesToday, int sessionTiles, int dailyLimit) {
        int session = Math.max(0, sessionTiles);
        int today = Math.max(session, tilesToday);      // 防脏数据:今日至少含本会话
        int prior = today - session;                    // 本会话之前今日已点格
        int cappedNow = Math.min(today, dailyLimit);
        int cappedPrior = Math.min(prior, dailyLimit);
        return Math.max(0, cappedNow - cappedPrior);
    }

    /** 从 priorEver 到 nowEver 跨过的最高里程碑阈值;没跨返回 0。 */
    static int crossedMilestone(int priorEver, int nowEver, int[] thresholds) {
        int best = 0;
        for (int t : thresholds) {
            if (priorEver < t && nowEver >= t && t > best) best = t;
        }
        return best;
    }

    /** 坐标取整到 3 位小数(≈110m)作为到点去重键;同一 POI 坐标稳定 → 同键。 */
    static String geoKey(double lat, double lng) {
        return String.format(java.util.Locale.US, "%.3f,%.3f", lat, lng);
    }

    /** 被点坐标就近匹配参与据点:返回 maxM 米内最近节点的 poiId,无则 null。 */
    static Long nearestWithin(List<CityNodeVO> nodes, double lat, double lng, int maxM) {
        if (nodes == null) return null;
        Long best = null;
        double bestD = Double.MAX_VALUE;
        for (CityNodeVO n : nodes) {
            if (n.getLat() == null || n.getLng() == null || n.getPoiId() == null) continue;
            double d = GeoDistanceUtils.haversineMeters(lat, lng, n.getLat().doubleValue(), n.getLng().doubleValue());
            if (d <= maxM && d < bestD) { bestD = d; best = n.getPoiId(); }
        }
        return best;
    }

    /** 会话首批格子的基础额度(与每日探索值上限同量级),之后按时长增长。 */
    static final int REVEAL_TILE_BASE = 200;
    /** 时长预算按「最高速度每走 100 米一格」放宽(geohash7 边长约 150 米,斜穿也不会更密)。 */
    static final double REVEAL_TILE_EDGE_M = 100.0;
    /** 单次漫游里程上限(米),防自报一个天文数字。 */
    static final int ROAM_MAX_DISTANCE_M = 200_000;
    /** 里程按格子数的额外放宽(米/格):会话可能晚于实际出发才建(离线先走),格子能证明走过。 */
    static final int DISTANCE_PER_TILE_ALLOWANCE_M = 300;

    /** 本会话最多可点亮的新格子数:基础额度 + 会话时长 × 最高速度 / 每格距离。 */
    static int revealTileBudget(Date startedAt, long nowMs) {
        long elapsedSec = startedAt == null ? 0L : Math.max(0L, (nowMs - startedAt.getTime()) / 1000L);
        double byTime = elapsedSec * MAX_MOVE_SPEED_MPS / REVEAL_TILE_EDGE_M;
        return (int) Math.min(Integer.MAX_VALUE, REVEAL_TILE_BASE + (long) Math.ceil(byTime));
    }

    /** 客户端自报里程的服务端上界:min(总上限, 时长×最高速度 + 格子×放宽);负数/空按 0。 */
    static int clampClientDistance(Integer distanceM, Date startedAt, Date finishedAt, int sessionTiles) {
        if (distanceM == null || distanceM <= 0) return 0;
        long elapsedSec = startedAt == null || finishedAt == null ? 0L
                : Math.max(0L, (finishedAt.getTime() - startedAt.getTime()) / 1000L);
        long bound = (long) Math.ceil(elapsedSec * MAX_MOVE_SPEED_MPS)
                + (long) Math.max(0, sessionTiles) * DISTANCE_PER_TILE_ALLOWANCE_M;
        return (int) Math.min(distanceM, Math.min(bound, ROAM_MAX_DISTANCE_M));
    }

    private static final String GEOHASH32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    /** 迷雾格只认 7 位 geohash(与小程序 utils/roam-geo.js geohash7 同字母表);其它长度/字符一律丢弃。 */
    static boolean isGeohash7(String t) {
        if (t == null || t.length() != 7) return false;
        for (int i = 0; i < t.length(); i++) {
            if (GEOHASH32.indexOf(t.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static List<String> splitTiles(String csv, int cap) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (isGeohash7(t)) out.add(t);
            if (out.size() >= cap) break;
        }
        return out;
    }

}
