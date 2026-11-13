package com.chengyinhub.business.service.channel.platform;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelAdmissionMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.friend.FriendInviteMapper;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;

/**
 * 空间准入（App ChannelAdmissionGovernanceClient）：/api/join-request/{submit,cancel,approve,reject}、/api/space/{join-pause,leave}、
 * /api/invite/revoke 与原回执 /api/channel-platform/admission/requests/{id}。
 * 成员关系仍由原链路写：俱乐部 IClubMemberService.requestJoin/reviewJoinRequest + IClubLifecycleCommandService.quit，
 * 队伍 IPlayTeamService.apply/withdraw/handle/quit。本服务只记原请求台账、暂停开关与有效封禁拦截。
 */
@Service
public class ChannelAdmissionService
{
    @Autowired
    private ChannelAdmissionMapper admission;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired(required = false)
    private ChannelGovernanceMapper governance;
    @Autowired(required = false)
    private FriendInviteMapper invites;
    @Autowired(required = false)
    private IClubMemberService clubMembers;
    @Autowired(required = false)
    private IClubLifecycleCommandService clubLifecycle;
    @Autowired(required = false)
    private IPlayTeamService teams;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, Set.of("guild_kind", "guild_id", "request_id", "invite_id", "message"));
        String requestId = requestId(b.get("request_id"));
        int kind = (int) ChannelGovernanceService.positive(b.get("guild_kind"), "guild_kind");
        long guild = ChannelGovernanceService.positive(b.get("guild_id"), "guild_id");
        Long inviteId = b.get("invite_id") == null ? null : ChannelGovernanceService.positive(b.get("invite_id"), "invite_id");
        String message = b.get("message") == null ? null : ChannelGovernanceService.text(b.get("message"), "message", 60).strip();
        return once(viewer, requestId, "SUBMIT", b, () ->
        {
            ChannelGuildAccess.requireSupportedKind(kind);
            if (access.standing(kind, guild, viewer) != GuildStanding.NONE) throw new ChannelRejection(409, "ALREADY_MEMBER", "你已经是成员");
            Map<String, Object> setting = admission.selectSetting(kind, guild);
            if (setting != null && ChannelGuildAccess.toLong(setting.get("effective")) == 1) throw new ChannelRejection(409, "JOIN_PAUSED", "暂停入会中");
            if (governance != null && governance.countActiveBan(kind, guild, viewer) > 0) throw new ChannelRejection(403, "MEMBER_BANNED", "你已被该空间封禁");
            boolean joined;
            if (kind == ChannelGuildAccess.GUILD_CLUB)
            {
                if (clubMembers == null) throw new IllegalStateException("club member service unavailable");
                try { joined = IClubMemberService.JOIN_STATE_JOINED.equals(clubMembers.requestJoin(guild, viewer, message == null ? "" : message)); }
                catch (IllegalArgumentException | IllegalStateException refused) { throw new ChannelRejection(409, "JOIN_REFUSED", refused.getMessage()); }
            }
            else
            {
                if (teams == null) throw new IllegalStateException("team service unavailable");
                try { teams.apply(guild, viewer, message); joined = false; }
                catch (RuntimeException refused) { throw new ChannelRejection(409, "JOIN_REFUSED", refused.getMessage()); }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("guildKind", kind);
            row.put("guildId", guild);
            row.put("memberId", viewer);
            row.put("inviteId", inviteId);
            row.put("message", message);
            row.put("status", joined ? 1 : 0);
            admission.insertRequest(row);
            Map<String, Object> out = result(requestId, ChannelGuildAccess.toLong(row.get("id")), joined ? 1 : 0);
            out.put("join_state", joined ? "JOINED" : "PENDING_REVIEW");
            out.put("guild_kind", kind);
            out.put("guild_id", String.valueOf(guild));
            return out;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, Set.of("id", "request_id"));
        String requestId = requestId(b.get("request_id"));
        long id = ChannelGovernanceService.positive(b.get("id"), "id");
        return once(viewer, requestId, "CANCEL", b, () ->
        {
            Map<String, Object> row = pending(id);
            if (ChannelGuildAccess.toLong(row.get("memberId")) != viewer) throw new ChannelRejection(404, "JOIN_REQUEST_NOT_FOUND", "申请不存在");
            int kind = (int) ChannelGuildAccess.toLong(row.get("guildKind"));
            long guild = ChannelGuildAccess.toLong(row.get("guildId"));
            if (kind == ChannelGuildAccess.GUILD_CLUB)
            {
                if (clubMembers == null) throw new IllegalStateException("club member service unavailable");
                var membership = clubMembers.selectMembershipRecord(guild, viewer);
                if (membership != null && Integer.valueOf(0).equals(membership.getStatus())) clubMembers.deleteByClubAndMember(guild, viewer);
            }
            else if (teams != null) teams.withdraw(guild, viewer);
            admission.setStatus(id, 3, null, null, null);
            return result(requestId, id, 3);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(long viewer, boolean approve, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, approve ? Set.of("id", "request_id", "remark")
                : Set.of("id", "request_id", "reject_reason_code", "reject_note"));
        String requestId = requestId(b.get("request_id"));
        long id = ChannelGovernanceService.positive(b.get("id"), "id");
        Integer reasonCode = approve ? null : (int) ChannelGovernanceService.integer(b.get("reject_reason_code"), "reject_reason_code");
        String note = (String) (approve ? b.get("remark") : b.get("reject_note"));
        if (note != null) ChannelGovernanceService.text(note, "note", 200);
        return once(viewer, requestId, approve ? "APPROVE" : "REJECT", b, () ->
        {
            Map<String, Object> row = pending(id);
            int kind = (int) ChannelGuildAccess.toLong(row.get("guildKind"));
            long guild = ChannelGuildAccess.toLong(row.get("guildId"));
            long member = ChannelGuildAccess.toLong(row.get("memberId"));
            GuildStanding actor = access.standing(kind, guild, viewer);
            if (actor != GuildStanding.OWNER && actor != GuildStanding.ADMIN) throw ChannelRejection.forbidden();
            try
            {
                if (kind == ChannelGuildAccess.GUILD_CLUB)
                {
                    if (clubMembers == null) throw new IllegalStateException("club member service unavailable");
                    clubMembers.reviewJoinRequest(guild, member, viewer, approve, note);
                }
                else
                {
                    if (teams == null) throw new IllegalStateException("team service unavailable");
                    teams.handle(guild, viewer, member, approve);
                }
            }
            catch (IllegalArgumentException refused) { throw new ChannelRejection(409, "JOIN_REVIEW_REFUSED", refused.getMessage()); }
            admission.setStatus(id, approve ? 1 : 2, viewer, reasonCode, note);
            Map<String, Object> out = result(requestId, id, approve ? 1 : 2);
            out.put("join_state", approve ? "JOINED" : "REJECTED");
            return out;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> leave(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, Set.of("guild_kind", "guild_id", "request_id"));
        String requestId = requestId(b.get("request_id"));
        int kind = (int) ChannelGovernanceService.positive(b.get("guild_kind"), "guild_kind");
        long guild = ChannelGovernanceService.positive(b.get("guild_id"), "guild_id");
        return once(viewer, requestId, "LEAVE", b, () ->
        {
            GuildStanding standing = access.standing(kind, guild, viewer);
            if (standing == GuildStanding.OWNER) throw new ChannelRejection(409, "OWNER_CANNOT_LEAVE", "主理人/队长不能直接退出");
            boolean left = standing != GuildStanding.NONE;
            if (left)
            {
                if (kind == ChannelGuildAccess.GUILD_CLUB)
                {
                    if (clubLifecycle == null) throw new IllegalStateException("club lifecycle unavailable");
                    clubLifecycle.quit(guild, viewer, "space-leave-" + requestId);
                }
                else
                {
                    if (teams == null) throw new IllegalStateException("team service unavailable");
                    teams.quit(guild, viewer);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("left", true);
            out.put("was_member", left);
            out.put("guild_kind", kind);
            out.put("guild_id", String.valueOf(guild));
            return out;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pause(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, Set.of("guild_kind", "guild_id", "paused", "reason", "paused_until", "request_id"));
        String requestId = requestId(b.get("request_id"));
        int kind = (int) ChannelGovernanceService.positive(b.get("guild_kind"), "guild_kind");
        long guild = ChannelGovernanceService.positive(b.get("guild_id"), "guild_id");
        if (!(b.get("paused") instanceof Boolean paused)) throw ChannelRejection.badField("paused");
        String reason = ChannelGovernanceService.text(b.get("reason"), "reason", 200);
        Date until = null;
        if (b.get("paused_until") != null)
        {
            try { until = Date.from(OffsetDateTime.parse(ChannelGovernanceService.text(b.get("paused_until"), "paused_until", 40)).toInstant()); }
            catch (java.time.format.DateTimeParseException e) { throw ChannelRejection.badField("paused_until"); }
        }
        Date untilFinal = until;
        return once(viewer, requestId, "PAUSE", b, () ->
        {
            GuildStanding actor = access.standing(kind, guild, viewer);
            if (actor != GuildStanding.OWNER && actor != GuildStanding.ADMIN) throw ChannelRejection.forbidden();
            admission.upsertSetting(kind, guild, paused ? 1 : 0, paused ? untilFinal : null, reason.strip(), viewer);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("join_paused", paused);
            out.put("guild_kind", kind);
            out.put("guild_id", String.valueOf(guild));
            return out;
        });
    }

    /** 撤销邀请：好友频道邀请的邀请人，或该空间主理人/管理员。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revokeInvite(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, Set.of("invite_id", "reason", "request_id"));
        String requestId = requestId(b.get("request_id"));
        long inviteId = ChannelGovernanceService.positive(b.get("invite_id"), "invite_id");
        ChannelGovernanceService.text(b.get("reason"), "reason", 200);
        return once(viewer, requestId, "INVITE_REVOKE", b, () ->
        {
            Map<String, Object> invite = invites == null ? null : invites.lockChannelInvite(inviteId);
            if (invite == null) throw new ChannelRejection(404, "INVITE_NOT_FOUND", "邀请不存在");
            GuildStanding actor = access.standing((int) ChannelGuildAccess.toLong(invite.get("guildKind")),
                    ChannelGuildAccess.toLong(invite.get("guildId")), viewer);
            if (ChannelGuildAccess.toLong(invite.get("inviterId")) != viewer && actor != GuildStanding.OWNER && actor != GuildStanding.ADMIN)
                throw ChannelRejection.forbidden();
            invites.setChannelInviteState(inviteId, "INVITE_ENDED");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("request_id", requestId);
            out.put("invite_id", String.valueOf(inviteId));
            out.put("status", 1);
            return out;
        });
    }

    public Map<String, Object> receipt(long viewer, String requestId)
    {
        String id = requestId(requestId);
        Map<String, Object> stored = channels.selectReceipt(viewer, "ADMIT", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_id", id);
        if (stored == null || stored.get("response") == null)
        {
            out.put("state", "NOT_FOUND");
            return out;
        }
        out.put("state", "APPLIED");
        out.put("operation", stored.get("operation"));
        Map<String, Object> result = ChannelReceipts.decode(stored.get("response").toString());
        result.remove("replayed");
        if (ChannelOnboardingService.OPERATION.equals(stored.get("operation")))
        {
            // 引导回执带原请求定位与指纹（App NativeChannelOnboardingFlow.recover 逐项核对）。
            out.put("guild_kind", result.get("guild_kind"));
            out.put("guild_id", result.get("guild_id"));
            out.put("object_id", 0);
            out.put("request_hash", stored.get("fingerprint"));
        }
        out.put("result", result);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> once(long viewer, String requestId, String operation, Map<String, Object> body,
            java.util.function.Supplier<Map<String, Object>> work)
    {
        Map<String, Object> out = ChannelReceipts.once(channels, viewer, "ADMIT", requestId,
                ChannelReceipts.fingerprint(operation, viewer, body), operation, 0L, work);
        out.remove("replayed");
        return out;
    }

    private Map<String, Object> pending(long id)
    {
        Map<String, Object> row = admission.lockRequest(id);
        if (row == null) throw new ChannelRejection(404, "JOIN_REQUEST_NOT_FOUND", "申请不存在");
        if (ChannelGuildAccess.toLong(row.get("status")) != 0) throw new ChannelRejection(409, "JOIN_REQUEST_CLOSED", "申请已处理");
        return row;
    }

    private static Map<String, Object> result(String requestId, long id, int status)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request_id", requestId);
        out.put("id", String.valueOf(id));
        out.put("status", status);
        return out;
    }

    static String requestId(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[A-Za-z0-9:._-]{1,64}")) throw ChannelRejection.badField("request_id");
        return s;
    }
}
