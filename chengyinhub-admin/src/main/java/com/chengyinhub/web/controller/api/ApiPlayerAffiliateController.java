package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.service.IPlayerAffiliateService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 玩家本人推广收入只读汇总。
 *
 * <p>身份只来自登录 principal；不接受客户端 memberId。没有提现、应计、退款入口，
 * 也没有"把分配标成已打款"的路径 —— 那些由服务端订单/退款事实驱动。
 * 提现能力在响应里明确为不可用，不返回假的 availableToWithdraw。
 */
@Tag(name = "玩家推广收入")
@RestController
@RequestMapping("/api/marketing/affiliate")
public class ApiPlayerAffiliateController extends BaseController {

    private final IPlayerAffiliateService affiliateService;

    public ApiPlayerAffiliateController(IPlayerAffiliateService affiliateService) {
        this.affiliateService = affiliateService;
    }

    @Operation(summary = "我的推广凭据、成交指标与收入桶(只读)")
    @GetMapping("/summary")
    public AjaxResult summary(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(affiliateService.summary(requireAppMemberId(), limit));
    }

    @ExceptionHandler(MarketingRevenueException.class)
    public AjaxResult handleAffiliateRejection(MarketingRevenueException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    /** 未登录如实 401，不降级成假身份读别人的推广收入。 */
    private long requireAppMemberId() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (ServiceException unauthorized) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}
