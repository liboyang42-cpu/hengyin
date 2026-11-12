package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.domain.vo.BusinessAuditLogPageVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;
import com.chengyinhub.business.service.support.ops.SupportOriginalReadFactory;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** NF-15 平台审计查询；当前阶段 fail-closed 为平台管理员专用。 */
@RestController
@RequestMapping("/business/audit-log")
public class BusinessAuditLogController extends BaseController {
    @Autowired
    private IBusinessAuditLogQueryService businessAuditLogQueryService;

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @PreAuthorize("@ss.hasPermi('business:audit:log:list') and @ss.hasRole('admin')")
    @GetMapping("/list")
    public AjaxResult list(BusinessAuditLogQueryDTO query) {
        try {
            SupportOriginalReadFactory reads = new SupportOriginalReadFactory(
                    businessAuditLogQueryService, businessAuditEventMapper);
            BusinessAuditLogPageVO page = reads.list(query, SysUser.isAdmin(getUserId()));
            confirmOriginalTarget(reads, query);
            return success(page);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** An order or run filter must match the original event. Other targets stay on their own entries. */
    private static void confirmOriginalTarget(SupportOriginalReadFactory reads, BusinessAuditLogQueryDTO query) {
        if (query == null || query.getTargetId() == null) {
            return;
        }
        if ("ORDER".equals(query.getTargetType())) {
            reads.locateOrder(query.getTargetId(), null);
        } else if ("RUN".equals(query.getTargetType())) {
            reads.locateRun(query.getTargetId(), null);
        }
    }
}
