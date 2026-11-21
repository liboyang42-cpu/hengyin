package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.exception.ErrorCode;

import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.domain.RoamStampExchange;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.RoamStampListVO;
import com.chengyinhub.business.mapper.RoamStampMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 漫游集邮 · UGC 邮票(P0 只做自由拍)。
 *
 * 【为什么不进 growth_badge】那是定义表(字典),buildWallV2 用 selectAllActive() 全量发给每个人,
 * 无 member 过滤无分页。UGC 进去会让所有人的墙上出现所有人的照片,并撑爆字典表。
 * 邮票走本表 + 集邮册独立界面;将来与勋章的连接靠 RuleEvaluator 计数,不混表。
 *
 * 【默认私密】只返回自己的邮票,不进广场、不进他人信息流。
 */
@RestController
@Tag(name = "漫游集邮")
@RequestMapping("/api/roam/stamp")
public class ApiRoamStampController extends BaseController {

    static final int PAGE_SIZE_DEFAULT = 20;
    static final int PAGE_SIZE_MAX = 50;
    static final int CAPTION_MAX = 140;
    static final int IDEM_KEY_MAX = 64;

    @Autowired
    private RoamStampMapper roamStampMapper;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IUmsMemberService umsMemberService;

    /** GAME-12: place lookup (a city node's id is its POI id). Optional so hand-built unit tests keep the free-photo path. */
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.RoamMapper roamMapper;

    /** GAME-12: scores the fix like the place check-in (accuracy, fix time, simulated, impossible speed). */
    @Autowired(required = false)
    private com.chengyinhub.business.locationtrust.LocationTrustService locationTrust;

    /**
     * GAME-12: place stamps (kind = 1), earned at the place, once per place. Off by default until the owner
     * confirms that stamps are place-bound collectibles; while off, {@code poiId} and the fix are ignored and
     * every stamp is a free photo (postcard), exactly as before.
     */
    @org.springframework.beans.factory.annotation.Value("${chengyin.roam.place-stamp.enabled:false}")
    private boolean placeStampEnabled;

    /** Extra metres on top of the place radius for a place stamp (GPS drift). */
    static final int PLACE_STAMP_SLACK_M = 50;
    /** Radius used when the place has none configured (same as the place check-in). */
    static final int PLACE_STAMP_DEFAULT_RADIUS_M = 80;

    /** 包级可见(非 private):测试用子类覆写它注入身份,好让行为测试能真调 create/list 入口方法,
     *  而不是只测静态纯函数 —— 闸放错层时,只有真走入口的测试抓得到。 */
    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    /** caption 归一:trim,全空白视为没写,超长截到 140(DB 列宽 140,不截会报 1406)。 */
    static String normalizeCaption(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() <= CAPTION_MAX) return s;
        int end = CAPTION_MAX;
        if (end < s.length() && Character.isHighSurrogate(s.charAt(end - 1)) && Character.isLowSurrogate(s.charAt(end))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 幂等键归一:trim,全空白视为没送(idem_key=NULL 不参与 uk,老客户端照常入册)。
     *  ⚠️ 超长只拒不截:idem_key 列宽 64,截断会让两个前缀相同的不同键撞成同一张票,
     *  用户静默丢一枚邮票且毫无提示。宁可当场报错。 */
    static String normalizeIdemKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }

    /** 分页夹取:默认 20,上限 50 —— 集邮册数百张,不能让客户端一次拉光。 */
    static int clampPageSize(Integer raw) {
        if (raw == null || raw <= 0) return PAGE_SIZE_DEFAULT;
        return Math.min(raw, PAGE_SIZE_MAX);
    }

    /** 由 pageNum/size 算 offset。pageNum 极大时整数溢出会算出负 offset,MySQL LIMIT 拒绝→500。
     *  用 long 算再钳到 [0, MAX_OFFSET],彻底避免负数/溢出落进 SQL。 */
    static int safeOffset(Integer pageNum, int size) {
        long num = (pageNum == null || pageNum < 1) ? 1L : pageNum.longValue();
        long off = (num - 1L) * size;
        if (off < 0L) off = 0L;
        long MAX_OFFSET = 1_000_000L;   // 分页深到 100 万条已远超集邮册规模,超过就回第一页语义
        if (off > MAX_OFFSET) off = 0L;
        return (int) off;
    }

    /** Free photo (postcard): the original three-field call. */
    public AjaxResult create(String picUrl, String caption, String idempotencyKey) {
        return create(picUrl, caption, idempotencyKey, null, null, null);
    }

