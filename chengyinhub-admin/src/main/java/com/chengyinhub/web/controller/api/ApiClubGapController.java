package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.appgaps.ClubProxyEnrollService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.domain.dto.ClubEventScopeRequest;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubWaitlistRequest;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IGroupCodeService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 俱乐部短名（{@code Core/ClubGapClient}，Handover 20 业主更正 2026-11-13）。
 *
 * <p>App 对这些名字一律 POST JSON，请求体就是 {@code ClubGapSurface.all} 里的字段，只读回 {@code code / errorCode / msg}
 * （HTTP 2xx 且 code == 200 才算成功）。小程序走的是同一能力的长路径，所以这里绝大多数只是别名：把字段原样交给
 * 既有控制器，登录、权限、送检、幂等与错误口径都和长路径逐字相同，不另起第二套规则。
 *
 * <p>新能力只有两处：{@code /api/club/events/proxy-enroll}（代报名邀请，见 {@link ClubProxyEnrollService}）与
 * {@code /api/club/workbench}（管理入口聚合）。{@code /api/club-compensation/quality/evidence} 按 2026-09-10 业主裁决
 * 已下线，留在 {@link ApiClubCompensationController}，回 410。
 */
@RestController
@Tag(name = "俱乐部缺口（App 短名）")
public class ApiClubGapController extends AppGapController {
    static final String WORKBENCH_FORBIDDEN = "CLUB_WORKBENCH_FORBIDDEN";
    static final String SETTLEMENT_FORBIDDEN = "CLUB_SETTLEMENT_FORBIDDEN";
    /** 管理入口按权限点出现；只有 club:read / 名单只读的普通成员看不到工作台。 */
    static final Map<String, String> WORKBENCH_TOOLS = tools();

    private final ApiClubEventOpsController eventOps;
    private final ApiClubEventNotificationController notices;
    private final ApiClubController clubs;
    private final ApiClubSettlementController settlements;
    private final IGroupCodeService groupCodes;
    private final ChannelPlatformService channels;
    private final IClubAccessService clubAccess;
    private final IClubService clubService;
    private final IClubMemberService clubMembers;
    private final ClubProxyEnrollService proxyEnroll;

    public ApiClubGapController(ApiClubEventOpsController eventOps, ApiClubEventNotificationController notices,
                                ApiClubController clubs, ApiClubSettlementController settlements,
                                IGroupCodeService groupCodes, ChannelPlatformService channels, IClubAccessService clubAccess,
                                IClubService clubService, IClubMemberService clubMembers, ClubProxyEnrollService proxyEnroll) {
        this.eventOps = eventOps;
        this.notices = notices;
        this.clubs = clubs;
        this.settlements = settlements;
        this.groupCodes = groupCodes;
        this.channels = channels;
        this.clubAccess = clubAccess;
        this.clubService = clubService;
        this.clubMembers = clubMembers;
        this.proxyEnroll = proxyEnroll;
    }

    // ---- 场次（报名页：候补与场次状态） ----

    @Operation(summary = "加入候补 {activityId}（= /api/club/event-ops/waitlist/join）")
    @PostMapping("/api/club/activity/join")
    public AjaxResult activityJoin(@RequestBody(required = false) ClubWaitlistRequest body) {
        return eventOps.joinWaitlist(body);
    }

    @Operation(summary = "取消候补 {activityId}（= /api/club/event-ops/waitlist/cancel）")
    @PostMapping("/api/club/activity/cancel")
    public AjaxResult activityCancel(@RequestBody(required = false) ClubWaitlistRequest body) {
        return eventOps.cancelWaitlist(body);
    }

    @Operation(summary = "场次状态 {clubId, activityId}（= /api/club/event-ops/occurrence/status）")
    @PostMapping("/api/club/activity/detail")
    public AjaxResult activityDetail(@RequestBody(required = false) ClubEventScopeRequest body) {
        return eventOps.occurrenceStatus(body);
    }

    // ---- 活动运营 ----

