package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.util.ActivityLifecycle;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的项目(发布资产)聚合。
 *
 * 聚合「我发布的主题(topic)」+「我发布的活动(activity)」为统一"项目"视图,供发布广场/我的-我的项目页使用。
 * 不新建数据模型:直接复用 CmsTopic / CmsActivity。
 *
 * ownerType 三值(供前端三 tab 分层 个人/俱乐部/商家,请求可传 ownerType 过滤,缺省=全部):
 *   - club   :clubId != null(俱乐部主理人以俱乐部身份发布)
 *   - merchant:该活动是「我作为商家中标承接」生成的活动 —— 即 activity.topicId 命中我在
 *              cms_registration_merchant 里 auditStatus=1(中标) 的 topicId 集合。
 *              (中标由后台调配 CmsRegistrationMerchantController#select 生成一条 memberId=商家 的 CmsActivity,
 *               故这些活动本就落在「memberId=我」的聚合里,只是过去被并入 member;此处正名为 merchant。)
 *   - member :其余(个人自办的主题/活动)。主题永远不会是 merchant(商家承接的是活动)。
 *
 * 说明:当前状态口径 = 审核态(0待审/1通过/2未过) × 上架态(topic.userStatus / activity.publishStatus),
 * 归一为 state ∈ {pending,online,offline,rejected}。文档设想的"草稿/进行中/已完成"完整生命周期需基于时间的新状态,
 * 属后续增强;本接口暴露 startTime/endTime,前端可据时间自行派生进行中/已完成。
 */
@RestController
@Tag(name = "我的项目")
@RequestMapping("/api/project")
public class ApiProjectController extends BaseController {

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;

    @Autowired
    private com.chengyinhub.business.service.ICoopInviteService coopInviteService;

    @Autowired
    private com.chengyinhub.business.service.IMerchantChapterApplicationService merchantChapterApplicationService;

    @Autowired
    private com.chengyinhub.business.service.IProjectHomeReadService projectHomeReadService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;


