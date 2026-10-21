package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only report and the caller's outing lines. Neither route is anonymous. */
@RestController
@RequestMapping("/api/city-game/fairness")
public class CityFairnessReportController extends BaseController {
    private final CityFairnessReportService reports;

    public CityFairnessReportController(CityFairnessReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/report")
    public ResponseEntity<AjaxResult> report(@RequestParam(required = false) String queryId,
                                              @RequestParam(required = false) Long asOf,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) String boardId,
                                              @RequestParam(required = false) String regionId,
                                              @RequestParam(required = false) String seasonId,
                                              @RequestParam(required = false) String rulesVersion) {
        Long accountId = currentAccount();
        if (accountId == null) {
            return respond(CityFairnessReportService.Page.reject(401, "UNAUTHORIZED", asOf));
        }
        return respond(reports.read(accountId, queryId, asOf, cursor, boardId, regionId, seasonId, rulesVersion));
    }

    @GetMapping("/outing")
    public ResponseEntity<AjaxResult> outing(@RequestParam(required = false) String queryId,
                                              @RequestParam(required = false) Long asOf,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) String boardId,
                                              @RequestParam(required = false) String regionId,
                                              @RequestParam(required = false) String seasonId,
                                              @RequestParam(required = false) String rulesVersion) {
        Long accountId = currentAccount();
        if (accountId == null) {
            CityFairnessReportService.Page denied = CityFairnessReportService.Page.reject(401, "UNAUTHORIZED", asOf);
            return finish(denied, outingData(denied));
        }
        CityFairnessReportService.Page page = reports.outings(accountId, queryId, asOf, cursor, boardId, regionId, seasonId, rulesVersion);
        return finish(page, outingData(page));
    }

    protected Long currentAccount() {
        try {
            Long accountId = getAppUserId();
            return accountId == null || accountId <= 0 ? null : accountId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ResponseEntity<AjaxResult> respond(CityFairnessReportService.Page page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", page.accountId);
        data.put("queryId", page.queryId);
        data.put("asOf", page.asOf);
        data.put("metricVersion", page.metricVersion);
        data.put("formulaId", page.formulaId);
        data.put("factRevision", page.factRevision);
        data.put("matureCount", page.matureCount);
        data.put("denominator", page.denominator);
        data.put("boardId", page.boardId);
        data.put("regionId", page.regionId);
        data.put("seasonId", page.seasonId);
        data.put("rulesVersion", page.rulesVersion);
        data.put("status", page.status);
        data.put("maturity", page.maturity);
        data.put("missing", page.missing);
        data.put("groups", page.groups);
        data.put("members", page.outingClasses);
        data.put("outingClasses", page.outingClasses);
        data.put("secondOutings", page.secondOutings);
        data.put("lateEvents", page.lateEvents);
        data.put("cursor", page.cursor);
        data.put("nextCursor", page.nextCursor);
        data.put("readBack", page.readBack);
        if (page.errorCode != null) data.put("errorCode", page.errorCode);
        return finish(page, data);
    }

    private static Map<String, Object> outingData(CityFairnessReportService.Page page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", page.accountId);
        data.put("queryId", page.queryId);
        data.put("asOf", page.asOf);
        data.put("metricVersion", page.metricVersion);
        data.put("formulaId", page.formulaId);
        data.put("factRevision", page.factRevision);
        data.put("boardId", page.boardId);
        data.put("regionId", page.regionId);
        data.put("seasonId", page.seasonId);
        data.put("rulesVersion", page.rulesVersion);
        data.put("status", page.status);
        data.put("maturity", page.maturity);
        data.put("t0", page.t0);
        data.put("missing", page.missing);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CityFairnessReader.OutingEvidence item : page.outings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("outingId", item.outingId);
            row.put("t0", item.t0);
            row.put("valid", item.valid);
            row.put("reason", item.reason);
            row.put("inWindow", item.inWindow);
            row.put("maturity", item.maturity);
            row.put("metricVersion", page.metricVersion);
            row.put("factRevision", page.factRevision);
            rows.add(row);
        }
        data.put("outings", rows);
        data.put("cursor", page.cursor);
        data.put("nextCursor", page.nextCursor);
        data.put("readBack", page.readBack);
        if (page.errorCode != null) data.put("errorCode", page.errorCode);
        return data;
    }

    private static ResponseEntity<AjaxResult> finish(CityFairnessReportService.Page page, Map<String, Object> data) {
        boolean gap = CityFairnessReader.NOT_COMPUTABLE.equals(page.status);
        int http = gap ? 422 : page.httpStatus;
        AjaxResult body = http == 200
                ? AjaxResult.success(data)
                : AjaxResult.error(http, gap ? CityFairnessReader.NOT_COMPUTABLE : page.errorCode);
        if (http != 200) body.put(AjaxResult.DATA_TAG, data);
        body.put("candidate", Boolean.TRUE);
        return ResponseEntity.status(http).header("X-Chengyin-Candidate", "city-fairness").body(body);
    }
}
