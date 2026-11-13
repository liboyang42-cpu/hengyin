package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelAdmissionService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 空间准入（docs/channels/contract.md §6.10）：入会申请/撤回/审核、退出、暂停入会、撤销邀请与原回执。 */
@RestController
@Tag(name = "空间准入")
public class ApiChannelAdmissionController extends BaseController
{
    @Autowired
    private ChannelAdmissionService admission;

    @Operation(summary = "提交入会申请")
    @PostMapping("/api/join-request/submit")
    public ResponseEntity<AjaxResult> submit(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.submit(me, body));
    }

    @Operation(summary = "撤回入会申请")
    @PostMapping("/api/join-request/cancel")
    public ResponseEntity<AjaxResult> cancel(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.cancel(me, body));
    }

    @Operation(summary = "通过入会申请（主理人/管理员/队长）")
    @PostMapping("/api/join-request/approve")
    public ResponseEntity<AjaxResult> approve(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.review(me, true, body));
    }

    @Operation(summary = "拒绝入会申请")
    @PostMapping("/api/join-request/reject")
    public ResponseEntity<AjaxResult> reject(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.review(me, false, body));
    }

    @Operation(summary = "退出空间")
    @PostMapping("/api/space/leave")
    public ResponseEntity<AjaxResult> leave(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.leave(me, body));
    }

    @Operation(summary = "暂停/恢复入会")
    @PostMapping("/api/space/join-pause")
    public ResponseEntity<AjaxResult> pause(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.pause(me, body));
    }

    @Operation(summary = "撤销邀请")
    @PostMapping("/api/invite/revoke")
    public ResponseEntity<AjaxResult> revoke(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.revokeInvite(me, body));
    }

    @Operation(summary = "准入原回执")
    @GetMapping("/api/channel-platform/admission/requests/{requestId}")
    public ResponseEntity<AjaxResult> receipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> admission.receipt(me, requestId));
    }
}
