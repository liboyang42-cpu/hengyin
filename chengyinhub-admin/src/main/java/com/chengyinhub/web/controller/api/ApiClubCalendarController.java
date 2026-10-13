package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ClubCalendarService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiClubCalendarController extends BaseController {
    @Autowired private ClubCalendarService calendarService;

    @GetMapping("/api/club/calendar")
    public AjaxResult calendar(@RequestParam Long clubId, @RequestParam String month,
                               @RequestParam(required = false) Long afterId,
                               @RequestParam(required = false) Long throughId) {
        Long viewerId;
        try { viewerId = getAppUserId(); }
        catch (Exception notLoggedIn) { viewerId = null; }
        try { return AjaxResult.success(calendarService.month(clubId, viewerId, month, afterId, throughId)); }
        catch (IllegalArgumentException e) { return AjaxResult.error(e.getMessage()); }
    }
}