    @Operation(summary = "活动系列 {clubId}（= /api/club/event-ops/series/list）")
    @PostMapping("/api/club/events")
    public AjaxResult events(@RequestBody(required = false) Map<String, Object> body) {
        return eventOps.listSeries(body);
    }

    @Operation(summary = "保存系列（= /api/club/event-ops/series/create，同字段同 requestId 幂等）")
    @PostMapping("/api/club/events/save")
    public AjaxResult eventsSave(@RequestBody(required = false) ClubEventSeriesRequest body) {
        return eventOps.createSeries(body);
    }

    @Operation(summary = "更正出勤（= /api/club/event-ops/attendance/correct）")
    @PostMapping("/api/club/events/Checkin")
    public AjaxResult eventsCheckin(@RequestBody(required = false) ClubAttendanceCorrectionRequest body) {
        return eventOps.correctAttendance(body);
    }

    @Operation(summary = "代报名邀请 {clubId, activityId, memberId}：不建报名单、不动钱，成员本人完成报名")
    @PostMapping("/api/club/events/proxy-enroll")
    public AjaxResult proxyEnroll(@RequestBody(required = false) Map<String, Object> body) {
        Long me = member();
        Long clubId = id(body, "clubId");
        Long activityId = id(body, "activityId");
        if (clubId == null) throw AppGapException.badRequest("clubId");
        if (activityId == null) throw AppGapException.badRequest("activityId");
        require(me, clubId, activityId, ClubPermission.ACTIVITY_MANAGE, "CLUB_FORBIDDEN");
        return success(proxyEnroll.propose(me, clubId, activityId, id(body, "memberId")));
    }

    @Operation(summary = "我收到的代报名邀请（本人去 /api/registration 完成报名）")
    @PostMapping("/api/club/events/proxy-enroll/mine")
    public AjaxResult proxyEnrollMine() {
        return success(proxyEnroll.mine(member()));
    }

    // ---- 团码 ----

    @Operation(summary = "团码资格 {activityId}：能否为该场次出码（不签码）")
    @PostMapping("/api/club/group-code")
    public AjaxResult groupCode(@RequestBody(required = false) Map<String, Object> body) {
        return groupCodes.status(id(body, "activityId"), appUid());
    }

    @Operation(summary = "签发团码 {activityId}（= /api/verify/groupcode/issue，JSON 体）")
    @PostMapping("/api/club/group-code/save")
    public AjaxResult groupCodeSave(@RequestBody(required = false) Map<String, Object> body) {
        return groupCodes.issue(id(body, "activityId"), appUid());
    }

    // ---- 入会、频道、通知 ----

    @Operation(summary = "申请加入 {id, joinMessage}（= /api/club/join）")
    @PostMapping("/api/club/join-requests/apply")
    public AjaxResult apply(@RequestBody(required = false) Club body) {
        return clubs.join(body == null ? new Club() : body);
    }

