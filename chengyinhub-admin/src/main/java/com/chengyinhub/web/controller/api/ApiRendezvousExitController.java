package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.rendezvous.RendezvousConsentService;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Proposed safe-exit route. Absent unless the contract profile and an explicit service bean exist.
 * The body cannot name a goal, peer, penalty, difficulty, team, or reward. */
@RestController
@Profile("w16-consent-contract")
@ConditionalOnBean(RendezvousSafeExitService.class)
@RequestMapping("/api/rendezvous/tickets/{ticketId}/exit")
public final class ApiRendezvousExitController {
    private final RendezvousSafeExitService exits;
    public ApiRendezvousExitController(RendezvousSafeExitService exits) { this.exits = Objects.requireNonNull(exits); }

    @GetMapping
    public ResponseEntity<AjaxResult> options(@PathVariable("ticketId") String ticketId, @RequestParam Map<String, String> query) {
        return respond(actor -> { empty(query); return exits.options(actor, ticketId); });
    }

    @PostMapping
    public ResponseEntity<AjaxResult> choose(@PathVariable("ticketId") String ticketId,
                                              @RequestBody Map<String, Object> body,
                                              @RequestParam Map<String, String> query) {
        return respond(actor -> {
            empty(query);
            if (body == null || body.size() != 1 || !body.containsKey("choice")) throw new InvalidRequest();
            Object value = body.get("choice");
            if (!(value instanceof String)) throw new InvalidRequest();
            return exits.choose(actor, ticketId, (String) value);
        });
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
                case "EXIT_UNAVAILABLE": return failure(HttpStatus.CONFLICT, "EXIT_UNAVAILABLE", "当前不能退出");
                case "EXIT_CONFLICT": return failure(HttpStatus.CONFLICT, "EXIT_CONFLICT", "已经做过不同的选择");
                case "HANDOFF_UNKNOWN": return failure(HttpStatus.CONFLICT, "HANDOFF_UNKNOWN", "组队结果未知，不能开始单人");
                case "HANDOFF_JOIN_UNCONFIRMED": return failure(HttpStatus.CONFLICT, "HANDOFF_JOIN_UNCONFIRMED", "组队尚未确认加入，不能开始单人");
                case "HANDOFF_COMPLETE": return failure(HttpStatus.CONFLICT, "HANDOFF_COMPLETE", "组队交接已完成，不能开始单人");
                case "SOLO_GOAL_UNAVAILABLE": return failure(HttpStatus.CONFLICT, "SOLO_GOAL_UNAVAILABLE", "没有可独自进行的已发布目标");
                case "SOLO_LAUNCHER_UNAVAILABLE": return failure(HttpStatus.CONFLICT, "SOLO_LAUNCHER_UNAVAILABLE", "单人启动器未接通");
                case "W16_EXIT_DISABLED": return failure(HttpStatus.SERVICE_UNAVAILABLE, "W16_EXIT_DISABLED", "安全退出未开放");
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
