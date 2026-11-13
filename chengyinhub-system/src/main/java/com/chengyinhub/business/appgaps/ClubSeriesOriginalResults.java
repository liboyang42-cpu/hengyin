package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ClubSeriesOriginalResultMapper;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 俱乐部系列创建「原始结果」（App Core/ClubSeriesOriginalResult.swift，club-series-original-result-v1）。
 * <ul>
 *   <li>{@link #record}：series/create 首次插入成功后、同一事务内冻结完整初始结果（原线格式、系列行、全部场次、生效日期）。</li>
 *   <li>{@link #read}：App 用原始字段读回；只认 (club, 发起人, trim(requestId))；原线格式不一致 409；无快照如实 UNKNOWN。</li>
 * </ul>
 * 这是不可变的「首次执行结果」，不代表当前排期或发布状态。
 */
@Service
public class ClubSeriesOriginalResults {
    /** App ClubGovernanceCommand createSeries 的线格式字段（另加三个恒为 null 的 rrule/seriesId/expectedVersion）。 */
    static final List<String> WIRE_KEYS = List.of("clubId", "topicId", "recurrenceType", "startDate", "startTime",
            "occurrenceCount", "customDates", "capacity", "defaultLeadMemberId", "waitlistEnabled", "offerMinutes", "requestId");
    static final ObjectMapper SORTED = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final ClubSeriesOriginalResultMapper mapper;

    public ClubSeriesOriginalResults(ClubSeriesOriginalResultMapper mapper) { this.mapper = mapper; }

    public static Map<String, Object> wire(ClubEventSeriesRequest r) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("clubId", r.getClubId()); w.put("topicId", r.getTopicId()); w.put("recurrenceType", r.getRecurrenceType());
        w.put("startDate", r.getStartDate()); w.put("startTime", r.getStartTime()); w.put("occurrenceCount", r.getOccurrenceCount());
        w.put("customDates", r.getCustomDates()); w.put("capacity", r.getCapacity());
        w.put("defaultLeadMemberId", r.getDefaultLeadMemberId()); w.put("waitlistEnabled", r.getWaitlistEnabled());
        w.put("offerMinutes", r.getOfferMinutes()); w.put("requestId", r.getRequestId());
        w.put("rrule", null); w.put("seriesId", null); w.put("expectedVersion", null);
        return w;
    }

    /** 首次创建同事务调用；任何失败都让整个创建回滚（不允许出现「系列已建、原始结果缺失」）。 */
    public void record(ClubEventSeries series, List<ClubEventOccurrence> occurrences, List<LocalDate> dates,
                       ClubEventSeriesRequest request) {
        Map<String, Object> wire = wire(request);
        long created = series.getCreateTime().getTime();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", series.getId()); s.put("clubId", series.getClubId()); s.put("topicId", series.getTopicId());
        s.put("recurrenceType", series.getRecurrenceType()); s.put("timezone", series.getTimezone());
        s.put("defaultLeadMemberId", series.getDefaultLeadMemberId()); s.put("defaultCapacity", series.getDefaultCapacity());
        s.put("waitlistEnabled", Boolean.TRUE.equals(series.getWaitlistEnabled()));
        s.put("hostReservationRequired", Boolean.TRUE.equals(series.getHostReservationRequired()));
        s.put("offerMinutes", series.getOfferMinutes()); s.put("status", series.getStatus()); s.put("version", series.getVersion());
        s.put("createdByMemberId", series.getCreatedByMemberId()); s.put("requestId", series.getRequestId());
        s.put("requestFingerprint", series.getRequestFingerprint());
        s.put("createdAtEpochMs", created); s.put("updatedAtEpochMs", series.getUpdateTime().getTime());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ClubEventOccurrence o : occurrences) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId()); row.put("seriesId", o.getSeriesId()); row.put("activityId", o.getActivityId());
            row.put("occurrenceAtEpochMs", o.getOccurrenceAt().getTime()); row.put("defaultLeadMemberId", o.getDefaultLeadMemberId());
            row.put("status", o.getStatus()); row.put("version", o.getVersion()); row.put("createdAtEpochMs", o.getCreateTime().getTime());
            rows.add(row);
        }
        List<String> effective = new ArrayList<>();
        for (LocalDate d : dates) effective.add(d.toString());
        Map<String, Object> result = identity(series.getCreatedByMemberId(), series.getClubId(), series.getTopicId(), series.getRequestId());
        result.put("state", "APPLIED"); result.put("wire", wire); result.put("series", s);
        result.put("effectiveDates", effective); result.put("startTime", request.getStartTime()); result.put("occurrences", rows);
        String receiptHash = AppOriginalRequestLedger.sha256Hex(canonical(result));
        result.put("receiptHash", receiptHash);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seriesId", series.getId()); row.put("clubId", series.getClubId()); row.put("topicId", series.getTopicId());
        row.put("actor", series.getCreatedByMemberId()); row.put("requestId", series.getRequestId());
        row.put("wireJson", canonical(wire)); row.put("resultJson", canonical(result)); row.put("receiptHash", receiptHash);
        if (mapper.insert(row) != 1) throw new IllegalStateException("series original result not recorded");
    }

    /** body 为 App 原始字段（ClubSeriesOriginalRequest.fields）。 */
    public AjaxResult read(long actor, Map<String, Object> body) {
        if (body == null || !body.keySet().containsAll(List.of("clubId", "topicId", "requestId"))
                || !WIRE_KEYS.containsAll(body.keySet())) return AjaxResult.error(400, "读回参数无效");
        Long clubId = ClubAdmissionOriginal.positive(body.get("clubId")), topicId = ClubAdmissionOriginal.positive(body.get("topicId"));
        if (clubId == null || topicId == null || !(body.get("requestId") instanceof String raw)) return AjaxResult.error(400, "读回参数无效");
        String requestId = raw.trim();
        if (requestId.isEmpty() || requestId.length() > 64) return AjaxResult.error(400, "requestId 需为 1 至 64 字");
        Map<String, Object> stored = mapper.selectByRequest(clubId, actor, requestId);
        if (stored == null) {
            Map<String, Object> unknown = identity(actor, clubId, topicId, requestId);
            unknown.put("state", "UNKNOWN");
            return AjaxResult.success(unknown);
        }
        Map<String, Object> posted = new LinkedHashMap<>();
        for (String k : WIRE_KEYS) posted.put(k, body.get(k));
        posted.put("rrule", null); posted.put("seriesId", null); posted.put("expectedVersion", null);
        try {
            JsonNode original = SORTED.readTree((String) stored.get("wireJson")), now = SORTED.readTree(canonical(posted));
            if (!original.equals(now) || body.size() != WIRE_KEYS.size())
                return AjaxResult.error(409, "requestId 已用于不同系列配置");
            return AjaxResult.success(SORTED.readValue((String) stored.get("resultJson"), LinkedHashMap.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("series original result unreadable", e);
        }
    }

    private static Map<String, Object> identity(long actor, long clubId, long topicId, String requestId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("version", 1); r.put("actorMemberId", actor); r.put("clubId", clubId); r.put("topicId", topicId); r.put("requestId", requestId);
        return r;
    }

    static String canonical(Object value) {
        try { return SORTED.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("series original result not serializable", e); }
    }
}
