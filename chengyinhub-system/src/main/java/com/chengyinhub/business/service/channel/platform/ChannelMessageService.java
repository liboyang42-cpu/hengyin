package com.chengyinhub.business.service.channel.platform;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.IChannelConversationGate;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;
import static com.chengyinhub.business.service.channel.platform.ChannelGuildAccess.toLong;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;

/**
 * 频道消息合同（App docs/channels/contract.md §2）：编辑/撤回 CAS + 修订快照 + 变更流，置顶，检索，上下文，
 * 以及 IM 原链路的频道授权闸与十进制字符串快照投影。消息仍存 im_message，不另造收件箱。
 */
@Service
public class ChannelMessageService implements IChannelConversationGate
{
    static final String SYNC_CONTRACT = "VISIBLE_ORDER_PLUS_MUTATIONS_V1";
    static final String REQUEST_PATTERN = "[A-Za-z0-9_-]{16,64}";
    static final int PIN_LIMIT = 20;

    @Autowired
    private ChannelMessageMapper messages;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired(required = false)
    private IWxContentSecurityService contentSecurity;
    @Autowired(required = false)
    private IUmsMemberService members;

    public ChannelMessageService() { }

    ChannelMessageService(ChannelMessageMapper messages, ChannelPlatformMapper channels, ChannelGuildAccess access)
    {
        this.messages = messages;
        this.channels = channels;
        this.access = access;
    }

    // ------------------------------------------------------------------ gate for the original IM pipeline

    @Override
    public boolean isChannel(Long conversationId)
    {
        if (conversationId == null || conversationId <= 0) return false;
        Integer type = channels.selectConversationType(conversationId);
        return type != null && type == CONVERSATION_TYPE_CHANNEL;
    }

    @Override
    public void requireRead(Long memberId, Long conversationId)
    {
        try { authorize(memberId == null ? 0 : memberId, conversationId, of(VIEW_CHANNEL, READ_HISTORY), false); }
        catch (ChannelRejection e) { throw new ServiceException(e.getMessage(), e.getStatus()).setDetailMessage(e.getErrorCode()); }
    }

    @Override
    public void requireSend(Long memberId, Long conversationId)
    {
        try
        {
            ChannelAccess a = authorize(memberId == null ? 0 : memberId, conversationId, of(VIEW_CHANNEL, SEND_MESSAGE), true);
            int slowmode = a.channel.getSlowmodeSec() == null ? 0 : a.channel.getSlowmodeSec();
            if (slowmode > 0 && !has(a.bits, BYPASS_SLOWMODE))
            {
                Date last = messages.lastSentAt(conversationId, memberId);
                if (last != null && System.currentTimeMillis() - last.getTime() < slowmode * 1000L)
                    throw new ChannelRejection(429, "SLOWMODE_ACTIVE", "慢速模式中，请稍后再发");
            }
        }
        catch (ChannelRejection e) { throw new ServiceException(e.getMessage(), e.getStatus()).setDetailMessage(e.getErrorCode()); }
    }

    record ChannelAccess(ChannelRow channel, long bits) { }

    ChannelAccess authorize(long memberId, Long conversationId, long needed, boolean write)
    {
        if (memberId <= 0) throw new ChannelRejection(401, "UNAUTHORIZED", "请先登录");
        ChannelRow row = conversationId == null ? null : channels.selectChannel(conversationId);
        if (row == null || row.isCategory() || row.getStatus() > 1) throw ChannelRejection.notFound();
        ChannelGuildAccess.requireSupportedKind(row.getGuildKind());
        long bits = access.effective(row, memberId);
        if (bits == 0) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能查看频道");
        if (!has(bits, VIEW_CHANNEL)) throw ChannelRejection.notFound();
        if (write && row.getStatus() == 1) throw new ChannelRejection(409, "CHANNEL_READ_ONLY", "频道已归档，只读");
        if ((bits & needed) != needed) throw new ChannelRejection(403, "MESSAGE_FORBIDDEN", "没有该频道的消息权限");
        channels.ensureConversationMember(row.getId(), memberId);
        return new ChannelAccess(row, bits);
    }

    // ------------------------------------------------------------------ projections

