package com.chengyinhub.web.controller.api;

import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelAuditLogService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 频道缺口包（App BeGapImDesk / NativeChannelAuditFlow / NativeWebhook*）：空间频道清单、空间审计、频道 Webhook。
 * 信封同频道合同（ChannelHttpResponse）；Webhook 族另带 err（= errorCode），App NativeWebhookHTTPService 读这个字段。
 */
@RestController
@Tag(name = "频道缺口")
public class ApiChannelGapController extends BaseController
{
    @Autowired
    private ChannelPlatformService channels;
    @Autowired
    private ChannelAuditLogService audit;
    @Autowired
    private ChannelWebhookService webhooks;

    @Operation(summary = "空间频道清单（与 /api/guilds/{kind}/{id}/channels 同源）")
    @GetMapping("/api/channels")
    public ResponseEntity<AjaxResult> channels(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId,
            @RequestParam(value = "with_unread", required = false) String withUnread)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channels.directory(me, guildKind, ChannelHttpResponse.id(guildId), "1".equals(withUnread)));
    }

    @Operation(summary = "空间审计（VIEW_AUDIT；verify=1 附本库链校验）")
    @GetMapping("/api/im/audit-log")
    public ResponseEntity<AjaxResult> auditLog(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId,
            @RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "verify", defaultValue = "0") String verify,
            @RequestParam(value = "before", required = false) String before)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me ->
        {
            if (!"0".equals(verify) && !"1".equals(verify)) throw ChannelRejection.badField("verify");
            return audit.read(me, guildKind, ChannelHttpResponse.id(guildId), before, limit, "1".equals(verify));
        });
    }

    // ---------------------------------------------------------------- webhook context

    @Operation(summary = "Webhook 管理上下文（MANAGE_WEBHOOKS）与事件目录")
    @GetMapping("/api/im/webhook/context")
    public ResponseEntity<AjaxResult> webhookContext(@RequestParam("owner_id") String ownerId, @RequestParam("channel_id") String channelId)
    {
        return hook(me -> webhooks.context(me, id(ownerId), id(channelId)));
    }

    // ---------------------------------------------------------------- subscriptions

    @Operation(summary = "出站订阅列表")
    @GetMapping("/api/im/webhook/subscriptions")
    public ResponseEntity<AjaxResult> subscriptions(@RequestParam("owner_type") String ownerType, @RequestParam("owner_id") String ownerId,
            @RequestParam("channel_id") String channelId, @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return hook(me -> webhooks.listSubscriptions(me, ownerType, id(ownerId), id(channelId), cursor, limit));
    }

    @Operation(summary = "新建出站订阅（密钥只在首次响应出现）")
    @PostMapping("/api/im/webhook/subscriptions")
    public ResponseEntity<AjaxResult> createSubscription(@RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.createSubscription(me, body));
    }

    @Operation(summary = "出站订阅详情")
    @GetMapping("/api/im/webhook/subscriptions/{id}")
    public ResponseEntity<AjaxResult> subscription(@PathVariable("id") String id)
    {
        return hook(me -> webhooks.subscription(me, id(id)));
    }

    @Operation(summary = "修改出站订阅（修订号 CAS）")
    @PutMapping("/api/im/webhook/subscriptions/{id}")
    public ResponseEntity<AjaxResult> updateSubscription(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.updateSubscription(me, id(id), body));
    }

    @Operation(summary = "删除出站订阅")
    @DeleteMapping("/api/im/webhook/subscriptions/{id}")
    public ResponseEntity<AjaxResult> deleteSubscription(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.deleteSubscription(me, id(id), body));
    }

    @Operation(summary = "轮换订阅签名密钥")
    @PostMapping("/api/im/webhook/subscriptions/{id}/rotate-secret")
    public ResponseEntity<AjaxResult> rotateSecret(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.rotateSecret(me, id(id), body));
    }

    @Operation(summary = "订阅投递记录")
    @GetMapping("/api/im/webhook/subscriptions/{id}/deliveries")
    public ResponseEntity<AjaxResult> deliveries(@PathVariable("id") String id, @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "include_payload", defaultValue = "0") String includePayload)
    {
        return hook(me -> webhooks.deliveries(me, id(id), state, cursor, limit, "1".equals(includePayload)));
    }

    @Operation(summary = "重试投递（只排队，不声称已发出）")
    @PostMapping("/api/im/webhook/subscriptions/{id}/deliveries/{deliveryId}/retry")
    public ResponseEntity<AjaxResult> retry(@PathVariable("id") String id, @PathVariable("deliveryId") String deliveryId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.retry(me, id(id), id(deliveryId), body));
    }

    @Operation(summary = "测试投递（只排队，不声称已发出）")
    @PostMapping("/api/im/webhook/subscriptions/{id}/test")
    public ResponseEntity<AjaxResult> test(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.test(me, id(id), body));
    }

    // ---------------------------------------------------------------- endpoints (inbound)

    @Operation(summary = "入站端点列表")
    @GetMapping("/api/im/webhook/endpoints")
    public ResponseEntity<AjaxResult> endpoints(@RequestParam("owner_type") String ownerType, @RequestParam("owner_id") String ownerId,
            @RequestParam("channel_id") String channelId, @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return hook(me -> webhooks.listEndpoints(me, ownerType, id(ownerId), id(channelId), cursor, limit));
    }

    @Operation(summary = "新建入站端点（本批不开放：409 WEBHOOK_INBOUND_UNAVAILABLE）")
    @PostMapping("/api/im/webhook/endpoints")
    public ResponseEntity<AjaxResult> createEndpoint(@RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.createEndpoint(me, body));
    }

    @Operation(summary = "入站端点详情")
    @GetMapping("/api/im/webhook/endpoints/{id}")
    public ResponseEntity<AjaxResult> endpoint(@PathVariable("id") String id)
    {
        return hook(me -> webhooks.endpoint(me, id(id)));
    }

    @Operation(summary = "修改入站端点（本批不开放）")
    @PutMapping("/api/im/webhook/endpoints/{id}")
    public ResponseEntity<AjaxResult> updateEndpoint(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.mutateEndpoint(me, id(id), body));
    }

    @Operation(summary = "删除入站端点（本批不开放）")
    @DeleteMapping("/api/im/webhook/endpoints/{id}")
    public ResponseEntity<AjaxResult> deleteEndpoint(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.mutateEndpoint(me, id(id), body));
    }

    @Operation(summary = "轮换入站令牌（本批不开放）")
    @PostMapping("/api/im/webhook/endpoints/{id}/rotate-token")
    public ResponseEntity<AjaxResult> rotateToken(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.mutateEndpoint(me, id(id), body));
    }

    @Operation(summary = "入站调用日志")
    @GetMapping("/api/im/webhook/endpoints/{id}/logs")
    public ResponseEntity<AjaxResult> endpointLogs(@PathVariable("id") String id, @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return hook(me -> webhooks.endpointLogs(me, id(id), result, cursor, limit));
    }

    // ---------------------------------------------------------------- helpers

    /** Webhook 族：拒绝体另带 err（App 只读 err / trace_id）。 */
    private ResponseEntity<AjaxResult> hook(Function<Long, Object> body)
    {
        ResponseEntity<AjaxResult> response = ChannelHttpResponse.run(this::getAppUserId, body);
        AjaxResult result = response.getBody();
        if (result != null && result.get("errorCode") != null) result.put("err", result.get("errorCode"));
        return response;
    }

    static long id(String raw)
    {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw new ChannelRejection(404, "WEBHOOK_NOT_FOUND", "资源不存在");
        return Long.parseLong(raw);
    }
}
