package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelReceipts;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 频道目录与管理（App docs/channels/contract.md §1）。路径、字段名与错误码按 App 合同逐字对齐；
 * 写接口要求 X-Audit-Log-Reason 与 body.reason 一致，request_id 原回执可回读。
 */
@RestController
@Tag(name = "频道平台")
public class ApiChannelPlatformController extends BaseController
{
    private static final String REASON = "X-Audit-Log-Reason";

    @Autowired
    private ChannelPlatformService channels;

    @Operation(summary = "空间频道目录(分组)")
    @GetMapping("/api/guilds/{guildKind}/{guildId}/channels")
    public ResponseEntity<AjaxResult> directory(@PathVariable("guildKind") int guildKind, @PathVariable("guildId") String guildId,
            @RequestParam(value = "include_hidden", required = false) String includeHidden,
            @RequestParam(value = "with_unread", required = false) String withUnread)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.directory(me, guildKind, ChannelHttpResponse.id(guildId), "1".equals(withUnread)));
    }

    @Operation(summary = "频道管理清单(含归档/待清除)")
    @GetMapping("/api/guilds/{guildKind}/{guildId}/channels/manage")
    public ResponseEntity<AjaxResult> manage(@PathVariable("guildKind") int guildKind, @PathVariable("guildId") String guildId)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.manageInventory(me, guildKind, ChannelHttpResponse.id(guildId)));
    }

    @Operation(summary = "创建频道/分类")
    @PostMapping("/api/guilds/{guildKind}/{guildId}/channels")
    public ResponseEntity<AjaxResult> create(@PathVariable("guildKind") int guildKind, @PathVariable("guildId") String guildId,
            @RequestBody(required = false) Map<String, Object> body, @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.create(me, guildKind, ChannelHttpResponse.id(guildId), body, reason));
    }

    @Operation(summary = "频道详情")
    @GetMapping("/api/channels/{id}")
    public ResponseEntity<AjaxResult> detail(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.detail(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "编辑频道")
    @PatchMapping("/api/channels/{id}")
    public ResponseEntity<AjaxResult> edit(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.edit(me, ChannelHttpResponse.id(id), body, reason));
    }

    @Operation(summary = "删除频道(可恢复期内)")
    @DeleteMapping("/api/channels/{id}")
    public ResponseEntity<AjaxResult> delete(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.delete(me, ChannelHttpResponse.id(id), body, reason));
    }

    @Operation(summary = "归档/取消归档")
    @PostMapping("/api/channels/{id}/archive")
    public ResponseEntity<AjaxResult> archive(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.archive(me, ChannelHttpResponse.id(id), body, reason));
    }

    @Operation(summary = "恢复已删除频道")
    @PostMapping("/api/channels/{id}/restore")
    public ResponseEntity<AjaxResult> restore(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.restore(me, ChannelHttpResponse.id(id), body, reason));
    }

    @Operation(summary = "频道权限覆盖")
    @GetMapping("/api/channels/{id}/permissions")
    public ResponseEntity<AjaxResult> permissions(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.permissions(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "设置权限覆盖")
    @PutMapping("/api/channels/{id}/permissions/{targetType}/{targetId}")
    public ResponseEntity<AjaxResult> putOverwrite(@PathVariable("id") String id, @PathVariable("targetType") int targetType,
            @PathVariable("targetId") String targetId, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.putOverwrite(me, ChannelHttpResponse.id(id), targetType, targetId, body, reason));
    }

    @Operation(summary = "删除权限覆盖")
    @DeleteMapping("/api/channels/{id}/permissions/{targetType}/{targetId}")
    public ResponseEntity<AjaxResult> deleteOverwrite(@PathVariable("id") String id, @PathVariable("targetType") int targetType,
            @PathVariable("targetId") String targetId, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = REASON, required = false) String reason)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.deleteOverwrite(me, ChannelHttpResponse.id(id), targetType, targetId, body, reason));
    }

    @Operation(summary = "成员在频道的有效权限")
    @GetMapping("/api/channels/{id}/effective/{memberId}")
    public ResponseEntity<AjaxResult> effective(@PathVariable("id") String id, @PathVariable("memberId") String memberId)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.effective(me, ChannelHttpResponse.id(id), ChannelHttpResponse.id(memberId)));
    }

    @Operation(summary = "频道操作日志")
    @GetMapping("/api/channels/{id}/op-log")
    public ResponseEntity<AjaxResult> opLog(@PathVariable("id") String id,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "before_id", required = false) String beforeId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.opLog(me, ChannelHttpResponse.id(id),
                beforeId == null ? null : ChannelHttpResponse.id(beforeId), limit));
    }

    @Operation(summary = "频道成员(可见者)")
    @GetMapping("/api/channels/{id}/members")
    public ResponseEntity<AjaxResult> members(@PathVariable("id") String id,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.members(me, ChannelHttpResponse.id(id), cursor, limit));
    }

    @Operation(summary = "空间角色")
    @GetMapping("/api/im/roles")
    public ResponseEntity<AjaxResult> roles(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId,
            @RequestParam(value = "include_disabled", defaultValue = "false") boolean includeDisabled)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.roles(me, guildKind, ChannelHttpResponse.id(guildId), includeDisabled));
    }

    @Operation(summary = "频道元数据原请求回执")
    @GetMapping("/api/channel-platform/requests/{requestId}")
    public ResponseEntity<AjaxResult> metadataReceipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.receipt(me, ChannelReceipts.META, requestId));
    }

    @Operation(summary = "频道权限原请求回执")
    @GetMapping("/api/channel-platform/governance/requests/{requestId}")
    public ResponseEntity<AjaxResult> governanceReceipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channels.receipt(me, ChannelReceipts.GOV, requestId));
    }

}
