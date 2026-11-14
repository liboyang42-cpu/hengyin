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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelAuditLogService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookDispatcher;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookInboundService;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.ip.IpUtils;

import jakarta.servlet.http.HttpServletRequest;

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
    @Autowired
    private ChannelWebhookInboundService inbound;

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

    @Operation(summary = "重试投递（重新排队；死信重投时尝试次数归零）")
    @PostMapping("/api/im/webhook/subscriptions/{id}/deliveries/{deliveryId}/retry")
    public ResponseEntity<AjaxResult> retry(@PathVariable("id") String id, @PathVariable("deliveryId") String deliveryId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.retry(me, id(id), id(deliveryId), body));
    }

    @Operation(summary = "测试投递（排队，由投递器签名发出）")
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

    @Operation(summary = "新建入站端点（签名密钥与接收地址只在首次响应出现）")
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

    @Operation(summary = "修改入站端点（修订号 CAS）")
    @PutMapping("/api/im/webhook/endpoints/{id}")
    public ResponseEntity<AjaxResult> updateEndpoint(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.updateEndpoint(me, id(id), body));
    }

    @Operation(summary = "删除入站端点")
    @DeleteMapping("/api/im/webhook/endpoints/{id}")
    public ResponseEntity<AjaxResult> deleteEndpoint(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.deleteEndpoint(me, id(id), body));
    }

    @Operation(summary = "轮换入站签名密钥（旧密钥立即失效）")
    @PostMapping("/api/im/webhook/endpoints/{id}/rotate-token")
    public ResponseEntity<AjaxResult> rotateToken(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return hook(me -> webhooks.rotateEndpointToken(me, id(id), body));
    }

    /**
     * 入站接收：无登录态，靠 HMAC 签名 + 时间窗 + 一次性 nonce（见 ChannelWebhookInboundService）。
     * 200 ACCEPTED（消息已可见）/ 202 PENDING_REVIEW（消息已落库待审）；拒绝按原因给 4xx，同样带 err。
     */
    @Anonymous
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @Operation(summary = "入站 Webhook 接收（签名校验后落频道消息）")
    @PostMapping("/api/im/webhook/inbound/{id}")
    public ResponseEntity<AjaxResult> inbound(@PathVariable("id") String id,
            @RequestHeader(value = ChannelWebhookDispatcher.HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = ChannelWebhookDispatcher.HEADER_NONCE, required = false) String nonce,
            @RequestHeader(value = ChannelWebhookDispatcher.HEADER_SIGNATURE, required = false) String signature,
            @RequestBody(required = false) byte[] body, HttpServletRequest request)
    {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) return webhookReject(404, "WEBHOOK_NOT_FOUND", "Webhook 不存在");
        if (body != null && body.length > ChannelWebhookInboundService.MAX_BODY_BYTES)
            return webhookReject(413, "WEBHOOK_BODY_TOO_LARGE", "请求体过大");
        try
        {
            ChannelWebhookInboundService.Result r = inbound.ingest(Long.parseLong(id), timestamp, nonce, signature, body, IpUtils.getIpAddr(request));
            AjaxResult result = r.errorCode() == null ? AjaxResult.success(r.message(), r.data()) : new AjaxResult(r.status(), r.message(), r.data());
            if (r.errorCode() != null)
            {
                result.put("errorCode", r.errorCode());
                result.put("err", r.errorCode());
                result.put("outcome_known", true);
            }
            else
            {
                result.put("code", r.status());
            }
            return ResponseEntity.status(r.status()).body(result);
        }
        catch (Exception e)
        {
            logger.error("入站 Webhook 结果待确认 type={}", e.getClass().getSimpleName());
            AjaxResult result = new AjaxResult(500, "结果待确认");
            result.put("errorCode", "OUTCOME_UNKNOWN");
            result.put("err", "OUTCOME_UNKNOWN");
            result.put("outcome_known", false);
            return ResponseEntity.status(500).body(result);
        }
    }

    private static ResponseEntity<AjaxResult> webhookReject(int status, String code, String message)
    {
        ResponseEntity<AjaxResult> response = ChannelHttpResponse.reject(status, code, message);
        response.getBody().put("err", code);
        return response;
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
