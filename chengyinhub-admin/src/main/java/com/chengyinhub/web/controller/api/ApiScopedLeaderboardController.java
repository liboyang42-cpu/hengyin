package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ScopedLeaderboardQueryDTO;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.ScopedLeaderboardApplication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * BE-14 榜单多作用域只读投影。
 *
 * <p>{@code GET /api/rank/list?scope=world|country|city|quest|club&scopeId=&period=week|all&limit=}
 * 返回 {@code {scope,scopeId,period,periodStart,periodEnd,scoreBasis,entries,me,coverage}}；
 * 只读、写侧仍是完成事实与回执。俱乐部作用域在服务层校验请求者权限，榜单只含该俱乐部
 * 授权成员的对局；响应不含手机号、邮箱、真实姓名或精确位置。
 */
@RestController
@Tag(name = "多作用域榜单")
@RequestMapping("/api/rank")
public class ApiScopedLeaderboardController extends BaseController {

    @Autowired
    private ScopedLeaderboardApplication scopedLeaderboardApplication;

    @Operation(summary = "榜单多作用域只读投影(世界/国家/城市/单关/俱乐部 × 周/累计)")
    @GetMapping("/list")
    public AjaxResult list(ScopedLeaderboardQueryDTO query) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception notLoggedIn) {
            return error("请先登录");
        }
        return scopedLeaderboardApplication.list(memberId, query);
    }
}
