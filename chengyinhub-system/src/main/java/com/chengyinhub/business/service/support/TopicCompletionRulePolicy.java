package com.chengyinhub.business.service.support;

import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 主题节点通关规则：默认全完成，可配置完成 N 个即通关。 */
public class TopicCompletionRulePolicy {

    private static final Logger logger = LoggerFactory.getLogger(TopicCompletionRulePolicy.class);

    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final int BINGO_CELLS = 9;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String validateAndNormalize(String rawJson, int totalNodes, Integer productType) {
        if (StringUtils.isBlank(rawJson)) return null;
        if (rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("主题通关规则不能超过 16 KiB");
        }
        ObjectNode root = parseObject(rawJson);
        JsonNode nodeRule = root.get("nodeCompletion");
        if (nodeRule != null && !nodeRule.isNull()) {
            if (!nodeRule.isObject()) throw new IllegalArgumentException("节点通关规则格式不正确");
            String mode = nodeRule.path("mode").asText("ALL");
            if (!"ALL".equals(mode) && !"AT_LEAST".equals(mode)) {
                throw new IllegalArgumentException("节点通关模式不支持");
            }
            if (ProductType.FREE_EXPLORE == (productType == null ? -1 : productType)
                    && "AT_LEAST".equals(mode)) {
                throw new IllegalArgumentException("自由探索按商家权益核销通关，不能改成节点 N/M 规则");
            }
            if ("AT_LEAST".equals(mode)) {
                int required = nodeRule.path("requiredCount").asInt(0);
                if (required < 1) throw new IllegalArgumentException("通关节点数至少为 1");
                if (totalNodes < 1 || required > totalNodes) {
                    throw new IllegalArgumentException("通关节点数不能超过已配置节点总数");
                }
            }
        }
        validateBingo(root.get("bingo"));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("主题通关规则无法序列化");
        }
    }

    /**
     * 宾果九格的**名称与奖励**。
     *
     * <p>★ 九格的**点亮条件是内置的、跨节点的**(完成 N 个点 / 连续两天来 / 周末来一次……),
     * 商家一个字都不用填 —— 所以这份配置里没有 nodeId:绑节点会让模板只能在那个主题用,
     * 换个主题就得重排。这里只让商家改「这一格叫什么」和「填上给什么」,
     * 于是同一份配置任何主题都能直接用。
     *
     * <p>发得出去的只有券和文字反馈。勋章不收 —— 勋章随节点列表提前下发给前端播动画,
     * 而节点列表是玩家开玩前就取的,那时还没有任何格子被填上,「按结果发勋章」
     * 在现有链路里根本不存在(同 RewardRulesValidator 的口径)。
     */
    private void validateBingo(JsonNode bingo) {
        if (bingo == null || bingo.isNull()) return;
        if (!bingo.isObject()) throw new IllegalArgumentException("宾果九宫格配置格式不正确");
        if (!bingo.path("enabled").asBoolean(false)) return;

        // 整段棋盘上的三句文案:标题给九格顶上看,两档奖给连成线 / 九格全亮报兑现(运行期原样下发给玩家)
        if (bingo.path("title").asText("").length() > 24) {
            throw new IllegalArgumentException("宾果九宫格标题不能超过 24 字");
        }
        for (String reward : new String[] { "lineReward", "fullReward" }) {
            if (bingo.path(reward).asText("").length() > 120) {
                throw new IllegalArgumentException("宾果九宫格的奖励文案不能超过 120 字");
            }
        }

        JsonNode cells = bingo.get("cells");
        if (cells == null || !cells.isArray() || cells.size() != BINGO_CELLS) {
            throw new IllegalArgumentException("宾果九宫格要正好配 " + BINGO_CELLS + " 格");
        }
        for (JsonNode cell : cells) {
            if (!cell.isObject()) throw new IllegalArgumentException("宾果格子格式不正确");
            String label = cell.path("label").asText("");
            if (label.length() > 24) throw new IllegalArgumentException("宾果格子名称不能超过 24 字");
            String feedback = cell.path("feedbackText").asText("");
            if (feedback.length() > 120) throw new IllegalArgumentException("宾果格子奖励文案不能超过 120 字");
            if (cell.has("medalName") || cell.has("medalImg")) {
                throw new IllegalArgumentException("宾果格子奖励不支持勋章,勋章请配在主题的「完成奖励」里");
            }
            long couponId = cell.path("couponId").asLong(0L);
            if (couponId < 0) throw new IllegalArgumentException("宾果格子的券不合法");
        }
    }

    /** 九格各自的名称;没配就返回空表,调用方回落到内置文案。 */
    public List<String> bingoCellLabels(String rawJson) {
        List<String> labels = new ArrayList<>();
        JsonNode cells = bingoCells(rawJson);
        if (cells == null) return labels;
        for (JsonNode cell : cells) labels.add(cell.path("label").asText(""));
        return labels;
    }

    /** 九宫格这一段开没开。读不动 / 没配 / 没开一律 false —— 与 {@link #bingoCells} 同一个口径。 */
    public boolean bingoEnabled(String rawJson) {
        return bingoNode(rawJson) != null;
    }

    /**
     * 九宫格上商家配的整段文案({@code title} / {@code lineReward} / {@code fullReward})。
     *
     * <p>这三样是**这一段棋盘**的,不是某一格的:标题给九格顶上看,两档奖给连成线、
     * 九格全亮那两下报兑现。没配一律空串,由前端按"没有这一行"处理 ——
     * 拿一句平台文案顶上去等于替商家承诺了他没承诺的东西。
     */
    public String bingoText(String rawJson, String field) {
        JsonNode bingo = bingoNode(rawJson);
        return bingo == null ? "" : bingo.path(field).asText("");
    }

    /** 第 position 格配的券;没配返回 0。 */
    public long bingoCellCouponId(String rawJson, int position) {
        JsonNode cells = bingoCells(rawJson);
        if (cells == null || position < 0 || position >= cells.size()) return 0L;
        return Math.max(0L, cells.get(position).path("couponId").asLong(0L));
    }

    /** 第 position 格配的文案;没配返回空串。 */
    public String bingoCellFeedback(String rawJson, int position) {
        JsonNode cells = bingoCells(rawJson);
        if (cells == null || position < 0 || position >= cells.size()) return "";
        return cells.get(position).path("feedbackText").asText("");
    }

    /** 九格里配的所有券 id(去掉 0),给写入口逐张校验归属用。 */
    public List<Long> bingoCouponIds(String rawJson) {
        List<Long> ids = new ArrayList<>();
        JsonNode cells = bingoCells(rawJson);
        if (cells == null) return ids;
        for (JsonNode cell : cells) {
            long couponId = cell.path("couponId").asLong(0L);
            if (couponId > 0) ids.add(couponId);
        }
        return ids;
    }

    /** 读不动 / 没开 / 格数不对一律返回 null,调用方回落到内置九格。 */
    private JsonNode bingoCells(String rawJson) {
        JsonNode bingo = bingoNode(rawJson);
        if (bingo == null) return null;
        JsonNode cells = bingo.get("cells");
        return (cells != null && cells.isArray() && cells.size() == BINGO_CELLS) ? cells : null;
    }

    /** 已开启的那一段 bingo;没配、没开、读不动一律 null。 */
    private JsonNode bingoNode(String rawJson) {
        if (StringUtils.isBlank(rawJson)) return null;
        JsonNode bingo;
        try {
            bingo = parseObject(rawJson).get("bingo");
        } catch (RuntimeException e) {
            /* 解析不了就回落到内置九格。写入口有 validateBingo 把关,正常存不进脏数据;
               但真存进去了,商家配的券会静默不发 —— 所以这里必须留一行,
               否则「奖励没发」这件事在日志里一点痕迹都没有。 */
            logger.warn("九宫格配置解析失败,回落内置格子:{}", e.getMessage());
            return null;
        }
        return (bingo != null && bingo.isObject() && bingo.path("enabled").asBoolean(false)) ? bingo : null;
    }

    public int requiredCount(String rawJson, int totalNodes) {
        if (totalNodes <= 0) return 0;
        if (StringUtils.isBlank(rawJson)) return totalNodes;
        ObjectNode root = parseObject(rawJson);
        JsonNode rule = root.get("nodeCompletion");
        if (rule == null || !rule.isObject() || !"AT_LEAST".equals(rule.path("mode").asText())) {
            return totalNodes;
        }
        int required = rule.path("requiredCount").asInt(totalNodes);
        return required < 1 || required > totalNodes ? totalNodes : required;
    }

    public boolean isCompleted(String rawJson, int totalNodes, int completedNodes) {
        int required = requiredCount(rawJson, totalNodes);
        return required > 0 && completedNodes >= required;
    }

    private ObjectNode parseObject(String rawJson) {
        try {
            JsonNode parsed = objectMapper.readTree(rawJson);
            if (!parsed.isObject()) throw new IllegalArgumentException("主题通关规则必须是对象");
            return (ObjectNode) parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("主题通关规则不是合法 JSON");
        }
    }
}
