package com.chengyinhub.business.service.channel.platform;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.ADMINISTRATOR;
import static com.chengyinhub.business.service.channel.platform.ChannelCapability.MANAGE_PROGRESS;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ProgressPrivacyMapper;
import com.chengyinhub.business.service.friend.FriendProofs;
import com.chengyinhub.business.service.friend.FriendService;

/**
 * 进度隐私（App 两套客户端共用 /api/privacy/progress）：
 * <ul>
 * <li>设置页（NativeProgressPrivacyService）：本人 GET /api/privacy/progress、频道 GET/POST /api/channel/{id}/progress/privacy，
 *     只改 aggregate_scope；回执 GET /api/progress/requests?request_id=。</li>
 * <li>频道进度页（NativeChannelProgressService）：GET /api/privacy/progress?channel_id=，POST 带四个范围；
 *     回执 GET /api/progress/requests/{requestId}。</li>
 * </ul>
 * 两套 GET 以是否带 channel_id 区分，POST 以请求体键区分。好友进度读取经 FriendProgressGate 尊重 aggregate_scope。
 */
@Service
public class ProgressPrivacyService implements FriendService.FriendProgressGate
{
    static final Set<String> AGGREGATE = Set.of("SELF", "FRIEND", "TEAM", "CHANNEL", "PUBLIC");
    static final Set<String> DETAIL = Set.of("SELF", "TEAM", "CHANNEL");

    @Autowired
    private ProgressPrivacyMapper privacy;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired
    private ChannelPlatformService platform;

    @Override
    public boolean friendAggregateVisible(long owner, long viewer)
    {
        Map<String, Object> row = privacy.selectMember(owner);
        String scope = row == null ? "PUBLIC" : String.valueOf(row.get("aggregateScope"));
        return "PUBLIC".equals(scope) || "FRIEND".equals(scope);
    }

    // ================================================================ settings snapshot (USER / CHANNEL)

    public Map<String, Object> userSnapshot(long viewer)
    {
        Map<String, Object> row = privacy.selectMember(viewer);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("actor_id", viewer);
        v.put("scope_type", "USER");
        v.put("scope_id", viewer);
        v.put("aggregate_scope", row == null ? "PUBLIC" : row.get("aggregateScope"));
        v.put("detail_scope", row == null ? "SELF" : row.get("detailScope"));
        v.put("photo_scope", row == null ? "SELF" : row.get("photoScope"));
        v.put("leaderboard_scope", row == null ? "IN" : row.get("leaderboardScope"));
        v.put("updated_at", row == null ? null : row.get("updatedAt"));
        v.put("is_default", row == null);
        v.put("applies_to", "ALL");
        v.put("editable_fields", List.of("aggregate_scope"));
        v.put("can_write", true);
        // 已配置的汇总范围由好友进度读取（FriendProgressGate）实际执行。
        v.put("aggregate_verified", row != null);
        return v;
    }

