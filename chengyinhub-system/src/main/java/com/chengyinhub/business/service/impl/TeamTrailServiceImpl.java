package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITeamTrailService;
import com.chengyinhub.common.core.redis.RedisCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 同场队伍路线。存储:Redis 两个 hash,key 带场次 id;
 * <ul>
 *   <li>{@code team_trail:pts:{activityId}} 成员 → {p:[[lat,lng]...], d:完成站数, t:最后上报毫秒}</li>
 *   <li>{@code team_trail:opt:{activityId}} 成员 → "1"/"0" 本人表态</li>
 * </ul>
 * 两个 key 每次写都重设过期:场次结束后 1 小时,且不超过最后一次写入后 6 小时。读时再按报名事实过滤,
 * 退出(退款/取消)的人即使还有残留点也不出现;关掉分享、结束本局都立即删除本人的点。
 */
@Service
public class TeamTrailServiceImpl implements ITeamTrailService {

    static final String PTS = "team_trail:pts:";
    static final String OPT = "team_trail:opt:";
    /** 四位小数 ≈ 11 米:够画步行路线,又不存精确位置。 */
    static final double GRID = 10000d;
    static final int MAX_POINTS = 240;
    static final int MAX_UPLOAD = 40;
    static final double MIN_STEP_M = 10d;
    static final long STALE_MS = 10L * 60L * 1000L;
    static final long MAX_TTL_MS = 6L * 3600L * 1000L;
    static final long AFTER_END_MS = 3600L * 1000L;
    static final int MAX_TEAMS = 12;

    @Autowired
    private RedisCache redisCache;
    @Autowired
    private CmsActivityMapper activityMapper;
    @Autowired
    private CmsTopicMapper topicMapper;
    @Autowired
    private PlayTeamMapper playTeamMapper;

    /** 没表过态时的分享默认值(业主 2026-11-03:默认开,每人可关)。改这一个配置即可。 */
    @Value("${chengyin.team-trail.default-share:true}")
    private boolean defaultShare = true;

    @Override
    public Map<String, Object> state(Long memberId, Long activityId) {
        long ttl = eligibleTtl(memberId, activityId);
        if (ttl <= 0) return unavailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        String raw = redisCache.getCacheMapValue(OPT + activityId, String.valueOf(memberId));
        out.put("share", raw == null ? defaultShare : "1".equals(raw));
        out.put("decided", raw != null);
        return out;
    }

