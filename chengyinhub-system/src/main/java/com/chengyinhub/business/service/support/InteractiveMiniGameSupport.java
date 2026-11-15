package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 独立节点小游戏套件（判断题 trueFalse / 猜图揭晓 imageGuess / 拼图 jigsaw / 刮刮卡 scratchCard）：
 * 单一真源承载保存期配置校验、安全公开投影、开局状态、服务端判定与玩家态脱敏回显。
 */
public final class InteractiveMiniGameSupport {
    public static final String SECTION_TRUE_FALSE = "trueFalse";
    public static final String SECTION_IMAGE_GUESS = "imageGuess";
    public static final String SECTION_JIGSAW = "jigsaw";
    public static final String SECTION_SCRATCH_CARD = "scratchCard";

    public static final double DEFAULT_SCRATCH_THRESHOLD = 0.45d;
    private static final double MIN_SCRATCH_THRESHOLD = 0.1d;
    private static final double MAX_SCRATCH_THRESHOLD = 0.9d;
    private static final int MIN_JIGSAW_DIM = 2;
    private static final int MAX_JIGSAW_DIM = 5;
    private static final int DEFAULT_JIGSAW_DIM = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InteractiveMiniGameSupport() {
    }

    // ==================== 保存 / 发布校验 ====================

    public static void validateAll(ObjectNode root) {
        if (root == null) return;
        validateTrueFalse(root.get(SECTION_TRUE_FALSE));
        validateImageGuess(root.get(SECTION_IMAGE_GUESS));
        validateJigsaw(root.get(SECTION_JIGSAW));
        validateScratchCard(root.get(SECTION_SCRATCH_CARD));
    }

