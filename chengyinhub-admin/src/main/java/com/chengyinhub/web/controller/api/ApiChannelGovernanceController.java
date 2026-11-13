package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelGovernanceService;
import com.chengyinhub.business.service.channel.platform.ChannelReceipts;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.friend.FriendProofs;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 频道治理（App docs/channels/contract.md §6.2–§6.6）：处置/申诉/举报/本人状态/角色写及其原回执。
 * 信封同频道合同：确定性拒绝 HTTP 状态 == body.code；结果不确定 500 + OUTCOME_UNKNOWN。
 */
@RestController
@Tag(name = "频道治理")
public class ApiChannelGovernanceController extends BaseController
{
    @Autowired
    private ChannelGovernanceService governance;

    // ---------------------------------------------------------------- moderation

    @Operation(summary = "禁言/踢出/封禁")
    @PostMapping("/api/im/moderation/{operation:timeout|kick|ban}")
    public ResponseEntity<AjaxResult> moderate(@PathVariable("operation") String operation, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.moderate(me, operation, body));
    }

    @Operation(summary = "撤销禁言/封禁")
    @PostMapping("/api/im/moderation/{actionId:[0-9]+}/revoke")
    public ResponseEntity<AjaxResult> revoke(@PathVariable("actionId") String actionId, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.revoke(me, ChannelHttpResponse.id(actionId), body));
    }

    @Operation(summary = "空间处置记录（VIEW_AUDIT）")
    @GetMapping("/api/im/moderation/actions")
    public ResponseEntity<AjaxResult> actions(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId,
            @RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "cursor", required = false) String cursor)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.actions(me, guildKind, ChannelHttpResponse.id(guildId), cursor, limit));
    }

    @Operation(summary = "我收到的处置")
    @GetMapping("/api/im/moderation/me")
    public ResponseEntity<AjaxResult> mine(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.mine(me, guildKind, ChannelHttpResponse.id(guildId)));
    }

    @Operation(summary = "处置/申诉原回执")
    @GetMapping("/api/channel-platform/governance/moderation/requests/{requestId}")
    public ResponseEntity<AjaxResult> moderationReceipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.moderationReceipt(me, requestId));
    }

    // ---------------------------------------------------------------- appeals

    @Operation(summary = "提交申诉")
    @PostMapping("/api/im/appeals")
    public ResponseEntity<AjaxResult> submitAppeal(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.submitAppeal(me, body));
    }

    @Operation(summary = "申诉列表（本人；VIEW_AUDIT 看全部）")
    @GetMapping("/api/im/appeals")
    public ResponseEntity<AjaxResult> appeals(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.appeals(me, guildKind, ChannelHttpResponse.id(guildId)));
    }

    @Operation(summary = "申诉详情")
    @GetMapping("/api/im/appeals/{id:[0-9]+}")
    public ResponseEntity<AjaxResult> appeal(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.appeal(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "补充申诉证据")
    @PostMapping("/api/im/appeals/{id:[0-9]+}/evidence")
    public ResponseEntity<AjaxResult> evidence(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.appealEvidence(me, ChannelHttpResponse.id(id), body));
    }

    @Operation(summary = "撤回申诉")
    @PostMapping("/api/im/appeals/{id:[0-9]+}/withdraw")
    public ResponseEntity<AjaxResult> withdraw(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.appealWithdraw(me, ChannelHttpResponse.id(id), body));
    }

    // ---------------------------------------------------------------- reports

    /** JSON 原请求（表单旧版仍由 ApiImController.report 处理）；body_digest 取原始字节的 SHA-256。 */
    @Operation(summary = "举报频道消息（JSON 原请求）")
    @PostMapping(value = "/api/im/report", consumes = "application/json")
    public ResponseEntity<AjaxResult> report(@RequestBody(required = false) byte[] raw)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me ->
        {
            if (raw == null || raw.length == 0 || raw.length > 8192) throw ChannelRejection.badField("body");
            Map<String, Object> body;
            try { body = ChannelReceipts.decode(new String(raw, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (IllegalStateException e) { throw ChannelRejection.badField("body"); }
            return governance.report(me, body, FriendProofs.sha256(raw));
        });
    }

    @Operation(summary = "举报原回执")
    @GetMapping("/api/channel-platform/governance/reports/requests/{requestId}")
    public ResponseEntity<AjaxResult> reportReceipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.reportReceipt(me, requestId));
    }

    // ---------------------------------------------------------------- personal state

    @Operation(summary = "本人频道状态（置顶/隐藏/静音）")
    @PostMapping("/api/channels/{id}/member-state")
    public ResponseEntity<AjaxResult> memberState(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.setMemberState(me, ChannelHttpResponse.id(id), body));
    }

    @Operation(summary = "本人频道状态原回执")
    @GetMapping("/api/channels/member-state-requests/{requestId}")
    public ResponseEntity<AjaxResult> memberStateReceipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.memberStateReceipt(me, requestId));
    }

    // ---------------------------------------------------------------- role writes

    @Operation(summary = "创建角色（MANAGE_ROLE）")
    @PostMapping("/api/im/roles")
    public ResponseEntity<AjaxResult> createRole(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.createRole(me, body));
    }

    @Operation(summary = "编辑角色")
    @PatchMapping("/api/im/roles/{id:[0-9]+}")
    public ResponseEntity<AjaxResult> editRole(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.editRole(me, ChannelHttpResponse.id(id), body));
    }

    @Operation(summary = "删除角色（同时撤销全部授予）")
    @DeleteMapping("/api/im/roles/{id:[0-9]+}")
    public ResponseEntity<AjaxResult> deleteRole(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.deleteRole(me, ChannelHttpResponse.id(id), body));
    }

    @Operation(summary = "设置成员的完整角色集合")
    @RequestMapping(value = "/api/im/members/{memberId:[0-9]+}/roles", method = { RequestMethod.PUT, RequestMethod.POST })
    public ResponseEntity<AjaxResult> memberRoles(@PathVariable("memberId") String memberId, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.setMemberRoles(me, ChannelHttpResponse.id(memberId), body));
    }

    @Operation(summary = "设置角色的完整成员集合")
    @RequestMapping(value = "/api/im/roles/{id:[0-9]+}/members", method = { RequestMethod.PUT, RequestMethod.POST })
    public ResponseEntity<AjaxResult> roleMembers(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> governance.setRoleMembers(me, ChannelHttpResponse.id(id), body));
    }
}
