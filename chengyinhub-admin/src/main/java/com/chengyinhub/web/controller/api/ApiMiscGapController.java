package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import com.chengyinhub.business.begapmisc.RegistrationOriginalEventService;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Map;

/**
 * be-gap-misc: the app's single-row short names (true-missing list, areas profile / npc-ai / community square /
 * registration-tickets / wallet-funds / orders-payment).
 *
 * <p>The app names these paths in {@code Core/RegionalEndpointAllowlistAudit}, {@code Core/RePoint10Routes} and
 * {@code Core/ProfileOrderEventHost}. Where the mini-program already serves the same capability under another path, the
 * request is handed unchanged to that controller method, so login, checks, idempotency and error codes are the long path's.
 * The two money writes are behind {@link MiscMoneySwitch}: refused with {@code code 403, errorCode FEATURE_CLOSED} unless the
 * server switch is on (default off; TEST turns it on).
 */
@RestController
@Tag(name = "App 单项短名（be-gap-misc）")
public class ApiMiscGapController extends BaseController {
    private final ApiLoginController login;
    private final ApiAiNpcController npc;
    private final ApiImController im;
    private final ApiRegistrationController registration;
    private final ApiWithdrawalController withdrawal;
    private final RegistrationOriginalEventService originalEvents;
    private final MiscMoneySwitch money;
    private final Validator validator;

    @Autowired
    public ApiMiscGapController(ApiLoginController login, ApiAiNpcController npc, ApiImController im,
                                ApiRegistrationController registration, ApiWithdrawalController withdrawal,
                                RegistrationOriginalEventService originalEvents, MiscMoneySwitch money,
                                Validator validator) {
        this.login = login;
        this.npc = npc;
        this.im = im;
        this.registration = registration;
        this.withdrawal = withdrawal;
        this.originalEvents = originalEvents;
        this.money = money;
        this.validator = validator;
    }

    /** profile: {@code /api/user/sms/send} = {@code /api/sms/send} (form {@code phone}, anonymous, same rate limits). */
    @Operation(summary = "发送手机验证码（App 短名）")
    @PostMapping("/api/user/sms/send")
    public AjaxResult userSmsSend(@RequestParam(value = "phone", required = false) String phone, HttpServletRequest request) {
        return login.smsSend(phone != null ? phone : text(json(request), "phone"), request);
    }

    /** npc/ai: {@code DELETE /api/npc/chat/delete} = {@code POST /api/ai/npc/chat/data/delete} (optional {@code requestId}). */
    @Operation(summary = "删除我的NPC聊天记录（App 短名）")
    @DeleteMapping("/api/npc/chat/delete")
    public AjaxResult npcChatDelete(@RequestBody(required = false) Map<String, String> body) {
        return npc.deleteChatData(body);
    }

    /** community square: {@code /api/ugc/block} = {@code /api/im/block} (form or JSON {@code target_member_id} / {@code peer_id}). */
    @Operation(summary = "拉黑（App 短名）")
    @PostMapping("/api/ugc/block")
    public AjaxResult ugcBlock(@RequestParam(value = "target_member_id", required = false) String target,
                               @RequestParam(value = "peer_id", required = false) String peer, HttpServletRequest request) {
        Map<String, Object> body = target == null && peer == null ? json(request) : null;
        if (body != null) {
            target = text(body, "target_member_id");
            if (target == null) target = text(body, "targetMemberId");
            peer = text(body, "peer_id");
        }
        return im.block(target, peer);
    }

    /**
     * registration/tickets: the order's original audit events (multipart {@code id}). Owner only; the rows are
     * {@code business_audit_event} facts whose target is that order, never built from the order's own fields.
     */
    @Operation(summary = "我的报名单原始审计事件")
    @PostMapping("/api/registration/original-event")
    public AjaxResult originalEvent(@RequestParam(value = "id", required = false) String id) {
        return originalEvents.read(getAppUserId(), id);
    }

    /** orders/payment: native APP pay parameters for the caller's own order ({@code id}); money switch first. */
    @Operation(summary = "订单支付（App 短名，资金开关）")
    @PostMapping("/api/pay/create")
    public AjaxResult payCreate(@RequestParam(value = "id", required = false) String id, HttpServletRequest request) {
        getAppUserId();
        if (!money.paymentsOpen()) return MiscMoneySwitch.closed();
        Map<String, Object> body = id == null ? json(request) : null;
        String orderId = id != null ? id : text(body, "id");
        if (orderId == null) orderId = text(body, "registrationId");
        if (orderId == null || !orderId.matches("[1-9][0-9]{0,18}")) return AjaxResult.error(400, "PAY_ORDER_REQUIRED");
        return registration.payApp(orderId);
    }

    /** wallet/funds: bank withdrawal of the caller's income ({@code WithdrawalDTO}); payout switch first. */
    @Operation(summary = "资产提现（App 短名，资金开关）")
    @PostMapping("/api/asset/withdraw")
    public AjaxResult assetWithdraw(@RequestBody(required = false) WithdrawalDTO body) {
        getAppUserId();
        if (!money.payoutOpen()) return MiscMoneySwitch.closed();
        if (body == null) return AjaxResult.error(400, "WITHDRAW_REQUEST_REQUIRED");
        // The long path validates with @Validated; a direct call has to run the same bean constraints.
        for (ConstraintViolation<WithdrawalDTO> violation : validator.validate(body)) {
            return AjaxResult.error(400, violation.getMessage());
        }
        return withdrawal.request(body);
    }

    /**
     * The app sends these short names as a form (the long paths' encoding); a JSON object body is also accepted. Form bodies
     * are bound by {@code @RequestParam}, so the body is read here only when it is JSON (at most 4 KB).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> json(HttpServletRequest request) {
        String type = request == null ? null : request.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) return null;
        try {
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length == 0 || bytes.length > 4096) return null;
            Object parsed = JSON.readValue(bytes, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String text(Map<String, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
