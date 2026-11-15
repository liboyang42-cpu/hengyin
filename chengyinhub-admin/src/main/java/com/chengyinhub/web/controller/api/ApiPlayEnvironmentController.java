package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.rpg.TopicWeatherEventService;
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
 * 玩家端环境条件与天气触发事件只读接口。
 *
 * <p>基于主题城市（实时天气 + 24 小时城市缓存 + 时段/周内/季节）计算当前环境快照，
 * 并评估 {@code weather_event_rule} 门控点位与彩蛋提示；天气源不可用时优雅降级为 unknown。
 */
@RestController
@Tag(name = "环境与天气事件")
@RequestMapping("/api/play")
public class ApiPlayEnvironmentController extends BaseController {

    @Autowired(required = false)
    private ICmsTopicService cmsTopicService;

    @Autowired(required = false)
    private TopicWeatherEventService topicWeatherEventService;

    @Operation(summary = "读取主题当前环境快照、降级状态及天气事件触发结果")
    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 60, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/environment")
    public AjaxResult environment(String topicId) {
        Long memberId = currentMemberId();
        if (memberId == null) {
            return coded(HttpStatus.UNAUTHORIZED, "请先登录", "ENV_LOGIN_REQUIRED");
        }
        Long tid = Convert.toLong(topicId, 0L);
        if (tid == null || tid <= 0L) {
            return coded(HttpStatus.BAD_REQUEST, "缺少有效的主题编号", "ENV_BAD_REQUEST");
        }
        if (cmsTopicService == null || topicWeatherEventService == null) {
            return coded(HttpStatus.ERROR, "环境服务暂不可用", "ENV_UNAVAILABLE");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(tid);
        if (topic == null) {
            return coded(HttpStatus.NOT_FOUND, "主题不存在", "ENV_TOPIC_NOT_FOUND");
        }
        try {
            return success(topicWeatherEventService.playerEnvironmentView(topic));
        } catch (ServiceException e) {
            return coded(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage(),
                    e.getDetailMessage() == null ? "ENV_REJECTED" : e.getDetailMessage());
        } catch (IllegalArgumentException e) {
            return coded(HttpStatus.BAD_REQUEST, e.getMessage(), "ENV_BAD_REQUEST");
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
