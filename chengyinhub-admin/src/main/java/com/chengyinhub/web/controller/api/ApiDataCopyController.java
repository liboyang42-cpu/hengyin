package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IMemberDataCopyService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ONB-10 (PIPL Art. 45): the logged-in member asks for a copy of their personal information.
 * Both routes act only on the current member; /api/** needs a login by default (SecurityConfig anyRequest).
 * Reply data: {requestId, status, createdAt "yyyy-MM-dd HH:mm:ss", dueBy "yyyy-MM-dd"}.
 */
@RestController
@Tag(name = "个人信息副本")
@RequestMapping("/api/user/data-copy")
public class ApiDataCopyController extends BaseController {

    @Autowired
    private IMemberDataCopyService dataCopyService;

    @Operation(summary = "申请个人信息副本(已有处理中的申请时直接返回该申请)")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    @PostMapping("/request")
    public AjaxResult request() {
        Long memberId = currentMemberId();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(dataCopyService.request(memberId));
    }

    @Operation(summary = "最近一次个人信息副本申请(没有时 data 为 null)")
    @GetMapping("/latest")
    public AjaxResult latest() {
        Long memberId = currentMemberId();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        AjaxResult result = AjaxResult.success();
        result.put(AjaxResult.DATA_TAG, dataCopyService.latest(memberId));
        return result;
    }

    private Long currentMemberId() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