    /**
     * 项目主页:同一条路线上的主办方和承接方落到同一个页面。
     *
     * 在此之前这些数据散在五个接口里(topic/info-to-merchant、registration/merchant/info、
     * coop/candidates、coop/list、merchant/upcoming-runs),前端要打五次才拼得出一屏。
     * 身份与联系方式的可见范围一律在 service 里判,不交给前端过滤。
     */
    @Operation(summary = "项目主页(主办/承接双身份聚合)")
    @PostMapping("/home")
    public AjaxResult home(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        Long topicId = null;
        if (body != null && body.get("topicId") != null) {
            try {
                topicId = Long.valueOf(String.valueOf(body.get("topicId")).trim());
            } catch (Exception ignored) {
            }
        }
        try {
            Long scopedMemberId = memberId;
            String scope = scopeOf(body);
            MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
            if (MerchantOperationScope.isMerchant(scope)
                    || (merchant != null && !MerchantOperationScope.isClub(scope))) {
                scopedMemberId = merchantAccessService.require(
                        memberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            }
            return AjaxResult.success(projectHomeReadService.getProjectHome(scopedMemberId, topicId));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Autowired
    private com.chengyinhub.business.service.IProjectPlayerReadService projectPlayerReadService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    /**
     * 项目玩家名单。商家开店前想知道的是「谁要来、谁还没来」,不是「已售 24 份」——
     * 所以这里给名单不给统计口径的分析。
     *
     * 联系方式给不给判在 service 里(主办方自办才给),前端不做二次过滤 ——
     * 前端过滤等于把号码先发出去再假装没发。
     */
    @Operation(summary = "项目玩家名单(谁要来 / 谁还没来)")
    @PostMapping("/players")
    public AjaxResult players(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        Long topicId = null;
        if (body != null && body.get("topicId") != null) {
            try {
                topicId = Long.valueOf(String.valueOf(body.get("topicId")).trim());
            } catch (Exception ignored) {
            }
        }
        try {
            if (MerchantOperationScope.isMerchant(scopeOf(body))) {
                MerchantAccessContext access = merchantAccessService.require(
                        memberId, MerchantPermission.VERIFY_RECORD_READ);
                return AjaxResult.success(projectPlayerReadService.listPlayers(
                        access.getOwnerMemberId(), topicId,
                        access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)));
            }
            CmsTopic topic = topicId == null ? null : cmsTopicService.selectCmsTopicById(topicId);
            if (topic != null && memberId.equals(topic.getMemberId())) {
                MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
                if (merchant == null) {
                    // 普通发布者/俱乐部主理人的既有主办路径不变；只有商家主体必须过 active guard。
                    return AjaxResult.success(projectPlayerReadService.listPlayers(memberId, topicId, true));
                }
                MerchantAccessContext hostAccess = merchantAccessService.require(
                        memberId, MerchantPermission.VERIFY_RECORD_READ);
                return AjaxResult.success(projectPlayerReadService.listPlayers(
                        hostAccess.getOwnerMemberId(), topicId,
                        hostAccess.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)));
            }
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.VERIFY_RECORD_READ);
            return AjaxResult.success(projectPlayerReadService.listPlayers(
                    access.getOwnerMemberId(), topicId,
                    access.hasPermission(MerchantPermission.CRM_SENSITIVE_READ)));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "我的项目列表(主题+活动聚合)")
    @PostMapping("/my")
    public AjaxResult my(String type, String state, String ownerType, Integer pageNum, Integer pageSize,
                         String scope) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            if (MerchantOperationScope.isMerchant(scope)) {
                memberId = merchantAccessService.require(
                        memberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }

        List<Map<String, Object>> all = new ArrayList<>();

        // 0) 我作为商家「中标承接」的 topicId 集合(auditStatus=1=中标),用于把中标生成的活动正名为 merchant
        java.util.Set<Long> wonTopicIds = new java.util.HashSet<>();
        CmsRegistrationMerchant wonProbe = new CmsRegistrationMerchant();
        wonProbe.setMemberId(memberId);
        wonProbe.setAuditStatus(1L);
        wonProbe.setDelFlag(0);
        List<CmsRegistrationMerchant> wonBids = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(wonProbe);
        if (wonBids != null) {
            for (CmsRegistrationMerchant w : wonBids) {
                if (w.getTopicId() != null) wonTopicIds.add(w.getTopicId());
            }
        }

        // 1) 我发布的主题(草稿 id 集合:标记 draft 态)
        java.util.Set<Long> draftSet = new java.util.HashSet<>(cmsTopicService.selectDraftTopicIds(memberId));
        CmsTopic topicQuery = new CmsTopic();
        topicQuery.setMemberId(memberId);
        topicQuery.setDelFlag(0);
        topicQuery.setActiveClubOnly(true);
        List<CmsTopic> topics = cmsTopicService.selectCmsTopicList(topicQuery);
        Map<Long, java.util.Set<String>> acceptedTypes = new HashMap<>();
        if (topics != null && !topics.isEmpty()) {
            List<Long> topicIds = topics.stream().map(CmsTopic::getId)
                    .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
            CoopInvite inviteQuery = new CoopInvite();
            inviteQuery.setStatus(1);
            inviteQuery.getParams().put("topicIds", topicIds);
            List<CoopInvite> accepted = coopInviteService.selectCoopInviteList(inviteQuery);
            if (accepted != null) {
                for (CoopInvite invite : accepted) {
                    acceptedTypes.computeIfAbsent(invite.getTopicId(), key -> new java.util.HashSet<>())
                            .add(invite.getToType());
                }
            }
        }
        java.util.Set<Long> acceptedChapterTopics = new java.util.HashSet<>();
        if (topics != null && topics.stream().anyMatch(t -> t.getClubId() != null
                && Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)
                        .equals(t.getProductType()))) {
            List<MerchantChapterApplication> applications =
                    merchantChapterApplicationService.selectForPublisher(memberId);
            for (MerchantChapterApplication application : applications) {
                if (Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED).equals(application.getStatus())
                        && Boolean.TRUE.equals(application.getOfferActive())) {
                    acceptedChapterTopics.add(application.getTopicId());
                }
            }
        }
        if (topics != null) {
            Map<Long, Integer> signupCounts = cmsActivityService.countSignedUpRegistrationsByTopicIds(
                    topics.stream().map(CmsTopic::getId).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toList()));
            for (CmsTopic t : topics) {
                t.setSignupCount(signupCounts.getOrDefault(t.getId(), 0));
                all.add(mapTopic(t, draftSet, acceptedChapterTopics, acceptedTypes));
            }
        }

        // 2) 我发布的活动
        CmsActivity actQuery = new CmsActivity();
        actQuery.setMemberId(memberId);
        actQuery.setActiveClubOnly(true);
        List<CmsActivity> acts = cmsActivityService.selectCmsActivityList(actQuery);
        if (acts != null) {
            Map<Long, Integer> signupCounts = cmsActivityService.countSignedUpRegistrationsByActivityIds(
                    acts.stream().map(CmsActivity::getId).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toList()));
            List<Long> merchantActivityIds = acts.stream()
                    .filter(a -> a.getClubId() == null && wonTopicIds.contains(a.getTopicId()))
                    .map(CmsActivity::getId).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
            Map<Long, Integer> pendingVerifyCounts = merchantActivityIds.isEmpty()
                    ? java.util.Collections.emptyMap()
                    : cmsActivityService.countPendingVerificationByActivityIds(merchantActivityIds);
            for (CmsActivity a : acts) {
                all.add(mapActivity(a, wonTopicIds, signupCounts, pendingVerifyCounts));
            }
        }

        // 3) 过滤:ownerType(三 tab) / type / state
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : all) {
            if (ownerType != null && !ownerType.isEmpty() && !"all".equalsIgnoreCase(ownerType)
                    && !ownerType.equals(item.get("ownerType"))) {
                continue;
            }
            if (type != null && !type.isEmpty() && !"all".equalsIgnoreCase(type)
                    && !type.equals(item.get("projectType"))) {
                continue;
            }
            if (state != null && !state.isEmpty() && !"all".equalsIgnoreCase(state)
                    && !state.equals(item.get("state"))) {
                continue;
            }
            filtered.add(item);
        }

        // 4) 排序:创建时间(id 兜底)倒序,新的在前
        filtered.sort(Comparator.comparingLong((Map<String, Object> m) -> toLong(m.get("id"))).reversed());

        // 5) 未结束项目总数:工作台「全部 N」不能用本页 rows 数 —— 一页 10 条会把 30 个项目说成 10 个。
        //    口径与小程序 utils/merchant-workbench.js 的 isEndedProject 逐字相同:
        //    档期未知(没有开始时间)不算结束;缺结束时间按单日算,端上坏数据(end < start)同退化。
        int activeTotal = 0;
        for (Map<String, Object> item : filtered) {
            if (!endedProject(item)) activeTotal++;
        }

        // 6) 内存分页(个人项目量小)
        int total = filtered.size();
        int pn = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int ps = pageSize == null || pageSize < 1 ? 10 : pageSize;
        int from = Math.min((pn - 1) * ps, total);
        int to = Math.min(from + ps, total);
        List<Map<String, Object>> pageRows = filtered.subList(from, to);

        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("activeTotal", activeTotal);
        data.put("rows", pageRows);
        return AjaxResult.success(data);
    }

    /**
     * 项目是否已结束。与小程序「项目卡状态签」同一判据:今天晚于结束日才算结束。
     *
     * <p>日期字段主题/活动各有两种形态:主题 mapTopic 放的是 {@code java.util.Date},
     * 活动放的是 {@code String};分页前 Map 里都是原样对象,这里两种都接。
     * 时区显式用 Asia/Shanghai —— 端上按设备日历日比较,生产用户在东八区。 */
    private boolean endedProject(Map<String, Object> item) {
        java.time.LocalDate start = toProjectDay(item.get("startTime"));
        if (start == null) start = toProjectDay(item.get("startDate"));
        if (start == null) return false;
        java.time.LocalDate end = toProjectDay(item.get("endTime"));
        if (end == null) end = toProjectDay(item.get("endDate"));
        if (end == null || end.isBefore(start)) end = start;
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).isAfter(end);
    }

    /** 项目日期 → 上海日历日。Date(含 java.sql.Date)/毫秒时间戳/『yyyy-MM-dd[ HH:mm[:ss]]』字符串都认。 */
    private java.time.LocalDate toProjectDay(Object value) {
        if (value == null) return null;
        java.time.Instant instant;
        if (value instanceof java.util.Date) {
            // java.sql.Date.toInstant() 会抛 UnsupportedOperationException,统一走 getTime()
            instant = java.time.Instant.ofEpochMilli(((java.util.Date) value).getTime());
        } else if (value instanceof Number) {
            instant = java.time.Instant.ofEpochMilli(((Number) value).longValue());
        } else {
            java.util.Date parsed = parseDate(String.valueOf(value));
            if (parsed == null) return null;
            instant = java.time.Instant.ofEpochMilli(parsed.getTime());
        }
        return instant.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private String scopeOf(Map<String, Object> body) {
        if (body == null || body.get("scope") == null) return null;
        return String.valueOf(body.get("scope"));
    }

    private Map<String, Object> mapTopic(CmsTopic t, java.util.Set<Long> draftSet,
                                       java.util.Set<Long> acceptedChapterTopics,
                                       Map<Long, java.util.Set<String>> acceptedTypes) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("bizType", "topic");
        // 项目类型:自玩 > 简单 > 专业
        String projectType;
        if (t.getSelfPlay() != null && t.getSelfPlay() == 1) {
            projectType = "self_play";
        } else if ("simple".equalsIgnoreCase(t.getPublishMode()) || "ai_simple".equalsIgnoreCase(t.getPublishMode())) {
            projectType = "simple_topic";
        } else {
            projectType = "pro_topic";
        }
        m.put("projectType", projectType);
        m.put("projectTypeText", projectTypeText(projectType));
        m.put("title", t.getName());
        m.put("cover", t.getImgUrl());
        int status = t.getStatus() == null ? 0 : t.getStatus();
        int userStatus = t.getUserStatus() == null ? 0 : t.getUserStatus();
        boolean isDraft = t.getId() != null && draftSet != null && draftSet.contains(t.getId());
        java.util.Date start = t.getStartDate();
        java.util.Date end = t.getEndDate();
        String state = lifecycleState(status, userStatus, isDraft, start, end);
        m.put("state", state);
        m.put("stateText", ActivityLifecycle.stateText(state));
        m.put("publishStatus", userStatus == 1 ? "online" : "offline");
        m.put("signupCount", t.getSignupCount() == null ? 0 : t.getSignupCount());
        m.put("viewCount", t.getViewCount() == null ? 0L : t.getViewCount());
        m.put("ownerType", t.getClubId() != null ? "club" : "member");
        m.put("clubId", t.getClubId());
        m.put("startTime", start);
        m.put("endTime", end);
        /* 稿 260:277 的卡片在标题下面还有一行地址(「静安区愚园路 68 号」)。
           cms_topic.address_name 一直有这个字段、列表 SQL 也一直在 select，
           只是这个接口没往外送 —— 小程序那边缺的是数据，不是版式。
           缺就不下发，前端按稿自己的规矩「缺就整行不出」。 */
        m.put("addressName", t.getAddressName());
        m.put("createTime", t.getCreateTime());
        putAcceptStatus(m, t, acceptedChapterTopics, acceptedTypes);
        return m;
    }

    /**
     * P0-4 承接三态(验收:每张项目卡显示当前承接状态)。
     * 对手方按主题归属定:个人/商家主题(clubId=null)看俱乐部,俱乐部主题看商家。
     * 「已承接」= 已接受对手方邀约；自由探索商家章节还可由已批准且供给生效的申请确认。
     * 「未承接」= 开了对应池(open_club_pool / merchant_status)但还没成交;其余 = 自行承办。
     */
    private void putAcceptStatus(Map<String, Object> m, CmsTopic t,
                                 java.util.Set<Long> acceptedChapterTopics,
                                 Map<Long, java.util.Set<String>> acceptedTypes) {
        java.util.Set<String> types = acceptedTypes.getOrDefault(t.getId(), java.util.Collections.emptySet());
        boolean clubAccepted = types.contains("club");
        boolean merchantAccepted = types.contains("merchant")
                || (Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)
                .equals(t.getProductType()) && acceptedChapterTopics.contains(t.getId()));
        String status;
        String text;
        if (t.getClubId() != null) {
            // 俱乐部主题:对手方 = 商家
            if (merchantAccepted) { status = "merchantAccepted"; text = "商家已承接"; }
            else if (t.getMerchantStatus() != null && t.getMerchantStatus() == 1) { status = "merchantOpen"; text = "商家未承接"; }
            else { status = "selfRun"; text = "自行承办"; }
        } else {
            // 个人/商家主题:对手方 = 俱乐部
            if (clubAccepted) { status = "clubAccepted"; text = "俱乐部已承接"; }
            else if (t.getOpenClubPool() != null && t.getOpenClubPool() == 1) { status = "clubOpen"; text = "俱乐部未承接"; }
            else { status = "selfRun"; text = "自行承办"; }
        }
        m.put("acceptStatus", status);
        m.put("acceptStatusText", text);
    }

    private Map<String, Object> mapActivity(CmsActivity a, java.util.Set<Long> wonTopicIds,
                                            Map<Long, Integer> signupCounts,
                                            Map<Long, Integer> pendingVerifyCounts) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("bizType", "activity");
        m.put("projectType", "club_activity");
        m.put("projectTypeText", projectTypeText("club_activity"));
        m.put("title", a.getName());
        m.put("cover", a.getImgUrl());
        int publishStatus = a.getPublishStatus() == null ? 0 : a.getPublishStatus().intValue();
        // CU-C-47:档期一律取落库的 start_date/end_date。CmsActivity.startTime/endTime 是
        // 「搜索用」瞬态字段(@TableField(exist=false),全仓无写入点),拿它下发给前端恒为 null
        // —— 我的项目卡片因此整行档期不渲染。与 mapTopic 同口径。
        java.util.Date activityStart = a.getStartDate();
        java.util.Date activityEnd = a.getEndDate();
        String state = ActivityLifecycle.state(a);
        m.put("state", state);
        m.put("stateText", ActivityLifecycle.stateText(state));
        m.put("publishStatus", publishStatus == 1 ? "online" : "offline");
        // CU-M-175:卡片「N 报名」与详情页「已报名 N 人」必须是同一个数。原来这里取
        // countOpenSaleRegistrations(payment_status 0/1/2,含拉起支付没付完的弃单),详情取
        // registration_status=2,同一活动两张屏 1 vs 0。开卖口径只留给「已售不可改」那类锁判据。
        m.put("signupCount", signupCounts.getOrDefault(a.getId(), 0));
        m.put("viewCount", a.getViewCount() == null ? 0L : a.getViewCount());
        // ownerType 优先级:club > merchant(中标承接活动) > member
        String ownerType;
        if (a.getClubId() != null) {
            ownerType = "club";
        } else if (a.getTopicId() != null && wonTopicIds != null && wonTopicIds.contains(a.getTopicId())) {
            ownerType = "merchant";
        } else {
            ownerType = "member";
        }
        m.put("ownerType", ownerType);
        /* 承办活动(我中标别人的主题生成的那条)底行按稿写「待核销 N」。
           只对承办卡算 —— 主办卡的底行是「N 报名 · N 浏览」,不需要这个数,
           全量算等于给每张卡多打一次库。 */
        if ("merchant".equals(ownerType)) {
            m.put("pendingVerifyCount", pendingVerifyCounts.getOrDefault(a.getId(), 0));
        }
        m.put("clubId", a.getClubId());
        m.put("startTime", activityStart);
        m.put("endTime", activityEnd);
        // 活动这边两个字段都有:addressName 是地点名,address 是详细地址,优先给地点名
        m.put("addressName", a.getAddressName() != null && !a.getAddressName().isEmpty()
                ? a.getAddressName() : a.getAddress());
        m.put("createTime", a.getCreateTime());
        return m;
    }

    /** 主题归一 state:草稿 > 未通过 > 审核中 > 已下架 > 在架(按时间派生 未开始/进行中/已完成) */
    private String lifecycleState(int status, int userStatus, boolean isDraft, java.util.Date start, java.util.Date end) {
        if (isDraft) return "draft";
        if (status == 2) return "rejected";
        if (status == 0) return "pending";
        if (userStatus != 1) return "offline";
        return ActivityLifecycle.timeBucket(start, end);
    }

    private java.util.Date parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String v = s.trim().replace('/', '-');
        for (String fmt : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"}) {
            try { return new java.text.SimpleDateFormat(fmt).parse(v); } catch (Exception ignore) { }
        }
        return null;
    }

    private String projectTypeText(String type) {
        switch (type) {
            case "simple_topic": return "简单主题";
            case "pro_topic": return "专业主题";
            case "self_play": return "自己玩";
            case "club_activity": return "俱乐部活动";
            default: return "";
        }
    }

    private long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0L;
    }
}
