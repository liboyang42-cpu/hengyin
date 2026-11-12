package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuoteRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.service.IMarketingExtraSeatService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 加座（route=3）：为同一位买家之外的第二位玩家单独买一份准入。
 *
 * <p>只有报价与我的加座两个读口；成交仍走 {@code POST /api/marketing/product/order}
 * （{@code fulfillmentKind=3}），由商品域唯一的积分/微信成交路径扣款、grant 并签发礼品来源。
 * 本控制器不收款、不建单、不发放权益，避免第二条资金路径。
 *
 * <p>会员身份只来自 {@link #getAppMemberId()}：客户端不能传 memberId / 父行 / 席位号，
 * 因此不存在"填别人订单号把票加到别人队里"的入口。
 */
@Tag(name = "营销加座")
@RestController
@RequestMapping("/api/marketing/extra-seat")
public class ApiMarketingExtraSeatController extends BaseController {

    private final IMarketingExtraSeatService extraSeatService;

    public ApiMarketingExtraSeatController(IMarketingExtraSeatService extraSeatService) {
        this.extraSeatService = extraSeatService;
    }

    @Operation(summary = "加座报价：服务端价 + 签名 + 原单容量与 partyBinding 事实")
    @PostMapping("/quote")
    public AjaxResult quote(@RequestBody(required = false) MarketingExtraSeatQuoteRequest request) {
        return success(extraSeatService.quote(requireAppMemberId(), request));
    }

    @Operation(summary = "我买过的加座（含待支付与已退款，带礼品领取状态）")
    @GetMapping("/orders")
    public AjaxResult myExtraSeats(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(extraSeatService.myExtraSeats(requireAppMemberId(), limit == null ? 20 : limit));
    }

    @ExceptionHandler(MarketingProductException.class)
    public AjaxResult handleExtraSeatRejection(MarketingProductException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        if (rejection.getData() != null) result.putAll(rejection.getData());
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
