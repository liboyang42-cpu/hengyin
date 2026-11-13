package com.chengyinhub.business.service.channel.platform;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelNotifyMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.IImService;

/**
 * 通知设置（App NativeChannelNotification* / IMExpandedService ordinary read+mute）。
 * <ul>
 * <li>偏好：scope 1 频道/会话、2 俱乐部、4 账号默认；level 3=继承，有效等级沿 频道→账号默认 解析（账号默认缺省 0=全部）。</li>
 * <li>写：PREFERENCE（JSON /api/im/notify-pref）、MUTE（JSON /api/im/mute，-1 永久/0 取消/n 分钟）、
 *     ACK（JSON /api/im/read）；均按 request_id 落原回执（族 NOTIFY），/api/im/notification-requests/{id} 回读。</li>
 * <li>MUTE 同时写旧 im_conversation_member.muted，保持既有角标口径一致。</li>
 * </ul>
 */
@Service
public class ChannelNotifyService implements ChannelGovernanceService.MuteBridge
{
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    static final Set<String> PREF_KEYS = Set.of("request_id", "scope_type", "scope_id", "level", "version", "muted_until", "quiet_start",
            "quiet_end", "quiet_tz", "suppress_broadcast", "suppress_team_status", "show_preview");

    @Autowired
    private ChannelNotifyMapper notify;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired(required = false)
    private IImService im;

    // ================================================================ reads

    public Map<String, Object> preferences(long viewer, Integer scopeType, String scopeId)
    {
        Map<String, Object> account = view(viewer, 4, viewer, notify.selectPref(viewer, 4, viewer), null);
        List<Map<String, Object>> list = new ArrayList<>();
        if (scopeType != null)
        {
            long id = scopeId(scopeType, scopeId);
            if (scopeType == 4)
            {
                if (id != viewer) throw ChannelRejection.forbidden();
                list.add(account);
            }
            else list.add(view(viewer, scopeType, id, notify.selectPref(viewer, scopeType, id), account));
        }
        else for (Map<String, Object> row : notify.listPrefs(viewer))
            list.add(view(viewer, (int) ChannelGuildAccess.toLong(row.get("scopeType")), ChannelGuildAccess.toLong(row.get("scopeId")), row, account));
        Map<String, Object> defaults = new LinkedHashMap<>(account);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("defaults", defaults);
        out.put("server_time", now());
        return out;
    }

    Map<String, Object> view(long viewer, int scopeType, long scopeId, Map<String, Object> row, Map<String, Object> account)
    {
        int level = row == null ? (scopeType == 4 ? 0 : 3) : (int) ChannelGuildAccess.toLong(row.get("level"));
        boolean permanent = row != null && ChannelGuildAccess.toLong(row.get("permanentMuted")) == 1;
        // 到期判定在库里做（与写入同一时钟），避免 JVM 与库时区不一致。
        Date until = row == null || ChannelGuildAccess.toLong(row.get("mutedActive")) == 0 ? null : ChannelRejection.toDate(row.get("mutedUntil"));
        String qs = row == null ? null : (String) row.get("quietStart");
        String qe = row == null ? null : (String) row.get("quietEnd");
        String qz = row == null ? null : (String) row.get("quietTz");
        boolean broadcast = row != null && ChannelGuildAccess.toLong(row.get("suppressBroadcast")) == 1;
        boolean team = row != null && ChannelGuildAccess.toLong(row.get("suppressTeamStatus")) == 1;
        Boolean preview = row == null || row.get("showPreview") == null ? null : ChannelGuildAccess.toLong(row.get("showPreview")) == 1;
        int effectiveLevel = level != 3 ? level : account == null ? 0 : (int) account.get("effective_level");
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("level", effectiveLevel);
        effective.put("muted", permanent || until != null);
        effective.put("permanent_muted", permanent);
        effective.put("quiet", inQuiet(qs, qe, qz));
        effective.put("suppress_broadcast", broadcast);
        effective.put("suppress_team_status", team);
        effective.put("show_preview", preview == null || preview);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("scope_type", scopeType);
        v.put("scope_id", String.valueOf(scopeId));
        v.put("level", level);
        v.put("effective_level", effectiveLevel);
        v.put("effective", effective);
        v.put("permanent_muted", permanent);
        v.put("muted_until", until == null ? null : iso(until));
        if (qs != null && qe != null && qz != null) { v.put("quiet_start", qs); v.put("quiet_end", qe); v.put("quiet_tz", qz); }
        v.put("suppress_broadcast", broadcast);
        v.put("suppress_team_status", team);
        v.put("show_preview", preview);
        v.put("version", row == null ? 0L : ChannelGuildAccess.toLong(row.get("version")));
        return v;
    }

