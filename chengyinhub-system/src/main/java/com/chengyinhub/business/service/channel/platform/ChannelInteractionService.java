package com.chengyinhub.business.service.channel.platform;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelInteractionMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.friend.FriendProofs;

/**
 * 频道接龙（App NativeChannelChain.swift）与每日打卡（NativeChannelCheckin.swift）。
 * 接龙存储复用 PR#728 的 im_chain / im_chain_entry；创建时在频道里发一张 msg_type=14 的接龙卡片。
 * 打卡以 Asia/Shanghai 自然日为业务日；连续天数 = 前一日连续天数 + 1。不发奖励、不支持补卡（makeupPolicy 未配置）。
 * 两族写都以 requestId 落原回执（CHAIN/CHECKIN），requestHash 与 App 的 chain-v1 / checkin-v1 指纹一致。
 */
@Service
public class ChannelInteractionService
{
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int UNLIMITED = Integer.MAX_VALUE;
    static final String NO_DEADLINE = "9999-12-31 23:59:59.999";

    @Autowired
    private ChannelInteractionMapper mapper;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelMessageService messages;

    // ================================================================ chain

    public static String chainDigest(String operation, String conversationId, String chainId, String title, Integer maxSlots,
            Long deadlineAt, String content)
    {
        return FriendProofs.lengthPrefixed(List.of("chain-v1", operation, conversationId, chainId == null ? "" : chainId,
                title == null ? "" : title, maxSlots == null ? "" : String.valueOf(maxSlots),
                deadlineAt == null ? "" : String.valueOf(deadlineAt), content == null ? "" : content));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> chain(long viewer, String operation, Map<String, Object> body)
    {
        if (body == null) throw ChannelRejection.badField("body");
        if (!Set.of("requestId", "conversationId", "operation", "chainId", "title", "maxSlots", "deadlineAt", "content").containsAll(body.keySet()))
            throw ChannelRejection.badField("unknown field");
        String op = operation.toUpperCase();
        if (!op.equals(body.get("operation"))) throw ChannelRejection.badField("operation");
        String requestId = lowerUuid(body.get("requestId"));
        String conversation = decimal(body.get("conversationId"), "conversationId");
        long conv = Long.parseLong(conversation);
        String chainId = body.get("chainId") == null ? null : decimal(body.get("chainId"), "chainId");
        String title = body.get("title") instanceof String t ? t : null;
        Integer maxSlots = body.get("maxSlots") instanceof Integer n ? n : null;
        Long deadline = body.get("deadlineAt") instanceof Number n ? n.longValue() : null;
        String content = body.get("content") instanceof String c ? c : null;
        if ("CREATE".equals(op))
        {
            if (chainId != null || content != null || !cleanText(title, 200) || maxSlots == null || maxSlots < 0
                    || (deadline != null && (deadline < 0 || deadline > 253402300799000L))) throw ChannelRejection.badField("create");
        }
        else if (chainId == null || title != null || maxSlots != null || deadline != null
                || ("JOIN".equals(op) ? !cleanText(content, 2000) : content != null)) throw ChannelRejection.badField(op.toLowerCase());
        String digest = chainDigest(op, conversation, chainId, title, maxSlots, deadline, content);
        return ChannelReceipts.once(channels, viewer, "CHAIN", requestId, digest, op, conv, () ->
        {
            Map<String, Object> result = switch (op)
            {
                case "CREATE" -> create(viewer, conv, title, maxSlots, deadline);
                case "CLOSE" -> close(viewer, conv, Long.parseLong(chainId));
                default -> participate(viewer, conv, Long.parseLong(chainId), op, content, requestId);
            };
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "original-channel-chain-request-v1");
            out.put("actorId", String.valueOf(viewer));
            out.put("conversationId", conversation);
            out.put("requestId", requestId);
            out.put("state", "APPLIED");
            out.put("operation", op);
            out.put("requestHash", digest);
            out.put("resultRedacted", false);
            out.put("result", result);
            return out;
        });
    }

