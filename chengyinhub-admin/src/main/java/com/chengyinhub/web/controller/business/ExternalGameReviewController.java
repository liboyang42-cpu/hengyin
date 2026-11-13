package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.externalgame.ExternalGameReviewService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Back-office review of external game submissions, same permissions as the audit workbench
 * ({@code business:audit:list} to read, {@code business:audit:review} to decide). Every transition is written to
 * external_game_review_event with the reviewer's sys_user id; the queue sweeps review timeouts before it reads.
 */
@RestController
@Tag(name = "外部游戏审核(后台)")
@RequestMapping("/business/external-game")
public class ExternalGameReviewController extends BaseController {
    private final ExternalGameReviewService reviews;

    public ExternalGameReviewController(ExternalGameReviewService reviews) { this.reviews = reviews; }

    @Operation(summary = "审核队列（默认待审 + 已升级，升级的排前）")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/review/list")
    public AjaxResult list(@RequestParam(required = false) String status, @RequestParam(required = false) Integer pageNum,
                           @RequestParam(required = false) Integer pageSize) {
        return success(reviews.adminQueue(status, pageNum, pageSize));
    }

    @Operation(summary = "提交详情（清单、文件、时间线、举报）")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/review/{submissionId}")
    public AjaxResult detail(@PathVariable String submissionId) {
        return success(reviews.adminDetail(submissionId));
    }

    @Operation(summary = "下载待审游戏包")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/review/{submissionId}/package")
    public ResponseEntity<byte[]> pkg(@PathVariable String submissionId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("X-Content-Type-Options", "nosniff")
                .body(reviews.adminPackage(submissionId));
    }

    @Operation(summary = "通过")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/{submissionId}/approve")
    public AjaxResult approve(@PathVariable String submissionId, @RequestBody(required = false) Map<String, Object> body) {
        return success(reviews.approve(getUserId(), submissionId, body));
    }

    @Operation(summary = "驳回（必须写原因）")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/{submissionId}/reject")
    public AjaxResult reject(@PathVariable String submissionId, @RequestBody(required = false) Map<String, Object> body) {
        return success(reviews.reject(getUserId(), submissionId, body));
    }

    @Operation(summary = "升级（必须写原因）")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/{submissionId}/escalate")
    public AjaxResult escalate(@PathVariable String submissionId, @RequestBody(required = false) Map<String, Object> body) {
        return success(reviews.escalate(getUserId(), submissionId, body));
    }

    @Operation(summary = "立即执行超时升级")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/review/sweep-timeouts")
    public AjaxResult sweep() {
        AjaxResult result = success();
        result.put("escalated", reviews.sweepTimeouts());
        result.put("reviewTimeoutDays", reviews.timeoutDays());
        return result;
    }

    @Operation(summary = "玩家举报列表")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/reports")
    public AjaxResult reports(@RequestParam(required = false) String gameId, @RequestParam(required = false) String status,
                              @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) {
        return success(reviews.adminReports(gameId, status, pageNum, pageSize));
    }

    @ExceptionHandler(AppGapException.class)
    public ResponseEntity<AjaxResult> failed(AppGapException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
