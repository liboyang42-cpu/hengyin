package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.citygame.analytics.value.CityValuePermissions;
import com.chengyinhub.business.citygame.analytics.value.ValueAnalyticsService;
import com.chengyinhub.business.citygame.analytics.value.ValueContracts;
import com.chengyinhub.business.citygame.analytics.value.ValueOutcome;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * CITY 价值统计的只读入口。权限默认不授予；查询参数不能指定操作者或塞入样例。
 */
@RestController
@RequestMapping("/business/city-value")
public class CityValueReadController extends BaseController {
    private static final Set<String> REPORT_KEYS = Set.of(
            "boardId", "budgetCurrency", "effectiveChoices", "from", "limit", "meaningfulActions", "pointsUnit",
            "priorFrom", "priorTo", "ratingMax", "ratingMin", "regionId", "requestId", "rulesReleaseId", "seasonId",
            "to", "veteranMinPriorServiceSuccess");
    private static final Set<String> EVENT_KEYS = Set.of(
            "boardId", "cursor", "from", "limit", "regionId", "rulesReleaseId", "seasonId", "to");
    private static final Set<String> RESULT_KEYS = Set.of("regionId", "rulesReleaseId");
    private static final Set<String> REQUEST_KEYS = Set.of("intent");

    private final ValueAnalyticsService reports;

    public CityValueReadController(ValueAnalyticsService reports) {
        this.reports = reports;
    }

    @PreAuthorize("@ss.hasPermi('" + CityValuePermissions.REPORT_QUERY + "')")
    @GetMapping("/report")
    public ResponseEntity<AjaxResult> report(HttpServletRequest request) {
        Long actor = actor();
        if (actor == null) {
            return respond(ValueOutcome.fail(401, "UNAUTHENTICATED", "未登录", envelope("UNAUTHENTICATED")));
        }
        try {
            rejectUnknown(request, REPORT_KEYS);
            return respond(reports.query(actor, reportQuery(request)));
        } catch (ParseFailure ex) {
            return respond(ValueOutcome.fail(400, ex.code, "参数无效", envelope(ex.code)));
        }
    }

    @PreAuthorize("@ss.hasPermi('" + CityValuePermissions.REPORT_QUERY + "')")
    @GetMapping("/events")
    public ResponseEntity<AjaxResult> events(HttpServletRequest request) {
        Long actor = actor();
        if (actor == null) {
            return respond(ValueOutcome.fail(401, "UNAUTHENTICATED", "未登录", envelope("UNAUTHENTICATED")));
        }
        try {
            rejectUnknown(request, EVENT_KEYS);
            return respond(reports.page(actor, page(request)));
        } catch (ParseFailure ex) {
            Map<String, Object> data = envelope(ex.code);
            data.put("retryCursor", request.getParameter("cursor") == null ? "" : request.getParameter("cursor"));
            return respond(ValueOutcome.fail(400, ex.code, "参数无效", data));
        }
    }

    @PreAuthorize("@ss.hasPermi('" + CityValuePermissions.REPORT_QUERY + "')")
    @GetMapping("/result/{id}")
    public ResponseEntity<AjaxResult> result(@PathVariable("id") long id, HttpServletRequest request) {
        Long actor = actor();
        if (actor == null) {
            return respond(ValueOutcome.fail(401, "UNAUTHENTICATED", "未登录", envelope("UNAUTHENTICATED")));
        }
        try {
            rejectUnknown(request, RESULT_KEYS);
            return respond(reports.readResult(actor, id, request.getParameter("regionId"), request.getParameter("rulesReleaseId")));
        } catch (ParseFailure ex) {
            return respond(ValueOutcome.fail(400, ex.code, "参数无效", envelope(ex.code)));
        }
    }

    @PreAuthorize("@ss.hasPermi('" + CityValuePermissions.REPORT_QUERY + "')")
    @GetMapping("/request/{requestId}")
    public ResponseEntity<AjaxResult> request(@PathVariable("requestId") String requestId, HttpServletRequest request) {
        Long actor = actor();
        if (actor == null) {
            return respond(ValueOutcome.fail(401, "UNAUTHENTICATED", "未登录", envelope("UNAUTHENTICATED")));
        }
        try {
            rejectUnknown(request, REQUEST_KEYS);
            String intent = request.getParameter("intent");
            if (intent == null || intent.isEmpty()) {
                return respond(reports.readRequest(actor, requestId));
            }
            if (!"cancel".equals(intent)) {
                throw new ParseFailure("MISSING_PARAM");
            }
            return respond(reports.cancel(actor, requestId));
        } catch (ParseFailure ex) {
            return respond(ValueOutcome.fail(400, ex.code, "参数无效", envelope(ex.code)));
        }
    }

