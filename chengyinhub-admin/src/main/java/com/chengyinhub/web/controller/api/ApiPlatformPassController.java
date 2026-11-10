package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 通行证（平台会员）。会员身份只来自登录态；订单号不属于本人一律「订单不存在」。
 * 开通只由已验证支付事实（JSAPI 回调）或服务端回查（虚拟支付）驱动，本控制器没有「标记已付」入口。
 */
@Tag(name = "通行证")
@RestController
@RequestMapping("/api/platform-pass")
public class ApiPlatformPassController extends BaseController {

    private final PlatformPassService service;

    public ApiPlatformPassController(PlatformPassService service) {
        this.service = service;
    }

    @Operation(summary = "通行证页：套餐、本人有效期、可玩主题数、支付渠道")
    @GetMapping
    public AjaxResult overview() {
        return success(service.overview(requireMemberId()));
    }

    @Operation(summary = "游戏主题库：官方开放自玩主题")
    @GetMapping("/library")
    public AjaxResult library(@RequestParam(required = false) Integer productType,
                              @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return success(service.library(requireMemberId(), productType, limit == null ? 50 : limit));
    }

    @Operation(summary = "下单（requestId 幂等；只建待支付单）")
    @PostMapping("/orders")
    public AjaxResult create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return success(service.createOrder(requireMemberId(), str(b.get("planCode")), str(b.get("channel")),
                str(b.get("requestId"))));
    }

    @Operation(summary = "我的通行证订单")
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(service.myOrders(requireMemberId(), limit == null ? 20 : limit));
    }

    @Operation(summary = "订单详情（轮询开通结果）")
    @GetMapping("/orders/{orderNo}")
    public AjaxResult order(@PathVariable String orderNo) {
        return success(service.order(requireMemberId(), orderNo));
    }

    @Operation(summary = "拉起支付参数：JSAPI 或虚拟支付（虚拟支付需新鲜 wx.login code）")
    @PostMapping("/orders/{orderNo}/pay")
    public AjaxResult pay(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body) {
        return success(service.preparePayment(requireMemberId(), orderNo, body == null ? null : str(body.get("jsCode"))));
    }

    @Operation(summary = "虚拟支付回查确认（前端支付成功后调用；以服务端回查为准）")
    @PostMapping("/orders/{orderNo}/confirm")
    public AjaxResult confirm(@PathVariable String orderNo) {
        return success(service.confirmXpay(requireMemberId(), orderNo));
    }

    @Operation(summary = "取消待支付单")
    @PostMapping("/orders/{orderNo}/cancel")
    public AjaxResult cancel(@PathVariable String orderNo) {
        return success(service.cancel(requireMemberId(), orderNo));
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
