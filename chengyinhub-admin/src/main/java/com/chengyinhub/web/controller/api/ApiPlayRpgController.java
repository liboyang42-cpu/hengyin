package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.constant.HttpStatus;
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
 * F6 RPG P1 状态栏只读接口。
 *
 * <p>只读登录会员本人的当前局(按会员 + 主题 + 活动定位,别人的局查不到);不写任何表,
 * 没有任何客户端改动量 / 进度钟 / 特质 / 环境的入口 —— 这些只由服务端结算写入。
 */
@RestController
@Tag(name = "RPG 状态栏")
@RequestMapping("/api/play/rpg")
public class ApiPlayRpgController extends BaseController {

    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;

    @Operation(summary = "本人本局的状态栏数据(hp / luck / 属性 / 动量 / 进度钟 / 特质 / 环境)")
    @GetMapping("/status")
    public AjaxResult status(String topicId, Long activityId) {
        Long memberId = currentMemberId();
        if (memberId == null) return coded(HttpStatus.UNAUTHORIZED, "请先登录", "RPG_LOGIN_REQUIRED");
        if (topicRouteRuntimeService == null) return coded(HttpStatus.ERROR, "状态栏不可用", "RPG_UNAVAILABLE");
        try {
            return success(topicRouteRuntimeService.rpgStatus(memberId, Convert.toLong(topicId, 0L), activityId));
        } catch (ServiceException e) {
            return coded(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage(),
                    e.getDetailMessage() == null ? "RPG_REJECTED" : e.getDetailMessage());
        } catch (IllegalArgumentException e) {
            return coded(HttpStatus.BAD_REQUEST, e.getMessage(), "RPG_BAD_REQUEST");
        } catch (IllegalStateException e) {
            return coded(HttpStatus.CONFLICT, e.getMessage(), "RPG_REJECTED");
        }
    }

    private static AjaxResult coded(int code, String message, String errorCode) {
        AjaxResult result = new AjaxResult(code, message);
        result.put("errorCode", errorCode);
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
