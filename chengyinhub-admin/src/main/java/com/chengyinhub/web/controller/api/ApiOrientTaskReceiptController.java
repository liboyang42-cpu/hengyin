package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientTaskService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 玩家原替代任务回执;独立于地理/队伍/安全Controller,不产生写副作用。 */
@RestController
@RequestMapping("/api/orient/task/alternative")
public class ApiOrientTaskReceiptController extends BaseController {
    @Autowired private IOrientTaskService taskService;

    @GetMapping("/receipt")
    public AjaxResult receipt(Long topicId, Long activityId, String playInstanceId,
                              Long taskId, String idempotencyKey) {
        Long memberId;
        try { memberId = getAppUserId(); }
        catch (Exception unauthenticated) { return AjaxResult.error(401, "请先登录"); }
        try {
            return AjaxResult.success(OrientPlayerWire.response(taskService.receipt(memberId, topicId,
                    activityId, playInstanceId, taskId, idempotencyKey)));
        } catch (OrientRejection rejected) {
            return AjaxResult.error(rejected.getMessage()).put("errorCode", rejected.getErrorCode());
        }
    }
}
