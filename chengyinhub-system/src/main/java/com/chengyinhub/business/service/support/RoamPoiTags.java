package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * roam_poi.tags / gallery_json 的统一格式工具(A5.5)。
 * tags 一律是 JSON 数组串,例如 ["roam:park","roam:seed:v1:sh-luxun-park","公园"];
 * 历史上商家资料同步写的是逗号分隔,这里统一转成 JSON 数组。
 */
public final class RoamPoiTags {
    public static final int MAX_LENGTH = 255;
    public static final int GALLERY_PREVIEW = 3;

    private RoamPoiTags() {}

    /** 任意输入(JSON 数组串 / 逗号、顿号、分号、空白分隔)→ JSON 数组串;空输入返回 null。超长时丢弃末尾标签直到 ≤255。 */
    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        List<String> parts = parse(raw.trim());
        while (!parts.isEmpty() && JSON.toJSONString(parts).length() > MAX_LENGTH) parts.remove(parts.size() - 1);
        return parts.isEmpty() ? null : JSON.toJSONString(parts);
    }

    private static List<String> parse(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text.startsWith("[")) {
            try {
                for (Object o : JSON.parseArray(text)) add(out, o == null ? null : String.valueOf(o));
                return new ArrayList<>(out);
            } catch (RuntimeException ignore) {
                // 不是合法 JSON 数组,按分隔符处理
            }
        }
        for (String piece : text.split("[,，、;；\\s]+")) add(out, piece);
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, String piece) {
        if (piece == null) return;
        String t = piece.replace("\"", "").trim();
        if (!t.isEmpty() && t.length() <= 32) out.add(t);
    }

    /** gallery_json 只取前 n 张(JSON 数组串);非法/空输入返回 null,不抛。 */
    public static String galleryHead(String galleryJson, int n) {
        if (galleryJson == null || galleryJson.trim().isEmpty()) return null;
        try {
            JSONArray arr = JSON.parseArray(galleryJson);
            if (arr == null || arr.isEmpty()) return null;
            if (arr.size() <= n) return arr.toJSONString();
            return new JSONArray(arr.subList(0, n)).toJSONString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