    public Map<String, Object> channelSnapshot(long viewer, long channelId)
    {
        ChannelRow channel = platform.visibleChannel(viewer, channelId);
        Map<String, Object> row = privacy.selectChannel(channelId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("actor_id", viewer);
        v.put("scope_type", "CHANNEL");
        v.put("scope_id", channelId);
        v.put("aggregate_scope", row == null ? "PUBLIC" : row.get("aggregateScope"));
        v.put("detail_scope", row == null ? "SELF" : row.get("detailScope"));
        v.put("board_enabled", row == null ? 1 : (int) ChannelGuildAccess.toLong(row.get("boardEnabled")));
        v.put("revision", row == null ? 0 : (int) ChannelGuildAccess.toLong(row.get("revision")));
        v.put("updated_at", row == null ? null : row.get("updatedAt"));
        v.put("is_default", row == null);
        v.put("applies_to", "ALL");
        v.put("editable_fields", List.of("aggregate_scope"));
        v.put("can_write", canManage(viewer, channel));
        v.put("aggregate_verified", false);
        return v;
    }

    private boolean canManage(long viewer, ChannelRow channel)
    {
        long bits = access.guildBits(channel.getGuildKind(), channel.getGuildId(), viewer);
        return ChannelPermissionCalculator.has(bits, MANAGE_PROGRESS) || ChannelPermissionCalculator.has(bits, ADMINISTRATOR);
    }

    public static String fingerprint(Map<String, Object> fields)
    {
        StringBuilder raw = new StringBuilder("progress-privacy-v1\n");
        for (Map.Entry<String, Object> e : new TreeMap<>(fields).entrySet())
        {
            String key = e.getKey();
            raw.append(key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(':').append(key);
            Object v = e.getValue();
            if (v == null) raw.append('N');
            else if (v instanceof String s) raw.append('S').append(s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(':').append(s);
            else raw.append('I').append(((Number) v).longValue()).append(';');
        }
        return FriendProofs.sha256(raw.toString());
    }

    /** 设置页写：只改 aggregate_scope；冲突以 REJECTED/PRIVACY_CONFLICT 回执（code 200）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAggregate(long viewer, Long channelId, Map<String, Object> body)
    {
        boolean user = channelId == null;
        String versionKey = user ? "expected_updated_at" : "expected_revision";
        if (body == null || !Set.of("request_id", "aggregate_scope", versionKey).equals(body.keySet())) throw ChannelRejection.badField("body");
        String requestId = upperUuid(body.get("request_id"));
        if (!(body.get("aggregate_scope") instanceof String scope) || !AGGREGATE.contains(scope)) throw ChannelRejection.badField("aggregate_scope");
        Object expected = body.get(versionKey);
        if (user ? !(expected == null || expected instanceof String) : !(expected instanceof Integer || expected instanceof Long))
            throw ChannelRejection.badField(versionKey);
        Map<String, Object> fields = new LinkedHashMap<>(body);
        fields.put("scope_type", user ? "USER" : "CHANNEL");
        fields.put("scope_id", user ? viewer : channelId);
        String hash = fingerprint(fields);
        long scopeId = user ? viewer : channelId;
        Map<String, Object> out = ChannelReceipts.once(channels, viewer, "PRIVACY", requestId, hash, "AGGREGATE", user ? 0L : channelId, () ->
        {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("actor_id", viewer);
            r.put("request_id", requestId);
            r.put("scope_type", user ? "USER" : "CHANNEL");
            r.put("scope_id", scopeId);
            r.put("payload_hash", hash);
            boolean conflict;
            if (user)
            {
                Map<String, Object> row = privacy.lockMember(viewer);
                conflict = !Objects.equals(row == null ? null : row.get("updatedAt"), expected);
                if (!conflict)
                {
                    Map<String, Object> next = memberRow(viewer, row);
                    next.put("aggregateScope", scope);
                    privacy.upsertMember(next);
                }
            }
            else
            {
                ChannelRow channel = platform.visibleChannel(viewer, channelId);
                if (!canManage(viewer, channel)) throw ChannelRejection.forbidden();
                Map<String, Object> row = privacy.lockChannel(channelId);
                conflict = (row == null ? 0L : ChannelGuildAccess.toLong(row.get("revision"))) != ((Number) expected).longValue();
                if (!conflict) privacy.upsertChannel(channelId, scope, viewer);
            }
            if (conflict)
            {
                r.put("outcome", "REJECTED");
                r.put("reason", "PRIVACY_CONFLICT");
                return r;
            }
            Map<String, Object> saved = user ? privacy.selectMember(viewer) : privacy.selectChannel(channelId);
            r.put("outcome", "APPLIED");
            r.put("reason", "AGGREGATE_PRIVACY_SAVED");
            r.put("applied_updated_at", saved.get("updatedAt"));
            if (!user) r.put("applied_revision", (int) ChannelGuildAccess.toLong(saved.get("revision")));
            return r;
        });
        out.remove("replayed");
        return out;
    }

    public Map<String, Object> settingsReceipt(long viewer, String requestId)
    {
        Map<String, Object> stored = channels.selectReceipt(viewer, "PRIVACY", upperUuid(requestId));
        if (stored == null || stored.get("response") == null || !"AGGREGATE".equals(stored.get("operation")))
            throw new ChannelRejection(404, "PROGRESS_REQUEST_NOT_FOUND", "没有找到该请求");
        Map<String, Object> out = ChannelReceipts.decode(stored.get("response").toString());
        out.remove("replayed");
        return out;
    }

    // ================================================================ channel progress page (four scopes)

    public Map<String, Object> progressPrivacy(long viewer, long channelId)
    {
        platform.visibleChannel(viewer, channelId);
        return progressView(viewer, channelId, privacy.selectMember(viewer));
    }

    Map<String, Object> progressView(long viewer, Long channelId, Map<String, Object> row)
    {
        // 频道进度页的范围枚举没有 FRIEND：仅好友可见对频道成员等同仅自己。
        String aggregate = row == null ? "PUBLIC" : "FRIEND".equals(row.get("aggregateScope")) ? "SELF" : (String) row.get("aggregateScope");
        String detail = row == null ? "SELF" : (String) row.get("detailScope");
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("aggregate_scope", aggregate);
        v.put("detail_scope", detail);
        v.put("photo_scope", row == null ? "SELF" : row.get("photoScope"));
        v.put("leaderboard_scope", row == null ? "IN" : row.get("leaderboardScope"));
        v.put("revision", row == null ? 0L : ChannelGuildAccess.toLong(row.get("revision")));
        v.put("updated_at", row == null ? null : row.get("updatedAt"));
        v.put("verified_at", null);
        if (channelId != null)
        {
            Map<String, Object> ch = privacy.selectChannel(channelId);
            Map<String, Object> effective = new LinkedHashMap<>();
            String channelAggregate = ch == null ? "PUBLIC" : (String) ch.get("aggregateScope");
            effective.put("aggregate_scope", stricter(aggregate, "FRIEND".equals(channelAggregate) ? "SELF" : channelAggregate));
            effective.put("detail_scope", stricter(detail, ch == null ? "SELF" : (String) ch.get("detailScope")));
            v.put("effective", effective);
        }
        v.put("applies_to", "CHANNEL_PROGRESS");
        return v;
    }

    static final List<String> BREADTH = List.of("SELF", "TEAM", "FRIEND", "CHANNEL", "PUBLIC");

    static String stricter(String a, String b) { return BREADTH.indexOf(a) <= BREADTH.indexOf(b) ? a : b; }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveProgress(long viewer, Map<String, Object> body)
    {
        if (body == null || !body.keySet().containsAll(Set.of("aggregate_scope", "detail_scope", "photo_scope", "leaderboard_scope", "request_id"))
                || !Set.of("aggregate_scope", "detail_scope", "photo_scope", "leaderboard_scope", "expected_revision", "expected_updated_at", "request_id")
                        .containsAll(body.keySet())) throw ChannelRejection.badField("body");
        if (!(body.get("request_id") instanceof String requestId) || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw ChannelRejection.badField("request_id");
        String aggregate = (String) enumValue(body.get("aggregate_scope"), AGGREGATE);
        String detail = (String) enumValue(body.get("detail_scope"), DETAIL);
        String photo = (String) enumValue(body.get("photo_scope"), Set.of("SELF", "TEAM", "ADMIN"));
        String board = (String) enumValue(body.get("leaderboard_scope"), Set.of("IN", "OUT"));
        Object expectedRevision = body.get("expected_revision");
        Object expectedUpdated = body.get("expected_updated_at");
        if (expectedRevision == null && !(expectedUpdated instanceof String)) throw ChannelRejection.badField("expected_revision");
        String fp = ChannelReceipts.fingerprint("PROGRESS_PRIVACY", viewer, body);
        Map<String, Object> out = ChannelReceipts.once(channels, viewer, "PRIVACY", requestId, fp, "PROGRESS", 0L, () ->
        {
            Map<String, Object> row = privacy.lockMember(viewer);
            boolean ok = expectedRevision != null
                    ? (row == null ? 0L : ChannelGuildAccess.toLong(row.get("revision"))) == ((Number) expectedRevision).longValue()
                    : row != null && expectedUpdated.equals(row.get("updatedAt"));
            if (!ok) throw new ChannelRejection(409, "PROGRESS_PRIVACY_CONFLICT", "隐私设置已变化，请刷新");
            Map<String, Object> next = memberRow(viewer, row);
            next.put("aggregateScope", aggregate);
            next.put("detailScope", detail);
            next.put("photoScope", photo);
            next.put("leaderboardScope", board);
            privacy.upsertMember(next);
            Map<String, Object> v = progressView(viewer, null, privacy.selectMember(viewer));
            v.put("request_id", requestId);
            v.put("status", "APPLIED");
            v.put("outcome_known", true);
            v.put("outcome_confirmed", true);
            return v;
        });
        return out;
    }

    public Map<String, Object> progressReceipt(long viewer, String requestId)
    {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw ChannelRejection.badField("request_id");
        Map<String, Object> stored = channels.selectReceipt(viewer, "PRIVACY", requestId);
        if (stored == null || stored.get("response") == null || !"PROGRESS".equals(stored.get("operation")))
            throw new ChannelRejection(404, "PROGRESS_REQUEST_NOT_FOUND", "没有找到该请求");
        Map<String, Object> out = ChannelReceipts.decode(stored.get("response").toString());
        out.put("replayed", true);
        return out;
    }

    // ================================================================ helpers

    private static Map<String, Object> memberRow(long viewer, Map<String, Object> row)
    {
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("memberId", viewer);
        next.put("aggregateScope", row == null ? "PUBLIC" : row.get("aggregateScope"));
        next.put("detailScope", row == null ? "SELF" : row.get("detailScope"));
        next.put("photoScope", row == null ? "SELF" : row.get("photoScope"));
        next.put("leaderboardScope", row == null ? "IN" : row.get("leaderboardScope"));
        return next;
    }

    static Object enumValue(Object raw, Set<String> allowed)
    {
        if (!(raw instanceof String s) || !allowed.contains(s)) throw ChannelRejection.badField("scope");
        return s;
    }

    static String upperUuid(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")) throw ChannelRejection.badField("request_id");
        return s;
    }
}
