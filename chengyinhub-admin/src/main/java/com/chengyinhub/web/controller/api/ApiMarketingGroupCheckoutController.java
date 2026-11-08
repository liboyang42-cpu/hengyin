package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutCreateRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.service.IMarketingGroupCheckoutService;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团体/学校商品结算入口（C06 商品 → 既有报名 C07 链路）。
 *
 * <p>这不是第二套 checkout：报价与建单都委托 {@link IMarketingGroupCheckoutService}，
 * 最终仍落在既有 {@code cms_registration} 与微信支付事实。客户端只提交 productId 与人数，
 * 不提交 ownerType/ownerId/ticketId/金额；会员身份只来自登录态。
 */
@Tag(name = "团体/学校商品结算")
@RestController
@RequestMapping("/api/marketing/group-checkout")
public class ApiMarketingGroupCheckoutController extends BaseController {

    private final IMarketingGroupCheckoutService groupCheckoutService;

    public ApiMarketingGroupCheckoutController(IMarketingGroupCheckoutService groupCheckoutService) {
        this.groupCheckoutService = groupCheckoutService;
    }

    @Operation(summary = "团体/学校报价：解析商品绑定并复用原报名报价")
    @PostMapping("/quote")
    public AjaxResult quote(@RequestBody(required = false) MarketingGroupCheckoutQuoteRequest request) {
        return success(groupCheckoutService.quote(requireAppMemberId(), request));
    }

    @Operation(summary = "团体/学校建单：复用原报名建单与幂等重放")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody(required = false) MarketingGroupCheckoutCreateRequest request) {
        return success(groupCheckoutService.create(requireAppMemberId(), request));
    }

    @ExceptionHandler(MarketingProductException.class)
    public AjaxResult handleProductRejection(MarketingProductException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        if (rejection.getData() != null) result.putAll(rejection.getData());
        return result;
    }

    @ExceptionHandler(RegistrationCheckoutException.class)
    public AjaxResult handleCheckoutRejection(RegistrationCheckoutException rejection) {
        AjaxResult result = new AjaxResult(rejection.getCode(), rejection.getMessage());
        if (rejection.getErrorCode() != null) result.put("errorCode", rejection.getErrorCode());
        return result;
    }

    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}
