package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
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

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
