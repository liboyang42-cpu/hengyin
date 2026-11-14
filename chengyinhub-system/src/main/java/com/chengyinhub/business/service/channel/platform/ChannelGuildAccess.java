package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.Overwrite;

/**
 * 空间关系与有效权限的实时裁决：俱乐部(1)以 club / club_member / club_role_assignment 为真源，
 * 队伍(3)以 play_team / play_team_member 为真源。IM 会话成员行只是索引，不授权。
 */
@Component
public class ChannelGuildAccess
{
    public static final int GUILD_CLUB = 1;
    public static final int GUILD_TEAM = 3;

    @Autowired
    private ChannelPlatformMapper mapper;

    public ChannelGuildAccess() { }

    ChannelGuildAccess(ChannelPlatformMapper mapper) { this.mapper = mapper; }

    public static void requireSupportedKind(int guildKind)
    {
        if (guildKind != GUILD_CLUB && guildKind != GUILD_TEAM)
            throw new ChannelRejection(400, "GUILD_KIND_UNSUPPORTED", "暂不支持该空间类型");
    }

    /** 空间不存在/已关闭 → 404 GUILD_NOT_FOUND；存在但非成员 → NONE。 */
    public GuildStanding standing(int guildKind, long guildId, long memberId)
    {
        requireSupportedKind(guildKind);
        if (guildId <= 0) throw new ChannelRejection(404, "GUILD_NOT_FOUND", "空间不存在");
        if (guildKind == GUILD_CLUB)
        {
            Map<String, Object> club = mapper.selectClubGuild(guildId);
            if (club == null || toLong(club.get("status")) != 1L || toLong(club.get("delFlag")) != 0L)
                throw new ChannelRejection(404, "GUILD_NOT_FOUND", "俱乐部不存在或已关闭");
            if (memberId <= 0) return GuildStanding.NONE;
            if (memberId == toLong(club.get("ownerId"))) return GuildStanding.OWNER;
            if (mapper.countClubMember(guildId, memberId) == 0) return GuildStanding.NONE;
            return mapper.countClubAdmin(guildId, memberId) > 0 ? GuildStanding.ADMIN : GuildStanding.MEMBER;
        }
        Map<String, Object> team = mapper.selectTeamGuild(guildId);
        long status = team == null ? -1 : toLong(team.get("status"));
        if (team == null || status == 3 || status == 4)
            throw new ChannelRejection(404, "GUILD_NOT_FOUND", "队伍不存在或已结束");
        if (memberId <= 0 || mapper.countTeamMember(guildId, memberId) == 0) return GuildStanding.NONE;
        return memberId == toLong(team.get("ownerId")) ? GuildStanding.OWNER : GuildStanding.MEMBER;
    }

    public GuildStanding requireMember(int guildKind, long guildId, long memberId)
    {
        GuildStanding standing = standing(guildKind, guildId, memberId);
        if (standing == GuildStanding.NONE) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能查看频道");
        return standing;
    }

    /** 成员在该空间的角色画像：启用角色 id 与合并后的角色位。 */
    public record RoleProfile(List<Long> roleIds, long permissions) { }

    public RoleProfile roles(int guildKind, long guildId, long memberId)
    {
        List<Long> ids = new ArrayList<>();
        long bits = 0;
        for (Map<String, Object> r : mapper.selectMemberRoles(guildKind, guildId, memberId))
        {
            ids.add(toLong(r.get("id")));
            bits |= toLong(r.get("permissions"));
        }
        return new RoleProfile(ids, bits);
    }

    public static List<Overwrite> overwrites(List<Map<String, Object>> rows)
    {
        List<Overwrite> list = new ArrayList<>();
        for (Map<String, Object> r : rows)
            list.add(new Overwrite((int) toLong(r.get("targetType")), toLong(r.get("targetId")),
                    toLong(r.get("allowBits")), toLong(r.get("denyBits"))));
        return list;
    }

    /** 单频道有效权限（读当前覆盖）。 */
    public long effective(ChannelRow channel, long memberId)
    {
        GuildStanding standing = standing(channel.getGuildKind(), channel.getGuildId(), memberId);
        return effective(channel, standing, memberId, overwrites(mapper.selectOverwrites(channel.getId())));
    }

    public long effective(ChannelRow channel, GuildStanding standing, long memberId, List<Overwrite> overwrites)
    {
        if (standing == GuildStanding.NONE) return 0L;
        RoleProfile profile = roles(channel.getGuildKind(), channel.getGuildId(), memberId);
        return ChannelPermissionCalculator.effective(standing, profile.permissions(), profile.roleIds(), overwrites,
                channel.getStatus() == null ? -1 : channel.getStatus());
    }

    /** 空间级能力（不看单频道覆盖）：管理频道/角色/审计/进度等。 */
    public long guildBits(int guildKind, long guildId, long memberId)
    {
        GuildStanding standing = standing(guildKind, guildId, memberId);
        if (standing == GuildStanding.NONE) return 0L;
        return ChannelPermissionCalculator.guildBase(standing, roles(guildKind, guildId, memberId).permissions());
    }

    static long toLong(Object value)
    {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Boolean b) return b ? 1L : 0L;
        return Long.parseLong(value.toString());
    }
}
