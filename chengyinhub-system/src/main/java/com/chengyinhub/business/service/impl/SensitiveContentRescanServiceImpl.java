package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ContentSensitiveHit;
import com.chengyinhub.business.mapper.ContentSensitiveMapper;
import com.chengyinhub.business.service.ISensitiveContentLibrary;
import com.chengyinhub.business.service.ISensitiveContentRescanService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.safety.SensitiveWordMatcher;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.service.ISysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round5D 存量复查。
 *
 * <p>词库版本(见 {@link ISensitiveContentLibrary#currentVersion})变了才扫;按类型、按 id 递增游标分批,
 * 每轮最多 {@code perRunLimit} 条,游标存 Redis(丢了就从头再扫,幂等)。同一版本、同一内容、同一模式只记一次。
 *
 * <p>下架走与 Round5C 微信 risky 回调同一条路径({@link IWxContentSecurityService#takeDownForSafety}):
 * 主题/活动下架留置、评论/俱乐部动态隐藏、社区帖文隐藏,并通知作者。商家与俱乐部资料没有「只下架文字」的
 * 现成路径(那条路径会清掉资料图),所以这两类只报告,不自动处理。
 * 开关 {@code content.sensitive.rescan.remove} 默认 false = 只报告;切到 true 后会按同一版本重扫一遍并下架。
 */
@Service
public class SensitiveContentRescanServiceImpl implements ISensitiveContentRescanService {

    private static final Logger log = LoggerFactory.getLogger(SensitiveContentRescanServiceImpl.class);

    static final String STATE_KEY = "content:sensitive:rescan:state";
    static final String REMOVE_SWITCH = "content.sensitive.rescan.remove";
    static final String ACTION_REPORT = "REPORT";
    static final String ACTION_REMOVED = "REMOVED";

    /** 复查的内容类型 → 可自动下架时对应的内容安全业务类型(null = 只报告)。顺序即扫描顺序。 */
    static final Map<String, Integer> TYPES = new LinkedHashMap<String, Integer>();

    static {
        TYPES.put("COMMENT", IWxContentSecurityService.BIZ_TYPE_COMMENT);
        TYPES.put("COMMUNITY_POST", IWxContentSecurityService.BIZ_TYPE_COMMUNITY_POST);
        TYPES.put("CLUB_POST", IWxContentSecurityService.BIZ_TYPE_CLUB_POST);
        TYPES.put("TOPIC", IWxContentSecurityService.BIZ_TYPE_TOPIC);
        TYPES.put("ACTIVITY", IWxContentSecurityService.BIZ_TYPE_ACTIVITY);
        TYPES.put("MERCHANT_PROFILE", null);
        TYPES.put("CLUB_PROFILE", null);
    }

    @Autowired
    private ContentSensitiveMapper mapper;
    @Autowired
    private ISensitiveContentLibrary library;
    @Autowired
    private IWxContentSecurityService wx;
    @Autowired
    private RedisCache redisCache;
    @Autowired
    private ISysConfigService configService;

    @Value("${content.sensitive.rescan.per-run-limit:500}")
    private int perRunLimit = 500;

    private Clock clock = Clock.systemDefaultZone();

    @Override
    public Result runOnce() {
        String version = library.currentVersion();
        if (version == null) {
            log.error("[敏感词复查] 词库不可用,本轮跳过");
            return new Result(0, 0, 0, false);
        }
        boolean remove = "true".equalsIgnoreCase(String.valueOf(configService.selectConfigByKey(REMOVE_SWITCH)).trim());
        String mode = remove ? ACTION_REMOVED : ACTION_REPORT;
        JSONObject state = loadState();
        if (state == null || !version.equals(state.getString("version")) || !mode.equals(state.getString("mode"))) {
            state = new JSONObject();
            state.put("version", version);
            state.put("mode", mode);
            state.put("typeIndex", 0);
            state.put("afterId", 0L);
            state.put("done", false);
        } else if (state.getBooleanValue("done")) {
            return new Result(0, 0, 0, true);
        }
        String[] types = TYPES.keySet().toArray(new String[0]);
        int budget = Math.max(1, perRunLimit);
        int scanned = 0, hits = 0, removed = 0;
        int typeIndex = state.getIntValue("typeIndex");
        long afterId = state.getLongValue("afterId");
        while (budget > 0 && typeIndex < types.length) {
            String type = types[typeIndex];
            List<Map<String, Object>> rows = mapper.selectPublicText(type, afterId, Math.min(budget, 100));
            if (rows == null || rows.isEmpty()) {
                typeIndex++;
                afterId = 0L;
                continue;
            }
            for (Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id == null) continue;
                afterId = Math.max(afterId, id);
                budget--;
                scanned++;
                Object text = row.get("text");
                SensitiveWordMatcher.Hit hit = text == null ? null : library.matchText(String.valueOf(text));
                if (hit == null || !hit.isBlock()) continue;
                if (mapper.countRescanHit(type, id, version, mode) > 0) continue; // 幂等:本版本本模式已处理
                hits++;
                Integer wxType = TYPES.get(type);
                String action = ACTION_REPORT;
                if (remove && wxType != null) {
                    try {
                        if (wx.takeDownForSafety(wxType, id)) {
                            action = ACTION_REMOVED;
                            removed++;
                        }
                    } catch (RuntimeException e) {
                        log.error("[敏感词复查] 下架失败,本条只报告 type={} id={} exceptionType={}", type, id,
                                e.getClass().getSimpleName());
                    }
                }
                // 只报告模式记 REPORT;下架模式成功记 REMOVED,资料类/下架失败记 REPORT(词库再变时会再试)。
                recordHit(type, id, hit.getCategory(), action, version);
            }
        }
        boolean finished = typeIndex >= types.length;
        state.put("typeIndex", typeIndex);
        state.put("afterId", afterId);
        state.put("done", finished);
        saveState(state);
        log.info("[敏感词复查] mode={} scanned={} hits={} removed={} finished={}", mode, scanned, hits, removed, finished);
        return new Result(scanned, hits, removed, finished);
    }

    private void recordHit(String type, Long id, String category, String action, String scanVersion) {
        ContentSensitiveHit hit = new ContentSensitiveHit();
        hit.setSource("RESCAN");
        hit.setContentType(type);
        hit.setContentId(id);
        hit.setCategory(category == null ? "OTHER" : category);
        hit.setAction(action);
        hit.setScanVersion(scanVersion);
        hit.setCreateTime(new Date(clock.millis()));
        mapper.insertHit(hit);
    }

    private JSONObject loadState() {
        try {
            Object raw = redisCache.getCacheObject(STATE_KEY);
            return raw == null ? null : JSON.parseObject(String.valueOf(raw));
        } catch (RuntimeException e) {
            log.warn("[敏感词复查] 游标读取失败,从头扫描 exceptionType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void saveState(JSONObject state) {
        try {
            redisCache.setCacheObject(STATE_KEY, state.toJSONString());
        } catch (RuntimeException e) {
            log.warn("[敏感词复查] 游标保存失败,下轮从头扫描(幂等) exceptionType={}", e.getClass().getSimpleName());
        }
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? null : Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
