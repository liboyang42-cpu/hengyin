package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantPermissionPolicy;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantOperatorAcceptRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorInviteRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorInviteRevokeRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorRemoveRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorRoleRequest;
import com.chengyinhub.business.service.IMerchantOperatorService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "商家经营团队")
@RequestMapping("/api/merchant")
public class ApiMerchantOperatorController extends BaseController {
    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private IMerchantOperatorService merchantOperatorService;
    @Autowired private IMmsMerchantService mmsMerchantService;

    @Operation(summary = "当前登录账号的商家域访问上下文")
    @PostMapping("/access/me")
    public AjaxResult accessMe() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            MerchantAccessContext access = merchantAccessService.resolve(principal);
            if (access == null) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("active", false);
                none.put("permissions", java.util.Collections.emptyList());
                none.put("canManageOperators", false);
                MmsMerchant application = mmsMerchantService.selectMmsMerchantByMemberId(principal);
                if (application == null) {
                    none.put("applicationState", "NONE");
                } else {
                    if (application.getId() == null || !principal.equals(application.getMemberId())
                            || !Integer.valueOf(0).equals(application.getDelFlag())) {
                        throw new ServiceException("商家申请归属无效或账号不可用", 403);
                    }
                    if ((!Long.valueOf(0L).equals(application.getStatus())
                            && !Long.valueOf(1L).equals(application.getStatus())
                            && !Long.valueOf(2L).equals(application.getStatus()))
                            || (!Long.valueOf(0L).equals(application.getAccountStatus())
                            && !Long.valueOf(1L).equals(application.getAccountStatus())
                            && !Long.valueOf(2L).equals(application.getAccountStatus()))) {
                        throw new ServiceException("商家身份状态不可用，请刷新后重试", 403);
                    }
                    String applicationState;
                    if (Long.valueOf(2L).equals(application.getAccountStatus())) {
                        applicationState = "DISABLED";
                    } else if (Long.valueOf(0L).equals(application.getStatus())) {
                        applicationState = "PENDING";
                    } else if (Long.valueOf(2L).equals(application.getStatus())) {
                        applicationState = "REJECTED";
                    } else if (Long.valueOf(1L).equals(application.getStatus())
                            && !Long.valueOf(1L).equals(application.getAccountStatus())) {
                        applicationState = "DISABLED";
                    } else {
                        throw new ServiceException("商家身份状态不可用，请刷新后重试", 403);
                    }
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", application.getId());
                    summary.put("name", application.getName());
                    summary.put("logo", application.getLogo());
                    summary.put("status", application.getStatus());
                    summary.put("accountStatus", application.getAccountStatus());
                    none.put("roleCode", MerchantRoleCode.MERCHANT_OWNER);
                    none.put("applicationState", applicationState);
                    none.put("merchant", summary);
                }
                return success(none);
            }
            MmsMerchant merchant = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
            if (merchant == null) throw new ServiceException("商家身份无效或权限不足", 403);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", merchant.getId());
            summary.put("name", merchant.getName());
            summary.put("logo", merchant.getLogo());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("active", true);
            data.put("merchant", summary);
            data.put("roleCode", access.getRoleCode());
            data.put("permissions", new ArrayList<>(access.getPermissions()));
            data.put("canManageOperators", access.hasPermission(MerchantPermission.OPERATOR_MANAGE));
            return success(data);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "可分配的固定员工角色")
    @PostMapping("/operators/roles")
    public AjaxResult roles() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            merchantAccessService.require(principal, MerchantPermission.OPERATOR_MANAGE);
            List<Map<String, Object>> roles = new ArrayList<>();
            for (String roleCode : MerchantRoleCode.operatorRoles()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("roleCode", roleCode);
                row.put("name", roleName(roleCode));
                row.put("permissions", new ArrayList<>(MerchantPermissionPolicy.permissionsFor(roleCode)));
                roles.add(row);
            }
            return success(roles);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/operators/list")
    public AjaxResult team() {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try { return success(merchantOperatorService.team(principal)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/operators/invite")
    public AjaxResult invite(@RequestBody(required = false) MerchantOperatorInviteRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantOperatorService.invite(principal,
                    request == null ? null : request.getRoleCode(),
                    request == null ? null : request.getRequestId()));
        } catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/operators/invite/accept")
    public AjaxResult accept(@RequestBody(required = false) MerchantOperatorAcceptRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantOperatorService.accept(principal,
                    request == null ? null : request.getToken(),
                    request == null ? null : request.getRequestId()));
        } catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/operators/role")
    public AjaxResult updateRole(@RequestBody(required = false) MerchantOperatorRoleRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantOperatorService.updateRole(principal,
                    request == null ? null : request.getOperatorId(),
                    request == null ? null : request.getRoleCode(),
                    request == null ? null : request.getVersion(),
                    request == null ? null : request.getRequestId()));
        } catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/operators/remove")
    public AjaxResult remove(@RequestBody(required = false) MerchantOperatorRemoveRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantOperatorService.remove(principal,
                    request == null ? null : request.getOperatorId(),
                    request == null ? null : request.getVersion(),
                    request == null ? null : request.getReason(),
                    request == null ? null : request.getRequestId()));
        } catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/operators/invite/revoke")
    public AjaxResult revokeInvite(@RequestBody(required = false) MerchantOperatorInviteRevokeRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantOperatorService.revokeInvite(principal,
                    request == null ? null : request.getInviteId(),
                    request == null ? null : request.getVersion(),
                    request == null ? null : request.getReason(),
                    request == null ? null : request.getRequestId()));
        } catch (ServiceException e) { return serviceError(e); }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }

    private String roleName(String roleCode) {
        if (MerchantRoleCode.MERCHANT_MANAGER.equals(roleCode)) return "店长";
        if (MerchantRoleCode.MERCHANT_CHECKIN.equals(roleCode)) return "核销员";
        if (MerchantRoleCode.MERCHANT_MARKETING.equals(roleCode)) return "运营";
        if (MerchantRoleCode.MERCHANT_FINANCE.equals(roleCode)) return "财务";
        return "未知角色";
    }
}
