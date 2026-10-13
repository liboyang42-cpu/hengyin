package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubEventAccess;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubPermissionPolicy;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.ClubRoleGovernanceMetadata;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.dto.ClubAccessRequest;
import com.chengyinhub.business.domain.dto.ClubRoleAssignRequest;
import com.chengyinhub.business.domain.dto.ClubRoleRevokeRequest;
import com.chengyinhub.business.domain.vo.ClubRoleAssignmentVO;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.constant.HttpStatus;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

@RestController
@Tag(name = "俱乐部角色与权限")
@RequestMapping("/api/club")
public class ApiClubRoleController extends BaseController {
    @Autowired private IClubAccessService clubAccessService;
    @Autowired private IClubRoleAssignmentService clubRoleAssignmentService;
    @Autowired private IClubService clubService;

    @Operation(summary = "当前账号在指定俱乐部/活动的访问上下文")
    @PostMapping("/access/me")
    public AjaxResult accessMe(@RequestBody(required = false) ClubAccessRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少俱乐部参数");
        try {
            ClubAccessContext access = request.getActivityId() == null
                    ? clubAccessService.resolveWithEventAccesses(principal, request.getClubId())
                    : clubAccessService.resolve(principal, request.getClubId(), request.getActivityId());
            if (access == null) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("active", false);
                none.put("roleCodes", Collections.emptyList());
                none.put("permissions", Collections.emptyList());
                none.put("eventAccesses", Collections.emptyList());
                none.put("canManageRoles", false);
                return success(none);
            }
            Club club = clubService.selectClubById(access.getClubId());
            if (club == null || !access.getClubId().equals(club.getId())) throw forbidden();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", club.getId());
            summary.put("name", club.getName());
            summary.put("logo", club.getLogo());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("active", true);
            data.put("club", summary);
            data.put("activityId", access.getActivityId());
            data.put("roleCodes", new ArrayList<>(access.getRoleCodes()));
            data.put("permissions", new ArrayList<>(access.getPermissions()));
            data.put("eventAccesses", eventAccesses(access.getEventAccesses()));
            data.put("canManageRoles", access.hasPermission(ClubPermission.ROLE_MANAGE));
            // 稿 M 268:223 的管理页每一行右侧带个数。这两个数算在这儿而不是
            // /api/club/detail:detail 是公开接口,委派数属于治理信息,不该对游客可见。
            // 有 ROLE_MANAGE 才给,没权限的人这一行本来也不出。
            if (access.hasPermission(ClubPermission.ROLE_MANAGE)) {
                data.put("delegatedRoleCount",
                        clubRoleAssignmentService.list(principal, access.getClubId(), null).size());
            }
            return success(data);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private List<Map<String, Object>> eventAccesses(List<ClubEventAccess> accesses) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ClubEventAccess access : accesses) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("activityId", access.getActivityId());
            summary.put("topicId", access.getTopicId());
            summary.put("roleCodes", new ArrayList<>(access.getRoleCodes()));
            summary.put("permissions", new ArrayList<>(access.getPermissions()));
            summaries.add(summary);
        }
        return summaries;
    }

    @Operation(summary = "角色目录与当前有效委派")
    @PostMapping("/roles/list")
    public AjaxResult list(@RequestBody(required = false) ClubAccessRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少俱乐部参数");
        try {
            List<ClubRoleAssignment> rows = clubRoleAssignmentService.list(
                    principal, request.getClubId(), request.getActivityId());
            List<ClubRoleAssignmentVO> assignments = new ArrayList<>();
            for (ClubRoleAssignment row : rows) assignments.add(ClubRoleAssignmentVO.from(row));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roles", roleCatalog(rows));
            data.put("assignments", assignments);
            return success(data);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/roles/assign")
    public AjaxResult assign(@RequestBody(required = false) ClubRoleAssignRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少角色参数");
        if (missingRequestId(request.getRequestId())) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少 requestId");
        }
        try {
            ClubRoleAssignment created = clubRoleAssignmentService.assign(principal,
                    request.getClubId(), request.getTargetMemberId(), request.getRoleCode(),
                    request.getActivityId(), request.getExpiresAt(), request.getRequestId());
            return success(ClubRoleAssignmentVO.from(created));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/roles/revoke")
    public AjaxResult revoke(@RequestBody(required = false) ClubRoleRevokeRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少角色参数");
        if (missingRequestId(request.getRequestId())) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少 requestId");
        }
        try {
            clubRoleAssignmentService.revoke(principal, request.getClubId(), request.getActivityId(),
                    request.getAssignmentId(), request.getVersion(), request.getReason(), request.getRequestId());
            return success("角色已撤销");
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private List<Map<String, Object>> roleCatalog(List<ClubRoleAssignment> assignments) {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (String roleCode : ClubRoleCode.assignableRoles()) {
            ClubRoleGovernanceMetadata metadata = ClubRoleGovernanceMetadata.forRole(roleCode);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roleId", metadata.getRoleId());
            row.put("roleCode", roleCode);
            row.put("name", roleName(roleCode));
            row.put("permissionLevel", metadata.getPermissionLevel());
            row.put("levelAuthorizationSource", metadata.isAuthorizationSource());
            row.put("scopeType", ClubRoleCode.isClubRole(roleCode)
                    ? ClubRoleAssignment.SCOPE_CLUB : ClubRoleAssignment.SCOPE_EVENT);
            List<String> permissionKeys = new ArrayList<>(ClubPermissionPolicy.permissionsFor(roleCode));
            row.put("permissionKeys", permissionKeys);
            row.put("permissions", permissionKeys);
            row.put("memberCount", countMembers(assignments, roleCode));
            roles.add(row);
        }
        return roles;
    }

    private int countMembers(List<ClubRoleAssignment> assignments, String roleCode) {
        Set<Long> members = new LinkedHashSet<>();
        if (assignments != null) {
            for (ClubRoleAssignment assignment : assignments) {
                if (roleCode.equals(assignment.getRoleCode()) && assignment.getTargetMemberId() != null) {
                    members.add(assignment.getTargetMemberId());
                }
            }
        }
        return members.size();
    }

    private boolean missingRequestId(String requestId) {
        return requestId == null || requestId.trim().isEmpty();
    }

    private String roleName(String roleCode) {
        if (ClubRoleCode.CLUB_CO_OWNER.equals(roleCode)) return "副主理人";
        if (ClubRoleCode.CLUB_OPERATOR.equals(roleCode)) return "俱乐部运营";
        if (ClubRoleCode.EVENT_LEAD.equals(roleCode)) return "活动领队";
        if (ClubRoleCode.EVENT_CHECKIN.equals(roleCode)) return "活动核销员";
        return "未知角色";
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private ServiceException forbidden() {
        return new ServiceException("俱乐部身份无效或权限不足", HttpStatus.FORBIDDEN);
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
