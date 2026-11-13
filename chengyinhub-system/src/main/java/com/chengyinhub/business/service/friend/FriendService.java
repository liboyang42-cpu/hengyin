package com.chengyinhub.business.service.friend;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.friend.FriendMapper;
import com.chengyinhub.business.service.channel.platform.ChannelReceipts;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;

/**
 * 好友合同（App Core/NativeFriend*.swift）：关系/请求/接受/拒绝/撤回/移除、备注/星标/分组、通讯录、好友进度。
 * <ul>
 * <li>拉黑沿用既有 im_block：任一方拉黑 → 关系投影为 NONE、列表不出现、请求返回 404 FRIEND_UNAVAILABLE（不披露谁拉黑）。</li>
 * <li>每个写都以 request_id 落原回执（族 FRIEND/FMETA/FREMOVE），payload_hash 与 App 本地指纹逐字节一致。</li>
 * <li>成为好友（接受或互相请求）时同事务建立/恢复单聊会话（DM），relationship.conversation_id 返回该会话。</li>
 * </ul>
 * 好友接口沿用 App 好友端的旧信封：HTTP 200 + body.code；带数据的拒绝回执以内部键 {@code __status} 交控制器映射。
 */
@Service
public class FriendService
{
    public static final String STATUS_KEY = "__status";
    static final Pattern UUID_RE = Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    static final Set<String> ACTIONS = Set.of("request", "accept", "reject", "cancel");
    static final Set<String> SECTIONS = Set.of("together", "recent", "all");
    static final int PAGE = 50;

    @Autowired
    private FriendMapper friends;

    @Autowired
    private ChannelPlatformMapper receipts;

    @Autowired(required = false)
    private FriendProgressGate progressGate;

    /** 进度隐私闸（由 ProgressPrivacyService 提供）；缺省全部可见，仍受好友侧 progress_scope 约束。 */
    public interface FriendProgressGate
    {
        boolean friendAggregateVisible(long owner, long viewer);
    }

    // ---------------------------------------------------------------- relation

    public Map<String, Object> relationship(long viewer, long peer)
    {
        requirePeer(viewer, peer);
        return relation(viewer, peer, friends.selectPair(Math.min(viewer, peer), Math.max(viewer, peer)));
    }

