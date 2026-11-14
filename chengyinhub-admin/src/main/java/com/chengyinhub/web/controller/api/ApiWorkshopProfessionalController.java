package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.workshop.WorkshopProfessionalService;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * W18 professional materialization of a purchased workshop text (app Core/WorkshopPaidProfessionalService.swift).
 * Every route is POST with a JSON body. Signed out is 401; any refusal keeps its HTTP status, which the app reads as
 * unavailable. Submissions go through the shared review state machine (see {@link WorkshopProfessionalService}).
 */
@RestController
@Tag(name = "工坊已购文本专业化")
@RequestMapping("/api/workshop/purchased")
public class ApiWorkshopProfessionalController extends AppGapController {
    private final WorkshopProfessionalService professional;

    public ApiWorkshopProfessionalController(WorkshopProfessionalService professional) { this.professional = professional; }

    @Operation(summary = "本人可专业化的主题（50 条一页）")
    @PostMapping("/professional-targets/list")
    public AjaxResult targets(@RequestBody(required = false) String body) {
        return success(professional.targets(member(), body));
    }

    @Operation(summary = "单个目标主题")
    @PostMapping("/professional-targets/detail")
    public AjaxResult target(@RequestBody(required = false) String body) {
        return success(professional.target(member(), body));
    }

    @Operation(summary = "提交专业化（进入审核状态机；AUTO 时服务端即时审定）")
    @PostMapping("/professional-operations/submit")
    public AjaxResult submit(@RequestBody(required = false) String body) {
        Long me = member();
        return success(professional.submit(me, appUserName(), body));
    }

    @Operation(summary = "按请求号查状态")
    @PostMapping("/professional-operations/status")
    public AjaxResult status(@RequestBody(required = false) String body) {
        return success(professional.status(member(), body));
    }

    @Operation(summary = "撤回（未审定的提交）")
    @PostMapping("/professional-operations/cancel")
    public AjaxResult cancel(@RequestBody(required = false) String body) {
        return success(professional.cancel(member(), body));
    }

    @Operation(summary = "本许可下的历史意图")
    @PostMapping("/professional-operations/history")
    public AjaxResult history(@RequestBody(required = false) String body) {
        return success(professional.history(member(), body));
    }

    private String appUserName() {
        try {
            return getAppUserName();
        } catch (Exception unknown) {
            return null;
        }
    }
}
