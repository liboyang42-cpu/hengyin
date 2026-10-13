package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.CreatorProfileApplyDTO;
import com.chengyinhub.business.service.ICreatorCenterService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 创作者中心接口。
 */
@RestController
@Tag(name = "P3创作者中心")
@RequestMapping("/api/creator")
public class ApiCreatorCenterController extends BaseController {

    @Autowired
    private ICreatorCenterService creatorCenterService;

    @Operation(summary = "创作者中心")
    @PostMapping("/center")
    public AjaxResult center() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        return success(creatorCenterService.getCenter(memberId));
    }

    @Operation(summary = "申请成为创作者")
    @PostMapping("/apply")
    public AjaxResult apply(CreatorProfileApplyDTO request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        return success(creatorCenterService.apply(memberId, request));
    }
}