    /** 历史页：把原 list 换成十进制字符串快照，并附变更流同步口径。 */
    public Map<String, Object> decorateHistory(long conversationId, Map<String, Object> page, List<Long> orderedIds)
    {
        Map<String, Object> data = new LinkedHashMap<>(page);
        data.put("list", snapshots(conversationId, orderedIds));
        Long cursor = messages.maxMutation(conversationId);
        data.put("mutation_cursor", String.valueOf(cursor == null ? 0L : cursor));
        data.put("sync_contract", SYNC_CONTRACT);
        data.put("legacy_cache_action", "RELOAD_ON_RESUME");
        Object next = data.get("nextCursor");
        if (next != null) data.put("nextCursor", String.valueOf(next));
        Object after = data.get("nextAfter");
        if (after != null) data.put("nextAfter", String.valueOf(after));
        return data;
    }

    public Map<String, Object> snapshot(long conversationId, long messageId)
    {
        List<Map<String, Object>> rows = snapshots(conversationId, List.of(messageId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<Map<String, Object>> snapshots(long conversationId, List<Long> ids)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return list;
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> r : messages.selectMessages(ids)) byId.put(toLong(r.get("id")), r);
        Set<Long> pinned = new HashSet<>(messages.selectPinnedIds(conversationId, ids));
        Map<Long, List<Map<String, Object>>> mentions = new HashMap<>();
        for (Map<String, Object> m : messages.selectMentions(ids))
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("member_id", String.valueOf(toLong(m.get("targetId"))));
            v.put("mention_type", m.get("mentionType"));
            mentions.computeIfAbsent(toLong(m.get("messageId")), k -> new ArrayList<>()).add(v);
        }
        for (Long id : ids)
        {
            Map<String, Object> r = byId.get(id);
            if (r == null || toLong(r.get("conversationId")) != conversationId) continue;
            list.add(snapshotView(r, pinned.contains(id), mentions.get(id)));
        }
        return list;
    }

