package com.chengyinhub.web.controller.business;

import java.util.List;

import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.IUserPermissionService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色配额配置（后台管理）
 */
@RestController
@RequestMapping("/business/userpermission")
public class UserPermissionController extends BaseController {

    @Autowired
    private IUserPermissionService userPermissionService;

    @PreAuthorize("@ss.hasPermi('business:userpermission:list')")
    @GetMapping("/list")
    public AjaxResult list() {
        List<UserPermission> list = userPermissionService.getAll();
        return AjaxResult.success(list);
    }

    @PreAuthorize("@ss.hasPermi('business:userpermission:edit')")
    @Log(title = "角色配额", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UserPermission p) {
        return toAjax(userPermissionService.update(p));
    }
}
