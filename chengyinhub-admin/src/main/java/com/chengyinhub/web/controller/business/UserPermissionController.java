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
        if (p == null || p.getId() == null || p.getId() <= 0) {
            return AjaxResult.error("请选择有效的角色配额记录");
        }
        // 与后台 role-quota 页 el-input-number 的 0..99 同口径;null 表示本次不改该项(Mapper 按 null 跳过)
        if (invalidQuota(p.getMaxThemes()) || invalidQuota(p.getMaxEvents()) || invalidQuota(p.getMaxCoupons())) {
            return AjaxResult.error("主题、活动和优惠券上限必须为0到99的整数");
        }
        return toAjax(userPermissionService.update(p));
    }

    private static boolean invalidQuota(Integer value) {
        return value != null && (value < 0 || value > 99);
    }
}
