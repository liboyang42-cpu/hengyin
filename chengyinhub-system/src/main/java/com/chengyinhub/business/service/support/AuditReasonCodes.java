package com.chengyinhub.business.service.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审核决定原因编码（固定清单）的单一真源。
 *
 * <p>审核员调用 review() 时可选传 reasonCodes[]，与原有自由文本 reasonText 并存；编码落 audit_record.reason_codes_json，
 * 作者时间线(/api/audit/timeline)与驳回通知按下表渲染中文标签。清单外编码一律 400。</p>
 *
 * <p>与既有 audit_record.reason_code 对齐：举报记录存的是 {@link CommunityReportPolicy} 编码，
 * 其中 FALSE_INFORMATION / INTELLECTUAL_PROPERTY / MINOR_SAFETY / OTHER 与本清单同名同义，标签也取同一文案；
 * 产品稿里的 FALSE_INFO、IP_INFRINGEMENT 作为别名接受，归一成上述规范编码再落库。</p>
 *
 * <pre>
 * TEXT_RISK              文字内容违规
 * IMAGE_RISK             图片内容违规
 * FALSE_INFORMATION      虚假信息            （别名 FALSE_INFO）
 * PRICE_ISSUE            价格信息有误
 * LOCATION_ISSUE         地点信息有误
 * INTELLECTUAL_PROPERTY  知识产权或内容盗用  （别名 IP_INFRINGEMENT）
 * MINOR_SAFETY           未成年人安全
 * OTHER                  其他
 * </pre>
 */
public final class AuditReasonCodes {
    private static final Map<String, String> LABELS;
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("TEXT_RISK", "文字内容违规");
        labels.put("IMAGE_RISK", "图片内容违规");
        labels.put("FALSE_INFORMATION", "虚假信息");
        labels.put("PRICE_ISSUE", "价格信息有误");
        labels.put("LOCATION_ISSUE", "地点信息有误");
        labels.put("INTELLECTUAL_PROPERTY", "知识产权或内容盗用");
        labels.put("MINOR_SAFETY", "未成年人安全");
        labels.put("OTHER", "其他");
        LABELS = Collections.unmodifiableMap(labels);
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("FALSE_INFO", "FALSE_INFORMATION");
        aliases.put("IP_INFRINGEMENT", "INTELLECTUAL_PROPERTY");
        ALIASES = Collections.unmodifiableMap(aliases);
    }

    private AuditReasonCodes() {
    }

    /** 规范编码，按展示顺序。 */
    public static Set<String> codes() {
        return LABELS.keySet();
    }

    /** 归一并校验：去空白、转大写、别名转规范、去重保序；清单外编码抛 IllegalArgumentException(→400)。 */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        Set<String> result = new LinkedHashSet<>();
        for (String item : raw) {
            String code = item == null ? "" : item.trim().toUpperCase(java.util.Locale.ROOT);
            code = ALIASES.getOrDefault(code, code);
            if (!LABELS.containsKey(code)) {
                throw new IllegalArgumentException("未知的审核原因编码：" + (item == null ? "null" : item.trim()));
            }
            result.add(code);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /** 已落库编码 → 中文标签；历史/未知编码原样跳过，不补造文案。 */
    public static List<String> labels(List<String> codes) {
        List<String> result = new ArrayList<>();
        if (codes == null) return result;
        for (String code : codes) {
            String label = code == null ? null : LABELS.get(code);
            if (label != null) result.add(label);
        }
        return result;
    }

    public static String toJson(List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(codes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("审核原因编码序列化失败", e);
        }
    }

    public static List<String> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node == null || !node.isArray()) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                if (item.isTextual()) result.add(item.asText());
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
