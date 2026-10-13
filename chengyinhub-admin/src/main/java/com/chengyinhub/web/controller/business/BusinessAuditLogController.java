package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.BusinessAuditLogQueryDTO;
import com.chengyinhub.business.service.IBusinessAuditLogQueryService;
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

    @PreAuthorize("@ss.hasPermi('business:audit:log:list') and @ss.hasRole('admin')")
    @GetMapping("/list")
    public AjaxResult list(BusinessAuditLogQueryDTO query) {
        try {
            return success(businessAuditLogQueryService.query(query, SysUser.isAdmin(getUserId())));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
        }
    }
}
