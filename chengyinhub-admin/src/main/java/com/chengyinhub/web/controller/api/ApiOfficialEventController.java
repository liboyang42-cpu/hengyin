package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.service.impl.TeamEventCommandService;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2OrganizerInviteRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2OrganizerTransferRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2PartyActionRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2PauseRequest;
import com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.NotActiveMerchantException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.service.ISysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方活动系统 · 小程序端接口(玩家 + 白名单发布者)。
 * 设计真源:vault《Questify官方活动系统_产品设计报告_2026-07-08》。
 *
 * @author cy
 * @date 2026-07-08
 */
@RestController
@Tag(name = "官方活动")
@RequestMapping("/api/official")
public class ApiOfficialEventController extends BaseController {

    @Autowired
    private com.chengyinhub.business.service.impl.OfficialEventAttendanceService attendance;

    @GetMapping("/events/{id}/attendance")
    public AjaxResult attendance(@PathVariable Long id) { return success(attendance.read(id,uid(),false)); }

    @GetMapping("/events/{id}/attendance/manage")
    public AjaxResult attendanceManage(@PathVariable Long id) {
        if (!canPublish(uid())) return error("无官方发布权限");
        return success(attendance.read(id,uid(),true));
    }

    @Autowired
    private TeamEventCommandService commandReceipts;

    @PostMapping("/command")
    public AjaxResult command(@RequestBody TeamEventCommand input) {
        Long actor = uid();
        if (!canPublish(actor)) return error("无官方发布权限");
        return AjaxResult.success(commandReceipts.execute(actor, "EVENT", input));
    }