    static boolean inQuiet(String start, String end, String zone)
    {
        if (start == null || end == null || zone == null) return false;
        try
        {
            LocalTime now = ZonedDateTime.now(ZoneId.of(zone)).toLocalTime(), s = LocalTime.parse(start), e = LocalTime.parse(end);
            return s.isBefore(e) ? !now.isBefore(s) && now.isBefore(e) : !now.isBefore(s) || now.isBefore(e);
        }
        catch (Exception invalid) { return false; }
    }

    // ================================================================ writes

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePreference(long viewer, Map<String, Object> body)
    {
        if (body == null || !PREF_KEYS.equals(body.keySet())) throw ChannelRejection.badField("body");
        String requestId = key(body.get("request_id"));
        int scopeType = (int) ChannelGovernanceService.integer(body.get("scope_type"), "scope_type");
        long scopeId = scopeId(scopeType, body.get("scope_id"));
        long level = ChannelGovernanceService.integer(body.get("level"), "level");
        long version = ChannelGovernanceService.integer(body.get("version"), "version");
        if (level < 0 || level > 3 || version < 0 || (scopeType == 4 && level == 3)) throw ChannelRejection.badField("level");
        Date until = body.get("muted_until") == null ? null : instant(body.get("muted_until"));
        String qs = (String) nullableText(body.get("quiet_start")), qe = (String) nullableText(body.get("quiet_end")), qz = (String) nullableText(body.get("quiet_tz"));
        if ((qs == null) != (qe == null) || (qs == null) != (qz == null)
                || (qs != null && (!qs.matches("([01][0-9]|2[0-3]):[0-5][0-9]") || !qe.matches("([01][0-9]|2[0-3]):[0-5][0-9]") || qs.equals(qe) || !validZone(qz))))
            throw ChannelRejection.badField("quiet");
        if (!(body.get("suppress_broadcast") instanceof Boolean broadcast) || !(body.get("suppress_team_status") instanceof Boolean team)
                || !(body.get("show_preview") == null || body.get("show_preview") instanceof Boolean)) throw ChannelRejection.badField("flags");
        if (scopeType == 4 && (scopeId != viewer || until != null || broadcast || team)) throw ChannelRejection.badField("account default");
        if (until != null && until.getTime() > System.currentTimeMillis() + 30L * 86400 * 1000) throw ChannelRejection.badField("muted_until");
        String fp = ChannelReceipts.fingerprint("PREFERENCE", scopeType + ":" + scopeId, body);
        return ChannelReceipts.once(channels, viewer, "NOTIFY", requestId, fp, "PREFERENCE", 0L, () ->
        {
            requireScope(viewer, scopeType, scopeId);
            Map<String, Object> current = notify.lockPref(viewer, scopeType, scopeId);
            if ((current == null ? 0L : ChannelGuildAccess.toLong(current.get("version"))) != version)
                throw new ChannelRejection(409, "NOTIFY_PREF_VERSION_CONFLICT", "设置已变化，请刷新");
            boolean permanent = current != null && ChannelGuildAccess.toLong(current.get("permanentMuted")) == 1;
            if (permanent && until != null) throw new ChannelRejection(409, "NOTIFY_PERMANENT_MUTE", "已永久静音，请先取消");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", viewer);
            row.put("scopeType", scopeType);
            row.put("scopeId", scopeId);
            row.put("level", level);
            row.put("permanentMuted", permanent ? 1 : 0);
            row.put("mutedUntil", dbTime(until));
            row.put("quietStart", qs);
            row.put("quietEnd", qe);
            row.put("quietTz", qz);
            row.put("suppressBroadcast", broadcast ? 1 : 0);
            row.put("suppressTeamStatus", team ? 1 : 0);
            row.put("showPreview", body.get("show_preview") == null ? null : ((Boolean) body.get("show_preview")) ? 1 : 0);
            notify.upsertPref(row);
            return receipt(requestId, "PREFERENCE", scopeType, scopeId, notify.selectPref(viewer, scopeType, scopeId));
        });
    }

