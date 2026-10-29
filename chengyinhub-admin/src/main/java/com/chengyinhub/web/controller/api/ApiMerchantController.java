package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.MerchantMetricWindow;
import com.chengyinhub.business.domain.dto.MerchantDashboardIncome;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.IMerchantLeadService;
import com.chengyinhub.business.domain.MerchantSubscription;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MerchantAftercareMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import com.chengyinhub.business.domain.dto.MerchantRegistrationDTO;
import com.chengyinhub.business.domain.dto.MerchantCustomerQuery;
import com.chengyinhub.business.service.IProjectPlayerReadService;
import com.chengyinhub.business.domain.dto.MerchantLedgerQuery;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.domain.vo.PublicFeaturedActivityVO;
import com.chengyinhub.business.domain.vo.PublicFeaturedCouponVO;
import com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO;
import com.chengyinhub.business.domain.vo.PublicMerchantVO;
import com.chengyinhub.business.domain.vo.MerchantFinancePage;
import com.chengyinhub.business.domain.vo.MerchantRedemptionSummary;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.domain.vo.MerchantEditProfileVO;
import com.chengyinhub.business.domain.vo.ClubInviteCandidateVO;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 用户相关接口
 *
 * @author webxh
 * @date 2025-08-22
 */
@RestController
//@Api(tags = "01.通用")
@Tag(name = "商户相关")
@RequestMapping("/api/merchant")
public class ApiMerchantController extends BaseController {
    @Autowired
    private com.chengyinhub.business.service.ConsentGuard consentGuard;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private NpcProfileService npcProfileService;

    @Autowired
    private NpcProfileMapper npcProfileMapper;

    @Autowired
    private com.chengyinhub.business.service.impl.ProfileReviewService profileReviewService;

    @Autowired
    private VoiceCloneClient voiceCloneClient;

    @Autowired
    private com.chengyinhub.business.service.ai.NpcVoiceEnrollmentService voiceEnrollmentService;

    private static final int NPC_SCOPE_MERCHANT = 2;
    private static final String NPC_PRESET_PREFIX = "px1:";
    /** persona 是喂给对话模型的系统提示词,商家自己写;上限防止把一次对话的 token 预算撑爆。 */
    private static final int NPC_PERSONA_MAX = 500;
    private static final int NPC_KNOWLEDGE_MAX = 2000;


    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private com.chengyinhub.business.service.support.AppMediaReferenceGuard mediaReferences;

    private String reviewedMedia(Long memberId, String reference, boolean multiple) {
        if (reference == null || reference.trim().isEmpty()) return reference;
        return multiple ? mediaReferences.requireAttachments(memberId, reference, true)
                : mediaReferences.requireAttachment(memberId, reference, true);
    }

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    /** RUN-52 发布者实名:采集走 /api/publisher/identity,商家入驻只验「登记了没有」。 */
    @Autowired
    private com.chengyinhub.business.service.impl.MemberIdentityService memberIdentityService;

    @Autowired
    private IMapGeocodeService mapGeocodeService;

