package com.chengyinhub.business.service.channel.platform;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.ADMINISTRATOR;
import static com.chengyinhub.business.service.channel.platform.ChannelCapability.MANAGE_WEBHOOKS;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 频道 Webhook 管理（App NativeWebhookHTTPService / NativeWebhookManagementContextSource，e74 线格式）。
 * <ul>
 * <li>授权：频道属于 owner_id 俱乐部（guild_kind 1）、未封禁/删除，且调用者在该频道有 MANAGE_WEBHOOKS（或 ADMINISTRATOR）。</li>
 * <li>出站订阅：建/改/删/轮换密钥都以 request_id 落原回执（族 HOOK）；修订号 CAS。签名密钥只存 SHA-256，明文只在首次响应出现，
 *     重放不再给出。本批没有出站投递器：测试与重试只落 PENDING 投递，不声称已发出。</li>
 * <li>入站端点：读路由就位；创建返回 409 WEBHOOK_INBOUND_UNAVAILABLE（本批不开放入站写入）。</li>
 * </ul>
 */
@Service
public class ChannelWebhookService
{
    public static final String FAMILY = "HOOK";
    public static final String PERMISSION = "im:webhook:manage";
    public static final String EVENT_REVISION = "im-webhook-events-v1";
    public static final String DISPATCH_REASON = "DISPATCH_NOT_CONFIGURED";
    /** 事件目录：id → 标题（顺序即展示顺序）。 */
    public static final Map<String, String> EVENTS;
    static
    {
        Map<String, String> events = new LinkedHashMap<>();
        events.put("message.created", "新消息");
        events.put("message.updated", "消息编辑");
        events.put("message.deleted", "消息撤回");
        events.put("member.joined", "成员加入");
        events.put("member.left", "成员离开");
        EVENTS = java.util.Collections.unmodifiableMap(events);
    }
    static final Set<String> DELIVERY_STATES = Set.of("PENDING", "INFLIGHT", "SENT", "FAILED", "DEAD", "UNKNOWN", "CANCELLED");
    static final Set<String> INBOUND_RESULTS = Set.of("RECEIVED", "PENDING_REVIEW", "ACCEPTED", "REJECTED", "BLOCKED", "RATE_LIMITED", "UNKNOWN");
    static final Set<String> CHANGE_KEYS = Set.of("request_id", "owner_type", "owner_id", "channel_id", "name", "url", "avatar_url", "events",
            "allowed_ips", "rate_limit_per_min", "status", "revision");
    static final Set<String> ACTION_KEYS = Set.of("request_id", "revision", "event_type");
    static final int MAX_RETRY_ATTEMPT = 5;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private ChannelWebhookMapper hooks;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;

    /** 授权结果：频道与有效权限位。 */
    record Scope(ChannelRow channel, long bits) { }

    // ================================================================ context

    public Map<String, Object> context(long viewer, long ownerId, long channelId)
    {
        Scope scope = manage(viewer, ownerId, channelId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actor_id", String.valueOf(viewer));
        out.put("owner_type", "CLUB");
        out.put("owner_id", String.valueOf(ownerId));
        out.put("channel_id", String.valueOf(channelId));
        out.put("authorization_version", authorizationVersion(viewer, ownerId, scope));
        out.put("management_permission", PERMISSION);
        out.put("event_revision", EVENT_REVISION);
        List<Map<String, Object>> events = new ArrayList<>();
        EVENTS.forEach((id, title) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", id);
            e.put("title", title);
            events.add(e);
        });
        out.put("events", events);
        return out;
    }

    static String authorizationVersion(long viewer, long ownerId, Scope scope)
    {
        String material = "im-webhook-auth-v1|" + viewer + "|" + ownerId + "|" + scope.channel().getId() + "|"
                + ChannelCapability.hex(scope.bits()) + "|" + scope.channel().getPermVersion() + "|" + scope.channel().getStatus();
        return "v1-" + sha256(material).substring(0, 32);
    }

    // ================================================================ subscriptions

    public Map<String, Object> listSubscriptions(long viewer, String ownerType, long ownerId, long channelId, String cursor, int limit)
    {
        requireClub(ownerType);
        manage(viewer, ownerId, channelId);
        int size = pageSize(limit);
        List<Map<String, Object>> rows = hooks.listSubscriptions(ownerId, channelId, cursor(cursor), size + 1);
        List<Map<String, Object>> list = new ArrayList<>();
        boolean more = rows.size() > size;
        for (Map<String, Object> r : more ? rows.subList(0, size) : rows) list.add(subscriptionView(r));
        return page(list, more);
    }

