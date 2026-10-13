package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IRecommendationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 推荐接口。
 */
@RestController
@Tag(name = "P3推荐")
@RequestMapping("/api/recommendation")
public class ApiRecommendationController extends BaseController {

    @Autowired
    private IRecommendationService recommendationService;

    @Operation(summary = "推荐列表")
    @PostMapping("/list")
    public AjaxResult list(String candidate_type, Integer limit) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        String candidateType = StringUtils.isBlank(candidate_type) ? "topic" : candidate_type;
        int size = limit == null ? 20 : Convert.toInt(limit, 20);
        return success(recommendationService.listForMember(memberId, candidateType, size));
    }
}
