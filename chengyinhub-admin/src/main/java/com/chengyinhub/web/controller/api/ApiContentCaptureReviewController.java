package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ContentCaptureReviewCommand;
import com.chengyinhub.business.service.support.release.ContentCaptureReviewService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.utils.SecurityUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Member-only exact candidate review submission/status. Review decisions stay in the audit workbench. */
@RestController
@RequestMapping("/api/content-capture-review")
public class ApiContentCaptureReviewController extends BaseController {
    // Strict parsing is local to this wire contract; do not change global Jackson behavior.
    private static final ObjectReader COMMAND_READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readerFor(ContentCaptureReviewCommand.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_COMMAND_CHARS = 4096;
    private final ContentCaptureReviewService reviews;

    public ApiContentCaptureReviewController(ContentCaptureReviewService reviews) { this.reviews = reviews; }

    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AjaxResult> submit(@RequestBody String body) {
        return invoke(body, true);
    }

    @PostMapping(value = "/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AjaxResult> status(@RequestBody String body) {
        return invoke(body, false);
    }

    private ResponseEntity<AjaxResult> invoke(String body, boolean submit) {
        Authentication authentication = SecurityUtils.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginAppUser))
            return failure(401, "CONTENT_REVIEW_AUTH_REQUIRED", "会员登录已失效，请重新登录");
        LoginAppUser principal = (LoginAppUser) authentication.getPrincipal();
        Long memberId = principal.getAppUser() == null ? null : principal.getAppUser().getUserId();
        if (memberId == null || memberId <= 0
                || (principal.getUserId() != null && !memberId.equals(principal.getUserId())))
            return failure(401, "CONTENT_REVIEW_AUTH_REQUIRED", "会员登录已失效，请重新登录");
        if (body == null || body.length() > MAX_COMMAND_CHARS) return invalid();
        ContentCaptureReviewCommand command;
        try { command = COMMAND_READER.readValue(body); }
        catch (JsonProcessingException | IllegalArgumentException e) { return invalid(); }
        if (command == null || !command.valid()) return invalid();
        try {
            Object result = submit
                    ? reviews.submitForMember(command.getTopicId(), memberId, command.getSnapshotId(),
                            command.getExpectedVersion(), command.getExpectedHash(), command.getRequestId())
                    : reviews.statusForMember(command.getTopicId(), memberId, command.getSnapshotId(),
                            command.getExpectedVersion(), command.getExpectedHash(), command.getRequestId());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(success(result));
        } catch (ContentCaptureReviewService.UnavailableException e) {
            return failure(503, "CONTENT_REVIEW_UNAVAILABLE", "候选内容审核暂不可用");
        } catch (IllegalStateException | DataIntegrityViolationException e) {
            return failure(409, "CONTENT_REVIEW_CONFLICT", "内容身份、权限或审核状态已变化，请刷新后重试");
        } catch (TransientDataAccessException e) {
            return failure(409, "CONTENT_REVIEW_RETRY", "操作冲突，请保留原请求标识重试");
        } catch (Exception e) {
            logger.error("候选内容审核接口失败", e);
            return failure(503, "CONTENT_REVIEW_UNAVAILABLE", "候选内容审核暂不可用");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AjaxResult> invalidBody() { return invalid(); }

    private ResponseEntity<AjaxResult> invalid() {
        return failure(400, "CONTENT_REVIEW_INVALID", "候选内容审核参数不完整或无效");
    }

    private ResponseEntity<AjaxResult> failure(int status, String code, String message) {
        AjaxResult result = new AjaxResult(status, message);
        result.put("errorCode", code);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(result);
    }
}
