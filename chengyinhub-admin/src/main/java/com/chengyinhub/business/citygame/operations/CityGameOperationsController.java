package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CITY 运营诊断与纠错候选入口。权限来自登录身份，默认不授予。
 */
@RestController
@RequestMapping("/business/citygame/operations")
public class CityGameOperationsController extends BaseController {

    private final CityGameOperationsService operations;

    public CityGameOperationsController(CityGameOperationsService operations) {
        this.operations = operations;
    }

    @PreAuthorize("@ss.hasPermi('" + CityGameOperationsPermissions.TRACE + "')")
    @PostMapping("/trace")
    public ResponseEntity<AjaxResult> trace(@RequestBody(required = false) JsonNode body) {
        return respond(operations.trace(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityGameOperationsPermissions.PROPOSE + "')")
    @PostMapping("/propose")
    public ResponseEntity<AjaxResult> propose(@RequestBody(required = false) JsonNode body) {
        return respond(operations.propose(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityGameOperationsPermissions.REVIEW + "')")
    @PostMapping("/review")
    public ResponseEntity<AjaxResult> review(@RequestBody(required = false) JsonNode body) {
        return respond(operations.review(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityGameOperationsPermissions.APPLY + "')")
    @PostMapping("/apply")
    public ResponseEntity<AjaxResult> apply(@RequestBody(required = false) JsonNode body) {
        return respond(operations.apply(getUserId(), body));
    }

    @PreAuthorize("@ss.hasAnyPermi('" + CityGameOperationsPermissions.QUERY + ","
            + CityGameOperationsPermissions.TRACE + "," + CityGameOperationsPermissions.PROPOSE + ","
            + CityGameOperationsPermissions.REVIEW + "," + CityGameOperationsPermissions.APPLY + "')")
    @GetMapping("/case/{id}")
    public ResponseEntity<AjaxResult> readCase(@PathVariable("id") long id) {
        return respond(operations.readCase(getUserId(), id));
    }

    @PreAuthorize("@ss.hasAnyPermi('" + CityGameOperationsPermissions.QUERY + ","
            + CityGameOperationsPermissions.TRACE + "," + CityGameOperationsPermissions.PROPOSE + ","
            + CityGameOperationsPermissions.REVIEW + "," + CityGameOperationsPermissions.APPLY + "')")
    @GetMapping("/request/{requestId}")
    public ResponseEntity<AjaxResult> readRequest(@PathVariable("requestId") String requestId) {
        return respond(operations.readRequest(getUserId(), requestId));
    }

    @PreAuthorize("@ss.hasAnyPermi('" + CityGameOperationsPermissions.QUERY + ","
            + CityGameOperationsPermissions.TRACE + "," + CityGameOperationsPermissions.PROPOSE + ","
            + CityGameOperationsPermissions.REVIEW + "," + CityGameOperationsPermissions.APPLY + "')")
    @GetMapping("/store/{merchantId}")
    public ResponseEntity<AjaxResult> readStore(@PathVariable("merchantId") String merchantId) {
        return respond(operations.readStore(getUserId(), merchantId));
    }

    private static ResponseEntity<AjaxResult> respond(CityGameOperationsOutcome outcome) {
        AjaxResult body = outcome.httpStatus == 200
                ? AjaxResult.success(outcome.data)
                : AjaxResult.error(outcome.httpStatus, outcome.message);
        if (outcome.httpStatus != 200 && outcome.data != null) {
            body.put(AjaxResult.DATA_TAG, outcome.data);
        }
        if (outcome.errorCode != null) {
            body.put("errorCode", outcome.errorCode);
        }
        body.put("candidate", Boolean.TRUE);
        return ResponseEntity.status(outcome.httpStatus).header("X-Chengyin-Candidate", "city-ops").body(body);
    }
}
