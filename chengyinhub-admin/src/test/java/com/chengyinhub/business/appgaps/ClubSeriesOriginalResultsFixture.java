package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 与 createSeries 插入路径同形的系列/场次（CUSTOM_DATES 两期，19:30 集合）。 */
final class ClubSeriesOriginalResultsFixture {
    final ClubEventSeriesRequest request = new ClubEventSeriesRequest();
    final ClubEventSeries series = new ClubEventSeries();
    final List<ClubEventOccurrence> occurrences = new ArrayList<>();
    final List<LocalDate> dates = List.of(LocalDate.of(2026, 12, 5), LocalDate.of(2026, 12, 12));

    ClubSeriesOriginalResultsFixture(long club, long topic, long actor, long seriesId, String requestId) {
        request.setClubId(club); request.setTopicId(topic); request.setRecurrenceType("CUSTOM_DATES");
        request.setStartTime("19:30"); request.setCustomDates(List.of("2026-12-05", "2026-12-12"));
        request.setDefaultLeadMemberId(actor); request.setWaitlistEnabled(true); request.setOfferMinutes(60);
        request.setRequestId(requestId);
        Date now = new Date(1764900000123L);
        series.setId(seriesId); series.setClubId(club); series.setTopicId(topic); series.setRecurrenceType("CUSTOM_DATES");
        series.setTimezone("Asia/Shanghai"); series.setDefaultLeadMemberId(actor); series.setWaitlistEnabled(true);
        series.setHostReservationRequired(false); series.setOfferMinutes(60); series.setStatus("ACTIVE"); series.setVersion(0);
        series.setCreatedByMemberId(actor); series.setRequestId(requestId.trim()); series.setRequestFingerprint("a".repeat(64));
        series.setCreateTime(now); series.setUpdateTime(now);
        long id = seriesId * 10;
        for (LocalDate d : dates) {
            ClubEventOccurrence o = new ClubEventOccurrence();
            o.setId(++id); o.setSeriesId(seriesId); o.setActivityId(id + 1000); o.setDefaultLeadMemberId(actor);
            o.setOccurrenceAt(Date.from(d.atTime(19, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
            o.setStatus("ACTIVE"); o.setVersion(0); o.setCreateTime(now);
            occurrences.add(o);
        }
    }

    /** App 读回时发送的原始字段（ClubSeriesOriginalRequest.fields，12 键）。 */
    Map<String, Object> fields() {
        Map<String, Object> f = new HashMap<>();
        f.put("clubId", request.getClubId()); f.put("topicId", request.getTopicId()); f.put("recurrenceType", "CUSTOM_DATES");
        f.put("startDate", null); f.put("startTime", "19:30"); f.put("occurrenceCount", null);
        f.put("customDates", List.of("2026-12-05", "2026-12-12")); f.put("capacity", null);
        f.put("defaultLeadMemberId", request.getDefaultLeadMemberId()); f.put("waitlistEnabled", true); f.put("offerMinutes", 60);
        f.put("requestId", request.getRequestId());
        return f;
    }
}
