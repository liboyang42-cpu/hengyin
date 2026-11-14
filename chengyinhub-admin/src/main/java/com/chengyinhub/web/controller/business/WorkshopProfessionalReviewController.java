package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.workshop.WorkshopProfessionalService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Back-office review of workshop professional materializations, on the same state machine and permissions as the
 * external game review ({@code business:audit:list} / {@code business:audit:review}). Only used when
 * {@code chengyin.workshop.professional.review=MANUAL}; with AUTO the server decides inside the submit.
 */
@RestController
@Tag(name = "工坊专业化审核(后台)")
@RequestMapping("/business/workshop-professional")
public class WorkshopProfessionalReviewController extends BaseController {
    private final WorkshopProfessionalService professional;

    public WorkshopProfessionalReviewController(WorkshopProfessionalService professional) { this.professional = professional; }

    @Operation(summary = "待审（已升级的排前）")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/review/list")
    public AjaxResult list() { return success(professional.adminQueue()); }

    @Operation(summary = "通过：重跑服务端校验并建模板；校验不过则驳回")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/{id}/approve")
    public AjaxResult approve(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return success(professional.approve(getUserId(), id, body == null ? null : (String) body.get("note")));
    }

    @Operation(summary = "驳回（必须写原因）")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/{id}/reject")
    public AjaxResult reject(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return success(professional.reject(getUserId(), id, body == null ? null : (String) body.get("reason")));
    }

    @Operation(summary = "升级（必须写原因）")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/{id}/escalate")
    public AjaxResult escalate(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return success(professional.escalate(getUserId(), id, body == null ? null : (String) body.get("reason")));
    }

    @ExceptionHandler(AppGapException.class)
    public ResponseEntity<AjaxResult> failed(AppGapException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
