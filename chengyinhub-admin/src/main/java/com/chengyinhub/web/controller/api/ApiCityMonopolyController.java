package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.citygame.CityGameException;
import com.chengyinhub.business.citygame.CityMonopolyService;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * City Monopoly player endpoints (CITY-MONOPOLY-SPEC §6.2). App-only gameplay; every amount is 积分.
 * Writes use requestId receipts (same requestId + different body → 409) and 300 s quotes bound to the rules hash.
 */
@RestController
@RequestMapping("/api/city-game")
public class ApiCityMonopolyController extends BaseController {
    private final CityMonopolyService service;
    private final RoamLocationConsentService locationConsent;

    public ApiCityMonopolyController(CityMonopolyService service, RoamLocationConsentService locationConsent) {
        this.service = service;
        this.locationConsent = locationConsent;
    }

    private com.chengyinhub.business.citygame.CityOnSiteTaskService onSiteTasks;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOnSiteTasks(com.chengyinhub.business.citygame.CityOnSiteTaskService onSiteTasks) { this.onSiteTasks = onSiteTasks; }

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

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 60, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/territory/map")
    public AjaxResult map(@RequestParam Double minLat, @RequestParam Double minLng,
                          @RequestParam Double maxLat, @RequestParam Double maxLng) {
        return success(service.map(member(), minLat, minLng, maxLat, maxLng));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 60, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/territory/property/{propertyId}")
    public AjaxResult property(@PathVariable String propertyId) {
        return success(service.property(member(), propertyId));
    }

    @PostMapping("/territory/claim/quote")
    public AjaxResult claimQuote(@RequestBody Map<String, Object> body) {
        return success(service.claimQuote(member(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/territory/claim/command")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult claimCommand(@RequestBody Map<String, Object> body) {
        return success(service.claimCommand(consentedMember(), body));
    }

    @PostMapping("/territory/upgrade/quote")
    public AjaxResult upgradeQuote(@RequestBody Map<String, Object> body) {
        return success(service.upgradeQuote(member(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/territory/upgrade/command")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult upgradeCommand(@RequestBody Map<String, Object> body) {
        return success(service.upgradeCommand(consentedMember(), body));
    }

    @PostMapping("/territory/challenge/quote")
    public AjaxResult challengeQuote(@RequestBody Map<String, Object> body) {
        return success(service.challengeQuote(member(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/territory/challenge/command")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult challengeCommand(@RequestBody Map<String, Object> body) {
        return success(service.challengeCommand(consentedMember(), body));
    }

    /**
     * On-site challenge task (N35): photo + GPS presence graded on the server. Not @Transactional on purpose: the location
     * consent gate runs in the service's own short transaction, then the photo review runs with no transaction held.
     */
    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 10, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/territory/challenge/task/submit")
    public AjaxResult challengeTaskSubmit(@RequestBody Map<String, Object> body) {
        if (onSiteTasks == null) throw new CityGameException(409, "TASK_VERIFIER_UNAVAILABLE", "任务核验未开放");
        return success(onSiteTasks.submit(member(), body, locationConsent::requireCurrentAgreement));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/territory/scout")
    public AjaxResult scout(@RequestBody Map<String, Object> body) {
        return success(service.scout(member(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/territory/shield")
    public AjaxResult shield(@RequestBody Map<String, Object> body) {
        return success(service.shield(member(), body));
    }

    @GetMapping("/territory/revenue")
    public AjaxResult revenue(@RequestParam(required = false) Long cursor, @RequestParam(required = false) Integer limit) {
        return success(service.revenue(member(), cursor, limit));
    }

    /** 积分 HUD summary (balance, pending rent, season earned/spent). There is no separate wallet. */
    @GetMapping("/territory/points")
    public AjaxResult points() {
        return success(service.points(member()));
    }

    @GetMapping("/territory/request/{requestId}")
    public AjaxResult receipt(@PathVariable String requestId) {
        return success(service.receiptOf(member(), requestId));
    }

    @GetMapping("/season/current")
    public AjaxResult season() {
        return success(service.seasonCurrent(member()));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @GetMapping("/season/leaderboard")
    public AjaxResult leaderboard(@RequestParam(required = false) String scope, @RequestParam(required = false) String seasonId) {
        return success(service.leaderboard(member(), scope, seasonId));
    }

    @PostMapping("/season/stipend")
    public AjaxResult stipend() {
        return success(service.stipend(member()));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 20, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/chance/command")
    public AjaxResult chance(@RequestBody Map<String, Object> body) {
        return success(service.chance(member(), body));
    }

    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 30, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    @PostMapping("/shop/purchase")
    public AjaxResult purchase(@RequestBody Map<String, Object> body) {
        return success(service.purchase(member(), body));
    }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<AjaxResult> failed(CityGameException e, HttpServletRequest request) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
