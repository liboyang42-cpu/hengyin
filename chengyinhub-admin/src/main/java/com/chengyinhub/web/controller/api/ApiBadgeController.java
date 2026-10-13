package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.IBadgeReadModelService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 勋章墙引擎读模型接口。
 */
@RestController
@Tag(name = "勋章墙")
@RequestMapping("/api/badge")
public class ApiBadgeController extends BaseController {

    @Autowired
    private IBadgeReadModelService badgeReadModelService;

    @Operation(summary = "勋章墙 v2")
    @PostMapping("/wall-v2")
    public AjaxResult wallV2() {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        return AjaxResult.success(badgeReadModelService.buildWallV2(memberId));
    }
}