    @PostMapping("/command/query")
    public AjaxResult commandQuery(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.query(uid(), "EVENT", input == null ? null : input.getRequestId()));
    }

    @PostMapping("/command/cancel")
    public AjaxResult commandCancel(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.cancel(uid(), "EVENT", input));
    }

    // Same player authorization as the legacy signup route, never the organizer whitelist.
    @PostMapping("/signup-command")
    public AjaxResult signupCommand(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.execute(uid(), "SIGNUP", input));
    }

    @PostMapping("/signup-command/query")
    public AjaxResult signupCommandQuery(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.query(uid(), "SIGNUP", input == null ? null : input.getRequestId()));
    }

    @PostMapping("/signup-command/cancel")
    public AjaxResult signupCommandCancel(@RequestBody TeamEventCommand input) {
        return AjaxResult.success(commandReceipts.cancel(uid(), "SIGNUP", input));
    }

    @Autowired
    private IOfficialEventService officialEventService;
    @Autowired
    private IOfficialEventV2Service officialEventV2Service;
    @Autowired
    private IOfficialBroadcastService officialBroadcastService;
    @Autowired
    private ISysConfigService configService;
    @Autowired
    private IWxContentSecurityService wxSec;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private ICoopInviteService coopInviteService;
    @Autowired
    private INotifyService notifyService;
    @Autowired
    private IContentSafetyGate contentSafetyGate;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    private String openidOf(Long memberId) {
        try {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null ? null : m.getWxOpenId();
        } catch (Exception e) { return null; }
    }

    // ===================== 玩家侧 =====================

    @Operation(summary = "官方活动列表(进行中/即将/已结束)")
    @GetMapping("/events")
    public AjaxResult events(@RequestParam(required = false) String city) {
        return success(officialEventService.listPublic(city, uid()));
    }

    @Operation(summary = "官方活动详情")
    @GetMapping("/events/{id}")
    public AjaxResult detail(@PathVariable("id") Long id) {
        Map<String, Object> vo = officialEventService.detail(id, uid());
        if (vo == null) return error("活动不存在");
        return success(vo);
    }

    @Operation(summary = "报名(一键,不占额度,幂等)")
    @PostMapping("/events/{id}/signup")
    public AjaxResult signup(@PathVariable("id") Long id) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        String err = officialEventService.signup(id, memberId);
        return err == null ? success("报名成功") : error(err);
    }

    @Operation(summary = "旧版完成入口提示（不写进度或奖励）")
    @PostMapping("/events/{id}/complete")
    public AjaxResult complete(@PathVariable("id") Long id) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        String err = officialEventService.completeTask(id, memberId);
        return err == null ? success() : error(err);
    }

    @Operation(summary = "集体进度")
    @GetMapping("/events/{id}/progress")
    public AjaxResult progress(@PathVariable("id") Long id) {
        return success(officialEventService.progress(id));
    }

    @Operation(summary = "我报名的活动")
    @GetMapping("/my-events")
    public AjaxResult myEvents() {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        return success(officialEventService.myEvents(memberId));
    }

    @Operation(summary = "可点亮的官方活动(roam 绑定 · 报名中/进行中)")
    @GetMapping("/active-for-roam")
    public AjaxResult activeForRoam(@RequestParam(required = false) String city) {
        return success(officialEventService.activeForRoam(city, uid()));
    }

    @Operation(summary = "V2 活动到达验证(服务端事实→evidence)")
    @PostMapping("/events/{id}/arrivals")
    public AjaxResult verifyArrival(@PathVariable("id") Long id, @RequestBody OfficialEventV2ArrivalRequest request) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        return success(officialEventV2Service.verifyArrival(id, memberId, request));
    }

    @Operation(summary = "商家/俱乐部接受、拒绝或退出本场活动邀约")
    @PostMapping("/v2/parties/{partyId}/{action}")
    public AjaxResult respondParty(@PathVariable("partyId") Long partyId, @PathVariable("action") String action,
                                   @RequestBody(required = false) OfficialEventV2PartyActionRequest request) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        String err = officialEventV2Service.respondParty(partyId, memberId, action, request == null ? null : request.getReason());
        return err == null ? success() : error(err);
    }

    @Operation(summary = "接受官方主办邀约(仅受邀白名单会员)")
    @PostMapping("/v2/organizer-invites/{partyId}/accept")
    public AjaxResult acceptOrganizerInvitation(@PathVariable("partyId") Long partyId,
                                                 @RequestBody(required = false) OfficialEventV2PartyActionRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.acceptOrganizerInvitation(partyId, memberId, request == null ? null : request.getReason());
        return err == null ? success("已接受主办邀约") : error(err);
    }

    @Operation(summary = "拒绝官方主办邀约(仅受邀白名单会员)")
    @PostMapping("/v2/organizer-invites/{partyId}/decline")
    public AjaxResult declineOrganizerInvitation(@PathVariable("partyId") Long partyId,
                                                  @RequestBody(required = false) OfficialEventV2PartyActionRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.declineOrganizerInvitation(partyId, memberId, request == null ? null : request.getReason());
        return err == null ? success("已拒绝主办邀约") : error(err);
    }

    @Operation(summary = "我的官方活动承接邀约")
    @GetMapping("/v2/party-inbox")
    public AjaxResult partyInbox() {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        return success(officialEventV2Service.partyInbox(memberId));
    }

    @Operation(summary = "官方通知点击回流")
    @PostMapping("/broadcast/{id}/click")
    public AjaxResult click(@PathVariable("id") Long id, @RequestParam(required = false) String channel) {
        Long memberId = uid();
        if (memberId <= 0) return success();
        officialBroadcastService.markClicked(id, memberId, channel);
        return success();
    }

    // ===================== 白名单发布者侧 =====================

    @Operation(summary = "官方批量邀请商家承接")
    @PostMapping("/invites")
    public AjaxResult inviteMerchants(@RequestBody OfficialMerchantInviteRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        if (request == null) return error("官方邀约参数不完整");
        try {
            List<Long> ids = coopInviteService.createOfficialMerchantInvites(memberId, request);
            if (request.getMerchantIds() != null) {
                for (Long merchantId : request.getMerchantIds()) {
                    try { notifyService.notifyCoopInvited(merchantId, "官方活动", "官方"); } catch (Exception ignore) {}
                }
            }
            return AjaxResult.success("邀约已发送", ids);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "官方邀约跟踪列表")
    @GetMapping("/invites")
    public AjaxResult officialInvites(@RequestParam(required = false) Integer status) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        CoopInvite query = new CoopInvite();
        query.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        query.setFromType(CoopInvite.FROM_OFFICIAL);
        query.setFromId(memberId);
        query.setStatus(status);
        return success(coopInviteService.selectCoopInviteList(query));
    }

    @Operation(summary = "当前商家收到的官方活动邀约")
    @GetMapping("/merchant-invites")
    public AjaxResult merchantInvites() {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        try {
            return success(coopInviteService.listOfficialMerchantInvites(memberId));
        } catch (NotActiveMerchantException e) {
            // 稳定机器码:协作邀请页对俱乐部主理人也会拉这条,「不是商家」= 本来没有官方邀约,不是故障
            AjaxResult notMerchant = new AjaxResult(HttpStatus.FORBIDDEN, e.getMessage());
            notMerchant.put("errorCode", NotActiveMerchantException.ERROR_CODE);
            return notMerchant;
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "轻发布:创建官方活动(白名单)")
    @PostMapping("/publish")
    public AjaxResult publish(@RequestBody OfficialEvent event) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        if (event == null) return error("活动不能为空");
        // 2026-10-20 分级审核:官方活动先审后发。标题/副标题/故事过统一门禁(PRE_REVIEW),明确违规直接拒;
        // 其余一律落待审并建 OFFICIAL_EVENT 审核单,审核台通过后才公开。
        PublishSafetyRequest safety = new PublishSafetyRequest();
        safety.setContentType(AuditTask.BIZ_OFFICIAL_EVENT);
        safety.setMemberId(memberId);
        safety.setOpenid(openidOf(memberId));
        safety.setText(IWxContentSecurityService.joinText(event.getTitle(), event.getSubtitle(), event.getStory()));
        safety.setReviewMode(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name());
        SafetyDecision decision = contentSafetyGate.decide(safety);
        if (decision == null || decision.isBlock()) return error("活动内容包含违规信息，请修改后重试");
        OfficialEvent saved = officialEventService.submitForReview(event, memberId);
        // 封面图异步内容安全送检(BIZ_TYPE_OFFICIAL_EVENT,违规经回调告警人工下架)
        if (event.getCoverImg() != null && !event.getCoverImg().trim().isEmpty()) {
            try { wxSec.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT, saved.getId(), event.getCoverImg(), openidOf(memberId)); } catch (Exception ignore) {}
        }
        return AjaxResult.success("已提交审核，审核通过后公开", saved.getId());
    }

    @Operation(summary = "V2 活动草稿保存(白名单发布者)")
    @PostMapping("/v2/drafts")
    public AjaxResult saveV2Draft(@RequestBody OfficialEventV2DraftRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        if (request == null || request.getEvent() == null) return error("活动草稿不能为空");
        if (request.getEvent().getId() != null && !ownsV2Event(request.getEvent().getId(), memberId)) return error("无权修改该活动");
        request.getEvent().setSenderAccount(memberId);
        OfficialEvent saved = officialEventV2Service.saveDraft(request, memberId);
        return AjaxResult.success("草稿已保存", saved.getId());
    }

    @Operation(summary = "V2 活动发布检查")
    @GetMapping("/v2/events/{id}/publish-check")
    public AjaxResult v2PublishCheck(@PathVariable("id") Long id) {
        Long memberId = uid();
        if (!canPublish(memberId) || !officialEventV2Service.canReadControlledEvent(id, memberId)) return error("无权查看该活动发布检查");
        return success(officialEventV2Service.checkPublishableForOperator(id, memberId));
    }

    @Operation(summary = "V2 活动提交排期")
    @PostMapping("/v2/events/{id}/schedule")
    public AjaxResult scheduleV2(@PathVariable("id") Long id) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.schedule(id, memberId);
        return err == null ? success("已提交审核，审核通过后进入排期") : error(err);
    }

    @Operation(summary = "邀请候任主办方(仅当前有效主办)")
    @PostMapping("/v2/events/{id}/organizer-invites")
    public AjaxResult inviteOrganizer(@PathVariable("id") Long id, @RequestBody OfficialEventV2OrganizerInviteRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.inviteOrganizer(id, memberId,
                request == null ? null : request.getMemberId(), request == null ? null : request.getReason());
        return err == null ? success("主办邀约已发送") : error(err);
    }

    @Operation(summary = "主办方受控转交(仅当前有效主办)")
    @PostMapping("/v2/events/{id}/organizer-transfer")
    public AjaxResult transferOrganizer(@PathVariable("id") Long id, @RequestBody OfficialEventV2OrganizerTransferRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.transferOrganizer(id, memberId,
                request == null ? null : request.getPartyId(), request == null ? null : request.getReason());
        return err == null ? success("主办方已转交") : error(err);
    }

    @Operation(summary = "V2 活动暂停(仅当前有效主办)")
    @PostMapping("/v2/events/{id}/pause")
    public AjaxResult pauseV2(@PathVariable("id") Long id, @RequestBody OfficialEventV2PauseRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.pause(id, memberId, request == null ? null : request.getReason(),
                request == null ? null : request.getImpactScope(), request == null ? null : request.getAlternativePlan(),
                request == null ? null : request.getNotificationPlan());
        return err == null ? success("活动已暂停") : error(err);
    }

    @Operation(summary = "V2 活动恢复(仅当前有效主办)")
    @PostMapping("/v2/events/{id}/resume")
    public AjaxResult resumeV2(@PathVariable("id") Long id,
                               @RequestBody(required = false) OfficialEventV2PartyActionRequest request) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        String err = officialEventV2Service.resume(id, memberId, request == null ? null : request.getReason());
        return err == null ? success("活动已恢复") : error(err);
    }

    @PostMapping("/broadcast-request/cancel")
    public AjaxResult cancelBroadcastCreation(@RequestBody java.util.Map<String,String> input){
        if(!canPublish(uid()))return error("无官方发布权限");return success(officialBroadcastService.cancelCreation("MEMBER:"+uid(),input.get("requestId")));
    }

    @GetMapping("/broadcast-request")
    public AjaxResult broadcastRequest(@RequestParam String requestId){
        if(!canPublish(uid()))return error("无官方发布权限");
        return success(officialBroadcastService.creationReceipt("MEMBER:"+uid(),requestId));
    }

    @Operation(summary = "发官方通知/公告(白名单):勾选玩家/俱乐部/商家,一起或分开发")
    @PostMapping("/broadcast")
    public AjaxResult broadcast(@RequestBody OfficialBroadcast bc) {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        if (bc == null) return error("通知不能为空");
        bc.setRequestOwner("MEMBER:"+memberId);
        OfficialEvent event = null;
        if (bc.getEventId() != null) {
            event = officialEventService.adminGet(bc.getEventId());
            if (event == null) return error("活动不存在");
        }
        // 内容安全:标题 + 文案(含分角色文案 JSON)
        String sec = wxSec.checkText(
                IWxContentSecurityService.joinText(bc.getTitle(), bc.getContentJson()),
                openidOf(memberId));
        if (sec != null) return error(sec);
        if (event != null && event.getContractVersion() != null && event.getContractVersion() >= 2) {
            OfficialBroadcast saved = officialEventV2Service.publishBroadcastForOperator(bc, memberId);
            return AjaxResult.success("通知已提交", saved.getId());
        }
        bc.setSenderAccount(memberId);
        OfficialBroadcast saved = officialBroadcastService.publish(bc, memberId);
        return AjaxResult.success("通知已提交", saved.getId());
    }

    @PostMapping("/broadcast/{id}/recover")
    public AjaxResult recoverBroadcast(@PathVariable Long id) {
        Long actor=uid();if(!canPublish(actor))return error("无官方发布权限");
        return success(officialBroadcastService.recover(id,actor));
    }

    @Operation(summary = "通知触达/点击复盘")
    @GetMapping("/broadcast/{id}/stats")
    public AjaxResult broadcastStats(@PathVariable("id") Long id) {
        Long me = uid();
        if (!canPublish(me)) return error("无官方发布权限");
        // 归属校验:仅发布者本人可看自己通知的复盘,防白名单内互看他人数据
        OfficialBroadcast bc = officialBroadcastService.getById(id, me);
        if (bc == null) return error("通知不存在");
        if (bc.getEventId() != null) {
            OfficialEvent event = officialEventService.adminGet(bc.getEventId());
            if (event == null) return error("活动不存在");
            if (event.getContractVersion() != null && event.getContractVersion() >= 2) {
                return success(officialEventV2Service.broadcastStatsForOperator(id, me));
            }
        }
        if (bc.getSenderAccount() == null || !bc.getSenderAccount().equals(me)) {
            return error("无权查看该通知数据");
        }
        return success(officialBroadcastService.reachStats(id, me));
    }

    @Operation(summary = "我发布的活动 + 通知")
    @GetMapping("/my-published")
    public AjaxResult myPublished() {
        Long memberId = uid();
        if (!canPublish(memberId)) return error("无官方发布权限");
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("events", officialEventService.listBySender(memberId));
        data.put("broadcasts", officialBroadcastService.listBySender(memberId, memberId));
        return success(data);
    }

    @Operation(summary = "当前账号是否有官方发布权限")
    @GetMapping("/can-publish")
    public AjaxResult canPublishInfo() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("canPublish", canPublish(uid()));
        return success(data);
    }

    /** 白名单:sys_config 键 official_publish_whitelist = 逗号分隔 memberId。空=全部拒绝。 */
    private boolean canPublish(Long memberId) {
        if (memberId == null || memberId <= 0) return false;
        String wl = configService.selectConfigByKey("official_publish_whitelist");
        if (StringUtils.isEmpty(wl)) return false;
        String me = String.valueOf(memberId);
        for (String s : wl.split(",")) {
            if (me.equals(s.trim())) return true;
        }
        return false;
    }

    private boolean ownsV2Event(Long eventId, Long memberId) {
        return memberId != null && officialEventV2Service.canOperateControlledEvent(eventId, memberId);
    }
}
