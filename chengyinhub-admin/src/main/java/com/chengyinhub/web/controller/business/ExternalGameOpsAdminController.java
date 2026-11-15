package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.externalgame.ExternalGameOpsService;
import com.chengyinhub.business.externalgame.ExternalGameReviewService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * G3 console actions for external games: report queue (resolve / dismiss), takedown and restore, operator node
 * binding, and the moderation audit trail. Reads use {@code business:audit:list}; each write has its own permission
 * ({@code business:externalgame:report|takedown|bind}), created by migration_20261115_external_games_ops_menus.sql
 * and granted to no role. Every write is also recorded in external_game_moderation_event and the operation log.
 */
@RestController
@Tag(name = "外部游戏运营(后台)")
@RequestMapping("/business/external-game/ops")
public class ExternalGameOpsAdminController extends BaseController {
    private final ExternalGameOpsService ops;
    private final ExternalGameReviewService reviews;

    public ExternalGameOpsAdminController(ExternalGameOpsService ops, ExternalGameReviewService reviews) {
        this.ops = ops;
        this.reviews = reviews;
    }

    @Operation(summary = "举报队列（含处理结果与下架状态）")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/reports")
    public AjaxResult reports(@RequestParam(required = false) String gameId, @RequestParam(required = false) String status,
                              @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        return success(ops.enrichReports(reviews.adminReports(gameId, status, pageNum, pageSize)));
    }

    @Operation(summary = "处理举报（ACTIONED 已处理 / DISMISSED 驳回，驳回必须写说明）")
    @PreAuthorize("@ss.hasPermi('business:externalgame:report')")
    @Log(title = "外部游戏-处理举报", businessType = BusinessType.UPDATE)
    @PostMapping("/reports/{reportId}/resolve")
    public AjaxResult resolve(@PathVariable Long reportId, @RequestBody(required = false) Map<String, Object> body) {
        return success(ops.resolveReport(getUserId(), reportId == null ? 0L : reportId, body));
    }

    @Operation(summary = "下架外部游戏（必须写原因；默认同时把该游戏未处理举报标为已处理）")
    @PreAuthorize("@ss.hasPermi('business:externalgame:takedown')")
    @Log(title = "外部游戏-下架", businessType = BusinessType.UPDATE)
    @PostMapping("/games/{gameId}/takedown")
    public AjaxResult takedown(@PathVariable String gameId, @RequestBody(required = false) Map<String, Object> body) {
        return success(ops.takedown(getUserId(), gameId, body));
    }

    @Operation(summary = "恢复上架（恢复最新审核通过的版本；必须写原因）")
    @PreAuthorize("@ss.hasPermi('business:externalgame:takedown')")
    @Log(title = "外部游戏-恢复上架", businessType = BusinessType.UPDATE)
    @PostMapping("/games/{gameId}/restore")
    public AjaxResult restore(@PathVariable String gameId, @RequestBody(required = false) Map<String, Object> body) {
        return success(ops.restore(getUserId(), gameId, body));
    }

    @Operation(summary = "节点绑定列表")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/bindings")
    public AjaxResult bindings(@RequestParam(required = false) String gameId, @RequestParam(required = false) Long topicId,
                               @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        return success(ops.listBindings(gameId, topicId, pageNum, pageSize));
    }

    @Operation(summary = "运营绑定外部游戏到主题节点")
    @PreAuthorize("@ss.hasPermi('business:externalgame:bind')")
    @Log(title = "外部游戏-绑定节点", businessType = BusinessType.UPDATE)
    @PutMapping("/nodes/{nodeId}/binding")
    public AjaxResult bind(@PathVariable Long nodeId, @RequestBody(required = false) Map<String, Object> body) {
        return success(ops.adminBind(getUserId(), nodeId == null ? 0L : nodeId, body));
    }

    @Operation(summary = "运营解除节点绑定")
    @PreAuthorize("@ss.hasPermi('business:externalgame:bind')")
    @Log(title = "外部游戏-解除节点绑定", businessType = BusinessType.UPDATE)
    @PostMapping("/nodes/{nodeId}/unbind")
    public AjaxResult unbind(@PathVariable Long nodeId, @RequestBody(required = false) Map<String, Object> body) {
        return success(ops.adminUnbind(getUserId(), nodeId == null ? 0L : nodeId, body));
    }

    @Operation(summary = "外部游戏运营留痕")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/events")
    public AjaxResult events(@RequestParam(required = false) String gameId, @RequestParam(required = false) Integer pageNum,
                             @RequestParam(required = false) Integer pageSize) {
        return success(ops.moderationEvents(gameId, pageNum, pageSize));
    }

    @ExceptionHandler(AppGapException.class)
    public ResponseEntity<AjaxResult> failed(AppGapException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
