package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.enums.PublicActivityScope;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.domain.vo.PublicRegistrationSummaryVO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.domain.vo.PublicActivityListVO;
import com.chengyinhub.business.domain.vo.PublicCollaboratorVO;
import com.chengyinhub.business.mapper.CmsActivityLikeMapper;
import com.chengyinhub.business.mapper.CmsRegistrationStatisticsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TmsBrowseRecordMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.util.ActivityLifecycle;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.CoordinateUtils;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;

import org.apache.commons.math3.stat.descriptive.rank.Min;
// Spring相关
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.lang.Math.min;

import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
/**
 * 活动
 *
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@Tag(name = "活动")
@RequestMapping("/api/activity")

public class ApiActivityController extends BaseController {

    @Autowired
    private ICmsActivityService cmsActivityService;

    /** O5b-1 开关(默认关):编辑分级 —— 轻改不重审,实质改动重新进审核。 */
    @org.springframework.beans.factory.annotation.Value("${chengyin.audit.o5b.light-edit.enabled:false}")
    private boolean lightEditClassificationEnabled = false;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    /** 拍板 2026-09-16 #10:营业状态门禁,给报名入口下发「商家暂停营业」提示。 */
    @Autowired
    private IMerchantBusinessStatusService merchantBusinessStatusService;

    @Autowired
    private com.chengyinhub.business.service.ITeamGroupService teamGroupService;

    /** [C8-05] 商家主办方取消场次:按商家权限(店主/PROJECT_MANAGE 员工)解析归属店主。 */
    @Autowired
    private com.chengyinhub.business.access.merchant.IMerchantAccessService merchantAccessService;

    /** [6-17] 俱乐部系列场次取消的收尾(候补终止/领队回收/站内通知)与 event-ops 同源。 */
    @Autowired
    private com.chengyinhub.business.service.IClubEventOpsService clubEventOpsService;

    // M1-3 社群活动门卡:校验查看者是否俱乐部成员
    @Autowired
    private com.chengyinhub.business.service.IClubMemberService clubMemberService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IContentSafetyGate contentSafetyGate;

    @Autowired
    private com.chengyinhub.business.service.support.ActivityReviewPolicy activityReviewPolicy;

    @Autowired
    private IAuditTaskService auditTaskService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private com.chengyinhub.business.service.IQuotaService quotaService;

    @Autowired
    private ICmsActivityLikeService cmsActivityLikeService;

    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    @Autowired
    private IViewCommentService commentService;

    @Autowired
    private ICmsMemberTemplateService memberTemplateService;

    @Autowired
    private IViewCollaboratorsService viewCollaboratorsService;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @Autowired
    private IViewRegistrationService viewRegistrationService;

    @Autowired
    private IOmsTicketService omsTicketService;

    @Autowired
    private CmsRegistrationStatisticsMapper cmsRegistrationStatisticsMapper;

    @Autowired
    private CmsActivityLikeMapper cmsActivityLikeMapper;

    @Autowired
    private UmsMemberFollowMapper umsMemberFollowMapper;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    @Autowired
    private UmsCommentMapper umsCommentMapper;

    @Autowired
    private TmsBrowseRecordMapper tmsBrowseRecordMapper;

    @Autowired
    private ITmsBrowseRecordService tmsBrowseRecordService;

    @Autowired
    private IUmsCommentService umsCommentService;


    @Operation(summary = "活动列表/我发布的活动")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "is_my", description = "0所有 1我发布的 2即将上线"),
            @Parameter(name = "keyword", description = "搜索关键词"),
            @Parameter(name = "category_id", description = "分类ID"),
            @Parameter(name = "sort_type", description = "排序：1附近的活动"),
            @Parameter(name = "longitude", description = "附近的活动传：地址经度"),
            @Parameter(name = "latitude", description = "附近的活动传：地址纬度")
    })
    public AjaxResult activityList(String is_my, String keyword, String category_id, String sort_type,
                                   String longitude, String latitude, String schedule,
                                   String product_type, String sort, String city_keyword,
                                   String distance_meters) {

        int isMy = Convert.toInt(is_my, 0);

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            logger.warn("activityList 获取用户ID异常", e);
        }

        CmsActivity cmsActivity = new CmsActivity();
        if (StringUtils.isNotEmpty(keyword)) {
            cmsActivity.setName(keyword);
        }
        if (StringUtils.isNotEmpty(sort_type)) {
            cmsActivity.setSortType(Convert.toInt(sort_type, 0));
        }
        if (StringUtils.isNotEmpty(longitude)) {
            cmsActivity.setLongitude(longitude);
        }
        if (StringUtils.isNotEmpty(latitude)) {
            cmsActivity.setLatitude(latitude);
        }
        try {
            com.chengyinhub.business.service.DiscoveryFilterContract.coordinates(longitude, latitude);
            cmsActivity.setCategoryIds(com.chengyinhub.business.service.DiscoveryFilterContract.categoryId(category_id));
            cmsActivity.setAddressName(com.chengyinhub.business.service.DiscoveryFilterContract.cityKeyword(city_keyword));
            cmsActivity.setScheduleFilter(com.chengyinhub.business.service.DiscoveryFilterContract.schedule(schedule));
            cmsActivity.setProductType(com.chengyinhub.business.service.DiscoveryFilterContract.productType(product_type));
            Integer contractDistance = com.chengyinhub.business.service.DiscoveryFilterContract
                    .distanceMeters(distance_meters, longitude, latitude);
            cmsActivity.setDistanceMeters(contractDistance);
            if (StringUtils.isNotEmpty(sort)) {
                Integer contractSort = com.chengyinhub.business.service.DiscoveryFilterContract
                        .activitySort(sort, longitude, latitude);
                cmsActivity.setSortType(contractSort);
                if (contractSort == null && contractDistance == null) {
                    cmsActivity.setLongitude(null);
                    cmsActivity.setLatitude(null);
                }
            } else if (StringUtils.isEmpty(distance_meters)
                    && StringUtils.isNotEmpty(longitude) && StringUtils.isNotEmpty(latitude)) {
                // 旧客户端只传坐标时保留既有“100km 内按距离”的行为；新 contract 必须显式传 sort/radius。
                cmsActivity.setSortType(1);
                cmsActivity.setDistanceMeters(
                        com.chengyinhub.business.service.DiscoveryFilterContract.ACTIVITY_RADIUS_METERS);
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }

        boolean publicList = isMy != 1;
        if (isMy == 1) {
            if (member_id > 0) {
                cmsActivity.setMemberId(member_id);
            } else {
                return error("请先登录");
            }
        } else if (isMy == 2) {
            //即将上线
            cmsActivity.setStartDate(new Date());
        }
        // isMy==1 是既有 owner seam，可看自己的全状态记录；
        // 其余分支走公开 Mapper：status/publish_status/del_flag/cancel_time 四道 Gate 写死在 SQL，
        // 「不出社群活动 + 只出未结束的」由 PUBLIC_SHELF 统一拨 —— 不再由各分支自己记得设开关。

        cmsActivity.setDelFlag(0);
        cmsActivity.setActiveClubOnly(true);

        List<CmsActivity> list;
        startPage();
        list = publicList
                ? cmsActivityService.selectPublicCmsActivityList(cmsActivity, PublicActivityScope.PUBLIC_SHELF)
                : cmsActivityService.selectCmsActivityList(cmsActivity);

        // Batch decoration — collect IDs, query once per type, map back
        List<Long> activityIds = new ArrayList<>();
        Set<Long> publisherIds = new LinkedHashSet<>();
        Set<String> allCategoryIds = new LinkedHashSet<>();
        for (CmsActivity activity : list) {
            if (activity.getId() != null) activityIds.add(activity.getId());
            if (activity.getMemberId() != null) publisherIds.add(activity.getMemberId());
            if (activity.getCategoryIds() != null) {
                for (String part : activity.getCategoryIds().split(",")) {
                    if (!part.trim().isEmpty()) allCategoryIds.add(part.trim());
                }
            }
        }

        Map<Long, Integer> likeMap = new HashMap<>();
        Map<Long, Boolean> followMap = new HashMap<>();
        if (member_id > 0 && !activityIds.isEmpty()) {
            for (CmsActivityLike like : cmsActivityLikeMapper.selectCmsActivityLikesByMemberAndDataIds(member_id, activityIds)) {
                if (like.getDataId() != null) likeMap.put(like.getDataId(), like.getLikeType() != null ? like.getLikeType() : 1);
            }
            if (!publisherIds.isEmpty()) {
                for (UmsMemberFollow f : umsMemberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(member_id, new ArrayList<>(publisherIds))) {
                    if (f.getFollowMemberId() != null) followMap.put(f.getFollowMemberId(), true);
                }
            }
        }

        Map<Long, BigDecimal> salesMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Map<String, Object> row : cmsRegistrationStatisticsMapper.statisticsSalesAmoutByOwners(2, activityIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) salesMap.put(oid, (BigDecimal) row.getOrDefault("totalTicketPrice", BigDecimal.ZERO));
            }
        }

        Map<Long, BigDecimal> priceMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Map<String, Object> row : omsTicketMapper.selectMinTicketPrices(2L, activityIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) priceMap.put(oid, (BigDecimal) row.getOrDefault("minTicketPrice", BigDecimal.ZERO));
            }
        }

        // ROUND(AVG(rating),1) 经 JDBC 回来是 BigDecimal，强转 Double 必炸；收敛到 RatingSummaryAggregator。
        RatingSummaryAggregator ratingSummary = activityIds.isEmpty()
                ? RatingSummaryAggregator.from(null)
                : RatingSummaryAggregator.from(umsCommentMapper.selectRatingSummaryByOwners(2L, activityIds));
        Map<Long, Double> ratingMap = ratingSummary.average;
        Map<Long, Integer> ratingTotalMap = ratingSummary.total;

        Map<Long, Long> browseMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Map<String, Object> row : tmsBrowseRecordMapper.countBrowseByTypeAndTargets(2, activityIds)) {
                Long tid = (Long) row.get("target_id");
                if (tid != null) browseMap.put(tid, (Long) row.getOrDefault("browse_count", 0L));
            }
        }

        Map<String, SysCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            for (SysCategory cat : sysCategoryService.selectSysCategoryListByIds(allCategoryIds.toArray(new String[0]))) {
                if (cat != null && cat.getId() != null) categoryMap.put(String.valueOf(cat.getId()), cat);
            }
        }

        for (CmsActivity activity : list) {
            activity.setIsLiked(member_id > 0 ? likeMap.getOrDefault(activity.getId(), 0) : 0);
            activity.setIsFollowTheUser(member_id > 0 && followMap.containsKey(activity.getMemberId()) ? 1 : 0);
            activity.setSalesAmout(salesMap.getOrDefault(activity.getId(), BigDecimal.ZERO));
            activity.setMinAmout(priceMap.getOrDefault(activity.getId(), BigDecimal.ZERO));
            if (activity.getCategoryIds() != null) {
                List<SysCategory> cats = new ArrayList<>();
                for (String id : activity.getCategoryIds().split(",")) {
                    SysCategory cat = categoryMap.get(id.trim());
                    if (cat != null) cats.add(cat);
                }
                if (!cats.isEmpty()) activity.setSysCategoryList(cats);
            }
            Double r = ratingMap.get(activity.getId());
            if (r != null && r > 0) {
                activity.setRating(r);
                activity.setRatingTotal(ratingTotalMap.getOrDefault(activity.getId(), 0));
            } else {
                activity.setRating(0.0);
                activity.setRatingTotal(0);
            }
            activity.setViewCount(browseMap.getOrDefault(activity.getId(), 0L));
        }

        TableDataInfo data = getDataTable(list);
        if (publicList) {
            List<PublicActivityListVO> publicRows = new ArrayList<>();
            for (CmsActivity activity : list) {
                publicRows.add(PublicActivityListVO.from(activity));
            }
            data.setRows(publicRows);
        }
        return AjaxResult.success(data);
    }

    /** 保留 Java 调用方的既有接口；HTTP 入口使用上面的扩展签名。 */
    public AjaxResult activityList(String is_my, String keyword, String category_id, String sort_type,
                                   String longitude, String latitude) {
        return activityList(is_my, keyword, category_id, sort_type, longitude, latitude,
                null, null, null, null, null);
    }


    @Operation(summary = "收藏列表")
    @PostMapping("/like_list")
    public AjaxResult likeList() {


        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            logger.warn("likeList 获取用户ID异常", e);
        }

        CmsActivity cmsActivity = new CmsActivity();
        cmsActivity.setIsLiked(1);
        cmsActivity.setIsLikedMemberId(member_id);

        List<CmsActivity> list;
        startPage();
        // 收藏列表是唯一保留已结束活动的公开面 —— 用户收藏过的历史活动仍要看得见;社群活动也出。
        list = cmsActivityService.selectPublicCmsActivityList(cmsActivity, PublicActivityScope.MEMBER_FAVOURITES);

        // Batch decoration — collect IDs, query once per type, map back
        List<Long> activityIds = new ArrayList<>();
        Set<Long> publisherIds = new LinkedHashSet<>();
        Set<String> allCategoryIds = new LinkedHashSet<>();
        for (CmsActivity activity : list) {
            if (activity.getId() != null) activityIds.add(activity.getId());
            if (activity.getMemberId() != null) publisherIds.add(activity.getMemberId());
            if (activity.getCategoryIds() != null) {
                for (String part : activity.getCategoryIds().split(",")) {
                    if (!part.trim().isEmpty()) allCategoryIds.add(part.trim());
                }
            }
        }

        Map<Long, Integer> likeMap = new HashMap<>();
        Map<Long, Boolean> followMap = new HashMap<>();
        if (member_id > 0 && !activityIds.isEmpty()) {
            for (CmsActivityLike like : cmsActivityLikeMapper.selectCmsActivityLikesByMemberAndDataIds(member_id, activityIds)) {
                if (like.getDataId() != null) likeMap.put(like.getDataId(), like.getLikeType() != null ? like.getLikeType() : 1);
            }
            if (!publisherIds.isEmpty()) {
                for (UmsMemberFollow f : umsMemberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(member_id, new ArrayList<>(publisherIds))) {
                    if (f.getFollowMemberId() != null) followMap.put(f.getFollowMemberId(), true);
                }
            }
        }

        Map<Long, BigDecimal> salesMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Map<String, Object> row : cmsRegistrationStatisticsMapper.statisticsSalesAmoutByOwners(2, activityIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) salesMap.put(oid, (BigDecimal) row.getOrDefault("totalTicketPrice", BigDecimal.ZERO));
            }
        }

        Map<Long, BigDecimal> priceMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Map<String, Object> row : omsTicketMapper.selectMinTicketPrices(2L, activityIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) priceMap.put(oid, (BigDecimal) row.getOrDefault("minTicketPrice", BigDecimal.ZERO));
            }
        }

        // ROUND(AVG(rating),1) 经 JDBC 回来是 BigDecimal，强转 Double 必炸；收敛到 RatingSummaryAggregator。
        RatingSummaryAggregator ratingSummary = activityIds.isEmpty()
                ? RatingSummaryAggregator.from(null)
                : RatingSummaryAggregator.from(umsCommentMapper.selectRatingSummaryByOwners(2L, activityIds));
        Map<Long, Double> ratingMap = ratingSummary.average;
        Map<Long, Integer> ratingTotalMap = ratingSummary.total;

        Map<Long, Long> browseMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Map<String, Object> row : tmsBrowseRecordMapper.countBrowseByTypeAndTargets(2, activityIds)) {
                Long tid = (Long) row.get("target_id");
                if (tid != null) browseMap.put(tid, (Long) row.getOrDefault("browse_count", 0L));
            }
        }

        Map<String, SysCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            for (SysCategory cat : sysCategoryService.selectSysCategoryListByIds(allCategoryIds.toArray(new String[0]))) {
                if (cat != null && cat.getId() != null) categoryMap.put(String.valueOf(cat.getId()), cat);
            }
        }

        for (CmsActivity activity : list) {
            activity.setIsLiked(member_id > 0 ? likeMap.getOrDefault(activity.getId(), 0) : 0);
            activity.setIsFollowTheUser(member_id > 0 && followMap.containsKey(activity.getMemberId()) ? 1 : 0);
            activity.setSalesAmout(salesMap.getOrDefault(activity.getId(), BigDecimal.ZERO));
            activity.setMinAmout(priceMap.getOrDefault(activity.getId(), BigDecimal.ZERO));
            if (activity.getCategoryIds() != null) {
                List<SysCategory> cats = new ArrayList<>();
                for (String id : activity.getCategoryIds().split(",")) {
                    SysCategory cat = categoryMap.get(id.trim());
                    if (cat != null) cats.add(cat);
                }
                if (!cats.isEmpty()) activity.setSysCategoryList(cats);
            }
            Double r = ratingMap.get(activity.getId());
            if (r != null && r > 0) {
                activity.setRating(r);
                activity.setRatingTotal(ratingTotalMap.getOrDefault(activity.getId(), 0));
            } else {
                activity.setRating(0.0);
                activity.setRatingTotal(0);
            }
            activity.setViewCount(browseMap.getOrDefault(activity.getId(), 0L));
        }

        TableDataInfo data = getDataTable(list);
        List<PublicActivityListVO> publicRows = new ArrayList<>();
        for (CmsActivity activity : list) {
            publicRows.add(PublicActivityListVO.from(activity));
        }
        data.setRows(publicRows);
        return AjaxResult.success(data);
    }


    @Operation(summary = "活动删除")
    @PostMapping("/delete")
    @Parameters({
            @Parameter(name = "id", description = "活动id"),
            @Parameter(name = "scope", description = "MERCHANT=以商家主办身份删除(店主或持项目管理权限的员工);空=俱乐部主理人")
    })
    public AjaxResult activityDelete(String id, String scope) {
        CmsActivity cmsActivity = new CmsActivity();
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }

        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {

        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        final ActivityPublisher publisher;
        try {
            publisher = resolveActivityPublisher(memberId, scope);
        } catch (ServiceException se) {
            return error(se.getMessage());
        }
        if (publisher == null) {
            return error("仅俱乐部主理人或商家可删除活动");
        }

        // 归属校验:仅活动创建者本人可删除,防止越权删除他人活动(IDOR)
        CmsActivity exist = cmsActivityService.selectCmsActivityById(cid);
        if (exist == null || (exist.getDelFlag() != null && exist.getDelFlag() == 2)) {
            return error("活动不存在");
        }
        if (OwnershipUtils.notOwner(exist.getMemberId(), publisher.ownerMemberId())) {
            return error("无权操作该活动");
        }
        String hostedRun = merchantHostedRunViolation(publisher, exist);
        if (hostedRun != null) {
            return error(hostedRun);
        }

        // [资损闸] 删除只软删 del_flag=2,不碰任何 registration、不退款、不回补库存。
        // 若该活动还有已付款报名,删完活动就查不到了,而用户的钱还在平台 —— 钱原地悬空。
        // 「删除」不该隐含大额资金操作,故此处拒绝并引导去走「取消活动」(那条会整单全额退)。
        if (cmsActivityService.countPaidRegistrations(cid) > 0) {
            return error("该活动已有用户付费报名,不能直接删除。请先「取消活动」(将全额退款给所有未核销报名),再删除。");
        }

        // [资损闸 · W9] 上面那道只看 payment_status=2 = 钱已到账,漏了「钱随时可能进来」:玩家拉起支付、
        // 微信回调还没到的单是 0 ⇒ 此刻删活动会放行 ⇒ 回调到达把单置 2 = 钱收了、单成了、活动没了,
        // 而「取消活动 → 全额退」这条补救路也随活动一起查不到了。
        // ⚠️ 无条件拦 0,不按 pay_expire_time 放过「过期弃单」—— 该字段后端无强制力(支付口不校验它),
        // 过期单照样付得了。弃单解堵靠 orderCloseTask 关单成 3(约 35 分钟内自愈),不靠这里放行。
        if (cmsActivityService.countPendingRegistrations(cid) > 0) {
            return error("该活动有用户的报名待支付,此刻删除会导致「钱收了、活动没了」。未支付的单会在支付窗口过期后由系统自动关单(约 30 分钟),届时即可删除。");
        }

        cmsActivity.setId(cid);
        cmsActivity.setDelFlag(2);
        cmsActivity.setMemberId(publisher.ownerMemberId());

        int rows = cmsActivityService.updateCmsActivity(cmsActivity);
        if (rows > 0) {
            return success("删除成功");
        } else {
            return error("删除失败");
        }
    }

    /**
     * 主办方取消活动:对该活动下所有【已付款且未核销】的报名整单全额退款(0 手续费),活动置已取消并下架。
     * 0 手续费全额退是跨平台无分歧的口径 —— 主办方违约却扣用户钱,法理站不住,也是舆情雷。
     */
    @Operation(summary = "取消活动(全额退款给所有已报名用户)")
    @PostMapping("/cancel")
    @Parameters({
            @Parameter(name = "id", description = "活动id"),
            @Parameter(name = "reason", description = "取消原因(展示给已报名用户)"),
            @Parameter(name = "scope", description = "MERCHANT=以商家主办身份取消(店主或有项目管理权限的员工);空=俱乐部主理人")
    })
    public AjaxResult activityCancel(String id, String reason, String scope) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        if (reason == null || reason.trim().isEmpty()) {
            return error("请填写取消原因,它会展示给已报名的用户");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        // F-L5C-02:取消原因会展示给全部已报名用户,写入前送检。
        String reasonSecMsg = wxContentSecurityService.checkText(reason, resolveOpenid(memberId));
        if (reasonSecMsg != null) {
            return error(reasonSecMsg);
        }
        try {
            ActivityCancelResult res;
            if (MerchantOperationScope.isMerchant(scope)) {
                // [C8-05] 商家主办方(天气/场地原因)取消并全额退款:权限=店主或持 PROJECT_MANAGE 的员工,
                // 场次归属按店主判,退款走同一个退款核心;承办别人主题的场次在 service 里 403。
                Long ownerMemberId = merchantAccessService.require(memberId, MerchantPermission.PROJECT_MANAGE)
                        .getOwnerMemberId();
                res = teamGroupService.cancelActivityByMerchantHost(cid, ownerMemberId, memberId, reason.trim());
            } else {
                if (!isClubActivityPublisher(memberId)) {
                    return error("仅俱乐部主理人可取消活动");
                }
                // [6-17] 取消活动必须与 /event-ops/cancel 同一套收尾:系列场次还要终止候补、发站内取消通知、
                // 回收领队。退款金额与派发逻辑仍全在 teamGroupService.cancelActivityByOwner 里,本入口不改钱。
                // ⚠️ 签名是 (actorMemberId, activityId, reason):传反 = 拿活动 id 当操作人(越权),见 FIX-WEATHER。
                res = clubEventOpsService.cancelActivityByOwnerWithClubWrapUp(memberId, cid, reason.trim());
            }
            // ⚠️ else 分支不能写「无已付款报名」—— 全部单都含已核销票被跳过时 refundedOrders=0,
            // 那句话是假的:明明有已付款报名,只是一笔都没自动退。
            StringBuilder msg = new StringBuilder(res.getRefundedOrders() > 0
                    ? ("活动已取消,已为 " + res.getRefundedOrders() + " 笔订单全额退款(原路退回,预计1-3个工作日)")
                    : "活动已取消(无可自动退款的已付款报名)");
            if (res.getManualOrders() > 0) {
                msg.append(";另有 ").append(res.getManualOrders())
                        .append(" 笔订单含已核销的票,无法自动退款,平台将人工跟进处理");
            }
            return AjaxResult.success(msg.toString());
        } catch (ServiceException se) {
            // 业务异常:文案本就是写给主办方看的,原样回显
            return AjaxResult.error(se.getMessage());
        } catch (Exception e) {
            // 非预期异常:getMessage() 可能是 null(弹窗写着「null」)或 SQL 报错(回显表结构),不能给用户看。
            // 注:全局兜底 GlobalExceptionHandler 同样是 error(e.getMessage()),指望它兜是兜不住的。
            logger.error("活动取消失败: activityId={}, memberId={}", cid, memberId, e);
            return AjaxResult.error("系统繁忙，请稍后重试");
        }
    }

    /** [C8-05] 取消确认框要写明「将给 N 位已付款玩家全额退款」:N 由服务端按与取消同一套授权算。 */
    @Operation(summary = "取消活动前预览:将被全额退款的已付款玩家人数")
    @PostMapping("/cancel_preview")
    public AjaxResult activityCancelPreview(String id, String scope) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            int paidPlayers;
            if (MerchantOperationScope.isMerchant(scope)) {
                Long ownerMemberId = merchantAccessService.require(memberId, MerchantPermission.PROJECT_MANAGE)
                        .getOwnerMemberId();
                paidPlayers = teamGroupService.countPaidPlayersForMerchantActivityCancel(cid, ownerMemberId);
            } else if (isClubActivityPublisher(memberId)) {
                paidPlayers = teamGroupService.countPaidPlayersForActivityCancel(cid, memberId);
            } else {
                return error("仅俱乐部主理人可取消活动");
            }
            Map<String, Object> out = new HashMap<>();
            out.put("paidPlayers", paidPlayers);
            return AjaxResult.success(out);
        } catch (ServiceException se) {
            return AjaxResult.error(se.getMessage());
        } catch (Exception e) {
            logger.error("活动取消预览失败: activityId={}, memberId={}", cid, memberId, e);
            return AjaxResult.error("系统繁忙，请稍后重试");
        }
    }

    @Operation(summary = "活动上下架")
    @PostMapping("/update_publish_status")
    @Parameters({
            @Parameter(name = "id", description = "活动id"),
            @Parameter(name = "scope", description = "MERCHANT=以商家主办身份上下架(店主或持项目管理权限的员工);空=俱乐部主理人")
    })
    public AjaxResult activityUpdatePublishStatus(String id, String scope) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {

        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        final ActivityPublisher publisher;
        try {
            publisher = resolveActivityPublisher(memberId, scope);
        } catch (ServiceException se) {
            return error(se.getMessage());
        }
        if (publisher == null) {
            return error("仅俱乐部主理人或商家可上下架活动");
        }

        CmsActivity cmsActivity = cmsActivityService.selectCmsActivityById(cid);
        if (cmsActivity != null) {
            // 归属校验:仅主办主体可上下架自己的活动(IDOR)。店员登录时操作人 ≠ 主体,按主体比。
            if (OwnershipUtils.notOwner(cmsActivity.getMemberId(), publisher.ownerMemberId())) {
                return error("无权操作该活动");
            }
            String hostedRun = merchantHostedRunViolation(publisher, cmsActivity);
            if (hostedRun != null) {
                return error(hostedRun);
            }
            cmsActivity.setPublishStatus(cmsActivity.getPublishStatus() == 1L ? 0L : 1L);
            int rows = cmsActivityService.updateCmsActivity(cmsActivity);
            if (rows > 0) {
                //发布状态：0下架中  1已上架
                return success(cmsActivity.getPublishStatus() == 1L ? "上架成功" : "下架成功");
            } else {
                return error(cmsActivity.getPublishStatus() == 1L ? "上架失败" : "下架失败");
            }
        } else {
            return error("活动不存在");
        }
    }


    @Operation(summary = "活动点赞")
    @PostMapping("/like")
    @Parameters({
            @Parameter(name = "id", description = "活动ID"),
            @Parameter(name = "type", description = "类型 1点赞")
    })
    public AjaxResult activityLike(String id, String type) {
        CmsActivityLike cmsActivityLike = new CmsActivityLike();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        Long dataId = Convert.toLong(id, 0L);
        Integer likeType = Convert.toInt(type, 1);
        if (dataId < 1) {
            return error("参数不合规");
        }

        cmsActivityLike.setDataId(dataId);
        cmsActivityLike.setMemberId(member_id);


        List<CmsActivityLike> list = cmsActivityLikeService.selectCmsActivityLikeList(cmsActivityLike);
        if (list.size() > 0) {
            //取消点赞
            // 提取ID集合
            Long[] ids = list.stream()
                    .map(CmsActivityLike::getId)
                    .toArray(Long[]::new);

            // 调用批量删除方法
            int rows = cmsActivityLikeService.deleteCmsActivityLikeByIds(ids);
            if (rows > 0) {
                return success("取消点赞成功");
            } else {
                return error("取消点赞失败");
            }
        } else {
            //新增
            cmsActivityLike.setLikeType(likeType);
            int rows = cmsActivityLikeService.insertCmsActivityLike(cmsActivityLike);
            if (rows > 0) {
                return success("点赞成功");
            } else {
                return error("点赞失败");
            }
        }
    }

    @Operation(summary = "发布活动")
    @Log(title = "发布活动", businessType = BusinessType.INSERT)
    @PostMapping("/publish")
    public AjaxResult publish(@Validated @RequestBody CmsActivityPublishRequest request) {
        try {

            // 添加调试日志
            // 添加调试日志
            logger.info("接收到请求参数: {}", request);
            logger.info("活动名称: {}", request.getName());


            // 获取当前登录用户ID
            Long member_id = getAppUserId();
            // 参数校验
            if (member_id == null || member_id <= 0) {
                return error("请先登录");
            }

            final ActivityPublisher publisher;
            try {
                publisher = resolveActivityPublisher(member_id, request == null ? null : request.getScope());
            } catch (ServiceException se) {
                return error(se.getMessage());
            }
            if (publisher == null) {
                return error("仅俱乐部主理人或商家可发布活动");
            }
            // 分级审核资质闸:未通过入驻审核的商家不能发布(先审后发也不行)
            if (publisher.isMerchant() && !publisher.isMerchantApproved()) {
                return error("商家未通过入驻审核，暂不能发布活动");
            }
            String hostViolation = merchantHostViolation(publisher, request);
            if (hostViolation != null) {
                return error(hostViolation);
            }

            // RBAC R3：按角色配额校验活动「同时存在」数量(按主办主体,不按操作人)
            String quotaErr = quotaService.checkCreate(publisher.ownerMemberId(), com.chengyinhub.business.service.IQuotaService.TYPE_EVENT);
            if (quotaErr != null) {
                return error(quotaErr);
            }

            // UGC 内容安全门禁(2026-10-20 分级审核):活动按内容先发后审;高风险类目 / 未审核商家 / 总闸 → 先审后发。
            // 明确违规两种模式都拦;先审时门禁 HOLD → 落库待审并建 BIZ_ACTIVITY 审核单(活动尚未落库,门禁不建单)。
            String secOpenid = resolveOpenid(member_id);
            PublishSafetyRequest safety = new PublishSafetyRequest();
            safety.setContentType(com.chengyinhub.business.domain.AuditTask.BIZ_ACTIVITY);
            safety.setMemberId(publisher.ownerMemberId());
            safety.setOpenid(secOpenid);
            safety.setText(IWxContentSecurityService.joinText(request.getName(), request.getDescription()));
            safety.setHasPrice(activityReviewPolicy.hasCharge(request));
            safety.setHasSignup(true);
            safety.setReviewMode(activityReviewPolicy.modeOf(request, publisher.reviewSubject()).name());
            SafetyDecision decision = contentSafetyGate.decide(safety);
            if (decision == null || decision.isBlock()) {
                return error("活动内容包含违规信息，请修改后重试");
            }
            final boolean preReview = decision.isHold();

            String username = getAppUserName();

            // 调用Service层发布活动。竞态安全:锁会员 → 持锁复查活动配额 → 发布,同事务原子,防并发绕过 maxEvents。
            //(上方 checkCreate 为发内容安全前的快速拦截;此处 guard 做权威原子校验;超限抛 ServiceException 由外层 catch 转 error。)
            // 锁与配额都按主办主体 —— 商家店员只是操作人,主体才是活动的容量归属。
            final Long fOwnerMemberId = publisher.ownerMemberId();
            final String fUsername = username;
            Long activityId = quotaService.runWithCreateGuard(fOwnerMemberId,
                    com.chengyinhub.business.service.IQuotaService.TYPE_EVENT,
                    () -> cmsActivityService.publishActivity(request, fOwnerMemberId, fUsername, preReview));
            if (decision.isFlagged()) {
                flagForSpotCheck(activityId, fOwnerMemberId, decision.getFlagReason(), request, false);
            }

            // UGC 图片内容安全:异步送检封面/图集,命中违规由消息推送回调自动下架(best-effort,不阻断)
            wxContentSecurityService.submitMediaCheck(
                    com.chengyinhub.business.service.IWxContentSecurityService.BIZ_TYPE_ACTIVITY,
                    activityId,
                    StringUtils.isNotEmpty(request.getImgArr()) ? request.getImgArr() : request.getImgUrl(),
                    secOpenid);

            return AjaxResult.success(preReview ? "活动发布成功，请等待审核" : "活动发布成功", activityId);

        } catch (ServiceException se) {
            // 配额上限/欠款/内容安全这类业务拒绝的文案本就是写给发布者看的,兜底 catch 会把它压成
            // 「发布活动失败,请稍后重试」—— 商家卡在 max_events 上时那就是静默失败。
            logger.warn("发布活动被业务拒绝：{}", se.getMessage());
            return error(se.getMessage());
        } catch (Exception e) {
            logger.error("发布活动失败：", e);
            return AjaxResult.error("发布活动失败，请稍后重试");
        }
    }

    @Operation(summary = "更新活动")
    @Log(title = "更新活动", businessType = BusinessType.UPDATE)
    @PostMapping("/update")
    public AjaxResult update(@Validated @RequestBody CmsActivityPublishRequest request) {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        final ActivityPublisher publisher;
        try {
            publisher = resolveActivityPublisher(memberId, request == null ? null : request.getScope());
        } catch (ServiceException se) {
            return error(se.getMessage());
        }
        if (publisher == null) {
            return error("仅俱乐部主理人或商家可发布活动");
        }
        String hostViolation = merchantHostViolation(publisher, request);
        if (hostViolation != null) {
            return error(hostViolation);
        }
        Long cid = request == null || request.getId() == null ? 0L : request.getId();
        if (cid < 1) {
            return error("参数不合规");
        }

        CmsActivity exist = cmsActivityService.selectCmsActivityById(cid);
        if (exist == null || (exist.getDelFlag() != null && exist.getDelFlag() == 2)) {
            return error("活动不存在");
        }
        // 归属按主办主体比:店员改自家活动不是越权,但别人(含另一身份)的活动改不了。
        if (OwnershipUtils.notOwner(exist.getMemberId(), publisher.ownerMemberId())) {
            return error("无权操作该活动");
        }
        String hostedRun = merchantHostedRunViolation(publisher, exist);
        if (hostedRun != null) {
            return error(hostedRun);
        }
        // CR-63(2026-09-15 裁决 15):已售出(有已支付报名)后只锁「时间与地点」,文字/图片/音频仍可改。
        // 旧口径「已有人买票就整单拒改」把改错别字也一起锁死了,不属于本次裁决。
        // P1-2(审查D):判据含待支付(0)/支付中(1)—— 玩家下单看到的是旧时间,支付回调到账后
        // 成交的却是新时间,「等回调的窗口」不能成为改期后门。3 支付失败/4 已退款不算。
        // 服务端为准:前端置灰(见 pages/publish/activity)只是体验,这道比较才是真闸。
        if (cmsActivityService.countOpenSaleRegistrations(cid) > 0) {
            String lockedChange = soldTimeLocationChange(exist, request);
            if (lockedChange != null) {
                return error("活动已售出，" + lockedChange + "不能修改；文字、图片、音频仍可改");
            }
        }
        // F-L5C-02 / AUDIT-G1-2:改文案与 /publish 同一道统一门禁 + 同一套分级(ActivityReviewPolicy),
        // 不能先发干净文本再改成违规内容。明确违规两种模式都拒;先审模式下改了内容即退回待审(见 service)。
        final boolean preReview;
        final SafetyDecision decision;
        try {
            CmsActivityPublishRequest policyView = request;
            if (request.getCategoryIds() == null) {
                // 编辑页不一定回带类目:按库里的类目判高风险,不能因为没传就降成先发后审。
                policyView = new CmsActivityPublishRequest();
                org.springframework.beans.BeanUtils.copyProperties(request, policyView);
                policyView.setCategoryIds(exist.getCategoryIds());
            }
            PublishSafetyRequest.ReviewMode mode =
                    activityReviewPolicy.modeOf(policyView, publisher.reviewSubject());
            PublishSafetyRequest safety = new PublishSafetyRequest();
            safety.setContentType(com.chengyinhub.business.domain.AuditTask.BIZ_ACTIVITY);
            safety.setMemberId(publisher.ownerMemberId());
            safety.setOpenid(resolveOpenid(memberId));
            safety.setText(IWxContentSecurityService.joinText(request.getName(), request.getDescription()));
            safety.setHasPrice(activityReviewPolicy.hasCharge(request));
            safety.setHasSignup(true);
            safety.setReviewMode(mode.name());
            decision = contentSafetyGate.decide(safety);
            if (decision == null || decision.isBlock()) {
                return error("活动内容包含违规信息，请修改后重试");
            }
            preReview = mode == PublishSafetyRequest.ReviewMode.PRE_REVIEW
                    || decision.isHold();
        } catch (Exception e) {
            logger.error("活动编辑内容安全裁决失败: activityId={}", cid, e);
            return error("系统繁忙，请稍后重试");
        }

        // O5b-1(开关默认关):文本机审已明确通过时才分级 —— 未打标,且没有因内容留置
        // (先审模式下活动必带报名,门禁一律回 HOLD/STRUCTURED_REVIEW,那是按模式的结构化复核,不是内容存疑)。
        // 轻改(错别字/措辞)不退回待审;先发后审下的实质改动(内容/媒体/时间/地点)走抽检单重新进审核,内容保持在架。
        // 已驳回/待审中的活动不受影响(service 里那两条分支不看 preReview)。
        boolean reReview = preReview;
        boolean substantiveSpotCheck = false;
        boolean textCleared = !decision.isFlagged()
                && (!decision.isHold() || "STRUCTURED_REVIEW".equals(decision.getReasonCode()));
        if (lightEditClassificationEnabled && textCleared) {
            com.chengyinhub.business.service.support.ContentEditClassifier.EditClass editClass =
                    com.chengyinhub.business.service.support.ContentEditClassifier.classifyActivity(exist, request);
            if (editClass == com.chengyinhub.business.service.support.ContentEditClassifier.EditClass.LIGHT) {
                reReview = false;
            } else if (editClass == com.chengyinhub.business.service.support.ContentEditClassifier.EditClass.SUBSTANTIVE
                    && !preReview) {
                substantiveSpotCheck = true;
            }
        }

        try {
            // SQL 的 where 带 member_id,传主体才写得动自家活动(员工只是操作人)。
            cmsActivityService.updateActivityByOwner(request, publisher.ownerMemberId(), getAppUserName(), reReview);
            if (decision.isFlagged() && !preReview) {
                flagForSpotCheck(cid, publisher.ownerMemberId(), decision.getFlagReason(), request, true);
            } else if (substantiveSpotCheck) {
                flagForSpotCheck(cid, publisher.ownerMemberId(), "SUBSTANTIVE_EDIT", request, true);
            }
            return success("活动已更新");
        } catch (ServiceException se) {
            return error(se.getMessage());
        } catch (Exception e) {
            logger.error("更新活动失败: activityId={}, memberId={}", cid, memberId, e);
            return error("系统繁忙，请稍后重试");
        }
    }

    /**
     * CR-63:已售出活动被锁的字段 —— 时间(开始/结束)与地点(名称/详细地址/坐标)。
     *
     * <p>判据只挑「时间与地点」两样:裁决 15 明确文字、图片、音频可改,旧口径整单拒改不保留。
     * 未传(null)的字段视为未修改(编辑页不一定每次都回带全部字段),不制造假变更;
     * 时间按秒比较 —— 客户端毫秒误差不能算「改了时间」;
     * 经纬度按数值比较(审查 §12.3)—— 121.47 与 121.470000 是同一个点,不能把改文字误判成改地点。
     *
     * @return 被改动的类别(时间/地点);都没动返回 null
     */
    private String soldTimeLocationChange(CmsActivity exist, CmsActivityPublishRequest request) {
        if (request.getStartDate() != null && !sameSecond(exist.getStartDate(), request.getStartDate())
                || request.getEndDate() != null && !sameSecond(exist.getEndDate(), request.getEndDate())) {
            return "时间";
        }
        if (request.getAddressName() != null && !Objects.equals(exist.getAddressName(), request.getAddressName())
                || request.getAddress() != null && !Objects.equals(exist.getAddress(), request.getAddress())
                || request.getLongitude() != null && !CoordinateUtils.sameCoordinate(exist.getLongitude(), request.getLongitude())
                || request.getLatitude() != null && !CoordinateUtils.sameCoordinate(exist.getLatitude(), request.getLatitude())) {
            return "地点";
        }
        return null;
    }

    private boolean sameSecond(Date left, Date right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getTime() / 1000L == right.getTime() / 1000L;
    }

    /** 内容安全送检需要发表者 openid:按 memberId 查会员;查不到返回空串(降级 scene 检测)。 */
    /**
     * 先发后审时机审没给出结论(要人工/审不了/缺 openid/检测关闭):活动照常上架,
     * 在审核台留一张非阻断抽检单(source=post_review_flag,不改活动状态),驳回即走既有下架。
     * best-effort:失败只记日志,不影响已上架的活动。
     */
    private void flagForSpotCheck(Long activityId, Long ownerMemberId, String reason,
                                  CmsActivityPublishRequest request, boolean edit) {
        logger.warn("活动先发后审打标待抽检 activityId={} reason={}", activityId, reason);
        try {
            // AUDIT-G1-5:快照带简介/图片/票价/俱乐部/主办会员,不只是 name。
            String snap = com.chengyinhub.business.service.support.ActivityAuditSnapshot.of(
                    activityId, request, ownerMemberId, reason);
            if (edit) {
                // 编辑时可能已有开放单(发布时的抽检单等):刷新而不是再插一张撞唯一约束。
                auditTaskService.submitOrRefreshActivity(activityId, ownerMemberId, "post_review_flag", snap);
            } else {
                auditTaskService.submit(com.chengyinhub.business.domain.AuditTask.BIZ_ACTIVITY, activityId,
                        ownerMemberId, "post_review_flag", snap);
            }
        } catch (Exception e) {
            logger.error("活动抽检单提交失败 activityId={}", activityId, e);
        }
    }

    private String resolveOpenid(Long memberId) {
        try {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null ? "" : m.getWxOpenId();
        } catch (Exception e) {
            return "";
        }
    }

    /** 活动生命周期写操作统一要求俱乐部主理人身份。 */
    private boolean isClubActivityPublisher(Long memberId) {
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        return member != null && IRoleService.ROLE_CLUB.equals(roleService.resolveRole(member));
    }

    /**
     * 活动写链路的「主办主体 + 实际操作人」。
     * ⚠️ member_id 落的是**主体**:店员登录时操作人 ≠ 店主,配额与归属校验一律按主体算 ——
     *    拿操作人比归属会把商家自己的活动判成「无权操作该活动」。
     */
    static final class ActivityPublisher {
        private final Long ownerMemberId;
        private final Long actorMemberId;
        private final boolean merchant;
        /** 商家主体已通过入驻审核(mms_merchant.status=1 等,真源是 MerchantAccessServiceImpl#isActiveMerchant) */
        private final boolean merchantApproved;

        private ActivityPublisher(Long ownerMemberId, Long actorMemberId, boolean merchant) {
            this(ownerMemberId, actorMemberId, merchant, false);
        }

        private ActivityPublisher(Long ownerMemberId, Long actorMemberId, boolean merchant, boolean merchantApproved) {
            this.ownerMemberId = ownerMemberId;
            this.actorMemberId = actorMemberId;
            this.merchant = merchant;
            this.merchantApproved = merchantApproved;
        }

        Long ownerMemberId() {
            return ownerMemberId;
        }

        Long actorMemberId() {
            return actorMemberId;
        }

        boolean isMerchant() {
            return merchant;
        }

        boolean isMerchantApproved() {
            return merchantApproved;
        }

        com.chengyinhub.business.service.support.ActivityReviewPolicy.Publisher reviewSubject() {
            return new com.chengyinhub.business.service.support.ActivityReviewPolicy.Publisher(merchant, merchantApproved);
        }
    }

    /**
     * 双身份解析(与 /api/activity/cancel、优惠券写链路同一套口径):
     * scope=MERCHANT ⇒ 商家主办,主体 = mms_merchant.member_id,权限要求店主或持 PROJECT_MANAGE 的店员;
     * 否则要求俱乐部主理人。
     * 身份不满足时 merchantAccessService.require 抛 ServiceException,由调用点原样透出文案;
     * 返 null 表示两条身份都不成立(玩家 / 未入驻),由调用点按各自动作回文案。
     */
    private ActivityPublisher resolveActivityPublisher(Long principalMemberId, String scope) {
        if (MerchantOperationScope.isMerchant(scope)) {
            com.chengyinhub.business.access.merchant.MerchantAccessContext context =
                    merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
            // require 只对通过入驻审核的商家行返回上下文;merchantId 为空即视为未审核
            return new ActivityPublisher(context.getOwnerMemberId(), principalMemberId, true,
                    context.getMerchantId() != null);
        }
        if (!isClubActivityPublisher(principalMemberId)) {
            return null;
        }
        return new ActivityPublisher(principalMemberId, principalMemberId, false);
    }

    /**
     * 商家主办这一路的约束。返 null=可放行。
     * ① club_id 有值就是俱乐部场次(公域不出、收尾走主理人那条线),两种身份不能混挂;
     * ② 收款:全仓没有 sub_mchid,下单一律走平台商户号(WechatPayServiceImpl#buildPrepayRequest),
     *    商家主办收钱等于替平台收 —— 9-15 定稿「钱进商家子商户号」尚未落地,故先只放开免费场,
     *    并给明确提示而不是静默失败。
     */
    private String merchantHostViolation(ActivityPublisher publisher, CmsActivityPublishRequest request) {
        if (!publisher.isMerchant() || request == null) {
            return null;
        }
        if (request.getClubId() != null && request.getClubId() > 0) {
            return "商家主办活动不能挂到俱乐部";
        }
        List<ActivityTicketRequest> tickets = request.getTickets();
        if (tickets != null) {
            for (ActivityTicketRequest ticket : tickets) {
                BigDecimal price = ticket == null ? null : ticket.getPrice();
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    return "商家主办活动还没开通收款，票价只能是 0";
                }
            }
        }
        return null;
    }

    /**
     * 俱乐部活动在商家域只读;承办场次(topic_id 有值)也不能由商家代替主题主办方修改,
     * 改/上架/删的决定权在主办方。取消那条线已在 TeamGroupServiceImpl 里挡,这里补齐剩下的写口。
     */
    private String merchantHostedRunViolation(ActivityPublisher publisher, CmsActivity exist) {
        if (publisher.isMerchant() && exist != null && exist.getClubId() != null && exist.getClubId() > 0) {
            return "俱乐部活动需由俱乐部主理人处理";
        }
        if (publisher.isMerchant() && exist != null && exist.getTopicId() != null && exist.getTopicId() > 0) {
            return "这场是承办场次，需由主题主办方处理";
        }
        return null;
    }


    @Operation(summary = "活动详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "活动id")
    })
    public AjaxResult activityInfo(String id) {
        CmsActivity cmsActivity = new CmsActivity();

        Long activityId = Convert.toLong(id, 0L);

        if (activityId > 0) {
            cmsActivity.setId(activityId);
        } else {
            return error("参数不合规");
        }

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            logger.warn("activityInfo 获取用户ID异常", e);
        }

        cmsActivity = cmsActivityService.selectCmsActivityDetailById(activityId, member_id);
        if (cmsActivity != null) {
            // M1-3 社群活动门卡:非俱乐部成员(且非创建者)分享落地看到加入引导,不下发完整详情
            if (cmsActivity.getClubId() != null && cmsActivity.getClubId() > 0) {
                boolean isMember = member_id > 0
                        && clubMemberService.selectByClubAndMember(cmsActivity.getClubId(), member_id) != null;
                boolean isOwner = member_id > 0 && OwnershipUtils.isOwner(cmsActivity.getMemberId(), member_id);
                if (!isMember && !isOwner) {
                    Map<String, Object> gate = new HashMap<>();
                    gate.put("gate", true);
                    gate.put("clubId", cmsActivity.getClubId());
                    gate.put("activityName", cmsActivity.getName());
                    gate.put("message", "来自俱乐部的活动，加入后查看");
                    return AjaxResult.success(gate);
                }
            }
            ActivityInfoVO activityInfoVO = new ActivityInfoVO();
            BeanUtils.copyBeanProp(activityInfoVO, cmsActivity);
            /* CU-M-106:活动状态的判定与「我的项目」列表共用 ActivityLifecycle —— cms_activity.status
               是审核码(0 待审 / 1 通过 / 2 未过),不是 official_event 那套 0..9 生命周期码。
               详情页过去拿它查生命周期表,审核通过(1)就被念成「即将开始」,而同一行在列表说「进行中」。 */
            activityInfoVO.setState(ActivityLifecycle.state(cmsActivity));
            activityInfoVO.setStateText(ActivityLifecycle.stateText(activityInfoVO.getState()));
            // 拍板 2026-09-16 #10:场次归属商家已打烊时,报名入口直接提示「商家暂停营业」。
            activityInfoVO.setMerchantClosed(
                    merchantBusinessStatusService.isTargetMerchantClosed(2, activityId));
            // CR-63:编辑页据此把时间/地点置灰并说明原因(真正的闸在 update/editOps)。
            // P1-2:与 update 的口径一致 —— 待支付/支付中也算「已售出」,不然界面说可改、保存被拒。
            activityInfoVO.setTimeLocationLocked(cmsActivityService.countOpenSaleRegistrations(activityId) > 0);

            Long templateId = cmsActivity.getTemplateId();
            // 将单个ID转换为List
            List<Long> idTemplateList = Arrays.asList(templateId);
            //活动模版
            List<CmsMemberTemplate> listMemberTemplate = memberTemplateService.selectCmsMemberTemplateByIds(idTemplateList);
            if (listMemberTemplate.size() > 0) {
                for (CmsMemberTemplate template : listMemberTemplate) {
                    com.chengyinhub.business.util.TemplateSecrets.strip(template);
                }
                activityInfoVO.setMemberTemplateList(listMemberTemplate);
            }
            if (cmsActivity.getTopicId() != null && cmsActivity.getTopicId() > 0) {
                CmsTopicNode nodeProbe = new CmsTopicNode();
                nodeProbe.setTopicId(cmsActivity.getTopicId());
                nodeProbe.setStatus(1L);
                activityInfoVO.setPlayNodeCount(cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe).size());
            } else {
                activityInfoVO.setPlayNodeCount(listMemberTemplate.size());
            }
            //活动阵容
            ViewCollaborators viewCollaborators = new ViewCollaborators();
            viewCollaborators.setDataType(2L);
            viewCollaborators.setDataId(activityId);
            List<ViewCollaborators> listViewCollaborators = viewCollaboratorsService.selectViewCollaboratorsList(viewCollaborators);
            if (listViewCollaborators.size() > 0) {
                List<PublicCollaboratorVO> collaborators = new ArrayList<>();
                for (ViewCollaborators collaborator : listViewCollaborators) {
                    collaborators.add(PublicCollaboratorVO.from(collaborator));
                }
                activityInfoVO.setCollaboratorsList(collaborators);
            }

            //活动评价
            ViewComment viewComment = new ViewComment();
            viewComment.setOwnerType(2L);
            viewComment.setOwnerId(cmsActivity.getId());
            viewComment.setStatus(1);
            viewComment.setDelFlag(0);


            List<ViewComment> list = commentService.selectViewCommentList(viewComment);
            activityInfoVO.setCommentCount(list.size());
            // CU-C-50:活动评分按真实评论算(与主题详情同口径),无评论自然落 0,不再写死 5
            UmsComment ratingCondition = new UmsComment();
            ratingCondition.setOwnerType(2L);
            ratingCondition.setOwnerId(cmsActivity.getId());
            ratingCondition.setStatus(1);
            ratingCondition.setDelFlag(0);
            Double averageRating = umsCommentService.selectAvgRatingByCondition(ratingCondition);
            // VO 是 float,AVG 无行时为 null,这里兜底成 0 避免拆箱异常
            activityInfoVO.setAverageRating(averageRating == null ? 0F : averageRating.floatValue());
            if (list.size() > 0) {
                List<ViewComment> commentList = new ArrayList<>();
                for (int i = 0; i < Math.min(5, list.size()); i++) {
                    commentList.add(list.get(i));
                }
                activityInfoVO.setCommentList(commentList);
            }

            //活动票务
            OmsTicket omsTicket = new OmsTicket();
            omsTicket.setDelFlag(0);
            omsTicket.setOwnerType(2L);
            omsTicket.setOwnerId(activityId);
            List<OmsTicket> listOmsTicket = omsTicketService.selectOmsTicketList(omsTicket);
            if (listOmsTicket.size() > 0) {
                List<PublicTicketVO> publicTickets = new ArrayList<>();
                for (int i = 0; i < listOmsTicket.size(); i++) {
                    ViewRegistration viewRegistration = new ViewRegistration();
                    viewRegistration.setTicketId(listOmsTicket.get(i).getId());
                    viewRegistration.setRegistrationStatus(2);
                    List<ViewRegistration> viewRegistrationList = viewRegistrationService.selectRegistrationSummaries(viewRegistration);
                    publicTickets.add(PublicTicketVO.from(listOmsTicket.get(i), viewRegistrationList));
                }
                activityInfoVO.setOmsTicketList(publicTickets);
            }
            // 回填当前用户报名状态：存在已支付报名(ownerType=2 活动 + registrationStatus=2)则 isSignUp=1
            // 前端据此显示「继续游玩」并拦重复下单；未登录 member_id=0 时查不到→0
            ViewRegistration mySignProbe = new ViewRegistration();
            mySignProbe.setOwnerType(2L);
            mySignProbe.setOwnerId(activityId);
            mySignProbe.setMemberId(member_id);
            mySignProbe.setRegistrationStatus(2);
            List<ViewRegistration> mySignList = viewRegistrationService.selectRegistrationSummaries(mySignProbe);
            activityInfoVO.setIsSignUp(mySignList != null && !mySignList.isEmpty() ? 1 : 0);


            //查询主题的分类信息
            String categoryIds = activityInfoVO.getCategoryIds();
            if (!StringUtils.isEmpty(categoryIds)) {
                String[] ids = categoryIds.split(",");
                List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                if (listCategory.size() > 0) {
                    activityInfoVO.setSysCategoryList(listCategory);
                }
            }


            //活动评价
            ViewRegistration viewRegistration = new ViewRegistration();
            viewRegistration.setOwnerType(2L);
            viewRegistration.setOwnerId(cmsActivity.getId());
            viewRegistration.setRegistrationStatus(2);

            List<ViewRegistration> viewRegistrationList = viewRegistrationService.selectRegistrationSummaries(viewRegistration);
            activityInfoVO.setRegistrationCount(viewRegistrationList.size());

            if (viewRegistrationList.size() > 0) {
                List<PublicRegistrationSummaryVO> viewRegistrations = new ArrayList<>();
                for (int i = 0; i < Math.min(5, viewRegistrationList.size()); i++) {
                    viewRegistrations.add(PublicRegistrationSummaryVO.from(viewRegistrationList.get(i)));
                }
                activityInfoVO.setRegistrationList(viewRegistrations);
            }

            //增加活动浏览量
            TmsBrowseRecord tmsBrowseRecord = new TmsBrowseRecord();
            tmsBrowseRecord.setMemberId(member_id);
            // browse_type 约定:活动=2、主题=1(读侧见本类 :257/:383 与 ApiTopicController)
            tmsBrowseRecord.setBrowseType(2L); //活动
            tmsBrowseRecord.setTargetId(activityId);
            tmsBrowseRecord.setTargetTitle(activityInfoVO.getName());
            tmsBrowseRecord.setBrowseTime(new Date());
            tmsBrowseRecordService.insertTmsBrowseRecord(tmsBrowseRecord);


            // 查询活动的销售金额
            Map<String, Object> params = new HashMap<>();
            params.put("ownerType", 2);  // 2：活动
            params.put("ownerId", cmsActivity.getId()); // 活动ID

            // 调用 mapper 方法
            List<Map<String, Object>> statistics = cmsRegistrationStatisticsMapper.statisticsSalesAmoutByOwner(params);
            cmsActivity.setSalesAmout(new BigDecimal(0));
            // 解析结果
            for (Map<String, Object> stat : statistics) {
                Integer ownerType = (Integer) stat.get("owner_type");
                Long ownerId = (Long) stat.get("owner_id");
                BigDecimal totalPrice = (BigDecimal) stat.get("totalTicketPrice");
                cmsActivity.setSalesAmout(totalPrice);
            }

            //查询活动的浏览量
            // 条件必须落在 params2 上:空 map 会让 mapper 的 <where> 和 GROUP BY 都不生成,
            // 退化成全表 COUNT(*),读到的是全站总浏览数
            Map<String, Object> params2 = new HashMap<>();
            params2.put("browseType", 2);  // 2：活动
            params2.put("targetId", cmsActivity.getId()); // 活动ID
            List<Map<String, Object>> browse = tmsBrowseRecordService.countBrowseByTypeAndTarget(params2);
            // 解析结果
            if (browse != null && !browse.isEmpty()) {
                // 因参数指定了browseType和targetId，列表通常只有一条数据
                Map<String, Object> statMap = browse.get(0);

                // 提取浏览类型
                Integer browseType = (Integer) statMap.get("browse_type");
                // 提取目标ID
                Long targetId = ((Number) statMap.get("target_id")).longValue();
                // 提取浏览次数（Long类型，count(*)返回数值类型）
                Long browseCount = ((Number) statMap.get("browse_count")).longValue();
                cmsActivity.setViewCount(browseCount);

            } else {
                cmsActivity.setViewCount(0L);
            }

            // U-168:报名页按服务器时刻置灰场次/票种/候补窗口,与建单闸同一把钟;只读、不入库。
            activityInfoVO.setServerNow(System.currentTimeMillis());
            return success(activityInfoVO);
        } else {
            if (member_id != null && member_id > 0) {
                CmsActivity cancelled = cmsActivityService.selectCmsActivityById(activityId);
                if (cancelled != null && cancelled.getCancelTime() != null
                        && !Integer.valueOf(2).equals(cancelled.getDelFlag())) {
                    ViewRegistration ownTicket = new ViewRegistration();
                    ownTicket.setOwnerType(2L);
                    ownTicket.setOwnerId(activityId);
                    ownTicket.setMemberId(member_id);
                    List<ViewRegistration> tickets = viewRegistrationService.selectRegistrationSummaries(ownTicket);
                    if (OwnershipUtils.isOwner(cancelled.getMemberId(), member_id)
                            || (tickets != null && !tickets.isEmpty())) {
                        AjaxResult result = error("活动已取消，请在订单中查看退款或售后进度");
                        result.put("errorCode", "ACTIVITY_CANCELLED");
                        result.put("canViewOwnOrders", tickets != null && !tickets.isEmpty());
                        return result;
                    }
                }
            }
            return error("活动不存在");
        }

    }


}
