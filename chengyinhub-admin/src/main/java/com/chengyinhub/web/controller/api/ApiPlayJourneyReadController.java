package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayJourneyReadService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅程案件册 / 背包只读接口(R8-CASEBOOK)。
 *
 * <p>登录会员只读自己的局:一局的权威事实只有本局 {@code topic_route_session.state_json}
 * 与 {@code topic_route_action} 流水,奖励领取状态复用既有进度 / 券 / 名次奖牌记录。
 * 本 controller 不写任何表。
 */
@RestController
@Tag(name = "旅程案件册")
@RequestMapping("/api/play/journey")
public class ApiPlayJourneyReadController extends BaseController {

    @Autowired(required = false)
    private IPlayJourneyReadService playJourneyReadService;

    @Operation(summary = "案件册(事实 / 关系 / 代价 / 日志 / 可选发现进度)")
    @GetMapping("/casebook")
    public AjaxResult casebook(String topicId, Long activityId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (playJourneyReadService == null) return error("参数有误");
        try {
            return success(playJourneyReadService.casebook(memberId, Convert.toLong(topicId, 0L), activityId));
        } catch (ServiceException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "背包(本主题各节点的奖励与领取状态)")
    @GetMapping("/backpack")
    public AjaxResult backpack(String topicId, Long activityId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        if (playJourneyReadService == null) return error("参数有误");
        try {
            return success(playJourneyReadService.backpack(memberId, Convert.toLong(topicId, 0L), activityId));
        } catch (ServiceException | IllegalArgumentException e) {
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
