package com.chengyinhub.business.clubinvite;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.clubinvite.mapper.ClubInviteMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.channel.platform.ChannelCapability;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelRow;

/**
 * W7 SOC-15: club invite links with an expiry (1 day, 7 days or never), an optional use limit (1, 10, 50 or no
 * limit), revoke, and an optional target channel opened after joining.
 *
 * <p>An invite grants nothing: accepting runs the club's own join path (public clubs join at once, others send a
 * request to the owner), so the join policy still applies. Uses are taken before the join and given back when the
 * join does not go through. Who may create invites is an open owner question; until it is answered only the club
 * owner and admins may ({@code chengyin.club.invite.members-create}, default false).
 */
@Service
public class ClubInviteService
{
    public static final Set<Integer> EXPIRY_DAYS = Set.of(0, 1, 7);
    public static final Set<Integer> MAX_USES = Set.of(0, 1, 10, 50);
    static final int LIST_LIMIT = 50;
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 10;
    static final long DAY_MS = 24L * 3600 * 1000;

    public static final String VALID = "VALID";
    public static final String EXPIRED = "EXPIRED";
    public static final String FULL = "FULL";
    public static final String REVOKED = "REVOKED";

    @Autowired
    private ClubInviteMapper mapper;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Value("${chengyin.club.invite.members-create:false}")
    private boolean membersMayCreate;

    private final SecureRandom random = new SecureRandom();

    public ClubInviteService() { }

    ClubInviteService(ClubInviteMapper mapper, ChannelPlatformMapper channels, ChannelGuildAccess access, boolean membersMayCreate)
    {
        this.mapper = mapper;
        this.channels = channels;
        this.access = access;
        this.membersMayCreate = membersMayCreate;
    }

    // ------------------------------------------------------------------ create, list, revoke

    public Map<String, Object> create(long me, Map<String, Object> body)
    {
        long clubId = positive(body == null ? null : body.get("clubId"), "INVITE_CLUB_INVALID", "请选择俱乐部");
        int days = choice(body.get("expiresInDays"), EXPIRY_DAYS, 7, "INVITE_EXPIRY_INVALID", "有效期只能选 1 天、7 天或永久");
        int uses = choice(body.get("maxUses"), MAX_USES, 0, "INVITE_USES_INVALID", "使用次数只能选不限、1、10 或 50");
        GuildStanding standing = access.standing(ChannelGuildAccess.GUILD_CLUB, clubId, me);
        if (!mayCreate(standing))
            throw new ChannelRejection(403, "INVITE_FORBIDDEN", "只有主理人和管理员可以生成邀请链接");
        Long channelId = null;
        Object rawChannel = body.get("channelId");
        if (rawChannel != null && !rawChannel.toString().isBlank())
        {
            long id = positive(rawChannel, "INVITE_CHANNEL_INVALID", "频道不存在");
            ChannelRow row = channels.selectChannel(id);
            if (row == null || row.getGuildKind() == null || row.getGuildKind() != ChannelGuildAccess.GUILD_CLUB
                    || row.getGuildId() == null || row.getGuildId() != clubId || row.isCategory()
                    || row.getStatus() == null || row.getStatus() != 0)
                throw new ChannelRejection(404, "INVITE_CHANNEL_INVALID", "频道不存在");
            if (!ChannelPermissionCalculator.has(access.effective(row, me), ChannelCapability.MANAGE_CHANNEL))
                throw new ChannelRejection(403, "INVITE_FORBIDDEN", "需要频道管理权限才能邀请到这个频道");
            channelId = id;
        }
        Date now = new Date();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("clubId", clubId);
        row.put("channelId", channelId);
        row.put("creatorMemberId", me);
        row.put("maxUses", uses == 0 ? null : uses);
        row.put("expiresAt", days == 0 ? null : new Date(now.getTime() + days * DAY_MS));
        for (int attempt = 0; ; attempt++)
        {
            row.put("code", newCode());
            try
            {
                mapper.insertInvite(row);
                break;
            }
            catch (DuplicateKeyException e)
            {
                if (attempt >= 2) throw e;
            }
        }
        return view(mapper.selectByCode((String) row.get("code")), now, true);
    }

