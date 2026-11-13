package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.ApprovedTopicReleases;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * App 已批准主题发行 v1。请求体按原始字节严格校验（大小、重复键、精确键集）；
 * App 按 HTTP 状态区分 401/403/409，所以业务错误码同时写成 HTTP 状态。
 */
@RestController
@RequestMapping("/api/approved-topic-release/v1")
@Tag(name = "App 已批准主题发行")
public class ApiAppGapsApprovedTopicReleaseController extends BaseController {
    private final ApprovedTopicReleases releases;

    public ApiAppGapsApprovedTopicReleaseController(ApprovedTopicReleases releases) { this.releases = releases; }

    @PostMapping("/review/sources")
    public AjaxResult reviewSources(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::reviewSources);
    }

    @PostMapping("/review/prepare")
    public AjaxResult reviewPrepare(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::reviewPrepare);
    }

    @PostMapping("/review/submit")
    public AjaxResult reviewSubmit(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::reviewSubmit);
    }

    @PostMapping("/review/status")
    public AjaxResult reviewStatus(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::reviewStatus);
    }

    @PostMapping("/review/current")
    public AjaxResult reviewCurrent(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::reviewCurrent);
    }

    @PostMapping("/prepare")
    public AjaxResult prepare(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::prepare);
    }

    @PostMapping("/publish")
    public AjaxResult publish(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::publish);
    }

    @PostMapping("/status")
    public AjaxResult status(@RequestBody(required = false) String body, HttpServletResponse response) {
        return run(body, response, releases::status);
    }

    private AjaxResult run(String body, HttpServletResponse response, BiFunction<Long, String, Map<String, Object>> call) {
        Long member = member();
        if (member == null) return error(response, 401, "请先登录");
        try { return AjaxResult.success(call.apply(member, body)); }
        catch (ServiceException e) { return error(response, e.getCode() == null ? 500 : e.getCode(), e.getMessage()); }
    }

    private Long member() {
        try { Long id = getAppUserId(); return id != null && id > 0 ? id : null; } catch (Exception e) { return null; }
    }

    private static AjaxResult error(HttpServletResponse response, int code, String message) {
        if (code >= 400 && code < 600) response.setStatus(code);
        return AjaxResult.error(code, message);
    }
}
