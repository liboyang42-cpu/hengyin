package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelProgressMapper;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;
import static com.chengyinhub.business.service.channel.platform.ChannelGuildAccess.toLong;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;

/**
 * 频道进度榜（App docs/channels/contract.md §3）。只读投影：成员 = 该空间在册成员，口径 = 完成的玩家可见节点数。
 * 绑定由具 MANAGE_PROGRESS 的管理者写入；没有绑定时 definitions 返回空列表、overview 返回 visible=false。
 */
@Service
public class ChannelProgressService
{
    static final String METRIC = "COMPLETED_NODES";
    static final String PERIOD = "ALL";

    @Autowired
    private ChannelProgressMapper progress;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired
    private ChannelPlatformService platform;

    public ChannelProgressService() { }

    ChannelProgressService(ChannelProgressMapper progress, ChannelPlatformMapper channels, ChannelGuildAccess access,
            ChannelPlatformService platform)
    {
        this.progress = progress;
        this.channels = channels;
        this.access = access;
        this.platform = platform;
    }

    public Map<String, Object> definitions(long viewer, long channelId)
    {
        platform.visibleChannel(viewer, channelId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> b : progress.selectBoards(channelId)) list.add(boardView(b));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visible", true);
        data.put("list", list);
        return data;
    }

