package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.platformpass.PlatformPassAppleService;
import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R6a:通行证的 iOS 内购(StoreKit 2)。会员身份只来自登录态。建单与开通都要求资金开关的 pass 区域打开,
 * 关闭时一律 503 {@code MONEY_SWITCH_CLOSED};开通只认 Apple 签名的交易,客户端不能「标记已付」。
 * 拒绝的写法与 {@link ApiPlatformPassController} 相同(HTTP 200 + body code + reasonCode;
 * 带 {@code X-Error-Contract: 2} 时由错误契约改成真实 HTTP 状态)。
 */
@Tag(name = "通行证 App Store 购买")
@RestController
@RequestMapping("/api/platform-pass/apple")
public class ApiPlatformPassAppleController extends BaseController {

    private final PlatformPassAppleService service;

    public ApiPlatformPassAppleController(PlatformPassAppleService service) {
        this.service = service;
    }

    @Operation(summary = "能否在 App Store 购买、环境、在售套餐对应的商品 ID(只来自服务端配置)")
    @GetMapping("/offer")
    public AjaxResult offer() {
        requireMemberId();
        return success(service.offer());
    }

    @Operation(summary = "建单(requestId 幂等):冻结套餐与商品,签发 appAccountToken")
    @PostMapping("/orders")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public AjaxResult create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return success(service.createOrder(requireMemberId(), str(b.get("planCode")), str(b.get("requestId"))));
    }

    @Operation(summary = "提交 StoreKit 2 签名交易(jwsRepresentation):验签后幂等开通")
    @PostMapping("/transactions")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public AjaxResult confirm(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return success(service.confirm(requireMemberId(), str(b.get("signedTransactionInfo"))));
    }

    @Operation(summary = "按交易号向 App Store Server API 取回签名交易并开通(丢失凭证时)")
    @PostMapping("/transactions/lookup")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult lookup(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return success(service.lookup(requireMemberId(), str(b.get("transactionId"))));
    }

    @ExceptionHandler(PlatformPassException.class)
    public AjaxResult handleRejection(PlatformPassException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    private long requireMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        return memberId;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