    private Map<String, Object> create(long viewer, long conv, String title, int maxSlots, Long deadline)
    {
        messages.authorize(viewer, conv, of(VIEW_CHANNEL, SEND_MESSAGE), true);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("conversationId", conv);
        msg.put("senderId", viewer);
        msg.put("content", "[接龙] " + title);
        mapper.insertCardMessage(msg);
        long messageId = ChannelGuildAccess.toLong(msg.get("id"));
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("conversationId", conv);
        chain.put("messageId", messageId);
        chain.put("creatorMemberId", viewer);
        chain.put("title", title);
        chain.put("maxSlots", maxSlots == 0 ? UNLIMITED : maxSlots);
        chain.put("deadlineAt", deadline == null ? NO_DEADLINE : ChannelNotifyService.dbTime(new java.util.Date(deadline)));
        mapper.insertChain(chain);
        long chainId = ChannelGuildAccess.toLong(chain.get("id"));
        mapper.setMessageExtra(messageId, ChannelReceipts.encode(new LinkedHashMap<>(Map.of(
                "source", "original-channel-chain-v1", "chainId", String.valueOf(chainId), "conversationId", String.valueOf(conv),
                "creatorMemberId", String.valueOf(viewer)))));
        mapper.touchConversation(conv, "[接龙] " + title);
        mapper.fanOutUnread(conv, viewer);
        return chainResult(mapper.selectChain(chainId, conv), null);
    }

    private Map<String, Object> close(long viewer, long conv, long chainId)
    {
        messages.authorize(viewer, conv, of(VIEW_CHANNEL), false);
        Map<String, Object> chain = lockChain(chainId, conv);
        if (ChannelGuildAccess.toLong(chain.get("creatorMemberId")) != viewer) throw new ChannelRejection(403, "CHAIN_FORBIDDEN", "只有发起人可以结束接龙");
        if (!"OPEN".equals(chain.get("status"))) throw new ChannelRejection(409, "CHAIN_CLOSED", "接龙已结束");
        mapper.updateChain(chainId, ChannelGuildAccess.toLong(chain.get("version")), (int) ChannelGuildAccess.toLong(chain.get("activeCount")), "CLOSED");
        return chainResult(mapper.selectChain(chainId, conv), null);
    }

    private Map<String, Object> participate(long viewer, long conv, long chainId, String op, String content, String requestId)
    {
        messages.authorize(viewer, conv, of(VIEW_CHANNEL, SEND_MESSAGE), true);
        Map<String, Object> chain = lockChain(chainId, conv);
        if (!accepting(chain) && "JOIN".equals(op)) throw new ChannelRejection(409, "CHAIN_NOT_ACCEPTING", "接龙已结束或已满");
        if (!"OPEN".equals(chain.get("status"))) throw new ChannelRejection(409, "CHAIN_CLOSED", "接龙已结束");
        int active = (int) ChannelGuildAccess.toLong(chain.get("activeCount"));
        Map<String, Object> entry = mapper.lockEntry(chainId, viewer);
        boolean wasActive = entry != null && "ACTIVE".equals(entry.get("status"));
        if ("JOIN".equals(op))
        {
            if (wasActive) throw new ChannelRejection(409, "CHAIN_ALREADY_JOINED", "你已经接过龙");
            if (entry == null) mapper.insertEntry(chainId, viewer, content, mapper.nextPosition(chainId), requestId);
            else mapper.updateEntry(chainId, viewer, ChannelGuildAccess.toLong(entry.get("version")), "ACTIVE", content, requestId);
            active++;
        }
        else
        {
            if (!wasActive) throw new ChannelRejection(409, "CHAIN_NOT_JOINED", "你还没有接龙");
            mapper.updateEntry(chainId, viewer, ChannelGuildAccess.toLong(entry.get("version")), "WITHDRAWN", "", requestId);
            active--;
        }
        if (mapper.updateChain(chainId, ChannelGuildAccess.toLong(chain.get("version")), active, "OPEN") != 1)
            throw new ChannelRejection(409, "CHAIN_VERSION_CONFLICT", "接龙刚刚变化，请刷新");
        return chainResult(mapper.selectChain(chainId, conv), entryView(mapper.selectEntry(chainId, viewer)));
    }

    private Map<String, Object> lockChain(long chainId, long conv)
    {
        Map<String, Object> chain = mapper.lockChain(chainId, conv);
        if (chain == null) throw new ChannelRejection(404, "CHAIN_NOT_FOUND", "接龙不存在");
        return chain;
    }

    static boolean accepting(Map<String, Object> chain)
    {
        return "OPEN".equals(chain.get("status")) && ChannelGuildAccess.toLong(chain.get("pastDeadline")) == 0
                && ChannelGuildAccess.toLong(chain.get("activeCount")) < ChannelGuildAccess.toLong(chain.get("maxSlots"));
    }

