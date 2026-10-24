package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IMerchantNodeDiscoveryService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 漫游 · 商家节点(city node)。
 * roam_poi type=2 商户据点的玩家侧接口:附近节点、节点详情(P1-B1),收藏(P1-B2)、完成领券(P1-B3)。
 * 薄层,不污染 ApiRoamController 的漫游会话逻辑。
 */
@RestController
@Tag(name = "漫游节点")
@RequestMapping("/api/city")
public class ApiCityNodeController extends BaseController {

    @Autowired
    private RoamMapper roamMapper;
    @Autowired
    private MemberCollectibleMapper collectibleMapper;
    @Autowired
    private ICmsMemberTemplateService templateService;
    @Autowired
    private IMerchantNodeDiscoveryService merchantNodeDiscoveryService;

    @Autowired
    private com.chengyinhub.business.mapper.GrowthBadgeMapper growthBadges;
    @Autowired
    private com.chengyinhub.business.mapper.PlayerBadgeMapper playerBadges;

    /** 张贴码密钥:与核销动态码同一把,口径见 ApiVerifyController#dynSecret */
    @Value("${verify.dyncode-secret:${token.secret}}")
    private String dynSecret;

    /** GPS 抖动容差(米):半径校验放宽,防真机漂移误伤到店玩家。 */
    private static final int GPS_TOLERANCE_M = 30;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "附近商家节点(经纬度包围盒粗筛)")
    @GetMapping("/nodes")
    public AjaxResult nodes(Double lat, Double lng, Integer radius,
                            String keyword, Integer categoryId, String tag, String cityRole) {
        if (lat == null || lng == null) return error("缺少定位");
        int r = (radius == null || radius <= 0) ? 3000 : Math.min(radius, 20000);
        double dLat = r / 111320.0;
        double dLng = r / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        List<CityNodeVO> list = roamMapper.selectMerchantNodesNear(
                lat - dLat, lat + dLat, lng - dLng, lng + dLng,
                lat, lng, keyword, categoryId, tag, cityRole);
        markUserState(list);
        return success(list);
    }

    @Operation(summary = "商家节点详情")
    @GetMapping("/nodes/{id}")
    public AjaxResult detail(@PathVariable("id") Long id) {
        if (id == null || id <= 0) return error("参数不合规");
        Long memberId = uid();
        CityNodeVO vo = roamMapper.selectPlayerMerchantNodeDetail(id, memberId);
        if (vo == null) return AjaxResult.error(410, "据点未开放或已下线");
        Long pendingCoupon = memberId > 0 ? roamMapper.selectPendingCityNodeCouponId(id, memberId) : null;
        vo.setNeedRedeem(pendingCoupon != null);
        if (pendingCoupon != null) vo.setCouponId(pendingCoupon);
        markUserState(Collections.singletonList(vo));
        return success(vo);
    }

    @Operation(summary = "商家公开主页的在架节点(收藏入口)")
    @GetMapping("/nodes/merchant/{merchantId}")
    public AjaxResult merchantNode(@PathVariable("merchantId") Long merchantId) {
        if (merchantId == null || merchantId <= 0) return error("参数不合规");
        CityNodeVO node = merchantNodeDiscoveryService.findActiveNode(merchantId);
        if (node == null) return success();
        markUserState(Collections.singletonList(node));
        return success(node);
    }

    @Operation(summary = "我收藏的商家节点")
    @GetMapping("/nodes/favorites")
    public AjaxResult favorites() {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        List<CityNodeVO> list = roamMapper.selectFavoriteMerchantNodes(memberId);
        markUserState(list);
        return success(list);
    }

    @Operation(summary = "收藏/取消收藏商家节点(切换)")
    @PostMapping("/nodes/{id}/favorite")
    public AjaxResult favorite(@PathVariable("id") Long id) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (id == null || id <= 0) return error("参数不合规");
        boolean nowFav;
        if (collectibleMapper.countOne(memberId, MemberCollectible.TYPE_NODE_FAV, id) > 0) {
            collectibleMapper.deleteOne(memberId, MemberCollectible.TYPE_NODE_FAV, id);
            nowFav = false;
        } else {
            if (roamMapper.selectPlayerMerchantNodeDetail(id, 0L) == null) return error("据点未开放或已下线");
            MemberCollectible c = new MemberCollectible();
            c.setMemberId(memberId);
            c.setCollType(MemberCollectible.TYPE_NODE_FAV);
            c.setRefId(id);
            c.setCreateTime(new Date());
            try { collectibleMapper.insertMemberCollectible(c); } catch (DuplicateKeyException ignore) { /* uk 并发去重 */ }
            nowFav = true;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("favorited", nowFav);
        return success(data);
    }

    /** 登录态下标记玩家是否已完成(领过券)与是否已收藏;未登录/空集不查。 */
    private void markUserState(List<CityNodeVO> list) {
        if (list == null || list.isEmpty()) return;
        // 玩家侧公开接口不返回商家联系电话；联系能力统一走商家主页的既有授权链路。
        for (CityNodeVO v : list) v.setMerchantPhone(null);
        Long memberId = uid();
        if (memberId <= 0) return;
        List<Long> ids = new ArrayList<>();
        for (CityNodeVO v : list) {
            if (v.getPoiId() != null) ids.add(v.getPoiId());
        }
        if (ids.isEmpty()) return;
        Set<Long> done = new HashSet<>(roamMapper.selectRewardedPoiIdsByMember(memberId, ids));
        Set<Long> fav = new HashSet<>();
        for (MemberCollectible c : collectibleMapper.selectListByMember(memberId, MemberCollectible.TYPE_NODE_FAV)) {
            if (c.getRefId() != null) fav.add(c.getRefId());
        }
        for (CityNodeVO v : list) {
            v.setCompleted(done.contains(v.getPoiId()));
            v.setFavorited(fav.contains(v.getPoiId()));
        }
    }

    @Operation(summary = "完成节点互动并领券(半径二次校验 + 按验证方式判定 + 每人每节点仅一次)")
    @PostMapping("/nodes/{id}/complete")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult complete(@PathVariable("id") Long id, Double lat, Double lng,
                               String answer, String photoUrl, String code) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (id == null || id <= 0) return error("参数不合规");
        roamMapper.lockClaimOwner(memberId);
        CityNodeVO node = roamMapper.selectPlayerMerchantNodeDetail(id, 0L);
        if (node == null) return error("据点未开放或已下线");
        if (node.getStatus() == null || node.getStatus() != 1) return error("节点未上线");
        if (node.getLat() == null || node.getLng() == null) return error("节点未设置坐标");

        // ① 服务端二次半径校验(GPS 只作触发,不作发放唯一依据)
        if (lat == null || lng == null) return error("缺少定位");
        int radius = (node.getRadiusM() != null && node.getRadiusM() > 0) ? node.getRadiusM() : 80;
        double dist = distMeters(lat, lng, node.getLat().doubleValue(), node.getLng().doubleValue());
        if (!withinReach(dist, radius)) return error("离据点太远,请到店后再完成");

        // ② 按验证方式判定完成(玩法跟模板走)
        Integer vm = node.getValidationMethod();
        if (vm != null && (vm == 1 || vm == 3)) {          // 1文字 / 3选项:答案校验
            CmsMemberTemplate tpl = node.getTemplateId() != null
                    ? templateService.selectCmsMemberTemplateById(node.getTemplateId()) : null;
            String expect = (tpl == null) ? null : (vm == 1 ? tpl.getQuestionAnswer() : tpl.getCorrectAnswer());
            if (!answerMatches(expect, answer)) {
                return error("答案不正确");
            }
        } else if (vm != null && vm == 2) {                // 2拍照:需上传照片
            // 只认走过本平台上传通道的 URL。放任意字符串通过 = 这一档退化成「随便填点什么」,
            // 跟没有这道闸一样(小程序 app.chooseImage 回的就是上传后的 http(s) URL)。
            // ⚠️ 目前只校验形态,照片内容未过审、也未落库(没有存放列),商家事后无从复核。
            String photo = photoUrl == null ? "" : photoUrl.trim();
            if (photo.isEmpty()) return error("请上传拍照打卡照片");
            if (!photo.startsWith("http://") && !photo.startsWith("https://")) {
                return error("照片地址不合法,请重新上传");
            }
        } else if (vm != null && vm == 4) {                // 4扫码:必须扫到本店那张张贴码
            // 2026-08-06 补实现。此前这里没有分支,落到下面的「以到店定位为准」——
            // 商家在配置里选了「到店扫码」,实际生效的是 GPS,而且不报错。静默降级比报错更坏。
            //
            // ⚠️ 这道闸挡的是「没扫到码」,**不是**「人不在店里」:上面那层半径校验用的
            // lat/lng 是客户端传的,可以伪造。所以码被拍照转发出去 + 伪造坐标,是能远程完成的。
            // 兜底不在这里 —— 完成只写一条 status=0 的待核销记录,券仍要商家扫玩家的动态码才发。
            Long scanned = VerifyDynCodeUtil.verifyPoiPoster(code, dynSecret);
            if (scanned == null) return error("请扫描本店的到店打卡码");
            // 扫了别家的码不算 —— 否则一张码配任意坐标就能刷遍全城
            if (!id.equals(scanned)) return error("这张码不属于本店");
        }
        // 5GPS到达 / 其它:以到店定位为准

        if (Boolean.TRUE.equals(node.getPhotoRequired())) {
            try {
                com.chengyinhub.common.utils.file.FileOssUploadUtils.verifyUploadedPhoto(photoUrl, memberId);
            } catch (IllegalArgumentException invalid) {
                return error("照片未上传成功或不属于当前账号，请重新拍摄上传");
            } catch (IllegalStateException unavailable) {
                return error("照片校验暂不可用，请稍后重试");
            }
        }

        // ③ 每玩家每节点仅一次(uk_poi_member 幂等):记到店打卡(status=0「待核销」),券不在此发
        Long couponId = node.getCouponId();
        boolean hasCoupon = couponId != null && couponId > 0;
        // 有券节点记 status=0(待商家扫码核销发券),无券节点记 status=1(已完成)
        int inserted = roamMapper.insertRewardLogIgnore(id, memberId, couponId,
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), hasCoupon ? 0 : 1, new Date());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("couponId", couponId);
        data.put("needRedeem", hasCoupon);   // 有券:引导玩家出示据点核销码给商家扫码领券
        if (inserted == 0) {
            Long pendingCouponId = roamMapper.selectPendingCityNodeCouponId(id, memberId);
            data.put("couponId", pendingCouponId);
            data.put("needRedeem", pendingCouponId != null && pendingCouponId > 0);
            data.put("alreadyClaimed", true);
            return AjaxResult.success("你已完成过该据点", data);
        }
        if (Boolean.TRUE.equals(node.getPhotoRequired())
                && roamMapper.savePoiEvidence(id, memberId, photoUrl, answer) != 1)
            throw new com.chengyinhub.common.exception.ServiceException("照片凭证保存失败，请重试");
        if (!hasCoupon && node.getBadgeCode() != null) {
            com.chengyinhub.business.domain.GrowthBadge def = growthBadges.selectByCode(node.getBadgeCode());
            if (def == null || !"0".equals(def.getStatus()))
                throw new com.chengyinhub.common.exception.ServiceException("地点勋章暂不可用，请重试");
            if (playerBadges.countByMemberAndCode(memberId, def.getBadgeCode()) == 0) {
                com.chengyinhub.business.domain.PlayerBadge badge = new com.chengyinhub.business.domain.PlayerBadge();
                badge.setMemberId(memberId); badge.setBadgeCode(def.getBadgeCode()); badge.setBadgeName(def.getBadgeName());
                badge.setIconUrl(def.getIconUrl()); badge.setSourceType(6); badge.setSourceId(id);
                badge.setDelFlag(0); badge.setObtainTime(new Date()); badge.setCreateTime(new Date());
                if (playerBadges.insertPlayerBadge(badge) != 1)
                    throw new com.chengyinhub.common.exception.ServiceException("勋章发放失败，请重试");
            }
            data.put("badgeCode", def.getBadgeCode()); data.put("badgeName", def.getBadgeName());
        }
        data.put("poiId", id);
        data.put("completed", !hasCoupon);
        // ④ C2 防伪造GPS薅券:到店券不再凭客户端 GPS 自动发放,改由商家扫玩家「据点核销码」核销发券
        //    (/api/verify/citynode/issue 玩家出码 → 商家扫 → /api/verify/citynode/redeem 验签+归属+幂等+发券)
        data.put("hint", hasCoupon ? "已到店打卡,出示据点核销码给商家即可领券" : "已到店打卡");
        return AjaxResult.success("完成", data);
    }

    /** 到店可达:距离在半径 + GPS 容差内(单测友好)。 */
    static boolean withinReach(double distM, int radiusM) {
        return distM <= radiusM + GPS_TOLERANCE_M;
    }

    /** 答案匹配:去空白 + 忽略大小写;任一为空视为不匹配(单测友好)。 */
    static boolean answerMatches(String expect, String given) {
        if (expect == null || given == null) return false;
        return expect.trim().equalsIgnoreCase(given.trim());
    }

    /** 两点球面距离(米,haversine)。 */
    static double distMeters(double lat1, double lng1, double lat2, double lng2) {
        return GeoDistanceUtils.haversineMeters(lat1, lng1, lat2, lng2);
    }
}
