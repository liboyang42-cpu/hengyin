package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.PlayerGapsMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-side reads and one idempotent write the app calls on existing tables: the caller's own
 * follow lists, the original receipt of the caller's route action, and collecting a reviewed route egg.
 * Every call acts as the logged-in member; ids from the client never select another member.
 */
@Service
public class PlayerGapsService {
    private final PlayerGapsMapper mapper;

    public PlayerGapsService(PlayerGapsMapper mapper) { this.mapper = mapper; }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
    }

    /** GET /api/user/follow-list: keyset page of the caller's following or followers. */
    public Map<String, Object> followList(Long owner, String kind, String cursor, String limit) {
        requireMember(owner);
        if (!"following".equals(kind) && !"followers".equals(kind)) throw new ServiceException("kind 无效", 400);
        if (cursor == null || !cursor.matches("0|[1-9][0-9]{0,17}")) throw new ServiceException("cursor 无效", 400);
        if (limit == null || !limit.matches("[1-9][0-9]?")) throw new ServiceException("limit 无效", 400);
        int size = Integer.parseInt(limit);
        if (size > 50) throw new ServiceException("limit 无效", 400);
        long after = Long.parseLong(cursor);
        List<Map<String, Object>> rows = "following".equals(kind)
                ? mapper.selectFollowing(owner, after, size + 1) : mapper.selectFollowers(owner, after, size + 1);
        boolean more = rows.size() > size;
        List<Map<String, Object>> page = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(size, rows.size()))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", ((Number) row.get("id")).longValue());
            out.put("nickname", row.get("nickname"));
            out.put("avatar", row.get("avatar"));
            out.put("isFollow", ((Number) row.get("isFollow")).intValue() == 1);
            page.add(out);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner_id", owner);
        out.put("kind", kind);
        out.put("rows", page);
        out.put("has_more", more);
        out.put("next_cursor", more ? page.get(page.size() - 1).get("id") : null);
        return out;
    }

    /** GET /api/play/route-action: the caller's own stored route-action receipt. Never inserts. */
    public Map<String, Object> routeAction(Long member, Long sessionId, Long memberId, String actionId) {
        requireMember(member);
        if (sessionId == null || sessionId <= 0 || memberId == null || actionId == null
                || !actionId.matches("[A-Za-z0-9_-]{1,96}")) throw new ServiceException("参数有误", 400);
        if (!member.equals(memberId)) throw new ServiceException("只能读取本人的路线动作", 403);
        Map<String, Object> row = mapper.selectRouteAction(sessionId, memberId, actionId);
        if (row == null) throw new ServiceException("没有这条路线动作", 404);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", ((Number) row.get("sessionId")).longValue());
        out.put("memberId", ((Number) row.get("memberId")).longValue());
        out.put("actionId", row.get("actionId"));
        out.put("responseJson", row.get("responseJson"));
        return out;
    }

    /**
     * POST /api/play/egg/collect: stores a reviewed egg in the caller's collection. The stored text is
     * the reviewed server text, never the client's; repeating the call is a no-op success.
     */
    public void collectEgg(Long member, Long eggId, Long topicId) {
        requireMember(member);
        if (eggId == null || eggId <= 0 || (topicId != null && topicId < 0)) throw new ServiceException("参数有误", 400);
        Map<String, Object> egg = mapper.selectApprovedEgg(eggId);
        if (egg == null) throw new ServiceException("彩蛋不存在或未审核", 404);
        Long eggTopic = egg.get("topicId") == null ? null : ((Number) egg.get("topicId")).longValue();
        if (topicId != null && topicId > 0 && !topicId.equals(eggTopic)) throw new ServiceException("彩蛋不属于该主题", 400);
        mapper.insertEggCollectible(member, eggTopic, eggId, (String) egg.get("text"));
    }
}