    @Operation(summary = "存一枚邮票(自由拍;带 poiId 与定位且开关打开时为地点盖章)")
    @PostMapping("/create")
    public AjaxResult create(String picUrl, String caption, String idempotencyKey, Long poiId, Double lat, Double lng) {
        Long memberId = uid();
        if (memberId <= 0) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        if (picUrl == null || picUrl.trim().isEmpty()) return error("图片不能为空");
        String idemKey = normalizeIdemKey(idempotencyKey);
        if (idemKey != null && idemKey.length() > IDEM_KEY_MAX) return error("幂等键过长");

        String checkedCaption = normalizeCaption(caption);
        if (checkedCaption != null && !checkedCaption.isEmpty()) {
            UmsMember author = umsMemberService.selectUmsMemberById(memberId);
            String risk = wxContentSecurityService.checkText(checkedCaption, author == null ? null : author.getWxOpenId());
            if (risk != null) return error(risk);
        }
        // GAME-12: a place stamp needs the place, a trusted fix and the player within radius + 50 m.
        com.chengyinhub.business.domain.vo.CityNodeVO place = null;
        String trustCode = null;
        if (placeStampEnabled && poiId != null && poiId > 0) {
            if (roamMapper == null) return error("地点盖章暂不可用，请稍后再试");
            place = roamMapper.selectPlayerMerchantNodeDetail(poiId, 0L);
            if (place == null || place.getStatus() == null || place.getStatus() != 1 || place.getLat() == null || place.getLng() == null) {
                return refuse(ErrorCode.ROAM_PLACE_NOT_FOUND, "这个地点暂时不能盖章");
            }
            if (lat == null || lng == null) return refuse(ErrorCode.LOCATION_REQUIRED, "需要定位才能给这个地点盖章");
            RoamStamp already = roamStampMapper.selectByMemberAndPoi(memberId, poiId);
            if (already != null) return success(placeReply(already.getId(), place, memberId, true, null));
            if (locationTrust != null) {
                com.chengyinhub.business.locationtrust.LocationTrustService.Assessment trusted = locationTrust.assess(
                        memberId, null, com.chengyinhub.business.locationtrust.LocationTrustKind.CITY_NODE, String.valueOf(poiId),
                        com.chengyinhub.business.locationtrust.LocationSignals.current(lat, lng));
                if (trusted.rejected()) {
                    return AjaxResult.error(com.chengyinhub.business.locationtrust.LocationTrustMessages.rejected(trusted.reasons()))
                            .put("trustCode", com.chengyinhub.business.locationtrust.LocationTrustMessages.code(trusted.reasons()))
                            .put("reasonCode", "STAMP_LOCATION_UNTRUSTED").put("retryable", true);
                }
                // A doubtful fix keeps the photo as a postcard; the place is not stamped.
                if (trusted.withheld()) {
                    trustCode = com.chengyinhub.business.locationtrust.LocationTrustMessages.code(trusted.reasons());
                    place = null;
                }
            }
            if (place != null && !placeStampWithinReach(place, lat, lng)) {
                return AjaxResult.error("离这个地点太远，到了再盖章").put("reasonCode", "STAMP_TOO_FAR").put("retryable", true);
            }
        }
        RoamStamp s = new RoamStamp();
        s.setMemberId(memberId);
        s.setPicUrl(picUrl.trim());
        s.setCaption(checkedCaption);
        s.setKind(place == null ? 0 : 1);   // 0 自由拍(明信片);1 地点盖章(GAME-12)
        s.setGeohash7(null);     // 普通票不占格,uk 允许多行 NULL;地点章按 uk_member_poi 去重,不按格(同格可有多个地点)
        if (place != null) {
            s.setPoiId(poiId);
            // 只存地点自己的坐标,不存玩家的定位点。
            s.setLat(place.getLat());
            s.setLng(place.getLng());
        }
        s.setCheckState(0);      // 0=未送检;送检成功后由回调迁到 1/2
        s.setIdemKey(idemKey);
        s.setDelFlag(0);
        try {
            roamStampMapper.insertStamp(s);
        } catch (DuplicateKeyException e) {
            // 撞了本表某道 uk。必须回查坐实撞的是 uk_member_idem —— 表上还有 uk_member_geohash(P1 盖销),
            // 两道 uk 抛的是同一个 DuplicateKeyException,只 catch 不回查就会把盖销重复
            // 当成幂等命中静默吞掉(相邻同型异常互相接住)。查不到就原样抛,不归这里管。
            //
            // ⚠️ 本方法**必须不带 @Transactional**:并发同键时 InnoDB 会让后到的 insert 等到
            // 赢家提交再抛 1062,autocommit 下这句回查是新快照、看得见赢家那行。若将来给
            // create 加上 @Transactional(REPEATABLE READ 快照在事务开始时定格),这句就查不到
            // 赢家 → existed=null → 原样抛 → 用户拿到 500 而不是幂等命中。要加事务先改这里。
            RoamStamp existed = idemKey == null ? null : roamStampMapper.selectByMemberAndIdemKey(memberId, idemKey);
            // GAME-12: a concurrent second stamp for the same place (uk_member_poi) answers with the first one.
            if (existed == null && place != null) {
                RoamStamp samePlace = roamStampMapper.selectByMemberAndPoi(memberId, poiId);
                if (samePlace != null) return success(placeReply(samePlace.getId(), place, memberId, true, null));
            }
            if (existed == null) throw e;
            Map<String, Object> hit = new LinkedHashMap<String, Object>();
            hit.put("id", existed.getId());
            hit.put("idempotent", true);   // 同键重放:返回原票,不再入册第二枚
            return success(hit);
        }

        submitStampMediaCheck(s, memberId);

        if (place != null) return success(placeReply(s.getId(), place, memberId, false, null));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", s.getId());
        if (placeStampEnabled && poiId != null && poiId > 0) {
            // The fix was doubtful: saved as a postcard, the place not stamped.
            data.put("kind", 0);
            data.put("placeStamped", false);
            if (trustCode != null) data.put("trustCode", trustCode);
        }
        return success(data);
    }

