package com.chengyinhub.business.service.friend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.friend.FriendInviteMapper;
import com.chengyinhub.business.mapper.friend.FriendMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelReceipts;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;

/**
 * 好友邀请（App NativeFriendChannelInvite* / NativeFriendTeamInvite*）。
 * <ul>
 * <li>频道邀请：邀请好友加入频道所在空间（admission_scope=SPACE）。被邀请人提交走原入会链路（俱乐部 requestJoin，
 *     队伍邀请码 join）；频道级准入（PARENT_SUBMIT/ACCEPT_CHANNEL/ACCEPT_RULES）没有服务端规则源，按原回执 REJECTED。</li>
 * <li>入队邀请：队员给好友发入队邀请；好友确认后按队伍 join_mode 走原 join（邀请码直进）或 apply（队长审核）。</li>
 * <li>两族写都落原回执（FINVITE/FTEAM），指纹与 App 一致（proof / 原始字节 SHA-256）。</li>
 * </ul>
 */
@Service
public class FriendInviteService
{
    static final Set<String> CHANNEL_ACTIONS = Set.of("CREATE", "SUBMIT", "PARENT_SUBMIT", "ACCEPT_CHANNEL", "ACCEPT_RULES", "REVOKE");

    @Autowired
    private FriendInviteMapper invites;
    @Autowired
    private FriendMapper friendMapper;
    @Autowired
    private FriendService friends;
    @Autowired
    private ChannelPlatformMapper receipts;
    @Autowired
    private ChannelPlatformService platform;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired(required = false)
    private IClubMemberService clubMembers;
    @Autowired(required = false)
    private IPlayTeamService teams;

    // ================================================================ channel invite

    public Map<String, Object> channelPicker(long viewer, long channelId, String section, String cursor)
    {
        Map<String, Object> channel = platform.detail(viewer, channelId);
        int kind = ((Number) channel.get("guild_kind")).intValue();
        long guild = Long.parseLong((String) channel.get("guild_id"));
        Map<String, Object> page = friends.directory(viewer, section, false, null, cursor, 50);
        page.put("items", picker(page, peer -> access.standing(kind, guild, peer) != GuildStanding.NONE));
        page.put("channel_id", String.valueOf(channelId));
        page.put("guild_id", String.valueOf(guild));
        page.put("guild_kind", kind);
        page.put("name", channel.get("name"));
        page.put("target_channel", false);
        return page;
    }