    static Map<String, Object> snapshotView(Map<String, Object> r, boolean pinned, List<Map<String, Object>> mentions)
    {
        int status = (int) toLong(r.get("status"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(toLong(r.get("id"))));
        v.put("conversationId", String.valueOf(toLong(r.get("conversationId"))));
        v.put("version", Math.max(1L, toLong(r.get("version"))));
        v.put("status", status);
        v.put("msgType", (int) toLong(r.get("msgType")));
        v.put("senderId", String.valueOf(toLong(r.get("senderId"))));
        v.put("senderName", r.get("senderName"));
        v.put("senderAvatar", r.get("senderAvatar"));
        v.put("createTime", ChannelPlatformService.format(r.get("createTime")));
        if (status == 0)
        {
            v.put("content", r.get("content"));
            v.put("extraJson", r.get("extraJson"));
            v.put("edited_at", ChannelPlatformService.format(r.get("editedAt")));
            v.put("pinned", pinned);
            boolean everyone = false;
            if (mentions != null) for (Map<String, Object> m : mentions) everyone |= "EVERYONE".equals(m.get("mention_type"));
            v.put("mentions", mentions == null ? List.of() : mentions);
            v.put("mention_everyone", everyone);
        }
        return v;
    }

    // ------------------------------------------------------------------ mutations

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> edit(long viewer, Map<String, Object> body)
    {
        Request q = Request.of(body, Set.of("conversation_id", "message_id", "expected_version", "content",
                "mention_member_ids", "mention_role_ids", "mention_everyone", "request_id"));
        long conv = q.id("conversation_id"), messageId = q.id("message_id");
        long expected = q.positiveLong("expected_version");
        String content = q.string("content");
        if (content.isBlank() || content.codePointCount(0, content.length()) > 2000) throw ChannelRejection.badField("内容不合法");
        List<Long> memberIds = q.ids("mention_member_ids"), roleIds = q.ids("mention_role_ids");
        boolean everyone = q.bool("mention_everyone");
        String fp = ChannelReceipts.fingerprint("EDIT", conv, q.body);
        return ChannelReceipts.once(channels, viewer, ChannelReceipts.MSG, q.requestId, fp, "EDIT", conv, () -> {
            ChannelAccess a = authorize(viewer, conv, of(VIEW_CHANNEL, SEND_MESSAGE), true);
            if (everyone && !has(a.bits, MENTION_EVERYONE)) throw new ChannelRejection(403, "MESSAGE_FORBIDDEN", "没有 @全体 权限");
            Map<String, Object> m = lockOwn(conv, messageId);
            if (toLong(m.get("senderId")) != viewer) throw new ChannelRejection(403, "MESSAGE_FORBIDDEN", "只能编辑自己的消息");
            if (toLong(m.get("msgType")) != 1) throw new ChannelRejection(409, "MESSAGE_NOT_EDITABLE", "只有文字消息可以编辑");
            long version = toLong(m.get("version"));
            if (version != expected) throw new ChannelRejection(409, "MESSAGE_VERSION_CONFLICT", "消息已被修改");
            Set<String> wanted = mentionKeys(memberIds, roleIds, everyone);
            boolean changed = !content.equals(m.get("content")) || !wanted.equals(currentMentions(messageId));
            if (changed)
            {
                checkText(viewer, content);
                messages.insertRevision(messageId, version, (String) m.get("content"), (int) toLong(m.get("checkStatus")), viewer);
                if (messages.updateContent(messageId, expected, content) != 1)
                    throw new ChannelRejection(409, "MESSAGE_VERSION_CONFLICT", "消息已被修改");
                messages.deleteMentions(messageId);
                for (Long id : memberIds) messages.insertMention(messageId, "USER", id);
                for (Long id : roleIds) messages.insertMention(messageId, "ROLE", id);
                if (everyone) messages.insertMention(messageId, "EVERYONE", 0L);
                mutation(conv, messageId, expected + 1, "EDIT");
            }
            long now = changed ? expected + 1 : expected;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message_id", String.valueOf(messageId));
            r.put("conversation_id", String.valueOf(conv));
            r.put("version", now);
            r.put("revision", now);
            r.put("changed", changed);
            return r;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recall(long viewer, Map<String, Object> body)
    {
        Request q = Request.of(body, Set.of("conversation_id", "message_id", "expected_version", "reason", "request_id"));
        long conv = q.id("conversation_id"), messageId = q.id("message_id");
        long expected = q.positiveLong("expected_version");
        String reason = q.body.get("reason") instanceof String s ? s : "";
        if (reason.codePointCount(0, reason.length()) > 200) throw ChannelRejection.badField("理由过长");
        String fp = ChannelReceipts.fingerprint("RECALL", conv, q.body);
        return ChannelReceipts.once(channels, viewer, ChannelReceipts.MSG, q.requestId, fp, "RECALL", conv, () -> {
            ChannelAccess a = authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
            Map<String, Object> m = lockOwn(conv, messageId);
            if (toLong(m.get("senderId")) != viewer && !has(a.bits, MANAGE_MESSAGE))
                throw new ChannelRejection(403, "MESSAGE_FORBIDDEN", "只能撤回自己的消息");
            long version = toLong(m.get("version"));
            boolean changed = false;
            if (toLong(m.get("status")) == 0)
            {
                if (version != expected) throw new ChannelRejection(409, "MESSAGE_VERSION_CONFLICT", "消息已被修改");
                if (messages.recall(messageId, expected) != 1) throw new ChannelRejection(409, "MESSAGE_VERSION_CONFLICT", "消息已被修改");
                messages.deletePin(conv, messageId);
                mutation(conv, messageId, expected + 1, "RECALL");
                version = expected + 1;
                changed = true;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message_id", String.valueOf(messageId));
            r.put("conversation_id", String.valueOf(conv));
            r.put("status", 1);
            r.put("version", version);
            r.put("changed", changed);
            return r;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pin(long viewer, Map<String, Object> body, boolean pin)
    {
        Request q = Request.of(body, Set.of("conversation_id", "message_id", "request_id"));
        long conv = q.id("conversation_id"), messageId = q.id("message_id");
        String op = pin ? "PIN" : "UNPIN";
        String fp = ChannelReceipts.fingerprint(op, conv, q.body);
        return ChannelReceipts.once(channels, viewer, ChannelReceipts.MSG, q.requestId, fp, op, conv, () -> {
            authorize(viewer, conv, of(VIEW_CHANNEL, PIN_MESSAGE), true);
            List<Map<String, Object>> current = messages.lockPins(conv);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message_id", String.valueOf(messageId));
            r.put("conversation_id", String.valueOf(conv));
            if (!pin)
            {
                messages.deletePin(conv, messageId);
                r.put("pinned", false);
                r.put("unpinned", true);
                return r;
            }
            Map<String, Object> m = lockOwn(conv, messageId);
            if (toLong(m.get("status")) != 0) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
            for (Map<String, Object> p : current)
            {
                if (toLong(p.get("messageId")) == messageId)
                {
                    r.put("pinned", true);
                    r.put("id", String.valueOf(toLong(p.get("id"))));
                    r.put("sort_no", (int) toLong(p.get("sortNo")));
                    return r;
                }
            }
            if (current.size() >= PIN_LIMIT) throw new ChannelRejection(409, "PIN_LIMIT_REACHED", "置顶已满 20 条");
            int sortNo = current.isEmpty() ? 100 : (int) toLong(current.get(0).get("sortNo")) + 100;
            Map<String, Object> row = new HashMap<>();
            row.put("conversationId", conv);
            row.put("messageId", messageId);
            row.put("sortNo", sortNo);
            row.put("pinnedBy", viewer);
            messages.insertPin(row);
            r.put("pinned", true);
            r.put("id", String.valueOf(toLong(row.get("id"))));
            r.put("sort_no", sortNo);
            return r;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sortPins(long viewer, Map<String, Object> body)
    {
        Request q = Request.of(body, Set.of("conversation_id", "ordered_pin_ids", "request_id"));
        long conv = q.id("conversation_id");
        List<Long> ordered = q.ids("ordered_pin_ids");
        String fp = ChannelReceipts.fingerprint("PIN_SORT", conv, q.body);
        return ChannelReceipts.once(channels, viewer, ChannelReceipts.MSG, q.requestId, fp, "PIN_SORT", conv, () -> {
            authorize(viewer, conv, of(VIEW_CHANNEL, PIN_MESSAGE), true);
            Set<Long> current = new HashSet<>();
            for (Map<String, Object> p : messages.lockPins(conv)) current.add(toLong(p.get("id")));
            if (!current.equals(new HashSet<>(ordered)) || current.size() != ordered.size())
                throw new ChannelRejection(409, "PIN_SET_CHANGED", "置顶列表已变化，请刷新");
            Map<String, Object> sortNos = new LinkedHashMap<>();
            int n = ordered.size();
            for (int i = 0; i < n; i++)
            {
                int sortNo = (n - i) * 100;
                messages.updatePinSort(ordered.get(i), sortNo);
                sortNos.put(String.valueOf(ordered.get(i)), sortNo);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("conversation_id", String.valueOf(conv));
            r.put("updated", n);
            r.put("sort_nos", sortNos);
            return r;
        });
    }

    // ------------------------------------------------------------------ reads

    public Map<String, Object> receipt(long viewer, String requestId)
    {
        if (requestId == null || !requestId.matches(REQUEST_PATTERN)) throw ChannelRejection.badField("request_id 不合法");
        Map<String, Object> stored = channels.selectReceipt(viewer, ChannelReceipts.MSG, requestId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("request_id", requestId);
        if (stored == null || stored.get("response") == null)
        {
            r.put("state", "NOT_FOUND");
            r.put("outcome_confirmed", false);
            return r;
        }
        long conv = toLong(stored.get("channelId"));
        r.put("state", "APPLIED");
        r.put("outcome_confirmed", true);
        r.put("operation", stored.get("operation"));
        r.put("conversation_id", String.valueOf(conv));
        try
        {
            authorize(viewer, conv, VIEW_CHANNEL.mask(), false);
            r.put("result", ChannelReceipts.decode(stored.get("response").toString()));
        }
        catch (ChannelRejection gone)
        {
            r.put("result_redacted", true);
        }
        return r;
    }

    public Map<String, Object> mutations(long viewer, long conv, String cursor, int limit)
    {
        authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
        long after = cursor(cursor, true);
        int size = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = messages.selectMutations(conv, after, size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> m : rows) ids.add(toLong(m.get("messageId")));
        Map<Long, Map<String, Object>> current = new HashMap<>();
        for (Map<String, Object> s : snapshots(conv, ids)) current.put(Long.parseLong((String) s.get("id")), s);
        List<Map<String, Object>> list = new ArrayList<>();
        long last = after;
        for (Map<String, Object> m : rows)
        {
            long id = toLong(m.get("id")), messageId = toLong(m.get("messageId")), version = toLong(m.get("version"));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("mutation_cursor", String.valueOf(id));
            v.put("message_id", String.valueOf(messageId));
            v.put("version", version);
            v.put("kind", m.get("kind"));
            Map<String, Object> snap = current.get(messageId);
            if ("RECALL".equals(m.get("kind")) || snap == null || toLong(snap.get("status")) != 0
                    || toLong(snap.get("version")) != version) v.put("invalidate", true);
            else v.put("message", snap);
            list.add(v);
            last = id;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("has_more", more);
        data.put("next_mutation_cursor", String.valueOf(last));
        data.put("sync_contract", SYNC_CONTRACT);
        return data;
    }

    public Map<String, Object> pins(long viewer, long conv)
    {
        authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> p : messages.selectPins(conv))
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", String.valueOf(toLong(p.get("id"))));
            v.put("message_id", String.valueOf(toLong(p.get("messageId"))));
            v.put("conversation_id", String.valueOf(conv));
            v.put("summary", p.get("summary"));
            v.put("sort_no", (int) toLong(p.get("sortNo")));
            list.add(v);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", list.size());
        data.put("limit", PIN_LIMIT);
        return data;
    }

    public Map<String, Object> search(long viewer, long conv, String keyword, String from, String to, String authorId,
            String cursor, int limit)
    {
        authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
        if (keyword == null || keyword.isBlank() || keyword.length() > 100) throw ChannelRejection.badField("关键词不合法");
        Long before = cursor == null ? null : cursor(cursor, false);
        Long author = authorId == null ? null : cursor(authorId, false);
        int size = Math.max(1, Math.min(limit, 20));
        String escaped = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        List<Long> ids = messages.search(conv, escaped, date(from), date(to), author, before, size + 1);
        boolean more = ids.size() > size;
        if (more) ids = ids.subList(0, size);
        List<Map<String, Object>> list = snapshots(conv, ids);
        Date oldest = messages.oldestMessageTime(conv);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("has_more", more);
        data.put("next_cursor", more ? list.get(list.size() - 1).get("id") : null);
        data.put("degraded", false);
        data.put("degraded_reason", null);
        data.put("oldest_searchable_at", oldest == null ? "" : ChannelPlatformService.format(oldest));
        return data;
    }

    public Map<String, Object> context(long viewer, long conv, long anchorId, int limit)
    {
        authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
        Map<String, Object> anchor = snapshot(conv, anchorId);
        if (anchor == null || toLong(anchor.get("status")) != 0) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        int size = Math.max(1, Math.min(limit, 20));
        List<Long> older = messages.contextOlder(conv, anchorId, size + 1);
        List<Long> newer = messages.contextNewer(conv, anchorId, size + 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "original-channel-message-context-v1");
        data.put("conversation_id", String.valueOf(conv));
        data.put("anchor_id", String.valueOf(anchorId));
        data.put("anchor", anchor);
        data.put("older", side(conv, older, size, true));
        data.put("newer", side(conv, newer, size, false));
        return data;
    }

    private Map<String, Object> side(long conv, List<Long> ids, int size, boolean older)
    {
        boolean more = ids.size() > size;
        List<Long> page = more ? new ArrayList<>(ids.subList(0, size)) : new ArrayList<>(ids);
        if (older) java.util.Collections.reverse(page);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("list", snapshots(conv, page));
        String edge = page.isEmpty() ? null : String.valueOf(older ? page.get(0) : page.get(page.size() - 1));
        v.put("has_more", more);
        v.put("next_cursor", older && more ? edge : null);
        v.put("next_after", !older && more ? edge : null);
        return v;
    }

    public Map<String, Object> revisions(long viewer, long messageId, String cursor, int limit)
    {
        Map<String, Object> m = messages.selectMessages(List.of(messageId)).stream().findFirst()
                .orElseThrow(() -> new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在"));
        long conv = toLong(m.get("conversationId"));
        if (!isChannel(conv)) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        authorize(viewer, conv, of(VIEW_CHANNEL, READ_HISTORY), false);
        Long before = cursor == null ? null : cursor(cursor, false);
        int size = Math.max(1, Math.min(limit, 20));
        List<Map<String, Object>> rows = messages.selectRevisions(messageId, before, size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("message_id", String.valueOf(messageId));
            v.put("revision_no", toLong(r.get("revisionNo")));
            int check = (int) toLong(r.get("checkStatus"));
            v.put("check_status", check);
            if (check == 1) v.put("content", r.get("content"));
            list.add(v);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("has_more", more);
        data.put("next_cursor", more ? String.valueOf(list.get(list.size() - 1).get("revision_no")) : null);
        return data;
    }

    /** 已读回执（原 markRead 推进后读回本人索引行）。 */
    public Map<String, Object> readState(long viewer, long conv, long readTo)
    {
        Map<String, Object> state = channels.selectReadState(viewer, conv);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("conversationId", conv);
        r.put("readTo", readTo);
        r.put("lastReadMessageId", state == null ? 0L : toLong(state.get("lastRead")));
        r.put("unread", state == null ? 0L : toLong(state.get("unread")));
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> lockOwn(long conv, long messageId)
    {
        Map<String, Object> m = messages.lockMessage(messageId);
        if (m == null || toLong(m.get("conversationId")) != conv) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        return m;
    }

    private void mutation(long conv, long messageId, long version, String kind)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("conversationId", conv);
        row.put("messageId", messageId);
        row.put("version", version);
        row.put("kind", kind);
        messages.insertMutation(row);
    }

    private Set<String> currentMentions(long messageId)
    {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> m : messages.selectMentions(List.of(messageId)))
            keys.add(m.get("mentionType") + ":" + toLong(m.get("targetId")));
        return keys;
    }

    private static Set<String> mentionKeys(Collection<Long> members, Collection<Long> roles, boolean everyone)
    {
        Set<String> keys = new HashSet<>();
        for (Long id : members) keys.add("USER:" + id);
        for (Long id : roles) keys.add("ROLE:" + id);
        if (everyone) keys.add("EVERYONE:0");
        return keys;
    }

    private void checkText(long viewer, String content)
    {
        if (contentSecurity == null || members == null) return;
        String verdict = IWxContentSecurityService.checkTextAsMember(contentSecurity, members, viewer, content);
        if (verdict != null) throw new ChannelRejection(422, "CONTENT_REJECTED", verdict);
    }

    private static long cursor(String raw, boolean allowZero)
    {
        if (raw != null && (raw.matches("[1-9][0-9]{0,18}") || (allowZero && "0".equals(raw)))) return Long.parseLong(raw);
        throw ChannelRejection.badField("游标不合法");
    }

    private static Date date(String raw)
    {
        if (raw == null) return null;
        if (raw.isEmpty() || raw.length() > 64) throw ChannelRejection.badField("时间不合法");
        for (String pattern : new String[] { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd" })
        {
            try
            {
                SimpleDateFormat f = new SimpleDateFormat(pattern);
                f.setLenient(false);
                return f.parse(raw);
            }
            catch (ParseException ignored) { }
        }
        throw ChannelRejection.badField("时间不合法");
    }

    /** JSON 写请求：未知字段拒绝，十进制字符串 id，request_id 16–64。 */
    static final class Request
    {
        final Map<String, Object> body;
        final String requestId;

        private Request(Map<String, Object> body, String requestId) { this.body = body; this.requestId = requestId; }

        static Request of(Map<String, Object> body, Set<String> allowed)
        {
            if (body == null) throw ChannelRejection.badField("请求体为空");
            for (String key : body.keySet()) if (!allowed.contains(key)) throw ChannelRejection.badField("未知字段 " + key);
            Object id = body.get("request_id");
            if (!(id instanceof String text) || !text.matches(REQUEST_PATTERN)) throw ChannelRejection.badField("request_id 不合法");
            return new Request(new LinkedHashMap<>(body), text);
        }

        long id(String key)
        {
            Object raw = body.get(key);
            if (raw instanceof String text && text.matches("[1-9][0-9]{0,18}")) return Long.parseLong(text);
            throw ChannelRejection.badField(key + " 不合法");
        }

        long positiveLong(String key)
        {
            Object raw = body.get(key);
            if ((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() > 0) return ((Number) raw).longValue();
            throw ChannelRejection.badField(key + " 不合法");
        }

        String string(String key)
        {
            if (body.get(key) instanceof String text) return text;
            throw ChannelRejection.badField(key + " 不合法");
        }

        boolean bool(String key)
        {
            Object raw = body.get(key);
            if (raw == null) return false;
            if (raw instanceof Boolean b) return b;
            throw ChannelRejection.badField(key + " 不合法");
        }

        List<Long> ids(String key)
        {
            Object raw = body.get(key);
            if (raw == null) return List.of();
            if (!(raw instanceof List<?> items) || items.size() > 20) throw ChannelRejection.badField(key + " 不合法");
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (Object item : items)
            {
                if (!(item instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField(key + " 不合法");
                ids.add(Long.parseLong(text));
            }
            if (ids.size() != items.size()) throw ChannelRejection.badField(key + " 重复");
            return new ArrayList<>(ids);
        }
    }

    static boolean same(Object a, Object b) { return Objects.equals(a, b); }
}