    public List<Map<String, Object>> list(long me, long clubId)
    {
        GuildStanding standing = access.standing(ChannelGuildAccess.GUILD_CLUB, clubId, me);
        if (standing == GuildStanding.NONE) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入俱乐部后才能查看邀请");
        boolean manager = standing == GuildStanding.OWNER || standing == GuildStanding.ADMIN;
        Date now = new Date();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> invite : mapper.selectByClub(clubId, LIST_LIMIT))
        {
            if (!manager && toLong(invite.get("creatorMemberId")) != me) continue;
            out.add(view(invite, now, true));
        }
        return out;
    }

    public Map<String, Object> revoke(long me, String code)
    {
        Map<String, Object> invite = byCode(code);
        long clubId = toLong(invite.get("clubId"));
        GuildStanding standing = access.standing(ChannelGuildAccess.GUILD_CLUB, clubId, me);
        boolean manager = standing == GuildStanding.OWNER || standing == GuildStanding.ADMIN;
        boolean creator = standing != GuildStanding.NONE && toLong(invite.get("creatorMemberId")) == me;
        if (!manager && !creator) throw new ChannelRejection(403, "INVITE_FORBIDDEN", "只有生成人、主理人和管理员可以撤销邀请");
        mapper.revoke(toLong(invite.get("id")));
        return view(mapper.selectByCode(invite.get("code").toString()), new Date(), true);
    }

    // ------------------------------------------------------------------ landing and accept

    /** What the invite page shows: the club card, the target channel, and whether the invite still works. */
    public Map<String, Object> landing(long me, String code)
    {
        Map<String, Object> invite = byCode(code);
        Map<String, Object> club = mapper.selectClubCard(toLong(invite.get("clubId")));
        if (club == null || toLong(club.get("status")) != 1L || toLong(club.get("delFlag")) != 0L)
            throw new ChannelRejection(404, "INVITE_NOT_FOUND", "邀请不存在或俱乐部已关闭");
        Map<String, Object> v = view(invite, new Date(), false);
        v.put("club_name", club.get("name"));
        Object logo = club.get("logo");
        v.put("club_logo", logo != null && logo.toString().matches("(?i)https?://.+") ? logo : null);
        Long channelId = invite.get("channelId") == null ? null : toLong(invite.get("channelId"));
        ChannelRow channel = channelId == null ? null : channels.selectChannel(channelId);
        boolean channelLive = channel != null && channel.getStatus() != null && channel.getStatus() == 0;
        v.put("channel_name", channelLive ? channel.getName() : null);
        if (!channelLive) v.put("channel_id", null);
        v.put("already_member", access.standing(ChannelGuildAccess.GUILD_CLUB, toLong(invite.get("clubId")), me) != GuildStanding.NONE);
        return v;
    }

    /** The invite a join is about to use. A member already in the club takes no use. */
    public record Ticket(long inviteId, long clubId, Long channelId, boolean alreadyMember) { }

    /** Takes one use before the join runs; refuses an expired, used-up or revoked invite with its own reason. */
    public Ticket take(long me, String code)
    {
        Map<String, Object> invite = byCode(code);
        long clubId = toLong(invite.get("clubId"));
        Long channelId = invite.get("channelId") == null ? null : toLong(invite.get("channelId"));
        if (access.standing(ChannelGuildAccess.GUILD_CLUB, clubId, me) != GuildStanding.NONE)
            return new Ticket(toLong(invite.get("id")), clubId, channelId, true);
        Date now = new Date();
        String state = state(invite, now);
        if (!VALID.equals(state)) throw refusal(state);
        if (mapper.takeUse(toLong(invite.get("id")), now) != 1) throw refusal(state(mapper.selectByCode(code), now));
        return new Ticket(toLong(invite.get("id")), clubId, channelId, false);
    }

    /** The join did not go through: give the use back. */
    public void giveBack(Ticket ticket)
    {
        if (ticket != null && !ticket.alreadyMember()) mapper.returnUse(ticket.inviteId());
    }

    // ------------------------------------------------------------------ helpers

    boolean mayCreate(GuildStanding standing)
    {
        return standing == GuildStanding.OWNER || standing == GuildStanding.ADMIN
                || (membersMayCreate && standing == GuildStanding.MEMBER);
    }

    static ChannelRejection refusal(String state)
    {
        if (FULL.equals(state)) return new ChannelRejection(410, "INVITE_FULL", "邀请次数已用完");
        if (REVOKED.equals(state)) return new ChannelRejection(410, "INVITE_REVOKED", "邀请已撤销");
        return new ChannelRejection(410, "INVITE_EXPIRED", "邀请已失效");
    }

    static String state(Map<String, Object> invite, Date now)
    {
        if (invite == null) return EXPIRED;
        if (invite.get("revokedAt") != null) return REVOKED;
        Long expires = millis(invite.get("expiresAt"));
        if (expires != null && expires <= now.getTime()) return EXPIRED;
        Object max = invite.get("maxUses");
        if (max != null && toLong(invite.get("uses")) >= toLong(max)) return FULL;
        return VALID;
    }

    Map<String, Object> view(Map<String, Object> invite, Date now, boolean full)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("code", invite.get("code"));
        v.put("club_id", String.valueOf(toLong(invite.get("clubId"))));
        v.put("channel_id", invite.get("channelId") == null ? null : String.valueOf(toLong(invite.get("channelId"))));
        v.put("state", state(invite, now));
        v.put("expires_at", millis(invite.get("expiresAt")));
        v.put("max_uses", invite.get("maxUses") == null ? null : (int) toLong(invite.get("maxUses")));
        v.put("uses", (int) toLong(invite.get("uses")));
        v.put("path", "/pages/club/detail/index?id=" + toLong(invite.get("clubId")) + "&invite=" + invite.get("code"));
        if (full)
        {
            v.put("created_at", millis(invite.get("createTime")));
            v.put("creator_member_id", String.valueOf(toLong(invite.get("creatorMemberId"))));
        }
        return v;
    }

    private Map<String, Object> byCode(String code)
    {
        if (!validCode(code)) throw new ChannelRejection(404, "INVITE_NOT_FOUND", "邀请不存在");
        Map<String, Object> invite = mapper.selectByCode(code);
        if (invite == null || invite.isEmpty()) throw new ChannelRejection(404, "INVITE_NOT_FOUND", "邀请不存在");
        return invite;
    }

    public static boolean validCode(String code)
    {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) if (ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        return true;
    }

    String newCode()
    {
        StringBuilder b = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return b.toString();
    }

    private static int choice(Object raw, Set<Integer> allowed, int fallback, String code, String message)
    {
        if (raw == null || raw.toString().isBlank()) return fallback;
        try
        {
            int value = new BigDecimal(raw.toString().strip()).intValueExact();
            if (allowed.contains(value)) return value;
        }
        catch (NumberFormatException | ArithmeticException ignored)
        {
            // falls through to the refusal
        }
        throw new ChannelRejection(400, code, message);
    }

    private static long positive(Object raw, String code, String message)
    {
        long value = raw == null ? 0L : toLong(raw);
        if (value <= 0) throw new ChannelRejection(400, code, message);
        return value;
    }

    /** DATETIME columns arrive as {@link Date} or {@link LocalDateTime} depending on the driver. */
    static Long millis(Object value)
    {
        if (value instanceof Date d) return d.getTime();
        if (value instanceof LocalDateTime t) return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return null;
    }

    static long toLong(Object value)
    {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try
        {
            return Long.parseLong(value.toString().strip());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }
}
