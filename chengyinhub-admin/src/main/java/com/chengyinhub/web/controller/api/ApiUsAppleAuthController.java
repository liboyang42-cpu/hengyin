package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.begapmisc.usapple.UsAppleAuthService;
import com.chengyinhub.business.begapmisc.usapple.UsAppleAuthService.Outcome;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.framework.web.service.AppLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * US market Sign in with Apple ({@code Core/USAppleService}). Challenge and exchange are anonymous JSON POSTs; the session
 * proof is a bodyless GET with {@code Authorization: Bearer <US session>}. HTTP status always equals the envelope
 * {@code code}; refusals carry only the documented {@code errorCode} (the app never parses {@code msg}). Default off
 * ({@code chengyin.us.apple.enabled}); off answers 503 {@code US_APPLE_UNAVAILABLE}.
 */
@RestController
@Tag(name = "US Apple 登录（be-gap-misc）")
public class ApiUsAppleAuthController extends BaseController {
    private final UsAppleAuthService service;
    private final AppLoginService login;
    private final IUmsMemberService members;

    @Autowired
    public ApiUsAppleAuthController(UsAppleAuthService service, AppLoginService login, IUmsMemberService members) {
        this.service = service;
        this.login = login;
        this.members = members;
    }

    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    @Operation(summary = "US Apple 登录挑战")
    @PostMapping("/api/us/auth/apple/challenges")
    public ResponseEntity<Map<String, Object>> challenges(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !body.isEmpty()) return reply(Outcome.error(400, UsAppleAuthService.INVALID_REQUEST));
        return reply(service.challenge());
    }

    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    @Operation(summary = "US Apple 身份换取会话")
    @PostMapping("/api/us/auth/apple/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@RequestBody(required = false) Map<String, Object> body) {
        Outcome outcome = service.exchange(body);
        if (outcome.status != 200) return reply(outcome);
        UmsMember member = members.selectUmsMemberById(outcome.memberId);
        String token = login.loginByUsAppleLink(member);
        Map<String, Object> envelope = envelope(outcome);
        envelope.put("token", token);
        envelope.put("market", "US");
        return ResponseEntity.status(200).cacheControl(CacheControl.noStore()).body(envelope);
    }

    @Operation(summary = "US 会话核验")
    @GetMapping("/api/us/auth/session")
    public ResponseEntity<Map<String, Object>> session() {
        return reply(service.session(getAppUserId()));
    }

    private static ResponseEntity<Map<String, Object>> reply(Outcome outcome) {
        return ResponseEntity.status(outcome.status).cacheControl(CacheControl.noStore()).body(envelope(outcome));
    }

    private static Map<String, Object> envelope(Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", outcome.status);
        body.put("msg", outcome.status == 200 ? "操作成功" : outcome.errorCode);
        if (outcome.errorCode != null) body.put("errorCode", outcome.errorCode);
        if (outcome.data != null) body.put("data", outcome.data);
        return body;
    }
}
