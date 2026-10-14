package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;
import com.chengyinhub.business.domain.dto.ClubEventScopeRequest;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubWaitlistRequest;
import com.chengyinhub.business.service.IClubEventAttendanceService;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubWaitlistService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "俱乐部活动运营")
@RequestMapping("/api/club/event-ops")
public class ApiClubEventOpsController extends BaseController {
    @Autowired private IClubEventOpsService eventOpsService;
    @Autowired private IClubWaitlistService waitlistService;
    @Autowired private IClubEventAttendanceService attendanceService;
    @Autowired private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;
    @Autowired private com.chengyinhub.business.service.IUmsMemberService umsMemberService;

    /** F-L5C-02:对他人可见的文字写入前送检;审不了按 fail-close 拦截。 */
    private String textRisk(Long actorMemberId, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        com.chengyinhub.business.domain.UmsMember actor = umsMemberService.selectUmsMemberById(actorMemberId);
        return wxContentSecurityService.checkText(text, actor == null ? null : actor.getWxOpenId());
    }

    @PostMapping("/topics")
    public AjaxResult topics(@RequestBody(required = false) Map<String, Object> request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Long clubId = positiveLong(request == null ? null : request.get("clubId"));
        if (clubId == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少俱乐部参数");
        try {
            return success(eventOpsService.listOpenableTopics(principal, clubId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/series/create")
    public AjaxResult createSeries(@RequestBody(required = false) ClubEventSeriesRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少系列参数");
        try {
            return success(eventOpsService.createSeries(principal, appUsername(principal), request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/series/list")
    public AjaxResult listSeries(@RequestBody(required = false) Map<String, Object> request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        Long clubId = positiveLong(request == null ? null : request.get("clubId"));
        if (clubId == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少俱乐部参数");
        try {
            return success(eventOpsService.listSeries(principal, clubId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/series/detail")
    public AjaxResult seriesDetail(@RequestBody(required = false) ClubEventSeriesRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null || request.getClubId() == null || request.getSeriesId() == null) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少系列参数");
        }
        try {
            return success(eventOpsService.getSeries(
                    principal, request.getClubId(), request.getSeriesId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    /** Figma E1「日期清单」:含已过去与已取消的场次,并附每场可编辑性。 */
    @PostMapping("/series/occurrences")
    public AjaxResult seriesOccurrences(@RequestBody(required = false) ClubEventSeriesRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null || request.getClubId() == null || request.getSeriesId() == null) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少系列参数");
        }
        try {
            return success(eventOpsService.listSeriesOccurrences(
                    principal, request.getClubId(), request.getSeriesId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/series/update-future")
    public AjaxResult updateFuture(@RequestBody(required = false) ClubEventSeriesRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少系列参数");
        try {
            return success(eventOpsService.updateFuture(principal, appUsername(principal), request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/cancel")
    public AjaxResult cancelOccurrence(@RequestBody(required = false) ClubEventCancellationRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少取消参数");
        // 取消原因会发给报名者与候补者。
        String risk = textRisk(principal, request.getReason());
        if (risk != null) return error(risk);
        try {
            return success(eventOpsService.cancelOccurrence(principal, appUsername(principal), request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/occurrence/status")
    public AjaxResult occurrenceStatus(@RequestBody(required = false) ClubEventScopeRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少场次参数");
        try {
            return success(eventOpsService.getCancellationState(
                    principal, request.getClubId(), request.getActivityId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/waitlist/join")
    public AjaxResult joinWaitlist(@RequestBody(required = false) ClubWaitlistRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少候补参数");
        try {
            return success(waitlistService.join(principal, request.getActivityId(), request.getTicketId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/waitlist/status")
    public AjaxResult waitlistStatus(@RequestBody(required = false) ClubWaitlistRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少候补参数");
        try {
            return success(waitlistService.myStatus(principal, request.getActivityId(), request.getTicketId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/waitlist/cancel")
    public AjaxResult cancelWaitlist(@RequestBody(required = false) ClubWaitlistRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少候补参数");
        try {
            return success(waitlistService.cancel(principal, request.getActivityId(), request.getTicketId(),
                    new java.util.Date()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/roster")
    public AjaxResult roster(@RequestBody(required = false) ClubEventScopeRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少场次参数");
        try {
            return success(attendanceService.roster(principal, request.getClubId(), request.getActivityId()));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/attendance/correct")
    public AjaxResult correctAttendance(@RequestBody(required = false) ClubAttendanceCorrectionRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少更正参数");
        try {
            return success(attendanceService.correct(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception ignored) { return null; }
    }

    private String appUsername(Long principal) {
        try {
            String username = getAppUserName();
            return username == null || username.trim().isEmpty() ? String.valueOf(principal) : username;
        } catch (Exception ignored) {
            return String.valueOf(principal);
        }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }

    private Long positiveLong(Object value) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
