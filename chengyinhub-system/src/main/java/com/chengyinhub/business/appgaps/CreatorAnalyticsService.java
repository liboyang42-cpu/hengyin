package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.domain.vo.PostGameOutcomeReportVO;
import com.chengyinhub.business.service.IPostGameOutcomeReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * U08-04 app-callable re-registration board for creators: the B8 post-game-outcome report scoped to the caller's own
 * topics ({@code cms_topic.member_id}, not deleted). Same maturity/watermark semantics as
 * {@code GET /business/analytics/post-game-outcome}; the response states the scope and the metrics left out because
 * they cannot be attributed to a topic. Read-only, aggregates only, no member identities, no money.
 */
@Service
public class CreatorAnalyticsService {
    static final int DEFAULT_WINDOW_DAYS = 30, MAX_TOPICS = 1000;

    private final JdbcTemplate jdbc;
    private final IPostGameOutcomeReportService reports;

    public CreatorAnalyticsService(JdbcTemplate jdbc, IPostGameOutcomeReportService reports) {
        this.jdbc = jdbc;
        this.reports = reports;
    }

    public Map<String, Object> postGameOutcome(Long viewer, String cohortDate, String windowDays, String topicId) {
        if (viewer == null || viewer <= 0) throw AppGapException.unauthorized();
        LocalDate cohort = date(cohortDate);
        int window = window(windowDays);
        List<Long> topics;
        if (topicId != null && !topicId.isEmpty()) {
            if (!topicId.matches("[1-9][0-9]{0,18}")) throw AppGapException.badRequest("topicId");
            topics = jdbc.queryForList("SELECT id FROM cms_topic WHERE id = ? AND member_id = ? AND del_flag <> 2", Long.class,
                    Long.parseLong(topicId), viewer);
            if (topics.isEmpty()) throw AppGapException.notFound("TOPIC_NOT_FOUND");
        } else {
            topics = jdbc.queryForList("SELECT id FROM cms_topic WHERE member_id = ? AND del_flag <> 2 ORDER BY id LIMIT " + (MAX_TOPICS + 1),
                    Long.class, viewer);
            if (topics.isEmpty()) throw new AppGapException(404, "CREATOR_TOPICS_NOT_FOUND", "还没有自己的主题");
            if (topics.size() > MAX_TOPICS) throw new AppGapException(400, "TOO_MANY_TOPICS", "主题过多，请按 topicId 查看");
        }
        final PostGameOutcomeReportVO report;
        try {
            report = reports.generateForTopics(cohort, window, topics);
        } catch (IllegalArgumentException invalid) {
            throw new AppGapException(400, "BAD_REQUEST", invalid.getMessage());
        } catch (IllegalStateException unavailable) {
            // A source that cannot be read is an error, never a 0.
            throw new AppGapException(503, "REPORT_SOURCE_UNAVAILABLE", unavailable.getMessage());
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("kind", "CREATOR_TOPICS");
        scope.put("topicIds", topics);
        scope.put("cohortRule", "FIRST_VALID_REGISTRATION_ON_SCOPE_TOPICS");
        scope.put("omittedMetrics", IPostGameOutcomeReportService.TOPIC_SCOPE_OMITTED_METRICS);
        scope.put("omittedReason", "NOT_ATTRIBUTABLE_TO_TOPIC");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "creator-post-game-outcome-v1");
        out.put("scope", scope);
        out.put("report", report);
        return out;
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.length() != 10) throw new AppGapException(400, "BAD_REQUEST", "日期格式必须为 yyyy-MM-dd");
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new AppGapException(400, "BAD_REQUEST", "日期格式必须为 yyyy-MM-dd");
        }
    }

    private static int window(String raw) {
        if (raw == null || raw.isEmpty()) return DEFAULT_WINDOW_DAYS;
        if (!raw.matches("[1-9][0-9]{0,2}")) throw AppGapException.badRequest("windowDays");
        return Integer.parseInt(raw);
    }
}