    public Map<String, Object> overview(long viewer, long channelId)
    {
        ChannelRow row = platform.visibleChannel(viewer, channelId);
        List<Map<String, Object>> boards = progress.selectBoards(channelId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (boards.isEmpty())
        {
            data.put("visible", false);
            return data;
        }
        Map<String, Object> board = boards.get(0);
        long topicId = toLong(board.get("topicId")), activityId = toLong(board.get("activityId"));
        List<Long> memberIds = memberIds(row);
        int required = progress.countListedNodes(topicId);
        Map<Long, Long> done = doneByMember(topicId, activityId, memberIds);
        int completed = 0;
        long percentSum = 0;
        for (Long m : memberIds)
        {
            long d = Math.min(done.getOrDefault(m, 0L), required);
            if (required > 0 && d >= required) completed++;
            percentSum += percent(d, required);
        }
        int total = Math.min(memberIds.size(), 50);
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("total", total);
        members.put("total_capped", memberIds.size() > 50);
        members.put("completed", Math.min(completed, total));
        members.put("avg_percent", memberIds.isEmpty() ? null : (int) (percentSum / memberIds.size()));
        data.put("visible", true);
        data.put("channel_id", channelId);
        data.put("topic_id", topicId);
        data.put("activity_id", activityId);
        data.put("revision", revision(board));
        data.put("as_of", ChannelPlatformService.format(new Date()));
        data.put("members", members);
        data.put("teams", List.of());
        return data;
    }

    public Map<String, Object> page(long viewer, long channelId, String boardKey, String period, int limit, boolean detail,
            Boolean completedFilter, String cursor)
    {
        ChannelRow row = platform.visibleChannel(viewer, channelId);
        Map<String, Object> board = board(channelId, boardKey);
        requirePeriod(period);
        int size = Math.max(1, Math.min(limit, (int) toLong(board.get("rowLimit"))));
        List<Map<String, Object>> rows = ranked(row, board);
        if (detail && completedFilter != null) rows.removeIf(r -> !completedFilter.equals(r.get("completed")));
        int offset = 0;
        if (cursor != null)
        {
            if (!detail || !cursor.matches("[1-9][0-9]{0,5}")) throw ChannelRejection.badField("cursor 不合法");
            offset = Integer.parseInt(cursor);
        }
        if (!detail) rows.removeIf(r -> r.get("state") != null);
        List<Map<String, Object>> list = rows.subList(Math.min(offset, rows.size()), Math.min(offset + size, rows.size()));
        boolean more = detail && offset + size < rows.size();
        Map<String, Object> me = null;
        for (Map<String, Object> r : ranked(row, board)) if (toLong(r.get("subject_id")) == viewer) me = r;
        Map<String, Object> data = pageHeader(board);
        data.put("list", new ArrayList<>(list));
        data.put("me", me);
        data.put("next_cursor", more ? String.valueOf(offset + size) : null);
        data.put("has_more", more);
        return data;
    }

    public Map<String, Object> best(long viewer, long channelId, String boardKey, String period)
    {
        ChannelRow row = platform.visibleChannel(viewer, channelId);
        Map<String, Object> board = board(channelId, boardKey);
        requirePeriod(period);
        for (Map<String, Object> r : ranked(row, board)) if (toLong(r.get("subject_id")) == viewer) return r;
        throw new ChannelRejection(404, "PROGRESS_SUBJECT_NOT_FOUND", "没有本人的进度");
    }

    public Map<String, Object> recent(long viewer, long channelId, String boardKey, String period, int limit)
    {
        Map<String, Object> me = best(viewer, channelId, boardKey, period);
        Map<String, Object> data = pageHeader(board(channelId, boardKey));
        data.put("list", List.of(me));
        data.put("me", me);
        data.put("next_cursor", null);
        data.put("has_more", false);
        return data;
    }

    public Map<String, Object> version(long viewer, long channelId, String boardKey)
    {
        platform.visibleChannel(viewer, channelId);
        Map<String, Object> board = board(channelId, boardKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visible", true);
        data.put("revision", revision(board));
        data.put("as_of", ChannelPlatformService.format(new Date()));
        return data;
    }

    public Map<String, Object> meter(long viewer, long channelId, long topicId, long activityId, String subjectType, long subjectId)
    {
        ChannelRow row = platform.visibleChannel(viewer, channelId);
        if (!"MEMBER".equals(subjectType)) throw ChannelRejection.badField("subject_type 不支持");
        boolean bound = false;
        for (Map<String, Object> b : progress.selectBoards(channelId))
            bound |= toLong(b.get("topicId")) == topicId && toLong(b.get("activityId")) == activityId;
        Map<String, Object> data = new LinkedHashMap<>();
        boolean self = subjectId == viewer;
        if (!bound || (!self && !has(access.guildBits(row.getGuildKind(), row.getGuildId(), viewer), MANAGE_PROGRESS))
                || !memberIds(row).contains(subjectId))
        {
            data.put("visible", false);
            return data;
        }
        int required = progress.countListedNodes(topicId);
        List<Long> nodes = progress.selectMemberNodes(topicId, activityId, subjectId);
        if (required <= 0)
        {
            data.put("visible", false);
            return data;
        }
        int done = Math.min(nodes.size(), required);
        data.put("visible", true);
        data.put("subject_id", subjectId);
        data.put("topic_id", topicId);
        data.put("activity_id", activityId);
        data.put("required", required);
        data.put("done", done);
        data.put("percent", percent(done, required));
        data.put("completed", done >= required);
        data.put("as_of", ChannelPlatformService.format(new Date()));
        data.put("revision", String.valueOf(progressMark(topicId, activityId)));
        data.put("completed_node_ids", nodes);
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bind(long viewer, long channelId, Map<String, Object> body)
    {
        if (body == null) throw ChannelRejection.badField("请求体为空");
        for (String key : body.keySet())
            if (!Set.of("board_key", "title", "topic_id", "activity_id", "row_limit", "request_id").contains(key))
                throw ChannelRejection.badField("未知字段 " + key);
        Object rid = body.get("request_id");
        if (!(rid instanceof String requestId) || !requestId.matches("[A-Za-z0-9_.:-]{8,64}")) throw ChannelRejection.badField("request_id 不合法");
        Object key = body.get("board_key"), title = body.get("title");
        if (!(key instanceof String boardKey) || !boardKey.matches("[a-z][a-z0-9_]{0,31}")) throw ChannelRejection.badField("board_key 不合法");
        if (!(title instanceof String name) || name.isBlank() || name.length() > 64) throw ChannelRejection.badField("title 不合法");
        long topicId = number(body.get("topic_id"), 1), activityId = number(body.get("activity_id"), 0);
        long rowLimit = body.get("row_limit") == null ? 20 : number(body.get("row_limit"), 1);
        if (rowLimit > 50) throw ChannelRejection.badField("row_limit 超出范围");
        String fp = ChannelReceipts.fingerprint("PROGRESS_BIND", channelId, body);
        return ChannelReceipts.once(channels, viewer, ChannelReceipts.META, requestId, fp, "PROGRESS_BIND", channelId, () -> {
            ChannelRow row = platform.visibleChannel(viewer, channelId);
            if (!has(access.guildBits(row.getGuildKind(), row.getGuildId(), viewer), MANAGE_PROGRESS)) throw ChannelRejection.forbidden();
            if (progress.countTopic(topicId) == 0) throw new ChannelRejection(404, "TOPIC_NOT_FOUND", "主题不存在");
            if (progress.selectBoards(channelId).size() >= 10) throw new ChannelRejection(409, "BOARD_LIMIT_REACHED", "最多 10 个榜");
            Map<String, Object> insert = new HashMap<>();
            insert.put("channelId", channelId);
            insert.put("boardKey", boardKey);
            insert.put("title", name);
            insert.put("topicId", topicId);
            insert.put("activityId", activityId);
            insert.put("rowLimit", rowLimit);
            insert.put("createBy", viewer);
            try { progress.insertBoard(insert); }
            catch (RuntimeException dup)
            {
                if (!ChannelRejection.isDuplicateKey(dup)) throw dup;
                throw new ChannelRejection(409, "BOARD_KEY_TAKEN", "榜单键已存在");
            }
            Map<String, Object> log = new HashMap<>();
            log.put("channelId", channelId);
            log.put("action", "PROGRESS_BIND");
            log.put("fromStatus", row.getStatus());
            log.put("toStatus", row.getStatus());
            log.put("reason", "bind " + boardKey);
            log.put("operatorId", viewer);
            log.put("requestId", requestId);
            channels.insertOpLog(log);
            return boardView(progress.selectBoard(channelId, boardKey));
        });
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> ranked(ChannelRow row, Map<String, Object> board)
    {
        long topicId = toLong(board.get("topicId")), activityId = toLong(board.get("activityId"));
        List<Long> memberIds = memberIds(row);
        int required = progress.countListedNodes(topicId);
        Map<Long, Map<String, Object>> facts = new HashMap<>();
        if (!memberIds.isEmpty())
            for (Map<String, Object> f : progress.selectMemberProgress(topicId, activityId, memberIds))
                facts.put(toLong(f.get("memberId")), f);
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> m : channels.selectGuildMembers(row.getGuildKind(), row.getGuildId(), 0, 500))
            names.put(toLong(m.get("memberId")), m.get("nickname") == null ? null : m.get("nickname").toString());
        String revision = revision(board);
        String asOf = ChannelPlatformService.format(new Date());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long m : memberIds)
        {
            Map<String, Object> f = facts.get(m);
            long done = f == null ? 0 : Math.min(toLong(f.get("done")), required);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("subject_type", "MEMBER");
            r.put("subject_id", m);
            r.put("topic_id", topicId);
            r.put("activity_id", activityId);
            r.put("fact_source", "PLAYER_NODE_PROGRESS");
            r.put("unit", "NODE");
            r.put("required", required);
            r.put("done", done);
            r.put("completed", required > 0 && done >= required);
            r.put("percent", percent(done, required));
            r.put("revision", revision);
            r.put("as_of", asOf);
            r.put("display_name", names.getOrDefault(m, String.valueOf(m)));
            r.put("metric", METRIC);
            r.put("metric_value", done);
            r.put("visible", true);
            r.put("_last", f == null ? null : ChannelRejection.toDate(f.get("lastTime")));
            if (done == 0)
            {
                r.put("state", required == 0 ? "NOT_APPLICABLE" : "NO_COMPLETED_EFFORT");
                r.put("rank", null);
                r.put("rank_known", false);
            }
            rows.add(r);
        }
        rows.sort(Comparator.<Map<String, Object>>comparingLong(r -> -toLong(r.get("done")))
                .thenComparing(r -> r.get("_last") == null ? Long.MAX_VALUE : ((Date) r.get("_last")).getTime())
                .thenComparingLong(r -> toLong(r.get("subject_id"))));
        int rank = 0, position = 0;
        Map<String, Object> previous = null;
        for (Map<String, Object> r : rows)
        {
            Object last = r.remove("_last");
            if (r.get("state") != null) continue;
            position++;
            if (previous == null || toLong(previous.get("done")) != toLong(r.get("done"))) rank = position;
            r.put("rank", rank);
            r.put("rank_known", true);
            previous = r;
            if (last == null) continue;
        }
        return rows;
    }

    private List<Long> memberIds(ChannelRow row)
    {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> m : channels.selectGuildMembers(row.getGuildKind(), row.getGuildId(), 0, 500))
            ids.add(toLong(m.get("memberId")));
        return ids;
    }

    private Map<Long, Long> doneByMember(long topicId, long activityId, List<Long> memberIds)
    {
        Map<Long, Long> done = new HashMap<>();
        if (memberIds.isEmpty()) return done;
        for (Map<String, Object> f : progress.selectMemberProgress(topicId, activityId, memberIds))
            done.put(toLong(f.get("memberId")), toLong(f.get("done")));
        return done;
    }

    private Map<String, Object> board(long channelId, String boardKey)
    {
        if (boardKey == null || !boardKey.matches("[a-z][a-z0-9_]{0,31}")) throw ChannelRejection.badField("board_key 不合法");
        Map<String, Object> board = progress.selectBoard(channelId, boardKey);
        if (board == null) throw new ChannelRejection(404, "BOARD_NOT_FOUND", "榜单不存在");
        return board;
    }

    private Map<String, Object> pageHeader(Map<String, Object> board)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visible", true);
        data.put("revision", revision(board));
        data.put("as_of", ChannelPlatformService.format(new Date()));
        data.put("board_key", board.get("boardKey"));
        data.put("period_key", PERIOD);
        return data;
    }

