package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ClubRoleAssignmentServiceImpl implements IClubRoleAssignmentService {
    @Autowired private IClubAccessService clubAccessService;
    @Autowired private ClubMapper clubMapper;
    @Autowired private ClubMemberMapper clubMemberMapper;
    @Autowired private CmsActivityMapper cmsActivityMapper;
    @Autowired private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    @Autowired private BusinessAuditEventMapper businessAuditEventMapper;

    @Override
    public List<ClubRoleAssignment> list(Long principalMemberId, Long clubId, Long activityId) {
        ClubAccessContext access = ownerAccess(principalMemberId, clubId);
        if (activityId != null) requireOwnedActivity(activityId, clubId);
        List<ClubRoleAssignment> rows = clubRoleAssignmentMapper.selectActiveByClubAndScope(
                clubId, activityId, new Date());
        if (rows == null) return Collections.emptyList();
        Date now = new Date();
        for (ClubRoleAssignment row : rows) validateVisibleAssignment(access, row, activityId, now);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubRoleAssignment assign(Long principalMemberId, Long clubId, Long targetMemberId,
                                     String roleCode, Long activityId, Date expiresAt, String requestId) {
        ClubAccessContext access = ownerAccess(principalMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        access = ownerAccess(principalMemberId, clubId);
        if (!isOwnedActiveClub(locked, access)) throw forbidden();
        if (!ClubRoleCode.isAssignable(roleCode)) throw badRequest("角色不合法");
        if (!positive(targetMemberId) || targetMemberId.equals(access.getOwnerMemberId())) {
            throw badRequest("目标成员不合法");
        }
        ClubMember member = clubMemberMapper.selectByClubAndMember(clubId, targetMemberId);
        if (!isActiveMember(member, clubId, targetMemberId)) throw badRequest("目标账号不是本俱乐部正常成员");

        String scopeType;
        Long scopeId;
        if (ClubRoleCode.isClubRole(roleCode)) {
            if (activityId != null) throw badRequest("俱乐部角色不能绑定活动");
            scopeType = ClubRoleAssignment.SCOPE_CLUB;
            scopeId = clubId;
        } else {
            if (!positive(activityId)) throw badRequest("活动角色缺少 activityId");
            requireOwnedActivity(activityId, clubId);
            scopeType = ClubRoleAssignment.SCOPE_EVENT;
            scopeId = activityId;
        }
        Date now = new Date();
        if (expiresAt != null && !expiresAt.after(now)) throw badRequest("角色有效期必须晚于当前时间");
        String normalizedRequestId = normalizeRequestId(requestId);
        BusinessAuditEvent receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", clubId, "CLUB_ROLE_ASSIGNED", normalizedRequestId);
        if (receipt != null) {
            return requireExactAssignReplay(receipt, access, targetMemberId, roleCode,
                    scopeType, scopeId, expiresAt, normalizedRequestId);
        }
        if (ClubRoleCode.EVENT_LEAD.equals(roleCode)) {
            revokeActiveEventLeads(access, clubId, activityId, principalMemberId,
                    "LEAD_REPLACED", normalizedRequestId);
        }
        clubRoleAssignmentMapper.expireActiveByScope(clubId, targetMemberId, scopeType, scopeId,
                now, String.valueOf(principalMemberId));
        if (clubRoleAssignmentMapper.selectActiveByUniqueScope(
                clubId, targetMemberId, scopeType, scopeId, now) != null) {
            throw conflict("该成员在此范围已有有效角色，请先撤销");
        }

        ClubRoleAssignment assignment = new ClubRoleAssignment();
        assignment.setClubId(clubId);
        assignment.setTargetMemberId(targetMemberId);
        assignment.setRoleCode(roleCode);
        assignment.setScopeType(scopeType);
        assignment.setScopeId(scopeId);
        assignment.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        assignment.setExpiresAt(expiresAt);
        assignment.setAssignedByMemberId(principalMemberId);
        assignment.setAssignedAt(now);
        assignment.setVersion(0);
        assignment.setCreateBy(String.valueOf(principalMemberId));
        assignment.setCreateTime(now);
        assignment.setUpdateBy(String.valueOf(principalMemberId));
        assignment.setUpdateTime(now);
        if (clubRoleAssignmentMapper.insertClubRoleAssignment(assignment) != 1) {
            throw conflict("角色分配失败，请刷新后重试");
        }
        audit(access, "CLUB_ROLE_ASSIGNED", assignment, null, assignmentJson(assignment),
                null, normalizedRequestId);
        return assignment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubRoleAssignment assignEventLeadForOccurrence(Long actorMemberId, Long clubId,
                                                            Long targetMemberId, Long activityId,
                                                            String requestId) {
        ClubAccessContext access = activityManageAccess(actorMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        access = activityManageAccess(actorMemberId, clubId);
        if (!isOwnedActiveClub(locked, access)) throw forbidden();
        if (!positive(targetMemberId) || targetMemberId.equals(access.getOwnerMemberId())) {
            throw badRequest("默认负责人无需或不能创建活动角色");
        }
        ClubMember member = clubMemberMapper.selectByClubAndMember(clubId, targetMemberId);
        if (!isActiveMember(member, clubId, targetMemberId)) {
            throw badRequest("默认负责人不是本俱乐部正常成员");
        }
        if (!positive(activityId)) throw badRequest("活动角色缺少 activityId");
        requireOwnedActivity(activityId, clubId);

        Date now = new Date();
        // 单场只有一个领队:运行态、场次默认负责人字段与 UI 都只认一个人,而
        // expireActiveByScope 只失效"同一目标成员"的旧角色 —— 换个成员分配会留下
        // 多条 ACTIVE 领队,运行态又只解析最早那条,于是"页面显示已委派、新领队仍
        // 无发车权"。所以改派前先把本场其它成员的有效领队一并收掉。
        // 只收 EVENT_LEAD + SCOPE_EVENT + 本 activity,多人 EVENT_CHECKIN 与
        // 俱乐部级角色不受影响。
        revokeActiveEventLeads(access, clubId, activityId, actorMemberId,
                "LEAD_REPLACED", normalizeRequestId(requestId));
        clubRoleAssignmentMapper.expireActiveByScope(
                clubId, targetMemberId, ClubRoleAssignment.SCOPE_EVENT, activityId,
                now, String.valueOf(actorMemberId));
        if (clubRoleAssignmentMapper.selectActiveByUniqueScope(
                clubId, targetMemberId, ClubRoleAssignment.SCOPE_EVENT, activityId, now) != null) {
            throw conflict("该成员在此活动已有有效角色");
        }
        ClubRoleAssignment assignment = new ClubRoleAssignment();
        assignment.setClubId(clubId);
        assignment.setTargetMemberId(targetMemberId);
        assignment.setRoleCode(ClubRoleCode.EVENT_LEAD);
        assignment.setScopeType(ClubRoleAssignment.SCOPE_EVENT);
        assignment.setScopeId(activityId);
        assignment.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        assignment.setAssignedByMemberId(actorMemberId);
        assignment.setAssignedAt(now);
        assignment.setVersion(0);
        assignment.setCreateBy(String.valueOf(actorMemberId));
        assignment.setCreateTime(now);
        assignment.setUpdateBy(String.valueOf(actorMemberId));
        assignment.setUpdateTime(now);
        if (clubRoleAssignmentMapper.insertClubRoleAssignment(assignment) != 1) {
            throw conflict("默认负责人角色绑定失败，请刷新后重试");
        }
        audit(access, "CLUB_EVENT_LEAD_ASSIGNED", assignment, null,
                assignmentJson(assignment), "SERIES_DEFAULT_LEAD", normalizeRequestId(requestId));
        return assignment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revokeEventLeadForCancelledOccurrence(Long actorMemberId, Long clubId,
                                                     Long activityId, String requestId) {
        if (!positive(actorMemberId) || !positive(clubId) || !positive(activityId)) {
            throw badRequest("取消场次角色参数不完整");
        }
        ClubAccessContext access = activityManageAccess(actorMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        access = activityManageAccess(actorMemberId, clubId);
        if (!isOwnedActiveClub(locked, access)) throw forbidden();
        requireOwnedActivity(activityId, clubId);
        return revokeActiveEventLeads(access, clubId, activityId, actorMemberId,
                "OCCURRENCE_CANCELLED", normalizeRequestId(requestId));
    }

    /**
     * 收掉本场所有有效领队。取消场次与改派领队共用:两处都要求"改完本场没有遗留的
     * ACTIVE 领队",差别只在审计原因码。
     */
    private int revokeActiveEventLeads(ClubAccessContext access, Long clubId, Long activityId,
                                       Long actorMemberId, String reason, String normalizedRequestId) {
        List<ClubRoleAssignment> rows = clubRoleAssignmentMapper
                .selectActiveEventLeadsForUpdate(clubId, activityId);
        if (rows == null || rows.isEmpty()) return 0;
        int revoked = 0;
        for (ClubRoleAssignment assignment : rows) {
            if (assignment == null || !clubId.equals(assignment.getClubId())
                    || !ClubRoleCode.EVENT_LEAD.equals(assignment.getRoleCode())
                    || !ClubRoleAssignment.SCOPE_EVENT.equals(assignment.getScopeType())
                    || !activityId.equals(assignment.getScopeId())
                    || !positive(assignment.getId())
                    || !ClubRoleAssignment.STATUS_ACTIVE.equals(assignment.getStatus())
                    || assignment.getVersion() == null || assignment.getVersion() < 0) {
                throw conflict("活动负责人角色状态不完整");
            }
            if (clubRoleAssignmentMapper.revokeCas(
                    assignment.getId(), clubId, assignment.getVersion(), actorMemberId,
                    reason, String.valueOf(actorMemberId)) != 1) {
                throw conflict("活动负责人角色状态已变化，请刷新");
            }
            auditCancelledEventLead(access, assignment, activityId, reason,
                    scopedRequestId(normalizedRequestId, assignment.getId()));
            revoked++;
        }
        return revoked;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubRoleAssignment setPlatformOperatorRole(Long platformUserId, Long clubId,
                                                       Long targetMemberId, boolean enabled,
                                                       String requestId) {
        if (!positive(platformUserId) || !positive(clubId) || !positive(targetMemberId)) {
            throw badRequest("平台角色参数不完整");
        }
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        if (locked == null || !clubId.equals(locked.getId()) || !positive(locked.getMemberId())
                || !Long.valueOf(1L).equals(locked.getStatus())
                || !Integer.valueOf(0).equals(locked.getDelFlag())
                || targetMemberId.equals(locked.getMemberId())) {
            throw forbidden();
        }
        ClubMember member = clubMemberMapper.selectByClubAndMember(clubId, targetMemberId);
        if (!isActiveMember(member, clubId, targetMemberId)) {
            throw badRequest("目标账号不是本俱乐部正常成员");
        }
        Date now = new Date();
        clubRoleAssignmentMapper.expireActiveByScope(
                clubId, targetMemberId, ClubRoleAssignment.SCOPE_CLUB, clubId,
                now, "PLATFORM:" + platformUserId);
        ClubRoleAssignment existing = clubRoleAssignmentMapper.selectActiveByUniqueScope(
                clubId, targetMemberId, ClubRoleAssignment.SCOPE_CLUB, clubId, now);
        String normalizedRequestId = normalizeRequestId(requestId);
        if (enabled) {
            if (existing != null) {
                if (!ClubRoleCode.CLUB_OPERATOR.equals(existing.getRoleCode())) {
                    throw conflict("该成员已有其他俱乐部角色，请从角色管理处理");
                }
                return existing;
            }
            ClubRoleAssignment assignment = new ClubRoleAssignment();
            assignment.setClubId(clubId);
            assignment.setTargetMemberId(targetMemberId);
            assignment.setRoleCode(ClubRoleCode.CLUB_OPERATOR);
            assignment.setScopeType(ClubRoleAssignment.SCOPE_CLUB);
            assignment.setScopeId(clubId);
            assignment.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
            assignment.setAssignedByMemberId(platformUserId);
            assignment.setAssignedAt(now);
            assignment.setVersion(0);
            assignment.setCreateBy("PLATFORM:" + platformUserId);
            assignment.setCreateTime(now);
            assignment.setUpdateBy("PLATFORM:" + platformUserId);
            assignment.setUpdateTime(now);
            if (clubRoleAssignmentMapper.insertClubRoleAssignment(assignment) != 1) {
                throw conflict("平台管理员角色绑定失败，请刷新后重试");
            }
            auditPlatform(locked, platformUserId, "CLUB_ROLE_ASSIGNED", assignment,
                    null, assignmentJson(assignment), "PLATFORM_LEGACY_ADMIN_ENABLED",
                    normalizedRequestId);
            return assignment;
        }
        if (existing == null) {
            if (clearLegacyAdminRole(clubId, targetMemberId)) {
                auditLegacyAdminRevoked(locked.getId(), locked.getMemberId(), platformUserId,
                        "PLATFORM_ADMIN", targetMemberId, "PLATFORM_LEGACY_ADMIN_DISABLED",
                        normalizedRequestId);
            }
            return null;
        }
        if (!ClubRoleCode.CLUB_OPERATOR.equals(existing.getRoleCode()) || existing.getVersion() == null) {
            throw conflict("该成员不是可由此入口取消的运营管理员");
        }
        if (clubRoleAssignmentMapper.revokeCas(existing.getId(), clubId, existing.getVersion(),
                platformUserId, "PLATFORM_LEGACY_ADMIN_DISABLED", "PLATFORM:" + platformUserId) != 1) {
            throw conflict("角色状态已变化，请刷新");
        }
        clearLegacyAdminRole(clubId, targetMemberId);
        auditPlatform(locked, platformUserId, "CLUB_ROLE_REVOKED", existing,
                assignmentJson(existing), "{\"status\":\"REVOKED\"}",
                "PLATFORM_LEGACY_ADMIN_DISABLED", normalizedRequestId);
        existing.setStatus(ClubRoleAssignment.STATUS_REVOKED);
        return existing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeLegacyOperator(Long principalMemberId, Long clubId, Long targetMemberId,
                                     String reason, String requestId) {
        if (!positive(targetMemberId)) throw badRequest("目标成员不合法");
        String normalizedReason = requireReason(reason);
        String normalizedRequestId = normalizeRequestId(requestId);
        ClubAccessContext access = ownerAccess(principalMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        access = ownerAccess(principalMemberId, clubId);
        if (!isOwnedActiveClub(locked, access) || targetMemberId.equals(access.getOwnerMemberId())) {
            throw forbidden();
        }
        ClubMember member = clubMemberMapper.selectByClubAndMemberForUpdate(clubId, targetMemberId);
        if (!isActiveMember(member, clubId, targetMemberId)) {
            throw badRequest("目标账号不是本俱乐部正常成员");
        }
        Date now = new Date();
        ClubRoleAssignment existing = clubRoleAssignmentMapper.selectActiveByUniqueScope(
                clubId, targetMemberId, ClubRoleAssignment.SCOPE_CLUB, clubId, now);
        boolean operatorRevoked = false;
        if (existing != null && ClubRoleCode.CLUB_OPERATOR.equals(existing.getRoleCode())) {
            if (existing.getId() == null || existing.getVersion() == null
                    || !ClubRoleAssignment.STATUS_ACTIVE.equals(existing.getStatus())) {
                throw conflict("角色状态已变化，请刷新");
            }
            if (clubRoleAssignmentMapper.revokeCas(existing.getId(), clubId, existing.getVersion(),
                    principalMemberId, normalizedReason, String.valueOf(principalMemberId)) != 1) {
                throw conflict("角色状态已变化，请刷新");
            }
            operatorRevoked = true;
            audit(access, "CLUB_ROLE_REVOKED", existing, assignmentJson(existing),
                    "{\"status\":\"REVOKED\"}", normalizedReason, normalizedRequestId);
        }
        boolean legacyCleared = clearStoredLegacyAdminRole(member);
        if (!operatorRevoked && legacyCleared) {
            auditLegacyAdminRevoked(clubId, access.getOwnerMemberId(), principalMemberId,
                    access.getPrimaryRoleCode(), targetMemberId, normalizedReason,
                    normalizedRequestId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long principalMemberId, Long clubId, Long activityId, Long assignmentId,
                       Integer version, String reason, String requestId) {
        if (!positive(assignmentId) || version == null || version < 0) throw badRequest("角色参数不完整");
        ClubAccessContext access = ownerAccess(principalMemberId, clubId);
        Club locked = clubMapper.selectClubByIdForUpdate(clubId);
        access = ownerAccess(principalMemberId, clubId);
        if (!isOwnedActiveClub(locked, access)) throw forbidden();
        ClubRoleAssignment assignment = clubRoleAssignmentMapper.selectByIdForUpdate(assignmentId);
        if (assignment == null || !clubId.equals(assignment.getClubId())
                || !ClubRoleCode.isAssignable(assignment.getRoleCode())) {
            throw conflict("角色状态已变化，请刷新");
        }
        validateRequestedScope(assignment, clubId, activityId);
        String normalizedReason = requireReason(reason);
        String normalizedRequestId = normalizeRequestId(requestId);
        BusinessAuditEvent receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", clubId, "CLUB_ROLE_REVOKED", normalizedRequestId);
        if (receipt != null) {
            requireExactRevokeReplay(receipt, access, assignment, version,
                    normalizedReason, normalizedRequestId);
            clearLegacyAdminRoleForOperator(assignment);
            return;
        }
        if (!ClubRoleAssignment.STATUS_ACTIVE.equals(assignment.getStatus())
                || !version.equals(assignment.getVersion())) {
            throw conflict("角色状态已变化，请刷新");
        }
        if (clubRoleAssignmentMapper.revokeCas(assignmentId, clubId, version, principalMemberId,
                normalizedReason, String.valueOf(principalMemberId)) != 1) {
            throw conflict("角色状态已变化，请刷新");
        }
        clearLegacyAdminRoleForOperator(assignment);
        audit(access, "CLUB_ROLE_REVOKED", assignment, assignmentJson(assignment),
                "{\"status\":\"REVOKED\"}", normalizedReason, normalizedRequestId);
    }

    private void clearLegacyAdminRoleForOperator(ClubRoleAssignment assignment) {
        if (assignment == null || !ClubRoleCode.CLUB_OPERATOR.equals(assignment.getRoleCode())
                || !ClubRoleAssignment.SCOPE_CLUB.equals(assignment.getScopeType())
                || !Objects.equals(assignment.getClubId(), assignment.getScopeId())) {
            return;
        }
        clearLegacyAdminRole(assignment.getClubId(), assignment.getTargetMemberId());
    }

    private boolean clearLegacyAdminRole(Long clubId, Long targetMemberId) {
        if (!positive(clubId) || !positive(targetMemberId)) {
            throw conflict("旧管理员成员状态不完整");
        }
        ClubMember member = clubMemberMapper.selectByClubAndMemberForUpdate(clubId, targetMemberId);
        return clearStoredLegacyAdminRole(member);
    }

    private boolean clearStoredLegacyAdminRole(ClubMember member) {
        if (member == null || !Integer.valueOf(1).equals(member.getRole())) return false;
        member.setRole(0);
        if (clubMemberMapper.updateClubMember(member) != 1) {
            throw conflict("旧管理员权限撤销失败，请刷新重试");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revokeAllForAccessEnd(Long clubId, Long targetMemberId, Long actorMemberId,
                                     String actorRoleCode, String reason) {
        if (!positive(clubId) || !positive(targetMemberId) || !positive(actorMemberId)) {
            throw badRequest("角色访问终止参数不完整");
        }
        String normalizedReason = requireReason(reason);
        Club club = clubMapper.selectClubById(clubId);
        if (club == null || !clubId.equals(club.getId()) || !positive(club.getMemberId())) {
            throw conflict("俱乐部状态已变化，请刷新");
        }
        Date now = new Date();
        int rows = clubRoleAssignmentMapper.revokeAllActiveByMember(
                clubId, targetMemberId, actorMemberId, normalizedReason, now,
                "ACCESS_END:" + actorMemberId);
        if (rows <= 0) return 0;

        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(clubId);
        event.setOwnerMemberId(club.getMemberId());
        event.setActorMemberId(actorMemberId);
        event.setActorRoleCode(actorRoleCode == null || actorRoleCode.trim().isEmpty()
                ? "SYSTEM_ACCESS_END" : actorRoleCode.trim());
        event.setAction("CLUB_ROLE_ASSIGNMENTS_REVOKED");
        event.setTargetType("CLUB_MEMBER");
        event.setTargetId(targetMemberId);
        event.setAfterJson("{\"status\":\"REVOKED\",\"count\":" + rows + "}");
        event.setReason(normalizedReason);
        event.setRequestId("club-role-access-end-" + UUID.randomUUID());
        event.setCreateTime(now);
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
        return rows;
    }

    private ClubAccessContext ownerAccess(Long principalMemberId, Long clubId) {
        ClubAccessContext access = clubAccessService.require(
                principalMemberId, clubId, null, ClubPermission.ROLE_MANAGE);
        if (access == null || !clubId.equals(access.getClubId())
                || !principalMemberId.equals(access.getPrincipalMemberId())
                || !principalMemberId.equals(access.getOwnerMemberId())
                || !access.getRoleCodes().contains(ClubRoleCode.CLUB_OWNER)) throw forbidden();
        return access;
    }

    private ClubAccessContext activityManageAccess(Long principalMemberId, Long clubId) {
        ClubAccessContext access = clubAccessService.require(
                principalMemberId, clubId, null, ClubPermission.ACTIVITY_MANAGE);
        if (access == null || !principalMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId()) || !positive(access.getOwnerMemberId())) {
            throw forbidden();
        }
        return access;
    }

    private void validateVisibleAssignment(ClubAccessContext access, ClubRoleAssignment assignment,
                                           Long activityId, Date now) {
        if (assignment == null || !access.getClubId().equals(assignment.getClubId())
                || !ClubRoleAssignment.STATUS_ACTIVE.equals(assignment.getStatus())
                || !ClubRoleCode.isAssignable(assignment.getRoleCode())
                || assignment.getExpiresAt() != null && !assignment.getExpiresAt().after(now)) throw forbidden();
        if (ClubRoleCode.isClubRole(assignment.getRoleCode())) {
            if (activityId != null || !ClubRoleAssignment.SCOPE_CLUB.equals(assignment.getScopeType())
                    || !access.getClubId().equals(assignment.getScopeId())) throw forbidden();
            return;
        }
        if (!ClubRoleAssignment.SCOPE_EVENT.equals(assignment.getScopeType())
                || !positive(assignment.getScopeId())
                || activityId != null && !activityId.equals(assignment.getScopeId())) throw forbidden();
        requireOwnedActivity(assignment.getScopeId(), access.getClubId());
    }

    private void validateRequestedScope(ClubRoleAssignment assignment, Long clubId, Long activityId) {
        if (ClubRoleCode.isClubRole(assignment.getRoleCode())) {
            if (!ClubRoleAssignment.SCOPE_CLUB.equals(assignment.getScopeType())
                    || !clubId.equals(assignment.getScopeId()) || activityId != null) throw forbidden();
            return;
        }
        if (!ClubRoleAssignment.SCOPE_EVENT.equals(assignment.getScopeType())
                || !positive(activityId) || !activityId.equals(assignment.getScopeId())) throw forbidden();
        requireOwnedActivity(activityId, clubId);
    }

    private void requireOwnedActivity(Long activityId, Long clubId) {
        CmsActivity activity = cmsActivityMapper.selectCmsActivityById(activityId);
        if (activity == null || !activityId.equals(activity.getId()) || !clubId.equals(activity.getClubId())
                || !Integer.valueOf(0).equals(activity.getDelFlag())) throw forbidden();
    }

    private boolean isOwnedActiveClub(Club club, ClubAccessContext access) {
        return club != null && access.getClubId().equals(club.getId())
                && access.getOwnerMemberId().equals(club.getMemberId())
                && Long.valueOf(1L).equals(club.getStatus()) && Integer.valueOf(0).equals(club.getDelFlag());
    }

    private boolean isActiveMember(ClubMember member, Long clubId, Long memberId) {
        return member != null && member.getId() != null && clubId.equals(member.getClubId())
                && memberId.equals(member.getMemberId()) && Integer.valueOf(1).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getDelFlag());
    }

    private void audit(ClubAccessContext access, String action, ClubRoleAssignment assignment,
                       String beforeJson, String afterJson, String reason, String requestId) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(access.getClubId());
        event.setOwnerMemberId(access.getOwnerMemberId());
        event.setActorMemberId(access.getPrincipalMemberId());
        event.setActorRoleCode(access.getPrimaryRoleCode());
        event.setAction(action);
        event.setTargetType("CLUB_ROLE_ASSIGNMENT");
        event.setTargetId(assignment.getTargetMemberId());
        event.setRelationId(assignment.getId());
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private void auditPlatform(Club club, Long platformUserId, String action,
                               ClubRoleAssignment assignment, String beforeJson,
                               String afterJson, String reason, String requestId) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(club.getId());
        event.setOwnerMemberId(club.getMemberId());
        event.setActorMemberId(platformUserId);
        event.setActorRoleCode("PLATFORM_ADMIN");
        event.setAction(action);
        event.setTargetType("CLUB_ROLE_ASSIGNMENT");
        event.setTargetId(assignment.getTargetMemberId());
        event.setRelationId(assignment.getId());
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private void auditLegacyAdminRevoked(Long clubId, Long ownerMemberId, Long actorMemberId,
                                         String actorRoleCode, Long targetMemberId,
                                         String reason, String requestId) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(clubId);
        event.setOwnerMemberId(ownerMemberId);
        event.setActorMemberId(actorMemberId);
        event.setActorRoleCode(actorRoleCode);
        event.setAction("CLUB_LEGACY_ADMIN_REVOKED");
        event.setTargetType("CLUB_MEMBER");
        event.setTargetId(targetMemberId);
        event.setBeforeJson("{\"role\":1}");
        event.setAfterJson("{\"role\":0}");
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private void auditCancelledEventLead(ClubAccessContext access,
                                         ClubRoleAssignment assignment,
                                         Long activityId, String reason, String requestId) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(access.getClubId());
        event.setOwnerMemberId(access.getOwnerMemberId());
        event.setActorMemberId(access.getPrincipalMemberId());
        event.setActorRoleCode(access.getPrimaryRoleCode());
        event.setAction("CLUB_EVENT_LEAD_REVOKED");
        event.setTargetType("CLUB_ROLE_ASSIGNMENT");
        event.setTargetId(assignment.getTargetMemberId());
        event.setRelationId(activityId);
        event.setBeforeJson(assignmentJson(assignment));
        event.setAfterJson("{\"status\":\"REVOKED\"}");
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
            throw conflict("业务审计写入失败");
        }
    }

    private ClubRoleAssignment requireExactAssignReplay(
            BusinessAuditEvent receipt, ClubAccessContext access, Long targetMemberId,
            String roleCode, String scopeType, Long scopeId, Date expiresAt, String requestId) {
        if (!exactRoleReceipt(receipt, access, "CLUB_ROLE_ASSIGNED", targetMemberId, requestId)
                || !positive(receipt.getRelationId()) || receipt.getReason() != null) {
            throw conflict("角色分配 requestId 已被其他请求使用");
        }
        ClubRoleAssignment committed = clubRoleAssignmentMapper.selectByIdForUpdate(
                receipt.getRelationId());
        if (committed == null || !access.getClubId().equals(committed.getClubId())
                || !targetMemberId.equals(committed.getTargetMemberId())
                || !roleCode.equals(committed.getRoleCode())
                || !scopeType.equals(committed.getScopeType())
                || !scopeId.equals(committed.getScopeId())
                || !ClubRoleAssignment.STATUS_ACTIVE.equals(committed.getStatus())
                || !access.getPrincipalMemberId().equals(committed.getAssignedByMemberId())
                || !Integer.valueOf(0).equals(committed.getVersion())
                || !Objects.equals(expiresAt, committed.getExpiresAt())
                || !sameAuditJson(assignmentJson(committed), receipt.getAfterJson())) {
            throw conflict("角色分配 requestId 与原请求不一致");
        }
        return committed;
    }

    private void requireExactRevokeReplay(
            BusinessAuditEvent receipt, ClubAccessContext access, ClubRoleAssignment assignment,
            Integer expectedVersion, String reason, String requestId) {
        if (!exactRoleReceipt(receipt, access, "CLUB_ROLE_REVOKED",
                assignment.getTargetMemberId(), requestId)
                || !assignment.getId().equals(receipt.getRelationId())
                || !Objects.equals(reason, receipt.getReason())
                || !sameAuditJson("{\"status\":\"REVOKED\"}", receipt.getAfterJson())
                || !sameAuditJson(assignmentJsonForStatus(
                        assignment, ClubRoleAssignment.STATUS_ACTIVE), receipt.getBeforeJson())
                || !ClubRoleAssignment.STATUS_REVOKED.equals(assignment.getStatus())
                || assignment.getVersion() == null
                || assignment.getVersion().longValue() != expectedVersion.longValue() + 1L
                || !access.getPrincipalMemberId().equals(assignment.getRevokedByMemberId())
                || !Objects.equals(reason, assignment.getRevokeReason())) {
            throw conflict("角色撤销 requestId 与原请求不一致");
        }
    }

    private boolean exactRoleReceipt(BusinessAuditEvent receipt, ClubAccessContext access,
                                     String action, Long targetMemberId, String requestId) {
        return receipt != null
                && "CLUB".equals(receipt.getDomainType())
                && access.getClubId().equals(receipt.getDomainId())
                && access.getOwnerMemberId().equals(receipt.getOwnerMemberId())
                && access.getPrincipalMemberId().equals(receipt.getActorMemberId())
                && Objects.equals(access.getPrimaryRoleCode(), receipt.getActorRoleCode())
                && action.equals(receipt.getAction())
                && "CLUB_ROLE_ASSIGNMENT".equals(receipt.getTargetType())
                && targetMemberId.equals(receipt.getTargetId())
                && requestId.equals(receipt.getRequestId());
    }

    private String assignmentJson(ClubRoleAssignment assignment) {
        return assignmentJsonForStatus(assignment, assignment.getStatus());
    }

    private boolean sameAuditJson(String expected, String actual) {
        // MySQL JSON 会重排字段和空格；按完整对象比较，损坏或多出字段仍拒绝。
        Map<String, Object> expectedState = JsonUtils.parseToMap(expected);
        return expectedState != null && expectedState.equals(JsonUtils.parseToMap(actual));
    }

    private String assignmentJsonForStatus(ClubRoleAssignment assignment, String status) {
        return "{\"roleCode\":\"" + assignment.getRoleCode() + "\",\"scopeType\":\""
                + assignment.getScopeType() + "\",\"scopeId\":" + assignment.getScopeId()
                + ",\"status\":\"" + status + "\"}";
    }

    private String normalizeRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        if (normalized.isEmpty()) normalized = UUID.randomUUID().toString();
        if (normalized.length() > 64) throw badRequest("requestId 不能超过64字");
        return normalized;
    }

    private String scopedRequestId(String baseRequestId, Long assignmentId) {
        String suffix = "-role-" + assignmentId;
        int prefixLength = 64 - suffix.length();
        if (prefixLength < 1) throw badRequest("角色审计 requestId 无法生成");
        String prefix = baseRequestId.length() > prefixLength
                ? baseRequestId.substring(0, prefixLength) : baseRequestId;
        return prefix + suffix;
    }

    private String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) throw badRequest("原因需为1至500字");
        return normalized;
    }

    private boolean positive(Long value) { return value != null && value > 0; }
    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden() { return new ServiceException("俱乐部身份无效或权限不足", HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }
}
