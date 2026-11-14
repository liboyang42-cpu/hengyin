package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;

/**
 * Back-office view of outbound channel-webhook deliveries across all subscriptions, and the redrive of a stuck or
 * dead-lettered delivery. The redrive has exactly the member retry's rules ({@link ChannelWebhookService#retry}):
 * DEAD is requeued with the attempt count reset; FAILED / UNKNOWN only while attempt &lt; {@link ChannelWebhookService#MAX_RETRY_ATTEMPT};
 * the subscription must still be active; the requeue is a compare-and-set on (state, attempt) through the same mapper.
 * The view never exposes the subscription URL (it can carry tokens), only its host, and never the signing secret.
 */
@Service
public class ChannelWebhookAdminService
{
    static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final ChannelWebhookMapper hooks;

    @Autowired
    public ChannelWebhookAdminService(JdbcTemplate jdbc, ChannelWebhookMapper hooks)
    {
        this.jdbc = jdbc;
        this.hooks = hooks;
    }

    private static final String COLUMNS = "d.id, d.subscription_id AS subscriptionId, d.event_id AS eventId, d.event_type AS eventType, d.state,"
            + " d.attempt, d.reason, d.response_snippet AS responseSnippet, d.http_status AS httpStatus, d.latency_ms AS latencyMs,"
            + " d.next_retry_at AS nextRetryAt, d.create_time AS createTime, d.update_time AS updateTime,"
            + " s.owner_type AS ownerType, s.owner_id AS ownerId, s.channel_id AS channelId, s.name AS subscriptionName,"
            + " s.url_host AS urlHost, s.status AS subscriptionStatus, s.deleted AS subscriptionDeleted";

    /** Default (no state) is the dead-letter queue; {@code ALL} lists every state. */
    public Map<String, Object> deliveries(String state, Long subscriptionId, Integer pageNum, Integer pageSize)
    {
        String filter = state == null || state.isBlank() ? "DEAD" : state.trim();
        if (!"ALL".equals(filter) && !ChannelWebhookService.DELIVERY_STATES.contains(filter)) throw ChannelRejection.badField("state");
        if (subscriptionId != null && subscriptionId <= 0) throw ChannelRejection.badField("subscriptionId");
        int size = pageSize == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (!"ALL".equals(filter)) { where.append(" AND d.state = ?"); args.add(filter); }
        if (subscriptionId != null) { where.append(" AND d.subscription_id = ?"); args.add(subscriptionId); }
        String from = " FROM im_webhook_delivery d LEFT JOIN im_webhook_subscription s ON s.id = d.subscription_id";
        Long total = jdbc.queryForObject("SELECT COUNT(1)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + COLUMNS + from + where + " ORDER BY d.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(adminView(r));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("DEAD", "FAILED", "UNKNOWN", "PENDING", "INFLIGHT", "SENT", "CANCELLED")) counts.put(s, 0L);
        for (Map<String, Object> c : jdbc.queryForList("SELECT state, COUNT(1) AS n FROM im_webhook_delivery GROUP BY state"))
            counts.put(String.valueOf(c.get("state")), ChannelGuildAccess.toLong(c.get("n")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", items);
        out.put("counts", counts);
        out.put("maxRetryAttempt", ChannelWebhookService.MAX_RETRY_ATTEMPT);
        return out;
    }

    /** Requeues one delivery as PENDING (reason RETRY_REQUESTED); the dispatcher signs and sends it. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> redrive(long adminId, long deliveryId)
    {
        if (adminId <= 0) throw new ChannelRejection(401, "UNAUTHORIZED", "请先登录");
        if (deliveryId <= 0) throw ChannelRejection.badField("deliveryId");
        List<Long> owner = jdbc.queryForList("SELECT subscription_id FROM im_webhook_delivery WHERE id = ?", Long.class, deliveryId);
        if (owner.isEmpty()) throw new ChannelRejection(404, "DELIVERY_NOT_FOUND", "投递不存在");
        long subscriptionId = owner.get(0);
        List<Map<String, Object>> subs = jdbc.queryForList(
                "SELECT status, deleted FROM im_webhook_subscription WHERE id = ? FOR UPDATE", subscriptionId);
        if (subs.isEmpty() || ChannelGuildAccess.toLong(subs.get(0).get("deleted")) != 0)
            throw new ChannelRejection(404, "WEBHOOK_NOT_FOUND", "Webhook 不存在");
        if (ChannelGuildAccess.toLong(subs.get(0).get("status")) != 1) throw new ChannelRejection(409, "WEBHOOK_PAUSED", "订阅已暂停");
        Map<String, Object> delivery = hooks.lockDelivery(subscriptionId, deliveryId);
        if (delivery == null) throw new ChannelRejection(404, "DELIVERY_NOT_FOUND", "投递不存在");
        String state = String.valueOf(delivery.get("state"));
        int attempt = (int) ChannelGuildAccess.toLong(delivery.get("attempt"));
        boolean dead = "DEAD".equals(state);
        if (!dead && (!("FAILED".equals(state) || "UNKNOWN".equals(state)) || attempt >= ChannelWebhookService.MAX_RETRY_ATTEMPT))
            throw new ChannelRejection(409, "DELIVERY_NOT_RETRYABLE", "该投递不可重试");
        if (hooks.requeueDelivery(deliveryId, state, attempt, dead) != 1) throw new ChannelRejection(409, "DELIVERY_NOT_RETRYABLE", "该投递已变化");
        Map<String, Object> view = ChannelWebhookService.deliveryView(hooks.selectDelivery(deliveryId), false);
        view.put("subscription_id", String.valueOf(subscriptionId));
        view.put("previous_state", state);
        view.put("previous_attempt", attempt);
        view.put("redriven_by", adminId);
        return view;
    }

    static boolean retryable(String state, int attempt)
    {
        return "DEAD".equals(state)
                || (("FAILED".equals(state) || "UNKNOWN".equals(state)) && attempt < ChannelWebhookService.MAX_RETRY_ATTEMPT);
    }

    private static Map<String, Object> adminView(Map<String, Object> r)
    {
        Map<String, Object> v = ChannelWebhookService.deliveryView(r, false);
        v.put("subscription_id", String.valueOf(ChannelGuildAccess.toLong(r.get("subscriptionId"))));
        v.put("updated_at", ChannelWebhookService.time(r.get("updateTime")));
        v.put("owner_type", r.get("ownerType"));
        v.put("owner_id", r.get("ownerId") == null ? null : String.valueOf(ChannelGuildAccess.toLong(r.get("ownerId"))));
        v.put("channel_id", r.get("channelId") == null ? null : String.valueOf(ChannelGuildAccess.toLong(r.get("channelId"))));
        v.put("subscription_name", r.get("subscriptionName"));
        v.put("url_host", r.get("urlHost"));
        boolean active = r.get("subscriptionStatus") != null && ChannelGuildAccess.toLong(r.get("subscriptionStatus")) == 1
                && ChannelGuildAccess.toLong(r.get("subscriptionDeleted")) == 0;
        v.put("subscription_active", active);
        v.put("retryable", active && retryable(String.valueOf(r.get("state")), (int) ChannelGuildAccess.toLong(r.get("attempt"))));
        return v;
    }
}
