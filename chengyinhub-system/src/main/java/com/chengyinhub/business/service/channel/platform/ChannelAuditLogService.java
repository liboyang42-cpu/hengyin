package com.chengyinhub.business.service.channel.platform;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.ADMINISTRATOR;
import static com.chengyinhub.business.service.channel.platform.ChannelCapability.VIEW_AUDIT;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelAuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 空间审计（App NativeChannelAuditFlow，GET /api/im/audit-log）。
 * <p>原始事件只来自 im_channel_op_log（频道生命周期/编辑/覆盖）与 im_moderation_action（处置与撤销）。读时在空间链头行锁下
 * 把尚未投影的原始行按原始时间追加进 im_channel_audit_log，每行 row_hash = SHA-256(prev_hash ‖ 长度前缀字段)。
 * 不推断历史、不声称外部锚定：verify 只报告本库内链与原始行的覆盖情况（boundary 固定文案，anchor_evidence 为 null）。</p>
 */
@Service
public class ChannelAuditLogService
{
    public static final String GENESIS = "0".repeat(64);
    public static final String BOUNDARY = "original-channel-audit-projection-v1; no historical backfill or external-anchor claim";
    static final int PROJECT_BATCH = 200;
    static final int VERIFY_PAGE = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ChannelAuditMapper audit;
    @Autowired
    private ChannelGuildAccess access;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> read(long viewer, int guildKind, long guildId, String before, int limit, boolean verify)
    {
        if (limit < 1 || limit > 50) throw ChannelRejection.badField("limit");
        Long beforeId = before == null || before.isEmpty() ? null : ChannelGovernanceService.parseId(before);
        long bits = access.guildBits(guildKind, guildId, viewer);
        if (bits == 0) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能查看");
        if (!has(bits, VIEW_AUDIT) && !has(bits, ADMINISTRATOR)) throw ChannelRejection.forbidden();

        project(guildKind, guildId);
        Map<String, Object> proof = verify ? verify(guildKind, guildId) : null;
        boolean frozen = proof != null && !Boolean.TRUE.equals(proof.get("linked_projection_ok"));

        List<Map<String, Object>> list = new ArrayList<>();
        boolean more = false;
        if (!frozen)
        {
            List<Map<String, Object>> rows = audit.listRows(guildKind, guildId, beforeId, limit + 1);
            more = rows.size() > limit;
            for (Map<String, Object> r : more ? rows.subList(0, limit) : rows) list.add(view(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guild_kind", guildKind);
        out.put("guild_id", String.valueOf(guildId));
        out.put("list", list);
        out.put("has_more", more);
        out.put("next_cursor", more ? list.get(list.size() - 1).get("id") : null);
        if (proof != null) out.put("verify", proof);
        return out;
    }

    /** 在链头行锁下追加未投影的原始行（每次最多 PROJECT_BATCH 条，剩余计入 unproven_original_rows）。 */
    void project(int guildKind, long guildId)
    {
        audit.ensureHead(guildKind, guildId, GENESIS);
        Map<String, Object> head = audit.lockHead(guildKind, guildId);
        if (head == null) throw new IllegalStateException("Audit head missing");
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> o : audit.pendingChannelOps(guildKind, guildId, PROJECT_BATCH)) pending.add(fromChannelOp(o));
        for (Map<String, Object> m : audit.pendingModerations(guildKind, guildId, PROJECT_BATCH)) pending.add(fromModeration(m));
        for (Map<String, Object> m : audit.pendingRevocations(guildKind, guildId, PROJECT_BATCH)) pending.add(fromRevocation(m));
        if (pending.isEmpty()) return;
        pending.sort(Comparator.<Map<String, Object>, String>comparing(r -> String.valueOf(r.get("originalTimeText")))
                .thenComparing(r -> String.valueOf(r.get("source")))
                .thenComparingLong(r -> ChannelGuildAccess.toLong(r.get("originalId"))));
        String prev = String.valueOf(head.get("headHash"));
        long headId = ChannelGuildAccess.toLong(head.get("headId"));
        for (Map<String, Object> row : pending.subList(0, Math.min(PROJECT_BATCH, pending.size())))
        {
            row.put("guildKind", guildKind);
            row.put("guildId", guildId);
            row.put("prevHash", prev);
            row.put("rowHash", rowHash(prev, row));
            if (audit.insertRow(row) != 1) throw new IllegalStateException("Audit projection insert failed");
            prev = String.valueOf(row.get("rowHash"));
            headId = ChannelGuildAccess.toLong(row.get("id"));
        }
        if (audit.updateHead(guildKind, guildId, headId, prev) != 1) throw new IllegalStateException("Audit head update failed");
    }

    /** 自创世起逐行重算；第一处断链之后的行一律算作未证明。 */
    Map<String, Object> verify(int guildKind, long guildId)
    {
        String prev = GENESIS;
        long checked = 0, through = 0, verifiedThrough = 0, broken = 0;
        String head = GENESIS;
        boolean linked = true;
        long after = 0;
        while (true)
        {
            List<Map<String, Object>> page = audit.chainRows(guildKind, guildId, after, VERIFY_PAGE);
            for (Map<String, Object> r : page)
            {
                long id = ChannelGuildAccess.toLong(r.get("id"));
                checked++;
                through = id;
                after = id;
                if (linked && prev.equals(r.get("prevHash")) && rowHash(prev, r).equals(r.get("rowHash")))
                {
                    prev = String.valueOf(r.get("rowHash"));
                    head = prev;
                    verifiedThrough = id;
                }
                else
                {
                    linked = false;
                    broken++;
                }
            }
            if (page.size() < VERIFY_PAGE) break;
        }
        long unproven = broken + audit.countUnprojected(guildKind, guildId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("chain_ok", linked && unproven == 0);
        v.put("linked_projection_ok", linked);
        v.put("unproven_original_rows", unproven);
        v.put("checked_rows", checked);
        v.put("through_original_id", String.valueOf(through));
        v.put("verified_through_original_id", String.valueOf(verifiedThrough));
        v.put("head_hash", head);
        v.put("frozen", !linked);
        v.put("anchor_evidence", null);
        v.put("externally_anchored", false);
        v.put("boundary", BOUNDARY);
        return v;
    }

    // ---------------------------------------------------------------- original → projection

    static Map<String, Object> fromChannelOp(Map<String, Object> o)
    {
        Map<String, Object> r = base("CHANNEL_OP", o.get("id"), o.get("channelId"), o.get("operatorId"), o.get("originalTime"),
                o.get("originalTimeText"));
        r.put("action", "CHANNEL_" + o.get("action"));
        r.put("targetType", "CHANNEL");
        r.put("targetId", ChannelGuildAccess.toLong(o.get("channelId")));
        r.put("reason", o.get("reason"));
        Map<String, Object> changes = new TreeMap<>();
        if (o.get("fromStatus") != null) changes.put("from_status", (int) ChannelGuildAccess.toLong(o.get("fromStatus")));
        if (o.get("toStatus") != null) changes.put("to_status", (int) ChannelGuildAccess.toLong(o.get("toStatus")));
        r.put("changesJson", changes.isEmpty() ? null : json(changes));
        r.put("changesRedacted", 0);
        return r;
    }

    /** 处置：理由正文不进审计（只给 reason_code），changes_redacted=1。 */
    static Map<String, Object> fromModeration(Map<String, Object> m)
    {
        Map<String, Object> r = base("MODERATION", m.get("id"), m.get("channelId"), m.get("operatorId"), m.get("originalTime"),
                m.get("originalTimeText"));
        r.put("action", "MODERATION_" + m.get("actionType"));
        r.put("targetType", "MEMBER");
        r.put("targetId", ChannelGuildAccess.toLong(m.get("targetMemberId")));
        r.put("reason", m.get("reasonCode"));
        Map<String, Object> changes = new TreeMap<>();
        changes.put("action_type", m.get("actionType"));
        changes.put("scope_type", m.get("scopeType"));
        changes.put("scope_id", String.valueOf(ChannelGuildAccess.toLong(m.get("scopeId"))));
        changes.put("duration_sec", (int) ChannelGuildAccess.toLong(m.get("durationSec")));
        changes.put("delete_message_sec", (int) ChannelGuildAccess.toLong(m.get("deleteMessageSec")));
        r.put("changesJson", json(changes));
        r.put("changesRedacted", 1);
        return r;
    }

    /** 撤销：撤销理由正文不进审计，changes_redacted=1。 */
    static Map<String, Object> fromRevocation(Map<String, Object> m)
    {
        Map<String, Object> r = base("MODERATION_REVOKE", m.get("id"), m.get("channelId"), m.get("revokedBy"), m.get("originalTime"),
                m.get("originalTimeText"));
        r.put("action", "MODERATION_REVOKE");
        r.put("targetType", "MODERATION_ACTION");
        r.put("targetId", ChannelGuildAccess.toLong(m.get("id")));
        r.put("reason", null);
        Map<String, Object> changes = new TreeMap<>();
        changes.put("action_id", String.valueOf(ChannelGuildAccess.toLong(m.get("id"))));
        changes.put("action_type", m.get("actionType"));
        changes.put("target_member_id", String.valueOf(ChannelGuildAccess.toLong(m.get("targetMemberId"))));
        r.put("changesJson", json(changes));
        r.put("changesRedacted", 1);
        return r;
    }

    private static Map<String, Object> base(String source, Object originalId, Object channelId, Object actor, Object time, Object timeText)
    {
        Date at = ChannelRejection.toDate(time);
        if (at == null || !(timeText instanceof String text) || text.isEmpty()) throw new IllegalStateException("Audit original time missing");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", source);
        r.put("originalId", ChannelGuildAccess.toLong(originalId));
        r.put("channelId", ChannelGuildAccess.toLong(channelId));
        long member = ChannelGuildAccess.toLong(actor);
        r.put("actorType", member > 0 ? "MEMBER" : "SYSTEM");
        r.put("actorMemberId", member > 0 ? member : null);
        r.put("originalTime", at);
        r.put("originalTimeText", text);
        return r;
    }

    // ---------------------------------------------------------------- hash / view

    /** 时间取库里的墙钟文本（DATE_FORMAT …%f），与 JDBC/JVM 时区换算无关，读回重算一致。 */
    static String rowHash(String prev, Map<String, Object> r)
    {
        Object[] fields = { "im-audit-v1", r.get("guildKind"), r.get("guildId"), r.get("channelId"), r.get("source"), r.get("originalId"),
                r.get("actorType"), r.get("actorMemberId"), r.get("action"), r.get("targetType"), r.get("targetId"), r.get("reason"),
                r.get("changesJson"), r.get("changesRedacted"), r.get("originalTimeText") };
        StringBuilder canonical = new StringBuilder(prev);
        for (Object f : fields)
        {
            String text = f == null ? "" : f instanceof Number n ? String.valueOf(n.longValue()) : f.toString();
            canonical.append(text.getBytes(StandardCharsets.UTF_8).length).append(':').append(text);
        }
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static Map<String, Object> view(Map<String, Object> r)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(ChannelGuildAccess.toLong(r.get("id"))));
        v.put("channel_id", String.valueOf(ChannelGuildAccess.toLong(r.get("channelId"))));
        v.put("actor_type", r.get("actorType"));
        long actor = ChannelGuildAccess.toLong(r.get("actorMemberId"));
        v.put("actor_member_id", actor > 0 ? String.valueOf(actor) : null);
        v.put("action", r.get("action"));
        v.put("target_type", r.get("targetType"));
        long target = ChannelGuildAccess.toLong(r.get("targetId"));
        v.put("target_id", target > 0 ? String.valueOf(target) : null);
        v.put("reason", r.get("reason"));
        v.put("create_time", ChannelGovernanceService.iso(ChannelRejection.toDate(r.get("originalTime"))));
        Object changes = r.get("changesJson");
        v.put("changes_available", changes != null);
        v.put("changes_redacted", ChannelGuildAccess.toLong(r.get("changesRedacted")) == 1);
        v.put("changes_json", changes == null ? null : ChannelReceipts.decode(changes.toString()));
        return v;
    }

    private static String json(Map<String, Object> value)
    {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode audit changes", e); }
    }
}