    /** GAME-12: reply for a place stamp: {id, kind, placeName, placesStamped, alreadyStamped}. */
    private Map<String, Object> placeReply(Long id, com.chengyinhub.business.domain.vo.CityNodeVO place, Long memberId,
                                           boolean alreadyStamped, String trustCode) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("kind", 1);
        data.put("placeStamped", true);
        data.put("alreadyStamped", alreadyStamped);
        data.put("placeName", place.getName());
        data.put("placesStamped", roamStampMapper.countPlacesByMember(memberId));
        if (trustCode != null) data.put("trustCode", trustCode);
        return data;
    }

    /** GAME-12: within the place radius (default 80 m) plus 50 m. */
    static boolean placeStampWithinReach(com.chengyinhub.business.domain.vo.CityNodeVO place, double lat, double lng) {
        int radius = place.getRadiusM() != null && place.getRadiusM() > 0 ? place.getRadiusM() : PLACE_STAMP_DEFAULT_RADIUS_M;
        double dist = com.chengyinhub.common.utils.GeoDistanceUtils.haversineMeters(lat, lng,
                place.getLat().doubleValue(), place.getLng().doubleValue());
        return dist <= radius + PLACE_STAMP_SLACK_M;
    }

    /**
     * 先发后审:票已入册即可见,图片异步送检;命中违规由 handleMediaCheckResult 置 check_state=2
     * 自动移出册子。
     *
     * ⚠️ 整段包 try:送检链路(取 openid / 打微信接口 / 回填 trace)任何异常都不能把一枚
     * 已经在库里的票变成 500 —— 那会让用户以为没存上而重拍,反倒制造重复。
     * 机审关闭或送检失败时票留在 check_state=0=未送检,这是诚实值:能一句 SQL 查出
     * 「哪些图逃过了机审」。送检失败的 ERROR 日志由 submitMediaCheckOne 内部落。
     */
    private void submitStampMediaCheck(RoamStamp s, Long memberId) {
        try {
            UmsMember publisher = umsMemberService.selectUmsMemberById(memberId);
            String openid = publisher != null ? publisher.getWxOpenId() : null;
            String traceId = wxContentSecurityService.submitMediaCheckOne(
                    IWxContentSecurityService.BIZ_TYPE_ROAM_STAMP, s.getId(), s.getPicUrl(), openid);
            if (traceId != null) {
                roamStampMapper.updateCheckTrace(s.getId(), traceId);
            }
        } catch (Exception e) {
            logger.error("[集邮] 邮票送检异常,该图逃过机审,需人工复审: stampId={}, exceptionType={}",
                    s.getId(), e.getClass().getSimpleName());
        }
    }

    /** 一次交换最多顺位试几张候选。撞 uk 只可能来自同一个人的并发换票,
     *  这个数字是「够用就行」的保险,不是随机池。 */
    static final int EXCHANGE_CANDIDATES = 5;

    @Operation(summary = "投一张换一张:投出自己的票,换回别人那张")
    @PostMapping("/exchange")
    public AjaxResult exchange(Long givenStampId) {
        Long memberId = uid();
        if (memberId <= 0) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        if (givenStampId == null || givenStampId <= 0) return refuse(ErrorCode.BAD_REQUEST, "参数有误");

        // 代价必须是我自己的、还在册的票。校验放在最前面 ——
        // 拿别人的票 id 当代价就等于白嫖一张,这是本玩法唯一的资损面。
        RoamStamp given = roamStampMapper.selectById(givenStampId);
        if (given == null || given.getDelFlag() == null || given.getDelFlag() != 0
                || !memberId.equals(given.getMemberId())) {
            return error("这张票不是你的");
        }

        // 幂等:同一张投出的票只兑换一次。重试/双击/弱网重发都拿回同一枚回礼,
        // 不会一次投票换走两张。先查再插,插的时候撞 uk 还会再查一次(见下)。
        RoamStampExchange existed = roamStampMapper.selectExchangeByGiven(memberId, givenStampId);
        if (existed != null) return success(exchangeResult(existed.getReceivedStampId(), true));

        List<Long> candidates = roamStampMapper.selectExchangeCandidates(memberId, EXCHANGE_CANDIDATES);
        if (candidates == null || candidates.isEmpty()) {
            // 空不是错:可能真没有别人的票,也可能机审还没放行。都如实说,不编一张出来。
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("exchanged", false);
            empty.put("reason", "还没有可以换的票");
            return success(empty);
        }

        for (Long candidateId : candidates) {
            RoamStampExchange row = new RoamStampExchange();
            row.setMemberId(memberId);
            row.setGivenStampId(givenStampId);
            row.setReceivedStampId(candidateId);
            try {
                roamStampMapper.insertExchange(row);
                return success(exchangeResult(candidateId, false));
            } catch (DuplicateKeyException e) {
                // 本表两道 uk 都抛同一个异常,必须回查坐实撞的是哪一道 ——
                // 只 catch 不回查,uk_member_received 的撞车会被当成幂等命中静默吞掉。
                RoamStampExchange hit = roamStampMapper.selectExchangeByGiven(memberId, givenStampId);
                if (hit != null) return success(exchangeResult(hit.getReceivedStampId(), true));
                // 撞的是 uk_member_received(这张我早就收过了):顺位换下一张候选
            }
        }
        Map<String, Object> exhausted = new LinkedHashMap<String, Object>();
        exhausted.put("exchanged", false);
        exhausted.put("reason", "还没有可以换的票");
        return success(exhausted);
    }

    /** 交换结果统一形状。取不到票(被判违规下架)时如实返回没换成,不返回一个空壳票。 */
    private Map<String, Object> exchangeResult(Long receivedStampId, boolean idempotent) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        RoamStamp received = receivedStampId == null ? null : roamStampMapper.selectById(receivedStampId);
        boolean usable = received != null && received.getDelFlag() != null && received.getDelFlag() == 0
                && received.getCheckState() != null && received.getCheckState() != 2;
        data.put("exchanged", usable);
        if (usable) {
            // ⚠️ 只回 RoamStampListVO:它不带 memberId,换到的票对收件人是匿名的。
            // 「TA 那句」是一句话,不是一个人 —— 露出作者就变成了社交,那是另一个产品。
            data.put("stamp", RoamStampListVO.from(received));
        } else {
            data.put("reason", "这张票已经下架了");
        }
        data.put("idempotent", idempotent);
        return data;
    }

    @Operation(summary = "我换回来的票(分页)")
    @PostMapping("/received")
    public AjaxResult received(Integer pageNum, Integer pageSize) {
        Long memberId = uid();
        if (memberId <= 0) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        int size = clampPageSize(pageSize);
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        List<RoamStamp> rows = roamStampMapper.selectReceivedByMember(memberId, safeOffset(pageNum, size), size);
        List<RoamStampListVO> safeRows = new ArrayList<RoamStampListVO>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (RoamStamp row : rows) safeRows.add(RoamStampListVO.from(row));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", safeRows);
        data.put("total", roamStampMapper.countReceivedByMember(memberId));
        data.put("pageNum", num);
        data.put("pageSize", size);
        return success(data);
    }

    @Operation(summary = "我的集邮册(分页)")
    @PostMapping("/list")
    public AjaxResult list(Integer pageNum, Integer pageSize) {
        Long memberId = uid();
        if (memberId <= 0) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        int size = clampPageSize(pageSize);
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        List<RoamStamp> rows = roamStampMapper.selectByMember(memberId, safeOffset(pageNum, size), size);
        List<RoamStampListVO> safeRows = new ArrayList<RoamStampListVO>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (RoamStamp row : rows) safeRows.add(RoamStampListVO.from(row));
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", safeRows);
        data.put("total", roamStampMapper.countByMember(memberId));
        // GAME-12: places stamped (place stamps only); free photos stay in `total`. Sent only while place stamps are on,
        // so the app never shows a "0 places" line for a feature that is switched off.
        if (placeStampEnabled) data.put("placesStamped", roamStampMapper.countPlacesByMember(memberId));
        data.put("pageNum", num);
        data.put("pageSize", size);
        return success(data);
    }
}
