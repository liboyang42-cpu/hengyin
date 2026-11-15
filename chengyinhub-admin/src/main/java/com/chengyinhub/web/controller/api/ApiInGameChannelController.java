package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.impl.InGameChannelService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-game IM channel resolution & unread summary API.
 */
@RestController
@Tag(name = "游戏内频道")
@RequestMapping("/api/im/ingame")
public class ApiInGameChannelController extends BaseController
{
    @Autowired
    private InGameChannelService inGameChannelService;

    @Operation(summary = "解析或加入游戏内频道")
    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/resolve")
    public AjaxResult resolve(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long activity_id,
            @RequestParam(required = false) Long topic_id,
            @RequestParam(required = false) Long team_id,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String city_team_id,
            @RequestParam(required = false) String board_id)
    {
        try
        {
            return AjaxResult.success(inGameChannelService.resolveChannel(
                    getAppUserId(), scope, activity_id, topic_id, team_id, district, city_team_id, board_id));
        }
        catch (ServiceException e)
        {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
        catch (Exception e)
        {
            logger.error("游戏内频道解析异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "获取游戏内频道未读摘要")
    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 60, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/summary")
    public AjaxResult summary(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long activity_id,
            @RequestParam(required = false) Long topic_id,
            @RequestParam(required = false) Long team_id,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String city_team_id,
            @RequestParam(required = false) String board_id)
    {
        try
        {
            return AjaxResult.success(inGameChannelService.getSummary(
                    getAppUserId(), scope, activity_id, topic_id, team_id, district, city_team_id, board_id));
        }
        catch (ServiceException e)
        {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
        catch (Exception e)
        {
            logger.error("游戏内频道摘要异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }
}
