package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService;
import com.chengyinhub.business.service.rendezvous.RendezvousMeetingService;
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

/** Meeting challenge, independent confirm, and handoff result query.
 * A scan flag, a double click, or coordinates are not a request field and are not success.
 * Absent unless the contract profile and an explicit meeting bean exist. */
@RestController
@Profile("w16-consent-contract")
@ConditionalOnBean(RendezvousMeetingService.class)
@RequestMapping("/api/rendezvous/tickets/{ticketId}")
public final class ApiRendezvousMeetingController {
    private final RendezvousMeetingService meetings;
    public ApiRendezvousMeetingController(RendezvousMeetingService meetings) { this.meetings = Objects.requireNonNull(meetings); }

    @PostMapping("/challenge")
    public ResponseEntity<AjaxResult> issue(@PathVariable("ticketId") String ticketId,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); empty(body); return meetings.issue(actor, ticketId); });
    }

    @GetMapping("/challenge")
    public ResponseEntity<AjaxResult> read(@PathVariable("ticketId") String ticketId, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return meetings.read(actor, ticketId); });
    }

    @PostMapping("/challenge/confirm")
    public ResponseEntity<AjaxResult> confirm(@PathVariable("ticketId") String ticketId,
                                               @RequestBody Map<String, Object> body,
                                               @RequestParam Map<String, String> query) {
        return respond(actor -> {
            empty(query);
            if (body == null || body.size() != 1 || !body.containsKey("challengeId")) throw new InvalidRequest();
            Object value = body.get("challengeId");
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw new InvalidRequest();
            return meetings.confirm(actor, ticketId, (String) value);
        });
    }

    @PostMapping("/handoff")
    public ResponseEntity<AjaxResult> handoff(@PathVariable("ticketId") String ticketId,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); empty(body); return meetings.handoff(actor, ticketId); });
    }

    @GetMapping("/handoff")
    public ResponseEntity<AjaxResult> result(@PathVariable("ticketId") String ticketId, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return meetings.result(actor, ticketId); });
    }

    private ResponseEntity<AjaxResult> respond(Function<Long, Object> operation) {
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
                case "CHALLENGE_MISMATCH": return failure(HttpStatus.CONFLICT, "CHALLENGE_MISMATCH", "会面口令不匹配");
                case "NO_CHALLENGE": return failure(HttpStatus.CONFLICT, "NO_CHALLENGE", "还没有会面口令");
                case "MEETING_UNAVAILABLE": return failure(HttpStatus.CONFLICT, "MEETING_UNAVAILABLE", "当前不能发起会面核验");
                case "W16_MEETING_DISABLED": return failure(HttpStatus.SERVICE_UNAVAILABLE, "W16_MEETING_DISABLED", "会面核验未开放");
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
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AjaxResult> malformedBody() { return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效"); }
    private static ResponseEntity<AjaxResult> unavailable() { return failure(HttpStatus.SERVICE_UNAVAILABLE, "W16_UNAVAILABLE", "接头服务暂时不可用，请稍后重试"); }
    private static ResponseEntity<AjaxResult> failure(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AjaxResult.error(status.value(), message).put("errorCode", code));
    }
}
