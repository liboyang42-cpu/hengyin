package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.moneypayout.PayoutBindingService;
import com.chengyinhub.business.moneypayout.PayoutRequestService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R6b money payout (FLOW-11, FLOW-13, MON-08). New routes only; no existing route changes.
 * <ul>
 *   <li>{@code /api/payout/request/*}: member withdrawal request, reviewed by an admin, paid out by WeChat transfer.</li>
 *   <li>{@code /api/payout/binding/*}: short-lived binding code (QR) for a payout receiver; claimed in the
 *       mini-program, confirmed in the app.</li>
 * </ul>
 * Every write is refused with {@code COMMON.FEATURE_OFF} unless its money switch area is open.
 */
@RestController
@Tag(name = "提现申请与收款绑定")
@RequestMapping("/api/payout")
public class ApiMoneyPayoutController extends BaseController {

    private final PayoutRequestService requests;
    private final PayoutBindingService bindings;

    public ApiMoneyPayoutController(PayoutRequestService requests, PayoutBindingService bindings) {
        this.requests = requests;
        this.bindings = bindings;
    }

    @Operation(summary = "提现页:开关、可提现余额、收款微信、我的申请")
    @GetMapping("/request/overview")
    public AjaxResult overview() {
        Long memberId = memberOrNull();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(requests.overview(memberId));
    }

    /** Body: {"amount": "12.50"}. */
    @Operation(summary = "提交提现申请(等待平台审核)")
    @PostMapping("/request/submit")
    public AjaxResult submit(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = memberOrNull();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return AjaxResult.success("提现申请已提交，等待审核", requests.submit(memberId, body == null ? null : body.get("amount")));
    }

    /** Body: {"id": 1}. */
    @Operation(summary = "撤回本人审核中的提现申请")
    @PostMapping("/request/cancel")
    public AjaxResult cancel(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = memberOrNull();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(requests.cancel(memberId, longOrNull(body == null ? null : body.get("id"))));
    }

    /** Body: {"purpose": "WALLET" | "CLUB_RECEIVER", "clubId": 1}. */
    @Operation(summary = "生成收款绑定码(几分钟内有效,展示为二维码)")
    @PostMapping("/binding/code")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult issue(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = memberOrNull();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(bindings.issue(memberId, text(body, "purpose"), longOrNull(body == null ? null : body.get("clubId"))));
    }

    @Operation(summary = "收款绑定码状态(App 轮询)")
    @GetMapping("/binding/status")
    public AjaxResult status(@RequestParam("purpose") String purpose,
                             @RequestParam(value = "clubId", required = false) Long clubId) {
        Long memberId = memberOrNull();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(bindings.status(memberId, purpose, clubId));
    }

    /** Body: {"purpose": "...", "clubId": 1}. */
    @Operation(summary = "在 App 里确认扫码的微信")
    @PostMapping("/binding/confirm")
    public AjaxResult confirm(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId = memberOrNull();
        if (memberId == null) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        return success(bindings.confirm(memberId, text(body, "purpose"), longOrNull(body == null ? null : body.get("clubId"))));
    }

    /**
     * Mini-program binding page. Body: {"bindCode": "...", "jsCode": "..."}. No sign-in: the one-time binding code
     * (issued to a signed-in app account) and a fresh wx.login code are the proof, and nothing binds until that app
     * account confirms.
     */
    @Anonymous
    @Operation(summary = "小程序扫码认领绑定码")
    @PostMapping("/binding/claim")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    public AjaxResult claim(@RequestBody(required = false) Map<String, Object> body) {
        return success(bindings.claim(text(body, "bindCode"), text(body, "jsCode")));
    }

    private Long memberOrNull() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOrNull(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
