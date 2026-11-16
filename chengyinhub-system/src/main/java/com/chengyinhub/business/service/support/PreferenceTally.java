package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * x-template-games R1:偏好题组(validation_method=6)给模板主人看的结果汇总。
 *
 * <p>新品盲测局这类题没有对错,店家要的是「大家选了什么」。这里只给每个结果的人数和占比,
 * 不给玩家身份。回答少于 {@link #MIN_RESPONSES} 时只报总数、不报分布 —— 一家小店里
 * 两三个人的选择,按人数就能认出是谁。
 *
 * <p>结果的顺序与名字取模板 preferenceJson 的 dimensions / results.title(作者写的),
 * 没人选过的结果也列出来(人数 0),作者能看出哪一项没人选。配置里已没有的历史结果码
 * 归到「其他」。
 */
public final class PreferenceTally
{
    public static final int MIN_RESPONSES = 3;
    private static final ObjectMapper JSON = new ObjectMapper();

    private PreferenceTally() {}

    public static Map<String, Object> summarize(String preferenceJson, List<Map<String, Object>> rows)
    {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        long total = 0L;
        if (rows != null) for (Map<String, Object> row : rows) {
            if (row == null) continue;
            Object code = row.get("resultCode");
            Object n = row.get("responses");
            long value = n instanceof Number ? ((Number) n).longValue() : 0L;
            if (value <= 0L) continue;
            String key = code == null ? "" : String.valueOf(code);
            counts.merge(key, value, Long::sum);
            total += value;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("minResponses", MIN_RESPONSES);
        boolean enough = total >= MIN_RESPONSES;
        out.put("enoughResponses", enough);
        if (!enough) {
            out.put("results", new ArrayList<Object>());
            return out;
        }
        JsonNode config = parse(preferenceJson);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (JsonNode dimension : config.path("dimensions")) {
            String code = dimension.asText("");
            if (code.isEmpty()) continue;
            String title = config.path("results").path(code).path("title").asText(code);
            results.add(row(code, title, counts.remove(code), total));
        }
        Long notApplicable = counts.remove("not_applicable");
        if (notApplicable != null) {
            results.add(row("not_applicable",
                    config.path("notApplicableResult").path("title").asText("不适用"), notApplicable, total));
        }
        long other = 0L;
        for (Long value : counts.values()) other += value;
        if (other > 0L) results.add(row("other", "其他", other, total));
        out.put("results", results);
        return out;
    }

    private static Map<String, Object> row(String code, String title, Long count, long total)
    {
        long n = count == null ? 0L : count;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("resultCode", code);
        row.put("title", title);
        row.put("responses", n);
        row.put("percent", total <= 0L ? 0 : Math.round(n * 100.0d / total));
        return row;
    }

    private static JsonNode parse(String raw)
    {
        if (raw == null || raw.trim().isEmpty()) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(raw);
            return node == null ? JSON.createObjectNode() : node;
        } catch (Exception unusable) {
            return JSON.createObjectNode();
        }
    }
}
