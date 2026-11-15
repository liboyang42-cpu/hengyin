package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.clubops.ClubOpsService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.OwnershipUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * club-ops(R8):附近俱乐部、活动日场次、运营看板与榜单可用度量。全部只读,不动钱。
 *
 * <ul>
 *   <li>{@code /api/club/nearby}:游客可用,只列公开可见的俱乐部,按真实距离排(CLB-06)。</li>
 *   <li>{@code /api/club/ops/projects}:今天与之后 14 天的场次 + 每场能做的动作(CLB-08/09),需要活动或签到权限。</li>
 *   <li>{@code /api/club/ops/dashboard}:按月的成员增长、到场率、缺席、回头参加(CLB-10),需要活动管理权限。</li>
 *   <li>{@code /api/club/leaderboard/sorts}:本俱乐部能用的榜单(CLB-13),可见性同俱乐部详情。</li>
 * </ul>
 */
@RestController
@Tag(name = "俱乐部运营(club-ops)")
public class ApiClubOpsController extends AppGapController {
    static final String OPS_FORBIDDEN = "CLUB_OPS_FORBIDDEN";
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ClubOpsService ops;
    private final IClubAccessService clubAccess;
    private final IClubService clubService;
    private final IClubMemberService clubMembers;

    public ApiClubOpsController(ClubOpsService ops, IClubAccessService clubAccess,
                                IClubService clubService, IClubMemberService clubMembers) {
        this.ops = ops;
        this.clubAccess = clubAccess;
        this.clubService = clubService;
        this.clubMembers = clubMembers;
    }

    @Operation(summary = "附近俱乐部 {lat, lng, radiusKm?, limit?, excludeMine?}(GCJ-02,按真实距离)")
    @PostMapping("/api/club/nearby")
    @Anonymous
    public AjaxResult nearby(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) throw AppGapException.badRequest("lat");
        Set<Long> exclude = null;
        Long me = optionalMember();
        if (me != null && Boolean.TRUE.equals(body.get("excludeMine"))) {
            exclude = new HashSet<>(clubMembers.selectClubIdsByMember(me));
            for (Club owned : clubService.selectClubsByMemberId(me)) exclude.add(owned.getId());
        }
        try {
            return success(ops.nearby(body.get("lat"), body.get("lng"), body.get("radiusKm"), body.get("limit"), exclude));
        } catch (ServiceException e) {
            throw new AppGapException(400, "BAD_REQUEST", e.getMessage());
        }
    }

    @Operation(summary = "活动日场次 {clubId}:今天(含进行中)与之后 14 天,每场报名/到场人数与可做动作")
    @PostMapping("/api/club/ops/projects")
    public AjaxResult projects(@RequestBody(required = false) Map<String, Object> body) {
        Long me = member();
        Long clubId = id(body, "clubId");
        if (clubId == null) throw AppGapException.badRequest("clubId");
        ClubAccessContext access = resolve(me, clubId);
        if (!access.hasPermission(ClubPermission.ACTIVITY_MANAGE) && !access.hasPermission(ClubPermission.EVENT_CHECKIN)) {
            throw new AppGapException(403, OPS_FORBIDDEN, "没有该俱乐部的活动管理权限");
        }
        return success(ops.projects(clubId, access, LocalDateTime.now(SHANGHAI)));
    }

    @Operation(summary = "运营看板 {clubId, months?}:按月成员增长、到场率、缺席、回头参加")
    @PostMapping("/api/club/ops/dashboard")
    public AjaxResult dashboard(@RequestBody(required = false) Map<String, Object> body) {
        Long me = member();
        Long clubId = id(body, "clubId");
        if (clubId == null) throw AppGapException.badRequest("clubId");
        ClubAccessContext access = resolve(me, clubId);
        if (!access.hasPermission(ClubPermission.ACTIVITY_MANAGE)) {
            throw new AppGapException(403, OPS_FORBIDDEN, "没有该俱乐部的活动管理权限");
        }
        try {
            return success(ops.dashboard(clubId, body.get("months"), LocalDateTime.now(SHANGHAI)));
        } catch (ServiceException e) {
            throw new AppGapException(400, "BAD_REQUEST", e.getMessage());
        }
    }

    @Operation(summary = "本俱乐部可用的榜单 {id}:到场/打卡/带队对所有俱乐部开放,里程类只给用里程的俱乐部")
    @PostMapping("/api/club/leaderboard/sorts")
    @Anonymous
    public AjaxResult leaderboardSorts(@RequestBody(required = false) Map<String, Object> body) {
        Long clubId = id(body, "id");
        if (clubId == null) clubId = id(body, "clubId");
        if (clubId == null) throw AppGapException.badRequest("id");
        Club club = clubService.selectClubById(clubId);
        if (club == null) throw AppGapException.notFound("CLUB_NOT_FOUND");
        if (Integer.valueOf(0).equals(club.getPublicVisible())) {
            Long me = optionalMember();
            boolean insider = me != null && (OwnershipUtils.isOwner(club.getMemberId(), me)
                    || clubMembers.selectByClubAndMember(clubId, me) != null);
            if (!insider) throw new AppGapException(403, "CLUB_PRIVATE", "这个俱乐部只对成员开放");
        }
        return success(ops.leaderboardSorts(clubId));
    }

    private ClubAccessContext resolve(Long me, Long clubId) {
        try {
            ClubAccessContext access = clubAccess.resolveWithEventAccesses(me, clubId);
            if (access == null) throw new AppGapException(403, OPS_FORBIDDEN, "没有该俱乐部的管理权限");
            return access;
        } catch (ServiceException e) {
            throw new AppGapException(403, OPS_FORBIDDEN, "没有该俱乐部的管理权限");
        }
    }

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || value instanceof Boolean) return null;
        Long parsed = Convert.toLong(value, null);
        return parsed == null || parsed <= 0 ? null : parsed;
    }
}
