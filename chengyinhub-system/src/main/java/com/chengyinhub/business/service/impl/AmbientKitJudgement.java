package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * G2 / GAM-07:沉默点单、音乐角、时段限定三个原本"只展示"的玩法,补上服务端判定与奖励。
 *
 * <p>★ 兼容:三段配置里新增 {@code scored}(布尔,默认 false)。不开它的旧模板行为一字不变 ——
 * 不参与通关判定、不发分(#857 / 2026-08-26 的拍板对旧内容继续有效)。作者在创作端打开
 * 「计入通关」后,才要求玩家在服务端留下一次合格的完成记录,并按 {@code xp} 发一次分。
 *
 * <p>★ 判定全用服务端时钟,不采信客户端上报的"我听完了 / 我用了多少秒":
 * <ul>
 *   <li>沉默点单:START 记服务端开表时间;FINISH 时服务端算用时,在时限内(+1.5 秒宽限)判过,
 *       超时判"这次没过",清表可重来。无时限时开表满 10 秒才认(防一键双发)。</li>
 *   <li>音乐角:START 记开始播放时间;FINISH 要求服务端经过的时间 ≥ 曲目时长的 80%
 *       (没填时长按 30 秒)。不够就是可重试的提示,不改状态。</li>
 *   <li>时段限定:CHECK_IN 当场按服务端时间校验开放时段,在时段内才记一次到场。</li>
 * </ul>
 * 奖励只发一次(passed 已为真时不再加分);全部动作幂等,不会因为重放多发。
 */
final class AmbientKitJudgement {
    static final Set<String> ACTIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "START_SILENT_ORDER", "FINISH_SILENT_ORDER", "START_MUSIC_CORNER", "FINISH_MUSIC_CORNER", "CHECK_IN_TIME_WINDOW")));
    static final long SLACK_MS = AdvancedActionJudgement.CLOCK_SLACK_MS;
    static final long SILENT_MIN_MS = 10_000L;
    static final int MUSIC_DEFAULT_SECONDS = 30;
    static final double MUSIC_LISTENED_RATIO = 0.8d;

    private AmbientKitJudgement() {}

    static boolean handles(String action) { return ACTIONS.contains(action); }

    static boolean scored(JsonNode section) {
        return enabled(section) && section.path("scored").asBoolean(false);
    }

    /** 判定结果:要加的分(0 表示不加)。时段限定的"开没开"由调用方的 requireWithinTimeWindow 先把守。 */
    static int apply(String action, JsonNode config, ObjectNode state, long nowMs) {
        switch (action) {
            case "START_SILENT_ORDER": return startSilentOrder(config.get("silentOrder"), state, nowMs);
            case "FINISH_SILENT_ORDER": return finishSilentOrder(config.get("silentOrder"), state, nowMs);
            case "START_MUSIC_CORNER": return startMusic(config.get("musicCorner"), state, nowMs);
            case "FINISH_MUSIC_CORNER": return finishMusic(config.get("musicCorner"), state, nowMs);
            case "CHECK_IN_TIME_WINDOW": return checkInTimeWindow(config.get("timeWindow"), state, nowMs);
            default: throw new IllegalArgumentException("高级玩法动作不支持");
        }
    }

    private static int startSilentOrder(JsonNode cfg, ObjectNode state, long nowMs) {
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启沉默点单");
        ObjectNode node = state.with("silentOrder");
        if (node.path("passed").asBoolean(false)) return 0;          // 已过,幂等
        if (node.path("startedAt").isNumber()) return 0;             // 已在计时,重复开始不重置
        node.put("startedAt", nowMs);
        return 0;
    }

    private static int finishSilentOrder(JsonNode cfg, ObjectNode state, long nowMs) {
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启沉默点单");
        ObjectNode node = state.with("silentOrder");
        if (node.path("passed").asBoolean(false)) return 0;
        if (!node.path("startedAt").isNumber()) throw new IllegalStateException("请先开始计时");
        long elapsed = Math.max(0L, nowMs - node.path("startedAt").asLong());
        long limitMs = Math.max(0, cfg.path("limitSeconds").asInt(0)) * 1000L;
        if (limitMs == 0 && elapsed < SILENT_MIN_MS) throw new IllegalStateException("刚开始,完成点单后再来确认");
        node.put("attempts", node.path("attempts").asInt(0) + 1);
        node.put("lastSeconds", elapsed / 1000L);
        node.remove("startedAt");
        if (limitMs > 0 && elapsed > limitMs + SLACK_MS) {
            node.put("passed", false);
            node.put("lastOutcome", "LATE");
            return 0;
        }
        node.put("passed", true);
        node.put("lastOutcome", "PASS");
        return scored(cfg) ? xp(cfg) : 0;
    }

    private static int startMusic(JsonNode cfg, ObjectNode state, long nowMs) {
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启音乐角");
        ObjectNode node = state.with("musicCorner");
        if (node.path("listened").asBoolean(false) || node.path("startedAt").isNumber()) return 0;
        node.put("startedAt", nowMs);
        return 0;
    }

    private static int finishMusic(JsonNode cfg, ObjectNode state, long nowMs) {
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启音乐角");
        ObjectNode node = state.with("musicCorner");
        if (node.path("listened").asBoolean(false)) return 0;
        if (!node.path("startedAt").isNumber()) throw new IllegalStateException("请先开始播放");
        long need = requiredListenMs(cfg);
        long elapsed = Math.max(0L, nowMs - node.path("startedAt").asLong());
        if (elapsed + SLACK_MS < need) {
            long left = (need - elapsed + 999L) / 1000L;
            throw new IllegalStateException("再听 " + left + " 秒就好");
        }
        node.put("listened", true);
        return scored(cfg) ? xp(cfg) : 0;
    }

    static long requiredListenMs(JsonNode cfg) {
        int seconds = cfg.path("durationSeconds").asInt(0);
        if (seconds <= 0) seconds = MUSIC_DEFAULT_SECONDS;
        return (long) Math.floor(seconds * MUSIC_LISTENED_RATIO * 1000d);
    }

    private static int checkInTimeWindow(JsonNode cfg, ObjectNode state, long nowMs) {
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启时段限定");
        ObjectNode node = state.with("timeWindow");
        if (node.path("attended").asBoolean(false)) return 0;
        node.put("attended", true);
        node.put("attendedAt", nowMs);
        return scored(cfg) ? xp(cfg) : 0;
    }

    /** 计入通关时才要求完成;不计入的旧模板一律放行(原口径)。 */
    static boolean ready(JsonNode config, JsonNode state) {
        if (scored(config.get("silentOrder")) && !state.path("silentOrder").path("passed").asBoolean(false)) return false;
        if (scored(config.get("musicCorner")) && !state.path("musicCorner").path("listened").asBoolean(false)) return false;
        if (scored(config.get("timeWindow")) && !state.path("timeWindow").path("attended").asBoolean(false)) return false;
        return true;
    }

    /** 投影到 playKit 的进度字段(只有状态,不含任何可伪造的判定输入)。 */
    static void project(String kind, JsonNode cfg, JsonNode state, Map<String, Object> value) {
        value.put("scored", cfg.path("scored").asBoolean(false));
        value.put("xp", cfg.path("xp").asInt(0));
        JsonNode node = state.path(kind);
        switch (kind) {
            case "silentOrder":
                value.put("running", node.path("startedAt").isNumber());
                value.put("passed", node.path("passed").asBoolean(false));
                value.put("attempts", node.path("attempts").asInt(0));
                value.put("lastOutcome", node.path("lastOutcome").asText(""));
                if (node.has("lastSeconds")) value.put("lastSeconds", node.path("lastSeconds").asLong());
                break;
            case "musicCorner":
                value.put("playing", node.path("startedAt").isNumber());
                value.put("listened", node.path("listened").asBoolean(false));
                value.put("requiredSeconds", requiredListenMs(cfg) / 1000L);
                break;
            case "timeWindow":
                value.put("attended", node.path("attended").asBoolean(false));
                break;
            default:
                break;
        }
    }

    private static int xp(JsonNode cfg) { return Math.max(0, Math.min(1000, cfg.path("xp").asInt(0))); }

    private static boolean enabled(JsonNode section) {
        return section != null && section.isObject() && section.path("enabled").asBoolean(false);
    }
}
