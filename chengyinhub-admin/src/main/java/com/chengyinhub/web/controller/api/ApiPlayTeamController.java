package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.service.impl.TeamEventCommandService;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.ITeamTrailService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 玩家组队:邀请制 + 公开申请制(地图附近队伍)。队伍与报名订单只做建队/入队资格校验，不承载退款动作。 */
@RestController
@Tag(name = "玩家组队")
@RequestMapping("/api/team")
public class ApiPlayTeamController extends BaseController {

    @Autowired
    private TeamEventCommandService commandReceipts;

    @PostMapping("/command")
    public AjaxResult command(@RequestBody TeamEventCommand input) {
        Long actor = memberId();

        return AjaxResult.success(commandReceipts.execute(actor, "TEAM", input));
    }

    @PostMapping("/command/query")
    public AjaxResult commandQuery(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.query(memberId(), "TEAM", input == null ? null : input.getRequestId()));
    }

    @PostMapping("/command/cancel")
    public AjaxResult commandCancel(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.cancel(memberId(), "TEAM", input));
    }

    @Autowired
    private IPlayTeamService playTeamService;
    private Long memberId() {
        return getAppUserId();
    }

    private Long optionalMemberId() {
        try { return getAppUserId(); } catch (Exception ignored) { return 0L; }
    }

    @Operation(summary = "创建邀请制队伍")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody Map<String, Object> body) {
        try {
            Long uid = memberId();
            PlayTeam team = playTeamService.create(uid,
                    Convert.toInt(body.get("ownerType"), 0),
                    Convert.toLong(body.get("ownerId"), 0L), Convert.toStr(body.get("title"), null),
                    body.get("maxMembers") == null ? null : Convert.toInt(body.get("maxMembers"), 4),
                    body.get("joinMode") == null ? null : Convert.toInt(body.get("joinMode"), 0));
            Map<String, Object> result = new HashMap<>();
            result.put("teamId", team.getId());
            result.put("team", team);
            return AjaxResult.success("队伍已创建", result);
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "队长切换组队方式(1=仅邀请 2=公开)")
    @PostMapping("/join-mode")
    public AjaxResult updateJoinMode(@RequestBody Map<String, Object> body) {
        try {
            int mode = playTeamService.updateJoinMode(Convert.toLong(body.get("teamId"), 0L), memberId(),
                    body.get("joinMode") == null ? null : Convert.toInt(body.get("joinMode"), 0));
            Map<String, Object> result = new HashMap<>();
            result.put("joinMode", mode);
            return AjaxResult.success("组队方式已更新", result);
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "队伍详情（成员或持邀请码可看）")
    @Anonymous
    @PostMapping("/info")
    public AjaxResult info(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> input = body == null ? Collections.emptyMap() : body;
            return AjaxResult.success(playTeamService.info(
                    Convert.toLong(input.get("teamId"), 0L),
                    Convert.toStr(input.get("inviteCode"), null), optionalMemberId()));
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "通过邀请加入队伍")
    @PostMapping("/join")
    public AjaxResult join(@RequestBody Map<String, Object> body) {
        try {
            PlayTeam team = playTeamService.join(Convert.toStr(body.get("inviteCode"), null), memberId());
            return AjaxResult.success("已加入队伍", team);
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "地图附近可申请的公开队伍(不含邀请码)")
    @GetMapping("/nearby")
    public AjaxResult nearby(Double lat, Double lng, Integer radius) {
        if (lat == null || lng == null) return error("缺少定位");
        try {
            return AjaxResult.success(playTeamService.nearby(memberId(), lat, lng, radius == null ? 0 : radius));
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "申请加入公开队伍(需持本场次有效票)")
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody Map<String, Object> body) {
        try {
            Date expireAt = playTeamService.apply(Convert.toLong(body.get("teamId"), 0L), memberId(),
                    Convert.toStr(body.get("message"), null));
            Map<String, Object> result = new HashMap<>();
            result.put("applyExpireTime", expireAt);
            return AjaxResult.success("已申请，等待队长同意", result);
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "撤回入队申请")
    @PostMapping("/withdraw")
    public AjaxResult withdraw(@RequestBody Map<String, Object> body) {
        try {
            playTeamService.withdraw(Convert.toLong(body.get("teamId"), 0L), memberId());
            return AjaxResult.success("已撤回申请");
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "队长查看待审申请")
    @PostMapping("/applications")
    public AjaxResult applications(@RequestBody Map<String, Object> body) {
        try {
            return AjaxResult.success(playTeamService.applications(Convert.toLong(body.get("teamId"), 0L), memberId()));
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "我的入队申请(申请中 / 队长未同意)")
    @PostMapping("/my-applications")
    public AjaxResult myApplications() {
        try {
            return AjaxResult.success(playTeamService.myApplications(memberId()));
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "举报队伍(进审核工作台,审核期间不出现在地图)")
    @PostMapping("/report")
    public AjaxResult report(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", playTeamService.report(Convert.toLong(body.get("teamId"), 0L), memberId(),
                    Convert.toStr(body.get("reason"), null)));
            return AjaxResult.success("已提交举报", result);
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "队长处理待审核成员")
    @PostMapping("/handle")
    public AjaxResult handle(@RequestBody Map<String, Object> body) {
        try {
            playTeamService.handle(Convert.toLong(body.get("teamId"), 0L), memberId(),
                    Convert.toLong(body.get("memberId"), 0L), approved(body.get("approved")));
            return AjaxResult.success("已处理");
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "退出队伍")
    @PostMapping("/quit")
    public AjaxResult quit(@RequestBody Map<String, Object> body) {
        try {
            playTeamService.quit(Convert.toLong(body.get("teamId"), 0L), memberId());
            return AjaxResult.success("已退出队伍");
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "队长移出成员")
    @PostMapping("/kick")
    public AjaxResult kick(@RequestBody Map<String, Object> body) {
        try {
            playTeamService.kick(Convert.toLong(body.get("teamId"), 0L), memberId(),
                    Convert.toLong(body.get("memberId"), 0L));
            return AjaxResult.success("成员已移出");
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "队长解散队伍")
    @PostMapping("/disband")
    public AjaxResult disband(@RequestBody Map<String, Object> body) {
        try {
            playTeamService.disband(Convert.toLong(body.get("teamId"), 0L), memberId());
            return AjaxResult.success("队伍已解散");
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Operation(summary = "我的队伍")
    @PostMapping("/my")
    public AjaxResult my() {
        try {
            return AjaxResult.success(playTeamService.my(memberId()));
        } catch (RuntimeException e) {
            return fail(e);
        }
    }

    @Autowired
    private ITeamTrailService teamTrailService;

    /* 第九轮 m2 第 2 项:同场队伍路线。只认登录的本人;是否这一场已付费、作者是否关掉都在服务里判,
       不满足一律只回 available=false,不回任何队伍。 */
    @Operation(summary = "同场路线:我能否使用与当前分享表态")
    @GetMapping("/trail/state")
    public AjaxResult trailState(Long activityId) {
        Long me = optionalMemberId();
        if (me == null || me <= 0L) return error("请先登录");
        return AjaxResult.success(teamTrailService.state(me, activityId));
    }

    @Operation(summary = "同场路线:开局前本人表态(关掉即删除本人已存的点)")
    @PostMapping("/trail/consent")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    public AjaxResult trailConsent(@RequestBody Map<String, Object> body) {
        Long me = optionalMemberId();
        if (me == null || me <= 0L) return error("请先登录");
        Long activityId = body == null ? null : Convert.toLong(body.get("activityId"), null);
        return AjaxResult.success(teamTrailService.consent(me, activityId, body != null && approved(body.get("share"))));
    }

    @Operation(summary = "同场路线:上报本人新走的点并取回同场其他队伍(约 12 秒一次)")
    @PostMapping("/trail/sync")
    @RateLimiter(time = 60, count = 12, limitType = LimitType.MEMBER)
    @SuppressWarnings("unchecked")
    public AjaxResult trailSync(@RequestBody Map<String, Object> body) {
        Long me = optionalMemberId();
        if (me == null || me <= 0L) return error("请先登录");
        if (body == null) return AjaxResult.success(teamTrailService.state(me, null));
        Long activityId = Convert.toLong(body.get("activityId"), null);
        List<List<Double>> pts = new java.util.ArrayList<>();
        Object raw = body.get("pts");
        if (raw instanceof List) {
            for (Object row : (List<Object>) raw) {
                if (!(row instanceof List) || ((List<Object>) row).size() < 2) continue;
                List<Object> pair = (List<Object>) row;
                Double lat = Convert.toDouble(pair.get(0), null), lng = Convert.toDouble(pair.get(1), null);
                if (lat != null && lng != null) pts.add(java.util.Arrays.asList(lat, lng));
                if (pts.size() >= 40) break;
            }
        }
        Integer done = Convert.toInt(body.get("done"), 0);
        return AjaxResult.success(teamTrailService.sync(me, activityId, pts, done, approved(body.get("finished"))));
    }

    private AjaxResult fail(RuntimeException e) {
        AjaxResult result = error(e.getMessage());
        if (e instanceof IPlayTeamService.TeamRejection) {
            result.put("errorCode", ((IPlayTeamService.TeamRejection) e).getErrorCode());
        }
        return result;
    }

    private boolean approved(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }
}
