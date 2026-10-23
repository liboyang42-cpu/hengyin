package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S531-3:从申诉补充材料里取出附件引用 {@code media-asset:{id}},给后台审核详情解析成可预览地址。
 *
 * <p>补充材料(evidence_json)并存两种形状:原来的纯补充材料(任意 JSON 对象/数组,或历史上的纯文本),
 * 以及带附件时的 {@code {"evidence":…,"attachments":["media-asset:1",…]}}。只认后一种形状里
 * {@code attachments} 数组中严格匹配 {@code media-asset:数字} 的字符串;其余一律视为没有附件,解析失败不抛。</p>
 */
public final class AppealAttachmentRefs {

    private static final Pattern REF = Pattern.compile("^media-asset:([1-9][0-9]{0,17})$");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 与申诉提交侧上限一致,防止异常数据把详情接口放大成批量签名。 */
    static final int MAX_REFS = 6;

    private AppealAttachmentRefs() {
    }

    /** 申诉 SUBMIT 记录快照:{"appealId":…,"appealOfTaskId":…,"evidence":"<evidence_json 字符串>"}。 */
    public static List<Long> fromSubmitSnapshot(String snapshotJson) {
        JsonNode root = parse(snapshotJson);
        if (root == null || !root.isObject()) return Collections.emptyList();
        JsonNode evidence = root.get("evidence");
        if (evidence == null || evidence.isNull()) return Collections.emptyList();
        return evidence.isTextual() ? fromEvidenceJson(evidence.asText()) : fromEvidenceNode(evidence);
    }

    /** 申诉单 evidence_json:纯补充材料(无附件)或 {"evidence":…,"attachments":[…]}。 */
    public static List<Long> fromEvidenceJson(String evidenceJson) {
        return fromEvidenceNode(parse(evidenceJson));
    }

    private static List<Long> fromEvidenceNode(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("evidence")) return Collections.emptyList();
        JsonNode attachments = node.get("attachments");
        if (attachments == null || !attachments.isArray()) return Collections.emptyList();
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : attachments) {
            if (ids.size() >= MAX_REFS) break;
            if (!item.isTextual()) continue;
            Matcher m = REF.matcher(item.asText());
            if (m.matches()) ids.add(Long.valueOf(m.group(1)));
        }
        return new ArrayList<>(ids);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception notJson) {
            return null;
        }
    }
}
