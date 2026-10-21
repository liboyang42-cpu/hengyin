package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** Participant read/accept/exit adapter for the reviewed W16 service.
 * Deliberately absent unless the contract-evaluation profile AND an explicit service
 * bean are provided. No production profile, bean, grant or safety policy is added.
 * Existing SecurityConfig's authenticated fallback remains unchanged; no Anonymous route.
 */
@RestController
@Profile("w16-consent-contract")
@ConditionalOnBean(RendezvousConsentService.class)
@RequestMapping("/api/rendezvous/tickets")
public final class ApiRendezvousConsentController {
    private final RendezvousConsentService service;
    public ApiRendezvousConsentController(RendezvousConsentService service) { this.service = Objects.requireNonNull(service); }

    @GetMapping("/{ticketId}")
    public ResponseEntity<AjaxResult> read(@PathVariable("ticketId") String ticketId,
                                          @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return service.read(actor, ticketId); });
    }
    @PostMapping("/{ticketId}/accept")
    public ResponseEntity<AjaxResult> accept(@PathVariable("ticketId") String ticketId,
                                            @RequestBody Map<String, Object> body, @RequestParam Map<String, String> query) {
        return respond(actor -> {
            empty(query);
            if (body == null || body.size() != 1 || !body.containsKey("expectedVersion")) throw new InvalidRequest();
            Object value = body.get("expectedVersion");
            // Do not coerce strings, booleans or fractional JSON numbers into consent versions.
            if (!(value instanceof Integer) && !(value instanceof Long)) throw new InvalidRequest();
            long version = ((Number) value).longValue();
            if (version < 1) throw new InvalidRequest();
            return service.accept(actor, ticketId, version);
        });
    }
    @PostMapping("/{ticketId}/cancel")
    public ResponseEntity<AjaxResult> cancel(@PathVariable("ticketId") String ticketId,
                                            @RequestBody(required = false) Map<String, Object> body, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); empty(body); return service.cancel(actor, ticketId); });
    }
    // Pool opt-in/selection, propose, safety administration, meeting verification and team
    // creation are deliberately not HTTP actions here. Eligibility/place policy is not invented.

    private ResponseEntity<AjaxResult> respond(Function<Long, RendezvousConsentService.View> operation) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(operation.apply(actor())));
        } catch (InsufficientAuthenticationException rejected) {
            return failure(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        } catch (InvalidRequest rejected) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
        } catch (RendezvousConsentService.Rejection rejected) {
            switch (rejected.getCode()) {
                case "NOT_FOUND": return failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "未找到该接头申请");
                case "INVALID_INPUT": return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
                case "AUTH_REQUIRED": return failure(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
                case "STALE_PROPOSAL": return failure(HttpStatus.CONFLICT, "STALE_PROPOSAL", "状态已更新，请刷新后确认");
                case "NO_PROPOSAL": return failure(HttpStatus.CONFLICT, "NO_PROPOSAL", "当前没有可确认的配对");
                default: return unavailable();
            }
        } catch (RuntimeException unavailable) {
            // No transport, SQL, actor, ticket or policy exception details leave this boundary.
            return unavailable();
        }
    }
    private long actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginAppUser)) throw new InsufficientAuthenticationException("APP_SESSION_REQUIRED");
        LoginAppUser login = (LoginAppUser) authentication.getPrincipal();
        // Same authoritative nested member field used by BaseController.getAppUserId().
        if (login.getAppUser() == null || login.getAppUser().getUserId() == null || login.getAppUser().getUserId() <= 0)
            throw new InsufficientAuthenticationException("APP_SESSION_REQUIRED");
        return login.getAppUser().getUserId();
    }
    private static void empty(Map<?, ?> values) { if (values != null && !values.isEmpty()) throw new InvalidRequest(); }
    private static final class InvalidRequest extends IllegalArgumentException { private static final long serialVersionUID = 1L; }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AjaxResult> malformedBody() { return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效"); }
    private static ResponseEntity<AjaxResult> unavailable() { return failure(HttpStatus.SERVICE_UNAVAILABLE, "W16_UNAVAILABLE", "接头服务暂时不可用，请稍后重试"); }
    private static ResponseEntity<AjaxResult> failure(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AjaxResult.error(status.value(), message).put("errorCode", code));
    }
}
