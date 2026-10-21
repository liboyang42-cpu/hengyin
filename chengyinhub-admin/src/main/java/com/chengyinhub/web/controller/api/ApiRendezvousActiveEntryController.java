package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService;
import com.chengyinhub.business.service.rendezvous.RendezvousPairingComposition;
import com.chengyinhub.business.service.rendezvous.RendezvousVersionedPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Active entry for join, readback, accept, reject and withdraw.
 * Off unless {@link RendezvousPairingComposition#ENABLED_PROPERTY} is true.
 * The contract-test profile keeps the older controllers, so the two are not both mapped.
 * This controller does not pair people and does not read a member profile.
 */
@RestController
@Profile("!w16-consent-contract")
@ConditionalOnProperty(name = RendezvousPairingComposition.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = RendezvousPairingComposition.DEFAULT_ENABLED)
@ConditionalOnBean({RendezvousConsentService.class, RendezvousVersionedPolicy.class})
public final class ApiRendezvousActiveEntryController {
    private final RendezvousConsentService consent;
    private final RendezvousVersionedPolicy policy;

    public ApiRendezvousActiveEntryController(RendezvousConsentService consent, RendezvousVersionedPolicy policy) {
        this.consent = Objects.requireNonNull(consent);
        this.policy = Objects.requireNonNull(policy);
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

    @GetMapping("/api/rendezvous/tickets/{ticketId}")
    public ResponseEntity<AjaxResult> read(@PathVariable("ticketId") String ticketId, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return consent.read(actor, ticketId); });
    }

    @PostMapping("/api/rendezvous/tickets/{ticketId}/accept")
    public ResponseEntity<AjaxResult> accept(@PathVariable("ticketId") String ticketId,
                                              @RequestBody Map<String, Object> body, @RequestParam Map<String, String> query) {
        return respond(actor -> {
            empty(query);
            if (body == null || body.size() != 1 || !body.containsKey("expectedVersion")) throw new InvalidRequest();
            Object value = body.get("expectedVersion");
            if (!(value instanceof Integer) && !(value instanceof Long)) throw new InvalidRequest();
            long version = ((Number) value).longValue();
            if (version < 1) throw new InvalidRequest();
            return consent.accept(actor, ticketId, version);
        });
    }

    @PostMapping("/api/rendezvous/tickets/{ticketId}/reject")
    public ResponseEntity<AjaxResult> reject(@PathVariable("ticketId") String ticketId,
                                              @RequestBody(required = false) Map<String, Object> body, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); empty(body); return consent.cancel(actor, ticketId); });
    }

    @PostMapping("/api/rendezvous/tickets/{ticketId}/cancel")
    public ResponseEntity<AjaxResult> cancel(@PathVariable("ticketId") String ticketId,
                                              @RequestBody(required = false) Map<String, Object> body, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); empty(body); return consent.cancel(actor, ticketId); });
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
                case "ACTIVE_TICKET_EXISTS": return failure(HttpStatus.CONFLICT, "ACTIVE_TICKET_EXISTS", "已有进行中的接头申请");
                case "STALE_PROPOSAL": return failure(HttpStatus.CONFLICT, "STALE_PROPOSAL", "状态已更新，请刷新后确认");
                case "NO_PROPOSAL": return failure(HttpStatus.CONFLICT, "NO_PROPOSAL", "当前没有可确认的配对");
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