    public Map<String, Object> subscription(long viewer, long id)
    {
        Map<String, Object> row = hooks.selectSubscription(id);
        if (row == null) throw notFound();
        manage(viewer, ChannelGuildAccess.toLong(row.get("ownerId")), ChannelGuildAccess.toLong(row.get("channelId")));
        return subscriptionView(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSubscription(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, CHANGE_KEYS);
        String requestId = requestId(b.get("request_id"));
        requireClub(b.get("owner_type"));
        long ownerId = ChannelGovernanceService.positive(b.get("owner_id"), "owner_id");
        long channelId = ChannelGovernanceService.positive(b.get("channel_id"), "channel_id");
        if (b.get("revision") != null) throw new ChannelRejection(409, "REVISION_CONFLICT", "新建不带修订号");
        String name = name(b.get("name"));
        List<String> events = events(b.get("events"));
        String url = url(b.get("url"), true);
        int status = status(b.get("status"), 1);
        rejectEndpointFields(b);
        manage(viewer, ownerId, channelId);
        String fp = ChannelReceipts.fingerprint("WEBHOOK_SUB_CREATE", ownerId + ":" + channelId, withoutRequestId(b));
        String[] secret = new String[1];
        Map<String, Object> out = once(viewer, requestId, fp, "WEBHOOK_SUB_CREATE", channelId, () ->
        {
            secret[0] = newSecret("whsec_");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ownerId", ownerId);
            row.put("channelId", channelId);
            row.put("name", name);
            row.put("url", url);
            row.put("urlHost", URI.create(url).getHost().toLowerCase(Locale.ROOT));
            row.put("eventsJson", encode(events));
            row.put("status", status);
            row.put("secretSha256", sha256(secret[0]));
            row.put("secretPrefix", secret[0].substring(0, 12));
            row.put("createdBy", viewer);
            if (hooks.insertSubscription(row) != 1) throw new IllegalStateException("Webhook insert failed");
            Map<String, Object> view = subscriptionView(hooks.selectSubscription(ChannelGuildAccess.toLong(row.get("id"))));
            view.put("secret_shown_once", true);
            return view;
        });
        if (secret[0] != null) out.put("secret", secret[0]);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateSubscription(long viewer, long id, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, CHANGE_KEYS);
        String requestId = requestId(b.get("request_id"));
        requireClub(b.get("owner_type"));
        long ownerId = ChannelGovernanceService.positive(b.get("owner_id"), "owner_id");
        long channelId = ChannelGovernanceService.positive(b.get("channel_id"), "channel_id");
        int revision = revision(b.get("revision"));
        String name = name(b.get("name"));
        List<String> events = events(b.get("events"));
        String url = url(b.get("url"), false);
        rejectEndpointFields(b);
        manage(viewer, ownerId, channelId);
        String fp = ChannelReceipts.fingerprint("WEBHOOK_SUB_UPDATE", id, withoutRequestId(b));
        return once(viewer, requestId, fp, "WEBHOOK_SUB_UPDATE", channelId, () ->
        {
            Map<String, Object> row = lockOwned(id, ownerId, channelId);
            requireRevision(row, revision);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("id", id);
            change.put("revision", revision);
            change.put("name", name);
            change.put("eventsJson", encode(events));
            change.put("status", status(b.get("status"), (int) ChannelGuildAccess.toLong(row.get("status"))));
            change.put("url", url);
            change.put("urlHost", url == null ? null : URI.create(url).getHost().toLowerCase(Locale.ROOT));
            if (hooks.updateSubscription(change) != 1) throw new ChannelRejection(409, "REVISION_CONFLICT", "订阅已被修改");
            return subscriptionView(hooks.selectSubscription(id));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteSubscription(long viewer, long id, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, ACTION_KEYS);
        String requestId = requestId(b.get("request_id"));
        int revision = revision(b.get("revision"));
        if (b.get("event_type") != null) throw ChannelRejection.badField("event_type");
        Map<String, Object> current = owned(viewer, id);
        String fp = ChannelReceipts.fingerprint("WEBHOOK_SUB_DELETE", id, withoutRequestId(b));
        return once(viewer, requestId, fp, "WEBHOOK_SUB_DELETE", ChannelGuildAccess.toLong(current.get("channelId")), () ->
        {
            Map<String, Object> row = lockOwned(id, ChannelGuildAccess.toLong(current.get("ownerId")), ChannelGuildAccess.toLong(current.get("channelId")));
            requireRevision(row, revision);
            if (hooks.deleteSubscription(id, revision) != 1) throw new ChannelRejection(409, "REVISION_CONFLICT", "订阅已被修改");
            return subscriptionView(hooks.selectSubscription(id));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rotateSecret(long viewer, long id, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, ACTION_KEYS);
        String requestId = requestId(b.get("request_id"));
        int revision = revision(b.get("revision"));
        if (b.get("event_type") != null) throw ChannelRejection.badField("event_type");
        Map<String, Object> current = owned(viewer, id);
        String fp = ChannelReceipts.fingerprint("WEBHOOK_SUB_ROTATE", id, withoutRequestId(b));
        String[] secret = new String[1];
        Map<String, Object> out = once(viewer, requestId, fp, "WEBHOOK_SUB_ROTATE", ChannelGuildAccess.toLong(current.get("channelId")), () ->
        {
            Map<String, Object> row = lockOwned(id, ChannelGuildAccess.toLong(current.get("ownerId")), ChannelGuildAccess.toLong(current.get("channelId")));
            requireRevision(row, revision);
            secret[0] = newSecret("whsec_");
            if (hooks.rotateSubscriptionSecret(id, revision, sha256(secret[0]), secret[0].substring(0, 12)) != 1)
                throw new ChannelRejection(409, "REVISION_CONFLICT", "订阅已被修改");
            Map<String, Object> view = subscriptionView(hooks.selectSubscription(id));
            view.put("secret_shown_once", true);
            return view;
        });
        if (secret[0] != null) out.put("secret", secret[0]);
        return out;
    }

    // ================================================================ deliveries

    public Map<String, Object> deliveries(long viewer, long id, String state, String cursor, int limit, boolean includePayload)
    {
        owned(viewer, id);
        if (state != null && !DELIVERY_STATES.contains(state)) throw ChannelRejection.badField("state");
        int size = pageSize(limit);
        List<Map<String, Object>> rows = hooks.listDeliveries(id, state, cursor(cursor), size + 1);
        boolean more = rows.size() > size;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : more ? rows.subList(0, size) : rows) list.add(deliveryView(r, includePayload));
        return page(list, more);
    }

    /** 测试投递：只落一条 PENDING（DISPATCH_NOT_CONFIGURED），不声称已发出。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> test(long viewer, long id, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, ACTION_KEYS);
        String requestId = requestId(b.get("request_id"));
        int revision = revision(b.get("revision"));
        if (!(b.get("event_type") instanceof String eventType) || !EVENTS.containsKey(eventType))
            throw new ChannelRejection(400, "EVENT_UNKNOWN", "未知事件");
        Map<String, Object> current = owned(viewer, id);
        String fp = ChannelReceipts.fingerprint("WEBHOOK_SUB_TEST", id, withoutRequestId(b));
        return once(viewer, requestId, fp, "WEBHOOK_SUB_TEST", ChannelGuildAccess.toLong(current.get("channelId")), () ->
        {
            Map<String, Object> row = lockOwned(id, ChannelGuildAccess.toLong(current.get("ownerId")), ChannelGuildAccess.toLong(current.get("channelId")));
            requireRevision(row, revision);
            requireActive(row);
            if (!decodeList(row.get("eventsJson")).contains(eventType)) throw new ChannelRejection(400, "EVENT_UNKNOWN", "订阅未包含该事件");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event_type", eventType);
            payload.put("test", true);
            payload.put("owner_type", "CLUB");
            payload.put("owner_id", String.valueOf(ChannelGuildAccess.toLong(row.get("ownerId"))));
            payload.put("channel_id", String.valueOf(ChannelGuildAccess.toLong(row.get("channelId"))));
            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("subscriptionId", id);
            delivery.put("eventId", "test-" + sha256(viewer + "|" + requestId).substring(0, 32));
            delivery.put("eventType", eventType);
            delivery.put("reason", DISPATCH_REASON);
            delivery.put("payloadJson", encode(payload));
            if (hooks.insertDelivery(delivery) != 1) throw new IllegalStateException("Webhook delivery insert failed");
            return deliveryView(hooks.selectDelivery(ChannelGuildAccess.toLong(delivery.get("id"))), false);
        });
    }

    /** 显式重试：只接受 FAILED/UNKNOWN 且尝试次数 &lt; 5 的投递，重新排队为 PENDING。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> retry(long viewer, long id, long deliveryId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, ACTION_KEYS);
        String requestId = requestId(b.get("request_id"));
        int revision = revision(b.get("revision"));
        if (b.get("event_type") != null) throw ChannelRejection.badField("event_type");
        Map<String, Object> current = owned(viewer, id);
        String fp = ChannelReceipts.fingerprint("WEBHOOK_SUB_RETRY", id + ":" + deliveryId, withoutRequestId(b));
        return once(viewer, requestId, fp, "WEBHOOK_SUB_RETRY", ChannelGuildAccess.toLong(current.get("channelId")), () ->
        {
            Map<String, Object> row = lockOwned(id, ChannelGuildAccess.toLong(current.get("ownerId")), ChannelGuildAccess.toLong(current.get("channelId")));
            requireRevision(row, revision);
            requireActive(row);
            Map<String, Object> delivery = hooks.lockDelivery(id, deliveryId);
            if (delivery == null) throw new ChannelRejection(404, "DELIVERY_NOT_FOUND", "投递不存在");
            String state = String.valueOf(delivery.get("state"));
            int attempt = (int) ChannelGuildAccess.toLong(delivery.get("attempt"));
            if (!("FAILED".equals(state) || "UNKNOWN".equals(state)) || attempt >= MAX_RETRY_ATTEMPT)
                throw new ChannelRejection(409, "DELIVERY_NOT_RETRYABLE", "该投递不可重试");
            if (hooks.requeueDelivery(deliveryId, state, attempt) != 1) throw new ChannelRejection(409, "DELIVERY_NOT_RETRYABLE", "该投递已变化");
            return deliveryView(hooks.selectDelivery(deliveryId), false);
        });
    }

    // ================================================================ endpoints (inbound)

    public Map<String, Object> listEndpoints(long viewer, String ownerType, long ownerId, long channelId, String cursor, int limit)
    {
        requireClub(ownerType);
        manage(viewer, ownerId, channelId);
        int size = pageSize(limit);
        List<Map<String, Object>> rows = hooks.listEndpoints(ownerId, channelId, cursor(cursor), size + 1);
        boolean more = rows.size() > size;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : more ? rows.subList(0, size) : rows) list.add(endpointView(r));
        return page(list, more);
    }

    public Map<String, Object> endpoint(long viewer, long id)
    {
        return endpointView(ownedEndpoint(viewer, id));
    }

    public Map<String, Object> endpointLogs(long viewer, long id, String result, String cursor, int limit)
    {
        ownedEndpoint(viewer, id);
        if (result != null && !INBOUND_RESULTS.contains(result)) throw ChannelRejection.badField("result");
        int size = pageSize(limit);
        List<Map<String, Object>> rows = hooks.listInboundLogs(id, result, cursor(cursor), size + 1);
        boolean more = rows.size() > size;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : more ? rows.subList(0, size) : rows) list.add(inboundView(r));
        return page(list, more);
    }

    /** 入站写入本批不开放：先做同样的授权与校验，再给确定性拒绝（不落回执、不建行）。 */
    public Map<String, Object> createEndpoint(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, CHANGE_KEYS);
        requestId(b.get("request_id"));
        requireClub(b.get("owner_type"));
        manage(viewer, ChannelGovernanceService.positive(b.get("owner_id"), "owner_id"),
                ChannelGovernanceService.positive(b.get("channel_id"), "channel_id"));
        throw inboundUnavailable();
    }

    /** 改/删/轮换令牌：端点必须存在且归属可管；本批不存在可写端点，存在时同样拒绝。 */
    public Map<String, Object> mutateEndpoint(long viewer, long id, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, CHANGE_KEYS);
        requestId(b.get("request_id"));
        ownedEndpoint(viewer, id);
        throw inboundUnavailable();
    }

    static ChannelRejection inboundUnavailable()
    {
        return new ChannelRejection(409, "WEBHOOK_INBOUND_UNAVAILABLE", "入站 Webhook 暂未开放");
    }

    // ================================================================ authorization

    Scope manage(long viewer, long ownerId, long channelId)
    {
        ChannelRow channel = channels.selectChannel(channelId);
        if (channel == null || channel.getGuildKind() == null || channel.getGuildKind() != ChannelGuildAccess.GUILD_CLUB
                || channel.getGuildId() == null || channel.getGuildId() != ownerId
                || channel.getStatus() == null || channel.getStatus() < 0 || channel.getStatus() > 1)
            throw ChannelRejection.notFound();
        long bits = access.effective(channel, viewer);
        if (bits == 0) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能操作");
        if (!has(bits, MANAGE_WEBHOOKS) && !has(bits, ADMINISTRATOR)) throw new ChannelRejection(403, "WEBHOOK_FORBIDDEN", "无权管理该频道的 Webhook");
        return new Scope(channel, bits);
    }

    private Map<String, Object> owned(long viewer, long id)
    {
        Map<String, Object> row = hooks.selectSubscription(id);
        if (row == null) throw notFound();
        manage(viewer, ChannelGuildAccess.toLong(row.get("ownerId")), ChannelGuildAccess.toLong(row.get("channelId")));
        return row;
    }

    private Map<String, Object> ownedEndpoint(long viewer, long id)
    {
        Map<String, Object> row = hooks.selectEndpoint(id);
        if (row == null) throw notFound();
        manage(viewer, ChannelGuildAccess.toLong(row.get("ownerId")), ChannelGuildAccess.toLong(row.get("channelId")));
        return row;
    }

    private Map<String, Object> lockOwned(long id, long ownerId, long channelId)
    {
        Map<String, Object> row = hooks.lockSubscription(id);
        if (row == null || ChannelGuildAccess.toLong(row.get("ownerId")) != ownerId || ChannelGuildAccess.toLong(row.get("channelId")) != channelId)
            throw notFound();
        return row;
    }

    private static void requireRevision(Map<String, Object> row, int revision)
    {
        if (ChannelGuildAccess.toLong(row.get("deleted")) != 0) throw notFound();
        if (ChannelGuildAccess.toLong(row.get("revision")) != revision) throw new ChannelRejection(409, "REVISION_CONFLICT", "订阅已被修改");
    }

    private static void requireActive(Map<String, Object> row)
    {
        if (ChannelGuildAccess.toLong(row.get("status")) != 1) throw new ChannelRejection(409, "WEBHOOK_PAUSED", "订阅已暂停");
    }

    static ChannelRejection notFound() { return new ChannelRejection(404, "WEBHOOK_NOT_FOUND", "Webhook 不存在"); }

    // ================================================================ receipts

    /** 原回执（族 HOOK）：存的是去掉一次性明文后的响应；重放标 replayed=true 且不再带明文。 */
    private Map<String, Object> once(long viewer, String requestId, String fingerprint, String operation, long channelId,
            Supplier<Map<String, Object>> work)
    {
        channels.reserveReceipt(viewer, FAMILY, requestId, fingerprint, operation, channelId);
        Map<String, Object> receipt = channels.lockReceipt(viewer, FAMILY, requestId);
        if (receipt == null) throw new IllegalStateException("Receipt reservation lost");
        if (!fingerprint.equals(String.valueOf(receipt.get("fingerprint"))))
            throw new ChannelRejection(409, "REQUEST_ID_CONFLICT", "该请求标识已用于其他内容");
        Object stored = receipt.get("response");
        if (stored != null)
        {
            Map<String, Object> replay = ChannelReceipts.decode(stored.toString());
            replay.put("replayed", Boolean.TRUE);
            replay.remove("secret_shown_once");
            return replay;
        }
        Map<String, Object> response = work.get();
        response.remove("secret");
        String encoded = ChannelReceipts.encode(response);
        if (channels.finishReceipt(viewer, FAMILY, requestId, encoded) != 1) throw new IllegalStateException("Receipt finish failed");
        Map<String, Object> out = ChannelReceipts.decode(encoded);
        out.put("replayed", Boolean.FALSE);
        return out;
    }

    // ================================================================ views

    static Map<String, Object> subscriptionView(Map<String, Object> r)
    {
        if (r == null) throw new IllegalStateException("Webhook row missing");
        Map<String, Object> v = resourceBase(r);
        v.put("url_host", r.get("urlHost"));
        List<String> events = decodeList(r.get("eventsJson"));
        v.put("events", events);
        v.put("event_count", events.size());
        v.put("fail_count", ChannelGuildAccess.toLong(r.get("failCount")));
        v.put("last_error", r.get("lastError"));
        v.put("last_delivery_at", time(r.get("lastDeliveryAt")));
        return v;
    }

    static Map<String, Object> endpointView(Map<String, Object> r)
    {
        Map<String, Object> v = resourceBase(r);
        v.put("avatar_url", r.get("avatarUrl"));
        v.put("token_prefix", r.get("tokenPrefix"));
        v.put("rate_limit_per_min", (int) ChannelGuildAccess.toLong(r.get("rateLimitPerMin")));
        v.put("allowed_ips", decodeList(r.get("allowedIpsJson")));
        v.put("last_called_at", time(r.get("lastCalledAt")));
        return v;
    }

    private static Map<String, Object> resourceBase(Map<String, Object> r)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(ChannelGuildAccess.toLong(r.get("id"))));
        v.put("channel_id", String.valueOf(ChannelGuildAccess.toLong(r.get("channelId"))));
        v.put("owner_type", r.get("ownerType"));
        v.put("owner_id", String.valueOf(ChannelGuildAccess.toLong(r.get("ownerId"))));
        v.put("name", r.get("name"));
        v.put("status", (int) ChannelGuildAccess.toLong(r.get("status")));
        v.put("deleted", ChannelGuildAccess.toLong(r.get("deleted")) == 1);
        v.put("revision", (int) ChannelGuildAccess.toLong(r.get("revision")));
        v.put("key_version", (int) ChannelGuildAccess.toLong(r.get("keyVersion")));
        v.put("created_at", time(r.get("createTime")));
        v.put("updated_at", time(r.get("updateTime")));
        return v;
    }

