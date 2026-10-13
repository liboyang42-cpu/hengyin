package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.dto.CircleThemeInstanceConfigRequest;
import com.chengyinhub.business.domain.dto.CircleExploreActionRequest;
import com.chengyinhub.business.service.ICircleExploreService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICircleThemeSupplyService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 圈层主题的城市实例配置与玩家会话入口。 */
@RestController
@RequestMapping("/api/circle-theme")
public class ApiCircleThemeController extends BaseController {
    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICircleThemeSupplyService circleThemeSupplyService;

    @Autowired
    private ICircleExploreService circleExploreService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Operation(summary = "已购买或领取玩家读取圈层城市实例供给；有效商户不在3—4家时返回空列表")
    @GetMapping("/instance/{topicId}/offers")
    public AjaxResult offers(@PathVariable Long topicId) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            circleExploreService.assertEligible(topicId, memberId);
            return AjaxResult.success(circleThemeSupplyService.listPublicSupplies(topicId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "创建圈层探索会话；有效商户不在3—4家时拒绝")
    @PostMapping("/session")
    public AjaxResult createSession(@RequestBody CircleExploreActionRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            return AjaxResult.success(circleExploreService.create(
                    request == null ? null : request.getTopicId(), memberId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "通过熟人邀请码加入圈层探索；不提供陌生人匹配")
    @PostMapping("/session/join")
    public AjaxResult joinSession(@RequestBody CircleExploreActionRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            return AjaxResult.success(circleExploreService.join(
                    request == null ? null : request.getInviteCode(), memberId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "自助记录一家供给；不表示到店、预约、核销或成交")
    @PostMapping("/session/record")
    public AjaxResult record(@RequestBody CircleExploreActionRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            circleExploreService.record(request == null ? null : request.getSessionId(),
                    request == null ? null : request.getOfferId(),
                    request == null ? null : request.getNote(), memberId);
            return success("已记录");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "保存主题轻互动答案")
    @PostMapping("/session/answer")
    public AjaxResult answer(@RequestBody CircleExploreActionRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            circleExploreService.answer(request == null ? null : request.getSessionId(),
                    request == null ? null : request.getStage(),
                    request == null ? null : request.getValue(), memberId);
            return success("已保存");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "读取私人圈层记录卡")
    @GetMapping("/session/{sessionId}/card")
    public AjaxResult card(@PathVariable Long sessionId) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            return AjaxResult.success(circleExploreService.card(sessionId, memberId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            return null;
        }
    }

    @Operation(summary = "圈层城市实例选择3—4个供给角色")
    @PostMapping("/instance/configure")
    public AjaxResult configure(@RequestBody CircleThemeInstanceConfigRequest request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (request == null) return error("参数为空");
        try {
            Long ownerMemberId = resolveProjectOwner(memberId, request.getScope());
            int changed = cmsTopicService.configureCircleCandidates(
                    request.getTopicId(), ownerMemberId, request.getCandidateCodes());
            return AjaxResult.success("配置已保存", changed);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "城市实例内容主体复核当前3—4家有效供给")
    @PostMapping("/instance/review")
    public AjaxResult review(@RequestBody CircleExploreActionRequest request) {
        Long memberId = currentMemberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            Long ownerMemberId = resolveProjectOwner(memberId, request == null ? null : request.getScope());
            return AjaxResult.success("供给复核已更新",
                    cmsTopicService.reviewCircleSupplies(
                            request == null ? null : request.getTopicId(), ownerMemberId));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    private Long resolveProjectOwner(Long principalMemberId, String scope) {
        if (!MerchantOperationScope.isMerchant(scope)) return principalMemberId;
        return merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                .getOwnerMemberId();
    }
}
