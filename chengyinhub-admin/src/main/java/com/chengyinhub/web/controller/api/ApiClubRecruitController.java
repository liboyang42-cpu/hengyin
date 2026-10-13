package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IClubChapterRecruitService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "俱乐部招商台")
@RequestMapping("/api/club/recruit")
public class ApiClubRecruitController extends BaseController {
    @Autowired private IClubChapterRecruitService clubChapterRecruitService;

    @Operation(summary = "招商总览:章节招募条件与待审数、节点承接态(club:activity:read)")
    @PostMapping("/overview")
    public AjaxResult overview(@RequestBody(required = false) Map<String, Object> body) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (body == null) return error("缺少俱乐部或主题参数");
        try {
            return success(clubChapterRecruitService.overview(principal,
                    asLong(body.get("clubId")), asLong(body.get("topicId"))));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private static Long asLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).longValue();
        try { return Long.valueOf(String.valueOf(raw).trim()); } catch (NumberFormatException e) { return null; }
    }


    /** 解析不出来就当没传(字段保持不变),而不是当 0 —— 把权益门槛静默清零是资损方向。 */


    /** 只认真正的布尔;别的形状一律 null,由服务层拒绝而不是当成 false 把招募关掉。 */

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
