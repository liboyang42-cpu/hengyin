package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.IChannelOrderVerificationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 渠道入站回调（L2 端口边界）。
 *
 * <p>路径放在 {@code /api/common/**} 的既有匿名放行范围内，但真正的信任判断在
 * {@code ChannelOrderVerificationProvider} 的签名/凭据校验里；没有真实实现时
 * 直接返回未配置，不会把回调当成功。事件按 (provider,event_id) 幂等。
 */
@RestController
@RequestMapping("/api/common/channel")
public class ApiChannelVerificationCallbackController extends BaseController {

    private final IChannelOrderVerificationService verificationService;

    public ApiChannelVerificationCallbackController(IChannelOrderVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/callback/{providerKey}")
    public AjaxResult callback(@PathVariable String providerKey,
                               @RequestHeader Map<String, String> headers,
                               @RequestBody(required = false) String rawBody) {
        ChannelInboundCallbackView view = verificationService.handleInbound(providerKey, headers, rawBody);
        if (Boolean.TRUE.equals(view.getAccepted())) {
            return AjaxResult.success(view);
        }
        AjaxResult rejected = AjaxResult.error(400, "渠道回调未通过验证");
        rejected.put("reasonCode", view.getReasonCode());
        rejected.put("data", view);
        return rejected;
    }

    @ExceptionHandler(ChannelVoucherException.class)
    public AjaxResult handleRejection(ChannelVoucherException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }
}
