package com.chengyinhub.business.service.channel.messaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.channel.messaging.ImMessagingMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.service.IImRealtimePublisher;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.common.exception.ServiceException;

import static com.chengyinhub.business.service.channel.messaging.MessageComposeSupport.number;

/**
 * W5 C2：表情回应、消息附加信息（回应计数 + 引用摘要 + 话题计数）、话题列表、正在输入。
 *
 * <p>读权限一律走 {@link IImService#requireReadable}（与历史分页同一道闸）；频道的回应再要 ADD_REACTION。
 * 表情只收 {@link #EMOJI} 里的固定 key，客户端自己画图标，不存自由文本，所以不需要内容审核。
 */
@Service
public class MessageInteractionService
{
    /** 固定表情集合（key → 展示用 Unicode，客户端可直接用 key 对照自己的表）。 */
    public static final Map<String, String> EMOJI;
    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("thumbs_up", "👍");
        m.put("heart", "❤️");
        m.put("laugh", "😂");
        m.put("party", "🎉");
        m.put("wow", "😮");
        m.put("sad", "😢");
        m.put("fire", "🔥");
        m.put("clap", "👏");
        EMOJI = java.util.Collections.unmodifiableMap(m);
    }

    static final int BATCH_LIMIT = 50;
    static final int EXCERPT_LIMIT = 80;
    static final long TYPING_INTERVAL_MS = 3000;

    @Autowired
    private ImMessagingMapper mapper;
    @Autowired
    private ChannelMessageMapper messages;
    @Autowired
    private IImService im;
    @Autowired(required = false)
    private ChannelMessageService channels;
    @Autowired(required = false)
    private IImRealtimePublisher realtime;

    /** 本节点的“正在输入”节流（每人每会话 3 秒一次）；多节点最坏每节点各一次，提示本身无副作用。 */
    private final Map<String, Long> typingAt = new ConcurrentHashMap<>();

    public MessageInteractionService() { }

    MessageInteractionService(ImMessagingMapper mapper, ChannelMessageMapper messages, IImService im,
            ChannelMessageService channels, IImRealtimePublisher realtime)
    {
        this.mapper = mapper;
        this.messages = messages;
        this.im = im;
        this.channels = channels;
        this.realtime = realtime;
    }

    // ---------------------------------------------------------------- reactions

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> react(long viewer, Map<String, Object> body)
    {
        if (body == null) throw bad("请求体为空");
        for (String key : body.keySet())
            if (!Set.of("conversation_id", "message_id", "emoji", "on").contains(key)) throw bad("未知字段 " + key);
        long conv = id(body.get("conversation_id"), "conversation_id");
        long messageId = id(body.get("message_id"), "message_id");
        Object emojiRaw = body.get("emoji");
        if (!(emojiRaw instanceof String emoji) || !EMOJI.containsKey(emoji)) throw bad("不支持的表情");
        Object onRaw = body.get("on");
        if (onRaw != null && !(onRaw instanceof Boolean)) throw bad("on 不合法");
        boolean on = onRaw == null || Boolean.TRUE.equals(onRaw);

        im.requireReadable(viewer, conv);
        if (channels != null && channels.isChannel(conv)) channels.requireReact(viewer, conv);
        Map<String, Object> head = mapper.selectMessageHead(messageId);
        if (head == null || number(head.get("conversationId")) != conv || number(head.get("status")) != ImMessage.STATUS_NORMAL
                || number(head.get("checkStatus")) != 1)
            throw new ServiceException("消息不存在或已撤回", 404);
        boolean changed = on ? mapper.insertReaction(messageId, viewer, emoji, conv) > 0
                : mapper.deleteReaction(messageId, viewer, emoji) > 0;
        if (changed && realtime != null)
        {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("message_id", String.valueOf(messageId));
            try { realtime.publish(conv, IImRealtimePublisher.REACTION_UPDATED, hint); }
            catch (RuntimeException ignored) { /* 旁路 */ }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("conversation_id", String.valueOf(conv));
        r.put("message_id", String.valueOf(messageId));
        r.put("changed", changed);
        r.put("reactions", reactions(List.of(messageId), viewer).getOrDefault(messageId, List.of()));
        return r;
    }

    public Map<String, Object> emojiSet()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, String> e : EMOJI.entrySet())
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("key", e.getKey());
            v.put("glyph", e.getValue());
            list.add(v);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("list", list);
        return r;
    }

    // ---------------------------------------------------------------- extras (reactions + quotes + threads)

    public Map<String, Object> extras(long viewer, long conv, String rawIds)
    {
        List<Long> ids = idList(rawIds);
        im.requireReadable(viewer, conv);
        Map<String, Object> items = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation_id", String.valueOf(conv));
        data.put("items", items);
        if (ids.isEmpty()) return data;
        // 只留属于本会话的 id。
        List<Long> own = new ArrayList<>();
        for (Map<String, Object> row : messages.selectMessages(ids))
            if (number(row.get("conversationId")) == conv) own.add(number(row.get("id")));
        if (own.isEmpty()) return data;
        Map<Long, List<Map<String, Object>>> reactions = reactions(own, viewer);
        Map<Long, Map<String, Object>> refs = new HashMap<>();
        Set<Long> quoted = new LinkedHashSet<>();
        for (Map<String, Object> ref : mapper.selectRefs(own))
        {
            refs.put(number(ref.get("messageId")), ref);
            long reply = number(ref.get("replyToId"));
            if (reply > 0) quoted.add(reply);
        }
        Map<Long, Map<String, Object>> quotes = quotes(conv, quoted);
        Map<Long, Map<String, Object>> threads = new HashMap<>();
        for (Map<String, Object> t : mapper.selectThreadSummaries(conv, own))
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("reply_count", number(t.get("replyCount")));
            v.put("last_reply_id", String.valueOf(number(t.get("lastReplyId"))));
            threads.put(number(t.get("rootId")), v);
        }
        for (Long id : own)
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("reactions", reactions.getOrDefault(id, List.of()));
            Map<String, Object> ref = refs.get(id);
            long reply = ref == null ? 0 : number(ref.get("replyToId"));
            long root = ref == null ? 0 : number(ref.get("threadRootId"));
            v.put("reply_to", reply > 0 ? quotes.getOrDefault(reply, unavailable(reply)) : null);
            v.put("thread_root_id", root > 0 ? String.valueOf(root) : null);
            v.put("thread", threads.get(id));
            items.put(String.valueOf(id), v);
        }
        return data;
    }

    // ---------------------------------------------------------------- threads

    public Map<String, Object> thread(long viewer, long conv, long rootId, String after, int limit)
    {
        im.requireReadable(viewer, conv);
        long afterId = after == null || after.isEmpty() ? 0 : id(after, "after");
        int size = Math.max(1, Math.min(limit, 50));
        Map<String, Object> head = mapper.selectMessageHead(rootId);
        if (head == null || number(head.get("conversationId")) != conv) throw new ServiceException("话题不存在", 404);
        List<Long> ids = mapper.selectThreadMessageIds(conv, rootId, afterId, size + 1);
        boolean more = ids.size() > size;
        if (more) ids = new ArrayList<>(ids.subList(0, size));
        List<Long> all = new ArrayList<>();
        all.add(rootId);
        all.addAll(ids);
        Map<Long, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> row : messages.selectMessages(all)) rows.put(number(row.get("id")), row);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Long id : ids)
        {
            Map<String, Object> row = rows.get(id);
            if (row != null) list.add(view(row));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation_id", String.valueOf(conv));
        data.put("root", rows.containsKey(rootId) ? view(rows.get(rootId)) : null);
        data.put("list", list);
        data.put("has_more", more);
        data.put("next_after", more && !ids.isEmpty() ? String.valueOf(ids.get(ids.size() - 1)) : null);
        return data;
    }

    // ---------------------------------------------------------------- typing

    public Map<String, Object> typing(long viewer, long conv)
    {
        im.requireReadable(viewer, conv);
        if (channels != null && channels.isChannel(conv)) channels.mentionScope(viewer, conv); // 发送权限（不受慢速模式限制）
        long now = System.currentTimeMillis();
        String key = viewer + ":" + conv;
        Long last = typingAt.get(key);
        boolean sent = false;
        if (last == null || now - last >= TYPING_INTERVAL_MS)
        {
            typingAt.put(key, now);
            if (typingAt.size() > 10_000) typingAt.entrySet().removeIf(e -> now - e.getValue() > 60_000);
            if (realtime != null)
            {
                Map<String, Object> hint = new LinkedHashMap<>();
                hint.put("member_id", String.valueOf(viewer));
                hint.put("expires_in_ms", 6000);
                try { realtime.publish(conv, IImRealtimePublisher.TYPING, hint); sent = true; }
                catch (RuntimeException ignored) { /* 旁路 */ }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("conversation_id", String.valueOf(conv));
        r.put("sent", sent);
        return r;
    }

    // ---------------------------------------------------------------- helpers

    Map<Long, List<Map<String, Object>>> reactions(List<Long> ids, long viewer)
    {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Map<String, Object> row : mapper.selectReactionCounts(ids, viewer))
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("emoji", row.get("emoji"));
            v.put("count", number(row.get("total")));
            v.put("mine", number(row.get("mine")) > 0);
            out.computeIfAbsent(number(row.get("messageId")), k -> new ArrayList<>()).add(v);
        }
        for (List<Map<String, Object>> list : out.values())
            list.sort((a, b) -> order(a.get("emoji")) - order(b.get("emoji")));
        return out;
    }

    private static int order(Object emoji)
    {
        int i = 0;
        for (String key : EMOJI.keySet()) { if (key.equals(emoji)) return i; i++; }
        return i;
    }

    private Map<Long, Map<String, Object>> quotes(long conv, Set<Long> ids)
    {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Map<String, Object> row : messages.selectMessages(new ArrayList<>(ids)))
        {
            long id = number(row.get("id"));
            if (number(row.get("conversationId")) != conv) continue;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("message_id", String.valueOf(id));
            boolean visible = number(row.get("status")) == ImMessage.STATUS_NORMAL && number(row.get("checkStatus")) == 1;
            v.put("state", visible ? "OK" : number(row.get("status")) == ImMessage.STATUS_RECALLED ? "RECALLED" : "UNAVAILABLE");
            if (visible)
            {
                v.put("sender_id", String.valueOf(number(row.get("senderId"))));
                v.put("sender_name", row.get("senderName"));
                v.put("msg_type", number(row.get("msgType")));
                v.put("excerpt", excerpt(row));
            }
            out.put(id, v);
        }
        return out;
    }

    private static Map<String, Object> unavailable(long id)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("message_id", String.valueOf(id));
        v.put("state", "UNAVAILABLE");
        return v;
    }

    static String excerpt(Map<String, Object> row)
    {
        long type = number(row.get("msgType"));
        if (type != ImMessage.MSG_TEXT && type != ImMessage.MSG_CARD && type != ImMessage.MSG_POLL) return null;
        Object content = row.get("content");
        if (content == null) return null;
        String text = content.toString();
        if (text.codePointCount(0, text.length()) <= EXCERPT_LIMIT) return text;
        return text.substring(0, text.offsetByCodePoints(0, EXCERPT_LIMIT)) + "…";
    }

    private static Map<String, Object> view(Map<String, Object> row)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        boolean visible = number(row.get("status")) == ImMessage.STATUS_NORMAL && number(row.get("checkStatus")) == 1;
        v.put("id", String.valueOf(number(row.get("id"))));
        v.put("conversationId", String.valueOf(number(row.get("conversationId"))));
        v.put("senderId", String.valueOf(number(row.get("senderId"))));
        v.put("senderName", row.get("senderName"));
        v.put("senderAvatar", row.get("senderAvatar"));
        v.put("msgType", number(row.get("msgType")));
        v.put("status", number(row.get("status")));
        v.put("content", visible ? row.get("content") : null);
        v.put("extraJson", visible ? row.get("extraJson") : null);
        v.put("createTime", row.get("createTime") == null ? null : row.get("createTime").toString());
        v.put("edited", row.get("editedAt") != null);
        return v;
    }

    private static List<Long> idList(String raw)
    {
        List<Long> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        Set<Long> seen = new LinkedHashSet<>();
        for (String part : raw.split(","))
        {
            String text = part.trim();
            if (!text.matches("[1-9][0-9]{0,18}")) throw bad("ids 不合法");
            seen.add(Long.parseLong(text));
        }
        if (seen.size() > BATCH_LIMIT) throw bad("ids 最多 " + BATCH_LIMIT + " 个");
        out.addAll(seen);
        return out;
    }

    private static long id(Object raw, String key)
    {
        String text = raw == null ? "" : String.valueOf(raw);
        if (!(raw instanceof String || raw instanceof Number) || !text.matches("[1-9][0-9]{0,18}")) throw bad(key + " 不合法");
        return Long.parseLong(text);
    }

    private static ServiceException bad(String message)
    {
        return new ServiceException(message, 400);
    }

}
