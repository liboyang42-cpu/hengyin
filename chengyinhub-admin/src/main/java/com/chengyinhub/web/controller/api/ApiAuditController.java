package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AuditAppeal;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.AuditAppealDTO;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** NF-01 申请人只读审批详情与时间线；AUDIT-G4 作者申诉与内容审核时间线(归属在服务层按内容作者校验)。 */
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

    @Operation(summary = "对被驳回/下架的内容发起申诉(每条内容一次)")
    @PostMapping("/appeal")
    public AjaxResult appeal(@RequestBody AuditAppealDTO request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "申诉参数不完整");
        try {
            AuditAppeal appeal = auditTaskService.submitAppeal(request.getBizType(), request.getBizId(), memberId,
                    request.getReason(), request.getEvidence() == null ? null : request.getEvidence().toString());
            AjaxResult ajax = success();
            ajax.put("appealId", appeal.getId());
            ajax.put("taskId", appeal.getTaskId());
            ajax.put("state", appeal.getState());
            ajax.put("dueAt", appeal.getDueAt());
            return ajax;
        } catch (ServiceException e) {
            AjaxResult error = new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
            if (e.getDetailMessage() != null) error.put("errorCode", e.getDetailMessage());
            return error;
        }
    }

    @Operation(summary = "我的内容审核时间线(仅作者本人)")
    @GetMapping("/timeline")
    public AjaxResult timeline(@RequestParam("bizType") String bizType, @RequestParam("bizId") Long bizId) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            return success(auditTaskService.contentTimeline(bizType, bizId, memberId));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
        }
    }
}