    @Override
    public Map<String, Object> consent(Long memberId, Long activityId, boolean share) {
        long ttl = eligibleTtl(memberId, activityId);
        if (ttl <= 0) return unavailable();
        String key = OPT + activityId;
        redisCache.setCacheMapValue(key, String.valueOf(memberId), share ? "1" : "0");
        redisCache.expire(key, ttl, TimeUnit.MILLISECONDS);
        if (!share) redisCache.deleteCacheMapValue(PTS + activityId, String.valueOf(memberId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("share", share);
        out.put("decided", true);
        return out;
    }

    @Override
    public Map<String, Object> sync(Long memberId, Long activityId, List<List<Double>> points, Integer done, boolean finished) {
        long ttl = eligibleTtl(memberId, activityId);
        if (ttl <= 0) return unavailable();
        String field = String.valueOf(memberId);
        String ptsKey = PTS + activityId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        String raw = redisCache.getCacheMapValue(OPT + activityId, field);
        boolean share = raw == null ? defaultShare : "1".equals(raw);
        out.put("share", share);
        if (finished || !share) {
            redisCache.deleteCacheMapValue(ptsKey, field);
            out.put("teams", Collections.emptyList());
            return out;
        }
        long now = System.currentTimeMillis();
        JSONObject mine = parse(redisCache.getCacheMapValue(ptsKey, field));
        JSONArray kept = mine == null ? new JSONArray() : mine.getJSONArray("p");
        if (kept == null) kept = new JSONArray();
        appendPoints(kept, points);
        JSONObject row = new JSONObject();
        row.put("p", kept);
        row.put("d", done == null ? 0 : Math.max(0, Math.min(999, done)));
        row.put("t", now);
        redisCache.setCacheMapValue(ptsKey, field, row.toJSONString());
        redisCache.expire(ptsKey, ttl, TimeUnit.MILLISECONDS);
        out.put("teams", teams(memberId, activityId, now));
        return out;
    }

    /** 同场其他队伍:每队一条线(最近上报的队员),进度取队内最大。自己的队不回。 */
    List<Map<String, Object>> teams(Long memberId, Long activityId, long now) {
        Map<String, Object> all = redisCache.getCacheMap(PTS + activityId);
        if (all == null || all.isEmpty()) return Collections.emptyList();
        Map<String, Object> opts = redisCache.getCacheMap(OPT + activityId);
        Map<Long, JSONObject> rows = new HashMap<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            Long id = parseLong(e.getKey());
            if (id == null) continue;
            Object opt = opts == null ? null : opts.get(e.getKey());
            boolean share = opt == null ? defaultShare : "1".equals(String.valueOf(opt));
            if (!share) continue;
            JSONObject row = parse(e.getValue() == null ? null : String.valueOf(e.getValue()));
            if (row == null || now - row.getLongValue("t") > STALE_MS) continue;
            rows.put(id, row);
        }
        if (!rows.containsKey(memberId)) rows.put(memberId, new JSONObject());
        List<Map<String, Object>> people = playTeamMapper.selectTrailParticipants(activityId, new ArrayList<>(rows.keySet()));
        if (people == null || people.isEmpty()) return Collections.emptyList();
        String myGroup = null;
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, Long> latest = new HashMap<>();
        for (Map<String, Object> p : people) {
            Long id = parseLong(p.get("memberId") == null ? null : String.valueOf(p.get("memberId")));
            if (id == null) continue;
            Object teamId = p.get("teamId");
            String group = teamId == null ? "m" + id : "t" + teamId;
            if (id.equals(memberId)) { myGroup = group; continue; }
            JSONObject row = rows.get(id);
            if (row == null || row.getJSONArray("p") == null) continue;
            long t = row.getLongValue("t");
            Map<String, Object> g = groups.get(group);
            if (g == null) {
                g = new LinkedHashMap<>();
                g.put("key", opaque(activityId, group));
                groups.put(group, g);
                g.put("done", 0);
            }
            // 第十二轮 k2(j6 N5):进度取服务端完成事实(本场已完成站数),不信客户端上报的 d。
            g.put("done", Math.max(((Number) g.get("done")).intValue(), serverDone(p.get("done"))));
            if (!latest.containsKey(group) || t > latest.get(group)) {
                latest.put(group, t);
                Object title = p.get("teamTitle");
                g.put("name", title != null && !String.valueOf(title).trim().isEmpty() ? String.valueOf(title) : String.valueOf(p.get("nickname") == null ? "" : p.get("nickname")));
                g.put("avatar", p.get("avatar") == null ? "" : String.valueOf(p.get("avatar")));
                g.put("pts", row.getJSONArray("p"));
                g.put("ago", Math.max(0L, (now - t) / 1000L));
            }
        }
        if (myGroup != null) groups.remove(myGroup);
        List<Map<String, Object>> out = new ArrayList<>(groups.values());
        out.removeIf(g -> !(g.get("pts") instanceof JSONArray) || ((JSONArray) g.get("pts")).isEmpty());
        out.sort((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))));
        return out.size() > MAX_TEAMS ? new ArrayList<>(out.subList(0, MAX_TEAMS)) : out;
    }

    static int serverDone(Object value) {
        if (!(value instanceof Number)) return 0;
        return Math.max(0, Math.min(999, ((Number) value).intValue()));
    }

    /**
     * 能用就返回存储过期毫秒数,不能用返回 0:未登录、不是这一场已付费的人、场次还没开始、场次已结束、作者关掉了都算不能用。
     */
    long eligibleTtl(Long memberId, Long activityId) {
        if (memberId == null || memberId <= 0L || activityId == null || activityId <= 0L) return 0L;
        CmsActivity activity = activityMapper.selectCmsActivityById(activityId);
        if (activity == null || (activity.getDelFlag() != null && !"0".equals(String.valueOf(activity.getDelFlag())))) return 0L;
        if (activity.getTopicId() == null) return 0L;
        CmsTopic topic = topicMapper.selectCmsTopicById(activity.getTopicId());
        if (topic == null) return 0L;
        if (!authorAllows(topic.getPublishedThemeRuntimeConfigJson())) {
            // 第十二轮 k7(j4 N10):作者关掉后,调用者自己已存的点当场删掉,不等过期。
            redisCache.deleteCacheMapValue(PTS + activityId, String.valueOf(memberId));
            return 0L;
        }
        long now = System.currentTimeMillis();
        // 第十二轮 k2(j6 N5):开场前不收也不回别队轨迹(集合前的位置不是游戏进度)。
        if (activity.getStartDate() != null && now < activity.getStartDate().getTime()) return 0L;
        long ttl = MAX_TTL_MS;
        if (activity.getEndDate() != null) {
            ttl = Math.min(ttl, activity.getEndDate().getTime() + AFTER_END_MS - now);
            if (ttl <= 0) return 0L;
        }
        if (playTeamMapper.hasPaidRegistration(memberId, 2, activityId) <= 0) return 0L;
        return ttl;
    }

    /** 作者在主题配置里 teamTrail=false 时整场关掉;没配置 = 开。 */
    static boolean authorAllows(String publishedConfig) {
        if (publishedConfig == null || publishedConfig.trim().isEmpty()) return true;
        try {
            JSONObject c = JSON.parseObject(publishedConfig);
            return c == null || !Boolean.FALSE.equals(c.getBoolean("teamTrail"));
        } catch (Exception e) {
            return true;
        }
    }

    static void appendPoints(JSONArray kept, List<List<Double>> points) {
        if (points == null) return;
        int n = 0;
        for (List<Double> p : points) {
            if (n++ >= MAX_UPLOAD) break;
            if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) continue;
            double lat = p.get(0), lng = p.get(1);
            if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
            if (lat == 0d && lng == 0d) continue;
            lat = Math.round(lat * GRID) / GRID;
            lng = Math.round(lng * GRID) / GRID;
            if (!kept.isEmpty()) {
                JSONArray last = kept.getJSONArray(kept.size() - 1);
                if (distanceM(last.getDoubleValue(0), last.getDoubleValue(1), lat, lng) < MIN_STEP_M) continue;
            }
            JSONArray q = new JSONArray();
            q.add(lat);
            q.add(lng);
            kept.add(q);
        }
        // 超出上限时把较早的一半隔点抽掉,首点和最近的点都留着
        while (kept.size() > MAX_POINTS) {
            int half = kept.size() / 2;
            for (int i = half - 1; i >= 1; i -= 2) kept.remove(i);
        }
    }

    static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double rad = Math.PI / 180d;
        double x = (lng2 - lng1) * rad * Math.cos((lat1 + lat2) / 2d * rad);
        double y = (lat2 - lat1) * rad;
        return Math.sqrt(x * x + y * y) * 6371000d;
    }

    static String opaque(Long activityId, String group) {
        String hex = DigestUtils.md5DigestAsHex(("team-trail|" + activityId + "|" + group).getBytes(StandardCharsets.UTF_8));
        return hex.substring(0, 12);
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try { return JSON.parseObject(raw); } catch (Exception e) { return null; }
    }

    private static Long parseLong(String raw) {
        if (raw == null) return null;
        try { return Long.valueOf(raw.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Map<String, Object> unavailable() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        return out;
    }
}