    /** JSON /api/im/mute：minutes -1 = 永久静音，0 = 取消，n = 静音 n 分钟（≤ 30 天）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mute(long viewer, Map<String, Object> body)
    {
        if (body == null || !Set.of("conversation_id", "request_id", "minutes").equals(body.keySet())) throw ChannelRejection.badField("body");
        String requestId = key(body.get("request_id"));
        long conv = scopeId(1, body.get("conversation_id"));
        long minutes = ChannelGovernanceService.integer(body.get("minutes"), "minutes");
        if (minutes < -1 || minutes > 43200) throw ChannelRejection.badField("minutes");
        String fp = ChannelReceipts.fingerprint("MUTE", conv, body);
        return ChannelReceipts.once(channels, viewer, "NOTIFY", requestId, fp, "MUTE", conv, () ->
        {
            requireScope(viewer, 1, conv);
            Map<String, Object> current = notify.lockPref(viewer, 1, conv);
            Map<String, Object> row = carry(viewer, 1, conv, current);
            row.put("permanentMuted", minutes == -1 ? 1 : 0);
            row.put("mutedUntil", minutes > 0 ? dbTime(new Date(System.currentTimeMillis() + minutes * 60_000L)) : null);
            notify.upsertPref(row);
            if (im != null) im.setMuted(viewer, conv, minutes != 0);
            return receipt(requestId, "MUTE", 1, conv, notify.selectPref(viewer, 1, conv));
        });
    }

    /** JSON /api/im/read：频道已读确认（conversation_id = 频道）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ack(long viewer, Map<String, Object> body)
    {
        if (body == null || !Set.of("conversation_id", "last_message_id", "request_id").equals(body.keySet())) throw ChannelRejection.badField("body");
        String requestId = key(body.get("request_id"));
        long conv = scopeId(1, body.get("conversation_id"));
        if (!(body.get("last_message_id") instanceof String last) || !last.matches("0|[1-9][0-9]{0,18}")) throw ChannelRejection.badField("last_message_id");
        long readTo = Long.parseLong((String) body.get("last_message_id"));
        String fp = ChannelReceipts.fingerprint("ACK", conv, body);
        return ChannelReceipts.once(channels, viewer, "NOTIFY", requestId, fp, "ACK", conv, () ->
        {
            requireScope(viewer, 1, conv);
            if (im == null) throw new IllegalStateException("im service unavailable");
            im.markRead(viewer, conv, readTo);
            Map<String, Object> out = receipt(requestId, "ACK", 1, conv, null);
            out.put("result", new LinkedHashMap<>(Map.of("last_read_message_id", String.valueOf(readTo))));
            return out;
        });
    }

    public Map<String, Object> notificationReceipt(long viewer, String requestId)
    {
        Map<String, Object> stored = channels.selectReceipt(viewer, "NOTIFY", key(requestId));
        if (stored == null || stored.get("response") == null || String.valueOf(stored.get("operation")).startsWith("ORD_"))
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("status", "NOT_FOUND");
            out.put("outcome", "UNKNOWN");
            out.put("resume_with_same_request", true);
            return out;
        }
        Map<String, Object> out = ChannelReceipts.decode(stored.get("response").toString());
        out.remove("replayed");
        return out;
    }

    private Map<String, Object> receipt(String requestId, String operation, int scopeType, long scopeId, Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_id", requestId);
        out.put("status", "APPLIED");
        out.put("operation", operation);
        out.put("scope_type", scopeType);
        out.put("scope_id", String.valueOf(scopeId));
        out.put("result_redacted", false);
        out.put("must_refresh_state", true);
        out.put("outcome", "APPLIED");
        if (row != null)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", ChannelGuildAccess.toLong(row.get("version")));
            result.put("permanent_muted", ChannelGuildAccess.toLong(row.get("permanentMuted")) == 1);
            Date until = ChannelRejection.toDate(row.get("mutedUntil"));
            result.put("muted_until", until == null ? null : iso(until));
            out.put("result", result);
        }
        return out;
    }

    private Map<String, Object> carry(long viewer, int scopeType, long scopeId, Map<String, Object> current)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("memberId", viewer);
        row.put("scopeType", scopeType);
        row.put("scopeId", scopeId);
        row.put("level", current == null ? 3 : current.get("level"));
        row.put("quietStart", current == null ? null : current.get("quietStart"));
        row.put("quietEnd", current == null ? null : current.get("quietEnd"));
        row.put("quietTz", current == null ? null : current.get("quietTz"));
        row.put("suppressBroadcast", current == null ? 0 : current.get("suppressBroadcast"));
        row.put("suppressTeamStatus", current == null ? 0 : current.get("suppressTeamStatus"));
        row.put("showPreview", current == null ? null : current.get("showPreview"));
        return row;
    }

    /** 范围归属：会话须为本人成员行（频道走频道闸）；俱乐部须为成员；账号默认只能是本人。 */
    private void requireScope(long viewer, int scopeType, long scopeId)
    {
        if (scopeType == 4) { if (scopeId != viewer) throw ChannelRejection.forbidden(); return; }
        if (scopeType == 2) { access.requireMember(ChannelGuildAccess.GUILD_CLUB, scopeId, viewer); return; }
        Integer type = notify.conversationType(scopeId);
        if (type == null) throw new ChannelRejection(404, "CONVERSATION_NOT_FOUND", "会话不存在");
        if (type == 5)
        {
            ChannelRow row = channels.selectChannel(scopeId);
            if (row == null || ChannelPermissionCalculator.has(access.effective(row, viewer), ChannelCapability.VIEW_CHANNEL) == false)
                throw ChannelRejection.notFound();
            channels.ensureConversationMember(scopeId, viewer);
            return;
        }
        Map<String, Object> m = notify.membership(scopeId, viewer);
        if (m == null) throw new ChannelRejection(403, "CONVERSATION_FORBIDDEN", "不是会话成员");
    }