    private static ValueContracts.Query reportQuery(HttpServletRequest request) {
        ValueContracts.Query query = new ValueContracts.Query();
        query.requestId = request.getParameter("requestId");
        query.regionId = request.getParameter("regionId");
        query.boardId = request.getParameter("boardId");
        query.seasonId = request.getParameter("seasonId");
        query.rulesReleaseId = request.getParameter("rulesReleaseId");
        query.from = instant(request, "from");
        query.to = instant(request, "to");
        query.priorFrom = instant(request, "priorFrom");
        query.priorTo = instant(request, "priorTo");
        Integer limit = integer(request, "limit");
        query.limit = limit == null ? 0 : limit;
        ValueContracts.Definition definition = new ValueContracts.Definition();
        definition.meaningfulActions = codes(request, "meaningfulActions");
        definition.effectiveChoices = codes(request, "effectiveChoices");
        definition.ratingMin = integer(request, "ratingMin");
        definition.ratingMax = integer(request, "ratingMax");
        definition.veteranMinPriorServiceSuccess = integer(request, "veteranMinPriorServiceSuccess");
        definition.budgetCurrency = request.getParameter("budgetCurrency");
        definition.pointsUnit = request.getParameter("pointsUnit");
        query.definition = definition;
        return query;
    }

    private static ValueContracts.Page page(HttpServletRequest request) {
        ValueContracts.Page page = new ValueContracts.Page();
        page.regionId = request.getParameter("regionId");
        page.boardId = request.getParameter("boardId");
        page.seasonId = request.getParameter("seasonId");
        page.rulesReleaseId = request.getParameter("rulesReleaseId");
        page.from = instant(request, "from");
        page.to = instant(request, "to");
        Integer limit = integer(request, "limit");
        page.limit = limit == null ? 0 : limit;
        page.cursor = request.getParameter("cursor");
        return page;
    }

    private static void rejectUnknown(HttpServletRequest request, Set<String> allowed) {
        for (String key : request.getParameterMap().keySet()) {
            if (!allowed.contains(key) || request.getParameterValues(key).length != 1) {
                throw new ParseFailure("UNKNOWN_FIELD");
            }
        }
    }

    private static Set<String> codes(HttpServletRequest request, String key) {
        if (request.getParameter(key) == null) {
            return null;
        }
        Set<String> codes = new TreeSet<String>();
        for (String part : request.getParameter(key).split(",")) {
            if (!part.trim().isEmpty()) {
                codes.add(part.trim());
            }
        }
        return codes;
    }

    private static Instant instant(HttpServletRequest request, String key) {
        String raw = request.getParameter(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new ParseFailure("MISSING_PARAM");
        }
    }

    private static Integer integer(HttpServletRequest request, String key) {
        String raw = request.getParameter(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ex) {
            throw new ParseFailure("MISSING_PARAM");
        }
    }

    private Long actor() {
        try {
            return getUserId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Map<String, Object> envelope(String status) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("candidate", Boolean.TRUE);
        data.put("contract", "CITY_VALUE_READ_V1");
        data.put("status", status);
        data.put("transport", "NOT_APPROVED");
        return data;
    }

    private static ResponseEntity<AjaxResult> respond(ValueOutcome outcome) {
        AjaxResult body = outcome.httpStatus == 200 ? AjaxResult.success(outcome.data) : AjaxResult.error(outcome.httpStatus, outcome.message);
        if (outcome.httpStatus != 200 && outcome.data != null) {
            body.put(AjaxResult.DATA_TAG, outcome.data);
        }
        if (outcome.errorCode != null) {
            body.put("errorCode", outcome.errorCode);
        }
        body.put("candidate", Boolean.TRUE);
        return ResponseEntity.status(outcome.httpStatus).header("X-Chengyin-Candidate", "city-value").body(body);
    }

    private static final class ParseFailure extends RuntimeException {
        private final String code;

        private ParseFailure(String code) {
            this.code = code;
        }
    }
}
