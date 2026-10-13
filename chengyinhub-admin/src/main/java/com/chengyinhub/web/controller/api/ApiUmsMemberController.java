package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.domain.vo.NicknameCheckVO;
import com.chengyinhub.business.domain.vo.SelfMemberVO;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.NicknameSuggestionServiceImpl;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.common.core.domain.AjaxResult;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.utils.StringUtils;


/**
 * 用户相关接口
 *
 * @author webxh
 * @date 2025-08-22
 */
@RestController
//@Api(tags = "01.通用")
@Tag(name = "用户相关")
@RequestMapping("/api/user")
public class ApiUmsMemberController extends BaseController {
    private static final String ADDRESS_UNAVAILABLE = "地址不存在或不可用";
    private static final String MEMBER_UNAVAILABLE = "用户不存在或不可用";
    private static final String INVITATION_FAILED = "邀请关系设置失败";

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IPublicMemberService publicMemberService;

    @Autowired
    private IMemberInvitationService memberInvitationService;

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
    private IUmsMemberAddressService umsMemberAddressService;

    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    @Autowired
    private ICommunityPostService communityPostService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    @Autowired
    private ICmsActivityLikeService cmsActivityLikeService;

    @Autowired
    private ICmsTopicLikeService cmsTopicLikeService;

    @Autowired
    private IUmsCreativeSquareLikeService umsCreativeSquareLikeService;

    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;

    @Autowired
    private IPlayerWxacodeService playerWxacodeService;

    @Autowired
    private INicknameSuggestionService nicknameSuggestionService;

    @Autowired
    private IPlayerProgressionService playerProgressionService;

    @Operation(summary = "检查昵称是否可用")
    @PostMapping("/nickname/check")
    public AjaxResult checkNickname(String nickname) {
        return success(nicknameSuggestionService.check(nickname, optionalMemberId()));
    }

    @Operation(summary = "生成当时可用的昵称后缀建议")
    @PostMapping("/nickname/suggest")
    public AjaxResult suggestNickname(String baseName, Integer cursor) {
        NicknameCheckVO result = nicknameSuggestionService.suggest(baseName, cursor, optionalMemberId());
        if (!result.isAvailable()) {
            AjaxResult error = new AjaxResult(com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST,
                    "昵称不符合规则或候选已耗尽");
            error.put("errorCode", result.getErrorCode());
            return error;
        }
        return success(result);
    }