    // ================================================================ mute bridge (频道本人静音)

    @Override
    public void setChannelPermanentMute(long memberId, long channelId, boolean muted)
    {
        Map<String, Object> row = carry(memberId, 1, channelId, notify.lockPref(memberId, 1, channelId));
        row.put("permanentMuted", muted ? 1 : 0);
        row.put("mutedUntil", null);
        notify.upsertPref(row);
    }

    @Override
    public boolean effectiveMuted(long memberId, long channelId)
    {
        Map<String, Object> row = notify.selectPref(memberId, 1, channelId);
        if (row == null) return false;
        return ChannelGuildAccess.toLong(row.get("permanentMuted")) == 1 || ChannelGuildAccess.toLong(row.get("mutedActive")) == 1;
    }

    // ================================================================ unread summary / delivery log

    public Map<String, Object> unreadSummary(long viewer)
    {
        long dm = 0, sys = 0, channel = 0, mentions = 0;
        List<Map<String, Object>> byChannel = new ArrayList<>();
        for (Map<String, Object> r : notify.unreadRows(viewer))
        {
            int type = (int) ChannelGuildAccess.toLong(r.get("type"));
            long unread = Math.max(0, ChannelGuildAccess.toLong(r.get("unread")));
            long mention = ChannelGuildAccess.toLong(r.get("mentions"));
            long conv = ChannelGuildAccess.toLong(r.get("conversationId"));
            boolean muted = ChannelGuildAccess.toLong(r.get("muted")) == 1 || (type == 5 && effectiveMuted(viewer, conv));
            if (type == 5)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("channel_id", String.valueOf(conv));
                row.put("last_read_msg_id", String.valueOf(Math.max(0, ChannelGuildAccess.toLong(r.get("lastRead")))));
                row.put("unread", unread);
                row.put("mention_count", mention);
                row.put("read_state_available", 1);
                byChannel.add(row);
            }
            if (muted) continue;
            if (type == 1) dm += unread;
            else if (type == 2) sys += unread;
            else channel += unread;
            mentions += mention;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", dm + sys + channel);
        out.put("mention_count", mentions);
        out.put("has_mention", mentions > 0 ? 1 : 0);
        out.put("by_tab", new LinkedHashMap<>(Map.of("dm", dm, "sys", sys, "channel", channel)));
        out.put("by_channel", byChannel);
        out.put("channel_scope", "CHANNEL_INDEX");
        return out;
    }

    /** 投递结果日志：服务端未持久化已签名的投递事件，如实返回空页（delivery_proven=false）。 */
    public Map<String, Object> deliveryLog(long viewer, int limit, String event, String cursor)
    {
        if (limit < 1 || limit > 50 || (event != null && !event.matches("[A-Z][A-Z0-9_]{0,39}"))) throw ChannelRejection.badField("query");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-notification-results-v1");
        out.put("member_id", String.valueOf(viewer));
        out.put("list", List.of());
        out.put("next_cursor", null);
        out.put("has_more", false);
        out.put("sources", new LinkedHashMap<>(Map.of("wx", true, "apns", false, "inapp", false)));
        out.put("delivery_proven", false);
        return out;
    }

    // ================================================================ ordinary IM read / mute (form, request_id 可选)

    public Map<String, Object> ordinaryRead(long viewer, long conv, long readTo, String requestId)
    {
        String id = lowerUuid(requestId);
        String fp = ChannelReceipts.fingerprint("ORD_READ", conv, Map.of("read_to", readTo));
        return ChannelReceipts.once(channels, viewer, "NOTIFY", id, fp, "ORD_READ", conv, () ->
        {
            if (im == null) throw new IllegalStateException("im service unavailable");
            im.markRead(viewer, conv, readTo);
            return ordinaryReceipt("read", viewer, conv, id, "requested_read_to", String.valueOf(readTo), "APPLIED");
        });
    }