    private Map<String, Object> boardView(Map<String, Object> b)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("board_key", b.get("boardKey"));
        v.put("title", b.get("title"));
        v.put("metric", METRIC);
        v.put("period", PERIOD);
        v.put("scope", "CHANNEL_MEMBERS");
        v.put("subject_type", "MEMBER");
        v.put("team_rollup", null);
        v.put("row_limit", (int) toLong(b.get("rowLimit")));
        v.put("visible", true);
        v.put("revision", toLong(b.get("revision")));
        v.put("topic_id", toLong(b.get("topicId")));
        v.put("activity_id", toLong(b.get("activityId")));
        v.put("filter_schema_json", Map.of());
        return v;
    }

    private String revision(Map<String, Object> board)
    {
        return toLong(board.get("revision")) + ":" + progressMark(toLong(board.get("topicId")), toLong(board.get("activityId")));
    }

    private long progressMark(long topicId, long activityId)
    {
        Long max = progress.maxProgressId(topicId, activityId);
        return max == null ? 0L : max;
    }

    private static void requirePeriod(String period)
    {
        if (period != null && !PERIOD.equalsIgnoreCase(period) && !"all".equals(period))
            throw new ChannelRejection(400, "PERIOD_UNSUPPORTED", "只支持累计榜");
    }

    static int percent(long done, long required)
    {
        if (required <= 0) return 0;
        return (int) Math.min(100, done * 100 / required);
    }

    private static long number(Object raw, long min)
    {
        if ((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() >= min) return ((Number) raw).longValue();
        throw ChannelRejection.badField("编号不合法");
    }
}
