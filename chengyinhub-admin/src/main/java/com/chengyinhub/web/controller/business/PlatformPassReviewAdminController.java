package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.business.platformpass.PlatformPassReversalReviewService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * T5:App Store 撤回退款(REFUND_REVERSED)后待复核的通行证订单。列表只读;重新开通 / 关闭都要备注,
 * 每次处理写一条平台审计。权限点 business:passReview:list / business:passReview:resolve 迁移不授予任何角色。
 */
@RestController
@RequestMapping("/business/passReview")
public class PlatformPassReviewAdminController extends BaseController {

    private final PlatformPassReversalReviewService service;

    public PlatformPassReviewAdminController(PlatformPassReversalReviewService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('business:passReview:list')")
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false) String state,
                             @RequestParam(required = false) Integer limit) {
        return success(service.list(state, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:passReview:resolve')")
    @Log(title = "通行证撤回退款复核:重新开通", businessType = BusinessType.UPDATE)
    @PostMapping("/orders/{orderNo}/regrant")
    public AjaxResult regrant(@PathVariable String orderNo, @RequestBody Map<String, Object> body) {
        return success(service.regrant(getUserId(), orderNo, note(body)));
    }

    @PreAuthorize("@ss.hasPermi('business:passReview:resolve')")
    @Log(title = "通行证撤回退款复核:关闭", businessType = BusinessType.UPDATE)
    @PostMapping("/orders/{orderNo}/close")
    public AjaxResult close(@PathVariable String orderNo, @RequestBody Map<String, Object> body) {
        return success(service.close(getUserId(), orderNo, note(body)));
    }

    @ExceptionHandler(PlatformPassException.class)
    public AjaxResult handleRejection(PlatformPassException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    private static String note(Map<String, Object> body) {
        Object v = body == null ? null : body.get("note");
        return v == null ? null : v.toString();
    }
}
