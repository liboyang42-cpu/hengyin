package com.chengyinhub.business.service.channel.platform;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 频道治理（App NativeChannelModerationFlow / AppealFlow / ReportFlow / PersonalStateFlow / RoleFlow）。
 * <ul>
 * <li>处置：禁言 TIMEOUT（频道级，TIMEOUT_MEMBER）、踢出 KICK / 封禁 BAN（空间级，KICK_MEMBER / BAN_MEMBER）。
 *     俱乐部踢出/封禁走既有 IClubLifecycleCommandService.removeMember（含群聊清理）；有效禁言/封禁由频道发送闸拦截。</li>
 * <li>申诉：被处置人 7 天内一次；补充证据最多 5 条；撤回。裁决在后台（无 App 路由）。</li>
 * <li>举报：JSON 原请求，审核单复用 IAuditTaskService.reportContent("IM_MESSAGE")。</li>
 * <li>角色写：只有空间主理人/管理员（MANAGE_ROLE）可建/改/删角色、给成员授予/撤销角色；系统角色不可改。</li>
 * </ul>
 * 所有写都以 request_id 落原回执；回执路由见 ApiChannelGovernanceController。
 */
@Service
public class ChannelGovernanceService
{
    static final Set<String> REASON_CODES = Set.of("NOISE", "DANGEROUS", "MINOR_SAFETY", "SELF_HARM", "SEXUAL_CONTENT", "HARASSMENT",
            "HATE", "FRAUD", "IMPERSONATION", "FALSE_INFORMATION", "SPAM", "PRIVACY", "INTELLECTUAL_PROPERTY", "OTHER");
    static final long APPEAL_WINDOW_MS = 7L * 24 * 3600 * 1000;
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ChannelGovernanceMapper gov;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired
    private ChannelPlatformService platform;
    @Autowired(required = false)
    private IClubLifecycleCommandService clubLifecycle;
    @Autowired(required = false)
    private IAuditTaskService auditTasks;
    @Autowired(required = false)
    private MuteBridge muteBridge;

    /** 本人静音 → 通知偏好永久静音（由通知服务实现，缺省不联动）。 */
    public interface MuteBridge
    {
        void setChannelPermanentMute(long memberId, long channelId, boolean muted);
        boolean effectiveMuted(long memberId, long channelId);
    }

    // ================================================================ moderation

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> moderate(long viewer, String operation, Map<String, Object> body)
    {
        if (!Set.of("timeout", "kick", "ban").contains(operation)) throw ChannelRejection.notFound();
        Map<String, Object> b = body(body, Set.of("guild_kind", "guild_id", "target_member_id", "scope_type", "scope_id", "duration_sec",
                "reason_code", "reason_text", "request_id", "delete_message_sec"));
        String requestId = uuid(b.get("request_id"));
        int guildKind = (int) positive(b.get("guild_kind"), "guild_kind");
        long guildId = positive(b.get("guild_id"), "guild_id");
        long target = positive(b.get("target_member_id"), "target_member_id");
        String scopeType = text(b.get("scope_type"), "scope_type", 8);
        long scopeId = positive(b.get("scope_id"), "scope_id");
        int seconds = (int) integer(b.get("duration_sec"), "duration_sec");
        int deleteSeconds = (int) integer(b.get("delete_message_sec"), "delete_message_sec");
        String code = text(b.get("reason_code"), "reason_code", 32);
        String reason = text(b.get("reason_text"), "reason_text", 500);
        String type = operation.toUpperCase();
        boolean timeout = "TIMEOUT".equals(type);
        if (!REASON_CODES.contains(code) || reason.isBlank()) throw ChannelRejection.badField("reason");
        if (!scopeType.equals(timeout ? "CHANNEL" : "GUILD") || (!timeout && scopeId != guildId)) throw ChannelRejection.badField("scope");
        if (timeout ? (seconds < 60 || seconds > 2419200) : "BAN".equals(type) ? (seconds < 60 || seconds > 7776000) : seconds != 0)
            throw ChannelRejection.badField("duration_sec");
        if (deleteSeconds < 0 || deleteSeconds > 604800 || (!"BAN".equals(type) && deleteSeconds != 0)) throw ChannelRejection.badField("delete_message_sec");
        String fp = ChannelReceipts.fingerprint("MODERATE_" + type, guildKind + ":" + guildId, b);
        return ChannelReceipts.once(channels, viewer, "MOD", requestId, fp, "MODERATE_" + type, timeout ? scopeId : 0L, () ->
        {
            GuildStanding actor = access.requireMember(guildKind, guildId, viewer);
            if (timeout)
            {
                ChannelRow row = platform.visibleChannel(viewer, scopeId);
                if (row.getGuildKind() != guildKind || row.getGuildId() != guildId) throw ChannelRejection.badField("scope_id");
                if (!has(access.effective(row, viewer), TIMEOUT_MEMBER)) throw ChannelRejection.forbidden();
            }
            else requireGuild(viewer, guildKind, guildId, "KICK".equals(type) ? KICK_MEMBER : BAN_MEMBER);
            if (target == viewer) throw new ChannelRejection(409, "MODERATION_TARGET_INVALID", "不能处置自己");
            GuildStanding standing = access.standing(guildKind, guildId, target);
            if (standing == GuildStanding.OWNER || (standing == GuildStanding.ADMIN && actor != GuildStanding.OWNER))
                throw new ChannelRejection(403, "MODERATION_HIERARCHY", "不能处置同级或更高的成员");
            if (standing == GuildStanding.NONE && !"BAN".equals(type)) throw new ChannelRejection(404, "MEMBER_NOT_FOUND", "对方不是空间成员");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("guildKind", guildKind);
            row.put("guildId", guildId);
            row.put("targetMemberId", target);
            row.put("actionType", type);
            row.put("status", "KICK".equals(type) ? "APPLIED" : "ACTIVE");
            row.put("scopeType", scopeType);
            row.put("scopeId", scopeId);
            row.put("reasonCode", code);
            row.put("reasonText", reason.strip());
            row.put("durationSec", seconds);
            row.put("deleteMessageSec", deleteSeconds);
            row.put("operatorId", viewer);
            row.put("requestId", requestId);
            gov.insertAction(row);
            long actionId = ChannelGuildAccess.toLong(row.get("id"));
            if (!timeout && standing != GuildStanding.NONE) endMembership(guildKind, guildId, target, viewer, requestId);
            if (deleteSeconds > 0)
            {
                gov.insertRecallMutations(guildKind, guildId, target, deleteSeconds);
                gov.recallWindow(guildKind, guildId, target, deleteSeconds);
            }
            Map<String, Object> out = actionView(gov.selectAction(actionId), viewer);
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            return out;
        });
    }

