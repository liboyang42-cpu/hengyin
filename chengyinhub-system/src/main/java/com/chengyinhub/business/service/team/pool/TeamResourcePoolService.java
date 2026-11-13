package com.chengyinhub.business.service.team.pool;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.team.pool.TeamResourcePoolMapper;
import com.chengyinhub.business.service.channel.platform.ChannelReceipts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 队伍共享资源池（UI优化施工清单 §25.1）：第一个答对全队推进；提示/跳过/计时全队共享；全队一个分数。
 * <ul>
 * <li>额度在建池时按主题已发布运行配置 {@code gameplayRuntime.teamPool{hints,skips,timerSec}} 冻结，之后改配置不影响本池。</li>
 * <li>同一 (队伍, 节点, 类型) 只消耗一次：唯一键 + 池行锁；并发的后到者得到 consumed=false 的同一结果。</li>
 * <li>全队分 = 任一在岗成员完成的玩家可见节点数，只从完成事实计算，客户端不能写。</li>
 * <li>不另造钱包/积分；不改原单人提示（积分解锁）链路。</li>
 * </ul>
 */
@Service
public class TeamResourcePoolService
{
    static final String HINT = "HINT";
    static final String SKIP = "SKIP";
    static final String REQUEST_PATTERN = "[A-Za-z0-9_-]{16,64}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TeamResourcePoolMapper mapper;

    public TeamResourcePoolService() { }

    TeamResourcePoolService(TeamResourcePoolMapper mapper) { this.mapper = mapper; }

    /** 一局的内容范围：play_team.owner_type 1=主题自玩(activity=0) 2=场次。 */
    record Run(long teamId, long topicId, long activityId) { }

