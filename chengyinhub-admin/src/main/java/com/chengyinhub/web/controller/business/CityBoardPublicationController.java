package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.cityboard.CityBoardOutcome;
import com.chengyinhub.business.cityboard.CityBoardPermissions;
import com.chengyinhub.business.cityboard.CityBoardPublicationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.service.PermissionService;
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
 * CITY 棋盘候选管理入口。权限默认不授予任何角色；客户端 JSON 不能充当审批。
 */
@RestController
@RequestMapping("/business/cityboard")
public class CityBoardPublicationController extends BaseController {

    private final CityBoardPublicationService publicationService;
    private final PermissionService permissions;

    public CityBoardPublicationController(CityBoardPublicationService publicationService, PermissionService permissions) {
        this.publicationService = publicationService;
        this.permissions = permissions;
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.DRAFT_SAVE + "')")
    @PostMapping("/draft/save")
    public ResponseEntity<AjaxResult> save(@RequestBody(required = false) JsonNode body) {
        return respond(publicationService.save(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.DRAFT_REOPEN + "')")
    @PostMapping("/draft/reopen")
    public ResponseEntity<AjaxResult> reopen(@RequestBody(required = false) JsonNode body) {
        return respond(publicationService.reopen(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.DRAFT_CANCEL + "')")
    @PostMapping("/draft/cancel")
    public ResponseEntity<AjaxResult> cancel(@RequestBody(required = false) JsonNode body) {
        return respond(publicationService.cancel(getUserId(), body));
    }

    @PreAuthorize("@ss.hasAnyPermi('" + CityBoardPermissions.DRAFT_QUERY + ","
            + CityBoardPermissions.REVIEW_VALIDATE + "," + CityBoardPermissions.REVIEW_REJECT + "')")
    @GetMapping("/draft/{id}")
    public ResponseEntity<AjaxResult> draft(@PathVariable("id") long id) {
        boolean staff = permissions.hasPermi(CityBoardPermissions.REVIEW_VALIDATE)
                || permissions.hasPermi(CityBoardPermissions.REVIEW_REJECT);
        return respond(publicationService.readDraft(getUserId(), id, staff));
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.REVIEW_VALIDATE + "')")
    @PostMapping("/review/validate")
    public ResponseEntity<AjaxResult> validate(@RequestBody(required = false) JsonNode body) {
        return respond(publicationService.validate(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.REVIEW_REJECT + "')")
    @PostMapping("/review/reject")
    public ResponseEntity<AjaxResult> reject(@RequestBody(required = false) JsonNode body) {
        return respond(publicationService.reject(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.PUBLISH + "')")
    @PostMapping("/publish")
    public ResponseEntity<AjaxResult> publish(@RequestBody(required = false) JsonNode body) {
        return respond(publicationService.publish(getUserId(), body));
    }

    @PreAuthorize("@ss.hasPermi('" + CityBoardPermissions.OPERATION_QUERY + "')")
    @GetMapping("/operation/{requestId}")
    public ResponseEntity<AjaxResult> operation(@PathVariable("requestId") String requestId) {
        return respond(publicationService.readOperation(requestId));
    }

    private static ResponseEntity<AjaxResult> respond(CityBoardOutcome outcome) {
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
        return ResponseEntity.status(outcome.httpStatus).header("X-Chengyin-Candidate", "city-board").body(body);
    }
}