    @Operation(summary = "俱乐部频道 {clubId}（= GET /api/channels?guild_kind=1&guild_id=clubId）")
    @PostMapping("/api/club/channels")
    public ResponseEntity<AjaxResult> clubChannels(@RequestBody(required = false) Map<String, Object> body) {
        Long clubId = id(body, "clubId");
        if (clubId == null) throw AppGapException.badRequest("clubId");
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.directory(me, 1, clubId, false));
    }

    @Operation(summary = "通知人数 {clubId}（= /api/club/event-notification/audience-counts）")
    @PostMapping("/api/club/notices")
    public AjaxResult noticeAudience(@RequestBody(required = false) ClubEventCampaignRequest body) {
        return notices.audienceCounts(body);
    }

    @Operation(summary = "发送通知（= /api/club/event-notification/send，渠道缺省 IN_APP）")
    @PostMapping("/api/club/notices/send")
    public AjaxResult noticeSend(@RequestBody(required = false) ClubEventCampaignRequest body) {
        return notices.send(body);
    }

    // ---- 管理与结算 ----

    @Operation(summary = "俱乐部工作台 {clubId}：身份、权限点、管理入口与待办计数")
    @PostMapping("/api/club/workbench")
    public AjaxResult workbench(@RequestBody(required = false) Map<String, Object> body) {
        Long me = member();
        Long clubId = id(body, "clubId");
        if (clubId == null) throw AppGapException.badRequest("clubId");
        ClubAccessContext access;
        try {
            access = clubAccess.resolveWithEventAccesses(me, clubId);
        } catch (ServiceException e) {
            throw new AppGapException(403, WORKBENCH_FORBIDDEN, "没有该俱乐部的管理权限");
        }
        List<String> tools = new ArrayList<>();
        if (access != null) {
            for (Map.Entry<String, String> tool : WORKBENCH_TOOLS.entrySet()) {
                if (access.hasPermission(tool.getValue())) tools.add(tool.getKey());
            }
        }
        if (tools.isEmpty()) throw new AppGapException(403, WORKBENCH_FORBIDDEN, "没有该俱乐部的管理权限");
        Club club = clubService.selectClubById(clubId);
        if (club == null) throw AppGapException.notFound("CLUB_NOT_FOUND");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", club.getId());
        summary.put("name", club.getName());
        summary.put("logo", club.getLogo());
        summary.put("status", club.getStatus());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clubId", clubId);
        data.put("club", summary);
        data.put("isOwner", me.equals(club.getMemberId()));
        data.put("roleCodes", new ArrayList<>(access.getRoleCodes()));
        data.put("permissions", new ArrayList<>(access.getPermissions()));
        data.put("tools", tools);
        data.put("memberCount", clubMembers.countByClub(clubId));
        data.put("pendingJoinRequestCount", access.hasPermission(ClubPermission.MEMBER_APPROVE)
                ? clubMembers.countPendingJoinRequests(clubId) : null);
        return success(data);
    }

    @Operation(summary = "结算摘要导出 {clubId}：只读（club:finance:read），不出个人视图、不提现")
    @PostMapping("/api/club/settlements/export")
    public AjaxResult settlementsExport(@RequestBody(required = false) Map<String, Object> body) {
        Long me = member();
        Long clubId = id(body, "clubId");
        if (clubId == null) throw AppGapException.badRequest("clubId");
        require(me, clubId, null, ClubPermission.FINANCE_READ, SETTLEMENT_FORBIDDEN);
        Map<String, Object> scoped = new LinkedHashMap<>();
        scoped.put("clubId", clubId);
        AjaxResult summary = settlements.summary(scoped);
        Object code = summary.get(AjaxResult.CODE_TAG);
        if (code instanceof Number && ((Number) code).intValue() == 200 && summary.get(AjaxResult.DATA_TAG) instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) summary.get(AjaxResult.DATA_TAG);
            data.put("clubId", clubId);
            data.put("readOnly", true);
            data.put("exportedAt", System.currentTimeMillis());
        }
        return summary;
    }

    private void require(Long me, Long clubId, Long activityId, String permission, String errorCode) {
        try {
            clubAccess.require(me, clubId, activityId, permission);
        } catch (ServiceException e) {
            throw new AppGapException(403, errorCode, e.getMessage() == null ? "权限不足" : e.getMessage());
        }
    }

    private long appUid() {
        Long id = optionalMember();
        return id == null ? 0L : id;
    }

    private static Long id(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || value instanceof Boolean) return null;
        Long parsed = Convert.toLong(value, null);
        return parsed == null || parsed <= 0 ? null : parsed;
    }

    private static Map<String, String> tools() {
        Map<String, String> tools = new LinkedHashMap<>();
        tools.put("join_requests", ClubPermission.MEMBER_APPROVE);
        tools.put("members", ClubPermission.MEMBER_MANAGE);
        tools.put("event_ops", ClubPermission.ACTIVITY_MANAGE);
        tools.put("checkin", ClubPermission.EVENT_CHECKIN);
        tools.put("notices", ClubPermission.NOTIFY_SEND);
        tools.put("content", ClubPermission.CONTENT_MANAGE);
        tools.put("settlement", ClubPermission.FINANCE_READ);
        tools.put("roles", ClubPermission.ROLE_MANAGE);
        return java.util.Collections.unmodifiableMap(tools);
    }
}
