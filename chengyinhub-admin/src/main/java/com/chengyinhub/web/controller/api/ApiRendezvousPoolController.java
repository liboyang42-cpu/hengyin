package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService;
import com.chengyinhub.business.service.rendezvous.RendezvousRoleSecretService;
import com.chengyinhub.business.service.rendezvous.RendezvousVersionedPolicy;
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

/** Self opt-in, current-ticket readback, reject/exit, and self role clue.
 * Absent unless the contract profile and the explicit beans exist.
 * No propose, coordinate, partner, or team route. */
@RestController
@Profile("w16-consent-contract")
@ConditionalOnBean({RendezvousConsentService.class, RendezvousVersionedPolicy.class, RendezvousRoleSecretService.class})
public final class ApiRendezvousPoolController {
    private final RendezvousConsentService consent;
    private final RendezvousVersionedPolicy policy;
    private final RendezvousRoleSecretService secrets;
    public ApiRendezvousPoolController(RendezvousConsentService consent, RendezvousVersionedPolicy policy,
                                       RendezvousRoleSecretService secrets) {
        this.consent = Objects.requireNonNull(consent);
        this.policy = Objects.requireNonNull(policy);
        this.secrets = Objects.requireNonNull(secrets);
    }

    @PostMapping("/api/rendezvous/pool/join")
    public ResponseEntity<AjaxResult> join(@RequestBody Map<String, Object> body, @RequestParam Map<String, String> query) {
        return respond(actor -> {
            empty(query);
            if (body == null || body.size() != 2 || !body.containsKey("pool") || !body.containsKey("eligibilityVersion")) throw new InvalidRequest();
            Object pool = body.get("pool"), version = body.get("eligibilityVersion");
            if (!(pool instanceof String) || !(version instanceof String)) throw new InvalidRequest();
            policy.requireClientVersion((String) version);
            String ttlVersion = policy.ttlVersion((String) version);
            Long ttl = policy.ticketTtl(ttlVersion);
            if (ttl == null) throw new PolicyClosed();
            return consent.join(actor, (String) pool, ttlVersion, ttl);
        });
    }

    @GetMapping("/api/rendezvous/pool/current")
    public ResponseEntity<AjaxResult> current(@RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return consent.current(actor); });
    }

    @PostMapping("/api/rendezvous/tickets/{ticketId}/reject")
    public ResponseEntity<AjaxResult> reject(@PathVariable("ticketId") String ticketId,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); empty(body); return consent.cancel(actor, ticketId); });
    }

    @GetMapping("/api/rendezvous/tickets/{ticketId}/secret")
    public ResponseEntity<AjaxResult> secret(@PathVariable("ticketId") String ticketId, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return secrets.read(actor, ticketId); });
    }

    private ResponseEntity<AjaxResult> respond(Function<Long, Object> operation) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(operation.apply(actor())));
        } catch (InsufficientAuthenticationException rejected) {
            return failure(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        } catch (InvalidRequest rejected) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
        } catch (PolicyClosed rejected) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_CLOSED", "接头规则未开放");
        } catch (RendezvousConsentService.Rejection rejected) {
            switch (rejected.getCode()) {
                case "NOT_FOUND": return failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "未找到该接头申请");
                case "INVALID_INPUT": return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
                case "AUTH_REQUIRED": return failure(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
                case "NOT_ELIGIBLE": return failure(HttpStatus.SERVICE_UNAVAILABLE, "NOT_ELIGIBLE", "当前不满足接头条件");
                case "POLICY_CLOSED": return failure(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_CLOSED", "接头规则未开放");
                case "SECRET_UNAVAILABLE": return failure(HttpStatus.CONFLICT, "SECRET_UNAVAILABLE", "双方确认后才能查看自己的角色线索");
                case "ACTIVE_TICKET_EXISTS": return failure(HttpStatus.CONFLICT, "ACTIVE_TICKET_EXISTS", "已有进行中的接头申请");
                default: return unavailable();
            }
        } catch (RuntimeException unavailable) {
            return unavailable();
        }
    }

    private static long actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginAppUser)) throw new InsufficientAuthenticationException("APP_SESSION_REQUIRED");
        LoginAppUser login = (LoginAppUser) authentication.getPrincipal();
        if (login.getAppUser() == null || login.getAppUser().getUserId() == null || login.getAppUser().getUserId() <= 0)
            throw new InsufficientAuthenticationException("APP_SESSION_REQUIRED");
        return login.getAppUser().getUserId();
    }

    private static void empty(Map<?, ?> values) { if (values != null && !values.isEmpty()) throw new InvalidRequest(); }
    private static final class InvalidRequest extends IllegalArgumentException { private static final long serialVersionUID = 1L; }
    private static final class PolicyClosed extends IllegalStateException { private static final long serialVersionUID = 1L; }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AjaxResult> malformedBody() { return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效"); }
    private static ResponseEntity<AjaxResult> unavailable() { return failure(HttpStatus.SERVICE_UNAVAILABLE, "W16_UNAVAILABLE", "接头服务暂时不可用，请稍后重试"); }
    private static ResponseEntity<AjaxResult> failure(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AjaxResult.error(status.value(), message).put("errorCode", code));
    }
}