    private static List<Map<String, Object>> picker(Map<String, Object> page, java.util.function.LongPredicate member)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : (List<?>) page.get("items"))
        {
            @SuppressWarnings("unchecked") Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) raw);
            item.put("already_member", member.test(((Number) item.get("peer_id")).longValue()));
            items.add(item);
        }
        return items;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> channelCommand(long viewer, Map<String, Object> body)
    {
        if (body == null || !Set.of("action", "object_id", "request_id", "peers", "message", "accepted").equals(body.keySet()))
            throw ChannelRejection.badField("body");
        if (!(body.get("action") instanceof String action) || !CHANNEL_ACTIONS.contains(action)) throw ChannelRejection.badField("action");
        long objectId = FriendService.longField(body, "object_id", true);
        String requestId = FriendService.uuid(body.get("request_id"));
        if (!(body.get("message") instanceof String message) || message.codePointCount(0, message.length()) > 60) throw ChannelRejection.badField("message");
        if (!(body.get("peers") instanceof List<?> rawPeers) || !(body.get("accepted") instanceof List<?> rawAccepted)) throw ChannelRejection.badField("peers");
        List<long[]> peers = new ArrayList<>();
        for (Object p : rawPeers)
        {
            if (!(p instanceof Map<?, ?> m) || !(m.get("id") instanceof Number id) || !(m.get("version") instanceof Number v)) throw ChannelRejection.badField("peers");
            peers.add(new long[] { id.longValue(), v.longValue() });
        }
        List<String[]> accepted = new ArrayList<>();
        for (Object a : rawAccepted)
        {
            if (!(a instanceof Map<?, ?> m) || !(m.get("rule_key") instanceof String k) || !(m.get("rule_hash") instanceof String h)) throw ChannelRejection.badField("accepted");
            accepted.add(new String[] { k, h });
        }
        if ("CREATE".equals(action) ? peers.isEmpty() || peers.size() > 10 || !message.isEmpty() || !accepted.isEmpty() : !peers.isEmpty())
            throw ChannelRejection.badField("peers");
        String proof = FriendProofs.channelInvite(action, objectId, message, peers, accepted);
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, "FINVITE", requestId, proof, action, objectId, () ->
        {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("viewer_id", viewer);
            r.put("request_id", requestId);
            r.put("action", action);
            r.put("object_id", String.valueOf(objectId));
            r.put("payload_hash", proof);
            boolean applied = switch (action)
            {
                case "CREATE" -> createInvites(viewer, objectId, peers, requestId);
                case "REVOKE" -> revokeInvite(viewer, objectId);
                case "SUBMIT" -> submitInvite(viewer, objectId, message);
                default -> false; // 频道级准入没有服务端规则源
            };
            r.put("outcome", applied ? "APPLIED" : "REJECTED");
            if (!applied) r.put("error_code", "FRIEND_INVITE_REJECTED");
            return r;
        });
        out.remove("replayed");
        return out;
    }

    private boolean createInvites(long viewer, long channelId, List<long[]> peers, String requestId)
    {
        Map<String, Object> channel = platform.detail(viewer, channelId);
        int kind = ((Number) channel.get("guild_kind")).intValue();
        long guild = Long.parseLong((String) channel.get("guild_id"));
        for (long[] p : peers)
        {
            Map<String, Object> pair = friendMapper.selectPair(Math.min(viewer, p[0]), Math.max(viewer, p[0]));
            if (FriendService.state(viewer, pair) != 1 || FriendService.num(pair.get("version")) != p[1] || friendMapper.countBlock(viewer, p[0]) > 0)
                return false;
        }
        for (long[] p : peers) invites.insertChannelInvite(viewer, p[0], channelId, kind, guild, requestId);
        return true;
    }

    private boolean revokeInvite(long viewer, long inviteId)
    {
        Map<String, Object> invite = invites.lockChannelInvite(inviteId);
        if (invite == null || FriendService.num(invite.get("inviterId")) != viewer || !"INVITED".equals(invite.get("state"))) return false;
        invites.setChannelInviteState(inviteId, "INVITE_ENDED");
        return true;
    }

    private boolean submitInvite(long viewer, long inviteId, String message)
    {
        Map<String, Object> invite = invites.lockChannelInvite(inviteId);
        if (invite == null || FriendService.num(invite.get("peerId")) != viewer || !"INVITED".equals(invite.get("state"))) return false;
        int kind = (int) FriendService.num(invite.get("guildKind"));
        long guild = FriendService.num(invite.get("guildId"));
        if (access.standing(kind, guild, viewer) == GuildStanding.NONE)
        {
            if (kind == ChannelGuildAccess.GUILD_CLUB)
            {
                if (clubMembers == null) throw new IllegalStateException("club member service unavailable");
                clubMembers.requestJoin(guild, viewer, message.isBlank() ? "好友邀请" : message.strip());
            }
            else
            {
                Map<String, Object> team = invites.selectTeam(guild);
                if (team == null || teams == null) return false;
                teams.join((String) team.get("inviteCode"), viewer);
            }
        }
        invites.setChannelInviteState(inviteId, "SUBMITTED");
        return true;
    }

    public Map<String, Object> channelReceipt(long viewer, String requestId)
    {
        Map<String, Object> stored = friends.stored(viewer, "FINVITE", FriendService.uuid(requestId));
        if (stored != null) return stored;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("request_id", requestId);
        out.put("outcome", "UNKNOWN");
        return out;
    }

    public Map<String, Object> channelBatch(long viewer, long channelId, String requestId)
    {
        List<Map<String, Object>> rows = invites.listBatch(viewer, FriendService.uuid(requestId));
        if (rows.isEmpty() || FriendService.num(rows.get(0).get("channelId")) != channelId)
            throw new ChannelRejection(404, "FRIEND_INVITE_NOT_FOUND", "邀请不存在");
        return batchView(viewer, rows);
    }

    public Map<String, Object> channelNotice(long viewer, long inviteId)
    {
        Map<String, Object> invite = invites.selectChannelInvite(inviteId);
        if (invite == null || FriendService.num(invite.get("inviterId")) != viewer) throw new ChannelRejection(404, "FRIEND_INVITE_NOT_FOUND", "邀请不存在");
        return batchView(viewer, invites.listBatch(viewer, (String) invite.get("requestId")));
    }

    private Map<String, Object> batchView(long viewer, List<Map<String, Object>> rows)
    {
        Map<String, Object> first = rows.get(0);
        long channelId = FriendService.num(first.get("channelId"));
        int kind = (int) FriendService.num(first.get("guildKind"));
        long guild = FriendService.num(first.get("guildId"));
        Map<String, Object> channel = platform.detail(viewer, channelId);
        int joined = 0, pending = 0, closed = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            long peer = FriendService.num(row.get("peerId"));
            boolean member = access.standing(kind, guild, peer) != GuildStanding.NONE;
            String state = member ? "JOINED" : switch (String.valueOf(row.get("state")))
            {
                case "INVITE_ENDED" -> "INVITE_ENDED";
                case "SUBMITTED" -> kind == ChannelGuildAccess.GUILD_CLUB && invites.countClubPending(guild, peer) > 0 ? "PENDING_REVIEW" : "REQUEST_ENDED";
                default -> "INVITED";
            };
            if ("JOINED".equals(state)) joined++;
            else if ("INVITED".equals(state) || "PENDING_REVIEW".equals(state)) pending++;
            else closed++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("invite_id", String.valueOf(FriendService.num(row.get("id"))));
            item.put("peer_id", String.valueOf(peer));
            item.put("state", state);
            item.put("friend", friendItem(viewer, peer));
            item.put("parent_member", member);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("channel_id", String.valueOf(channelId));
        out.put("request_id", first.get("requestId"));
        out.put("name", channel.get("name"));
        out.put("guild_kind", kind);
        out.put("target_channel", false);
        out.put("invited", rows.size());
        out.put("joined", joined);
        out.put("pending", pending);
        out.put("closed", closed);
        out.put("items", items);
        return out;
    }

    /** 被邀请人落地页：空间名、入会状态；频道级规则不存在，rules 恒为空。 */
    public Map<String, Object> channelLanding(long viewer, long inviteId)
    {
        Map<String, Object> invite = invites.selectChannelInvite(inviteId);
        if (invite == null || FriendService.num(invite.get("peerId")) != viewer) throw new ChannelRejection(404, "FRIEND_INVITE_NOT_FOUND", "邀请不存在");
        int kind = (int) FriendService.num(invite.get("guildKind"));
        long guild = FriendService.num(invite.get("guildId"));
        String joinState;
        if (access.standing(kind, guild, viewer) != GuildStanding.NONE) joinState = "ALREADY_MEMBER";
        else if ("INVITE_ENDED".equals(invite.get("state"))) joinState = "INVITE_REVOKED";
        else if (kind == ChannelGuildAccess.GUILD_CLUB && invites.countClubPending(guild, viewer) > 0) joinState = "PENDING_REVIEW";
        else joinState = "NOT_MEMBER";
        String name = kind == ChannelGuildAccess.GUILD_CLUB ? invites.selectClubName(guild) : teamName(invites.selectTeam(guild), guild);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("invite_id", String.valueOf(inviteId));
        out.put("guild_id", String.valueOf(guild));
        out.put("guild_kind", kind);
        out.put("join_state", joinState);
        out.put("space", Map.of("name", name == null || name.isEmpty() ? "空间" : name));
        out.put("rules", List.of());
        out.put("admission_scope", "SPACE");
        return out;
    }

    public Map<String, Object> channelMine(long viewer, String before)
    {
        long cursor = "0".equals(before) ? 0 : Long.parseLong(FriendService.longField(Map.of("b", parseId(before)), "b", true) + "");
        List<Map<String, Object>> rows = invites.listMyChannelInvites(viewer, cursor);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("invite_id", String.valueOf(FriendService.num(r.get("id"))));
            item.put("channel_id", String.valueOf(FriendService.num(r.get("channelId"))));
            item.put("request_id", r.get("requestId"));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("items", items);
        out.put("next_before", rows.size() == 50 ? items.get(items.size() - 1).get("invite_id") : "0");
        return out;
    }

    // ================================================================ team invite

    public Map<String, Object> teamPicker(long viewer, long teamId, String section, String cursor)
    {
        Map<String, Object> team = invites.selectTeam(teamId);
        if (team == null || invites.countTeamMember(teamId, viewer) == 0) throw new ChannelRejection(403, "TEAM_NOT_MEMBER", "只有队员可以邀请好友");
        Map<String, Object> page = friends.directory(viewer, section, false, null, cursor, 50);
        page.put("items", picker(page, peer -> invites.countTeamMember(teamId, peer) > 0));
        page.put("team_id", String.valueOf(teamId));
        page.put("name", teamName(team, teamId));
        return page;
    }

    public Map<String, Object> teamLanding(long viewer, String offerId)
    {
        Map<String, Object> offer = invites.selectOffer(offerId);
        if (offer == null || (FriendService.num(offer.get("recipientId")) != viewer && FriendService.num(offer.get("inviterId")) != viewer))
            throw new ChannelRejection(404, "TEAM_OFFER_NOT_FOUND", "邀请不存在");
        return offerView(viewer, offer);
    }

    public Map<String, Object> teamMine(long viewer, String before)
    {
        long cursor = "0".equals(before) ? 0 : parseId(before);
        List<Map<String, Object>> rows = invites.listMyOffers(viewer, cursor);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(offerView(viewer, r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("items", items);
        out.put("next_before", rows.size() == 50 ? String.valueOf(FriendService.num(rows.get(rows.size() - 1).get("seq"))) : "0");
        return out;
    }

    private Map<String, Object> offerView(long viewer, Map<String, Object> offer)
    {
        long teamId = FriendService.num(offer.get("teamId"));
        long recipient = FriendService.num(offer.get("recipientId"));
        long inviter = FriendService.num(offer.get("inviterId"));
        String state = String.valueOf(offer.get("state"));
        if ("PENDING_REVIEW".equals(state) && invites.countTeamMember(teamId, recipient) > 0) state = "JOINED";
        boolean isRecipient = recipient == viewer;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("offer_id", offer.get("offerId"));
        out.put("team_id", String.valueOf(teamId));
        out.put("name", teamName(invites.selectTeam(teamId), teamId));
        out.put("state", state);
        out.put("recipient", isRecipient);
        if (isRecipient) out.put("inviter", friendItem(viewer, inviter));
        out.put("can_act", isRecipient && "WAITING_CONFIRM".equals(state));
        out.put("join_mode", (int) FriendService.num(offer.get("joinMode")));
        return out;
    }

    /** POST /api/team/original-request：原始字节即指纹来源。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> teamCommand(long viewer, byte[] raw)
    {
        if (raw == null || raw.length == 0 || raw.length > 4096) throw ChannelRejection.badField("body");
        Map<String, Object> envelope = ChannelReceipts.decode(new String(raw, StandardCharsets.UTF_8));
        if (!Set.of("requestId", "action", "expectedTeamId", "body").equals(envelope.keySet()) || !(envelope.get("body") instanceof Map<?, ?> b))
            throw ChannelRejection.badField("envelope");
        if (!(envelope.get("requestId") instanceof String requestId) || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ChannelRejection.badField("requestId");
        String action = String.valueOf(envelope.get("action"));
        long teamId = FriendService.longField(envelope, "expectedTeamId", true);
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) b;
        if (!(body.get("teamId") instanceof Number t) || t.longValue() != teamId) throw ChannelRejection.badField("teamId");
        String fingerprint = FriendProofs.sha256(raw);
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, "FTEAM", requestId, fingerprint, action, teamId, () ->
        {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("contract", "ORIGINAL_TEAM_REQUEST_V1");
            r.put("requestId", requestId);
            r.put("action", action);
            r.put("teamId", teamId);
            r.put("targetKey", "friend-offer:" + teamId);
            r.put("requestFingerprint", fingerprint);
            String refusal;
            try
            {
                r.put("friendOffer", switch (action)
                {
                    case "FRIEND_OFFER" -> offer(viewer, teamId, body.get("peers"), requestId);
                    case "FRIEND_CONFIRM", "FRIEND_DECLINE", "FRIEND_REVOKE" -> act(viewer, teamId, action, body);
                    default -> throw new OfferRefusal("TEAM_OFFER_ACTION_UNSUPPORTED");
                });
                r.put("outcome", "APPLIED");
                return r;
            }
            catch (OfferRefusal e) { refusal = e.getMessage(); }
            r.put("outcome", "REJECTED");
            r.put("errorCode", refusal);
            return r;
        });
        out.remove("replayed");
        return out;
    }

    static final class OfferRefusal extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        OfferRefusal(String code) { super(code, null, false, false); }
    }

    private Map<String, Object> offer(long viewer, long teamId, Object rawPeers, String requestId)
    {
        Map<String, Object> team = invites.selectTeam(teamId);
        if (team == null || invites.countTeamMember(teamId, viewer) == 0) throw new OfferRefusal("TEAM_NOT_MEMBER");
        if (!(rawPeers instanceof List<?> peers) || peers.isEmpty() || peers.size() > 10) throw new OfferRefusal("TEAM_OFFER_INVALID");
        int joinMode = (int) FriendService.num(team.get("joinMode")) == 2 ? 2 : 1;
        List<Map<String, Object>> issued = new ArrayList<>();
        for (Object p : peers)
        {
            if (!(p instanceof Map<?, ?> m) || !(m.get("id") instanceof Number id) || !(m.get("version") instanceof Number v)) throw new OfferRefusal("TEAM_OFFER_INVALID");
            long peer = id.longValue();
            Map<String, Object> pair = friendMapper.selectPair(Math.min(viewer, peer), Math.max(viewer, peer));
            if (FriendService.state(viewer, pair) != 1 || FriendService.num(pair.get("version")) != v.longValue() || friendMapper.countBlock(viewer, peer) > 0)
                throw new OfferRefusal("FRIEND_VERSION_CONFLICT");
            if (invites.countTeamMember(teamId, peer) > 0) throw new OfferRefusal("TEAM_ALREADY_MEMBER");
            String offerId = UUID.randomUUID().toString();
            invites.insertOffer(offerId, teamId, viewer, peer, joinMode, requestId);
            issued.add(new LinkedHashMap<>(Map.of("offer_id", offerId, "peer_id", String.valueOf(peer))));
        }
        return new LinkedHashMap<>(Map.of("offers", issued));
    }

    private Map<String, Object> act(long viewer, long teamId, String action, Map<String, Object> body)
    {
        if (!(body.get("offerId") instanceof String offerId)) throw new OfferRefusal("TEAM_OFFER_INVALID");
        Map<String, Object> offer = invites.lockOffer(offerId);
        if (offer == null || FriendService.num(offer.get("teamId")) != teamId) throw new OfferRefusal("TEAM_OFFER_NOT_FOUND");
        boolean recipient = FriendService.num(offer.get("recipientId")) == viewer;
        boolean inviter = FriendService.num(offer.get("inviterId")) == viewer;
        if (!"WAITING_CONFIRM".equals(offer.get("state"))) throw new OfferRefusal("TEAM_OFFER_CLOSED");
        switch (action)
        {
            case "FRIEND_REVOKE" ->
            {
                if (!inviter) throw new OfferRefusal("TEAM_OFFER_FORBIDDEN");
                invites.setOfferState(offerId, "REVOKED");
            }
            case "FRIEND_DECLINE" ->
            {
                if (!recipient) throw new OfferRefusal("TEAM_OFFER_FORBIDDEN");
                invites.setOfferState(offerId, "DECLINED");
            }
            default ->
            {
                if (!recipient) throw new OfferRefusal("TEAM_OFFER_FORBIDDEN");
                int mode = (int) FriendService.num(offer.get("joinMode"));
                if (!(body.get("joinMode") instanceof Number m) || m.intValue() != mode) throw new OfferRefusal("TEAM_JOIN_MODE_CHANGED");
                Map<String, Object> team = invites.selectTeam(teamId);
                if (team == null || teams == null) throw new OfferRefusal("TEAM_NOT_FOUND");
                try
                {
                    if (mode == 1) teams.join((String) team.get("inviteCode"), viewer);
                    else teams.apply(teamId, viewer, "好友邀请");
                }
                catch (RuntimeException refused) { throw new OfferRefusal("TEAM_JOIN_REFUSED"); }
                invites.setOfferState(offerId, mode == 1 ? "JOINED" : "PENDING_REVIEW");
            }
        }
        return new LinkedHashMap<>(Map.of("offer_id", offerId));
    }

    public Map<String, Object> teamReceipt(long viewer, String requestId)
    {
        Map<String, Object> stored = friends.stored(viewer, "FTEAM", requestId);
        if (stored != null) return stored;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "ORIGINAL_TEAM_REQUEST_V1");
        out.put("outcome", "UNKNOWN");
        out.put("requestId", requestId);
        return out;
    }

    // ================================================================ helpers

    private Map<String, Object> friendItem(long viewer, long peer)
    {
        Map<String, Object> pair = friendMapper.selectPair(Math.min(viewer, peer), Math.max(viewer, peer));
        Map<String, Object> meta = friendMapper.selectMeta(viewer, peer);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("peerId", peer);
        row.put("version", pair == null ? 1L : Math.max(1L, FriendService.num(pair.get("version"))));
        row.put("nickname", friendMapper.nickname(peer));
        if (meta != null) { row.put("remark", meta.get("remark")); row.put("groupId", meta.get("groupId")); row.put("starred", meta.get("starred")); }
        return friends.friendItem(row);
    }

    private static String teamName(Map<String, Object> team, long teamId)
    {
        Object title = team == null ? null : team.get("title");
        return title == null || title.toString().isBlank() ? "队伍" + teamId : title.toString();
    }

    static long parseId(String raw)
    {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("before");
        return Long.parseLong(raw);
    }
}