    private void endMembership(int guildKind, long guildId, long target, long actor, String requestId)
    {
        if (guildKind == ChannelGuildAccess.GUILD_CLUB)
        {
            if (clubLifecycle == null) throw new IllegalStateException("club lifecycle unavailable");
            clubLifecycle.removeMember(guildId, target, actor, "channel-moderation-" + requestId);
        }
        else gov.endTeamMembership(guildId, target);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revoke(long viewer, long actionId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "version", "reason"));
        String requestId = uuid(b.get("request_id"));
        if (!(b.get("version") instanceof String v) || !v.matches("[0-9]{1,18}")) throw ChannelRejection.badField("version");
        long expected = Long.parseLong((String) b.get("version"));
        String reason = text(b.get("reason"), "reason", 500);
        if (reason.isBlank()) throw ChannelRejection.badField("reason");
        String fp = ChannelReceipts.fingerprint("MODERATE_REVOKE", actionId, b);
        return ChannelReceipts.once(channels, viewer, "MOD", requestId, fp, "MODERATE_REVOKE", 0L, () ->
        {
            Map<String, Object> action = gov.lockAction(actionId);
            if (action == null) throw new ChannelRejection(404, "MODERATION_ACTION_NOT_FOUND", "处置不存在");
            int guildKind = (int) ChannelGuildAccess.toLong(action.get("guildKind"));
            long guildId = ChannelGuildAccess.toLong(action.get("guildId"));
            String type = String.valueOf(action.get("actionType"));
            if ("TIMEOUT".equals(type))
            {
                ChannelRow row = platform.visibleChannel(viewer, ChannelGuildAccess.toLong(action.get("scopeId")));
                if (!has(access.effective(row, viewer), TIMEOUT_MEMBER)) throw ChannelRejection.forbidden();
            }
            else if ("BAN".equals(type)) requireGuild(viewer, guildKind, guildId, BAN_MEMBER);
            else throw new ChannelRejection(409, "MODERATION_NOT_REVOCABLE", "踢出不可撤销");
            if (!"ACTIVE".equals(action.get("status"))) throw new ChannelRejection(409, "MODERATION_NOT_ACTIVE", "处置已结束");
            if (gov.revokeAction(actionId, expected, viewer, reason.strip()) != 1)
                throw new ChannelRejection(409, "VERSION_CONFLICT", "处置已变化，请刷新");
            Map<String, Object> out = actionView(gov.selectAction(actionId), viewer);
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            out.put("revoked_action_id", String.valueOf(actionId));
            return out;
        });
    }

    public Map<String, Object> actions(long viewer, int guildKind, long guildId, String cursor, int limit)
    {
        requireGuild(viewer, guildKind, guildId, VIEW_AUDIT);
        Long before = cursor == null ? null : parseId(cursor);
        int size = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = gov.listActions(guildKind, guildId, null, before, size + 1);
        boolean more = rows.size() > size;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : more ? rows.subList(0, size) : rows) list.add(actionView(r, viewer));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("has_more", more);
        out.put("next_cursor", more ? list.get(list.size() - 1).get("action_id") : null);
        return out;
    }

    /** 被处置人本人视角：踢出/封禁后已不是成员，仍可读自己的处置与申诉入口。 */
    public Map<String, Object> mine(long viewer, int guildKind, long guildId)
    {
        ChannelGuildAccess.requireSupportedKind(guildKind);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : gov.listActions(guildKind, guildId, viewer, null, 50)) list.add(actionView(r, viewer));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("has_more", false);
        return out;
    }

    Map<String, Object> actionView(Map<String, Object> r, long viewer)
    {
        long id = ChannelGuildAccess.toLong(r.get("id"));
        long target = ChannelGuildAccess.toLong(r.get("targetMemberId"));
        Date starts = ChannelRejection.toDate(r.get("startsAt"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("action_id", String.valueOf(id));
        v.put("guild_kind", (int) ChannelGuildAccess.toLong(r.get("guildKind")));
        v.put("guild_id", String.valueOf(ChannelGuildAccess.toLong(r.get("guildId"))));
        v.put("target_member_id", String.valueOf(target));
        v.put("action_type", r.get("actionType"));
        v.put("status", r.get("status"));
        v.put("version", String.valueOf(ChannelGuildAccess.toLong(r.get("version"))));
        v.put("scope_type", r.get("scopeType"));
        v.put("scope_id", String.valueOf(ChannelGuildAccess.toLong(r.get("scopeId"))));
        v.put("reason_code", r.get("reasonCode"));
        v.put("reason_text", r.get("reasonText"));
        v.put("duration_sec", (int) ChannelGuildAccess.toLong(r.get("durationSec")));
        v.put("starts_at", iso(starts));
        v.put("expires_at", iso(ChannelRejection.toDate(r.get("expiresAt"))));
        if (target == viewer)
        {
            Map<String, Object> appeal = gov.selectAppealByAction(id, viewer);
            Date deadline = starts == null ? null : new Date(starts.getTime() + APPEAL_WINDOW_MS);
            boolean open = appeal == null && deadline != null && deadline.after(new Date()) && !"REVOKED".equals(r.get("status"));
            v.put("appealable", open ? 1 : 0);
            v.put("appeal_deadline", iso(deadline));
            v.put("existing_appeal_id", appeal == null ? null : String.valueOf(ChannelGuildAccess.toLong(appeal.get("id"))));
        }
        return v;
    }

    // ================================================================ appeals

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitAppeal(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("guild_kind", "guild_id", "action_id", "reason_code", "reason_text", "submit_request_id"));
        String requestId = uuid(b.get("submit_request_id"));
        int guildKind = (int) positive(b.get("guild_kind"), "guild_kind");
        long guildId = positive(b.get("guild_id"), "guild_id");
        long actionId = positive(b.get("action_id"), "action_id");
        String code = text(b.get("reason_code"), "reason_code", 32);
        String reason = text(b.get("reason_text"), "reason_text", 500);
        if (reason.isBlank()) throw ChannelRejection.badField("reason_text");
        String fp = ChannelReceipts.fingerprint("APPEAL_SUBMIT", actionId, b);
        return ChannelReceipts.once(channels, viewer, "APPEAL", requestId, fp, "APPEAL_SUBMIT", 0L, () ->
        {
            Map<String, Object> action = gov.lockAction(actionId);
            if (action == null || ChannelGuildAccess.toLong(action.get("targetMemberId")) != viewer
                    || ChannelGuildAccess.toLong(action.get("guildKind")) != guildKind || ChannelGuildAccess.toLong(action.get("guildId")) != guildId)
                throw new ChannelRejection(404, "MODERATION_ACTION_NOT_FOUND", "处置不存在");
            if (!Integer.valueOf(1).equals(actionView(action, viewer).get("appealable")))
                throw new ChannelRejection(409, "APPEAL_NOT_ALLOWED", "该处置当前不可申诉");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actionId", actionId);
            row.put("appellantMemberId", viewer);
            row.put("guildKind", guildKind);
            row.put("guildId", guildId);
            row.put("reasonCode", code);
            row.put("reasonText", reason.strip());
            try { gov.insertAppeal(row); }
            catch (RuntimeException e)
            {
                if (ChannelRejection.isDuplicateKey(e)) throw new ChannelRejection(409, "APPEAL_EXISTS", "已提交过申诉");
                throw e;
            }
            Map<String, Object> out = appealView(gov.selectAppeal(ChannelGuildAccess.toLong(row.get("id"))));
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            return out;
        });
    }

    public Map<String, Object> appeal(long viewer, long appealId)
    {
        Map<String, Object> a = gov.selectAppeal(appealId);
        if (a == null) throw new ChannelRejection(404, "APPEAL_NOT_FOUND", "申诉不存在");
        if (ChannelGuildAccess.toLong(a.get("appellantMemberId")) != viewer)
            requireGuild(viewer, (int) ChannelGuildAccess.toLong(a.get("guildKind")), ChannelGuildAccess.toLong(a.get("guildId")), VIEW_AUDIT);
        return appealView(a);
    }

    public Map<String, Object> appeals(long viewer, int guildKind, long guildId)
    {
        ChannelGuildAccess.requireSupportedKind(guildKind);
        boolean auditor;
        try { auditor = has(access.guildBits(guildKind, guildId, viewer), VIEW_AUDIT); }
        catch (ChannelRejection e) { auditor = false; }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> a : gov.listAppeals(guildKind, guildId, auditor ? null : viewer)) list.add(appealView(a));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("has_more", false);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> appealEvidence(long viewer, long appealId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "evidence_json"));
        String requestId = uuid(b.get("request_id"));
        if (!(b.get("evidence_json") instanceof Map<?, ?> evidence) || !(evidence.get("text") instanceof String t)
                || t.isBlank() || t.length() > 500 || evidence.size() != 1) throw ChannelRejection.badField("evidence_json");
        String fp = ChannelReceipts.fingerprint("APPEAL_EVIDENCE", appealId, b);
        return ChannelReceipts.once(channels, viewer, "APPEAL", requestId, fp, "APPEAL_EVIDENCE", 0L, () ->
        {
            Map<String, Object> a = ownAppeal(viewer, appealId);
            if (!Integer.valueOf(1).equals(appealView(a).get("can_add_evidence"))) throw new ChannelRejection(409, "APPEAL_EVIDENCE_CLOSED", "不能再补充证据");
            String item;
            try { item = JSON.writeValueAsString(Map.of("text", ((String) evidence.get("text")).strip(), "at", iso(new Date()))); }
            catch (Exception e) { throw new IllegalStateException(e); }
            if (gov.appendEvidence(appealId, item) != 1) throw new ChannelRejection(409, "APPEAL_EVIDENCE_CLOSED", "不能再补充证据");
            Map<String, Object> out = appealView(gov.selectAppeal(appealId));
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            return out;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> appealWithdraw(long viewer, long appealId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "reason"));
        String requestId = uuid(b.get("request_id"));
        String reason = text(b.get("reason"), "reason", 500);
        String fp = ChannelReceipts.fingerprint("APPEAL_WITHDRAW", appealId, b);
        return ChannelReceipts.once(channels, viewer, "APPEAL", requestId, fp, "APPEAL_WITHDRAW", 0L, () ->
        {
            ownAppeal(viewer, appealId);
            if (gov.withdrawAppeal(appealId, reason.strip()) != 1) throw new ChannelRejection(409, "APPEAL_CLOSED", "申诉已结束");
            Map<String, Object> out = appealView(gov.selectAppeal(appealId));
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            return out;
        });
    }

    private Map<String, Object> ownAppeal(long viewer, long appealId)
    {
        Map<String, Object> a = gov.lockAppeal(appealId);
        if (a == null || ChannelGuildAccess.toLong(a.get("appellantMemberId")) != viewer)
            throw new ChannelRejection(404, "APPEAL_NOT_FOUND", "申诉不存在");
        return a;
    }

    Map<String, Object> appealView(Map<String, Object> a)
    {
        List<?> evidence;
        try { evidence = a.get("evidenceJson") == null ? List.of() : JSON.readValue(a.get("evidenceJson").toString(), List.class); }
        catch (Exception e) { evidence = List.of(); }
        String status = String.valueOf(a.get("status"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("appeal_id", String.valueOf(ChannelGuildAccess.toLong(a.get("id"))));
        v.put("action_id", String.valueOf(ChannelGuildAccess.toLong(a.get("actionId"))));
        v.put("appellant_member_id", String.valueOf(ChannelGuildAccess.toLong(a.get("appellantMemberId"))));
        v.put("guild_kind", (int) ChannelGuildAccess.toLong(a.get("guildKind")));
        v.put("guild_id", String.valueOf(ChannelGuildAccess.toLong(a.get("guildId"))));
        v.put("status", status);
        v.put("reason_code", a.get("reasonCode"));
        v.put("reason_text", a.get("reasonText"));
        v.put("decision_reason", a.get("decisionReason"));
        v.put("evidence", evidence);
        v.put("can_add_evidence", Set.of("PENDING", "REVIEWING").contains(status) && evidence.size() < 5 ? 1 : 0);
        v.put("create_time", iso(ChannelRejection.toDate(a.get("createTime"))));
        return v;
    }

    // ================================================================ reports

    /** body 为 App 原始 JSON 字节（body_digest = 其 SHA-256）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> report(long viewer, Map<String, Object> body, String digest)
    {
        Map<String, Object> b = body(body, Set.of("target_type", "target_id", "reason_code", "reason_text", "submit_request_id"));
        String requestId = uuid(b.get("submit_request_id"));
        if (!"MESSAGE".equals(b.get("target_type"))) throw ChannelRejection.badField("target_type");
        if (!(b.get("target_id") instanceof String raw) || !raw.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("target_id");
        long messageId = Long.parseLong((String) b.get("target_id"));
        String code = text(b.get("reason_code"), "reason_code", 32);
        if (!REASON_CODES.contains(code) || "NOISE".equals(code)) throw ChannelRejection.badField("reason_code");
        String note = b.get("reason_text") == null ? null : text(b.get("reason_text"), "reason_text", 500);
        String fp = ChannelReceipts.fingerprint("REPORT", messageId, b);
        return ChannelReceipts.once(channels, viewer, "REPORT", requestId, fp, "REPORT", 0L, () ->
        {
            Map<String, Object> m = gov.selectMessageForReport(messageId);
            if (m == null) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
            long conversation = ChannelGuildAccess.toLong(m.get("conversationId"));
            platform.visibleChannel(viewer, conversation);
            if (ChannelGuildAccess.toLong(m.get("senderId")) == viewer) throw new ChannelRejection(409, "REPORT_SELF", "不能举报自己的消息");
            if (auditTasks == null) throw new IllegalStateException("audit task service unavailable");
            Long task = auditTasks.reportContent("IM_MESSAGE", messageId, viewer, code + (note == null ? "" : "：" + note));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reporterMemberId", viewer);
            row.put("targetType", "MESSAGE");
            row.put("targetId", messageId);
            row.put("channelId", conversation);
            row.put("reasonCode", code);
            row.put("reasonText", note);
            row.put("bodyDigest", digest);
            row.put("auditTaskId", task);
            row.put("requestId", requestId);
            gov.insertReport(row);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", "APPLIED");
            out.put("request_id", requestId);
            out.put("reporter_member_id", String.valueOf(viewer));
            out.put("target_type", "MESSAGE");
            out.put("target_id", String.valueOf(messageId));
            out.put("channel_id", String.valueOf(conversation));
            out.put("body_digest", digest);
            out.put("report_id", String.valueOf(ChannelGuildAccess.toLong(row.get("id"))));
            out.put("audit_task_id", String.valueOf(task));
            out.put("status", "PENDING");
            return out;
        });
    }

    // ================================================================ personal state

    public static Map<String, Object> myState(ChannelGovernanceMapper gov, MuteBridge muteBridge, long viewer, long channelId)
    {
        Map<String, Object> s = gov.selectMemberState(channelId, viewer);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("pinned", s == null ? 0 : (int) ChannelGuildAccess.toLong(s.get("pinned")));
        v.put("hidden", s == null ? 0 : (int) ChannelGuildAccess.toLong(s.get("hidden")));
        boolean own = s != null && ChannelGuildAccess.toLong(s.get("muted")) == 1;
        boolean effective = own || (muteBridge != null && muteBridge.effectiveMuted(viewer, channelId));
        v.put("muted", effective ? 1 : 0);
        v.put("permanent_muted", own);
        v.put("muted_until", null);
        return v;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setMemberState(long viewer, long channelId, Map<String, Object> body)
    {
        if (body == null || body.size() != 2 || !body.containsKey("request_id")) throw ChannelRejection.badField("body");
        String requestId = uuid(body.get("request_id"));
        String field = body.keySet().stream().filter(k -> !"request_id".equals(k)).findFirst().orElse("");
        if (!Set.of("pinned", "hidden", "muted").contains(field)) throw ChannelRejection.badField("field");
        long value = integer(body.get(field), field);
        if (value != 0 && value != 1) throw ChannelRejection.badField(field);
        String fp = ChannelReceipts.fingerprint("MEMBER_STATE", channelId, body);
        return ChannelReceipts.once(channels, viewer, "STATE", requestId, fp, "MEMBER_STATE", channelId, () ->
        {
            platform.visibleChannel(viewer, channelId);
            gov.upsertMemberState(channelId, viewer, field, (int) value);
            if ("muted".equals(field) && muteBridge != null) muteBridge.setChannelPermanentMute(viewer, channelId, value == 1);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("status", "APPLIED");
            out.put("channel_id", String.valueOf(channelId));
            out.put("member_id", String.valueOf(viewer));
            out.putAll(myState(gov, muteBridge, viewer, channelId));
            return out;
        });
    }

    public Map<String, Object> memberStateReceipt(long viewer, String requestId)
    {
        Map<String, Object> stored = channels.selectReceipt(viewer, "STATE", uuid(requestId));
        if (stored == null || stored.get("response") == null)
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("status", "NOT_FOUND");
            out.put("resume_with_same_request", 1);
            return out;
        }
        Map<String, Object> result = ChannelReceipts.decode(stored.get("response").toString());
        long channelId = ChannelGuildAccess.toLong(stored.get("channelId"));
        try { platform.visibleChannel(viewer, channelId); }
        catch (ChannelRejection gone)
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("status", "APPLIED");
            out.put("result_redacted", 1);
            return out;
        }
        return result;
    }

    // ================================================================ role writes

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRole(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "guild_kind", "guild_id", "name", "permissions", "hoist", "mentionable",
                "reason", "color", "display_level"));
        String requestId = uuid(b.get("request_id"));
        int guildKind = (int) positive(b.get("guild_kind"), "guild_kind");
        long guildId = positive(b.get("guild_id"), "guild_id");
        String name = roleName(b.get("name"));
        long bits = roleBits(b.get("permissions"));
        String fp = ChannelReceipts.fingerprint("ROLE_CREATE", guildKind + ":" + guildId, b);
        return ChannelReceipts.once(channels, viewer, "GOV", requestId, fp, "ROLE_CREATE", 0L, () ->
        {
            requireGuild(viewer, guildKind, guildId, MANAGE_ROLE);
            if (gov.countRoles(guildKind, guildId) >= 250) throw new ChannelRejection(409, "ROLE_LIMIT_REACHED", "角色数量已达上限");
            if (gov.countRoleName(guildKind, guildId, name, 0) > 0) throw new ChannelRejection(409, "ROLE_NAME_TAKEN", "角色名已存在");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("guildKind", guildKind);
            row.put("guildId", guildId);
            row.put("name", name);
            row.put("position", gov.countRoles(guildKind, guildId) + 1);
            row.put("permissions", bits);
            row.put("mentionable", flag(b.get("mentionable"), "mentionable"));
            row.put("hoist", flag(b.get("hoist"), "hoist"));
            row.put("color", color(b.get("color")));
            row.put("displayLevel", b.get("display_level") == null ? null : text(b.get("display_level"), "display_level", 8));
            gov.insertRole(row);
            return roleResult(requestId, gov.selectRoleRow(ChannelGuildAccess.toLong(row.get("id"))));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> editRole(long viewer, long roleId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "version", "permissions", "reason", "name", "position", "status", "color",
                "hoist", "mentionable"));
        String requestId = uuid(b.get("request_id"));
        long expected = version(b.get("version"));
        long bits = roleBits(b.get("permissions"));
        String fp = ChannelReceipts.fingerprint("ROLE_EDIT", roleId, b);
        return ChannelReceipts.once(channels, viewer, "GOV", requestId, fp, "ROLE_EDIT", 0L, () ->
        {
            Map<String, Object> role = managedRole(viewer, roleId);
            if (ChannelGuildAccess.toLong(role.get("version")) != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "角色已变化，请刷新");
            Map<String, Object> row = new LinkedHashMap<>(role);
            row.put("expectedVersion", expected);
            row.put("permissions", bits);
            if (b.containsKey("name"))
            {
                String name = roleName(b.get("name"));
                if (gov.countRoleName((int) ChannelGuildAccess.toLong(role.get("guildKind")), ChannelGuildAccess.toLong(role.get("guildId")), name, roleId) > 0)
                    throw new ChannelRejection(409, "ROLE_NAME_TAKEN", "角色名已存在");
                row.put("name", name);
                long position = integer(b.get("position"), "position");
                if (position < 0 || position > 1000) throw ChannelRejection.badField("position");
                row.put("position", position);
                row.put("status", flag(b.get("status"), "status"));
                row.put("color", color(b.get("color")));
                row.put("hoist", flag(b.get("hoist"), "hoist"));
                row.put("mentionable", flag(b.get("mentionable"), "mentionable"));
            }
            if (gov.updateRole(row) != 1) throw new ChannelRejection(409, "VERSION_CONFLICT", "角色已变化，请刷新");
            return roleResult(requestId, gov.selectRoleRow(roleId));
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteRole(long viewer, long roleId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "version", "reason"));
        String requestId = uuid(b.get("request_id"));
        long expected = version(b.get("version"));
        String fp = ChannelReceipts.fingerprint("ROLE_DELETE", roleId, b);
        return ChannelReceipts.once(channels, viewer, "GOV", requestId, fp, "ROLE_DELETE", 0L, () ->
        {
            Map<String, Object> role = managedRole(viewer, roleId);
            if (ChannelGuildAccess.toLong(role.get("version")) != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "角色已变化，请刷新");
            int revoked = gov.deleteRoleGrants(roleId);
            if (gov.deleteRole(roleId, expected) != 1) throw new ChannelRejection(409, "VERSION_CONFLICT", "角色已变化，请刷新");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            out.put("id", String.valueOf(roleId));
            out.put("deleted", true);
            out.put("revoked_grants", revoked);
            out.put("audit_id", auditId(requestId));
            return out;
        });
    }

    /** PUT /api/im/members/{member}/roles：成员在该空间的完整角色集合。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setMemberRoles(long viewer, long memberId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "guild_kind", "guild_id", "role_ids", "scope_type", "scope_id", "expires_at",
                "grant_reason"));
        String requestId = uuid(b.get("request_id"));
        int guildKind = (int) positive(b.get("guild_kind"), "guild_kind");
        long guildId = positive(b.get("guild_id"), "guild_id");
        List<Long> wanted = ids(b.get("role_ids"));
        Grant grant = grant(b);
        String fp = ChannelReceipts.fingerprint("MEMBER_ROLES", memberId, b);
        return ChannelReceipts.once(channels, viewer, "GOV", requestId, fp, "MEMBER_ROLES", 0L, () ->
        {
            requireGuild(viewer, guildKind, guildId, MANAGE_ROLE);
            if (access.standing(guildKind, guildId, memberId) == GuildStanding.NONE) throw new ChannelRejection(404, "MEMBER_NOT_FOUND", "对方不是空间成员");
            for (long roleId : wanted) assignable(gov.lockRole(roleId), guildKind, guildId);
            grant.checkScope(guildKind, guildId);
            Set<Long> current = new TreeSet<>(gov.selectMemberRoleIds(guildKind, guildId, memberId));
            List<String> granted = new ArrayList<>(), revoked = new ArrayList<>();
            for (long roleId : wanted)
            {
                gov.grantRole(roleId, memberId, grant.scopeType, grant.scopeId, grant.expiresAt, grant.reason, viewer);
                if (!current.contains(roleId)) granted.add(String.valueOf(roleId));
            }
            for (long roleId : current)
                if (!wanted.contains(roleId)) { gov.revokeRole(roleId, memberId); revoked.add(String.valueOf(roleId)); }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            out.put("member_id", String.valueOf(memberId));
            out.put("role_ids", wanted.stream().map(String::valueOf).toList());
            out.put("granted", granted);
            out.put("revoked", revoked);
            out.put("audit_id", auditId(requestId));
            return out;
        });
    }

    /** PUT /api/im/roles/{id}/members：角色的完整成员集合。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setRoleMembers(long viewer, long roleId, Map<String, Object> body)
    {
        Map<String, Object> b = body(body, Set.of("request_id", "member_ids", "scope_type", "scope_id", "expires_at", "grant_reason"));
        String requestId = uuid(b.get("request_id"));
        List<Long> wanted = ids(b.get("member_ids"));
        Grant grant = grant(b);
        String fp = ChannelReceipts.fingerprint("ROLE_MEMBERS", roleId, b);
        return ChannelReceipts.once(channels, viewer, "GOV", requestId, fp, "ROLE_MEMBERS", 0L, () ->
        {
            Map<String, Object> role = gov.lockRole(roleId);
            if (role == null) throw new ChannelRejection(404, "ROLE_NOT_FOUND", "角色不存在");
            int guildKind = (int) ChannelGuildAccess.toLong(role.get("guildKind"));
            long guildId = ChannelGuildAccess.toLong(role.get("guildId"));
            requireGuild(viewer, guildKind, guildId, MANAGE_ROLE);
            assignable(role, guildKind, guildId);
            grant.checkScope(guildKind, guildId);
            for (long memberId : wanted)
                if (access.standing(guildKind, guildId, memberId) == GuildStanding.NONE) throw new ChannelRejection(404, "MEMBER_NOT_FOUND", "对方不是空间成员");
            Set<Long> current = new TreeSet<>(gov.selectRoleMemberIds(roleId));
            List<String> added = new ArrayList<>(), removed = new ArrayList<>();
            for (long memberId : wanted)
            {
                gov.grantRole(roleId, memberId, grant.scopeType, grant.scopeId, grant.expiresAt, grant.reason, viewer);
                if (!current.contains(memberId)) added.add(String.valueOf(memberId));
            }
            for (long memberId : current)
                if (!wanted.contains(memberId)) { gov.revokeRole(roleId, memberId); removed.add(String.valueOf(memberId)); }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("state", "APPLIED");
            out.put("role_id", String.valueOf(roleId));
            out.put("added", added);
            out.put("removed", removed);
            out.put("audit_id", auditId(requestId));
            return out;
        });
    }

    private record Grant(String scopeType, long scopeId, Date expiresAt, String reason)
    {
        void checkScope(int guildKind, long guildId)
        {
            if ("GUILD".equals(scopeType) && scopeId != guildId) throw ChannelRejection.badField("scope_id");
        }
    }

    private Grant grant(Map<String, Object> b)
    {
        String scope = text(b.get("scope_type"), "scope_type", 8);
        if (!Set.of("GUILD", "CHANNEL").contains(scope)) throw ChannelRejection.badField("scope_type");
        long scopeId = positive(b.get("scope_id"), "scope_id");
        Date expires = null;
        if (b.get("expires_at") != null)
        {
            try { expires = Date.from(java.time.OffsetDateTime.parse(text(b.get("expires_at"), "expires_at", 40)).toInstant()); }
            catch (java.time.format.DateTimeParseException e) { throw ChannelRejection.badField("expires_at"); }
            if (!expires.after(new Date())) throw ChannelRejection.badField("expires_at");
        }
        String reason = text(b.get("grant_reason"), "grant_reason", 120);
        return new Grant(scope, scopeId, expires, reason);
    }

    private Map<String, Object> managedRole(long viewer, long roleId)
    {
        Map<String, Object> role = gov.lockRole(roleId);
        if (role == null) throw new ChannelRejection(404, "ROLE_NOT_FOUND", "角色不存在");
        requireGuild(viewer, (int) ChannelGuildAccess.toLong(role.get("guildKind")), ChannelGuildAccess.toLong(role.get("guildId")), MANAGE_ROLE);
        if (ChannelGuildAccess.toLong(role.get("isSystem")) == 1) throw new ChannelRejection(409, "ROLE_SYSTEM", "系统角色不可修改");
        return role;
    }

    private static void assignable(Map<String, Object> role, int guildKind, long guildId)
    {
        if (role == null || ChannelGuildAccess.toLong(role.get("guildKind")) != guildKind || ChannelGuildAccess.toLong(role.get("guildId")) != guildId)
            throw new ChannelRejection(404, "ROLE_NOT_FOUND", "角色不存在");
        if (ChannelGuildAccess.toLong(role.get("isSystem")) == 1 || ChannelGuildAccess.toLong(role.get("status")) != 1)
            throw new ChannelRejection(409, "ROLE_NOT_ASSIGNABLE", "该角色不可授予");
    }

    Map<String, Object> roleResult(String requestId, Map<String, Object> r)
    {
        Map<String, Object> out = roleView(r);
        out.put("request_id", requestId);
        out.put("state", "APPLIED");
        out.put("audit_id", auditId(requestId));
        return out;
    }

    public static Map<String, Object> roleView(Map<String, Object> r)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(ChannelGuildAccess.toLong(r.get("id"))));
        v.put("guild_kind", (int) ChannelGuildAccess.toLong(r.get("guildKind")));
        v.put("guild_id", String.valueOf(ChannelGuildAccess.toLong(r.get("guildId"))));
        v.put("name", r.get("name"));
        v.put("version", String.valueOf(ChannelGuildAccess.toLong(r.get("version"))));
        v.put("permissions", ChannelCapability.hex(ChannelGuildAccess.toLong(r.get("permissions"))));
        v.put("position", (int) ChannelGuildAccess.toLong(r.get("position")));
        v.put("status", (int) ChannelGuildAccess.toLong(r.get("status")));
        v.put("is_system", (int) ChannelGuildAccess.toLong(r.get("isSystem")));
        v.put("managed", 0);
        v.put("is_everyone", 0);
        v.put("hoist", (int) ChannelGuildAccess.toLong(r.get("hoist")));
        v.put("mentionable", (int) ChannelGuildAccess.toLong(r.get("mentionable")));
        v.put("color", r.get("color") == null ? null : (int) ChannelGuildAccess.toLong(r.get("color")));
        v.put("display_level", r.get("displayLevel"));
        return v;
    }

    /** 审计号 = 原回执的稳定派生号（同一 request_id 重放得到同一个）。 */
    private static String auditId(String requestId)
    {
        long h = 0;
        for (char c : requestId.toCharArray()) h = h * 31 + c;
        return String.valueOf((h & 0x1fffffffffffffL) | 1L);
    }

    // ================================================================ receipts

    /** 治理原回执（处置与申诉共用 /governance/moderation/requests/{id}）。 */
    public Map<String, Object> moderationReceipt(long viewer, String requestId)
    {
        String id = uuid(requestId);
        Map<String, Object> stored = channels.selectReceipt(viewer, "MOD", id);
        if (stored == null || stored.get("response") == null) stored = channels.selectReceipt(viewer, "APPEAL", id);
        return wrapReceipt(id, stored);
    }

    public Map<String, Object> reportReceipt(long viewer, String requestId)
    {
        String id = uuid(requestId);
        Map<String, Object> stored = channels.selectReceipt(viewer, "REPORT", id);
        if (stored == null || stored.get("response") == null)
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", id);
            out.put("state", "NOT_FOUND");
            out.put("retry_with_original_request", true);
            return out;
        }
        return ChannelReceipts.decode(stored.get("response").toString());
    }

    static Map<String, Object> wrapReceipt(String requestId, Map<String, Object> stored)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_id", requestId);
        if (stored == null || stored.get("response") == null)
        {
            out.put("state", "NOT_FOUND");
            out.put("outcome_known", false);
            out.put("retry_with_original_request", true);
            return out;
        }
        out.put("state", "APPLIED");
        out.put("outcome_known", true);
        out.put("operation", stored.get("operation"));
        out.put("result", ChannelReceipts.decode(stored.get("response").toString()));
        return out;
    }

    // ================================================================ helpers

    private void requireGuild(long viewer, int guildKind, long guildId, ChannelCapability needed)
    {
        long bits = access.guildBits(guildKind, guildId, viewer);
        if (bits == 0) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能操作");
        if (!has(bits, needed) && !has(bits, ADMINISTRATOR)) throw ChannelRejection.forbidden();
    }

    static Map<String, Object> body(Map<String, Object> body, Set<String> allowed)
    {
        if (body == null) throw ChannelRejection.badField("请求体为空");
        for (String k : body.keySet()) if (!allowed.contains(k)) throw ChannelRejection.badField("未知字段 " + k);
        return body;
    }

    static String uuid(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"))
            throw ChannelRejection.badField("request_id");
        return s;
    }

    /** 正整数：JSON 数字或十进制字符串。 */
    static long positive(Object raw, String field)
    {
        long v;
        if (raw instanceof Integer || raw instanceof Long) v = ((Number) raw).longValue();
        else if (raw instanceof String s && s.matches("[1-9][0-9]{0,18}")) v = Long.parseLong(s);
        else throw ChannelRejection.badField(field);
        if (v <= 0) throw ChannelRejection.badField(field);
        return v;
    }

    static long integer(Object raw, String field)
    {
        if (!(raw instanceof Integer || raw instanceof Long)) throw ChannelRejection.badField(field);
        return ((Number) raw).longValue();
    }

    static String text(Object raw, String field, int max)
    {
        if (!(raw instanceof String s) || s.length() > max || s.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw ChannelRejection.badField(field);
        return s;
    }

    static int flag(Object raw, String field)
    {
        long v = integer(raw, field);
        if (v != 0 && v != 1) throw ChannelRejection.badField(field);
        return (int) v;
    }

    static Integer color(Object raw)
    {
        if (raw == null) return null;
        long v = integer(raw, "color");
        if (v < 0 || v > 0xFFFFFF) throw ChannelRejection.badField("color");
        return (int) v;
    }

    static long version(Object raw)
    {
        if (raw instanceof String s && s.matches("[0-9]{1,18}")) return Long.parseLong(s);
        if (raw instanceof Integer || raw instanceof Long) return ((Number) raw).longValue();
        throw ChannelRejection.badField("version");
    }

    static String roleName(Object raw)
    {
        String name = text(raw, "name", 64).strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 32) throw ChannelRejection.badField("name");
        return name;
    }

    static long roleBits(Object raw)
    {
        if (!(raw instanceof String s)) throw ChannelRejection.badField("permissions");
        Long bits = ChannelCapability.parseHex(s);
        if (bits == null || (bits & ~ChannelCapability.all()) != 0) throw ChannelRejection.badField("permissions");
        return bits;
    }

    static List<Long> ids(Object raw)
    {
        if (!(raw instanceof List<?> list) || list.size() > 500) throw ChannelRejection.badField("ids");
        List<Long> out = new ArrayList<>();
        for (Object o : list)
        {
            long id = positive(o, "ids");
            if (out.contains(id)) throw ChannelRejection.badField("ids");
            out.add(id);
        }
        return out;
    }

    static Long parseId(String raw)
    {
        if (!raw.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("cursor");
        return Long.parseLong(raw);
    }

    static String iso(Date d)
    {
        if (d == null) return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(d);
    }
}
