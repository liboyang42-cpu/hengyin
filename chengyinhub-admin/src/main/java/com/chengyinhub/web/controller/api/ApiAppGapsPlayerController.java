package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.LocationShareService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.appgaps.CityOrientationAnswerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * App gaps A pass 2, player routes: the city-orientation answer receipt and personal location sharing.
 * Every call acts as the logged-in member only.
 */
@RestController
public class ApiAppGapsPlayerController extends AppGapController {
    private final CityOrientationAnswerService orientation;
    private final LocationShareService share;

    public ApiAppGapsPlayerController(CityOrientationAnswerService orientation, LocationShareService share) {
        this.orientation = orientation;
        this.share = share;
    }

    // ---- city orientation ----

    @PostMapping("/api/play/city-orientation/answer")
    public AjaxResult orientationAnswer(@RequestBody Map<String, Object> body) {
        return success(orientation.answer(member(), body));
    }

    @GetMapping("/api/play/city-orientation/receipt")
    public AjaxResult orientationReceipt(@RequestParam String sessionId, @RequestParam String nodeId, @RequestParam String requestId) {
        return success(orientation.receipt(member(), sessionId, nodeId, requestId));
    }

    // ---- personal location share ----

    @GetMapping("/api/location-share/personal/context")
    public AjaxResult shareContext(@RequestParam String recipientId) { return success(share.context(member(), recipientId)); }

    @PostMapping("/api/location-share/personal/grants")
    public AjaxResult shareCreate(@RequestBody Map<String, Object> body) { return success(share.create(member(), body)); }

    @GetMapping("/api/location-share/personal/requests/{requestKey}")
    public AjaxResult shareRequest(@PathVariable String requestKey) { return success(share.requestStatus(member(), requestKey)); }

    @PostMapping("/api/location-share/personal/requests/{requestKey}/revoke")
    public AjaxResult shareRevoke(@PathVariable String requestKey) { return success(share.revoke(member(), requestKey)); }

    @GetMapping("/api/location-share/personal/owned")
    public AjaxResult shareOwned() { return success(share.owned(member())); }

    @GetMapping("/api/location-share/personal/received")
    public AjaxResult shareReceived() { return success(share.received(member())); }

    @GetMapping("/api/location-share/personal/received/events")
    public AjaxResult shareEvents(@RequestParam String cursor) { return success(share.events(member(), cursor)); }

    @PostMapping("/api/location-share/personal/grants/{grantId}/observations")
    public AjaxResult sharePublish(@PathVariable String grantId, @RequestBody Map<String, Object> body) {
        return success(share.publish(member(), grantId, body));
    }
}
