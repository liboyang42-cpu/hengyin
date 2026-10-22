package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.service.IClubCalendarService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.OwnershipUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 俱乐部月历场次(W10a #19)。单独成类,不往 ApiClubController 里加 —— 那个类同时被帖子与
 * 带队编辑两条线在改,这里只读、只有一个端点。
 *
 * <p>可见性与 {@code /api/club/topics} 同一道闸:俱乐部不公开时只有主理人与 status=1 成员可看。
 * 治理者(canGovernClub:主理人或 role=1 管理员)额外看到待审/已取消场次,并带 state 区分;
 * 其余人只看公开可见场次。clubId 只是「想看哪个俱乐部」,不自证身份。
 */
@RestController
@Tag(name = "俱乐部月历")
@RequestMapping("/api/club/calendar")
public class ApiClubCalendarController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ApiClubCalendarController.class);

    private final IClubCalendarService calendarService;
    private final IClubService clubService;
    private final IClubMemberService clubMemberService;

    public ApiClubCalendarController(IClubCalendarService calendarService,
                                     IClubService clubService,
                                     IClubMemberService clubMemberService) {
        this.calendarService = calendarService;
        this.clubService = clubService;
        this.clubMemberService = clubMemberService;
    }

    @Operation(summary = "俱乐部某月真实场次(日期 + updatedAt;治理者含待审/已取消)")
    @PostMapping("/sessions")
    public AjaxResult sessions(@RequestBody(required = false) Map<String, Object> body) {
        Long viewer;
        try {
            viewer = getAppUserId();
        } catch (Exception notLoggedIn) {
            return error("请先登录");
        }
        Long clubId = body == null ? null : Convert.toLong(body.get("clubId"));
        if (clubId == null || clubId <= 0) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) return error("俱乐部不存在");
        if (!canViewClub(club, viewer)) return error("这个俱乐部只对成员开放");
        boolean governor = clubMemberService.canGovernClub(club, viewer);
        String month = body.get("month") == null ? null : String.valueOf(body.get("month"));
        try {
            return success(calendarService.monthSessions(clubId, month, governor, viewer));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private boolean canViewClub(Club club, Long viewer) {
        if (club.getPublicVisible() == null || !Integer.valueOf(0).equals(club.getPublicVisible())) return true;
        if (viewer == null) return false;
        if (OwnershipUtils.isOwner(club.getMemberId(), viewer)) return true;
        try {
            ClubMember membership = clubMemberService.selectMembershipRecord(club.getId(), viewer);
            return membership != null && Integer.valueOf(1).equals(membership.getStatus());
        } catch (RuntimeException e) {
            log.warn("判定俱乐部可见性时身份读取失败，按不可见处理，clubId={}", club.getId(), e);
            return false;
        }
    }
}
