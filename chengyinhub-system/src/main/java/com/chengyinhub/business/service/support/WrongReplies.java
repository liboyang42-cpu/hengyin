package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * F02 按错答回复(解谜赛「中间答案表」):打字类玩法段上的可选 {@code wrongReplies}。
 *
 * <pre>{@code "wrongReplies":[{"match":["3","三"],"reply":"数对了一半","images":[...]}]}</pre>
 *
 * <p>只在服务端:配置留在 config_snapshot,玩家只收到命中那一条的 reply。匹配沿用该玩法
 * 现有的归一方法(调用方传入),不做模糊/拼音/简繁。命中只换一段文字:次数、maxTries、
 * 得分、奖励都不变。
 */
public final class WrongReplies {

    public static final String FIELD = "wrongReplies";
    public static final int MAX_ROWS = 20;
    public static final int MAX_MATCHES = 10;
    public static final int MAX_MATCH_LENGTH = 200;
    public static final int MAX_REPLY_LENGTH = 500;
    /** 本局状态:命中第几条(0 起);没命中不写。 */
    public static final String STATE_INDEX = "lastNearMissIndex";
    public static final String STATE_NEAR_MISS = "lastNearMiss";

    private WrongReplies() {
    }

    /** 比较器:作者写法与玩家输入,按该玩法自己的归一口径判断是否同一写法。 */
    public interface Matcher {
        boolean same(String authored, String submitted);
    }

    /**
     * 保存闸。{@code acceptedAsCorrect} 判断一个写法会不会被该玩法判成答对(正确答案或别名):
     * 会就拒 —— 否则正确答案会被吞成错答。
     */
    public static void validate(JsonNode section, String label, Predicate<String> acceptedAsCorrect, Matcher matcher) {
        if (section == null || !section.isObject()) return;
        JsonNode rows = section.get(FIELD);
        if (rows == null || rows.isNull()) return;
        if (!rows.isArray()) throw new IllegalArgumentException(label + "错答回复必须是数组");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException(label + "错答回复最多 " + MAX_ROWS + " 条");
        Set<String> seen = new HashSet<>();
        java.util.List<String> all = new java.util.ArrayList<>();
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) throw new IllegalArgumentException(label + "错答回复格式不正确");
            var names = row.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!"match".equals(name) && !"reply".equals(name) && !"images".equals(name)) {
                    throw new IllegalArgumentException(label + "错答回复不支持的字段：" + name);
                }
            }
            JsonNode match = row.get("match");
            if (match == null || !match.isArray() || match.size() == 0 || match.size() > MAX_MATCHES) {
                throw new IllegalArgumentException(label + "每条错答回复须有 1 至 " + MAX_MATCHES + " 个写法");
            }
            for (JsonNode one : match) {
                if (!one.isTextual() || one.asText().trim().isEmpty()) {
                    throw new IllegalArgumentException(label + "错答写法不能为空");
                }
                String text = one.asText().trim();
                if (text.length() > MAX_MATCH_LENGTH) {
                    throw new IllegalArgumentException(label + "错答写法不能超过 " + MAX_MATCH_LENGTH + " 字");
                }
                if (acceptedAsCorrect != null && acceptedAsCorrect.test(text)) {
                    throw new IllegalArgumentException(label + "错答写法与正确答案或别名重叠：" + text);
                }
                for (String previous : all) {
                    if (matcher.same(previous, text) || matcher.same(text, previous)) {
                        throw new IllegalArgumentException(label + "同一错答写法不能出现两次：" + text);
                    }
                }
                if (!seen.add(text)) throw new IllegalArgumentException(label + "同一错答写法不能出现两次：" + text);
                all.add(text);
            }
            JsonNode reply = row.get("reply");
            if (reply == null || !reply.isTextual() || reply.asText().trim().isEmpty()) {
                throw new IllegalArgumentException(label + "错答回复内容不能为空");
            }
            if (reply.asText().length() > MAX_REPLY_LENGTH) {
                throw new IllegalArgumentException(label + "错答回复不能超过 " + MAX_REPLY_LENGTH + " 字");
            }
            validateImages(row.get("images"), label);
        }
    }

    /** 命中第几条(0 起);没命中 -1。按作者书写顺序,先命中先得。 */
    public static int match(JsonNode section, List<String> submitted, Matcher matcher) {
        if (section == null || submitted == null) return -1;
        JsonNode rows = section.path(FIELD);
        if (!rows.isArray()) return -1;
        for (int i = 0; i < rows.size(); i++) {
            for (JsonNode one : rows.get(i).path("match")) {
                if (!one.isTextual()) continue;
                for (String input : submitted) {
                    if (input != null && !input.trim().isEmpty() && matcher.same(one.asText().trim(), input)) return i;
                }
            }
        }
        return -1;
    }

    /**
     * 把本次判错的回复写进本局状态:命中写那一条的 reply/images,没命中写通用错答
     * ({@code outcomeRules.fail.reply},没配就清空,由客户端兜底)。
     */
    public static void record(JsonNode config, String kit, ObjectNode kitState, int index) {
        JsonNode section = config == null ? null : config.path(kit);
        if (index >= 0 && section != null) {
            JsonNode row = section.path(FIELD).get(index);
            kitState.put("lastFeedback", row.path("reply").asText(""));
            ArrayNode images = kitState.putArray("lastFeedbackImages");
            for (JsonNode image : row.path("images")) if (image.isTextual()) images.add(image.asText().trim());
            kitState.put(STATE_NEAR_MISS, true);
            kitState.put(STATE_INDEX, index);
            return;
        }
        String generic = config == null ? "" : config.path("outcomeRules").path("fail").path("reply").asText("");
        kitState.put("lastFeedback", generic);
        ArrayNode images = kitState.putArray("lastFeedbackImages");
        if (config != null) {
            for (JsonNode image : config.path("outcomeRules").path("fail").path("images")) {
                if (image.isTextual()) images.add(image.asText().trim());
            }
        }
        kitState.put(STATE_NEAR_MISS, false);
        kitState.remove(STATE_INDEX);
    }

    /** 答对后清掉上一次错答的痕迹,免得重进时还亮着旧回复。 */
    public static void clear(ObjectNode kitState) {
        if (kitState == null) return;
        if (kitState.has(STATE_NEAR_MISS)) {
            kitState.remove(STATE_NEAR_MISS);
            kitState.remove(STATE_INDEX);
        }
    }

    /** 结算 FAIL 时换成命中那一条的话;费用/效果仍按 fail 原样。没命中返回 null。 */
    public static JsonNode replyRow(JsonNode block, String kit, JsonNode index) {
        if (block == null || kit == null || index == null || !index.isIntegralNumber()) return null;
        JsonNode row = block.path(kit).path(FIELD).path(index.asInt());
        return row.isObject() && row.path("reply").isTextual() ? row : null;
    }

    private static void validateImages(JsonNode images, String label) {
        if (images == null || images.isNull()) return;
        if (!images.isArray() || images.size() > 3) throw new IllegalArgumentException(label + "错答回复图片最多 3 张");
        for (JsonNode image : images) {
            String value = image.isTextual() ? image.asText().trim() : "";
            boolean localPath = value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0;
            if (value.isEmpty() || value.length() > 512 || (!localPath && !value.startsWith("https://")
                    && !value.matches("media-asset:[1-9][0-9]{0,18}"))) {
                throw new IllegalArgumentException(label + "错答回复图片地址必须是 https 链接或站内路径");
            }
        }
    }
}
