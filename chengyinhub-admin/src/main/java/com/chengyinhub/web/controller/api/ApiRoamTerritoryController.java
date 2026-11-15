package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.citygame.CityGameException;
import com.chengyinhub.business.citygame.RoamTerritoryService;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Merged Roam + CITY territory endpoints: unified map nearby query, geotagged photo + GPS
 * claiming, visitor check-ins earning owner in-game POINTS, and season leaderboard.
 */
@RestController
@RequestMapping("/api/roam/territory")
public class ApiRoamTerritoryController extends BaseController {
    private final RoamTerritoryService service;
    private final RoamLocationConsentService locationConsent;

    public ApiRoamTerritoryController(RoamTerritoryService service, RoamLocationConsentService locationConsent) {
        this.service = service;
        this.locationConsent = locationConsent;
    }

    /** 接收定位的写命令先过漫游定位授权闸（与 ApiRoamController 同一互斥锁顺序），未同意不落任何坐标。 */
    private Long consentedMember() {
        Long memberId = member();
        locationConsent.requireCurrentAgreement(memberId);
        return memberId;
    }

    private Long member() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            throw new CityGameException(401, "UNAUTHORIZED", "请先登录");
        }
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/nearby")
    public AjaxResult nearby(@RequestParam Double lat,
                             @RequestParam Double lng,
                             @RequestParam(required = false) Integer radius,
                             @RequestParam(required = false) String boardId,
                             @RequestParam(required = false) String regionId,
                             @RequestParam(required = false) String seasonId) {
        return success(service.nearby(member(), lat, lng, radius, boardId, regionId, seasonId));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 20, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/claim")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult claim(@RequestBody Map<String, Object> body) {
        return success(service.claim(consentedMember(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 20, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/checkin")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult checkin(@RequestBody Map<String, Object> body) {
        return success(service.checkin(consentedMember(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/leaderboard")
    public AjaxResult leaderboard(@RequestParam(required = false) String boardId,
                                  @RequestParam(required = false) String regionId,
                                  @RequestParam(required = false) String seasonId,
                                  @RequestParam(required = false) Integer limit) {
        return success(service.leaderboard(boardId, regionId, seasonId, limit));
    }

    @GetMapping("/my")
    public AjaxResult myTerritories() {
        return success(service.myTerritories(member()));
    }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<AjaxResult> failed(CityGameException e, HttpServletRequest request) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