    /** 冻结额度；null = 本主题未配置共享池。 */
    record Limits(int hints, int skips, int timerSec) { }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pool(long viewer, long teamId)
    {
        Run run = requireMember(viewer, teamId);
        Map<String, Object> pool = ensurePool(run);
        if (pool == null) return disabled(run);
        return view(run, pool);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> consume(long viewer, String kind, Map<String, Object> body)
    {
        if (!HINT.equals(kind) && !SKIP.equals(kind)) throw new IllegalArgumentException("kind");
        Command c = Command.of(body, Set.of("teamId", "nodeId", "requestId"));
        long teamId = c.number("teamId"), nodeId = c.number("nodeId");
        String fp = ChannelReceipts.fingerprint(kind, teamId, c.body);
        return once(viewer, c.requestId, teamId, fp, () -> {
            Run run = requireMember(viewer, teamId);
            if (ensurePool(run) == null) throw new TeamPoolRejection(409, "TEAM_POOL_DISABLED", "该玩法未开启全队共享");
            Map<String, Object> pool = mapper.lockPool(teamId);
            if (expired(pool)) throw new TeamPoolRejection(409, "TEAM_POOL_TIMER_EXPIRED", "全队计时已结束");
            if (mapper.countListedNode(run.topicId(), nodeId) == 0) throw new TeamPoolRejection(404, "NODE_NOT_FOUND", "节点不存在");
            Map<String, Object> existing = mapper.selectUse(teamId, kind, nodeId);
            // 先证明提示正文拿得出来，再扣全队额度；拿不出来既不扣也不记。
            Map<String, Object> hint = HINT.equals(kind) ? sharedHint(run, nodeId, viewer) : null;
            if (HINT.equals(kind) && hint == null)
                throw new TeamPoolRejection(409, "TEAM_POOL_HINT_UNAVAILABLE", "该节点没有可用的提示内容，未扣除额度");
            boolean consumed = false;
            long by;
            if (existing != null)
            {
                by = toLong(existing.get("memberId"));
            }
            else
            {
                if (mapper.consume(teamId, kind) != 1)
                    throw new TeamPoolRejection(409, "TEAM_POOL_EXHAUSTED", HINT.equals(kind) ? "全队提示已用完" : "全队跳过已用完");
                try
                {
                    mapper.insertUse(teamId, kind, nodeId, viewer, c.requestId);
                }
                catch (RuntimeException race)
                {
                    if (!ChannelRejection.isDuplicateKey(race)) throw race;
                    // 池行锁下不应出现；出现即说明锁序被破坏，整笔回滚而不是重复扣。
                    throw new IllegalStateException("Concurrent pool use without lock", race);
                }
                consumed = true;
                by = viewer;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("requestId", c.requestId);
            r.put("kind", kind);
            r.put("nodeId", nodeId);
            r.put("consumed", consumed);
            r.put("byMemberId", by);
            if (hint != null) r.put("hint", hint);
            r.put("pool", view(run, mapper.selectPool(teamId)));
            return r;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startTimer(long viewer, Map<String, Object> body)
    {
        Command c = Command.of(body, Set.of("teamId", "requestId"));
        long teamId = c.number("teamId");
        String fp = ChannelReceipts.fingerprint("TIMER_START", teamId, c.body);
        return once(viewer, c.requestId, teamId, fp, () -> {
            Run run = requireMember(viewer, teamId);
            if (ensurePool(run) == null) throw new TeamPoolRejection(409, "TEAM_POOL_DISABLED", "该玩法未开启全队共享");
            Map<String, Object> pool = mapper.lockPool(teamId);
            if (toLong(pool.get("timerLimitSec")) <= 0) throw new TeamPoolRejection(409, "TEAM_POOL_TIMER_DISABLED", "该玩法没有全队计时");
            if (pool.get("timerStartedAt") == null) mapper.startTimer(teamId);
            return view(run, mapper.selectPool(teamId));
        });
    }

    public Map<String, Object> receipt(long viewer, long teamId, String requestId)
    {
        if (requestId == null || !requestId.matches(REQUEST_PATTERN)) throw new TeamPoolRejection(400, "INVALID_FIELD", "requestId 不合法");
        requireMember(viewer, teamId);
        Map<String, Object> stored = mapper.selectReceipt(viewer, requestId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requestId", requestId);
        if (stored == null || stored.get("response") == null || toLong(stored.get("teamId")) != teamId)
        {
            r.put("state", "NOT_FOUND");
            return r;
        }
        r.put("state", "APPLIED");
        r.put("result", ChannelReceipts.decode(stored.get("response").toString()));
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> once(long viewer, String requestId, long teamId, String fp, Supplier<Map<String, Object>> work)
    {
        mapper.reserveReceipt(viewer, requestId, teamId, fp);
        Map<String, Object> receipt = mapper.lockReceipt(viewer, requestId);
        if (receipt == null) throw new IllegalStateException("Receipt reservation lost");
        if (!fp.equals(String.valueOf(receipt.get("fingerprint"))))
            throw new TeamPoolRejection(409, "REQUEST_ID_CONFLICT", "该请求标识已用于其他内容");
        if (receipt.get("response") != null)
        {
            Map<String, Object> replay = ChannelReceipts.decode(receipt.get("response").toString());
            replay.put("replayed", true);
            return replay;
        }
        Map<String, Object> response = work.get();
        String encoded = ChannelReceipts.encode(response);
        if (mapper.finishReceipt(viewer, requestId, encoded) != 1) throw new IllegalStateException("Receipt finish failed");
        return ChannelReceipts.decode(encoded);
    }

    Run requireMember(long viewer, long teamId)
    {
        Map<String, Object> team = teamId <= 0 ? null : mapper.selectTeam(teamId);
        if (team == null) throw new TeamPoolRejection(404, "TEAM_NOT_FOUND", "队伍不存在");
        if (viewer <= 0 || mapper.countActiveMember(teamId, viewer) == 0)
            throw new TeamPoolRejection(403, "TEAM_NOT_MEMBER", "只有队伍成员可以使用全队资源");
        long ownerType = toLong(team.get("ownerType")), ownerId = toLong(team.get("ownerId"));
        if (ownerType == 1) return new Run(teamId, ownerId, 0L);
        Long topicId = mapper.selectActivityTopic(ownerId);
        if (topicId == null) throw new TeamPoolRejection(404, "TEAM_NOT_FOUND", "队伍场次不存在");
        return new Run(teamId, topicId, ownerId);
    }

    /** 已有池直接用（冻结值）；没有池时按当前已发布配置冻结一次；未配置返回 null。 */
    private Map<String, Object> ensurePool(Run run)
    {
        Map<String, Object> pool = mapper.selectPool(run.teamId());
        if (pool != null) return pool;
        Limits limits = limits(mapper.selectPublishedTheme(run.topicId()));
        if (limits == null) return null;
        Map<String, Object> row = new HashMap<>();
        row.put("teamId", run.teamId());
        row.put("topicId", run.topicId());
        row.put("activityId", run.activityId());
        row.put("hintsTotal", limits.hints());
        row.put("skipsTotal", limits.skips());
        row.put("timerLimitSec", limits.timerSec());
        mapper.insertPool(row);
        return mapper.selectPool(run.teamId());
    }

    static Limits limits(String themeJson)
    {
        if (themeJson == null || themeJson.isBlank()) return null;
        try
        {
            JsonNode pool = JSON.readTree(themeJson).path("gameplayRuntime").path("teamPool");
            if (!pool.isObject()) return null;
            int hints = bounded(pool.path("hints"), 0, 99);
            int skips = bounded(pool.path("skips"), 0, 99);
            int timer = bounded(pool.path("timerSec"), 0, 86_400);
            return new Limits(hints, skips, timer);
        }
        catch (Exception malformed)
        {
            return null;
        }
    }

    private static int bounded(JsonNode node, int min, int max)
    {
        if (node.isMissingNode() || node.isNull()) return 0;
        if (!node.canConvertToInt()) throw new IllegalArgumentException("bad limit");
        return Math.max(min, Math.min(max, node.asInt()));
    }

    private boolean expired(Map<String, Object> pool)
    {
        long limit = toLong(pool.get("timerLimitSec"));
        Date started = (Date) asDate(pool.get("timerStartedAt"));
        return limit > 0 && started != null && System.currentTimeMillis() > started.getTime() + limit * 1000L;
    }

    private Map<String, Object> view(Run run, Map<String, Object> pool)
    {
        List<Long> solved = mapper.selectSolvedNodes(run.teamId(), run.topicId(), run.activityId());
        long hintsTotal = toLong(pool.get("hintsTotal")), hintsUsed = toLong(pool.get("hintsUsed"));
        long skipsTotal = toLong(pool.get("skipsTotal")), skipsUsed = toLong(pool.get("skipsUsed"));
        long limit = toLong(pool.get("timerLimitSec"));
        Date started = (Date) asDate(pool.get("timerStartedAt"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("teamId", run.teamId());
        v.put("topicId", run.topicId());
        v.put("activityId", run.activityId());
        v.put("enabled", true);
        v.put("hintsTotal", hintsTotal);
        v.put("hintsUsed", hintsUsed);
        v.put("hintsLeft", hintsTotal - hintsUsed);
        v.put("skipsTotal", skipsTotal);
        v.put("skipsUsed", skipsUsed);
        v.put("skipsLeft", skipsTotal - skipsUsed);
        v.put("timerLimitSec", limit);
        v.put("timerStartedAt", started == null ? null : started.getTime());
        v.put("timerDeadline", started == null || limit <= 0 ? null : started.getTime() + limit * 1000L);
        v.put("timerExpired", expired(pool));
        v.put("teamScore", solved.size());
        v.put("scoreBasis", "TEAM_FIRST_SOLVE_NODES");
        v.put("solvedNodeIds", solved);
        v.put("version", toLong(pool.get("version")));
        Date frozen = (Date) asDate(pool.get("frozenAt"));
        v.put("frozenAt", frozen == null ? null : frozen.getTime());
        return v;
    }

    private static Map<String, Object> disabled(Run run)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("teamId", run.teamId());
        v.put("topicId", run.topicId());
        v.put("activityId", run.activityId());
        v.put("enabled", false);
        v.put("unavailableReason", "TEAM_POOL_NOT_CONFIGURED");
        return v;
    }

    private static Object asDate(Object value)
    {
        if (value instanceof java.time.LocalDateTime t) return java.sql.Timestamp.valueOf(t);
        return value;
    }

    static long toLong(Object value)
    {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }

    static final class Command
    {
        final Map<String, Object> body;
        final String requestId;

        private Command(Map<String, Object> body, String requestId) { this.body = body; this.requestId = requestId; }

        static Command of(Map<String, Object> body, Set<String> allowed)
        {
            if (body == null) throw new TeamPoolRejection(400, "INVALID_FIELD", "请求体为空");
            for (String key : body.keySet())
                if (!allowed.contains(key)) throw new TeamPoolRejection(400, "INVALID_FIELD", "未知字段 " + key);
            Object id = body.get("requestId");
            if (!(id instanceof String text) || !text.matches(REQUEST_PATTERN))
                throw new TeamPoolRejection(400, "INVALID_FIELD", "requestId 不合法");
            return new Command(new LinkedHashMap<>(body), text);
        }

        long number(String key)
        {
            Object raw = body.get(key);
            if ((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() > 0) return ((Number) raw).longValue();
            if (raw instanceof String text && text.matches("[1-9][0-9]{0,18}")) return Long.parseLong(text);
            throw new TeamPoolRejection(400, "INVALID_FIELD", key + " 不合法");
        }
    }

    /**
     * 全队共享提示正文：与单人提示阶梯同一冻结来源（本局路线会话快照的 nodeAnswers / nodeConfigs.hintLadder，
     * 见 PlayHelpApplication.frozenResolved），取最低一级可下发的非跳过、非揭答层；没有阶梯时回落冻结的 hint1。
     * 共享提示不收积分（额度即全队代价）；返回 {level, type, content}，推不出时 null。
     */
    Map<String, Object> sharedHint(Run run, long nodeId, long viewer)
    {
        String snapshot = mapper.selectTeamRunSnapshot(run.teamId(), run.topicId(), run.activityId(), viewer);
        if (snapshot == null) return null;
        com.fasterxml.jackson.databind.JsonNode answer;
        try { answer = com.chengyinhub.business.service.support.NodeJourneySnapshot.answer(snapshot, nodeId); }
        catch (RuntimeException unusableSnapshot) { return null; }
        com.fasterxml.jackson.databind.JsonNode ladder = null;
        if (answer != null && answer.path("hintLadder").isArray()) ladder = answer.path("hintLadder");
        else
        {
            try
            {
                com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(snapshot);
                com.fasterxml.jackson.databind.JsonNode configured = root.path("nodeConfigs").path(String.valueOf(nodeId)).path("hintLadder");
                if (configured.isArray()) ladder = configured;
            }
            catch (Exception unreadable) { ladder = null; }
        }
        java.util.List<com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelSpec> specs;
        try { specs = com.chengyinhub.business.service.support.PlayHelpUsagePolicy.levels(ladder); }
        catch (IllegalArgumentException invalidLadder) { specs = java.util.List.of(); }
        java.util.List<com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelSpec> ordered = new java.util.ArrayList<>(specs);
        ordered.sort(java.util.Comparator.comparingInt(com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelSpec::level));
        for (com.chengyinhub.business.service.support.PlayHelpUsagePolicy.LevelSpec spec : ordered)
        {
            String type = spec.type();
            if (!spec.usable() || com.chengyinhub.business.service.support.PlayHelpContent.TYPE_SKIP.equals(type)
                    || com.chengyinhub.business.service.support.PlayHelpContent.TYPE_ANSWER.equals(type)) continue;
            com.fasterxml.jackson.databind.JsonNode entry = null;
            for (com.fasterxml.jackson.databind.JsonNode e : ladder) if (e.path("level").asInt() == spec.level()) entry = e;
            String content = com.chengyinhub.business.service.support.PlayHelpContent.approved(spec.level(), type, entry, answer);
            if (content != null) return hintView(spec.level(), type, content);
        }
        if (ordered.isEmpty())
        {
            String legacy = com.chengyinhub.business.service.support.PlayHelpContent.approved(1,
                    com.chengyinhub.business.service.support.PlayHelpContent.TYPE_TEXT, null, answer);
            if (legacy != null && !legacy.isBlank()) return hintView(1, com.chengyinhub.business.service.support.PlayHelpContent.TYPE_TEXT, legacy);
        }
        return null;
    }

    private static Map<String, Object> hintView(int level, String type, String content)
    {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("level", level);
        hint.put("type", type);
        hint.put("content", content);
        return hint;
    }
}
