package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.UsageConsentMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * App「使用同意留痕」只读：GET /api/compliance/usage-records（?beforeId 翻页）与 /usage-records/request?requestId=。
 * 只回本人 app_launch 场景的原始留痕；协议原文按留痕时的 doc_type+version 精确读取，缺失/未发布/不完整如实标注。
 * nativeMarketBinding 恒为 UNPROVEN：留痕表没有记录端（原生/小程序）与市场，不臆断。
 */
@Service
public class UsageConsentRecords {
    private final UsageConsentMapper mapper;

    public UsageConsentRecords(UsageConsentMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> history(long member, String beforeId) {
        Long before = null;
        if (beforeId != null) {
            if (!beforeId.matches("[1-9][0-9]{0,17}")) throw new ServiceException("beforeId 无效", 400);
            before = Long.parseLong(beforeId);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectHistory(member, before)) items.add(record(row));
        Map<String, Object> page = head(member);
        page.put("items", items);
        page.put("nextBeforeId", items.size() == 50 ? items.get(49).get("id") : null);
        return page;
    }

    public Map<String, Object> original(long member, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || requestId.chars().anyMatch(Character::isISOControl))
            throw new ServiceException("requestId 无效", 400);
        Map<String, Object> row = mapper.selectByRequest(member, requestId);
        if (row == null || row.isEmpty()) throw new ServiceException("未找到该留痕", 404);
        Map<String, Object> record = record(row);
        Map<String, Object> out = head(member);
        out.put("record", record);
        out.put("latestRecordId", mapper.selectLatestId(member, (String) record.get("docType")));
        out.put("document", document((String) record.get("docType"), (String) record.get("docVersion"),
                mapper.selectDocument((String) record.get("docType"), (String) record.get("docVersion"))));
        return out;
    }

    static Map<String, Object> document(String docType, String version, Map<String, Object> doc) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("docType", docType); d.put("version", version);
        String availability;
        if (doc == null) availability = "MISSING";
        else if (!(doc.get("status") instanceof Number s) || s.intValue() != 1) availability = "UNPUBLISHED";
        else if (blank(doc.get("title")) || blank(doc.get("content")) || millis(doc.get("publishTime")) == null) availability = "INCOMPLETE";
        else availability = "SOURCE_AVAILABLE";
        d.put("availability", availability);
        boolean available = "SOURCE_AVAILABLE".equals(availability);
        d.put("title", available ? doc.get("title") : null);
        d.put("content", available ? doc.get("content") : null);
        d.put("publishedAt", available ? millis(doc.get("publishTime")) : null);
        return d;
    }

    static Map<String, Object> head(long member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", member); m.put("scene", "app_launch"); m.put("readOnly", true); m.put("nativeMarketBinding", "UNPROVEN");
        return m;
    }

    static Map<String, Object> record(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", ((Number) row.get("id")).longValue()); r.put("docType", row.get("docType")); r.put("docVersion", row.get("docVersion"));
        r.put("eventType", row.get("eventType")); r.put("requestId", row.get("requestId")); r.put("occurredAt", millis(row.get("occurredAt")));
        return r;
    }

    static boolean blank(Object o) { return o == null || String.valueOf(o).isBlank(); }

    static Long millis(Object o) {
        if (o instanceof Date d) return d.getTime();
        if (o instanceof java.time.LocalDateTime t) return t.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        return null;
    }
}
