package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅程检定:掷骰 / 花幸运重掷 / 结算 / 力竭休整。
 *
 * <p>activityId 区分活动局与自玩局；省略时仅兼容唯一局。
 * 所有动作都是服务端幂等:同参重放返回同一份回执,不重掷、不二次扣血/扣幸运。
 */
@RestController
@Tag(name = "旅程检定")
@RequestMapping("/api/play/check")
public class ApiPlayCheckController extends BaseController {

    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;

    @Operation(summary = "掷骰")
    @PostMapping("/roll")
    public AjaxResult roll(String topicId, Long activityId, String nodeId, String checkId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        try {
            return success(topicRouteRuntimeService.roll(memberId, Convert.toLong(topicId, 0L), activityId,
                    Convert.toLong(nodeId, 0L), checkId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "花幸运重掷")
    @PostMapping("/reroll")
    public AjaxResult reroll(String topicId, Long activityId, String nodeId, String checkId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        try {
            return success(topicRouteRuntimeService.reroll(memberId, Convert.toLong(topicId, 0L), activityId,
                    Convert.toLong(nodeId, 0L), checkId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "结算检定")
    @PostMapping("/settle")
    public AjaxResult settle(String topicId, Long activityId, String nodeId, String checkId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        try {
            return success(topicRouteRuntimeService.settle(memberId, Convert.toLong(topicId, 0L), activityId,
                    Convert.toLong(nodeId, 0L), checkId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "力竭休整")
    @PostMapping("/rest")
    public AjaxResult rest(String topicId, Long activityId, String nodeId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        try {
            return success(topicRouteRuntimeService.rest(memberId, Convert.toLong(topicId, 0L), activityId,
                    Convert.toLong(nodeId, 0L)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "接受魔鬼交易(掷骰前,代价换加值)")
    @PostMapping("/bargain")
    public AjaxResult bargain(String topicId, Long activityId, String nodeId, String checkId) {
        Long memberId = currentMemberId();
        if (memberId == null) return coded(HttpStatus.UNAUTHORIZED, "请先登录", "CHECK_LOGIN_REQUIRED");
        if (topicRouteRuntimeService == null) return coded(HttpStatus.ERROR, "参数有误", "CHECK_UNAVAILABLE");
        try {
            return success(topicRouteRuntimeService.bargain(memberId, Convert.toLong(topicId, 0L), activityId,
                    Convert.toLong(nodeId, 0L), checkId));
        } catch (ServiceException e) {
            return coded(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage(), e.getDetailMessage());
        } catch (IllegalArgumentException e) {
            return coded(HttpStatus.BAD_REQUEST, e.getMessage(), "CHECK_BAD_REQUEST");
        } catch (IllegalStateException e) {
            return coded(HttpStatus.CONFLICT, e.getMessage(), "CHECK_REJECTED");
        }
    }

    @Operation(summary = "花动量补足失败的检定(掷骰后、结算前)")
    @PostMapping("/momentum")
    public AjaxResult momentum(String topicId, Long activityId, String nodeId, String checkId) {
        Long memberId = currentMemberId();
        if (memberId == null) return coded(HttpStatus.UNAUTHORIZED, "请先登录", "CHECK_LOGIN_REQUIRED");
        if (topicRouteRuntimeService == null) return coded(HttpStatus.ERROR, "参数有误", "CHECK_UNAVAILABLE");
        try {
            return success(topicRouteRuntimeService.spendMomentum(memberId, Convert.toLong(topicId, 0L), activityId,
                    Convert.toLong(nodeId, 0L), checkId));
        } catch (ServiceException e) {
            return coded(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage(), e.getDetailMessage());
        } catch (IllegalArgumentException e) {
            return coded(HttpStatus.BAD_REQUEST, e.getMessage(), "CHECK_BAD_REQUEST");
        } catch (IllegalStateException e) {
            return coded(HttpStatus.CONFLICT, e.getMessage(), "CHECK_REJECTED");
        }
    }

    /** F6 新接口统一带 errorCode;老四个接口的返回形状不动。 */
    private static AjaxResult coded(int code, String message, String errorCode) {
        AjaxResult result = new AjaxResult(code, message);
        result.put("errorCode", errorCode == null ? "CHECK_REJECTED" : errorCode);
        return result;
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