    public static void validateTrueFalse(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "判断题配置格式不正确");
        requiredText(cfg, "title", 200, "判断题题干不能为空", "判断题题干不能超过 200 字");
        optionalMediaUrl(cfg, "imageUrl", 512, "判断题配图地址不能超过 512 字", "判断题配图地址必须是 https 链接或站内路径");
        if (!cfg.has("answer") || !cfg.get("answer").isBoolean()) {
            throw new IllegalArgumentException("判断题必须设置布尔型正确答案");
        }
        optionalText(cfg, "explanation", 300, "判断题解析不能超过 300 字");
        optionalText(cfg, "feedbackCorrect", 200, "判断题答对反馈不能超过 200 字");
        optionalText(cfg, "feedbackWrong", 200, "判断题答错反馈不能超过 200 字");
        int maxTries = cfg.path("maxTries").asInt(0);
        if (maxTries < 0 || maxTries > 10) {
            throw new IllegalArgumentException("判断题可答次数须为 0 至 10，0 表示不限");
        }
        validateXp(cfg, "判断题");
    }

    public static void validateImageGuess(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "猜图揭晓配置格式不正确");
        requiredText(cfg, "title", 120, "猜图题干不能为空", "猜图题干不能超过 120 字");
        requiredMediaUrl(cfg, "imageUrl", 512, "猜图必须配图片", "猜图图片地址不能超过 512 字", "猜图图片地址必须是 https 链接或站内路径");
        requiredText(cfg, "answer", 120, "猜图必须填写正确答案", "猜图答案不能超过 120 字");
        optionalText(cfg, "hint", 120, "猜图提示不能超过 120 字");
        if (cfg.has("aliases") && !cfg.get("aliases").isNull()) {
            JsonNode aliases = cfg.get("aliases");
            if (!aliases.isArray() || aliases.size() > 10) {
                throw new IllegalArgumentException("猜图别名最多 10 条");
            }
            for (JsonNode alias : aliases) {
                if (!alias.isTextual() || alias.asText().trim().isEmpty() || alias.asText().trim().length() > 120) {
                    throw new IllegalArgumentException("猜图别名须为 1 至 120 字");
                }
            }
        }
        if (cfg.has("revealSteps") && !cfg.get("revealSteps").isNull()) {
            JsonNode steps = cfg.get("revealSteps");
            if (!steps.isArray() || steps.size() > 6) {
                throw new IllegalArgumentException("猜图分段提示最多 6 条");
            }
            for (JsonNode step : steps) {
                if (!step.isTextual() || step.asText().trim().isEmpty() || step.asText().trim().length() > 120) {
                    throw new IllegalArgumentException("猜图分段提示须为 1 至 120 字");
                }
            }
        }
        int maxTries = cfg.path("maxTries").asInt(0);
        if (maxTries < 0 || maxTries > 10) {
            throw new IllegalArgumentException("猜图可猜次数须为 0 至 10，0 表示不限");
        }
        validateXp(cfg, "猜图揭晓");
    }

    public static void validateJigsaw(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "拼图配置格式不正确");
        requiredText(cfg, "title", 120, "拼图标题不能为空", "拼图标题不能超过 120 字");
        requiredMediaUrl(cfg, "imageUrl", 512, "拼图必须配图片", "拼图图片地址不能超过 512 字", "拼图图片地址必须是 https 链接或站内路径");
        int rows = cfg.path("rows").asInt(DEFAULT_JIGSAW_DIM);
        int cols = cfg.path("cols").asInt(DEFAULT_JIGSAW_DIM);
        if (rows < MIN_JIGSAW_DIM || rows > MAX_JIGSAW_DIM || cols < MIN_JIGSAW_DIM || cols > MAX_JIGSAW_DIM) {
            throw new IllegalArgumentException("拼图行列数须为 2 至 5");
        }
        int maxAttempts = cfg.path("maxAttempts").asInt(0);
        if (maxAttempts < 0 || maxAttempts > 10) {
            throw new IllegalArgumentException("拼图提交次数上限须为 0 至 10，0 表示不限");
        }
        validateXp(cfg, "拼图");
    }

    public static void validateScratchCard(JsonNode cfg) {
        if (!enabled(cfg)) return;
        requireObject(cfg, "刮刮卡配置格式不正确");
        requiredText(cfg, "title", 120, "刮刮卡标题不能为空", "刮刮卡标题不能超过 120 字");
        optionalText(cfg, "coverLabel", 60, "刮刮卡涂层文案不能超过 60 字");
        if (cfg.has("threshold") && !cfg.get("threshold").isNull()) {
            if (!cfg.get("threshold").isNumber()) {
                throw new IllegalArgumentException("刮刮卡揭晓阈值格式不正确");
            }
            double threshold = cfg.get("threshold").asDouble();
            if (!Double.isFinite(threshold) || threshold < MIN_SCRATCH_THRESHOLD || threshold > MAX_SCRATCH_THRESHOLD) {
                throw new IllegalArgumentException("刮刮卡揭晓阈值须在 0.1 至 0.9 之间");
            }
        }
        requiredText(cfg, "prizeText", 200, "刮刮卡揭晓内容不能为空", "刮刮卡揭晓内容不能超过 200 字");
        optionalMediaUrl(cfg, "revealImageUrl", 512, "刮刮卡揭晓配图地址不能超过 512 字", "刮刮卡揭晓配图地址必须是 https 链接或站内路径");
        validateXp(cfg, "刮刮卡");
    }

    // ==================== 公开投影（不含答案 / 奖励秘密） ====================

    public static void copySafeConfigs(ObjectNode target, JsonNode config) {
        if (target == null || config == null) return;
        if (enabled(config.path(SECTION_TRUE_FALSE))) {
            JsonNode src = config.path(SECTION_TRUE_FALSE);
            ObjectNode out = target.putObject(SECTION_TRUE_FALSE);
            out.put("enabled", true);
            putText(out, "title", src);
            putText(out, "imageUrl", src);
            out.put("maxTries", src.path("maxTries").asInt(0));
            out.put("xp", src.path("xp").asInt(0));
        }
        if (enabled(config.path(SECTION_IMAGE_GUESS))) {
            JsonNode src = config.path(SECTION_IMAGE_GUESS);
            ObjectNode out = target.putObject(SECTION_IMAGE_GUESS);
            out.put("enabled", true);
            putText(out, "title", src);
            putText(out, "imageUrl", src);
            putText(out, "hint", src);
            out.put("revealTotal", totalRevealSteps(src));
            out.put("maxTries", src.path("maxTries").asInt(0));
            out.put("xp", src.path("xp").asInt(0));
        }
        if (enabled(config.path(SECTION_JIGSAW))) {
            JsonNode src = config.path(SECTION_JIGSAW);
            ObjectNode out = target.putObject(SECTION_JIGSAW);
            out.put("enabled", true);
            putText(out, "title", src);
            putText(out, "imageUrl", src);
            out.put("rows", jigsawDim(src, "rows"));
            out.put("cols", jigsawDim(src, "cols"));
            out.put("maxAttempts", src.path("maxAttempts").asInt(0));
            out.put("xp", src.path("xp").asInt(0));
        }
        if (enabled(config.path(SECTION_SCRATCH_CARD))) {
            JsonNode src = config.path(SECTION_SCRATCH_CARD);
            ObjectNode out = target.putObject(SECTION_SCRATCH_CARD);
            out.put("enabled", true);
            putText(out, "title", src);
            putText(out, "coverLabel", src);
            out.put("threshold", scratchThreshold(src));
            out.put("xp", src.path("xp").asInt(0));
        }
    }

    // ==================== 建局状态初始化 ====================

    public static void initState(JsonNode config, ObjectNode state) {
        if (config == null || state == null) return;
        if (enabled(config.path(SECTION_JIGSAW))) {
            JsonNode cfg = config.path(SECTION_JIGSAW);
            int total = jigsawDim(cfg, "rows") * jigsawDim(cfg, "cols");
            ObjectNode node = state.putObject(SECTION_JIGSAW);
            node.put("attempts", 0);
            node.put("finished", false);
            node.put("passed", false);
            ArrayNode order = node.putArray("order");
            for (int piece : initialJigsawOrder(total)) {
                order.add(piece);
            }
        }
        if (enabled(config.path(SECTION_IMAGE_GUESS))) {
            ObjectNode node = state.putObject(SECTION_IMAGE_GUESS);
            node.put("attempts", 0);
            node.put("revealLevel", 1);
            node.put("finished", false);
            node.put("passed", false);
        }
        if (enabled(config.path(SECTION_SCRATCH_CARD))) {
            ObjectNode node = state.putObject(SECTION_SCRATCH_CARD);
            node.put("revealed", false);
            node.put("passed", false);
            node.put("finished", false);
        }
    }

    // ==================== 服务端判定 ====================

    public static int submitTrueFalse(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.path(SECTION_TRUE_FALSE);
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启判断题");
        ObjectNode node = state.with(SECTION_TRUE_FALSE);
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一题已经结束了");
        }
        Boolean guess = parseBool(payload == null ? null : payload.get("answer"));
        if (guess == null) {
            throw new IllegalArgumentException("请选择判断结果");
        }
        boolean expected = cfg.path("answer").asBoolean(false);
        boolean correct = guess.booleanValue() == expected;
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastAnswer", guess.booleanValue());
        node.put("lastCorrect", correct);
        node.put("passed", correct);
        int maxTries = cfg.path("maxTries").asInt(0);
        node.put("finished", correct || (maxTries > 0 && attempts >= maxTries));
        String fb = correct ? cfg.path("feedbackCorrect").asText("") : cfg.path("feedbackWrong").asText("");
        if (!fb.isEmpty()) node.put("lastFeedback", fb);
        return correct ? cfg.path("xp").asInt(0) : 0;
    }

    public static int submitImageGuess(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.path(SECTION_IMAGE_GUESS);
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启猜图揭晓");
        ObjectNode node = state.with(SECTION_IMAGE_GUESS);
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一题已经结束了");
        }
        int totalSteps = totalRevealSteps(cfg);
        int currentReveal = Math.max(1, node.path("revealLevel").asInt(1));
        String subAction = text(payload, "action");
        if ("REVEAL_STEP".equalsIgnoreCase(subAction)) {
            int nextReveal = Math.min(totalSteps, currentReveal + 1);
            node.put("revealLevel", nextReveal);
            node.remove("lastCorrect");
            return 0;
        }
        String guess = text(payload, "guess");
        if (guess.isEmpty()) {
            throw new IllegalArgumentException("请输入猜测答案");
        }
        boolean correct = matchesAnswer(guess, cfg);
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        node.put("lastGuess", guess);
        node.put("lastCorrect", correct);
        node.put("passed", correct);
        int maxTries = cfg.path("maxTries").asInt(0);
        boolean finished = correct || (maxTries > 0 && attempts >= maxTries);
        node.put("finished", finished);
        node.put("revealLevel", finished ? totalSteps : Math.min(totalSteps, currentReveal + 1));
        return correct ? cfg.path("xp").asInt(0) : 0;
    }

    public static int submitJigsaw(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.path(SECTION_JIGSAW);
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启拼图");
        ObjectNode node = state.with(SECTION_JIGSAW);
        if (node.path("finished").asBoolean(false)) {
            throw new IllegalStateException("这一关拼图已经结束了");
        }
        int total = jigsawDim(cfg, "rows") * jigsawDim(cfg, "cols");
        Object rawOrder = payload == null ? null : payload.get("order");
        if (!(rawOrder instanceof List<?> rawList) || rawList.size() != total) {
            throw new IllegalArgumentException("请提交完整的拼图块顺序");
        }
        List<Integer> order = new ArrayList<>(total);
        Set<Integer> seen = new HashSet<>(total);
        boolean solved = true;
        for (int i = 0; i < rawList.size(); i++) {
            Integer piece = parseInt(rawList.get(i));
            if (piece == null || piece < 0 || piece >= total || !seen.add(piece)) {
                throw new IllegalArgumentException("拼图块编号必须覆盖 0 至 " + (total - 1) + " 且不能重复");
            }
            order.add(piece);
            if (piece != i) solved = false;
        }
        int attempts = node.path("attempts").asInt(0) + 1;
        node.put("attempts", attempts);
        ArrayNode savedOrder = node.putArray("order");
        for (int piece : order) savedOrder.add(piece);
        node.put("lastCorrect", solved);
        node.put("passed", solved);
        int maxAttempts = cfg.path("maxAttempts").asInt(0);
        node.put("finished", solved || (maxAttempts > 0 && attempts >= maxAttempts));
        return solved ? cfg.path("xp").asInt(0) : 0;
    }

    public static int submitScratchCard(JsonNode config, ObjectNode state, Map<String, Object> payload) {
        JsonNode cfg = config.path(SECTION_SCRATCH_CARD);
        if (!enabled(cfg)) throw new IllegalArgumentException("该玩法未开启刮刮卡");
        ObjectNode node = state.with(SECTION_SCRATCH_CARD);
        if (node.path("revealed").asBoolean(false)) {
            throw new IllegalStateException("这张刮刮卡已经刮开了");
        }
        Double progress = parseDouble(payload == null ? null : payload.get("progress"));
        if (progress == null || !Double.isFinite(progress) || progress < 0.0d || progress > 1.0d) {
            throw new IllegalArgumentException("刮卡进度须在 0 到 1 之间");
        }
        double threshold = scratchThreshold(cfg);
        if (progress < threshold) {
            throw new IllegalArgumentException("再多刮开一点即可揭晓");
        }
        node.put("progress", progress);
        node.put("revealed", true);
        node.put("passed", true);
        node.put("finished", true);
        return cfg.path("xp").asInt(0);
    }

    // ==================== 玩家态白名单组装 ====================

    public static void populateKit(JsonNode config, ObjectNode state, Map<String, Object> kit) {
        if (config == null || state == null || kit == null) return;
        if (enabled(config.path(SECTION_TRUE_FALSE))) {
            JsonNode cfg = config.path(SECTION_TRUE_FALSE);
            JsonNode st = state.path(SECTION_TRUE_FALSE);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", cfg.path("title").asText(""));
            if (!cfg.path("imageUrl").asText("").isEmpty()) value.put("imageUrl", cfg.path("imageUrl").asText(""));
            value.put("maxTries", cfg.path("maxTries").asInt(0));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("attempts", st.path("attempts").asInt(0));
            boolean finished = st.path("finished").asBoolean(false);
            value.put("finished", finished);
            value.put("passed", st.path("passed").asBoolean(false));
            if (st.has("lastAnswer")) value.put("lastAnswer", st.path("lastAnswer").asBoolean(false));
            if (st.has("lastCorrect")) value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            if (!st.path("lastFeedback").asText("").isEmpty()) value.put("lastFeedback", st.path("lastFeedback").asText(""));
            if (finished) {
                value.put("answer", cfg.path("answer").asBoolean(false));
                if (!cfg.path("explanation").asText("").isEmpty()) value.put("explanation", cfg.path("explanation").asText(""));
            }
            kit.put(SECTION_TRUE_FALSE, value);
        }
        if (enabled(config.path(SECTION_IMAGE_GUESS))) {
            JsonNode cfg = config.path(SECTION_IMAGE_GUESS);
            JsonNode st = state.path(SECTION_IMAGE_GUESS);
            int totalSteps = totalRevealSteps(cfg);
            boolean finished = st.path("finished").asBoolean(false);
            int revealLevel = finished ? totalSteps : Math.max(1, Math.min(totalSteps, st.path("revealLevel").asInt(1)));
            int revealPercent = (int) Math.min(100, Math.round((revealLevel * 100.0d) / totalSteps));
            List<String> unlockedClues = new ArrayList<>();
            JsonNode steps = cfg.path("revealSteps");
            if (steps.isArray()) {
                int clueLimit = finished ? steps.size() : Math.min(steps.size(), Math.max(0, revealLevel - 1));
                for (int i = 0; i < clueLimit; i++) {
                    unlockedClues.add(steps.get(i).asText(""));
                }
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", cfg.path("title").asText(""));
            value.put("imageUrl", cfg.path("imageUrl").asText(""));
            if (!cfg.path("hint").asText("").isEmpty()) value.put("hint", cfg.path("hint").asText(""));
            value.put("revealLevel", revealLevel);
            value.put("revealTotal", totalSteps);
            value.put("revealPercent", revealPercent);
            value.put("revealedClues", unlockedClues);
            value.put("maxTries", cfg.path("maxTries").asInt(0));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", finished);
            value.put("passed", st.path("passed").asBoolean(false));
            if (!st.path("lastGuess").asText("").isEmpty()) value.put("lastGuess", st.path("lastGuess").asText(""));
            if (st.has("lastCorrect")) value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            if (finished) value.put("answer", cfg.path("answer").asText(""));
            kit.put(SECTION_IMAGE_GUESS, value);
        }
        if (enabled(config.path(SECTION_JIGSAW))) {
            JsonNode cfg = config.path(SECTION_JIGSAW);
            JsonNode st = state.path(SECTION_JIGSAW);
            int rows = jigsawDim(cfg, "rows");
            int cols = jigsawDim(cfg, "cols");
            int total = rows * cols;
            List<Integer> order = new ArrayList<>(total);
            if (st.path("order").isArray() && st.path("order").size() == total) {
                for (JsonNode one : st.path("order")) order.add(one.asInt());
            } else {
                for (int p : initialJigsawOrder(total)) order.add(p);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", cfg.path("title").asText(""));
            value.put("imageUrl", cfg.path("imageUrl").asText(""));
            value.put("rows", rows);
            value.put("cols", cols);
            value.put("order", order);
            value.put("maxAttempts", cfg.path("maxAttempts").asInt(0));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("attempts", st.path("attempts").asInt(0));
            value.put("finished", st.path("finished").asBoolean(false));
            value.put("passed", st.path("passed").asBoolean(false));
            if (st.has("lastCorrect")) value.put("lastCorrect", st.path("lastCorrect").asBoolean(false));
            kit.put(SECTION_JIGSAW, value);
        }
        if (enabled(config.path(SECTION_SCRATCH_CARD))) {
            JsonNode cfg = config.path(SECTION_SCRATCH_CARD);
            JsonNode st = state.path(SECTION_SCRATCH_CARD);
            boolean revealed = st.path("revealed").asBoolean(false);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", cfg.path("title").asText(""));
            if (!cfg.path("coverLabel").asText("").isEmpty()) value.put("coverLabel", cfg.path("coverLabel").asText(""));
            value.put("threshold", scratchThreshold(cfg));
            value.put("xp", cfg.path("xp").asInt(0));
            value.put("revealed", revealed);
            value.put("passed", st.path("passed").asBoolean(false));
            value.put("finished", st.path("finished").asBoolean(false));
            if (st.has("progress")) value.put("progress", st.path("progress").asDouble(0.0d));
            if (revealed) {
                value.put("prizeText", cfg.path("prizeText").asText(""));
                if (!cfg.path("revealImageUrl").asText("").isEmpty()) {
                    value.put("revealImageUrl", cfg.path("revealImageUrl").asText(""));
                }
            }
            kit.put(SECTION_SCRATCH_CARD, value);
        }
    }

    public static boolean mechanicsReady(JsonNode config, JsonNode state) {
        if (config == null || state == null) return true;
        if (enabled(config.path(SECTION_TRUE_FALSE))
                && !state.path(SECTION_TRUE_FALSE).path("finished").asBoolean(false)) return false;
        if (enabled(config.path(SECTION_IMAGE_GUESS))
                && !state.path(SECTION_IMAGE_GUESS).path("finished").asBoolean(false)) return false;
        if (enabled(config.path(SECTION_JIGSAW))
                && !state.path(SECTION_JIGSAW).path("finished").asBoolean(false)) return false;
        if (enabled(config.path(SECTION_SCRATCH_CARD))
                && !state.path(SECTION_SCRATCH_CARD).path("revealed").asBoolean(false)) return false;
        return true;
    }

    // ==================== 内部工具 ====================

    static List<Integer> initialJigsawOrder(int total) {
        List<Integer> order = new ArrayList<>(total);
        for (int i = 0; i < total; i++) order.add(i);
        Collections.rotate(order, -1);
        if (order.size() >= 4) {
            Collections.swap(order, 0, order.size() - 2);
        }
        return order;
    }

    private static int jigsawDim(JsonNode cfg, String field) {
        int val = cfg.path(field).asInt(DEFAULT_JIGSAW_DIM);
        return Math.max(MIN_JIGSAW_DIM, Math.min(MAX_JIGSAW_DIM, val));
    }

    private static int totalRevealSteps(JsonNode cfg) {
        int clues = cfg.path("revealSteps").isArray() ? cfg.path("revealSteps").size() : 0;
        return Math.max(3, clues + 1);
    }

    private static double scratchThreshold(JsonNode cfg) {
        JsonNode node = cfg.get("threshold");
        if (node == null || !node.isNumber()) return DEFAULT_SCRATCH_THRESHOLD;
        double value = node.asDouble(DEFAULT_SCRATCH_THRESHOLD);
        if (!Double.isFinite(value) || value < MIN_SCRATCH_THRESHOLD || value > MAX_SCRATCH_THRESHOLD) {
            return DEFAULT_SCRATCH_THRESHOLD;
        }
        return value;
    }

    private static boolean matchesAnswer(String guess, JsonNode cfg) {
        String normalizedGuess = normalizeAnswer(guess);
        if (normalizedGuess.isEmpty()) return false;
        if (normalizedGuess.equals(normalizeAnswer(cfg.path("answer").asText("")))) return true;
        for (JsonNode alias : cfg.path("aliases")) {
            if (normalizedGuess.equals(normalizeAnswer(alias.asText("")))) return true;
        }
        return false;
    }

    private static String normalizeAnswer(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean enabled(JsonNode section) {
        return section != null && section.isObject() && section.path("enabled").asBoolean(false);
    }

    private static void requireObject(JsonNode node, String message) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(message);
    }

    private static void requiredText(JsonNode node, String field, int maxLength, String blankMsg, String longMsg) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(blankMsg);
        if (value.length() > maxLength) throw new IllegalArgumentException(longMsg);
    }

    private static void optionalText(JsonNode node, String field, int maxLength, String longMsg) {
        if (node.has(field) && !node.get(field).isNull() && node.path(field).asText("").length() > maxLength) {
            throw new IllegalArgumentException(longMsg);
        }
    }

    private static void requiredMediaUrl(JsonNode node, String field, int maxLength,
            String missingMsg, String longMsg, String invalidMsg) {
        if (!node.has(field) || node.get(field).isNull()) throw new IllegalArgumentException(missingMsg);
        optionalMediaUrl(node, field, maxLength, longMsg, invalidMsg);
    }

    private static void optionalMediaUrl(JsonNode node, String field, int maxLength,
            String longMsg, String invalidMsg) {
        if (!node.has(field) || node.get(field).isNull()) return;
        JsonNode valueNode = node.get(field);
        if (!valueNode.isTextual()) throw new IllegalArgumentException(invalidMsg);
        String value = valueNode.asText().trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(longMsg);
        boolean localPath = value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0;
        if (value.isEmpty() || (!localPath && !value.startsWith("https://") && !value.matches("media-asset:[1-9][0-9]{0,18}"))) {
            throw new IllegalArgumentException(invalidMsg);
        }
    }

    private static void validateXp(JsonNode section, String label) {
        int xp = section.path("xp").asInt(0);
        if (xp < 0 || xp > 1000) {
            throw new IllegalArgumentException(label + "奖励分须为 0 至 1000");
        }
    }

    private static void putText(ObjectNode target, String field, JsonNode source) {
        if (source.has(field) && source.get(field).isTextual() && !source.get(field).asText("").trim().isEmpty()) {
            target.put(field, source.get(field).asText("").trim());
        }
    }

    private static String text(Map<String, Object> payload, String field) {
        Object raw = payload == null ? null : payload.get(field);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static Boolean parseBool(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String trimmed = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(trimmed)) return Boolean.TRUE;
            if ("false".equals(trimmed)) return Boolean.FALSE;
        }
        return null;
    }

    private static Integer parseInt(Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d) && Math.floor(d) == d && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) d);
            }
            return null;
        }
        if (raw instanceof String s) {
            try { return Integer.valueOf(s.trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static Double parseDouble(Object raw) {
        if (raw instanceof Number n) return Double.valueOf(n.doubleValue());
        if (raw instanceof String s) {
            try { return Double.valueOf(s.trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
