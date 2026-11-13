package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.CreatorMaintenanceService;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * U08-03 creator maintenance workbench for the app: player feedback on the caller's own topics and the
 * maintenance tickets built on it. Author-only (cms_topic.member_id); no money. See CreatorMaintenanceService.
 */
@RestController
@Tag(name = "创作者维护工作台")
public class ApiCreatorMaintenanceController extends AppGapController {
    private final CreatorMaintenanceService maintenance;

    public ApiCreatorMaintenanceController(CreatorMaintenanceService maintenance) { this.maintenance = maintenance; }

    @Operation(summary = "我的主题收到的完局问题反馈（不含反馈者身份）")
    @GetMapping("/api/creator/feedback")
    public AjaxResult feedback(@RequestParam(required = false) String topicId, @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) String limit) {
        return success(maintenance.feedback(member(), topicId, cursor, limit));
    }

    @Operation(summary = "我的维护单")
    @GetMapping("/api/creator/maintenance/tickets")
    public AjaxResult tickets(@RequestParam(required = false) String topicId, @RequestParam(required = false) String status,
                              @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        return success(maintenance.tickets(member(), topicId, status, cursor, limit));
    }

    @Operation(summary = "维护单详情（阶段由发布与复测事实推导）")
    @GetMapping("/api/creator/maintenance/tickets/{ticketId}")
    public AjaxResult ticket(@PathVariable String ticketId) {
        return success(maintenance.ticket(member(), ticketId));
    }

    @Operation(summary = "开维护单（链接反馈，记下原内容版本）")
    @PostMapping(value = "/api/creator/maintenance/tickets", consumes = "application/json")
    public AjaxResult open(@RequestBody(required = false) Map<String, Object> body) {
        return success(maintenance.open(member(), body));
    }

    @Operation(summary = "开单原请求回读（结果未知时用同一 requestId 查询）")
    @GetMapping("/api/creator/maintenance/requests/{requestId}")
    public AjaxResult openRequest(@PathVariable String requestId) {
        return success(maintenance.openRequest(member(), requestId));
    }

    @Operation(summary = "关闭维护单（FIXED 需已发布新版本；NO_CHANGE 需写明原因）")
    @PostMapping(value = "/api/creator/maintenance/tickets/{ticketId}/close", consumes = "application/json")
    public AjaxResult close(@PathVariable String ticketId, @RequestBody(required = false) Map<String, Object> body) {
        return success(maintenance.close(member(), ticketId, body));
    }
}