    static Map<String, Object> deliveryView(Map<String, Object> r, boolean includePayload)
    {
        if (r == null) throw new IllegalStateException("Webhook delivery missing");
        Map<String, Object> v = new LinkedHashMap<>();
        String id = String.valueOf(ChannelGuildAccess.toLong(r.get("id")));
        v.put("id", id);
        v.put("delivery_id", id);
        v.put("event_id", r.get("eventId"));
        v.put("event_type", r.get("eventType"));
        v.put("state", r.get("state"));
        v.put("attempt", (int) ChannelGuildAccess.toLong(r.get("attempt")));
        v.put("reason", r.get("reason"));
        v.put("response_snippet", r.get("responseSnippet"));
        v.put("http_status", r.get("httpStatus") == null ? null : (int) ChannelGuildAccess.toLong(r.get("httpStatus")));
        v.put("latency_ms", r.get("latencyMs") == null ? null : ChannelGuildAccess.toLong(r.get("latencyMs")));
        v.put("next_retry_at", time(r.get("nextRetryAt")));
        v.put("created_at", time(r.get("createTime")));
        if (includePayload && r.get("payloadJson") != null) v.put("payload_json", ChannelReceipts.decode(r.get("payloadJson").toString()));
        return v;
    }

    static Map<String, Object> inboundView(Map<String, Object> r)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(ChannelGuildAccess.toLong(r.get("id"))));
        v.put("result", r.get("result"));
        v.put("state", r.get("result"));
        v.put("reason", r.get("reason"));
        v.put("message_id", r.get("messageId") == null ? null : String.valueOf(ChannelGuildAccess.toLong(r.get("messageId"))));
        v.put("created_at", time(r.get("createTime")));
        v.put("request_hash", r.get("requestHash"));
        v.put("nonce", r.get("nonce"));
        v.put("endpoint_id", String.valueOf(ChannelGuildAccess.toLong(r.get("endpointId"))));
        v.put("sender_type", r.get("senderType") == null ? null : (int) ChannelGuildAccess.toLong(r.get("senderType")));
        v.put("webhook_id", r.get("webhookId") == null ? null : String.valueOf(ChannelGuildAccess.toLong(r.get("webhookId"))));
        v.put("original_message_stored", r.get("originalMessageStored") == null ? null : ChannelGuildAccess.toLong(r.get("originalMessageStored")) == 1);
        return v;
    }

    /** App NativeWebhookServerTime：上海时区 yyyy-MM-dd HH:mm:ss.SSS（23 字符）。 */
    static String time(Object value)
    {
        Date d = ChannelRejection.toDate(value);
        if (d == null) return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(d);
    }

    private static Map<String, Object> page(List<Map<String, Object>> list, boolean more)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("has_more", more);
        out.put("next_cursor", more ? list.get(list.size() - 1).get("id") : null);
        return out;
    }

    // ================================================================ input

    static Map<String, Object> body(Map<String, Object> body, Set<String> allowed)
    {
        return ChannelGovernanceService.body(body, allowed);
    }

    /** NativeWebhookRequestID：1–64 位 [A-Za-z0-9_-]。 */
    static String requestId(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[A-Za-z0-9_-]{1,64}")) throw ChannelRejection.badField("request_id");
        return s;
    }

    static void requireClub(Object ownerType)
    {
        if (!"CLUB".equals(ownerType)) throw ChannelRejection.badField("owner_type");
    }

    static String name(Object raw)
    {
        if (!(raw instanceof String s) || s.strip().isEmpty() || s.length() > 64 || s.chars().anyMatch(Character::isISOControl))
            throw new ChannelRejection(400, "WEBHOOK_CHANGE_INVALID", "名称不合法");
        return s;
    }

    static List<String> events(Object raw)
    {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 32) throw new ChannelRejection(400, "EVENT_UNKNOWN", "事件不合法");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : list)
        {
            if (!(o instanceof String e) || !EVENTS.containsKey(e) || !seen.add(e)) throw new ChannelRejection(400, "EVENT_UNKNOWN", "事件不合法");
            out.add(e);
        }
        return out;
    }

    /** 只收 https、带主机、无凭据/片段的地址；拒绝 localhost 与内网/保留 IP 字面量。 */
    static String url(Object raw, boolean required)
    {
        if (raw == null)
        {
            if (required) throw new ChannelRejection(400, "URL_REQUIRED", "缺少回调地址");
            return null;
        }
        if (!(raw instanceof String s) || s.length() > 512) throw new ChannelRejection(400, "URL_INVALID", "回调地址不合法");
        URI uri;
        try { uri = new URI(s); }
        catch (Exception e) { throw new ChannelRejection(400, "URL_INVALID", "回调地址不合法"); }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || host.isEmpty() || uri.getUserInfo() != null || uri.getFragment() != null
                || privateHost(host))
            throw new ChannelRejection(400, "URL_INVALID", "回调地址不合法");
        return s;
    }

    static boolean privateHost(String rawHost)
    {
        String host = IDN.toASCII(rawHost.toLowerCase(Locale.ROOT));
        if (host.startsWith("[") || host.contains(":")) return true; // IPv6 字面量一律不收
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return true;
        if (!host.matches("[0-9.]+")) return !host.contains(".");
        String[] p = host.split("\\.");
        if (p.length != 4) return true;
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127);
    }

    static int status(Object raw, int fallback)
    {
        if (raw == null) return fallback;
        if (!(raw instanceof Integer i) || (i != 0 && i != 1)) throw ChannelRejection.badField("status");
        return i;
    }

    static int revision(Object raw)
    {
        if (!(raw instanceof Integer i) || i <= 0) throw ChannelRejection.badField("revision");
        return i;
    }

    private static void rejectEndpointFields(Map<String, Object> b)
    {
        for (String k : new String[] { "avatar_url", "allowed_ips", "rate_limit_per_min" })
            if (b.get(k) != null) throw ChannelRejection.badField(k);
    }

    static int pageSize(int limit)
    {
        if (limit < 1 || limit > 100) throw new ChannelRejection(400, "LIMIT_INVALID", "limit 不合法");
        return limit;
    }

    static Long cursor(String raw)
    {
        if (raw == null || raw.isEmpty()) return null;
        if (!raw.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("cursor");
        return Long.parseLong(raw);
    }

    private static Map<String, Object> withoutRequestId(Map<String, Object> b)
    {
        Map<String, Object> copy = new LinkedHashMap<>(b);
        copy.remove("request_id");
        return copy;
    }

    // ================================================================ codec

    static String newSecret(String prefix)
    {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String text)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    static String encode(Object value)
    {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode webhook field", e); }
    }

    static List<String> decodeList(Object raw)
    {
        if (raw == null) return List.of();
        try { return JSON.readValue(raw.toString(), new TypeReference<List<String>>() { }); }
        catch (Exception e) { throw new IllegalStateException("Cannot read webhook field", e); }
    }
}
