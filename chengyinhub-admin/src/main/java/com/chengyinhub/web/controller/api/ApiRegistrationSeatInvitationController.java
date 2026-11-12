package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.SeatInvitationActionRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationClaimRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationCreateRequest;
import com.chengyinhub.business.service.IRegistrationSeatInvitationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已付多人票「内含席位」邀请 HTTP 适配（P5）。
 *
 * <p>身份只来自登录态；客户端不能传 buyerId / recipientId 冒充归属。返回值不含
 * 受让人手机号等身份资料，明文邀请码只在签发/幂等重放时由服务端重算返回。
 */
@Tag(name = "玩法内含席位邀请")
@RestController
@RequestMapping("/api/play/seat-invitations")
public class ApiRegistrationSeatInvitationController extends BaseController {

    private final IRegistrationSeatInvitationService invitations;

    public ApiRegistrationSeatInvitationController(IRegistrationSeatInvitationService invitations) {
        this.invitations = invitations;
    }

    @Operation(summary = "购票人邀请他人进入原单内含席位（2..N）")
    @PostMapping("/invite")
    public AjaxResult invite(@RequestBody(required = false) SeatInvitationCreateRequest request) {
        return success(invitations.invite(requireMember(), request));
    }

    @Operation(summary = "购票人查看原单邀请历史与席位守恒计数")
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) Long registrationId) {
        return success(invitations.ownerView(requireMember(), registrationId));
    }

    @Operation(summary = "购票人或已领取受让人按邀请ID恢复结果")
    @GetMapping("/{invitationId}")
    public AjaxResult status(@PathVariable Long invitationId) {
        return success(invitations.status(requireMember(), invitationId));
    }

    @Operation(summary = "受让人登录后领取内含席位")
    @PostMapping("/claim")
    public AjaxResult claim(@RequestBody(required = false) SeatInvitationClaimRequest request) {
        return success(invitations.claim(requireMember(), request));
    }

    @Operation(summary = "购票人撤销待领取邀请（误邀）")
    @PostMapping("/{invitationId}/cancel")
    public AjaxResult cancel(@PathVariable Long invitationId,
                             @RequestBody(required = false) SeatInvitationActionRequest request) {
        SeatInvitationActionRequest body = request == null ? new SeatInvitationActionRequest() : request;
        body.setInvitationId(invitationId);
        return success(invitations.cancel(requireMember(), body));
    }

    @Operation(summary = "购票人释放已领取席位；受让人已开局游玩时拒绝")
    @PostMapping("/{invitationId}/release")
    public AjaxResult release(@PathVariable Long invitationId,
                              @RequestBody(required = false) SeatInvitationActionRequest request) {
        SeatInvitationActionRequest body = request == null ? new SeatInvitationActionRequest() : request;
        body.setInvitationId(invitationId);
        return success(invitations.release(requireMember(), body));
    }

    private long requireMember() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0L) {
            throw new ServiceException("请先登录", 403);
        }
        return memberId;
    }
}
