package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 玩法 Beat(Encounter)只读解析:这一拍演什么。
 *
 * <p>只读接口,不写任何表;答案类字段(题目答案、正确选项、answerReveal)绝不出现在响应里。
 * 与 {@code ApiPlayProgressController} 分开,避免两条产品线互相踩。
 */
@RestController
@RequestMapping("/api/play")
public class ApiPlayEncounterController extends BaseController {

    @Autowired
    private IPlayEncounterService playEncounterService;

    @Operation(summary = "玩法 Beat 只读解析")
    @GetMapping("/encounter")
    public AjaxResult encounter(Long topicId, Long activityId, Long nodeId) {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (topicId == null || topicId <= 0L || nodeId == null || nodeId <= 0L) return error("参数有误");
        try {
            return success(playEncounterService.resolve(memberId, topicId, activityId, nodeId));
        } catch (ServiceException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }
}
