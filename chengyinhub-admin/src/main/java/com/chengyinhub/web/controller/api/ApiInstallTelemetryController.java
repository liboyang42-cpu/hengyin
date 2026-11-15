package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.exception.ErrorCode;

import com.chengyinhub.business.telemetry.InstallEventSchema;
import com.chengyinhub.business.telemetry.InstallTelemetryService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * P6 measurement(GRO-03 / PERF-06):登录前首会话埋点与 MetricKit 健康数据。
 *
 * <p>匿名入口只有两条精确 POST(SecurityConfig 单列):{@code /api/telemetry/anon/events}
 * 与 {@code /api/telemetry/metrickit}。方法内不取登录态,按 IP 限流,合同见
 * {@link InstallEventSchema}。{@code /api/telemetry/link} 需要登录。
 */
@RestController
@Tag(name = "P6 安装埋点")
@RequestMapping("/api/telemetry")
public class ApiInstallTelemetryController extends BaseController {

    @Autowired
    private InstallTelemetryService telemetryService;

    @Operation(summary = "匿名安装事件批量上报(登录前可用)")
    @PostMapping("/anon/events")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.IP)
    public AjaxResult anonymousEvents(@RequestBody(required = false) String body) {
        try {
            return accepted(telemetryService.recordAnonymousBatch(body, System.currentTimeMillis()));
        } catch (InstallEventSchema.Rejected rejected) {
            return error(rejected.getMessage());
        }
    }

    @Operation(summary = "登录后把安装号关联到当前账号")
    @PostMapping("/link")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult link(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            memberId = null;
        }
        if (memberId == null || memberId <= 0) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        Object installId = body == null ? null : body.get("installId");
        if (body != null && body.size() > 1) {
            return error("上报内容包含未知字段");
        }
        try {
            boolean linked = telemetryService.link(installId instanceof String s ? s : null, memberId);
            Map<String, Object> data = new HashMap<>();
            data.put("linked", linked);
            return success(data);
        } catch (InstallEventSchema.Rejected rejected) {
            return error(rejected.getMessage());
        }
    }

    @Operation(summary = "MetricKit 指标与诊断载荷(登录前可用)")
    @PostMapping("/metrickit")
    @RateLimiter(time = 60, count = 6, limitType = LimitType.IP)
    public AjaxResult metricKit(@RequestBody(required = false) String body) {
        try {
            return accepted(telemetryService.recordMetricPayload(body, System.currentTimeMillis()));
        } catch (InstallEventSchema.Rejected rejected) {
            return error(rejected.getMessage());
        }
    }

    private AjaxResult accepted(int count) {
        Map<String, Object> data = new HashMap<>();
        data.put("accepted", count);
        return success(data);
    }
}