    @Operation(summary = "获取当前玩家个人小程序码")
    @PostMapping("/player-code")
    public AjaxResult getPlayerWxacode() {
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
            return success(Collections.<String, Object>singletonMap(
                    "qr", playerWxacodeService.getOrCreate(memberId)));
        } catch (IllegalStateException e) {
            logger.warn("获取玩家个人小程序码失败，memberId={}，reason={}", memberId, e.getMessage());
            return error("个人码生成服务繁忙，请稍后重试");
        } catch (Exception e) {
            logger.error("获取玩家个人小程序码失败，memberId={}", memberId);
            return error("个人码生成服务繁忙，请稍后重试");
        }
    }

    @Operation(summary = "获取积分统计信息")
    @PostMapping("/points/statistics")
    public AjaxResult getPointsStatistics() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        try {
            PointsStatisticsDTO statistics = umsMemberService.getPointsStatistics(memberId);
            return success(statistics);
        } catch (Exception e) {
            logger.error("获取积分统计信息失败", e);
            return error("获取积分统计信息失败");
        }
    }


    @Operation(summary = "获取余额统计信息")
    @PostMapping("/balance/statistics")
    public AjaxResult getBalanceStatistics() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        try {
            PointsStatisticsDTO statistics = umsMemberService.getBalanceStatistics(memberId);
            return success(statistics);
        } catch (Exception e) {
            logger.error("获取积分统计信息失败", e);
            return error("获取积分统计信息失败");
        }
    }

    @Operation(summary = "积分明细列表")
    @PostMapping("/points/list")
    @Parameters({
            @Parameter(name = "pageNum", description = "页码"),
            @Parameter(name = "pageSize", description = "每页大小")
    })
    public AjaxResult getPointsList() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        try {
            UmsMemberPointsDetail pointsDetail = new UmsMemberPointsDetail();
            pointsDetail.setMemberId(memberId);
            pointsDetail.setDelFlag(0);

            startPage();
            List<UmsMemberPointsDetail> list = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(pointsDetail);
            TableDataInfo data = getDataTable(list);

            return success(data);
        } catch (Exception e) {
            logger.error("获取积分明细失败", e);
            return error("获取积分明细失败");
        }
    }



    @Operation(summary = "余额明细列表")
    @PostMapping("/balance/list")
    @Parameters({
            @Parameter(name = "pageNum", description = "页码"),
            @Parameter(name = "pageSize", description = "每页大小"),
            @Parameter(name = "eventType", description = "事件类型: 1主题分润 2活动分润 3提现申请 4提现驳回")
    })
    public AjaxResult getBalanceList(String eventType) {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        try {
            UmsMemberBalanceDetail balanceDetail = new UmsMemberBalanceDetail();
            balanceDetail.setMemberId(memberId);
            balanceDetail.setDelFlag(Long.valueOf(0));
            if (com.chengyinhub.common.utils.StringUtils.isNotEmpty(eventType)) {
                balanceDetail.setEventType(Long.valueOf(eventType));
            }

            startPage();
            List<UmsMemberBalanceDetail> list = umsMemberBalanceDetailService.selectUmsMemberBalanceDetailList(balanceDetail);
            TableDataInfo data = getDataTable(list);

            return success(data);
        } catch (Exception e) {
            logger.error("获取积分明细失败", e);
            return error("获取积分明细失败");
        }
    }

    @Operation(summary = "统计图表")
    @PostMapping("/statistics_chat")
    @Parameters({
            @Parameter(name = "year", description = "年份")
    })
    public AjaxResult statisticsChat(String year) {


        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }


        Map<String, Object> params = new HashMap<>();
        params.put("year", year);
        params.put("memberId", member_id);
        List<Map<String, Object>> statistics = umsMemberBalanceDetailService.statisticsChangeBalance(params);
        AjaxResult ajax = AjaxResult.success(statistics);
        return ajax;
    }

    @Operation(summary = "收入来源分解（主题/邀请/品牌）")
    @PostMapping("/income/breakdown")
    public AjaxResult getIncomeBreakdown() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            IncomeBreakdownDTO dto = umsMemberBalanceDetailService.getIncomeBreakdown(memberId);
            return success(dto);
        } catch (Exception e) {
            logger.error("获取收入分解失败", e);
            return error("获取收入分解失败");
        }
    }

    @Operation(summary = "主题创作收益统计")
    @PostMapping("/income/topic-stats")
    public AjaxResult getTopicIncomeStats() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            IncomeStatsDTO dto = umsMemberBalanceDetailService.getTopicIncomeStats(memberId);
            return success(dto);
        } catch (Exception e) {
            logger.error("获取主题创作统计失败", e);
            return error("获取主题创作统计失败");
        }
    }

    @Operation(summary = "邀请商户收益统计")
    @PostMapping("/income/invite-stats")
    public AjaxResult getInviteIncomeStats() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            IncomeStatsDTO dto = umsMemberBalanceDetailService.getInviteIncomeStats(memberId);
            return success(dto);
        } catch (Exception e) {
            logger.error("获取邀请商户统计失败", e);
            return error("获取邀请商户统计失败");
        }
    }

    @Operation(summary = "品牌参与收益统计")
    @PostMapping("/income/brand-stats")
    public AjaxResult getBrandIncomeStats() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            IncomeBrandStatsDTO dto = umsMemberBalanceDetailService.getBrandIncomeStats(memberId);
            return success(dto);
        } catch (Exception e) {
            logger.error("获取品牌参与统计失败", e);
            return error("获取品牌参与统计失败");
        }
    }

    @Operation(summary = "主题创作收益列表")
    @PostMapping("/income/topic-list")
    public AjaxResult getTopicIncomeList() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            CmsTopic query = new CmsTopic();
            query.setMemberId(memberId);
            query.setDelFlag(0);
            startPage();
            List<CmsTopic> list = cmsTopicService.selectCmsTopicList(query);
            for (CmsTopic topic : list) {
                topic.setSalesAmout(umsMemberBalanceDetailService.selectIncomeByEventTypeAndEventId(
                        memberId, 1L, topic.getId()));
            }
            TableDataInfo data = getDataTable(list);
            return success(data);
        } catch (Exception e) {
            logger.error("获取主题创作列表失败", e);
            return error("获取主题创作列表失败");
        }
    }

    @Operation(summary = "品牌参与收益列表")
    @PostMapping("/income/brand-list")
    public AjaxResult getBrandIncomeList() {
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            CmsTopic query = new CmsTopic();
            query.setDelFlag(0);
            startPage();
            List<CmsTopic> allTopics = cmsTopicService.selectCmsTopicList(query);
            List<CmsTopic> brandList = new java.util.ArrayList<>();
            for (CmsTopic topic : allTopics) {
                topic.setSalesAmout(umsMemberBalanceDetailService.selectIncomeByEventTypeAndEventId(
                        memberId, 2L, topic.getId()));
                if (topic.getSalesAmout() != null && topic.getSalesAmout().compareTo(BigDecimal.ZERO) > 0) {
                    brandList.add(topic);
                }
            }
            TableDataInfo data = new TableDataInfo();
            data.setRows(brandList);
            data.setTotal(brandList.size());
            return success(data);
        } catch (Exception e) {
            logger.error("获取品牌参与列表失败", e);
            return error("获取品牌参与列表失败");
        }
    }

    @Operation(summary = "用户列表")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "user_type", description = "用户类型：0所有 1普通会员 2商户会员"),
            @Parameter(name = "keyword", description = "搜索词"),
    })
    public AjaxResult userList(String user_type, String keyword) {
        startPage();
        List<PublicMemberVO> list = publicMemberService.selectPublicMemberList(user_type, keyword);
        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }


    @Operation(summary = "用户邀请列表")
    @PostMapping("/invite_list")
    @Parameters({

    })
    public AjaxResult inviteList() {

        // 获取当前登录用户ID
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {

        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }

        startPage();
        List<PublicMemberVO> list = publicMemberService.selectInvitedPublicMemberList(memberId);
        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }


    @Operation(summary = "会员公开资料(匿名可访问,白名单)")
    @PostMapping("/public-info")
    @Parameters({
            @Parameter(name = "member_id", description = "用户id")
    })
    public AjaxResult getPublicUserInfo(String member_id) {
        if (StringUtils.isBlank(member_id)) {
            return error(MEMBER_UNAVAILABLE);
        }
        Long memberId = Convert.toLong(member_id);
        if (memberId == null || memberId < 1) {
            return error(MEMBER_UNAVAILABLE);
        }
        // 未登录不算错误:这条接口就是给匿名冷启动用的。登录了才带观看者关系。
        Long viewerMemberId = 0L;
        try {
            viewerMemberId = getAppUserId();
        } catch (Exception ignore) {
        }
        PublicMemberVO publicMember = viewerMemberId != null && viewerMemberId > 0
                ? publicMemberService.selectPublicMemberById(memberId, viewerMemberId)
                : publicMemberService.selectPublicMemberById(memberId);
        // mapper 已强制 status=1 且 del_flag=0,查不到即视为不可用,不区分禁用/删除/不存在
        return publicMember == null ? error(MEMBER_UNAVAILABLE) : success(publicMember);
    }

    @Operation(summary = "用户详情，不传用户ID就是查询自己")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "member_id", description = "用户id")
    })
    public AjaxResult getUserInfo(String member_id) {

        boolean isMy = false;
        Long memberId = 0L;
        Long loginMemberId = 0L;

        try {
            loginMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (loginMemberId == null || loginMemberId <= 0) {
            return error("请先登录");
        }

        if (StringUtils.isNotBlank(member_id)) {
            memberId = Convert.toLong(member_id);
        } else {
            memberId = loginMemberId;
        }
        if (memberId < 1) {
            return AjaxResult.error(MEMBER_UNAVAILABLE);
        }
        isMy = memberId.equals(loginMemberId);
        if (!isMy) {
            PublicMemberVO publicMember = publicMemberService.selectPublicMemberById(memberId, loginMemberId);
            return publicMember == null ? AjaxResult.error(MEMBER_UNAVAILABLE) : success(publicMember);
        }

        UmsMember umsMember = umsMemberService.selectUmsMemberById(memberId);
        if (umsMember == null) {
            return AjaxResult.error("数据获取失败");
        } else {
            umsMember.setPassword("");//密码不要返回给前端
            // 2026-09-16 截图冒烟安全核查:本人拿整条记录是设计(资料页/收益页读 balance、phone),
            // 但外部账号标识与登录元数据客户端一处都不读(openid 走登录接口,落库那侧显式写空),
            // 与匿名公开面禁止列表(password/phone/wxOpenId)同口径 —— 一律不下发。
            umsMember.setWxOpenId(null);
            umsMember.setAppOpenId(null);
            umsMember.setUnionId(null);
            umsMember.setAppleId(null);
            umsMember.setLoginIp(null);
            if (isMy) {
                if (!Long.valueOf(1L).equals(umsMember.getStatus()) || !Integer.valueOf(0).equals(umsMember.getDelFlag())) {
                    return error("用户不可用");
                }
                // 资料只用于展示，不改变持久角色或兼容身份投影。
                if ("merchant".equals(umsMember.getRole())) {
                    umsMember.setMerchant(mmsMerchantService.selectMmsMerchantByMemberId(memberId));
                }
                umsMember.setUserType("merchant".equals(umsMember.getRole()) ? 2L : 1L);
            }

            int followNum = umsMemberFollowService.selectUmsMemberFollowNumByMemberId(memberId);
            int fanNum = umsMemberFollowService.selectUmsMemberFansNumByMemberId(memberId);
            umsMember.setFollowNum(followNum);
            umsMember.setFansNum(fanNum);


            UmsMemberFollow umsMemberFollow = new UmsMemberFollow();
            umsMemberFollow.setMemberId(loginMemberId);
            umsMemberFollow.setFollowMemberId(memberId);
            List<UmsMemberFollow> umsMemberFollowList = umsMemberFollowService.selectUmsMemberFollowList(umsMemberFollow);
            if (umsMemberFollowList.size() > 0) {
                umsMember.setIsFollow(1);
            } else {
                umsMember.setIsFollow(0);
            }

            int topicNum = cmsTopicService.selectCmsTopicNumByMemberId(memberId);
            int activityNum = cmsActivityService.selectCmsActivityNumByMemberId(memberId);
            umsMember.setTopicNum(topicNum);
            umsMember.setActivityNum(activityNum);
            umsMember.setLikeNum(0);


            int activityLikeNUm = cmsActivityLikeService.selectUmsMemberLikeNumByMemberId(memberId);
            int topicLikeNUm = cmsTopicLikeService.selectUmsMemberLikeNumByMemberId(memberId);
            int squareLikeNUm = umsCreativeSquareLikeService.selectUmsMemberLikeNumByMemberId(memberId);
            umsMember.setLikeNum(activityLikeNUm + topicLikeNUm + squareLikeNUm);

            //查询主题的分类信息
            String categoryIds = umsMember.getTagIds();
            if (!StringUtils.isEmpty(categoryIds)) {
                String[] ids = categoryIds.split(",");
                List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                if (listCategory.size() > 0) {
                    umsMember.setSysCategoryList(listCategory);
                }
            }

            umsMember.setJoinUserNum(0);
            Map<String, Object> params = new HashMap<>();
            params.put("memberId", memberId);
            Integer joinNum = umsMemberBalanceDetailService.selectJoinNum(params);
            if (joinNum != null) {
                umsMember.setJoinUserNum(joinNum);
            }
            // 本人分支只下发逐字段白名单投影(UmsMember 整实体含 password 兜底清空、
            // unionId/appOpenId/appleId/loginIp 等账号标识;见 SelfMemberVO 类注释)。
            SelfMemberVO selfVo = SelfMemberVO.from(umsMember);
            selfVo.setFriendNum(umsMemberFollowService.selectMutualFollowCount(memberId));
            // RUN-13(拍板 2026-09-19 第4条)等级单一真源 = player_growth:本人视角的 levelId 也不再用
            // 旧 ums_member.level_id(前端资料页徽章图/Lv. 文案跟这个字段)。无行兜底 Lv.1 与 getGrowth 同口径。
            if (playerProgressionService != null) {
                Integer lv = playerProgressionService.levelsByMemberIds(
                        java.util.Collections.singletonList(memberId)).get(memberId);
                selfVo.setLevelId((long) (lv == null ? 1 : lv));
            }
            return success(selfVo);
        }
    }

    @Operation(summary = "更新个人信息")
    @Log(title = "更新个人信息", businessType = BusinessType.UPDATE)
    @PostMapping("/update")
    public AjaxResult updateUserInfo(@RequestBody UmsMemberUpdateDTO request) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }

        // 一个可更新字段都没有(字段名传错)照旧拒,别 200 装成功
        if (request.getName() == null && request.getAvatar() == null && request.getWechat() == null
                && request.getCasePics() == null && request.getIntroduction() == null
                && request.getCaseIntroducton() == null && request.getWebsite() == null
                && request.getWorkAddress() == null && request.getTagIds() == null) {
            return new AjaxResult(com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST, "没有可更新的资料");
        }
        // T8:部分更新 —— 没传 name(如首页城市回写只带 workAddress)就不校验、不修改昵称;
        // 传了(含空串)照旧校验,App 现有调用行为不变。其余字段 mapper 本就按 null 跳过。
        NicknameCheckVO nicknameCheck = request.getName() == null ? null
                : nicknameSuggestionService.check(request.getName(), member_id);
        if (nicknameCheck != null && !nicknameCheck.isAvailable()) {
            AjaxResult conflict = new AjaxResult(
                    NicknameSuggestionServiceImpl.ERROR_NICKNAME_TAKEN.equals(nicknameCheck.getErrorCode())
                            ? com.chengyinhub.common.constant.HttpStatus.CONFLICT
                            : com.chengyinhub.common.constant.HttpStatus.BAD_REQUEST,
                    NicknameSuggestionServiceImpl.ERROR_NICKNAME_TAKEN.equals(nicknameCheck.getErrorCode())
                            ? "昵称已被占用，请选择建议昵称" : "昵称不符合规则");
            conflict.put("errorCode", nicknameCheck.getErrorCode());
            return conflict;
        }

        // 内容安全:昵称/简介/网址等用户自填文本命中违规即拒(资料随昵称+头像在全站评论/帖子公开展示)
        UmsMember cur = umsMemberService.selectUmsMemberById(member_id);
        String openid = cur != null ? cur.getWxOpenId() : null;
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(request.getName(), request.getIntroduction(),
                        request.getCaseIntroducton(), request.getWechat(), request.getWebsite(), request.getWorkAddress()),
                openid);
        if (secMsg != null) return error(secMsg);

        UmsMember umsMember = new UmsMember();
        umsMember.setId(member_id);
        umsMember.setName(request.getName());
        umsMember.setNickname(request.getName());
        umsMember.setAvatar(reviewedMedia(member_id, request.getAvatar(), false));
        umsMember.setWechat(request.getWechat());
        umsMember.setCasePics(reviewedMedia(member_id, request.getCasePics(), true));
        umsMember.setIntroduction(request.getIntroduction());
        umsMember.setCaseIntroducton(request.getCaseIntroducton());
        umsMember.setWebsite(request.getWebsite());
        umsMember.setWorkAddress(request.getWorkAddress());
        umsMember.setTagIds(request.getTagIds());

        int rows;
        try {
            rows = umsMemberService.updateUmsMember(umsMember);
        } catch (DuplicateKeyException e) {
            AjaxResult conflict = new AjaxResult(com.chengyinhub.common.constant.HttpStatus.CONFLICT,
                    "昵称刚被其他用户占用，请重新选择");
            conflict.put("errorCode", NicknameSuggestionServiceImpl.ERROR_NICKNAME_TAKEN);
            return conflict;
        }
        // 头像/案例图异步内容安全送检,违规经回调自动清除(BIZ_TYPE_MEMBER_PROFILE)
        String profileImgs = (StringUtils.isNotEmpty(request.getAvatar()) ? request.getAvatar() : "")
                + ";" + (StringUtils.isNotEmpty(request.getCasePics()) ? request.getCasePics() : "");
        if (profileImgs.length() > 1) {
            try { wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_MEMBER_PROFILE, member_id, profileImgs, openid); } catch (Exception ignore) {}
        }
        if (rows > 0) {
            return success("更新成功");
        } else {
            return error("更新失败");
        }
    }

    private Long optionalMemberId() {
        try {
            Long memberId = getAppUserId();
            return memberId != null && memberId > 0 ? memberId : null;
        } catch (Exception e) {
            return null;
        }
    }


    @Operation(summary = "设置邀请人")
    @Log(title = "设置邀请人", businessType = BusinessType.UPDATE)
    @PostMapping("/setInviter")
    public AjaxResult setInviter(String inviter_id) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }

        return memberInvitationService.bindInviter(member_id, inviter_id)
                ? success("设置成功")
                : error(INVITATION_FAILED);
    }


    @Operation(summary = "用户收件地址列表")
    @PostMapping("/address/list")
    public AjaxResult getUserAddressList() {
        startPage();
        List<UmsMemberAddress> list = umsMemberAddressService.selectOwnedAddressList(getAppUserId());
        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }

    @Operation(summary = "获取地址详情")
    @PostMapping("/address/info")
    @Parameters({
            @Parameter(name = "id", description = "地址ID")
    })
    public AjaxResult getUserAddressInfo(String id) {
        Long addressId = Convert.toLong(id, 0L);
        UmsMemberAddress address = umsMemberAddressService.selectOwnedAddressById(addressId, getAppUserId());
        return address == null ? AjaxResult.error(ADDRESS_UNAVAILABLE) : success(address);
    }

    @Operation(summary = "删除用户收件地址")
    @PostMapping("/address/delete")
    @Parameters({
            @Parameter(name = "id", description = "地址ID")
    })
    public AjaxResult deleteUserAddress(String id) {
        Long addressId = Convert.toLong(id, 0L);
        int rows = umsMemberAddressService.deleteOwnedAddress(addressId, getAppUserId());
        return rows == 1 ? success() : error(ADDRESS_UNAVAILABLE);
    }

    @Operation(summary = "添加/编辑用户地址")
    @PostMapping("/address/action")
    @Parameters({
            @Parameter(name = "id", description = "地址ID，填写了ID是编辑，不填写是新增"),
            @Parameter(name = "fullName", description = "姓名"),
            @Parameter(name = "mobilePhone", description = "手机号"),
            @Parameter(name = "province", description = "省市区"),
            @Parameter(name = "detailAddress", description = "详细地址"),
            @Parameter(name = "isDefault", description = "是否默认 0不是  1是")
    })
    public AjaxResult userAddressActoin(String id, String fullName, String mobilePhone, String province,
                                        String detailAddress, String isDefault, String requestId) {
        String normalizedName = fullName == null ? "" : fullName.trim();
        String normalizedMobile = mobilePhone == null ? "" : mobilePhone.trim();
        if (StringUtils.isBlank(normalizedName)) {
            return error("请输入姓名");
        }
        if (!normalizedMobile.matches("^1[3-9]\\d{9}$")) {
            return error("请输入正确的手机号");
        }
        UmsMemberAddress address = new UmsMemberAddress();
        Long memberId = getAppUserId();
        if (Convert.toLong(id, 0L) > 0) {
            //编辑
            address.setId(Convert.toLong(id));
        } else {
            //添加
            address.setId(null);
        }
        address.setFullName(normalizedName);
//        if (StringUtils.isNotEmpty(area_code)) {
            address.setAreaCode("+86");
//        }
        address.setMobilePhone(normalizedMobile);
        if (StringUtils.isNotEmpty(province)) {
            address.setProvince(province.trim());
        }
        if (StringUtils.isNotEmpty(detailAddress)) {
            address.setDetailAddress(detailAddress.trim());
        }
        if (StringUtils.isNotEmpty(isDefault)) {
            //是否默认 0不是  1是
            address.setIsDefault(Convert.toInt(isDefault, 0));
        } else {
            address.setIsDefault(0);
        }

        if (address.getId() != null && address.getId() > 0) {
            //编辑
            int rows = umsMemberAddressService.updateOwnedAddress(address, memberId);
            return rows == 1 ? success() : error(ADDRESS_UNAVAILABLE);
        } else {
            //添加
            UmsMemberAddress saved = umsMemberAddressService.insertOwnedAddressIdempotent(address, memberId, requestId);
            return saved != null ? success(saved) : error("请求标识无效，请重新提交");
        }
    }

    // 保留源码级兼容，旧单测/内部调用不经过 Spring 参数绑定时仍可调用。
    public AjaxResult userAddressActoin(String id, String fullName, String mobilePhone, String province,
                                        String detailAddress, String isDefault) {
        return userAddressActoin(id, fullName, mobilePhone, province, detailAddress, isDefault, null);
    }

    @Operation(summary = "设置默认收件地址")
    @PostMapping("/address/setDefault")
    @Parameters({
            @Parameter(name = "id", description = "地址ID")
    })
    public AjaxResult setDefaultUserAddress(String id) {
        Long addressId = Convert.toLong(id, 0L);
        int rows = umsMemberAddressService.setOwnedAddressDefault(addressId, getAppUserId());
        return rows == 1 ? success() : error(ADDRESS_UNAVAILABLE);
    }

    /** 源码级兼容:旧调用者不带期望状态时,保持既有 toggle 语义(HTTP 旧调用者由 follow=null 进入同一分支)。 */
    public AjaxResult userFollowActoin(String follow_member_id) {
        return userFollowActoin(follow_member_id, null);
    }

    /**
     * R10-07:关注必须带「期望状态」才可重试幂等。
     * follow=1 确保关注、follow=0 确保取消;已是目标态则成功空操作,绝不反向。
     * follow 缺省(null/空白)= 旧调用者,保持既有 toggle。权限仍只认当前登录会员。
     */
    @Operation(summary = "关注/取消关注")
    @PostMapping("/follow/action")
    @Parameters({
            @Parameter(name = "follow_member_id", description = "被关注用户id"),
            @Parameter(name = "follow", description = "期望状态:1关注 0取消;缺省保持旧toggle")
    })
    public AjaxResult userFollowActoin(String follow_member_id, String follow) {
        Long member_id = null;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            // fail-closed:认证栈异常不得当成匿名 toggle 别人的关系,留痕后按未登录处理。
            logger.warn("关注操作获取登录会员失败", e);
        }
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        Long targetId = Convert.toLong(follow_member_id, 0L);
        if (targetId <= 0) {
            return error("用户不存在");
        }
        // 只认显式缺省(null)= 旧调用者 toggle;显式传了非 1/0(含空串)= 非法客户端,不猜意图。
        String desired = follow == null ? null : follow.trim();
        if (desired != null && !"1".equals(desired) && !"0".equals(desired)) {
            return error("关注状态无效");
        }
        // 行锁 + 查重 + 写在 Service 同一事务内完成:并发首次关注不重复,丢响应重试不反转。
        Boolean desiredState = desired == null ? null : Boolean.valueOf("1".equals(desired));
        IUmsMemberFollowService.ApplyResult result =
                umsMemberFollowService.applyFollowState(member_id, targetId, desiredState);
        switch (result) {
            case FOLLOWED:
                // #1057 帖文平台:真发生新关注才通知被关注者(ALREADY_FOLLOWED 静默重试不重复打扰)。
                communityPostService.notifyFollow(targetId, member_id);
                return success("关注成功");
            case ALREADY_FOLLOWED:
                return success("关注成功");
            case UNFOLLOWED:
            case ALREADY_UNFOLLOWED:
                return success("取消关注成功");
            case SELF_FOLLOW:
                return error("不能关注自己");
            case INVALID_MEMBER:
                return error("用户不存在");
            default:
                return error("操作失败，请重试");
        }
    }
}