    public Map<String, Object> ordinaryMute(long viewer, long conv, boolean muted, String requestId)
    {
        String id = lowerUuid(requestId);
        String fp = ChannelReceipts.fingerprint("ORD_MUTE", conv, Map.of("muted", muted));
        return ChannelReceipts.once(channels, viewer, "NOTIFY", id, fp, "ORD_MUTE", conv, () ->
        {
            if (im == null) throw new IllegalStateException("im service unavailable");
            im.setMuted(viewer, conv, muted);
            return ordinaryReceipt("mute", viewer, conv, id, "requested_muted", muted, "APPLIED");
        });
    }

    public Map<String, Object> ordinaryReceipt(long viewer, String kind, long conv, String requestId, Object requested)
    {
        String id = lowerUuid(requestId);
        Map<String, Object> stored = channels.selectReceipt(viewer, "NOTIFY", id);
        String op = "read".equals(kind) ? "ORD_READ" : "ORD_MUTE";
        if (stored != null && stored.get("response") != null && op.equals(stored.get("operation"))
                && ChannelGuildAccess.toLong(stored.get("channelId")) == conv)
        {
            Map<String, Object> out = ChannelReceipts.decode(stored.get("response").toString());
            out.remove("replayed");
            return out;
        }
        return ordinaryReceipt(kind, viewer, conv, id, "read".equals(kind) ? "requested_read_to" : "requested_muted", requested, "NOT_FOUND");
    }

    static Map<String, Object> ordinaryReceipt(String kind, long viewer, long conv, String requestId, String field, Object requested, String outcome)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-ordinary-im-" + kind + "-receipt-v1");
        out.put("owner_member_id", String.valueOf(viewer));
        out.put("conversation_id", String.valueOf(conv));
        out.put("request_id", requestId);
        out.put(field, requested);
        out.put("outcome", outcome);
        return out;
    }

    public Map<String, Object> ordinaryState(long viewer, String kind, long conv)
    {
        Integer type = notify.conversationType(conv);
        Map<String, Object> m = notify.membership(conv, viewer);
        if (type == null || m == null || type < 1 || type > 3) throw new ChannelRejection(404, "CONVERSATION_NOT_FOUND", "会话不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-ordinary-im-" + kind + "-state-v1");
        out.put("owner_member_id", String.valueOf(viewer));
        out.put("conversation_id", String.valueOf(conv));
        out.put("conversation_type", type);
        if ("read".equals(kind))
        {
            out.put("latest_visible_message_id", String.valueOf(notify.latestVisible(conv)));
            out.put("last_read_message_id", String.valueOf(Math.max(0, ChannelGuildAccess.toLong(m.get("lastRead")))));
            out.put("unread", (int) Math.max(0, ChannelGuildAccess.toLong(m.get("unread"))));
        }
        else out.put("muted", ChannelGuildAccess.toLong(m.get("muted")) == 1);
        return out;
    }

    // ================================================================ helpers

    static long scopeId(int scopeType, Object raw)
    {
        if (scopeType != 1 && scopeType != 2 && scopeType != 4) throw ChannelRejection.badField("scope_type");
        if (!(raw instanceof String s) || !s.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("scope_id");
        return Long.parseLong(s);
    }

    static String key(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[A-Za-z0-9_-]{16,96}")) throw ChannelRejection.badField("request_id");
        return s;
    }

    static String lowerUuid(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ChannelRejection.badField("request_id");
        return s;
    }

    static Object nullableText(Object raw)
    {
        if (raw != null && !(raw instanceof String)) throw ChannelRejection.badField("text");
        return raw;
    }

    static boolean validZone(String zone)
    {
        try { ZoneId.of(zone); return true; }
        catch (Exception e) { return false; }
    }

    static Date instant(Object raw)
    {
        if (!(raw instanceof String s)) throw ChannelRejection.badField("muted_until");
        try { return Date.from(OffsetDateTime.parse(s).toInstant()); }
        catch (Exception e) { throw ChannelRejection.badField("muted_until"); }
    }

    static String iso(Date d) { return ISO.format(d.toInstant().atZone(ZoneId.of("Asia/Shanghai"))); }

    static String now() { return iso(new Date()); }

    /** 库会话时区为 +08:00：以上海墙钟字符串写 DATETIME，不依赖 JVM 默认时区。 */
    static String dbTime(Date d)
    {
        return d == null ? null : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(d.toInstant().atZone(ZoneId.of("Asia/Shanghai")));
    }
}