    Map<String, Object> relation(long viewer, long peer, Map<String, Object> pair)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("viewer_id", viewer);
        r.put("peer_id", peer);
        int state = state(viewer, pair);
        if (state != 0 && friends.countBlock(viewer, peer) > 0) state = 0;
        r.put("state", state);
        r.put("version", pair == null ? 0L : num(pair.get("version")));
        if (state == 1)
        {
            Long cid = friends.selectSingleConversation(bizKey(viewer, peer));
            if (cid != null && cid > 0) r.put("conversation_id", cid);
        }
        return r;
    }

    static int state(long viewer, Map<String, Object> pair)
    {
        if (pair == null) return 0;
        int status = (int) num(pair.get("status"));
        if (status == 1) return 1;
        if (status == 0 && num(pair.get("expired")) == 0) return num(pair.get("requesterId")) == viewer ? 2 : 3;
        return 0;
    }

    // ---------------------------------------------------------------- pages

    public Map<String, Object> page(long viewer, boolean requests, long cursor, int limit, Long reviewCursor)
    {
        if (cursor < 0 || limit < 1 || limit > 100) throw ChannelRejection.badField("cursor/limit");
        int size = Math.min(limit, PAGE);
        List<Map<String, Object>> rows = requests ? friends.listPending(viewer, cursor, size + 1) : friends.listFriends(viewer, cursor, size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Map<String, Object>> incoming = new ArrayList<>(), outgoing = new ArrayList<>(), list = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            long peer = num(row.get("peerId"));
            if (!requests) list.add(friendItem(row));
            else
            {
                boolean mine = num(row.get("requesterId")) == viewer;
                Map<String, Object> item = item(peer, nick(row, peer), mine ? 2 : 3, num(row.get("version")),
                        str(row.get("msg")), epochMs(row.get("expireTime")));
                (mine ? outgoing : incoming).add(item);
            }
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("viewer_id", viewer);
        page.put("incoming", incoming);
        page.put("outgoing", outgoing);
        page.put("friends", list);
        page.put("has_more", more);
        page.put("next_cursor", more ? num(rows.get(rows.size() - 1).get("pairId")) : null);
        if (requests)
        {
            // 好友申请当前不进入人工审核：审核队列恒为空页（PENDING_REVIEW 不会出现）。
            page.put("reviews", List.of());
            page.put("review_has_more", false);
            page.put("review_next_cursor", null);
        }
        else page.put("groups", groups(viewer));
        return page;
    }

    Map<String, Object> friendItem(Map<String, Object> row)
    {
        long peer = num(row.get("peerId"));
        Map<String, Object> item = item(peer, nick(row, peer), 1, num(row.get("version")), "", 0L);
        Object remark = row.get("remark");
        if (remark != null && !remark.toString().isEmpty()) item.put("friend_remark", remark.toString());
        if (row.get("groupId") != null) item.put("group_id", num(row.get("groupId")));
        item.put("starred", (int) num(row.get("starred")));
        return item;
    }

    static Map<String, Object> item(long peer, String nickname, int state, long version, String msg, long expiresMs)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("peer_id", peer);
        item.put("nickname", nickname);
        item.put("state", state);
        item.put("version", version);
        item.put("msg", msg == null ? "" : msg);
        item.put("expires_at_ms", expiresMs);
        return item;
    }

    // ---------------------------------------------------------------- request / accept / reject / cancel

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> write(long viewer, String action, Map<String, Object> body)
    {
        if (!ACTIONS.contains(action)) throw new ChannelRejection(404, "FRIEND_ACTION_NOT_FOUND", "不支持的操作");
        if (body == null) throw ChannelRejection.badField("body");
        if (!Set.of("peer_id", "request_id", "msg", "expected_version", "review_id", "source_type", "source_id", "source_generation")
                .containsAll(body.keySet())) throw ChannelRejection.badField("unknown field");
        long peer = longField(body, "peer_id", true);
        String requestId = uuid(body.get("request_id"));
        String msg = body.get("msg") instanceof String s ? s : null;
        if (msg == null) throw ChannelRejection.badField("msg");
        Long expected = body.containsKey("expected_version") ? longField(body, "expected_version", true) : null;
        Long review = body.containsKey("review_id") ? longField(body, "review_id", true) : null;
        int sourceType = body.containsKey("source_type") ? (int) longField(body, "source_type", true) : 0;
        long sourceId = body.containsKey("source_id") ? longField(body, "source_id", true) : 0L;
        String generation = body.get("source_generation") instanceof String g ? g : null;
        if ("request".equals(action))
        {
            String trimmed = msg.strip();
            if (trimmed.isEmpty() || msg.codePointCount(0, msg.length()) > 60 || expected != null || review != null)
                throw ChannelRejection.badField("msg");
            if (sourceType != 0 && (sourceType > 4 || sourceId <= 0
                    || (sourceType == 4) != (generation != null)
                    || (generation != null && !Set.of("legacySquare", "communityV1").contains(generation))))
                throw ChannelRejection.badField("source");
        }
        else if (!msg.isEmpty() || expected == null || sourceType != 0 || (review != null && !"cancel".equals(action)))
            throw ChannelRejection.badField("expected_version");
        requirePeer(viewer, peer);
        String proof = FriendProofs.command(peer, action, msg, expected, review, sourceType, sourceId, generation);
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, "FRIEND", requestId, proof, action, peer, () ->
        {
            apply(viewer, peer, action, msg, expected, review, sourceType, sourceId);
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("viewer_id", viewer);
            receipt.put("peer_id", peer);
            receipt.put("action", action);
            receipt.put("request_id", requestId);
            receipt.put("outcome", "APPLIED");
            receipt.put("payload_hash", proof);
            receipt.put("relationship", relation(viewer, peer, friends.selectPair(Math.min(viewer, peer), Math.max(viewer, peer))));
            return receipt;
        });
        out.remove("replayed");
        return out;
    }

    private void apply(long viewer, long peer, String action, String msg, Long expected, Long review, int sourceType, long sourceId)
    {
        long low = Math.min(viewer, peer), high = Math.max(viewer, peer);
        if (friends.countBlock(viewer, peer) > 0) throw new ChannelRejection(404, "FRIEND_UNAVAILABLE", "暂时无法添加对方");
        Map<String, Object> pair = friends.lockPair(low, high);
        int state = state(viewer, pair);
        Long source = sourceType == 0 ? null : sourceId;
        switch (action)
        {
            case "request" ->
            {
                if (pair == null)
                {
                    try { friends.insertPending(low, high, viewer, msg.strip(), sourceType, source); }
                    catch (RuntimeException e)
                    {
                        if (!ChannelRejection.isDuplicateKey(e)) throw e;
                        throw new ChannelRejection(409, "FRIEND_VERSION_CONFLICT", "关系刚刚变化，请刷新");
                    }
                    return;
                }
                if (state == 1 || state == 2) return;
                if (state == 3) { cas(pair, 1, num(pair.get("requesterId")), null, 0, null); openDirect(viewer, peer); return; }
                cas(pair, 0, viewer, msg.strip(), sourceType, source);
            }
            case "accept", "reject" ->
            {
                if (state != 3) throw new ChannelRejection(404, "FRIEND_REQUEST_NOT_FOUND", "好友申请已失效");
                if (num(pair.get("version")) != expected) throw new ChannelRejection(409, "FRIEND_VERSION_CONFLICT", "申请已变化，请刷新");
                if ("accept".equals(action)) { cas(pair, 1, num(pair.get("requesterId")), null, 0, null); openDirect(viewer, peer); }
                else cas(pair, 2, num(pair.get("requesterId")), null, 0, null);
            }
            default ->
            {
                if (review != null) throw new ChannelRejection(404, "FRIEND_REVIEW_NOT_FOUND", "审核记录不存在");
                if (state != 2) throw new ChannelRejection(404, "FRIEND_REQUEST_NOT_FOUND", "好友申请已失效");
                if (num(pair.get("version")) != expected) throw new ChannelRejection(409, "FRIEND_VERSION_CONFLICT", "申请已变化，请刷新");
                cas(pair, 3, viewer, null, 0, null);
            }
        }
    }

    private void cas(Map<String, Object> pair, int status, long requester, String msg, int sourceType, Long sourceId)
    {
        if (friends.transition(num(pair.get("id")), num(pair.get("version")), status, requester, msg, sourceType, sourceId) != 1)
            throw new ChannelRejection(409, "FRIEND_VERSION_CONFLICT", "关系刚刚变化，请刷新");
    }

    /** DM：与 ImServiceImpl.startSingle 同一 biz_key（low_high）；成为好友时建立或恢复双方会话成员行。 */
    void openDirect(long a, long b)
    {
        String key = bizKey(a, b);
        friends.insertSingleConversation(key);
        Long cid = friends.selectSingleConversation(key);
        if (cid == null) throw new IllegalStateException("direct conversation missing");
        friends.ensureConversationMember(cid, a);
        friends.ensureConversationMember(cid, b);
    }

    public Map<String, Object> receipt(long viewer, long peer, String action, String requestId)
    {
        Map<String, Object> stored = stored(viewer, "FRIEND", uuid(requestId));
        if (stored == null || !action.equals(stored.get("action")) || num(stored.get("peer_id")) != peer)
            throw new ChannelRejection(404, "FRIEND_RECEIPT_NOT_FOUND", "没有找到该请求");
        return stored;
    }

    // ---------------------------------------------------------------- removal

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> remove(long viewer, Map<String, Object> body)
    {
        if (body == null || !Set.of("peer_id", "expected_version", "request_id").equals(body.keySet()))
            throw ChannelRejection.badField("body");
        long peer = longField(body, "peer_id", true);
        long expected = longField(body, "expected_version", true);
        String requestId = uuid(body.get("request_id"));
        requirePeer(viewer, peer);
        String proof = FriendProofs.removal(peer, expected);
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, "FREMOVE", requestId, proof, "remove", peer, () ->
        {
            long low = Math.min(viewer, peer), high = Math.max(viewer, peer);
            Map<String, Object> pair = friends.lockPair(low, high);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("viewer_id", viewer);
            r.put("peer_id", peer);
            r.put("expected_version", expected);
            r.put("request_id", requestId);
            r.put("action", "remove");
            r.put("payload_hash", proof);
            boolean friend = state(viewer, pair) == 1 && friends.countBlock(viewer, peer) == 0;
            if (friend && num(pair.get("version")) == expected)
            {
                cas(pair, 3, num(pair.get("requesterId")), null, 0, null);
                friends.clearPairMeta(viewer, peer);
                r.put("outcome", "APPLIED");
                r.put("reason", "REMOVED");
                r.put("removed_version", expected + 1);
            }
            else
            {
                r.put("outcome", "REJECTED");
                r.put("reason", friend ? "VERSION_CONFLICT" : "NOT_FRIENDS");
                r.put(STATUS_KEY, friend ? 409 : 404);
            }
            r.put("current_relationship", relation(viewer, peer, friends.selectPair(low, high)));
            return r;
        });
        out.remove("replayed");
        return out;
    }

    public Map<String, Object> removalReceipt(long viewer, long peer, long expected, String requestId)
    {
        Map<String, Object> stored = stored(viewer, "FREMOVE", uuid(requestId));
        if (stored == null || num(stored.get("peer_id")) != peer || num(stored.get("expected_version")) != expected)
            throw new ChannelRejection(404, "FRIEND_RECEIPT_NOT_FOUND", "没有找到该请求");
        stored.remove(STATUS_KEY);
        return stored;
    }

    // ---------------------------------------------------------------- metadata

    public Map<String, Object> metadata(long viewer, long peer)
    {
        requirePeer(viewer, peer);
        Map<String, Object> pair = friends.selectPair(Math.min(viewer, peer), Math.max(viewer, peer));
        if (state(viewer, pair) != 1 || friends.countBlock(viewer, peer) > 0)
            throw new ChannelRejection(404, "FRIEND_NOT_FOUND", "对方不是你的好友");
        return metadataView(viewer, peer, num(pair.get("version")), friends.selectMeta(viewer, peer));
    }

    static Map<String, Object> metadataView(long viewer, long peer, long friendVersion, Map<String, Object> meta)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("viewer_id", viewer);
        m.put("peer_id", peer);
        m.put("friend_version", friendVersion);
        m.put("version", meta == null ? 0L : num(meta.get("version")));
        m.put("remark", meta == null ? null : meta.get("remark"));
        m.put("group_id", meta == null || meta.get("groupId") == null ? null : num(meta.get("groupId")));
        m.put("starred", meta == null ? 0 : (int) num(meta.get("starred")));
        // 好友侧未接入通知策略：备注/星标不改变推送（App 显示「不影响通知」）。
        m.put("notification_effect", "NOT_CONNECTED");
        m.put("progress_scope", meta == null ? 1 : (int) num(meta.get("progressScope")));
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> metadataWrite(long viewer, String action, Map<String, Object> body)
    {
        if (body == null) throw ChannelRejection.badField("body");
        Set<String> allowed = switch (action)
        {
            case "remark" -> Set.of("request_id", "expected_version", "peer_id", "expected_friend_version", "remark", "starred",
                    "progress_scope", "group_id");
            case "group_save" -> Set.of("id", "name", "position", "expected_version", "request_id");
            case "group_delete" -> Set.of("id", "move_to_group_id", "expected_version", "request_id");
            default -> throw new ChannelRejection(404, "FRIEND_ACTION_NOT_FOUND", "不支持的操作");
        };
        if (!allowed.containsAll(body.keySet())) throw ChannelRejection.badField("unknown field");
        String requestId = uuid(body.get("request_id"));
        long expected = longField(body, "expected_version", false);
        long target = switch (action)
        {
            case "remark" -> longField(body, "peer_id", true);
            case "group_save" -> body.get("id") == null ? 0L : longField(body, "id", true);
            default -> longField(body, "id", true);
        };
        String proof;
        try { proof = FriendProofs.metadata(body); }
        catch (IllegalArgumentException e) { throw ChannelRejection.badField("value type"); }
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, "FMETA", requestId, proof, action, target, () ->
        {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("viewer_id", viewer);
            r.put("request_id", requestId);
            r.put("action", action);
            r.put("target_id", target);
            r.put("payload_hash", proof);
            String refusal;
            try
            {
                if ("remark".equals(action)) r.put("current", applyRemark(viewer, target, expected, body));
                else r.put("groups", "group_save".equals(action) ? saveGroup(viewer, target, expected, body)
                        : deleteGroup(viewer, target, expected, body));
                r.put("outcome", "APPLIED");
                return r;
            }
            catch (MetadataRefusal e) { refusal = e.reason; r.put(STATUS_KEY, e.status); }
            r.put("outcome", "REJECTED");
            r.put("reason", refusal);
            return r;
        });
        out.remove("replayed");
        return out;
    }

    static final class MetadataRefusal extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        final int status;
        final String reason;
        MetadataRefusal(int status, String reason) { super(reason, null, false, false); this.status = status; this.reason = reason; }
    }

    private Map<String, Object> applyRemark(long viewer, long peer, long expected, Map<String, Object> body)
    {
        if (peer == viewer) throw new MetadataRefusal(400, "INVALID_PEER");
        long expectedFriend = longField(body, "expected_friend_version", true);
        Map<String, Object> pair = friends.lockPair(Math.min(viewer, peer), Math.max(viewer, peer));
        if (state(viewer, pair) != 1 || friends.countBlock(viewer, peer) > 0) throw new MetadataRefusal(404, "NOT_FRIENDS");
        if (num(pair.get("version")) != expectedFriend) throw new MetadataRefusal(409, "FRIEND_VERSION_CONFLICT");
        Map<String, Object> meta = friends.lockMeta(viewer, peer);
        if ((meta == null ? 0L : num(meta.get("version"))) != expected) throw new MetadataRefusal(409, "VERSION_CONFLICT");
        String remark = meta == null ? null : (String) meta.get("remark");
        Long group = meta == null || meta.get("groupId") == null ? null : num(meta.get("groupId"));
        int starred = meta == null ? 0 : (int) num(meta.get("starred"));
        int scope = meta == null ? 1 : (int) num(meta.get("progressScope"));
        if (body.containsKey("remark"))
        {
            Object raw = body.get("remark");
            if (raw != null && !(raw instanceof String)) throw new MetadataRefusal(400, "INVALID_REMARK");
            remark = raw == null ? null : Normalizer.normalize(((String) raw).strip(), Normalizer.Form.NFC);
            if (remark != null && (remark.isEmpty() || remark.codePointCount(0, remark.length()) > 30
                    || remark.chars().anyMatch(Character::isISOControl))) throw new MetadataRefusal(400, "INVALID_REMARK");
        }
        if (body.containsKey("starred"))
        {
            Object raw = body.get("starred");
            if (raw != null && !(raw instanceof Number n && (n.longValue() == 0 || n.longValue() == 1))) throw new MetadataRefusal(400, "INVALID_STARRED");
            starred = raw == null ? 0 : ((Number) raw).intValue();
        }
        if (body.containsKey("progress_scope"))
        {
            Object raw = body.get("progress_scope");
            if (raw != null && !(raw instanceof Number n && (n.longValue() == 0 || n.longValue() == 1))) throw new MetadataRefusal(400, "INVALID_PROGRESS_SCOPE");
            scope = raw == null ? 1 : ((Number) raw).intValue();
        }
        if (body.containsKey("group_id"))
        {
            Object raw = body.get("group_id");
            if (raw == null) group = null;
            else if (raw instanceof Number n && n.longValue() > 0 && friends.lockGroup(viewer, n.longValue()) != null) group = n.longValue();
            else throw new MetadataRefusal(404, "GROUP_NOT_FOUND");
        }
        friends.upsertMeta(viewer, peer, remark, group, starred, scope);
        return metadataView(viewer, peer, num(pair.get("version")), friends.selectMeta(viewer, peer));
    }

    private Map<String, Object> saveGroup(long viewer, long id, long expected, Map<String, Object> body)
    {
        if (!(body.get("name") instanceof String rawName) || !(body.get("position") instanceof Number pos))
            throw new MetadataRefusal(400, "INVALID_GROUP");
        String name = Normalizer.normalize(rawName.strip(), Normalizer.Form.NFC);
        long position = pos.longValue();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 12 || name.chars().anyMatch(Character::isISOControl)
                || position < 0 || position > Integer.MAX_VALUE) throw new MetadataRefusal(400, "INVALID_GROUP");
        friends.lockGroupOwner(viewer);
        if (friends.countGroupName(viewer, name, id) > 0) throw new MetadataRefusal(409, "GROUP_NAME_TAKEN");
        if (id == 0)
        {
            if (expected != 0) throw new MetadataRefusal(409, "VERSION_CONFLICT");
            if (friends.countGroups(viewer) >= 19) throw new MetadataRefusal(409, "GROUP_LIMIT_REACHED");
            friends.insertGroup(viewer, name, (int) position);
        }
        else
        {
            Map<String, Object> g = friends.lockGroup(viewer, id);
            if (g == null) throw new MetadataRefusal(404, "GROUP_NOT_FOUND");
            if (num(g.get("version")) != expected || friends.updateGroup(viewer, id, expected, name, (int) position) != 1)
                throw new MetadataRefusal(409, "VERSION_CONFLICT");
        }
        return groups(viewer);
    }

    private Map<String, Object> deleteGroup(long viewer, long id, long expected, Map<String, Object> body)
    {
        Object rawMove = body.get("move_to_group_id");
        Long move = rawMove == null ? null : rawMove instanceof Number n ? n.longValue() : -1L;
        friends.lockGroupOwner(viewer);
        Map<String, Object> g = friends.lockGroup(viewer, id);
        if (g == null) throw new MetadataRefusal(404, "GROUP_NOT_FOUND");
        if (num(g.get("version")) != expected) throw new MetadataRefusal(409, "VERSION_CONFLICT");
        if (move != null && (move == id || move <= 0 || friends.lockGroup(viewer, move) == null)) throw new MetadataRefusal(404, "GROUP_NOT_FOUND");
        friends.moveGroupMembers(viewer, id, move);
        if (friends.deleteGroup(viewer, id, expected) != 1) throw new MetadataRefusal(409, "VERSION_CONFLICT");
        return groups(viewer);
    }

    public Map<String, Object> groups(long viewer)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("id", null);
        def.put("name", "未分组");
        def.put("position", 0);
        def.put("version", 0L);
        def.put("is_default", 1);
        def.put("friend_count", friends.countDirectory(viewer, "all", 0L, false));
        list.add(def);
        for (Map<String, Object> g : friends.listGroups(viewer))
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", num(g.get("id")));
            row.put("name", g.get("name"));
            row.put("position", (int) num(g.get("position")));
            row.put("version", num(g.get("version")));
            row.put("is_default", 0);
            row.put("friend_count", (int) num(g.get("friendCount")));
            list.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("list", list);
        return out;
    }

    public Map<String, Object> metadataReceipt(long viewer, String action, long target, String requestId, String payloadHash)
    {
        Map<String, Object> stored = stored(viewer, "FMETA", uuid(requestId));
        if (stored == null || !action.equals(stored.get("action")) || num(stored.get("target_id")) != target
                || (payloadHash != null && !payloadHash.equals(stored.get("payload_hash"))))
            throw new ChannelRejection(404, "FRIEND_RECEIPT_NOT_FOUND", "没有找到该请求");
        stored.remove(STATUS_KEY);
        return stored;
    }

    // ---------------------------------------------------------------- directory

    public Map<String, Object> directory(long viewer, String section, boolean starred, Long groupId, String cursor, int limit)
    {
        if (!SECTIONS.contains(section) || limit != PAGE || (groupId != null && groupId < 0)) throw ChannelRejection.badField("section");
        long after = 0;
        if (cursor != null)
        {
            if (!cursor.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("cursor");
            after = Long.parseLong(cursor);
        }
        Map<String, Object> groups = groups(viewer);
        if (groupId != null && groupId > 0 && ((List<?>) groups.get("list")).stream()
                .noneMatch(g -> groupId.equals(((Map<?, ?>) g).get("id")))) throw new ChannelRejection(404, "GROUP_NOT_FOUND", "分组不存在");
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("all", "recent", "together"))
            counts.put(s, Math.min(500, friends.countDirectory(viewer, s, groupId, starred)));
        int total = (int) counts.get(section);
        List<Map<String, Object>> rows = friends.listDirectory(viewer, section, groupId, starred, after, PAGE + 1);
        boolean more = rows.size() > PAGE;
        if (more) rows = rows.subList(0, PAGE);
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder rev = new StringBuilder(viewer + "|" + section + "|" + groupId + "|" + starred + "|" + counts);
        for (Map<String, Object> row : rows)
        {
            items.add(friendItem(row));
            rev.append('|').append(row.get("peerId")).append(':').append(row.get("version")).append(':')
               .append(row.get("groupId")).append(':').append(row.get("starred")).append(':').append(row.get("remark"));
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("viewer_id", viewer);
        page.put("section", section);
        page.put("group_id", groupId);
        page.put("starred_only", starred);
        page.put("revision", FriendProofs.sha256(rev.toString()));
        page.put("groups", groups);
        page.put("counts", counts);
        page.put("total", total);
        page.put("items", items);
        page.put("has_more", more);
        page.put("next_cursor", more ? String.valueOf(num(rows.get(rows.size() - 1).get("pairId"))) : null);
        return page;
    }

    public Map<String, Object> directoryDetail(long viewer, long peer)
    {
        requirePeer(viewer, peer);
        Map<String, Object> pair = friends.selectPair(Math.min(viewer, peer), Math.max(viewer, peer));
        if (state(viewer, pair) != 1 || friends.countBlock(viewer, peer) > 0)
            throw new ChannelRejection(404, "FRIEND_NOT_FOUND", "对方不是你的好友");
        Map<String, Object> meta = friends.selectMeta(viewer, peer);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("peerId", peer);
        row.put("version", pair.get("version"));
        row.put("nickname", friends.nickname(peer));
        if (meta != null) { row.put("remark", meta.get("remark")); row.put("groupId", meta.get("groupId")); row.put("starred", meta.get("starred")); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("item", friendItem(row));
        Long cid = friends.selectSingleConversation(bizKey(viewer, peer));
        if (cid != null) out.put("conversation_id", cid);
        return out;
    }

    // ---------------------------------------------------------------- friend progress

    public Map<String, Object> progress(long viewer, long peer, long expectedFriendVersion)
    {
        requirePeer(viewer, peer);
        Map<String, Object> pair = friends.selectPair(Math.min(viewer, peer), Math.max(viewer, peer));
        if (state(viewer, pair) != 1 || friends.countBlock(viewer, peer) > 0)
            throw new ChannelRejection(404, "FRIEND_NOT_FOUND", "对方不是你的好友");
        if (num(pair.get("version")) != expectedFriendVersion) throw new ChannelRejection(409, "FRIEND_VERSION_CONFLICT", "好友关系已变化");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("peer_id", peer);
        out.put("friend_version", expectedFriendVersion);
        Map<String, Object> peerMeta = friends.selectMeta(peer, viewer);
        boolean visible = (peerMeta == null || num(peerMeta.get("progressScope")) != 0)
                && (progressGate == null || progressGate.friendAggregateVisible(peer, viewer));
        out.put("visible", visible);
        if (!visible) return out;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : friends.listPeerProgress(peer))
        {
            long required = num(row.get("required"));
            if (required <= 0 || row.get("title") == null || str(row.get("title")).isEmpty()) continue;
            long topic = num(row.get("topicId")), activity = num(row.get("activityId"));
            long done = Math.min(num(row.get("done")), required);
            String asOf = String.valueOf(ChannelRejection.toDate(row.get("asOf")) == null ? "" : ChannelRejection.toDate(row.get("asOf")).toInstant());
            Map<String, Object> meter = new LinkedHashMap<>();
            meter.put("visible", true);
            meter.put("subjectId", peer);
            meter.put("topicId", topic);
            meter.put("activityId", activity);
            meter.put("required", required);
            meter.put("done", done);
            meter.put("percent", (int) (done * 100 / required));
            meter.put("completed", done >= required);
            meter.put("asOf", asOf.isEmpty() ? "1970-01-01T00:00:00Z" : asOf);
            meter.put("revision", FriendProofs.sha256(peer + "|" + topic + "|" + activity + "|" + done + "|" + required + "|" + asOf));
            meter.put("visibility", Map.of("aggregate", "FRIEND", "detail", "SELF"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "t" + topic + "-a" + activity);
            item.put("title", row.get("title"));
            item.put("topicID", topic);
            item.put("activityID", activity);
            item.put("meter", meter);
            items.add(item);
        }
        out.put("items", items);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    Map<String, Object> stored(long viewer, String family, String requestId)
    {
        Map<String, Object> row = receipts.selectReceipt(viewer, family, requestId);
        if (row == null || row.get("response") == null) return null;
        return ChannelReceipts.decode(row.get("response").toString());
    }

    void requirePeer(long viewer, long peer)
    {
        if (peer <= 0 || peer == viewer) throw ChannelRejection.badField("peer_id");
        if (friends.nickname(peer) == null) throw new ChannelRejection(404, "FRIEND_PEER_NOT_FOUND", "用户不存在");
    }

    static String bizKey(long a, long b) { return Math.min(a, b) + "_" + Math.max(a, b); }

    static String uuid(Object raw)
    {
        if (!(raw instanceof String s) || !UUID_RE.matcher(s).matches()) throw ChannelRejection.badField("request_id");
        return s;
    }

    static long longField(Map<String, Object> body, String key, boolean positive)
    {
        Object v = body.get(key);
        if (!(v instanceof Integer || v instanceof Long)) throw ChannelRejection.badField(key);
        long n = ((Number) v).longValue();
        if (positive ? n <= 0 : n < 0) throw ChannelRejection.badField(key);
        return n;
    }

    static String nick(Map<String, Object> row, long peer)
    {
        Object n = row.get("nickname");
        return n == null || n.toString().isEmpty() ? "用户" + peer : n.toString();
    }

    static long epochMs(Object value)
    {
        java.util.Date d = ChannelRejection.toDate(value);
        return d == null ? 0L : d.getTime();
    }

    static String str(Object v) { return v == null ? "" : v.toString(); }

    static long num(Object value)
    {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Boolean b) return b ? 1L : 0L;
        return Long.parseLong(value.toString());
    }
}
