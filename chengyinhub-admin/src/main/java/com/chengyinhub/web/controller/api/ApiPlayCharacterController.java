package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.ThemeCharacterService;
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
 * 动态角色卡只读接口(F2 RPG P0 / T02)。
 *
 * <p>登录会员只读自己的角色卡;属性值的权威来源是服务端局状态与跨局台账,本 controller 不写任何表,
 * 也不提供客户端改属性的入口。还没建过角色时返回模板(各属性初值)。
 */
@RestController
@Tag(name = "动态角色卡")
@RequestMapping("/api/play")
public class ApiPlayCharacterController extends BaseController {

    @Autowired
    private ThemeCharacterService themeCharacterService;

    @Operation(summary = "本人在某主题下的角色卡(未建角色返回模板)")
    @GetMapping("/character")
    public AjaxResult character(String topicId) {
        Long memberId = currentMemberId();
        if (memberId == null) {
            AjaxResult result = new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
            result.put("errorCode", "CHARACTER_LOGIN_REQUIRED");
            return result;
        }
        try {
            return success(themeCharacterService.card(memberId, Convert.toLong(topicId, 0L)));
        } catch (ServiceException e) {
            AjaxResult result = new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
            if (e.getDetailMessage() != null) result.put("errorCode", e.getDetailMessage());
            return result;
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