    /** 门店联系电话:11 位大陆手机号。 */
    static final java.util.regex.Pattern MERCHANT_PHONE = java.util.regex.Pattern.compile("1[3-9]\\d{9}");
    /** 门店微信号:字母、数字、下划线或减号,最多 20 位。 */
    static final java.util.regex.Pattern MERCHANT_WECHAT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,20}");
    /** 行业类型多选:逗号分隔的正整数 id,最多 MAX_MERCHANT_CATEGORIES 个。 */
    static final java.util.regex.Pattern MERCHANT_CATEGORY_IDS = java.util.regex.Pattern.compile("[1-9]\\d{0,8}(,[1-9]\\d{0,8})*");
    static final int MAX_MERCHANT_CATEGORIES = 5;

    /** location_verified:1=商家在地图上亲自点的。 */
    static final int LOCATION_PICKED = 1;
    /** location_verified:2=按地址反查推断的,没人确认过。0 是建表默认值,表示压根没坐标。 */
    static final int LOCATION_INFERRED = 2;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    @Autowired
    private IOmsOrderService omsOrderService;

    @Autowired
    private OmsOrderMapper omsOrderMapper;

    @Autowired
    private IProjectPlayerReadService projectPlayerReadService;

    @Autowired
    private ISettlementService settlementService;

    @Autowired
    private MerchantFinanceQueryService merchantFinanceQueryService;

    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    @Autowired
    private ICmsRegistrationScanService cmsRegistrationScanService;

    @Autowired
    private ISmsCouponService smsCouponService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private com.chengyinhub.business.service.IClubMemberService clubMemberService;

    @Autowired
    private IMerchantLeadService merchantLeadService;

    @Autowired
    private com.chengyinhub.business.service.ICmsActivityService cmsActivityService;

    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;

    @Autowired
    private MerchantSubscriptionMapper merchantSubscriptionMapper;

    @Autowired
    private IMerchantFulfillmentReadService merchantFulfillmentReadService;

    @Autowired
    private IMerchantAggregateReadService merchantAggregateReadService;

    @Autowired
    private ICoopInviteService coopInviteService;

    @Autowired
    private IMerchantNodeDiscoveryService merchantNodeDiscoveryService;

    @Autowired
    private com.chengyinhub.business.service.IMerchantInsightService merchantInsightService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private IPlayPredictService playPredictService;

    @Autowired
    private MerchantOperatorMapper merchantOperatorMapper;

    @Autowired
    private MerchantAftercareMapper merchantAftercareMapper;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Operation(summary = "用户详情，不传用户ID就是查询自己")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "member_id", description = "用户id")
    })
    public AjaxResult getUserInfo() {

        boolean isMy = false;
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (memberId < 1) {
            return AjaxResult.error("参数错误");
        }
        // 入驻申请页必须能回读“待审/驳回”状态；active guard 只适用于已生效商家与员工。
        // 先按 token 主体精确查 owner 自己的未删除申请，找不到时才进入统一操作员权限链。
        MmsMerchant mmsMerchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
        if (mmsMerchant == null) {
            if (merchantAccessService.resolve(memberId) == null) {
                AjaxResult result = AjaxResult.success();
                result.put("applicationState", "NONE");
                return result;
            }
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROFILE_WRITE);
            mmsMerchant = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        } else if (!memberId.equals(mmsMerchant.getMemberId())
                || !Integer.valueOf(0).equals(mmsMerchant.getDelFlag())) {
            throw new ServiceException("商家申请归属无效或账号不可用", 403);
        }
        if (mmsMerchant == null) {
            return AjaxResult.error("数据获取失败");
        } else {

            // 查询分类信息，categoryId 可能为 null
            if (categoryIdsOf(mmsMerchant) != null) {
                String categoryIds = categoryIdsOf(mmsMerchant);
                if (!StringUtils.isEmpty(categoryIds)) {
                    String[] ids = categoryIds.split(",");
                    List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                    if (listCategory.size() > 0) {
                        mmsMerchant.setSysCategoryList(listCategory);
                    }
                }
            }

            return success(mmsMerchant);
        }
    }

    @Operation(summary = "附近商家目录(含未注册商家,可电话联系)")
    @PostMapping("/nearby")
    @Parameters({
            @Parameter(name = "longitude", description = "当前经度"),
            @Parameter(name = "latitude", description = "当前纬度"),
            @Parameter(name = "radius", description = "半径(米),默认5000,上限50000"),
            @Parameter(name = "limit", description = "条数,默认30,上限100")
    })
    public AjaxResult nearby(String longitude, String latitude, String radius, String limit) {
        if (StringUtils.isEmpty(longitude) || StringUtils.isEmpty(latitude)) {
            return error("缺少定位");
        }
        double lng, lat;
        try {
            lng = Double.parseDouble(longitude);
            lat = Double.parseDouble(latitude);
        } catch (Exception e) {
            return error("定位参数错误");
        }
        int r = parseIntOr(radius, 5000);
        if (r <= 0 || r > 50000) r = 5000;   // 限半径,防全城扫描
        int l = parseIntOr(limit, 30);
        if (l <= 0 || l > 100) l = 30;        // 限条数
        // 电话只对商家身份返回(未注册商家/玩家看不到电话;俱乐部主理人可后续按需放开)
        boolean canSeePhone = false;
        try {
            Long memberId = getAppUserId();
            if (memberId != null && memberId > 0) {
                merchantAccessService.require(memberId, MerchantPermission.CRM_SENSITIVE_READ);
                canSeePhone = true;
            }
        } catch (Exception ignore) {}
        return success(merchantLeadService.nearby(lat, lng, r, l, canSeePhone));
    }

    private int parseIntOr(String s, int dft) {
        if (StringUtils.isEmpty(s)) return dft;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dft; }
    }

    @Operation(summary = "读营业状态(营业中/已打烊)")
    @PostMapping("/business-status")
    public AjaxResult businessStatus() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.BASIC_READ);
        MmsMerchant m = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (m == null) return error("数据获取失败");
        int bs = m.getBusinessStatus() == null ? 1 : m.getBusinessStatus();
        Map<String, Object> d = new HashMap<>();
        d.put("businessStatus", bs);
        d.put("businessStatusText", bs == 1 ? "营业中" : "已打烊");
        return success(d);
    }

    @Operation(summary = "改营业状态(纯展示,不做门禁)")
    @PostMapping("/business-status/update")
    @Parameters({ @Parameter(name = "business_status", description = "1营业中 0已打烊") })
    public AjaxResult businessStatusUpdate(String business_status) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        MmsMerchant m = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (m == null) return error("数据获取失败");
        int bs;
        try { bs = Integer.parseInt(business_status); } catch (Exception e) { return error("营业状态参数错误"); }
        if (bs != 0 && bs != 1) return error("营业状态参数错误");
        MmsMerchant upd = new MmsMerchant();
        upd.setId(m.getId());
        upd.setBusinessStatus(bs);
        int rows = mmsMerchantService.updateAuthorizedProfile(
                upd, memberId, MerchantPermission.PROFILE_WRITE);
        if (rows != 1) {
            MmsMerchant current = rows == 0
                    ? mmsMerchantService.selectMmsMerchantById(m.getId()) : null;
            if (current == null || !Objects.equals(current.getBusinessStatus(), bs)) {
                throw merchantWriteConflict();
            }
        }
        return success(bs == 1 ? "已营业" : "已打烊");
    }

    @Operation(summary = "商家公开主页(匿名可访问,白名单)")
    @PostMapping("/public-home")
    @Parameters({
            @Parameter(name = "id", description = "商家ID"),
            @Parameter(name = "memberId", description = "商家会员ID")
    })
    public AjaxResult publicHome(@RequestBody(required = false) Map<String, Object> body) {
        Long id = body == null ? null : aggregateLong(body.get("id"));
        Long memberId = body == null ? null : aggregateLong(body.get("memberId"));
        // 失败关闭:必须恰好给一个 ID。都给会让两条查询路径的可见性判定含糊,都不给等于全表公开入口。
        if ((id == null) == (memberId == null)) return error("请提供商家ID或会员ID其中之一");
        if ((id != null && id < 1) || (memberId != null && memberId < 1)) return error("商家不存在或未开放");

        MmsMerchant m = id != null
                ? mmsMerchantService.selectMmsMerchantById(id)
                : mmsMerchantService.selectMmsMerchantByMemberId(memberId);
        if (!isPubliclyVisible(m)) return error("商家不存在或未开放");

        PublicMerchantVO vo = PublicMerchantVO.from(m);
        vo.setSysCategoryList(publicCategories(categoryIdsOf(m)));
        // 装修里选中的招牌主推(活动/券)。旧 public-detail 一直有这张卡,统一主页漏了 ——
        // 商家在装修页看到"发布后即可选一个置顶到主页",公开主页却什么都不显示。
        // 归属与公开状态每次匿名读取都重新核验,保存时合法不代表此刻仍公开。
        vo.setFeatured(resolvePublicFeatured(m));
        // 门店 AI 形象:取不到就留 null,主页整块不渲染。不能因为形象查询出错就把整个主页判失败。
        try {
            vo.setNpc(npcProfileService.resolveForMerchant(m.getId()));
        } catch (Exception e) {
            logger.warn("[merchant/public-home] npc resolve failed, merchantId={}, errorType={}",
                    m.getId(), e.getClass().getName());
        }
        return success(vo);
    }

    /** 公开可见 = 审核通过 && 账号启用 && 未删除。未知值一律失败关闭。 */
    private boolean isPubliclyVisible(MmsMerchant m) {
        return m != null
                && m.getStatus() != null && m.getStatus() == 1L
                && m.getAccountStatus() != null && m.getAccountStatus() == 1L
                && m.getDelFlag() != null && m.getDelFlag() == 0;
    }

    /** 品类只出 id/categoryName,不透出分类状态、删除标记与审计字段。 */
    /** 行业类型 id 串:多选列 category_ids 优先,迁移前/老数据回退到单值 category_id。 */
    static String categoryIdsOf(MmsMerchant m) {
        if (m == null) return null;
        if (m.getCategoryIds() != null && !m.getCategoryIds().trim().isEmpty()) return m.getCategoryIds().trim();
        return m.getCategoryId() == null ? null : m.getCategoryId().toString();
    }

    private List<PublicMemberVO.PublicCategoryVO> publicCategories(String categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return null;
        List<SysCategory> cats = sysCategoryService.selectSysCategoryListByIds(categoryIds.split(","));
        if (cats == null || cats.isEmpty()) return null;
        List<PublicMemberVO.PublicCategoryVO> out = new java.util.ArrayList<>();
        for (SysCategory c : cats) {
            PublicMemberVO.PublicCategoryVO item = new PublicMemberVO.PublicCategoryVO();
            item.setId(c.getId());
            item.setCategoryName(c.getCategoryName());
            out.add(item);
        }
        return out;
    }

    @Operation(summary = "商家公开主页(匿名可访问,脱敏) —— 旧客户端合同,新前端请用 /public-home")
    @PostMapping("/public-detail")
    public AjaxResult publicDetail(@RequestBody MmsMerchant body) {
        if (body == null || body.getId() == null) return error("缺少商家ID");
        MmsMerchant m = mmsMerchantService.selectMmsMerchantById(body.getId());
        if (!isPubliclyVisible(m)) {
            return error("商家不存在或未开放");
        }
        PublicMerchantDiscoveryVO publicMerchant = PublicMerchantDiscoveryVO.from(m);
        publicMerchant.setSysCategoryList(publicCategories(categoryIdsOf(m)));
        AjaxResult ajax = success(publicMerchant);
        ajax.put("officialActivities", publicOfficialActivities(
                coopInviteService.listAwardedOfficialActivities(m.getMemberId())));
        Object featured = resolvePublicFeatured(m);
        if (featured != null) ajax.put("featured", featured);
        return ajax;
    }

    /**
     * 招牌主推卡的匿名解析(public-detail 与统一主页 public-home 共用同一份口径)。
     * 归属/公开状态每次读取都重新核验;类型未知、已下架、已易主、查询异常一律返回 null,
     * 宁可主页不显示这张卡,也不能把别家或已下架的内容挂上去。
     */
    private Object resolvePublicFeatured(MmsMerchant m) {
        if (m == null || m.getFeaturedType() == null || m.getFeaturedType() == 0 || m.getFeaturedId() == null) {
            return null;
        }
        try {
            if (m.getFeaturedType() == 1) {
                com.chengyinhub.business.domain.CmsActivity activity =
                        cmsActivityService.selectPublicCmsActivityById(m.getFeaturedId());
                if (isOwnedPublicActivity(activity, m.getMemberId())) {
                    return PublicFeaturedActivityVO.from(activity);
                }
            } else if (m.getFeaturedType() == 2) {
                SmsCoupon coupon = smsCouponService.selectSmsCouponById(m.getFeaturedId());
                if (isOwnedActiveCoupon(coupon, m.getMemberId(), m.getId())) {
                    return PublicFeaturedCouponVO.from(coupon);
                }
            }
        } catch (Exception ignore) {
            // 主推卡是可选装饰:查询失败只丢这张卡,不能把整个公开主页判失败。
        }
        return null;
    }

    @Operation(summary = "商家搜索/发现列表(E5,公开脱敏:按名/品类/探索标签/城市角色;传 originLat/originLng 则按距离升序)")
    @PostMapping("/list")
    public AjaxResult merchantList(@RequestBody(required = false) MerchantSearchQuery query) {
        if (query == null) query = new MerchantSearchQuery();
        query.setStatus(1L);   // 仅审核通过的商家对外可见
        query.setAccountStatus(1L);
        query.setDelFlag(0);
        // 公开搜索只允许 name/category/tags/cityRole 与定位。清空 Mapper 其他筛选字段,
        // 否则即使响应已脱敏,仍可用「命中/未命中」探测手机号、微信等私密信息。
        query.setMemberId(null);
        query.setWechat(null);
        query.setLogo(null);
        query.setDescription(null);
        query.setDerivatives(null);
        query.setWebsite(null);
        query.setPreference(null);
        query.setPhone(null);
        query.setAddress(null);
        query.setBusinessTime(null);
        query.setReson(null);
        query.setCoopOpen(null);
        // 有定位(originLat/originLng 都传)→ 按距离升序;否则回退原排序,行为零变
        boolean geoSort = query.getOriginLat() != null && query.getOriginLng() != null;
        startPage();
        List<? extends MmsMerchant> list = geoSort
                ? mmsMerchantService.selectMerchantListByDistance(query)
                : mmsMerchantService.selectMmsMerchantList(query);
        List<PublicMerchantDiscoveryVO> publicRows = new java.util.ArrayList<>();
        // kept 与 publicRows 一一对齐(跳过 null 行),品类回填按下标配对不会错位
        List<MmsMerchant> kept = new java.util.ArrayList<>();
        if (list != null) {
            for (MmsMerchant m : list) {
                if (m != null) {
                    publicRows.add(PublicMerchantDiscoveryVO.from(m));
                    kept.add(m);
                }
            }
        }
        fillDiscoveryCategories(publicRows, kept);
        return success(publicRows);
    }

    /**
     * CU-M-61(2026-09-24 走查):发现列表也要带品类名 —— 商家卡无封面/Logo 时按品类出图标,
     * 拿不到品类就只能全员同一只咖啡杯(书店和花店长得一模一样)。同一页的 category_id 一次
     * 查完(不定长 CSV 展开),不按行重复查库;品类仍只出 id/categoryName 两个字段。
     */
    private void fillDiscoveryCategories(List<PublicMerchantDiscoveryVO> rows, List<? extends MmsMerchant> sources) {
        if (rows == null || rows.isEmpty() || sources == null || sources.isEmpty()) return;
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (MmsMerchant m : sources) {
            if (m == null || m.getCategoryId() == null) continue;
            for (String part : m.getCategoryId().toString().split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) ids.add(trimmed);
            }
        }
        if (ids.isEmpty()) return;
        List<SysCategory> cats = sysCategoryService.selectSysCategoryListByIds(ids.toArray(new String[0]));
        if (cats == null || cats.isEmpty()) return;
        Map<String, PublicMemberVO.PublicCategoryVO> byId = new HashMap<>();
        for (SysCategory c : cats) {
            PublicMemberVO.PublicCategoryVO item = new PublicMemberVO.PublicCategoryVO();
            item.setId(c.getId());
            item.setCategoryName(c.getCategoryName());
            byId.put(String.valueOf(c.getId()), item);
        }
        for (int i = 0; i < rows.size() && i < sources.size(); i++) {
            MmsMerchant m = sources.get(i);
            if (m == null || m.getCategoryId() == null) continue;
            List<PublicMemberVO.PublicCategoryVO> own = new ArrayList<>();
            for (String part : m.getCategoryId().toString().split(",")) {
                PublicMemberVO.PublicCategoryVO hit = byId.get(part.trim());
                if (hit != null) own.add(hit);
            }
            if (!own.isEmpty()) rows.get(i).setSysCategoryList(own);
        }
    }

    @Operation(summary = "我的付费权益(E4,先做能力:读已授予的生效权益)")
    @PostMapping("/subscription")
    public AjaxResult mySubscription() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.BASIC_READ);
        MerchantSubscription q = new MerchantSubscription();
        q.setMerchantId(access.getOwnerMemberId());
        q.setIsActive(1);
        return success(merchantSubscriptionMapper.selectMerchantSubscriptionList(q));
    }

    @Operation(summary = "商家承接档案(读自己的)")
    @PostMapping("/coop-profile")
    public AjaxResult coopProfile() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.COOP_MANAGE);
        MmsMerchant m = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (m == null) return error("商家不存在");
        MerchantEditProfileVO profile = MerchantEditProfileVO.from(m);
        if (!access.hasPermission(MerchantPermission.PROFILE_WRITE)) {
            // 联系方式只给能改资料的岗位(店主/店长)回显;只管承接的运营看不到
            profile.setPhone(null);
            profile.setWechat(null);
            profile.setWechatQrcode(null);
        }
        profile.setSysCategoryList(publicCategories(categoryIdsOf(m)));
        return success(profile);
    }

    /** 匿名商家页只公开已中标主题的展示信息，绝不传合作报价与内部 ID。 */
    private List<Map<String, Object>> publicOfficialActivities(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            if (row == null || row.get("topicId") == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("topicId", row.get("topicId"));
            if (row.get("title") != null) item.put("title", row.get("title"));
            if (row.get("cover") != null) item.put("cover", row.get("cover"));
            out.add(item);
        }
        return out;
    }

    @Operation(summary = "保存商家承接档案(自己的)")
    @Log(title = "商家承接档案", businessType = BusinessType.UPDATE)
    /** 把多个图片字段(单图/CSV)合成一段 ; 分隔串供 submitMediaCheck(空字段忽略) */
    private String mediaCsv(String... imgs) {
        StringBuilder sb = new StringBuilder();
        for (String s : imgs) if (s != null && s.trim().length() > 0) { if (sb.length() > 0) sb.append(";"); sb.append(s); }
        return sb.toString();
    }

    private ServiceException merchantWriteConflict() {
        return new ServiceException("商家资料已变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    private boolean matchesIfSupplied(Object requested, Object persisted) {
        return requested == null || Objects.equals(requested, persisted);
    }

    private boolean coopProfileMatches(MmsMerchant current, MmsMerchant request) {
        return current != null
                && matchesIfSupplied(request.getCapacity(), current.getCapacity())
                && matchesIfSupplied(request.getAvailableTime(), current.getAvailableTime())
                && matchesIfSupplied(request.getSuitActivityTypes(), current.getSuitActivityTypes())
                && matchesIfSupplied(request.getChargeType(), current.getChargeType())
                && matchesIfSupplied(request.getDemand(), current.getDemand())
                && matchesIfSupplied(request.getCoopOpen(), current.getCoopOpen());
    }

    private boolean decorMatches(MmsMerchant current, MmsMerchant request) {
        if (current == null
                || !matchesIfSupplied(request.getCoverImage(), current.getCoverImage())
                || !matchesIfSupplied(request.getSlogan(), current.getSlogan())
                || !matchesIfSupplied(request.getCityRole(), current.getCityRole())
                || !matchesIfSupplied(request.getStoryTitle(), current.getStoryTitle())
                || !matchesIfSupplied(request.getGallery(), current.getGallery())
                || !matchesIfSupplied(request.getTags(), current.getTags())
                || !matchesIfSupplied(request.getFeaturedType(), current.getFeaturedType())
                || !matchesIfSupplied(request.getFeaturedId(), current.getFeaturedId())
                || !matchesIfSupplied(request.getServiceTag(), current.getServiceTag())
                || !matchesIfSupplied(request.getServiceText(), current.getServiceText())
                || !matchesIfSupplied(request.getCategoryId(), current.getCategoryId())
                || !matchesIfSupplied(request.getCategoryIds(), current.getCategoryIds())
                || !matchesIfSupplied(request.getAddress(), current.getAddress())) {
            return false;
        }
        if (request.getLocationLat() != null && request.getLocationLng() != null) {
            return Objects.equals(request.getLocationLat(), current.getLocationLat())
                    && Objects.equals(request.getLocationLng(), current.getLocationLng())
                    && Integer.valueOf(LOCATION_PICKED).equals(current.getLocationVerified());
        }
        return true;
    }

    /** 商家 openid(内容安全检测用) */
    private String merchantOpenid(Long memberId) {
        com.chengyinhub.business.domain.UmsMember u = umsMemberService.selectUmsMemberById(memberId);
        return u == null ? null : u.getWxOpenId();
    }

    @PostMapping("/coop-profile/save")
    public AjaxResult saveCoopProfile(@RequestBody MmsMerchant body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.COOP_MANAGE);
        MmsMerchant m = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (m == null) return error("商家不存在");
        // 内容安全:合作需求自由文本命中违规即拒(商家资料匿名可访问公开)
        String secMsg = wxContentSecurityService.checkText(IWxContentSecurityService.joinText(body.getDemand(),
                        body.getAvailableTime(), body.getSuitActivityTypes()),
                merchantOpenid(access.getOwnerMemberId()));
        if (secMsg != null) return error(secMsg);
        MmsMerchant upd = new MmsMerchant();
        upd.setId(m.getId());
        upd.setCapacity(body.getCapacity());
        upd.setAvailableTime(body.getAvailableTime());
        upd.setSuitActivityTypes(body.getSuitActivityTypes());
        upd.setChargeType(body.getChargeType());
        upd.setDemand(body.getDemand());
        upd.setCoopOpen(body.getCoopOpen());
        // 接待人数是 Integer,null 只能表示「不改」;清空须显式声明 params.clearCapacity=true(Service 判)
        upd.getParams().put(MmsMerchant.CLEAR_CAPACITY, body.getParams().get(MmsMerchant.CLEAR_CAPACITY));
        int rows = mmsMerchantService.updateAuthorizedProfile(
                upd, memberId, MerchantPermission.COOP_MANAGE);
        if (rows != 1) {
            MmsMerchant current = rows == 0
                    ? mmsMerchantService.selectMmsMerchantById(m.getId()) : null;
            if (!coopProfileMatches(current, body)) {
                throw merchantWriteConflict();
            }
        }
        return AjaxResult.success("已保存");
    }

    @Operation(summary = "保存店铺装修展示层(自己的,分区块即时保存)")
    @Log(title = "店铺装修", businessType = BusinessType.UPDATE)
    @PostMapping("/decor/save")
    public AjaxResult saveDecor(@RequestBody MmsMerchant body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        MmsMerchant m = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (m == null) return error("请先提交商家入驻申请");
        if (body.getAddress() != null && (body.getAddress().length() > 255
                || body.getLocationLat() == null || body.getLocationLng() == null)) return error("请在地图上选择完整门店地址");
        // 长度闸:超出列宽(varchar(8)/varchar(64))会落库报错,提前拒
        if (body.getServiceTag() != null && body.getServiceTag().length() > 8) return error("服务标签最多8个字");
        if (body.getServiceText() != null && body.getServiceText().length() > 64) return error("服务文案最多64个字");
        // 内容安全:slogan/故事标题/标签等展示文本命中违规即拒
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(body.getSlogan(), body.getStoryTitle(), body.getTags(),
                        body.getServiceTag(), body.getServiceText(), body.getAddress(), body.getCityRole()),
                merchantOpenid(access.getOwnerMemberId()));
        if (secMsg != null) return error(secMsg);
        String featuredError = validateFeaturedSelection(
                body.getFeaturedType(), body.getFeaturedId(), access);
        if (featuredError != null) return error(featuredError);
        // 行业类型多选(owner 2026-10-22):categoryIds 是多选真源,categoryId 恒为其中第一个,
        // 只认单类目的旧读者(搜索筛选/卡片图标)继续读 category_id。老客户端只传 categoryId 时两列同步成单选。
        String categoryIds = null;
        Integer primaryCategoryId = body.getCategoryId();
        if (body.getCategoryIds() != null) {
            String raw = body.getCategoryIds().trim();
            if (raw.isEmpty()) return error("请至少选择一个行业类型");
            if (!MERCHANT_CATEGORY_IDS.matcher(raw).matches()) return error("行业类型参数错误");
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(java.util.Arrays.asList(raw.split(",")));
            if (ids.size() > MAX_MERCHANT_CATEGORIES) return error("行业类型最多选 " + MAX_MERCHANT_CATEGORIES + " 个");
            List<SysCategory> found = sysCategoryService.selectSysCategoryListByIds(ids.toArray(new String[0]));
            if (found == null || found.size() != ids.size()) return error("行业类型已变化，请刷新后重选");
            categoryIds = String.join(",", ids);
            primaryCategoryId = Integer.valueOf(ids.iterator().next());
        } else if (primaryCategoryId != null) {
            categoryIds = String.valueOf(primaryCategoryId);
        }
        // 白名单:只允许改展示层字段 + 行业类型(categoryId/categoryIds),按 memberId 定位自己的商家,防 IDOR
        MmsMerchant upd = new MmsMerchant();
        upd.setId(m.getId());
        upd.setCoverImage(reviewedMedia(memberId, body.getCoverImage(), false));
        upd.setSlogan(body.getSlogan());
        upd.setCityRole(body.getCityRole());
        upd.setStoryTitle(body.getStoryTitle());
        upd.setGallery(reviewedMedia(memberId, body.getGallery(), true));
        upd.setTags(body.getTags());
        upd.setFeaturedType(body.getFeaturedType());
        upd.setFeaturedId(body.getFeaturedId());
        upd.setServiceTag(body.getServiceTag());
        upd.setServiceText(body.getServiceText());
        if (primaryCategoryId != null) upd.setCategoryId(primaryCategoryId);
        if (categoryIds != null) upd.setCategoryIds(categoryIds);
        // 位置:verified 由服务端按"是否提交了完整坐标"判定,不信任客户端自证的 locationVerified(防自证位置)
        if (body.getLocationLat() != null && body.getLocationLng() != null) {
            upd.setLocationLat(body.getLocationLat());
            upd.setLocationLng(body.getLocationLng());
            upd.setLocationVerified(1);
            if (body.getAddress() != null) upd.setAddress(body.getAddress());
        }
        int rows = mmsMerchantService.updateAuthorizedProfile(
                upd, memberId, MerchantPermission.PROFILE_WRITE);
        if (rows != 1) {
            MmsMerchant current = rows == 0
                    ? mmsMerchantService.selectMmsMerchantById(m.getId()) : null;
            if (!decorMatches(current, upd)) {
                throw merchantWriteConflict();
            }
        }
        if (body.getTags() != null) merchantNodeDiscoveryService.syncTags(m.getId(), body.getTags());
        // 封面/相册/logo 异步内容安全送检,违规经回调自动清除(BIZ_TYPE_MERCHANT_PROFILE)
        try {
            wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_MERCHANT_PROFILE, m.getId(),
                    mediaCsv(body.getCoverImage(), body.getGallery(), body.getLogo()),
                    merchantOpenid(access.getOwnerMemberId()));
        } catch (Exception ignore) {}
        return AjaxResult.success("已保存");
    }

    // ===== 门店 AI 形象(npc_profile scope_type=2)=====
    //
    // 与后台 CmsMerchantNpcController 的分工:那边是运营超管改任意商家的,这边是商家改自己的。
    // 两边都写同一张表,所以这里必须自己校 scope_id —— 只按 id 更新会变成谁都能改别人的形象。

    /**
     * 商家读自己的形象(含停用的),给装修页做编辑态回填。
     * 用 selectMerchantNpcList 而不是 resolveForMerchant:后者只返回启用且过审的,
     * 编辑页要能看见自己刚提交、还没过审的那条,否则会以为没保存上。
     */
    @PostMapping("/npc/profile")
    public AjaxResult npcProfile() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        List<NpcProfile> list = npcProfileMapper.selectMerchantNpcList(access.getMerchantId(), null);
        return success(list == null || list.isEmpty() ? null : list.get(0));
    }

    /**
     * 保存门店形象。avatar 两种合法形态:
     *   px1:xxx   小程序内置的预设像素形象,存编码不存图
     *   图片 URL   商家上传的照片,已在端上处理成像素画再上传
     * 其余一律拒 —— 不校验的话这个字段会变成任意字符串的垃圾场,而它最终要进 img src。
     */
    @PostMapping("/npc/save")
    public AjaxResult npcSave(@RequestBody NpcProfile body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        if (body == null) return error("参数有误");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        Long merchantId = access.getMerchantId();
        if (mmsMerchantService.selectMmsMerchantById(merchantId) == null) {
            return error("请先提交商家入驻申请");
        }

        String name = trimOrNull(body.getName());
        String avatar = trimOrNull(body.getAvatar());
        String greeting = body.getGreeting() == null ? null : body.getGreeting().trim();
        // persona 是门店分身的「人设」系统提示词,只有玩家点「和 TA 聊聊」时消费。
        // null = 本次不改;显式空串 = 清空(清空后玩家侧回到「分身还没上线」)。
        // M-28:性格改为预设(gentle/cute/savage,也认中文标签);旧版自由填写的人设原样保留为 legacy。
        String persona = com.chengyinhub.business.support.NpcPersonality.normalize(body.getPersona());
        String knowledge = body.getKnowledge() == null ? null : body.getKnowledge().trim();
        if (name == null) return error("请填写形象名字");
        if (name.length() > 32) return error("名字最多 32 个字");
        if (avatar == null) return error("请选择形象");
        if (avatar.length() > 255) return error("形象地址过长");
        if (greeting != null && greeting.length() > 255) return error("招呼语最多 255 个字");
        if (persona != null && persona.length() > NPC_PERSONA_MAX) {
            return error("性格设定最多 " + NPC_PERSONA_MAX + " 个字");
        }
        if (knowledge != null && knowledge.length() > NPC_KNOWLEDGE_MAX) {
            return error("店铺知识最多 " + NPC_KNOWLEDGE_MAX + " 个字");
        }
        boolean preset = com.chengyinhub.business.support.NpcAvatar.isPreset(avatar);
        if (!com.chengyinhub.business.support.NpcAvatar.isAllowed(avatar)) {
            return error("形象格式不支持");
        }

        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(name, greeting, persona, knowledge),
                merchantOpenid(access.getOwnerMemberId()));
        if (secMsg != null) return error(secMsg);

        List<NpcProfile> exists = npcProfileMapper.selectMerchantNpcList(merchantId, null);
        NpcProfile mine = (exists == null || exists.isEmpty()) ? null : exists.get(0);

        NpcProfile save = new NpcProfile();
        save.setName(name);
        save.setAvatar(avatar);
        save.setGreeting(greeting);
        if (persona != null) save.setPersona(persona);
        if (knowledge != null) save.setKnowledge(knowledge);
        // M-28 先发后审:文本已同步过内容安全,保存即对玩家生效(玩家读闸只放 audit_status=1);
        // 上传的形象图随后异步送检,违规由回调打回。唯一例外:被驳回过、且形象图没换的上传图,
        // 不能靠重新保存绕过机审结论,仍回到待审。
        boolean keepsRejectedPhoto = mine != null && Integer.valueOf(2).equals(mine.getAuditStatus())
                && !preset && avatar.equals(mine.getAvatar());
        save.setAuditStatus(keepsRejectedPhoto ? 0 : 1);
        if (mine == null) {
            save.setScopeType(NPC_SCOPE_MERCHANT);
            save.setScopeId(merchantId);
            // persona 是 NOT NULL 列;商家没填人设就落空串,不编一段假人设塞进去。
            if (save.getPersona() == null) save.setPersona("");
            save.setSort(0);
            save.setEnabled(1);
            if (npcProfileMapper.insertMerchantNpc(save) != 1) return error("形象未保存，请重试");
        } else {
            // 防 IDOR:只按 id 更新等于谁都能改别人的形象。id 从库里查出来,
            // 且必须是自己名下这条,不接受客户端传的 id。
            if (mine.getScopeId() == null || !mine.getScopeId().equals(merchantId)) {
                return error("无权修改该形象");
            }
            save.setId(mine.getId());
            save.setScopeId(merchantId);
            if (npcProfileMapper.updateMerchantSelfNpc(save) != 1) return error("形象未保存，请重试");
        }

        // 上传的照片要走异步内容安全;预设是内置资源,没有送检的意义。
        // 送检对象必须是这一行 NPC(BIZ_TYPE_MERCHANT_NPC,bizId=npc_profile.id),不能借 MERCHANT_PROFILE
        // (bizId=merchantId):那条回调核对的是商家 logo/封面/相册,头像哈希永远对不上,违规头像会被当旧媒体放过。
        Long npcId = mine != null ? mine.getId() : save.getId();
        if (!preset && npcId != null) {
            try {
                // 单图走 submitMediaCheckOne:submitMediaCheck 是多图接口,返回不了单一 trace_id
                wxContentSecurityService.submitMediaCheckOne(
                        IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, npcId,
                        avatar, merchantOpenid(access.getOwnerMemberId()));
            } catch (Exception ignore) {}
        }
        AjaxResult ok = AjaxResult.success("已保存");
        // #25:保存 ≠ 已上线。回执说明人设待审、玩家此刻能否看到、形象图复核到哪一步。
        // 回执读失败不能把已经成功的保存变成报错:缺回执时客户端按「审核中」展示。
        try {
            List<NpcProfile> saved = npcProfileMapper.selectMerchantNpcList(merchantId, null);
            ok.put("receipt", profileReviewService.merchantNpcReceipt(merchantId,
                    saved == null || saved.isEmpty() ? null : saved.get(0)));
        } catch (RuntimeException receiptUnavailable) {
            logger.warn("[npc/save] 审核回执暂不可用 merchantId={}", merchantId);
        }
        return ok;
    }

    /** 门店 AI 形象审核回执(#25):只读,归属同 /npc/profile(当前账号所属商家 + PROFILE_WRITE)。 */
    @GetMapping("/npc/review")
    public AjaxResult npcReview() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);
        List<NpcProfile> list = npcProfileMapper.selectMerchantNpcList(access.getMerchantId(), null);
        return success(profileReviewService.merchantNpcReceipt(access.getMerchantId(),
                list == null || list.isEmpty() ? null : list.get(0)));
    }

    // ===== 门店 AI 形象的声音 =====
    //
    // 商家录一段话 → 上传拿到音频 URL → 这里发起复刻 → 存音色 id。
    // 复刻不是立刻可用,中间有部署过程,所以状态必须落库:只看 voice_id 有没有值,
    // 会在音色还没就绪时就拿去合成,得到一个长得像「服务抖了一下」的失败。

    /** voice_status:0=未配置 1=复刻中 2=可用 3=复刻失败 */
    private static final int VOICE_NONE = 0;
    private static final int VOICE_PENDING = 1;
    private static final int VOICE_READY = 2;
    private static final int VOICE_FAILED = 3;

    /**
     * 用商家上传的录音创建音色。
     *
     * 录音要求由上游定:mp3/m4a/wav、10 秒到 5 分钟、20MB 以内。这里不重复校验时长
     * (拿不到音频时长,校验只能是假的),但会把上游的拒绝原因原样带回给商家。
     */
    @PostMapping("/npc/voice/enroll")
    public AjaxResult npcVoiceEnroll(@RequestBody NpcProfile body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        consentGuard.requireCurrentAgreement(memberId, "voice_clone_processing", "merchant_voice");
        if (body == null) return error("参数有误");
        if (!Boolean.TRUE.equals(body.getVoiceConsent())) return error("请先确认声音为本人所有或已获得授权");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);

        String sample = trimOrNull(body.getVoiceSample());
        if (sample == null) return error("请先录一段话");
        if (sample.length() > 4096) return error("录音地址过长");
        if (!sample.matches("media-asset:[1-9][0-9]{0,18}") && !sample.startsWith("/") && !sample.startsWith("https://") && !sample.startsWith("http://")) return error("录音地址不支持");

        NpcProfile mine = ownNpcOrNull(access.getMerchantId());
        if (mine == null) return error("请先保存形象,再给它配声音");
        sample = mediaReferences.requireAttachment(memberId, sample, true);
        if (sample.length() > 255) return error("录音引用过长");
        final String canonicalSample = sample;
        NpcProfile result;
        try {
            result = voiceEnrollmentService.enroll(memberId, NPC_SCOPE_MERCHANT, access.getMerchantId(),
                    mine.getId(), canonicalSample, body.getVoiceRequestId(), body.getVoicePreviousIntentId(),
                    Boolean.TRUE.equals(body.getVoiceDuplicateChargeConsent()),
                    () -> mediaReferences.processingUrl(canonicalSample, memberId, "AUDIO"));
        } catch (com.chengyinhub.business.service.ai.NpcVoiceEnrollmentService.Rejected rejected) {
            return error(rejected.getMessage());
        }
        AjaxResult r = AjaxResult.success(Integer.valueOf(VOICE_READY).equals(result.getVoiceStatus())
                ? "声音已就绪" : "声音结果已记录，请查询原任务");
        r.put("voiceStatus", result.getVoiceStatus());
        r.put("voiceSample", result.getVoiceSample());
        r.put("voicePreviousIntentId", result.getVoicePreviousIntentId());
        r.put("voiceRequestId", result.getVoiceRequestId());
        r.put("voiceSelected", result.getVoiceSelected());
        r.put("voiceIntentId", result.getVoiceIntentId());
        r.put("voiceIntentState", result.getVoiceIntentState());
        r.put("voiceReasonCode", result.getVoiceReasonCode());
        return r;
    }

    /** 查声音状态。复刻中时前端轮询这里,不要拿合成失败去反推状态。 */
    public AjaxResult npcVoiceStatus() { return npcVoiceStatus(null); }

    @PostMapping("/npc/voice/status")
    public AjaxResult npcVoiceStatus(@RequestBody(required = false) NpcProfile body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);

        NpcProfile mine = ownNpcOrNull(access.getMerchantId());
        AjaxResult r = AjaxResult.success();
        if (mine == null) {
            r.put("voiceStatus", VOICE_NONE);
            return r;
        }
        mine = voiceEnrollmentService.status(memberId, NPC_SCOPE_MERCHANT, access.getMerchantId(), mine.getId(),
                body == null ? null : body.getVoiceRequestId());
        r.put("voiceStatus", mine.getVoiceStatus() == null ? VOICE_NONE : mine.getVoiceStatus());
        r.put("voiceSample", mine.getVoiceSample());
        r.put("voicePreviousIntentId", mine.getVoicePreviousIntentId());
        r.put("voiceRequestId", mine.getVoiceRequestId());
        r.put("voiceSelected", mine.getVoiceSelected());
        r.put("voiceHistory", voiceEnrollmentService.history(memberId, NPC_SCOPE_MERCHANT, access.getMerchantId(), mine.getId()));
        r.put("voiceIntentId", mine.getVoiceIntentId());
        r.put("voiceIntentState", mine.getVoiceIntentState());
        r.put("voiceReasonCode", mine.getVoiceReasonCode());
        return r;
    }

    /** 清除音色。商家想重录时先清,避免旧音色继续被用来合成。 */
    @PostMapping("/npc/voice/reset")
    public AjaxResult npcVoiceReset() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.PROFILE_WRITE);

        NpcProfile mine = ownNpcOrNull(access.getMerchantId());
        if (mine == null) return AjaxResult.success("已清除");
        NpcProfile upd = new NpcProfile();
        upd.setId(mine.getId());
        upd.setScopeId(access.getMerchantId());
        upd.setVoiceVersion(mine.getVoiceVersion());
        upd.setVoiceId(null);
        upd.setVoiceStatus(VOICE_NONE);
        return npcProfileMapper.updateMerchantNpcVoice(upd) == 1
                ? AjaxResult.success("已清除") : error("声音未清除，请重试");
    }

    /**
     * 取当前商家名下那条形象。
     *
     * 所有写声音的入口都必须过这里 —— 直接拿客户端传的 id 去更新,等于谁都能改别人的音色。
     */
    private NpcProfile ownNpcOrNull(Long merchantId) {
        List<NpcProfile> list = npcProfileMapper.selectMerchantNpcList(merchantId, null);
        if (list == null || list.isEmpty()) return null;
        NpcProfile mine = list.get(0);
        if (mine.getScopeId() == null || !mine.getScopeId().equals(merchantId)) return null;
        return mine;
    }

    /** 空白当作没填。前端传空串和不传是同一个意思,别把空串写进库。 */
    private static String trimOrNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 精选对象的 type/id 是成对合同。活动沿用权威公开查询；优惠券没有公开查询，读取后精确判定。
     * null/null 表示本次分区保存不触碰精选；0/null 表示清除。
     */
    private String validateFeaturedSelection(Integer type, Long id, MerchantAccessContext access) {
        if (type == null && id == null) return null;
        if (type == null) return "主推内容参数不完整";
        if (type == 0) return id == null ? null : "主推内容参数不完整";
        if ((type != 1 && type != 2) || id == null || id < 1) return "主推内容参数不完整";
        if (type == 1) {
            com.chengyinhub.business.domain.CmsActivity activity =
                    cmsActivityService.selectPublicCmsActivityById(id);
            return isOwnedPublicActivity(activity, access.getOwnerMemberId())
                    ? null : "只能主推自己已公开的活动";
        }
        SmsCoupon coupon = smsCouponService.selectSmsCouponById(id);
        return isOwnedActiveCoupon(coupon, access.getOwnerMemberId(), access.getMerchantId())
                ? null : "只能主推当前门店进行中的优惠券";
    }

    private boolean isOwnedPublicActivity(com.chengyinhub.business.domain.CmsActivity activity,
                                          Long ownerMemberId) {
        return activity != null
                && ownerMemberId != null && ownerMemberId.equals(activity.getMemberId())
                && activity.getStatus() != null && activity.getStatus() == 1L
                && activity.getPublishStatus() != null && activity.getPublishStatus() == 1L
                && activity.getDelFlag() != null && activity.getDelFlag() == 0;
    }

    private boolean isOwnedActiveCoupon(SmsCoupon coupon, Long ownerMemberId, Long merchantRowId) {
        Date now = new Date();
        return coupon != null
                && ownerMemberId != null && ownerMemberId.equals(coupon.getMemberId())
                && merchantRowId != null && merchantRowId.equals(coupon.getMerchantId())
                && Integer.valueOf(1).equals(coupon.getScopeType())
                && coupon.getStatus() != null && coupon.getStatus() == 1
                && coupon.getDelFlag() != null && coupon.getDelFlag() == 0
                && coupon.getStartTime() != null && !coupon.getStartTime().after(now)
                && coupon.getEndTime() != null && !coupon.getEndTime().before(now);
    }

    @Operation(summary = "商家看俱乐部列表(发邀请用)")
    @PostMapping("/clubs")
    public AjaxResult clubs(@RequestBody(required = false) Club query) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        merchantAccessService.require(principalMemberId, MerchantPermission.COOP_MANAGE);
        Club publicQuery = new Club();
        if (query != null) {
            // 候选搜索只接受 Mapper 明确支持的公开字段。memberId/审核/联系方式等即使不回显，
            // 进入 WHERE 也会把“有无结果”变成私有字段枚举器。
            publicQuery.setName(query.getName());
            publicQuery.setCategoryId(query.getCategoryId());
        }
        publicQuery.setStatus(1L); // 仅已开放俱乐部
        List<ClubInviteCandidateVO> candidates = new ArrayList<>();
        List<Club> clubs = clubService.selectClubList(publicQuery);
        if (clubs != null) {
            for (Club club : clubs) {
                // CU-M-02:club.member_count 不含主理人,俱乐部主页走 ApiClubController.effectiveMemberCount(含主理人),
                // 同一个俱乐部这里写 0 位、主页写 1 名。两处必须同口径。
                club.setMemberCount(effectiveMemberCount(club));
                candidates.add(ClubInviteCandidateVO.from(club));
            }
        }
        return success(candidates);
    }

    /** 与 ApiClubController 同一份口径,判据收在 {@code IClubMemberService#countEffectiveMembers}。 */
    private int effectiveMemberCount(Club club) {
        return clubMemberService.countEffectiveMembers(club);
    }

    @Operation(summary = "商家关系首页聚合(发现+合作关系)")
    @PostMapping("/relation-home")
    public AjaxResult relationHome(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.COOP_MANAGE);

        Long topicId = body == null ? null : aggregateLong(body.get("topicId"));
        if (body != null && body.containsKey("topicId") && (topicId == null || topicId < 1)) {
            return error("topicId参数错误");
        }
        Double originLat = body == null ? null : aggregateDouble(body.get("originLat"));
        Double originLng = body == null ? null : aggregateDouble(body.get("originLng"));
        boolean hasOriginLat = body != null && body.containsKey("originLat");
        boolean hasOriginLng = body != null && body.containsKey("originLng");
        if (hasOriginLat != hasOriginLng) return error("定位参数需成对提供");
        if (hasOriginLat && (originLat == null || originLng == null)) return error("定位参数错误");
        if (originLat != null && (!Double.isFinite(originLat) || !Double.isFinite(originLng)
                || originLat < -90D || originLat > 90D
                || originLng < -180D || originLng > 180D)) return error("定位参数错误");
        int limit = body == null ? 10 : aggregateInt(body.get("limit"), 10);
        if (limit < 1 || limit > 20) limit = 10;
        return success(merchantAggregateReadService.relationHome(
                access.getOwnerMemberId(), topicId, originLat, originLng, limit));
    }

    @Operation(summary = "商家营销首页聚合")
    @PostMapping("/marketing-home")
    public AjaxResult marketingHome(
            @RequestParam(value = "windowDays", required = false) String requestedWindowDays) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                memberId, MerchantPermission.MARKETING_READ);
        int windowDays = aggregateWindowDays(requestedWindowDays);
        if (windowDays < 0) return error("windowDays仅支持7或30");
        return success(merchantAggregateReadService.marketingHome(
                access.getOwnerMemberId(), access.getMerchantId(), windowDays));
    }

    private Long aggregateLong(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }

    private Double aggregateDouble(Object value) {
        if (value == null) return null;
        try { return Double.valueOf(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }

    private int aggregateInt(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value).trim()); } catch (Exception e) { return fallback; }
    }

    private int aggregateWindowDays(Object requestedWindowDays) {
        if (requestedWindowDays == null) return 30;
        int value = aggregateInt(requestedWindowDays, -1);
        return value == 7 || value == 30 ? value : -1;
    }

    @Operation(summary = "更新个人信息")
    @Log(title = "更新个人信息", businessType = BusinessType.UPDATE)
    @PostMapping("/update")
    public AjaxResult updateUserInfo(@Validated @RequestBody MmsMerchant request) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }


        MerchantAccessContext access = merchantAccessService.require(
                member_id, MerchantPermission.PROFILE_WRITE);
        MmsMerchant mmsMerchant = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (mmsMerchant == null) {
            return error("请先提交商家入驻申请");
        }
        if (request.getBusinessTime() != null) {
            String hours = request.getBusinessTime();
            if (hours.length() > 255 || !hours.matches("周(?:一至周日|[一二三四五六日](?:、[一二三四五六日])*) ([01][0-9]|2[0-3]):[0-5][0-9]-(?:次日)?([01][0-9]|2[0-3]):[0-5][0-9]")) {
                return error("请选择有效的经营日与时间");
            }
            String[] range = hours.substring(hours.indexOf(' ') + 1).replace("次日", "").split("-");
            if (range[0].equals(range[1])) return error("开始与结束时间不能相同");
            if ((range[1].compareTo(range[0]) < 0) != hours.contains("次日")) return error("请确认跨夜经营时间");
        }
        if (request.getDerivativeBenefits() != null && request.getDerivativeBenefits().length() > 100) {
            return error("衍生权益最多 100 个字");
        }
        // owner 2026-10-22:联系电话 / 门店微信号 / 门店二维码改由商家在「门店信息」自助维护(原 CU-M-08 只读)。
        // 电话是到店联系的唯一通道,只收 11 位大陆手机号、不允许清空;微信号与二维码可清空。
        if (request.getPhone() != null && !MERCHANT_PHONE.matcher(request.getPhone()).matches()) {
            return error("请填写 11 位手机号");
        }
        if (request.getWechat() != null && !request.getWechat().isEmpty()
                && !MERCHANT_WECHAT_ID.matcher(request.getWechat()).matches()) {
            return error("微信号只能包含字母、数字、下划线或减号，最多 20 位");
        }
        if (request.getWechatQrcode() != null && request.getWechatQrcode().length() > 512) {
            return error("二维码图片地址过长，请重新上传");
        }
        // 内容安全:商家名/简介/衍生介绍等文本命中违规即拒(商家资料匿名可访问公开)
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(request.getName(), request.getDescription(), request.getDerivativeBenefits(), request.getDerivatives(),
                        request.getPreference(), request.getWebsite(), request.getWechat()),
                merchantOpenid(access.getOwnerMemberId()));
        if (secMsg != null) return error(secMsg);
        // 部分更新白名单:null 表示本次不修改，空字符串仍可显式清空。
        // 使用新对象而不是修改查询实体，避免 phone/license/status 等非编辑字段被意外带回更新。
        MmsMerchant update = new MmsMerchant();
        update.setId(mmsMerchant.getId());
        if (request.getLogo() != null) update.setLogo(reviewedMedia(member_id, request.getLogo(), false));
        if (request.getName() != null) update.setName(request.getName());
        if (request.getDescription() != null) update.setDescription(request.getDescription());
        if (request.getDerivatives() != null) update.setDerivatives(request.getDerivatives());
        if (request.getDerivativeBenefits() != null) update.setDerivativeBenefits(request.getDerivativeBenefits());
        if (request.getWebsite() != null) update.setWebsite(request.getWebsite());
        if (request.getPreference() != null) update.setPreference(request.getPreference());
        if (request.getBusinessTime() != null) update.setBusinessTime(request.getBusinessTime());
        if (request.getPhone() != null) update.setPhone(request.getPhone());
        if (request.getWechat() != null) update.setWechat(request.getWechat());
        if (request.getWechatQrcode() != null) {
            update.setWechatQrcode(reviewedMedia(member_id, request.getWechatQrcode(), false));
        }
        if (request.getLogo() == null && request.getName() == null && request.getDescription() == null
                && request.getDerivatives() == null && request.getDerivativeBenefits() == null && request.getWebsite() == null
                && request.getPreference() == null && request.getBusinessTime() == null
                && request.getPhone() == null && request.getWechat() == null && request.getWechatQrcode() == null) {
            return error("没有可更新的资料");
        }
        int rows = mmsMerchantService.updateAuthorizedProfile(
                update, member_id, MerchantPermission.PROFILE_WRITE);
        // logo / 门店二维码异步内容安全送检(BIZ_TYPE_MERCHANT_PROFILE)
        if (request.getLogo() != null || request.getWechatQrcode() != null) {
            try {
                wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_MERCHANT_PROFILE,
                        mmsMerchant.getId(), mediaCsv(request.getLogo(), request.getWechatQrcode()), merchantOpenid(member_id));
            } catch (Exception ignore) {}
        }
        if (rows > 0) {
            return success("更新成功");
        } else {
            return error("更新失败");
        }
    }


    @Operation(summary = "商家数据面板")
    @PostMapping("/dashboard")
    public AjaxResult getDashboard() {
        Long principalMemberId = 0L;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.BASIC_READ);
        Long ownerMemberId = access.getOwnerMemberId();
        Map<String, Object> data = new HashMap<>();
        // 按实际字段声明窗口：到店统计按报名创建时间取 cohort，在架供给和待处理订单是当前快照。
        Map<String, Object> windows = new LinkedHashMap<>();

        // 品牌收入与趋势属于资金域，非财务角色连查询都不执行。
        if (access.hasPermission(MerchantPermission.FINANCE_READ)) {
            // 累计收入与近7日分桶都在 SQL 聚合(SUM / GROUP BY 日),不再把全量流水拉回内存。
            // 近7日收入趋势(按天):收入流水(changeType=1)按自然日分桶,缺天补0
            java.text.SimpleDateFormat keyFmt = new java.text.SimpleDateFormat("yyyy-MM-dd");
            java.text.SimpleDateFormat labelFmt = new java.text.SimpleDateFormat("MM-dd");
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            cal.add(java.util.Calendar.DATE, -6);
            MerchantDashboardIncome income =
                    umsMemberBalanceDetailService.readDashboardIncome(ownerMemberId, cal.getTime());
            data.put("revenue", income.getTotal().setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
            Map<String, BigDecimal> incomeByDay = income.getByDay();
            String[] dayKeys = new String[7];
            String[] dayLabels = new String[7];
            Map<String, BigDecimal> dayMap = new HashMap<>();
            for (int i = 0; i < 7; i++) {
                String k = keyFmt.format(cal.getTime());
                dayKeys[i] = k;
                dayLabels[i] = labelFmt.format(cal.getTime());
                BigDecimal amount = incomeByDay == null ? null : incomeByDay.get(k);
                dayMap.put(k, amount == null ? BigDecimal.ZERO : amount);
                cal.add(java.util.Calendar.DATE, 1);
            }
            java.util.List<Map<String, Object>> revenue7d = new java.util.ArrayList<>();
            for (int i = 0; i < 7; i++) {
                Map<String, Object> row = new HashMap<>();
                row.put("date", dayLabels[i]);
                row.put("amount", dayMap.get(dayKeys[i])
                        .setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
                revenue7d.add(row);
            }
            data.put("revenue7d", revenue7d);
            windows.put("revenue", MerchantMetricWindow.ALL_TIME.code());
            windows.put("revenue7d", MerchantMetricWindow.CALENDAR_7D_INCL_TODAY.code());
        }

        if (access.hasPermission(MerchantPermission.ORDER_READ)) {
            // oms_order.mms_merchant_id 是商家行主键，不是登录会员 ID。
            OmsOrder orderQuery = new OmsOrder();
            orderQuery.setMmsMerchantId(access.getMerchantId());
            orderQuery.setStatus(1L);
            List<OmsOrder> pendingOrders = omsOrderService.selectOmsOrderList(orderQuery).stream()
                    .filter(o -> o.getDelFlag() == null || o.getDelFlag() != 2)
                    .collect(java.util.stream.Collectors.toList());
            data.put("pendingOrders", pendingOrders.size());
            windows.put("pendingOrders", MerchantMetricWindow.CURRENT_SNAPSHOT.code());
        }

        // 升级5:曝光/到店/核销率/复购率/在架供给 —— 与店铺参谋同一 facts 口径,数字直出不走 AI。
        // best-effort:insight 块取不到不拖垮既有收入面板;分享无埋点不造字段。
        if (access.hasPermission(MerchantPermission.MARKETING_READ)) {
            try {
                data.put("insight", merchantInsightService.dashboard(ownerMemberId));
                windows.put("insight.exposure", MerchantMetricWindow.ROLLING_30D.code());
                windows.put("insight.checkin", MerchantMetricWindow.REGISTRATION_COHORT_ROLLING_30D.code());
                windows.put("insight.supplyCount", MerchantMetricWindow.CURRENT_SNAPSHOT.code());
            } catch (Exception e) {
                logger.warn("商家看板 insight 块聚合失败 memberId={}", ownerMemberId, e);
            }
        }
        data.put("windows", windows);

        return success(data);
    }

    @Operation(summary = "商家今日待办摘要(待核销/已核销/扫码待确认)")
    @PostMapping("/todo-summary")
    public AjaxResult todoSummary() {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.BASIC_READ);
        Long ownerMemberId = access.getOwnerMemberId();

        List<CmsRegistrationMerchant> won = Collections.emptyList();
        Map<String, Map<String, Object>> byProject = new LinkedHashMap<>();
        int pendingVerify = 0, verifiedCount = 0, pendingScanConfirm = 0;
        Date todoNow = new Date();
        if (access.hasPermission(MerchantPermission.VERIFY_RECORD_READ)) {
            // 我中标的主题(调配状态机 auditStatus=1)
            CmsRegistrationMerchant mProbe = new CmsRegistrationMerchant();
            mProbe.setMemberId(ownerMemberId);
            mProbe.setAuditStatus(1L);
            mProbe.setDelFlag(0);
            won = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(mProbe);

        // ★ 按**去重后的 topicId** 计数。一商家在同一主题承接多章是常态
        // (uk_offer_merchant_chapter 只约束一商家一章一行),原来按 won 逐行循环
        // 会把同一主题的待核销数重复累加 —— 全局数被放大,且和按项目拆出来的
        // byTopic 对不上账。
        LinkedHashSet<Long> wonTopicIds = new LinkedHashSet<>();
        for (CmsRegistrationMerchant w : won) {
            if (w.getTopicId() != null) wonTopicIds.add(w.getTopicId());
        }

        // 按项目拆的待办:工作台一张卡一个项目,没有这层拆分卡片上只能把全局数字
        // 复制一遍 = 假数据。项目 = (ownerType, ownerId):1 主题 / 2 活动,
        // 两者在工作台平起平坐,所以这里也一视同仁地拆。
        // 口径同商家主题详情:已支付报名按核销态分(payment=2 / ownerType=1主题);
        // 全部项目一次分组计数(不再逐项目拉全行 .size()),查询次数与项目数无关。
        Map<Long, long[]> topicCounts = wonTopicIds.isEmpty() ? Collections.<Long, long[]>emptyMap()
                : cmsRegistrationService.countPaidVerificationByOwners(1L, wonTopicIds);
        Map<Long, Date> topicEndDates = wonTopicIds.isEmpty() ? Collections.emptyMap()
                : cmsTopicService.selectTodoTopicEndDates(wonTopicIds);
        for (Long topicId : wonTopicIds) {
            long[] tc = topicCounts == null ? null : topicCounts.get(topicId);
            // verifiedCount 是累计成绩不是待办 ⇒ 已结束的项目也照算,别让历史核销数凭空消失。
            verifiedCount += tc == null ? 0 : (int) tc[1];

            // 待办类(pendingVerify / 项目卡)才按结束时间过滤:结束满一周的主题不再进待办,
            // 口径同玩家侧 ApiRegistrationController.filterEndedOverWeek。不加这层的后果实测过:
            // 2026-02 的一条扫码记录能在半年后仍挂在工作台上,点进去却是「已结束」。
            if (endedOverWeek(topicEndDates.get(topicId), todoNow)) continue;

            int topicPendingVerify = tc == null ? 0 : (int) tc[0];
            pendingVerify += topicPendingVerify;

            byProject.put(projectKey(1L, topicId), projectRow(1L, topicId, topicPendingVerify));
        }

        // 活动(ownerType=2)同样逐个拆,不再只并进一个总数
        List<com.chengyinhub.business.domain.CmsActivity> activities = myActivities(ownerMemberId);
        LinkedHashSet<Long> activityIds = new LinkedHashSet<>();
        for (com.chengyinhub.business.domain.CmsActivity a : activities) activityIds.add(a.getId());
        Map<Long, long[]> activityCounts = activityIds.isEmpty() ? Collections.<Long, long[]>emptyMap()
                : cmsRegistrationService.countPaidVerificationByOwners(2L, activityIds);
        for (com.chengyinhub.business.domain.CmsActivity a : activities) {
            long[] ac = activityCounts == null ? null : activityCounts.get(a.getId());
            verifiedCount += ac == null ? 0 : (int) ac[1];  // 同上:累计成绩不过滤

            if (endedOverWeek(a.getEndDate(), todoNow)) continue;

            int actPendingVerify = ac == null ? 0 : (int) ac[0];
            pendingVerify += actPendingVerify;

            byProject.put(projectKey(2L, a.getId()), projectRow(2L, a.getId(), actPendingVerify));
        }

        // 扫码待确认(玩家扫码后待商家确认):scan.member_id=本商家 且 status=0
        CmsRegistrationScan sProbe = new CmsRegistrationScan();
        sProbe.setMemberId(ownerMemberId);
        sProbe.setStatus(0L);
        List<CmsRegistrationScan> pendingScans = cmsRegistrationScanService.selectCmsRegistrationScanList(sProbe);
        // 待确认扫码的项目归属:scan → registration → (ownerType, ownerId)。
        // ★ 过滤判据是「归属项目结束满一周了没有」,不是「有没有对应卡片」。
        //   按卡片过滤会连自己发布的主题一起漏掉 —— 那类项目从来不进 byProject
        //   (byProject 只收中标承接的主题 + 自己发布的活动),会变成静默漏报。
        //   落不到卡片上的仍只进全局数,保持原语义。
        LinkedHashSet<Long> scanRegistrationIds = new LinkedHashSet<>();
        for (CmsRegistrationScan s : pendingScans) {
            if (s.getRegistrationId() != null) scanRegistrationIds.add(s.getRegistrationId());
        }
        Map<Long, com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO> scanOwners = scanRegistrationIds.isEmpty()
                ? Collections.emptyMap() : cmsRegistrationService.selectTodoRegistrationOwners(scanRegistrationIds);
        // 仅批量读取去重；同一报名的多条扫码仍逐条计数。
        for (CmsRegistrationScan s : pendingScans) {
            com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO scanReg = scanOwners.get(s.getRegistrationId());
            if (scanReg == null || scanReg.getOwnerType() == null || scanReg.getOwnerId() == null) continue;
            if (endedOverWeek(scanReg.getEndDate(), todoNow)) continue;
            pendingScanConfirm++;
            Map<String, Object> row = byProject.get(projectKey(scanReg.getOwnerType(), scanReg.getOwnerId()));
            if (row != null) row.put("pendingScanConfirm", ((Integer) row.get("pendingScanConfirm")) + 1);
        }
        }

        long pendingOrders = 0L;
        long refundCount = 0L;
        if (access.hasPermission(MerchantPermission.ORDER_READ)) {
            // oms_order.mms_merchant_id 是商家行主键；非订单角色不执行查询。
            OmsOrder opPending = new OmsOrder();
            opPending.setMmsMerchantId(access.getMerchantId());
            opPending.setStatus(1L);
            pendingOrders = omsOrderService.selectOmsOrderList(opPending).stream()
                    .filter(o -> o.getDelFlag() == null || o.getDelFlag() != 2).count();
        }
        if (access.hasPermission(MerchantPermission.AFTERCARE_READ)) {
            // 与退款售后中心同一真源：只统计尚未留下商家意见的平台待审申请。
            refundCount = merchantAftercareMapper.countOwnedRefundList(
                    access.getMerchantId(), ownerMemberId, "PENDING");
        }

        long pendingPredict = 0L;
        if (access.hasPermission(MerchantPermission.PROJECT_MANAGE)) {
            // 竞猜「给答案」48 小时待办(D-14):不看私信就发现不了,超时那一轮直接作废、谁都拿不到奖。
            // 名单与 /api/merchant/predict/inbox 同一份(同一个服务方法),点进去条数才对得上;
            // 超时轮次被 PredictVoidExpiredTask 标成作废(settle_status≠0)后自然从这个数里移出。
            pendingPredict = playPredictService.pendingInbox(ownerMemberId, IPlayPredictService.ANSWER_GRACE_HOURS).size();
        }

        Map<String, Object> data = new HashMap<>();
        data.put("biddingTopics", won.size());
        data.put("pendingVerify", pendingVerify);
        data.put("verifiedCount", verifiedCount);
        data.put("pendingScanConfirm", pendingScanConfirm);
        data.put("pendingOrders", pendingOrders);
        data.put("refundCount", refundCount);
        data.put("pendingPredict", pendingPredict);
        // 订单类待办(待发货/退款)挂在商家自己名下,库里没有到项目的关联 ⇒ 不进 byProject。
        data.put("byProject", new java.util.ArrayList<>(byProject.values()));
        return success(data);
    }

    /**
     * 与玩家侧「我参与的」同口径:项目结束满 {@link com.chengyinhub.web.service.registration.RegistrationQueryApplication#HIDE_AFTER_END_DAYS}
     * 天即从商家待办里隐去(只隐藏,不删数据)。
     *
     * <p>★ 窗口期直接借玩家侧那个常量,不在这里自留一份 —— 两边各写一个 7,改口径时必然只改一半。
     */
    static boolean endedOverWeek(Date endDate, Date now) {
        if (endDate == null || now == null) return false;
        long cutoff = now.getTime() - com.chengyinhub.web.service.registration.RegistrationQueryApplication.HIDE_AFTER_END_DAYS * 24L * 60L * 60L * 1000L;
        return endDate.getTime() < cutoff;
    }

    /** 项目主键:(ownerType, ownerId) 拼串,1 主题 / 2 活动 —— 两者 id 各自独立编号,不能只用 id。 */
    private String projectKey(Long ownerType, Long ownerId) {
        return ownerType + ":" + ownerId;
    }

    private Map<String, Object> projectRow(Long ownerType, Long ownerId, int pendingVerify) {
        Map<String, Object> row = new HashMap<>();
        row.put("ownerType", ownerType);
        row.put("ownerId", ownerId);
        row.put("pendingVerify", pendingVerify);
        row.put("pendingScanConfirm", 0);
        return row;
    }

    /** 我发布的活动(delFlag 由 service 过滤)。 */
    private List<com.chengyinhub.business.domain.CmsActivity> myActivities(Long memberId) {
        com.chengyinhub.business.domain.CmsActivity probe = new com.chengyinhub.business.domain.CmsActivity();
        probe.setMemberId(memberId);
        List<com.chengyinhub.business.domain.CmsActivity> list = cmsActivityService.selectCmsActivityList(probe);
        List<com.chengyinhub.business.domain.CmsActivity> out = new java.util.ArrayList<>();
        if (list != null) {
            for (com.chengyinhub.business.domain.CmsActivity a : list) {
                if (a.getId() != null) out.add(a);
            }
        }
        return out;
    }

    /** 报名单所属项目的结束时间:owner_type=1 主题→cms_topic.end_date;=2 活动→cms_activity.end_date。 */
    private Date ownerEndDate(CmsRegistration reg) {
        if (reg == null || reg.getOwnerType() == null || reg.getOwnerId() == null) return null;
        if (reg.getOwnerType() == 1L) {
            com.chengyinhub.business.domain.CmsTopic t = cmsTopicService.selectCmsTopicById(reg.getOwnerId());
            return t == null ? null : t.getEndDate();
        }
        if (reg.getOwnerType() == 2L) {
            com.chengyinhub.business.domain.CmsActivity a = cmsActivityService.selectCmsActivityById(reg.getOwnerId());
            return a == null ? null : a.getEndDate();
        }
        return null;
    }

    /**
     * 即将到店的场次(商家侧)。
     *
     * <p><b>为什么要有它</b>:场次、俱乐部、成团态、人数这些事实库里全有
     * ({@code cms_topic.club_id} / {@code oms_ticket} / {@code cms_registration}),
     * 但商家侧一个读接口都没有 —— 商家不知道哪天有团要来。
     *
     * <p><b>鉴权</b>:只返回本商家**已中标**(auditStatus=1)主题下的场次。传了 topicId 也必须先落在
     * 这批主题里,否则任何商家都能读到全平台任意主题的场次与人数。
     *
     * <p><b>「预计到我这」给的是区间不是时刻</b>(2026-07-28 拍板):把场次时长按节点数均分,
     * 取本商家节点所在的那一段。这是**估算**,`estimated` 恒 true;节点序位或时长缺一个就
     * 不给区间(返回 null),让前端只显示场次时段 —— 宁可少说,不编一个精确到分钟的假时刻。
     *
     * <p>不返回名册明细:商家需要的是「几个人」,不是「谁」。名册是 PII,留在带队侧。
     */
    @Operation(summary = "商家即将到店的场次(含俱乐部包团)")
    @PostMapping("/upcoming-runs")
    public AjaxResult upcomingRuns(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY_RECORD_READ);

        Long topicIdFilter = null;
        if (body != null && body.get("topicId") != null) {
            try { topicIdFilter = Long.valueOf(String.valueOf(body.get("topicId")).trim()); } catch (Exception ignored) { }
        }

        return success(merchantFulfillmentReadService.listUpcomingRuns(
                access.getOwnerMemberId(), topicIdFilter));
    }

    @Operation(summary = "商家券统计(领取/核销)")
    @PostMapping("/coupon-summary")
    public AjaxResult couponSummary() {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.COUPON_MANAGE);
        SmsCoupon probe = new SmsCoupon();
        probe.setMemberId(access.getOwnerMemberId());
        probe.setMerchantId(access.getMerchantId());
        probe.setScopeType(1);
        // CU-M-86(2026-09-24):couponCount 与 marketing-home 是同一个卡面数字(「N 张在投放」),
        // 口径必须一致 —— 只剔 del_flag=2 会把已停发(4)/未开始(0)也算成在投放。
        // 领取/核销仍是这叠券的累计数;couponTotal 单独出,供前端判「有没有券」。
        Date now = new Date();
        long received = 0L, verified = 0L;
        int onAir = 0, total = 0;
        for (SmsCoupon c : smsCouponService.selectSmsCouponList(probe)) {
            if (c.getDelFlag() != null && c.getDelFlag() == 2) continue;
            total++;
            if (c.onAir(now)) onAir++;
            received += c.getReceiveCount() == null ? 0L : c.getReceiveCount();
            verified += c.getUseCount() == null ? 0L : c.getUseCount();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("couponCount", onAir);
        data.put("couponTotal", total);
        data.put("received", received);
        data.put("verified", verified);
        return success(data);
    }

    @Operation(summary = "商家实时动态(收入到账/核销等真实事件)")
    @PostMapping("/events")
    public AjaxResult events() {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.BASIC_READ);
        Long memberId = access.getOwnerMemberId();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm");
        java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();

        // 收入到账
        if (access.hasPermission(MerchantPermission.FINANCE_READ)) {
            UmsMemberBalanceDetail bq = new UmsMemberBalanceDetail();
            bq.setMemberId(memberId);
            bq.setChangeType(1L);
            for (UmsMemberBalanceDetail b : umsMemberBalanceDetailService.selectUmsMemberBalanceDetailList(bq)) {
                if (b.getCreateTime() == null) continue;
                Map<String, Object> e = new HashMap<>();
                e.put("type", "income");
                e.put("label", "收入到账");
                e.put("content", incomeLabel(b.getEventType()) + " +¥" + (b.getChangeBalance() == null ? "0" : b.getChangeBalance().toPlainString()));
                // 项目归属:主题分润(1)/商家分润(5) 的 event_id 是 topic_id,活动分润(2) 是 activity_id
                // (见 CmsTopicServiceImpl / CmsActivityServiceImpl 两处结算入口)。其余类型不属于任何项目。
                Long incomeEvent = b.getEventType();
                if (b.getEventId() != null && incomeEvent != null) {
                    if (incomeEvent == 1L || incomeEvent == 5L) {
                        e.put("ownerType", 1L);
                        e.put("ownerId", b.getEventId());
                    } else if (incomeEvent == 2L) {
                        e.put("ownerType", 2L);
                        e.put("ownerId", b.getEventId());
                    }
                }
                e.put("_t", b.getCreateTime().getTime());
                e.put("time", fmt.format(b.getCreateTime()));
                events.add(e);
            }
        }
        // 核销完成
        if (access.hasPermission(MerchantPermission.VERIFY_RECORD_READ)) {
            CmsRegistrationScan sq = new CmsRegistrationScan();
            sq.setMemberId(memberId);
            // 核销台账的唯一写入口 writeScanLedger 落的是 registrationType=1、status=0;
            // registrationType=0 是报名时按节点模板插的行(member_id=玩家),不是本商家核销。
            sq.setRegistrationType(1);
            for (CmsRegistrationScan s : cmsRegistrationScanService.selectCmsRegistrationScanList(sq)) {
                if (s.getCreateTime() == null) continue;
                // 4-10 复看整改:原先按 scan.status==1 过滤,但 status 从没有过 1 —— 核销事件一条都不显示。
                if (!Integer.valueOf(1).equals(s.getRegistrationType())) continue;
                Map<String, Object> e = new HashMap<>();
                e.put("type", "verify");
                e.put("label", "核销");
                e.put("content", "完成一笔到店核销");
                e.put("_t", s.getCreateTime().getTime());
                e.put("_scan", s);
                e.put("time", fmt.format(s.getCreateTime()));
                events.add(e);
            }
        }
        events.sort((a, b) -> Long.compare((Long) b.get("_t"), (Long) a.get("_t")));
        java.util.List<Map<String, Object>> top = events.size() > 10 ? new java.util.ArrayList<>(events.subList(0, 10)) : events;
        // 核销事件的项目归属得一条条回查 registration ⇒ 只查最终下发的这 10 条。
        // 排序前对全量核销记录查是 N+1(核销表是全历史的)。
        for (Map<String, Object> e : top) {
            e.remove("_t");
            Object scan = e.remove("_scan");
            if (scan == null) continue;
            CmsRegistration reg = ((CmsRegistrationScan) scan).getRegistrationId() == null ? null
                    : cmsRegistrationService.selectCmsRegistrationById(((CmsRegistrationScan) scan).getRegistrationId());
            if (reg != null && reg.getOwnerType() != null && reg.getOwnerId() != null) {
                e.put("ownerType", reg.getOwnerType());
                e.put("ownerId", reg.getOwnerId());
            }
        }
        return success(top);
    }

    private String incomeLabel(Long eventType) {
        if (eventType == null) return "收入";
        if (eventType == 1L) return "主题分润";
        if (eventType == 2L) return "活动分润";
        if (eventType == 5L) return "邀请分润";
        return "收入";
    }

    @Operation(summary = "商家经营事件分析(兼容路由)")
    @PostMapping("/funnel")
    public AjaxResult funnel(
            @RequestParam(value = "windowDays", required = false) String requestedWindowDays) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principalMemberId == null || principalMemberId < 1) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.MARKETING_READ);
        int windowDays = aggregateWindowDays(requestedWindowDays);
        if (windowDays < 0) return error("windowDays仅支持7或30");
        return success(merchantAggregateReadService.funnel(access.getOwnerMemberId(), windowDays));
    }

    @Operation(summary = "商家核销台账")
    @PostMapping("/verification-records")
    public AjaxResult verificationRecords() {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
        CmsRegistrationScan sq = new CmsRegistrationScan();
        sq.setMemberId(access.getOwnerMemberId());
        List<CmsRegistrationScan> list = cmsRegistrationScanService.selectCmsRegistrationScanList(sq);
        list.sort((a, b) -> {
            long ta = a.getCreateTime() == null ? 0L : a.getCreateTime().getTime();
            long tb = b.getCreateTime() == null ? 0L : b.getCreateTime().getTime();
            return Long.compare(tb, ta);
        });
        return success(list);
    }

    @Operation(summary = "商家订单/异常列表(按状态/售后筛)")
    @PostMapping("/orders")
    public AjaxResult orders(@RequestBody(required = false) OmsOrder query) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.ORDER_READ);
        if (query == null) query = new OmsOrder();
        if (!access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)) {
            // 响应后脱敏还不够：敏感条件若进 SQL，会把“是否命中”变成手机号/会员关系探测器。
            redactOrderContact(query);
        }
        query.setMmsMerchantId(access.getMerchantId()); // oms_order 外键是 mms_merchant.id
        List<OmsOrder> list = omsOrderService.selectOmsOrderList(query).stream()
                .filter(o -> o.getDelFlag() == null || o.getDelFlag() != 2)
                .collect(java.util.stream.Collectors.toList());
        if (!access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)) {
            list.forEach(this::redactOrderContact);
        }
        return success(list);
    }

    @Operation(summary = "商家经营台账分页与汇总")
    @PostMapping("/orders/ledger")
    public AjaxResult ledgerOrders(@RequestBody(required = false) MerchantLedgerQuery query) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.ORDER_READ);
        if (query == null) {
            query = new MerchantLedgerQuery();
        }
        String filter = query.safeStatus();
        Long status = null;
        if ("pending".equals(filter)) status = Long.valueOf(1L);
        else if ("completed".equals(filter)) status = Long.valueOf(4L);
        Boolean aftersaleOnly = "aftersale".equals(filter);
        MerchantLedgerQuery.TimeRange range = MerchantLedgerQuery.resolveTimeRange(query.getPeriod(), LocalDateTime.now());
        Timestamp beginTime = range.getStart() == null ? null : Timestamp.valueOf(range.getStart());
        Timestamp endTime = range.getEnd() == null ? null : Timestamp.valueOf(range.getEnd());

        List<OmsOrder> rows;
        long total;
        try {
            PageHelper.startPage(query.safePageNum(), query.safePageSize(), "create_time desc, id desc");
            rows = omsOrderMapper.selectMerchantLedgerOrders(
                    access.getMerchantId(), status, aftersaleOnly, beginTime, endTime);
            total = new PageInfo<>(rows).getTotal();
        } finally {
            PageHelper.clearPage();
        }
        if (!access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)) {
            rows.forEach(this::redactOrderContact);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("rows", rows);
        data.put("total", total);
        data.put("summary", omsOrderMapper.selectMerchantLedgerSummary(
                access.getMerchantId(), status, aftersaleOnly, beginTime, endTime));
        return success(data);
    }

    private void redactOrderContact(OmsOrder order) {
        if (order == null) return;
        order.setMemberId(null);
        order.setPayId(null);
        order.setMemberCouponId(null);
        order.setReceiverName(null);
        order.setReceiverPhone(null);
        order.setReceiverPhoneCode(null);
        order.setReceiverProvince(null);
        order.setReceiverDetailAddress(null);
        order.setOrderNote(null);
        order.setDeliverySn(null);
        order.setPaymentTransactionId(null);
        order.setCommerceRequestId(null);
    }

    /**
     * 商家客户名册。跨项目的「我的全部客户」——按买家聚合我的订单,一行一个人。
     *
     * 为什么不复用 /api/project/players:那个必须带 topicId(没给就抛「缺少主题」),
     * 只能回答「这个项目谁要来」,回答不了「我一共有哪些客户」。
     *
     * 每行给的是**状态分档**(已到店 / 待到店 / 已退款),不是订单金额流水 —— 商家看名册
     * 是要判断「这个人到底什么情况」:买了票没来?买了又退了?来过几次?
     *
     * 数据源是 cms_registration 而不是 oms_order:后者没有核销态,也答不出「来没来」,
     * 而且按 status 过滤会让退款的人直接从名册里消失(那正好是最该看见的一档)。
     * 状态机与联系方式可见性都复用 ProjectPlayerReadService,不另立一套 —— 同一个人
     * 在项目页和名册页显示成两种状态是最难查的一类 bug。
     *
     * 分页在这里切:名册是内存里跨项目归拢出来的,PageHelper 拦不到。
     */
    @Operation(summary = "商家客户名册(跨项目,按人归拢,带到店/退款状态)")
    @PostMapping("/customers")
    public AjaxResult customers(@RequestBody(required = false) MerchantCustomerQuery query) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.CRM_READ);
        if (query == null) {
            query = new MerchantCustomerQuery();
        }
        return success(projectPlayerReadService.listMerchantCustomers(
                access.getOwnerMemberId(), query,
                access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)));
    }

    @Operation(summary = "商家结算批次与对账汇总")
    @PostMapping("/settlements/ledger")
    public AjaxResult settlementLedger(@RequestBody(required = false) MerchantLedgerQuery query) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.FINANCE_READ);
        Long ownerMemberId = access.getOwnerMemberId();
        if (query == null) {
            query = new MerchantLedgerQuery();
        }
        com.chengyinhub.business.domain.SettlementBatch batchQuery = new com.chengyinhub.business.domain.SettlementBatch();
        batchQuery.setMerchantId(ownerMemberId);
        List<com.chengyinhub.business.domain.SettlementBatch> rows;
        long total;
        try {
            PageHelper.startPage(query.safePageNum(), query.safePageSize());
            rows = settlementService.selectBatchList(batchQuery);
            total = new PageInfo<>(rows).getTotal();
        } finally {
            PageHelper.clearPage();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("rows", rows);
        data.put("total", total);
        data.put("summary", settlementService.getSummary(ownerMemberId));
        data.put("reconciliation", settlementService.getReconciliation(ownerMemberId));
        return success(data);
    }

    /** 资金域入口：memberId 仅从登录态取得，客户端传入的同名字段不参与任何归属判断。 */
    @Operation(summary = "商家资金结算概览")
    @PostMapping("/finance/overview")
    public AjaxResult financeOverview() {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.FINANCE_READ);
        return success(merchantFinanceQueryService.overview(access.getOwnerMemberId()));
    }

    @Operation(summary = "商家核销记录")
    @PostMapping("/finance/redemptions")
    public AjaxResult financeRedemptions(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
        boolean includeFinance = access.hasPermission(MerchantPermission.FINANCE_READ);
        String filter = body == null || body.get("filter") == null ? "all" : String.valueOf(body.get("filter"));
        if (!includeFinance) filter = "all";
        int pageNum = body == null ? 1 : aggregateInt(body.get("pageNum"), 1);
        int pageSize = body == null ? 20 : aggregateInt(body.get("pageSize"), 20);
        MerchantFinancePage<MerchantRedemptionView> page = merchantFinanceQueryService.redemptions(
                access.getOwnerMemberId(), filter, pageNum, pageSize);
        return success(includeFinance ? page : redactRedemptionFinance(page));
    }

    @Operation(summary = "商家核销详情")
    @PostMapping("/finance/redemption-detail")
    public AjaxResult financeRedemptionDetail(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY_RECORD_READ);
        String recordType = body == null || body.get("recordType") == null ? null : String.valueOf(body.get("recordType"));
        String recordId = body == null || body.get("recordId") == null ? null : String.valueOf(body.get("recordId"));
        MerchantRedemptionView result = merchantFinanceQueryService.redemptionDetail(
                access.getOwnerMemberId(), recordType, recordId);
        if (result != null && !access.hasPermission(MerchantPermission.FINANCE_READ)) {
            redactRedemptionFinance(result);
        }
        return result == null ? error("记录不可见") : success(result);
    }

    @Operation(summary = "商家对公结算批次")
    @PostMapping("/finance/public-transfer-batches")
    public AjaxResult financePublicTransferBatches(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.FINANCE_READ);
        int pageNum = body == null ? 1 : aggregateInt(body.get("pageNum"), 1);
        int pageSize = body == null ? 20 : aggregateInt(body.get("pageSize"), 20);
        return success(merchantFinanceQueryService.publicTransferBatches(
                access.getOwnerMemberId(), pageNum, pageSize));
    }

    @Operation(summary = "商家对公结算批次详情")
    @PostMapping("/finance/public-transfer-batch-detail")
    public AjaxResult financePublicTransferBatchDetail(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.FINANCE_READ);
        Long batchId = body == null ? null : aggregateLong(body.get("batchId"));
        Object result = merchantFinanceQueryService.publicTransferBatchDetail(
                access.getOwnerMemberId(), batchId);
        return result == null ? error("记录不可见") : success(result);
    }

    @Operation(summary = "商家已成立收入与调整明细")
    @PostMapping("/finance/settlement-entries")
    public AjaxResult financeSettlementEntries(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.FINANCE_READ);
        String source = body == null || body.get("source") == null ? "all" : String.valueOf(body.get("source"));
        int pageNum = body == null ? 1 : aggregateInt(body.get("pageNum"), 1);
        int pageSize = body == null ? 20 : aggregateInt(body.get("pageSize"), 20);
        return success(merchantFinanceQueryService.settlementEntries(
                access.getOwnerMemberId(), source, pageNum, pageSize));
    }

    private MerchantFinancePage<MerchantRedemptionView> redactRedemptionFinance(
            MerchantFinancePage<MerchantRedemptionView> page) {
        if (page == null) return null;
        for (MerchantRedemptionView row : page.getRows()) {
            redactRedemptionFinance(row);
        }
        if (page.getSummary() instanceof MerchantRedemptionSummary) {
            MerchantRedemptionSummary summary = (MerchantRedemptionSummary) page.getSummary();
            summary.setPendingAmount(null);
            summary.setArrivedAmount(null);
        }
        return page;
    }

    private void redactRedemptionFinance(MerchantRedemptionView row) {
        if (row == null) return;
        row.setSettlementAmount(null);
        row.setSettlementState(null);
        row.setSettlementRoute(null);
        row.setDisplayState(null);
        row.setNoCashReason(null);
        row.setPublicSettlement(null);
    }

    @Operation(summary = "商家入驻")
    @Log(title = "商家入驻", businessType = BusinessType.INSERT)
    @PostMapping("/merchant_registration")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult merchantRegistration(@Validated @RequestBody MerchantRegistrationDTO request) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }

        // 新入驻与接受员工邀请统一先锁会员行，避免并发把同一账号写成“商家 Owner + 其他店员工”。
        if (request.getId() == null) {
            if (umsMemberMapper.lockMemberById(member_id) == null) {
                return error("当前会员不可用");
            }
            if (merchantOperatorMapper.selectActiveByMemberId(member_id) != null) {
                return error("您已加入商家经营团队，不能再申请自己的商家");
            }
            // 主理人可以先提交待审申请；商家正式生效前由服务端收口校验全部俱乐部移交。
            // RUN-52 发布者实名:商家是要收钱的发布者,新入驻必须先把姓名与身份证号交给平台。
            // 只闸 id == null 这一支:改一份已经填到一半的申请时,那个表单里没有这两个字段,
            // 拦在那里等于把人锁在自己填过的东西外面;他真要把店开出来收钱,还是会撞上下面那道发布闸。
            String identityErr = memberIdentityService.checkRegistered(member_id);
            if (identityErr != null) return error(identityErr);
        }

        MmsMerchant mmsMerchant = new MmsMerchant();
        mmsMerchant.setMemberId(member_id);
        mmsMerchant.setName(request.getName());
        mmsMerchant.setCategoryId(request.getCategoryId());
        mmsMerchant.setDescription(request.getDescription());
        mmsMerchant.setDerivatives(request.getDerivatives());
        mmsMerchant.setPreference(request.getPreference());
        mmsMerchant.setPhone(request.getPhone());
        mmsMerchant.setAddress(request.getAddress());
        mmsMerchant.setBusinessTime(request.getBusinessTime());
        mmsMerchant.setBusinessLicense(request.getBusinessLicense());
        mmsMerchant.setWechat(request.getWechat());
        //入住审核状态0 待审核  1审核通过  2审核失败
        mmsMerchant.setStatus(0L);

        if (request.getId() != null) {
            MmsMerchant existing = mmsMerchantService.selectMmsMerchantById(request.getId());
            if (existing == null) {
                return error("商家信息不存在");
            }
            if (OwnershipUtils.notOwner(existing.getMemberId(), member_id)) {
                return error("无权修改该商家信息");
            }
            if (!Integer.valueOf(0).equals(existing.getDelFlag())
                    || existing.getAccountStatus() == null
                    || Long.valueOf(2L).equals(existing.getAccountStatus())) {
                return error("该商家账号已停用或不可用，无法修改入驻申请");
            }
            if (!Long.valueOf(0L).equals(existing.getStatus())
                    && !Long.valueOf(2L).equals(existing.getStatus())) {
                return error("已审核商家请使用店铺资料编辑，不能重新打开入驻申请");
            }
            mmsMerchant.setId(request.getId());
            mmsMerchant.setReson("");
            applyLocation(mmsMerchant, request, existing);
            // memberId 只作为 expectedOwner 传入 service 做锁后归属复核；
            // 自助资料 patch 本身永不携带 Owner 字段，也不能绕过 service 的字段白名单。
            mmsMerchant.setMemberId(null);
            int rows = mmsMerchantService.updateOwnerRegistrationProfile(mmsMerchant, member_id);
            if (rows > 0) {
                return success("更新成功");
            } else {
                return error("更新失败");
            }
        } else {
            MmsMerchant existing = mmsMerchantService.selectMmsMerchantByMemberId(member_id);
            if (existing != null) {
                return error("已提交过商家入驻申请，请勿重复提交");
            }
            //账号状态：1启用  2禁用
            mmsMerchant.setAccountStatus(0L);
            //删除标记 0 代表存在  2删除
            mmsMerchant.setDelFlag(0);
            applyLocation(mmsMerchant, request, null);
            int rows = mmsMerchantService.insertMmsMerchant(mmsMerchant);
            if (rows > 0) {
                return success("更新成功");
            } else {
                return error("更新失败");
            }
        }
    }

    /**
     * 入驻/改资料时的坐标落库。三条路,优先级从高到低:
     *
     * 1. 前端给了选点坐标 —— 商家自己在地图上点的,最准。wx.chooseLocation 本来就跟 address
     *    一起返回经纬度,以前在 apply 页被丢掉了,所以生产 10 家商家全有地址、零家有坐标。
     * 2. 更新且库里已有坐标 —— 一律不动,留 null 让 mapper 的动态 SET 跳过这两列。
     *    这条最要紧:商家在装修页点过精确坐标后,只要再改一次资料,下面第 3 条的地址反查
     *    就会把它盖掉 —— 不是抹成空,是换成更粗的推断值,更难发现。
     * 3. 只有地址、没坐标(商家手打地址没点地图)—— 用地址反查兜底。反查不可信时
     *    (见 IMapGeocodeService#suggestCoords 的可信度闸)坐标留空,绝不塞城市中心。
     *
     * location_verified 区分来源:1=商家亲自点的,2=我们按地址推断的(0 是建表默认值,
     * 含义是"没坐标",不能拿来表示"有坐标但没人确认")。存量回填要按这个分档挑行。
     */
    /** 提交上来的坐标是不是就是库里那一对。相等即视为"没重新点过",来源标记不该升级。 */
    private static boolean isSameSpot(MerchantRegistrationDTO request, MmsMerchant existing) {
        return existing != null
                && existing.getLocationLat() != null && existing.getLocationLng() != null
                && existing.getLocationLat().doubleValue() == request.getLocationLat().doubleValue()
                && existing.getLocationLng().doubleValue() == request.getLocationLng().doubleValue();
    }

    // 包内可见而非 private:这三条路是资料页每次保存都会走的,值得被直接测到。
    void applyLocation(MmsMerchant target, MerchantRegistrationDTO request, MmsMerchant existing) {
        // 经纬度必须成对,只给一个视同没给 —— 半个坐标定位到的是赤道或本初子午线上的某处。
        if (request.getLocationLat() != null && request.getLocationLng() != null) {
            target.setLocationLat(request.getLocationLat());
            target.setLocationLng(request.getLocationLng());
            // 原样传回来的坐标不算"刚点的"。客户端若把库里的值回填进表单再提交,
            // 上次按地址推断出来的坐标会一来一回冒充成商家确认值。当前 apply 页已不回填,
            // 但这道闸得在服务端:线上还跑着旧版本小程序,而且判据只有服务端有。
            target.setLocationVerified(isSameSpot(request, existing)
                    ? existing.getLocationVerified() : LOCATION_PICKED);
            return;
        }
        if (existing != null && existing.getLocationLat() != null && existing.getLocationLng() != null) {
            return;
        }
        if (StringUtils.isEmpty(request.getAddress())) {
            return;
        }
        // region 传 null:商家资料里没有城市字段(MmsMerchant.cityRole 是角色不是城市)。
        // 可信度闸挡得住区/市中心的回退值,但挡不住"另一个城市里同名路的门牌号"——
        // 那种匹配可信度同样很高。等 DTO 有了城市再收窄。
        double[] coords = mapGeocodeService.suggestCoords(request.getAddress(), null);
        if (coords != null) {
            target.setLocationLng(coords[0]);
            target.setLocationLat(coords[1]);
            target.setLocationVerified(LOCATION_INFERRED);
        }
    }

}