    static Map<String, Object> chainResult(Map<String, Object> chain, Map<String, Object> entry)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("chainId", String.valueOf(ChannelGuildAccess.toLong(chain.get("id"))));
        r.put("messageId", String.valueOf(ChannelGuildAccess.toLong(chain.get("messageId"))));
        r.put("creatorMemberId", String.valueOf(ChannelGuildAccess.toLong(chain.get("creatorMemberId"))));
        r.put("chainVersion", String.valueOf(ChannelGuildAccess.toLong(chain.get("version"))));
        r.put("status", chain.get("status"));
        r.put("activeCount", (int) ChannelGuildAccess.toLong(chain.get("activeCount")));
        if (entry != null) r.put("entry", entry);
        return r;
    }

    static Map<String, Object> entryView(Map<String, Object> e)
    {
        if (e == null) return null;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(ChannelGuildAccess.toLong(e.get("id"))));
        v.put("chainId", String.valueOf(ChannelGuildAccess.toLong(e.get("chainId"))));
        v.put("memberId", String.valueOf(ChannelGuildAccess.toLong(e.get("memberId"))));
        v.put("position", String.valueOf(ChannelGuildAccess.toLong(e.get("position"))));
        v.put("version", String.valueOf(ChannelGuildAccess.toLong(e.get("version"))));
        v.put("status", e.get("status"));
        if ("ACTIVE".equals(e.get("status"))) v.put("content", e.get("content"));
        return v;
    }

    public Map<String, Object> chainInfo(long viewer, long conv, long chainId, long cursor, int limit)
    {
        if (cursor < 0 || limit < 1 || limit > 50) throw ChannelRejection.badField("cursor/limit");
        ChannelMessageService.ChannelAccess a = messages.authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
        Map<String, Object> chain = mapper.selectChain(chainId, conv);
        if (chain == null) throw new ChannelRejection(404, "CHAIN_NOT_FOUND", "接龙不存在");
        boolean canParticipate = ChannelPermissionCalculator.has(a.bits(), SEND_MESSAGE) && a.channel().getStatus() == 0;
        boolean open = "OPEN".equals(chain.get("status"));
        long creator = ChannelGuildAccess.toLong(chain.get("creatorMemberId"));
        long max = ChannelGuildAccess.toLong(chain.get("maxSlots"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", String.valueOf(chainId));
        c.put("conversationId", String.valueOf(conv));
        c.put("messageId", String.valueOf(ChannelGuildAccess.toLong(chain.get("messageId"))));
        c.put("creatorMemberId", String.valueOf(creator));
        c.put("title", chain.get("title"));
        c.put("status", chain.get("status"));
        c.put("version", String.valueOf(ChannelGuildAccess.toLong(chain.get("version"))));
        c.put("maxSlots", max == UNLIMITED ? 0 : (int) max);
        c.put("activeCount", (int) ChannelGuildAccess.toLong(chain.get("activeCount")));
        Long deadline = shanghaiMillis(chain.get("deadlineAt"));
        c.put("deadlineAt", deadline == null || deadline >= 253402300799000L ? null : deadline);
        c.put("accepting", accepting(chain));
        c.put("canParticipate", canParticipate);
        c.put("canClose", open && creator == viewer && canParticipate);
        List<Map<String, Object>> rows = mapper.listEntries(chainId, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> e : more ? rows.subList(0, limit) : rows) list.add(entryView(e));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("source", "original-channel-chain-v1");
        page.put("chain", c);
        page.put("list", list);
        Map<String, Object> mine = entryView(mapper.selectEntry(chainId, viewer));
        if (mine != null) page.put("mine", mine);
        page.put("hasMore", more);
        page.put("nextCursor", more ? list.get(list.size() - 1).get("position") : null);
        return page;
    }

    public Map<String, Object> interactionReceipt(long viewer, long conv, String requestId)
    {
        String id = lowerUuid(requestId);
        Map<String, Object> stored = channels.selectReceipt(viewer, "CHAIN", id);
        if (stored == null || stored.get("response") == null || ChannelGuildAccess.toLong(stored.get("channelId")) != conv)
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "original-channel-chain-request-v1");
            out.put("actorId", String.valueOf(viewer));
            out.put("conversationId", String.valueOf(conv));
            out.put("requestId", id);
            out.put("state", "NOT_FOUND");
            return out;
        }
        Map<String, Object> out = ChannelReceipts.decode(stored.get("response").toString());
        out.remove("replayed");
        try { messages.authorize(viewer, conv, of(VIEW_CHANNEL), false); }
        catch (ChannelRejection gone) { out.remove("result"); out.put("resultRedacted", true); }
        return out;
    }

    // ================================================================ checkin

    public static String checkinDigest(String channelId, String date)
    {
        return FriendProofs.lengthPrefixed(List.of("checkin-v1", channelId, date));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkin(long viewer, Map<String, Object> body)
    {
        if (body == null || !Set.of("requestId", "channelId", "checkinDate").equals(body.keySet())) throw ChannelRejection.badField("body");
        String requestId = lowerUuid(body.get("requestId"));
        String channel = decimal(body.get("channelId"), "channelId");
        if (!(body.get("checkinDate") instanceof String date) || !date.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}")) throw ChannelRejection.badField("checkinDate");
        long channelId = Long.parseLong(channel);
        String digest = checkinDigest(channel, date);
        return ChannelReceipts.once(channels, viewer, "CHECKIN", requestId, digest, "CHECKIN", channelId, () ->
        {
            messages.authorize(viewer, channelId, of(VIEW_CHANNEL), false);
            String today = LocalDate.now(ZONE).toString();
            if (!today.equals(date)) throw new ChannelRejection(409, "CHECKIN_DATE_MISMATCH", "打卡日期已变化，请刷新");
            Map<String, Object> fact = mapper.selectCheckin(channelId, viewer, date);
            String code = "ALREADY_CHECKED";
            if (fact == null)
            {
                Map<String, Object> prior = mapper.selectCheckin(channelId, viewer, LocalDate.parse(date).minusDays(1).toString());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("channelId", channelId);
                row.put("memberId", viewer);
                row.put("date", date);
                row.put("streak", prior == null ? 1 : (int) ChannelGuildAccess.toLong(prior.get("streak")) + 1);
                row.put("requestId", requestId);
                try { mapper.insertCheckin(row); code = "CHECKED"; }
                catch (RuntimeException e) { if (!ChannelRejection.isDuplicateKey(e)) throw e; }
                fact = mapper.selectCheckin(channelId, viewer, date);
            }
            Map<String, Object> result = factView(fact);
            result.put("resultCode", code);
            result.put("rewardIssued", false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "original-channel-checkin-request-v1");
            out.put("actorId", String.valueOf(viewer));
            out.put("channelId", channel);
            out.put("requestId", requestId);
            out.put("state", "APPLIED");
            out.put("requestHash", digest);
            out.put("checkinDate", date);
            out.put("resultRedacted", false);
            out.put("result", result);
            return out;
        });
    }

    public Map<String, Object> checkinReceipt(long viewer, long channelId, String requestId, String date)
    {
        String id = lowerUuid(requestId);
        Map<String, Object> stored = channels.selectReceipt(viewer, "CHECKIN", id);
        if (stored == null || stored.get("response") == null || ChannelGuildAccess.toLong(stored.get("channelId")) != channelId)
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "original-channel-checkin-request-v1");
            out.put("actorId", String.valueOf(viewer));
            out.put("channelId", String.valueOf(channelId));
            out.put("requestId", id);
            out.put("state", "NOT_FOUND");
            out.put("requestHash", checkinDigest(String.valueOf(channelId), date));
            out.put("checkinDate", date);
            // 业务日已过的未落地请求不会再被受理。
            out.put("unappliedFinal", !LocalDate.now(ZONE).toString().equals(date));
            return out;
        }
        Map<String, Object> out = ChannelReceipts.decode(stored.get("response").toString());
        out.remove("replayed");
        return out;
    }

    public Map<String, Object> checkinStatus(long viewer, long channelId)
    {
        ChannelMessageService.ChannelAccess a = messages.authorize(viewer, channelId, of(VIEW_CHANNEL), false);
        Map<String, Object> out = clock(viewer, channelId);
        String today = (String) out.get("checkinDate");
        Map<String, Object> fact = mapper.selectCheckin(channelId, viewer, today);
        Map<String, Object> yesterday = fact != null ? null : mapper.selectCheckin(channelId, viewer, LocalDate.parse(today).minusDays(1).toString());
        out.put("checked", fact != null);
        out.put("canCheckin", fact == null && a.channel().getStatus() == 0);
        out.put("makeupSelfService", false);
        out.put("rewardPolicyConfigured", false);
        if (fact != null) out.put("fact", factView(fact));
        Map<String, Object> streakRow = fact != null ? fact : yesterday;
        out.put("streak", streakRow == null ? 0 : (int) ChannelGuildAccess.toLong(streakRow.get("streak")));
        out.put("streakThrough", streakRow == null ? null : streakRow.get("checkinDate"));
        List<Map<String, Object>> history = new ArrayList<>();
        for (String d : mapper.listHistory(channelId, viewer, today)) history.add(new LinkedHashMap<>(Map.of("checkinDate", d, "kind", "CHECKIN")));
        out.put("history", history);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("configured", false);
        policy.put("enabled", false);
        policy.put("timezone", ZONE.getId());
        out.put("makeupPolicy", policy);
        return out;
    }

    public Map<String, Object> checkinRank(long viewer, long channelId, int days, int limit)
    {
        if (days != 30 || (limit != 10 && limit != 50)) throw ChannelRejection.badField("days/limit");
        messages.authorize(viewer, channelId, of(VIEW_CHANNEL), false);
        Map<String, Object> out = clock(viewer, channelId);
        String today = (String) out.get("checkinDate");
        List<Map<String, Object>> rows = mapper.rank(channelId, today, limit + 1);
        boolean more = rows.size() > limit && limit < 50;
        List<Map<String, Object>> list = new ArrayList<>();
        int rank = 0;
        for (Map<String, Object> r : rows)
        {
            if (list.size() == limit) break;
            long member = ChannelGuildAccess.toLong(r.get("memberId"));
            list.add(rankRow(channelId, member, ++rank, (int) ChannelGuildAccess.toLong(r.get("days")), today,
                    (String) r.get("nickname"), (String) r.get("avatar")));
        }
        int myDays = mapper.countDays(channelId, viewer, today);
        Integer myRank = myDays == 0 ? null : mapper.countAhead(channelId, today, myDays, viewer) + 1;
        out.put("periodDays", 30);
        out.put("limit", limit);
        out.put("list", list);
        out.put("me", rankRow(channelId, viewer, myRank, myDays, today, null, null));
        out.put("othersVisible", true);
        out.put("hasMore", more);
        return out;
    }

    private Map<String, Object> rankRow(long channelId, long member, Integer rank, int days, String today, String nickname, String avatar)
    {
        Map<String, Object> fact = mapper.selectCheckin(channelId, member, today);
        if (fact == null) fact = mapper.selectCheckin(channelId, member, LocalDate.parse(today).minusDays(1).toString());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("memberId", String.valueOf(member));
        v.put("rank", rank);
        v.put("days", days);
        v.put("makeupDays", 0);
        v.put("streak", fact == null ? 0 : (int) ChannelGuildAccess.toLong(fact.get("streak")));
        v.put("nickname", nickname);
        v.put("avatar", avatar);
        return v;
    }

    private static Map<String, Object> clock(long viewer, long channelId)
    {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-channel-checkin-v1");
        out.put("actorId", String.valueOf(viewer));
        out.put("channelId", String.valueOf(channelId));
        out.put("timezone", ZONE.getId());
        out.put("checkinDate", now.toLocalDate().toString());
        out.put("serverTime", now.toInstant().toEpochMilli());
        out.put("nextDayAt", now.toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
        return out;
    }

    static Map<String, Object> factView(Map<String, Object> f)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(ChannelGuildAccess.toLong(f.get("id"))));
        v.put("channelId", String.valueOf(ChannelGuildAccess.toLong(f.get("channelId"))));
        v.put("memberId", String.valueOf(ChannelGuildAccess.toLong(f.get("memberId"))));
        v.put("checkinDate", f.get("checkinDate"));
        v.put("source", f.get("source"));
        v.put("streak", (int) ChannelGuildAccess.toLong(f.get("streak")));
        java.util.Date at = ChannelRejection.toDate(f.get("checkedAt"));
        v.put("checkedAt", at == null ? 1L : at.getTime());
        return v;
    }

    // ================================================================ helpers

    static String lowerUuid(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ChannelRejection.badField("requestId");
        return s;
    }

    static String decimal(Object raw, String field)
    {
        if (!(raw instanceof String s) || !s.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField(field);
        return s;
    }

    /** DATETIME 以上海墙钟存放（库会话 +08:00）。 */
    static Long shanghaiMillis(Object value)
    {
        if (value instanceof java.time.LocalDateTime t) return t.atZone(ZONE).toInstant().toEpochMilli();
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().atZone(ZONE).toInstant().toEpochMilli();
        return null;
    }

    static boolean cleanText(String value, int limit)
    {
        return value != null && !value.isEmpty() && value.codePointCount(0, value.length()) <= limit && value.equals(value.strip())
                && !value.contains("<") && !value.contains("&") && value.indexOf('\0') < 0;
    }
}
