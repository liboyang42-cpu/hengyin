package com.chengyinhub.business.service.friend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.friend.NativeBlacklistMapper;
import com.chengyinhub.business.service.channel.platform.ChannelReceipts;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;

/**
 * 黑名单合同（App Core/NativeBlacklist*.swift，docs/channels/contract.md §8.1）：列表、当前状态、取消拉黑、原回执与结算。
 * <ul>
 * <li>真源是既有 im_block，与 /api/im/block、/api/im/unblock 和好友的拉黑语义同一张表；拉黑仍走 /api/im/block。</li>
 * <li>取消拉黑只删 expected_block_id 这一行（行锁内比对），不恢复被拉黑时移除的好友或请求。</li>
 * <li>每次取消以 request_id 落原回执（族 BLOCK），payload_hash 与 App 指纹 native-blacklist-v1 逐字节一致。</li>
 * <li>结算（settle）对未落地的 request_id 写入终局 REJECTED/FINALIZED_WITHOUT_CHANGE，此后同一 request_id 永不生效。</li>
 * </ul>
 * 拒绝也是 code 200 + 回执（App 只认 code 200 的回执），参数错误才是 4xx。
 */
@Service
public class NativeBlacklistService
{
    static final String FAMILY = "BLOCK";
    static final int PAGE = 50;
    static final Set<String> COMMAND_KEYS = Set.of("peer_id", "expected_block_id", "request_id");

    @Autowired
    private NativeBlacklistMapper blocks;

    @Autowired
    private ChannelPlatformMapper receipts;

    public Map<String, Object> page(long viewer, long cursor)
    {
        if (cursor < 0) throw ChannelRejection.badField("cursor");
        List<Map<String, Object>> rows = blocks.selectPage(viewer, cursor, PAGE + 1);
        boolean more = rows.size() > PAGE;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : more ? rows.subList(0, PAGE) : rows)
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("block_id", FriendService.num(row.get("block_id")));
            item.put("peer_id", FriendService.num(row.get("peer_id")));
            item.put("nickname", FriendService.str(row.get("nickname")));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("items", items);
        out.put("has_more", more);
        out.put("next_cursor", more ? items.get(items.size() - 1).get("block_id") : null);
        out.put("write_enabled", true);
        return out;
    }

    public Map<String, Object> current(long viewer, long peer)
    {
        requirePeer(viewer, peer);
        Long id = blocks.selectBlockId(viewer, peer);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", viewer);
        out.put("peer_id", peer);
        out.put("blocked", id != null);
        out.put("block_id", id);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unblock(long viewer, Map<String, Object> body)
    {
        Command c = command(viewer, body);
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, FAMILY, c.requestId, c.proof(), "unblock", c.peer, () ->
        {
            Long id = blocks.lockBlockId(viewer, c.peer);
            if (id == null) return receipt(viewer, c, "REJECTED", "BLOCK_ABSENT");
            if (id != c.expected) return receipt(viewer, c, "REJECTED", "BLOCK_CHANGED");
            if (blocks.deleteBlock(id, viewer, c.peer) != 1) throw new IllegalStateException("Block row changed under lock");
            return receipt(viewer, c, "APPLIED", "UNBLOCKED");
        });
        out.remove("replayed");
        return out;
    }

    /** 同一 request_id 已有回执则原样返回；没有则落终局「未改变」回执，封住这个原请求。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settle(long viewer, Map<String, Object> body)
    {
        Command c = command(viewer, body);
        Map<String, Object> out = ChannelReceipts.once(receipts, viewer, FAMILY, c.requestId, c.proof(), "unblock", c.peer,
                () -> receipt(viewer, c, "REJECTED", "FINALIZED_WITHOUT_CHANGE"));
        out.remove("replayed");
        return out;
    }

    /** 只读：已落地回执原样返回；没有则 outcome UNKNOWN（不带 reason），绝不在读里写。 */
    public Map<String, Object> receipt(long viewer, long peer, long expected, String requestId)
    {
        Command c = new Command(peer, expected, FriendService.uuid(requestId));
        requirePeer(viewer, peer);
        if (expected <= 0) throw ChannelRejection.badField("expected_block_id");
        Map<String, Object> row = receipts.selectReceipt(viewer, FAMILY, c.requestId);
        if (row != null && row.get("response") != null)
        {
            if (!c.proof().equals(String.valueOf(row.get("fingerprint"))))
                throw new ChannelRejection(409, "REQUEST_ID_CONFLICT", "该请求标识已用于其他内容");
            return ChannelReceipts.decode(row.get("response").toString());
        }
        return receipt(viewer, c, "UNKNOWN", null);
    }

    // ---------------------------------------------------------------- helpers

    static String proof(long peer, long expected)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("native-blacklist-v1\n" + peer + "\n" + expected).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, Object> receipt(long viewer, Command c, String outcome, String reason)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("viewer_id", viewer);
        r.put("peer_id", c.peer);
        r.put("expected_block_id", c.expected);
        r.put("request_id", c.requestId);
        r.put("action", "UNBLOCK");
        r.put("payload_hash", c.proof());
        r.put("outcome", outcome);
        if (reason != null) r.put("reason", reason);
        return r;
    }

    private static Command command(long viewer, Map<String, Object> body)
    {
        if (body == null || !COMMAND_KEYS.equals(body.keySet())) throw ChannelRejection.badField("body");
        long peer = FriendService.longField(body, "peer_id", true);
        long expected = FriendService.longField(body, "expected_block_id", true);
        String requestId = FriendService.uuid(body.get("request_id"));
        requirePeer(viewer, peer);
        return new Command(peer, expected, requestId);
    }

    private static void requirePeer(long viewer, long peer)
    {
        if (peer <= 0 || peer == viewer) throw ChannelRejection.badField("peer_id");
    }

    record Command(long peer, long expected, String requestId)
    {
        Command
        {
            requestId = requestId.toLowerCase(java.util.Locale.ROOT);
        }

        String proof() { return NativeBlacklistService.proof(peer, expected); }
    }
}
