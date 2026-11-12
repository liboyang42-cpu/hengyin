package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ChannelReceiptRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemRequest;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.IChannelRedemptionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 玩家渠道码兑换与回执。身份只取真实登录态；
 * 未登录抛 401，绝不降级成 0 号或他人身份。
 */
@RestController
@RequestMapping("/api/channel")
public class ApiChannelRedemptionController extends BaseController {

    private final IChannelRedemptionService redemptionService;

    public ApiChannelRedemptionController(IChannelRedemptionService redemptionService) {
        this.redemptionService = redemptionService;
    }

    @PostMapping("/redeem")
    public AjaxResult redeem(@RequestBody(required = false) ChannelRedeemRequest request) {
        return success(redemptionService.redeem(requireAppMemberId(), request));
    }

    @PostMapping("/receipt")
    public AjaxResult receipt(@RequestBody(required = false) ChannelReceiptRequest request) {
        return success(redemptionService.receipt(requireAppMemberId(), request));
    }

    @ExceptionHandler(ChannelVoucherException.class)
    public AjaxResult handleRejection(ChannelVoucherException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
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
