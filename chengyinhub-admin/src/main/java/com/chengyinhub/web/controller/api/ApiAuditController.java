package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** NF-01 申请人只读审批详情与时间线。 */
@RestController
@Tag(name = "我的审批进度")
@RequestMapping("/api/audit")
public class ApiAuditController extends BaseController {
    @Autowired
    private IAuditTaskService auditTaskService;

    @Operation(summary = "申请详情与审批时间线")
    @GetMapping("/{taskId}/detail")
    public AjaxResult detail(@PathVariable Long taskId) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        AuditTask task = auditTaskService.selectAuditTaskById(taskId);
        if (task == null) return new AjaxResult(HttpStatus.NOT_FOUND, "审核任务不存在");
        if (task.getSubmitterId() == null || !task.getSubmitterId().equals(memberId)) {
            return new AjaxResult(HttpStatus.FORBIDDEN, "无权查看该审批记录");
        }
        try {
            return success(auditTaskService.detail(taskId, memberId, false));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
        }
    }
}
