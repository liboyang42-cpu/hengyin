package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.city.read.CityPlayerReadRepository;
import com.chengyinhub.business.city.read.CityPlayerReadService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** Explicit development read surface, not a production grant or a runtime command adapter. */
@RestController
@Profile("city-player-read-contract")
@ConditionalOnProperty(name = "questify.city-player-read.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/city-game")
public final class ApiCityPlayerReadController {
    private final CityPlayerReadService service;
    public ApiCityPlayerReadController(ObjectProvider<CityPlayerReadRepository> repositories) {
        // A missing provider must remain unavailable, never become fake empty published state.
        service = new CityPlayerReadService(repositories.getIfAvailable());
    }
    @GetMapping("/current")
    public ResponseEntity<AjaxResult> current(@RequestParam MultiValueMap<String, String> query) {
        return respond(query, CityPlayerReadService.View.BOARD);
    }
    @GetMapping("/participation")
    public ResponseEntity<AjaxResult> participation(@RequestParam MultiValueMap<String, String> query) {
        return respond(query, CityPlayerReadService.View.PARTICIPATION);
    }
    @GetMapping("/points")
    public ResponseEntity<AjaxResult> points(@RequestParam MultiValueMap<String, String> query) {
        return respond(query, CityPlayerReadService.View.POINTS);
    }
    private ResponseEntity<AjaxResult> respond(MultiValueMap<String, String> query, CityPlayerReadService.View view) {
        try {
            Actor actor = actor();
            Set<String> fields = view == CityPlayerReadService.View.BOARD ? Collections.singleton("regionId")
                    : new HashSet<String>(Arrays.asList("regionId", "boardId", "seasonId", "rulesReleaseId", "revision"));
            if (query == null || !query.keySet().equals(fields)) throw new CityPlayerReadService.Failure("INVALID_REQUEST");
            for (List<String> values : query.values()) if (values == null || values.size() != 1 || values.get(0) == null)
                throw new CityPlayerReadService.Failure("INVALID_REQUEST");
            Long revision = null;
            if (view != CityPlayerReadService.View.BOARD) {
                String raw = query.getFirst("revision");
                if (!raw.matches("0|[1-9][0-9]{0,15}")) throw new CityPlayerReadService.Failure("INVALID_REQUEST");
                revision = Long.valueOf(raw);
            }
            Map<String, Object> result = service.read(actor.memberId, actor.epoch, query.getFirst("regionId"), view,
                    query.getFirst("boardId"), query.getFirst("seasonId"), query.getFirst("rulesReleaseId"), revision);
            Actor after = actor();
            if (actor.authentication != after.authentication || actor.memberId != after.memberId || actor.epoch != after.epoch)
                throw new CityPlayerReadService.Failure("AUTH_REQUIRED");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(result));
        } catch (CityPlayerReadService.Failure rejected) {
            switch (rejected.code) {
                case "AUTH_REQUIRED": return failure(HttpStatus.UNAUTHORIZED, rejected.code);
                case "INVALID_REQUEST": return failure(HttpStatus.BAD_REQUEST, rejected.code);
                case "CITY_SNAPSHOT_CHANGED": return failure(HttpStatus.CONFLICT, rejected.code);
                case "CITY_PARTICIPATION_UNAVAILABLE":
                case "CITY_POINTS_UNAVAILABLE": return failure(HttpStatus.SERVICE_UNAVAILABLE, rejected.code);
                default: return failure(HttpStatus.SERVICE_UNAVAILABLE, "CITY_READ_UNAVAILABLE");
            }
        } catch (RuntimeException unavailable) { return failure(HttpStatus.SERVICE_UNAVAILABLE, "CITY_READ_UNAVAILABLE"); }
    }
    private static Actor actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof LoginAppUser))
            throw new CityPlayerReadService.Failure("AUTH_REQUIRED");
        LoginAppUser login = (LoginAppUser) auth.getPrincipal();
        // Existing App identity is the nested member ID; sys_user and query identities never qualify.
        if (login.getAppUser() == null || login.getAppUser().getUserId() == null || login.getAppUser().getUserId() <= 0
                || login.getSessionEpoch() == null || login.getSessionEpoch() < 0
                || (login.getUserId() != null && !login.getUserId().equals(login.getAppUser().getUserId())))
            throw new CityPlayerReadService.Failure("AUTH_REQUIRED");
        return new Actor(auth, login.getAppUser().getUserId(), login.getSessionEpoch());
    }
    private static final class Actor {
        final Authentication authentication; final long memberId, epoch;
        Actor(Authentication auth, long memberId, long epoch) {
            this.authentication = auth; this.memberId = memberId; this.epoch = epoch;
        }
    }
    private static ResponseEntity<AjaxResult> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(AjaxResult.error(status.value(), code).put("errorCode", code));
    }
}
